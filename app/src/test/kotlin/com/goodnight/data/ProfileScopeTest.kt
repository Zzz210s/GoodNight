package com.goodnight.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.goodnight.data.db.FocusSessionEntity
import com.goodnight.data.db.GoodNightDatabase
import com.goodnight.timer.TimeProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v2.2 Task 1:时钟的「作用域内唯一」与「可用时钟集合」。
 *
 * 作用域内唯一只能由仓库层保证:SQLite 的唯一索引把 NULL 视为互不相同,
 * 表达不了「通用时钟之间也唯一」,所以本测试是这条不变量的唯一防线
 * (v2.2 起 `profile.name` 的索引已去掉 unique)。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ProfileScopeTest {
    private val dbName = "goodnight-profile-scope.db"
    private var db: GoodNightDatabase? = null
    private lateinit var profiles: ProfileRepository
    private lateinit var tasks: TaskRepository

    /** 可控时钟:createdAt 决定顺序,必须逐个钉死才能断言「顺序稳定」 */
    private var nowMs = 1L
    private val time = object : TimeProvider {
        override fun now() = nowMs
        override fun elapsedRealtime() = 0L
    }

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.deleteDatabase(dbName) // 残留文件会让 build() 复用旧库,先清
        db = GoodNightDatabase.build(ctx, dbName)
        profiles = ProfileRepository(db!!.profileDao(), time)
        tasks = TaskRepository(db!!)
    }

    @After fun tearDown() {
        db?.close()
        db = null
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(dbName)
    }

    private suspend fun clock(name: String, taskId: Long? = null, createdAt: Long): Long {
        nowMs = createdAt
        return profiles.create(name, 25, 5, taskId = taskId)!!
    }

    /** 给时钟造一条历史再删:走归档分支(有引用 → 行保留,从列表隐藏) */
    private suspend fun archive(id: Long) {
        db!!.focusSessionDao().insertAll(
            listOf(FocusSessionEntity(profileId = id, startAt = 1, endAt = 2))
        )
        assertEquals(ProfileRemoval.Archived, profiles.removeOrArchive(id))
    }

    @Test fun sameNameAllowedInDifferentScopes() = runTest {
        val a = tasks.create("A", 1L)!!
        val b = tasks.create("B", 2L)!!
        assertNotNull(profiles.create("专注", 25, 5, taskId = a))
        assertNotNull(profiles.create("专注", 25, 5, taskId = b))
        assertNotNull(profiles.create("专注", 25, 5, taskId = null))
        assertEquals(3, profiles.count())
    }

    @Test fun sameNameRejectedWithinScope() = runTest {
        val a = tasks.create("A", 1L)!!
        assertNotNull(profiles.create("专注", 25, 5, taskId = a))
        assertNull("同任务内重名被拒", profiles.create("专注", 30, 10, taskId = a))
        assertNotNull(profiles.create("专注", 25, 5, taskId = null)) // 通用先占名
        assertNull("通用之间也重名", profiles.create("专注", 30, 10, taskId = null))
        assertEquals("A 专属 + 通用 = 2 行", 2, profiles.count())
    }

    @Test fun availableForMergesOwnAndGenericInStableOrder() = runTest {
        val a = tasks.create("A", 1L)!!
        val b = tasks.create("B", 2L)!!
        clock("通用1", null, createdAt = 10)
        clock("通用2", null, createdAt = 20)
        val genOld = clock("老时钟", null, createdAt = 30)
        clock("B专属", b, createdAt = 40)
        clock("A专属1", a, createdAt = 50)
        clock("A专属2", a, createdAt = 60)
        clock("A专属2b", a, createdAt = 60) // 同一 createdAt:按 id 兜底
        archive(genOld)

        assertEquals(
            "专属在前(createdAt,再 id)、通用在后",
            listOf("A专属1", "A专属2", "A专属2b", "通用1", "通用2"),
            profiles.availableFor(a).first().map { it.name },
        )
        assertEquals(listOf("B专属", "通用1", "通用2"), profiles.availableFor(b).first().map { it.name })
        assertEquals(
            "未绑定任务只有通用时钟",
            listOf("通用1", "通用2"),
            profiles.availableFor(null).first().map { it.name },
        )
    }

    @Test fun observeAllActiveSplitsGenericThenTaskScoped() = runTest {
        val a = tasks.create("A", 1L)!!
        val b = tasks.create("B", 2L)!!
        clock("通用1", null, createdAt = 10)
        clock("B专属", b, createdAt = 20)
        clock("A专属", a, createdAt = 30)
        val genOld = clock("老时钟", null, createdAt = 40)
        archive(genOld)

        assertEquals(
            "管理页:通用段在前,专属段按 taskId/createdAt/id;归档不出现",
            listOf("通用1", "A专属", "B专属"),
            profiles.observeAllActive().first().map { it.name },
        )
        assertEquals(
            "全量流仍含归档行(历史/报表要按 id 解析名字)",
            4,
            profiles.profiles.first().size,
        )
    }
}

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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v2.2 Task 1 修复轮的边界用例(两条缺陷由上一轮修复本身引入,见 task-1-report):
 *
 * W1 删任务把专属时钟转通用时,归档行**不该**被去重改名 —— 归档行不占名字,改名纯属副作用,
 * 会让历史/日报按 id 解析出的旧名字静默漂移。
 * W2 [ProfileRepository.removeOrArchive] 对不存在的 id 必须报 `Deleted`,不能报 `Archived`,
 * 否则 Task 5 的「已归档,历史保留」提示会在未知 id / 并发双删时对用户撒谎。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ProfileRemovalEdgeTest {
    private val dbName = "goodnight-profile-removal-edge.db"
    private var db: GoodNightDatabase? = null
    private lateinit var profiles: ProfileRepository
    private lateinit var tasks: TaskRepository

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

    private suspend fun clock(name: String, taskId: Long? = null): Long {
        nowMs += 1
        return profiles.create(name, 25, 5, taskId = taskId)!!
    }

    /** 造一条历史再删:走归档分支(行保留、taskId 不动) */
    private suspend fun archive(id: Long) {
        db!!.focusSessionDao().insertAll(
            listOf(FocusSessionEntity(profileId = id, startAt = 1, endAt = 2))
        )
        assertEquals(ProfileRemoval.Archived, profiles.removeOrArchive(id))
    }

    /**
     * W1:归档的专属「专注」与活跃通用「专注」并存时删任务 —— 归档行保持原名,活跃通用行不动。
     * 实现逐行无条件去重(`freeToGeneric(p.id, uniqueGenericName(p))`)时,归档行会被改成
     * 「专注 (2)」,本用例红在第一条名字断言上。
     */
    @Test fun deleteTaskKeepsArchivedClockNameWhileDedupingActiveOnes() = runTest {
        val a = tasks.create("A", 1L)!!
        val scoped = clock("专注", taskId = a)
        archive(scoped)
        val generic = clock("专注")

        tasks.deleteTask(a)

        val archived = profiles.byId(scoped)!!
        assertEquals("归档行保持原名", "专注", archived.name)
        assertTrue("归档标记不因转通用被清", archived.archived)
        assertNull("归档行也解绑,不留悬挂引用", archived.taskId)
        assertEquals("活跃通用行名字不动", "专注", profiles.byId(generic)!!.name)
        assertEquals(
            "归档行不进可用集合,通用层仍是唯一一条「专注」",
            listOf(generic to "专注"),
            profiles.availableFor(null).first().map { it.id to it.name },
        )
    }

    /**
     * W2:不存在的 id(含已删过一次的 id)必须报 `Deleted`,且不凭空造出或改动任何行。
     * 实现退回「rowsAffected = 0 就归档」时,红在第一次断言上。
     */
    @Test fun removeOrArchiveUnknownIdReportsDeletedAndWritesNothing() = runTest {
        assertEquals(
            "未知 id → Deleted(不是 Archived)",
            ProfileRemoval.Deleted,
            profiles.removeOrArchive(999L),
        )
        assertEquals("不产生任何行", 0, profiles.count())

        val p = clock("专注")
        assertEquals(
            "库里有别的行时未知 id 仍报 Deleted",
            ProfileRemoval.Deleted,
            profiles.removeOrArchive(999L),
        )
        assertNotNull("无关的行不受影响", profiles.byId(p))

        assertEquals("首次删除:真删", ProfileRemoval.Deleted, profiles.removeOrArchive(p))
        assertEquals(
            "同一 id 再删(并发双删的串行形态)仍报 Deleted,不谎称已归档",
            ProfileRemoval.Deleted,
            profiles.removeOrArchive(p),
        )
        assertNull(profiles.byId(p))
        assertEquals("删除后库为空", 0, profiles.count())
    }
}

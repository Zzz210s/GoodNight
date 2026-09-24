package com.goodnight.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.goodnight.data.db.DailyTotalEntity
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
 * v2.2 Task 1:删时钟的两种结局(有历史 → 归档 / 无历史 → 真删)、删任务转通用、改归属。
 *
 * 修掉的现存隐患:旧 `delete` 是裸删,会留下悬空 `profileId`(每日详情/报表解析不到名字),
 * 且顺带删掉的段落让时间账凭空减少。归档保留行,历史仍可解析。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ProfileRemovalTest {
    private val dbName = "goodnight-profile-removal.db"
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
        ctx.deleteDatabase(dbName)
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

    private suspend fun session(profileId: Long, taskId: Long? = null, startAt: Long = 100) {
        db!!.focusSessionDao().insertAll(
            listOf(
                FocusSessionEntity(
                    profileId = profileId, startAt = startAt,
                    endAt = startAt + 600_000, taskId = taskId,
                )
            )
        )
    }

    @Test fun archivesClockWithSessionHistory() = runTest {
        val p = clock("专注")
        session(p)
        assertEquals(ProfileRemoval.Archived, profiles.removeOrArchive(p))

        val row = profiles.byId(p)
        assertNotNull("归档不是删行", row)
        assertTrue(row!!.archived)
        assertNull("归档不碰归属", row.taskId)
        assertEquals("历史段一行不少", 1, db!!.focusSessionDao().count())
        assertTrue("归档后从可用集合隐藏", profiles.availableFor(null).first().isEmpty())
        assertTrue("归档后从管理页隐藏", profiles.observeAllActive().first().isEmpty())
    }

    @Test fun archivesClockWithDailyTotalOnly() = runTest {
        val p = clock("专注")
        db!!.dailyTotalDao().upsert(
            DailyTotalEntity(date = "2026-09-24", profileId = p, workMillis = 600_000, updatedAt = 1)
        )
        assertEquals("每日合计也算历史", ProfileRemoval.Archived, profiles.removeOrArchive(p))
        assertTrue(profiles.byId(p)!!.archived)
        assertEquals(600_000L, db!!.dailyTotalDao().getWorkMillis("2026-09-24", p))
    }

    @Test fun deletesClockWithoutHistory() = runTest {
        val p = clock("专注")
        assertEquals(ProfileRemoval.Deleted, profiles.removeOrArchive(p))
        assertNull(profiles.byId(p))
        assertEquals(0, profiles.count())
    }

    @Test fun deleteTaskTurnsScopedClockGenericAndKeepsRows() = runTest {
        val a = tasks.create("A", 1L)!!
        val b = tasks.create("B", 2L)!!
        val pa = clock("A专属", taskId = a)
        val pb = clock("B专属", taskId = b)
        session(pa, taskId = a, startAt = 100)
        session(pb, taskId = b, startAt = 200)

        tasks.deleteTask(a)

        assertNull("专属时钟转通用", profiles.byId(pa)!!.taskId)
        assertEquals("其它任务的时钟不受影响", b, profiles.byId(pb)!!.taskId)
        assertEquals("时钟行不少", 2, profiles.count())
        val segs = db!!.focusSessionDao().getAll()
        assertEquals("会话行不少", 2, segs.size)
        assertNull("该任务的会话解绑", segs.first { it.profileId == pa }.taskId)
        assertEquals("其它会话不动", b, segs.first { it.profileId == pb }.taskId)
        assertEquals(
            "转通用后进入通用集合",
            listOf("A专属"),
            profiles.availableFor(null).first().map { it.name },
        )
    }

    @Test fun moveToChangesOwnershipWithoutTouchingSessions() = runTest {
        val a = tasks.create("A", 1L)!!
        val p = clock("专注", taskId = a)
        session(p, taskId = a)
        val before = db!!.focusSessionDao().getAll()

        profiles.moveTo(p, null)
        assertNull("专属 -> 通用", profiles.byId(p)!!.taskId)
        profiles.moveTo(p, a)
        assertEquals("通用 -> 专属", a, profiles.byId(p)!!.taskId)
        assertEquals("改归属不碰历史", before, db!!.focusSessionDao().getAll())
    }

    /** 改归属不能把「作用域内唯一」破掉:目标作用域已有同名活跃时钟时保持原归属 */
    @Test fun moveToRefusesNameClashInTargetScope() = runTest {
        val a = tasks.create("A", 1L)!!
        clock("专注", taskId = a)
        val generic = clock("专注")
        profiles.moveTo(generic, a)
        assertNull("目标作用域重名 → 保持通用", profiles.byId(generic)!!.taskId)
        assertEquals(1, db!!.profileDao().getAll().count { it.taskId == a })
    }
}

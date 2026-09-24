package com.goodnight.ui.settings

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.goodnight.data.ProfileRemoval
import com.goodnight.data.db.FocusSessionEntity
import com.goodnight.data.db.ProfileEntity
import com.goodnight.data.db.ProfileMode
import com.goodnight.di.AppGraph
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v2.2 Task 5:删除预告(归档 vs 真删)与三条前序指针 —— 归档不调清账路径、归档行不满足
 * 「至少保留 1 个」、归档时钟从活跃流消失。列表分段的用例见 ProfileManageTest。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh", application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileDeletePlanTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var g: AppGraph

    /** DataStore 单例按文件名缓存,逐用例一份(见 #432) */
    @get:Rule val testName = TestName()

    @Before fun setUp() {
        g = AppGraph(ctx, useInMemoryDb = true, storeFileName = "profile_delete_${testName.methodName}")
    }

    @After fun tearDown() {
        runBlocking { g.appScope.coroutineContext.job.cancelAndJoin(); runCatching { g.db.close() } }
    }

    private fun vm() = SettingsViewModel(g)

    private suspend fun clock(name: String, taskId: Long? = null) =
        g.profileRepo.byId(g.profileRepo.create(name, 25, 5, ProfileMode.COUNTDOWN, taskId)!!)!!

    private suspend fun session(profileId: Long, millis: Long = 25 * 60_000L, taskId: Long? = null) =
        g.db.focusSessionDao().insertAll(
            listOf(FocusSessionEntity(profileId = profileId, startAt = 0, endAt = millis, taskId = taskId))
        )

    /** 归档:先造一段历史(有引用才归档),再走仓库的 removeOrArchive */
    private suspend fun archive(clock: ProfileEntity) {
        session(clock.id)
        assertEquals(ProfileRemoval.Archived, g.profileRepo.removeOrArchive(clock.id))
    }

    /** 删除预告:被会话段或每日合计引用的时钟 → 归档;两者都没有 → 真删(与原子删除同口径) */
    @Test fun deletePlanArchivesOnlyClocksWithHistory() = runTest {
        val withSession = clock("有会话")
        val withDailyOnly = clock("只有合计")
        val clean = clock("无历史")
        val plan = deletePlan(
            listOf(withSession, withDailyOnly, clean),
            sessionMillis = mapOf(withSession.id to 25 * 60_000L),
            dailyTotals = mapOf(withDailyOnly.id to 90 * 60_000L),
        )
        assertEquals(3, plan.count)
        assertEquals(2, plan.archiveCount)
        assertEquals("归档分钟 = 会话优先 + 每日合计", 115L, plan.archiveMinutes)
        assertTrue(plan.anyArchive)

        val plain = deletePlan(listOf(clean), emptyMap(), emptyMap())
        assertEquals(0, plain.archiveCount)
        assertEquals(0L, plain.archiveMinutes)
        assertFalse(plain.anyArchive)
    }

    /**
     * 指针 1:「至少保留 1 个」只约束真删 —— 唯一一个有时钟记录的时钟仍能从列表隐藏(归档行还在,
     * 历史照样解析它的名字),否则「只有一个时钟且有记录」的用户永远删不掉它。
     */
    @Test fun lastClockWithHistoryStillArchives() = runTest {
        val only = clock("唯一")
        session(only.id, millis = 25 * 60_000L)

        val plan = planDeletion(
            listOf(only),
            activeCount = 1,
            sessionMillis = mapOf(only.id to 25 * 60_000L),
            dailyTotals = emptyMap(),
        )

        assertEquals(1, plan.count)
        assertEquals(1, plan.archiveCount)
        assertEquals(25L, plan.archiveMinutes)
        assertTrue(plan.anyArchive)
    }

    /** 无历史且全选:留最后一个活跃时钟(与 [SettingsViewModel.deleteProfile] 的门控同口径) */
    @Test fun planDeletionKeepsLastClockWithoutHistory() = runTest {
        val a = clock("A")
        val b = clock("B")

        val plan = planDeletion(listOf(a, b), activeCount = 2, sessionMillis = emptyMap(), dailyTotals = emptyMap())

        assertEquals("只动一个:另一个必须留下", 1, plan.count)
        assertEquals(0, plan.archiveCount)
        assertFalse(plan.anyArchive)
        assertEquals(listOf(a.id), plan.clocks.map { it.id })
    }

    /** 指针 3:有历史的时钟删除走归档 —— 行保留、历史账一行不删、不再调清账路径 */
    @Test fun deleteProfileArchivesHistoryAndKeepsRecords() = runTest {
        val p = clock("有历史")
        session(p.id, millis = 30 * 60_000L)
        g.totalsRepo.addWork("2026-09-24", p.id, 30 * 60_000L)

        assertFalse("归档不需要发 stop", vm().deleteProfile(p))

        assertTrue("行保留并标记归档", g.profileRepo.byId(p.id)!!.archived)
        assertEquals("会话段一行不少", 1, g.db.focusSessionDao().getAll().size)
        assertEquals(
            "每日合计一行不少",
            30 * 60_000L,
            g.totalsRepo.profileTotals().first().first { it.profileId == p.id }.total,
        )
        assertTrue("归档后从管理页活跃流消失", g.profileRepo.observeAllActive().first().none { it.id == p.id })
    }

    /** 指针 1:归档行不算「至少保留 1 个」—— 界面上看不见的行不能替活跃时钟挡删除 */
    @Test fun archivedClockDoesNotSatisfyKeepOneRule() = runTest {
        val live = clock("在用")
        val dead = clock("旧")
        archive(dead)

        assertFalse("唯一活跃时钟不可删(归档行不算数)", vm().deleteProfile(live))
        assertNotNull("活跃时钟未被删", g.profileRepo.byId(live.id))
        assertFalse(g.profileRepo.byId(live.id)!!.archived)
    }
}

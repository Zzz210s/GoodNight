package com.goodnight.ui.settings

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.goodnight.data.db.ProfileMode
import com.goodnight.di.AppGraph
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v2.2 Task 5:删除预告的**纯计算** —— 哪些归档(有历史,或正被引擎选中)、哪些真删、
 * 「至少保留 1 个活跃时钟」扣下谁。写库的实际结局见 `ProfileDeleteApplyTest`(归档/真删)与
 * `ProfileDeleteInUseTest`(正在使用 = 不停机 + 归档),列表分段见 `ProfileManageTest`。
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

    private suspend fun clock(name: String, taskId: Long? = null) =
        g.profileRepo.byId(g.profileRepo.create(name, 25, 5, ProfileMode.COUNTDOWN, taskId)!!)!!

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
        assertFalse(plan.anyInUse)

        val plain = deletePlan(listOf(clean), emptyMap(), emptyMap())
        assertEquals(0, plain.archiveCount)
        assertEquals(0L, plain.archiveMinutes)
        assertFalse(plain.anyArchive)
    }

    /**
     * 复审修复(计划侧):正被引擎选中的时钟在计划里也算归档(即使无历史)—— 确认框的归档计数与
     * 「当前这段计时会继续」提示都靠它;实际写库同一判据(见 ProfileDeleteInUseTest)。
     */
    @Test fun planArchivesInUseClockWithoutHistory() = runTest {
        val inUse = clock("在用")
        val clean = clock("无历史")

        val plan = planDeletion(
            listOf(inUse, clean), activeCount = 3,
            sessionMillis = emptyMap(), dailyTotals = emptyMap(),
            engineProfileId = inUse.id,
        )

        assertEquals(2, plan.count)
        assertEquals("在用时钟进归档集合", setOf(inUse.id), plan.archivedIds)
        assertEquals("归档包裹里没有分钟,归档分钟数为 0", 0L, plan.archiveMinutes)
        assertEquals(setOf(inUse.id), plan.inUseIds)
        assertTrue(plan.anyInUse)
        assertEquals("另一个才是真删", listOf(clean.id), plan.removed.map { it.id })
    }

    /**
     * 复审修复:归档也计入「还剩几个活跃时钟」—— 两个活跃时钟全选、其中一个有历史时,
     * 归档 + 真删会把活跃列表清零(首页开始键失效)。计划必须留下最后一个(它在 keptIds 里,
     * 确认框据此解释按钮计数与执行数的差额)。
     */
    @Test fun planDeletionKeepsOneActiveClockWhenOthersWouldOnlyArchive() = runTest {
        val clean = clock("无历史")
        val withHistory = clock("有历史")

        val plan = planDeletion(
            listOf(clean, withHistory),
            activeCount = 2,
            sessionMillis = mapOf(withHistory.id to 25 * 60_000L),
            dailyTotals = emptyMap(),
        )

        assertEquals("只执行一个:另一个必须留在活跃列表", 1, plan.count)
        assertEquals("勾选数是 2(按钮计数口径),正文首句要能对上", 2, plan.selectedCount)
        assertEquals("留下的那个不归档", 0, plan.archiveCount)
        assertEquals(0L, plan.archiveMinutes)
        assertEquals(listOf(clean.id), plan.clocks.map { it.id })
        assertEquals(setOf(withHistory.id), plan.keptIds)
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
}

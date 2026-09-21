package com.goodnight.ui.home

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.goodnight.data.db.ProfileEntity
import com.goodnight.di.AppGraph
import com.goodnight.timer.EngineStatus
import com.goodnight.timer.Phase
import com.goodnight.timer.RuntimeSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class) // 绕过 GoodNightApp 真实装配,保持测试封闭
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    private fun snap(status: EngineStatus) = RuntimeSnapshot(
        profileId = 1, workMillis = 1, restMillis = 1, phase = Phase.WORK, status = status,
        cycleCount = 0, startElapsed = 0, endElapsed = 1, endWall = 0,
        timeSpentPaused = 0, lastPauseTime = 0, timeAtPause = 0,
        savedAtWall = 0, savedAtElapsed = 0, ckptDate = null, ckptAccum = 0,
    )

    @Test fun selectProfilePolicy() = runTest {
        // 新增第二个 @Test 必须用独立 store 文件名:同进程同文件多实例会抛异常(见 AlarmReceiverTest 头注)
        val g = AppGraph(ctx, useInMemoryDb = true, storeFileName = "hv1")
        g.bootstrap()
        val vm = HomeViewModel(g)
        // RUNNING:忽略
        g.engine.restore(snap(EngineStatus.RUNNING))
        assertFalse(vm.selectProfile(ProfileEntity(9, "X", 25, 5, 0)))
        assertEquals(-1L, g.settingsRepo.activeProfileId.first()) // IGNORED 不写
        // PAUSED:重开
        g.engine.restore(snap(EngineStatus.PAUSED))
        assertTrue(vm.selectProfile(ProfileEntity(9, "X", 25, 5, 0)))
        assertEquals(9L, g.settingsRepo.activeProfileId.first())
        // IDLE:仅设 active
        g.engine.restore(null)
        assertFalse(vm.selectProfile(ProfileEntity(8, "Y", 25, 5, 0)))
        assertEquals(8L, g.settingsRepo.activeProfileId.first()) // SET_ACTIVE 确实落库
    }

    @Test fun dayDetailBreaksDownPerProfile() = runTest {
        val g = AppGraph(ctx, useInMemoryDb = true, storeFileName = "hv_daydetail")
        g.bootstrap()
        // #3 首装空库:无种子行,明细归属完全由测试自建的 id 决定
        val pomoId = g.profileRepo.create("番茄", 25, 5)
        val deepId = g.profileRepo.create("深度", 50, 10)
        val today = java.time.LocalDate.now()
        g.totalsRepo.addWork(today.toString(), pomoId, 30 * 60_000L)
        g.totalsRepo.addWork(today.toString(), deepId, 90 * 60_000L)
        val vm = HomeViewModel(g)
        vm.selectDay(today)
        val d = vm.dayDetail.first { it != null }!!
        assertEquals(2, d.rows.size)
        assertEquals(2 * 3_600_000L, d.totalMillis)
        assertEquals(90 * 60_000L, d.rows[0].millis)           // 按时长降序
        assertEquals("深度", d.rows[0].profileName)
        vm.selectDay(null)
        shadowOf(Looper.getMainLooper()).idle() // Robolectric 主 looper 暂停,Main 上的状态流恢复需手动泵
        assertNull(vm.dayDetail.value)
    }

    @Test fun dayDetailRefreshesWhenTotalsChange() = runTest {
        val g = AppGraph(ctx, useInMemoryDb = true, storeFileName = "hv_daydetail_live")
        g.bootstrap()
        val today = java.time.LocalDate.now()
        val id = g.profileRepo.create("专注", 25, 5)
        g.totalsRepo.addWork(today.toString(), id, 30 * 60_000L)
        val vm = HomeViewModel(g)
        vm.selectDay(today)
        assertEquals(30 * 60_000L, vm.dayDetail.first { it != null }!!.totalMillis)
        // 不重新 selectDay:Room 失效通知驱动 dayTotals 重发,detail 应自动反映新总额。
        // 失效链路在 Room 后台线程间逐跳推进,每跳回暂停的 Main looper 都需手动泵(同上
        // selectDay(null) 后 idle 的既有模式);泵多轮直到状态流换新值。
        g.totalsRepo.addWork(today.toString(), id, 20 * 60_000L)
        val deadline = System.currentTimeMillis() + 10_000
        while (vm.dayDetail.value?.totalMillis != 50 * 60_000L && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        assertEquals(50 * 60_000L, vm.dayDetail.value?.totalMillis)
    }

    @Test fun dayDetailHidesDeletedProfile() = runTest {
        // v1.10.8:删除配置时级联清掉它的段落与合计 —— 每日详情不再出现"已删除配置"行
        val g = AppGraph(ctx, useInMemoryDb = true, storeFileName = "hv_deleted")
        g.bootstrap()
        val pomoId = g.profileRepo.create("番茄", 25, 5)
        val tempId = g.profileRepo.create("临时", 25, 5)
        val today = java.time.LocalDate.now()
        g.totalsRepo.addWork(today.toString(), pomoId, 30 * 60_000L)
        g.totalsRepo.addWork(today.toString(), tempId, 90 * 60_000L)
        val zone = java.time.ZoneId.systemDefault()
        val t0 = today.atStartOfDay(zone).toInstant().toEpochMilli()
        g.totalsRepo.recordWorkSession(pomoId, t0 + 9 * 3_600_000L, t0 + 9 * 3_600_000L + 30 * 60_000L, zone)
        g.totalsRepo.recomputeDay(today.toString(), zone)
        g.profileRepo.delete(g.profileRepo.byId(tempId)!!)
        g.totalsRepo.deleteProfileData(tempId)
        val vm = HomeViewModel(g)
        vm.selectDay(today)
        val d = vm.dayDetail.first { it != null }!!
        assertEquals(1, d.rows.size)
        assertEquals("番茄", d.rows[0].profileName)
        // 行合计 == 该行时间段之和(单一数据源)
        assertEquals(30 * 60_000L, d.rows[0].millis)
        assertEquals(30 * 60_000L, d.rows[0].sessions.sumOf { it.second - it.first })
        assertEquals(d.rows.sumOf { it.millis }, d.totalMillis)
    }

    @Test fun dayDetailRowTotalsEqualSpanSums() = runTest {
        // v1.10.8:合并规则(间隔<=3 分钟)与合计同源:显示合并了几段,合计就含那几段
        val g = AppGraph(ctx, useInMemoryDb = true, storeFileName = "hv_consistent")
        g.bootstrap()
        val id = g.profileRepo.create("专注", 25, 5)
        val today = java.time.LocalDate.now()
        val zone = java.time.ZoneId.systemDefault()
        val t0 = today.atStartOfDay(zone).toInstant().toEpochMilli() + 9 * 3_600_000L
        // 两段,间隔 2 分钟(<=3 分钟)-> 展示合并为一条,合计也算成 32 分钟
        g.totalsRepo.recordWorkSession(id, t0, t0 + 10 * 60_000L, zone)
        g.totalsRepo.recordWorkSession(id, t0 + 12 * 60_000L, t0 + 32 * 60_000L, zone)
        g.totalsRepo.recomputeDay(today.toString(), zone)
        val vm = HomeViewModel(g)
        vm.selectDay(today)
        val d = vm.dayDetail.first { it != null }!!
        assertEquals(1, d.rows[0].sessions.size)
        assertEquals(32 * 60_000L, d.rows[0].millis)
        assertEquals(32 * 60_000L, d.totalMillis)
    }
}

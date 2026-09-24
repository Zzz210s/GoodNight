package com.goodnight.ui.home

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.goodnight.R
import com.goodnight.di.AppGraph
import com.goodnight.service.ACTION_SET_TASK
import com.goodnight.service.EXTRA_TASK_ID
import com.goodnight.service.NO_TASK_ID
import com.goodnight.timer.EngineStatus
import com.goodnight.timer.Phase
import com.goodnight.timer.RuntimeSnapshot
import java.time.LocalDate
import java.time.ZoneId
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

/**
 * v2.1 Task 7:计时页任务 chip + 每日详情任务名。
 *
 * 项目无 Compose UI 测试基础设施,故 chip 的"文案"在 VM 层钉:未绑定 = [R.string.task_unbound]
 * (本类用 zh 限定符断言中文文案),绑定后 = `currentTask.title`;选择动作钉在**命令**上
 * (经 [com.goodnight.service.TimerCommands] 发出的 SET_TASK intent,服务->协调器->引擎)。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh", application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class HomeTaskChipTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private fun snap(status: EngineStatus, taskId: Long? = null) = RuntimeSnapshot(
        profileId = 1, workMillis = 60_000, restMillis = 30_000, phase = Phase.WORK, status = status,
        cycleCount = 0, startElapsed = 0, endElapsed = 60_000, endWall = 0,
        timeSpentPaused = 0, lastPauseTime = 0, timeAtPause = 0,
        savedAtWall = 0, savedAtElapsed = 0, ckptDate = null, ckptAccum = 0, taskId = taskId,
    )

    /** 未绑定时的 chip 文案(zh 资源);en 对照在 EnResourcesTest */
    @Test fun unboundLabelIsChineseResourceText() {
        assertEquals("未绑定任务", ctx.getString(R.string.task_unbound))
    }

    @Test fun chipFollowsEngineBinding() = runTest {
        val g = AppGraph(ctx, useInMemoryDb = true, storeFileName = "htc_chip_follows")
        g.bootstrap()
        val id = g.taskRepo.create("写周报", g.time.now())!!
        val vm = HomeViewModel(g)
        g.engine.restore(snap(EngineStatus.RUNNING))
        assertNull("未绑定时 chip 落到未绑定文案", vm.currentTask.first())
        g.engine.setTask(id)
        assertEquals("写周报", vm.currentTask.first { it != null }!!.title)
        g.engine.setTask(null)
        assertNull("解绑后回到未绑定", vm.currentTask.first { it == null })
    }

    /** 选择 = 走既有命令入口(TimerCommands),而不是直接驱动引擎(协调器持有唯一那把 mutex) */
    @Test fun pickingTaskSendsSetTaskCommand() = runTest {
        val g = AppGraph(ctx, useInMemoryDb = true, storeFileName = "htc_pick_cmd")
        g.bootstrap()
        val id = g.taskRepo.create("写周报", g.time.now())!!
        val vm = HomeViewModel(g)
        g.engine.restore(snap(EngineStatus.RUNNING))
        vm.onOpenTaskPicker()
        assertTrue(vm.taskPickerOpen.value)
        vm.onPickTask(id)
        assertFalse("选择后选择器应关闭", vm.taskPickerOpen.value)
        val bound = shadowOf(app).nextStartedService
        assertEquals(ACTION_SET_TASK, bound.action)
        assertEquals(id, bound.getLongExtra(EXTRA_TASK_ID, NO_TASK_ID))
        vm.onPickTask(null)
        assertEquals("「不绑定」用哨兵值表达 null", NO_TASK_ID, shadowOf(app).nextStartedService.getLongExtra(EXTRA_TASK_ID, NO_TASK_ID))
    }

    /** 未绑定段:详情行不带任务名(UI 据此不渲染名字行) */
    @Test fun unboundSpanHasNoTaskName() = runTest {
        val g = AppGraph(ctx, useInMemoryDb = true, storeFileName = "htc_detail_unbound")
        g.bootstrap()
        val pid = g.profileRepo.create("专注", 25, 5)!!
        val day = LocalDate.now()
        val t0 = dayStart(day) + 9 * 3_600_000L
        g.totalsRepo.recordWorkSession(pid, t0, t0 + 30 * 60_000L)
        g.totalsRepo.recomputeDay(day.toString())
        val vm = HomeViewModel(g)
        vm.selectDay(day)
        val row = vm.dayDetail.first { it != null }!!.rows[0]
        assertEquals(1, row.taskSpans.size)
        assertNull(row.taskSpans[0].taskName)
        assertNull(row.taskSpans[0].taskId)
    }

    /**
     * 指针②:任务切点两侧 gap=0,展示合并([DayDetailRow.sessions])会把它们并回一条 ——
     * 若直接拿合并结果渲染,一个显示块里会出现两个任务名。细分必须按 taskId 切开。
     */
    @Test fun taskCutSplitsDisplaySpansInsideMergedBlock() = runTest {
        val g = AppGraph(ctx, useInMemoryDb = true, storeFileName = "htc_detail_cut")
        g.bootstrap()
        val pid = g.profileRepo.create("专注", 25, 5)!!
        val a = g.taskRepo.create("写周报", g.time.now())!!
        val b = g.taskRepo.create("读论文", g.time.now())!!
        val day = LocalDate.now()
        val t0 = dayStart(day) + 9 * 3_600_000L
        g.totalsRepo.recordWorkSession(pid, t0, t0 + 30 * 60_000L, taskId = a)
        g.totalsRepo.recordWorkSession(pid, t0 + 30 * 60_000L, t0 + 60 * 60_000L, taskId = b)
        g.totalsRepo.recomputeDay(day.toString())
        val vm = HomeViewModel(g)
        vm.selectDay(day)
        val row = vm.dayDetail.first { it != null }!!.rows[0]
        assertEquals("展示合并仍把 gap=0 的两段并成一条", 1, row.sessions.size)
        assertEquals(listOf("写周报", "读论文"), row.taskSpans.map { it.taskName })
        assertEquals(2, row.taskSpans.size)
        assertEquals(60 * 60_000L, row.millis)
        assertEquals("细分时段之和 == 行合计(合计口径不变)", row.millis, row.taskSpans.sumOf { it.end - it.start })
    }

    /** 同一任务被暂停切成两行(间隔 <= 3 分钟):细分里必须并回一条,不能显示两个同名段 */
    @Test fun sameTaskRowsMergeIntoOneNamedSpan() = runTest {
        val g = AppGraph(ctx, useInMemoryDb = true, storeFileName = "htc_detail_same")
        g.bootstrap()
        val pid = g.profileRepo.create("专注", 25, 5)!!
        val a = g.taskRepo.create("写周报", g.time.now())!!
        val day = LocalDate.now()
        val t0 = dayStart(day) + 9 * 3_600_000L
        g.totalsRepo.recordWorkSession(pid, t0, t0 + 10 * 60_000L, taskId = a)
        g.totalsRepo.recordWorkSession(pid, t0 + 12 * 60_000L, t0 + 30 * 60_000L, taskId = a)
        g.totalsRepo.recomputeDay(day.toString())
        val vm = HomeViewModel(g)
        vm.selectDay(day)
        val row = vm.dayDetail.first { it != null }!!.rows[0]
        assertEquals(1, row.taskSpans.size)
        assertEquals("写周报", row.taskSpans[0].taskName)
        assertEquals(t0, row.taskSpans[0].start)
        assertEquals(t0 + 30 * 60_000L, row.taskSpans[0].end)
    }

    /**
     * 绑定任务已归档(不在进行中列表)时,选择器列表里仍要出现它 —— 否则 chip 显示某任务、
     * 列表里却哪一项都不打勾(「不绑定」也不打勾,因为绑定 id 非 null)。
     */
    @Test fun pickerListsBoundTaskAfterItIsArchived() = runTest {
        val g = AppGraph(ctx, useInMemoryDb = true, storeFileName = "htc_picker_done")
        g.bootstrap()
        val a = g.taskRepo.create("写周报", g.time.now())!!
        val b = g.taskRepo.create("读论文", g.time.now())!!
        val vm = HomeViewModel(g)
        g.engine.restore(snap(EngineStatus.RUNNING, taskId = b))
        g.taskRepo.setDone(b, true, g.time.now())
        assertEquals("归档后不在进行中列表", listOf(a), vm.activeTasks.first { it.isNotEmpty() }.map { it.id })
        // 先等 chip 的绑定解析出来(与 chipFollowsEngineBinding 同款等待),再读选择器列表:
        // 此时 combine 的首次发射就已含绑定任务,不必再等派生流(避免 Robolectric 主 looper 泵不到)
        assertEquals("打勾 id 取可解析的绑定任务", b, vm.currentTask.first { it != null }!!.id)
        assertEquals("但选择器仍列出它(且是打勾项)", listOf(a, b), vm.pickerTasks.first { it.size == 2 }.map { it.id })
    }

    private fun dayStart(day: LocalDate): Long =
        day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
}

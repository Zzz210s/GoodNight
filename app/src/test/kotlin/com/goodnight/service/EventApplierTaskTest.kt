package com.goodnight.service

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.goodnight.di.AppGraph
import com.goodnight.timer.EngineEvent
import com.goodnight.timer.Phase
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v2.1 Task 5:服务层落库带任务 + 通知标题带任务名。
 *
 * 关键语义(计划裁定):
 * - `TaskSwitched` 在服务层是**空效果**(引擎已把切点记入快照并随结算事件下发);
 * - 结算事件的 `taskId` 是**段内最后一个**子段的任务,整段段首任务只能取**最早切点的
 *   `fromTaskId`**,段内无切点时整段才取 `ev.taskId`;
 * - 落段切点表 = 事件切点表映射为 `(切点, 从该切点起那一段的 taskId)`。
 *
 * 库名/偏好文件名带测试类名,避免 Robolectric 沙箱内 DataStore/Room 串扰。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh")
class EventApplierTaskTest {
    private lateinit var ctx: Context
    private lateinit var graph: AppGraph
    private lateinit var applier: EventApplier

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        graph = AppGraph(ctx, useInMemoryDb = true, storeFileName = "applier_task_store")
        applier = EventApplier(graph, graph.coordinator.ledger, graph.coordinator.notifier)
    }

    @After fun tearDown() {
        runBlocking {
            graph.appScope.coroutineContext.job.cancelAndJoin()
            runCatching { graph.db.close() }
        }
    }

    /** 今天本地时间 [hour]:00 的墙钟 ms(与落库用的 systemDefault 时区一致) */
    private fun localMs(hour: Int, minute: Int = 0): Long {
        val zone = ZoneId.systemDefault()
        return LocalDate.now(zone).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
    }

    private fun dayOf(t: Long): String =
        LocalDate.ofInstant(Instant.ofEpochMilli(t), ZoneId.systemDefault()).toString()

    private suspend fun dayTotal(t: Long): Long =
        graph.totalsRepo.breakdownByDate(dayOf(t)).sumOf { it.total }

    /** 结算事件:taskId = 段内末子段任务;taskCuts = "切点,切换前,切换后;..." */
    private fun settle(ss: Long, se: Long, taskId: Long?, cuts: String = "") = EngineEvent.PhaseFinished(
        finished = Phase.WORK, settleMillis = 0L, profileId = 1L, next = Phase.REST, auto = false,
        sessionStartWall = ss, sessionEndWall = se, taskId = taskId, taskCuts = cuts,
    )

    @Test fun workTitleAppendsTaskNameOnlyWhenBound() {
        assertEquals("工作中 · 写周报", TimerNotifications.workTitle("工作中", "写周报"))
        assertEquals("工作中", TimerNotifications.workTitle("工作中", null))
        assertEquals("工作中", TimerNotifications.workTitle("工作中", "  "))
    }

    @Test fun inProgressTitleCarriesTaskName() {
        TimerNotifications.ensureChannels(ctx)
        val bound = TimerNotifications.inProgress(ctx, snapOf().copy(taskId = 7L), "写周报")
        assertEquals("工作中 · 写周报", bound.extras.getCharSequence(NotificationCompat.EXTRA_TITLE).toString())
        val unbound = TimerNotifications.inProgress(ctx, snapOf().copy(taskId = 7L))
        assertEquals("工作中", unbound.extras.getCharSequence(NotificationCompat.EXTRA_TITLE).toString())
    }

    /** 段首 A、09:30 切到 B:两段各带自己 id,当日合计仍等于整段时长 */
    @Test fun settleWithTaskCutBindsEachSegmentToItsOwnTask() = runBlocking {
        val a = graph.taskRepo.create("任务A", now = 1L)!!
        val b = graph.taskRepo.create("任务B", now = 2L)!!
        val t0 = localMs(9)
        val cut = t0 + 30 * 60_000L
        val t1 = t0 + 60 * 60_000L

        applier.apply(settle(t0, t1, taskId = b, cuts = "$cut,$a,$b"))

        val rows = graph.totalsRepo.sessionsBetweenMs(t0, t1)
        assertEquals(listOf(t0 to cut, cut to t1), rows.map { it.startAt to it.endAt })
        assertEquals(listOf(a, b), rows.map { it.taskId }) // 前段段首任务 A(非 ev.taskId),后段 B
        assertEquals(60 * 60_000L, dayTotal(t0))
    }

    /** 段内无切点(首次绑定即定义整段):整段归 ev.taskId */
    @Test fun settleWithoutCutBindsWholeSegmentToEventTask() = runBlocking {
        val a = graph.taskRepo.create("任务A", now = 1L)!!
        val t0 = localMs(9)
        val t1 = t0 + 60 * 60_000L

        applier.apply(settle(t0, t1, taskId = a))

        val rows = graph.totalsRepo.sessionsBetweenMs(t0, t1)
        assertEquals(listOf(t0 to t1), rows.map { it.startAt to it.endAt })
        assertEquals(listOf(a), rows.map { it.taskId })
        assertEquals(60 * 60_000L, dayTotal(t0))
    }

    /**
     * 运行中删除已绑定任务 → 结算:运行态还带着已删 id(删除只清库内引用),
     * 落库必须丢弃它(整段归未绑定),而时间账照旧保留。
     */
    @Test fun settleWithDeletedTaskStoresUnbound() = runBlocking {
        val a = graph.taskRepo.create("任务A", now = 1L)!!
        val t0 = localMs(9)
        val t1 = t0 + 60 * 60_000L
        graph.taskRepo.deleteTask(a)

        applier.apply(settle(t0, t1, taskId = a))

        val rows = graph.totalsRepo.sessionsBetweenMs(t0, t1)
        assertEquals(listOf(t0 to t1), rows.map { it.startAt to it.endAt })
        assertNull(rows.single().taskId)
        assertEquals(60 * 60_000L, dayTotal(t0))
    }

    /** 切点指向已删任务:那一段归未绑定,前一段仍保留自己的任务 id */
    @Test fun settleWithDeletedTaskInCutStoresUnbound() = runBlocking {
        val a = graph.taskRepo.create("任务A", now = 1L)!!
        val b = graph.taskRepo.create("任务B", now = 2L)!!
        val t0 = localMs(9)
        val cut = t0 + 30 * 60_000L
        val t1 = t0 + 60 * 60_000L
        graph.taskRepo.deleteTask(b)

        applier.apply(settle(t0, t1, taskId = b, cuts = "$cut,$a,$b"))

        val rows = graph.totalsRepo.sessionsBetweenMs(t0, t1)
        assertEquals(listOf(t0 to cut, cut to t1), rows.map { it.startAt to it.endAt })
        assertEquals(listOf(a, null), rows.map { it.taskId })
        assertEquals(60 * 60_000L, dayTotal(t0))
    }

    /** TaskSwitched 在服务层是空效果:不落库、不动快照(切点由引擎记入快照并随结算事件下发) */
    @Test fun taskSwitchedWritesNothing() = runBlocking {
        val a = graph.taskRepo.create("任务A", now = 1L)!!
        val b = graph.taskRepo.create("任务B", now = 2L)!!
        val t0 = localMs(9)
        val before = graph.engine.snapshot.value

        applier.apply(EngineEvent.TaskSwitched(t0 + 30 * 60_000L, a, b))

        assertTrue(graph.totalsRepo.sessionsBetweenMs(t0, t0 + 60 * 60_000L).isEmpty())
        assertEquals(0L, dayTotal(t0))
        assertEquals(before, graph.engine.snapshot.value)
    }

    @Test fun titleForResolvesBoundTaskOnly() = runBlocking {
        val a = graph.taskRepo.create("  写周报  ", now = 1L)!!
        assertEquals("写周报", graph.coordinator.notifier.titleFor(snapOf().copy(taskId = a)))
        assertNull(graph.coordinator.notifier.titleFor(snapOf().copy(taskId = 9_999L)))
        assertNull(graph.coordinator.notifier.titleFor(snapOf()))
        assertNull(graph.coordinator.notifier.titleFor(null))
    }
}

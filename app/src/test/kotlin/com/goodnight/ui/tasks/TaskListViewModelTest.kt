package com.goodnight.ui.tasks

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.goodnight.R
import com.goodnight.data.TaskRepository
import com.goodnight.data.db.FocusSessionEntity
import com.goodnight.di.AppGraph
import com.goodnight.timer.EngineStatus
import com.goodnight.timer.Phase
import com.goodnight.timer.RuntimeSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * v2.1 Task 6:任务页 VM。
 *
 * Robolectric 下测试线程即主线程,`viewModelScope` 的 Main.immediate + 直通 executor 使
 * 落库内联完成;[pump] 兜底任何经主 looper 的派发(约定同 ReportViewModelTest)。
 * 删除用例同时覆盖 Task 5 遗留项:删除必须先清运行态绑定,否则结算会写孤儿 taskId。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh", application = android.app.Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class TaskListViewModelTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var g: AppGraph

    @Before fun setUp() {
        g = AppGraph(ctx, useInMemoryDb = true, storeFileName = "tlvm_store")
    }

    @After fun tearDown() {
        runCatching { g.db.close() }
    }

    private fun pump() = shadowOf(Looper.getMainLooper()).idle()
    private fun vm() = TaskListViewModel(g)
    private suspend fun active() = g.taskRepo.observeActive().first()
    private suspend fun idOf(title: String) = active().first { it.title == title }.id

    private fun snap(taskId: Long?) = RuntimeSnapshot(
        profileId = 1, workMillis = 1, restMillis = 1, phase = Phase.WORK,
        status = EngineStatus.RUNNING, cycleCount = 0, startElapsed = 0, endElapsed = 1,
        endWall = 0, timeSpentPaused = 0, lastPauseTime = 0, timeAtPause = 0,
        savedAtWall = 0, savedAtElapsed = 0, ckptDate = null, ckptAccum = 0, taskId = taskId,
    )

    private suspend fun insertSpan(taskId: Long?, startAt: Long, millis: Long) =
        g.db.focusSessionDao().insertAll(
            listOf(FocusSessionEntity(profileId = 1, startAt = startAt, endAt = startAt + millis, taskId = taskId)),
        )

    @Test fun createRejectsBlankAndOverlongThenAcceptsTrimmed() = runTest {
        val v = vm()
        v.onCreate("   "); pump()
        assertEquals(TaskInputError.BLANK, v.inputError.value)
        assertEquals(0, active().size)

        v.onCreate("字".repeat(TaskRepository.MAX_TITLE + 1)); pump()
        assertEquals(TaskInputError.TOO_LONG, v.inputError.value)
        assertEquals(0, active().size)

        v.onCreate("  写周报  "); pump() // 合法:清错误、按去空白入库
        assertNull(v.inputError.value)
        assertEquals(listOf("写周报"), active().map { it.title })
    }

    @Test fun toggleDoneMovesBetweenGroupsAndClearsDoneAt() = runTest {
        val v = vm()
        v.onCreate("A"); v.onCreate("B"); pump()
        val a = idOf("A")

        v.onToggleDone(a); pump()
        assertEquals(listOf("B"), active().map { it.title })
        val done = g.taskRepo.observeDone().first().single()
        assertEquals(a, done.id)
        assertNotNull(done.doneAt)

        v.onToggleDone(a); pump() // 取消完成:必须清 doneAt,否则已完成列表按旧值错位
        assertEquals(listOf("A", "B"), active().map { it.title })
        assertNull(active().first { it.id == a }.doneAt)
        assertEquals(0, g.taskRepo.observeDone().first().size)
    }

    @Test fun moveReordersActiveAndCompactsSortOrder() = runTest {
        val v = vm()
        v.onCreate("A"); v.onCreate("B"); v.onCreate("C"); pump()

        v.onMove(2, 0); pump() // 把第 3 项拖到最前
        val rows = active()
        assertEquals(listOf("C", "A", "B"), rows.map { it.title })
        assertEquals(listOf(1L, 2L, 3L), rows.map { it.sortOrder }) // 压实连续,不留空洞
    }

    @Test fun renameTrimsRejectsInvalidAndKeepsOldTitle() = runTest {
        val v = vm()
        v.onCreate("写周报"); pump()
        val id = idOf("写周报")

        v.onRename(id, "  写月报 "); pump()
        assertNull(v.inputError.value)
        assertEquals("写月报", active().single().title)

        v.onRename(id, "   "); pump()
        assertEquals(TaskInputError.BLANK, v.inputError.value)
        assertEquals("写月报", active().single().title)
    }

    /** Task 5 遗留项①:删除必须先解除运行态绑定,再删行 —— 否则结算写出指向已删任务的孤儿 id */
    @Test fun deleteClearsRuntimeBindingAndKeepsTimeLedger() = runTest {
        val v = vm()
        v.onCreate("写周报"); pump()
        val id = idOf("写周报")
        insertSpan(taskId = id, startAt = 0, millis = 10 * 60_000L)
        g.engine.restore(snap(taskId = id))

        v.onDelete(id); pump()

        assertEquals(0, active().size)
        assertNull(g.engine.snapshot.value?.taskId) // 运行态绑定已清
        val row = g.db.focusSessionDao().between(-1, 10_000_000).single()
        assertNull(row.taskId) // 时间账保留:段还在,只置空引用
        assertEquals(10 * 60_000L, row.endAt - row.startAt)
    }

    @Test fun deletePromptCarriesRecordedMinutesOfThatTask() = runTest {
        val v = vm()
        v.onCreate("写周报"); v.onCreate("其它"); pump()
        val id = idOf("写周报")
        val other = idOf("其它")
        insertSpan(taskId = id, startAt = 0, millis = 25 * 60_000L)
        insertSpan(taskId = id, startAt = 100_000_000, millis = 5 * 60_000L)
        insertSpan(taskId = other, startAt = 200_000_000, millis = 60 * 60_000L)
        insertSpan(taskId = null, startAt = 300_000_000, millis = 60 * 60_000L)

        v.onDeleteRequest(id); pump()
        val prompt = v.deletePrompt.value!!
        assertEquals(id, prompt.id)
        assertEquals("写周报", prompt.title)
        assertEquals(30L, prompt.minutes) // 只算本任务关联的段
        assertEquals(
            "将删除任务,已记录的 30 分钟会保留为未绑定",
            ctx.getString(R.string.task_delete_confirm, prompt.minutes),
        )

        v.onDeleteConfirmed(); pump()
        assertNull(v.deletePrompt.value)
        assertEquals(listOf("其它"), active().map { it.title })
    }

    @Test fun recordedMinutesRoundsToNearestMinute() = runTest {
        val v = vm()
        v.onCreate("写周报"); pump()
        val id = idOf("写周报")
        insertSpan(taskId = id, startAt = 0, millis = 90_000L) // 1 分 30 秒 -> 2 分钟
        assertEquals(2L, v.recordedMinutes(id))
        assertEquals(0L, v.recordedMinutes(9_999L)) // 未知任务:0
    }

    @Test fun deleteDismissKeepsTask() = runTest {
        val v = vm()
        v.onCreate("写周报"); pump()
        v.onDeleteRequest(idOf("写周报")); pump()
        v.onDeleteDismiss(); pump()
        assertNull(v.deletePrompt.value)
        assertEquals(1, active().size)
    }
}

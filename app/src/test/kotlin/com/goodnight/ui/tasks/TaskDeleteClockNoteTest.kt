package com.goodnight.ui.tasks

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.goodnight.data.db.ProfileMode
import com.goodnight.di.AppGraph
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * v2.2 Task 7:删任务确认框要讲清**专属时钟的去向**(它们会被转成通用时钟,不丢设置与账)。
 *
 * 只在真有时钟会被转通用时出现 —— 没有专属时钟时文案与 v2.1 逐字相同(既有用例
 * TaskListViewModelTest / TaskDeleteInFlightTest 断言的就是这一支)。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh", application = android.app.Application::class)
class TaskDeleteClockNoteTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var g: AppGraph

    @Before fun setUp() {
        g = AppGraph(ctx, useInMemoryDb = true, storeFileName = "task_delete_clock_store")
    }

    @After fun tearDown() {
        runCatching { g.db.close() }
    }

    private fun pump() = shadowOf(Looper.getMainLooper()).idle()
    private fun vm() = TaskListViewModel(g)
    private suspend fun idOf(title: String): Long =
        g.taskRepo.observeActive().first().single { it.title == title }.id

    @Test fun promptCarriesBoundClockCountAndTextMentionsConversion() = runTest {
        val v = vm()
        v.onCreate("写周报"); v.onCreate("其它"); pump()
        val id = idOf("写周报")
        // 一个专属时钟 + 一个通用时钟:只有前者会被删任务转通用
        g.profileRepo.create("番茄", 25, 5, ProfileMode.COUNTDOWN, id)
        g.profileRepo.create("深度工作", 45, 15, ProfileMode.COUNTDOWN, null)

        v.onDeleteRequest(id); pump()
        val prompt = v.deletePrompt.value!!
        assertEquals(1, prompt.clocks)
        // 时钟数走 plurals:zh 只有 other 形态,文案与 v2.2 逐字相同
        assertEquals(
            "将删除任务,已记录的 0 分钟会保留为未绑定;它的 1 个专属时钟会转为通用时钟",
            deleteConfirmText(ctx, prompt).toString(),
        )
    }

    @Test fun promptWithoutBoundClocksKeepsOldText() = runTest {
        val v = vm()
        v.onCreate("写周报"); pump()
        val id = idOf("写周报")
        g.profileRepo.create("深度工作", 45, 15, ProfileMode.COUNTDOWN, null) // 通用时钟不随任务走

        v.onDeleteRequest(id); pump()
        val prompt = v.deletePrompt.value!!
        assertEquals(0, prompt.clocks)
        assertEquals(
            "将删除任务,已记录的 0 分钟会保留为未绑定",
            deleteConfirmText(ctx, prompt).toString(),
        )
    }

    /** 删除后真的转通用(文案承诺与写库行为一致):时钟行仍在、taskId 置空 */
    @Test fun confirmedDeleteFreesTheClocks() = runTest {
        val v = vm()
        v.onCreate("写周报"); pump()
        val id = idOf("写周报")
        val clockId = g.profileRepo.create("番茄", 25, 5, ProfileMode.COUNTDOWN, id)!!

        v.onDeleteRequest(id); pump()
        v.onDeleteConfirmed(); pump()

        assertNull(g.profileRepo.byId(clockId)?.taskId)
        assertEquals(0, g.profileRepo.countByTask(id))
    }
}

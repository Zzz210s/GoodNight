package com.goodnight.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.goodnight.data.db.FocusSessionEntity
import com.goodnight.data.db.GoodNightDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v2.1 Task 9 评审补测:导入后归一化不变量。
 *
 * 1. 非空库导入 v1:任务表与已绑定段原样保留(spec「导入 v1 时任务表不变」);
 * 2. 悬挂 `taskId`(引用的任务不在库中)在导入事务末尾置为 NULL,有效引用不动;
 * 3. `done`/`doneAt` 成对:未完成行的 `doneAt` 归一为 null。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class) // 绕过 GoodNightApp 真实装配,保持测试封闭
class DataTransferV2NormalizeTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val opened = ArrayList<GoodNightDatabase>()

    /** 每个用例独立库名(带测试类名)+ 先删文件:同沙箱多测试类共用 goodnight.db 会串扰 */
    private fun open(name: String): GoodNightDatabase {
        val file = "DataTransferV2NormalizeTest_$name.db"
        ctx.deleteDatabase(file)
        return GoodNightDatabase.build(ctx, file).also { opened += it }
    }

    @After fun tearDown() { opened.forEach { it.close() }; opened.clear() }

    /** 非空库导入 v1:任务表不变、已绑定段保留,新来的 v1 段为未绑定(profileId 也逐段核对) */
    @Test fun importV1OnNonEmptyDbKeepsTasksAndBindings() = runTest {
        val db = open("v1_nonempty")
        val task = TaskRepository(db).create("本机任务", now = 1L)!!
        db.focusSessionDao().insertAll(
            listOf(FocusSessionEntity(id = 7L, profileId = 1L, startAt = 10L, endAt = 20L, taskId = task)),
        )

        val counts = DataTransfer.importJson(db, V1_JSON)

        assertEquals(0, counts.tasks) // v1 无 tasks 数组
        val tasks = db.taskDao().allNow()
        assertEquals(1, tasks.size)
        assertEquals(task, tasks.single().id)
        assertEquals("本机任务", tasks.single().title)
        assertFalse(tasks.single().done)

        val ss = db.focusSessionDao().getAll()
        assertEquals(listOf(10L, 100L, 300L), ss.map { it.startAt })
        assertEquals(listOf(1L, 1L, 1L), ss.map { it.profileId }) // 所有合计的 join 键
        assertEquals(task, ss[0].taskId)
        assertNull(ss[1].taskId)
        assertNull(ss[2].taskId)
    }

    /** 归一化只动悬挂引用:库内有效绑定不受影响,同批导入的悬挂引用置空 */
    @Test fun importNormalizesOnlyDanglingRefs() = runTest {
        val db = open("dangling")
        val task = TaskRepository(db).create("本机任务", now = 1L)!!
        db.focusSessionDao().insertAll(
            listOf(FocusSessionEntity(profileId = 1L, startAt = 1L, endAt = 2L, taskId = task)),
        )

        DataTransfer.importJson(db, DANGLING_JSON) // 段 2 引用不存在的任务 99

        assertTrue(db.taskDao().allNow().any { it.id == task })
        val ss = db.focusSessionDao().getAll()
        assertEquals(listOf(1L, 10L), ss.map { it.startAt })
        assertEquals(task, ss[0].taskId)
        assertNull(ss[1].taskId)
    }

    /** done/doneAt 成对:外部工具写 done=0 + doneAt 非空(或布尔 true 被读成 0)时 doneAt 归一为 null */
    @Test fun importNormalizesUnpairedDoneAt() = runTest {
        val db = open("doneat")
        DataTransfer.importJson(db, DONE_AT_JSON)

        val tasks = db.taskDao().allNow().sortedBy { it.id }
        assertEquals(listOf("未完成", "已完成"), tasks.map { it.title })
        assertFalse(tasks[0].done)
        assertNull(tasks[0].doneAt)
        assertTrue(tasks[1].done)
        assertEquals(456L, tasks[1].doneAt)
    }

    private companion object {
        /** 真实 v1 备份形状:无 tasks 键、会话无 taskId */
        const val V1_JSON = """{"version":1,"exportedAt":1,"profiles":[],"dailyTotals":[],
            "focusSessions":[{"id":1,"profileId":1,"startAt":100,"endAt":200},
                             {"id":2,"profileId":1,"startAt":300,"endAt":400}]}"""

        const val DANGLING_JSON = """{"version":2,"exportedAt":1,"profiles":[],"dailyTotals":[],"tasks":[],
            "focusSessions":[{"id":2,"profileId":1,"startAt":10,"endAt":20,"taskId":99}]}"""

        const val DONE_AT_JSON = """{"version":2,"exportedAt":1,"profiles":[],"dailyTotals":[],
            "focusSessions":[],
            "tasks":[{"id":1,"title":"未完成","done":0,"doneAt":123,"createdAt":1,"sortOrder":1},
                     {"id":2,"title":"已完成","done":1,"doneAt":456,"createdAt":2,"sortOrder":2}]}"""
    }
}

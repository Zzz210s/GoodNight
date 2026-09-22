package com.goodnight.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.goodnight.data.db.FocusSessionEntity
import com.goodnight.data.db.GoodNightDatabase
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
 * v2.1 Task 2:任务仓库(读写 + 删除事务)。
 *
 * 库名带测试类名并独占:同一 Robolectric 沙箱里多个测试类共享 "goodnight.db"
 * 会 SQLITE_BUSY / 数据串扰。[deleteTask] 的「时间账保留」是规格硬要求
 * (删任务只解绑,不删段),故断言要同时覆盖 段还在 + taskId 已置空。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class TaskRepositoryTest {
    private val dbName = "goodnight-task-repo.db"
    private var db: GoodNightDatabase? = null
    private lateinit var repo: TaskRepository

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.deleteDatabase(dbName) // 残留文件会让 build() 复用旧库,先清
        db = GoodNightDatabase.build(ctx, dbName)
        repo = TaskRepository(db!!)
    }

    @After fun tearDown() {
        db?.close()
        db = null
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(dbName)
    }

    private suspend fun insertSession(taskId: Long?, startAt: Long) {
        db!!.focusSessionDao().insertAll(
            listOf(FocusSessionEntity(profileId = 1, startAt = startAt, endAt = startAt + 1, taskId = taskId))
        )
    }

    private suspend fun activeIds(): List<Long> = repo.observeActive().first().map { it.id }

    @Test fun createRejectsBlankAndOverlongTitles() = runTest {
        assertNull(repo.create("   ", now = 1))
        assertNull(repo.create("字".repeat(101), now = 1))
        assertNotNull(repo.create("写周报", now = 1))
        assertEquals(1, repo.observeActive().first().size)
    }

    @Test fun createAcceptsExactlyMaxTitleAndTrims() = runTest {
        assertEquals(100, TaskRepository.MAX_TITLE)
        assertNotNull(repo.create("字".repeat(TaskRepository.MAX_TITLE), now = 1))
        val id = repo.create("  写周报  ", now = 1_000)!!
        val row = repo.observeActive().first().first { it.id == id }
        assertEquals("写周报", row.title)
        assertEquals(1_000L, row.createdAt)
        assertEquals(false, row.done)
        assertNull(row.doneAt)
    }

    @Test fun createAppendsToEndOfManualOrder() = runTest {
        val a = repo.create("A", now = 1)!!
        val b = repo.create("B", now = 2)!!
        val c = repo.create("C", now = 3)!!
        val rows = repo.observeActive().first()
        assertEquals(listOf(a, b, c), rows.map { it.id })
        assertEquals(listOf(1L, 2L, 3L), rows.map { it.sortOrder }) // 新任务 = maxSortOrder + 1
    }

    @Test fun renameTrimsAndSilentlyRejectsInvalid() = runTest {
        val id = repo.create("写周报", now = 1)!!
        repo.rename(id, "  写月报 ")
        assertEquals("写月报", repo.observeActive().first().single().title)

        repo.rename(id, "   ")                              // 纯空白:拒绝
        repo.rename(id, "字".repeat(101))                   // 超长:拒绝
        repo.rename(id, "")                                 // 空串:拒绝
        assertEquals("写月报", repo.observeActive().first().single().title)
    }

    @Test fun setDoneMovesBetweenListsAndStampsDoneAt() = runTest {
        val id = repo.create("写周报", now = 1)!!
        repo.setDone(id, done = true, now = 9_000)

        assertTrue(repo.observeActive().first().isEmpty())
        val done = repo.observeDone().first().single()
        assertEquals(id, done.id)
        assertEquals(true, done.done)
        assertEquals(9_000L, done.doneAt)

        repo.setDone(id, done = false, now = 10_000) // 取消完成必须清 doneAt,不留旧值
        assertTrue(repo.observeDone().first().isEmpty())
        val back = repo.observeActive().first().single()
        assertEquals(false, back.done)
        assertNull(back.doneAt)
    }

    @Test fun deleteTaskKeepsSessionsButClearsRef() = runTest {
        val taskId = repo.create("写周报", now = 1_000)!!
        insertSession(taskId = taskId, startAt = 1)

        repo.deleteTask(taskId)

        assertTrue(repo.observeActive().first().isEmpty())
        val rows = db!!.focusSessionDao().between(0, 100)
        assertEquals(1, rows.size)      // 时间账保留
        assertNull(rows.first().taskId) // 引用置空
    }

    @Test fun deleteTaskLeavesOtherTasksRefsIntact() = runTest {
        val a = repo.create("A", now = 1)!!
        val b = repo.create("B", now = 2)!!
        insertSession(taskId = a, startAt = 1)
        insertSession(taskId = b, startAt = 2)
        insertSession(taskId = null, startAt = 3)

        repo.deleteTask(a)

        val refs = db!!.focusSessionDao().between(0, 100).map { it.taskId }
        assertEquals(listOf(null, b, null), refs) // 只有 a 的引用被清
    }

    @Test fun moveToReordersAndCompactsSortOrder() = runTest {
        val a = repo.create("A", now = 1)!!
        val b = repo.create("B", now = 2)!!
        val c = repo.create("C", now = 3)!!

        repo.moveTo(c, targetIndex = 0, list = repo.observeActive().first())
        val after = repo.observeActive().first()
        assertEquals(listOf(c, a, b), after.map { it.id })
        assertEquals(listOf(1L, 2L, 3L), after.map { it.sortOrder }) // 压实为连续

        repo.moveTo(c, targetIndex = 2, list = after)
        assertEquals(listOf(a, b, c), activeIds())

        repo.moveTo(a, targetIndex = 1, list = repo.observeActive().first())
        assertEquals(listOf(b, a, c), activeIds())
        assertEquals(listOf(1L, 2L, 3L), repo.observeActive().first().map { it.sortOrder })
    }

    @Test fun moveToClampsIndexAndIgnoresUnknownId() = runTest {
        val a = repo.create("A", now = 1)!!
        val b = repo.create("B", now = 2)!!

        repo.moveTo(999L, targetIndex = 0, list = repo.observeActive().first()) // 不在列表:不动
        assertEquals(listOf(a, b), activeIds())

        repo.moveTo(b, targetIndex = 99, list = repo.observeActive().first()) // 越界:夹到末尾
        assertEquals(listOf(a, b), activeIds())

        repo.moveTo(b, targetIndex = -5, list = repo.observeActive().first()) // 越界:夹到开头
        assertEquals(listOf(b, a), activeIds())
    }
}

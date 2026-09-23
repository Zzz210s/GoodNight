package com.goodnight.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.goodnight.data.db.FocusSessionEntity
import com.goodnight.data.db.GoodNightDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v2.1 Task 6 追加到 [TaskRepository] 的两件事(自 TaskRepositoryTest 拆出,保持单文件 <=200 行):
 *
 * 1. [TaskRepository.moveTo] 的前置条件(解决 Task 2 park 项):调用方快照可能过期 ——
 *    拖动期间新建了任务、或某行被勾选完成。列表外的活跃行若被忽略会保留旧 sortOrder,
 *    与压实结果重复;要求「以调用方顺序为准 + 列表外活跃行按原顺序追加」且 sortOrder 恒为 1..n。
 * 2. [TaskRepository.recordedMillis]:删除确认文案「已记录的 N 分钟」的取值口径。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class TaskMoveToTest {
    private val dbName = "goodnight-task-moveto.db"
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

    @Test fun moveToWithStaleSnapshotKeepsExtrasAndUniqueSortOrder() = runTest {
        val a = repo.create("A", now = 1)!!
        val b = repo.create("B", now = 2)!!
        repo.create("C", now = 3)
        val stale = repo.observeActive().first() // 拖动前快照
        repo.create("D", now = 4)                // 快照外新增
        repo.setDone(b, done = true, now = 5)    // 快照内行已移出活跃组

        repo.moveTo(a, targetIndex = 2, list = stale)

        val after = repo.observeActive().first()
        assertEquals(listOf("C", "D", "A"), after.map { it.title })
        assertEquals(listOf(1L, 2L, 3L), after.map { it.sortOrder })
        assertEquals(after.size, after.map { it.sortOrder }.distinct().size) // 无重复
    }

    /** 快照里全是旧行、库内只有其子集时也不留空洞(压实覆盖库内实况) */
    @Test fun moveToCompactsAgainstDbNotOnlySnapshot() = runTest {
        val a = repo.create("A", now = 1)!!
        repo.create("B", now = 2)
        val stale = repo.observeActive().first()
        repo.deleteTask(stale.last().id) // 快照里的 B 已删(行删除,非完成)

        repo.moveTo(a, targetIndex = 0, list = stale) // from == to,旧实现会早退不压实

        val after = repo.observeActive().first()
        assertEquals(listOf(a), after.map { it.id })
        assertEquals(listOf(1L), after.map { it.sortOrder })
    }

    @Test fun moveToIgnoresIdThatIsNoLongerActive() = runTest {
        val a = repo.create("A", now = 1)!!
        val b = repo.create("B", now = 2)!!
        val stale = repo.observeActive().first()
        repo.setDone(b, done = true, now = 3) // 被拖的行已完成

        repo.moveTo(b, targetIndex = 0, list = stale)

        assertEquals(listOf(a), repo.observeActive().first().map { it.id })
    }

    /** 删除确认文案的分钟数来源 —— 只合计该任务关联的段,未知任务为 0 */
    @Test fun recordedMillisSumsOnlyThatTasksSessions() = runTest {
        val a = repo.create("A", now = 1)!!
        val b = repo.create("B", now = 1)!!
        insertSpan(a, startAt = 0, millis = 25 * 60_000L)
        insertSpan(a, startAt = 100_000_000, millis = 5 * 60_000L)
        insertSpan(b, startAt = 200_000_000, millis = 60 * 60_000L)
        insertSpan(null, startAt = 300_000_000, millis = 60 * 60_000L)

        assertEquals(30 * 60_000L, repo.recordedMillis(a))
        assertEquals(60 * 60_000L, repo.recordedMillis(b))
        assertEquals(0L, repo.recordedMillis(9_999L))
    }

    private suspend fun insertSpan(taskId: Long?, startAt: Long, millis: Long) {
        db!!.focusSessionDao().insertAll(
            listOf(FocusSessionEntity(profileId = 1, startAt = startAt, endAt = startAt + millis, taskId = taskId))
        )
    }
}

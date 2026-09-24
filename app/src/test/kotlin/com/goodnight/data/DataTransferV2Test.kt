package com.goodnight.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.goodnight.data.db.DailyTotalEntity
import com.goodnight.data.db.FocusSessionEntity
import com.goodnight.data.db.GoodNightDatabase
import com.goodnight.data.db.ProfileEntity
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
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
 * v2.1 Task 9:备份 v2(顶层 `tasks` + `focusSessions[].taskId`)与 v1 兼容导入。
 *
 * 覆盖:v1 账目逐值等价且任务表为空、v2 往返含任务字段与每段绑定、备份里引用的任务已不存在
 * 时仍可导入(段与账目保留,悬挂引用归一为未绑定)、更高版本安全拒绝(不写库)。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class) // 绕过 GoodNightApp 真实装配,保持测试封闭
class DataTransferV2Test {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val opened = ArrayList<GoodNightDatabase>()

    /** 每个用例独立库名(带测试类名)+ 先删文件:同沙箱多测试类共用 goodnight.db 会串扰 */
    private fun open(name: String): GoodNightDatabase {
        val file = "DataTransferV2Test_$name.db"
        ctx.deleteDatabase(file)
        return GoodNightDatabase.build(ctx, file).also { opened += it }
    }

    @After fun tearDown() { opened.forEach { it.close() }; opened.clear() }

    /** 真实 v1 备份(无 tasks、会话无 taskId):导入后账目逐值等价、任务为空、绑定全空 */
    @Test fun importV1BackupKeepsAccountsAndLeavesTasksEmpty() = runTest {
        val db = open("v1")
        val counts = DataTransfer.importJson(db, V1_JSON)

        assertEquals(2, counts.profiles)
        assertEquals(1, counts.dailyTotals)
        assertEquals(2, counts.sessions)
        assertEquals(0, counts.tasks)
        assertEquals(5, counts.total) // 2 配置 + 1 日累计 + 2 段 + 0 任务
        assertTrue(db.taskDao().allNow().isEmpty())

        val ps = db.profileDao().getAll()
        assertEquals(listOf(1L, 2L), ps.map { it.id })
        assertEquals(listOf("番茄", "正计时"), ps.map { it.name })
        assertEquals(listOf(25, 45), ps.map { it.workMinutes })
        assertEquals(listOf(5, 10), ps.map { it.restMinutes })
        assertEquals(listOf(10L, 20L), ps.map { it.createdAt })
        assertEquals(listOf(0, 1), ps.map { it.mode })

        val t = db.dailyTotalDao().getAll().single()
        assertEquals("2026-09-01", t.date)
        assertEquals(1L, t.profileId)
        assertEquals(1_500_000L, t.workMillis)
        assertEquals(30L, t.updatedAt)

        val ss = db.focusSessionDao().getAll()
        assertEquals(listOf(1L, 2L), ss.map { it.id })
        assertEquals(listOf(1L, 2L), ss.map { it.profileId })
        assertEquals(listOf(100L, 300L), ss.map { it.startAt })
        assertEquals(listOf(200L, 400L), ss.map { it.endAt })
        assertTrue(ss.all { it.taskId == null })
    }

    /** v2 往返:导出含 tasks 与每段 taskId,导入后任务逐字段一致、绑定保持 */
    @Test fun exportImportV2RoundTripKeepsTasksAndBindings() = runTest {
        val src = open("src")
        val pid = src.profileDao().insert(
            ProfileEntity(name = "番茄", workMinutes = 25, restMinutes = 5, createdAt = 1L, mode = 0),
        )
        src.dailyTotalDao().upsert(DailyTotalEntity("2026-09-01", pid, 600_000L, 2L))
        val repo = TaskRepository(src)
        val active = repo.create("写周报", now = 1L)!!
        val done = repo.create("读书", now = 2L)!!
        repo.setDone(done, true, now = 5L)
        src.focusSessionDao().insertAll(
            listOf(
                FocusSessionEntity(profileId = pid, startAt = 1L, endAt = 2L, taskId = active),
                FocusSessionEntity(profileId = pid, startAt = 3L, endAt = 4L),
            ),
        )

        val json = DataTransfer.exportJson(src)
        val root = JSONObject(json)
        assertEquals(3, root.getInt("version"))
        assertEquals(2, root.getJSONArray("tasks").length())
        assertEquals(active, root.getJSONArray("focusSessions").getJSONObject(0).getLong("taskId"))
        // v2:未绑定的段也写 taskId 键(显式 null),便于外部工具识别 v2 会话结构
        assertTrue(root.getJSONArray("focusSessions").getJSONObject(1).isNull("taskId"))

        val dst = open("roundtrip")
        val counts = DataTransfer.importJson(dst, json)
        assertEquals(1, counts.profiles)
        assertEquals(1, counts.dailyTotals)
        assertEquals(2, counts.sessions)
        assertEquals(2, counts.tasks)
        assertEquals(6, counts.total) // 1 配置 + 1 日累计 + 2 段 + 2 任务

        val tasks = dst.taskDao().allNow().sortedBy { it.id }
        assertEquals(listOf(active, done), tasks.map { it.id })
        assertEquals(listOf("写周报", "读书"), tasks.map { it.title })
        assertFalse(tasks[0].done); assertNull(tasks[0].doneAt)
        assertTrue(tasks[1].done); assertEquals(5L, tasks[1].doneAt)
        assertEquals(listOf(1L, 2L), tasks.map { it.sortOrder })
        assertEquals(listOf(1L, 2L), tasks.map { it.createdAt })

        val ss = dst.focusSessionDao().getAll()
        assertEquals(active, ss[0].taskId)
        assertNull(ss[1].taskId)
        assertEquals(600_000L, dst.dailyTotalDao().getAll().single().workMillis)
        assertEquals("番茄", dst.profileDao().getAll().single().name)
    }

    /**
     * 备份里的段引用了 tasks 中不存在的任务(任务已删的旧备份 / 来自另一台设备):不报错,
     * 段与账目保留,但悬挂的 taskId 在导入事务末尾归一为 null —— 否则它会在之后导入
     * 另一台设备的备份(同 id 是另一个任务)时被静默重绑,历史归属被改写且无提示。
     */
    @Test fun importV2BackupWithDeletedTaskRefStillWorks() = runTest {
        val db = open("orphan")
        val counts = DataTransfer.importJson(db, ORPHAN_JSON)
        assertEquals(0, counts.tasks)
        assertEquals(1, counts.sessions)
        assertTrue(db.taskDao().allNow().isEmpty())
        val s = db.focusSessionDao().getAll().single()
        assertEquals(100L, s.startAt)
        assertEquals(200L, s.endAt)
        assertNull(s.taskId)
    }

    /** 合并而非整库替换:外来任务按 id 入库,库内未涉及的任务保留 */
    @Test fun importMergesTasksWithoutWipingExisting() = runTest {
        val db = open("merge")
        val keep = TaskRepository(db).create("保留", now = 1L)!!
        val counts = DataTransfer.importJson(db, MERGE_JSON)
        assertEquals(1, counts.tasks)
        val tasks = db.taskDao().allNow().sortedBy { it.id }
        assertEquals(listOf(keep, 100L), tasks.map { it.id })
        assertEquals("外来", tasks[1].title)
        assertEquals(3L, tasks[1].sortOrder)
    }

    /** 更高版本(本机读不懂的未来格式)安全拒绝:不写任何行,不静默丢数据 */
    @Test fun importRejectsNewerFormatVersion() = runTest {
        val db = open("v4")
        val err = runCatching { DataTransfer.importJson(db, V4_JSON) }.exceptionOrNull()
        assertTrue("应抛 IllegalArgumentException,实际 $err", err is IllegalArgumentException)
        assertTrue(db.profileDao().getAll().isEmpty())
        assertTrue(db.taskDao().allNow().isEmpty())
        assertTrue(db.focusSessionDao().getAll().isEmpty())
    }

    private companion object {
        const val V1_JSON = """{"version":1,"exportedAt":111,
            "profiles":[
              {"id":1,"name":"番茄","workMinutes":25,"restMinutes":5,"createdAt":10,"mode":0},
              {"id":2,"name":"正计时","workMinutes":45,"restMinutes":10,"createdAt":20,"mode":1}],
            "dailyTotals":[{"date":"2026-09-01","profileId":1,"workMillis":1500000,"updatedAt":30}],
            "focusSessions":[{"id":1,"profileId":1,"startAt":100,"endAt":200},
                             {"id":2,"profileId":2,"startAt":300,"endAt":400}]}"""

        const val ORPHAN_JSON = """{"version":2,"exportedAt":1,"profiles":[],"dailyTotals":[],"tasks":[],
            "focusSessions":[{"id":1,"profileId":1,"startAt":100,"endAt":200,"taskId":99}]}"""

        const val MERGE_JSON = """{"version":2,"exportedAt":1,"profiles":[],"dailyTotals":[],
            "tasks":[{"id":100,"title":"外来","done":0,"createdAt":7,"doneAt":null,"sortOrder":3}],
            "focusSessions":[]}"""

        const val V4_JSON = """{"version":4,"exportedAt":1,"profiles":[
            {"id":1,"name":"未来","workMinutes":25,"restMinutes":5,"createdAt":1,"mode":0}],
            "dailyTotals":[],"tasks":[],"focusSessions":[]}"""
    }
}

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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v2.2 Task 2:备份 v3(`profile.taskId` + `profile.archived`)与旧版兼容。
 *
 * 覆盖:v3 往返逐值一致(含归档行)、v2 导入后时钟全为通用而段归属照旧、v1 导入后时钟全为通用、
 * 更高版本安全拒绝且零写入。导入后归一化与作用域去重见 [DataTransferV3NormalizeTest]。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class) // 绕过 GoodNightApp 真实装配,保持测试封闭
class DataTransferV3Test {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val opened = ArrayList<GoodNightDatabase>()

    /** 每个用例独立库名(带测试类名)+ 先删文件:同沙箱多测试类共用 goodnight.db 会串扰 */
    private fun open(name: String): GoodNightDatabase {
        val file = "DataTransferV3Test_$name.db"
        ctx.deleteDatabase(file)
        return GoodNightDatabase.build(ctx, file).also { opened += it }
    }

    @After fun tearDown() { opened.forEach { it.close() }; opened.clear() }

    /** v3 往返:`taskId` 与 `archived` 逐值一致(通用写显式 null),账目与会话不变 */
    @Test fun exportImportV3RoundTripKeepsTaskIdAndArchived() = runTest {
        val src = open("src")
        val task = TaskRepository(src).create("写周报", now = 1L)!!
        val generic = src.profileDao().insert(
            ProfileEntity(name = "番茄", workMinutes = 25, restMinutes = 5, createdAt = 1L),
        )
        val bound = src.profileDao().insert(
            ProfileEntity(
                name = "专注", workMinutes = 50, restMinutes = 10, createdAt = 2L,
                mode = 1, taskId = task,
            ),
        )
        val archived = src.profileDao().insert(
            ProfileEntity(name = "旧时钟", workMinutes = 15, restMinutes = 3, createdAt = 3L, archived = true),
        )
        src.dailyTotalDao().upsert(DailyTotalEntity("2026-09-02", bound, 600_000L, 7L))
        src.focusSessionDao().insertAll(
            listOf(FocusSessionEntity(profileId = bound, startAt = 10L, endAt = 20L, taskId = task)),
        )

        val json = DataTransfer.exportJson(src)
        assertEquals(3, JSONObject(json).getInt("version"))
        val pArr = JSONObject(json).getJSONArray("profiles")
        assertTrue(pArr.getJSONObject(0).isNull("taskId")) // 通用:键恒在、显式 null
        assertEquals(0, pArr.getJSONObject(0).getInt("archived"))
        assertEquals(task, pArr.getJSONObject(1).getLong("taskId"))
        assertEquals(0, pArr.getJSONObject(1).getInt("archived"))
        assertEquals(1, pArr.getJSONObject(2).getInt("archived"))

        val dst = open("roundtrip")
        val counts = DataTransfer.importJson(dst, json)
        assertEquals(3, counts.profiles)
        assertEquals(1, counts.dailyTotals)
        assertEquals(1, counts.sessions)
        assertEquals(1, counts.tasks)
        assertEquals(6, counts.total) // 3 配置 + 1 日累计 + 1 段 + 1 任务

        val ps = dst.profileDao().getAll()
        assertEquals(listOf(generic, bound, archived), ps.map { it.id })
        assertEquals(listOf("番茄", "专注", "旧时钟"), ps.map { it.name })
        assertEquals(listOf<Long?>(null, task, null), ps.map { it.taskId })
        assertEquals(listOf(false, false, true), ps.map { it.archived })
        assertEquals(listOf(25, 50, 15), ps.map { it.workMinutes })
        assertEquals(600_000L, dst.dailyTotalDao().getAll().single().workMillis)
        val s = dst.focusSessionDao().getAll().single()
        assertEquals(task, s.taskId)
        assertEquals(10L, s.startAt)
    }

    /** 任务作用域内的归档行:往返后 taskId/archived 都保留,且归档行不占名(同名活跃行保持原名) */
    @Test fun roundTripKeepsArchivedClockInTaskScope() = runTest {
        val src = open("archived_task")
        val task = TaskRepository(src).create("写周报", now = 1L)!!
        val archived = src.profileDao().insert(
            ProfileEntity(
                name = "专注", workMinutes = 50, restMinutes = 10, createdAt = 1L,
                taskId = task, archived = true,
            ),
        )
        val active = src.profileDao().insert(
            ProfileEntity(name = "专注", workMinutes = 25, restMinutes = 5, createdAt = 2L, taskId = task),
        )

        val json = DataTransfer.exportJson(src)
        val dst = open("archived_task_rt")
        DataTransfer.importJson(dst, json)

        val ps = dst.profileDao().getAll()
        assertEquals(listOf(archived, active), ps.map { it.id })
        assertEquals(listOf(task, task), ps.map { it.taskId })
        assertEquals(listOf(true, false), ps.map { it.archived })
        assertEquals(listOf("专注", "专注"), ps.map { it.name }) // 归档行不占名,活跃行不加后缀
    }

    /** v2(无这两列)导入不报错:时钟全部成为通用并视为未归档,账目与会话逐值不变 */
    @Test fun importV2BackupMakesAllClocksGeneric() = runTest {
        val db = open("v2")
        val counts = DataTransfer.importJson(db, V2_JSON)

        assertEquals(2, counts.profiles)
        val ps = db.profileDao().getAll()
        assertEquals(listOf("番茄", "专注"), ps.map { it.name })
        assertTrue(ps.all { it.taskId == null })
        assertTrue(ps.all { !it.archived })
        assertEquals(listOf(25, 50), ps.map { it.workMinutes })
        assertEquals(listOf(0, 1), ps.map { it.mode })

        val t = db.dailyTotalDao().getAll().single()
        assertEquals("2026-09-01", t.date)
        assertEquals(1L, t.profileId)
        assertEquals(1_500_000L, t.workMillis)
        assertEquals(30L, t.updatedAt)

        val ss = db.focusSessionDao().getAll()
        assertEquals(listOf(100L, 300L), ss.map { it.startAt })
        assertEquals(listOf(200L, 400L), ss.map { it.endAt })
        assertEquals(5L, ss[0].taskId) // v2 段归属照旧保留(不因时钟变通用而改动)
        assertNull(ss[1].taskId)
    }

    /** v1(无 tasks、无 taskId/archived)导入不报错:时钟全通用,账目逐值不变 */
    @Test fun importV1BackupMakesAllClocksGeneric() = runTest {
        val db = open("v1")
        DataTransfer.importJson(db, V1_JSON)

        val ps = db.profileDao().getAll()
        assertEquals(listOf(1L, 2L), ps.map { it.id })
        assertTrue(ps.all { it.taskId == null })
        assertTrue(ps.all { !it.archived })
        assertEquals(listOf(1_500_000L), db.dailyTotalDao().getAll().map { it.workMillis })
        val ss = db.focusSessionDao().getAll()
        assertEquals(listOf(100L, 300L), ss.map { it.startAt })
        assertTrue(ss.all { it.taskId == null })
    }

    /** 更高版本(本机读不懂的未来格式)安全拒绝:四表零写入 */
    @Test fun importRejectsNewerFormatVersion() = runTest {
        val db = open("v4")
        val err = runCatching { DataTransfer.importJson(db, V4_JSON) }.exceptionOrNull()
        assertTrue("应抛 IllegalArgumentException,实际 $err", err is IllegalArgumentException)
        assertEquals(3, DataTransfer.FORMAT_VERSION)
        assertTrue(db.profileDao().getAll().isEmpty())
        assertTrue(db.taskDao().allNow().isEmpty())
        assertTrue(db.dailyTotalDao().getAll().isEmpty())
        assertTrue(db.focusSessionDao().getAll().isEmpty())
    }

    private companion object {
        const val V1_JSON = """{"version":1,"exportedAt":1,
            "profiles":[
              {"id":1,"name":"番茄","workMinutes":25,"restMinutes":5,"createdAt":10,"mode":0},
              {"id":2,"name":"正计时","workMinutes":45,"restMinutes":10,"createdAt":20,"mode":1}],
            "dailyTotals":[{"date":"2026-09-01","profileId":1,"workMillis":1500000,"updatedAt":30}],
            "focusSessions":[{"id":1,"profileId":1,"startAt":100,"endAt":200},
                             {"id":2,"profileId":2,"startAt":300,"endAt":400}]}"""

        const val V2_JSON = """{"version":2,"exportedAt":1,
            "profiles":[
              {"id":1,"name":"番茄","workMinutes":25,"restMinutes":5,"createdAt":10,"mode":0},
              {"id":2,"name":"专注","workMinutes":50,"restMinutes":10,"createdAt":20,"mode":1}],
            "dailyTotals":[{"date":"2026-09-01","profileId":1,"workMillis":1500000,"updatedAt":30}],
            "tasks":[{"id":5,"title":"写周报","done":0,"doneAt":null,"createdAt":1,"sortOrder":1}],
            "focusSessions":[{"id":1,"profileId":1,"startAt":100,"endAt":200,"taskId":5},
                             {"id":2,"profileId":2,"startAt":300,"endAt":400,"taskId":null}]}"""

        const val V4_JSON = """{"version":4,"exportedAt":1,"profiles":[
            {"id":1,"name":"未来","workMinutes":25,"restMinutes":5,"createdAt":1,"mode":0,"taskId":null,"archived":0}],
            "dailyTotals":[],"tasks":[],"focusSessions":[]}"""
    }
}

package com.goodnight.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.goodnight.data.db.GoodNightDatabase
import com.goodnight.data.db.ProfileEntity
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
 * v2.2 Task 2:导入后的归一化与作用域去重(审查点名项)。
 *
 * 备份可能带来同一作用域的同名**活跃**时钟(旧备份里两个任务下同名、或手改过的文件),
 * 而 DB 没有唯一索引 —— 导入走 upsertAll 就会破坏「作用域内唯一」。策略与
 * [TaskRepository.deleteTask] 同套:保留先到者,后者按数字后缀改名。
 * 先划定的优先顺序:**库内既存行 > 同批导入行**,同批内以 (createdAt, id) 判先到
 * (与库内列表排序同口径,不依赖文件行序);归档行不占名。
 * 文件里缺 `id` 的行(入库被 autoGenerate 分到新 id)也算同批导入行 —— 否则它们会抢名,
 * 库里更老的行反被改名,同一作用域留下两个同名活跃时钟(见 [importIdentifiesRowsWithoutIdAsBatch])。
 * 归一化(悬挂 `profile.taskId` 归为通用)必须排在去重之前,否则归为通用后新撞出的同名
 * 会漏掉(见 [importDedupesGenericCollisionCreatedByNormalization])。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class) // 绕过 GoodNightApp 真实装配,保持测试封闭
class DataTransferV3NormalizeTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val opened = ArrayList<GoodNightDatabase>()

    /** 每个用例独立库名(带测试类名)+ 先删文件:同沙箱多测试类共用 goodnight.db 会串扰 */
    private fun open(name: String): GoodNightDatabase {
        val file = "DataTransferV3NormalizeTest_$name.db"
        ctx.deleteDatabase(file)
        return GoodNightDatabase.build(ctx, file).also { opened += it }
    }

    @After fun tearDown() { opened.forEach { it.close() }; opened.clear() }

    /** 悬挂 `profile.taskId`(任务不在库中)归一为通用;有效引用不受影响 */
    @Test fun importNormalizesDanglingProfileTaskRef() = runTest {
        val db = open("dangling")
        DataTransfer.importJson(db, DANGLING_JSON)

        val ps = db.profileDao().getAll().sortedBy { it.id }
        assertEquals(listOf("有效引用", "孤影"), ps.map { it.name })
        assertEquals(7L, ps[0].taskId)
        assertNull(ps[1].taskId)
        assertFalse(ps[1].archived)
    }

    /** 同批导入的同作用域同名活跃时钟:保留文件里先到者,后者加数字后缀 */
    @Test fun importDedupesSameNameActiveClocksInScope() = runTest {
        val db = open("dup")
        DataTransfer.importJson(db, DUP_IN_FILE_JSON)

        val ps = db.profileDao().getAll().sortedBy { it.id }
        // 同一通用作用域:id 1 保留,id 2 改名;不同任务作用域各自独立,互不影响
        assertEquals(listOf("专注", "专注 (2)", "专注", "专注"), ps.map { it.name })
        assertEquals(listOf<Long?>(null, null, 10L, 11L), ps.map { it.taskId })
        assertEquals(listOf(25, 50, 15, 20), ps.map { it.workMinutes })
        assertEquals(listOf(1L, 2L, 3L, 4L), ps.map { it.id }) // 只改名字,不新增/删除行
    }

    /** 先到者按 (createdAt, id) 判定,与文件行序无关:文件里后出现的旧时钟保留原名 */
    @Test fun importDedupKeepsOlderClockNameRegardlessOfFileOrder() = runTest {
        val db = open("order")
        DataTransfer.importJson(db, REVERSED_ORDER_JSON)

        val ps = db.profileDao().getAll().sortedBy { it.id }
        // 文件里 id 2 在前,但它的 createdAt 更早 -> id 2 保留「专注」,id 1 改名
        assertEquals(listOf("专注 (2)", "专注"), ps.map { it.name })
        assertEquals(listOf(1L, 2L), ps.map { it.id })
        assertEquals(listOf(20L, 10L), ps.map { it.createdAt })
    }

    /** 归一化在建名之前:悬挂 taskId 行归为通用后与该层既有同名时钟去重 */
    @Test fun importDedupesGenericCollisionCreatedByNormalization() = runTest {
        val db = open("dup_after_norm")
        DataTransfer.importJson(db, NORM_COLLIDE_JSON)

        val ps = db.profileDao().getAll().sortedBy { it.id }
        assertEquals(listOf("专注", "专注 (2)"), ps.map { it.name })
        assertEquals(listOf<Long?>(null, null), ps.map { it.taskId })
    }

    /** 库内既存行优先占名:导入的同名活跃时钟改名;既存行与归档行名字都不动 */
    @Test fun importDedupesAgainstExistingRowAndSkipsArchived() = runTest {
        val db = open("existing")
        val existing = db.profileDao().insert(
            ProfileEntity(name = "专注", workMinutes = 25, restMinutes = 5, createdAt = 1L),
        )
        DataTransfer.importJson(db, EXISTING_COLLIDE_JSON)

        val ps = db.profileDao().getAll().sortedBy { it.id }
        assertEquals(listOf("专注", "专注 (2)", "专注"), ps.map { it.name })
        assertEquals(existing, ps[0].id)
        assertEquals(25, ps[0].workMinutes)
        assertFalse(ps[0].archived)
        assertEquals(50, ps[1].workMinutes)
        assertTrue(ps[2].archived) // 归档行不占名,保持原名
    }

    /** 后缀避让库内既有名字:库内已有「专注 (2)」时导入的重复行跳到「专注 (3)」 */
    @Test fun importDedupSuffixSkipsNamesAlreadyTaken() = runTest {
        val db = open("suffix")
        db.profileDao().insert(
            ProfileEntity(name = "专注", workMinutes = 25, restMinutes = 5, createdAt = 1L),
        )
        db.profileDao().insert(
            ProfileEntity(name = "专注 (2)", workMinutes = 30, restMinutes = 6, createdAt = 2L),
        )
        DataTransfer.importJson(db, EXISTING_COLLIDE_JSON)

        val ps = db.profileDao().getAll().sortedBy { it.id }
        assertEquals(listOf("专注", "专注 (2)", "专注 (3)", "专注"), ps.map { it.name })
    }

    /**
     * 文件里缺 `id` 的行(解析为 0,入库被 autoGenerate 分到新 id)必须仍算本批导入行:
     * 否则它们被当成库内既存行去先占名,同一作用域留下两个同名活跃时钟、库里更老的行反被改名。
     */
    @Test fun importIdentifiesRowsWithoutIdAsBatch() = runTest {
        val db = open("missing_id")
        val existing = db.profileDao().insert(
            ProfileEntity(name = "专注", workMinutes = 25, restMinutes = 5, createdAt = 1L),
        )
        DataTransfer.importJson(db, MISSING_ID_JSON)

        val ps = db.profileDao().getAll().sortedBy { it.id }
        assertEquals(listOf("专注", "专注 (2)", "专注 (3)"), ps.map { it.name })
        assertEquals(existing, ps[0].id) // 库内既存(更老)行保留原名
        assertEquals(listOf(25, 50, 15), ps.map { it.workMinutes })
        // 同作用域只剩一个活跃的「专注」,且是既存的那行在占名
        assertEquals(existing, db.profileDao().byNameInScope("专注", null)!!.id)
    }

    private companion object {
        const val DANGLING_JSON = """{"version":3,"exportedAt":1,
            "profiles":[
              {"id":1,"name":"有效引用","workMinutes":25,"restMinutes":5,"createdAt":1,"mode":0,"taskId":7,"archived":0},
              {"id":2,"name":"孤影","workMinutes":30,"restMinutes":6,"createdAt":2,"mode":0,"taskId":99,"archived":0}],
            "dailyTotals":[],
            "tasks":[{"id":7,"title":"写周报","done":0,"doneAt":null,"createdAt":1,"sortOrder":1}],
            "focusSessions":[]}"""

        const val DUP_IN_FILE_JSON = """{"version":3,"exportedAt":1,
            "profiles":[
              {"id":1,"name":"专注","workMinutes":25,"restMinutes":5,"createdAt":1,"mode":0,"taskId":null,"archived":0},
              {"id":2,"name":"专注","workMinutes":50,"restMinutes":10,"createdAt":2,"mode":1,"taskId":null,"archived":0},
              {"id":3,"name":"专注","workMinutes":15,"restMinutes":3,"createdAt":3,"mode":0,"taskId":10,"archived":0},
              {"id":4,"name":"专注","workMinutes":20,"restMinutes":4,"createdAt":4,"mode":0,"taskId":11,"archived":0}],
            "dailyTotals":[],
            "tasks":[{"id":10,"title":"A","done":0,"doneAt":null,"createdAt":1,"sortOrder":1},
                     {"id":11,"title":"B","done":0,"doneAt":null,"createdAt":2,"sortOrder":2}],
            "focusSessions":[]}"""

        const val NORM_COLLIDE_JSON = """{"version":3,"exportedAt":1,
            "profiles":[
              {"id":1,"name":"专注","workMinutes":25,"restMinutes":5,"createdAt":1,"mode":0,"taskId":null,"archived":0},
              {"id":2,"name":"专注","workMinutes":50,"restMinutes":10,"createdAt":2,"mode":0,"taskId":99,"archived":0}],
            "dailyTotals":[],"tasks":[],"focusSessions":[]}"""

        const val EXISTING_COLLIDE_JSON = """{"version":3,"exportedAt":1,
            "profiles":[
              {"id":50,"name":"专注","workMinutes":50,"restMinutes":10,"createdAt":1,"mode":0,"taskId":null,"archived":0},
              {"id":51,"name":"专注","workMinutes":15,"restMinutes":3,"createdAt":2,"mode":0,"taskId":null,"archived":1}],
            "dailyTotals":[],"tasks":[],"focusSessions":[]}"""

        const val REVERSED_ORDER_JSON = """{"version":3,"exportedAt":1,
            "profiles":[
              {"id":1,"name":"专注","workMinutes":25,"restMinutes":5,"createdAt":20,"mode":0,"taskId":null,"archived":0},
              {"id":2,"name":"专注","workMinutes":50,"restMinutes":10,"createdAt":10,"mode":0,"taskId":null,"archived":0}],
            "dailyTotals":[],"tasks":[],"focusSessions":[]}"""

        /** 两行都缺 `id`(与库内既存行同作用域同名),行序即 createdAt 升序 */
        const val MISSING_ID_JSON = """{"version":3,"exportedAt":1,
            "profiles":[
              {"name":"专注","workMinutes":50,"restMinutes":10,"createdAt":2,"mode":0,"taskId":null,"archived":0},
              {"name":"专注","workMinutes":15,"restMinutes":3,"createdAt":3,"mode":0,"taskId":null,"archived":0}],
            "dailyTotals":[],"tasks":[],"focusSessions":[]}"""
    }
}

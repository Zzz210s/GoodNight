package com.goodnight.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.goodnight.data.db.GoodNightDatabase
import com.goodnight.data.db.ProfileEntity
import com.goodnight.data.db.ProfileMode
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
 * v2.2 Task 1:schema v4 -> v5(`profile.taskId` + `profile.archived` + 索引重建)。
 *
 * 手工构造 v4 库文件(逐字取自 Room v4 生成的 DDL),插入存量数据后经注册的 MIGRATION_4_5
 * 升级,断言:存量时钟全部成为通用时钟、其余字段逐值不变;focus_session / daily_total
 * 行数数值不动;`index_profile_name` 去掉唯一约束、新增 `index_profile_taskId`。
 *
 * 索引必须直查 sqlite_master:Robolectric 的 legacy SQLite 没有 index_xinfo/origin 列,
 * Room 打开时会跳过索引校验(索引漂移在单测里是假绿),真机/模拟器实测另见 Task 7。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ProfileMigrationV5Test {
    private val dbName = "goodnight-migration-v5.db"
    private var db: GoodNightDatabase? = null

    @After fun tearDown() {
        db?.close()
        db = null
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(dbName)
    }

    /** Room v4 的完整表结构(4 张表 + 2 个索引),供手工建旧库 */
    private val v4Ddl = listOf(
        "CREATE TABLE IF NOT EXISTS `profile` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`name` TEXT NOT NULL, `workMinutes` INTEGER NOT NULL, `restMinutes` INTEGER NOT NULL, " +
            "`createdAt` INTEGER NOT NULL, `mode` INTEGER NOT NULL DEFAULT 0)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_profile_name` ON `profile` (`name`)",
        "CREATE TABLE IF NOT EXISTS `daily_total` (`date` TEXT NOT NULL, `profileId` INTEGER NOT NULL, " +
            "`workMillis` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`date`, `profileId`))",
        "CREATE TABLE IF NOT EXISTS `focus_session` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`profileId` INTEGER NOT NULL, `startAt` INTEGER NOT NULL, `endAt` INTEGER NOT NULL, `taskId` INTEGER)",
        "CREATE INDEX IF NOT EXISTS `index_focus_session_taskId` ON `focus_session` (`taskId`)",
        "CREATE TABLE IF NOT EXISTS `task` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`title` TEXT NOT NULL, `done` INTEGER NOT NULL DEFAULT 0, `createdAt` INTEGER NOT NULL, " +
            "`doneAt` INTEGER, `sortOrder` INTEGER NOT NULL)",
        "CREATE INDEX IF NOT EXISTS `index_task_done_sortOrder` ON `task` (`done`, `sortOrder`)",
    )

    private fun buildV4(ctx: Context) {
        val v4 = SQLiteDatabase.openOrCreateDatabase(ctx.getDatabasePath(dbName).absolutePath, null)
        v4Ddl.forEach { v4.execSQL(it) }
        v4.execSQL(
            "INSERT INTO `profile` (`name`,`workMinutes`,`restMinutes`,`createdAt`,`mode`) VALUES " +
                "('番茄',25,5,1700000000000,0),('深度',50,10,1700000001000,1),('阅读',45,15,1700000002000,0)"
        )
        v4.execSQL(
            "INSERT INTO `task` (`title`,`done`,`createdAt`,`doneAt`,`sortOrder`) " +
                "VALUES ('写周报',0,1700000003000,NULL,1)"
        )
        v4.execSQL(
            "INSERT INTO `focus_session` (`profileId`,`startAt`,`endAt`,`taskId`) VALUES " +
                "(1,1700000004000,1700000010000,NULL),(1,1700000010000,1700000016000,1)"
        )
        v4.execSQL(
            "INSERT INTO `daily_total` (`date`,`profileId`,`workMillis`,`updatedAt`) VALUES " +
                "('2026-09-24',1,3600000,1700000020000),('2026-09-25',2,1800000,1700000030000)"
        )
        v4.version = 4 // PRAGMA user_version:Room 据此走 onUpgrade
        v4.close()
    }

    private fun profileIndexSql(): Map<String, String> =
        db!!.openHelper.readableDatabase
            .query("SELECT name, sql FROM sqlite_master WHERE type = 'index' AND tbl_name = 'profile'")
            .use { c -> buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1) ?: "") } }

    /** 列名 -> 该列的 dflt_value(无默认值为 null):与 Room 迁移后的逐列校验同口径 */
    private fun profileColumns(): Map<String, String?> =
        db!!.openHelper.readableDatabase.query("PRAGMA table_info(`profile`)").use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(1), c.getString(4)) }
        }

    @Test fun upgradeV4KeepsDataAndAddsTaskScopeColumns() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        buildV4(ctx)

        db = GoodNightDatabase.build(ctx, dbName)
        assertEquals("迁移链落在 v5", 5, db!!.openHelper.writableDatabase.version)

        val rows = db!!.profileDao().getAll()
        assertEquals(3, rows.size)
        assertEquals(
            "存量时钟逐值不变",
            listOf("番茄" to 25, "深度" to 50, "阅读" to 45),
            rows.map { it.name to it.workMinutes },
        )
        assertEquals(listOf(5, 10, 15), rows.map { it.restMinutes })
        assertEquals(
            listOf(1700000000000L, 1700000001000L, 1700000002000L),
            rows.map { it.createdAt },
        )
        assertEquals(
            listOf(ProfileMode.COUNTDOWN, ProfileMode.COUNTUP, ProfileMode.COUNTDOWN),
            rows.map { it.mode },
        )
        rows.forEach {
            assertNull("升级来的时钟都是通用时钟", it.taskId)
            assertFalse("升级来的时钟都未归档", it.archived)
        }

        val segs = db!!.focusSessionDao().getAll()
        assertEquals(2, segs.size)
        assertEquals(listOf(1700000004000L, 1700000010000L), segs.map { it.startAt })
        assertEquals(listOf(1700000010000L, 1700000016000L), segs.map { it.endAt })
        assertNull(segs[0].taskId)
        assertEquals(1L, segs[1].taskId)

        val totals = db!!.dailyTotalDao().getAll()
        assertEquals(2, totals.size)
        assertEquals(listOf("2026-09-24" to 3_600_000L, "2026-09-25" to 1_800_000L), totals.map { it.date to it.workMillis })

        val indexSql = profileIndexSql()
        assertTrue("name 索引仍在", indexSql.containsKey("index_profile_name"))
        assertFalse("name 不再是全局唯一", indexSql.getValue("index_profile_name").contains("UNIQUE"))
        assertTrue("新增 taskId 索引", indexSql.containsKey("index_profile_taskId"))
        assertTrue(indexSql.getValue("index_profile_taskId").contains("`taskId`"))

        val cols = profileColumns()
        assertTrue("taskId 列存在", cols.containsKey("taskId"))
        assertNull("taskId 无默认值(存量行 NULL)", cols["taskId"])
        assertEquals("archived 默认值必须与实体声明一致", "0", cols["archived"])

        // 非唯一索引生效:同名行可以直接落库(唯一性交给仓库层)
        db!!.profileDao().insert(
            ProfileEntity(name = "番茄", workMinutes = 25, restMinutes = 5, createdAt = 1700000004000)
        )
        assertEquals(4, db!!.profileDao().count())
    }
}

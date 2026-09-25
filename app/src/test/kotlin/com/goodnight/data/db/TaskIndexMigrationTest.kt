package com.goodnight.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v2.1 Task 1 修复:迁移库的索引必须与实体声明一致。
 *
 * 背景:Robolectric 的 legacy SQLite 没有 `index_xinfo`/`origin` 列,Room 会跳过索引校验,
 * [TaskMigrationTest] 因此对"索引漂移"是假绿。真机上 `MIGRATION_3_4` 建了
 * `index_focus_session_taskId` 而 `FocusSessionEntity` 没声明(或反过来),Room 打开
 * 迁移库会抛 "Migration didn't properly handle" —— 老用户升级后开库即崩。
 * 本测试直查 sqlite_master + PRAGMA index_info,并以"新建库"为基准比对索引集合。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class TaskIndexMigrationTest {
    private val dbName = "goodnight-index-migration.db"
    private val freshDbName = "goodnight-index-migration-fresh.db"
    private var db: GoodNightDatabase? = null

    /** Room v3 生成的 DDL(与 TaskMigrationTest / ModeMigrationTest 同一份) */
    private val v3ProfileDdl =
        "CREATE TABLE IF NOT EXISTS `profile` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`name` TEXT NOT NULL, `workMinutes` INTEGER NOT NULL, `restMinutes` INTEGER NOT NULL, " +
            "`createdAt` INTEGER NOT NULL, `mode` INTEGER NOT NULL DEFAULT 0)"
    private val v3ProfileIndexDdl = "CREATE UNIQUE INDEX IF NOT EXISTS `index_profile_name` ON `profile` (`name`)"
    private val v3DailyDdl =
        "CREATE TABLE IF NOT EXISTS `daily_total` (`date` TEXT NOT NULL, " +
            "`profileId` INTEGER NOT NULL, `workMillis` INTEGER NOT NULL, " +
            "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`date`, `profileId`))"
    private val v3SessionDdl =
        "CREATE TABLE IF NOT EXISTS `focus_session` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`profileId` INTEGER NOT NULL, `startAt` INTEGER NOT NULL, `endAt` INTEGER NOT NULL)"

    @After fun tearDown() {
        db?.close()
        db = null
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.deleteDatabase(dbName)
        ctx.deleteDatabase(freshDbName)
    }

    /**
     * 手工建 v3 库 -> 经 MIGRATION_3_4/4_5 升到 v5:两个索引必须存在且列/列序正确,
     * 并且索引集合与新建库完全一致(少建=真机崩溃,多建=schema 分叉)。
     */
    @Test fun migration3To4CreatesIndexesDeclaredByEntities() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val v3 = SQLiteDatabase.openOrCreateDatabase(ctx.getDatabasePath(dbName).absolutePath, null)
        v3.execSQL(v3ProfileDdl)
        v3.execSQL(v3ProfileIndexDdl)
        v3.execSQL(v3DailyDdl)
        v3.execSQL(v3SessionDdl)
        v3.version = 3
        v3.execSQL("INSERT INTO `focus_session` (`profileId`, `startAt`, `endAt`) VALUES (1, 1000, 2000)")
        v3.close()

        db = Room.databaseBuilder(ctx, GoodNightDatabase::class.java, dbName)
            .addMigrations(
                GoodNightDatabase.MIGRATION_1_2,
                GoodNightDatabase.MIGRATION_2_3,
                GoodNightDatabase.MIGRATION_3_4,
                GoodNightDatabase.MIGRATION_4_5,
            )
            .build()

        assertNotNull(
            "迁移后缺 index_focus_session_taskId",
            indexColumns("index_focus_session_taskId"),
        )
        assertEquals(listOf("taskId"), indexColumns("index_focus_session_taskId"))
        assertNotNull(
            "迁移后缺 index_task_done_sortOrder",
            indexColumns("index_task_done_sortOrder"),
        )
        assertEquals(listOf("done", "sortOrder"), indexColumns("index_task_done_sortOrder"))
        // v2.2 Task 1:profile 的索引同样必须与实体声明一致(name 去唯一 + 新增 taskId)
        assertNotNull("迁移后缺 index_profile_taskId", indexColumns("index_profile_taskId"))
        assertEquals(listOf("taskId"), indexColumns("index_profile_taskId"))
        assertEquals(listOf("name"), indexColumns("index_profile_name"))

        val fresh = GoodNightDatabase.build(ctx, freshDbName)
        try {
            val migrated = db!!.openHelper.writableDatabase
            val created = fresh.openHelper.writableDatabase
            for (table in listOf("focus_session", "task", "profile")) {
                assertEquals(
                    "表 $table 的索引集合与新建库不一致",
                    indexNames(created, table),
                    indexNames(migrated, table),
                )
            }
        } finally {
            fresh.close()
        }
    }

    /** 索引存在则返回列名(按序),不存在返回 null;legacy SQLite 也支持 index_info */
    private fun indexColumns(name: String): List<String>? {
        val sqlite = db!!.openHelper.writableDatabase
        val exists = sqlite.query("SELECT 1 FROM sqlite_master WHERE type = 'index' AND name = '$name'")
            .use { it.moveToFirst() }
        if (!exists) return null
        return sqlite.query("PRAGMA index_info(`$name`)").use { c ->
            val cols = mutableListOf<Pair<Int, String>>()
            while (c.moveToNext()) cols.add(c.getInt(0) to c.getString(2))
            cols.sortedBy { it.first }.map { it.second }
        }
    }

    /** 某表的具名索引名集合(自动索引 sql IS NULL,天然排除) */
    private fun indexNames(database: SupportSQLiteDatabase, table: String): Set<String> =
        database.query(
            "SELECT name FROM sqlite_master " +
                "WHERE type = 'index' AND sql IS NOT NULL AND tbl_name = '$table'"
        ).use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(0)) }
        }
}

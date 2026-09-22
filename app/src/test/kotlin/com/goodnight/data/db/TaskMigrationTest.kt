package com.goodnight.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v2.1 Task 1:schema v3 -> v4(新增 `task` 表 + `focus_session.taskId` 可空列)。
 *
 * Room 的 databaseBuilder 总是按当前类版本建库,无法"先建 v3 再升 v4",
 * 故沿用 [com.goodnight.data.ModeMigrationTest] 的做法:手工构造 v3 库文件
 * (v3 生成的原始 DDL)并置 user_version=3,再由注册了 MIGRATION_3_4 的开库触发升级。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class TaskMigrationTest {
    /** 独立库名,避免同一 Robolectric 沙箱内多个测试类共享 goodnight.db 造成串扰 */
    private val dbName = "goodnight-task-migration.db"
    private var db: GoodNightDatabase? = null

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
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(dbName)
    }

    /** 手工建 v3 库 + 插旧数据 -> 开 v4(触发 3->4):旧行逐值保留,task 表可用 */
    @Test fun migration3To4KeepsOldRowsAndAddsTaskTable() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.deleteDatabase(dbName)
        val path = ctx.getDatabasePath(dbName).absolutePath
        val v3 = SQLiteDatabase.openOrCreateDatabase(path, null)
        v3.execSQL(v3ProfileDdl)
        v3.execSQL(v3ProfileIndexDdl)
        v3.execSQL(v3DailyDdl)
        v3.execSQL(v3SessionDdl)
        v3.version = 3
        v3.execSQL(
            "INSERT INTO `profile` (`name`, `workMinutes`, `restMinutes`, `createdAt`, `mode`) " +
                "VALUES ('写作', 50, 10, 1700000000000, 1)"
        )
        v3.execSQL("INSERT INTO `focus_session` (`profileId`, `startAt`, `endAt`) VALUES (1, 1000, 2000)")
        v3.close()

        db = Room.databaseBuilder(ctx, GoodNightDatabase::class.java, dbName)
            .addMigrations(
                GoodNightDatabase.MIGRATION_1_2,
                GoodNightDatabase.MIGRATION_2_3,
                GoodNightDatabase.MIGRATION_3_4,
            )
            .build()

        // 旧行逐值保留
        val profile = db!!.profileDao().getAll().single()
        assertEquals("写作", profile.name)
        assertEquals(50, profile.workMinutes)
        assertEquals(ProfileMode.COUNTUP, profile.mode)
        val rows = db!!.focusSessionDao().between(0, 9999)
        assertEquals(1, rows.size)
        assertEquals(1L, rows.first().profileId)
        assertEquals(1000L, rows.first().startAt)
        assertEquals(2000L, rows.first().endAt)
        assertNull("旧段无任务绑定", rows.first().taskId)

        // task 表可用
        val id = db!!.taskDao().insert(TaskEntity(title = "写周报", createdAt = 5, sortOrder = 1))
        assertTrue(id > 0)
        assertEquals(1, db!!.taskDao().observeActive().first().size)
    }

    /** 升级后的 v4 库:任务 DAO 全部查询/更新可用(taskId 可写可清空) */
    @Test fun taskDaoWorksOnMigratedDatabase() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.deleteDatabase(dbName)
        val path = ctx.getDatabasePath(dbName).absolutePath
        val v3 = SQLiteDatabase.openOrCreateDatabase(path, null)
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
            )
            .build()
        val dao = db!!.taskDao()

        val a = dao.insert(TaskEntity(title = "写周报", createdAt = 1, sortOrder = 2))
        val b = dao.insert(TaskEntity(title = "读论文", createdAt = 2, sortOrder = 1))
        assertEquals(2L, dao.maxSortOrder())
        assertEquals(listOf(b, a), dao.observeActive().first().map { it.id })

        dao.updateSortOrder(a, 0)
        assertEquals(listOf(a, b), dao.observeActive().first().map { it.id })
        dao.rename(a, "写月报")
        assertEquals("写月报", dao.observeActive().first().first { it.id == a }.title)

        // 段绑定任务 -> 归档后不再出现在未完成列表,且清引用可还原为未绑定
        assertEquals(1, db!!.focusSessionDao().between(0, 9999).size)
        db!!.focusSessionDao().insertAll(
            listOf(FocusSessionEntity(profileId = 1, startAt = 3000, endAt = 4000, taskId = a))
        )
        dao.setDone(a, true, 9000L)
        assertTrue(dao.observeActive().first().none { it.id == a })
        val done = dao.observeDone().first().single()
        assertEquals(a, done.id)
        assertEquals(true, done.done)
        assertEquals(9000L, done.doneAt)

        dao.clearTaskRefs(a)
        assertEquals(2, db!!.focusSessionDao().getAll().count { it.taskId == null })
        dao.deleteById(b)
        assertEquals(0, dao.observeActive().first().size)
    }
}

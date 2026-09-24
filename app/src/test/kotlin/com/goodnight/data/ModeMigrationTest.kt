package com.goodnight.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.goodnight.data.db.FocusSessionEntity
import com.goodnight.data.db.GoodNightDatabase
import com.goodnight.data.db.ProfileDao
import com.goodnight.data.db.ProfileMode
import com.goodnight.data.db.TaskEntity
import com.goodnight.timer.TimeProvider
import kotlinx.coroutines.flow.first
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
 * Task 6 / #10:schema v1 -> v2(mode 列)迁移测试。仓库无既有 migration 测试先例,
 * 故手工构造 v1 库文件(Room v1 生成的原始 DDL,取自迁移前 generated schema),
 * 插入一行数据后经注册的 MIGRATION_1_2 升级打开,断言数据保留且 mode 补 0。
 *
 * v2.1 Task 1:GoodNightDatabase.build 已注册 MIGRATION_3_4,本测试随之覆盖 v1 -> v4 全链
 * (1->2->3->4 一次跑完),断言 profile/daily_total 逐值保留、focus_session/task 可用。
 *
 * v2.2 Task 1:链路再延伸一级到 v5(1->2->3->4->5),升级来的时钟必须落在「通用时钟」
 * (`taskId = NULL`、`archived = 0`),老库的 `index_profile_name` 唯一约束被降级为普通索引。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ModeMigrationTest {
    private val time = object : TimeProvider {
        var nowMs = 1000L
        override fun now() = nowMs
        override fun elapsedRealtime() = 0L
    }

    private var db: GoodNightDatabase? = null

    /** Room v1 生成的 DDL(GoodNightDatabase_Impl.createAllTables,取自 v2 引入前) */
    private val v1ProfileDdl =
        "CREATE TABLE IF NOT EXISTS `profile` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`name` TEXT NOT NULL, `workMinutes` INTEGER NOT NULL, `restMinutes` INTEGER NOT NULL, " +
            "`createdAt` INTEGER NOT NULL)"
    private val v1IndexDdl = "CREATE UNIQUE INDEX IF NOT EXISTS `index_profile_name` ON `profile` (`name`)"
    private val v1DailyDdl =
        "CREATE TABLE IF NOT EXISTS `daily_total` (`date` TEXT NOT NULL, " +
            "`profileId` INTEGER NOT NULL, `workMillis` INTEGER NOT NULL, " +
            "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`date`, `profileId`))"

    @After fun tearDown() {
        db?.close()
        db = null
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase("goodnight.db")
    }

    /** 新库(v2 直接建表):create 透传 mode;profiles 流/byId/modeOf 读回 */
    @Test fun createRoundTripsModeColumn() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, GoodNightDatabase::class.java)
            .allowMainThreadQueries().build()
        val repo = ProfileRepository(db!!.profileDao(), time)
        val a = repo.create("专注", 25, 5)!! // 缺省 COUNTDOWN
        val b = repo.create("写作", 90, 0, ProfileMode.COUNTUP)!!
        assertTrue(a > 0 && b > 0)
        assertEquals(ProfileMode.COUNTDOWN, repo.modeOf(a))
        assertEquals(ProfileMode.COUNTUP, repo.modeOf(b))
        assertEquals(ProfileMode.COUNTUP, repo.byId(b)!!.mode)
        val flowMode = repo.profiles.first().first { it.id == b }.mode
        assertEquals(ProfileMode.COUNTUP, flowMode)
    }

    /** 旧库升级:手工建 v1 schema + 插一行 -> 经注册迁移开 v5,行保留且 mode=0、时钟成为通用 */
    @Test fun upgradeV1KeepsRowsAndDefaultsModeToCountdown() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val path = ctx.getDatabasePath("goodnight.db").absolutePath
        val v1 = SQLiteDatabase.openOrCreateDatabase(path, null)
        v1.execSQL(v1ProfileDdl)
        v1.execSQL(v1IndexDdl)
        v1.execSQL(v1DailyDdl)
        v1.version = 1 // PRAGMA user_version:Room 据此走 onUpgrade
        v1.execSQL("INSERT INTO `profile` (`name`, `workMinutes`, `restMinutes`, `createdAt`) " +
            "VALUES ('老番茄', 25, 5, 1700000000000)")
        // v1.2 #2:首签名版(v0.3.0,schema v1)累积的专注明细同样必须继承
        v1.execSQL("INSERT INTO `daily_total` (`date`, `profileId`, `workMillis`, `updatedAt`) " +
            "VALUES ('2026-08-30', 1, 3600000, 1700000001000)")
        v1.close()

        // GoodNightDatabase.build 内含 addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
        db = GoodNightDatabase.build(ctx)
        // v2.2 Task 1:迁移链已延伸到 v5(1->2->3->4->5 一次跑完),user_version 落在 5
        assertEquals(5, db!!.openHelper.writableDatabase.version)
        val repo = ProfileRepository(db!!.profileDao(), time)
        val rows = repo.profiles.first()
        assertEquals("旧行保留且仅一行", 1, rows.size)
        val old = rows.single()
        assertEquals("老番茄", old.name)
        assertEquals(25, old.workMinutes)
        assertEquals(ProfileMode.COUNTDOWN, old.mode) // 升级行 mode 补 0
        assertNull("v5:老时钟成为通用时钟", old.taskId)
        assertFalse("v5:老时钟未归档", old.archived)
        assertEquals(ProfileMode.COUNTDOWN, repo.modeOf(old.id))
        // 升级后新库能力完整:可继续写入(mode 透传)
        val id2 = repo.create("新配置", 50, 10, ProfileMode.COUNTUP)!!
        assertTrue(id2 > old.id)
        assertEquals(ProfileMode.COUNTUP, repo.modeOf(id2))
        assertEquals(2, repo.count())
        // daily_total 明细逐值保留(日期/归属/时长/时间戳)
        val totals = DailyTotalRepository(db!!, db!!.dailyTotalDao(), db!!.focusSessionDao(), time)
            .rangeBreakdown("2026-08-30", "2026-08-30")
        assertEquals(1, totals.size)
        assertEquals("2026-08-30", totals.single().date)
        assertEquals(1L, totals.single().profileId)
        assertEquals(3_600_000L, totals.single().total)

        // focus_session 表由 2->3 建出、3->4 增出 taskId:写入读回逐值一致,升级来的段未绑定任务
        db!!.focusSessionDao().insertAll(
            listOf(FocusSessionEntity(profileId = old.id, startAt = 1700000002000, endAt = 1700000003000))
        )
        val segs = db!!.focusSessionDao().getAll()
        assertEquals(1, segs.size)
        assertEquals(old.id, segs.single().profileId)
        assertEquals(1700000002000L, segs.single().startAt)
        assertEquals(1700000003000L, segs.single().endAt)
        assertNull("v1 升级来的段默认未绑定任务", segs.single().taskId)
        // task 表(3->4 新建)可用
        val newTask = db!!.taskDao().insert(TaskEntity(title = "写周报", createdAt = 1700000004000, sortOrder = 0))
        assertTrue(newTask > 0)
        assertEquals(1, db!!.taskDao().observeActive().first().size)
    }
}

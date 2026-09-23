package com.goodnight.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ProfileEntity::class, DailyTotalEntity::class, FocusSessionEntity::class, TaskEntity::class],
    version = 4,
    exportSchema = false,
)
abstract class GoodNightDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun dailyTotalDao(): DailyTotalDao
    abstract fun focusSessionDao(): FocusSessionDao
    abstract fun taskDao(): TaskDao

    companion object {
        /**
         * v1 -> v2(Task 6 / #10):profiles 增 mode 列(0=倒计时,1=正计时)。
         * 非破坏:旧库升级逐行保留、mode 补 0;新增列带 DEFAULT 与实体
         * @ColumnInfo(defaultValue = "0") 对齐(Room 迁移后逐列校验)。
         * 本次为唯一 schema 变更,后续任务不得再动表结构。
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `profile` ADD COLUMN `mode` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v2 -> v3(v1.3 #6):新增 focus_session 段记录表(非破坏,纯建表)。 */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `focus_session` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`profileId` INTEGER NOT NULL, `startAt` INTEGER NOT NULL, `endAt` INTEGER NOT NULL)"
                )
            }
        }

        /**
         * v3 -> v4(v2.1 Task 1):新增 `task` 表与 `focus_session.taskId` 可空列,纯增量。
         *
         * 不加外键:SQLite 无法用 ALTER 给既有表加外键(只能重建表,风险更高),
         * 「删除任务时把引用置空」由应用层在同一事务内保证(见 TaskRepository)。
         * DDL 全部 IF NOT EXISTS,迁移可重复执行;`taskId` 无默认值 -> 旧段自动为 NULL(未绑定)。
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `task` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`title` TEXT NOT NULL, `done` INTEGER NOT NULL DEFAULT 0, " +
                        "`createdAt` INTEGER NOT NULL, `doneAt` INTEGER, `sortOrder` INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_task_done_sortOrder` ON `task` (`done`, `sortOrder`)")
                db.execSQL("ALTER TABLE `focus_session` ADD COLUMN `taskId` INTEGER")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_focus_session_taskId` ON `focus_session` (`taskId`)")
            }
        }

        /**
         * v1.11.2:[name] 可覆盖库文件名 —— 单元测试用它给每个测试类独立的库文件,
         * 避免同一 Robolectric 沙箱里多个测试类共享 "goodnight.db" 造成的 SQLITE_BUSY / 数据串扰。
         */
        fun build(context: Context, name: String = "goodnight.db"): GoodNightDatabase =
            Room.databaseBuilder(context, GoodNightDatabase::class.java, name)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
    }
}

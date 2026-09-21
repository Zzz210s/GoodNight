package com.goodnight.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [ProfileEntity::class, DailyTotalEntity::class, FocusSessionEntity::class], version = 3, exportSchema = false)
abstract class GoodNightDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun dailyTotalDao(): DailyTotalDao
    abstract fun focusSessionDao(): FocusSessionDao

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
         * v1.11.2:[name] 可覆盖库文件名 —— 单元测试用它给每个测试类独立的库文件,
         * 避免同一 Robolectric 沙箱里多个测试类共享 "goodnight.db" 造成的 SQLITE_BUSY / 数据串扰。
         */
        fun build(context: Context, name: String = "goodnight.db"): GoodNightDatabase =
            Room.databaseBuilder(context, GoodNightDatabase::class.java, name)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
    }
}

package com.goodnight.data

import androidx.room.withTransaction
import com.goodnight.data.db.GoodNightDatabase
import com.goodnight.data.db.ProfileEntity
import com.goodnight.data.db.DailyTotalEntity
import com.goodnight.data.db.FocusSessionEntity
import com.goodnight.data.db.TaskEntity
import org.json.JSONArray
import org.json.JSONObject

/**
 * 数据导入/导出:把 profile / daily_total / focus_session(/ v2.1 起的 task)序列化为 JSON(SAF 存/读),
 * 导入时按主键 upsert 合并(配置按 id 覆盖、日累计按 date+profileId 覆盖、段按 id 忽略重复、任务按 id 覆盖)。
 *
 * **格式版本**:v1 = 无 `tasks`、会话无 `taskId`;v2(v2.1 Task 9)= 顶层 `tasks` 数组 +
 * `focusSessions[].taskId`。导入 v1 时任务表为空、全部段为未绑定,账目与旧版逐值等价。
 * 版本高于 [FORMAT_VERSION] 一律拒绝(抛 IllegalArgumentException):未来格式可能有本版读不懂的
 * 字段,按老规则合并会静默丢数据,宁可让调用方提示"备份文件无效"。
 */
object DataTransfer {
    const val FORMAT_VERSION = 2

    /** 导出全部数据为 JSON 字符串 */
    suspend fun exportJson(db: GoodNightDatabase): String {
        val profiles = db.profileDao().getAll()
        val totals = db.dailyTotalDao().getAll()
        val sessions = db.focusSessionDao().getAll()
        val tasks = db.taskDao().allNow().sortedBy { it.id } // 按 id 排序:导出文件可 diff

        val root = JSONObject()
        root.put("version", FORMAT_VERSION)
        root.put("exportedAt", System.currentTimeMillis())

        val pArr = JSONArray()
        profiles.forEach { p -> pArr.put(JSONObject()
            .put("id", p.id)
            .put("name", p.name)
            .put("workMinutes", p.workMinutes)
            .put("restMinutes", p.restMinutes)
            .put("createdAt", p.createdAt)
            .put("mode", p.mode))
        }
        root.put("profiles", pArr)

        val tArr = JSONArray()
        totals.forEach { t -> tArr.put(JSONObject()
            .put("date", t.date)
            .put("profileId", t.profileId)
            .put("workMillis", t.workMillis)
            .put("updatedAt", t.updatedAt))
        }
        root.put("dailyTotals", tArr)

        val sArr = JSONArray()
        sessions.forEach { s -> sArr.put(JSONObject()
            .put("id", s.id)
            .put("profileId", s.profileId)
            .put("startAt", s.startAt)
            .put("endAt", s.endAt)
            // v2:未绑定时写显式 null(键恒在,便于外部工具识别 v2 会话结构)
            .put("taskId", s.taskId ?: JSONObject.NULL))
        }
        root.put("focusSessions", sArr)

        val kArr = JSONArray()
        tasks.forEach { k -> kArr.put(JSONObject()
            .put("id", k.id)
            .put("title", k.title)
            .put("done", if (k.done) 1 else 0)
            .put("doneAt", k.doneAt ?: JSONObject.NULL)
            .put("sortOrder", k.sortOrder)
            .put("createdAt", k.createdAt))
        }
        root.put("tasks", kArr)

        return root.toString(2)
    }

    /** 导入 JSON 并 upsert 合并;返回 (配置数, 日累计数, 段数, 任务数)。解析失败/版本过新抛 IllegalArgumentException */
    suspend fun importJson(db: GoodNightDatabase, json: String): ImportCounts {
        val root = JSONObject(json)
        // 缺 version 的按 v1 处理(历史文件均带 version,这里只对"读不懂的更新格式"拒绝)
        val version = root.optInt("version", 1)
        require(version <= FORMAT_VERSION) { "不支持的备份版本: $version(本版最高 $FORMAT_VERSION)" }

        val profiles = root.optJSONArray("profiles") ?: JSONArray()
        val totals = root.optJSONArray("dailyTotals") ?: JSONArray()
        val sessions = root.optJSONArray("focusSessions") ?: JSONArray()
        // v1 无 tasks:空数组 -> 任务表不动
        val tasks = root.optJSONArray("tasks") ?: JSONArray()

        val profileRows = ArrayList<ProfileEntity>(profiles.length())
        for (i in 0 until profiles.length()) {
            val o = profiles.getJSONObject(i)
            profileRows.add(ProfileEntity(
                id = o.optLong("id", 0),
                name = o.getString("name"),
                workMinutes = o.optInt("workMinutes", 25),
                restMinutes = o.optInt("restMinutes", 5),
                createdAt = o.optLong("createdAt", 0),
                mode = o.optInt("mode", 0),
            ))
        }
        val totalRows = ArrayList<DailyTotalEntity>(totals.length())
        for (i in 0 until totals.length()) {
            val o = totals.getJSONObject(i)
            totalRows.add(DailyTotalEntity(
                date = o.getString("date"),
                profileId = o.getLong("profileId"),
                workMillis = o.optLong("workMillis", 0),
                updatedAt = o.optLong("updatedAt", 0),
            ))
        }
        val sessionRows = ArrayList<FocusSessionEntity>(sessions.length())
        for (i in 0 until sessions.length()) {
            val o = sessions.getJSONObject(i)
            sessionRows.add(FocusSessionEntity(
                id = o.optLong("id", 0),
                profileId = o.getLong("profileId"),
                startAt = o.getLong("startAt"),
                endAt = o.getLong("endAt"),
                // v1 无该键 / v2 未绑定 -> null
                taskId = if (o.isNull("taskId")) null else o.optLong("taskId"),
            ))
        }
        val taskRows = ArrayList<TaskEntity>(tasks.length())
        for (i in 0 until tasks.length()) {
            val o = tasks.getJSONObject(i)
            taskRows.add(TaskEntity(
                id = o.optLong("id", 0),
                title = o.getString("title"),
                done = o.optInt("done", 0) != 0,
                createdAt = o.optLong("createdAt", 0),
                doneAt = if (o.isNull("doneAt")) null else o.optLong("doneAt"),
                sortOrder = o.optLong("sortOrder", 0),
            ))
        }

        db.withTransaction {
            // 任务先入库:段里的 taskId 才能解析到(无外键,顺序只为可读性/自洽)
            db.taskDao().upsertAll(taskRows)
            db.profileDao().upsertAll(profileRows)
            db.dailyTotalDao().upsertAll(totalRows)
            db.focusSessionDao().insertAllIgnore(sessionRows)
        }
        return ImportCounts(profiles.length(), totals.length(), sessions.length(), tasks.length())
    }

    /** 文件中的行数(非实际写入数:段按 id 忽略重复)。[total] 供界面「恢复 N 条」展示 */
    data class ImportCounts(val profiles: Int, val dailyTotals: Int, val sessions: Int, val tasks: Int) {
        val total: Int get() = profiles + dailyTotals + sessions + tasks
    }
}

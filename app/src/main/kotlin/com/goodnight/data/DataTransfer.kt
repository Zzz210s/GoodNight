package com.goodnight.data

import androidx.room.withTransaction
import com.goodnight.data.db.GoodNightDatabase
import com.goodnight.data.db.ProfileEntity
import com.goodnight.data.db.DailyTotalEntity
import com.goodnight.data.db.FocusSessionEntity
import org.json.JSONArray
import org.json.JSONObject

/**
 * 数据导入/导出(v1.9.1):把 profile / daily_total / focus_session 三表序列化为 JSON(SAF 存/读),
 * 导入时按主键 upsert 合并(配置按 id 覆盖、日累计按 date+profileId 覆盖、段按 id 忽略重复)。
 * 导出后文件可核对;"导出→校验→合并→恢复"流程参考备份类方案(但仅本 App 粒度,不涉及整机)。
 */
object DataTransfer {
    const val FORMAT_VERSION = 1

    /** 导出全部数据为 JSON 字符串 */
    suspend fun exportJson(db: GoodNightDatabase): String {
        val profiles = db.profileDao().getAll()
        val totals = db.dailyTotalDao().getAll()
        val sessions = db.focusSessionDao().getAll()

        val root = JSONObject()
        root.put("version", FORMAT_VERSION)
        root.put("exportedAt", System.currentTimeMillis())

        val pArr = JSONArray()
        profiles.forEach { p ->
            pArr.put(JSONObject()
                .put("id", p.id)
                .put("name", p.name)
                .put("workMinutes", p.workMinutes)
                .put("restMinutes", p.restMinutes)
                .put("createdAt", p.createdAt)
                .put("mode", p.mode))
        }
        root.put("profiles", pArr)

        val tArr = JSONArray()
        totals.forEach { t ->
            tArr.put(JSONObject()
                .put("date", t.date)
                .put("profileId", t.profileId)
                .put("workMillis", t.workMillis)
                .put("updatedAt", t.updatedAt))
        }
        root.put("dailyTotals", tArr)

        val sArr = JSONArray()
        sessions.forEach { s ->
            sArr.put(JSONObject()
                .put("id", s.id)
                .put("profileId", s.profileId)
                .put("startAt", s.startAt)
                .put("endAt", s.endAt))
        }
        root.put("focusSessions", sArr)

        return root.toString(2)
    }

    /** 导入 JSON 并 upsert 合并;返回 (配置数, 日累计数, 段数)。解析失败抛 IllegalArgumentException */
    suspend fun importJson(db: GoodNightDatabase, json: String): ImportCounts {
        val root = JSONObject(json)
        val profiles = root.optJSONArray("profiles") ?: JSONArray()
        val totals = root.optJSONArray("dailyTotals") ?: JSONArray()
        val sessions = root.optJSONArray("focusSessions") ?: JSONArray()

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
            ))
        }

        db.withTransaction {
            db.profileDao().upsertAll(profileRows)
            db.dailyTotalDao().upsertAll(totalRows)
            db.focusSessionDao().insertAllIgnore(sessionRows)
        }
        return ImportCounts(profiles.length(), totals.length(), sessions.length())
    }

    data class ImportCounts(val profiles: Int, val dailyTotals: Int, val sessions: Int)
}

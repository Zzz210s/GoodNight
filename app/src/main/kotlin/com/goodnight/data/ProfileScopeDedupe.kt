package com.goodnight.data

import com.goodnight.data.db.GoodNightDatabase
import com.goodnight.data.db.ProfileEntity

/**
 * v2.2 Task 2:导入后的「作用域内唯一」修复。
 *
 * 备份可能带来同一作用域(taskId 相同)的同名**活跃**时钟:旧备份里两个任务下同名、
 * 或手改过的文件;DB 层没有唯一索引(见 [ProfileRepository] 头注),导入走 upsertAll 就会
 * 立出两个同名时钟,让 `byNameInScope` 的判定失去意义。策略与 [TaskRepository.deleteTask]
 * 同套:保留先到者,后者按数字后缀改名(「专注」→「专注 (2)」)。
 *
 * 先到者优先级:**库内既存行 > 本批导入行**。既存行不在这批导入里,不该被一次导入改写名字;
 * 同批导入行则按 (createdAt, id) 升序判先到 —— 这是库内列表的排序口径(见
 * [com.goodnight.data.db.ProfileDao.getAll]),不依赖备份文件里的行序(手改过的文件行序可能乱)。
 * **归档行不占名**(与 [com.goodnight.data.db.ProfileDao.byNameInScope] 排除归档同口径):
 * 给它加后缀纯属副作用,还会让历史里按 id 解析出的旧名字静默漂移。
 *
 * 本步骤必须排在归一化(悬挂 `profile.taskId` 归为通用)**之后**:归为通用的行可能与该层
 * 既有的同名时钟撞名,先建名就会漏掉这种新撞出的重复。
 */
internal object ProfileScopeDedupe {
    /**
     * @param imported 本批导入的时钟行(按 id 定位库内行;去重顺序按 createdAt/id,与文件行序无关)
     * @return 被改名的行数(仅供测试与诊断)
     */
    suspend fun apply(db: GoodNightDatabase, imported: List<ProfileEntity>): Int {
        val dao = db.profileDao()
        val order = imported.sortedWith(compareBy({ it.createdAt }, { it.id })).map { it.id }
        val ids = imported.map { it.id }.toSet()
        val byId = HashMap<Long, ProfileEntity>()
        val taken = HashMap<Long?, MutableSet<String>>()

        // 1. 库内既存活跃行先占名
        for (row in dao.getAll()) {
            if (row.id in ids) byId[row.id] = row else if (!row.archived) taken.byScope(row.taskId).add(row.name)
        }
        // 2. 本批导入的活跃行按 createdAt/id 升序建名,撞名者加数字后缀
        var renamed = 0
        for (id in order) {
            // 重复 id 只处理第一次(REPLACE 语义下最后一次写入生效,行本身已是最终状态)
            val row = byId.remove(id) ?: continue
            if (row.archived) continue
            val names = taken.byScope(row.taskId)
            if (names.add(row.name)) continue
            val unique = uniqueName(row.name, names)
            names.add(unique)
            dao.update(row.copy(name = unique))
            renamed += 1
        }
        return renamed
    }

    private fun MutableMap<Long?, MutableSet<String>>.byScope(taskId: Long?): MutableSet<String> =
        getOrPut(taskId) { HashSet() }

    /** 依次试「名字 (2)」「名字 (3)」… 直到该作用域内无人占用 */
    private fun uniqueName(base: String, taken: Set<String>): String {
        var n = 2
        while ("$base ($n)" in taken) n += 1
        return "$base ($n)"
    }
}

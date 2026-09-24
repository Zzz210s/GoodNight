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
 * **批次身份规则**:判「库内既存行」不能只看 `id` 是否来自文件 —— 文件里 `profiles[]` 可以缺 `id`
 * (解析为 0),这类行入库由 `autoGenerate` 分到新 id,`id in importedIds` 永远落空,于是被误判成
 * 既存行去先占名:一份缺 `id` 的文件就能在同一作用域留下两个同名活跃时钟,库里更老的行反被改名。
 * 因此判据是 `id in importedIds || id > maxIdBeforeImport`:自增主键
 * (INTEGER PRIMARY KEY AUTOINCREMENT)保证本批新插入的行拿到比导入前更大的 id,缺 `id` 的行
 * 照样算本批。不用「按 name/taskId/createdAt 指纹去库里配对」是因为有歧义:库内既存行可能与
 * 缺 `id` 的新行指纹完全一致,且悬挂 `taskId` 归一后的值与文件里未必相同,配对可能错到既存行头上。
 *
 * 本步骤必须排在归一化(悬挂 `profile.taskId` 归为通用)**之后**:归为通用的行可能与该层
 * 既有的同名时钟撞名,先建名就会漏掉这种新撞出的重复。
 */
internal object ProfileScopeDedupe {
    /**
     * @param importedIds 本批导入行在文件里带的 `id`(缺 `id` 的文件行是 0,不构成本批身份)
     * @param maxIdBeforeImport 写这批之前库内最大的时钟 id(空库传 0)
     */
    suspend fun apply(db: GoodNightDatabase, importedIds: Set<Long>, maxIdBeforeImport: Long) {
        val dao = db.profileDao()
        val taken = HashMap<Long?, MutableSet<String>>()
        val batch = ArrayList<ProfileEntity>()

        // 1. 库内既存活跃行先占名;本批行(含缺 id 被分到新 id 的)留到第 2 步按先后顺序建名
        for (row in dao.getAll()) {
            if (row.id in importedIds || row.id > maxIdBeforeImport) batch.add(row)
            else if (!row.archived) taken.byScope(row.taskId).add(row.name)
        }
        // 2. 本批活跃行按 (createdAt, id) 升序建名,撞名者加数字后缀
        for (row in batch.sortedWith(compareBy({ it.createdAt }, { it.id }))) {
            if (row.archived) continue
            val names = taken.byScope(row.taskId)
            if (names.add(row.name)) continue
            val unique = uniqueName(row.name, names)
            names.add(unique)
            dao.update(row.copy(name = unique))
        }
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

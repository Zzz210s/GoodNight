package com.goodnight.ui.home

import com.goodnight.data.DayPeriod
import com.goodnight.data.db.FocusSessionEntity
import com.goodnight.data.groupByPeriod
import com.goodnight.data.mergeSessions
import java.time.LocalDate
import java.time.ZoneId

/**
 * v2.1 Task 7:每日详情里的一段 —— 时间区间 + 归属任务(未绑定 = null)。
 * [taskName] 由标题表解析;段归属的任务已被删时查不到名字,按未绑定渲染。
 */
data class DaySpan(val start: Long, val end: Long, val taskId: Long? = null, val taskName: String? = null)

data class DayDetailRow(
    val profileName: String,
    val millis: Long,
    val index: Int,
    /**
     * v1.3 #6:当日该时钟各段 [startAt..endAt](墙钟 ms,升序),供详情小行展示。
     * = 展示合并([mergeSessions])后的区间集合,是行合计的口径来源(不带任务名)。
     */
    val sessions: List<Pair<Long, Long>> = emptyList(),
    /**
     * v2.1 Task 7:显示用细分 —— 与 [sessions] 覆盖同一批区间(合计口径不变),但按任务切点切开,
     * 每段带自己的任务名。任务切点两侧 gap=0,[sessions] 会把它并回一条,直接拿合并结果渲染
     * 会出现"一个显示块里两个任务名",故显示走这里(见 [taskSpansOf])。
     */
    val taskSpans: List<DaySpan> = emptyList(),
)

data class DayDetailUi(val date: LocalDate, val totalMillis: Long, val rows: List<DayDetailRow>)

/**
 * 把某时钟当日的原始段落转成"带任务名的显示细分":
 * ①区间集合仍由 [mergeSessions] 决定(间隔 <=3 分钟合并、合并后 <3 分钟丢弃)—— 合计口径不变;
 * ②每个合并段再按任务切点切开:段内第 i 行覆盖 [row_i.startAt, row_{i+1}.startAt)(末行到合并段终点),
 * 相邻同任务的细分并回一条。
 *
 * 因此细分区间**恒好铺满**合并段之和:卡片里的"专注总计"与可见时间段不会对不上,
 * 也不会像"先按 taskId 分组再各自合并"那样把不足 3 分钟的任务片段整段丢掉(用户选了任务就不是误触)。
 */
fun taskSpansOf(sessions: List<FocusSessionEntity>, titles: Map<Long, String>): List<DaySpan> {
    val rows = sessions.filter { it.endAt > it.startAt }.sortedBy { it.startAt }
    val out = ArrayList<DaySpan>()
    var i = 0
    for ((start, end) in mergeSessions(rows.map { it.startAt to it.endAt })) {
        while (i < rows.size && rows[i].startAt < start) i++ // 跳过被最小跨度规则丢弃的行(与合计同口径)
        val block = ArrayList<FocusSessionEntity>()
        while (i < rows.size && rows[i].startAt < end) block += rows[i++]
        if (block.isEmpty()) { // 合并段由这些行生成,理论不可达;真出现时按未绑定保留区间
            out += DaySpan(start, end)
            continue
        }
        block.forEachIndexed { k, r ->
            val spanEnd = block.getOrNull(k + 1)?.startAt ?: end
            val last = out.lastOrNull()
            if (last != null && last.taskId == r.taskId && last.end == r.startAt) {
                out[out.lastIndex] = last.copy(end = spanEnd) // 同一任务且首尾相接:并回一条
            } else {
                out += DaySpan(r.startAt, spanEnd, r.taskId, r.taskId?.let { titles[it] })
            }
        }
    }
    return out
}

/**
 * 与 data 层 [groupByPeriod] 同规则(同一大时段的连续段合为一组),但保留每段的任务名 ——
 * 直接借它的分组实现(同一份规则,不再写一遍),再按区间把 [DaySpan] 贴回(分组保持输入顺序,
 * 且细分区间互不相同)。
 */
fun groupSpansByPeriod(
    spans: List<DaySpan>,
    zone: ZoneId = ZoneId.systemDefault(),
): List<Pair<DayPeriod, List<DaySpan>>> {
    val byInterval = spans.associateBy { it.start to it.end }
    return groupByPeriod(spans.map { it.start to it.end }, zone)
        .map { (period, list) -> period to list.map { byInterval.getValue(it) } }
}

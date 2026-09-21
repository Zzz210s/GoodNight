package com.goodnight.ui.report

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goodnight.data.db.DayProfileTotal
import com.goodnight.data.db.ProfileEntity
import com.goodnight.di.AppGraph
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

enum class ReportRange { WEEK, MONTH, LIFETIME }

data class ReportRow(
    val label: String,
    val millis: Long,
    /** v1.4 本地化辅助:月报桶序号与起止(MM-dd);0 = 非桶行 */
    val weekIndex: Int = 0,
    val rangeFrom: String = "",
    val rangeTo: String = "",
)

data class ProfileTotalUi(val profileName: String, val millis: Long)

data class ReportUiState(
    val range: ReportRange = ReportRange.WEEK,
    val rows: List<ReportRow> = emptyList(),
    val profileTotals: List<ProfileTotalUi> = emptyList(),
    /** v1.5:健康风指标与时段分布(仅周/月窗口,长期累计页签为空) */
    val metrics: ReportMetrics? = null,
    val timeSlots: List<SlotMinutes> = emptyList(),
    /** v1.9.1:历史回顾 —— 当前报表窗口末日期(锚点)与是否仍可往后切(未到今日) */
    val anchor: LocalDate = LocalDate.now(),
    val canGoNext: Boolean = false,
    /** v1.9.13 #43:首次打开应用日期(往期回顾起点);无历史时为空 */
    val firstLaunch: LocalDate? = null,
)

/** 报表窗口(闭区间 ISO 日期):周 = 本周一(ISO 周一为一周首日)至 today;月 = 本月 1 日至 today。 */
fun reportWindow(range: ReportRange, today: LocalDate): Pair<String, String> = when (range) {
    ReportRange.WEEK -> {
        val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
        monday.toString() to today.toString()
    }
    ReportRange.MONTH -> today.withDayOfMonth(1).toString() to today.toString()
    ReportRange.LIFETIME -> LocalDate.ofEpochDay(0).toString() to today.toString()
}

/**
 * 明细行。周报:逐日行(label "MM-dd");月报:按周分桶(label "第 N 周(MM-dd~MM-dd)",
 * 桶 = 当月按日切 7 天段,跨月末尾桶止于 today)。raw 只含窗口内有数据的单元,按日期升序。
 */
fun reportRows(range: ReportRange, today: LocalDate, raw: List<DayProfileTotal>): List<ReportRow> {
    val byDate = raw.groupBy { it.date }.mapValues { (_, rs) -> rs.sumOf { it.total } }
    val dates = byDate.toSortedMap()
    return when (range) {
        ReportRange.WEEK -> dates.map { (date, m) -> ReportRow(date.substring(5), m) }
        ReportRange.LIFETIME -> emptyList() // 长期累计走 profileTotals,明细为空
        ReportRange.MONTH -> dates.entries
            .groupBy { (date, _) -> (LocalDate.parse(date).dayOfMonth - 1) / 7 + 1 }
            .map { (bucket, dayEntries) ->
                val start = today.withDayOfMonth((bucket - 1) * 7 + 1)
                val end = start.plusDays(6).let { if (it.isAfter(today)) today else it }
                val from = start.toString().substring(5)
                val to = end.toString().substring(5)
                ReportRow("第 $bucket 周($from~$to)", dayEntries.sumOf { it.value }, bucket, from, to)
            }
    }
}

fun reportProfileTotals(
    profiles: List<ProfileEntity>,
    raw: List<DayProfileTotal>,
): List<ProfileTotalUi> = raw
    // v1.10.8:已删除配置不参与统计(其段落/合计在删除时已级联清理,这里再兜一层)
    .filter { r -> profiles.any { it.id == r.profileId } }
    .groupBy { it.profileId }
    .map { (id, rs) ->
        ProfileTotalUi(
            profileName = profiles.first { it.id == id }.name,
            millis = rs.sumOf { it.total },
        )
    }
    .sortedByDescending { it.millis }

class ReportViewModel(
    val graph: AppGraph,
    /** 可注入时钟:报表窗口以今天为锚,测试传固定日期 */
    private val clock: () -> LocalDate = { LocalDate.now() },
) : ViewModel() {
    private val _range = MutableStateFlow(ReportRange.WEEK)
    private val _anchor = MutableStateFlow(clock())
    private val _ui = MutableStateFlow(ReportUiState())
    val ui: StateFlow<ReportUiState> = _ui.asStateFlow()

    init {
        // 生产自动刷新:dayTotals(epoch) 是 daily_total 表失效信号,profiles 是 profile 表失效信号——
        // 范围切换、新增记录、设置页改名/删除配置都驱动同一重建。Report VM 常驻 activity 级
        // ViewModelStore,配置表并入 combine 后改名/删除无需再等新记录或切范围即重解析名称
        viewModelScope.launch {
            combine(
                _range,
                _anchor,
                graph.totalsRepo.dayTotals(EPOCH),
                graph.profileRepo.profiles,
            ) { range, _, _, _ -> range }
                .collect { refreshInternal() }
        }
        // v1.9.13 #43(修正):往期回顾下限 = 应用安装日(firstInstallTime —— “打开软件那一天”)。
        // 之前误用“最早数据日”(可能含导入/异常早的记录)导致能选到未使用时的日期;安装日
        // 从 PackageManager 读,跨版本稳定、不随数据变。无法读取时回退 null(不限制)。
        viewModelScope.launch {
            val installed = runCatching {
                val ai = graph.appContext.packageManager.getPackageInfo(graph.appContext.packageName, 0)
                java.time.LocalDate.ofInstant(
                    java.time.Instant.ofEpochMilli(ai.firstInstallTime),
                    java.time.ZoneId.systemDefault(),
                )
            }.getOrNull()
            _ui.value = _ui.value.copy(firstLaunch = installed)
        }
    }

    fun setRange(r: ReportRange) {
        _range.value = r
        // 切换页签回到最新(历史位置不跨页签保留,各 tab 从当前期起)
        _anchor.value = clock()
        // 乐观同步选中档:按钮立即切换,数据随后由自动重建落地(避免 DB 往返期间滞留旧高亮)
        _ui.value = _ui.value.copy(range = r)
    }

    /** 回顾上一期:周→往前一周;月→往前一月;长期累计无历史 */
    /** v1.9.8 下拉自由选择:跳到包含 [date] 的周期(周/月) */
    fun jumpTo(date: LocalDate) {
        if (_range.value == ReportRange.LIFETIME) return
        val d = if (date.isAfter(clock())) clock() else date
        _anchor.value = d
    }

    fun prevPeriod() {
        if (_range.value == ReportRange.LIFETIME) return
        val d = if (_range.value == ReportRange.WEEK) _anchor.value.minusWeeks(1) else _anchor.value.minusMonths(1)
        val min = _ui.value.firstLaunch
        _anchor.value = if (min != null && d.isBefore(min)) min else d
    }

    /** 回顾下一期:往后切,但不越过今日 */
    fun nextPeriod() {
        if (_range.value == ReportRange.LIFETIME) return
        val d = if (_range.value == ReportRange.WEEK) _anchor.value.plusWeeks(1) else _anchor.value.plusMonths(1)
        _anchor.value = if (d.isAfter(clock())) clock() else d
    }

    /** 挂起重建:测试与自动刷新共用同一实现(避开 stateIn/flatMapLatest 的测试环境悬挂) */
    suspend fun refresh() {
        refreshInternal()
    }

    private suspend fun refreshInternal() {
        val range = _range.value
        val profiles = graph.profileRepo.profiles.first()
        val lifetime = range == ReportRange.LIFETIME
        // 长期累计页:锚点恒为今日(不可回看);周/月:锚点为选中期
        val today = clock()
        val anchor = if (lifetime) today else _anchor.value
        val (from, to) = reportWindow(range, anchor)
        val raw = graph.totalsRepo.rangeBreakdown(from, to)
        val prevTotal = if (lifetime) null else {
            val (prevFrom, prevTo) = prevWindowOf(from, to)
            graph.totalsRepo.rangeBreakdown(prevFrom, prevTo).sumOf { it.total }
        }
        val zone = java.time.ZoneId.systemDefault()
        val startMs = LocalDate.parse(from).atStartOfDay(zone).toInstant().toEpochMilli()
        val endMs = LocalDate.parse(to).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val sessions = graph.totalsRepo.sessionsBetweenMs(startMs, endMs)
        val (metrics, slots) = summarizeWindow(from, to, raw, sessions, prevTotal, zone)
        _ui.value = ReportUiState(
            range = range,
            rows = if (lifetime) emptyList() else reportRows(range, anchor, raw),
            profileTotals = reportProfileTotals(profiles, raw),
            metrics = metrics,
            timeSlots = slots,
            anchor = anchor,
            canGoNext = !lifetime && anchor.isBefore(today),
            firstLaunch = _ui.value.firstLaunch,
        )
    }

    private companion object {
        /** 表变更信号窗:全历史起点(与主页 from 口径一致),只为触发 Room 失效重查 */
        val EPOCH: String = LocalDate.ofEpochDay(0).toString()
    }
}

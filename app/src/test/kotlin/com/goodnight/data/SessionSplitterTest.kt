package com.goodnight.data

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.3 #6:跨午夜切分纯函数测试(Asia/Shanghai 定区,避免 CI 时区漂移) */
class SessionSplitterTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun ms(y: Int, m: Int, d: Int, h: Int, min: Int) =
        java.time.LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant().toEpochMilli()

    @Test fun sameDayYieldsSingleSegment() {
        val segs = splitAtMidnights(ms(2026, 9, 4, 9, 15), ms(2026, 9, 4, 9, 40), zone)
        assertEquals(1, segs.size)
        assertEquals(ms(2026, 9, 4, 9, 15), segs[0].first)
        assertEquals(ms(2026, 9, 4, 9, 40), segs[0].second)
    }

    @Test fun crossOneMidnightSplitsAt0000() {
        val segs = splitAtMidnights(ms(2026, 9, 4, 23, 30), ms(2026, 9, 5, 0, 20), zone)
        assertEquals(2, segs.size)
        assertEquals(ms(2026, 9, 4, 23, 30), segs[0].first)
        assertEquals(ms(2026, 9, 5, 0, 0), segs[0].second) // 前段止于 00:00(Q2)
        assertEquals(ms(2026, 9, 5, 0, 0), segs[1].first) // 后段起于 00:00(Q2)
        assertEquals(ms(2026, 9, 5, 0, 20), segs[1].second)
    }

    @Test fun crossTwoMidnightsYieldsThreeSegments() {
        val segs = splitAtMidnights(ms(2026, 9, 4, 23, 0), ms(2026, 9, 6, 0, 10), zone)
        assertEquals(3, segs.size)
        // segs[0]=9/4 23:00~9/5 00:00;segs[1]=9/5 整天;segs[2]=9/6 00:00~00:10
        assertEquals(ms(2026, 9, 5, 0, 0), segs[0].second)
        assertEquals(ms(2026, 9, 5, 0, 0), segs[1].first)
        assertEquals(ms(2026, 9, 6, 0, 0), segs[1].second)
        assertEquals(ms(2026, 9, 6, 0, 0), segs[2].first)
        assertEquals(ms(2026, 9, 6, 0, 10), segs[2].second)
    }

    @Test fun exactlyAtMidnightBoundaryKeepsSingleDaySegment() {
        // 23:59:59.x ~ 00:00 边界:end==nextMidnight 时前段已含全部,不产生空后段
        val end = ms(2026, 9, 5, 0, 0)
        val segs = splitAtMidnights(ms(2026, 9, 4, 23, 58), end, zone)
        assertEquals(1, segs.size)
        assertEquals(end, segs[0].second)
    }

    @Test fun emptyOrInvertedRangeYieldsNothing() {
        val t = ms(2026, 9, 4, 10, 0)
        assertTrue(splitAtMidnights(t, t, zone).isEmpty())
        assertTrue(splitAtMidnights(t, t - 1000, zone).isEmpty())
    }

    // ---- v2.1 Task 3:任务切换切点(与午夜切点合成同一条边界列表) ----

    @Test fun extraCutSplitsAtTaskSwitch() {
        val segs = splitAtMidnights(ms(2026, 9, 22, 9, 0), ms(2026, 9, 22, 10, 0), zone,
            extraCuts = listOf(ms(2026, 9, 22, 9, 30)))
        assertEquals(listOf(ms(2026, 9, 22, 9, 0) to ms(2026, 9, 22, 9, 30),
                            ms(2026, 9, 22, 9, 30) to ms(2026, 9, 22, 10, 0)), segs)
    }

    @Test fun cutOutsideWindowIsIgnoredAndCutsAreSorted() {
        val segs = splitAtMidnights(1000, 5000, zone, extraCuts = listOf(3000, 500, 9000, 3000))
        assertEquals(listOf(1000L to 3000L, 3000L to 5000L), segs)
    }

    /** 端点上的切点不切(否则产出零长段);与午夜边界同时存在时逐段产出 */
    @Test fun boundaryCutsAreIgnoredAndMidnightJoinsTheSameBoundaryList() {
        val s = ms(2026, 9, 22, 9, 0)
        val e = ms(2026, 9, 22, 10, 0)
        assertEquals(listOf(s to e), splitAtMidnights(s, e, zone, extraCuts = listOf(s, e)))
        assertEquals(listOf(
            ms(2026, 9, 4, 23, 30) to ms(2026, 9, 4, 23, 45),
            ms(2026, 9, 4, 23, 45) to ms(2026, 9, 5, 0, 0),
            ms(2026, 9, 5, 0, 0) to ms(2026, 9, 5, 0, 10),
            ms(2026, 9, 5, 0, 10) to ms(2026, 9, 5, 0, 30),
        ), splitAtMidnights(ms(2026, 9, 4, 23, 30), ms(2026, 9, 5, 0, 30), zone,
            extraCuts = listOf(ms(2026, 9, 4, 23, 45), ms(2026, 9, 5, 0, 10))))
    }

    @Test fun buildSessionRowsCarriesTaskIdOnEverySegment() {
        val rows = buildSessionRows(7L, 1000, 5000, zone, taskId = 42L, extraCuts = listOf(3000))
        assertEquals(listOf(1000L to 3000L, 3000L to 5000L), rows.map { it.startAt to it.endAt })
        assertEquals(listOf(42L, 42L), rows.map { it.taskId })
        assertEquals(listOf(7L, 7L), rows.map { it.profileId })
        // 默认参数:既有调用方零改动,taskId 为 null
        assertEquals(listOf<Long?>(null), buildSessionRows(1L, 1000, 5000, zone).map { it.taskId })
    }

    // ---- v2.1 Task 3 fix:taskCuts 让一次调用内各段分属不同任务 ----

    /** 09:00-10:00、09:30 从任务 A 切到任务 B → 前段 A、后段 B(断言具体归属,不是"都有值") */
    @Test fun taskCutsBindEachSegmentToItsOwnTask() {
        val s = ms(2026, 9, 22, 9, 0)
        val cut = ms(2026, 9, 22, 9, 30)
        val rows = buildSessionRows(7L, s, ms(2026, 9, 22, 10, 0), zone,
            taskId = 1L, taskCuts = listOf(cut to 2L))
        assertEquals(listOf(s to cut, cut to ms(2026, 9, 22, 10, 0)), rows.map { it.startAt to it.endAt })
        assertEquals(listOf(1L, 2L), rows.map { it.taskId })
    }

    /** 唯一 taskCuts 项在窗口起点之前:切点不产生边界(开区间外),但 id 延续到整窗 */
    @Test fun taskCutBeforeWindowStartBindsWholeWindow() {
        val s = ms(2026, 9, 22, 9, 0)
        val rows = buildSessionRows(7L, s, s + 600_000L, zone, taskId = 1L,
            taskCuts = listOf((s - 300_000L) to 2L))
        assertEquals(listOf(s to s + 600_000L), rows.map { it.startAt to it.endAt })
        assertEquals(listOf(2L), rows.map { it.taskId })
    }

    /** 切点正好落在段起点:不产生零长段,但归属从第一段就生效(<= 起点即命中) */
    @Test fun taskCutAtWindowStartBindsFromFirstSegment() {
        val s = ms(2026, 9, 22, 9, 0)
        val rows = buildSessionRows(7L, s, s + 600_000L, zone, taskId = 1L, taskCuts = listOf(s to 3L))
        assertEquals(listOf(3L), rows.map { it.taskId })
    }

    /** 显式 null = 从此段起未绑定,不回退到参数 taskId */
    @Test fun explicitNullTaskCutClearsBindingInsteadOfFallingBack() {
        val rows = buildSessionRows(7L, 1000L, 5000L, zone, taskId = 1L, taskCuts = listOf(1000L to null))
        assertEquals(listOf<Long?>(null), rows.map { it.taskId })
    }

    /** extraCuts 与 taskCuts 同时传:合并成一条边界列表,归属只看 taskCuts */
    @Test fun extraCutsAndTaskCutsShareOneBoundaryList() {
        val rows = buildSessionRows(7L, 1000L, 5000L, zone, taskId = 1L,
            extraCuts = listOf(2000L), taskCuts = listOf(4000L to 2L))
        assertEquals(listOf(1000L to 2000L, 2000L to 4000L, 4000L to 5000L), rows.map { it.startAt to it.endAt })
        assertEquals(listOf(1L, 1L, 2L), rows.map { it.taskId })
    }
}

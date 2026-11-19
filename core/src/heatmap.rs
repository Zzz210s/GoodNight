//! 热力图数据:52 周 × 7 天,五档分级,连续记录天数。

use crate::analytics::percentages;
use crate::model::Session;
use crate::time::{add_days, day_start, next_day_start, start_of_week_monday};

/// 五档上界(分钟):0 / <60 / <120 / <240 / ≥240。
pub const HEATMAP_THRESHOLDS: [i64; 3] = [60, 120, 240];

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum HeatLevel {
    None,
    Low,
    Medium,
    High,
    Full,
}

impl HeatLevel {
    pub fn as_index(self) -> usize {
        match self {
            HeatLevel::None => 0,
            HeatLevel::Low => 1,
            HeatLevel::Medium => 2,
            HeatLevel::High => 3,
            HeatLevel::Full => 4,
        }
    }
}

pub fn heat_level(ms: i64) -> HeatLevel {
    if ms <= 0 {
        return HeatLevel::None;
    }
    let minutes = ms / 60_000;
    if minutes < HEATMAP_THRESHOLDS[0] {
        HeatLevel::Low
    } else if minutes < HEATMAP_THRESHOLDS[1] {
        HeatLevel::Medium
    } else if minutes < HEATMAP_THRESHOLDS[2] {
        HeatLevel::High
    } else {
        HeatLevel::Full
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct HeatmapDay {
    pub day: i64,
    pub total_ms: i64,
    pub study_work_ms: i64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HeatmapWeek {
    pub week_start: i64,
    /// 七天,周一在前;`None` = 未来日期,不画格子。
    pub cells: [Option<HeatmapDay>; 7],
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct HeatmapStats {
    pub active_days: i64,
    pub current_streak: i64,
    pub total_ms: i64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Heatmap {
    pub weeks: Vec<HeatmapWeek>,
    pub stats: HeatmapStats,
    pub max_ms: i64,
}

/// 最近 `weeks` 周(周一为首列),最后一列含今天;今天之后留空。
pub fn build_heatmap(sessions: &[Session], weeks: usize, now: i64) -> Heatmap {
    let today = day_start(now);
    let week_count = weeks.max(1);
    let first_week = start_of_week_monday(add_days(today, -7 * (week_count as i64 - 1)));

    let mut result = Vec::with_capacity(week_count);
    let mut by_day: Vec<HeatmapDay> = Vec::new();

    for w in 0..week_count {
        let week_start = add_days(first_week, 7 * w as i64);
        let mut cells: [Option<HeatmapDay>; 7] = [None; 7];
        for (d, cell) in cells.iter_mut().enumerate() {
            let day = add_days(week_start, d as i64);
            if day > today {
                continue;
            }
            let end = next_day_start(day);
            let mut total_ms = 0i64;
            let mut study_work_ms = 0i64;
            for session in sessions {
                let ms = session.overlap_ms(day, end);
                if ms <= 0 {
                    continue;
                }
                total_ms += ms;
                if crate::categories::is_study_work(&session.category) {
                    study_work_ms += ms;
                }
            }
            let entry = HeatmapDay { day, total_ms, study_work_ms };
            by_day.push(entry);
            *cell = Some(entry);
        }
        result.push(HeatmapWeek { week_start, cells });
    }

    let active_days = by_day.iter().filter(|d| d.total_ms > 0).count() as i64;
    let total_ms: i64 = by_day.iter().map(|d| d.total_ms).sum();
    let max_ms = by_day.iter().map(|d| d.total_ms).max().unwrap_or(0);

    // 今天还没记录时,连续天数从昨天往前算;空一整天就断。
    let mut cursor = today;
    if value_on(&by_day, cursor) <= 0 {
        cursor = add_days(cursor, -1);
    }
    let mut current_streak = 0i64;
    while value_on(&by_day, cursor) > 0 {
        current_streak += 1;
        cursor = add_days(cursor, -1);
    }

    Heatmap {
        weeks: result,
        stats: HeatmapStats { active_days, current_streak, total_ms },
        max_ms,
    }
}

fn value_on(days: &[HeatmapDay], day: i64) -> i64 {
    days.iter().find(|d| d.day == day).map(|d| d.total_ms).unwrap_or(0)
}

/// 各分类在某区间的占比,给饼图用。
pub fn category_shares(ms: &[i64]) -> Vec<i64> {
    percentages(ms)
}

//! 区间内的每日强度:周视图一排七格,月视图按周对齐的日历网格。

use crate::heatmap::heat_level;
use crate::model::Session;
use crate::time::{add_days, calendar_day_count, day_start, next_day_start, start_of_week_monday};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct DayTotal {
    pub day: i64,
    pub total_ms: i64,
    /// 0..=4,与热力图同一套阈值。
    pub level: usize,
}

fn total_for(sessions: &[Session], start: i64, end: i64) -> DayTotal {
    let mut total_ms = 0i64;
    for session in sessions {
        let ms = session.overlap_ms(start, end);
        if ms > 0 {
            total_ms += ms;
        }
    }
    DayTotal {
        day: start,
        total_ms,
        level: heat_level(total_ms).as_index(),
    }
}

/// 区间内每一天(左闭右开),不足一天的部分按本地日边界切。
pub fn days_in_range(sessions: &[Session], start: i64, end: i64) -> Vec<DayTotal> {
    let mut days = Vec::new();
    let mut cursor = day_start(start);
    while cursor < end {
        let day_end = next_day_start(cursor).min(end);
        let clamped_start = cursor.max(start);
        days.push(total_for(sessions, clamped_start, day_end));
        cursor = next_day_start(cursor);
    }
    days
}

/// 周视图:固定七格,周一在前。
pub fn week_days(sessions: &[Session], start: i64) -> Vec<DayTotal> {
    (0..7)
        .map(|offset| {
            let day = add_days(start, offset);
            total_for(sessions, day, next_day_start(day))
        })
        .collect()
}

/// 月视图:按周对齐成 7 列的日历,首尾留空为 None。
pub fn month_grid(sessions: &[Session], start: i64, end: i64) -> Vec<Option<DayTotal>> {
    let mut grid: Vec<Option<DayTotal>> = Vec::new();

    let first = day_start(start);
    let week_start = start_of_week_monday(first);
    let lead = calendar_day_count(week_start, first).max(0) as usize;
    grid.extend(std::iter::repeat(None).take(lead));

    for day in days_in_range(sessions, start, end) {
        grid.push(Some(day));
    }

    while grid.len() % 7 != 0 {
        grid.push(None);
    }
    grid
}

/// 有记录的天数与总时长,给区间摘要用。
pub fn range_summary(days: &[DayTotal]) -> (i64, i64) {
    let active = days.iter().filter(|d| d.total_ms > 0).count() as i64;
    let total = days.iter().map(|d| d.total_ms).sum();
    (active, total)
}

//! 统计区间:周(周一起)、月、以及它们的平移与标签。

use chrono::Datelike;

use crate::time::{add_days, day_start, next_day_start, shift_month, start_of_month, start_of_week_monday};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Range {
    pub start: i64,
    pub end: i64,
}

impl Range {
    pub fn contains(&self, ms: i64) -> bool {
        ms >= self.start && ms < self.end
    }
}

/// 本周:周一 00:00 到下周一 00:00。
pub fn week_range(ms: i64) -> Range {
    let start = start_of_week_monday(day_start(ms));
    Range { start, end: add_days(start, 7) }
}

/// 平移若干周,返回目标周内任意一天(用于再算区间)。
pub fn shift_weeks(ms: i64, delta: i64) -> i64 {
    add_days(start_of_week_monday(day_start(ms)), 7 * delta)
}

/// 本月:1 日 00:00 到下月 1 日。
pub fn month_range(ms: i64) -> Range {
    let start = start_of_month(ms);
    Range { start, end: shift_month(start, 1) }
}

pub fn shift_months(ms: i64, delta: i32) -> i64 {
    shift_month(start_of_month(ms), delta)
}

/// 单日区间。
pub fn day_range(ms: i64) -> Range {
    let start = day_start(ms);
    Range { start, end: next_day_start(start) }
}

/// `11月11日 – 11月17日`。
pub fn week_label(ms: i64) -> String {
    let range = week_range(ms);
    let last_day = add_days(range.start, 6);
    format!("{} – {}", short_date(range.start), short_date(last_day))
}

/// `2026年11月`。
pub fn month_label(ms: i64) -> String {
    use chrono::{Local, TimeZone};
    let dt = Local
        .timestamp_millis_opt(start_of_month(ms))
        .single()
        .unwrap_or_else(|| Local.timestamp_millis_opt(0).unwrap());
    format!("{}年{}月", dt.year(), dt.month())
}

/// `11月11日`。
pub fn short_date(ms: i64) -> String {
    use chrono::{Local, TimeZone};
    let dt = Local
        .timestamp_millis_opt(day_start(ms))
        .single()
        .unwrap_or_else(|| Local.timestamp_millis_opt(0).unwrap());
    format!("{}月{}日", dt.month(), dt.day())
}

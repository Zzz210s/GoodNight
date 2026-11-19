//! 区间每日强度:周视图七格、月视图日历网格。

use tally_core::intensity::{month_grid, range_summary, week_days};
use tally_core::model::Session;
use tally_core::ranges::{month_range, shift_months, week_range};
use tally_core::time::weekday_label;

const MIN: i64 = 60_000;

fn at(y: i32, m: u32, d: u32, hh: u32, mm: u32) -> i64 {
    use chrono::{Local, TimeZone};
    Local.with_ymd_and_hms(y, m, d, hh, mm, 0).single().expect("valid").timestamp_millis()
}

fn session(start: i64, minutes: i64) -> Session {
    Session {
        id: format!("s{start}"),
        activity: "写代码".into(),
        category: "work".into(),
        started_at: start,
        ended_at: start + minutes * MIN,
        created_at: start,
    }
}

#[test]
fn week_days_are_seven_monday_first() {
    let wednesday = at(2026, 11, 18, 10, 0);
    let start = week_range(wednesday).start;
    let days = week_days(&[session(at(2026, 11, 18, 9, 0), 70)], start);

    assert_eq!(days.len(), 7);
    assert_eq!(weekday_label(days[0].day), "周一");
    assert_eq!(weekday_label(days[6].day), "周日");
    assert_eq!(days[2].total_ms, 70 * MIN, "周三那段记在周三");
    assert_eq!(days[2].level, 2, "70 分钟落在第二档");
    assert_eq!(days[0].level, 0, "没记录的日子为空档");
}

#[test]
fn month_grid_starts_on_monday_and_fills_weeks() {
    let anchor = at(2026, 11, 18, 12, 0);
    let range = month_range(anchor);
    // 2026-11-01 是周日,所以前面应留 6 个空位。
    let grid = month_grid(&[session(at(2026, 11, 5, 9, 0), 150)], range.start, range.end);

    assert_eq!(weekday_label(grid[6].unwrap().day), "周日", "第 7 格是 11 月 1 日");
    assert!(grid[0..6].iter().all(|cell| cell.is_none()), "前 6 格是上月留白");
    assert_eq!(grid.len() % 7, 0, "必须凑满整周");

    let days: Vec<_> = grid.iter().flatten().collect();
    assert_eq!(days.len(), 30, "11 月有 30 天");
    let fifth = grid
        .iter()
        .flatten()
        .find(|d| d.day == at(2026, 11, 5, 0, 0))
        .expect("11月5日存在");
    assert_eq!(fifth.total_ms, 150 * MIN);
    assert_eq!(fifth.level, 3, "150 分钟落在第三档");
}

#[test]
fn month_grid_tail_is_padded() {
    let anchor = at(2026, 12, 10, 12, 0);
    let range = month_range(anchor);
    let grid = month_grid(&[], range.start, range.end);
    assert_eq!(grid.len() % 7, 0);
    assert_eq!(grid.iter().flatten().count(), 31, "12 月有 31 天");
}

#[test]
fn summary_counts_active_days() {
    let start = week_range(at(2026, 11, 18, 9, 0)).start;
    let days = week_days(
        &[
            session(at(2026, 11, 16, 9, 0), 30),
            session(at(2026, 11, 18, 9, 0), 45),
        ],
        start,
    );
    let (active, total) = range_summary(&days);
    assert_eq!(active, 2);
    assert_eq!(total, 75 * MIN);
}

#[test]
fn shifting_month_keeps_grid_consistent() {
    let previous = shift_months(at(2026, 1, 15, 0, 0), -1);
    let range = month_range(previous);
    let grid = month_grid(&[], range.start, range.end);
    assert_eq!(grid.iter().flatten().count(), 31, "2025 年 12 月有 31 天");
}

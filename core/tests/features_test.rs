//! 区间、环形图几何、计划三类功能的行为测试。

use tally_core::donut::build_donut;
use tally_core::model::PlanSection;
use tally_core::plans::{add_plan, delete_plan, plans_for, section_summary, toggle_plan};
use tally_core::ranges::{day_range, month_range, month_label, shift_months, shift_weeks, week_label, week_range};
use tally_core::store::Database;
use tally_core::time::weekday_label;

const MIN: i64 = 60_000;

fn at(y: i32, m: u32, d: u32, hh: u32, mm: u32) -> i64 {
    use chrono::{Local, TimeZone};
    Local
        .with_ymd_and_hms(y, m, d, hh, mm, 0)
        .single()
        .expect("valid local time")
        .timestamp_millis()
}

#[test]
fn week_starts_on_monday_and_spans_seven_days() {
    let wednesday = at(2026, 11, 18, 15, 0);
    let range = week_range(wednesday);
    assert_eq!(weekday_label(range.start), "周一");
    assert_eq!(range.end - range.start, 7 * 24 * 60 * MIN);
    assert!(range.contains(wednesday));

    let sunday = at(2026, 11, 22, 23, 0);
    assert_eq!(week_range(sunday).start, range.start, "周日仍属同一周");
}

#[test]
fn week_shift_moves_by_seven_days() {
    let base = at(2026, 11, 18, 12, 0);
    let prev = shift_weeks(base, -1);
    let next = shift_weeks(base, 1);
    assert_eq!(next - week_range(base).start, 7 * 24 * 60 * MIN);
    assert_eq!(week_range(base).start - prev, 7 * 24 * 60 * MIN);
    assert_eq!(week_label(base), "11月16日 – 11月22日");
}

#[test]
fn month_range_covers_whole_month() {
    let ms = at(2026, 11, 18, 9, 0);
    let range = month_range(ms);
    assert_eq!(range.start, at(2026, 11, 1, 0, 0));
    assert_eq!(range.end, at(2026, 12, 1, 0, 0));
    assert_eq!(month_label(ms), "2026年11月");

    let jan = shift_months(at(2026, 1, 15, 0, 0), -1);
    assert_eq!(month_label(jan), "2025年12月", "跨年回退正确");
}

#[test]
fn day_range_is_local_midnight_to_midnight() {
    let range = day_range(at(2026, 11, 18, 13, 37));
    assert_eq!(range.start, at(2026, 11, 18, 0, 0));
    assert_eq!(range.end, at(2026, 11, 19, 0, 0));
}

fn cat(color: &str, ms: i64) -> (String, String, i64) {
    (String::new(), color.to_string(), ms)
}

#[test]
fn donut_makes_one_slice_per_category() {
    let slices = build_donut(
        &[cat("#2a78d6", 60 * MIN), cat("#eb6834", 40 * MIN)],
        100.0,
        100.0,
        80.0,
        50.0,
    );
    assert_eq!(slices.len(), 2);
    assert_eq!(slices.iter().map(|s| s.percent).sum::<i64>(), 100);
    assert_eq!(slices[0].percent, 60);
    assert_eq!(slices[1].percent, 40);
    for slice in &slices {
        assert!(slice.path.starts_with('M'), "路径必须以 M 开头");
        assert!(slice.path.ends_with('Z'), "路径必须闭合");
        assert!(slice.path.contains('A'), "必须含弧线段");
    }
    // 角度必须首尾相接且总覆盖 2π
    let total: f64 = slices.iter().map(|s| s.end - s.start).sum();
    assert!(total < std::f64::consts::PI * 2.0, "扇区之间要留缝");
    assert!(total > std::f64::consts::PI * 1.9);
}

#[test]
fn donut_handles_single_and_empty_input() {
    let one = build_donut(&[cat("#008300", 30 * MIN)], 10.0, 10.0, 8.0, 4.0);
    assert_eq!(one.len(), 1);
    assert_eq!(one[0].percent, 100);
    assert!(one[0].path.matches('A').count() >= 4, "整环需要两段外弧加两段内弧");

    let none = build_donut(&[cat("#000000", 0)], 10.0, 10.0, 8.0, 4.0);
    assert!(none.is_empty(), "零时长不画扇区");
}

#[test]
fn plans_add_toggle_and_delete() {
    let mut db = Database::default();
    let item = add_plan(&mut db, PlanSection::Week, "写周报", "work", None).expect("created");
    assert_eq!(db.plans.len(), 1);
    assert!(item.period_start.is_some(), "周计划要记住所属那周");
    assert!(item.completed_at.is_none());

    assert!(add_plan(&mut db, PlanSection::Week, "   ", "work", None).is_none());

    assert!(toggle_plan(&mut db, &item.id));
    assert!(db.plans[0].completed_at.is_some());
    assert!(toggle_plan(&mut db, &item.id));
    assert!(db.plans[0].completed_at.is_none());

    assert!(delete_plan(&mut db, &item.id));
    assert!(db.plans.is_empty());
    assert!(!delete_plan(&mut db, "no-such-id"));
}

#[test]
fn plans_are_filtered_by_section_and_sorted() {
    let mut db = Database::default();
    add_plan(&mut db, PlanSection::Week, "本周第一件", "work", None).unwrap();
    let done = add_plan(&mut db, PlanSection::Week, "已经做完", "study", None).unwrap();
    add_plan(&mut db, PlanSection::Month, "本月目标", "life", None).unwrap();

    toggle_plan(&mut db, &done.id);

    let now = {
        use std::time::{SystemTime, UNIX_EPOCH};
        SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_millis() as i64
    };
    let week = plans_for(&db, PlanSection::Week, now);
    assert_eq!(week.len(), 2);
    assert!(week[0].completed_at.is_none(), "未完成的排在前面");
    assert!(week[1].completed_at.is_some());

    let month = plans_for(&db, PlanSection::Month, now);
    assert_eq!(month.len(), 1);
    assert_eq!(section_summary(&week), "2 项 · 已完成 1");
    assert_eq!(section_summary(&[]), "暂无");
}

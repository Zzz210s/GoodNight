//! 核心行为测试:统计口径、跨午夜裁剪、占比合计、热力图分级与连续性。

use tally_core::analytics::{allocate_shares, build_breakdown, percentages};
use tally_core::categories::{all_categories, new_custom_category, resolve};
use tally_core::heatmap::{build_heatmap, heat_level, HeatLevel};
use tally_core::model::Session;
use tally_core::time::{day_start, format_duration, next_day_start, start_of_week_monday};

const MIN: i64 = 60_000;

fn session(id: &str, activity: &str, category: &str, start: i64, minutes: i64) -> Session {
    Session {
        id: id.to_string(),
        activity: activity.to_string(),
        category: category.to_string(),
        started_at: start,
        ended_at: start + minutes * MIN,
        created_at: start,
    }
}

fn at(y: i32, m: u32, d: u32, hh: u32, mm: u32) -> i64 {
    use chrono::{Local, TimeZone};
    Local
        .with_ymd_and_hms(y, m, d, hh, mm, 0)
        .single()
        .expect("valid local time")
        .timestamp_millis()
}

#[test]
fn breakdown_groups_by_category_then_activity() {
    let day = at(2026, 11, 10, 0, 0);
    let end = next_day_start(day);
    let sessions = vec![
        session("a", "写代码", "work", at(2026, 11, 10, 9, 0), 60),
        session("b", "写代码", "work", at(2026, 11, 10, 11, 0), 30),
        session("c", "读论文", "study", at(2026, 11, 10, 14, 0), 30),
        session("d", "午休", "rest", at(2026, 11, 10, 12, 0), 30),
    ];
    let breakdown = build_breakdown(&sessions, &[], day, end);

    assert_eq!(breakdown.total_ms, 150 * MIN);
    assert_eq!(breakdown.study_work_ms, 120 * MIN);
    assert_eq!(breakdown.categories.len(), 3);

    let work = breakdown.categories.iter().find(|c| c.id == "work").unwrap();
    assert_eq!(work.ms, 90 * MIN);
    assert_eq!(work.activities.len(), 1, "同名活动应合并");
    assert_eq!(work.activities[0].ms, 90 * MIN);

    let percentages: i64 = breakdown.categories.iter().map(|c| c.percentage).sum();
    assert_eq!(percentages, 100, "占比合计必须为 100");
    assert_eq!(work.percentage, 60);
}

#[test]
fn breakdown_clips_records_across_midnight() {
    let day = at(2026, 11, 10, 0, 0);
    let end = next_day_start(day);
    let sessions = vec![session("a", "夜读", "study", at(2026, 11, 10, 23, 30), 60)];
    let today = build_breakdown(&sessions, &[], day, end);
    assert_eq!(today.total_ms, 30 * MIN, "昨天那段只算 30 分钟");

    let tomorrow = next_day_start(day);
    let tomorrow_end = next_day_start(tomorrow);
    let next = build_breakdown(&sessions, &[], tomorrow, tomorrow_end);
    assert_eq!(next.total_ms, 30 * MIN, "今天那段也只算 30 分钟");
}

#[test]
fn sessions_outside_range_are_ignored() {
    let day = at(2026, 11, 10, 0, 0);
    let end = next_day_start(day);
    let sessions = vec![session("old", "旧记录", "work", day - 10 * 86_400_000, 30)];
    let breakdown = build_breakdown(&sessions, &[], day, end);
    assert_eq!(breakdown.total_ms, 0);
    assert!(breakdown.categories.is_empty());
}

#[test]
fn shares_always_sum_to_total() {
    assert_eq!(percentages(&[1, 1, 1]), vec![34, 33, 33]);
    assert_eq!(percentages(&[1, 1, 1]).iter().sum::<i64>(), 100);
    assert_eq!(percentages(&[5, 0, 0]), vec![100, 0, 0]);
    assert_eq!(percentages(&[1, 2, 3, 4]), vec![10, 20, 30, 40]);
    assert_eq!(percentages(&[]), Vec::<i64>::new());
    assert_eq!(percentages(&[0, 0]), vec![0, 0]);
    assert_eq!(allocate_shares(&[1, 1], 5), vec![3, 2], "余数按入参顺序分配");
}

#[test]
fn custom_categories_live_after_builtins() {
    assert_eq!(all_categories(&[]).len(), 6);
    let created = new_custom_category("阅读", &[]).expect("created");
    assert_eq!(created.label, "阅读");
    assert!(created.id.starts_with("custom-"));
    assert_eq!(created.color, "#7b61c9");

    let list = vec![created.clone()];
    assert_eq!(all_categories(&list).len(), 7);
    assert_eq!(all_categories(&list)[6].id, created.id);
    assert_eq!(resolve(&list, &created.id).label, "阅读");
    assert_eq!(resolve(&list, "does-not-exist").id, "other");
}

#[test]
fn custom_category_rejects_blank_and_duplicate() {
    assert!(new_custom_category("   ", &[]).is_none());
    assert!(new_custom_category("工作", &[]).is_none(), "内置分类不许重名");
    let first = new_custom_category("阅读", &[]).unwrap();
    assert!(new_custom_category("阅读", &[first]).is_none());
    assert_eq!(new_custom_category("abcdefgh", &[]).unwrap().label, "abcdefgh".chars().take(6).collect::<String>());
}

#[test]
fn heatmap_levels_match_thresholds() {
    assert_eq!(heat_level(0), HeatLevel::None);
    assert_eq!(heat_level(30 * MIN), HeatLevel::Low);
    assert_eq!(heat_level(59 * MIN), HeatLevel::Low);
    assert_eq!(heat_level(60 * MIN), HeatLevel::Medium);
    assert_eq!(heat_level(120 * MIN), HeatLevel::High);
    assert_eq!(heat_level(239 * MIN), HeatLevel::High);
    assert_eq!(heat_level(240 * MIN), HeatLevel::Full);
    assert_eq!(heat_level(240 * MIN).as_index(), 4);
}

#[test]
fn heatmap_counts_streak_and_skips_future() {
    let now = at(2026, 11, 10, 15, 0);
    let today = day_start(now);
    let sessions = vec![
        session("a", "写代码", "work", at(2026, 11, 10, 9, 0), 30),
        session("b", "写代码", "work", at(2026, 11, 9, 9, 0), 30),
        session("c", "写代码", "work", at(2026, 11, 8, 9, 0), 30),
    ];
    let heatmap = build_heatmap(&sessions, 52, now);

    assert_eq!(heatmap.weeks.len(), 52);
    assert_eq!(heatmap.stats.active_days, 3);
    assert_eq!(heatmap.stats.current_streak, 3);
    assert_eq!(heatmap.stats.total_ms, 90 * MIN);

    let last_week = heatmap.weeks.last().unwrap();
    let today_index = last_week
        .cells
        .iter()
        .position(|c| c.map(|d| d.day == today).unwrap_or(false))
        .expect("今天的格子存在");
    for cell in last_week.cells.iter().skip(today_index + 1) {
        assert!(cell.is_none(), "未来日期不画格子");
    }
    assert_eq!(last_week.week_start, start_of_week_monday(today));
}

#[test]
fn heatmap_streak_allows_today_empty() {
    let now = at(2026, 11, 10, 8, 0);
    let sessions = vec![session("b", "写代码", "work", at(2026, 11, 9, 9, 0), 30)];
    let heatmap = build_heatmap(&sessions, 52, now);
    assert_eq!(heatmap.stats.current_streak, 1, "今天还没记录,连续从昨天续");
    assert_eq!(heatmap.stats.active_days, 1);
}

#[test]
fn durations_read_naturally() {
    assert_eq!(format_duration(0), "0秒");
    assert_eq!(format_duration(45 * 1000), "45秒");
    assert_eq!(format_duration(23 * MIN), "23分");
    assert_eq!(format_duration(60 * MIN), "1小时");
    assert_eq!(format_duration(83 * MIN), "1小时23分");
}

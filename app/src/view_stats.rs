//! 分析页与计划页的模型:环形图路径、周/月日历强度、分类摘要、计划条目。

use slint::{ModelRc, VecModel};
use tally_core::donut::build_donut;
use tally_core::intensity::{month_grid, range_summary, week_days};
use tally_core::model::PlanSection;
use tally_core::plans::{plans_for, section_summary};
use tally_core::ranges::{day_range, month_label, month_range, week_label, week_range};
use tally_core::time::{format_date_label, format_duration};
use tally_core::DayTotal;
use tally_core::{build_breakdown, Database};

use crate::view::{chip_color, clock_label, now_ms, Shared};
use crate::{CategoryRow, DonutSlice, MainWindow, PlanRow, RangeCell, TimelineRow};

/// 环形图占据的正方形边长与内外半径。
const DONUT_BOX: f64 = 128.0;
const DONUT_OUTER: f64 = 60.0;
const DONUT_INNER: f64 = 34.0;

/// 五档强度配色:从「没有记录」到「四小时以上」。
pub const HEAT_COLORS: [&str; 5] = ["#eeece4", "#cfe3d4", "#9fc9a8", "#66a677", "#2f7a45"];
const WEEKDAY_SHORT: [&str; 7] = ["一", "二", "三", "四", "五", "六", "日"];

/// 当前统计区间:period 0 今日、1 本周、2 本月。
pub fn range_for(period: i32, anchor: i64) -> (i64, i64, String) {
    match period {
        1 => {
            let range = week_range(anchor);
            (range.start, range.end, week_label(anchor))
        }
        2 => {
            let range = month_range(anchor);
            (range.start, range.end, month_label(anchor))
        }
        _ => {
            let range = day_range(anchor);
            (range.start, range.end, format_date_label(anchor))
        }
    }
}

fn text_color(level: usize) -> slint::Color {
    if level >= 3 {
        slint::Color::from_rgb_u8(255, 255, 255)
    } else {
        chip_color("#0b0b0b")
    }
}

fn fill_color(level: usize) -> slint::Color {
    chip_color(HEAT_COLORS[level.min(4)])
}

/// 一格里的时长文案:不足一小时用分钟,超过用一位小数的小时。
fn compact(ms: i64) -> String {
    if ms <= 0 {
        return String::new();
    }
    let minutes = ms / 60_000;
    if minutes < 60 {
        format!("{minutes}m")
    } else {
        format!("{:.1}h", ms as f64 / 3_600_000.0)
    }
}

fn donut_rows(breakdown: &tally_core::Breakdown, center: f64) -> Vec<DonutSlice> {
    let values: Vec<(String, String, i64)> = breakdown
        .categories
        .iter()
        .map(|c| (c.id.clone(), c.color.clone(), c.ms))
        .collect();
    build_donut(&values, center, center, DONUT_OUTER, DONUT_INNER)
        .into_iter()
        .map(|slice| DonutSlice {
            path: slice.path.as_str().into(),
            color: chip_color(&slice.color),
        })
        .collect()
}

fn category_rows(breakdown: &tally_core::Breakdown) -> Vec<CategoryRow> {
    breakdown
        .categories
        .iter()
        .filter(|c| c.ms > 0)
        .map(|c| CategoryRow {
            id: c.id.as_str().into(),
            label: c.label.as_str().into(),
            color: chip_color(&c.color),
            amount: format_duration(c.ms).as_str().into(),
            percent: format!("{}%", c.percentage).as_str().into(),
        })
        .collect()
}

fn entry_rows(db: &Database, start: i64, end: i64) -> Vec<TimelineRow> {
    tally_core::timeline::build_day_timeline(&db.sessions, &db.categories, start, end)
        .into_iter()
        .map(|entry| TimelineRow {
            key: entry.key.as_str().into(),
            label: entry.label.as_str().into(),
            color: chip_color(&entry.color),
            time: format!("{}–{}", clock_label(entry.started_at), clock_label(entry.ended_at))
                .as_str()
                .into(),
            duration: format_duration(entry.ms).as_str().into(),
        })
        .collect()
}

fn week_cells(days: &[DayTotal]) -> Vec<RangeCell> {
    days.iter()
        .enumerate()
        .map(|(index, day)| RangeCell {
            label: WEEKDAY_SHORT[index.min(6)].into(),
            hint: compact(day.total_ms).as_str().into(),
            color: fill_color(day.level),
            text_color: text_color(day.level),
        })
        .collect()
}

fn month_rows(grid: &[Option<DayTotal>]) -> Vec<Vec<RangeCell>> {
    grid.chunks(7)
        .map(|row| {
            row.iter()
                .map(|cell| match cell {
                    Some(day) => RangeCell {
                        label: day_number(day.day).as_str().into(),
                        hint: "".into(),
                        color: fill_color(day.level),
                        text_color: text_color(day.level),
                    },
                    None => RangeCell {
                        label: "".into(),
                        hint: "".into(),
                        color: slint::Color::from_argb_u8(0, 0, 0, 0),
                        text_color: slint::Color::from_argb_u8(0, 0, 0, 0),
                    },
                })
                .collect()
        })
        .collect()
}

fn day_number(day: i64) -> String {
    use chrono::{Local, TimeZone};
    Local
        .timestamp_millis_opt(day)
        .single()
        .map(|dt| dt.format("%-d").to_string())
        .unwrap_or_default()
}

fn to_rows_model(rows: Vec<Vec<RangeCell>>) -> ModelRc<ModelRc<RangeCell>> {
    let outer: Vec<ModelRc<RangeCell>> = rows
        .into_iter()
        .map(|row| ModelRc::new(VecModel::from(row)))
        .collect();
    ModelRc::new(VecModel::from(outer))
}

/// 刷新分析页。`anchor` 是当前查看的那一天(周/月按它定位)。
pub fn refresh_stats(ui: &MainWindow, db: &Shared, anchor: i64) {
    let period = ui.get_stats_period();
    let (start, end, label) = range_for(period, anchor);
    let store = db.borrow();

    ui.set_stats_range(label.as_str().into());

    let breakdown = build_breakdown(&store.sessions, &store.categories, start, end);
    ui.set_stats_total(format_duration(breakdown.total_ms).as_str().into());
    ui.set_stats_study(format_duration(breakdown.study_work_ms).as_str().into());
    ui.set_stats_percent(if breakdown.total_ms > 0 {
        ((breakdown.study_work_ms as f64 * 100.0) / breakdown.total_ms as f64).round() as i32
    } else {
        0
    });
    ui.set_donut_slices(donut_rows(&breakdown, DONUT_BOX / 2.0).as_slice().into());
    ui.set_cat_summary(category_rows(&breakdown).as_slice().into());

    let entries = if period == 0 {
        entry_rows(&store, start, end)
    } else {
        Vec::new()
    };
    ui.set_stats_entries(entries.as_slice().into());

    // 周与月直接把每日强度铺在页面里,不再有单独的热力图页。
    if period == 1 {
        let days = week_days(&store.sessions, start);
        let (active, total) = range_summary(&days);
        ui.set_week_cells(week_cells(&days).as_slice().into());
        ui.set_month_rows(to_rows_model(Vec::new()));
        ui.set_range_note(format_summary(active, total).as_str().into());
    } else if period == 2 {
        let grid = month_grid(&store.sessions, start, end);
        let days: Vec<DayTotal> = grid.iter().flatten().copied().collect();
        let (active, total) = range_summary(&days);
        ui.set_month_rows(to_rows_model(month_rows(&grid)));
        ui.set_week_cells(Vec::new().as_slice().into());
        ui.set_range_note(format_summary(active, total).as_str().into());
    } else {
        ui.set_week_cells(Vec::new().as_slice().into());
        ui.set_month_rows(to_rows_model(Vec::new()));
        ui.set_range_note("".into());
    }
}

fn format_summary(active: i64, total: i64) -> String {
    if active == 0 {
        return "这几天还没有记录".to_string();
    }
    format!("{active} 天有记录 · 合计 {}", format_duration(total))
}

/// 刷新计划页当前分区的条目。
pub fn refresh_plans(ui: &MainWindow, db: &Shared) {
    let section = match ui.get_plan_section() {
        1 => PlanSection::Month,
        2 => PlanSection::Deadline,
        _ => PlanSection::Week,
    };
    let store = db.borrow();
    let items = plans_for(&store, section, now_ms());
    ui.set_plan_summary(section_summary(&items).as_str().into());
    let rows: Vec<PlanRow> = items
        .iter()
        .map(|item| {
            let color = tally_core::categories::resolve(&store.categories, &item.category).color;
            PlanRow {
                id: item.id.as_str().into(),
                title: item.title.as_str().into(),
                done: item.completed_at.is_some(),
                color: chip_color(&color),
            }
        })
        .collect();
    ui.set_plan_rows(rows.as_slice().into());
}

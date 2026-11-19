//! 分析页与计划页的模型:环形图路径、热力图网格、分类摘要、计划条目。

use slint::{ModelRc, VecModel};
use tally_core::donut::build_donut;
use tally_core::heatmap::build_heatmap;
use tally_core::model::PlanSection;
use tally_core::plans::{plans_for, section_summary};
use tally_core::ranges::{day_range, month_label, month_range, week_label, week_range};
use tally_core::time::{format_date_label, format_duration};
use tally_core::{build_breakdown, Database};

use crate::view::{chip_color, clock_label, now_ms, Shared};
use crate::{
    CategoryRow, DonutSlice, HeatCell, MainWindow, PlanRow, TimelineRow,
};

/// 环形图占据的正方形边长与内外半径。
const DONUT_BOX: f64 = 168.0;
const DONUT_OUTER: f64 = 80.0;
const DONUT_INNER: f64 = 46.0;

/// 五档热度配色,与图例一致。
pub const HEAT_COLORS: [&str; 5] = ["#eeece4", "#cfe3d4", "#9fc9a8", "#66a677", "#2f7a45"];

/// 当前统计区间:period 0 今日、1 本周、2 本月、3 热力图。
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
        3 => {
            let range = day_range(anchor);
            (range.start, range.end, "最近 52 周".to_string())
        }
        _ => {
            let range = day_range(anchor);
            (range.start, range.end, format_date_label(anchor))
        }
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

fn heat_model(db: &Database) -> (ModelRc<ModelRc<HeatCell>>, String) {
    let heatmap = build_heatmap(&db.sessions, 52, now_ms());
    let weeks: Vec<ModelRc<HeatCell>> = heatmap
        .weeks
        .iter()
        .map(|week| {
            let cells: Vec<HeatCell> = week
                .cells
                .iter()
                .map(|cell| match cell {
                    Some(day) => {
                        let level = tally_core::heatmap::heat_level(day.total_ms).as_index();
                        HeatCell {
                            level: level as i32,
                            color: chip_color(HEAT_COLORS[level]),
                        }
                    }
                    None => HeatCell {
                        level: 0,
                        color: chip_color(HEAT_COLORS[0]),
                    },
                })
                .collect();
            ModelRc::new(VecModel::from(cells))
        })
        .collect();

    let stats = format!(
        "52 周 · {} 天有记录 · 连续 {} 天 · 合计 {}",
        heatmap.stats.active_days,
        heatmap.stats.current_streak,
        format_duration(heatmap.stats.total_ms)
    );
    (ModelRc::new(VecModel::from(weeks)), stats)
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
    ui.set_donut_slices(
        donut_rows(&breakdown, DONUT_BOX / 2.0).as_slice().into(),
    );
    ui.set_cat_summary(category_rows(&breakdown).as_slice().into());

    let entries = if period == 0 {
        entry_rows(&store, start, end)
    } else {
        Vec::new()
    };
    ui.set_stats_entries(entries.as_slice().into());

    let (weeks, stats) = heat_model(&store);
    ui.set_heat_weeks(weeks);
    ui.set_heat_stats(stats.as_str().into());
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

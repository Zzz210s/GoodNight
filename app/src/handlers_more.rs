//! 分析页与计划页的交互:切换区间、前后翻页、计划增删改。

use std::cell::Cell;
use std::path::PathBuf;
use std::rc::Rc;

use slint::ComponentHandle;
use tally_core::model::PlanSection;
use tally_core::plans::{add_plan, delete_plan, toggle_plan};
use tally_core::ranges::{shift_months, shift_weeks};

use crate::view::refresh;
use crate::view_stats::{refresh_plans, refresh_stats};
use crate::view::Shared;
use crate::MainWindow;

/// 当前查看的那一天;周/月翻页只动它。
pub type Anchor = Rc<Cell<i64>>;

pub fn wire_more(ui: &MainWindow, db: Shared, path: PathBuf, anchor: Anchor) {
    wire_period(ui, anchor.clone());
    wire_shift(ui, db.clone(), anchor);
    wire_plan_section(ui, db.clone());
    wire_plan_actions(ui, db, path);
}

fn wire_period(ui: &MainWindow, anchor: Anchor) {
    let ui_weak = ui.as_weak();
    ui.on_set_stats_period(move |period| {
        let Some(ui) = ui_weak.upgrade() else { return };
        // 切到今日或热力图时把锚点拉回今天,避免翻过页后回不到本周。
        if period == 0 || period == 3 {
            anchor.set(crate::view::now_ms());
        }
        ui.set_stats_period(period);
    });
}

fn wire_shift(ui: &MainWindow, db: Shared, anchor: Anchor) {
    let ui_weak = ui.as_weak();
    ui.on_shift_stats(move |delta| {
        let Some(ui) = ui_weak.upgrade() else { return };
        let period = ui.get_stats_period();
        if period != 1 && period != 2 {
            return;
        }
        let current = anchor.get();
        let moved = if period == 1 {
            shift_weeks(current, delta as i64)
        } else {
            shift_months(current, delta)
        };
        anchor.set(moved);
        refresh_stats(&ui, &db, moved);
    });
}

fn wire_plan_section(ui: &MainWindow, db: Shared) {
    let ui_weak = ui.as_weak();
    ui.on_set_plan_section(move |section| {
        let Some(ui) = ui_weak.upgrade() else { return };
        ui.set_plan_section(section);
        ui.set_plan_draft("".into());
        refresh_plans(&ui, &db);
    });
}

fn wire_plan_actions(ui: &MainWindow, db: Shared, path: PathBuf) {
    {
        let ui_weak = ui.as_weak();
        let db = db.clone();
        let path = path.clone();
        ui.on_add_plan(move |title| {
            let Some(ui) = ui_weak.upgrade() else { return };
            let section = match ui.get_plan_section() {
                1 => PlanSection::Month,
                2 => PlanSection::Deadline,
                _ => PlanSection::Week,
            };
            let category = db.borrow().prefs.last_category.clone();
            let mut store = db.borrow_mut();
            if add_plan(&mut store, section, title.as_str(), &category, None).is_some() {
                let _ = store.save(&path);
                drop(store);
                ui.set_plan_draft("".into());
                refresh_plans(&ui, &db);
            }
        });
    }

    {
        let ui_weak = ui.as_weak();
        let db = db.clone();
        let path = path.clone();
        ui.on_toggle_plan(move |id| {
            let Some(ui) = ui_weak.upgrade() else { return };
            let mut store = db.borrow_mut();
            if toggle_plan(&mut store, id.as_str()) {
                let _ = store.save(&path);
            }
            drop(store);
            refresh_plans(&ui, &db);
        });
    }

    {
        let ui_weak = ui.as_weak();
        let db = db.clone();
        let path = path.clone();
        ui.on_remove_plan(move |id| {
            let Some(ui) = ui_weak.upgrade() else { return };
            let mut store = db.borrow_mut();
            if delete_plan(&mut store, id.as_str()) {
                let _ = store.save(&path);
            }
            drop(store);
            refresh_plans(&ui, &db);
            refresh(&ui, &db);
        });
    }
}

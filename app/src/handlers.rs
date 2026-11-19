//! 界面上每个交互对应的动作:改状态、落盘、刷新。

use std::path::PathBuf;

use slint::ComponentHandle;

use tally_core::categories::resolve;
use tally_core::model::{Session, TimerState};

use crate::view::{now_ms, open_sheet, refresh, Shared};
use crate::MainWindow;

const TARGET_STEPS: [i64; 5] = [0, 15, 25, 45, 90];

pub fn wire(ui: &MainWindow, db: Shared, path: PathBuf) {
    wire_start_stop(ui, db.clone(), path.clone());
    wire_save(ui, db.clone(), path.clone());
    wire_discard(ui, db.clone(), path.clone());
    wire_categories(ui, db.clone(), path.clone());
    wire_target(ui, db.clone(), path);
    wire_tabs(ui);
}

fn wire_start_stop(ui: &MainWindow, db: Shared, path: PathBuf) {
    let ui_weak = ui.as_weak();
    ui.on_start_stop(move || {
        let Some(ui) = ui_weak.upgrade() else { return };
        let mut store = db.borrow_mut();
        match store.timer.clone() {
            TimerState::Idle => {
                store.timer = TimerState::Running {
                    id: uuid::Uuid::new_v4().to_string(),
                    started_at: now_ms(),
                    prefill: None,
                };
                let _ = store.save(&path);
                drop(store);
                refresh(&ui, &db);
            }
            TimerState::Running { id, started_at, prefill } => {
                let ended = now_ms().max(started_at);
                store.timer = TimerState::Pending {
                    id,
                    started_at,
                    ended_at: ended,
                    prefill,
                };
                let _ = store.save(&path);
                drop(store);
                refresh(&ui, &db);
                open_sheet(&ui, &db);
            }
            TimerState::Pending { .. } => {
                drop(store);
                open_sheet(&ui, &db);
            }
        }
    });
}

fn wire_save(ui: &MainWindow, db: Shared, path: PathBuf) {
    let ui_weak = ui.as_weak();
    ui.on_save_session(move |activity, category| {
        let Some(ui) = ui_weak.upgrade() else { return };
        let activity = activity.trim().to_string();
        if activity.is_empty() {
            ui.set_sheet_error("先写一下做了什么".into());
            return;
        }
        let mut store = db.borrow_mut();
        let state = store.timer.clone();
        let TimerState::Pending { id, started_at, ended_at, .. } = state else {
            return;
        };

        // 不足 1 分钟视为误触,与原版一致:不落库。
        if ended_at - started_at < 60_000 {
            store.timer = TimerState::Idle;
            let _ = store.save(&path);
            drop(store);
            ui.set_sheet_visible(false);
            ui.set_draft_activity("".into());
            refresh(&ui, &db);
            return;
        }

        let category_id = resolve(&store.categories, &category).id;
        store.append_session(Session {
            id,
            activity,
            category: category_id.clone(),
            started_at,
            ended_at,
            created_at: now_ms(),
        });
        store.timer = TimerState::Idle;
        store.prefs.last_category = category_id;
        let _ = store.save(&path);
        drop(store);
        ui.set_sheet_visible(false);
        ui.set_draft_activity("".into());
        refresh(&ui, &db);
    });
}

fn wire_discard(ui: &MainWindow, db: Shared, path: PathBuf) {
    let ui_weak = ui.as_weak();
    ui.on_discard_session(move || {
        let Some(ui) = ui_weak.upgrade() else { return };
        let mut store = db.borrow_mut();
        store.timer = TimerState::Idle;
        let _ = store.save(&path);
        drop(store);
        ui.set_sheet_visible(false);
        ui.set_draft_activity("".into());
        refresh(&ui, &db);
    });
}

fn wire_categories(ui: &MainWindow, db: Shared, path: PathBuf) {
    {
        let ui_weak = ui.as_weak();
        ui.on_select_category(move |id| {
            if let Some(ui) = ui_weak.upgrade() {
                ui.set_draft_category(id);
            }
        });
    }
    {
        let ui_weak = ui.as_weak();
        ui.on_start_new_category(move || {
            if let Some(ui) = ui_weak.upgrade() {
                ui.set_creating_category(true);
                ui.set_new_category_name("".into());
            }
        });
    }
    {
        let ui_weak = ui.as_weak();
        ui.on_cancel_new_category(move || {
            if let Some(ui) = ui_weak.upgrade() {
                ui.set_creating_category(false);
                ui.set_new_category_name("".into());
            }
        });
    }
    let ui_weak = ui.as_weak();
    ui.on_submit_new_category(move |name| {
        let Some(ui) = ui_weak.upgrade() else { return };
        let mut store = db.borrow_mut();
        match store.add_category(name.as_str()) {
            Some(created) => {
                let _ = store.save(&path);
                let id = created.id.clone();
                drop(store);
                ui.set_creating_category(false);
                ui.set_new_category_name("".into());
                ui.set_sheet_error("".into());
                ui.set_draft_category(id.as_str().into());
                refresh(&ui, &db);
            }
            None => {
                drop(store);
                ui.set_sheet_error("名字为空或已存在".into());
            }
        }
    });
}

fn wire_target(ui: &MainWindow, db: Shared, path: PathBuf) {
    let ui_weak = ui.as_weak();
    ui.on_cycle_target(move || {
        let Some(ui) = ui_weak.upgrade() else { return };
        let mut store = db.borrow_mut();
        let index = TARGET_STEPS
            .iter()
            .position(|v| *v == store.prefs.target_minutes)
            .unwrap_or(0);
        store.prefs.target_minutes = TARGET_STEPS[(index + 1) % TARGET_STEPS.len()];
        let _ = store.save(&path);
        drop(store);
        refresh(&ui, &db);
    });
}

fn wire_tabs(ui: &MainWindow) {
    let ui_weak = ui.as_weak();
    ui.on_set_tab(move |tab| {
        if let Some(ui) = ui_weak.upgrade() {
            ui.set_tab(tab);
        }
    });
}

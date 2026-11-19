//! 共享状态、颜色解析,以及把核心数据转成界面模型。

use std::cell::RefCell;
use std::rc::Rc;
use std::time::{SystemTime, UNIX_EPOCH};

use slint::{ModelRc, VecModel};
use tally_core::categories::{all_categories, resolve};
use tally_core::model::TimerState;
use tally_core::time::{format_date_label, format_duration, format_elapsed};
use tally_core::Database;

use crate::{CategoryData, MainWindow};

pub type Shared = Rc<RefCell<Database>>;

const CHIPS_PER_ROW: usize = 4;

pub fn now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

pub fn clock_label(ms: i64) -> String {
    tally_core::format_clock(ms)
}

/// `#rrggbb` → Slint 颜色。解析失败时回落到中性灰。
pub fn chip_color(hex: &str) -> slint::Color {
    let raw = hex.trim_start_matches('#');
    if raw.len() == 6 {
        if let (Ok(r), Ok(g), Ok(b)) = (
            u8::from_str_radix(&raw[0..2], 16),
            u8::from_str_radix(&raw[2..4], 16),
            u8::from_str_radix(&raw[4..6], 16),
        ) {
            return slint::Color::from_rgb_u8(r, g, b);
        }
    }
    slint::Color::from_rgb_u8(137, 135, 129)
}

fn chips(db: &Database) -> Vec<CategoryData> {
    all_categories(&db.categories)
        .into_iter()
        .map(|category| CategoryData {
            id: category.id.as_str().into(),
            label: category.label.as_str().into(),
            color: chip_color(&category.color),
        })
        .collect()
}

/// 分类 chips 按每行 4 个切好,界面不做布局计算。
fn category_rows(db: &Database) -> Vec<Vec<CategoryData>> {
    chips(db).chunks(CHIPS_PER_ROW).map(|c| c.to_vec()).collect()
}

fn to_rows_model(rows: Vec<Vec<CategoryData>>) -> ModelRc<ModelRc<CategoryData>> {
    let outer: Vec<ModelRc<CategoryData>> = rows
        .into_iter()
        .map(|row| ModelRc::new(VecModel::from(row)))
        .collect();
    ModelRc::new(VecModel::from(outer))
}

/// 把库里的状态整体刷到界面:计时页与今日分析共用一次遍历。
pub fn refresh(ui: &MainWindow, db: &Shared) {
    let now = now_ms();
    let store = db.borrow();

    let (status, elapsed, hint, button, running) = match store.timer.clone() {
        TimerState::Idle => ("准备开始", 0, "准备好后开始", "开始", false),
        TimerState::Running { started_at, .. } => {
            ("计时中", now - started_at, "点按钮结束并记录", "结束", true)
        }
        TimerState::Pending { started_at, ended_at, .. } => {
            ("等待记录", ended_at - started_at, "点按钮填写记录", "记录", false)
        }
    };

    ui.set_status_label(status.into());
    ui.set_elapsed_label(format_elapsed(elapsed).into());
    ui.set_hint_label(hint.into());
    ui.set_button_label(button.into());
    ui.set_running(running);
    ui.set_target_minutes(store.prefs.target_minutes as i32);

    if running && store.prefs.target_minutes > 0 {
        let remain = store.prefs.target_minutes * 60_000 - elapsed;
        let label = if remain >= 0 {
            format!("剩余 {}", format_elapsed(remain))
        } else {
            format!("超时 {}", format_elapsed(-remain))
        };
        ui.set_remain_label(label.into());
        ui.set_over_target(remain < 0);
    } else {
        ui.set_remain_label("".into());
        ui.set_over_target(false);
    }

    ui.set_categories(chips(&store).as_slice().into());
    ui.set_category_rows(to_rows_model(category_rows(&store)));
    ui.set_date_label(format_date_label(now).into());
}

/// 打开记录弹层:区间文案、活动名与分类都从待记录状态推导。
pub fn open_sheet(ui: &MainWindow, db: &Shared) {
    let store = db.borrow();
    if let TimerState::Pending { started_at, ended_at, prefill, .. } = &store.timer {
        ui.set_sheet_range(
            format!(
                "{} · {} – {}",
                format_duration(ended_at - started_at),
                clock_label(*started_at),
                clock_label(*ended_at)
            )
            .as_str()
            .into(),
        );
        let activity = prefill.as_ref().map(|p| p.activity.clone()).unwrap_or_default();
        ui.set_draft_activity(activity.as_str().into());
        let category = prefill
            .as_ref()
            .map(|p| p.category.clone())
            .unwrap_or_else(|| store.prefs.last_category.clone());
        ui.set_draft_category(resolve(&store.categories, &category).id.as_str().into());
    }
    drop(store);
    ui.set_sheet_error("".into());
    ui.set_creating_category(false);
    ui.set_new_category_name("".into());
    ui.set_sheet_visible(true);
}

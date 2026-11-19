//! Tally 入口:装配状态、界面与定时刷新。业务在 core,交互在 handlers,显示转换在 view。

slint::include_modules!();

mod handlers;
mod handlers_more;
mod view;
mod view_stats;

use std::cell::{Cell, RefCell};
use std::rc::Rc;
use std::time::Duration;

use tally_core::Database;

fn main() -> Result<(), slint::PlatformError> {
    let data_path = tally_core::default_data_path();
    let db = Rc::new(RefCell::new(
        Database::load(&data_path).unwrap_or_default(),
    ));

    let ui = MainWindow::new()?;
    let anchor: Rc<Cell<i64>> = Rc::new(Cell::new(view::now_ms()));

    view::refresh(&ui, &db);
    view_stats::refresh_stats(&ui, &db, anchor.get());
    view_stats::refresh_plans(&ui, &db);
    handlers::wire(&ui, db.clone(), data_path.clone());
    handlers_more::wire_more(&ui, db.clone(), data_path.clone(), anchor.clone());

    // 每 500 ms 刷新一次;时间由墙钟推导,后台节流不会算错。
    let timer = slint::Timer::default();
    {
        let ui_weak = ui.as_weak();
        let db = db.clone();
        let anchor = anchor.clone();
        timer.start(
            slint::TimerMode::Repeated,
            Duration::from_millis(500),
            move || {
                if let Some(ui) = ui_weak.upgrade() {
                    view::refresh(&ui, &db);
                    view_stats::refresh_stats(&ui, &db, anchor.get());
                }
            },
        );
    }

    {
        let store = db.borrow();
        println!(
            "[tally] 数据 {} · 会话 {} 条 · 自定义分类 {} 个",
            data_path.display(),
            store.sessions.len(),
            store.categories.len()
        );
    }

    let result = ui.run();
    drop(timer);
    result
}

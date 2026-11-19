//! Tally 核心:与 UI 无关的模型、存储与统计。Android 与 Windows 共用。

pub mod analytics;
pub mod categories;
pub mod donut;
pub mod heatmap;
pub mod intensity;
pub mod model;
pub mod plans;
pub mod ranges;
pub mod store;
pub mod time;
pub mod timeline;

pub use analytics::{build_breakdown, Breakdown, CategoryGroup};
pub use timeline::{build_day_timeline, TimelineEntry};
pub use categories::{all_categories, base_categories, is_study_work, resolve};
pub use donut::{build_donut, Slice};
pub use ranges::{day_range, month_range, week_range, Range};
pub use heatmap::{build_heatmap, heat_level, HeatLevel, Heatmap};
pub use intensity::{days_in_range, month_grid, range_summary, week_days, DayTotal};
pub use model::{Category, PlanItem, PlanSection, Prefs, Session, TimerState};
pub use store::{default_data_path, Database};
pub use time::{day_start, format_clock, format_date_label, format_duration, format_elapsed};

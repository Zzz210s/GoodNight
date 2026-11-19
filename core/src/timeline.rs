//! 当日时间轴:把会话裁剪到当天,按开始时间排序。

use crate::analytics::normalize_label;
use crate::model::{Category, Session};

/// 一天里的时间轴条目:同一活动、连续记录会并成一条显示。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TimelineEntry {
    pub key: String,
    pub label: String,
    pub category: String,
    pub color: String,
    pub started_at: i64,
    pub ended_at: i64,
    pub ms: i64,
}

/// 某一天的时间轴,按开始时间升序;跨午夜的部分按当天裁剪。
pub fn build_day_timeline(
    sessions: &[Session],
    custom: &[Category],
    day: i64,
    day_end: i64,
) -> Vec<TimelineEntry> {
    let mut entries: Vec<TimelineEntry> = sessions
        .iter()
        .filter_map(|session| {
            let start = session.started_at.max(day);
            let end = session.ended_at.min(day_end);
            if end <= start {
                return None;
            }
            let category = crate::categories::resolve(custom, &session.category);
            Some(TimelineEntry {
                key: format!("{}|{}", session.category, normalize_label(&session.activity)),
                label: normalize_label(&session.activity),
                category: category.id,
                color: category.color,
                started_at: start,
                ended_at: end,
                ms: end - start,
            })
        })
        .collect();
    entries.sort_by_key(|entry| entry.started_at);
    entries
}

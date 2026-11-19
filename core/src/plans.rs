//! 计划:周计划 / 月计划 / Deadline 三类条目的增删与完成。

use crate::model::{PlanItem, PlanSection};
use crate::ranges::{month_range, week_range};
use crate::store::Database;
use crate::time::description;

fn now_ms() -> i64 {
    use std::time::{SystemTime, UNIX_EPOCH};
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

/// 条目归属的周期起点:周计划取周一、月计划取 1 日,Deadline 无周期。
pub fn period_start_for(section: PlanSection, today: i64) -> Option<i64> {
    match section {
        PlanSection::Week => Some(week_range(today).start),
        PlanSection::Month => Some(month_range(today).start),
        PlanSection::Deadline => None,
    }
}

/// 新增条目。标题为空时返回 `None`。
pub fn add_plan(
    db: &mut Database,
    section: PlanSection,
    title: &str,
    category: &str,
    due_at: Option<i64>,
) -> Option<PlanItem> {
    let title = title.trim();
    if title.is_empty() {
        return None;
    }
    let now = now_ms();
    let item = PlanItem {
        id: format!("plan-{:x}", now),
        section,
        title: title.chars().take(60).collect(),
        category: category.to_string(),
        period_start: period_start_for(section, now),
        due_at: match section {
            PlanSection::Deadline => due_at.or(Some(crate::time::day_start(now))),
            _ => None,
        },
        completed_at: None,
        created_at: now,
        updated_at: now,
    };
    db.plans.push(item.clone());
    Some(item)
}

pub fn toggle_plan(db: &mut Database, id: &str) -> bool {
    let now = now_ms();
    match db.plans.iter_mut().find(|p| p.id == id) {
        Some(item) => {
            item.completed_at = if item.completed_at.is_some() { None } else { Some(now) };
            item.updated_at = now;
            true
        }
        None => false,
    }
}

pub fn delete_plan(db: &mut Database, id: &str) -> bool {
    let before = db.plans.len();
    db.plans.retain(|p| p.id != id);
    db.plans.len() != before
}

/// 某个分区当前周期的条目:已完成沉底,其余按创建时间升序。
pub fn plans_for(db: &Database, section: PlanSection, today: i64) -> Vec<PlanItem> {
    let period = period_start_for(section, today);
    let mut items: Vec<PlanItem> = db
        .plans
        .iter()
        .filter(|p| p.section == section)
        .filter(|p| match (period, p.period_start) {
            (Some(expected), Some(actual)) => expected == actual,
            _ => true,
        })
        .cloned()
        .collect();
    items.sort_by(|a, b| {
        a.completed_at
            .is_some()
            .cmp(&b.completed_at.is_some())
            .then(a.created_at.cmp(&b.created_at))
    });
    items
}

/// 供界面显示的一行文案:`3 项 · 已完成 1`。
pub fn section_summary(items: &[PlanItem]) -> String {
    let done = items.iter().filter(|p| p.completed_at.is_some()).count();
    description(items.len(), done)
}

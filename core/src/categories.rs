//! 分类表:内置六个(固定 id、固定颜色)+ 用户自定义。

use crate::model::Category;

pub const DEFAULT_CATEGORY_ID: &str = "other";

/// 内置分类。顺序固定,颜色跟分类走、不跟排名走。
pub fn base_categories() -> Vec<Category> {
    vec![
        Category { id: "work".into(), label: "工作".into(), color: "#2a78d6".into(), custom: false },
        Category { id: "study".into(), label: "学习".into(), color: "#eb6834".into(), custom: false },
        Category { id: "rest".into(), label: "休息".into(), color: "#1baf7a".into(), custom: false },
        Category { id: "exercise".into(), label: "运动".into(), color: "#eda100".into(), custom: false },
        Category { id: "life".into(), label: "生活".into(), color: "#e87ba4".into(), custom: false },
        Category { id: DEFAULT_CATEGORY_ID.into(), label: "其他".into(), color: "#008300".into(), custom: false },
    ]
}

/// 自定义分类可选色板,按创建顺序轮转。
pub const CUSTOM_COLORS: [&str; 6] = [
    "#7b61c9", "#0f9fb5", "#c9538f", "#8a6d3b", "#4b8f3a", "#b3502a",
];

/// 内置 + 自定义,自定义永远排在后面。
pub fn all_categories(custom: &[Category]) -> Vec<Category> {
    let mut list = base_categories();
    list.extend(custom.iter().cloned());
    list
}

/// 未知 id(旧数据)回落到「其他」,避免出现无色分类。
pub fn resolve(custom: &[Category], id: &str) -> Category {
    all_categories(custom)
        .into_iter()
        .find(|c| c.id == id)
        .unwrap_or_else(|| {
            base_categories()
                .into_iter()
                .find(|c| c.id == DEFAULT_CATEGORY_ID)
                .expect("default category exists")
        })
}

/// 是否属于「学习 + 工作」——统计口径里唯一的一处特殊分组。
pub fn is_study_work(id: &str) -> bool {
    id == "work" || id == "study"
}

/// 新自定义分类:名字最多 6 字、颜色按已有数量轮转、id 带 `custom-` 前缀。
pub fn new_custom_category(label: &str, existing: &[Category]) -> Option<Category> {
    let trimmed: String = label.trim().chars().take(6).collect();
    if trimmed.is_empty() {
        return None;
    }
    if all_categories(existing).iter().any(|c| c.label == trimmed) {
        return None;
    }
    let color = CUSTOM_COLORS[existing.len() % CUSTOM_COLORS.len()].to_string();
    Some(Category {
        id: format!("custom-{:x}", now_millis()),
        label: trimmed,
        color,
        custom: true,
    })
}

fn now_millis() -> i64 {
    use std::time::{SystemTime, UNIX_EPOCH};
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

//! 统计:区间内的分类/活动分布。全部纯函数,输入相同输出相同。

use std::collections::HashMap;

use crate::categories::{all_categories, is_study_work};
use crate::model::{Category, Session};

/// 一个活动的聚合。`key` = `分类|活动名`,与 Tally 的选中逻辑一致。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ActivityGroup {
    pub key: String,
    pub label: String,
    pub category: String,
    pub color: String,
    pub ms: i64,
    pub percentage: i64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CategoryGroup {
    pub id: String,
    pub label: String,
    pub color: String,
    pub ms: i64,
    pub percentage: i64,
    pub activities: Vec<ActivityGroup>,
}

#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct Breakdown {
    pub total_ms: i64,
    pub study_work_ms: i64,
    pub categories: Vec<CategoryGroup>,
    /// 展平后的活动(按分类顺序),饼图外环用。
    pub activities: Vec<ActivityGroup>,
}

pub(crate) fn normalize_label(activity: &str) -> String {
    activity.split_whitespace().collect::<Vec<_>>().join(" ")
}

/// 最大余数法(纯整数):整数份额之和恰好等于 `total`,余数相同时按入参顺序。
pub fn allocate_shares(values: &[i64], total: i64) -> Vec<i64> {
    let sum: i64 = values.iter().sum();
    if sum <= 0 || total <= 0 || values.is_empty() {
        return vec![0; values.len()];
    }

    let mut shares: Vec<i64> = values.iter().map(|v| v * total / sum).collect();
    let mut remainders: Vec<(i64, usize)> = values
        .iter()
        .enumerate()
        .map(|(index, v)| ((v * total) % sum, index))
        .collect();
    remainders.sort_by(|a, b| b.0.cmp(&a.0).then(a.1.cmp(&b.1)));

    let mut assigned: i64 = shares.iter().sum();
    let mut cursor = 0usize;
    while assigned < total {
        let (_, index) = remainders[cursor % remainders.len()];
        shares[index] += 1;
        assigned += 1;
        cursor += 1;
    }
    shares
}

/// 百分比(整数,最大余数法),有数据时合计恒为 100。
pub fn percentages(values: &[i64]) -> Vec<i64> {
    allocate_shares(values, 100)
}

#[derive(Clone)]
struct Acc {
    label: String,
    first_seen: i64,
    ms: i64,
}

/// 按区间统计分类与活动;跨午夜、跨边界按重叠时长裁剪。
pub fn build_breakdown(
    sessions: &[Session],
    custom: &[Category],
    start: i64,
    end: i64,
) -> Breakdown {
    let mut by_category: HashMap<String, HashMap<String, Acc>> = HashMap::new();
    let mut total_ms = 0i64;
    let mut study_work_ms = 0i64;

    for session in sessions {
        let ms = session.overlap_ms(start, end);
        if ms <= 0 {
            continue;
        }
        total_ms += ms;
        if is_study_work(&session.category) {
            study_work_ms += ms;
        }
        let label = normalize_label(&session.activity);
        let bucket = by_category.entry(session.category.clone()).or_default();
        match bucket.get_mut(&label) {
            Some(acc) => {
                acc.ms += ms;
                acc.first_seen = acc.first_seen.min(session.started_at);
            }
            None => {
                bucket.insert(
                    label.clone(),
                    Acc { label, first_seen: session.started_at, ms },
                );
            }
        }
    }

    let mut categories = Vec::new();
    let mut activities_out = Vec::new();

    for category in all_categories(custom) {
        let Some(map) = by_category.get(&category.id) else {
            continue;
        };
        let mut activities: Vec<Acc> = map.values().cloned().collect();
        activities.sort_by(|a, b| b.ms.cmp(&a.ms).then(a.first_seen.cmp(&b.first_seen)));

        let activity_shares = percentages(&activities.iter().map(|a| a.ms).collect::<Vec<_>>());
        let groups: Vec<ActivityGroup> = activities
            .iter()
            .zip(activity_shares)
            .map(|(activity, percentage)| ActivityGroup {
                key: format!("{}|{}", category.id, activity.label),
                label: activity.label.clone(),
                category: category.id.clone(),
                color: category.color.clone(),
                ms: activity.ms,
                percentage,
            })
            .collect();

        let category_ms: i64 = activities.iter().map(|a| a.ms).sum();
        categories.push(CategoryGroup {
            id: category.id.clone(),
            label: category.label.clone(),
            color: category.color.clone(),
            ms: category_ms,
            percentage: 0,
            activities: groups.clone(),
        });
        activities_out.extend(groups);
    }

    let category_shares = percentages(&categories.iter().map(|c| c.ms).collect::<Vec<_>>());
    for (category, share) in categories.iter_mut().zip(category_shares) {
        category.percentage = share;
    }

    Breakdown { total_ms, study_work_ms, categories, activities: activities_out }
}

//! 数据模型:与 Tally 0.10.0 的字段一一对应,保证旧备份可以导入。

use serde::{Deserialize, Serialize};

/// 分类。内置六个 + 用户自定义;`custom` 只用于界面区分,不参与统计。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Category {
    pub id: String,
    pub label: String,
    /// `#rrggbb`
    pub color: String,
    #[serde(default)]
    pub custom: bool,
}

/// 一段被记录的时间。`started_at` / `ended_at` 是本地时区的 epoch 毫秒。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Session {
    pub id: String,
    pub activity: String,
    pub category: String,
    pub started_at: i64,
    pub ended_at: i64,
    pub created_at: i64,
}

impl Session {
    /// 与区间 `[start, end)` 的重叠毫秒。跨午夜、跨边界都靠它裁剪。
    pub fn overlap_ms(&self, start: i64, end: i64) -> i64 {
        (self.ended_at.min(end) - self.started_at.max(start)).max(0)
    }

    pub fn duration_ms(&self) -> i64 {
        (self.ended_at - self.started_at).max(0)
    }
}

/// 计时器状态机:空闲 → 运行中 → 待填记录。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "status", rename_all = "lowercase")]
pub enum TimerState {
    Idle,
    Running {
        id: String,
        started_at: i64,
        #[serde(skip_serializing_if = "Option::is_none", default)]
        prefill: Option<TimerPrefill>,
    },
    Pending {
        id: String,
        started_at: i64,
        ended_at: i64,
        #[serde(skip_serializing_if = "Option::is_none", default)]
        prefill: Option<TimerPrefill>,
    },
}

impl Default for TimerState {
    fn default() -> Self {
        TimerState::Idle
    }
}

/// 从计划项启动计时时带上的快照。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct TimerPrefill {
    pub plan_id: String,
    pub activity: String,
    pub category: String,
}

/// 跨启动保存的用户偏好。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Prefs {
    #[serde(default = "default_category")]
    pub last_category: String,
    /// 计时目标(分钟),0 = 未设定。只影响提示,不改会话数据。
    #[serde(default)]
    pub target_minutes: i64,
}

fn default_category() -> String {
    crate::categories::DEFAULT_CATEGORY_ID.to_string()
}

impl Default for Prefs {
    fn default() -> Self {
        Self {
            last_category: default_category(),
            target_minutes: 0,
        }
    }
}

/// 计划分区:周计划、月计划、Deadline。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum PlanSection {
    Week,
    Month,
    Deadline,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct PlanItem {
    pub id: String,
    pub section: PlanSection,
    pub title: String,
    pub category: String,
    /// 周计划 = 周一 00:00,月计划 = 当月 1 日 00:00;Deadline 为 None。
    pub period_start: Option<i64>,
    /// Deadline 的到期日 00:00;其它分区为 None。
    pub due_at: Option<i64>,
    pub completed_at: Option<i64>,
    pub created_at: i64,
    pub updated_at: i64,
}

/// 计划页三个区块的显示偏好。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct PlanPrefs {
    pub show_week: bool,
    pub show_month: bool,
    pub show_deadlines: bool,
    pub section_order: Vec<PlanSection>,
}

impl Default for PlanPrefs {
    fn default() -> Self {
        Self {
            show_week: true,
            show_month: true,
            show_deadlines: true,
            section_order: vec![PlanSection::Week, PlanSection::Month, PlanSection::Deadline],
        }
    }
}

//! 本地存储:一个 JSON 文件,原子写入。23 KB 量级的数据不需要数据库。

use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

use crate::model::{Category, PlanItem, PlanPrefs, Prefs, Session, TimerState};

/// 与 Tally 0.10.0 的备份结构对齐,可直接导入它的导出。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
pub struct Database {
    #[serde(default)]
    pub sessions: Vec<Session>,
    #[serde(default)]
    pub timer: TimerState,
    #[serde(default)]
    pub prefs: Prefs,
    #[serde(default)]
    pub categories: Vec<Category>,
    #[serde(default)]
    pub plans: Vec<PlanItem>,
    #[serde(default)]
    pub plan_prefs: PlanPrefs,
}

impl Database {
    pub fn load(path: &Path) -> std::io::Result<Self> {
        match fs::read_to_string(path) {
            Ok(raw) => Ok(serde_json::from_str(&raw).unwrap_or_else(|error| {
                eprintln!("[tally] 数据文件损坏,已回落到空库: {error}");
                Database::default()
            })),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(Database::default()),
            Err(error) => Err(error),
        }
    }

    /// 先写临时文件再改名,避免进程被杀时写坏数据。
    pub fn save(&self, path: &Path) -> std::io::Result<()> {
        if let Some(dir) = path.parent() {
            fs::create_dir_all(dir)?;
        }
        let tmp = temp_path(path);
        let json = serde_json::to_vec_pretty(self)?;
        {
            let mut file = fs::File::create(&tmp)?;
            file.write_all(&json)?;
            file.sync_all()?;
        }
        fs::rename(&tmp, path)?;
        Ok(())
    }

    /// 记录一段会话;同 id 已存在时不动(可安全重试)。
    pub fn append_session(&mut self, session: Session) {
        if self.sessions.iter().any(|s| s.id == session.id) {
            return;
        }
        self.sessions.push(session);
    }

    /// 按 id 更新活动名与分类;返回是否命中。
    pub fn update_session(&mut self, id: &str, activity: &str, category: &str) -> bool {
        match self.sessions.iter_mut().find(|s| s.id == id) {
            Some(session) => {
                session.activity = activity.to_string();
                session.category = category.to_string();
                true
            }
            None => false,
        }
    }

    pub fn delete_session(&mut self, id: &str) -> bool {
        let before = self.sessions.len();
        self.sessions.retain(|s| s.id != id);
        self.sessions.len() != before
    }

    /// 新增自定义分类;名字重复或为空时返回 None。
    pub fn add_category(&mut self, label: &str) -> Option<Category> {
        let category = crate::categories::new_custom_category(label, &self.categories)?;
        self.categories.push(category.clone());
        Some(category)
    }

    /// 删除自定义分类;内置分类不可删。历史记录保留,显示时回落到「其他」。
    pub fn remove_category(&mut self, id: &str) -> bool {
        if !id.starts_with("custom-") {
            return false;
        }
        let before = self.categories.len();
        self.categories.retain(|c| c.id != id);
        self.categories.len() != before
    }
}

fn temp_path(path: &Path) -> PathBuf {
    let mut tmp = path.to_path_buf();
    let name = format!(
        "{}.tmp",
        tmp.file_name().and_then(|n| n.to_str()).unwrap_or("tally.json")
    );
    tmp.set_file_name(name);
    tmp
}

/// 默认数据文件位置:Windows → `%APPDATA%\tally\tally.json`;其它平台 → `~/.tally/tally.json`。
pub fn default_data_path() -> PathBuf {
    let dir = if cfg!(windows) {
        std::env::var_os("APPDATA")
            .map(PathBuf::from)
            .unwrap_or_else(|| PathBuf::from("."))
            .join("tally")
    } else {
        std::env::var_os("HOME")
            .map(PathBuf::from)
            .unwrap_or_else(|| PathBuf::from("."))
            .join(".tally")
    };
    dir.join("tally.json")
}

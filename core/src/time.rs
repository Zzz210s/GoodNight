//! 时间工具:本地日边界、时长格式化、时钟标签。全部按本地时区计算。

use chrono::{DateTime, Datelike, Local, NaiveDate, TimeZone, Weekday};

pub const DAY_MS: i64 = 24 * 60 * 60 * 1000;

fn to_local(ms: i64) -> DateTime<Local> {
    Local
        .timestamp_millis_opt(ms)
        .single()
        .unwrap_or_else(|| Local.timestamp_millis_opt(0).unwrap())
}

/// 本地当天 00:00 的 epoch 毫秒。
pub fn day_start(ms: i64) -> i64 {
    let dt = to_local(ms);
    let date = NaiveDate::from_ymd_opt(dt.year(), dt.month(), dt.day()).expect("valid date");
    let midnight = date.and_hms_opt(0, 0, 0).expect("valid midnight");
    Local
        .from_local_datetime(&midnight)
        .earliest()
        .map(|d| d.timestamp_millis())
        .unwrap_or(ms)
}

/// 本地次日 00:00;夏令时切换那天不是 24 小时。
pub fn next_day_start(ms: i64) -> i64 {
    let dt = to_local(day_start(ms));
    let next = dt.date_naive() + chrono::Days::new(1);
    let midnight = next.and_hms_opt(0, 0, 0).expect("valid midnight");
    Local
        .from_local_datetime(&midnight)
        .earliest()
        .map(|d| d.timestamp_millis())
        .unwrap_or(ms + DAY_MS)
}

/// 本地口径加天数(跨夏令时也正确)。
pub fn add_days(ms: i64, amount: i64) -> i64 {
    let dt = to_local(day_start(ms));
    let target = dt.date_naive() + chrono::Days::new(amount.unsigned_abs());
    let target = if amount >= 0 {
        target
    } else {
        dt.date_naive() - chrono::Days::new(amount.unsigned_abs())
    };
    let midnight = target.and_hms_opt(0, 0, 0).expect("valid midnight");
    Local
        .from_local_datetime(&midnight)
        .earliest()
        .map(|d| d.timestamp_millis())
        .unwrap_or(ms)
}

/// 周一 00:00。
pub fn start_of_week_monday(ms: i64) -> i64 {
    let day = day_start(ms);
    let weekday = to_local(day).weekday();
    let offset = match weekday {
        Weekday::Mon => 0,
        Weekday::Tue => 1,
        Weekday::Wed => 2,
        Weekday::Thu => 3,
        Weekday::Fri => 4,
        Weekday::Sat => 5,
        Weekday::Sun => 6,
    };
    add_days(day, -offset)
}

/// 当月 1 日 00:00。
pub fn start_of_month(ms: i64) -> i64 {
    let dt = to_local(day_start(ms));
    let first = NaiveDate::from_ymd_opt(dt.year(), dt.month(), 1).expect("valid first");
    let midnight = first.and_hms_opt(0, 0, 0).expect("valid midnight");
    Local
        .from_local_datetime(&midnight)
        .earliest()
        .map(|d| d.timestamp_millis())
        .unwrap_or(ms)
}

/// 按月平移;返回目标月 1 日 00:00。
pub fn shift_month(ms: i64, delta: i32) -> i64 {
    let dt = to_local(start_of_month(ms));
    let mut year = dt.year();
    let mut month = dt.month() as i32 + delta;
    while month < 1 {
        month += 12;
        year -= 1;
    }
    while month > 12 {
        month -= 12;
        year += 1;
    }
    let first = NaiveDate::from_ymd_opt(year, month as u32, 1).expect("valid first");
    let midnight = first.and_hms_opt(0, 0, 0).expect("valid midnight");
    Local
        .from_local_datetime(&midnight)
        .earliest()
        .map(|d| d.timestamp_millis())
        .unwrap_or(ms)
}

/// 实时计时显示:`HH:MM:SS`,小时可以超过 99。
pub fn format_elapsed(ms: i64) -> String {
    let total = (ms.max(0)) / 1000;
    format!(
        "{:02}:{:02}:{:02}",
        total / 3600,
        (total % 3600) / 60,
        total % 60
    )
}

/// 摘要用时长:`45秒` / `23分` / `1小时23分`。
pub fn format_duration(ms: i64) -> String {
    let total_minutes = (ms.max(0)) / 60_000;
    if total_minutes < 1 {
        return format!("{}秒", (ms.max(0)) / 1000);
    }
    let hours = total_minutes / 60;
    let minutes = total_minutes % 60;
    match (hours, minutes) {
        (0, m) => format!("{m}分"),
        (h, 0) => format!("{h}小时"),
        (h, m) => format!("{h}小时{m}分"),
    }
}

/// 起止时刻:`14:32`。
pub fn format_clock(ms: i64) -> String {
    let dt = to_local(ms);
    format!("{:02}:{:02}", dt.format("%H"), dt.format("%M"))
        .replace(' ', "")
}

const WEEKDAYS: [&str; 7] = ["周一", "周二", "周三", "周四", "周五", "周六", "周日"];

pub fn weekday_label(ms: i64) -> &'static str {
    match to_local(ms).weekday() {
        Weekday::Mon => WEEKDAYS[0],
        Weekday::Tue => WEEKDAYS[1],
        Weekday::Wed => WEEKDAYS[2],
        Weekday::Thu => WEEKDAYS[3],
        Weekday::Fri => WEEKDAYS[4],
        Weekday::Sat => WEEKDAYS[5],
        Weekday::Sun => WEEKDAYS[6],
    }
}

/// 日期标题:`11月1日 周六`。
pub fn format_date_label(ms: i64) -> String {
    let dt = to_local(ms);
    format!("{}月{}日 {}", dt.format("%-m"), dt.format("%-d"), weekday_label(ms))
}

/// 本地日历天数(用 UTC 归一化相减,避免夏令时把 14/15 天边界算错)。
pub fn calendar_day_count(start: i64, end: i64) -> i64 {
    if end <= start {
        return 0;
    }
    let a = to_local(start).date_naive();
    let b = to_local(end).date_naive();
    (b - a).num_days()
}

/// 计划分区摘要:`3 项 · 已完成 1`;空列表给出「暂无」。
pub fn description(total: usize, done: usize) -> String {
    if total == 0 {
        return "暂无".to_string();
    }
    if done == 0 {
        format!("{total} 项")
    } else {
        format!("{total} 项 · 已完成 {done}")
    }
}

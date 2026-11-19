//! 环形图几何:把各分类的时长换成可直接画出来的 SVG 路径。
//!
//! 角度从 12 点方向顺时针分配;扇区之间留 `gap` 弧度,占比过小的扇区不再
//! 单独留缝,避免碎成一片。路径由 Rust 生成,界面只需要 `Path { commands }`。

use std::f64::consts::PI;

#[derive(Debug, Clone, PartialEq)]
pub struct Slice {
    /// SVG 路径命令,绝对坐标。
    pub path: String,
    pub color: String,
    pub start: f64,
    pub end: f64,
    pub percent: i64,
}

const GAP: f64 = 0.012;

fn point(cx: f64, cy: f64, r: f64, angle: f64) -> (f64, f64) {
    // 12 点方向为 0,顺时针为正。
    let a = angle - PI / 2.0;
    (cx + r * a.cos(), cy + r * a.sin())
}

/// 生成环形图扇区。`values` 中非正的项直接跳过。
pub fn build_donut(
    values: &[(String, String, i64)],
    cx: f64,
    cy: f64,
    outer: f64,
    inner: f64,
) -> Vec<Slice> {
    let positives: Vec<&(String, String, i64)> = values.iter().filter(|v| v.2 > 0).collect();
    let total: i64 = positives.iter().map(|v| v.2).sum();
    if total <= 0 || outer <= inner || outer <= 0.0 {
        return Vec::new();
    }

    let single = positives.len() == 1;
    let gap = if single { 0.0 } else { GAP };

    let mut slices = Vec::with_capacity(positives.len());
    let mut cursor = 0.0f64;

    for entry in &positives {
        let sweep = (entry.2 as f64 / total as f64) * PI * 2.0;
        let a0 = cursor + gap / 2.0;
        let a1 = cursor + sweep - gap / 2.0;
        cursor += sweep;

        if a1 <= a0 {
            continue;
        }

        let path = if single {
            full_ring(cx, cy, outer, inner)
        } else {
            let (ox0, oy0) = point(cx, cy, outer, a0);
            let (ix0, iy0) = point(cx, cy, inner, a0);
            let (ix1, iy1) = point(cx, cy, inner, a1);
            let (ox1, oy1) = point(cx, cy, outer, a1);
            let large = if (a1 - a0) > PI { 1 } else { 0 };
            format!(
                "M {ox0:.2} {oy0:.2} A {outer:.2} {outer:.2} 0 {large} 1 {ox1:.2} {oy1:.2} \
                 L {ix1:.2} {iy1:.2} A {inner:.2} {inner:.2} 0 {large} 0 {ix0:.2} {iy0:.2} Z"
            )
        };

        slices.push(Slice {
            path,
            color: entry.1.clone(),
            start: a0,
            end: a1,
            percent: ((entry.2 as f64 * 100.0) / total as f64).round() as i64,
        });
    }

    slices
}

fn full_ring(cx: f64, cy: f64, outer: f64, inner: f64) -> String {
    let (ox, oy) = point(cx, cy, outer, 0.0);
    let (ix, iy) = point(cx, cy, inner, 0.0);
    let (oxb, oyb) = point(cx, cy, outer, PI);
    let (ixb, iyb) = point(cx, cy, inner, PI);
    format!(
        "M {ox:.2} {oy:.2} A {outer:.2} {outer:.2} 0 1 1 {oxb:.2} {oyb:.2} \
         A {outer:.2} {outer:.2} 0 1 1 {ox:.2} {oy:.2} Z \
         M {ix:.2} {iy:.2} A {inner:.2} {inner:.2} 0 1 0 {ixb:.2} {iyb:.2} \
         A {inner:.2} {inner:.2} 0 1 0 {ix:.2} {iy:.2} Z"
    )
}

/// 标签用的锚点:扇区中角处的中径坐标。
pub fn label_anchor(slice: &Slice, cx: f64, cy: f64, radius: f64) -> (f64, f64) {
    point(cx, cy, radius, (slice.start + slice.end) / 2.0)
}

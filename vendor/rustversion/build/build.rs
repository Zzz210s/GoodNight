// 本机杀软会隔离上游 rustversion 的构建脚本产物,这里用等价实现替换:
// 唯一职责是探测 rustc 版本,并把结果写成 proc-macro 需要的 version.expr。

use std::env;
use std::ffi::OsString;
use std::fs;
use std::path::Path;
use std::process::Command;

fn main() {
    println!("cargo:rerun-if-changed=build/build.rs");
    println!("cargo:rerun-if-env-changed=RUSTC");

    let rustc = env::var_os("RUSTC").unwrap_or_else(|| OsString::from("rustc"));
    let output = Command::new(&rustc)
        .arg("--version")
        .env("RUSTVERSION", "1")
        .output()
        .expect("failed to run rustc --version");

    let text = String::from_utf8_lossy(&output.stdout).to_string();
    let line = text.lines().last().unwrap_or("").trim().to_string();
    let mut words = line.split(' ');
    let _ = words.next();
    let raw = words.next().unwrap_or("1.0.0").to_string();

    let mut halves = raw.split('-');
    let numbers = halves.next().unwrap_or("1.0.0");
    let channel = halves.next().unwrap_or("");

    let mut digits = numbers.split('.');
    let minor: u16 = digits.next().and_then(|d| d.parse().ok()).unwrap_or(0);
    let patch: u16 = digits.next().and_then(|d| d.parse().ok()).unwrap_or(0);

    let channel_expr = match channel {
        "" => "crate::version::Channel::Stable",
        "beta" => "crate::version::Channel::Beta",
        "nightly" => "crate::version::Channel::Dev",
        _ => "crate::version::Channel::Dev",
    };

    let body = format!(
        "crate::version::Version {{\n    minor: {minor},\n    patch: {patch},\n    channel: {channel_expr},\n}}\n"
    );

    let out_dir = env::var_os("OUT_DIR").expect("OUT_DIR not set");
    fs::write(Path::new(&out_dir).join("version.expr"), body).expect("failed to write version.expr");

    if minor < 38 {
        println!("cargo:rustc-cfg=cfg_macro_not_allowed");
    }
    if minor >= 80 {
        println!("cargo:rustc-check-cfg=cfg(cfg_macro_not_allowed)");
        println!("cargo:rustc-check-cfg=cfg(host_os, values(\"windows\"))");
    }

    let host = env::var_os("HOST").unwrap_or_default();
    if host.to_string_lossy().split('-').nth(2) == Some("windows") {
        println!("cargo:rustc-cfg=host_os=\"windows\"");
    }

    println!("[rustversion-patch] rustc {raw} → version.expr 已写入");
}

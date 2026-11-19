# GoodNight(良夜)

按柳比歇夫方法记录时间的应用:你把做过的事按时间段记下来,应用把这些片段汇总成「时间到底花在哪」。

这是 Rust 重写版,替代 2.x 的 React Native / Kotlin 实现,从安装包换成单个原生程序,体积小一个数量级。

- 目前可在 Windows 运行,Android 打包待接
- 界面用 [Slint](https://slint.dev),业务全在与界面无关的 Rust 核心里
- 数据就是一个 JSON 文件 —— 没有数据库、没有账号、不联网

## 功能

- **计时**:开始 / 结束,结束后填写这段做了什么。不足 1 分钟的片段按误触丢弃。
- **分类**:内置六个,可自行添加。自定义名字最多 6 字,重名拒绝,颜色按色板轮转。
- **今日分析**:当日占比环形图,加上每段时间轴。
- **周 / 月分析**:同一套分解,按周或按月查看,左右箭头前后翻页。
- **热力图**:最近 52 周,52 × 7 格,五档深浅,并统计连续记录天数。
- **计划**:周计划、月计划、Deadline 三类条目,可勾选完成、可删除。
- **目标**:可选倒计时(15 / 25 / 45 / 90 分钟),超时给出提示。

## 构建

```bash
cargo test              # 核心逻辑:统计口径、时间边界、环形图几何、计划
cargo run               # 运行 Windows 版
cargo build --release   # 产出 target\release\tally.exe(约 6.9 MB)
```

需要稳定版 Rust 工具链;Windows 上使用 MSVC。

数据文件在 `%APPDATA%\tally\tally.json`(其它平台 `~/.tally/tally.json`)。写入时先写临时文件再改名,进程被杀不会写坏。

## 目录

```
core/    模型、存储、统计、环形图几何、热力图 —— 不依赖任何界面
app/     Slint 界面、事件接线、视图模型
vendor/  rustversion 的本地副本(见下)
```

`core` 是纯 Rust,各平台共用;界面层只负责把核心数据转成视图模型、把点击转回动作。

## 关于 vendor/rustversion

部分杀毒软件会隔离上游 `rustversion` 编译出的构建脚本,导致 `cargo build` 报 `拒绝访问 (os error 5)`。这里用等价实现替换:探测 `rustc --version`,写出 proc-macro 需要的 `version.expr`。如果本机没有这个问题,删掉 `Cargo.toml` 里的 `[patch.crates-io]` 段和 `vendor/` 目录即可。

## 状态

| 平台 | 状态 |
|---|---|
| Windows | 已构建并日常使用 |
| Android | 核心与平台无关;Slint 的 `android-activity` 入口与 `cargo-ndk` 打包尚未接入 |

2.x 的 Kotlin 实现保留在 `v2.2.0` 标签上。

## 许可

GPL-3.0,见 [LICENSE](LICENSE) 与 [NOTICE](NOTICE)。

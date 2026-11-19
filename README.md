# GoodNight

A time-tracking app built around the Lyubishchev method: you record what you did in blocks of time, and the app turns those blocks into a picture of where your days actually go.

This is the Rust rewrite. It replaces the React Native / Kotlin implementation of 2.x with a single native binary and a much smaller footprint.

- Windows today, Android packaging next
- UI in [Slint](https://slint.dev), all logic in a UI-free Rust core
- Data is one JSON file on disk — no database, no account, no network

## What it does

- **Timer** — start / stop, then write down what you did. Segments under a minute are treated as mis-taps and dropped.
- **Categories** — six built in, plus your own. Custom names are capped at six characters, duplicates are rejected, colors come from a rotating palette.
- **Daily analysis** — a donut of where today went, plus a timeline of each recorded segment.
- **Week / month analysis** — the same breakdown over the current week or month, with arrows to page back and forth.
- **Heatmap** — the last 52 weeks as a grid of 52 × 7 cells, five intensity levels, current streak.
- **Plans** — weekly, monthly and deadline items, with completion toggles.
- **Targets** — an optional countdown (15 / 25 / 45 / 90 minutes) with an overrun state.

## Build

```bash
cargo test              # core logic: statistics, time boundaries, donut geometry, plans
cargo run               # run the Windows build
cargo build --release   # -> target\release\tally.exe (about 6.9 MB)
```

Requires a stable Rust toolchain. On Windows the MSVC toolchain is used.

Data lives in `%APPDATA%\tally\tally.json` (`~/.tally/tally.json` elsewhere). The file is written to a temporary path and renamed, so a killed process cannot leave it half-written.

## Layout

```
core/    models, storage, statistics, donut geometry, heatmap — no UI dependency
app/     Slint UI, event wiring, view models
vendor/  local copy of `rustversion` (see below)
```

`core` is plain Rust and is shared by every platform build; the UI layer only turns core data into view models and forwards clicks.

## vendored rustversion

Some antivirus products quarantine the build-script executable that the upstream `rustversion` crate compiles, which makes `cargo build` fail with `Access is denied (os error 5)`. The vendored copy in `vendor/rustversion` replaces it with an equivalent build script: probe `rustc --version`, write the `version.expr` the proc macro expects. If your machine does not have this problem, delete the `[patch.crates-io]` section in `Cargo.toml` and the `vendor/` directory.

## Status

| Platform | State |
|---|---|
| Windows | built and used daily |
| Android | core is platform-neutral; the Slint `android-activity` entry and `cargo-ndk` packaging are not wired yet |

The 2.x Kotlin implementation is still available at tag `v2.2.0`.

## License

GPL-3.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).

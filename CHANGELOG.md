# Changelog

All notable changes to this project are documented here. Versions follow [SemVer](https://semver.org/).

## 3.0.0

Rewritten in Rust. The React Native / Kotlin implementation of 2.x remains at tag `v2.2.0`.

### Changed

- Native UI in Slint instead of React Native; Windows build is a single 6.9 MB executable instead of a 97 MB APK.
- Data is one JSON file (`%APPDATA%\tally\tally.json`) written atomically, replacing AsyncStorage and SQLite.
- Statistics, time boundaries and donut geometry live in a UI-free `tally-core` crate with unit tests.

### Added

- Plan page: weekly / monthly / deadline items with completion toggles and delete.
- Week and month analysis with donut chart and per-category breakdown, paginated by arrows.
- Heatmap view: 52 weeks × 7 days, five intensity levels, active-day count and current streak.
- `vendor/rustversion` with an equivalent build script, working around antivirus products that quarantine the upstream one.

### Removed

- Android packaging is not wired up in this version; the core crate is platform-neutral and ready for it.

# GoodNight

**English | [简体中文](./README.zh-CN.md)**

An Android work/rest cycle timer with a GitHub-style daily focus heatmap. Reliable in the background: exact alarms, kill/reboot self-recovery, cross-midnight bookkeeping.

> Named after Dylan Thomas's line *"Do not go gentle into that good night"* — 良夜 (liáng yè) means "good night".

## Contents

- [Background](#background)
- [Features](#features)
- [Install](#install)
- [Usage](#usage)
- [Architecture](#architecture)
- [Tests](#tests)
- [Acknowledgments](#acknowledgments)
- [License](#license)

## Background

The core problem of pomodoro-style apps is background reliability: lock screen, force-kill, reboot, and midnight crossings must not lose accounting. GoodNight solves this with a foreground service + exact alarms + a dual-clock engine (monotonic clock for timing, wall clock for reconciliation), and visualizes long-term focus history as a daily heatmap.

## Features

- Work/rest dual durations cycle automatically; keeps running in the background (notification carries a live countdown chronometer, phase progress bar and icon pause/skip/stop actions).
- Full-history daily focus heatmap: virtualized lazy grid with month labels, legend and fused corners; tap any day for a detail card with a per-profile breakdown.
- Multiple named duration profiles, each accumulating its own total.
- Pause/resume/skip/stop; stopping ends the whole cycle and zeroes the loop counter; after a pause, changing the duration restarts immediately with the new one.
- On timeout: sound + vibration alert, stops automatically, no manual dismiss needed.
- Re-opens with automatic reconciliation after a force-kill; resumes timing automatically after a device reboot.
- Daily totals settle on a 60-second cadence with cross-midnight split-day bookkeeping (error <= 60 seconds).
- Material You dynamic color (Android 12+, ember-orange fallback below) with edge-to-edge layout.
- Stroke icon system (Lucide geometry) with a spring play/pause morph, press micro-scale and cycle-badge bump; respects the system "remove animations" setting.
- Real path-level icon morphing (play to pause triangle-bars split) on a pure-Kotlin engine (SVG arc-length resampling, Procrustes alignment, polar interpolation), with staggered action-zone reflow and phase-text transitions.
- Per-profile count-up mode (stopwatch semantics: manual pause/stop, no auto phase rotation) alongside the default countdown, selected when creating or editing a profile.
- Weekly and monthly focus reports (per-day or per-week buckets plus per-profile totals) reachable from settings.
- Fresh installs start empty with guidance to create the first profile (no seeded demo data); upgrades preserve all data via a non-destructive schema migration. Square heatmap cells with always-visible per-column month labels; icon-only start button and unified 56dp action keys.
- Reworked top bar: profile switcher dropdown in the center and a menu (weekly/monthly report shortcuts) on the right. Heatmap cells rounded to 2dp, dates before the first record render blank, and month labels follow the GitHub pattern. Scheduled Sunday/last-day-of-month summary notifications (only when the period has focus time). Unified spring/tween motion across remaining state changes.
- Expanding full-width top-bar panels (profile switch / report menu) that push page content down with animated transitions instead of floating menus. Profile management lives on its own screen, entered from a pinned row at the top of the profile panel; the settings screen is trimmed to the permission banner and reminder intensity.
- Gesture/3-button back navigation: pressing back on the home screen minimizes instead of exiting; sub-screens return to home. The term 配置 is uniformly renamed 时钟 (clock). Reports gain a third lifetime tab (per-clock totals to date); profile cards no longer show lifetime totals (moved to reports) and creation moved to a "+" in the top bar. Day-detail cards now list each focus span (HH:mm ~ HH:mm) under each clock's total; spans crossing midnight split at 00:00 into per-day segments, while pre-existing history stays intact.
- Fully bilingual app (English default; Chinese under values-zh) that follows the system locale, including reports, notifications, settings and the day-detail timeline. Report menu and tabs are aligned (Weekly / Monthly / Clocks) with balanced segmented buttons. Clock management: tap a card to edit, or enter delete mode via the trash icon to multi-select and delete from a bottom bar.
- Health-report-style Weekly/Monthly summary (total + vs previous period, focus days, streak, daily average, best day, time-of-day distribution); button icon rendering is jank-free (geometry cached, no per-frame path rebuilds); notifications always occupy a single row via one channel and one notification id.
- Gentle crossfade-only screen transitions; focus segments shorter than one minute are treated as mis-touches (never counted, stored or shown; historical ones are purged once on upgrade); the lifetime report tab is renamed 总时长/Total and shows the same health summary as weekly/monthly.
- Equal-duration cross-fade screen transitions (no white-frame gaps); redesigned Weekly/Monthly/总报 reports with a hero summary card, metric tiles, time-of-day distribution bars and per-day/per-clock proportional bar visualization.
- Report time-of-day labels are name-only (Morning/Afternoon/Evening/Night); seven color theme packs (Ember, Light green, Blue, Purple, Rose, Teal, Nord) switchable in Settings, persisted, light-only.
- Clocks are grouped by task: a shared section plus one section per task that owns clocks, and a clock created from a task card lands in that task's section. Tapping a clock chip on a task card starts the timer already bound to that task, the timer card reads "task · clock", and switching the clock mid-run asks for confirmation first. Deleting a clock that has history archives it instead (its name and time stay in reports and the daily detail); deleting a task returns its clocks to the shared section. Backups move to format v3 (v1/v2 files still import).

## Install

Requires Android 8.0 (API 26) or newer.

> **v2.0.0 changes the package name** (`com.embertimer` → `com.goodnight`): it is a **new app identity**, so it
> cannot upgrade over an older install in place. To migrate: back up in the old version (Settings → Backup →
> Backup), uninstall the old app, install 2.0.0, then restore the backup in Settings.

- Download the APK from [Releases](https://github.com/Zzz210s/GoodNight/releases) (v0.3.0+ is release-signed and installs directly; note that upgrading over a debug-signed v0.2.0 install requires uninstalling first).
- Or build from source:

```bash
git clone https://github.com/Zzz210s/GoodNight.git
cd GoodNight
./gradlew :app:assembleDebug
# output: app/build/outputs/apk/debug/app-debug.apk
```

For a signed release build: generate a keystore and set the four keys (`storeFile`/`storePassword`/`keyAlias`/`keyPassword`) in `local.properties`; without them the release build falls back to the debug keystore.

## Usage

1. Grant the notification permission on first launch.
2. (Optional) create your own duration profiles on the settings page.
3. Tap "Start" on the home screen to enter the work/rest cycle.
4. Phase endings play a sound and show a notification; pause/skip/stop work both in-app and from the notification.
5. Tap any day on the home-screen heatmap to see its total and per-profile breakdown.

## Architecture

A single-module Compose app with clean layering:

<img src="docs/architecture.svg" alt="Architecture" width="700">

- `timer/` pure Kotlin timing engine (state machine, checkpoint reconciliation, dual-clock recovery) with no Android dependency, JVM-testable.
- `service/` foreground service (event-driven: notifications/alarms/alerts/settlement), exact-alarm scheduling, boot/alarm receivers.
- `data/` Room (profile, daily_total) + DataStore (settings, runtime state).
- `ui/` Compose (Material 3): home (timer card + heatmap), settings.

Timing-correctness design: engine event replay=0 + subscription handshake, a single mutex serializing all driver paths, settlement attribution carried by events (robust to RESET/profile-switch interleaving), drain-aware service teardown.

## Tests

87 unit tests (JVM + Robolectric) covering engine semantics, event policy, receiver gating, and ViewModel contracts:

```bash
./gradlew test
```

## Acknowledgments

- Background-timing reliability design references [adrcotfas/goodtime](https://github.com/adrcotfas/goodtime) (GPL-3.0).
- Heatmap and daily-aggregation data model references [nsh07/Tomato](https://github.com/nsh07/Tomato) (GPL-3.0).

This project is an independent implementation and does not copy their source code (see [NOTICE](NOTICE)).

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Changes are tracked in [CHANGELOG.md](CHANGELOG.md).
## License

[GPL-3.0](LICENSE)

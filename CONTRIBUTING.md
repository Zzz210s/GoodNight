# Contributing

Thanks for your interest in improving GoodNight. / 感谢关注良夜(GoodNight)。

This is a Jetpack Compose Android app (Kotlin, Room, M3). See [README.md](README.md) for setup and [Architecture](#architecture) below.

## Setup / 环境

1. Open the project in Android Studio (or build from CLI with JDK 17 + Android SDK).
2. `./gradlew test` runs the full unit suite (Robolectric + Room in-memory).
3. Release builds need a keystore (`local.properties`: `storeFile/storePassword/keyAlias/keyPassword`); CI can sign via secrets if configured.

## Branches / Trunk-based model

- We use **trunk-based development**: `main` is the single long-lived branch and is always releasable.
- Short-lived feature branches are merged into `main` (usually via squash merge) and deleted.
- Releases are cut by tagging `main` with `vX.Y.Z`; GitHub Actions builds and publishes the APK.

## Rules / 约定

- **Each source/test file ≤ 200 lines** — split into single-responsibility modules when exceeded.
- **Bilingual user-facing text**: EN in `values/`, ZH in `values-zh/`; both must be updated together.
- **Tests** for new logic (JVM/Robolectric); the suite must stay green.
- **No AI/tooling artifacts** committed (`.superpowers/`, `.codegraph/`, plans, spec docs).
- Public README is bilingual (`README.md` EN + `README.zh-CN.md` ZH), cross-linked at the top.

## Reporting issues / 提问题

Use the issue template and include the app version, device, and reproduction steps.

## Pull requests

Each PR should be small, focused, and pass `./gradlew test`. Update `CHANGELOG.md` and `CHANGELOG.zh-CN.md` for user-visible changes.

## Architecture

- `timer/` — pure engine (snapshot, phase transitions, event policy); JVM-testable.
- `service/` — foreground timer service, notifications (RemoteViews-safe), alarm scheduling, crash logger.
- `data/` — Room (DB/DAO/entities), repositories, import/export (DataTransfer), DataStore settings.
- `ui/` — Compose screens (home, settings, clock management, report), heatmap, morph engine, theme (color packs + motion tokens).

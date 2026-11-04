# Flocket for Android

Native Android client for [herdr-core](https://github.com/herdrdev/herdr-core) (`hc`): browse the terminals and agents on your hosts and attach to them over SSH. Kotlin, Jetpack Compose, Material 3, Navigation 3, Hilt, Room.

This is a self-contained Gradle project. Protocol docs and wire fixtures live in [`../protocol`](../protocol), decisions in [`../docs/decisions`](../docs/decisions).

## Features

- Onboarding: device key generation (Ed25519, Keystore-wrapped), copyable `authorized_keys` command, host setup, explicit host-key trust.
- Hosts over SSH with key or password auth; pinned host keys, changed keys are a hard stop; automatic `hc` discovery.
- Agents across all hosts and federated machines (`rohan/t7`), grouped by host and space, a Priority sort (needs you, done, working, idle) or Grouped sort with desktop tab numbers, agent icons, filters, stale-data banners.
- Rename spaces and tabs from the list and the tab strip; names are written to the desktop's `herdr-app` layout ([ADR-0008](../docs/decisions/0008-rename-in-desktop-layout.md)).
- New agent from a template or command; send text, interrupt, kill and remove with at-most-once semantics.
- Interactive terminal powered by libghostty: IME, hardware keyboard, extra-keys row, pinch zoom, scrollback, copy/paste; passive attach that never resizes another viewer's terminal until you interact.
- Files: paste an image or file into a terminal (clipboard, photo or file picker; up to 8 MB, the path is typed for the agent), send a file to the terminal's folder, and see files agents share with `flocket-share` (card on the terminal, image/text viewer, Save, Share, paste path). Host settings install the helper and copy the agent instruction ([ADR-0011](../docs/decisions/0011-transfer-connection-and-agent-shares.md)).
- Survives rotation, fold/unfold, process death and network changes; a short foreground service keeps an open terminal connected for ~3 minutes in the background (opt-out, [ADR-0006](../docs/decisions/0006-background-short-service.md)).
- Phone, foldable and tablet layouts (list-detail), light/dark/dynamic color.
- Settings: app defaults with per-host overrides (font, terminal colors, extra keys, clipboard policy, auto-connect, default folder).

## Requirements

- JDK 21 (the Gradle daemon and toolchain use 21; bytecode targets 17)
- Android SDK 37 (`local.properties` with `sdk.dir`, or `ANDROID_HOME`)
- minSdk 29, targetSdk and compileSdk 37

## Quick start

```bash
cd android
./gradlew :app:assembleDebug        # build the debug app (com.mtajchert.flocket.debug)
scripts/check.sh quick              # format check + unit tests
./gradlew spotlessApply             # format
```

All verification commands: [docs/TESTING.md](docs/TESTING.md).

## Module map

| Module | Responsibility | May depend on |
|---|---|---|
| `:app` | Activity, Navigation 3 host, Hilt graph | everything |
| `:benchmark` | Startup and terminal macrobenchmarks, baseline profile (needs a device) | `:app` (target) |
| `:samples:terminal-playground` | Sample app to try the terminal renderer without SSH: Ghostty engine fed by an in-process fake stream (echo shell, colors, 60 Hz redraw, flood) | `:core:terminal`, `:core:terminal-ghostty`, designsystem, session |
| `:feature:<x>:api` | Navigation keys, public contracts | `:core:model` |
| `:feature:<x>:impl` | Screens, ViewModels | own `api`, other features' `api`, `:core:*` |
| `:core:model` | Domain types (JVM) | `:core:common` |
| `:core:common` | `AppResult`/`AppError`, Clock, Logger (JVM) | none (bottom of the graph) |
| `:core:protocol` | `ByteTransport`, codec, JSON-RPC client, DTOs (JVM) | `:core:model`, `:core:common` |
| `:core:session` | `CoreSession`: epochs, hello, reducer, reconnect (JVM) | `:core:protocol`, model, common |
| `:core:ssh` | SSHJ `ByteTransport`, host-key verification (JVM) | `:core:protocol`, model, common |
| `:core:security` | Keystore-wrapped device keys | `:core:ssh`, model, common |
| `:core:database` | Room (hosts, pinned keys) | `:core:model` |
| `:core:datastore` | Preferences DataStore | `:core:model` |
| `:core:data` | Repositories, `ConnectionManager` | session, ssh, protocol, database, datastore, security, model, common |
| `:core:designsystem` | `FlocketTheme`, components | none |
| `:core:ui` | Shared UI: error mapping, status chips, trust dialogs, host form, settings form | designsystem, terminal, data, model, common |
| `:core:terminal` | `TerminalEngine`, `TerminalController`, Compose renderer, IME bridge | designsystem, session, model, common |
| `:core:terminal-ghostty` | libghostty-vt engine (JNI) | `:core:terminal`, common |
| `:core:testing` | Fakes, sample data and test rules (test-only) | data, model, common |

Dependency rules and the module graph: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md). Agent guide: [AGENTS.md](AGENTS.md).

## Docs

[Architecture](docs/ARCHITECTURE.md), [Testing](docs/TESTING.md), [Screenshot testing](docs/SCREENSHOT_TESTING.md), [Adaptive layouts](docs/ADAPTIVE_LAYOUTS.md), [Agent setup](docs/AGENT_SETUP.md), [Performance](docs/PERFORMANCE_TESTING.md), [Releasing](docs/RELEASING.md), [Security defaults](docs/SECURITY_DEFAULTS.md), [Localization](docs/LOCALIZATION.md), [Third-party notices](THIRD_PARTY_NOTICES.md).

# Flocket for Android

Native Android client for [herdr-core](../docs/reference/) (`hc`): browse the terminals and agents on your hosts and attach to them over SSH. Kotlin, Jetpack Compose, Material 3, Navigation 3, Hilt, Room.

This is a self-contained Gradle project. Protocol docs and wire fixtures live in [`../protocol`](../protocol), decisions in [`../docs/decisions`](../docs/decisions).

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
| `:benchmark` | Startup benchmark, baseline profile | `:app` (target) |
| `:feature:<x>:api` | Navigation keys, public contracts | `:core:model` |
| `:feature:<x>:impl` | Screens, ViewModels | own `api`, other features' `api`, `:core:*` |
| `:core:model` | Domain types (JVM) | none |
| `:core:common` | `AppResult`/`AppError`, dispatchers, Clock, Logger (JVM) | `:core:model` |
| `:core:protocol` | `ByteTransport`, codec, JSON-RPC client, DTOs (JVM) | `:core:model`, `:core:common` |
| `:core:session` | `CoreSession`: epochs, hello, reducer, reconnect (JVM) | `:core:protocol`, model, common |
| `:core:ssh` | SSHJ `ByteTransport`, host-key verification (JVM) | `:core:protocol`, model, common |
| `:core:security` | Keystore, device keys | model, common |
| `:core:database` | Room (hosts, pinned keys) | `:core:model` |
| `:core:datastore` | Preferences DataStore | `:core:model` |
| `:core:data` | Repositories, `ConnectionManager` | session, ssh, protocol, database, datastore, security, model, common |
| `:core:designsystem` | `FlocketTheme`, components | none |
| `:core:ui` | Shared UI on the design system | designsystem, model, common |
| `:core:terminal` | `TerminalEngine`, Compose renderer | designsystem, model, common |
| `:core:terminal-ghostty` | libghostty-vt engine (JNI) | `:core:terminal`, common |
| `:core:testing` | Fakes and test rules | model, common |

Dependency rules and the module graph: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md). Agent guide: [AGENTS.md](AGENTS.md).

## Docs

[Architecture](docs/ARCHITECTURE.md), [Testing](docs/TESTING.md), [Screenshot testing](docs/SCREENSHOT_TESTING.md), [Adaptive layouts](docs/ADAPTIVE_LAYOUTS.md), [Agent setup](docs/AGENT_SETUP.md), [Performance](docs/PERFORMANCE_TESTING.md), [Releasing](docs/RELEASING.md), [Security defaults](docs/SECURITY_DEFAULTS.md), [Localization](docs/LOCALIZATION.md), [Third-party notices](THIRD_PARTY_NOTICES.md).

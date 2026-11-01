# Flocket Android: agent guide

Read the root [`../AGENTS.md`](../AGENTS.md) first (product rules, working rules). Architecture: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md). Binding decisions: [`../docs/decisions/`](../docs/decisions/).

## Modules and dependency rules

- Pure Kotlin/JVM (never depend on Android): `core:model`, `core:common`, `core:protocol`, `core:session`, `core:ssh`.
- Android libraries: `core:security`, `core:database`, `core:datastore`, `core:data`, `core:designsystem`, `core:ui`, `core:terminal`, `core:terminal-ghostty`, `core:testing`.
- Features: `feature:<x>:api` (nav keys, public contracts) and `feature:<x>:impl` (screens, ViewModels) for onboarding, hosts, terminals, terminal, settings. `:app` wires everything.
- Rules, enforced at configuration time by `build-logic` (`ModuleBoundaryConventionPlugin`): `feature:X:impl` never depends on another feature's `impl`; `core:*` never depends on `feature:*`; `feature:*:api` never depends on an `impl`.
- Module namespace = `com.mtajchert.flocket` + module path, hyphens dropped (`core:terminal-ghostty` -> `...core.terminal.ghostty`).

## Adding a module

Add `include(...)` to `settings.gradle.kts`, then a build file of about 5-20 lines with a top comment stating the module's responsibility and allowed dependencies. Pick the convention plugin:

| Module kind | Plugin(s) |
|---|---|
| Pure Kotlin/JVM | `flocket.jvm.library` |
| Android library | `flocket.android.library` |
| Android library with Compose | `flocket.android.library` + `flocket.android.compose` |
| Feature impl | `flocket.android.feature` (library + compose + hilt + core deps + own `api`) |
| Hilt / Room | `flocket.hilt` / `flocket.android.room` |
| App | `flocket.android.application` |

Versions live only in `gradle/libs.versions.toml`.

## Rules

- **Errors**: public APIs of `core:session`, `core:data` and repositories return `AppResult<T>` (`Success` or `Failure(AppError)`) and do not throw. `AppError` is a sealed taxonomy in `core:common`; add a case only with review. Always rethrow `CancellationException`. Low-level modules may throw typed exceptions internally and convert at the public boundary. (`AppResult`/`AppError` are implemented in `core:common` by a later task.)
- JSON uses kotlinx.serialization with `ignoreUnknownKeys = true` and `explicitNulls = false`. Coroutines everywhere, no callbacks in public APIs.
- Never log secrets, key material or terminal bytes. Use the `core:common` Logger facade in JVM modules.
- Tests: JUnit4, kotlinx-coroutines-test, Turbine, backtick test names. Real `hc` only with an isolated session (`flocket-test-<random>`), skipped when `hc` is absent, always stopped in teardown. Never touch the developer's default herdr session.

## Commands (run from `android/`; canonical list in [docs/TESTING.md](docs/TESTING.md))

```bash
./gradlew :core:protocol:test                    # fastest: one JVM module
./gradlew :feature:hosts:impl:testDebugUnitTest  # one Android module
scripts/check.sh quick                           # spotlessCheck + all unit tests
scripts/check.sh full                            # + lint, assembleDebug, assembleRelease, screenshots
./gradlew spotlessApply                          # format before every commit
```

## Screenshots

Validate with `validateDebugScreenshotTest`; inspect every changed PNG before committing an update, and never regenerate references just to silence an unexplained failure. Screenshot wrappers use `FlocketTheme(dynamicColor = false)`. See [docs/SCREENSHOT_TESTING.md](docs/SCREENSHOT_TESTING.md).

## More

[Testing](docs/TESTING.md), [Screenshot testing](docs/SCREENSHOT_TESTING.md), [Adaptive layouts](docs/ADAPTIVE_LAYOUTS.md), [Agent setup](docs/AGENT_SETUP.md), [Releasing](docs/RELEASING.md), [Security defaults](docs/SECURITY_DEFAULTS.md), [Performance](docs/PERFORMANCE_TESTING.md), [Localization](docs/LOCALIZATION.md). Update the nearest README/AGENTS.md when conventions change.

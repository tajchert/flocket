# Testing

This file is the canonical home of the verification commands. Run them from `android/`.

## Layers

| Layer | Question | Location | Device |
|---|---|---|---|
| JVM unit tests | Is the protocol/session/ssh logic right? | `core/{model,common,protocol,session,ssh}/src/test` | no |
| Wire contract tests | Do we parse the shared fixtures? | `../protocol/fixtures`, used from `core:protocol` | no |
| Real-core integration | Does it work against a real `hc`? | `core:protocol`, `core:session` (skipped when `hc` is not on PATH) | no |
| SSH integration | Does `core:ssh` work against an embedded MINA sshd? | `core:ssh/src/test` | no |
| Android unit + Compose (Robolectric) | Do ViewModels and UI react correctly? | `src/test` | no |
| Screenshot tests | Does it look right at every window size? | `src/screenshotTest` | no |
| Device journeys / benchmarks | Does it work on real framework/hardware? | `src/androidTest`, `benchmark` | yes |

Real `hc` tests use an isolated session (`--session flocket-test-<random>` or `HC_SESSION`), stop it in teardown, and never touch the developer's default session.
On macOS, start the `hc` child with `TMPDIR=/tmp`: the default per-user `TMPDIR` makes the core's socket path longer than the 103-byte Unix socket limit and `hc connect` fails (`RealCoreIntegrationTest` passes it through `ProcessTransport(environment = ...)`).

## Commands

Fastest feedback, narrowest first:

```bash
./gradlew :core:protocol:test                      # one pure-Kotlin module
./gradlew :feature:hosts:impl:testDebugUnitTest    # one Android module
./gradlew :core:designsystem:validateDebugScreenshotTest
scripts/check.sh quick                             # spotlessCheck + all JVM and Android unit tests
scripts/check.sh full                              # quick + lint, debug and release builds, screenshots
```

What `scripts/check.sh` runs:

```bash
# quick
./gradlew spotlessCheck test testDebugUnitTest
# full = quick plus
./gradlew lintDebug assembleDebug :app:assembleRelease
./gradlew validateDebugScreenshotTest              # every module that has screenshot tests
```

Formatting: `./gradlew spotlessApply` before every commit.

Other tasks:

```bash
./gradlew koverHtmlReport                      # aggregated coverage: build/reports/kover/html/index.html
./gradlew :core:designsystem:updateDebugScreenshotTest   # regenerate reviewed screenshot references
# Device tests: require an emulator or device
./gradlew :benchmark:connectedNonMinifiedReleaseAndroidTest
```

End-to-end against a real `hc` over SSH on an emulator (manual): [journeys/E2E_SMOKE.md](journeys/E2E_SMOKE.md), using `scripts/dev-sshd.sh`.

Reports: tests in `<module>/build/reports/tests/`, screenshots in `<module>/build/reports/screenshotTest/preview/debug/index.html`.

## Compose behaviour tests run on the JVM

Compose UI tests live in `src/test` and run through Robolectric. The convention plugins configure what this needs:

1. **JDK opens.** Robolectric reaches into JDK internals; every `Test` task gets the `--add-opens`/`--add-exports` flags (see `Support.kt` in `build-logic`). Without them tests fail with "Failed to interact with raw FileDescriptor internals".
2. **A pinned test SDK.** Robolectric trails `targetSdk`, so a module with Robolectric tests needs `src/test/resources/robolectric.properties` with `sdk=36` (see `app/`). Raise it when Robolectric catches up.

Reserve `src/androidTest` for what needs real hardware: Keystore behaviour, real lifecycle and process death, system UI.

## Coverage

[Kover](https://github.com/Kotlin/kotlinx-kover) produces one aggregated report for the whole project from the root `build.gradle.kts`, because per-module reports misattribute execution across modules. It covers local unit tests only and is a tool for finding untested code, not a release gate.

## Adding tests for a new feature

1. Unit tests for every ViewModel state transition and failure path.
2. Robolectric tests for the stateless content composables.
3. Screenshot tests: all states at phone width, one rich state across `@PreviewFormFactors`, `@PreviewLargeFont` for text-heavy screens.
4. Device tests only for what needs a device.

See also [Screenshot testing](SCREENSHOT_TESTING.md) and [Performance testing](PERFORMANCE_TESTING.md).

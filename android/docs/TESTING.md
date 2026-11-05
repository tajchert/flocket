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
| Device journeys | Does the real app work against a real `hc` over SSH? | `app/src/androidTest/.../journey` | yes |
| Device tests / benchmarks | Does it work on real framework/hardware? | other `src/androidTest`, `benchmark` | yes |

Real `hc` tests use an isolated session (`--session flocket-test-<random>` or `HC_SESSION`), stop it in teardown, and never touch the developer's default session.
On macOS, start the `hc` child with `TMPDIR=/tmp`: the default per-user `TMPDIR` makes the core's socket path longer than the 103-byte Unix socket limit and `hc connect` fails (`RealCoreIntegrationTest` passes it through `ProcessTransport(environment = ...)`).
Slow real-core measurements are skipped unless `FLOCKET_SLOW=1` is set, for example the federated transfer stall over a throttled link (recorded in ADR-0011): `FLOCKET_SLOW=1 ./gradlew :core:session:test --tests '*TransferRealCoreIntegrationTest*' --rerun -i | grep FederatedTransferStall`.

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

End-to-end against a real `hc` over SSH on an emulator: see [Device journeys](#device-journeys).

CI (`.github/workflows/android.yml`): the `instrumented` job runs `connectedDebugAndroidTest` for the modules with `androidTest` sources (`:core:terminal-ghostty`, `:core:terminal`, `:feature:terminal:impl`) on an API 35 `google_apis` x86_64 emulator with KVM and a cached AVD, after `build` passes. Add new device-test modules to its task list. Reports upload as `instrumented-reports` on failure.

Reports: tests in `<module>/build/reports/tests/`, screenshots in `<module>/build/reports/screenshotTest/preview/debug/index.html`.

## Device journeys

Journeys are Kotlin androidTests in `:app` (`com.mtajchert.flocket.journey`, classes named `*JourneyTest`). They run the real app (Hilt graph, Room, Keystore, libghostty, SSH) against a real `hc` over the throwaway sshd. Nothing is faked. They check results on the core (`hc ls`, `hc read`, `hc get`), not on pixels. Which manual steps they cover: [journeys/E2E_SMOKE.md](journeys/E2E_SMOKE.md). Decision: [ADR-0009](../../docs/decisions/0009-device-journeys-androidtest.md).

```bash
scripts/e2e-device.sh up [--no-build] [--fresh]   # boot the emulator, start sshd, install the debug app
scripts/e2e-device.sh journey                      # every journey test
scripts/e2e-device.sh journey OnboardingJourneyTest TerminalJourneyTest#<method>   # selectors
scripts/e2e-device.sh journey --smoke              # only tests annotated @Smoke
scripts/e2e-device.sh journey --repeat 5           # flakiness check: per-test pass counts
scripts/e2e-device.sh journey --no-build           # skip Gradle (the APKs are already built)
scripts/e2e-device.sh down                         # stop hc session, sshd and emulator
```

- A selector is `Class` or `Class#method`; a bare class name means `com.mtajchert.flocket.journey.<Class>`.
- `journey` builds `:app:assembleDebug :app:assembleDebugAndroidTest` in one Gradle call and installs an APK only when its hash changed or the package is missing.
- **Isolation is per test method.** Before every method the runner clears the app's data, grants the notification permission, disables the system keyboards and runs that one method in its own `am instrument`. Tests never depend on order; a test that needs an onboarded app seeds it through the app's repositories (`JourneyRule`).
- Before every method the runner also resets device-wide state that outlives the data clear: Wi-Fi and data on, airplane mode off, portrait with auto-rotate off, screen awake and unlocked, the sshd listener up. Tests still restore what they change in `finally`.
- Each run prints one `PASS`/`FAIL`/`IGNORED` line per test with seconds and a summary (`journeys: X passed, Y failed, Z ignored in Ns`); the exit code is non-zero on any failure. `@Ignore` (a parked known bug, with the evidence in its KDoc) does not fail the run; a skipped test does (the runner arguments did not arrive).
- Each test has a time limit of `FLOCKET_JOURNEY_TIMEOUT` seconds (default 180); a test that hangs fails and the run goes on.
- Raw instrumentation output of the last run of each test: `$FLOCKET_E2E_DIR/journeys/<Class>#<method>.txt` (default `$TMPDIR/flocket-e2e`).
- Every test works in the isolated hc session (`FLOCKET_HC_SESSION`, default `flocket-e2e`) and kills its terminals before and after. Tests skip themselves without the runner's arguments, so a plain `connectedDebugAndroidTest` passes, but **do not use `connected*` for journeys**: it installs on every attached device.
- Journeys today (16 methods, about 110 s for the whole set): `OnboardingJourneyTest`, `TerminalJourneyTest`, `PassiveOpenJourneyTest` (the `@Smoke` set, about 30 s), `RotationJourneyTest`, `BackgroundJourneyTest`, `LockJourneyTest`, `NetworkJourneyTest`, `SshLinkJourneyTest`, `FullScreenJourneyTest`, `DownloadJourneyTest` (large agent shares, 6 methods, about 60 s).
- `DownloadJourneyTest` shares 120 MiB and 200 MiB random files with the repo's `flocket-share` and checks SHA-256 on the phone (cache dir), cancel leaving no `.part`, terminal echo while a download runs, and a generated tiny APK (`scripts/make-test-apk.sh`) through the system install confirmation. The harness sshd serves SFTP (`Subsystem sftp internal-sftp`); `@NoSftp` tests comment that line out for the run to exercise the chunked fallback. The runner grants the app `REQUEST_INSTALL_PACKAGES` before every test (a grant from inside the test kills the process). The `speedOf200MiB*` tests print `download-speed …` lines into the raw output file. Federated download speed and stalls: `FLOCKET_SLOW=1 ./gradlew :core:session:test --tests '*TransferRealCoreIntegrationTest*200 MiB*'`.
- After a click, wait on the UI it should change (`waitFor`, `waitForGone`) before polling the core: the Compose test clock only recomposes while the test syncs with it, so a click followed straight by `hc` polling never reaches the screen. Which smoke step each covers: [journeys/E2E_SMOKE.md](journeys/E2E_SMOKE.md).
- Adding one: a new `*JourneyTest` class with `@get:Rule val journey = JourneyRule(host)` and `companion object { @JvmField @ClassRule val host = HostRule() }`; `JourneyRule` seeds an onboarded, online host (`Start.Cleared` for the onboarding UI). Mac-side steps go through `journey.host` (`hc(...)`, `read`, `get`, `runShell`, `acceptedLogins()`); UI helpers are in `ComposeJourney.kt` and `LifecycleSupport.kt`. Prefer string resources (`journey.string(R.string.x)`) over literals. Run it with `journey <Class>`; check it with `--repeat 3`.
- Timings (M-series Mac, warm Gradle): the runner adds about 0.7 s per test (data clear plus instrumentation start); a journey of 5-6 s of test time takes 6-7 s wall. A cold emulator boot is 30-80 s once per `up`.

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

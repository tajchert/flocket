# ADR-0009: Device journeys are androidTests against a real hc

Status: accepted (2026-11-02)

## Context
The end-to-end smoke journey (onboarding, host trust, terminals, lifecycle) was a Markdown
checklist ([E2E_SMOKE.md](../../android/docs/journeys/E2E_SMOKE.md)), run by hand or by an agent
tapping the emulator at 8-14 s per tap. Robolectric and `androidTest` cover the screens, but only
against fakes. The bugs of the last weeks (scrollback gap, size jump, reconnect after background)
lived in the gap: the real app against a real core over SSH.

We measured the same journey (onboarding, add host, trust, new terminal, `echo` checked with
`hc read`, kill, remove) four ways on one emulator:

| Way | Wall time | Notes |
|---|---|---|
| Bash harness (`adb input` + `uiautomator`) | 110 s | edit script, rerun |
| tester-army `e2e`, deterministic | 18.4 s | TypeScript, Node 22, 420 MB `node_modules`, a daemon; accessibility-tree locators need workarounds for Compose fields; leaves its IME as default; snapshot helper wedged after `android layout` in 2 of ~20 runs |
| tester-army `e2e`, agentic | 115 s first run, 18.5 s replayed | $0.21 for the first run |
| Kotlin `androidTest` in `:app` | **8.8 s** | 0.7 s test-APK rebuild, no new toolchain |

## Decision
Device journeys are Kotlin `androidTest`s in `:app` (`com.mtajchert.flocket.journey`). They run the
real app (Hilt graph, Room, Keystore, libghostty, SSH) against a real `hc` over the dev sshd
(`scripts/dev-sshd.sh`); nothing is faked. Rules:

- **Per-test isolation.** The runner clears the app's data and runs exactly one method per
  `am instrument`. Tests never depend on order.
- **Seeded state.** Tests that need an onboarded, connected app seed it through the app's own
  repositories (device key, host trust, host, preferences). Only the onboarding journey uses the
  onboarding UI.
- **Assertions on the core** (`hc ls`, `hc read`, `hc get`) through a control SSH connection built
  on `core:ssh`, in an isolated hc session. Tests kill their terminals before and after.
- **The runner lives in the harness:** `scripts/e2e-device.sh journey` builds, installs an APK
  only when its hash changed, runs each method, and prints PASS/FAIL. Gradle `connected*` tasks are
  not used for journeys because they install on every attached device. Without the runner's
  instrumentation arguments the tests skip themselves, so a plain connected run still passes.

See [TESTING.md](../../android/docs/TESTING.md#device-journeys).

## Consequences
- Running journeys needs an emulator or device, `hc` and the dev sshd on the same machine.
- Journeys are fast enough (about 7 s each, 0.7 s runner overhead) to run after every change.
- CI does not run them yet. A later step adds `hc` and the dev sshd to the `instrumented` job.
- Process death cannot be tested from inside the app's process. Those journeys need a driver
  outside it (a second test APK with UiAutomator, or `e2e`); this ADR does not rule that out.
- Rotation, background and network journeys use `UiDevice` and shell commands from the test.
- iOS gets its own tests ([ADR-0004](0004-native-per-platform-shared-fixtures.md)); the manual
  checklist stays the shared description of the steps.

# Flocket roadmap

This is the plan for the milestones after milestone 1, the Android foundation described in
[`design/2026-11-01-android-foundation.md`](design/2026-11-01-android-foundation.md).
Each milestone lists its goal and scope, the decisions it needs from the project owner, and
where in the code and the spec to start reading.

Paths are relative to the repository root. Android module paths are under `android/`. The
protocol references point to [`protocol/SPEC.md`](../protocol/SPEC.md).

Rules that apply to every milestone (from [`AGENTS.md`](../AGENTS.md)):

- **At-most-once mutations:** input, uploads and approvals are never replayed after a dropped connection.
- **Trust before credentials:** the SSH host key is verified before any credential is sent.
- **The core owns runtime:** closing or detaching the app never stops a terminal or an agent.
- **Protocol first:** a protocol change starts in `protocol/`.
- **ADRs:** a choice that affects the whole project gets an ADR in `docs/decisions/`.

---

## M1.1: Finish and harden (in progress)

**Goal:** the app runs on the current herdr-core release, and the terminal's known rough
edges are fixed.

- Move to hc 0.1.16:
  - Add the schema snapshot and a first-paint fixture recorded from 0.1.16.
  - Require hc 0.1.16 or newer at `hello`.
  - Add a real-core test showing that FACTS does not take size ownership.
- Terminal: smooth pixel scrolling with fling; selection handles with the floating Copy/Paste toolbar; grapheme clustering (mode 2027), so ZWJ emoji take one cell.
- The new-terminal sheet always prefills the per-host default folder.
- A private GitHub repository, a CI emulator job, and release signing configured from environment variables.
- The physical-device pass was deferred. It moves to M1.2.

## M1.2: Physical-device validation

**Goal:** confirm on real hardware the numbers that the emulator can't settle.

- During a heavy flood, the terminal's frame time should stay at or below 8 ms at P90. On the emulator it is about 17 ms, and the emulator's GPU is a likely cause.
- Measure throughput over Wi-Fi and LTE, against 8–13 MB/s on the emulator.
- Check that the background keep-alive service works on Android 14.
- Check the path where the system refuses to start the foreground service.
- Retest the lifecycle matrix on a foldable: fold and unfold, tabletop posture, lock, process death.

**Start here:**

- Benchmarks: `android/benchmark` (`TerminalBenchmarks`), and how to run them in
  `android/docs/PERFORMANCE_TESTING.md` and `android/docs/TERMINAL_RENDERING.md`.
- Throughput test: `android/core/terminal/src/androidTest/.../DeviceThroughputTest.kt`.
- Keep-alive service: `android/app/src/main/kotlin/com/mtajchert/flocket/keepalive/TerminalKeepAliveService.kt`, and [ADR-0006](decisions/0006-background-short-service.md).
- Journey checklist: `android/docs/journeys/E2E_SMOKE.md`.

---

## M2: Know when an agent needs you

**Goal:** you don't have to keep the app open to find out that an agent is waiting for input or blocked.

**Scope:**

1. **While connected:** a local notification when a terminal's agent status changes to an urgent state, such as waiting or blocked.
   - There is a notification channel for each kind of status.
   - Tapping it opens that terminal.
   - Notifications can be switched off for the whole app and for each host, using the layered settings pattern.
2. **While the app is suspended:** something running on the host has to deliver the push.

**Decision needed (project-wide, needs an ADR):** how the push gets from the host to the phone.

| Option | Notes |
|---|---|
| Host companion that posts to **ntfy or UnifiedPush** (recommended to evaluate first) | Open source, needs no Google account, and the host can run it itself. |
| Firebase Cloud Messaging | Needs a server holding FCM credentials, and depends on Google services. |
| A push feature proposed upstream in herdr-core | Cleanest in the long run, but depends on upstream. |

**Start here:**

- Status model: `android/core/model/src/main/kotlin/com/mtajchert/flocket/core/model/Terminal.kt` (`AgentState`, urgency). SPEC §6.3 covers the program status protocol, and §8.2 covers the `status` and `turn` events.
- Event stream: `android/core/session/src/main/kotlin/com/mtajchert/flocket/core/session/EventReducer.kt` and `CoreSessionImpl.kt`. Notifications must only react to status changes, never to a snapshot being replaced after `missed` or a reconnect.
- App-level lifecycle and the per-host sessions: `android/core/data/src/main/kotlin/com/mtajchert/flocket/core/data/internal/ConnectionManagerImpl.kt`.
- Existing notification plumbing (channel, permission): `android/app/src/main/kotlin/com/mtajchert/flocket/keepalive/`.
- Layered settings: `android/core/data` (`HostSettingsRepository`), `android/core/datastore`, and the settings screens in `android/feature/settings` and `android/feature/hosts`.
- Opening a terminal from a notification, which needs a deep link: `android/app/src/main/kotlin/com/mtajchert/flocket/navigation/FlocketNavHost.kt` and `android/feature/terminal/api/.../TerminalRoute.kt`.

---

## M3: Send files to the host

**Goal:** give an agent a screenshot, photo or file from the phone.

**Scope:**

- Use `terminal.put` to upload a file of up to 8 MB next to a terminal. It returns `{path, bytes}`.
- Then optionally type the returned path into the terminal, which is the "image paste for agents" flow.
- Flocket appears in the Android share sheet, and picking a terminal is a step of the share flow.
- Uploads are at most once: a dropped connection shows "may not have been uploaded" and offers a manual retry.
- There is a size check before upload, and progress plus cancel while it runs.

**Start here:**

- Spec: SPEC §4 and §4.16, where `terminal.put` is listed as optional and must be capability-gated through the `hello` methods. Before coding, record a fixture and refresh `protocol/SPEC.md`.
- RPC client: `android/core/protocol/src/main/kotlin/com/mtajchert/flocket/core/protocol/CoreConnection.kt` and `dto/`. Method constants live in `android/core/model/.../Core.kt` (`CoreInfo`).
- Session API: `android/core/session/.../CoreSession.kt`. Add a `put` call that returns `AppResult`, with `OutcomeUnknown` when the connection drops after the upload was sent.
- UI: the terminal screen `android/feature/terminal/impl/.../TerminalScreen.kt` and `TerminalViewModel.kt`. The share target goes in `android/app/src/main/AndroidManifest.xml`, plus a new picker entry in `android/feature/terminals`.

---

## M4: An agent-first view

**Goal:** follow an agent's conversation and answer it without having to read raw terminal output.

**Scope:**

- A transcript or chat view of an agent session.
- One-tap approvals and quick replies, each sent exactly once and shown with its outcome.
- The raw terminal stays one tap away.

**Decision needed:** where the structured transcript comes from. This needs research first.

- One option is parsing what the agent writes to the screen, which is fragile.
- Another is a program-side protocol, an extension of OSC 7501 or an upstream herdr-core feature.

Write the research up in `docs/reference/` before you design anything.

**Start here:**

- SPEC §6.3 (status), §4.12 (`terminal.read`), §4.9 (`terminal.send`) and §4.10 (`terminal.keys`).
- Existing at-most-once send flow: `android/feature/terminals/impl/.../TerminalsViewModel.kt` (send text, interrupt) and `TerminalDialogs.kt`.
- Error and outcome mapping: `android/core/ui/src/main/kotlin/com/mtajchert/flocket/core/ui/error/UiError.kt`.

---

## M5: Previews

**Goal:** see what an agent is building, such as a website, an image or later an emulator, without leaving the app.

**Scope:**

1. **Web preview:** forward a port on the host over the existing SSH connection, for example a dev server on `localhost:5173`, and open it in a Custom Tab or WebView.
   - Forwarding binds to loopback only.
   - The user starts and stops each forward and can see which forwards are active.
2. **Image preview:** for files an agent creates, through `file.read`. That method is not yet in the app's method list, so first check what the spec says about it.
3. **Later:** streaming an emulator or the screen, and casting. Each needs its own design.

**Start here:**

- SSH layer: `android/core/ssh/src/main/kotlin/com/mtajchert/flocket/core/ssh/SshConnector.kt` and `SshExecTransport`. SSHJ supports local port forwarding (`LocalPortForwarder`).
- Per-host connection ownership: `android/core/data/.../internal/SshHostConnector.kt` and `ConnectionManagerImpl.kt`. A forward must follow the host's connection epochs and reconnects.
- Security rules for forwards: `android/docs/SECURITY_DEFAULTS.md`.

---

## M6: Reach hosts beyond the local network

**Goal:** connect to a home or work machine from anywhere, with an easier first-time setup.

**Scope:**

- QR pairing: the desktop shows the host, port, host-key fingerprint and hc path, and the phone scans it, which replaces typing them in and trusting the key by hand.
- Tailscale and MagicDNS hosts.
- Possibly a relay.

The relay is a project-wide security decision and needs an ADR.

**Start here:**

- Onboarding: `android/feature/onboarding/impl/.../OnboardingScreen.kt` and `OnboardingViewModel.kt`.
- Host form and trust: `android/core/ui/src/main/kotlin/com/mtajchert/flocket/core/ui/hostform/`, `android/core/data` (`HostTrustRepository`), and [ADR-0005](decisions/0005-direct-ssh-transport.md).

---

## iOS

iOS can start in parallel with any milestone above. It shares no code with Android, only
the protocol. That was decided in [ADR-0004](decisions/0004-native-per-platform-shared-fixtures.md).

**Start here:**

- [`protocol/SPEC.md`](../protocol/SPEC.md), especially §11 (the reference client algorithm) and §12 (gotchas).
- Wire fixtures in [`protocol/fixtures/`](../protocol/fixtures/README.md).
- [ADR-0003](decisions/0003-terminal-engine-libghostty.md): libghostty also builds for iOS.
- The Android module boundaries in `android/docs/ARCHITECTURE.md`. iOS should mirror them where it makes sense: protocol, then session, then data, then UI.

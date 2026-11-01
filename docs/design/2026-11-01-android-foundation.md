# Flocket Android — foundation and milestone 1 design

Status: accepted for implementation · Date: 2026-11-01 · Scope: Android only (iOS later)

## 1. Intent

Flocket is an open-source, native mobile client for **herdr-core** (`hc`), which keeps
programs and coding agents alive in terminals on your machines. From a phone, foldable or
tablet, you should be able to see every terminal and its agent status at a glance, open a
real terminal, type into it, and leave again without disturbing anything on the host.

It is also the software house's flagship open-source project. The codebase has to be easy
to join (several teams, mostly AI agents), hard to break, and not over-engineered.

## 2. Goals, non-goals, anti-goals

**Goals (milestone 1)**

- Connect to a stock, current herdr-core over SSH (`ssh host hc connect`), with explicit
  host-key trust and keys kept in Android Keystore.
- Support several hosts. Show a list of terminals across hosts, including federated
  machines (`rohan/t7`), with the agent, status, cwd and liveness of each.
- Attach to a terminal with a real VT emulator (libghostty), and support typing, an
  extra-keys row, resizing, copying and detaching.
- Start a new terminal from a template or command, and kill or remove terminals, with
  confirmation for destructive actions.
- Recover from network changes, screen lock and process death without showing a stale
  "Online" state or sending input twice.
- Give phones a polished adaptive UX, and give foldables and tablets list-detail layouts.
- Make onboarding simple and good-looking: add a host, trust its key, copy your public
  key, and you're connected.
- Give agents and developers fast feedback: JVM tests in seconds, shared protocol
  fixtures, and a real-core integration test on the dev machine.

**Non-goals (later milestones)**

- Push notifications when the app is suspended (this needs a host-side companion).
- Sending files to a host (`terminal.put`), reading or editing remote files.
- A transcript or chat view of an agent conversation.
- Previewing images, websites or emulators, and casting.
- An internet relay, Tailscale integration, or QR pairing.
- Editing the shared `herdr-app` desktop layout document. A read-only import may come
  later.
- Compatibility with the original Herdr TUI server, its plugins, or its pane/workspace API.

**Anti-goals (it fails even if it "works" when…)**

- A dropped connection replays terminal input or approvals ("at most once", always).
- Opening a terminal on the phone silently resizes it for the desktop user.
- The protocol layer depends on SSH, Android, or a UI framework.
- Feature modules grow their own error types, networking or ad-hoc state machines.
- New contributors need Zig or the NDK just to change UI.

## 3. Decisions made by the project owner

| Decision | Choice | Record |
|---|---|---|
| Name and identity | **Flocket**, `com.mtajchert.flocket` (domain flocket.mtajchert.com) | ADR-0001 |
| License | **Apache-2.0** | ADR-0002 |
| Terminal engine | **libghostty via JNI** | ADR-0003 |
| Sharing with iOS | **Native code per platform**, with shared wire fixtures in `/protocol` | ADR-0004 |
| Target core | Only the latest herdr-core. The minimum is set at build freeze to the validated version. | handoff |

## 4. Architecture

```
┌────────────── app (navigation, adaptive scaffold, DI graph, lifecycle) ──────────────┐
│ feature:onboarding  feature:hosts  feature:terminals  feature:terminal  feature:settings │
│        (api/impl split; features never depend on another feature's impl)              │
├──────────────────────────────────────────────────────────────────────────────────────┤
│ core:ui  core:designsystem  core:terminal (Compose renderer + TerminalEngine API)     │
│ core:data (repositories, ConnectionManager: lifecycle, network, per-host sessions)    │
│ core:database (Room)  core:datastore (prefs)  core:security (Keystore, keys)          │
│ core:terminal-ghostty (JNI + prebuilt .so, implements TerminalEngine)                 │
├────────────────────────────── pure Kotlin / JVM (no Android) ─────────────────────────┤
│ core:session  — CoreSession: epochs, hello policy, event reducer, missed recovery,    │
│                 reconnect/backoff, attach sessions with credit, ActionOutcome         │
│ core:ssh      — SSHJ ByteTransport (exec, no PTY), HostKeyVerifier port, hc discovery │
│ core:protocol — ByteTransport port, NDJSON→framed codec, JSON-RPC client, DTOs        │
│ core:model    — CoreEndpoint, TerminalRef, TerminalRecord, AgentStatus, …             │
│ core:common   — AppResult / AppError, dispatchers, Clock, logging facade              │
└──────────────────────────────────────────────────────────────────────────────────────┘
```

The rules below are enforced by the build where possible.

1. **Dependency direction**: features → core. Core never depends on a feature. A feature
   may depend on another feature's `api` module, never on its `impl`. The JVM modules never
   depend on Android modules.
2. **Protocol is transport-agnostic.** `core:protocol` sees only `ByteTransport`
   (a bounded read flow, a suspend write and close). SSH, a local process (for tests) and the
   in-memory fake core all implement it.
3. **One authoritative model per connection.** `CoreSession` owns the hello, the
   subscription and the terminal records. UI state is derived from it, never written back.
   Each (re)connect gets a new **connection epoch**, and callbacks from older epochs are
   dropped.
4. **Hosts are independent.** Host A reconnecting must never block host B. Cached data
   from a disconnected host is shown as *stale*, and writes are disabled.
5. **Status is not liveness.** Process state (live, finished, lost) and agent status
   (OSC 7501, possibly unknown) are separate fields. Missing status means *unknown*.
6. **Mutations are at most once.** Reads may retry. Writes (input, keys, kill, run) are
   never replayed automatically. If the connection drops after dispatch,
   `ActionOutcome.Unknown` is shown and the state is reconciled by re-reading.

### Error handling (the one pattern everyone follows)

- Public APIs of `core:session`, `core:data` and repositories return `AppResult<T>`
  (`Success` or `Failure(AppError)`). They do not throw. `CancellationException` is
  always rethrown, never wrapped.
- `AppError` is a small **sealed** taxonomy in `core:common`, for example `Unreachable`,
  `AuthFailed`, `HostKeyUnknown`, `HostKeyChanged`, `CoreNotFound`, `CoreIncompatible`,
  `Rpc(code)`, `Timeout`, `OutcomeUnknown`, `Protocol` and `Storage`. A new case needs a
  review.
- `core:ui` maps each `AppError` to a string resource and to a suggested action
  (retry, edit host, trust key, …). Screens never format errors themselves.
- Low-level modules may throw typed exceptions internally. They are converted to
  `AppError` at the module's public boundary, and only there.

### Connection lifecycle

- `ConnectionManager` (app-scoped) holds one `CoreSession` per active host.
- While the app is in the foreground, sessions are kept open. When it goes to the
  background, a grace period (60 s) starts, then the sessions detach cleanly. There is no
  foreground service in M1, and terminals live on the host anyway. *(Superseded by
  [ADR-0006](../decisions/0006-background-short-service.md): Android 15+ cuts a background
  app's network after ~5 s, so with a terminal open a `shortService` keeps the link up for
  ~3 min; otherwise sessions detach at once.)*
- When the app returns to the foreground or the network comes back, the app reconnects
  with jittered exponential backoff, starts a new epoch, re-runs hello, re-subscribes,
  installs a fresh snapshot and re-attaches open terminals for a fresh first paint.

### Terminal surface

- `core:terminal` defines a `TerminalEngine` interface (feed bytes, resize, read screen
  state, encode keys, events for title, bell and clipboard). It also provides the Compose
  renderer that draws the cell grid on a Canvas, plus IME and hardware-keyboard input and
  an extra-keys row.
- `core:terminal-ghostty` implements the engine with **libghostty-vt** (Ghostty's VT
  parser and terminal state, MIT) over a small JNI shim of our own. libghostty-vt has no
  renderer, so we draw the cells. We use Ghostty's **key encoder**
  (`ghostty_key_encoder_setopt_from_terminal`) instead of hand-written key tables, so
  application-cursor mode and the Kitty keyboard protocol come out right.
  `android/scripts/build-ghostty.sh` pins a Ghostty commit and the Zig version taken from
  its `build.zig.zon`. It downloads Zig into `~/.cache` and needs `ANDROID_NDK_HOME`. It
  builds `arm64-v8a` and `x86_64` with 16 KB page alignment and writes `SHA256SUMS`.
  The resulting `.so` files (about 1.1 MB each) are **committed**, so contributors who
  don't touch native code never need Zig. The JNI shim does compile with the NDK through
  CMake, but Gradle downloads the NDK automatically.
- A pure-Kotlin `FakeTerminalEngine` handles JVM tests, previews and screenshot tests.
- Flow control: credit is returned only after bytes have been fed into the engine.
  Outgoing input is bounded. A stalled terminal never blocks control RPCs.
- **Resize policy** (`protocol/SPEC.md` §7.5): the most recently active viewer owns the
  size, so attaching from the phone would resize a desktop user's terminal. The phone
  therefore attaches **passively** (`observe`, or `resumed` without RESIZE) and renders at
  the terminal's current size (scaled or pannable). It takes ownership (RESIZE + FOCUS 1)
  on the first keystroke or an explicit "Fit to this screen", and for terminals it
  started itself. It sends FOCUS 0 when the app goes to the background, and never sends
  FACTS while passive (on 0.1.15, FACTS takes size ownership).

### Security

- SSH via SSHJ: an exec channel without a PTY runs `<hc path> connect`. Stdout carries
  only protocol bytes, and stderr is collected separately with a bound.
- **Host trust:** the host fingerprint is checked before any credential is sent. A first
  contact needs explicit user confirmation. A changed key is a hard failure with a
  clear explanation.
- **Device keys:** Ed25519 keys are generated on the device by default, with the private
  key encrypted by an AES-GCM key held in Android Keystore. Importing an OpenSSH key and
  password auth are supported. Passwords are not stored by default.
- `hc` path: the host's configured path is used if set. Otherwise it is detected once
  (`command -v hc` in a login shell, then the common install paths) and stored.

### Adaptive UX

- Compact width: a terminal list, then the terminal on its own full screen.
- Medium and expanded widths, and unfolded foldables: list-detail with Material 3 adaptive
  and a Navigation 3 scene strategy. Hinge and tabletop postures are respected.
- Hardware keyboards are first-class on tablets.

## 5. Testing strategy (fast feedback first)

| Layer | Where | Speed | Needs |
|---|---|---|---|
| Unit tests | JVM modules (`core:protocol`, `core:session`, `core:ssh`, `core:model`, `core:common`) | seconds | nothing |
| Wire contract tests | `/protocol/fixtures`, shared with iOS | seconds | nothing |
| Real-core integration | `core:protocol` and `core:session` via `hc connect --session flocket-test-*` | ~10 s | `hc` on PATH (skipped otherwise) |
| SSH integration | `core:ssh` against an embedded Apache MINA sshd that execs a fake or real core | ~10 s | nothing |
| Compose behaviour | Robolectric in `src/test` | ~30 s per module | nothing |
| Screenshot tests | Phone, foldable, tablet; light and dark; font ×1.5 | ~1 min | nothing |
| Device journeys | Emulator connects to the dev machine over SSH (10.0.2.2) or to the debug demo host | minutes | emulator |

A **debug-only demo host** (an in-process fake core that replays fixtures) lets agents
and screenshots exercise the whole app without SSH.

## 6. Repository layout

```
/README.md  /AGENTS.md  /CLAUDE.md  /LICENSE  /NOTICE
/docs/            shared docs: architecture, ADRs (docs/decisions), reference, design
/protocol/        SPEC.md, schema snapshots, wire fixtures (shared by Android and iOS)
/android/         Gradle project (README.md, AGENTS.md, CLAUDE.md, build-logic, modules)
/ios/             (future)
```

## 7. Calls made by the builder (open to override)

- `minSdk` **29** (Android 10), with `targetSdk` and `compileSdk` at 37. The template used 26.
- No foreground service in M1. A 60 s background grace period, then the session detaches.
  *(Superseded by ADR-0006: a `shortService` while a terminal is open, else immediate detach.)*
- Phone-side resize is opt-in, as above.
- Spotless + ktlint formatting, plus Android Lint and a build-time module-boundary check. No
  detekt yet.
- Convention plugins in `android/build-logic` replace per-module boilerplate.
- Hilt for DI and Navigation 3 for navigation, both carried over from the template.
- Room for hosts and pinned host keys, and DataStore for preferences.
- The demo host exists only in debug builds.

Scope updated 31 October 2026: our product targets only the new **herdr-core**, whose CLI is **hc**, with native Swift/SwiftUI and Kotlin/Compose clients. Compatibility with the original Herdr TUI, its server, plugins and pane/workspace API is outside the implementation scope. Earlier TUI clients remain component or design references only.

# Recommendation for a core only native app

Build a small core protocol client and native application around it. The closest reviewed starting reference is **zzulanas/herdr-ios**, especially its standalone HerdrKit package. Use the core-specific implementations in **eliasstravik/shepherdr**, **luiscleto/shepherdr** and **Collie** to cross-check framing, subscription recovery and layout projection. Borrow selected SSH, key storage, terminal rendering and notification components from other clients after replacing their TUI integration.

Heeler and Herdroid are therefore component references, not the primary end-to-end foundations for this scope. No reviewed native Kotlin codebase has been established here as a complete latest-core implementation. Android should implement the official core contract, using native-client references for platform integration and the same protocol fixtures as iOS.

## Version baseline and compatibility policy

The refreshed upstream default branch was **e07c2a1bedd62cf4787ee026ee23ee79c7d95491**, dated 31 October 2026. Its [Cargo manifest](https://github.com/herdrdev/herdr-core/blob/e07c2a1bedd62cf4787ee026ee23ee79c7d95491/crates/herdr-core/Cargo.toml) declares **0.1.16**. This identifies the inspected source version; it does not establish that a 0.1.16 release binary has been published. The protocol remains generation 1 and documented as preview.

Target the latest official core available at implementation time, record the actual `hc --version`, and save `hc schema` output with integration fixtures. At build freeze, set the product's minimum supported version to that validated current baseline; maintaining older core releases is not a requirement. Use the [official API contract](https://github.com/herdrdev/herdr-core/blob/e07c2a1bedd62cf4787ee026ee23ee79c7d95491/schema/README.md), [OpenRPC schema](https://github.com/herdrdev/herdr-core/blob/e07c2a1bedd62cf4787ee026ee23ee79c7d95491/schema/hc.openrpc.json) and server behavior as authority when client examples disagree.

Negotiate `hello`, check generation, framing, required features and only the methods our app needs. Refuse incompatible generations, but accept compatible additive versions and unknown optional fields. Keep optional features individually capability-gated. Do not equate a new patch version with an incompatible protocol.

Two reference-specific traps need correction before reuse:

- **eliasstravik/shepherdr:** [protocol.ts](https://github.com/eliasstravik/shepherdr/blob/b420dd0c7164f0637c9c1d16b1a3d28e1fcf2a8d/server/core/protocol.ts) pins 0.1.14 and [client.ts](https://github.com/eliasstravik/shepherdr/blob/b420dd0c7164f0637c9c1d16b1a3d28e1fcf2a8d/server/core/client.ts) requires exact version equality. This check would reject 0.1.16. Borrow the protocol implementation, replace that product policy.

- **luiscleto/shepherdr:** [core_client.go](https://github.com/luiscleto/shepherdr/blob/03455eaf099fc592d71816f4971b5e46cb857fe6/internal/herdr/core_client.go) validates against 0.1.15 and warns on newer versions. Its feature and method checks are useful; refresh the validated version set rather than inheriting the old range.

Source review establishes reusable patterns, not runtime compatibility. The clients and their tests were not built or executed against the refreshed core in this review.

# Connection and domain architecture

The default foreground path is **native app → authenticated SSH exec channel → `hc connect` → core**. Run the protocol channel without a PTY, keep stdout exclusively for protocol bytes, and collect bounded stderr separately. Resolve a configured or discovered core executable on the host; do not hardcode one binary alias or install path. SSH supplies encryption, host trust and account authentication. Core's local API is not a separate per-device authorization boundary.

Use one core-specific protocol layer, with platform transport implementations below it. A separate mobile HTTP gateway is not needed for this direct path. A small host notification companion can be added for suspended-app push; an internet relay is an optional later transport choice.

| Layer | Responsibility | Boundary to preserve |
| - | - | - |
| Native UI | Agent list, terminal, actions, settings and optional chat | No wire parsing or SSH callbacks in views |
| App state and actions | Terminal records, status, selected target, stale state, action outcomes | Connection generations reject old callbacks; UI state stays separate from server state |
| Core session | Handshake, request matching, event reducer, terminal channels and document cache | One authoritative model of the current core connection |
| Byte transport | SSH exec stream, cancellation, trust and bounded I/O | Protocol must not depend on a particular SSH library |
| Host runtime | Unmodified herdr-core | Core owns terminal and program lifetime; closing the app only detaches |

## Protocol and state rules

1. Connect in newline-delimited JSON-RPC 2.0 mode. Request framed mode in `hello`; upgrade parsing only after that reply. One read may contain both the reply and the first binary frame.

2. Implement the core frame header exactly: channel u32 little-endian, kind u8, length u32 little-endian, then payload. Enforce frame and aggregate buffer bounds. Channel zero carries RPC; terminal and event streams use assigned channels.

3. Bootstrap from the `events.subscribe` response, which contains a terminal snapshot and sequence. Apply subsequent events according to the core contract. On `missed`, install the supplied fresh terminal snapshot and refetch watched documents. On reconnect, start a new connection epoch and bootstrap again.

4. Start with an unfiltered event subscription so document events are visible. Sequence gaps must be interpreted with subscription filters in mind: core uses a global sequence and filtered streams can intentionally omit events. Do not copy an unfiltered reducer's strict contiguous-sequence assumption into a filtered subscription.

5. Use `terminal.attach` for interactive streams. Render the first paint, handle raw DATA, return CREDIT as output is consumed, and support resize, focus, display facts and CLOSE. Bound outgoing input as well as incoming output. A stalled terminal must not stall control RPCs.

6. Preserve remote terminal addresses such as `rohan/t7`. Core already federates machines. Do not copy Collie's deliberate exclusion of remote addresses or introduce a mandatory Crew service.

7. Keep process liveness separate from reported agent status. OSC 7501 status is program-provided; missing status is unknown. A kept terminal restarting with a new shell does not mean the old coding task resumed.

The official [wire implementation](https://github.com/herdrdev/herdr-core/blob/e07c2a1bedd62cf4787ee026ee23ee79c7d95491/crates/herdr-core/src/wire.rs), [events implementation](https://github.com/herdrdev/herdr-core/blob/e07c2a1bedd62cf4787ee026ee23ee79c7d95491/crates/herdr-core/src/events.rs), [machine documentation](https://github.com/herdrdev/herdr-core/blob/e07c2a1bedd62cf4787ee026ee23ee79c7d95491/docs/machines.md) and [security boundary](https://github.com/herdrdev/herdr-core/blob/e07c2a1bedd62cf4787ee026ee23ee79c7d95491/docs/security.md) are the canonical references for these rules.

## Terminals and grouping

Core owns terminals, labels, status, events and opaque documents. Desktop spaces, tabs and pane-layout JSON are application conventions stored in the `herdr-app` document, not the old TUI workspace API. Our own grouping can use labels and an app-owned document namespace. Treat desktop layout import as optional integration; the native app should work with core alone while the desktop window is closed.

If editing shared documents becomes a requirement, preserve unknown JSON fields and use the version returned by `doc.get` in `doc.set`. A conflict requires rereading and reconciling. Terminal snapshots do not contain document values; subscribe before reading, and refetch on relevant document events, reconnects and `missed` notifications. Do not blindly overwrite another app's layout with a simplified native model.

# What to borrow and exactly where

Links below are pinned to inspected revisions. **Adapt** means reusable code or behavior with stated changes. **Pattern** means reproduce the design in our own core implementation. None of these entries asks the team to adopt an entire application.

## Priority zero core protocol and correctness

| Work item | Exact source | What to borrow | Required adaptation or limit |
| - | - | - | - |
| API contract and generated types | [Core schema folder](https://github.com/herdrdev/herdr-core/tree/e07c2a1bedd62cf4787ee026ee23ee79c7d95491/schema) | Method/parameter/result/error definitions; schema fixture and additive compatibility rules | Generate or hand-map only the methods needed. Wire framing is documented separately and is not supplied by OpenRPC generation. Apache-2.0. |
| Swift wire client | [HerdrKit folder](https://github.com/zzulanas/herdr-ios/tree/a030501ccc71cce3dbc50bfeecb3e4dc7c3a2392/Packages/HerdrKit/Sources/HerdrKit), especially [HerdrClient.swift](https://github.com/zzulanas/herdr-ios/blob/a030501ccc71cce3dbc50bfeecb3e4dc7c3a2392/Packages/HerdrKit/Sources/HerdrKit/HerdrClient.swift) and [Frame.swift](https://github.com/zzulanas/herdr-ios/blob/a030501ccc71cce3dbc50bfeecb3e4dc7c3a2392/Packages/HerdrKit/Sources/HerdrKit/Frame.swift) | ByteTransport separation, request ids/cancellation, incremental frame decoding, early-channel handling, credits and close lifecycle | Closest native core starting point. Replace its unbounded outgoing AsyncStream with bounded buffering; review when credit is returned relative to actual rendering. Refine essential vs optional method requirements. App's iOS 26 floor is not a protocol requirement. Apache-2.0. |
| Independent framed implementation | [eliasstravik core/client.ts](https://github.com/eliasstravik/shepherdr/blob/b420dd0c7164f0637c9c1d16b1a3d28e1fcf2a8d/server/core/client.ts) and [protocol.ts](https://github.com/eliasstravik/shepherdr/blob/b420dd0c7164f0637c9c1d16b1a3d28e1fcf2a8d/server/core/protocol.ts) | Plain-to-framed transition, synchronous channel registration before first paint, request/stream routing and bounded pending requests | Cross-check and port behavior to Kotlin/Swift. Remove exact 0.1.14 equality; Node sockets/processes are not native-mobile dependencies. MIT. |
| Subscription reducer and recovery | [eliasstravik core/session.ts](https://github.com/eliasstravik/shepherdr/blob/b420dd0c7164f0637c9c1d16b1a3d28e1fcf2a8d/server/core/session.ts) | Connection epoch, subscription snapshot, event reduction, `missed` replacement, liveness and document resync callbacks | Preserve core semantics; its strict gap check assumes an unfiltered stream. Add native lifecycle/network handling and jitter. MIT. |
| Alternative core reader and attach | [luiscleto core_client.go](https://github.com/luiscleto/shepherdr/blob/03455eaf099fc592d71816f4971b5e46cb857fe6/internal/herdr/core_client.go), [core_attach.go](https://github.com/luiscleto/shepherdr/blob/03455eaf099fc592d71816f4971b5e46cb857fe6/internal/herdr/core_attach.go) and [core_project.go](https://github.com/luiscleto/shepherdr/blob/03455eaf099fc592d71816f4971b5e46cb857fe6/internal/herdr/core_project.go) | Generation/features checks, bounded core reads, channel/credit handling and projection of terminal records | Useful second implementation oracle. Use only core files; omit TUI subprocess bridge and desktop-window requirements. Apache-2.0. |
| Kotlin protocol fixtures and Swift regressions | [Swift ClientTests.swift](https://github.com/zzulanas/herdr-ios/blob/a030501ccc71cce3dbc50bfeecb3e4dc7c3a2392/Packages/HerdrKit/Tests/HerdrKitTests/ClientTests.swift), [HerdrKitTests.swift](https://github.com/zzulanas/herdr-ios/blob/a030501ccc71cce3dbc50bfeecb3e4dc7c3a2392/Packages/HerdrKit/Tests/HerdrKitTests/HerdrKitTests.swift), [core framed test helper](https://github.com/herdrdev/herdr-core/blob/e07c2a1bedd62cf4787ee026ee23ee79c7d95491/crates/herdr-core/tests/common/framed.rs) | Fragmented/coalesced frames, malformed lengths, hello compatibility, cancellation, credits and late attach cleanup | Share wire-byte fixtures across iOS/Android. Refresh expected messages against latest core. Tests are examples, not evidence that our port passes. Apache-2.0. |

## Priority one native platform integration

| Work item | Exact source | What to borrow | Required adaptation or limit |
| - | - | - | - |
| iOS SSH exec transport | [CitadelTransport.swift](https://github.com/zzulanas/herdr-ios/blob/a030501ccc71cce3dbc50bfeecb3e4dc7c3a2392/App/Sources/Net/CitadelTransport.swift) and [Contracts.swift](https://github.com/zzulanas/herdr-ios/blob/a030501ccc71cce3dbc50bfeecb3e4dc7c3a2392/Packages/HerdrKit/Sources/HerdrKit/Contracts.swift) | Core-specific no-PTY SSH channel, independent stdout/stderr, transport cancellation and friendly error classification | Use configured/discovered `hc connect`, support our host platforms and session selection. Strengthen first-contact host trust; do not inherit fixed paths. Apache-2.0. |
| Native iOS connection lifecycle | [AppStore.swift](https://github.com/zzulanas/herdr-ios/blob/a030501ccc71cce3dbc50bfeecb3e4dc7c3a2392/App/Sources/Store/AppStore.swift) | Connection generation guards, stale callback rejection, resume probes, deliberate background disconnect and initial-alert suppression | Extract a session/controller layer rather than copying a large UI store. Preserve remote-core terminals, which the sample app does not fully expose. Apache-2.0. |
| Explicit SSH host trust | [HeelerSSHHostKeyVerifier.swift](https://github.com/ZingerLittleBee/Heeler/blob/3c00f3d2e2d923716905df9e87a3626425a26452/Sources/Heeler/Transport/HeelerSSHHostKeyVerifier.swift) and [HostKeyPolicy.swift](https://github.com/ZingerLittleBee/Heeler/blob/3c00f3d2e2d923716905df9e87a3626425a26452/Sources/Heeler/Transport/HostKeyPolicy.swift) | First-use fingerprint confirmation before authentication, pin persistence and changed-key failure | Protocol-independent pattern. Keep it; replace all Herdr TUI-specific connection commands. Apache-2.0. |
| Android SSH and protected storage | [Herdroid SSH folder](https://github.com/deex2/herdroid/tree/6c59e1c0194723e00d48f16f88b3d740ec0239fc/app/src/main/java/dev/herdroid/core/ssh), especially [SshConnector.kt](https://github.com/deex2/herdroid/blob/6c59e1c0194723e00d48f16f88b3d740ec0239fc/app/src/main/java/dev/herdroid/core/ssh/SshConnector.kt), plus [SecretStore.kt](https://github.com/deex2/herdroid/blob/6c59e1c0194723e00d48f16f88b3d740ec0239fc/app/src/main/java/dev/herdroid/core/data/db/SecretStore.kt) | SSHJ integration, explicit trust, key/password separation, Keystore-wrapped storage and route handling | Run a core byte stream instead of its Rust TUI bridge. Hardware-only P-256 is its product choice, not a core requirement; decide our import/backup policy separately. Apache-2.0. |
| Android connection ownership | [Herdroid session implementation folder](https://github.com/deex2/herdroid/tree/6c59e1c0194723e00d48f16f88b3d740ec0239fc/app/src/main/java/dev/herdroid/session/impl), especially ConnectionStateMachine.kt and ConnectionOwner.kt | Service ownership, transient vs fatal error handling, retry and network-restored wakeup | Replace bridge/protocol callbacks with our core session. Existing alerts depend on live SSH and do not solve dead-process push. Apache-2.0. |
| Terminal renderer candidates | [benkraus native terminal module](https://github.com/benkraus/herdr-mobile/tree/c133a1ffb61a0429f7cddc253440468c048e4b0e/modules/t3-terminal), including ios and android folders; [core-native TerminalEngine.swift](https://github.com/zzulanas/herdr-ios/blob/a030501ccc71cce3dbc50bfeecb3e4dc7c3a2392/App/Sources/UI/TerminalEngine.swift) | Ghostty-backed native rendering on both platforms, or existing SwiftTerm integration tied to core attach | Pick a renderer per platform and feed raw core DATA bytes. Remove Expo wrappers as needed; do not copy TUI terminal.frame decoding. Review Windows keyboard mode: the Swift sample strips CSI ?9001, which is not a complete ConPTY input implementation. MIT module with component notices; Swift sample Apache-2.0. |

## Priority two optional product features

| Work item | Exact source | What to borrow | Required adaptation or limit |
| - | - | - | - |
| Capability model and core mapping | [Collie core adapter folder](https://github.com/AltanS/collie/tree/9eeedd89b380ac19005da6aa0f72f09a1df2df9c/bridge/mux/herdr-core), especially adapter.ts, client.ts and layout.ts | Capability-driven UI, explicit unsupported outcomes and conversion to app-owned models | Simplify to one engine. Do not inherit remote-address exclusion, Crew federation, plain-text-only mirror or limits caused by its web product. MIT. |
| Optional desktop layout import | [Swift Layout.swift](https://github.com/zzulanas/herdr-ios/blob/a030501ccc71cce3dbc50bfeecb3e4dc7c3a2392/Packages/HerdrKit/Sources/HerdrKit/Layout.swift) and [luiscleto core_projector.go](https://github.com/luiscleto/shepherdr/blob/03455eaf099fc592d71816f4971b5e46cb857fe6/internal/herdr/core_projector.go) | Read/projection of the herdr-app document, preservation of unknown fields and orphan-terminal presentation | Begin read-only. Treat herdr-app JSON conventions as app-level coupling; core treats the document as opaque. Apache-2.0. |
| Action outcomes and deduplication | [Pairfob operations.go](https://github.com/arronKler/pairfob/blob/152dc9e8f9b0c3de8fb12369325f0a15839f26f9/internal/daemon/operations.go) | Persist intent before effects, bind operation id to target/intent, and restore interrupted operations as unknown | Pattern only for a future host coordinator. Core JSON-RPC ids do not activate this ledger, and a phone-local ledger cannot prove remote execution after a timeout. Never port Pairfob's TUI runtime adapter. Apache-2.0. |
| Core event to APNs prototype | [core-native herdr-notify.py](https://github.com/zzulanas/herdr-ios/blob/a030501ccc71cce3dbc50bfeecb3e4dc7c3a2392/App/Resources/Notify/herdr-notify.py) | Core event monitoring, notification targeting and a concrete host-originated alert path | Prototype reference. Its direct APNs credential deployment is not a product-wide onboarding solution; design app-owned enrollment/revocation and native targets. Apache-2.0. |
| Encrypted push payloads | [Heeler seal-envelope.js](https://github.com/ZingerLittleBee/Heeler/blob/3c00f3d2e2d923716905df9e87a3626425a26452/plugin/src/seal-envelope.js), [notification-envelope.js](https://github.com/ZingerLittleBee/Heeler/blob/3c00f3d2e2d923716905df9e87a3626425a26452/plugin/src/notification-envelope.js) and [relay folder](https://github.com/ZingerLittleBee/Heeler/tree/3c00f3d2e2d923716905df9e87a3626425a26452/relay) | Per-device authenticated encryption and a push relay that forwards ciphertext | Replace TUI plugin hooks with core event subscriptions; issue our own app/bundle push credentials. Recheck metadata leakage and audit the adapted protocol. Apache-2.0. |
| Android push lifecycle | [Lerdr push folder](https://github.com/IGUNUBLUE/lerdr-rust-kotlin/tree/42e4dd7629d06cd5d66edff140a345bd0ba1031a/app/app/src/main/kotlin/com/lerdr/app/push) and [PushSubscriptionMigrationTest.kt](https://github.com/IGUNUBLUE/lerdr-rust-kotlin/blob/42e4dd7629d06cd5d66edff140a345bd0ba1031a/app/app/src/test/kotlin/com/lerdr/app/push/PushSubscriptionMigrationTest.kt) | Endpoint registration, acknowledged migration and reconnect reconciliation | Product pattern. Wire it to our core notification companion; choosing UnifiedPush versus another native delivery path is independent of the core protocol. MIT. |

## What the team should not transplant

| Earlier candidate or component | Core only disposition |
| - | - |
| benkraus relay and useHerdrConnection | Native rendering reference only. Its REST/WSS contract, TUI pane ids and terminal-frame state machine are not the core protocol. |
| carsol and bsorescu integrations | No core transport reuse. TUI CLI wrappers, PTY attachment and directory-based transcript matching should not enter our new client. |
| Heeler, Herdroid and makefunstuff TUI bindings | Borrow platform components; replace their pane RPCs, terminal-session commands, host shims and event semantics. |
| Pairfob, 0cv and Lerdr complete gateways | Optional infrastructure references, not core-compatible drop-ins. Host adapters need a core implementation. 0cv is AGPL-3.0-or-later; do not treat it as the same code-reuse choice as the listed MIT/Apache components. |
| Kraal endpoint client and Herdrup api-bridge | Different protocol or server-fork assumptions. Kraal's TUI endpoint generation 1 is not core generation 1. |
| eliasstravik exact 0.1.14 check | Replace version equality with current generation/features/method policy. |
| Desktop-app command actions | Optional future integration. Creating, viewing and controlling core terminals must not require an open desktop window. |

# Initial team handoff

1. **Protocol owners:** read the official schema/wire/events contract, implement bounded parsers in Swift and Kotlin, and share byte fixtures. Cross-check both independent core clients rather than copying one unchecked.

2. **iOS owners:** extract HerdrKit and its ByteTransport, replace unbounded buffering and automatic first-use trust, then connect through no-PTY SSH. Keep UI/controller state separate.

3. **Android owners:** reuse SSH/storage/session ownership patterns from Herdroid, implement a core-specific transport and reducer, and attach a renderer directly to core channel bytes. The TUI Rust bridge is not part of this work.

4. **State/UI owners:** support local and federated terminal addresses, fresh snapshots after reconnect, unknown agent status and optional read-only desktop layout import. Avoid a global dependency on desktop spaces/tabs.

5. **Validation owners:** run the scenarios below against the actual latest official core available when implementation begins, record its version/schema and exercise real devices. None of the borrowed code is assumed already compatible with that version.

Suggested first milestone: both native platforms connect to a stock current core, list terminals/status, open a real terminal, type/resize/detach, and recover from network change and process restart with bounded memory. Add transcript chat, push and relay onboarding after that core loop is demonstrated.

# Implementation rules for the core native clients

## Native state and session ownership

Define app-owned CoreEndpoint, TerminalRef, TerminalRecord, AgentStatus, ConnectionState, CapabilitySet and ActionOutcome models. This product has one engine: herdr-core. Qualify terminal identity with the configured endpoint/core identity and address; use a separate local connection epoch to reject late callbacks. Do not assume the persistent core identity changes whenever its process restarts.

Separate a canonical state store from the screen's presentation state. A disconnected machine may show cached data, visibly stale, while writes stay disabled. Machine A reconnecting must not block Machine B. Cancel or reject late callbacks from the previous connection generation or selected profile.

| Module | Contract | Primary references |
| - | - | - |
| Core protocol | Handshake, JSON-RPC, framed channels, cancellation and limits | Official core schema/wire; Swift HerdrKit; independent Shepherdr core clients |
| SSH transport | No-PTY byte stream, host trust, cancellation and bounded I/O | Core-native CitadelTransport; Heeler verifier; Herdroid SSH/storage |
| State reconciler | Subscription snapshot, sequence/epoch, missed recovery and document refresh | Official core events; eliasstravik CoreSession; native AppStore |
| Terminal session | Initial paint, DATA/CREDIT, input, resize, focus and close | Official attach contract; native renderer components |
| Conversation provider | Optional harness-specific transcript with exact session association | Core-native chat code as a limited example; never infer by directory alone |
| Action coordinator | Target validation, dispatch state and unknown-outcome reconciliation | Core method semantics; Pairfob ledger as an optional host-side pattern |
| Notification coordinator | Core event watcher, enrolled devices and native push | Core-native notifier; adapted Heeler encryption; Android push lifecycle |

## Direct SSH connection

Use an authenticated SSH exec channel to run the installed core's connect command without a PTY. Keep the core protocol client independent of Citadel, SSHJ or another transport library. Select core sessions through a validated host configuration and keep stdout free from banners or diagnostics. Runtime ownership remains on the host; detaching or cancelling a viewer must not stop the agent.

Verify the host fingerprint before presenting credentials. Pairing should bind a device key to the expected host fingerprint; a QR containing only an address is insufficient. Keep device secrets in Keychain/Keystore. SSH over Tailscale is useful when hosts are otherwise unreachable, but Tailscale should be an optional network route rather than part of the domain model.

An internet relay is optional product work after the direct core connection is proven. If needed, adapt the transport and enrollment patterns in the borrowing section while implementing a core-specific host adapter. A push notification companion can be much smaller than a terminal gateway. Neither should become a prerequisite for foreground SSH use.

## Keep terminal and conversation surfaces separate

A real terminal remains necessary for arbitrary agents, interactive prompts, shell escape, cursor movement and alternate screens. A friendly transcript is a separate projection for reading, search and tool activity. It does not become an authoritative approval protocol just because it is drawn as a chat bubble.

Core terminal addresses identify runtime terminals, not a universal Claude/Codex conversation. An optional chat provider must bind to the actual harness session using trustworthy session metadata or hooks. Handle multiple agents in one directory, agent replacement, transcript rotation and unsupported formats. Keep chat a projection; use core actions or attached input to control the actual terminal.

Use a tested terminal emulator, bound scrollback and buffers, and return core stream credit as bytes are consumed. Reject callbacks from prior connection epochs and request a fresh attach/paint after stream failure. Implement the core's input, focus and resize semantics, including Windows keyboard-mode behavior when supporting ConPTY. Define who may resize a shared terminal so opening the phone does not unexpectedly disturb another viewer.

## Make uncertain delivery visible

Reads may retry; terminal writes and approvals need a different policy. If a connection breaks after dispatch but before acknowledgment, show an unknown outcome and reconcile rather than blindly sending again. A request id alone does not provide exactly-once execution unless the receiver durably remembers it. Distinguish queued input from agent acceptance and observed completion.

Before answering an approval, refresh the target identity and current prompt. Notifications should identify an event and intended session, not contain a reusable unrestricted shell credential. A notification tap should reopen and validate current state before applying an action.

## Design background notifications as a host-side feature

An open socket and a foreground local notification are not reliable suspended-app delivery. Apple's [background execution guidance](https://developer.apple.com/documentation/uikit/preparing-your-ui-to-run-in-the-background) explains that apps do not normally receive extra execution time after entering background. Android [periodic work](https://developer.android.com/reference/androidx/work/PeriodicWorkRequest) has a 15-minute minimum and can be delayed.

Use host-side event monitoring with APNs and an appropriate Android push path for timely alerts; native storage, per-device revocation and minimal/encrypted payloads belong in the design. Keep foreground SSH operation usable without push. Resume by checking liveness and refreshing authoritative state; do not assume the previous connection survived suspension.

# Qualification before calling a foundation gold standard

These are the next experiments that would distinguish a promising codebase from a dependable foundation. They have not been performed by this source review.

| Scenario | Required evidence |
| - | - |
| Latest core handshake | Record actual hc version and schema; current validated baseline connects; compatible additive versions pass capability checks; incompatible generation fails |
| Frame parser | Fragmented/coalesced reads, plain reply plus first frame in one read, malformed lengths, invalid channel state and bounded memory |
| Attach flow control | First paint, CREDIT after consumption, stalled renderer, input/resize/focus, orderly CLOSE and late attach cleanup |
| Event recovery | Initial subscription snapshot, unfiltered sequences, filtered gaps, missed replacement and reconnect with sequence reset |
| Documents | Refetch after reconnect/missed/doc event; optimistic version conflict handled; unknown JSON preserved if writing |
| Machine federation | Local and prefixed remote addresses remain distinct; remote loss and recovery do not disable healthy machines |
| Native lifecycle | Screen lock, Wi-Fi/cellular change, app death and foreground resume recover without stale Online state or duplicate input |
| Action ambiguity | Disconnect before/after dispatch; unknown outcomes remain visible; no automatic replay of mutating calls |
| Trust and storage | Unknown/changed SSH host keys, imported/generated device keys and unavailable protected storage are handled deliberately |
| Background alerts | A real core event reaches a suspended device; tap revalidates the current target rather than executing stale approval data |

Qualify the Swift HerdrKit-based core implementation and the new Kotlin core protocol layer against the same fixtures and real stock-core host. Heeler/Herdroid trials qualify borrowed platform components only. Builds, tests and device compatibility remain implementation-team validation work; this source review does not establish passing results.

# Historical discovery appendix and source revisions

The following catalog preserves the wider source survey for traceability. It is not a list of core-compatible foundations. Current implementation priorities and all borrowing decisions are defined above.

All six supplied repositories received actual source inspection. Additional source inspections covered Heeler, Herdroid, makefunstuff, lerdr, mohamed-essam, BisonHerdr, 0cv, teasec4, tuan3w, HerdrGo, Ovis, eliasstravik, Herdrup, Kraal, zzulanas and Herdi iOS. Their depth varies: the original six and shortlisted foundations received closer transport/recovery review; some secondary candidates received a narrower architectural inspection.

The discovery-only candidates below were found during the broader mobile-client survey. Their descriptions do not establish current herdr-core support, native implementation quality or latest-version compatibility. Do not add one to the implementation baseline without inspecting its actual core binding. TUI-only code is outside the target architecture.

## Other iOS oriented discoveries

Discovery only; implementation quality and compatibility are not established by repository descriptions.

[cunderw/driverr](https://github.com/cunderw/driverr), [leescot/herdr-remote-ios](https://github.com/leescot/herdr-remote-ios), [rbstp/collie](https://github.com/rbstp/collie), [Volpestyle/herdr-ios](https://github.com/Volpestyle/herdr-ios), [BrianBal/herdr-ios](https://github.com/BrianBal/herdr-ios), [Xuzan9396/herdr-ios](https://github.com/Xuzan9396/herdr-ios), [SKS-MStow/IOS-Herdr](https://github.com/SKS-MStow/IOS-Herdr), [nourhelmi/herdr-mobile](https://github.com/nourhelmi/herdr-mobile), [nimrc/bellterm-ios](https://github.com/nimrc/bellterm-ios), [nguyenhuy158/hc-ios](https://github.com/nguyenhuy158/hc-ios), [rarce/wooloo-io](https://github.com/rarce/wooloo-io), [skelz0r/heraldr](https://github.com/skelz0r/heraldr), [3loc/herden](https://github.com/3loc/herden), [btuckerc/herdwick](https://github.com/btuckerc/herdwick).

## Other Android oriented discoveries

Discovery only; implementation quality and compatibility are not established by repository descriptions.

[dany547/Herdroid-herdr-android-app-project](https://github.com/dany547/Herdroid-herdr-android-app-project), [nimrc/bellterm](https://github.com/nimrc/bellterm), [manosriram/herdr-android](https://github.com/manosriram/herdr-android), [gimso2x/herdr-android](https://github.com/gimso2x/herdr-android), [ch8n/herdr-remote-android](https://github.com/ch8n/herdr-remote-android), [brittonr/herdr-gpui-android](https://github.com/brittonr/herdr-gpui-android), [NielsdaWheelz/herdr-mobile](https://github.com/NielsdaWheelz/herdr-mobile), [artogahr/herdroid](https://github.com/artogahr/herdroid), [angaz/sshepherd](https://github.com/angaz/sshepherd), [nico2525nn/herdr-mobile](https://github.com/nico2525nn/herdr-mobile), [RazvanManolache/herdr-on-the-go](https://github.com/RazvanManolache/herdr-on-the-go), [rarce/wooloo-android](https://github.com/rarce/wooloo-android), [maxiusofmaximus/excubia](https://github.com/maxiusofmaximus/excubia), [code-akram/or2](https://github.com/code-akram/or2), [muratbilge/kabuk](https://github.com/muratbilge/kabuk), [enzodevs/openherdr](https://github.com/enzodevs/openherdr), [leekt/pdx](https://github.com/leekt/pdx), [subin-babu-ai/pocketherd](https://github.com/subin-babu-ai/pocketherd), [azusachino/cappuccino](https://github.com/azusachino/cappuccino), [Ormus-Solutions/mercurio](https://github.com/Ormus-Solutions/mercurio), [s8-ken/Acrylterm](https://github.com/s8-ken/Acrylterm).

## Other Flutter React Native and web candidates

Discovery only; implementation quality and compatibility are not established by repository descriptions.

[kosumic/whip](https://github.com/kosumic/whip), [Tomyail/herdr-connect](https://github.com/Tomyail/herdr-connect), [weekitmo/herdr-pocket](https://github.com/weekitmo/herdr-pocket), [keinstn/drover](https://github.com/keinstn/drover), [finik/shepherd](https://github.com/finik/shepherd), [juanibiapina/herdr-mobile](https://github.com/juanibiapina/herdr-mobile), [nihey/herdr-mobile](https://github.com/nihey/herdr-mobile), [nyx-rattapoom/herdr-mobile-client](https://github.com/nyx-rattapoom/herdr-mobile-client), [SandroHub013/herdr-mobile](https://github.com/SandroHub013/herdr-mobile), [HFO4AR/herdr-pocket](https://github.com/HFO4AR/herdr-pocket), [0xharkirat/herdr_mobile](https://github.com/0xharkirat/herdr_mobile), [powerfooI/roamgate](https://github.com/powerfooI/roamgate), [kcosr/herdr-web](https://github.com/kcosr/herdr-web), [barnuri/herdr-web](https://github.com/barnuri/herdr-web), [eyalev/herdr-web](https://github.com/eyalev/herdr-web), [alecuba16/herdr-webui](https://github.com/alecuba16/herdr-webui), [stefanopineda/herdr-web](https://github.com/stefanopineda/herdr-web), [arcnessai/arcdr](https://github.com/arcnessai/arcdr), [wuxiaobai24/mobile-herdr](https://github.com/wuxiaobai24/mobile-herdr), [lamngockhuong/termote](https://github.com/lamngockhuong/termote), [beefiker/herdloom](https://github.com/beefiker/herdloom), [jeanlorencini/herdr-web-ui](https://github.com/jeanlorencini/herdr-web-ui), [ihainan/corral](https://github.com/ihainan/corral).

## Excluded or unresolved entries

`martebytes/herdr-mobile` redirects to carsol. `shepherdr.dog` advertises a separate native preview but no public source link was located, so it is not ranked. Notification-only plugins such as meshterm-herdr-ios, generic terminal clients without a checked Herdr adapter, empty repositories and unrelated livestock projects are excluded. This is a dated public-source survey, not a guarantee that no unindexed client exists.

Pinned source revisions make the findings reproducible; later repository changes may fix the concerns or change their scope.

| Repository | Commit snapshot | Commit date UTC | License in source |
| - | - | - | - |
| 0cv/herdr-mobile-relay | [76c797e17c5e](https://github.com/0cv/herdr-mobile-relay/tree/76c797e17c5e7c0d094bfc7fa98a462d55676c8e) | 2026-10-31 | AGPL-3.0-or-later |
| AltanS/collie | [9eeedd89b380](https://github.com/AltanS/collie/tree/9eeedd89b380ac19005da6aa0f72f09a1df2df9c) | 2026-10-31 | MIT |
| IGUNUBLUE/lerdr-rust-kotlin | [42e4dd7629d0](https://github.com/IGUNUBLUE/lerdr-rust-kotlin/tree/42e4dd7629d06cd5d66edff140a345bd0ba1031a) | 2026-10-21 | MIT |
| ToxicBakery/BisonHerdr | [219220c71328](https://github.com/ToxicBakery/BisonHerdr/tree/219220c713284377092ea342383df664b0f17544) | 2026-10-28 | Apache-2.0 |
| ZingerLittleBee/Heeler | [3c00f3d2e2d9](https://github.com/ZingerLittleBee/Heeler/tree/3c00f3d2e2d923716905df9e87a3626425a26452) | 2026-10-31 | Apache-2.0 |
| arronKler/pairfob | [152dc9e8f9b0](https://github.com/arronKler/pairfob/tree/152dc9e8f9b0c3de8fb12369325f0a15839f26f9) | 2026-10-31 | Apache-2.0 |
| benkraus/herdr-mobile | [c133a1ffb61a](https://github.com/benkraus/herdr-mobile/tree/c133a1ffb61a0429f7cddc253440468c048e4b0e) | 2026-08-23 | MIT |
| benkraus/herdr-plugin-mobile-relay | [0195d7d959df](https://github.com/benkraus/herdr-plugin-mobile-relay/tree/0195d7d959df5d7df163cf1e25c598aaab9720ef) | 2026-08-12 | MIT |
| bsorescu/herdr-mobile | [459c02a2362b](https://github.com/bsorescu/herdr-mobile/tree/459c02a2362b3535320d457dc8df7a1ae336d15b) | 2026-08-25 | MIT |
| carsol/herdr-mobile | [3a89e8047e65](https://github.com/carsol/herdr-mobile/tree/3a89e8047e65c61c631146fda77af2963ca8c1a2) | 2026-09-15 | MIT |
| dcolinmorgan/herdr-remote | [67582cec5d86](https://github.com/dcolinmorgan/herdr-remote/tree/67582cec5d86ae83897074403d373716c77ede78) | 2026-10-30 | AGPL-3.0-or-later or commercial |
| deex2/herdroid | [6c59e1c01947](https://github.com/deex2/herdroid/tree/6c59e1c0194723e00d48f16f88b3d740ec0239fc) | 2026-10-19 | Apache-2.0 |
| devswha/ovis | [16bdda598093](https://github.com/devswha/ovis/tree/16bdda5980933a73d5e10c4fd3ec4c4e51c36f54) | 2026-10-31 | MIT |
| eliasstravik/shepherdr | [b420dd0c7164](https://github.com/eliasstravik/shepherdr/tree/b420dd0c7164f0637c9c1d16b1a3d28e1fcf2a8d) | 2026-10-31 | MIT |
| herdr-go/herdr-go | [ebeaa4202955](https://github.com/herdr-go/herdr-go/tree/ebeaa420295540b19bbdadf85561738ff74849a3) | 2026-09-08 | No repository LICENSE found |
| herdrdev/herdr-core | [e07c2a1bedd6](https://github.com/herdrdev/herdr-core/tree/e07c2a1bedd62cf4787ee026ee23ee79c7d95491) | 2026-10-31 | Apache-2.0 |
| herdrdev/herdr | [ee5bfb5041bb](https://github.com/herdrdev/herdr/tree/ee5bfb5041bb3e4a49d591c72308aae3c81ecdde) | 2026-10-29 | Apache-2.0 |
| luiscleto/shepherdr | [03455eaf099f](https://github.com/luiscleto/shepherdr/tree/03455eaf099fc592d71816f4971b5e46cb857fe6) | 2026-10-31 | Apache-2.0 |
| makefunstuff/herdr-android | [0072bc9fec8d](https://github.com/makefunstuff/herdr-android/tree/0072bc9fec8da1ee1855587b2ef6cdbf01963453) | 2026-10-31 | MIT |
| mohamed-essam/herdr-mobile | [c14e74bb1082](https://github.com/mohamed-essam/herdr-mobile/tree/c14e74bb1082959898ded9f88b97613a03c48143) | 2026-10-20 | AGPL-3.0 |
| teasec4/herdr-mobile-app | [027340414104](https://github.com/teasec4/herdr-mobile-app/tree/02734041410404ef0d3f6bcaf2a9379517439967) | 2026-09-05 | MIT |
| themartianapp/herdrup | [9845ecf0743c](https://github.com/themartianapp/herdrup/tree/9845ecf0743c7dce8073dc4a00db23b1fec069a8) | 2026-10-30 | Apache-2.0 |
| tuan3w/herdr-mobile | [91ed042cd68f](https://github.com/tuan3w/herdr-mobile/tree/91ed042cd68f2b2b20f4784158359c00fda11be4) | 2026-10-30 | GPL-3.0 |
| zzjcool/kraal | [baae4a644be0](https://github.com/zzjcool/kraal/tree/baae4a644be02b44e3310529b5fe0e936a232278) | 2026-10-30 | Apache-2.0 |
| zzulanas/herdr-ios | [a030501ccc71](https://github.com/zzulanas/herdr-ios/tree/a030501ccc71cce3dbc50bfeecb3e4dc7c3a2392) | 2026-10-30 | Apache-2.0 |

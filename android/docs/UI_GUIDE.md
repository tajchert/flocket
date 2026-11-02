# UI guide

## Principles

- Generic Material 3 from `:core:designsystem` (`FlocketTheme`, dynamic color on, design v2 teal fallback scheme). No custom widget kits.
- Fixed semantic colors (attention/amber = blocked on you, red = error only, done/green, terminal preview, key caps) are not dynamic: read `MaterialTheme.flocketColors` (`FlocketColors`), never hard-code hex. Map an `AgentStatus` with `agentStatusStyle()` (`:core:ui`); shared pieces: `AgentTile`, `AgentStatusChip`, `NeedYouPill`, `CountBadge`, `GroupHeader`, `DotFilterChip`.
- Mono UI text (previews, addresses, fingerprints, commands, key caps) uses `FlocketMonoFamily` / `FlocketMono.label|small|body` (JetBrains Mono, shared with the terminal); other text stays the system font. Corner radii: `FlocketCorners`.
- Accessible by default: touch targets of at least 48dp, a `contentDescription` for every icon button (decorative icons use `null`), and no color-only meaning. Status always pairs color with an icon and a word (`AgentStatusChip`, `ConnectionStateBadge`). `Unknown` agent status is neutral "No status", never "Idle".
- All user-visible text is in `res/values/strings.xml` of the owning module; use plurals for counts; layouts must survive RTL and font scale 1.5.
- Dark theme must work: screenshot wrappers put content in a `Surface`.
- Content width: readable column up to 600-720dp (`CenteredContent`); never stretch forms across a tablet.

## Adding a screen

1. Route: a `@Serializable` `NavKey` in `feature:x:api`.
2. State: a `data class XUiState` (or sealed interface) and a Hilt `@HiltViewModel` exposing `StateFlow<XUiState>`. Inject repository interfaces from `:core:data` only. One-off outcomes are state flags, not events.
3. Screens: a stateless `XScreen(state, callbacks)` (callbacks as lambdas or a small data class with no-op defaults) and a private stateful route that does `hiltViewModel()` + `collectAsStateWithLifecycle()` and calls the navigator. No business logic in composables.
4. Entry: add `entry<XRoute> { ... }` to the feature's `EntryProviderInstaller` module (see ARCHITECTURE.md, Navigation). Persist form input in `SavedStateHandle`.
5. Tests: ViewModel unit tests with fakes and `MainDispatcherRule`; a Robolectric Compose test for behavior; screenshot tests in `src/screenshotTest` (`@PreviewTest` with `@PreviewFormFactors`, `@PreviewLargeFont`, a dark preview). Look at the PNGs before committing.

## Errors

`AppError` becomes text in exactly one place: `AppError.toUiError()` in `:core:ui` (`UiError(title, message, primaryAction, detail)`). The `when` is exhaustive, so a new `AppError` case does not compile until mapped. `UiText` is a resource id plus args, so ViewModels hold `UiError` without a `Context`. Show it with `ErrorState` (full area) or `ErrorBanner` (inline) and handle `UiErrorAction` (Retry, TrustHostKey, EditHost, InstallHelp, ReenterPassword, ForgetPinnedKey) in the screen or ViewModel. Never format an `AppError` in a screen.

Trust: `HostKeyTrustDialog` for first contact (pin with `HostTrustRepository.pinAndRetry`). `HostKeyChangedDialog` never offers trust; it offers "Forget pinned key..." behind a second confirmation (`forgetAndRetry`).

## Fakes in previews and tests

`:core:testing` provides `FakeHostRepository`, `FakeHostTrustRepository`, `FakeDeviceKeyRepository`, `FakePreferencesRepository`, `FakeConnectionManager` with `FakeCoreSession` (drive state with `session.set(...)` / `update {}`), `MainDispatcherRule` and `SampleData` (hosts, terminals of every status, a stale host, a remote `rohan/t7`, host key errors). Screens get plain state objects built from `SampleData`; screenshot tests never touch Hilt. Debug builds of `:app` bind the fakes through `DemoDataModule` until `:core:data` has real bindings.

## Adaptive rules

- Decide layout from available width (`BoxWithConstraints`) or the pane directive, never from device type. Compact: one column; 600dp+: centered content; 840dp+: two panes where it helps (onboarding welcome, hosts list-detail).
- A detail pane draws no back arrow when two panes are visible (`rememberIsMultiPane()` in hosts).
- Respect insets (`safeDrawing`) in custom bars; use `imePadding()` on forms.
- Screenshot every screen at phone, foldable and tablet widths (`@PreviewFormFactors`), at font scale 1.5 and in dark.

## Settings layering

Terminal settings come in two layers. App-only: theme, dynamic color, keep connected in background. Overridable per host (Settings, "Terminal defaults" and Host detail, "Host settings"): font size, color scheme, extra keys, keep screen on, clipboard write policy, bell, connect on launch, new-terminal folder.

- Model and resolver: `core:model` `TerminalPreferences`, `HostSettingsOverrides` (null = "Use app default") and `resolve(app, overrides)`. Consumers read `HostSettingsRepository.effective(hostId)`; never merge by hand.
- Storage: app defaults in DataStore (`UserPreferences.terminal`), overrides in the Room `host_settings` table (schema v2, one row per host that overrides anything, cascades with the host, no row = all defaults).
- UI: one shared `TerminalSettingsForm` (`core:ui`) serves both screens. Host mode adds a "Use app default (value)" switch per setting; turning it off starts from the current app default. The hosts list shows a "Custom" badge.
- Pinch-zoom writes the host's font override when it has one, else the app default.
- `autoConnect` off: the host stays Idle until `ConnectionManager.connect(hostId)` (opening the host or one of its terminals, pull to refresh, Connect).
- New terminal folder: the configured default (host override, else app default) wins; only when none is set, the last folder used on the host, else `~`.

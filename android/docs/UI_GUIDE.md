# UI guide

## Principles

- Generic Material 3 from `:core:designsystem` (`FlocketTheme`, dynamic color on, teal fallback). No custom widget kits.
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

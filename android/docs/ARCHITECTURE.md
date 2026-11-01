# Android architecture

Condensed from the [foundation design](../../docs/design/2026-11-01-android-foundation.md) (section 4), which is authoritative.

## Module graph

```mermaid
graph TD
  app --> feature_impl["feature:*:impl"]
  feature_impl --> feature_api["feature:*:api (own and other features)"]
  feature_impl --> core_ui[core:ui]
  feature_impl --> core_data[core:data]
  feature_impl --> core_terminal
  core_ui --> core_designsystem[core:designsystem]
  core_terminal[core:terminal] --> core_designsystem
  core_terminal --> core_session
  core_ghostty[core:terminal-ghostty] --> core_terminal
  core_data --> core_session[core:session]
  core_data --> core_ssh[core:ssh]
  core_data --> core_database[core:database]
  core_data --> core_datastore[core:datastore]
  core_data --> core_security[core:security]
  core_session --> core_protocol[core:protocol]
  core_ssh --> core_protocol
  core_protocol --> core_model[core:model]
  core_protocol --> core_common[core:common]
  core_common --> core_model
```

Layers, top to bottom: `:app` (navigation, DI graph, lifecycle); feature modules; Android core modules (`ui`, `designsystem`, `terminal`, `data`, `database`, `datastore`, `security`, `terminal-ghostty`); pure Kotlin/JVM modules (`session`, `ssh`, `protocol`, `model`, `common`).

## Layer rules

1. **Dependency direction**: features depend on core. Core never depends on a feature. A feature may depend on another feature's `api`, never its `impl`. JVM modules never depend on Android modules. The build enforces the feature/core rules (`ModuleBoundaryConventionPlugin`).
2. **Protocol is transport-agnostic.** `core:protocol` sees only `ByteTransport`. SSH, a local process and the in-memory fake core implement it.
3. **One authoritative model per connection.** `CoreSession` owns hello, subscription and terminal records; UI state is derived from it. Each (re)connect starts a new connection epoch and callbacks from older epochs are dropped.
4. **Hosts are independent.** One host reconnecting never blocks another. Cached data from a disconnected host is shown as stale and writes are disabled.
5. **Status is not liveness.** Process state and agent status (possibly unknown) are separate fields.
6. **Mutations are at most once.** Reads may retry; writes are never replayed automatically. After a drop, `ActionOutcome.Unknown` is shown and state is reconciled by re-reading.

## Error handling

Public APIs of `core:session`, `core:data` and repositories return `AppResult<T>` (`Success` or `Failure(AppError)`) and do not throw; `CancellationException` is always rethrown. `AppError` is a small sealed taxonomy in `core:common` (`Unreachable`, `AuthFailed`, `HostKeyUnknown`, `HostKeyChanged`, `CoreNotFound`, `CoreIncompatible`, `Rpc(code)`, `Timeout`, `OutcomeUnknown`, `Protocol`, `Storage`); a new case needs review. `core:ui` maps each error to a string resource and a suggested action. Low-level modules convert their typed exceptions to `AppError` at their public boundary only.

## Navigation

One Navigation 3 back stack, owned by `:app` (`FlocketNavHost`). Features never know each other's `impl`.

- `feature:x:api` exposes `@Serializable` `NavKey` routes (e.g. `HostsRoute`, `HostDetailRoute(hostId)`, `OnboardingRoute`). Other features navigate by pushing those keys.
- `feature:x:impl` contributes its entries with a Hilt multibinding: a `@Module @InstallIn(SingletonComponent)` whose `@Provides @IntoSet` returns an `EntryProviderInstaller` (defined in `:core:ui`). The installer registers `entry<Route> { ... }` and receives a `Navigator` (`navigate`, `back`, `goHome`) instead of the back stack. List/detail roles go in entry metadata (`ListDetailSceneStrategy.listPane()` / `detailPane()`).
- `:app` injects `Set<EntryProviderInstaller>`, installs them into one `entryProvider`, and adds the placeholder entries for Terminals and Settings until those features land (their routes then move to the respective `:api`).
- Top-level destinations (Terminals = start, Hosts, Settings) are shown by `NavigationSuiteScaffold`: bottom bar on compact windows, rail on medium and expanded. Selecting one replaces the stack; the first stack element is the selected tab. The bar is hidden on full-screen routes (`isFullScreen()` in `:app`: onboarding, host edit).
- First run: `AppViewModel` reads `UserPreferences.onboardingCompleted` once; if false the stack starts at `OnboardingRoute`, which calls `goHome()` when done.
- One-off results (saved, deleted, finished) are state flags in the UiState; the route reacts in a `LaunchedEffect` and navigates.

## Build logic

`build-logic/convention` holds the convention plugins (`flocket.jvm.library`, `flocket.android.library`, `flocket.android.compose`, `flocket.android.feature`, `flocket.android.application`, `flocket.android.room`, `flocket.hilt`). Module build files only pick plugins and declare project-specific dependencies. Spotless with ktlint and Compose rules is configured in the root `build.gradle.kts`; style options are in `.editorconfig`.

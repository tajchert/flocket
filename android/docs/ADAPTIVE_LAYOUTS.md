# Adaptive layouts

Compact width shows the terminal list, then the terminal on its own full screen. Medium and expanded widths (tablets, unfolded foldables, desktop windows) show list and detail side by side. Hinge and tabletop postures are respected. Hardware keyboards are first-class on tablets.

## How it works

- `:app` creates `FlocketNavHost` (`app/src/main/kotlin/com/mtajchert/flocket/navigation/`) with a single Navigation 3 back stack and `rememberListDetailSceneStrategy`. Features do not branch on screen size; a destination declares its pane role as `NavEntry` metadata (`ListDetailSceneStrategy.listPane()` / `detailPane()`), and the scene strategy decides what fits.
- This is the Navigation 3 Scenes approach, deliberately not `ListDetailPaneScaffold`, which would need selection state alongside the back stack (two sources of truth).
- `calculatePaneScaffoldDirectiveWithTwoPanesOnMediumWidth(...)` (two panes from 600dp; the plain `calculatePaneScaffoldDirective` would only split expanded windows, leaving an unfolded Fold single-pane) is copied with `horizontalPartitionSpacerSize = 0.dp` to remove the gap between panes (b/418201867); drop the override once fixed upstream.
- When two panes are visible a detail screen must not draw a back arrow. Entries receive a `showDetailBackButton` flag (false on multi-pane layouts).

## Sidebar, focus mode and chrome

- `FlocketNavHost` provides `LocalPaneLayout` (`multiPane`, `sidebarVisible`, `compactHeight`, `navigationRail`, from `:core:ui`) and `LocalSidebarToggle`. `multiPane` is measured from the directive before focus mode narrows it. Compact height is a window height below 480dp (landscape phone).
- The list pane (sidebar) is 300dp on expanded widths and 280dp on other multi-pane windows (`defaultPanePreferredWidth` of the directive); the terminal gets the rest.
- On multi-pane windows, with a terminal open, the Terminals header has a collapse button ("Hide terminal list") just before its ⋮ menu that enters focus mode. In focus mode the terminal top bar's navigation icon ("Show terminal list") brings the list back. Beside the list the terminal bar has no navigation icon; single-pane keeps the back arrow.
- Focus mode (hidden sidebar) swaps the directive for `maxHorizontalPartitions = 1`. It animates: on multi-pane windows the list-detail strategy also handles single-pane layouts (`shouldHandleSinglePaneLayout`), so the scene stays and the scaffold slides the list away while the terminal widens; the rail slides out through `NavigationSuiteScaffoldState` (`hide`/`show`) instead of switching to `NavigationSuiteType.None`; `PaneCard` eases its margins and corners. The terminal sends one resize once the width settles (the controller debounces). It is `rememberSaveable` (survives rotation and fold, not process death), resets when the window becomes single-pane or the top route is not a terminal, and Back leaves it before popping the stack.
- Dense (multi-pane) sidebar: the filter chip row moves into the top bar's ⋮ menu, above Sort, and the ⋮ button carries a dot while the filter is not "All". The Priority list (the default, one list across hosts) does the same. On compact height the sidebar and terminal top bars share the `surfaceContainer` colour so the status bar strip is uniform; single-pane phones keep the chip row and default colours.
- Pane cards (`PaneLayout.paneCards`: multi-pane, sidebar visible, not compact height): `rememberPaneCardEntryDecorator` in `:app` draws each list-detail entry as a `PaneCard` (`:core:ui`) on the window background. The list is a `surfaceContainerLow` card with 24dp leading corners, the detail a card with 24dp trailing corners, 8dp from the window edges; a pane with no neighbour rounds every corner. Inside the list card the surface tones step up one level (`listPaneColors`), so a screen's `background` is the card and its `surfaceContainerLow` cards stay visible. The card pads the system bars itself. Entries opt in with `PaneRole.List/Detail.metadata()` next to the `ListDetailSceneStrategy` metadata; detail placeholders use `DetailPanePlaceholder`.
- The rail carries the design's New agent FAB as its header (`NavigationSuiteScaffold` is unrolled into `NavigationSuiteScaffoldLayout` + `NavigationRail(header)`; the bottom bar is unchanged). It shows Agents (keeping an open terminal) and asks the list for its sheet through `NewTerminalLauncher` (`:feature:terminals:api`); with no hosts it opens Add host. While the rail shows (`navigationRail`), the terminals list hides its own New button and FAB.
- The navigation rail/bar is hidden while a terminal is on top when the window is single-pane, in focus mode, or compact height (`isFullScreen`).
- A wide but short window (a landscape phone) gets the rail, not the default bottom bar, so the list keeps its height (`navigationSuiteType`).
- A pane beside the visible sidebar pads only its top, bottom and end insets: the start cutout and nav bar belong to the sidebar.
- Rotation, fold and unfold keep the terminal's `TerminalViewModel` (attach, engine state); only the surface is re-created.
- The focus decisions (`isFocusMode`, `shouldResetFocus`, `backLeavesFocus`) are pure functions covered by `FocusModeTest`; `:app` has no Compose test setup. Manual check: on a tablet or unfolded emulator open a terminal, hide the sidebar, press Back (sidebar returns, terminal stays), rotate and fold in focus mode.

## Terminal tab strip

Below the terminal top bar, a 40dp strip shows the tabs of the open terminal's space (scrollable, status dot, open tab selected) and, when the tab has 2+ panes, a pane switcher (agent label or name). It appears when the space has 2+ tabs or the tab has 2+ panes. Tapping navigates to the tab's active pane (else first) with a passive `TerminalRoute`. Finished terminals other than the open one are left out of both, as in the sidebar; a tab with none left is hidden. Data comes from `TerminalViewModel.tabStrip` (`buildWorkspace` on the host's `SessionState`, deduplicated, separate from `uiState`). On compact height the strip hides while the IME is visible.

Full-screen terminal: the terminal's ⋮ menu has "Hide top bar", which hides the top bar and the tab strip so the terminal gets their rows (the status bar stays). A small translucent button in the terminal's top-end corner ("Show top bar") brings them back; beside it stay the "next needs you" pill and, while the host is not online, its connection badge (rule 7). The reconnecting and size-taken chips stack under it. The choice is app-wide (`UserPreferences.terminalTopBarHidden`), so every terminal opens the same way until switched back. While hidden, multi-pane focus mode's "Show terminal list" is reached by bringing the bar back first.

## Testing

Screen-level composables are covered by screenshot tests at phone (360x816), foldable (673x841) and tablet (1280x800) sizes; use `@PreviewFormFactors` from `:core:designsystem`. See [Screenshot testing](SCREENSHOT_TESTING.md).

The whole multi-pane window (rail, list card, terminal card) is rendered from the real screens by `AppLayoutScreenshotTests` in `:app` (`app/src/screenshotTest/`), at tablet and unfolded-foldable sizes. It places the panes by hand because `FlocketNavHost` needs Hilt; keep its sidebar widths in step with `paneScaffoldDirective`. The README's tablet and foldable screenshots are downscaled from its references.

## Not yet adopted

| API | Reason |
|---|---|
| `Grid`, `FlexBox` | Experimental in Compose 1.11; outside the stable-dependency policy. |
| `NavigationSuiteScaffold` | Adopt when a second top-level destination appears, instead of adding a bottom bar first. |

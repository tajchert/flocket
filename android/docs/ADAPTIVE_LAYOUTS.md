# Adaptive layouts

Compact width shows the terminal list, then the terminal on its own full screen. Medium and expanded widths (tablets, unfolded foldables, desktop windows) show list and detail side by side. Hinge and tabletop postures are respected. Hardware keyboards are first-class on tablets.

## How it works

- `:app` creates `FlocketNavHost` (`app/src/main/kotlin/com/mtajchert/flocket/navigation/`) with a single Navigation 3 back stack and `rememberListDetailSceneStrategy`. Features do not branch on screen size; a destination declares its pane role as `NavEntry` metadata (`ListDetailSceneStrategy.listPane()` / `detailPane()`), and the scene strategy decides what fits.
- This is the Navigation 3 Scenes approach, deliberately not `ListDetailPaneScaffold`, which would need selection state alongside the back stack (two sources of truth).
- `calculatePaneScaffoldDirective(...)` is copied with `horizontalPartitionSpacerSize = 0.dp` to remove the gap between panes (b/418201867); drop the override once fixed upstream.
- When two panes are visible a detail screen must not draw a back arrow. Entries receive a `showDetailBackButton` flag (false on multi-pane layouts).

## Sidebar, focus mode and chrome

- `FlocketNavHost` provides `LocalPaneLayout` (`multiPane`, `sidebarVisible`, `compactHeight`, from `:core:ui`) and `LocalSidebarToggle`. `multiPane` is measured from the directive before focus mode narrows it. Compact height is a window height below 480dp (landscape phone).
- The list pane (sidebar) is 300dp on expanded widths and 280dp on other multi-pane windows (`defaultPanePreferredWidth` of the directive); the terminal gets the rest.
- On multi-pane windows the terminal top bar's navigation icon toggles the sidebar ("Hide sidebar" / "Show sidebar"); single-pane keeps the back arrow.
- Focus mode (hidden sidebar) swaps the directive for `maxHorizontalPartitions = 1`. It is `rememberSaveable` (survives rotation and fold, not process death), resets when the window becomes single-pane or the top route is not a terminal, and Back leaves it before popping the stack.
- Dense (multi-pane) sidebar: the filter chip row is replaced by a filter icon in the top bar (menu with the current filter checked, a dot while it is not "All"). The sidebar and terminal top bars share the `surfaceContainer` colour so the status bar strip is uniform; single-pane phones keep the chip row and default colours.
- The navigation rail/bar is hidden while a terminal is on top when the window is single-pane, in focus mode, or compact height (`isFullScreen`).
- A wide but short window (a landscape phone) gets the rail, not the default bottom bar, so the list keeps its height (`navigationSuiteType`).
- A pane beside the visible sidebar pads only its top, bottom and end insets: the start cutout and nav bar belong to the sidebar.
- Rotation, fold and unfold keep the terminal's `TerminalViewModel` (attach, engine state); only the surface is re-created.
- The focus decisions (`isFocusMode`, `shouldResetFocus`, `backLeavesFocus`) are pure functions covered by `FocusModeTest`; `:app` has no Compose test setup. Manual check: on a tablet or unfolded emulator open a terminal, hide the sidebar, press Back (sidebar returns, terminal stays), rotate and fold in focus mode.

## Terminal tab strip

Below the terminal top bar, a 40dp strip shows the tabs of the open terminal's space (scrollable, status dot, open tab selected) and, when the tab has 2+ panes, a pane switcher (agent label or name). It appears when the space has 2+ tabs or the tab has 2+ panes. Tapping navigates to the tab's active pane (else first) with a passive `TerminalRoute`. Finished terminals other than the open one are left out of both, as in the sidebar; a tab with none left is hidden. Data comes from `TerminalViewModel.tabStrip` (`buildWorkspace` on the host's `SessionState`, deduplicated, separate from `uiState`). On compact height the strip hides while the IME is visible.

## Testing

Screen-level composables are covered by screenshot tests at phone (360x816), foldable (673x841) and tablet (1280x800) sizes; use `@PreviewFormFactors` from `:core:designsystem`. See [Screenshot testing](SCREENSHOT_TESTING.md).

## Not yet adopted

| API | Reason |
|---|---|
| `Grid`, `FlexBox` | Experimental in Compose 1.11; outside the stable-dependency policy. |
| `NavigationSuiteScaffold` | Adopt when a second top-level destination appears, instead of adding a bottom bar first. |

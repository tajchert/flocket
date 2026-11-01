# Adaptive layouts

Compact width shows the terminal list, then the terminal on its own full screen. Medium and expanded widths (tablets, unfolded foldables, desktop windows) show list and detail side by side. Hinge and tabletop postures are respected. Hardware keyboards are first-class on tablets.

## How it works

- `:app` creates `FlocketNavHost` (`app/src/main/kotlin/com/mtajchert/flocket/navigation/`) with a single Navigation 3 back stack and `rememberListDetailSceneStrategy`. Features do not branch on screen size; a destination declares its pane role as `NavEntry` metadata (`ListDetailSceneStrategy.listPane()` / `detailPane()`), and the scene strategy decides what fits.
- This is the Navigation 3 Scenes approach, deliberately not `ListDetailPaneScaffold`, which would need selection state alongside the back stack (two sources of truth).
- `calculatePaneScaffoldDirective(...)` is copied with `horizontalPartitionSpacerSize = 0.dp` to remove the gap between panes (b/418201867); drop the override once fixed upstream.
- When two panes are visible a detail screen must not draw a back arrow. Entries receive a `showDetailBackButton` flag (false on multi-pane layouts).

## Testing

Screen-level composables are covered by screenshot tests at phone (360x816), foldable (673x841) and tablet (1280x800) sizes; use `@PreviewFormFactors` from `:core:designsystem`. See [Screenshot testing](SCREENSHOT_TESTING.md).

## Not yet adopted

| API | Reason |
|---|---|
| `Grid`, `FlexBox` | Experimental in Compose 1.11; outside the stable-dependency policy. |
| `NavigationSuiteScaffold` | Adopt when a second top-level destination appears, instead of adding a bottom bar first. |

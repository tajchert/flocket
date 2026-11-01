# Terminal rendering and input (`core:terminal`)

How a terminal gets from `TerminalStream.output` bytes to pixels, and how keys get back. Engine internals
(libghostty-vt, JNI) are in [TERMINAL_ENGINE.md](TERMINAL_ENGINE.md); the wire protocol is in
[`../../protocol/SPEC.md`](../../protocol/SPEC.md) §7.

## Pieces

| Type | Package | Role |
|---|---|---|
| `TerminalController` | `controller` | Owns one `TerminalEngine` on a dedicated thread, binds a `TerminalStream`, coalesces frames, size ownership, focus, theme, input queue, events. Plain Kotlin, lives in a ViewModel or app-scoped holder. |
| `FrameClock` / `ChoreographerFrameClock` | `controller` | Vsync ticks; at most one published frame per tick. |
| `TerminalFrameSource` / `StaticFrameSource` | `controller` | What the renderer pulls rows from (controller, or a fixed snapshot for previews). |
| `TerminalView` | `ui` | Hardware-accelerated `View`: per-row `RenderNode`s, cursor/selection/IME overlay, `InputConnection`, hardware keys, gestures. |
| `TerminalRenderer`, `BoxDrawing` | `ui` | Draws snapshot rows: merged background rects, text runs, per-cell fallback, geometric box/block glyphs. |
| `TerminalSurface`, `TerminalExtraKeys`, `ExtraKeysRow`, `TerminalSnapshot` | `ui` | Compose entry points (`AndroidView` host, extra-keys row, static preview). |
| `KeyEventMapper`, `TerminalInputConnection` | `input` | Android key events and IME calls to `KeyInput`/text. |
| `FakeTerminalEngine` | `fake` | Tiny pure-Kotlin engine for tests, previews and screenshots. Not an emulator. |

Fonts: JetBrains Mono 2.304 Regular/Bold/Italic/BoldItalic (`res/font`, OFL-1.1, see `THIRD_PARTY_NOTICES.md`). Missing
glyphs fall back to the system fonts through Minikin.

## Threading and the frame pipeline

```
stream.output ──collect──▶ [engine thread] engine.feed(chunk) ─▶ frame request (conflated)
                                   │ (credit for the chunk returns when feed returns)
vsync (Choreographer) ──▶ [engine thread] drainEvents + engine.snapshot(work)
                                   └─ copy dirty rows work ─▶ shared   (lock, ~µs)
                                      frameVersion = (v + 1) & 0x7F
[main] frameVersion collect ─▶ view.invalidate()
[main] onDraw: acquireFrame: copy dirty rows shared ─▶ render (lock, ~µs)
               re-record RenderNode of each dirty row, replay all rows, draw cursor/selection/IME overlay
```

- One engine per controller, confined to a private daemon thread (`flocket-terminal`), also for `close()`.
- Output is fed as it arrives; there is no queue in the controller, so backpressure (credit) is exactly the engine's
  parse speed. The collector yields after every chunk so frames and keystrokes interleave with a backlog.
- Coalescing: any number of chunks between two vsyncs produce one snapshot. Nothing is published while the surface is
  not visible (`setVisible(false)`), bytes are still consumed. Test: `publishes at most one frame per vsync tick and
  drops no bytes`.
- Triple buffer: `work` (engine side, always the same target so Ghostty's dirty tracking stays incremental), `shared`
  (lock-guarded, accumulates dirty rows until the UI pulls), `render` (UI side). Steady state allocates nothing per
  frame except the frame-clock continuation; `frameVersion` cycles 0..127 so its boxed `Long` comes from the JVM cache.
  Grapheme maps (emoji, combining marks) copy only entries in dirty rows.
- Why a `View` rather than Compose `Canvas`: per-row display lists (`RenderNode`, API 29+) mean a frame re-records only
  dirty rows and replays the rest; `onCreateInputConnection` gives a direct IME bridge; nothing on the frame path
  recomposes. `AndroidView` adds no per-frame cost. Software canvases (layoutlib screenshots, Robolectric) draw rows
  directly.

## Drawing

- Monospace grid: cell width = advance of `M` (fractional), cell height = ceil(ascent + descent), baseline centered.
- Backgrounds: one rect per span of equal color, default background painted once.
- Text: one `drawText` per run of cells with equal color and style when every glyph's advance equals the cell width
  (cached per BMP code point). Wide CJK, emoji, graphemes and fallback-font glyphs are drawn per cell, centered across
  1 or 2 cells and squeezed horizontally if wider. Wide spacers are skipped.
- Box drawing (U+2500-254F, U+256D-257B) and block elements (U+2580-259F) are drawn as rects so borders join across
  rows (font glyphs leave gaps when line height exceeds glyph height). Double lines still use the font.
- Underline/strikethrough: merged rects. Faint and inverse are already resolved by the engine. Invisible cells are not drawn.
- Cursor: block (inverted glyph), bar, underline; hollow block when the view is not focused. No blink (battery).
- Selection: translucent overlay; IME composing text is drawn at the cursor with an underline.

## Size ownership (SPEC §7.5)

| State | Engine size | Sends |
|---|---|---|
| Passive (default bind) | mirrors `stream.size` | nothing (no RESIZE, FOCUS 1, FACTS). Rendered scaled to fit width; pinch zooms 1-4x with horizontal pan. |
| Owner (`AttachMode.Owner`, first key/paste, or `fitToScreen()`) | viewport cols x rows | RESIZE on change (debounced 120 ms), FOCUS 1/0 with visibility and focus, FACTS on theme change. |
| Another viewer took the size (a `size` event that is not what we requested) | mirrors `stream.size` | back to passive until the next keystroke or `fitToScreen()`. |
| Link dropped (`connected = false`) | retained | back to passive: the session reattaches with `resumed:true` and sends a fresh first paint. A "Reconnecting…" chip is shown over the retained screen. |
| Observe | mirrors `stream.size` | nothing; input refused (`isReadOnly`). |

FOCUS 0 is sent only after a FOCUS 1. Pinch in Owner mode changes the font size (callback to persist it) and the grid is
re-measured once, at gesture end; during the pinch only the canvas scale changes.

## Lifecycle matrix

| Event | What happens | Covered by |
|---|---|---|
| Rotation, fold/unfold, theme/locale change | Activity and composable are recreated; the controller (ViewModel) survives, no reattach, no new engine. The new view pulls a full frame. Owner: one debounced RESIZE after the size settles. Passive: re-scale only. | `TerminalSurfaceDeviceTest.recreatingTheActivity…`, `owner resizes once after a fold animation settles` |
| Multi-window / freeform resize, IME show/hide | `onSizeChanged` per animation frame; the view keeps the cursor row visible immediately; Owner resizes 120 ms after the last change. | `owner resizes once…` |
| Screen off, app backgrounded | `LifecycleStartEffect` → `setVisible(false)`: FOCUS 0 (if we sent FOCUS 1), frames paused, bytes still consumed. The stream is kept: its owner decides the grace period (`CoreSession.stop()` parks streams). | `focus follows visibility…`, `frames are not published while invisible` |
| Foreground / unlock | `setVisible(true)`: FOCUS 1 if owner and focused; the retained engine renders instantly; a fresh paint follows if the session reattached. | same |
| Reconnect | `connected` false → overlay, passive; the same `TerminalStream` continues with a fresh first paint (fed like any output). | `a reconnect drops ownership…` |
| Process death | Nothing survives: rebuild the controller from the `TerminalAddress` (saved state), attach passively, `bind()`. The core's first paint restores the screen; scrollback comes from `historyLines`. | by construction |
| Screen really closed | Owner calls `controller.close()` (releases the engine) and `stream.detach()`. | `close releases the engine…` |

## Input

- IME: `TerminalInputConnection` with `TYPE_CLASS_TEXT | VISIBLE_PASSWORD | NO_SUGGESTIONS`, no extract UI, no
  personalized learning. `commitText` is sent as typed (`\n` → Enter). Composing text (CJK, swipe, Gboard words) stays
  local and is drawn as an overlay until committed or finished. `deleteSurroundingText` with nothing composed becomes
  Backspace/Delete keys. Key events from the IME go through `KeyEventMapper`.
- Hardware keyboard: `KeyEventMapper` maps Ctrl/Alt/Shift/Meta, arrows, Home/End/PgUp/PgDn, Insert/Delete, F1-F12,
  Esc, Tab, Enter, Backspace; AltGr characters stay text; Back/volume/bare modifiers are not consumed. Space is sent as
  the character `" "` (the Ghostty encoder only attaches text to `Key.Character`). Ctrl+Shift+C/V copy/paste.
- Extra-keys row: Esc, Tab, sticky Ctrl/Alt (apply once to the next key from any source, including the IME), arrows,
  Home/End/PgUp/PgDn, `| ~ / -`.
- Writes go through a bounded queue (256) to one writer coroutine: in order, never retried; a full queue or a failed
  `write` is emitted on `failures`. Input snaps the viewport to the bottom.
- Gestures: vertical drag/fling scrolls scrollback in whole rows (the sub-row remainder is carried, not drawn), tap
  focuses and shows the keyboard, long-press selects a word and dragging extends it, a floating Copy/Paste action mode
  appears, two-finger tap pastes.

## Profiling

```bash
# Dev loop (no SSH): echo / colors / htop / flood / scrollback modes, Passive/Owner, font, theme.
./gradlew :samples:terminal-playground:installDebug
adb shell am start -n com.mtajchert.flocket.samples.terminal.debug/com.mtajchert.flocket.samples.terminal.PlaygroundActivity --es mode flood

# Macrobenchmarks (FrameTimingMetric) against the release-like playground.
./gradlew :samples:terminal-playground:installBenchmarkRelease :benchmark:assembleBenchmarkRelease
adb install -r -t benchmark/build/outputs/apk/benchmarkRelease/benchmark-benchmarkRelease.apk
adb shell am instrument -w -e class com.mtajchert.flocket.benchmark.TerminalFloodBenchmark \
  -e androidx.benchmark.suppressErrors EMULATOR com.mtajchert.flocket.benchmark/androidx.test.runner.AndroidJUnitRunner
```

(`connectedBenchmarkReleaseAndroidTest` also works once `:app` builds in `benchmarkRelease`.) Traces land in
`/storage/emulated/0/Android/media/com.mtajchert.flocket.benchmark/`; open them in ui.perfetto.dev and look at
`RenderThread` `DrawFrame` and the `flocket-terminal` thread. `adb shell dumpsys gfxinfo <pkg>` gives GPU percentiles.

## Benchmark numbers

Emulator only (arm64 `pixel` AVD, API 36, 4 vCPU, host GPU on Apple Silicon, 1080x2400). Indicative: the emulated GPU
costs 14-17 ms per frame in every mode (`gfxinfo` GPU P50), so frame durations are bounded by the emulator, not by the
renderer. Physical-device numbers still need a device.

| Benchmark (3 iterations, `CompilationMode.Full`) | frameDurationCpuMs P50 / P90 / P99 | frameOverrunMs P50 / P90 / P99 |
|---|---|---|
| `TerminalFloodBenchmark.flood` (5 s of ~60 KB mixed-SGR chunks as fast as parsed, 52x41) | 1.8 / 17.4 / 19.3 | -13.9 / 1.1 / 3.1 |
| `TerminalFloodBenchmark.fullScreenRedraw` (htop-like, 60 Hz full redraw) | 2.6 / 5.0 / (P95 5.9) | not recorded |
| `TerminalScrollBenchmark.flingScrollback` (3000 lines, 3 flings up and down) | 1.6 / 6.2 / 19.6 | -13.3 / -6.1 / 4.5 |

The flood P90 is not within the 8 ms target on the emulator. The P50 is CPU work (UI thread recording dirty rows plus
RenderThread); the slow tail matches the emulated GPU's per-frame cost (RenderThread waits for buffers), and the htop
redraw, which records just as many rows but mostly rects, has a P90 of 5 ms. Re-measure on a physical device before
optimizing further. Next steps if a device shows the same tail: scroll-blit (shift row display lists instead of
re-recording on pure scrolls), a 30 Hz cap while the engine has a backlog, and a glyph-run cache per row.

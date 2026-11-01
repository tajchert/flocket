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
| `SmoothScroll`, `TerminalSelection` | `ui` | Pure models (JVM-tested): fractional scroll position and selection, both in row ids. |
| `TerminalRenderer`, `BoxDrawing` | `ui` | Draws snapshot rows: merged background rects, text runs, per-cell fallback, geometric box/block glyphs. |
| `TerminalSurface`, `TerminalExtraKeys`, `ExtraKeysRow`, `TerminalSnapshot` | `ui` | Compose entry points (`AndroidView` host, extra-keys row, static preview). |
| `KeyEventMapper`, `TerminalInputConnection` | `input` | Android key events and IME calls to `KeyInput`/text. |
| `FakeTerminalEngine` | `fake` | Tiny pure-Kotlin engine for tests, previews and screenshots. Not an emulator. |

Fonts: JetBrains Mono 2.304 Regular/Bold/Italic/BoldItalic (`res/font`, OFL-1.1, see `THIRD_PARTY_NOTICES.md`). Missing
glyphs fall back to the system fonts through Minikin.

## Threading and the frame pipeline

```
stream.output ──collect──▶ [engine thread] engine.feed(chunk) ─▶ frame request (conflated)
                                   │ (the chunk counts as consumed when feed returns; CoreConnection
                                   │  batches its credit: 32 KiB or end of burst)
vsync (Choreographer) ──▶ [engine thread] drainEvents + engine.snapshot(work)
                                   └─ copy dirty rows work ─▶ shared   (lock, ~µs)
                                      frameVersion = (v + 1) & 0x7F
[main] frameVersion collect ─▶ view.invalidate()
[main] onDraw: acquireFrame: copy dirty rows shared ─▶ render (lock, ~µs)
               re-record RenderNode of each dirty row, replay all rows, draw cursor/selection/IME overlay
```

- One engine per controller, confined to a private daemon thread (`flocket-terminal`), also for `close()`.
- Output is fed as it arrives; there is no queue in the controller, so backpressure (credit) is exactly the engine's
  parse speed. Feeding is never gated on vsync: only publishing is. The collector yields after every chunk so frames and keystrokes interleave with a backlog.
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
- Selection: translucent overlay plus teardrop handles in Material `primary` (`TerminalView.selectionColor`, set by the
  Compose wrappers; falls back to the theme's cursor color). IME composing text is drawn at the cursor with an underline.

## Size ownership (SPEC §7.5)

| State | Engine size | Sends |
|---|---|---|
| Passive (default `bind`; the app always binds as Owner, [ADR-0007](../../docs/decisions/0007-fit-terminal-on-open.md)) | mirrors `stream.size` | nothing (no RESIZE, FOCUS 1, FACTS). Rendered scaled to fit width; pinch zooms 1-4x with horizontal pan. |
| Owner (`AttachMode.Owner`, first key/paste, or `fitToScreen()`) | viewport cols x rows | RESIZE on change (debounced 120 ms), FOCUS 1/0 with visibility and focus, FACTS on theme change. |
| Another viewer took the size (a `size` event that is not what we requested, after the core confirmed our last RESIZE; sizes seen before that confirmation are stale and ignored) | mirrors `stream.size` | back to passive until the next keystroke or `fitToScreen()`. |
| Link dropped (`connected = false`) | retained | back to passive: the session reattaches with `resumed:true` and sends a fresh first paint. A "Reconnecting…" chip is shown over the retained screen. Bound as Owner: takes the size again once connected and visible. |
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
| Window focus lost (dialog, notification shade, lock screen) | `TerminalView.onWindowFocusChanged` → `setFocused(false)`: FOCUS 0 only if owner. | by construction |
| Screen really closed | Owner calls `controller.close()` (releases the engine) and `stream.detach()`. | `close releases the engine…`, `clearing the ViewModel detaches the stream` |

## The terminal screen (`feature:terminal:impl`)

`TerminalViewModel` (assisted with the `TerminalRoute`; the saved back stack brings it back after process death) owns
one `TerminalController` (Ghostty engine via `TerminalControllerFactory`) and the stream:

- Waits for `ConnectionManager.session(hostId)` (null until the hosts load) and for Online, then attaches with
  `historyLines = 1000`. Every attach is `AttachMode.Owner` at `controller.preferredSize()` (the viewport grid), so
  opening a terminal fits it to the screen ([ADR-0007](../../docs/decisions/0007-fit-terminal-on-open.md)).
- `StreamEnd`: `ProgramEnded` → "Exited (code N)" banner (Remove/Close); `ProgramLost` or attach `unavailable` →
  "Waiting for a new shell…", re-attach when the record is Running again, then a "fresh shell" notice; `Unknown` or
  `not_found` → "Stream ended" with Reattach. A record that is already Finished is not attached.
- `onCleared()` (real close or another terminal replacing the two-pane detail, never rotation): `detach()` then
  `controller.close()` in the application scope. Covered by `TerminalRotationTest` (device, `ActivityScenario.recreate`)
  and `TerminalViewModelTest`.
- Screen: Fit to screen (only while passive; it resizes for every viewer), keyboard toggle, Paste, Copy screen text,
  Interrupt, Kill (confirm); OSC 52 consent dialog; keep-screen-on, font size (pinch persisted) and extra keys from
  `UserPreferences`; the pane takes focus when shown so hardware keyboards type immediately.

## Input

- IME: `TerminalInputConnection` with plain `TYPE_CLASS_TEXT`: a normal keyboard (suggestions, swipe, voice, learning)
  with no autocorrect or auto-capitals, because no `AUTO_CORRECT`, `MULTI_LINE` or `CAP_*` flag is set. No extract UI.
  `commitText` is sent as typed (`\n` → Enter). Composing text (CJK, swipe, Gboard words) stays
  local and is drawn as an overlay until committed or finished. `deleteSurroundingText` with nothing composed becomes
  Backspace/Delete keys. Key events from the IME go through `KeyEventMapper`.
- Hardware keyboard: `KeyEventMapper` maps Ctrl/Alt/Shift/Meta, arrows, Home/End/PgUp/PgDn, Insert/Delete, F1-F12,
  Esc, Tab, Enter, Backspace; AltGr characters stay text; Back/volume/bare modifiers are not consumed. Space is sent as
  the character `" "` (the Ghostty encoder only attaches text to `Key.Character`). Ctrl+Shift+C/V copy/paste.
- Extra-keys row: Esc, Tab, sticky Ctrl/Alt (apply once to the next key from any source, including the IME), arrows,
  Home/End/PgUp/PgDn, `| ~ / -`.
- Writes go through a bounded queue (256) to one writer coroutine: in order, never retried; a full queue or a failed
  `write` is emitted on `failures`. Input snaps the viewport to the bottom.
- Gestures: vertical drag/fling scrolls scrollback pixel-smoothly, or the program itself on the alternate screen or under mouse tracking (below), tap focuses and shows the keyboard (or
  clears a selection), long-press selects a word (below; on blank cells past the row's text it selects nothing), two-finger tap pastes.

## Scrolling

- Row ids: `ScreenSnapshot.historyBase` counts rows that left the screen buffer (eviction at the scrollback cap, clear
  scrollback, reset), so `historyBase + screen row` names the same content while output arrives. Ghostty tracks it with
  a tracked grid ref; the fake engine counts evictions.
- `SmoothScroll` keeps a fractional top row id. The engine viewport only sits on whole rows: the view requests
  `ceil(top)` with the absolute `TerminalController.scrollToRow` (resolved on the engine thread against the current
  history base with `TerminalEngine.scrollViewportTo`, so neither moves in flight nor output fed since the last frame
  shift it; reaching the bottom uses `scrollToBottom`, which follows output) and draws the
  frame shifted down by the fraction, rounded to whole device pixels, with the row above the viewport filling the gap.
  The controller reads that row (`TerminalEngine.readRow`, read-only) only while the view asks for it (touching or
  scrolled up), and only when it is a different row; at the live bottom nothing extra happens per frame. The row is
  published with its frame under one lock and the view takes both in one `acquire` call, so they always match.
- The engine lags a frame or two behind requests; the shift never exceeds the one row we have, so a fast fling moves
  in whole rows and turns smooth as it slows. Fling: `OverScroller`, stopped at the oldest row and at the bottom.
- Live (at the bottom) follows new output. Scrolled up stays on its content (ids are stable). Input snaps the engine
  to the bottom and the view goes live; less than a row above the bottom with no new output keeps its fraction.
- Programs that take the wheel (`ScreenSnapshot.scrollInput`, like a desktop terminal): while the program tracks the
  mouse (?9/?1000/?1002/?1003, e.g. Codex), drag and fling send one wheel event per row of travel at the touched cell
  (`TerminalController.scrollProgram` → `TerminalEngine.encodeScroll`, libghostty's mouse encoder, one write per
  call); on the alternate screen without mouse tracking they send Up/Down keys (alternate scroll ?1007, on by default;
  `less`, `man`). With ?1007 off the alternate screen ignores drags. Read-only viewers send nothing. Drag down = older
  = wheel up / Up.

## Selection

- `TerminalSelection` holds two cells in row ids, so it survives output, scrolling and height changes (IME). It is
  cleared when its start row is evicted, when the width changes (reflow), when the active screen switches
  (`ScreenSnapshot.altScreen`: shell <-> vim) and on any key.
- Long-press selects a word (letters, digits, `-_./~:@%+=#`, any non-ASCII; a wide character whole) and dragging
  extends the end. Two teardrop handles (start hangs below-left, end below-right) drag with the finger offset kept;
  they may cross (ends swap) and auto-scroll near the top/bottom edge, into scrollback.
- Toolbar: the platform floating `ActionMode` (TalkBack-friendly): Copy (then clears), Paste, Select all (whole
  scrollback). Tap elsewhere clears. Ctrl+Shift+C/V copy/paste.
- Text: `TerminalController.selectionText` calls `TerminalEngine.textRange` on the engine thread, so rows scrolled off
  screen are included; wide characters and grapheme clusters appear once, soft-wrapped rows join without a newline,
  line ends are trimmed. Static frame sources (previews) use the same rules on visible rows (`RowTextBuilder`).
- Copy never writes an empty clip: if the start was evicted, the screen switched or the text is blank, a toast says
  nothing was copied; engine failures are caught (cancellation is rethrown).
- Accessibility: `ExploreByTouchHelper` (androidx.customview) exposes the handles as virtual views ("Selection start
  handle", "Selection end handle") with hover and accessibility focus, and custom actions to move a handle to the
  previous/next word or line.
- "Copy screen text" in the terminal screen still copies the visible screen.

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

**Emulator numbers drift between days, so compare A/B, never against this table alone.** On 2026-11-01 (M1.1) the
same AVD gave P50 ≈ 7 ms for the *unchanged* pre-M1.1 build. Interleaved runs of the builds before and after smooth
scrolling and selection overlapped within noise (flood P90 17.2-21.3 ms before vs 7.9-17.6 after; fling P90 17.2-17.5
vs 8.0-17.0). To A/B: build both commits' `:samples:terminal-playground` and `:benchmark` `benchmarkRelease` APKs (a
second worktree for the old commit) and alternate runs on one emulator.

The flood P90 is not within the 8 ms target on the emulator. The P50 is CPU work (UI thread recording dirty rows plus
RenderThread); the slow tail matches the emulated GPU's per-frame cost (RenderThread waits for buffers), and the htop
redraw, which records just as many rows but mostly rects, has a P90 of 5 ms. Re-measure on a physical device before
optimizing further. Next steps if a device shows the same tail: scroll-blit (shift row display lists instead of
re-recording on pure scrolls), a 30 Hz cap while the engine has a backlog, and a glyph-run cache per row.

## Performance: output throughput

How fast a flood reaches the engine end to end, measured layer by layer with the same program: a terminal
that waits for a go signal, then floods `base64` of random bytes or the coloured `yes` of the
[E2E smoke journey](journeys/E2E_SMOKE.md), and prints its own duration. "MB/s" is bytes delivered to the
consumer divided by the time from go to the end marker. Before the fix the 5 MB JVM runs hit the
core's repaint (see below), so only their CREDIT pattern is comparable. Floods over 4 MiB make the core drop a slow
viewer's backlog and repaint (SPEC §7.3), which hides the drain rate, so the device runs use 3 MB.

| Layer (where) | Before | After |
|---|---|---|
| No viewer (program alone, macOS) | 0.04-0.06 s for 5 MB | same |
| `CoreConnection` over `hc connect` (JVM, macOS) | fast; 1 CREDIT per DATA (avg 1.9-2.4 KB) | 3 MB colour: 32.7 MB/s, avg 8 KB/CREDIT; 5 MB base64: 65 MB/s |
| + SSHJ over embedded MINA sshd (JVM) | fast; avg 2.4-4.9 KB/CREDIT | 3 MB colour: 27.4 MB/s; 5 MB base64: 52-64 MB/s |
| + SSHJ over OpenSSH (`dev-sshd.sh`, JVM) | fast; avg 0.6-2.9 KB/CREDIT | 3 MB colour: 29.6 MB/s, avg 11 KB/CREDIT |
| Device: `CoreConnection` + SSHJ, emulator → 10.0.2.2, 3 MB colour | 5.2 MB/s, 820 CREDITs | 12.6-13.6 MB/s, 216-272 CREDITs |
| Device: + CoreSession + TerminalController + Ghostty 52x41, shell | 2.9 MB/s, 2705 CREDITs | 11.8-12.1 MB/s, 103-120 CREDITs |
| Device: same, shown in a `TerminalSurface` (View + vsync) | 2.85 MB/s, 1769 CREDITs | 8.4-9.0 MB/s (base64: 10.7) |

Emulator: arm64 `pixel` AVD, API 36, hc 0.1.15, OpenSSH harness on the Mac. Frames published during a
3 MB flood: 14-20 (coalesced), so throughput is not tied to vsync.

What was slow: every DATA frame (often only 0.5-2 KB, the core's PTY reads) cost one CREDIT frame,
and the collector waited for that write (an SSH packet via SSHJ) before taking the next chunk. With a
256 KiB window that capped a remote viewer at a few MB/s and made it very sensitive to latency. Fixes:

- `CoreConnection` batches credit: one CREDIT per 32 KiB consumed, or when the stream's queue runs dry
  (end of a burst), so idle unreturned credit never sits at the window. Credit is still only for bytes
  the collector finished with.
- Attach streams get a one-off 768 KiB CREDIT after the reply (window 1 MiB, like the events boost), so
  one round trip can carry 1 MiB. Client-side memory stays bounded by the window (+64 KiB slack).
- SSHJ sockets use TCP_NODELAY: credit and keystrokes are small writes someone waits for; with Nagle
  they can wait for a delayed ACK.

The ~70 s for a 5 MB flood seen in the first E2E run was not reproduced on the current code (the same
journey on the emulator, before these changes, finished in under 2 s); the layer numbers above
are the measurable cost that remained.

Reproduce (each test skips itself without `hc`, runs in an isolated `flocket-test-tp*` session, and appends
to `build/throughput/results.txt` of its module; `FLOCKET_FLOOD_KIND=color`, `FLOCKET_FLOOD_BYTES=3000000`):

```bash
./gradlew :core:protocol:test --tests '*ProtocolThroughputIntegrationTest' --rerun
FLOCKET_TP_SSH_PORT=2222 FLOCKET_TP_SSH_KEY=<key> ./gradlew :core:ssh:test --tests '*SshThroughputIntegrationTest' --rerun
# Device: dev-sshd.sh running with the key authorized; tpKey = base64 of the private key file.
./gradlew :core:terminal:assembleDebugAndroidTest   # then adb install -t the androidTest APK and:
adb shell am instrument -w -e class com.mtajchert.flocket.core.terminal.DeviceThroughputTest \
  -e tpPort 2222 -e tpUser "$(id -un)" -e tpKey "<base64>" -e tpHc "$(command -v hc)" \
  -e tpSession flocket-test-tpdev -e tpKind color -e tpBytes 3000000 -e tpCols 52 -e tpRows 41 -e tpShell 1 -e tpUi 1 \
  com.mtajchert.flocket.core.terminal.test/androidx.test.runner.AndroidJUnitRunner
adb logcat -d -s THROUGHPUT:I     # results; afterwards: HC_SESSION=flocket-test-tpdev TMPDIR=/tmp hc server stop
```

## Back with a selection

While a selection exists, `TerminalView` registers an `OnBackInvokedCallback` (PRIORITY_OVERLAY, API 33+; older versions use `onKeyDown`) so Back clears the selection, handles and toolbar first and only the next Back navigates. The floating toolbar does not consume Back, and Navigation 3's callback would otherwise win. The callback is unregistered with a `post` so the same Back does not also reach navigation. With the IME open the first Back hides the IME (platform), so it takes up to three presses from selection to the list.

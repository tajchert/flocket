# Terminal engine (libghostty-vt)

`:core:terminal-ghostty` implements `TerminalEngine` ([ADR-0003](../../docs/decisions/0003-terminal-engine-libghostty.md)) on top of libghostty-vt, the VT core of Ghostty, through a small JNI shim. Everything above the interface (renderer, input, session glue) only sees `TerminalEngine`.

## How it works

```
Kotlin GhosttyTerminalEngine  ->  GhosttyNative (JNI, RegisterNatives)  ->  libflocket_ghostty.so (src/main/cpp)  ->  libghostty-vt.so (prebuilt)
```

- `feed` writes bytes straight into `ghostty_terminal_vt_write` (chunking is arbitrary).
- `snapshot` calls `ghostty_render_state_update`, walks the dirty rows (all rows when the target is new/resized, the theme changed, or ghostty reports a full redraw) and writes resolved ARGB colors, first code point and flags into primitive `IntArray`s owned by `ScreenSnapshot`. Cursor and scrollback numbers come back in a 16-int header. Cells with more than one code point (emoji sequences, combining marks) travel in a side channel `[cellIndex, n, cp...]` that Kotlin turns into `ScreenSnapshot.graphemes`. Inverse is applied; faint blends the foreground 2:1 toward the background. Bold is not mapped to bright colors.
- `encodeKey` uses `ghostty_key_encoder_setopt_from_terminal`, so DECCKM, keypad, kitty keyboard flags and modifyOtherKeys follow what the program enabled. `encodePaste` wraps in `ESC[200~ ... ESC[201~` only when mode 2004 is on, otherwise converts `\n` to `\r` and neutralises control bytes.
- Events (title, bell, OSC 52 clipboard write, OSC 7 pwd, OSC 9/777 notification) are queued natively and drained as one byte array.
- Terminal replies (`OPT_WRITE_PTY`) are never installed, so DA/DSR/color-query answers are dropped: the host core answers them (SPEC section 7.3). Mode 9001 is unknown to libghostty and ignored, so it cannot alter key encoding. OSC 7501 is consumed by libghostty (no callback installed).
- Colors: `setTheme` sets default fg/bg/cursor and ANSI 0-15; entries 16-255 keep libghostty's xterm defaults.

## Threading and lifetime

Not thread-safe. Confine every call, including `close()`, to one thread/dispatcher (for example `Dispatchers.Default.limitedParallelism(1)`). `close()` is idempotent and frees native memory; any other call afterwards throws `IllegalStateException`. Native libraries load lazily at first construction; if they cannot load (unsupported ABI) construction throws `UnsatisfiedLinkError` with an explanatory message.

## JNI surface (`GhosttyNative`)

`nativeCreate(cols, rows, scrollbackLines)`, `nativeFree`, `nativeFeed(h, bytes, off, len)`, `nativeResize`, `nativeSetTheme(h, fg, bg, cursor, palette16)`, `nativeSnapshot(h, full, cols, rows, codepoints, fg, bg, flags, dirty, header)`, `nativeFetchGraphemes`, `nativeScroll(h, delta)`, `nativeScrollToBottom`, `nativeEncodeKey(h, keyOrdinal, mods, utf8, len)`, `nativeEncodePaste`, `nativeDrainEvents`, `nativeTitle`, `nativeText(h, historyLines)`. No JNI calls happen inside libghostty callbacks; array access uses `GetPrimitiveArrayCritical`. The `Key` enum order is mirrored in `key_for()` in `flocket_ghostty.cpp` (Kotlin has an init check).

## Rebuilding the native libraries

Pinned in `scripts/build-ghostty.sh`: Ghostty commit, Zig version (Ghostty's `minimum_zig_version`), NDK, API 29.

```bash
export ANDROID_NDK_HOME=/path/to/ndk/30.0.16248370   # auto-detected in the usual SDK locations
android/scripts/build-ghostty.sh            # skips when VERSION already matches
android/scripts/build-ghostty.sh --force    # rebuild and re-vendor
android/scripts/build-ghostty.sh --verify   # rebuild to a temp dir, compare with SHA256SUMS
```

The script downloads Zig from ziglang.org (sha256 pinned in the script, cached in `~/.cache/flocket/zig/<version>`), shallow-fetches Ghostty into `~/.cache/flocket/ghostty/<commit>`, applies `scripts/ghostty/patches/*.patch`, builds `-Demit-lib-vt -Doptimize=ReleaseFast -Dstrip=true -Dsimd=false -Dcpu=baseline` for `aarch64-linux-android.29` and `x86_64-linux-android.29`, and vendors:

- `src/main/jniLibs/{arm64-v8a,x86_64}/libghostty-vt.so` (committed),
- `third_party/ghostty-vt/{include,LICENSE,VERSION,SHA256SUMS}`.

Two post-build facts: the upstream SONAME `libghostty-vt.so.0` is rewritten in place to `libghostty-vt.so` (an APK can only ship that name; otherwise the shim's `DT_NEEDED` is unresolvable), and the single patch links libc (without it `dlopen` fails on `getauxval`). Builds are bit-for-bit reproducible on the same host and toolchain (`--verify` passes). 16 KB page alignment (`LOAD` segments `0x4000`) comes from upstream; check with `llvm-readelf -l`.

The shim is built by AGP through CMake (`src/main/cpp/CMakeLists.txt`, `ndkVersion = "30.0.16248370"`, CMake 4.1.2, ABIs `arm64-v8a` and `x86_64`, `-Wl,-z,max-page-size=16384`). CI needs the NDK and CMake installed via `sdkmanager`.

## Bumping the Ghostty commit (checklist)

1. Pick a commit; read its `build.zig.zon` for `minimum_zig_version` and update `ZIG_VERSION` plus the three Zig sha256 values (from `https://ziglang.org/download/index.json`).
2. Update `GHOSTTY_COMMIT`. Try the build without patches; re-check whether `0001` is still needed and still applies.
3. `--force`, then check `NEEDED` (only libc/libm), SONAME, `LOAD` alignment `0x4000`, `--verify`.
4. Diff `third_party/ghostty-vt/include` against the old headers: the C API is not stable. Fix `flocket_ghostty.cpp`.
5. Run the instrumented tests (below) and compare the `GHOSTTY_PERF` numbers with [Performance](#performance).
6. Update `THIRD_PARTY_NOTICES.md` if the dependency set in `build.zig.zon` changed, and commit libs, headers, VERSION and SHA256SUMS together.

## Tests

`GhosttyTerminalEngineTest` (`src/androidTest`) runs golden streams, including `protocol/fixtures/attach-first-paint.hex` (copied to androidTest assets).

```bash
emulator -avd pixel -no-window -no-audio -no-boot-anim -no-snapshot-save &
cd android && ./gradlew :core:terminal-ghostty:connectedDebugAndroidTest
adb emu kill
```

JVM unit tests cannot load the `.so`; Kotlin stays thin on purpose.

## Performance

`GhosttyPerfTest` (`src/androidTest`) logs its numbers with tag `GHOSTTY_PERF` (`adb logcat -s GHOSTTY_PERF`). Each snapshot run feeds a full screen of colored text so that every row is dirty, then times `snapshot()` into a reused target (100 iterations, median). The flood test feeds about 1 MiB of `yes`-style lines with SGR colors in 16 KB reads (median of 5 runs).

Recorded 2026-11-01 on the `pixel` AVD (arm64-v8a, Apple Silicon host, debug build; the shim is built with `-O2`):

| Measurement | Just after boot | Warm (two later runs) |
|---|---|---|
| Fully dirty snapshot, 200x60 | 0.62 ms | 0.19 / 0.24 ms |
| Fully dirty snapshot, 120x40 | 0.17 ms | 0.08 / 0.10 ms |
| 1 MiB SGR flood, feed only | 6.0 ms (175 MB/s) | 1.7 / 2.0 ms |
| 1 MiB SGR flood, snapshot after every 16 KB read | 26.9 ms | 7.0 / 8.2 ms |

The per-cell walk (about 6-8 C calls per cell) stays well under the 2 ms frame budget at tablet size, so the bulk `GHOSTTY_RENDER_STATE_ROW_DATA_CELLS_RAW` path is not used. Revisit it if a real device measures over about 2 ms for a fully dirty 200x60 snapshot. Physical mid-range phones are expected to be several times slower than this emulator. When the target is reused, `snapshot()` allocates nothing on the Kotlin side except the Strings of multi-code-point cells. Natively it reuses the grapheme vector and does not allocate per cell, except for clusters longer than 16 code points.

## Grapheme clustering default

`nCreate` sets DEC mode 2027 (`GHOSTTY_MODE_GRAPHEME_CLUSTER`) to true with `GHOSTTY_TERMINAL_OPT_MODE_DEFAULT`, which sets the current value and the value restored by RIS and DECSTR (herdr-core's first paint starts with a reset). Without it libghostty-vt spreads ZWJ emoji and flags over several cells; the Ghostty app also defaults to `grapheme-width-method = unicode`. Programs can still turn it off with `CSI ? 2027 l`.

## Known limitations

- Grapheme clustering (mode 2027) is on by default (see Grapheme clustering default below).
- Hyperlink targets (OSC 8) are flagged (`CellFlags.HYPERLINK`) but URIs are not exposed; selection, mouse reporting, focus events, kitty graphics and clipboard read are not wired.
- OSC 52 writes are reported as events and acknowledged to the program; the UI decides whether to honor them.
- ABIs: arm64-v8a and x86_64 only. The x86_64 library is built and checked statically but was not run on an x86_64 emulator (the dev machine is arm64; the `pixel` AVD is arm64-v8a).
- Palette entries 16-255 are not themeable yet.

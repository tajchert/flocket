# ADR-0003: libghostty-vt as the terminal engine

Status: accepted (2026-11-01)

## Decision
The Android terminal uses **libghostty-vt** (from ghostty-org/ghostty, MIT) through a
small JNI shim of our own, behind a platform-neutral `TerminalEngine` interface. We
render the cell grid ourselves, with Compose and Canvas. The iOS app may use the same
engine later.

## Alternatives considered
- Vendoring the Termux terminal-emulator: mature, pure Java, and no native build. Rejected
  in favour of a more modern engine that both platforms can share, with Kitty keyboard
  and graphics support.
- Writing our own VT engine in Kotlin: months of work and a long tail of bugs.
- The SagerNet/libghostty-android Maven artifact: still alpha, and ties us to a third
  party's API and release cadence. We may use it as a reference.

## Consequences
- The libghostty C API is marked unstable, so we **pin a Ghostty commit**.
  `android/scripts/build-ghostty.sh` rebuilds from that commit with the Zig version it
  declares, plus the Android NDK.
- The prebuilt `.so` files for `arm64-v8a` and `x86_64` are committed, along with their
  checksums. Contributors who don't change native code never install Zig.
- Everything above the engine goes through `TerminalEngine`. JVM tests, previews and
  screenshot tests use a pure-Kotlin fake. A small set of instrumented tests runs the
  real engine against the same golden byte streams.
- Upstream has no official Android CI for this build, so updating the pinned Ghostty
  commit is a deliberate, reviewed change.

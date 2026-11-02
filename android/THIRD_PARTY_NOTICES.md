# Third-party notices

Flocket is Apache-2.0 ([ADR-0002](../docs/decisions/0002-license.md)). Runtime dependencies and bundled code are listed here. Add a row for every new dependency, and keep license headers on adapted code.

| Library | License | Use |
|---|---|---|
| AndroidX (Core, Activity, Lifecycle, Navigation 3, DataStore, Room, Splashscreen, ProfileInstaller) | Apache-2.0 | App framework and persistence |
| Jetpack Compose and Material 3 (incl. adaptive) | Apache-2.0 | UI |
| Kotlin, kotlinx.coroutines, kotlinx.serialization | Apache-2.0 | Language runtime, concurrency, JSON |
| Dagger / Hilt | Apache-2.0 | Dependency injection |
| SSHJ (`com.hierynomus:sshj`) | Apache-2.0 | SSH transport (`core:ssh`) |
| BouncyCastle (`bcprov`, `bcpkix`) | MIT (Bouncy Castle License) | Cryptography for SSHJ and key handling |
| libghostty-vt (Ghostty, commit `83b57ac5`, prebuilt in `core/terminal-ghostty/src/main/jniLibs`) | MIT (Copyright Mitchell Hashimoto, Ghostty contributors; text in `core/terminal-ghostty/third_party/ghostty-vt/LICENSE`) | Terminal emulation (`core:terminal-ghostty`) |
| uucode (statically linked into libghostty-vt) | MIT | Unicode property tables |
| zlib (statically linked into libghostty-vt) | zlib license | Kitty graphics decompression |
| wuffs (statically linked into libghostty-vt) | Apache-2.0 OR MIT | Image decoding for Kitty graphics |
| zigimg (in libghostty-vt build graph) | MIT | Image helpers |
| JetBrains Mono 2.304 (fonts bundled in `core/designsystem/src/main/res/font`) | SIL OFL-1.1 (Copyright 2020 The JetBrains Mono Project Authors; text in `core/terminal/third_party/jetbrains-mono/OFL.txt`) | Terminal and mono UI font (`core:designsystem`, used by `core:terminal`) |
| herdr-mobile `t3-terminal` Android patch (`scripts/ghostty/patches/0001-*.patch`, adapted) | MIT | Link libghostty-vt with libc on Android; build-time only |
| LobeHub icons (`@lobehub/icons-static-svg` 1.101.0; 17 agent glyphs converted to VectorDrawables in `core/ui/src/main/res/drawable/ic_agent_*.xml`; Copyright (c) 2023 LobeHub) | MIT | Agent icons in the terminal list and tab strip |
| Herdr.app 0.1.11 agent catalog (`core/model/.../AgentCatalog.kt`, adapted: names, start commands, match names, tile colours of 20 coding agents, and the interpreter list) | Apache-2.0 | Matching a terminal to a coding agent |
| Herdr.app braille sprite (`src/sprites.rs`, adapted in `core/terminal/.../BoxDrawing.kt`: Ghostty's braille dot sizing) | Apache-2.0 | Braille drawn as fixed dots in the terminal |

Build only (not shipped): Zig 0.17.0 toolchain (MIT) and Ghostty's `translate_c`/Aro (MIT) used by `scripts/build-ghostty.sh`; Android NDK (Apache-2.0) and CMake (BSD-3-Clause) for the JNI shim.

Build and test only (not shipped): JUnit 4 (EPL-1.0), Turbine (Apache-2.0), Robolectric (MIT), Apache MINA SSHD (Apache-2.0), Kover (Apache-2.0), Spotless and ktlint (Apache-2.0 / MIT), Compose Rules for ktlint (Apache-2.0).

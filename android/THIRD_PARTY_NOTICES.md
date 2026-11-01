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
| herdr-mobile `t3-terminal` Android patch (`scripts/ghostty/patches/0001-*.patch`, adapted) | MIT | Link libghostty-vt with libc on Android; build-time only |

Build only (not shipped): Zig 0.17.0 toolchain (MIT) and Ghostty's `translate_c`/Aro (MIT) used by `scripts/build-ghostty.sh`; Android NDK (Apache-2.0) and CMake (BSD-3-Clause) for the JNI shim.

Build and test only (not shipped): JUnit 4 (EPL-1.0), Turbine (Apache-2.0), Robolectric (MIT), Apache MINA SSHD (Apache-2.0), Kover (Apache-2.0), Spotless and ktlint (Apache-2.0 / MIT), Compose Rules for ktlint (Apache-2.0).

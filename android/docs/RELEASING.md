# Releasing

1. Run the full verification in [Testing](TESTING.md#commands) (`scripts/check.sh full`).
2. Bump `versionCode`/`versionName` in `build-logic` (`AndroidApplicationConventionPlugin`) or override them in `app/build.gradle.kts`.
3. Configure release signing outside the repository (a keystore path and passwords from environment variables or `~/.gradle/gradle.properties`); never commit keys.
4. Build: `./gradlew :app:assembleRelease` (APK) or `./gradlew :app:bundleRelease` (AAB, under `app/build/outputs/bundle/release/`). Release builds use R8 shrinking and resource shrinking; install and smoke-test the minified build on a device or emulator before publishing, because R8 problems only appear there.
5. Regenerate the baseline profile on a device when startup paths changed (see [Performance testing](PERFORMANCE_TESTING.md)).
6. Update `THIRD_PARTY_NOTICES.md` for any new dependency, and tag the release.

An AAB is not directly installable with `adb install`; use [bundletool](https://developer.android.com/tools/bundletool) to generate device APKs.

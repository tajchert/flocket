# Releasing

1. Run the full verification in [Testing](TESTING.md#commands) (`scripts/check.sh full`).
2. Bump `versionCode`/`versionName` in `build-logic` (`AndroidApplicationConventionPlugin`) or override them in `app/build.gradle.kts`.
3. Configure release signing outside the repository (see [Release signing](#release-signing)); never commit keys.
4. Build: `./gradlew :app:assembleRelease` (APK) or `./gradlew :app:bundleRelease` (AAB, under `app/build/outputs/bundle/release/`). Release builds use R8 shrinking and resource shrinking; install and smoke-test the minified build on a device or emulator before publishing, because R8 problems only appear there.
5. Regenerate the baseline profile on a device when startup paths changed (see [Performance testing](PERFORMANCE_TESTING.md)).
6. Update `THIRD_PARTY_NOTICES.md` for any new dependency, and tag the release.

An AAB is not directly installable with `adb install`; use [bundletool](https://developer.android.com/tools/bundletool) to generate device APKs.

## Release signing

`app/build.gradle.kts` signs the `release` build only when all four values are set, as environment variables or Gradle properties (for example in `~/.gradle/gradle.properties`; the environment wins): `FLOCKET_KEYSTORE_FILE`, `FLOCKET_KEYSTORE_PASSWORD`, `FLOCKET_KEY_ALIAS`, `FLOCKET_KEY_PASSWORD`. If any is missing, `assembleRelease` produces `app-release-unsigned.apk`, so `scripts/check.sh full` and CI work without secrets. The values are never printed.

Create an upload key once, outside the repository (`*.jks` and `*.keystore` are gitignored; back it up somewhere safe, and never commit it):

```sh
mkdir -p ~/.flocket
keytool -genkeypair -v -keystore ~/.flocket/upload.jks -keyalg RSA -keysize 4096 -validity 10000 -alias upload
```

Build locally:

```sh
export FLOCKET_KEYSTORE_FILE=~/.flocket/upload.jks FLOCKET_KEY_ALIAS=upload
read -rs FLOCKET_KEYSTORE_PASSWORD; read -rs FLOCKET_KEY_PASSWORD; export FLOCKET_KEYSTORE_PASSWORD FLOCKET_KEY_PASSWORD
./gradlew :app:assembleRelease    # or :app:bundleRelease
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

The APK uses signature scheme v2+ only (minSdk 29), so use `apksigner`, not `keytool -printcert -jarfile`.

In GitHub Actions, store the keystore as a base64 secret (`base64 -i ~/.flocket/upload.jks | pbcopy`) named `FLOCKET_KEYSTORE_BASE64`, plus secrets `FLOCKET_KEYSTORE_PASSWORD`, `FLOCKET_KEY_ALIAS` and `FLOCKET_KEY_PASSWORD`, then decode it to a temp file in a release-only job (never on pull requests from forks):

```yaml
- name: Sign release
  env:
    KEYSTORE_B64: ${{ secrets.FLOCKET_KEYSTORE_BASE64 }}
    FLOCKET_KEYSTORE_PASSWORD: ${{ secrets.FLOCKET_KEYSTORE_PASSWORD }}
    FLOCKET_KEY_ALIAS: ${{ secrets.FLOCKET_KEY_ALIAS }}
    FLOCKET_KEY_PASSWORD: ${{ secrets.FLOCKET_KEY_PASSWORD }}
  run: |
    echo "$KEYSTORE_B64" | base64 -d > "$RUNNER_TEMP/upload.jks"
    FLOCKET_KEYSTORE_FILE="$RUNNER_TEMP/upload.jks" ./gradlew --no-daemon :app:bundleRelease
```

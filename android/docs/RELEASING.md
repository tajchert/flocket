# Releasing

1. Run the full verification in [Testing](TESTING.md#commands) (`scripts/check.sh full`).
2. Bump `versionCode`/`versionName` in `build-logic` (`AndroidApplicationConventionPlugin`) or override them in `app/build.gradle.kts`.
3. Configure release signing outside the repository (see [Release signing](#release-signing)); never commit keys.
4. Build: `./gradlew :app:assembleRelease` (APK) or `./gradlew :app:bundleRelease` (AAB, under `app/build/outputs/bundle/release/`). Release builds use R8 shrinking and resource shrinking; install and smoke-test the minified build on a device or emulator before publishing, because R8 problems only appear there.
5. Regenerate the baseline profile on a device when startup paths changed (see [Performance testing](PERFORMANCE_TESTING.md)).
6. Update `THIRD_PARTY_NOTICES.md` for any new dependency, and tag the release.

An AAB is not directly installable with `adb install`; use [bundletool](https://developer.android.com/tools/bundletool) to generate device APKs.

## Release signing

`app/build.gradle.kts` signs the `release` build only when all four values are set. It reads them from the gitignored file `android/keystore.properties` first, then from environment variables, then from Gradle properties:

```properties
storeFile=/Users/<user>/keystores/flocket-release.jks
storePassword=<password>
keyAlias=flocket
keyPassword=<password>
```

The matching environment variables or Gradle properties are `FLOCKET_KEYSTORE_FILE`, `FLOCKET_KEYSTORE_PASSWORD`, `FLOCKET_KEY_ALIAS` and `FLOCKET_KEY_PASSWORD`. If any value is missing, `assembleRelease` produces `app-release-unsigned.apk`, so `scripts/check.sh full` and CI work without secrets. The values are never printed.

`keystore.properties`, `*.jks` and `*.keystore` must stay uncommitted. Keep a backup of the keystore and its password outside the machine: a lost key means users cannot upgrade in place.

Expected release certificate:

```text
CN=Flocket Release, O=Tajchert, C=PL
SHA-256: fc55e7f3ad29d64d165f1881b4f05b21aad25b2dbaf3afec7a185361b91fa47c
```

Build and verify locally; the certificate DN and SHA-256 must match the values above:

```sh
./gradlew :app:assembleRelease    # or :app:bundleRelease
APKSIGNER=$(find "$ANDROID_HOME/build-tools" -name apksigner -type f | sort -V | tail -n1)
"$APKSIGNER" verify --print-certs app/build/outputs/apk/release/app-release.apk
```

The APK uses signature scheme v2+ only (minSdk 29), so use `apksigner`, not `keytool -printcert -jarfile`.

In GitHub Actions, store the keystore as a base64 secret (`base64 -i ~/keystores/flocket-release.jks | pbcopy`) named `FLOCKET_KEYSTORE_BASE64`, plus secrets `FLOCKET_KEYSTORE_PASSWORD`, `FLOCKET_KEY_ALIAS` and `FLOCKET_KEY_PASSWORD`, then decode it to a temp file in a release-only job (never on pull requests from forks):

```yaml
- name: Sign release
  env:
    KEYSTORE_B64: ${{ secrets.FLOCKET_KEYSTORE_BASE64 }}
    FLOCKET_KEYSTORE_PASSWORD: ${{ secrets.FLOCKET_KEYSTORE_PASSWORD }}
    FLOCKET_KEY_ALIAS: ${{ secrets.FLOCKET_KEY_ALIAS }}
    FLOCKET_KEY_PASSWORD: ${{ secrets.FLOCKET_KEY_PASSWORD }}
  run: |
    echo "$KEYSTORE_B64" | base64 -d > "$RUNNER_TEMP/flocket-release.jks"
    FLOCKET_KEYSTORE_FILE="$RUNNER_TEMP/flocket-release.jks" ./gradlew --no-daemon :app:bundleRelease
```

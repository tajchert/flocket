#!/usr/bin/env bash
# Builds a tiny installable APK (no code, one empty application) for the APK-install device journey.
#   scripts/make-test-apk.sh OUT.apk [PACKAGE] [LABEL]
# Uses aapt2 and apksigner from the newest Android build-tools and a throwaway keystore. Prints OUT.apk.
set -euo pipefail
out="${1:?usage: make-test-apk.sh OUT.apk [PACKAGE] [LABEL]}"
pkg="${2:-com.mtajchert.flocket.e2e.tiny}"
label="${3:-Flocket E2E Tiny}"
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/homebrew/share/android-commandlinetools}}"
bt="$(ls -d "$sdk"/build-tools/* | sort -V | tail -1)"
jar="$(ls -d "$sdk"/platforms/android-* | sort -V | tail -1)/android.jar"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
cat >"$work/AndroidManifest.xml" <<XML
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="$pkg"
    android:versionCode="1" android:versionName="1.0">
    <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="34" />
    <application android:label="$label" android:hasCode="false" />
</manifest>
XML
"$bt/aapt2" link -o "$work/unsigned.apk" -I "$jar" --manifest "$work/AndroidManifest.xml" >/dev/null
keytool -genkeypair -keystore "$work/k.jks" -storepass flocket -keypass flocket -alias k -keyalg RSA \
  -keysize 2048 -validity 3650 -dname "CN=flocket-e2e" >/dev/null 2>&1
"$bt/apksigner" sign --ks "$work/k.jks" --ks-pass pass:flocket --key-pass pass:flocket --out "$out" "$work/unsigned.apk"
echo "$out"

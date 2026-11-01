#!/usr/bin/env bash
# Verification entry point. Canonical documentation: docs/TESTING.md.
#   scripts/check.sh quick   spotlessCheck + JVM tests + Android unit tests (no screenshots)
#   scripts/check.sh full    quick + lint, debug and release builds, screenshot validation
set -euo pipefail
cd "$(dirname "$0")/.."

mode="${1:-quick}"
run() {
  echo ">>> ./gradlew $*"
  ./gradlew "$@" --console=plain
}

case "$mode" in
  quick)
    run spotlessCheck test testDebugUnitTest
    ;;
  full)
    run spotlessCheck test testDebugUnitTest
    run lintDebug assembleDebug :app:assembleRelease
    run validateDebugScreenshotTest
    ;;
  *)
    echo "usage: scripts/check.sh [quick|full]" >&2
    exit 2
    ;;
esac
echo ">>> check.sh $mode: OK"

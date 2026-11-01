#!/usr/bin/env bash
# Emulator harness for device journeys: infra (emulator, sshd, install) plus the journey runner.
# Canonical docs: docs/TESTING.md ("Device journeys").
#
#   scripts/e2e-device.sh up [--no-build] [--fresh]  boot the emulator, start sshd, install the debug app
#   scripts/e2e-device.sh journey [Class|Class#method ...] [--smoke] [--no-build] [--repeat N]
#                                                   run journey androidTests, each in a cleared app
#   scripts/e2e-device.sh hc <args...>              hc in the isolated session (e.g. `hc run --bg sh`)
#   scripts/e2e-device.sh shot [file] [max-px]      screenshot, scaled down (default 600 px)
#   scripts/e2e-device.sh down                      stop the hc session, sshd and the emulator
#
# Device I/O goes through the `android` CLI (delta installs, screenshots); adb is used for settings,
# the IME, `pm` and `am instrument`. Isolation: it only talks to its own emulator (FLOCKET_SERIAL,
# default emulator-5580), sshd port FLOCKET_SSHD_PORT (2222), hc session FLOCKET_HC_SESSION (default
# flocket-e2e) and state dir FLOCKET_E2E_DIR. It never touches ~/.ssh, the system sshd, the default
# herdr session or other devices. Never use Gradle connected* tasks for journeys: they install on
# every attached device.
set -euo pipefail
cd "$(dirname "$0")/.."

AVD="${FLOCKET_AVD:-pixel}"
SERIAL="${FLOCKET_SERIAL:-emulator-5580}"
EMU_PORT="${SERIAL#emulator-}"
HC_SESSION_NAME="${FLOCKET_HC_SESSION:-flocket-e2e}"
STATE="${FLOCKET_E2E_DIR:-${TMPDIR:-/tmp}/flocket-e2e}"
SSHD_PORT="${FLOCKET_SSHD_PORT:-2222}"
APP_ID="com.mtajchert.flocket.debug"
# Every keyboard is disabled while the harness drives the UI: injected keys arrive out of order with
# Gboard, and any on-screen keyboard (including the voice IME fallback) hides the lower form fields.
IMES=(
  com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME
  com.google.android.tts/com.google.android.apps.speech.tts.googletts.settings.asr.voiceime.VoiceInputMethodService
)
export FLOCKET_SSHD_DIR="$STATE/sshd"
DEV="--device=$SERIAL"

log() { echo ">>> $*" >&2; }
die() { echo "e2e-device: $*" >&2; exit 1; }
A() { adb -s "$SERIAL" "$@"; }
hc_e2e() { (cd /tmp && env -u HC_TERMINAL_SOCKET HC_SESSION="$HC_SESSION_NAME" TMPDIR=/tmp hc "$@"); }

booted() { [[ "$(A shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; }

emulator_up() {
  if adb devices | grep -q "^$SERIAL[[:space:]]*device"; then
    log "reusing $SERIAL"
  else
    # Raw `emulator`: `android emulator start` cannot pin the console port, so the serial would vary.
    log "booting $AVD as $SERIAL"
    mkdir -p "$STATE"
    nohup emulator -avd "$AVD" -port "$EMU_PORT" -no-window -no-audio -no-boot-anim -read-only \
      >"$STATE/emulator.log" 2>&1 &
    A wait-for-device
  fi
  local end=$((SECONDS + 180))
  until booted; do ((SECONDS < end)) || die "emulator did not boot (see $STATE/emulator.log)"; sleep 2; done
  for k in window_animation_scale transition_animation_scale animator_duration_scale; do
    A shell settings put global "$k" 0
  done
}

# Run after `pm clear`/installs: clearing app data re-enables the system keyboards.
keyboards_off() { for ime in "${IMES[@]}"; do A shell ime disable "$ime" >/dev/null 2>&1 || true; done; }

APK="app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
TEST_ID="$APP_ID.test"
RUNNER="androidx.test.runner.AndroidJUnitRunner"
JOURNEY_PKG="com.mtajchert.flocket.journey"
JOURNEYS="$STATE/journeys"

build_apks() {
  log "assembleDebug + assembleDebugAndroidTest"
  ./gradlew -q :app:assembleDebug :app:assembleDebugAndroidTest --console=plain 2>&1 | { grep -v '^w:' >&2 || true; }
  [[ -f "$APK" && -f "$TEST_APK" ]] || die "build failed or APKs missing (see output above)"
}

sha_of() { shasum -a 256 "$1" | awk '{print $1}'; }

# Installs only when the APK changed since the last install on this serial, or the package is gone
# (the AVD is started -read-only, so a reboot wipes installs). $1 name, $2 apk, $3 package.
ensure_installed() {
  local name="$1" apk="$2" pkg="$3" want have_file="$JOURNEYS/.installed-$1.sha"
  mkdir -p "$JOURNEYS"
  want="$SERIAL $(sha_of "$apk")"
  if [[ "$(cat "$have_file" 2>/dev/null || true)" == "$want" ]] && [[ -n "$(A shell pm path "$pkg" | tr -d '\r')" ]]; then
    return 0
  fi
  log "installing $name"
  # -t: androidTest APKs are test-only; -r: keep it a replace, not a fresh install.
  A install -r -t "$apk" >/dev/null
  echo "$want" >"$have_file"
}

cmd_up() {
  local build=1 fresh=0
  for a in "$@"; do
    case "$a" in
      --no-build) build=0 ;;
      --fresh) fresh=1 ;;
      *) die "unknown option $a" ;;
    esac
  done
  emulator_up
  scripts/dev-sshd.sh start "$SSHD_PORT" >&2
  if ((build)); then log "assembleDebug"; ./gradlew -q :app:assembleDebug --console=plain 2>&1 | { grep -v '^w:' >&2 || true; }; fi
  [[ -f "$APK" ]] || die "no $APK (run without --no-build)"
  if ((fresh)); then A shell pm clear "$APP_ID" >/dev/null 2>&1 || true; fi
  log "installing (delta)"
  android install "$DEV" --apks="$APK" >/dev/null
  mkdir -p "$JOURNEYS"
  echo "$SERIAL $(sha_of "$APK")" >"$JOURNEYS/.installed-app-debug.sha"
  keyboards_off
  echo "ready: serial=$SERIAL, hc session=$HC_SESSION_NAME, sshd 127.0.0.1:$SSHD_PORT (10.0.2.2 from the emulator)"
  echo "next: scripts/e2e-device.sh journey [--smoke]"
}

now() { python3 -c 'import time; print(time.time())'; }

# Instrumentation selection args for the given selectors: "-e class A,B#m" (a bare word is a class in
# the journey package) or the whole package.
selection_args() {
  local cls=() sel
  for sel in "$@"; do
    [[ "${sel%%#*}" == *.* ]] || sel="$JOURNEY_PKG.$sel"
    cls+=("$sel")
  done
  if ((${#cls[@]})); then
    local IFS=,
    echo "-e class ${cls[*]}"
  else
    echo "-e package $JOURNEY_PKG"
  fi
}

# Arguments the journeys read through InstrumentationRegistry (see HostControl). Values are shell
# quoted because adb joins them into one command line on the device.
journey_args() {
  local key="$STATE/control_ed25519" a=()
  local pairs=(
    "controlKey=$(base64 <"$key" | tr -d '\n')"
    "user=$(id -un)"
    "hostFingerprint=$(ssh-keygen -lf "$FLOCKET_SSHD_DIR/host_ed25519.pub" | awk '{print $2}')"
    "hcPath=$(command -v hc)"
    "hcSession=$HC_SESSION_NAME"
    "host=10.0.2.2"
    "port=$SSHD_PORT"
    "sshdDir=$FLOCKET_SSHD_DIR"
    "devSshd=$PWD/scripts/dev-sshd.sh"
    "serial=$SERIAL"
    "adbPath=$(command -v adb)"
  )
  local p
  for p in "${pairs[@]}"; do a+=(-e "${p%%=*}" "$(printf '%q' "${p#*=}")"); done
  echo "${a[*]}"
}

# stdin is closed on purpose: `adb shell` otherwise waits on an inherited, open stdin and never returns.
instrument() { # $@ = instrumentation args after `am instrument -w -r`
  # A hung test must not hang the run (FLOCKET_JOURNEY_TIMEOUT s); the next pm clear kills what it left.
  timeout "${FLOCKET_JOURNEY_TIMEOUT:-180}" adb -s "$SERIAL" shell am instrument -w -r "$@" "$TEST_ID/$RUNNER" </dev/null | tr -d '\r'
}

cmd_journey() {
  local build=1 smoke=0 repeat=1 sels=()
  while (($#)); do
    case "$1" in
      --no-build) build=0 ;;
      --smoke) smoke=1 ;;
      --repeat) shift; repeat="${1:?--repeat needs a number}"; [[ "$repeat" =~ ^[1-9][0-9]*$ ]] || die "bad --repeat $repeat" ;;
      -*) die "unknown option $1" ;;
      *) sels+=("$1") ;;
    esac
    shift
  done
  booted || die "no emulator on $SERIAL (run: scripts/e2e-device.sh up)"
  ((build)) && build_apks
  [[ -f "$APK" && -f "$TEST_APK" ]] || die "APKs missing (run without --no-build)"
  mkdir -p "$JOURNEYS"
  ensure_installed app-debug "$APK" "$APP_ID"
  ensure_installed app-debug-androidTest "$TEST_APK" "$TEST_ID"

  local key="$STATE/control_ed25519"
  [[ -f "$key" ]] || ssh-keygen -q -t ed25519 -N '' -f "$key" -C flocket-e2e-control
  scripts/dev-sshd.sh authorize "$(cat "$key.pub")" >/dev/null

  local sel_args args
  sel_args="$(selection_args ${sels[@]+"${sels[@]}"})"
  ((smoke)) && sel_args="$sel_args -e annotation $JOURNEY_PKG.Smoke"
  args="$(journey_args)"

  # Discovery: with `-e log true` nothing runs, the runner only reports each test it would run.
  local tests=() t
  # shellcheck disable=SC2086
  while IFS= read -r t; do tests+=("$t"); done < <(
    instrument -e log true $sel_args $args |
      awk -F= '/^INSTRUMENTATION_STATUS: class=/ {c=$2} /^INSTRUMENTATION_STATUS: test=/ {print c "#" $2}' | sort -u)
  ((${#tests[@]})) || die "no journey tests selected (${sels[*]:-all}$( ((smoke)) && echo ", smoke"))"

  local passed=0 failed=0 start run name t0 secs out result reason
  local -a counts=()
  start="$(now)"
  for ((run = 1; run <= repeat; run++)); do
    ((repeat > 1)) && echo "--- run $run/$repeat"
    for name in "${tests[@]}"; do
      t0="$(now)"
      A shell pm clear "$APP_ID" >/dev/null
      A shell pm grant "$APP_ID" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
      keyboards_off
      out="$JOURNEYS/$name.txt"
      # shellcheck disable=SC2086
      instrument -e class "$name" $args >"$out" || echo "INSTRUMENTATION_ABORTED: timed out or adb failed" >>"$out"
      secs="$(python3 -c "print('%.1f' % ($(now) - $t0))")"
      reason=""
      # An assumption failure (status code -4) is reported as OK too, so check it first: a skipped
      # journey means the runner args did not arrive, which must never look like a pass.
      if grep -q '^INSTRUMENTATION_STATUS_CODE: -4' "$out"; then
        result=FAIL; reason=" (skipped: missing args)"
      elif grep -q '^OK (1 test' "$out"; then
        result=PASS
      else
        result=FAIL
      fi
      printf '%-4s %5ss  %s%s\n' "$result" "$secs" "$name" "$reason"
      if [[ $result == PASS ]]; then
        passed=$((passed + 1)); counts+=("$name")
      else
        failed=$((failed + 1))
        # First stack= line, then the failure text; capped so a failure stays readable.
        { grep -m1 '^INSTRUMENTATION_STATUS: stack=' "$out" || true
          grep -A6 -m1 -E '^(There w(as|ere) [0-9]+ failure|INSTRUMENTATION_RESULT: shortMsg)' "$out" || true
          grep -m1 -E '^Process crashed|^INSTRUMENTATION_ABORTED' "$out" || true; } | head -8 | cut -c1-200 | sed 's/^/     /'
      fi
    done
  done
  if ((repeat > 1)); then
    echo "--- passes per test (of $repeat)"
    for name in "${tests[@]}"; do
      printf '%d/%d  %s\n' "$(printf '%s\n' ${counts[@]+"${counts[@]}"} | grep -cxF "$name")" "$repeat" "$name"
    done
  fi
  echo "journeys: $passed passed, $failed failed in $(python3 -c "print(round($(now) - $start))")s"
  ((failed == 0))
}

cmd_shot() {
  local out="${1:-$STATE/shot.png}" px="${2:-600}"
  mkdir -p "$(dirname "$out")"
  android screen capture "$DEV" -o "$out" >/dev/null
  if command -v sips >/dev/null; then sips -Z "$px" "$out" >/dev/null; fi
  echo "$out"
}

cmd_down() {
  hc_e2e server stop >/dev/null 2>&1 && log "hc session $HC_SESSION_NAME stopped" || true
  scripts/dev-sshd.sh stop >&2 || true
  if adb devices | grep -q "^$SERIAL[[:space:]]"; then android emulator stop "$SERIAL" >/dev/null 2>&1 || A emu kill >/dev/null 2>&1; log "$SERIAL stopped"; fi
}

cmd="${1:-}"; shift || true
case "$cmd" in
  up) cmd_up "$@" ;;
  journey) cmd_journey "$@" ;;
  hc) hc_e2e "$@" ;;
  shot) cmd_shot "$@" ;;
  down) cmd_down ;;
  *) sed -n '2,16p' "$0"; exit 2 ;;
esac

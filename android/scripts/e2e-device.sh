#!/usr/bin/env bash
# One-command emulator harness for end-to-end checks against a real herdr-core over SSH.
# Canonical docs: docs/journeys/E2E_SMOKE.md.
#
#   scripts/e2e-device.sh up [--no-build] [--fresh]  boot the emulator, start sshd, install the debug
#                                                   app, onboard, add + trust the host -> Terminals list
#   scripts/e2e-device.sh hc <args…>                hc in the isolated session (e.g. `hc run --bg sh`)
#   scripts/e2e-device.sh ui                        visible app nodes: "x,y  text  desc"
#   scripts/e2e-device.sh tap <text>                tap the node whose text or description is <text>
#   scripts/e2e-device.sh wait <text> [secs]        wait until <text> is on screen
#   scripts/e2e-device.sh type <text> [--enter]     send keystrokes (e.g. into an open terminal)
#   scripts/e2e-device.sh shot [file] [max-px]      screenshot, scaled down (default 600 px)
#   scripts/e2e-device.sh down                      stop the hc session, sshd and the emulator
#
# Device I/O goes through the `android` CLI (delta installs, selector-based input, `layout`); adb is
# used only for settings, the IME and `am`. Isolation: it only talks to its own emulator
# (FLOCKET_SERIAL, default emulator-5580) and the hc session FLOCKET_HC_SESSION (default
# flocket-e2e). It never touches ~/.ssh, the system sshd, the default herdr session or other devices.
set -euo pipefail
cd "$(dirname "$0")/.."

AVD="${FLOCKET_AVD:-pixel}"
SERIAL="${FLOCKET_SERIAL:-emulator-5580}"
EMU_PORT="${SERIAL#emulator-}"
HC_SESSION_NAME="${FLOCKET_HC_SESSION:-flocket-e2e}"
STATE="${FLOCKET_E2E_DIR:-${TMPDIR:-/tmp}/flocket-e2e}"
SSHD_PORT="${FLOCKET_SSHD_PORT:-2222}"
APP_ID="com.mtajchert.flocket.debug"
APK="app/build/outputs/apk/debug/app-debug.apk"
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

# Exact, case-sensitive selector for a visible text (regex metacharacters escaped).
sel() { printf 'text=/^%s$/' "$(printf '%s' "$1" | sed -e 's/[][\.*^$+?(){}|/]/\\&/g')"; }

ui_dump() {
  { android layout "$DEV" --flat 2>/dev/null || true; } | python3 -I -c '
import json, sys
try:
    windows = json.load(sys.stdin)
except ValueError:  # no layout yet (just booted, app starting)
    windows = []
for w in windows:
    if w.get("type") == "SYSTEM":  # status and navigation bars
        continue
    for n in w.get("content", []):
        t, d = n.get("text", ""), n.get("content-desc", "")
        if (t or d) and not n.get("hidden"):
            print(n.get("center", "").strip("[]") + "\t" + t + "\t" + d)
'
}

on_screen() { ui_dump | awk -F'\t' -v w="$1" '$2 == w || $3 == w { f = 1 } END { exit !f }'; }

wait_for() {
  local text="$1" secs="${2:-20}" end=$((SECONDS + ${2:-20}))
  while ((SECONDS < end)); do on_screen "$text" && return 0; sleep 0.5; done
  echo "--- screen ---" >&2; ui_dump >&2
  die "timed out after ${secs}s waiting for '$text'"
}

tap_text() {
  android input tap "$DEV" --match=first --wait="$(( ${2:-20} * 1000 ))" "$(sel "$1")" >/dev/null 2>&1 ||
    android input tap "$DEV" --match=first --wait=1000 "desc=/^$1\$/" >/dev/null 2>&1 ||
    { ui_dump >&2; die "no '$1' on screen"; }
}

scroll_to() {
  local i
  for i in 1 2 3 4 5 6 7 8; do
    on_screen "$1" && return 0
    if ((i > 1)); then
      android input scroll "$DEV" --match=first --dir=down --amount=0.4 "scrollable=true" >/dev/null 2>&1 || true
    fi
    sleep 0.3
  done
  ui_dump >&2
  die "could not scroll to '$1'"
}

# "<focused 0|1><TAB><text>" of the text field under the label $1 (empty if there is none).
field_state() {
  { android layout "$DEV" --flat 2>/dev/null || true; } | python3 -I -c '
import json, re, sys
label = sys.argv[1]
try:
    windows = json.load(sys.stdin)
except ValueError:
    sys.exit(0)
nodes = [n for w in windows if w.get("type") != "SYSTEM" for n in w.get("content", [])]
def box(n): return list(map(int, re.findall(r"-?\d+", n.get("bounds", ""))))
for l in nodes:
    if l.get("text") != label or "EditText" in l.get("class", ""): continue
    lx0, ly0, lx1, ly1 = box(l); cx, cy = (lx0 + lx1) // 2, (ly0 + ly1) // 2
    for f in nodes:
        if "EditText" not in f.get("class", ""): continue
        x0, y0, x1, y1 = box(f)
        if x0 <= cx <= x1 and y0 <= cy <= y1:
            print(("1" if "FOCUSED" in (f.get("state") or []) else "0") + "\t" + (f.get("text") or ""))
            sys.exit(0)
' "$1"
}

# The system re-enables keyboards after boot and `pm clear`, so also hide one when it shows.
keyboard_shown() { A shell dumpsys input_method | grep -q 'mInputShown=true'; }
hide_keyboard() { if keyboard_shown; then android input key "$DEV" back >/dev/null; sleep 0.3; fi; }

# Focus the field labelled $1 and replace its text with $2 (checked, one retry).
fill() {
  local attempt end st
  for attempt in 1 2; do
    hide_keyboard
    scroll_to "$1"
    tap_text "$1"
    end=$((SECONDS + 3))
    until [[ "$(field_state "$1")" == 1$'\t'* ]]; do ((SECONDS < end)) || break; sleep 0.2; done
    android input text "$DEV" --match=first --wait=3000 "$2" "focused=true" >/dev/null 2>&1 || true
    hide_keyboard
    end=$((SECONDS + 2))
    while ((SECONDS < end)); do
      st="$(field_state "$1")"
      [[ "${st#*$'\t'}" == "$2" ]] && return 0
      sleep 0.2
    done
    log "retrying '$1' (field shows '${st#*$'\t'}')"
  done
  die "could not type '$2' into '$1'"
}

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

onboard() {
  log "onboarding"
  keyboards_off
  tap_text "Get started" 30
  tap_text "I have one"
  tap_text "Generate key"
  wait_for "Key ready. Add it to your machine before connecting." 30
  local pub
  pub="$(ui_dump | grep -oE 'ssh-ed25519 [A-Za-z0-9+/=]{20,}( [A-Za-z0-9._@-]+)?' | head -1)"
  [[ -n "$pub" ]] || die "no public key on the key screen"
  scripts/dev-sshd.sh authorize "$pub" >&2
  tap_text "Continue"
  wait_for "Add your machine"
  fill "Name" "e2e"
  fill "Hostname or IP address" "10.0.2.2"
  fill "Username" "$(id -un)"
  hide_keyboard
  scroll_to "Advanced"
  tap_text "Advanced"
  fill "SSH port" "$SSHD_PORT"
  fill "hc session name" "$HC_SESSION_NAME"
  hide_keyboard
  scroll_to "Save and connect"
  tap_text "Save and connect"
  wait_for "Trust this host?" 30
  local want
  want="$(ssh-keygen -lf "$FLOCKET_SSHD_DIR/host_ed25519.pub" | awk '{print $2}' | sed 's/^SHA256://')"
  # The dialog groups the fingerprint in blocks of four; compare without whitespace.
  ui_dump | tr -d ' \n' | grep -qF "$want" || die "trust dialog fingerprint does not match the harness sshd key"
  tap_text "Trust and connect"
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
  if ((build)); then log "assembleDebug"; ./gradlew -q :app:assembleDebug --console=plain 2>&1 | grep -v '^w:' >&2 || true; fi
  [[ -f "$APK" ]] || die "no $APK (run without --no-build)"
  if ((fresh)); then A shell pm clear "$APP_ID" >/dev/null 2>&1 || true; fi
  log "installing (delta)"
  android install "$DEV" --apks="$APK" >/dev/null
  keyboards_off
  # Skip the first-terminal "Allow notifications?" prompt (it swallows the first taps and keys).
  A shell pm grant "$APP_ID" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
  A shell am start -n "$APP_ID/com.mtajchert.flocket.MainActivity" >/dev/null
  local end=$((SECONDS + 20)) screen
  while ((SECONDS < end)); do
    screen="$(ui_dump)"
    grep -q $'\tGet started\t' <<<"$screen" && { onboard; break; }
    grep -q $'\tTerminals\t' <<<"$screen" && { log "app already onboarded"; break; }
    sleep 0.5
  done
  wait_for "Terminals" 30
  echo "ready: serial=$SERIAL, hc session=$HC_SESSION_NAME, sshd 127.0.0.1:$SSHD_PORT (10.0.2.2 from the emulator)"
  echo "next: scripts/e2e-device.sh hc run --bg --no-parent sh   # a terminal to open on the phone"
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
  hc) hc_e2e "$@" ;;
  ui) ui_dump ;;
  tap) tap_text "${1:?text}" "${2:-20}" ;;
  wait) wait_for "${1:?text}" "${2:-20}" ;;
  type) keyboards_off # Gboard reorders injected keys
    # Keystrokes, not "set text": the terminal view is not a text field. Spaces must be %s.
    A shell input text "$(printf '%s' "${1:?text}" | sed 's/ /%s/g')"
    if [[ "${2:-}" == --enter ]]; then android input key "$DEV" enter >/dev/null; fi ;;
  shot) cmd_shot "$@" ;;
  down) cmd_down ;;
  *) sed -n '2,19p' "$0"; exit 2 ;;
esac

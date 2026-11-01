# End-to-end smoke journey (emulator + real `hc` over SSH)

A manual journey that runs the debug app against a real herdr-core on the developer's machine,
through a throwaway SSH server. Use it after changes to sessions, the terminal screen, navigation
or lifecycle handling. Unit and screenshot tests are described in [TESTING.md](../TESTING.md).

## Prerequisites

- macOS or Linux with `hc` installed (here `~/.local/bin/hc`; the app must find it by discovery,
  so leave "Path to hc" empty).
- OpenSSH `sshd` binary (it is run unprivileged; the system sshd and `~/.ssh` are never touched).
- An AVD (here `pixel`, `sdk_gphone64_arm64`, Android 16 / API 36, `BE2A.250530.026.F3`).

## Safety rules

- The app's host uses the hc session **`flocket-e2e`**. On the machine, only ever run
  `HC_SESSION=flocket-e2e TMPDIR=/tmp hc …` (and `--no-parent` for `hc run`, so a terminal you run
  from inside your own herdr terminal is not parented to it). Never touch the default session.
- Start your own emulator on a fixed port and address it with `adb -s emulator-5580` only. Don't
  run Gradle `connected*` tasks while other devices are attached (they install on all of them).
- `dev-sshd.sh stop` only stops the listener: open connections survive. To cut the app's link,
  kill only the `sshd-session` whose socket is `127.0.0.1:2222` (`lsof -nP -a -p <pid> -iTCP`), never
  sessions of the system sshd.

## Harness

```bash
cd android
export FLOCKET_SSHD_DIR=/some/scratch/dir/e2e-sshd    # default: $TMPDIR/flocket-dev-sshd
scripts/dev-sshd.sh start            # 127.0.0.1:2222, emulator reaches it at 10.0.2.2:2222
scripts/dev-sshd.sh authorize "ssh-ed25519 AAAA… flocket-device"
scripts/dev-sshd.sh status | stop
ssh-keygen -lf "$FLOCKET_SSHD_DIR/host_ed25519.pub"   # fingerprint to compare in the trust dialog

emulator -avd pixel -no-window -no-audio -no-boot-anim -no-snapshot-save -port 5580 &
./gradlew :app:assembleDebug && adb -s emulator-5580 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5580 shell am start -n com.mtajchert.flocket.debug/com.mtajchert.flocket.MainActivity
```

Drive the UI with `adb shell uiautomator dump` + `adb shell input tap/text/keyevent`, and screenshots
with `adb exec-out screencap -p`. Disable animations (`settings put global *_animation_scale 0`).

**Typing caveat:** with Gboard as the active IME, keys injected by `adb shell input` are sometimes
reordered on the first input after a terminal opens (`echo` arrived as `eoch`/`ceho`). With the IME
disabled (`adb shell ime disable com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME`)
the same steps were always in order, so type with the IME disabled and re-enable it afterwards.

## Journey and expected results

1. Fresh install: onboarding welcome, "What you'll need", device key.
2. Generate key: the screen shows the `echo '<pubkey>' >> ~/.ssh/authorized_keys` line; copy the key
   from the UI dump into `dev-sshd.sh authorize`. Add host: name, `10.0.2.2`, username `id -un`,
   device key, Advanced: port 2222, hc session `flocket-e2e`, hc path empty. Save and connect.
   Expected: trust dialog with the same SHA-256 fingerprint as `ssh-keygen -lf`; Trust → the
   Terminals list with the host **Online**.
3. `hc run --bg --no-parent --name macsh --cwd /tmp sh` on the machine: the row appears live. New
   terminal (FAB) → Default shell, cwd `~` → Start: the terminal screen opens as owner (the core
   size becomes the phone grid, e.g. 52x39).
4. Type `echo hello-flocket` + Enter; `hc read <t>` shows it. `top` redraws; sticky Ctrl + `c`
   from the extra keys stops it.
5. Open `macsh` (headless 200x120) from the list: `hc get` still says 200x120 (passive, scaled to
   fit); the first key resizes it to the phone grid.
6. Lifecycle: see the matrix. Watch `grep -c "Accepted publickey" $FLOCKET_SSHD_DIR/sshd.log` for
   reconnects, `hc get <t>` (200x120 = no viewer left) and `adb logcat | grep -E "CoreSession|InetDiagMessage"`.
7. Smoothness: in an owned terminal run a 5 MB colour flood
   (`yes "$(printf '\033[3%dmflocket\033[0m ' 1 2 3 4 5 6)" | head -c 5000000`) and read
   `adb shell dumpsys gfxinfo com.mtajchert.flocket.debug` (reset before each run). Ctrl+C under an
   endless flood must stop it promptly.
8. Overflow → Kill → confirm: the exit banner shows with Remove/Close; Remove returns to the list.

Clean up: `HC_SESSION=flocket-e2e TMPDIR=/tmp hc server stop`, `dev-sshd.sh stop`,
`adb -s emulator-5580 emu kill`; check that `hc ls` (default session) is unchanged.

## Results, 2026-11-01

hc 0.1.15; emulator `pixel` (sdk_gphone64_arm64, Android 16, API 36); debug build of `test/e2e`.

| Step | Result | Notes |
|---|---|---|
| 1-2 Onboarding, key, host, trust | PASS | Fingerprint matched; hc found by discovery. Key list shows the alias `key-<uuid>`, not the label. |
| 3 Live list, new terminal (owner) | PASS | Mac-started terminal appeared via the event stream. |
| 4 Typing, `top`, Ctrl+C | PASS | |
| 5 Passive open keeps size | PASS after fix | Before: opening a headless terminal resized it 200x120 → 80x24. |
| 6a Rotation (terminal, list) | PASS | Passive stays 200x120; owner follows the grid (65x9 landscape with IME). No reconnect. |
| 6b Unfold 2208x1840 / fold back | PASS | List-detail with rail appears; terminal keeps content and session. No reconnect. |
| 6c Lock/unlock within 60 s | FAIL (platform) | ~5 s after locking, Android 16 blocks the app's network (`blocked=APP_BACKGROUND`) and destroys its sockets; the viewer drops and the app reconnects on unlock. Content and input fine afterwards. |
| 6d Background > 60 s, foreground | PASS (with 6c caveat) | Link already dropped ~5 s after HOME (same cause). Foreground: reconnect, passive re-attach, fresh paint, correct content. |
| 6e Wi-Fi + data off/on | PASS | "Reconnecting…" chip, typed key not sent; Online 1 s after re-enable. |
| 6f Process death (`am kill`) | PASS | Recents → terminal screen restored, passive attach, content repainted, input works. |
| 6g sshd link killed | PASS | Reconnecting chip, Online 2 s after sshd restart; key typed while down not replayed. |
| 7 Flood + Ctrl+C | PASS (slow) | See numbers; Ctrl+C under an endless flood returned to the shell in 0.33 s; no ANR. |
| 8 Kill, exit banner, Remove | PASS | Banner says "Exited (code 1)" for a kill (core reason `killed`). |

gfxinfo (emulator, host GPU; indicative only, P3 measured 14-17 ms GPU per frame on this AVD):

| Run | Frames | Janky | P50 / P90 / P99 |
|---|---|---|---|
| 5 MB flood (70 s) | 51 | 3.9 % | 16 / 17 / 21 ms |
| Endless flood 4 s + Ctrl+C | 298 | 8.4 % | 20 / 29 / 34 ms |

Throughput: the same 5 MB flood finishes in ~3 s with no viewer but takes ~70 s with the phone
attached, while the app, `hc connect` and the core are all near 0 % CPU. The stream is latency
bound somewhere between the SSH channel window (sshj) and CREDIT return, not CPU bound.

## Known issues (ranked)

1. **Background network block (Android 15+/16).** The 60 s grace period in `ConnectionManagerImpl`
   cannot hold: the platform cuts network for backgrounded apps after ~5 s (screen lock or HOME).
   Fix needs a product decision: e.g. a `shortService` foreground service started on ON_STOP while
   terminals are open (exempt from the background chain), or accept and shorten the grace period.
2. **Flood throughput** ~70 KB/s through the phone (see above); investigate the sshj receive
   window / window-adjust timing and CREDIT round trips on a physical device.
3. Exit banner shows "Exited (code N)" for killed terminals; the core's `reason` is not used.
4. The host header counts hidden finished terminals ("4 terminals" with 2 rows).
5. The device key picker shows the alias (`key-<uuid>`) instead of the label.
6. The New terminal FAB has no accessible label in the UI dump.

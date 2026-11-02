# End-to-end smoke journey (emulator + real `hc` over SSH)

The journey that runs the debug app against a real herdr-core on the developer's machine, through a
throwaway SSH server. Use it after changes to sessions, the terminal screen, navigation or lifecycle
handling. Most steps are automated as Kotlin androidTests (`:app`, package
`com.mtajchert.flocket.journey`); this file is the index of what is automated, the safety rules, and
the steps that stay manual. How to run them: [TESTING.md](../TESTING.md#device-journeys).

| Step | Automated by | Status |
|---|---|---|
| 1-2 Onboarding, key, host, trust | `OnboardingJourneyTest` | automated, `@Smoke` |
| 3-4 Live list, new terminal, typing | `TerminalJourneyTest` | automated, `@Smoke` |
| 5 Open fits the phone; another viewer keeps the size until the first key (ADR-0007) | `PassiveOpenJourneyTest` | automated, `@Smoke` |
| 6a Rotation: owner follows the grid, passive keeps the other viewer's size, list survives | `RotationJourneyTest` | automated |
| 6b Fold / unfold | - | manual (the `pixel` AVD is not foldable) |
| 6c' Lock / unlock with a terminal open | `LockJourneyTest` | automated |
| 6d / 6h Background and return (keep-alive on), 6j from the list, 6k keep-alive off | `BackgroundJourneyTest` | automated (short dwell; the > 60 s and ~3 min window cases 6d/6i stay manual) |
| 6e Network off / on, keys typed offline not replayed | `NetworkJourneyTest` | automated |
| 6f Process death | - | manual: `am instrument` force-stops the app when it starts and ends, which drops the saved state; needs a separate UiAutomator driver module |
| 6g sshd link killed, keys typed while down not replayed | `SshLinkJourneyTest` | automated |
| 7 Smoothness (flood, gfxinfo) | - | manual |
| 8 Kill, exit banner, Remove | `TerminalJourneyTest` | automated |
| 9 Full screen: hiding the top bar gives the terminal its rows, the corner button gives them back | `FullScreenJourneyTest` | automated |

## Prerequisites

- macOS or Linux with `hc` installed (here `~/.local/bin/hc`; the app must find it by discovery,
  so leave "Path to hc" empty).
- OpenSSH `sshd` binary (it is run unprivileged; the system sshd and `~/.ssh` are never touched).
- An AVD (here `pixel`, `sdk_gphone64_arm64`, Android 16 / API 36, `BE2A.250530.026.F3`).

## Safety rules

- The app's host uses the hc session **`flocket-e2e`**. On the machine, only ever run
  `HC_SESSION=flocket-e2e TMPDIR=/tmp hc …` (and `--no-parent` for `hc run`, so a terminal you run
  from inside your own herdr terminal is not parented to it). Never touch the default session.
- Start your own emulator on a fixed port and address it with `adb -s emulator-5580` only. Another
  project may already hold that port: check the AVD (`adb -s emulator-5580 shell getprop
  ro.boot.qemu.avd_name` must print `pixel`) before using or killing it. Don't
  run Gradle `connected*` tasks while other devices are attached (they install on all of them).
- `dev-sshd.sh stop` only stops the listener: open connections survive. To cut the app's link,
  kill only the `sshd-session` whose socket is `127.0.0.1:2222` (`lsof -nP -a -p <pid> -iTCP`), never
  sessions of the system sshd.

## Manual setup

`scripts/e2e-device.sh up` boots the emulator, starts the sshd and installs the app; then use `hc`,
`shot` and `down` (usage: run it without arguments). For the manual steps, onboard by hand: generate
the key, copy the `ssh-ed25519 ...` line into `scripts/dev-sshd.sh authorize "<key>"`, add the host
(`10.0.2.2`, `id -un`, Advanced: the harness port and hc session) and compare the trust dialog's
fingerprint with `ssh-keygen -lf "$FLOCKET_SSHD_DIR/host_ed25519.pub"`. Keyboards are disabled by the
harness (Gboard reorders keys injected by `adb shell input`). To test the notification prompt itself,
revoke it with `adb -s <serial> shell pm revoke com.mtajchert.flocket.debug android.permission.POST_NOTIFICATIONS`.

## Journey and expected results (what the automated journeys encode)

1. Fresh install: onboarding welcome, "What you'll need", device key.
2. Generate key: the screen shows the `echo '<pubkey>' >> ~/.ssh/authorized_keys` line; copy the key
   from the UI dump into `dev-sshd.sh authorize`. Add host: name, `10.0.2.2`, username `id -un`,
   device key, Advanced: port 2222, hc session `flocket-e2e`, hc path empty. Save and connect.
   Expected: trust dialog with the same SHA-256 fingerprint as `ssh-keygen -lf`; Trust → the
   Agents list with the host **Online**.
3. `hc run --bg --no-parent --name macsh --cwd /tmp sh` on the machine: the row appears live. New
   agent (FAB) → Default shell, cwd `~` → Start: the terminal screen opens as owner (the core
   size becomes the phone grid, e.g. 52x39).
4. Type `echo hello-flocket` + Enter; `hc read <t>` shows it. `top` redraws; sticky Ctrl + `c`
   from the extra keys stops it.
5. Open `macsh` (headless 200x120) from the list: since ADR-0007 the phone takes the size at once
   (`hc get` shows the phone grid). When another viewer takes it (`hc attach` on the Mac), the phone
   goes passive and leaves that size until its first key, tap or return.
6. Lifecycle: see the matrix. With a terminal open, leaving the app starts the `shortService`
   keep-alive (ADR-0006): `adb shell dumpsys activity services com.mtajchert.flocket.debug` shows
   `TerminalKeepAliveService` with `isForeground=true types=0x00000800`, and `dumpsys netpolicy`
   shows the uid with `procState=FGS … effective=NONE` (not blocked). The first terminal screen asks
   once for the notification permission ("Allow notifications?" → system dialog). Watch `grep -c "Accepted publickey" $FLOCKET_SSHD_DIR/sshd.log` for
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

## Results, 2026-11-01 (ADR-0006 background keep-alive)

Debug build of `feat/background-service`; same AVD (API 36). Owner terminal `t7` (52x28 on the phone,
200x120 headless). Reconnects counted with `grep -c "Accepted publickey" sshd.log`.

| Case | Result | Evidence |
|---|---|---|
| Notification permission prompt | PASS | Rationale dialog on the first terminal open, Continue → system dialog → Allow; not shown again. |
| 6c' Lock 30 s → unlock | PASS | FGS start allowed (`code:ACTIVITY_VISIBILITY_GRACE_PERIOD`, from ProcessLifecycle ON_STOP); `types=0x800`; `InetDiagMessage` destroyed other uids' sockets, not ours; `hc get` stayed 52x28 (owner kept); 0 new SSH accepts; service stopped on unlock. |
| 6h HOME → Settings 60 s → back | PASS | netpolicy `procState=FGS blocked=APP_BACKGROUND … effective=NONE`; notification "Flocket connected · 1 terminal"; instant return, typing works, 0 new accepts. |
| 6i Background > 3 min | PASS | At 170 s: `ConnectionManager: detaching 1 session(s): window over`, service and notification gone, core size back to 200x120 (viewer left), uid then `effective=APP_BACKGROUND`. No `onTimeout`/ANR. Return: 1 reconnect, passive attach, input works. |
| 6j Background from the list (no terminal) | PASS | `detaching … background, no terminal open` at once, no FGS; return reconnects (1 accept). |
| 6k Setting "Keep connected in background" off, terminal open, HOME | PASS | `detaching … keep-alive off` at once; no FGS, no notification. |

## Known issues (ranked)

1. ~~Background network block (Android 15+/16).~~ Fixed by ADR-0006 (see the results above). With
   only the list open, a lock still detaches and the unlock reconnects (by design).
2. **Flood throughput** ~70 KB/s through the phone (see above); investigate the sshj receive
   window / window-adjust timing and CREDIT round trips on a physical device.
3. ~~Exit banner reason~~, ~~host header count~~ ("1 shown · 6 total"), ~~key alias in pickers~~
   (label from the public-key comment) and ~~unlabelled FABs~~: fixed on `feat/background-service`.

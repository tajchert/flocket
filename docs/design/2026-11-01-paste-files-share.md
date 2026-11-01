# Paste into a terminal, send files, and agent shares — design

Status: accepted for implementation · Date: 2026-11-01 · Scope: Android (the protocol
parts apply to every platform)

## 1. Intent

From the phone you want to hand things to an agent and get things back from it:

- **Paste into the current terminal session** is the common case: paste text, or an
  image or file (from the clipboard or picked on the phone) straight into Claude Code,
  Codex or a shell, as if you had pasted it on the desktop.
- **Send a file into the session's folder** is the less common case: put a file next to
  the work, under a name you choose.
- **An agent shows or sends you a file.** You tell your agents (with a short instruction)
  "to show me a file, run `flocket-share <path>`". The file then shows up in the app on
  that terminal, where you can preview it (image or text) and save or share it on the
  phone.

Everything goes through herdr-core's own methods over the existing SSH connection. There
is no new server.

## 2. Goals, non-goals, anti-goals

**Goals**

1. Paste an image or file into a terminal: upload with `terminal.put`, then paste the
   returned path with the terminal's normal (bracketed) paste. No Enter is pressed.
2. Text paste keeps working as today, and image paste is offered from the same place.
3. Send a file into the terminal's current folder with `file.write`.
4. A host-side `flocket-share` helper and a `flocket-share` herdr document, specified in
   `protocol/SPEC.md`. The app shows shares on their terminal, previews images and text,
   and saves or shares any shared file.
5. The app can install the helper on a host (and on its federated machines) and shows the
   agent instruction to copy.

**Non-goals (later)**

- Files over 8 MB (herdr-core's limit for `terminal.put`, `file.write` and
  `machine.exec` stdout).
- A file browser, and tappable paths in terminal output.
- Flocket in the Android share sheet (share-in).
- Push notifications for shares while the app is closed.
- iOS.

**Anti-goals (a failure even if it works)**

- The terminal stutters or stalls while a file transfers.
- An upload or write is sent twice after a dropped connection, or a path is pasted for an
  upload whose outcome is unknown.
- The app downloads shared files the user did not open.
- A share from `rohan/t3` lands on local `t3`, or the reverse.

## 3. Constraints and facts (verified against `hc 0.1.16`)

- `terminal.put {terminal, name, data(base64, ≤ 8 MB raw)}` → `{path, bytes}`. The file
  lands in `<state>/files/<tN>/` on the terminal's machine and is removed with the
  terminal. The name is sanitised (`my shot.png` → `my_shot.png`), which makes the path
  safe to paste. It accepts a federated address (`rohan/t7`).
- `file.write {path, data(base64, ≤ 8 MB), cwd?, on?}` writes atomically and creates
  missing folders. It **overwrites**.
- `file.read` is **text only** (it returns lines). Binary downloads use
  `machine.exec {argv:["cat","--",path], on?}`. Stdout comes back as base64, at most
  8 MB, with a `truncated` flag. `machine.exec` is cancelled when the caller's connection
  goes away.
- Terminals get `HC_TERMINAL_ID` (for example `t1`) and `HC_SESSION` in their
  environment, so `hc connect` from inside a terminal reaches the right core.
- Plain-mode `hc connect` works one-shot (`printf '<json>\n' | hc connect`) for
  `doc.get` and `doc.set`. A stale `version` gives a `conflict` error.
- Documents: name rules `[a-z0-9-_.]{1,32}`, ≤ 1 MiB, compare-and-set on `version`, one
  set of documents per machine, `doc` events carry `machine` for remote ones
  (SPEC §9).
- An 8 MB file is about 10.7 MB of base64 in a single frame (MAX_PAYLOAD is 16 MiB).
  Sent on the terminal's connection it would block keystrokes, terminal output and other
  replies for the length of the transfer (seconds on LAN, minutes on a slow mobile link),
  and the 15 s read timeouts would fire.
- Product rules that bind this work: mutations at most once (rule 3), addresses opaque
  and federated (rule 5), writes disabled while stale (rule 7).

## 4. Decisions

### 4.1 Transfers use their own connection

Uploads (`terminal.put`, `file.write`) and downloads (`machine.exec cat`) run on a
**separate, short-lived core connection**: a second `hc connect` exec channel on the
host's existing SSH session (`SshHostConnector` already opens extra execs). SSH channel
flow control interleaves it with the terminal's channel, so the terminal stays live. The
transfer connection opens on demand and closes after an idle period. A transfer timeout
scales with size (it is not the 15 s read timeout).

`terminal.put` and `file.write` are mutations and are **never retried**. A connection lost
after the request was written gives `OutcomeUnknown`. The download (`cat`) is a read and
may be retried once.

### 4.2 Paste into a terminal

- The terminal screen's Paste action becomes a small chooser when an image or file is
  possible: **Paste text** (as today), **Paste image from clipboard** (only when the
  clipboard holds an image), **Choose photo** (system photo picker, no permission) and
  **Choose file** (system document picker). Ctrl+V and the two-finger tap keep pasting
  text directly. When the clipboard holds an image and no text, they paste the image.
- Image or file paste: check the size (≤ 8 MB, otherwise say so and stop), upload with
  `terminal.put` to the terminal's full address, then paste `<path> ` (path plus one space)
  through the existing `TerminalController.paste`, so bracketed paste applies.
- Names: a clipboard image is named `paste-<yyyyMMdd-HHmmss>.<ext>` from its MIME type. A
  picked file keeps its display name.
- Progress: a small inline indicator on the terminal screen with Cancel while uploading.
  Cancelling before the request is written is clean. Cancelling after it gives the
  unknown outcome below.
- Outcomes follow the existing send pattern (`TerminalsViewModel.sendText`, `SendPhase`):
  success pastes the path, failure shows the mapped error, and `OutcomeUnknown` shows "may
  not have been uploaded" with a manual **Retry**. **Nothing is pasted** in the unknown
  case.
- Disabled while the session is stale or the terminal is finished.

### 4.3 Send a file to the session's folder

- An overflow action, **Send file to folder…**: pick a file, then a dialog shows the target
  folder (`Terminal.displayCwd`, the foreground process's cwd, on the terminal's machine)
  and an editable name.
- Before writing, check whether the target exists (`machine.exec ["test","-e",path]` with
  `on`). If it does, the dialog asks for an explicit **Replace**.
- Write with `file.write {path, data, on: address.machine}`. Same at-most-once outcomes as
  paste. On success, a message shows the written path. Nothing is typed into the terminal.

### 4.4 Agent shares: the `flocket-share` document and helper

**The document** (add to `protocol/SPEC.md` as a Flocket convention, next to §9):

- The name is `flocket-share`, one per machine. The value is
  `{"v":1,"shares":[<entry>, …]}`, newest first.
- An entry is `{"id":string, "terminal":string, "paths":[string,…], "note"?:string,
  "at":u64 unix seconds}`.
  - `terminal` is the writer's `HC_TERMINAL_ID`, a machine-local id. The app builds the
    address from the machine the document lives on, for example `rohan` + `t3` →
    `rohan/t3`.
  - Paths are absolute on that machine.
- Readers ignore unknown fields and entries they can't parse, and tolerate a missing or
  `null` document.
- Writers prepend the new entry with compare-and-set, and re-read and retry on
  `conflict`. When the serialised value would exceed 64 KiB, the writer starts a fresh
  list containing only the new entry. The app keeps its own history, so dropping old
  entries is harmless.

**The helper** `flocket-share [-m NOTE] PATH…`:

- POSIX `sh` plus standard tools (`sed`, `od`, `date`); no `jq` or `python`. It runs only
  inside a herdr terminal: without `HC_TERMINAL_ID` it exits non-zero with a clear
  message.
- Sandboxed agents (Codex's default sandbox, Claude Code's sandbox mode) may be blocked
  from the core's socket. Then the helper exits non-zero and prints a message that tells
  the agent to rerun the command outside the sandbox. Both tools already ask the user to
  approve that. There is no fallback channel (decided by the user).
- It checks that each path exists and is a regular readable file, makes it absolute, and
  warns (but still shares) when a file is over 8 MB.
- It talks to the core with plain-mode `hc connect` (`$HC_BIN`, else `hc`, else
  `herdr-core`) and prints `shared <n> file(s) to Flocket`.
- It does not depend on hc's JSON key order beyond what `hc 0.1.16` is verified to
  produce, and a protocol fixture records that reply shape.
- The canonical source is `protocol/tools/flocket-share`, with a test script in
  `protocol/tools/` that runs against a real isolated `hc` session
  (`flocket-test-<random>`, skipped when `hc` is absent, always stopped). The Android
  build bundles the same file as an asset; there is no second copy in the repo.
- `protocol/tools/README.md` explains manual install and contains the canonical **agent
  instruction text**, which the app shows too.

**The app side:**

- `CoreSession` exposes shares: it fetches `flocket-share` after subscribe, reconnect,
  `missed` and every `doc` event with that name (including remote machines, with `on`).
  Today `CoreSessionImpl` ignores doc events other than `herdr-app`.
- "Seen" state is stored per host, machine and entry id, and survives process death.
  - New, unseen shares for the open terminal appear as a card on the terminal screen,
    for example "Shared report.png" or "Shared 3 files", with the note if one was given.
  - An overflow item **Shared files** lists that terminal's recent shares.
  - The terminals list may show a small marker on rows with unseen shares (builder's
    call).
- Opening a share shows its full path and the machine, and downloads only on that tap. It
  first checks the size (`machine.exec` with `wc -c`) and refuses files over 8 MB with a
  clear message. A `truncated` result counts as a failure, never a silently partial file.
- Preview:
  - Images use `ImageDecoder`, downsampled to the screen, with no new image library unless
    the builder finds one necessary.
  - Text (UTF-8, by MIME type or extension, up to 1 MB shown) uses a monospace scrollable
    view.
  - Anything else shows name and size only.
- **Save** (the system "create document" picker) and **Share** (a `FileProvider` in the
  app plus `ACTION_SEND`) are available for every file. Downloaded bytes go to the app's
  cache and are cleared on a schedule the builder picks.
- A share whose terminal is gone still opens (the file may still exist). A missing file
  gives a clear "file no longer exists" message.

### 4.5 Installing the helper and the agent instruction

- The host screen (or host settings) gets **File sharing from agents**:
  - It shows whether `flocket-share` is installed on the host and on each federated
    machine (`machine.exec ["sh","-c","command -v flocket-share"]` with `on`).
  - **Install** writes the bundled helper to `~/.local/bin/flocket-share` with
    `file.write` and `on`, then `machine.exec chmod +x`. It warns when `~/.local/bin` is
    not on the login shell's PATH and shows the full path to use instead.
  - It shows the agent instruction with **Copy**, for the user to paste into their
    `AGENTS.md` or `CLAUDE.md`.
- Installing is an explicit user action, never automatic.

### 4.6 Calls made by the design author (review these)

- **Federated transfers.** Transfers to `rohan/…` terminals go through the local core's
  link to rohan, which the app does not control. Accept this for v1. A real-core test
  measures whether a remote 8 MB transfer stalls other `rohan/*` terminals, and the
  result is written up in the ADR. If they do stall, a later change lowers the cap for
  remote machines.
- **Seen state and duplicates.** An entry `id` is random and globally unique, at least
  64 random bits. Seen state and history are keyed by entry id alone, so a machine
  reachable both directly and through federation shows each share once.
- **First read of a machine's document** (fresh install, new host, newly federated
  machine): entries with `at` older than 10 minutes are marked seen silently. Newer ones
  show as new.
- **ADR.** The transfer connection departs from SPEC §1's "one connection carries
  everything", and `flocket-share` is a cross-platform convention. Both are recorded in a
  new ADR (`docs/decisions/0007-…`) alongside the SPEC change.

## 5. What is left to the builder

- Exact UI placement and copy, within the existing terminal screen patterns
  (`OverflowMenu`, `TerminalActions`, dialogs in the style of `TerminalDialogs.kt`) and the
  UI guide.
- Module placement, within the module rules in `android/AGENTS.md` (Android URI and
  ContentResolver code stays out of the pure JVM modules).
- Where "seen" state and history live (DataStore or Room), and the cache clean-up policy.
- Whether the share card also offers a quick **Paste path into terminal**.

## 6. Verification

- Unit tests for every new protocol call against `FakeCore`, the at-most-once outcomes
  (dropped after write → `OutcomeUnknown`, nothing pasted), federated address and `on`
  handling, share parsing (unknown fields, bad entries, null document, version going
  backwards).
- Real-core integration tests (isolated session): `terminal.put` round trip, `file.write`
  then download, the helper writing a share that the session surfaces.
- Screenshot tests for the new dialogs and the share card.
- A manual check with Claude Code and Codex that a pasted image path becomes an image
  attachment. If one of them doesn't attach it, the path is still pasted, and the
  difference is noted in the user docs.

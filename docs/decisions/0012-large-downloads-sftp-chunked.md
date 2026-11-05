# ADR-0012: Large downloads use SFTP on the host link, chunked `machine.exec` elsewhere

Status: accepted (2026-11-05)

## Context
Agents share files with the phone (ADR-0011), but a download is one `machine.exec cat`
whose stdout `hc` caps at 8 MB, held in memory as one base64 frame. Debug APKs and build
artifacts are often larger. We want files up to 200 MiB, with progress and cancel, written
to disk as they arrive. Design: [`docs/design/2026-11-05-large-downloads-apk-install.md`](../design/2026-11-05-large-downloads-apk-install.md).

Measured for 200 MiB (see Consequences): SFTP is about 1.6× faster than 4 MiB chunks over
`machine.exec` on a loopback link (no base64, no per-call wait), with flat memory. Chunks
are the only option for federated machines, whose files the SSH host cannot reach.

## Decision
1. **Files on the host's own machine download over SFTP**, on a new channel of the SSH
   session the app already holds (ADR-0005). The app reads only paths that the core gave
   it (agent shares). SFTP is a file read on a transport we already trust, not a second
   source of state, so the core still owns runtime (product rule 2).
2. **Everything else uses chunked reads over `machine.exec`** on the transfer connection
   (ADR-0011): `stat`, then `dd` slices of 4 MiB on the local machine and 1 MiB on a
   federated machine, so a stall on the core's link to that machine lasts about one slice.
   The convention is in SPEC §4.20.
3. **SFTP falls back to chunks** when the host refuses the subsystem, or SFTP cannot see or
   read the file (macOS privacy rules apply to `sftp-server` but not to `hc`). A session
   that saw the subsystem refused does not try SFTP again until it reconnects.
4. **Integrity**: a download is complete only when the byte count equals the size and a
   final stat matches the first (size and mtime). Otherwise it fails as `FileChanged`.
   A `truncated` or short chunk is a failure, never a partial file.
5. **Downloads are reads**: they may retry and resume (rule 3 covers mutations only).
   Uploads keep the 8 MB cap.
6. **Limit 200 MiB** (`MAX_DOWNLOAD_BYTES`); the `flocket-share` helper warns above it.

## Consequences
- `core:protocol` gains a `DirectFileSource` port; `core:ssh` implements it with sshj SFTP;
  `core:data`'s SSH transport exposes it. Non-SSH transports (tests, a future relay) simply
  don't implement it and get chunks.
- A second code path to test: the fake core learns `stat`/`dd`, and an in-process MINA SFTP
  server tests the reader.
- Downloads do not survive leaving the terminal screen or process death, and stop when the
  short service (ADR-0006) ends; they can be resumed while the screen lives.
- Measurements (2026-11-05, `hc` 0.1.18, 200 MiB of random bytes, M-series Mac). The device
  rows run `DownloadJourneyTest#speedOf200MiB*` on an API 36 emulator through the dev sshd
  (loopback, SFTP via `internal-sftp`; the chunked row comments the subsystem out), timed
  from Open to Done including the SHA-256-checked write to the phone's cache. The rohan rows
  run `TransferRealCoreIntegrationTest` (`FLOCKET_SLOW=1`, JVM, real core, `hc machine add
  --via`) with tickers on rohan and locally and a typed-into `cat` on rohan, as in ADR-0011 §4.

  | Link | Method | Duration | MB/s | Terminal echo during the transfer |
  |---|---|---|---|---|
  | emulator to dev sshd (loopback) | SFTP | 6.6 s | 31.7 | local terminal echo 74 ms (journey `terminalKeepsEchoingDuringDownload`) |
  | emulator to dev sshd (loopback) | chunked, 4 MiB | 10.6 s | 19.7 | not measured (same SSH session as the terminal) |
  | rohan, pipe (`sh -c`) | chunked, 1 MiB | 3.6 s | 58.0 | rohan 27 ms, output gap 43 ms (baseline 42 ms); local gap 62 ms |
  | rohan, 10 Mbit/s each way | chunked, 1 MiB | 294 s | 0.71 | rohan echo 1.4 s, longest rohan output gap 1.5 s; local gap 64 ms |

  Result: SFTP is about 1.6× faster than chunks locally. With 1 MiB slices the stall that
  ADR-0011 §4 measured for a single 8 MiB frame (12 s) drops to about 1.5 s on a 10 Mbit/s
  link, since each slice crosses the core's link on its own and other frames get in between.
  The slow link moves 0.71 MB/s (a link of 1.25 MB/s carrying base64 plus a `dd` round trip per
  slice), so 200 MiB takes about 5 minutes there. Local terminals were never affected.

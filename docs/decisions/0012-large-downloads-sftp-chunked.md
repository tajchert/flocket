# ADR-0012: Large downloads use SFTP on the host link, chunked `machine.exec` elsewhere

Status: accepted (2026-11-05)

## Context
Agents share files with the phone (ADR-0011), but a download is one `machine.exec cat`
whose stdout `hc` caps at 8 MB, held in memory as one base64 frame. Debug APKs and build
artifacts are often larger. We want files up to 200 MiB, with progress and cancel, written
to disk as they arrive. Design: [`docs/design/2026-11-05-large-downloads-apk-install.md`](../design/2026-11-05-large-downloads-apk-install.md).

Estimated for 200 MiB (to be replaced by measurements): SFTP is about 1.3–1.4× faster than
4 MiB chunks over `machine.exec` on typical links (no base64, no per-call wait), with flat
memory. Chunks are the only option for federated machines, whose files the SSH host
cannot reach.

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
- Measurements: to be added here after implementation.

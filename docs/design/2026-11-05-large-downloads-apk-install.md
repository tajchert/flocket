# Large downloads (up to 200 MB) and APK install — design

Status: accepted (2026-11-05). Decision record: [ADR-0012](../decisions/0012-large-downloads-sftp-chunked.md).
Builds on [`2026-11-01-paste-files-share.md`](2026-11-01-paste-files-share.md) and
[ADR-0011](../decisions/0011-transfer-connection-and-agent-shares.md).

## 1. Intent

Agents build things on the laptop that the user wants on the phone, most often an APK.
Today a shared file over 8 MB is refused, and a debug APK is usually bigger. The user wants
to open a shared file of up to 200 MB, watch it download, cancel it, keep it on the phone,
and, when it is an APK, install it with one confirmation.

## 2. Goals, non-goals, anti-goals

Goals
- Download a shared file of up to **200 MiB** (209 715 200 bytes) from the laptop to the phone.
- **Fast path: SFTP** on the existing SSH session, for files on the host's own machine.
- **Fallback: chunked `machine.exec`** for federated machines (`rohan/…`), for hosts without
  SFTP, and when SFTP cannot read the file.
- **Progress** (bytes and percent) and **Cancel**.
- **Streamed to disk**, never held whole in memory.
- **APK**: when a downloaded file is an APK, ask the user whether to install it. If Flocket
  may not install apps yet, explain and send them to the system setting, then continue.

Non-goals
- Phone → laptop. Uploads (`terminal.put`, `file.write`) keep the 8 MB cap.
- Downloads that survive leaving the terminal screen or process death. No new foreground
  service. The existing short service (ADR-0006) keeps the link up about 3 minutes in the
  background; after that the download fails and can be resumed.
- Browsing the host's file system. Only paths an agent shared are downloaded.
- Silent installs or auto-updates.

Anti-goals (a failure even if it works)
- A download that freezes terminals on the same host. Both paths run on their own SSH
  channel; terminals must keep printing and echoing.
- A corrupt or half file presented as complete (truncated chunk, file rewritten mid-download).
- Memory growing with file size (an out-of-memory kill at 150 MB).
- A federated download that stalls rohan's terminals for many seconds (ADR-0011 §4).

## 3. Constraints and facts

- `hc` caps one `machine.exec` stdout at 8 MB and reports `truncated` (SPEC §4.20). Frames are
  base64 JSON (about 33% larger).
- The host link is direct SSH via sshj 0.41.2 (ADR-0005). One `SSHClient` per host; the main
  `hc connect` and the transfer connection are exec channels on it
  (`core/data/.../SshHostConnector.kt`, `ClosingTransport` is a `SiblingTransportSource`).
  sshj ships `SFTPClient`; the local window is 2 MiB and the max packet 32 KiB.
- `core:session`, `core:ssh`, `core:protocol` are pure JVM. `core:session` sees only
  `ByteTransport`; capabilities of the link are discovered with interfaces on the transport
  (the `SiblingTransportSource` pattern).
- macOS: `sftp-server` runs under sshd, so files under `~/Desktop`, `~/Documents`,
  `~/Downloads` can be denied unless "Allow full disk access for remote users" is on. `hc`
  (started from a terminal) may read them. So SFTP "permission denied" must fall back.
- minSdk 29. Flocket ships as a sideloaded APK (GitHub releases), not on Play, so
  `REQUEST_INSTALL_PACKAGES` needs no Play declaration.
- Existing viewer code holds the file as `ByteArray` (`FileLoad.Loaded.bytes`) and writes it
  to `cacheDir/shares/<share id>/<name>` only to share it out
  (`feature/terminal/impl/.../share/`).

## 4. Decisions

### 4.1 Where the code goes

| Layer | Change |
|---|---|
| `core:protocol` (`transport/DirectFileSource.kt`, new) | Port: `DirectFileSource`, `DirectFileReader`, `RemoteFileStat`, `DirectFileException`. |
| `core:ssh` (`SshSftp.kt`, new; `SshSession`) | `SshSession.openFileReader()`: an sshj `SFTPClient` on the same `SSHClient`, implementing `DirectFileReader`. |
| `core:data` (`SshHostConnector.kt`) | `SshLink` gains `openFileReader()`; `ClosingTransport` also implements `DirectFileSource`. Readers close with the link. |
| `core:model` (`Transfers.kt`) | `MAX_DOWNLOAD_BYTES = 200L * 1024 * 1024`. `MAX_TRANSFER_BYTES` stays 8 MiB for uploads. |
| `core:common` (`AppError`) | New case `FileChanged(path)`: the file changed while it was downloaded. |
| `core:session` (`Downloader.kt`, new; `CoreSession`) | `download(...)`: picks SFTP or chunked, streams to a file, progress, resume, change detection. `readFile` stays for small files (unchanged). |
| `feature:terminal:impl` (`share/`) | File-backed viewer, progress + cancel, resume, previews from file, Save/Share from file, cache pruning, APK prompt and install. |
| `protocol/` | SPEC §4.20 chunked-read convention, §9.2 size note; `flocket-share` warns above 200 MB; agent instruction text. |

### 4.2 The port (`core:protocol`, `transport` package)

```kotlin
/** A transport whose link can read files of the machine `hc connect` runs on directly (SFTP). */
interface DirectFileSource {
    /** Opens a reader on a new channel. Throws DirectFileException(UNAVAILABLE) when the host refuses SFTP. */
    suspend fun openFileReader(): DirectFileReader
}

interface DirectFileReader {
    /** null when [path] does not exist. Throws DirectFileException(DENIED) when not readable. */
    suspend fun stat(path: String): RemoteFileStat?

    /**
     * Copies [path] from byte [offset] to its end into [out], calling [onBytes] with each block's size.
     * Cancelling the caller closes the remote handle promptly. Returns the bytes copied.
     * Throws DirectFileException (DENIED, MISSING, IO) or TransportException.
     */
    suspend fun copyTo(path: String, offset: Long, out: java.io.OutputStream, onBytes: (Int) -> Unit): Long

    /** Closes the SFTP channel. Idempotent, safe from a cancelled coroutine. */
    suspend fun close()
}

data class RemoteFileStat(val size: Long, val mtimeSeconds: Long, val isRegularFile: Boolean)

class DirectFileException(val reason: Reason, message: String, cause: Throwable? = null) : java.io.IOException(message, cause) {
    enum class Reason { UNAVAILABLE, DENIED, MISSING, IO }
}
```

`onBytes` is a low-level port callback, not public `CoreSession` API (which uses `Flow`).

### 4.3 SFTP reader (`core:ssh`)

- `SshSession.openFileReader()` calls `client.newSFTPClient()` on `Dispatchers.IO`. A refused
  subsystem or a failed channel open → `UNAVAILABLE`.
- `stat`: `SFTPClient.statExistence` / `stat`; `NO_SUCH_FILE` → null; `PERMISSION_DENIED` → `DENIED`.
- `copyTo`: open `RemoteFile` for READ (`PERMISSION_DENIED` → `DENIED`), read with
  `ReadAheadRemoteFileInputStream(maxUnconfirmedReads = 64, fileOffset = offset)` (64 × 32 KiB
  = 2 MiB in flight, matching the window), write to `out` in 64 KiB blocks, call `onBytes`, check
  cancellation between blocks. On cancellation close the stream and the `RemoteFile` with
  `NonCancellable`.
- Never logs paths' contents or file bytes; may log sizes and timings.
- Tests: in-process Apache MINA server (`TestSshServer.kt` already exists) plus
  `org.apache.sshd:sshd-sftp` (same version as `mina-sshd`, test-only): full copy, copy from an
  offset, missing file, denied file, server without SFTP subsystem, cancel mid-copy closes the
  handle (server sees close), 50 MB copy completes with flat heap (no `ByteArray` of file size).

### 4.4 Download engine (`core:session`)

Public API on `CoreSession`:

```kotlin
/**
 * Streams [path] on [machine] (null = the host's machine) into [target], creating or truncating it, or
 * appending when [resume] still matches the remote file. Cold flow: collecting starts it, cancelling the
 * collector stops it (the partial file is left as it is; the caller deletes or keeps it). Never throws.
 */
fun download(machine: String?, path: String, target: java.io.File, resume: DownloadResume? = null): Flow<DownloadEvent>

sealed interface DownloadEvent {
    data class Started(val size: Long, val method: DownloadMethod, val resume: DownloadResume) : DownloadEvent
    data class Progress(val bytes: Long, val size: Long) : DownloadEvent
    data class Done(val size: Long, val method: DownloadMethod) : DownloadEvent
    /** [resume] is non-null when the partial file can be continued with it. */
    data class Failed(val error: AppError, val resume: DownloadResume?) : DownloadEvent
}

enum class DownloadMethod { SFTP, CHUNKED }

/** Identity of the remote file at the start; a resume is valid only while size and mtime match. */
data class DownloadResume(val machine: String?, val path: String, val size: Long, val mtimeSeconds: Long)
```

Rules
- Requires Online and not stale, like other transfers. Reads, so retry is allowed (rule 3).
- **Method choice**:
  1. `machine != null` → CHUNKED.
  2. The main transport is not a `DirectFileSource`, or this session already saw `UNAVAILABLE`
     (remembered until the session reconnects) → CHUNKED.
  3. Otherwise SFTP: `openFileReader()`, `stat`. `UNAVAILABLE`, `DENIED`, or `null` (missing:
     the SFTP view may differ from the core's) → CHUNKED, from the same offset.
  4. CHUNKED then decides: missing → `FileMissing(path)`.
- **Size**: `size > MAX_DOWNLOAD_BYTES` → `Failed(FileTooLarge(MAX_DOWNLOAD_BYTES), null)`.
  Not a regular file → `Failed(InvalidInput("file", "not a regular file"), null)`.
- **Free space**: `target.parentFile.usableSpace < size - existing + 32 MiB` →
  `Failed(Storage("not enough space"), null)`.
- **Resume**: when [resume] equals the fresh stat (machine, path, size, mtime) and `target`
  exists, continue at `target.length()` (CHUNKED: rounded down to 64 KiB, truncating `target`).
  Otherwise start at 0. `Started.resume` always carries the fresh stat.
- **Change detection**: after the last byte, stat again. Size or mtime differ from `Started`, or
  bytes written ≠ size → `Failed(FileChanged(path), null)` (no resume: start over).
- **Progress**: emit at most every 100 ms or 1 MiB, plus one at the end. Emits from the IO
  dispatcher are fine; the collector conflates.
- **Failures mid-file** (link drop, timeout, I/O) → `Failed(error, resume)` with the token from
  `Started`, so the caller can offer Resume.
- **SFTP channel lifetime**: one reader per download, closed at the end. No sharing with
  `TransferChannel`.

Chunked protocol (also written into SPEC §4.20, on the transfer connection):
- Stat: `machine.exec {argv: ["sh", "-c", STAT, "sh", path], on}` where
  `STAT = 'stat -c "%s %Y %F" -- "$1" 2>/dev/null || stat -f "%z %m %HT" -- "$1"'`
  (GNU/busybox, then BSD/macOS). Output `"<size> <mtime> <type words>"`; regular when the type
  words lower-cased start with `regular`. Non-zero exit → missing.
- Chunk: `machine.exec {argv: ["dd", "if=" + path, "bs=65536", "skip=" + offset/65536,
  "count=" + chunk/65536], on, timeout}`. Chunk = **4 MiB** when `machine == null`, **1 MiB** for
  federated machines (keeps the federation-link stall per call to about a second, ADR-0011 §4).
  One call at a time.
- Each chunk: `timed_out` → `Timeout`; `truncated` → `Protocol("chunk truncated")`; `code != 0`
  → `FileMissing`; decoded length must equal `min(chunk, size - offset)` except when the file
  shrank, which is `FileChanged`. A chunk that failed with `ConnectionLost` is retried once on a
  freshly opened transfer connection (existing `transferRead`).
- Per-call timeout: `CallTimeouts.transfer(chunkBytes)`.
- `dd` writes its statistics to stderr; ignore stderr on success.

Tests (JVM, `core:session`): `FakeCore` learns `machine.exec` for `sh -c STAT` and `dd` over an
in-memory file; a fake `DirectFileSource` transport. Cases: SFTP happy path; each fallback
trigger (unavailable, denied, missing-in-SFTP-but-present-in-core); remote machine uses 1 MiB
chunks and `on`; too large; not regular; free space; resume valid/invalid; file changed (size
and mtime); truncated chunk; short chunk; timeout; link drop mid-file → `Failed` with resume;
cancel stops further calls. Real-core test (isolated `flocket-test-*` session, skipped without
`hc`): 20 MiB chunked download local and via `rohan` (`hc machine add --via`, like
`TransferRealCoreIntegrationTest`), checksum equal.

### 4.5 Phone side: viewer, files, progress (`feature:terminal:impl`)

- **Files**: download to `cacheDir/shares/<share id>/<name>.part`, rename to `<name>` on `Done`.
  The FileProvider path (`shares`) is unchanged, so Share hands out the file without a copy.
- **Reuse**: the controller remembers, per (machine, path), the `DownloadResume` of finished files.
  Opening the same file again stats it (cheap) and reuses the local copy when size and mtime still
  match. Lost on process death: then it downloads again. *(Design author's call.)*
- **State** (`ShareModels.kt`): `FileLoad.Loading(path, bytes, size?, method?)` (size null until
  `Started`), `FileLoad.Loaded(path, file: File, size: Long)` (no `ByteArray`),
  `FileLoad.Failed(path, error, canResume: Boolean)`.
- **Ownership**: the download belongs to `ShareController` (terminal ViewModel), not to the dialog.
  Closing the viewer keeps it running; the shared-files sheet row shows "Downloading 45%"; tapping
  the share reopens the viewer on the progress. Opening a *different* file cancels the running one
  (today's behaviour). Leaving the terminal screen (ViewModel cleared) cancels. *(Author's call.)*
- **Progress UI**: a determinate bar with "12.3 MB of 180 MB" and the method hidden (debug log only).
  Indeterminate until `Started`. A **Cancel** button: cancels, deletes the `.part`, returns to the
  list (several files) or closes the viewer (one file).
- **Failure**: the error message (existing `toUiError`) plus **Resume** when `canResume`, else
  **Try again**. `FileChanged` says "The file changed on the laptop while downloading. Try again."
- **Previews from the file**: image when `mime` is `image/*` and size ≤ 32 MiB, decoded with
  `ImageDecoder.createSource(file)` and the existing target size; text preview reads the first
  `MAX_TEXT_BYTES` from the file; `SharePreview.classify` takes a prefix (first 64 KiB), not the
  whole file. Everything else shows the existing detail card (name, size, Save, Share).
- **Save** (`CreateDocument`): stream-copies the file on IO; a snackbar "Saving…" then "Saved".
- **Pruning** (`ShareFiles.prune`): keep 24 h as now, plus keep the folder under **1 GiB** total,
  deleting oldest first; never delete a file that is downloading or shown. Stale `.part` files older
  than 24 h go too. *(Author's call.)*
- Strings in `res/values/strings.xml` (and every existing locale file per `docs/LOCALIZATION.md`).
  Screenshot tests for: progress, failed with Resume, APK detail with Install.

### 4.6 APK install

- **Is it an APK**: name ends with `.apk` (any case). After download, read it with
  `PackageManager.getPackageArchiveInfo(path, 0)` (set `applicationInfo.sourceDir/publicSourceDir`
  to read the label). Unreadable → show the normal detail card with "Not a valid app package",
  no Install.
- **Ask**: when an APK download finishes *while the viewer shows it*, show a dialog once:
  "Install <label> <versionName>?" with the package name in small text, buttons **Install** and
  **Not now**. The detail card of an APK always has an **Install** button too.
- **Permission**: if `packageManager.canRequestPackageInstalls()` is false, a dialog: "To install
  apps from your laptop, allow Flocket to install unknown apps." → **Open settings** launches
  `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` with `package:<our package>`. On return (activity
  result), check again: granted → continue the install; not granted → snackbar "Flocket can't
  install apps yet".
- **Install**: `PackageInstaller` session (`MODE_FULL_INSTALL`), stream the file into
  `openWrite("base.apk", 0, size)`, `fsync`, commit with a `PendingIntent.getBroadcast` (explicit,
  `FLAG_MUTABLE`) to a non-exported `ApkInstallReceiver`. Receiver:
  `STATUS_PENDING_USER_ACTION` → start `EXTRA_INTENT` (`FLAG_ACTIVITY_NEW_TASK`); `STATUS_SUCCESS`
  → "Installed <label>"; failures → "Couldn't install: <EXTRA_STATUS_MESSAGE>". Results reach the
  UI through a process-wide `SharedFlow` (`ApkInstallEvents`) and show as a snackbar.
- Android always shows its own confirmation; signature mismatches are reported by the installer.
  Installing a newer Flocket replaces the running app (the process is killed): allowed.
- Manifest (`feature/terminal/impl` manifest, merged): `REQUEST_INSTALL_PACKAGES`, the receiver.
- Code: `share/ApkInstall.kt` (info, permission check, session), `share/ApkInstallReceiver.kt`,
  `share/ApkDialogs.kt`. Unit tests (Robolectric where it supports `PackageInstaller`; otherwise
  test the pure parts: status → message mapping, the "ask once" rule).

### 4.7 Host tool, SPEC, docs

- `protocol/tools/flocket-share`: the size warning moves from 8 MB to 200 MB (209 715 200 bytes);
  the message says Flocket can't open files over 200 MB. The bundled copy in the app stays byte-
  identical (existing test). `test-flocket-share.sh` updated.
- `protocol/tools/agent-instruction.md` and README: "up to 200 MB". Keep bundled copy identical.
- `protocol/SPEC.md`: §1 note (downloads may also use SFTP on the SSH link, ADR-0012), §4.20 the
  chunked-read convention above, §9.2 "over 200 MB … the app refuses".
- `android/docs/TESTING.md` or the files journey doc: the new journey cases.

## 5. Left to the builder

- sshj API details (e.g. `statExistence` vs catching `SFTPException`), exact progress throttling.
- Compose layout of the progress and dialogs, within `docs/UI_GUIDE.md`.
- Whether Robolectric covers `PackageInstaller`; if not, cover it in the device journey.

## 6. Verification

- Unit tests per §4.3–4.6; `scripts/check.sh quick` green; `scripts/check.sh full` before merge
  of the UI stream (lint, release build, screenshots).
- Device journeys (`scripts/e2e-device.sh journey …`, ADR-0009): (a) agent shares a 120 MiB file
  → progress → Done → checksum of the saved file matches; (b) cancel mid-way → `.part` gone;
  (c) a small test APK → prompt → permission granted by
  `appops set <pkg> REQUEST_INSTALL_PACKAGES allow` → installer confirmation appears (tap via
  UiAutomator) → package installed (`pm list packages`); (d) terminal keeps echoing during a
  download (assert on `hc read`).
- Measure on the emulator against the dev sshd: 200 MiB SFTP vs chunked (local), and chunked via
  `rohan` with the 1 MiB chunk on a 10 Mbit/s link; record the table in ADR-0012.

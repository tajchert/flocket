package com.mtajchert.flocket.feature.terminal.impl.transfer

import android.net.Uri
import com.mtajchert.flocket.core.common.error.AppError
import com.mtajchert.flocket.core.common.result.AppResult
import com.mtajchert.flocket.core.model.MAX_TRANSFER_BYTES
import com.mtajchert.flocket.core.model.TerminalAddress
import com.mtajchert.flocket.core.session.CoreSession
import com.mtajchert.flocket.core.ui.error.UiError
import com.mtajchert.flocket.core.ui.error.UiText
import com.mtajchert.flocket.core.ui.error.toUiError
import com.mtajchert.flocket.feature.terminal.impl.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Paste an image or file into one terminal, and send a file to its folder. Owned by the terminal's ViewModel.
 *
 * Uploads are mutations: never retried here. A loss after the request was written is [TransferPhase.OutcomeUnknown],
 * pastes nothing, and waits for the user's manual [retry]. Everything is refused while the session is stale.
 *
 * Known limitation: leaving the terminal mid-upload clears the ViewModel and cancels the upload without
 * reporting its (possibly unknown) outcome; there is no app-level channel for it.
 */
class TerminalTransfers(
    private val scope: CoroutineScope,
    private val address: TerminalAddress,
    private val session: () -> CoreSession?,
    private val uris: UriSource,
    /** Pastes into the terminal (bracketed paste via the controller). */
    private val paste: (String) -> Unit,
    private val notify: (UiText) -> Unit,
    private val now: () -> LocalDateTime = { LocalDateTime.now() },
) {
    private val _phase = MutableStateFlow<TransferPhase>(TransferPhase.Idle)

    /** Progress of the paste upload (the strip above the terminal). */
    val phase: StateFlow<TransferPhase> = _phase

    private val _sendToFolder = MutableStateFlow<SendToFolderState?>(null)

    /** The send-to-folder dialog, null = closed. */
    val sendToFolder: StateFlow<SendToFolderState?> = _sendToFolder

    private var job: Job? = null
    private var retryAction: (suspend () -> Unit)? = null
    private var folderUri: Uri? = null

    private val busy get() = job?.isActive == true

    // ------------------------------------------------------------------ paste

    /** Upload a picked photo or file under its own name, then paste its path. */
    fun pasteUri(uri: Uri) = startPaste { upload(uri, fixedName = null) }

    private fun busyNotice(): Boolean {
        if (!busy) return false
        notify(UiText.Res(R.string.transfer_busy))
        return true
    }

    /** Upload the clipboard's image as `paste-<yyyyMMdd-HHmmss>.<ext>`, then paste its path. */
    fun pasteClipboardImage(
        uri: Uri,
        mime: String?,
    ) {
        val name = "paste-${now().format(STAMP)}.${extensionFor(mime)}"
        startPaste { upload(uri, fixedName = name) }
    }

    /** Runs the last paste again (after an unknown outcome or a failure). Re-reads the same URI. */
    fun retry() {
        val action = retryAction ?: return
        val p = _phase.value
        if (busy || (p !is TransferPhase.OutcomeUnknown && p !is TransferPhase.Failed)) return
        job = scope.launch { action() }
    }

    /** Hides the failure or unknown-outcome strip. */
    fun dismiss() {
        if (busy) return
        retryAction = null
        _phase.value = TransferPhase.Idle
    }

    /** Stops the upload in progress. Once the request may have been written the result is unknown. */
    fun cancel() {
        val folder = _sendToFolder.value
        if (folder != null && folder.phase.isActive()) {
            val running = folder.phase
            job?.cancel()
            job = null
            if (running is TransferPhase.Uploading) {
                _sendToFolder.value = folder.copy(phase = TransferPhase.OutcomeUnknown(running.name))
                recheckAfterUnknown(running.name)
            } else {
                _sendToFolder.value = folder.copy(phase = TransferPhase.Idle)
            }
            return
        }
        val running = _phase.value
        if (!running.isActive()) return
        job?.cancel()
        job = null
        _phase.value =
            if (running is TransferPhase.Uploading) TransferPhase.OutcomeUnknown(running.name) else TransferPhase.Idle
    }

    private fun startPaste(block: suspend () -> Unit) {
        if (busyNotice()) return
        retryAction = block
        job = scope.launch { block() }
    }

    private suspend fun upload(
        uri: Uri,
        fixedName: String?,
    ) {
        val s = readySession()
        if (s == null) {
            _phase.value = TransferPhase.Failed(AppError.ConnectionLost.toUiError())
            return
        }
        _phase.value = TransferPhase.Reading
        val loaded =
            when (val r = load(uri, fixedName)) {
                is AppResult.Success -> r.value
                is AppResult.Failure -> return fail(r.error)
            }
        _phase.value = TransferPhase.Uploading(loaded.name)
        when (val r = s.putFile(address, loaded.name, loaded.bytes)) {
            is AppResult.Success -> {
                _phase.value = TransferPhase.Done(r.value.path)
                retryAction = null
                paste("${escapeForShell(r.value.path)} ")
            }

            is AppResult.Failure -> {
                if (r.error is AppError.OutcomeUnknown) {
                    _phase.value = TransferPhase.OutcomeUnknown(loaded.name)
                } else {
                    fail(r.error)
                }
            }
        }
    }

    private fun fail(error: AppError) {
        _phase.value = TransferPhase.Failed(error.toUiError())
    }

    // ------------------------------------------------------------------ send to folder

    /** Describe the picked file and check its target; opens the dialog. Nothing is written yet. */
    fun prepareSendToFolder(uri: Uri) {
        if (busyNotice()) return
        val action: suspend () -> Unit = { prepare(uri) }
        retryAction = action
        job = scope.launch { action() }
    }

    private suspend fun prepare(uri: Uri) {
        val s = readySession()
        if (s == null) {
            _phase.value = TransferPhase.Failed(AppError.ConnectionLost.toUiError())
            return
        }
        _phase.value = TransferPhase.Reading
        val picked =
            when (val r = uris.describe(uri)) {
                is AppResult.Success -> r.value
                is AppResult.Failure -> return fail(r.error)
            }
        if ((picked.sizeBytes ?: 0L) > MAX_TRANSFER_BYTES) return fail(AppError.FileTooLarge(MAX_TRANSFER_BYTES))
        val folder =
            s.state.value
                .terminal(address)
                ?.displayCwd
        if (folder.isNullOrEmpty()) {
            _phase.value =
                TransferPhase.Failed(
                    UiError(
                        UiText.Res(R.string.transfer_no_folder_title),
                        UiText.Res(R.string.transfer_no_folder_message),
                    ),
                )
            return
        }
        val exists = exists(s, targetPath(folder, picked.displayName))
        folderUri = uri
        retryAction = null
        _phase.value = TransferPhase.Idle
        _sendToFolder.value =
            SendToFolderState(
                name = picked.displayName,
                folder = folder,
                exists = exists == true,
                checkFailed = exists == null,
            )
    }

    /**
     * Writes the picked file as [name] in the folder. Without [replace] an existing target is not
     * touched: the dialog shows the Replace choice instead.
     */
    fun confirmSendToFolder(
        name: String,
        replace: Boolean,
    ) {
        val state = _sendToFolder.value ?: return
        val uri = folderUri ?: return
        if (busyNotice()) return
        job = scope.launch { send(state, uri, name.trim(), replace) }
    }

    private suspend fun send(
        state: SendToFolderState,
        uri: Uri,
        name: String,
        replace: Boolean,
    ) {
        fun update(
            exists: Boolean = state.exists,
            checkFailed: Boolean = state.checkFailed,
            phase: TransferPhase,
        ) = _sendToFolder.update { it?.copy(name = name, exists = exists, checkFailed = checkFailed, phase = phase) }

        fun failed(error: AppError) = update(phase = TransferPhase.Failed(error.toUiError()))
        if (name.isEmpty() || '/' in name || name == "." || name == "..") {
            return failed(AppError.InvalidInput("name", "not a file name"))
        }
        val s = readySession() ?: return failed(AppError.ConnectionLost)
        val target = targetPath(state.folder, name)
        if (!replace) {
            when (exists(s, target)) {
                true -> return update(exists = true, checkFailed = false, phase = TransferPhase.Idle)
                null -> return update(exists = false, checkFailed = true, phase = TransferPhase.Idle)
                false -> update(exists = false, checkFailed = false, phase = TransferPhase.Idle)
            }
        }
        update(phase = TransferPhase.Reading)
        val loaded =
            when (val r = load(uri, name)) {
                is AppResult.Success -> r.value
                is AppResult.Failure -> return failed(r.error)
            }
        update(phase = TransferPhase.Uploading(name))
        when (val r = s.writeFile(address.machine, target, loaded.bytes)) {
            is AppResult.Success -> {
                _sendToFolder.value = null
                folderUri = null
                notify(UiText.Res(R.string.transfer_sent_format, listOf(target)))
            }

            is AppResult.Failure -> {
                if (r.error is AppError.OutcomeUnknown) {
                    update(phase = TransferPhase.OutcomeUnknown(name))
                    recheckAfterUnknown(name)
                } else {
                    failed(r.error)
                }
            }
        }
    }

    /** After an unknown write, re-read the target: the file may well be there, so the next send must say Replace. */
    private fun recheckAfterUnknown(name: String) {
        scope.launch {
            val state = _sendToFolder.value ?: return@launch
            val s = session() ?: return@launch
            val exists = exists(s, targetPath(state.folder, name))
            _sendToFolder.update { it?.copy(name = name, exists = exists == true, checkFailed = exists == null) }
        }
    }

    /** Close the dialog (not while an upload may still be running: use [cancel] first). */
    fun dismissSendToFolder() {
        if (_sendToFolder.value?.phase?.isActive() == true) return
        _sendToFolder.value = null
        folderUri = null
    }

    // ------------------------------------------------------------------ shared

    private class Loaded(
        val name: String,
        val bytes: ByteArray,
    )

    private fun readySession(): CoreSession? = session()?.takeIf { !it.state.value.isStale }

    /** Size is checked before the content is read; the read itself also stops at the cap. */
    private suspend fun load(
        uri: Uri,
        fixedName: String?,
    ): AppResult<Loaded> {
        var name = fixedName
        if (name == null) {
            val picked =
                when (val r = uris.describe(uri)) {
                    is AppResult.Success -> r.value
                    is AppResult.Failure -> return AppResult.Failure(r.error)
                }
            if ((picked.sizeBytes ?: 0L) > MAX_TRANSFER_BYTES) {
                return AppResult.Failure(AppError.FileTooLarge(MAX_TRANSFER_BYTES))
            }
            name = picked.displayName
        }
        return when (val r = uris.read(uri, MAX_TRANSFER_BYTES)) {
            is AppResult.Success -> AppResult.Success(Loaded(name, r.value))
            is AppResult.Failure -> AppResult.Failure(r.error)
        }
    }

    /** `test -e`: 0 = exists, 1 = absent, anything else (or a failed call) = unknown (null). */
    private suspend fun exists(
        s: CoreSession,
        path: String,
    ): Boolean? =
        when (val r = s.exec(address.machine, listOf("test", "-e", path), mutating = false)) {
            is AppResult.Success -> {
                when (r.value.code) {
                    0 -> true
                    1 -> false
                    else -> null
                }
            }

            is AppResult.Failure -> {
                null
            }
        }

    private fun TransferPhase.isActive() = this is TransferPhase.Reading || this is TransferPhase.Uploading

    private fun targetPath(
        folder: String,
        name: String,
    ) = folder.trimEnd('/') + "/" + name

    companion object {
        private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

        private const val SHELL_SPECIAL = " \t\"'\\$`()&;|<>*?[]#~!"

        /** Backslash-escapes characters a shell or agent prompt would split on, like a dropped path. */
        internal fun escapeForShell(path: String): String =
            buildString {
                for (c in path) {
                    if (c in SHELL_SPECIAL) append('\\')
                    append(c)
                }
            }

        internal fun extensionFor(mime: String?): String =
            when (mime?.lowercase()) {
                "image/png" -> "png"
                "image/jpeg", "image/jpg" -> "jpg"
                "image/webp" -> "webp"
                "image/gif" -> "gif"
                else -> "bin"
            }
    }
}

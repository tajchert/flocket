package com.mtajchert.flocket.feature.terminal.impl.share

import com.mtajchert.flocket.core.common.error.AppError
import com.mtajchert.flocket.core.common.time.Clock
import com.mtajchert.flocket.core.data.ShareSeenRepository
import com.mtajchert.flocket.core.model.AgentShare
import com.mtajchert.flocket.core.model.HostId
import com.mtajchert.flocket.core.model.TerminalAddress
import com.mtajchert.flocket.core.session.CoreSession
import com.mtajchert.flocket.core.session.DownloadEvent
import com.mtajchert.flocket.core.session.DownloadResume
import com.mtajchert.flocket.core.ui.error.toUiError
import com.mtajchert.flocket.feature.terminal.impl.escapeForShell
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Agent shares of ONE terminal: the card, the shared-files list and the viewer. Owned by the terminal ViewModel.
 *
 * [shares] is every share of the host's session; this keeps those whose address is [address] (so `t3` and
 * `rohan/t3` stay apart). The first non-empty list of a source (host, machine) goes through
 * [ShareSeenRepository.firstRead] (also for an empty one), which hides old entries. A file is downloaded only when the user opens it.
 */
class ShareController(
    private val scope: CoroutineScope,
    private val hostId: HostId,
    private val address: TerminalAddress,
    /**
     * The shares and, per machine (null = local), when the session first read that machine's share document (even
     * if empty). One flow so the two always belong together.
     */
    feed: Flow<Pair<List<AgentShare>, Map<String?, Long>>>,
    private val session: () -> CoreSession?,
    private val seen: ShareSeenRepository,
    private val pasteText: (String) -> Unit,
    private val sharesRoot: File,
    private val nowSeconds: () -> Long = { Clock.System.nowMillis() / 1000 },
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val processed = MutableStateFlow<List<AgentShare>>(emptyList())
    private val seenIds = MutableStateFlow<Set<String>>(emptySet())
    private val local = MutableStateFlow(Local())
    private val current = MutableStateFlow<Current?>(null)
    private val installResult = MutableStateFlow<ApkInstallResult?>(null)
    private var job: Job? = null

    /** This controller started an install and waits for its result (the event bus is process-wide). */
    private var installPending = false

    /** Tokens of finished files, so opening one again only checks it on the laptop (lost with the process). */
    private val finished = mutableMapOf<Pair<String?, String>, DownloadResume>()

    private data class Local(
        val sheetOpen: Boolean = false,
        val viewer: ShareItem? = null,
        /** The path the viewer shows; null while choosing from several files. */
        val shown: String? = null,
    )

    /** The one running (or last) download. It belongs to the controller, not to the viewer. */
    private data class Current(
        val shareId: String,
        val machine: String?,
        val path: String,
        val target: File,
        val part: File,
        val state: FileLoad,
        /** Continues [part] after a failure. */
        val resume: DownloadResume? = null,
    )

    val ui: StateFlow<SharesUi> =
        combine(processed, seenIds, local, current, installResult) { list, seenIds, loc, cur, install ->
            val mine = list.filter { it.address == address }.map { it.toItem() }
            val file =
                if (cur != null && cur.shareId == loc.viewer?.id && cur.path == loc.shown) cur.state else FileLoad.Idle
            SharesUi(
                card = mine.firstOrNull { it.id !in seenIds },
                all = mine,
                sheetOpen = loc.sheetOpen,
                viewer = loc.viewer?.let { ViewerUi(it, file) },
                downloading =
                    (cur?.state as? FileLoad.Loading)
                        ?.let {
                            mapOf(
                                it.path to
                                    it.size?.let { s -> if (s > 0) (it.bytes.toFloat() / s).coerceIn(0f, 1f) else 0f },
                            )
                        }.orEmpty(),
                installResult = install,
            )
        }.stateIn(scope, SharingStarted.Eagerly, SharesUi())

    init {
        // Collected here, not in the composable: the system install dialog stops the activity, and the result has
        // no replay.
        scope.launch {
            ApkInstallEvents.results.collect {
                if (installPending) {
                    installPending = false
                    installResult.value = it
                }
            }
        }
        scope.launch { seen.seen.collect { seenIds.value = it } }
        scope.launch {
            feed.collect { (list, at) ->
                // Hide old entries on the first read of each source before anything can show. "Old" is judged at
                // the session's first read of that machine, not now: a screen opened later must not hide a share
                // that arrived after an empty first read.
                (list.map { it.machine } + at.keys).distinct().forEach { machine ->
                    seen.firstRead(hostId, machine, list, at[machine] ?: nowSeconds())
                }
                // Publish the fresh seen set first, so a hidden old entry never flashes as a card.
                seenIds.value = seen.seen.first()
                processed.value = list
            }
        }
    }

    fun showSheet() = local.update { it.copy(sheetOpen = true) }

    fun hideSheet() = local.update { it.copy(sheetOpen = false) }

    /** Hides the card (and the share's entry in "new" state) for good. */
    fun dismiss(id: String) {
        scope.launch { seen.markSeen(listOf(id)) }
    }

    /** Opens the viewer. Nothing is downloaded until a file is chosen; a single file is chosen for the user. */
    fun open(id: String) {
        val share = processed.value.firstOrNull { it.id == id && it.address == address }?.toItem() ?: return
        local.update { it.copy(sheetOpen = false, viewer = share, shown = null) }
        scope.launch { seen.markSeen(listOf(id)) }
        val running = current.value?.takeIf { it.shareId == share.id && it.state is FileLoad.Loading }
        if (running != null) {
            local.update { it.copy(shown = running.path) }
        } else {
            share.paths.singleOrNull()?.let { openFile(it) }
        }
    }

    /**
     * Shows [path]. A download of it that is running (or failed and waiting for Resume) is shown as it is; any other
     * file ends the running download and its partial file.
     */
    fun openFile(path: String) {
        val share = local.value.viewer ?: return
        val cur = current.value
        local.update { it.copy(shown = path) }
        val waiting = (cur?.state as? FileLoad.Failed)?.canResume == true
        if (cur != null && cur.shareId == share.id && cur.path == path && (cur.state is FileLoad.Loading || waiting)) {
            return
        }
        val drop = discard()
        val target = ShareFiles.target(sharesRoot, share.id, baseName(path))
        start(
            Current(share.id, share.machine, path, target, ShareFiles.partOf(target), FileLoad.Loading(path)),
            drop,
            null,
        )
    }

    /** Continues the partial file after a failure with a resume token. */
    fun resume() {
        val cur = current.value ?: return
        val token = cur.resume ?: return
        if (cur.state !is FileLoad.Failed) return
        start(cur.copy(state = FileLoad.Loading(cur.path), resume = null), null, token)
    }

    /** Starts the download of the failed file again from zero. */
    fun retry() {
        val cur = current.value ?: return
        val drop = discard()
        start(cur.copy(state = FileLoad.Loading(cur.path), resume = null), drop, null)
    }

    /** Stops the download, deletes the partial file, and goes back to the list or closes the viewer. */
    fun cancelDownload() {
        val cur = current.value
        val previous = job
        val drop = discard()
        // Later starts wait for this, so a late delete never hits a new download's part file.
        job =
            scope.launch(io) {
                previous?.join()
                drop?.delete()
            }
        if (cur != null) finished.remove(cur.machine to cur.path)
        current.value = null
        local.update { l ->
            if (l.viewer != null &&
                l.viewer.fileCount > 1
            ) {
                l.copy(shown = null)
            } else {
                l.copy(viewer = null, shown = null)
            }
        }
    }

    /** The install prompt was shown: do not ask again for this download. */
    fun installPromptShown() {
        current.update { c ->
            val s = c?.state
            if (s is FileLoad.Loaded && s.askInstall) c.copy(state = s.copy(askInstall = false)) else c
        }
    }

    /** An install was handed to the system; its result is for this screen. */
    fun installStarted() {
        installPending = true
    }

    fun installResultShown() {
        installResult.value = null
    }

    /** Ends the running download; returns the partial file to delete (not yet deleted: that is IO). */
    private fun discard(): File? {
        job?.cancel()
        val cur = current.value ?: return null
        return if (cur.state !is FileLoad.Loaded) cur.part else null
    }

    /**
     * [drop] is a stale partial file to delete first. [resumeToken] continues [Current.part]; with none, a finished
     * file of this share is re-checked (its target becomes the part and the engine decides) or a fresh download starts.
     */
    private fun start(
        cur: Current,
        drop: File?,
        resumeToken: DownloadResume?,
    ) {
        current.value = cur
        val key = cur.machine to cur.path
        val previous = job
        job =
            scope.launch {
                previous?.join()

                fun update(block: (Current) -> Current) =
                    current.update {
                        if (it?.path == cur.path &&
                            it.shareId == cur.shareId
                        ) {
                            block(it)
                        } else {
                            it
                        }
                    }
                var token: DownloadResume? = resumeToken
                var reused = false
                val prepared =
                    withContext(io) {
                        try {
                            drop?.delete()
                            val finishedToken = finished[key]
                            if (resumeToken == null && finishedToken != null && cur.target.exists()) {
                                token = finishedToken
                                reused = true
                            }
                            ShareFiles.prune(sharesRoot, nowSeconds() * 1000, keep = setOf(cur.target, cur.part))
                            cur.part.parentFile?.mkdirs()
                            if (reused) {
                                Files.move(cur.target.toPath(), cur.part.toPath(), StandardCopyOption.REPLACE_EXISTING)
                            } else if (token == null) {
                                cur.part.delete()
                            }
                            true
                        } catch (e: IOException) {
                            false
                        }
                    }
                if (!prepared) {
                    finished.remove(key)
                    update {
                        it.copy(
                            state = FileLoad.Failed(cur.path, AppError.Storage("share cache").toUiError(), false),
                        )
                    }
                    return@launch
                }
                val flow = session()?.download(cur.machine, cur.path, cur.part, token)
                if (flow == null) {
                    update { it.copy(state = FileLoad.Failed(cur.path, AppError.ConnectionLost.toUiError(), false)) }
                    return@launch
                }
                var started: DownloadResume? = token
                var startedSeen = false
                var downloaded = true
                flow.collect { ev ->
                    when (ev) {
                        is DownloadEvent.Started -> {
                            started = ev.resume
                            startedSeen = true
                            downloaded = ev.resumedFrom < ev.size
                            update { it.copy(state = FileLoad.Loading(cur.path, ev.resumedFrom, ev.size)) }
                        }

                        is DownloadEvent.Progress -> {
                            update { it.copy(state = FileLoad.Loading(cur.path, ev.bytes, ev.size)) }
                        }

                        is DownloadEvent.Done -> {
                            val moved =
                                withContext(io) {
                                    try {
                                        Files.move(
                                            cur.part.toPath(),
                                            cur.target.toPath(),
                                            StandardCopyOption.REPLACE_EXISTING,
                                        )
                                        true
                                    } catch (e: IOException) {
                                        cur.part.delete()
                                        false
                                    }
                                }
                            if (moved) {
                                started?.let { finished[key] = it }
                                update {
                                    val shows = local.value.viewer?.id == cur.shareId && local.value.shown == cur.path
                                    it.copy(
                                        state =
                                            FileLoad.Loaded(
                                                cur.path,
                                                cur.target,
                                                ev.size,
                                                askInstall = shows && downloaded && ApkInstall.isApk(cur.target.name),
                                            ),
                                        resume = null,
                                    )
                                }
                            } else {
                                finished.remove(key)
                                update {
                                    it.copy(
                                        state =
                                            FileLoad.Failed(
                                                cur.path,
                                                AppError.Storage("share cache").toUiError(),
                                                false,
                                            ),
                                    )
                                }
                            }
                        }

                        is DownloadEvent.Failed -> {
                            val gone = ev.error is AppError.FileMissing || ev.error is AppError.FileChanged
                            if (reused && !startedSeen && !gone) {
                                // The laptop could not be asked: the cached copy is still the file we had.
                                val restored =
                                    withContext(io) {
                                        try {
                                            Files.move(
                                                cur.part.toPath(),
                                                cur.target.toPath(),
                                                StandardCopyOption.REPLACE_EXISTING,
                                            )
                                            true
                                        } catch (e: IOException) {
                                            false
                                        }
                                    }
                                if (restored) {
                                    update {
                                        it.copy(
                                            state = FileLoad.Loaded(cur.path, cur.target, cur.target.length()),
                                            resume = null,
                                        )
                                    }
                                    return@collect
                                }
                            }
                            val resumable = ev.resume
                            if (resumable == null) {
                                finished.remove(key)
                                withContext(io) { cur.part.delete() }
                            }
                            update {
                                it.copy(
                                    state =
                                        FileLoad.Failed(
                                            cur.path,
                                            ev.error.toUiError(),
                                            canResume =
                                                resumable != null,
                                        ),
                                    resume = resumable,
                                )
                            }
                        }
                    }
                }
            }
    }

    /** Several files: from one file back to the list. The download keeps running. */
    fun backToList() {
        local.update { it.copy(shown = null) }
    }

    /** Closes the viewer. A running download keeps going; the sheet shows its progress. */
    fun closeViewer() {
        local.update { it.copy(viewer = null, shown = null) }
    }

    /** Pastes the shown file's path plus a space (so the user can keep typing), then closes the viewer. */
    fun pastePath() {
        val path =
            when (val f = ui.value.viewer?.file) {
                is FileLoad.Loaded -> f.path
                is FileLoad.Loading -> f.path
                is FileLoad.Failed -> f.path
                else -> return
            }
        pasteText("${escapeForShell(path)} ")
        closeViewer()
    }
}

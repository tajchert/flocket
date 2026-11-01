package com.mtajchert.flocket.feature.terminal.impl.share

import com.mtajchert.flocket.core.common.error.AppError
import com.mtajchert.flocket.core.common.result.AppResult
import com.mtajchert.flocket.core.common.time.Clock
import com.mtajchert.flocket.core.data.ShareSeenRepository
import com.mtajchert.flocket.core.model.AgentShare
import com.mtajchert.flocket.core.model.HostId
import com.mtajchert.flocket.core.model.TerminalAddress
import com.mtajchert.flocket.core.session.CoreSession
import com.mtajchert.flocket.core.ui.error.toUiError
import com.mtajchert.flocket.feature.terminal.impl.escapeForShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    shares: Flow<List<AgentShare>>,
    /** Per machine (null = local): when the session first read that machine's share document (even if empty). */
    firstReadAt: Flow<Map<String?, Long>> = flowOf(emptyMap()),
    private val session: () -> CoreSession?,
    private val seen: ShareSeenRepository,
    private val pasteText: (String) -> Unit,
    private val nowSeconds: () -> Long = { Clock.System.nowMillis() / 1000 },
) {
    private val processed = MutableStateFlow<List<AgentShare>>(emptyList())
    private val seenIds = MutableStateFlow<Set<String>>(emptySet())
    private val local = MutableStateFlow(Local())
    private var download: Job? = null

    private data class Local(
        val sheetOpen: Boolean = false,
        val viewer: ViewerUi? = null,
    )

    val ui: StateFlow<SharesUi> =
        combine(processed, seenIds, local) { list, seenIds, loc ->
            val mine = list.filter { it.address == address }.map { it.toItem() }
            SharesUi(
                card = mine.firstOrNull { it.id !in seenIds },
                all = mine,
                sheetOpen = loc.sheetOpen,
                viewer = loc.viewer,
            )
        }.stateIn(scope, SharingStarted.Eagerly, SharesUi())

    init {
        scope.launch { seen.seen.collect { seenIds.value = it } }
        scope.launch {
            combine(shares, firstReadAt) { list, at -> list to at }.collect { (list, at) ->
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
        download?.cancel()
        local.update { it.copy(sheetOpen = false, viewer = ViewerUi(share)) }
        scope.launch { seen.markSeen(listOf(id)) }
        share.paths.singleOrNull()?.let { openFile(it) }
    }

    fun openFile(path: String) {
        val viewer = local.value.viewer ?: return
        download?.cancel()
        local.update { it.copy(viewer = viewer.copy(file = FileLoad.Loading(path))) }
        download =
            scope.launch {
                val result =
                    session()?.readFile(viewer.share.machine, path) ?: AppResult.Failure(AppError.ConnectionLost)
                val file =
                    when (result) {
                        is AppResult.Success -> FileLoad.Loaded(path, result.value)
                        is AppResult.Failure -> FileLoad.Failed(path, result.error.toUiError())
                    }
                local.update { l ->
                    // The user may have moved on while the transfer ran.
                    if (l.viewer?.share?.id == viewer.share.id && l.viewer.file == FileLoad.Loading(path)) {
                        l.copy(viewer = l.viewer.copy(file = file))
                    } else {
                        l
                    }
                }
            }
    }

    /** Several files: from one file back to the list. */
    fun backToList() {
        download?.cancel()
        local.update { l -> l.copy(viewer = l.viewer?.copy(file = FileLoad.Idle)) }
    }

    fun closeViewer() {
        download?.cancel()
        local.update { it.copy(viewer = null) }
    }

    /** Pastes the shown file's path plus a space (so the user can keep typing), then closes the viewer. */
    fun pastePath() {
        val path =
            when (val f = local.value.viewer?.file) {
                is FileLoad.Loaded -> f.path
                is FileLoad.Loading -> f.path
                is FileLoad.Failed -> f.path
                else -> return
            }
        pasteText("${escapeForShell(path)} ")
        closeViewer()
    }
}

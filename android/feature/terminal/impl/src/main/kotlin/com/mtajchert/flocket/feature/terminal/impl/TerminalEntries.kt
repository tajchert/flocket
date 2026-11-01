package com.mtajchert.flocket.feature.terminal.impl

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mtajchert.flocket.core.model.BellMode
import com.mtajchert.flocket.core.terminal.controller.TerminalController
import com.mtajchert.flocket.core.terminal.engine.TerminalEngineFactory
import com.mtajchert.flocket.core.terminal.ghostty.GhosttyTerminalEngine
import com.mtajchert.flocket.core.terminal.ui.TerminalExtraKeys
import com.mtajchert.flocket.core.terminal.ui.TerminalSurface
import com.mtajchert.flocket.core.terminal.ui.TerminalSurfaceState
import com.mtajchert.flocket.core.terminal.ui.TerminalThemes
import com.mtajchert.flocket.core.ui.nav.EntryProviderInstaller
import com.mtajchert.flocket.core.ui.nav.Navigator
import com.mtajchert.flocket.core.ui.nav.PaneRole
import com.mtajchert.flocket.feature.terminal.api.TerminalRoute
import com.mtajchert.flocket.feature.terminal.impl.share.ShareActions
import com.mtajchert.flocket.feature.terminal.impl.share.ShareOverlays
import com.mtajchert.flocket.feature.terminal.impl.transfer.ClipboardOffer
import com.mtajchert.flocket.feature.terminal.impl.transfer.PasteChooserSheet
import com.mtajchert.flocket.feature.terminal.impl.transfer.SendToFolderDialog
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.launch

/** Contributes [TerminalRoute]: the detail pane of the terminals list-detail, full screen on phones. */
@Module
@InstallIn(SingletonComponent::class)
object TerminalNavigationModule {
    @Provides
    @IntoSet
    @OptIn(ExperimentalMaterial3AdaptiveApi::class)
    fun terminalEntries(): EntryProviderInstaller =
        EntryProviderInstaller { scope, navigator ->
            scope.entry<TerminalRoute>(
                metadata = ListDetailSceneStrategy.detailPane() + PaneRole.Detail.metadata(),
            ) { key ->
                TerminalRouteEntry(key, navigator)
            }
        }

    @Provides
    fun terminalControllerFactory(): TerminalControllerFactory =
        TerminalControllerFactory {
            TerminalController(TerminalEngineFactory { c, r -> GhosttyTerminalEngine(c, r) }, TerminalThemes.Dark)
        }
}

@Composable
private fun TerminalRouteEntry(
    key: TerminalRoute,
    navigator: Navigator,
) {
    // One ViewModel per nav entry (the entry decorator scopes the store): selecting another terminal
    // in the two-pane layout replaces the entry, which clears this ViewModel and detaches.
    val viewModel =
        hiltViewModel<TerminalViewModel, TerminalViewModel.Factory>(key = "${key.hostId}/${key.address}") {
            it.create(key)
        }
    TerminalRouteContent(
        viewModel,
        onClose = navigator::back,
        onOpenTerminal = { address -> navigator.navigate(TerminalRoute(key.hostId, address)) },
    )
}

/** Wires a [TerminalViewModel] to the screen and the platform (clipboard, keep-screen-on, keyboard). */
@OptIn(ExperimentalLayoutApi::class)
// One-shot snackbar text, not displayed state.
@SuppressLint("LocalContextGetResourceValueCall", "LocalContextResourcesRead")
@Composable
fun TerminalRouteContent(
    viewModel: TerminalViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenTerminal: (address: String) -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val tabStrip by viewModel.tabStrip.collectAsStateWithLifecycle()
    val header by viewModel.header.collectAsStateWithLifecycle()
    val openTerminal by rememberUpdatedState(onOpenTerminal)
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val view = LocalView.current
    val surfaceState = remember { TerminalSurfaceState() }
    val onCloseUpdated by rememberUpdatedState(onClose)

    // Leaving for the list must not leave the soft keyboard up over it. Hide it while the terminal
    // view is still attached (it holds the window token the IME is bound to).
    fun close() {
        surfaceState.hideKeyboard()
        onCloseUpdated()
    }
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val theme = remember(dark, state.colorScheme) { TerminalThemes.forScheme(state.colorScheme, dark) }
    val imeVisible = WindowInsets.isImeVisible

    // FLAG_KEEP_SCREEN_ON while this screen is shown (from the preference).
    DisposableEffect(view, state.keepScreenOn) {
        view.keepScreenOn = state.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }
    // "Looking" = app resumed AND this pane composed (Nav3 composes only drawn entries, the detail pane
    // included): the ViewModel marks the agent seen while this holds.
    LifecycleResumeEffect(viewModel) {
        viewModel.setLooking(true)
        onPauseOrDispose { viewModel.setLooking(false) }
    }
    // Hardware keyboards type into the terminal as soon as the pane is shown (tablets, two-pane).
    LaunchedEffect(surfaceState) {
        withFrameNanos { }
        surfaceState.requestFocus()
    }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is TerminalScreenEvent.Message -> {
                    snackbar.showSnackbar(event.text.resolve(context), withDismissAction = true)
                }

                is TerminalScreenEvent.Failure -> {
                    snackbar.showSnackbar(
                        context.getString(
                            R.string.terminal_error_format,
                            event.error.title.resolve(context),
                            event.error.message.resolve(context),
                        ),
                        withDismissAction = true,
                        duration = SnackbarDuration.Long,
                    )
                }

                TerminalScreenEvent.OutcomeUnknown -> {
                    snackbar.showSnackbar(
                        context.getString(R.string.terminal_outcome_unknown),
                        withDismissAction = true,
                        duration = SnackbarDuration.Indefinite,
                    )
                }

                is TerminalScreenEvent.CopyToClipboard -> {
                    context.clipboard()?.setPrimaryClip(ClipData.newPlainText("terminal", event.text))
                }

                TerminalScreenEvent.Close -> {
                    close()
                }
            }
        }
    }

    // BEGIN transfer
    val transfers = viewModel.transfers
    val transferPhase by transfers.phase.collectAsStateWithLifecycle()
    val sendState by transfers.sendToFolder.collectAsStateWithLifecycle()
    var chooser by remember { mutableStateOf<ClipboardOffer?>(null) }
    val photoPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) transfers.pasteUri(uri)
        }
    val filePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) transfers.pasteUri(uri)
        }
    val sendPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) transfers.prepareSendToFolder(uri)
        }
    // END transfer

    val actions =
        remember(viewModel, surfaceState) {
            TerminalActions(
                onBack = { close() },
                onOpenTerminal = { openTerminal(it) },
                onRenameTab = viewModel::requestRename,
                onRenameChange = viewModel::updateRename,
                onRenameSave = viewModel::confirmRename,
                onRenameDismiss = viewModel::dismissRename,
                onFit = viewModel::fitToScreen,
                // BEGIN transfer
                onPaste = { chooser = context.clipboardOffer() },
                onSendToFolder = { sendPicker.launch(arrayOf("*/*")) },
                onTransferCancel = viewModel.transfers::cancel,
                onTransferRetry = viewModel.transfers::retry,
                onTransferDismiss = viewModel.transfers::dismiss,
                // END transfer
                onCopy = viewModel::copyScreen,
                onInterrupt = viewModel::interrupt,
                onKill = viewModel::requestKill,
                onConfirmKill = viewModel::confirmKill,
                onDismissKill = viewModel::dismissKill,
                onClose = viewModel::close,
                onRemove = viewModel::remove,
                onReattach = viewModel::retryAttach,
                onDismissFreshShell = viewModel::dismissFreshShell,
                onClipboardAnswer = viewModel::answerClipboardRequest,
                onClipboardAlways = viewModel::alwaysAllowClipboard,
                onReply = viewModel::sendReply,
                onTopBarHidden = viewModel::setTopBarHidden,
            )
        }
    // BEGIN transfer
    chooser?.let { offer ->
        PasteChooserSheet(
            clipboard = offer,
            onPasteText = {
                chooser = null
                context.clipboardText()?.let(viewModel::paste)
            },
            onPasteClipboardImage = {
                chooser = null
                context.clipboardImage()?.let { (uri, mime) -> transfers.pasteClipboardImage(uri, mime) }
            },
            onChoosePhoto = {
                chooser = null
                photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onChooseFile = {
                chooser = null
                filePicker.launch(arrayOf("*/*"))
            },
            onDismiss = { chooser = null },
        )
    }
    sendState?.let { send ->
        SendToFolderDialog(
            state = send,
            onConfirm = transfers::confirmSendToFolder,
            onCancelUpload = transfers::cancel,
            onDismiss = transfers::dismissSendToFolder,
        )
    }
    // END transfer
    if (state.offerNotificationPermission) {
        NotificationPermissionPrompt(onAnswer = viewModel::onNotificationPermissionAsked)
    }
    val toggleKeyboard = { if (imeVisible) surfaceState.hideKeyboard() else surfaceState.showKeyboard() }
    // --- agent shares (T5) ---
    val shares by viewModel.shares.ui.collectAsStateWithLifecycle()
    val shareActions = remember(viewModel) { ShareActions.of(viewModel.shares) }
    ShareOverlays(
        ui = shares,
        actions = shareActions,
        canPaste = state.canInteract,
        onMessage = { text -> scope.launch { snackbar.showSnackbar(text, withDismissAction = true) } },
    )
    // --- end agent shares ---
    TerminalScreen(
        modifier = modifier,
        shares = shares,
        shareActions = shareActions,
        state = state,
        actions = actions.copy(onToggleKeyboard = toggleKeyboard),
        snackbarHostState = snackbar,
        tabStrip = tabStrip,
        transfer = transferPhase,
        header = header,
        extraKeys = {
            TerminalExtraKeys(viewModel.controller, keyboardVisible = imeVisible, onToggleKeyboard = toggleKeyboard)
        },
    ) { modifier ->
        TerminalSurface(
            controller = viewModel.controller,
            theme = theme,
            modifier = modifier,
            fontSizeSp = state.fontSizeSp,
            onFontSizeChange = viewModel::onFontSizeChange,
            onClipboardWriteRequest = viewModel::onClipboardWriteRequest,
            onPasteImage = transfers::pasteClipboardImage,
            bellHaptics = state.bell == BellMode.Haptic,
            // The screen draws its own reconnecting chip and connecting overlay.
            reconnectingLabel = null,
            state = surfaceState,
        )
    }
}

private fun Context.clipboard(): ClipboardManager? = getSystemService(ClipboardManager::class.java)

// BEGIN transfer
private fun Context.clipboardText(): String? =
    clipboard()
        ?.primaryClip
        ?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)
        ?.coerceToText(this)
        ?.toString()
        ?.takeIf { it.isNotEmpty() }

/** The clipboard's image as (content URI, MIME type), null when it holds none. */
private fun Context.clipboardImage(): Pair<Uri, String?>? {
    val clip = clipboard()?.primaryClip?.takeIf { it.itemCount > 0 } ?: return null
    val item = clip.getItemAt(0)
    val uri = item.uri ?: return null
    if (!clip.description.hasMimeType("image/*")) return null
    return uri to clip.description.getMimeType(0).takeIf { it.startsWith("image/") }
}

private fun Context.clipboardOffer(): ClipboardOffer {
    val image = clipboardImage() != null
    val clip = clipboard()?.primaryClip?.takeIf { it.itemCount > 0 }
    // An image item coerces to its URI text: only real text counts.
    val hasText = clip?.getItemAt(0)?.text?.isNotEmpty() == true || (!image && clipboardText() != null)
    return ClipboardOffer(hasText = hasText, hasImage = image)
}
// END transfer

/**
 * Asks once, in context, for POST_NOTIFICATIONS (Android 13+) so the keep-alive notification can show while
 * the terminal stays connected in the background (ADR-0006). Denying is fine: the service runs anyway.
 */
@Composable
private fun NotificationPermissionPrompt(onAnswer: () -> Unit) {
    val context = LocalContext.current
    val needed =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onAnswer() }
    if (!needed) return
    AlertDialog(
        onDismissRequest = onAnswer,
        icon = { Icon(Icons.Outlined.Notifications, contentDescription = null) },
        title = { Text(stringResource(R.string.terminal_notifications_title)) },
        text = { Text(stringResource(R.string.terminal_notifications_message)) },
        confirmButton = {
            TextButton(onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }) { Text(stringResource(R.string.terminal_notifications_allow)) }
        },
        dismissButton = {
            TextButton(onClick = onAnswer) { Text(stringResource(R.string.terminal_notifications_not_now)) }
        },
    )
}

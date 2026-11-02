@file:Suppress("ktlint:standard:max-line-length")

package com.mtajchert.flocket.feature.terminals.impl

import android.annotation.SuppressLint
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mtajchert.flocket.core.model.HostId
import com.mtajchert.flocket.core.ui.component.RenameDialog
import com.mtajchert.flocket.core.ui.error.UiErrorAction
import com.mtajchert.flocket.core.ui.nav.DetailPanePlaceholder
import com.mtajchert.flocket.core.ui.nav.EntryProviderInstaller
import com.mtajchert.flocket.core.ui.nav.Navigator
import com.mtajchert.flocket.core.ui.nav.PaneRole
import com.mtajchert.flocket.feature.hosts.api.HostDetailRoute
import com.mtajchert.flocket.feature.hosts.api.HostEditRoute
import com.mtajchert.flocket.feature.hosts.api.HostsRoute
import com.mtajchert.flocket.feature.onboarding.api.OnboardingRoute
import com.mtajchert.flocket.feature.terminal.api.TerminalRoute
import com.mtajchert.flocket.feature.terminals.api.NewTerminalLauncher
import com.mtajchert.flocket.feature.terminals.api.TerminalsRoute
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.flow.first

/** Contributes the terminals home. On wide windows it is the list pane; [TerminalRoute] is the detail pane. */
@Module
@InstallIn(SingletonComponent::class)
object TerminalsNavigationModule {
    @Provides
    @IntoSet
    @OptIn(ExperimentalMaterial3AdaptiveApi::class)
    fun terminalsEntries(requests: NewTerminalRequests): EntryProviderInstaller =
        EntryProviderInstaller { scope, navigator ->
            scope.entry<TerminalsRoute>(
                metadata =
                    ListDetailSceneStrategy.listPane(
                        detailPlaceholder = {
                            DetailPanePlaceholder(stringResource(R.string.terminals_select_placeholder))
                        },
                    ) + PaneRole.List.metadata(),
            ) { TerminalsRoute(navigator, requests) }
        }

    @Provides
    fun newTerminalLauncher(requests: NewTerminalRequests): NewTerminalLauncher = requests
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("LocalContextGetResourceValueCall", "LocalContextResourcesRead") // one-shot event text, not displayed state
@Composable
private fun TerminalsRoute(
    navigator: Navigator,
    newTerminalRequests: NewTerminalRequests,
    viewModel: TerminalsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val dialog by viewModel.dialog.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    // The rail's New terminal button: the same sheet as the list's own button, or Add host when there is none.
    LaunchedEffect(viewModel, newTerminalRequests) {
        newTerminalRequests.requests.collect {
            val loaded = viewModel.uiState.first { it.loaded }
            if (loaded.emptyKind ==
                EmptyKind.NoHosts
            ) {
                navigator.navigate(HostEditRoute(null))
            } else {
                viewModel.openNewTerminal()
            }
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is TerminalsEvent.Info -> {
                    snackbar.showSnackbar(event.text.resolve(context), withDismissAction = true)
                }

                is TerminalsEvent.Failure -> {
                    snackbar.showSnackbar(
                        context.getString(
                            R.string.msg_error_format,
                            event.error.title.resolve(context),
                            event.error.message.resolve(context),
                        ),
                        withDismissAction = true,
                        duration = SnackbarDuration.Long,
                    )
                }

                TerminalsEvent.OutcomeUnknown -> {
                    snackbar.showSnackbar(
                        context.getString(R.string.msg_outcome_unknown),
                        withDismissAction = true,
                        duration = SnackbarDuration.Indefinite,
                    )
                }

                is TerminalsEvent.OpenTerminal -> {
                    navigator.navigate(TerminalRoute(event.hostId.value, event.address))
                }

                is TerminalsEvent.OpenHost -> {
                    navigator.navigate(
                        if (event.action ==
                            UiErrorAction.EditHost
                        ) {
                            HostEditRoute(event.hostId.value)
                        } else {
                            HostDetailRoute(event.hostId.value)
                        },
                    )
                }
            }
        }
    }

    val openRoute = navigator.current as? TerminalRoute
    LaunchedEffect(viewModel, openRoute?.hostId, openRoute?.address) {
        viewModel.setOpenTerminal(openRoute?.hostId?.let(::HostId), openRoute?.address)
    }
    val actions =
        remember(viewModel, navigator) {
            TerminalsActions(
                onFilter = viewModel::setFilter,
                onSort = viewModel::setSort,
                onClearFilters = viewModel::clearFilters,
                onRefresh = viewModel::refresh,
                onOpen = { navigator.navigate(TerminalRoute(it.hostId.value, it.address)) },
                onKill = viewModel::requestKill,
                onOpenChild = { navigator.navigate(TerminalRoute(it.hostId.value, it.address)) },
                onPin = viewModel::setPinned,
                onMarkUnread = viewModel::markUnread,
                onInterrupt = viewModel::interrupt,
                onSendText = viewModel::requestSend,
                onRename = viewModel::requestRename,
                onNewTerminal = { viewModel.openNewTerminal() },
                onAddHost = { navigator.navigate(HostEditRoute(null)) },
                onHostAction = viewModel::onHostAction,
            )
        }
    TerminalsScreen(
        state = state,
        actions = actions,
        selected = navigator.current as? TerminalRoute,
        snackbarHostState = snackbar,
    )

    when (val d = dialog) {
        is TerminalDialog.ConfirmKill -> {
            KillConfirmDialog(d.item, onConfirm = viewModel::confirmKill, onDismiss = viewModel::dismissDialog)
        }

        is TerminalDialog.SendText -> {
            SendTextDialog(
                d,
                onTextChange = viewModel::onSendTextChange,
                onSend = { viewModel.sendText(force = false) },
                onForce = { viewModel.sendText(force = true) },
                onDismiss = viewModel::dismissDialog,
            )
        }

        is TerminalDialog.NewTerminal -> {
            NewTerminalSheet(
                d.form,
                remember(viewModel) {
                    NewTerminalCallbacks(
                        onHost = viewModel::selectHost,
                        onMachine = { m -> viewModel.updateForm { it.copy(machine = m) } },
                        onSource = { s -> viewModel.updateForm { it.copy(source = s) } },
                        onCommand = { c -> viewModel.updateForm { it.copy(command = c) } },
                        onCwd = { c -> viewModel.updateForm { it.copy(cwd = c) } },
                        onName = { n -> viewModel.updateForm { it.copy(name = n) } },
                        onKeep = { k -> viewModel.updateForm { it.copy(keep = k) } },
                        onSubmit = viewModel::submitNewTerminal,
                        onCancel = viewModel::dismissDialog,
                    )
                },
            )
        }

        is TerminalDialog.Rename -> {
            RenameDialog(
                isTab = d.ref.tabId != null,
                text = d.text,
                placeholder = d.placeholder,
                onTextChange = viewModel::updateRename,
                onSave = viewModel::confirmRename,
                onDismiss = viewModel::dismissDialog,
            )
        }

        null -> {
            Unit
        }
    }
}

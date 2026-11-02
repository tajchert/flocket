package com.mtajchert.flocket.feature.hosts.impl

import android.content.Intent
import androidx.compose.material3.Surface
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import com.mtajchert.flocket.core.model.HostId
import com.mtajchert.flocket.core.ui.component.ImportKeyDialog
import com.mtajchert.flocket.core.ui.component.rememberKeyFilePicker
import com.mtajchert.flocket.core.ui.nav.DetailPanePlaceholder
import com.mtajchert.flocket.core.ui.nav.EntryProviderInstaller
import com.mtajchert.flocket.core.ui.nav.Navigator
import com.mtajchert.flocket.core.ui.nav.PaneRole
import com.mtajchert.flocket.core.ui.terminalsettings.TerminalSettingsCallbacks
import com.mtajchert.flocket.feature.hosts.api.HostDetailRoute
import com.mtajchert.flocket.feature.hosts.api.HostEditRoute
import com.mtajchert.flocket.feature.hosts.api.HostSettingsRoute
import com.mtajchert.flocket.feature.hosts.api.HostsRoute
import com.mtajchert.flocket.feature.hosts.impl.detail.HostDetailCallbacks
import com.mtajchert.flocket.feature.hosts.impl.detail.HostDetailNavigation
import com.mtajchert.flocket.feature.hosts.impl.detail.HostDetailScreen
import com.mtajchert.flocket.feature.hosts.impl.detail.HostDetailViewModel
import com.mtajchert.flocket.feature.hosts.impl.edit.HostEditScreen
import com.mtajchert.flocket.feature.hosts.impl.edit.HostEditViewModel
import com.mtajchert.flocket.feature.hosts.impl.list.HostsScreen
import com.mtajchert.flocket.feature.hosts.impl.list.HostsViewModel
import com.mtajchert.flocket.feature.hosts.impl.settings.HostSettingsCallbacks
import com.mtajchert.flocket.feature.hosts.impl.settings.HostSettingsScreen
import com.mtajchert.flocket.feature.hosts.impl.settings.HostSettingsViewModel
import com.mtajchert.flocket.feature.onboarding.api.OnboardingRoute
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/** Contributes the hosts feature's destinations to the app's Navigation 3 graph. */
@Module
@InstallIn(SingletonComponent::class)
object HostsNavigationModule {
    @Provides
    @IntoSet
    @OptIn(ExperimentalMaterial3AdaptiveApi::class)
    fun hostsEntries(): EntryProviderInstaller =
        EntryProviderInstaller { scope, navigator ->
            scope.entry<HostsRoute>(
                metadata =
                    ListDetailSceneStrategy.listPane(
                        detailPlaceholder = {
                            DetailPanePlaceholder(stringResource(R.string.hosts_select_placeholder))
                        },
                    ) + PaneRole.List.metadata(),
            ) { HostsRoute(navigator) }
            scope.entry<HostDetailRoute>(
                metadata = ListDetailSceneStrategy.detailPane() + PaneRole.Detail.metadata(),
            ) { key ->
                HostDetailRoute(key.hostId, navigator)
            }
            scope.entry<HostEditRoute> { key -> HostEditRoute(key.hostId, navigator) }
            scope.entry<HostSettingsRoute> { key -> HostSettingsRoute(key.hostId, navigator) }
        }
}

@Composable
private fun HostsRoute(
    navigator: Navigator,
    viewModel: HostsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    HostsScreen(
        state = state,
        onHostClick = { navigator.navigate(HostDetailRoute(it.value)) },
        onAddHost = { navigator.navigate(HostEditRoute(null)) },
        onTour = { navigator.navigate(OnboardingRoute) },
    )
}

@Composable
private fun HostEditRoute(
    hostId: String?,
    navigator: Navigator,
) {
    val viewModel = hiltViewModel<HostEditViewModel, HostEditViewModel.Factory>(key = hostId) { it.create(hostId) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.saved) { if (state.saved) navigator.back() }
    val pickKeyFile = rememberKeyFilePicker(viewModel::onImportFilePicked, viewModel::onImportFileFailed)
    HostEditScreen(
        state = state,
        onFormChange = viewModel::onFormChange,
        onImportKey = viewModel::requestImport,
        onCreateKey = viewModel::createKey,
        onSave = viewModel::save,
        onClose = navigator::back,
    )
    state.importDialog?.let { dialog ->
        ImportKeyDialog(
            state = dialog,
            onChange = viewModel::onImportChange,
            onSource = viewModel::onImportSource,
            onPickFile = pickKeyFile,
            onConfirm = viewModel::importKey,
            onDismiss = viewModel::dismissImport,
        )
    }
}

@Composable
private fun HostDetailRoute(
    hostId: String,
    navigator: Navigator,
) {
    val viewModel = hiltViewModel<HostDetailViewModel, HostDetailViewModel.Factory>(key = hostId) { it.create(hostId) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    val helpUrl = stringResource(R.string.install_help_url)
    LaunchedEffect(state.deleted) { if (state.deleted) navigator.back() }
    HostDetailScreen(
        state = state,
        callbacks =
            HostDetailCallbacks(
                onBack = if (rememberIsMultiPane()) null else navigator::back,
                onEdit = { navigator.navigate(HostEditRoute(hostId)) },
                onHostSettings = { navigator.navigate(HostSettingsRoute(hostId)) },
                onConnect = viewModel::retry,
                onErrorAction = {
                    when (viewModel.onErrorAction(it)) {
                        HostDetailNavigation.Edit -> navigator.navigate(HostEditRoute(hostId))
                        HostDetailNavigation.InstallHelp -> uriHandler.openUri(helpUrl)
                        null -> Unit
                    }
                },
                onRequestForget = viewModel::requestForgetPinned,
                onRequestDelete = viewModel::requestDelete,
                onDismissDialog = viewModel::dismissDialog,
                onTrust = viewModel::trust,
                onForget = viewModel::forgetPinned,
                onSubmitPassword = viewModel::submitPassword,
                onDelete = viewModel::delete,
            ),
    )
}

@Composable
private fun HostSettingsRoute(
    hostId: String,
    navigator: Navigator,
) {
    val viewModel =
        hiltViewModel<HostSettingsViewModel, HostSettingsViewModel.Factory>(
            key = "settings/$hostId",
        ) { it.create(hostId) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    HostSettingsScreen(
        state = state,
        callbacks =
            HostSettingsCallbacks(
                onBack = navigator::back,
                onResetAll = viewModel::resetAll,
                terminal =
                    TerminalSettingsCallbacks(
                        onFontSize = viewModel::setFontSize,
                        onColorScheme = viewModel::setColorScheme,
                        onExtraKeys = viewModel::setShowExtraKeys,
                        onKeepScreenOn = viewModel::setKeepScreenOn,
                        onClipboard = viewModel::setClipboardPolicy,
                        onBell = viewModel::setBell,
                        onAutoConnect = viewModel::setAutoConnect,
                        onWorkingDirectory = viewModel::setWorkingDirectory,
                        onUseAppDefault = viewModel::setUseAppDefault,
                    ),
            ),
    )
}

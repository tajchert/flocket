@file:Suppress("ktlint:standard:max-line-length")

package com.mtajchert.flocket.feature.settings.impl

import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mtajchert.flocket.core.ui.component.ImportKeyDialog
import com.mtajchert.flocket.core.ui.component.rememberKeyFilePicker
import com.mtajchert.flocket.core.ui.nav.EntryProviderInstaller
import com.mtajchert.flocket.core.ui.nav.Navigator
import com.mtajchert.flocket.core.ui.terminalsettings.TerminalSettingsCallbacks
import com.mtajchert.flocket.feature.hosts.api.HostsRoute
import com.mtajchert.flocket.feature.settings.api.LicensesRoute
import com.mtajchert.flocket.feature.settings.api.SettingsRoute
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.launch
import com.mtajchert.flocket.core.ui.R as CoreUiR

@Module
@InstallIn(SingletonComponent::class)
object SettingsNavigationModule {
    @Provides
    @IntoSet
    fun settingsEntries(): EntryProviderInstaller =
        EntryProviderInstaller { scope, navigator ->
            scope.entry<SettingsRoute> { SettingsRoute(navigator) }
            scope.entry<LicensesRoute> { LicensesRoute(navigator) }
        }
}

@SuppressLint("LocalContextGetResourceValueCall", "LocalContextResourcesRead") // one-shot event text, not displayed state
@Composable
private fun SettingsRoute(
    navigator: Navigator,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val dialog by viewModel.dialog.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val githubUrl = context.getString(R.string.github_url)
    val herdrUrl = context.getString(R.string.herdr_url)

    val appInfo =
        remember(context) {
            val info =
                try {
                    context.packageManager.getPackageInfo(context.packageName, 0)
                } catch (_: PackageManager.NameNotFoundException) {
                    null
                }
            AppInfo(info?.versionName ?: "?", info?.longVersionCode ?: 0L)
        }

    val pickKeyFile =
        rememberKeyFilePicker(
            onPick = viewModel::onImportFilePicked,
            onFail = {
                scope.launch { snackbar.showSnackbar(context.getString(CoreUiR.string.key_import_file_failed)) }
            },
        )

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is SettingsEvent.Info -> {
                    snackbar.showSnackbar(event.text.resolve(context))
                }

                is SettingsEvent.Failure -> {
                    snackbar.showSnackbar(
                        event.error.title.resolve(context) + ". " + event.error.message.resolve(context),
                    )
                }
            }
        }
    }

    val callbacks =
        remember(viewModel, navigator) {
            SettingsCallbacks(
                onTheme = viewModel::setTheme,
                onDynamicColor = viewModel::setDynamicColor,
                terminal =
                    TerminalSettingsCallbacks(
                        onFontSize = viewModel::setTerminalFontSize,
                        onColorScheme = viewModel::setColorScheme,
                        onExtraKeys = viewModel::setShowExtraKeys,
                        onKeepScreenOn = viewModel::setKeepScreenOn,
                        onClipboard = viewModel::setClipboardPolicy,
                        onBell = viewModel::setBell,
                        onAutoConnect = viewModel::setAutoConnect,
                        onWorkingDirectory = viewModel::setDefaultWorkingDirectory,
                    ),
                onKeepConnected = viewModel::setKeepConnected,
                onGenerateKey = viewModel::requestGenerate,
                onImportKey = { viewModel.requestImport() },
                onDeleteKey = viewModel::requestDelete,
                onHosts = { navigator.navigate(HostsRoute) },
                onLicenses = { navigator.navigate(LicensesRoute) },
                onGithub = { uriHandler.openUri(githubUrl) },
                onHerdr = { uriHandler.openUri(herdrUrl) },
            )
        }
    SettingsScreen(
        state = state,
        appInfo = appInfo,
        callbacks = callbacks,
        dynamicColorAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
        snackbarHostState = snackbar,
    )

    when (val d = dialog) {
        is SettingsDialog.Generate -> {
            GenerateKeyDialog(d, viewModel::onGenerateLabel, viewModel::generate, viewModel::dismissDialog)
        }

        is SettingsDialog.Import -> {
            ImportKeyDialog(
                d.form,
                onChange = { label, pem, pass -> viewModel.onImportChange(label, pem, pass) },
                onSource = viewModel::onImportSource,
                onPickFile = pickKeyFile,
                onConfirm = viewModel::import,
                onDismiss = viewModel::dismissDialog,
            )
        }

        is SettingsDialog.Delete -> {
            DeleteKeyDialog(d.item, viewModel::confirmDelete, viewModel::dismissDialog)
        }

        null -> {
            Unit
        }
    }
}

@SuppressLint("LocalContextGetResourceValueCall", "LocalContextResourcesRead") // one-shot event text, not displayed state
@Composable
private fun LicensesRoute(navigator: Navigator) {
    val context = LocalContext.current
    val notices =
        remember(context) {
            val text =
                context.resources
                    .openRawResource(
                        R.raw.third_party_notices,
                    ).bufferedReader()
                    .use { it.readText() }
            parseNotices(text)
        }
    LicensesScreen(notices, onBack = navigator::back)
}

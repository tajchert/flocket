package com.mtajchert.flocket.feature.onboarding.impl

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mtajchert.flocket.core.ui.component.rememberKeyFilePicker
import com.mtajchert.flocket.core.ui.nav.EntryProviderInstaller
import com.mtajchert.flocket.core.ui.nav.Navigator
import com.mtajchert.flocket.feature.onboarding.api.OnboardingRoute
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/** Contributes the onboarding destination to the app's Navigation 3 graph. */
@Module
@InstallIn(SingletonComponent::class)
object OnboardingNavigationModule {
    @Provides
    @IntoSet
    fun onboardingEntries(): EntryProviderInstaller =
        EntryProviderInstaller { scope, navigator ->
            scope.entry<OnboardingRoute> { OnboardingRoute(navigator) }
        }
}

@Composable
private fun OnboardingRoute(
    navigator: Navigator,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    val helpUrl = stringResource(R.string.connect_install_url)
    val pickKeyFile = rememberKeyFilePicker(viewModel::onImportFilePicked, viewModel::onImportFileFailed)
    LaunchedEffect(state.finished) { if (state.finished) navigator.goHome() }
    OnboardingScreen(
        state = state,
        actions =
            OnboardingActions(
                onNext = viewModel::next,
                onBack = {
                    // Leaving the first page closes the flow instead.
                    if (state.canGoBack) viewModel.back() else navigator.back()
                },
                onGenerateKey = viewModel::generateKey,
                onToggleImport = viewModel::toggleImport,
                onImportPem = viewModel::onImportPem,
                onImportSource = viewModel::onImportSource,
                onPickKeyFile = pickKeyFile,
                onImportPassphrase = viewModel::onImportPassphrase,
                onImportKey = viewModel::importKey,
                onUsePassword = viewModel::usePassword,
                onFormChange = viewModel::onFormChange,
                onTrust = viewModel::trustHostKey,
                onDismissTrust = viewModel::dismissTrust,
                onDismissChanged = viewModel::dismissChanged,
                onForgetPinned = viewModel::forgetPinnedKey,
                onSubmitPassword = viewModel::submitPassword,
                onDismissPassword = viewModel::dismissPassword,
                onErrorAction = { if (viewModel.onErrorAction(it)) uriHandler.openUri(helpUrl) },
                onEditHost = viewModel::editHost,
                onSkip = viewModel::skip,
            ),
    )
}

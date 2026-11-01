package com.mtajchert.flocket.feature.onboarding.impl

import android.content.res.Configuration
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.mtajchert.flocket.core.common.error.AppError
import com.mtajchert.flocket.core.designsystem.preview.PreviewFormFactors
import com.mtajchert.flocket.core.designsystem.preview.PreviewLargeFont
import com.mtajchert.flocket.core.designsystem.theme.FlocketTheme
import com.mtajchert.flocket.core.model.ConnectionState
import com.mtajchert.flocket.core.testing.SampleData
import com.mtajchert.flocket.core.ui.error.toUiError
import com.mtajchert.flocket.core.ui.hostform.HostFormState

private const val NOW = SampleData.NOW_MS

@Preview(
    name = "Phone dark",
    showBackground = true,
    widthDp = 412,
    heightDp = 892,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    apiLevel = 36,
)
private annotation class PreviewPhoneDark

@Preview(name = "Phone", showBackground = true, widthDp = 412, heightDp = 892, apiLevel = 36)
private annotation class PreviewPhone

@Composable
private fun Shot(state: OnboardingUiState) =
    FlocketTheme(dynamicColor = false) { Surface { OnboardingScreen(state, OnboardingActions(), nowMs = NOW) } }

private fun at(step: OnboardingStep) = OnboardingUiState(step = step)

@PreviewTest
@PreviewFormFactors
@PreviewLargeFont
@PreviewPhoneDark
@Composable
private fun OnboardingWelcomeScreenshot() = Shot(at(OnboardingStep.Welcome))

@PreviewTest
@PreviewPhone
@PreviewPhoneDark
@Composable
private fun OnboardingPrerequisitesScreenshot() = Shot(at(OnboardingStep.Prerequisites))

@PreviewTest
@PreviewPhone
@Composable
private fun OnboardingKeyInitialScreenshot() = Shot(at(OnboardingStep.DeviceKey))

@PreviewTest
@PreviewFormFactors
@PreviewLargeFont
@Composable
private fun OnboardingKeyReadyScreenshot() =
    Shot(
        OnboardingUiState(
            step = OnboardingStep.DeviceKey,
            keys = listOf(SampleData.deviceKey),
            form = HostFormState(keyAlias = SampleData.deviceKey.alias),
        ),
    )

@PreviewTest
@PreviewFormFactors
@PreviewPhoneDark
@Composable
private fun OnboardingAddHostScreenshot() =
    Shot(
        OnboardingUiState(
            step = OnboardingStep.AddHost,
            keys = listOf(SampleData.deviceKey),
            form =
                HostFormState(
                    label = "Studio Mac",
                    hostname = "studio.tail1234.ts.net",
                    username = "mtajchert",
                    keyAlias = "key-1",
                ),
        ),
    )

@PreviewTest
@PreviewPhone
@Composable
private fun OnboardingConnectingScreenshot() =
    Shot(
        OnboardingUiState(
            step = OnboardingStep.Connect,
            connect = ConnectUiState("Studio Mac", ConnectionState.Connecting(1)),
        ),
    )

@PreviewTest
@PreviewPhone
@PreviewPhoneDark
@Composable
private fun OnboardingConnectErrorScreenshot() =
    Shot(
        OnboardingUiState(
            step = OnboardingStep.Connect,
            connect =
                ConnectUiState(
                    "Studio Mac",
                    ConnectionState.NeedsAttention(
                        AppError.CoreNotFound(listOf("/usr/local/bin/hc"), "hc: command not found"),
                    ),
                    error = AppError.CoreNotFound(listOf("/usr/local/bin/hc"), "hc: command not found").toUiError(),
                ),
        ),
    )

@PreviewTest
@PreviewPhone
@Composable
private fun OnboardingConnectedScreenshot() =
    Shot(
        OnboardingUiState(
            step = OnboardingStep.Connect,
            connect =
                ConnectUiState(
                    "Studio Mac",
                    ConnectionState.Online(SampleData.coreInfo),
                    coreName = "studio-mac",
                    connected = true,
                ),
        ),
    )

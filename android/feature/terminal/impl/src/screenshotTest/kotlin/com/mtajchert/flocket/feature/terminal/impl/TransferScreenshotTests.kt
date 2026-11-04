package com.mtajchert.flocket.feature.terminal.impl

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.mtajchert.flocket.core.common.error.AppError
import com.mtajchert.flocket.core.designsystem.theme.FlocketTheme
import com.mtajchert.flocket.core.model.ConnectionState
import com.mtajchert.flocket.core.testing.SampleData
import com.mtajchert.flocket.core.ui.error.toUiError
import com.mtajchert.flocket.feature.terminal.impl.transfer.ClipboardOffer
import com.mtajchert.flocket.feature.terminal.impl.transfer.PasteChooserContent
import com.mtajchert.flocket.feature.terminal.impl.transfer.SendToFolderDialog
import com.mtajchert.flocket.feature.terminal.impl.transfer.SendToFolderState
import com.mtajchert.flocket.feature.terminal.impl.transfer.TransferPhase

@Preview(name = "Phone", showBackground = true, widthDp = 412, heightDp = 500, apiLevel = 36)
private annotation class PreviewTransferPhone

@Preview(
    name = "Phone dark",
    showBackground = true,
    widthDp = 412,
    heightDp = 500,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    apiLevel = 36,
)
private annotation class PreviewTransferDark

private val liveState =
    TerminalUiState(
        address = "t1",
        hostLabel = "Studio Mac",
        agentLabel = "claude",
        title = "refactor-auth",
        connection = ConnectionState.Online(SampleData.coreInfo),
        phase = TerminalPhase.Live,
        streamConnected = true,
        isOwner = true,
    )

@Composable
private fun Themed(content: @Composable () -> Unit) {
    FlocketTheme(darkTheme = isSystemInDarkTheme(), dynamicColor = false) {
        Surface(color = MaterialTheme.colorScheme.surface) { content() }
    }
}

@Composable
private fun StripShot(phase: TransferPhase) {
    Themed {
        TerminalScreen(
            state = liveState.copy(showExtraKeys = false),
            actions = TerminalActions(),
            transfer = phase,
        ) { modifier -> Box(modifier.fillMaxSize()) }
    }
}

@PreviewTest
@PreviewTransferPhone
@PreviewTransferDark
@Composable
private fun PasteChooserWithImageScreenshot() =
    Themed {
        PasteChooserContent(ClipboardOffer(hasText = false, hasImage = true), {}, {}, {}, {})
    }

@PreviewTest
@PreviewTransferPhone
@Composable
private fun PasteChooserTextScreenshot() =
    Themed {
        PasteChooserContent(ClipboardOffer(hasText = true, hasImage = false), {}, {}, {}, {})
    }

@PreviewTest
@PreviewTransferPhone
@PreviewTransferDark
@Composable
private fun TransferUploadingScreenshot() = StripShot(TransferPhase.Uploading("screenshot-2026-11-01.png"))

@PreviewTest
@PreviewTransferPhone
@PreviewTransferDark
@Composable
private fun TransferOutcomeUnknownScreenshot() = StripShot(TransferPhase.OutcomeUnknown("screenshot-2026-11-01.png"))

@PreviewTest
@PreviewTransferPhone
@Composable
private fun TransferFailedScreenshot() =
    StripShot(TransferPhase.Failed(AppError.FileTooLarge(8L * 1024 * 1024).toUiError()))

@PreviewTest
@PreviewTransferPhone
@PreviewTransferDark
@Composable
private fun SendToFolderDialogScreenshot() =
    Themed {
        SendToFolderDialog(
            SendToFolderState("notes.txt", "/Users/mtajchert/code/flocket"),
            { _, _ -> },
            {},
            {},
        )
    }

@PreviewTest
@PreviewTransferPhone
@PreviewTransferDark
@Composable
private fun SendToFolderExistsScreenshot() =
    Themed {
        SendToFolderDialog(
            SendToFolderState("notes.txt", "/Users/mtajchert/code/flocket", exists = true),
            { _, _ -> },
            {},
            {},
        )
    }

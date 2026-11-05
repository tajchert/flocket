package com.mtajchert.flocket.feature.terminal.impl.share

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.mtajchert.flocket.core.common.error.AppError
import com.mtajchert.flocket.core.designsystem.theme.FlocketTheme
import com.mtajchert.flocket.core.model.ConnectionState
import com.mtajchert.flocket.core.testing.SampleData
import com.mtajchert.flocket.core.ui.error.toUiError
import com.mtajchert.flocket.feature.terminal.impl.TerminalActions
import com.mtajchert.flocket.feature.terminal.impl.TerminalPhase
import com.mtajchert.flocket.feature.terminal.impl.TerminalScreen
import com.mtajchert.flocket.feature.terminal.impl.TerminalUiState
import com.mtajchert.flocket.feature.terminal.impl.transfer.TransferPhase

@Preview(name = "Phone", showBackground = true, widthDp = 412, heightDp = 892, apiLevel = 36)
private annotation class PreviewPhone

@Preview(
    name = "Phone dark",
    showBackground = true,
    widthDp = 412,
    heightDp = 892,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    apiLevel = 36,
)
private annotation class PreviewPhoneDark

@Preview(name = "Short", showBackground = true, widthDp = 412, heightDp = 160, apiLevel = 36)
private annotation class PreviewShort

private const val NOW = 1_800_000_000L

private val one =
    ShareItem(
        id = "s1",
        paths = listOf("/Users/dev/code/flocket/out/report.md"),
        machine = null,
        note = "Summary of the auth refactor and what is left",
        atEpochSeconds = NOW - 120,
    )
private val many =
    ShareItem(
        id = "s2",
        paths = listOf("/srv/builds/shot-1.png", "/srv/builds/shot-2.png", "/srv/builds/log.txt"),
        machine = "rohan",
        note = null,
        atEpochSeconds = NOW - 3_600,
    )

@Composable
private fun Themed(content: @Composable () -> Unit) {
    FlocketTheme(darkTheme = isSystemInDarkTheme(), dynamicColor = false) {
        Surface { content() }
    }
}

@Composable
private fun Viewer(
    body: ViewerBody,
    share: ShareItem = one,
    path: String? = one.paths.first(),
    size: Long? = null,
    canPaste: Boolean = true,
) = Themed {
    ShareViewerContent(share, path, body, size, canPaste, ShareActions(), onSave = {}, onShare = {})
}

private fun sampleImage() =
    Bitmap
        .createBitmap(800, 500, Bitmap.Config.ARGB_8888)
        .also {
            Canvas(it).drawRect(
                0f,
                0f,
                800f,
                500f,
                Paint().apply {
                    shader =
                        LinearGradient(
                            0f,
                            0f,
                            800f,
                            500f,
                            0xFF1565C0.toInt(),
                            0xFF66BB6A.toInt(),
                            Shader.TileMode.CLAMP,
                        )
                },
            )
        }.asImageBitmap()

@PreviewTest
@PreviewShort
@Composable
private fun ShareCardOneScreenshot() = Themed { Column { ShareCard(one, ShareActions()) } }

@PreviewTest
@PreviewShort
@Composable
private fun ShareCardManyScreenshot() = Themed { Column { ShareCard(many, ShareActions()) } }

@PreviewTest
@PreviewPhone
@Composable
private fun SharedFilesSheetScreenshot() =
    Themed {
        SharedFilesContent(
            listOf(one, many),
            NOW,
            ShareActions(),
            downloading =
                mapOf(one.paths.first() to 0.25f),
        )
    }

@PreviewTest
@PreviewPhone
@Composable
private fun ShareViewerLoadingScreenshot() = Viewer(body = ViewerBody.Loading())

@PreviewTest
@PreviewPhone
@Composable
private fun ViewerDownloadingScreenshot() =
    Viewer(
        path = "/Users/dev/build/app-release.apk",
        body = ViewerBody.Loading(bytes = 45_000_000L, size = 180_000_000L),
    )

@PreviewTest
@PreviewPhone
@Composable
private fun ViewerFailedResumeScreenshot() =
    Viewer(
        path = "/Users/dev/build/app-release.apk",
        body = ViewerBody.Failed(AppError.ConnectionLost.toUiError(), canResume = true),
    )

@PreviewTest
@PreviewPhone
@Composable
private fun ViewerApkDetailScreenshot() =
    Viewer(
        path = "/Users/dev/build/app-release.apk",
        body = ViewerBody.Apk(ApkInfo("Demo app", "com.example.demo", "1.2.0")),
        size = 18_400_000L,
    )

@PreviewTest
@PreviewPhone
@PreviewPhoneDark
@Composable
private fun ShareViewerImageScreenshot() =
    Viewer(share = many, path = many.paths.first(), body = ViewerBody.Image(sampleImage()))

@PreviewTest
@PreviewPhone
@Composable
private fun ShareViewerTextScreenshot() =
    Viewer(
        body =
            ViewerBody.Text(
                listOf(
                    "# Auth refactor",
                    "",
                    "- moved the token store behind `TokenStore`",
                    "- 42 tests pass",
                    "",
                    "TODO: rotate keys on logout",
                ),
                cut = false,
            ),
    )

@PreviewTest
@PreviewPhone
@Composable
private fun ShareViewerOtherScreenshot() =
    Viewer(path = "/Users/dev/build/app-release.apk", body = ViewerBody.Other, size = 18_400_000L)

@PreviewTest
@PreviewPhone
@PreviewPhoneDark
@Composable
private fun ShareViewerMissingScreenshot() =
    Viewer(body = ViewerBody.Failed(AppError.FileMissing(one.paths.first()).toUiError()), canPaste = false)

@PreviewTest
@PreviewPhone
@Composable
private fun ShareViewerFileListScreenshot() = Viewer(share = many, path = null, body = ViewerBody.Files(many.paths))

@PreviewTest
@PreviewPhoneDark
@Composable
private fun ShareCardOverTerminalScreenshot() =
    FlocketTheme(darkTheme = true, dynamicColor = false) {
        TerminalScreen(
            state =
                TerminalUiState(
                    address = "t1",
                    hostLabel = "Studio Mac",
                    agentLabel = "claude",
                    connection = ConnectionState.Online(SampleData.coreInfo),
                    phase = TerminalPhase.Live,
                    streamConnected = true,
                    isOwner = true,
                    showExtraKeys = false,
                ),
            actions = TerminalActions(),
            shares = SharesUi(card = one, all = listOf(one)),
        ) { modifier -> Box(modifier.background(Color(0xFF101418))) }
    }

@PreviewTest
@PreviewPhoneDark
@Composable
private fun OverlaysTogetherScreenshot() =
    FlocketTheme(darkTheme = true, dynamicColor = false) {
        TerminalScreen(
            state =
                TerminalUiState(
                    address = "t1",
                    hostLabel = "Studio Mac",
                    agentLabel = "claude",
                    connection = ConnectionState.Online(SampleData.coreInfo),
                    phase = TerminalPhase.Live,
                    streamConnected = false,
                    showExtraKeys = false,
                ),
            actions = TerminalActions(),
            shares = SharesUi(card = one, all = listOf(one)),
            transfer = TransferPhase.Uploading("screenshot.png"),
        ) { modifier -> Box(modifier.background(Color(0xFF101418))) }
    }

@PreviewTest
@PreviewPhone
@PreviewPhoneDark
@Composable
private fun ApkInstallDialogScreenshot() =
    Themed { ApkInstallDialog(ApkInfo("Demo app", "com.example.demo", "1.2.0"), onInstall = {}, onDismiss = {}) }

@PreviewTest
@PreviewPhone
@PreviewPhoneDark
@Composable
private fun ApkPermissionDialogScreenshot() = Themed { ApkPermissionDialog(onOpenSettings = {}, onDismiss = {}) }

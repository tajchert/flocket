package com.mtajchert.flocket.feature.terminal.impl.share

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.Immutable
import com.mtajchert.flocket.core.common.error.AppError
import com.mtajchert.flocket.core.common.result.AppResult
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/** What the install prompt shows. */
@Immutable
data class ApkInfo(
    val label: String,
    val packageName: String,
    val versionName: String?,
)

object ApkInstall {
    internal const val EXTRA_LABEL = "flocket.apk.label"
    private const val SPLIT_NAME = "base.apk"

    fun isApk(name: String): Boolean = name.endsWith(".apk", ignoreCase = true)

    /** Reads label/package/version from the file; null when it is not a valid APK. Blocking: call on IO. */
    fun readInfo(
        context: Context,
        file: File,
    ): ApkInfo? {
        val pm = context.packageManager
        val pkg =
            try {
                pm.getPackageArchiveInfo(file.path, 0)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } ?: return null
        val app = pkg.applicationInfo
        val label =
            if (app == null) {
                pkg.packageName
            } else {
                app.sourceDir = file.path
                app.publicSourceDir = file.path
                app.loadLabel(pm).toString().ifBlank { pkg.packageName }
            }
        return ApkInfo(label = label, packageName = pkg.packageName, versionName = pkg.versionName)
    }

    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun permissionSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /** Streams [file] into a PackageInstaller session and commits it; the result arrives on [ApkInstallEvents]. Blocking: call on IO. */
    fun install(
        context: Context,
        file: File,
        info: ApkInfo,
    ): AppResult<Unit> {
        val installer = context.packageManager.packageInstaller
        var sessionId = -1
        return try {
            val size = file.length()
            val params =
                PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setSize(size)
                    setAppPackageName(info.packageName)
                }
            sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite(SPLIT_NAME, 0, size).use { out ->
                    file.inputStream().use { it.copyTo(out, 64 * 1024) }
                    session.fsync(out)
                }
                val callback =
                    PendingIntent.getBroadcast(
                        context,
                        sessionId,
                        Intent(context, ApkInstallReceiver::class.java).putExtra(EXTRA_LABEL, info.label),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                    )
                session.commit(callback.intentSender)
            }
            AppResult.Success(Unit)
        } catch (e: CancellationException) {
            abandon(installer, sessionId)
            throw e
        } catch (e: Exception) {
            abandon(installer, sessionId)
            AppResult.Failure(AppError.Storage(e.message ?: "Could not start the install"))
        }
    }

    private fun abandon(
        installer: PackageInstaller,
        sessionId: Int,
    ) {
        if (sessionId < 0) return
        try {
            installer.abandonSession(sessionId)
        } catch (_: Exception) {
            // Already finished or abandoned.
        }
    }
}

sealed interface ApkInstallResult {
    data class Installed(
        val label: String,
    ) : ApkInstallResult

    data class Failed(
        val label: String,
        val message: String?,
    ) : ApkInstallResult

    /** STATUS_FAILURE_ABORTED: the user declined. */
    data class Cancelled(
        val label: String,
    ) : ApkInstallResult
}

/** Process-wide; the screen collects it for a snackbar. */
object ApkInstallEvents {
    private val flow = MutableSharedFlow<ApkInstallResult>(extraBufferCapacity = 8)
    val results: SharedFlow<ApkInstallResult> = flow.asSharedFlow()

    internal fun emit(result: ApkInstallResult) {
        flow.tryEmit(result)
    }
}

/** PackageInstaller status + message to a result; null for PENDING_USER_ACTION. */
internal fun installResultOf(
    status: Int,
    message: String?,
    label: String,
): ApkInstallResult? =
    when (status) {
        PackageInstaller.STATUS_PENDING_USER_ACTION -> null
        PackageInstaller.STATUS_SUCCESS -> ApkInstallResult.Installed(label)
        PackageInstaller.STATUS_FAILURE_ABORTED -> ApkInstallResult.Cancelled(label)
        else -> ApkInstallResult.Failed(label, message)
    }

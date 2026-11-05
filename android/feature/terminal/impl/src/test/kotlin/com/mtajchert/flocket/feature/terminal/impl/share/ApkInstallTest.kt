package com.mtajchert.flocket.feature.terminal.impl.share

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ApkInstallTest {
    @get:Rule val tmp = TemporaryFolder()
    private val context: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `isApk matches any case and only the extension`() {
        assertTrue(ApkInstall.isApk("a.apk"))
        assertTrue(ApkInstall.isApk("A.APK"))
        assertFalse(ApkInstall.isApk("apk"))
        assertFalse(ApkInstall.isApk("a.apk.txt"))
        assertFalse(ApkInstall.isApk("a.aab"))
    }

    @Test
    fun `installResultOf maps statuses`() {
        assertEquals(
            ApkInstallResult.Installed("Demo"),
            installResultOf(PackageInstaller.STATUS_SUCCESS, null, "Demo"),
        )
        assertEquals(
            ApkInstallResult.Cancelled("Demo"),
            installResultOf(PackageInstaller.STATUS_FAILURE_ABORTED, "x", "Demo"),
        )
        assertEquals(
            ApkInstallResult.Failed("Demo", "INSTALL_FAILED_UPDATE_INCOMPATIBLE"),
            installResultOf(
                PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
                "INSTALL_FAILED_UPDATE_INCOMPATIBLE",
                "Demo",
            ),
        )
        assertNull(installResultOf(PackageInstaller.STATUS_PENDING_USER_ACTION, null, "Demo"))
    }

    @Test
    fun `permission settings intent targets our package`() {
        val intent = ApkInstall.permissionSettingsIntent(context)
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, intent.action)
        assertEquals("package:${context.packageName}", intent.dataString)
    }

    @Test
    fun `readInfo of a non-apk file is null`() {
        val file = tmp.newFile("notes.apk").also { it.writeText("not a zip") }
        assertNull(ApkInstall.readInfo(context, file))
    }

    @Test
    fun `receiver starts the confirmation for pending user action`() {
        val confirm = Intent("test.CONFIRM")
        val intent =
            Intent(context, ApkInstallReceiver::class.java)
                .putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_PENDING_USER_ACTION)
                .putExtra(Intent.EXTRA_INTENT, confirm)
        ApkInstallReceiver().onReceive(context, intent)
        val started = shadowOf(context).nextStartedActivity
        assertEquals("test.CONFIRM", started.action)
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun `receiver emits the result`() =
        kotlinx.coroutines.test.runTest {
            ApkInstallEvents.results.test {
                val intent =
                    Intent(context, ApkInstallReceiver::class.java)
                        .putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_SUCCESS)
                        .putExtra(ApkInstall.EXTRA_LABEL, "Demo")
                ApkInstallReceiver().onReceive(context, intent)
                assertEquals(ApkInstallResult.Installed("Demo"), awaitItem())
            }
        }

    @Test
    fun `install writes the file into a session and commits`() {
        val file = tmp.newFile("demo.apk").also { it.writeBytes(ByteArray(1000) { i -> i.toByte() }) }
        val info = ApkInfo("Demo", "com.example.demo", "1.0")
        val result = ApkInstall.install(context, file, info)
        assertTrue(result.toString(), result is com.mtajchert.flocket.core.common.result.AppResult.Success)
    }
}

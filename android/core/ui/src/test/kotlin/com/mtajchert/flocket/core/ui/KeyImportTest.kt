package com.mtajchert.flocket.core.ui

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.mtajchert.flocket.core.designsystem.theme.FlocketTheme
import com.mtajchert.flocket.core.ui.component.KeySource
import com.mtajchert.flocket.core.ui.component.KeySourceFields
import com.mtajchert.flocket.core.ui.component.MAX_KEY_FILE_CHARS
import com.mtajchert.flocket.core.ui.component.keyLabelFromFileName
import com.mtajchert.flocket.core.ui.component.looksLikePrivateKey
import com.mtajchert.flocket.core.ui.component.readKeyFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class KeyImportTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun `labels come from the file name without its extension`() {
        assertEquals("id_ed25519", keyLabelFromFileName("id_ed25519"))
        assertEquals("work", keyLabelFromFileName("work.pem"))
        assertEquals(".hidden", keyLabelFromFileName(".hidden"))
        assertNull(keyLabelFromFileName(null))
        assertNull(keyLabelFromFileName("  "))
    }

    @Test
    fun `only private key headers pass the pre-check`() {
        assertTrue(looksLikePrivateKey("-----BEGIN OPENSSH PRIVATE KEY-----\nabc"))
        assertTrue(looksLikePrivateKey("-----BEGIN RSA PRIVATE KEY-----\nabc"))
        assertFalse(looksLikePrivateKey("ssh-ed25519 AAAA me@mac"))
    }

    @Test
    fun `a key file is read whole with its display name, and oversized or empty files are refused`() {
        val dir =
            File(
                ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir,
                "keys",
            ).apply { mkdirs() }
        FileProvider.files = dir
        Robolectric.setupContentProvider(FileProvider::class.java, AUTHORITY)
        val resolver = ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver

        val key = "-----BEGIN OPENSSH PRIVATE KEY-----\n" + "a".repeat(5000) + "\n-----END OPENSSH PRIVATE KEY-----\n"
        File(dir, "id_ed25519").writeText(key)
        val picked = readKeyFile(resolver, Uri.parse("content://$AUTHORITY/id_ed25519"))!!
        assertEquals("id_ed25519", picked.name)
        assertEquals(key, picked.text)
        assertFalse(picked.toString().contains("PRIVATE"))

        File(dir, "big").writeText("x".repeat(MAX_KEY_FILE_CHARS + 1))
        assertNull(readKeyFile(resolver, Uri.parse("content://$AUTHORITY/big")))
        File(dir, "empty").writeText("")
        assertNull(readKeyFile(resolver, Uri.parse("content://$AUTHORITY/empty")))
        assertNull(readKeyFile(resolver, Uri.parse("content://$AUTHORITY/missing")))
    }

    @Test
    fun `file is the default source and paste is one tap away`() {
        var source = KeySource.File
        var picks = 0
        rule.setContent {
            FlocketTheme {
                KeySourceFields(
                    source = source,
                    fileName = null,
                    pem = "",
                    notAKey = false,
                    onPickFile = { picks++ },
                    onPemChange = {},
                    onSourceChange = { source = it },
                )
            }
        }
        rule.onNodeWithText("Choose key file").performClick()
        assertEquals(1, picks)
        rule.onNodeWithText("Paste key text instead").performClick()
        assertEquals(KeySource.Paste, source)
    }

    @Test
    fun `a picked file shows its name, never the key, and flags a public key`() {
        rule.setContent {
            FlocketTheme {
                KeySourceFields(
                    source = KeySource.File,
                    fileName = "id_ed25519.pub",
                    pem = "ssh-ed25519 AAAA",
                    notAKey = true,
                    onPickFile = {},
                    onPemChange = {},
                    onSourceChange = {},
                )
            }
        }
        rule.onNodeWithText("id_ed25519.pub").assertIsDisplayed()
        rule.onNodeWithText("This isn’t a private key. Choose the file without .pub.").assertIsDisplayed()
        rule.onNodeWithText("Choose a different file").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithTextCount("ssh-ed25519 AAAA"))
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String) =
        onAllNodes(
            androidx.compose.ui.test
                .hasText(text),
        ).fetchSemanticsNodes().size

    /** Serves files from [files] like a document provider: a display name plus a readable stream. */
    class FileProvider : ContentProvider() {
        override fun onCreate() = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor =
            MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply {
                addRow(arrayOf(uri.lastPathSegment))
            }

        override fun openFile(
            uri: Uri,
            mode: String,
        ): ParcelFileDescriptor {
            val file = File(files, uri.lastPathSegment!!)
            if (!file.exists()) throw java.io.FileNotFoundException(uri.toString())
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        override fun getType(uri: Uri): String? = null

        override fun insert(
            uri: Uri,
            values: ContentValues?,
        ): Uri? = null

        override fun delete(
            uri: Uri,
            selection: String?,
            selectionArgs: Array<out String>?,
        ) = 0

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ) = 0

        companion object {
            lateinit var files: File
        }
    }

    private companion object {
        const val AUTHORITY = "com.mtajchert.flocket.test.keys"
    }
}

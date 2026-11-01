package com.mtajchert.flocket.core.terminal.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.mtajchert.flocket.core.terminal.controller.TerminalController
import com.mtajchert.flocket.core.terminal.fake.FakeTerminalEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TerminalViewPasteTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clipboard = context.getSystemService(ClipboardManager::class.java)
    private val uri = Uri.parse("content://media/external/images/1")
    private lateinit var view: TerminalView
    private val images = mutableListOf<Pair<Uri, String?>>()

    @Before
    fun setUp() {
        view = TerminalView(context)
        view.bind(
            TerminalController(
                engineFactory = { c, r -> FakeTerminalEngine(c, r) },
                theme = FakeTerminalEngine.DEFAULT_THEME,
                frameClock = { awaitCancellation() },
                engineDispatcher = UnconfinedTestDispatcher(),
            ),
        )
    }

    private fun imageClip() =
        ClipData(
            ClipDescription("image", arrayOf("image/png")),
            ClipData.Item(uri),
        )

    @Test
    fun `image only clip calls onPasteImage`() {
        view.onPasteImage = { u, mime -> images += u to mime }
        clipboard.setPrimaryClip(imageClip())
        assertTrue(view.pasteClipboard())
        assertEquals(listOf(uri to "image/png"), images)
    }

    @Test
    fun `clip with text and image pastes the text`() {
        view.onPasteImage = { u, mime -> images += u to mime }
        clipboard.setPrimaryClip(
            ClipData(
                ClipDescription("both", arrayOf("image/png", "text/plain")),
                ClipData.Item("hello", null, uri),
            ),
        )
        assertTrue(view.pasteClipboard())
        assertTrue(images.isEmpty())
    }

    @Test
    fun `without a listener an image clip keeps the old behaviour`() {
        view.onPasteImage = null
        clipboard.setPrimaryClip(imageClip())
        // Old behaviour: the item is coerced to text (a content URI has none), nothing goes to the image hook.
        view.pasteClipboard()
        assertTrue(images.isEmpty())
    }
}

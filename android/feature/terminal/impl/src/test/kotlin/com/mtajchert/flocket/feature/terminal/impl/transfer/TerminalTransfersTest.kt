package com.mtajchert.flocket.feature.terminal.impl.transfer

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mtajchert.flocket.core.common.error.AppError
import com.mtajchert.flocket.core.common.result.AppResult
import com.mtajchert.flocket.core.model.ConnectionState
import com.mtajchert.flocket.core.model.MAX_TRANSFER_BYTES
import com.mtajchert.flocket.core.model.PutReceipt
import com.mtajchert.flocket.core.model.TerminalAddress
import com.mtajchert.flocket.core.session.ExecOutcome
import com.mtajchert.flocket.core.testing.FakeCoreSession
import com.mtajchert.flocket.core.testing.SampleData
import com.mtajchert.flocket.core.ui.error.UiText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime

private class FakeUriSource : UriSource {
    val files = mutableMapOf<Uri, Pair<PickedFile, ByteArray>>()
    val reads = mutableListOf<Uri>()

    fun add(
        uri: Uri,
        name: String,
        bytes: ByteArray = byteArrayOf(1, 2, 3),
        mime: String? = null,
        reportedSize: Long? = bytes.size.toLong(),
    ) {
        files[uri] = PickedFile(name, mime, reportedSize) to bytes
    }

    override suspend fun describe(uri: Uri): AppResult<PickedFile> =
        files[uri]?.let { AppResult.Success(it.first) } ?: AppResult.Failure(AppError.FileMissing(uri.toString()))

    override suspend fun read(
        uri: Uri,
        maxBytes: Long,
    ): AppResult<ByteArray> {
        reads += uri
        val bytes = files[uri]?.second ?: return AppResult.Failure(AppError.FileMissing(uri.toString()))
        return if (bytes.size >
            maxBytes
        ) {
            AppResult.Failure(AppError.FileTooLarge(maxBytes))
        } else {
            AppResult.Success(bytes)
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class TerminalTransfersTest {
    private val png = Uri.parse("content://media/a.png")
    private val src = FakeUriSource().apply { add(png, "a.png") }
    private val session = FakeCoreSession(SampleData.studio.endpoint, SampleData.studioOnline)
    private val pasted = mutableListOf<String>()
    private val messages = mutableListOf<UiText>()

    private fun TestScope.transfers(
        address: String = "t1",
        now: LocalDateTime = LocalDateTime.of(2026, 11, 1, 9, 5, 7),
    ) = TerminalTransfers(
        scope = backgroundScope,
        address = TerminalAddress(address),
        session = { session },
        uris = src,
        paste = { pasted += it },
        notify = { messages += it },
        now = { now },
    )

    private val call get() = session.transferCalls

    @Test
    fun `pasting a picked image uploads then pastes path and space`() =
        runTest(UnconfinedTestDispatcher()) {
            val t = transfers()
            t.pasteUri(png)
            assertEquals(listOf("put:t1:a.png:3"), call)
            assertEquals(listOf("/fake/files/t1/a.png "), pasted)
            assertEquals(TransferPhase.Done("/fake/files/t1/a.png"), t.phase.value)
        }

    @Test
    fun `clipboard image gets a timestamped name from its mime type`() =
        runTest(UnconfinedTestDispatcher()) {
            val clip = Uri.parse("content://clip/1")
            src.add(clip, "ignored")
            transfers().pasteClipboardImage(clip, "image/jpeg")
            transfers().pasteClipboardImage(clip, "image/webp")
            transfers().pasteClipboardImage(clip, null)
            assertEquals(
                listOf(
                    "put:t1:paste-20261101-090507.jpg:3",
                    "put:t1:paste-20261101-090507.webp:3",
                    "put:t1:paste-20261101-090507.bin:3",
                ),
                call,
            )
        }

    @Test
    fun `outcome unknown pastes nothing and offers retry`() =
        runTest(UnconfinedTestDispatcher()) {
            session.putHandler = { _, _, _ -> AppResult.Failure(AppError.OutcomeUnknown("terminal.put")) }
            val t = transfers()
            t.pasteUri(png)
            assertEquals(TransferPhase.OutcomeUnknown("a.png"), t.phase.value)
            assertTrue(pasted.isEmpty())
        }

    @Test
    fun `retry uploads again only when asked`() =
        runTest(UnconfinedTestDispatcher()) {
            var first = true
            session.putHandler = { a, n, b ->
                if (first) {
                    first = false
                    AppResult.Failure(AppError.OutcomeUnknown("terminal.put"))
                } else {
                    AppResult.Success(
                        com.mtajchert.flocket.core.model
                            .PutReceipt("/p/$n", b.size.toLong()),
                    )
                }
            }
            val t = transfers()
            t.pasteUri(png)
            assertEquals(1, call.size)
            t.retry()
            assertEquals(2, call.size)
            assertEquals(listOf("/p/a.png "), pasted)
            // Nothing left to retry once it succeeded.
            t.retry()
            assertEquals(2, call.size)
        }

    @Test
    fun `retry re-reads the uri and shows the mapped error when it is gone`() =
        runTest(UnconfinedTestDispatcher()) {
            session.putHandler = { _, _, _ -> AppResult.Failure(AppError.OutcomeUnknown("terminal.put")) }
            val t = transfers()
            t.pasteUri(png)
            src.files.remove(png)
            t.retry()
            assertTrue(t.phase.value is TransferPhase.Failed)
            assertEquals(1, call.size)
        }

    @Test
    fun `file over 8 MB is refused before upload`() =
        runTest(UnconfinedTestDispatcher()) {
            val big = Uri.parse("content://docs/big")
            src.add(big, "big.bin", ByteArray(0), reportedSize = MAX_TRANSFER_BYTES + 1)
            val t = transfers()
            t.pasteUri(big)
            val p = t.phase.value as TransferPhase.Failed
            assertEquals(AppError.FileTooLarge(MAX_TRANSFER_BYTES), p.error.source)
            assertTrue(src.reads.isEmpty())
            assertTrue(call.isEmpty())
        }

    @Test
    fun `unknown size that turns out too large is refused before upload`() =
        runTest(UnconfinedTestDispatcher()) {
            val big = Uri.parse("content://docs/big")
            src.add(big, "big.bin", ByteArray((MAX_TRANSFER_BYTES + 1).toInt()), reportedSize = null)
            val t = transfers()
            t.pasteUri(big)
            assertTrue(t.phase.value is TransferPhase.Failed)
            assertTrue(call.isEmpty())
        }

    @Test
    fun `federated terminal uploads to the full address`() =
        runTest(UnconfinedTestDispatcher()) {
            transfers(address = "rohan/t3").pasteUri(png)
            assertEquals(listOf("put:rohan/t3:a.png:3"), call)
        }

    @Test
    fun `stale session disables paste and send`() =
        runTest(UnconfinedTestDispatcher()) {
            session.update { it.copy(connection = ConnectionState.Idle) }
            val t = transfers()
            t.pasteUri(png)
            assertTrue(t.phase.value is TransferPhase.Failed)
            t.prepareSendToFolder(png)
            assertNull(t.sendToFolder.value)
            assertTrue(call.isEmpty())
            assertTrue(src.reads.isEmpty())
        }

    @Test
    fun `send to folder targets displayCwd on the terminal's machine`() =
        runTest(UnconfinedTestDispatcher()) {
            session.execHandler = { _, _, _ -> AppResult.Success(ExecOutcome(1, ByteArray(0), "", false)) }
            val t = transfers(address = "t1")
            t.prepareSendToFolder(png)
            val state = t.sendToFolder.value!!
            assertEquals("/Users/mtajchert/code/flocket", state.folder)
            assertEquals("a.png", state.name)
            assertFalse(state.exists)
            t.confirmSendToFolder("a.png", replace = false)
            assertEquals(
                listOf(
                    "exec:null:test -e /Users/mtajchert/code/flocket/a.png:false",
                    "exec:null:test -e /Users/mtajchert/code/flocket/a.png:false",
                    "write:null:/Users/mtajchert/code/flocket/a.png:3",
                ),
                call,
            )
            assertNull(t.sendToFolder.value)
            assertEquals(1, messages.size)
        }

    @Test
    fun `existing target needs replace`() =
        runTest(UnconfinedTestDispatcher()) {
            session.execHandler = { _, _, _ -> AppResult.Success(ExecOutcome(0, ByteArray(0), "", false)) }
            val t = transfers()
            t.prepareSendToFolder(png)
            assertTrue(t.sendToFolder.value!!.exists)
            t.confirmSendToFolder("a.png", replace = false)
            assertTrue(call.none { it.startsWith("write:") })
            assertTrue(t.sendToFolder.value!!.exists)
            t.confirmSendToFolder("a.png", replace = true)
            assertEquals(1, call.count { it.startsWith("write:") })
        }

    @Test
    fun `send to folder never types into the terminal`() =
        runTest(UnconfinedTestDispatcher()) {
            session.execHandler = { _, _, _ -> AppResult.Success(ExecOutcome(1, ByteArray(0), "", false)) }
            val t = transfers()
            t.prepareSendToFolder(png)
            t.confirmSendToFolder("a.png", replace = false)
            assertEquals(1, call.count { it.startsWith("write:") })
            assertTrue(pasted.isEmpty())
        }

    @Test
    fun `send to folder outcome unknown rechecks the target and asks for replace`() =
        runTest(UnconfinedTestDispatcher()) {
            var execs = 0
            session.execHandler =
                { _, _, _ -> AppResult.Success(ExecOutcome(if (++execs >= 3) 0 else 1, ByteArray(0), "", false)) }
            session.writeHandler = { _, _, _ -> AppResult.Failure(AppError.OutcomeUnknown("file.write")) }
            val t = transfers()
            t.prepareSendToFolder(png)
            t.confirmSendToFolder("a.png", replace = false)
            val s = t.sendToFolder.value!!
            assertEquals(TransferPhase.OutcomeUnknown("a.png"), s.phase)
            assertTrue(s.exists)
        }

    @Test
    fun `cancel while uploading reports an unknown outcome`() =
        runTest(UnconfinedTestDispatcher()) {
            val gate = CompletableDeferred<Unit>()
            session.putHandler = { _, _, _ ->
                gate.await()
                AppResult.Failure(AppError.ConnectionLost)
            }
            val t = transfers()
            t.pasteUri(png)
            assertEquals(TransferPhase.Uploading("a.png"), t.phase.value)
            t.cancel()
            assertEquals(TransferPhase.OutcomeUnknown("a.png"), t.phase.value)
            assertTrue(pasted.isEmpty())
        }

    @Test
    fun `unclear existence check requires an explicit replace`() =
        runTest(UnconfinedTestDispatcher()) {
            session.execHandler = { _, _, _ -> AppResult.Success(ExecOutcome(2, ByteArray(0), "", false)) }
            val t = transfers()
            t.prepareSendToFolder(png)
            assertTrue(t.sendToFolder.value!!.checkFailed)
            t.confirmSendToFolder("a.png", replace = false)
            assertTrue(call.none { it.startsWith("write:") })
            assertTrue(t.sendToFolder.value!!.checkFailed)
            session.execHandler = { _, _, _ -> AppResult.Failure(AppError.ConnectionLost) }
            t.confirmSendToFolder("a.png", replace = true)
            assertEquals(1, call.count { it.startsWith("write:") })
        }

    @Test
    fun `failed existence call is also treated as unknown`() =
        runTest(UnconfinedTestDispatcher()) {
            session.execHandler = { _, _, _ -> AppResult.Failure(AppError.Timeout("exec")) }
            val t = transfers()
            t.prepareSendToFolder(png)
            assertTrue(t.sendToFolder.value!!.checkFailed)
        }

    @Test
    fun `pasted path escapes what a shell would split on`() =
        runTest(UnconfinedTestDispatcher()) {
            val odd = Uri.parse("content://docs/odd")
            src.add(odd, "my shot (1).png")
            session.putHandler = { _, n, b -> AppResult.Success(PutReceipt("/files/t1/$n", b.size.toLong())) }
            transfers().pasteUri(odd)
            assertEquals(listOf("/files/t1/my\\ shot\\ \\(1\\).png "), pasted)
        }

    @Test
    fun `plain path pastes unchanged`() {
        assertEquals("/a/b-c_d.9/e.png", TerminalTransfers.escapeForShell("/a/b-c_d.9/e.png"))
        assertEquals("a\\\$b\\`c\\;d", TerminalTransfers.escapeForShell("a\$b`c;d"))
        assertEquals("a~b#c", TerminalTransfers.escapeForShell("a~b#c"))
        assertEquals("x\\{a\\,b\\}.png", TerminalTransfers.escapeForShell("x{a,b}.png"))
    }

    @Test
    fun `second transfer while busy tells the user and keeps the running one`() =
        runTest(UnconfinedTestDispatcher()) {
            val gate = CompletableDeferred<Unit>()
            session.putHandler = { _, _, _ ->
                gate.await()
                AppResult.Failure(AppError.OutcomeUnknown("terminal.put"))
            }
            val t = transfers()
            t.pasteUri(png)
            t.prepareSendToFolder(png)
            t.pasteUri(png)
            assertEquals(2, messages.size)
            assertNull(t.sendToFolder.value)
            gate.complete(Unit)
            assertEquals(TransferPhase.OutcomeUnknown("a.png"), t.phase.value)
            t.retry()
            assertEquals(2, call.count { it.startsWith("put:") })
        }
}

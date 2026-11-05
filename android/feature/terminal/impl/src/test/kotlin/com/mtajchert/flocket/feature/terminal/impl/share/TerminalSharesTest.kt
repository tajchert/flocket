package com.mtajchert.flocket.feature.terminal.impl.share

import com.mtajchert.flocket.core.common.error.AppError
import com.mtajchert.flocket.core.model.AgentShare
import com.mtajchert.flocket.core.model.HostId
import com.mtajchert.flocket.core.model.TerminalAddress
import com.mtajchert.flocket.core.session.CoreSession
import com.mtajchert.flocket.core.session.DownloadEvent
import com.mtajchert.flocket.core.session.DownloadMethod
import com.mtajchert.flocket.core.session.DownloadResume
import com.mtajchert.flocket.core.testing.FakeCoreSession
import com.mtajchert.flocket.core.testing.FakeShareSeenRepository
import com.mtajchert.flocket.core.testing.MainDispatcherRule
import com.mtajchert.flocket.core.testing.SampleData
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalSharesTest {
    @get:Rule val main = MainDispatcherRule()

    @get:Rule val tmp = TemporaryFolder()

    private val now = 1_000_000L
    private var clock = now
    private val shares = MutableStateFlow<List<AgentShare>>(emptyList())
    private val firstReadAt = MutableStateFlow<Map<String?, Long>>(emptyMap())
    private val seen = FakeShareSeenRepository()
    private val session = FakeCoreSession(SampleData.studio.endpoint)
    private val pasted = mutableListOf<String>()

    private fun share(
        id: String,
        address: String = "t3",
        machine: String? = null,
        at: Long = now - 30,
        vararg paths: String = arrayOf("/work/out/$id.txt"),
    ) = AgentShare(id, TerminalAddress(address), machine, paths.toList(), "note $id", at)

    private val root by lazy { File(tmp.root, "shares") }

    private val resume = DownloadResume(null, "/work/out/a.txt", 5, 100)

    private fun started(
        size: Long = 5,
        from: Long = 0,
    ) = DownloadEvent.Started(size, DownloadMethod.SFTP, resume, from)

    private fun done(size: Long = 5) = DownloadEvent.Done(size, DownloadMethod.SFTP)

    /** A download that writes [bytes] to the target and ends with Done. */
    private fun writes(bytes: String) {
        session.downloadHandler = { _, _, target, _ ->
            flow {
                emit(started(bytes.length.toLong()))
                target.parentFile?.mkdirs()
                target.writeText(bytes)
                emit(done(bytes.length.toLong()))
            }
        }
    }

    private fun partOf(
        shareId: String = "a",
        name: String = "a.txt",
    ) = File(File(root, shareId), "$name.part")

    private fun TestScope.controller(
        address: String = "t3",
        repo: FakeShareSeenRepository = seen,
    ): ShareController =
        ShareController(
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            hostId = HostId("studio"),
            address = TerminalAddress(address),
            feed = combine(shares, firstReadAt) { s, f -> s to f },
            session = { session as CoreSession },
            seen = repo,
            pasteText = { pasted += it },
            sharesRoot = root,
            nowSeconds = { clock },
            io = UnconfinedTestDispatcher(testScheduler),
        ).also { runCurrent() }

    @Test
    fun `card shows only unseen shares of this terminal`() =
        runTest {
            // "rohan/t3" is a different terminal than "t3": same short address, different machine.
            shares.value =
                listOf(share("a"), share("r", address = "rohan/t3", machine = "rohan"), share("c", address = "t4"))
            val c = controller("t3")
            assertEquals(
                listOf("a"),
                c.ui.value.all
                    .map { it.id },
            )
            assertEquals(
                "a",
                c.ui.value.card
                    ?.id,
            )
            c.dismiss("a")
            runCurrent()
            assertNull(c.ui.value.card)
            assertEquals(
                "the sheet still lists it",
                listOf("a"),
                c.ui.value.all
                    .map { it.id },
            )
        }

    @Test
    fun `dismiss marks seen and survives recreation`() =
        runTest {
            shares.value = listOf(share("a"))
            val first = controller()
            first.dismiss("a")
            runCurrent()
            val second = controller()
            assertNull(second.ui.value.card)
            assertEquals(
                listOf("a"),
                second.ui.value.all
                    .map { it.id },
            )
        }

    @Test
    fun `first read hides entries older than ten minutes`() =
        runTest {
            shares.value = listOf(share("old", at = now - 601), share("fresh", at = now - 599))
            val c = controller()
            assertEquals(
                "fresh",
                c.ui.value.card
                    ?.id,
            )
            // A later arrival of an old-looking entry is not hidden: only the first read does that.
            shares.value = listOf(share("late", at = now - 3_000)) + shares.value
            runCurrent()
            assertEquals(
                "late",
                c.ui.value.card
                    ?.id,
            )
        }

    @Test
    fun `empty first read at T0 then a share then a late viewmodel still shows the card`() =
        runTest {
            // The session read an empty document at T0; the share arrived a minute later; the screen opens at T0+15.
            firstReadAt.value = mapOf(null to now)
            shares.value = listOf(share("new", at = now + 60))
            clock = now + 15 * 60
            val c = controller()
            assertEquals(
                "new",
                c.ui.value.card
                    ?.id,
            )
        }

    @Test
    fun `an empty first read records the source so later shares are not judged again`() =
        runTest {
            firstReadAt.value = mapOf(null to now)
            val early = controller()
            clock = now + 20 * 60
            shares.value = listOf(share("late", at = now - 3_000))
            runCurrent()
            assertEquals(
                "late",
                early.ui.value.card
                    ?.id,
            )
        }

    @Test
    fun `an entry older than ten minutes before the first read stays hidden`() =
        runTest {
            firstReadAt.value = mapOf(null to now)
            clock = now + 15 * 60
            shares.value = listOf(share("old", at = now - 601), share("fresh", at = now - 599))
            val c = controller()
            assertEquals(
                "fresh",
                c.ui.value.card
                    ?.id,
            )
        }

    @Test
    fun `opening downloads once and never before`() =
        runTest {
            shares.value = listOf(share("a"))
            writes("hello")
            val c = controller()
            assertTrue(session.transferCalls.isEmpty())
            c.open("a")
            runCurrent()
            assertEquals(listOf("download:null:/work/out/a.txt:false"), session.transferCalls)
            val file =
                c.ui.value.viewer
                    ?.file as FileLoad.Loaded
            assertEquals("hello", file.file.readText())
            assertEquals(File(File(root, "a"), "a.txt"), file.file)
            assertFalse(partOf().exists())
            assertFalse("not an APK", file.askInstall)
            assertTrue("opening marks it seen", c.ui.value.card == null)
        }

    @Test
    fun `progress updates loading bytes and size`() =
        runTest {
            shares.value = listOf(share("a"))
            val gate = CompletableDeferred<Unit>()
            session.downloadHandler = { _, _, _, _ ->
                flow {
                    emit(started(100))
                    emit(DownloadEvent.Progress(40, 100))
                    gate.await()
                }
            }
            val c = controller()
            c.open("a")
            runCurrent()
            assertEquals(
                FileLoad.Loading("/work/out/a.txt", 40, 100),
                c.ui.value.viewer
                    ?.file,
            )
            assertEquals(mapOf("/work/out/a.txt" to 0.4f), c.ui.value.downloading)
            gate.cancel()
        }

    @Test
    fun `closing the viewer keeps the download running and reopening shows progress`() =
        runTest {
            shares.value = listOf(share("a"))
            val gate = CompletableDeferred<Unit>()
            session.downloadHandler = { _, _, target, _ ->
                flow {
                    emit(started(100))
                    target.parentFile?.mkdirs()
                    target.writeText("x")
                    emit(DownloadEvent.Progress(10, 100))
                    gate.await()
                    emit(done(100))
                }
            }
            val c = controller()
            c.open("a")
            runCurrent()
            c.closeViewer()
            assertNull(c.ui.value.viewer)
            assertEquals(1, c.ui.value.downloading.size)
            c.open("a")
            runCurrent()
            assertEquals(
                FileLoad.Loading("/work/out/a.txt", 10, 100),
                c.ui.value.viewer
                    ?.file,
            )
            assertEquals("no second download", 1, session.transferCalls.size)
            gate.complete(Unit)
            runCurrent()
            assertTrue(
                c.ui.value.viewer
                    ?.file is FileLoad.Loaded,
            )
        }

    @Test
    fun `opening a different file cancels the running one and deletes its part file`() =
        runTest {
            shares.value =
                listOf(share("m", paths = arrayOf("/a/x.bin", "/a/y.txt")))
            val gate = CompletableDeferred<Unit>()
            session.downloadHandler = { _, path, target, _ ->
                flow {
                    emit(started(100))
                    target.parentFile?.mkdirs()
                    target.writeText("partial")
                    if (path == "/a/x.bin") gate.await()
                    emit(done(100))
                }
            }
            val c = controller()
            c.open("m")
            c.openFile("/a/x.bin")
            runCurrent()
            val xPart = partOf("m", "x.bin")
            assertTrue(xPart.exists())
            c.backToList()
            c.openFile("/a/y.txt")
            runCurrent()
            assertFalse(xPart.exists())
            assertEquals(
                "y.txt",
                (
                    c.ui.value.viewer
                        ?.file as FileLoad.Loaded
                ).file.name,
            )
        }

    @Test
    fun `cancel deletes the part file and a reopen starts from zero`() =
        runTest {
            shares.value = listOf(share("a"))
            val gate = CompletableDeferred<Unit>()
            session.downloadHandler = { _, _, target, _ ->
                flow {
                    emit(started(100))
                    target.parentFile?.mkdirs()
                    target.writeText("partial")
                    gate.await()
                }
            }
            val c = controller()
            c.open("a")
            runCurrent()
            assertTrue(partOf().exists())
            c.cancelDownload()
            assertFalse(partOf().exists())
            assertNull("one file: the viewer closes", c.ui.value.viewer)
            var partWhenCalled = true
            session.downloadHandler = { _, _, target, resume ->
                partWhenCalled = target.exists()
                assertNull(resume)
                flowOf(done())
            }
            c.open("a")
            runCurrent()
            assertFalse(partWhenCalled)
            assertEquals(2, session.transferCalls.size)
            assertEquals("download:null:/work/out/a.txt:false", session.transferCalls.last())
        }

    @Test
    fun `cancel with several files goes back to the list`() =
        runTest {
            shares.value = listOf(share("m", paths = arrayOf("/a/x.bin", "/a/y.txt")))
            session.downloadHandler = { _, _, _, _ -> flow { awaitCancellation() } }
            val c = controller()
            c.open("m")
            c.openFile("/a/x.bin")
            runCurrent()
            c.cancelDownload()
            assertEquals(
                FileLoad.Idle,
                c.ui.value.viewer
                    ?.file,
            )
        }

    @Test
    fun `failure with a resume token offers resume`() =
        runTest {
            shares.value = listOf(share("a"))
            session.downloadHandler = { _, _, target, _ ->
                flow {
                    emit(started())
                    target.parentFile?.mkdirs()
                    target.writeText("he")
                    emit(DownloadEvent.Failed(AppError.ConnectionLost, resume))
                }
            }
            val c = controller()
            c.open("a")
            runCurrent()
            val failed =
                c.ui.value.viewer
                    ?.file as FileLoad.Failed
            assertTrue(failed.canResume)
            assertEquals("he", partOf().readText())
        }

    @Test
    fun `resume continues the partial file`() =
        runTest {
            shares.value = listOf(share("a"))
            session.downloadHandler = { _, _, target, _ ->
                flow {
                    emit(started())
                    target.parentFile?.mkdirs()
                    target.writeText("he")
                    emit(DownloadEvent.Failed(AppError.ConnectionLost, resume))
                }
            }
            val c = controller()
            c.open("a")
            runCurrent()
            var seenTarget: File? = null
            var seenResume: DownloadResume? = null
            session.downloadHandler = { _, _, target, r ->
                flow {
                    seenTarget = target
                    seenResume = r
                    emit(started(from = 2))
                    target.appendText("llo")
                    emit(done())
                }
            }
            c.resume()
            runCurrent()
            assertEquals(partOf(), seenTarget)
            assertEquals(resume, seenResume)
            val loaded =
                c.ui.value.viewer
                    ?.file as FileLoad.Loaded
            assertEquals("hello", loaded.file.readText())
            assertFalse(partOf().exists())
        }

    @Test
    fun `FileChanged deletes the part file and offers try again`() =
        runTest {
            shares.value = listOf(share("a"))
            session.downloadHandler = { _, _, target, _ ->
                flow {
                    emit(started())
                    target.parentFile?.mkdirs()
                    target.writeText("old")
                    emit(DownloadEvent.Failed(AppError.FileChanged("/work/out/a.txt"), null))
                }
            }
            val c = controller()
            c.open("a")
            runCurrent()
            val failed =
                c.ui.value.viewer
                    ?.file as FileLoad.Failed
            assertFalse(failed.canResume)
            assertEquals(AppError.FileChanged("/work/out/a.txt"), failed.error.source)
            assertFalse(partOf().exists())
            // Try again starts from zero.
            writes("hello")
            c.retry()
            runCurrent()
            assertTrue(
                c.ui.value.viewer
                    ?.file is FileLoad.Loaded,
            )
        }

    @Test
    fun `storage failure leaves no part file`() =
        runTest {
            shares.value = listOf(share("a"))
            session.downloadHandler = { _, _, _, _ -> flowOf(DownloadEvent.Failed(AppError.Storage("full"), null)) }
            val c = controller()
            c.open("a")
            runCurrent()
            assertTrue(
                c.ui.value.viewer
                    ?.file is FileLoad.Failed,
            )
            assertFalse(partOf().exists())
        }

    @Test
    fun `finished file is reused when unchanged`() =
        runTest {
            shares.value = listOf(share("a"))
            writes("hello")
            val c = controller()
            c.open("a")
            runCurrent()
            c.closeViewer()
            var seenTarget: File? = null
            var seenResume: DownloadResume? = null
            var partContent: String? = null
            session.downloadHandler = { _, _, target, r ->
                flow {
                    seenTarget = target
                    seenResume = r
                    partContent = target.readText()
                    emit(started(from = 5))
                    emit(done())
                }
            }
            c.open("a")
            runCurrent()
            assertEquals(partOf(), seenTarget)
            assertEquals(resume, seenResume)
            assertEquals("hello", partContent)
            assertEquals(
                "hello",
                (
                    c.ui.value.viewer
                        ?.file as FileLoad.Loaded
                ).file.readText(),
            )
        }

    @Test
    fun `apk download asks to install once`() =
        runTest {
            shares.value = listOf(share("a", paths = arrayOf("/work/out/app.apk")), share("b"))
            writes("PK")
            val c = controller()
            c.open("a")
            runCurrent()
            assertTrue(
                (
                    c.ui.value.viewer
                        ?.file as FileLoad.Loaded
                ).askInstall,
            )
            c.installPromptShown()
            assertFalse(
                (
                    c.ui.value.viewer
                        ?.file as FileLoad.Loaded
                ).askInstall,
            )
            c.closeViewer()
            c.open("b")
            runCurrent()
            assertFalse(
                (
                    c.ui.value.viewer
                        ?.file as FileLoad.Loaded
                ).askInstall,
            )
        }

    @Test
    fun `several paths list first and download only the tapped one`() =
        runTest {
            shares.value =
                listOf(share("m", machine = "rohan", address = "rohan/t3", paths = arrayOf("/a/x.png", "/a/y.txt")))
            val c = controller("rohan/t3")
            c.open("m")
            runCurrent()
            assertEquals(
                FileLoad.Idle,
                c.ui.value.viewer
                    ?.file,
            )
            assertTrue(session.transferCalls.isEmpty())
            c.openFile("/a/y.txt")
            runCurrent()
            assertEquals(listOf("download:rohan:/a/y.txt:false"), session.transferCalls)
            c.backToList()
            assertEquals(
                FileLoad.Idle,
                c.ui.value.viewer
                    ?.file,
            )
        }

    @Test
    fun `missing file shows FileMissing message`() =
        runTest {
            shares.value = listOf(share("a"))
            session.downloadHandler = { _, p, _, _ -> flowOf(DownloadEvent.Failed(AppError.FileMissing(p), null)) }
            val c = controller()
            c.open("a")
            runCurrent()
            val failed =
                c.ui.value.viewer
                    ?.file as FileLoad.Failed
            assertEquals(AppError.FileMissing("/work/out/a.txt"), failed.error.source)
        }

    @Test
    fun `paste path pastes path and space`() =
        runTest {
            shares.value = listOf(share("a"))
            val c = controller()
            c.open("a")
            runCurrent()
            c.pastePath()
            assertEquals(listOf("/work/out/a.txt "), pasted)
        }

    @Test
    fun `paste path escapes spaces like a dropped path`() =
        runTest {
            shares.value = listOf(share("sp", paths = arrayOf("/home/u/My Report.png")))
            val c = controller()
            c.open("sp")
            runCurrent()
            c.pastePath()
            assertEquals(listOf("/home/u/My\\ Report.png "), pasted)
        }
}

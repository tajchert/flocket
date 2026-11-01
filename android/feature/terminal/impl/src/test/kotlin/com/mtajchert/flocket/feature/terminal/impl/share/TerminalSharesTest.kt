package com.mtajchert.flocket.feature.terminal.impl.share

import com.mtajchert.flocket.core.common.error.AppError
import com.mtajchert.flocket.core.common.result.AppResult
import com.mtajchert.flocket.core.model.AgentShare
import com.mtajchert.flocket.core.model.HostId
import com.mtajchert.flocket.core.model.TerminalAddress
import com.mtajchert.flocket.core.session.CoreSession
import com.mtajchert.flocket.core.testing.FakeCoreSession
import com.mtajchert.flocket.core.testing.FakeShareSeenRepository
import com.mtajchert.flocket.core.testing.MainDispatcherRule
import com.mtajchert.flocket.core.testing.SampleData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalSharesTest {
    @get:Rule val main = MainDispatcherRule()

    private val now = 1_000_000L
    private val shares = MutableStateFlow<List<AgentShare>>(emptyList())
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

    private fun TestScope.controller(
        address: String = "t3",
        repo: FakeShareSeenRepository = seen,
    ): ShareController =
        ShareController(
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            hostId = HostId("studio"),
            address = TerminalAddress(address),
            shares = shares,
            session = { session as CoreSession },
            seen = repo,
            pasteText = { pasted += it },
            nowSeconds = { now },
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
    fun `opening downloads once and never before`() =
        runTest {
            shares.value = listOf(share("a"))
            session.readHandler = { _, _ -> AppResult.Success("hello".toByteArray()) }
            val c = controller()
            assertTrue(session.transferCalls.isEmpty())
            c.open("a")
            runCurrent()
            assertEquals(listOf("read:null:/work/out/a.txt"), session.transferCalls)
            val file =
                c.ui.value.viewer
                    ?.file as FileLoad.Loaded
            assertEquals("hello", String(file.bytes))
            assertTrue("opening marks it seen", c.ui.value.card == null)
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
            assertEquals(listOf("read:rohan:/a/y.txt"), session.transferCalls)
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
            session.readHandler = { _, p -> AppResult.Failure(AppError.FileMissing(p)) }
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
}

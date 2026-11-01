package com.mtajchert.flocket.feature.hosts.impl

import com.mtajchert.flocket.core.common.error.AppError
import com.mtajchert.flocket.core.common.result.AppResult
import com.mtajchert.flocket.core.model.ConnectionState
import com.mtajchert.flocket.core.model.MachineInfo
import com.mtajchert.flocket.core.model.MachineState
import com.mtajchert.flocket.core.session.ExecOutcome
import com.mtajchert.flocket.core.session.SessionState
import com.mtajchert.flocket.core.testing.FakeConnectionManager
import com.mtajchert.flocket.core.testing.FakeHostRepository
import com.mtajchert.flocket.core.testing.MainDispatcherRule
import com.mtajchert.flocket.core.testing.SampleData
import com.mtajchert.flocket.feature.hosts.impl.settings.AssetReader
import com.mtajchert.flocket.feature.hosts.impl.settings.HelperStatus
import com.mtajchert.flocket.feature.hosts.impl.settings.ShareHelperViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ShareHelperViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val studio = SampleData.studio.id
    private val helperBytes = "#!/bin/sh\necho hi\n".toByteArray()
    private val reader =
        AssetReader { name ->
            when (name) {
                "flocket-share" -> helperBytes
                "agent-instruction.md" -> "Run flocket-share PATH.\n".toByteArray()
                else -> error(name)
            }
        }

    private fun manager() =
        FakeConnectionManager(FakeHostRepository(SampleData.hosts), initialState = { h ->
            SessionState(
                h.endpoint,
                connection = ConnectionState.Online(SampleData.coreInfo),
                machines = listOf(MachineInfo("rohan", MachineState.Connected, "connected")),
            )
        })

    private fun ok(
        code: Int,
        out: String = "",
    ) = AppResult.Success(ExecOutcome(code, out.toByteArray(), "", false))

    private fun vm(m: FakeConnectionManager) = ShareHelperViewModel(studio.value, m, reader)

    /** The install reads the asset on the IO dispatcher, so wait until no row is busy and the PATH check is in. */
    private suspend fun ShareHelperViewModel.settled() {
        uiState.first { st ->
            st.rows.none { r ->
                val s = r.status
                s == HelperStatus.Installing || s == HelperStatus.Checking ||
                    (s is HelperStatus.Installed && s.onPath == null)
            }
        }
    }

    @Test
    fun `status per machine uses on`() =
        runTest {
            val m = manager()
            m.overview.value
            val session = m.fakeSession(studio)!!
            session.execHandler = { machine, _, _ ->
                if (machine == null) ok(0, "/home/u/.local/bin/flocket-share\n") else ok(1)
            }
            val vm = vm(m)
            val rows = vm.uiState.value.rows
            assertEquals(listOf(null, "rohan"), rows.map { it.machine })
            assertEquals(HelperStatus.Installed("/home/u/.local/bin/flocket-share", onPath = true), rows[0].status)
            assertEquals(HelperStatus.NotInstalled, rows[1].status)
            val argv = ShareHelperViewModel.STATUS_ARGV.joinToString(" ")
            assertTrue(session.transferCalls.contains("exec:null:$argv:false"))
            assertTrue(session.transferCalls.contains("exec:rohan:$argv:false"))
        }

    @Test
    fun `install writes then chmods then rechecks`() =
        runTest {
            val m = manager()
            m.overview.value
            val session = m.fakeSession(studio)!!
            var installed = false
            session.homeHandler = { AppResult.Success("/home/u") }
            session.execHandler = { _, argv, _ ->
                when {
                    argv[0] == "chmod" -> ok(0).also { installed = true }
                    argv[1] == "-lc" -> ok(1)
                    installed -> ok(0, "/home/u/.local/bin/flocket-share")
                    else -> ok(1)
                }
            }
            val vm = vm(m)
            vm.install(null)
            vm.settled()
            val path = "/home/u/.local/bin/flocket-share"
            val calls = session.transferCalls.filter { !it.startsWith("exec:rohan") }
            assertEquals(
                listOf(
                    "exec:null:${ShareHelperViewModel.STATUS_ARGV.joinToString(" ")}:false",
                    "write:null:$path:${helperBytes.size}",
                    "exec:null:chmod 755 $path:true",
                    "exec:null:${ShareHelperViewModel.STATUS_ARGV.joinToString(" ")}:false",
                    "exec:null:sh -lc command -v flocket-share:false",
                ),
                calls,
            )
            assertEquals(
                HelperStatus.Installed(path, onPath = false),
                vm.uiState.value.rows[0]
                    .status,
            )
        }

    @Test
    fun `install outcome unknown does not chmod`() =
        runTest {
            val m = manager()
            m.overview.value
            val session = m.fakeSession(studio)!!
            session.homeHandler = { AppResult.Success("/home/u") }
            session.writeHandler = { _, _, _ -> AppResult.Failure(AppError.OutcomeUnknown("file.write")) }
            val vm = vm(m)
            vm.install(null)
            vm.settled()
            assertTrue(session.transferCalls.none { it.contains("chmod") })
            assertEquals(
                HelperStatus.MayNotBeInstalled,
                vm.uiState.value.rows[0]
                    .status,
            )
        }

    @Test
    fun `instruction text comes from the bundled asset`() {
        val m = manager()
        val vm = vm(m)
        assertEquals("Run flocket-share PATH.", vm.uiState.value.instruction)
    }

    @Test
    fun `failed login PATH check shows no warning`() =
        runTest {
            val m = manager()
            m.overview.value
            val session = m.fakeSession(studio)!!
            session.execHandler = { _, argv, _ ->
                if (argv[1] ==
                    "-lc"
                ) {
                    AppResult.Failure(AppError.ConnectionLost)
                } else {
                    ok(0, "/home/u/.local/bin/flocket-share")
                }
            }
            val vm = vm(m)
            assertEquals(
                null,
                (
                    vm.uiState.value.rows[0]
                        .status as HelperStatus.Installed
                ).onPath,
            )
        }

    @Test
    fun `PATH warning persists across a recheck`() =
        runTest {
            val m = manager()
            m.overview.value
            val session = m.fakeSession(studio)!!
            session.execHandler =
                { _, argv, _ -> if (argv[1] == "-lc") ok(1) else ok(0, "/home/u/.local/bin/flocket-share") }
            val vm = vm(m)
            vm.recheck()
            assertEquals(
                false,
                (
                    vm.uiState.value.rows[0]
                        .status as HelperStatus.Installed
                ).onPath,
            )
        }

    @Test
    fun `install does nothing while the session is stale`() =
        runTest {
            val m = manager()
            m.overview.value
            val session = m.fakeSession(studio)!!
            val vm = vm(m)
            session.update { it.copy(connection = ConnectionState.Idle) }
            val before = session.transferCalls.size
            vm.install(null)
            assertEquals(before, session.transferCalls.size)
            assertTrue(session.calls.none { it.startsWith("home") })
        }
}

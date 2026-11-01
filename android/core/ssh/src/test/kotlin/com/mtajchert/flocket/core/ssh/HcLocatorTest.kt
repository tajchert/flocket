package com.mtajchert.flocket.core.ssh

import com.mtajchert.flocket.core.common.error.AppError
import com.mtajchert.flocket.core.common.result.AppResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HcLocatorTest {
    @Test
    fun `configured path wins without running anything`() =
        runBlocking {
            val r = HcLocator.locate({ error("must not run") }, "/x/hc")
            assertEquals(AppResult.Success("/x/hc"), r)
        }

    @Test
    fun `takes last absolute line ignoring motd`() =
        runBlocking {
            val r = HcLocator.locate({ CommandResult("Welcome\n/usr/local/bin/hc\n", "", 0) })
            assertEquals(AppResult.Success("/usr/local/bin/hc"), r)
        }

    @Test
    fun `missing hc yields CoreNotFound with searched paths and stderr`() =
        runBlocking {
            val r = HcLocator.locate({ CommandResult("", "boom", 1) })
            val e = (r as AppResult.Failure).error as AppError.CoreNotFound
            assertTrue("/opt/homebrew/bin/hc" in e.searchedPaths)
            assertEquals("boom", e.stderr)
        }

    @Test
    fun `script is POSIX sh valid and builds the core command`() {
        val p = ProcessBuilder("sh", "-n", "-c", HcLocator.SCRIPT).start()
        assertEquals(0, p.waitFor())
        assertEquals(listOf("/a/hc", "connect"), HcLocator.coreCommand("/a/hc"))
        assertEquals(listOf("/a/hc", "connect", "--session", "s"), HcLocator.coreCommand("/a/hc", "s"))
    }

    @Test
    fun `script finds hc on a real shell`() {
        run {
            val dir =
                java.nio.file.Files
                    .createTempDirectory("hcloc")
                    .toFile()
            val home = java.io.File(dir, ".local/bin").apply { mkdirs() }
            java.io.File(home, "hc").apply {
                writeText("#!/bin/sh\n")
                setExecutable(true)
            }
            val pb = ProcessBuilder("sh", "-c", HcLocator.SCRIPT).redirectErrorStream(false)
            pb.environment()["HOME"] = dir.path
            pb.environment()["PATH"] = "/usr/bin:/bin"
            pb.environment()["SHELL"] = "/bin/sh"
            val out =
                pb.start().let {
                    it.inputStream
                        .readBytes()
                        .decodeToString()
                        .also { _ -> it.waitFor() }
                }
            assertEquals("${dir.path}/.local/bin/hc", out.trim())
            dir.deleteRecursively()
        }
    }

    @Test
    fun `shell quoting escapes single quotes`() {
        assertEquals("'it'\\''s'", ShellQuote.quote("it's"))
        assertEquals("/a/hc connect --session 'a b'", ShellQuote.join(listOf("/a/hc", "connect", "--session", "a b")))
    }

    @Test
    fun `script is one line and ignores a login shell that reads stdin`() {
        assertTrue('\n' !in HcLocator.SCRIPT)
        val dir =
            java.nio.file.Files
                .createTempDirectory("hcloc")
                .toFile()
        try {
            java.io.File(dir, ".local/bin").mkdirs()
            java.io.File(dir, ".local/bin/hc").apply {
                writeText("#!/bin/sh\n")
                setExecutable(true)
            }
            val shell =
                java.io.File(dir, "promptsh").apply {
                    writeText("#!/bin/sh\nread answer\nexec /bin/sh \"$@\"\n")
                    setExecutable(true)
                }
            val pb = ProcessBuilder("sh", "-c", HcLocator.SCRIPT)
            pb.environment()["HOME"] = dir.path
            pb.environment()["PATH"] = "/usr/bin:/bin"
            pb.environment()["SHELL"] = shell.path
            val p = pb.start() // stdin stays open, like an SSH exec channel
            assertTrue(
                "locator hung on a login shell reading stdin",
                p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS),
            )
            assertEquals(
                "${dir.path}/.local/bin/hc",
                p.inputStream
                    .readBytes()
                    .decodeToString()
                    .trim(),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `assignment-like and non-ascii args are quoted`() {
        assertEquals("'X=1'", ShellQuote.quote("X=1"))
        assertEquals("'é'", ShellQuote.quote("é"))
        assertEquals("''", ShellQuote.quote(""))
        assertEquals("'\$(id)'", ShellQuote.quote("\$(id)"))
    }
}

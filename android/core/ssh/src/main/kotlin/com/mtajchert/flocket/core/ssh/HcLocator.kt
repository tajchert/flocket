package com.mtajchert.flocket.core.ssh

import com.mtajchert.flocket.core.common.error.AppError
import com.mtajchert.flocket.core.common.result.AppResult

/** Finds the `hc` binary on a host and builds the `hc connect` command line. */
object HcLocator {
    val searchedPaths =
        listOf(
            "command -v hc (login \$SHELL)",
            "command -v hc (sh -l)",
            "~/.local/bin/hc",
            "/opt/homebrew/bin/hc",
            "/usr/local/bin/hc",
            "/usr/bin/hc",
        )

    /**
     * One line on purpose: the exec command passes through the user's login shell, and csh/tcsh
     * reject newlines inside single quotes. stdin is /dev/null so a profile that prompts can't hang.
     */
    internal val SCRIPT =
        listOf(
            "for s in \"\${SHELL:-sh}\" sh; do " +
                "p=\$(\"\$s\" -lc 'command -v hc' </dev/null 2>/dev/null | grep '^/' | tail -n 1); " +
                "if [ -n \"\$p\" ] && [ -x \"\$p\" ]; then echo \"\$p\"; exit 0; fi; " +
                "done",
            "for p in \"\$HOME/.local/bin/hc\" /opt/homebrew/bin/hc /usr/local/bin/hc /usr/bin/hc; do " +
                "if [ -x \"\$p\" ]; then echo \"\$p\"; exit 0; fi; " +
                "done",
            "exit 1",
        ).joinToString("; ")

    suspend fun locate(
        runner: CommandRunner,
        configured: String? = null,
    ): AppResult<String> {
        if (!configured.isNullOrBlank()) return AppResult.Success(configured)
        val result =
            try {
                runner.run(listOf("sh", "-c", SCRIPT))
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                return AppResult.Failure(AppError.Unexpected(e))
            }
        val path =
            result.stdout
                .lines()
                .map { it.trim() }
                .lastOrNull { it.startsWith("/") }
        return if (result.exitStatus == 0 && path != null) {
            AppResult.Success(path)
        } else {
            AppResult.Failure(AppError.CoreNotFound(searchedPaths, result.stderr.ifBlank { null }))
        }
    }

    fun coreCommand(
        hcPath: String,
        session: String? = null,
    ): List<String> = listOf(hcPath, "connect") + (session?.let { listOf("--session", it) } ?: emptyList())
}

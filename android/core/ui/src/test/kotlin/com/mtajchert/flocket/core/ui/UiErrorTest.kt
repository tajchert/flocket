package com.mtajchert.flocket.core.ui

import com.mtajchert.flocket.core.common.error.AppError
import com.mtajchert.flocket.core.testing.SampleData
import com.mtajchert.flocket.core.ui.error.UiErrorAction
import com.mtajchert.flocket.core.ui.error.toUiError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UiErrorTest {
    private val all: List<AppError> =
        listOf(
            AppError.Unreachable("h"),
            AppError.ConnectionLost,
            AppError.Timeout("hello"),
            SampleData.hostKeyUnknown,
            SampleData.hostKeyChanged,
            AppError.AuthFailed("me", listOf("publickey")),
            AppError.CoreNotFound(listOf("/x")),
            AppError.CoreIncompatible("gen 9"),
            AppError.Rpc("unavailable", "busy"),
            AppError.Protocol("bad"),
            AppError.OutcomeUnknown("kill"),
            AppError.Unsupported("terminal.keys"),
            AppError.Storage("db"),
            AppError.SecureStorageUnavailable(),
            AppError.InvalidInput("port", "range"),
            AppError.Unexpected(RuntimeException("x")),
        )

    @Test
    fun `every error maps and keeps its source`() {
        all.forEach { assertEquals(it, it.toUiError().source) }
    }

    @Test
    fun `trust and forget actions are offered for key errors`() {
        assertEquals(UiErrorAction.TrustHostKey, SampleData.hostKeyUnknown.toUiError().primaryAction)
        assertEquals(UiErrorAction.ForgetPinnedKey, SampleData.hostKeyChanged.toUiError().primaryAction)
    }

    @Test
    fun `auth failure with password asks to re-enter it, otherwise edit host`() {
        assertEquals(
            UiErrorAction.ReenterPassword,
            AppError.AuthFailed("u", listOf("password")).toUiError().primaryAction,
        )
        assertEquals(UiErrorAction.EditHost, AppError.AuthFailed("u", listOf("publickey")).toUiError().primaryAction)
    }

    @Test
    fun `unknown outcome offers no automatic retry`() {
        assertNull(AppError.OutcomeUnknown("kill").toUiError().primaryAction)
    }
}

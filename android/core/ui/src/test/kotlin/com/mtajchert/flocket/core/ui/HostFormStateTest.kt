package com.mtajchert.flocket.core.ui

import com.mtajchert.flocket.core.model.HostAuth
import com.mtajchert.flocket.core.ui.hostform.AuthKind
import com.mtajchert.flocket.core.ui.hostform.HostFormError
import com.mtajchert.flocket.core.ui.hostform.HostFormField
import com.mtajchert.flocket.core.ui.hostform.HostFormState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostFormStateTest {
    private val valid = HostFormState(label = "Mac", hostname = "mac.local", username = "me", keyAlias = "key-1")

    @Test
    fun `valid form has no errors and builds a draft`() {
        assertTrue(valid.validate().isEmpty())
        val draft = valid.copy(session = " work ", hcPath = "").toDraft()
        assertEquals("work", draft.session)
        assertEquals(null, draft.hcPath)
        assertEquals(HostAuth.Key("key-1"), draft.auth)
    }

    @Test
    fun `required fields and key are reported`() {
        val errors = HostFormState().validate()
        assertEquals(HostFormError.Required, errors[HostFormField.Label])
        assertEquals(HostFormError.Required, errors[HostFormField.Hostname])
        assertEquals(HostFormError.Required, errors[HostFormField.Username])
        assertEquals(HostFormError.NoKey, errors[HostFormField.Key])
    }

    @Test
    fun `password auth needs no key`() {
        assertTrue(valid.copy(authKind = AuthKind.Password, keyAlias = null).validate().isEmpty())
    }

    @Test
    fun `port must be in range`() {
        assertEquals(HostFormError.InvalidPort, valid.copy(port = "0").validate()[HostFormField.Port])
        assertEquals(HostFormError.InvalidPort, valid.copy(port = "65536").validate()[HostFormField.Port])
        assertTrue(valid.copy(port = "65535").validate().isEmpty())
    }

    @Test
    fun `session name follows core rules`() {
        listOf(
            "a",
            "work-1",
            "a.b_c",
            "x".repeat(32),
        ).forEach { assertTrue(it, valid.copy(session = it).validate().isEmpty()) }
        listOf("-a", "Work", "a b", "x".repeat(33), ".a").forEach {
            assertEquals(it, HostFormError.InvalidSession, valid.copy(session = it).validate()[HostFormField.Session])
        }
    }

    @Test
    fun `hc path must be absolute and hostname has no spaces`() {
        assertEquals(HostFormError.InvalidHcPath, valid.copy(hcPath = "hc").validate()[HostFormField.HcPath])
        assertEquals(HostFormError.InvalidHostname, valid.copy(hostname = "a b").validate()[HostFormField.Hostname])
    }
}

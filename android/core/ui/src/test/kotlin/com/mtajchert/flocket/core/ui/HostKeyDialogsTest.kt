@file:Suppress("ktlint:standard:max-line-length")

package com.mtajchert.flocket.core.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.mtajchert.flocket.core.designsystem.theme.FlocketTheme
import com.mtajchert.flocket.core.testing.SampleData
import com.mtajchert.flocket.core.ui.component.HostKeyChangedDialog
import com.mtajchert.flocket.core.ui.component.HostKeyTrustDialog
import com.mtajchert.flocket.core.ui.component.groupFingerprint
import com.mtajchert.flocket.core.ui.hostform.HostForm
import com.mtajchert.flocket.core.ui.hostform.HostFormState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HostKeyDialogsTest {
    @get:Rule val rule = createComposeRule()

    private val unknown = SampleData.hostKeyUnknown
    private val changed = SampleData.hostKeyChanged

    @Test
    fun `trust dialog shows fingerprint and trusts on confirm`() {
        var trusted = false
        rule.setContent {
            FlocketTheme {
                HostKeyTrustDialog(unknown.host, unknown.keyType, unknown.fingerprint, onTrust = {
                    trusted =
                        true
                }, onDismiss = {})
            }
        }
        rule.onNodeWithText("Trust this host?").assertIsDisplayed()
        rule.onNodeWithText(groupFingerprint(unknown.fingerprint)).assertIsDisplayed()
        rule.onNodeWithText("Trust and connect").performClick()
        assertTrue(trusted)
    }

    @Test
    fun `changed key dialog never offers trust and needs a second confirmation to forget`() {
        var forgotten = false
        rule.setContent {
            FlocketTheme {
                HostKeyChangedDialog(changed.host, changed.keyType, changed.expectedFingerprint, changed.actualFingerprint, {
                    forgotten =
                        true
                }, {})
            }
        }
        rule.onNodeWithText("Trust and connect").assertDoesNotExist()
        rule.onNodeWithText("Forget pinned key…").performClick()
        assertEquals(false, forgotten)
        rule.onNodeWithText("Forget the pinned key?").assertIsDisplayed()
        rule.onNodeWithText("Forget key").performClick()
        assertTrue(forgotten)
    }

    @Test
    fun `form shows inline validation errors`() {
        val state = HostFormState(hostname = "a b")
        rule.setContent { FlocketTheme { HostForm(state, state.validate(), emptyList(), {}, {}) } }
        rule.onNodeWithText("Enter a hostname or IP address without spaces").assertIsDisplayed()
        rule.onNodeWithText("Choose or create a device key").assertExists()
    }
}

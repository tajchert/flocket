package com.mtajchert.flocket.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalPreferencesTest {
    private val app =
        TerminalPreferences(
            fontSizeSp = 15f,
            colorScheme = TerminalColorScheme.SolarizedDark,
            showExtraKeys = false,
            keepScreenOn = false,
            clipboardWritePolicy = ClipboardWritePolicy.Deny,
            bell = BellMode.Off,
            autoConnect = false,
            defaultWorkingDirectory = "~/work",
        )

    @Test
    fun `no overrides resolves to the app defaults`() {
        val r = resolve(app, HostSettingsOverrides())
        assertEquals(app, r.terminal)
        assertTrue(r.overridden.isEmpty())
        assertFalse(r.hasOverrides)
    }

    @Test
    fun `each override wins alone and only for its own field`() {
        val cases: List<Pair<HostSetting, HostSettingsOverrides>> =
            listOf(
                HostSetting.FontSize to HostSettingsOverrides(fontSizeSp = 20f),
                HostSetting.ColorScheme to HostSettingsOverrides(colorScheme = TerminalColorScheme.Light),
                HostSetting.ExtraKeys to HostSettingsOverrides(showExtraKeys = true),
                HostSetting.KeepScreenOn to HostSettingsOverrides(keepScreenOn = true),
                HostSetting.Clipboard to HostSettingsOverrides(clipboardWritePolicy = ClipboardWritePolicy.Allow),
                HostSetting.Bell to HostSettingsOverrides(bell = BellMode.Haptic),
                HostSetting.AutoConnect to HostSettingsOverrides(autoConnect = true),
                HostSetting.WorkingDirectory to HostSettingsOverrides(defaultWorkingDirectory = "~/code"),
            )
        for ((setting, o) in cases) {
            val r = resolve(app, o)
            assertEquals(setOf(setting), r.overridden)
            assertEquals(setOf(setting), o.overridden)
            // Everything else equals the app default.
            val expected =
                when (setting) {
                    HostSetting.FontSize -> app.copy(fontSizeSp = 20f)
                    HostSetting.ColorScheme -> app.copy(colorScheme = TerminalColorScheme.Light)
                    HostSetting.ExtraKeys -> app.copy(showExtraKeys = true)
                    HostSetting.KeepScreenOn -> app.copy(keepScreenOn = true)
                    HostSetting.Clipboard -> app.copy(clipboardWritePolicy = ClipboardWritePolicy.Allow)
                    HostSetting.Bell -> app.copy(bell = BellMode.Haptic)
                    HostSetting.AutoConnect -> app.copy(autoConnect = true)
                    HostSetting.WorkingDirectory -> app.copy(defaultWorkingDirectory = "~/code")
                }
            assertEquals("$setting", expected, r.terminal)
        }
    }

    @Test
    fun `an override may equal the app default and still counts as custom`() {
        val r = resolve(app, HostSettingsOverrides(fontSizeSp = 15f))
        assertEquals(app, r.terminal)
        assertEquals(setOf(HostSetting.FontSize), r.overridden)
    }

    @Test
    fun `a blank working directory falls back to the app default and is not an override`() {
        val o = HostSettingsOverrides(defaultWorkingDirectory = "  ")
        val r = resolve(app, o)
        assertEquals("~/work", r.terminal.defaultWorkingDirectory)
        assertTrue(o.isEmpty)
        assertTrue(r.overridden.isEmpty())
    }

    @Test
    fun `all overrides together replace every app value`() {
        val o =
            HostSettingsOverrides(
                18f,
                TerminalColorScheme.HighContrastDark,
                true,
                true,
                ClipboardWritePolicy.Allow,
                BellMode.Haptic,
                true,
                "/srv",
            )
        val r = resolve(app, o)
        assertEquals(HostSetting.entries.toSet(), r.overridden)
        assertEquals(18f, r.terminal.fontSizeSp)
        assertEquals("/srv", r.terminal.defaultWorkingDirectory)
        assertEquals(ClipboardWritePolicy.Allow, r.terminal.clipboardWritePolicy)
    }

    @Test
    fun `cleared and customized round trip every setting`() {
        for (s in HostSetting.entries) {
            val custom = HostSettingsOverrides().customized(s, app)
            assertEquals(setOf(s), custom.overridden)
            assertTrue(custom.cleared(s).isEmpty)
        }
    }

    @Test
    fun `changing the app default changes hosts without that override only`() {
        val o = HostSettingsOverrides(fontSizeSp = 11f)
        val changed = app.copy(fontSizeSp = 22f, bell = BellMode.Haptic)
        val r = resolve(changed, o)
        assertEquals(11f, r.terminal.fontSizeSp)
        assertEquals(BellMode.Haptic, r.terminal.bell)
    }
}

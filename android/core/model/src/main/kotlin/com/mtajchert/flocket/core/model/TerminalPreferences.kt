package com.mtajchert.flocket.core.model

/** Terminal color scheme choice. [FollowApp] picks Light or Dark from the app theme. */
enum class TerminalColorScheme { FollowApp, Light, Dark, SolarizedLight, SolarizedDark, HighContrastDark }

/** What to do when a program asks to write the clipboard (OSC 52). */
enum class ClipboardWritePolicy { Ask, Allow, Deny }

/** What the terminal bell does. */
enum class BellMode { Haptic, Off }

/** The terminal settings a host can override. Order = display order. */
enum class HostSetting {
    FontSize,
    ColorScheme,
    ExtraKeys,
    KeepScreenOn,
    Clipboard,
    Bell,
    AutoConnect,
    WorkingDirectory,
}

/** App-level terminal defaults. Every host starts from these. */
data class TerminalPreferences(
    /** Terminal font size in sp. */
    val fontSizeSp: Float = DEFAULT_FONT_SP,
    val colorScheme: TerminalColorScheme = TerminalColorScheme.FollowApp,
    /** Show the extra-keys row (Esc, Tab, Ctrl, arrows...) above the soft keyboard. */
    val showExtraKeys: Boolean = true,
    val keepScreenOn: Boolean = true,
    val clipboardWritePolicy: ClipboardWritePolicy = ClipboardWritePolicy.Ask,
    val bell: BellMode = BellMode.Haptic,
    /** Connect this host when the app opens; false = only once the user opens it. */
    val autoConnect: Boolean = true,
    /** Working directory prefilled for new terminals. */
    val defaultWorkingDirectory: String = DEFAULT_WORKING_DIRECTORY,
) {
    companion object {
        const val DEFAULT_FONT_SP = 13f
        const val DEFAULT_WORKING_DIRECTORY = "~"
    }
}

/** Per-host overrides. A null field means "use the app default". */
data class HostSettingsOverrides(
    val fontSizeSp: Float? = null,
    val colorScheme: TerminalColorScheme? = null,
    val showExtraKeys: Boolean? = null,
    val keepScreenOn: Boolean? = null,
    val clipboardWritePolicy: ClipboardWritePolicy? = null,
    val bell: BellMode? = null,
    val autoConnect: Boolean? = null,
    val defaultWorkingDirectory: String? = null,
) {
    /** The settings this host overrides. A blank working directory counts as not overridden. */
    val overridden: Set<HostSetting>
        get() =
            buildSet {
                if (fontSizeSp != null) add(HostSetting.FontSize)
                if (colorScheme != null) add(HostSetting.ColorScheme)
                if (showExtraKeys != null) add(HostSetting.ExtraKeys)
                if (keepScreenOn != null) add(HostSetting.KeepScreenOn)
                if (clipboardWritePolicy != null) add(HostSetting.Clipboard)
                if (bell != null) add(HostSetting.Bell)
                if (autoConnect != null) add(HostSetting.AutoConnect)
                if (!defaultWorkingDirectory.isNullOrBlank()) add(HostSetting.WorkingDirectory)
            }

    val isEmpty: Boolean get() = overridden.isEmpty()

    /** Back to the app default for [setting]. */
    fun cleared(setting: HostSetting): HostSettingsOverrides =
        when (setting) {
            HostSetting.FontSize -> copy(fontSizeSp = null)
            HostSetting.ColorScheme -> copy(colorScheme = null)
            HostSetting.ExtraKeys -> copy(showExtraKeys = null)
            HostSetting.KeepScreenOn -> copy(keepScreenOn = null)
            HostSetting.Clipboard -> copy(clipboardWritePolicy = null)
            HostSetting.Bell -> copy(bell = null)
            HostSetting.AutoConnect -> copy(autoConnect = null)
            HostSetting.WorkingDirectory -> copy(defaultWorkingDirectory = null)
        }

    /** Override [setting] with whatever [from] holds for it (what "Use app default" off starts at). */
    fun customized(
        setting: HostSetting,
        from: TerminalPreferences,
    ): HostSettingsOverrides =
        when (setting) {
            HostSetting.FontSize -> copy(fontSizeSp = from.fontSizeSp)
            HostSetting.ColorScheme -> copy(colorScheme = from.colorScheme)
            HostSetting.ExtraKeys -> copy(showExtraKeys = from.showExtraKeys)
            HostSetting.KeepScreenOn -> copy(keepScreenOn = from.keepScreenOn)
            HostSetting.Clipboard -> copy(clipboardWritePolicy = from.clipboardWritePolicy)
            HostSetting.Bell -> copy(bell = from.bell)
            HostSetting.AutoConnect -> copy(autoConnect = from.autoConnect)
            HostSetting.WorkingDirectory -> copy(defaultWorkingDirectory = from.defaultWorkingDirectory)
        }
}

/** What a host actually uses: the app defaults with the host's overrides applied. */
data class EffectiveHostSettings(
    val terminal: TerminalPreferences,
    /** Which values come from the host (not the app defaults). */
    val overridden: Set<HostSetting> = emptySet(),
) {
    val hasOverrides: Boolean get() = overridden.isNotEmpty()
}

/** Pure merge: each non-null (and, for the directory, non-blank) override wins over [app]. */
fun resolve(
    app: TerminalPreferences,
    overrides: HostSettingsOverrides,
): EffectiveHostSettings =
    EffectiveHostSettings(
        terminal =
            TerminalPreferences(
                fontSizeSp = overrides.fontSizeSp ?: app.fontSizeSp,
                colorScheme = overrides.colorScheme ?: app.colorScheme,
                showExtraKeys = overrides.showExtraKeys ?: app.showExtraKeys,
                keepScreenOn = overrides.keepScreenOn ?: app.keepScreenOn,
                clipboardWritePolicy = overrides.clipboardWritePolicy ?: app.clipboardWritePolicy,
                bell = overrides.bell ?: app.bell,
                autoConnect = overrides.autoConnect ?: app.autoConnect,
                defaultWorkingDirectory =
                    overrides.defaultWorkingDirectory?.takeIf { it.isNotBlank() } ?: app.defaultWorkingDirectory,
            ),
        overridden = overrides.overridden,
    )

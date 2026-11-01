@file:Suppress("ktlint:compose:multiple-emitters-check")

package com.mtajchert.flocket.core.ui.terminalsettings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mtajchert.flocket.core.model.BellMode
import com.mtajchert.flocket.core.model.ClipboardWritePolicy
import com.mtajchert.flocket.core.model.HostSetting
import com.mtajchert.flocket.core.model.TerminalColorScheme
import com.mtajchert.flocket.core.model.TerminalPreferences
import com.mtajchert.flocket.core.terminal.ui.TerminalThemes
import com.mtajchert.flocket.core.ui.R
import kotlinx.coroutines.delay

/** Slider range for the terminal font size (sp). */
const val MIN_TERMINAL_FONT_SP = 8f
const val MAX_TERMINAL_FONT_SP = 28f

/**
 * What the form shows. App-level screen: [overridden] is null and [values] are the app defaults.
 * Host screen: [values] are the host's effective settings, [overridden] the ones it sets itself and
 * [appDefaults] what the others currently fall back to.
 */
@Immutable
data class TerminalSettingsFormState(
    val values: TerminalPreferences,
    val appDefaults: TerminalPreferences? = null,
    val overridden: Set<HostSetting>? = null,
)

@Immutable
class TerminalSettingsCallbacks(
    val onFontSize: (Float) -> Unit = {},
    val onColorScheme: (TerminalColorScheme) -> Unit = {},
    val onExtraKeys: (Boolean) -> Unit = {},
    val onKeepScreenOn: (Boolean) -> Unit = {},
    val onClipboard: (ClipboardWritePolicy) -> Unit = {},
    val onBell: (BellMode) -> Unit = {},
    val onAutoConnect: (Boolean) -> Unit = {},
    val onWorkingDirectory: (String) -> Unit = {},
    /** Host screen only: `true` = follow the app default, `false` = start customizing. */
    val onUseAppDefault: (HostSetting, Boolean) -> Unit = { _, _ -> },
)

/** Live preview plus every overridable terminal setting; shared by the app and the host screens. */
@Composable
fun TerminalSettingsForm(
    state: TerminalSettingsFormState,
    callbacks: TerminalSettingsCallbacks,
    modifier: Modifier = Modifier,
) {
    val v = state.values
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TerminalPreview(v.fontSizeSp, v.colorScheme, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        SettingBlock(HostSetting.FontSize, R.string.ts_font_size, null, state, callbacks) {
            FontSizeControl(v.fontSizeSp, callbacks.onFontSize)
        }
        SettingBlock(
            HostSetting.ColorScheme,
            R.string.ts_color_scheme,
            R.string.ts_color_scheme_hint,
            state,
            callbacks,
        ) {
            ColorSchemeControl(v.colorScheme, callbacks.onColorScheme)
        }
        SwitchBlock(
            HostSetting.ExtraKeys,
            R.string.ts_extra_keys,
            R.string.ts_extra_keys_hint,
            state,
            callbacks,
            v.showExtraKeys,
            callbacks.onExtraKeys,
        )
        SwitchBlock(
            HostSetting.KeepScreenOn,
            R.string.ts_keep_screen_on,
            R.string.ts_keep_screen_on_hint,
            state,
            callbacks,
            v.keepScreenOn,
            callbacks.onKeepScreenOn,
        )
        SettingBlock(HostSetting.Clipboard, R.string.ts_clipboard, R.string.ts_clipboard_hint, state, callbacks) {
            Segmented(ClipboardWritePolicy.entries, v.clipboardWritePolicy, callbacks.onClipboard, "clipboard") {
                clipboardLabel(it)
            }
        }
        SettingBlock(HostSetting.Bell, R.string.ts_bell, R.string.ts_bell_hint, state, callbacks) {
            Segmented(BellMode.entries, v.bell, callbacks.onBell, "bell") { bellLabel(it) }
        }
        SwitchBlock(
            HostSetting.AutoConnect,
            R.string.ts_auto_connect,
            R.string.ts_auto_connect_hint,
            state,
            callbacks,
            v.autoConnect,
            callbacks.onAutoConnect,
        )
        SettingBlock(
            HostSetting.WorkingDirectory,
            R.string.ts_working_directory,
            R.string.ts_working_directory_hint,
            state,
            callbacks,
        ) { WorkingDirectoryControl(v.defaultWorkingDirectory, callbacks.onWorkingDirectory) }
    }
}

@Composable
private fun schemeLabel(scheme: TerminalColorScheme): String =
    stringResource(
        when (scheme) {
            TerminalColorScheme.FollowApp -> R.string.ts_scheme_follow_app
            TerminalColorScheme.Light -> R.string.ts_scheme_light
            TerminalColorScheme.Dark -> R.string.ts_scheme_dark
            TerminalColorScheme.SolarizedLight -> R.string.ts_scheme_solarized_light
            TerminalColorScheme.SolarizedDark -> R.string.ts_scheme_solarized_dark
            TerminalColorScheme.HighContrastDark -> R.string.ts_scheme_high_contrast
        },
    )

@Composable
private fun clipboardLabel(policy: ClipboardWritePolicy): String =
    stringResource(
        when (policy) {
            ClipboardWritePolicy.Ask -> R.string.ts_clipboard_ask
            ClipboardWritePolicy.Allow -> R.string.ts_clipboard_allow
            ClipboardWritePolicy.Deny -> R.string.ts_clipboard_deny
        },
    )

@Composable
private fun bellLabel(bell: BellMode): String =
    stringResource(if (bell == BellMode.Haptic) R.string.ts_bell_haptic else R.string.ts_bell_off)

/** Short label of a setting's app default, e.g. for "Use app default (13 sp)". */
@Composable
private fun appDefaultSummary(
    setting: HostSetting,
    prefs: TerminalPreferences,
): String =
    when (setting) {
        HostSetting.FontSize -> "${prefs.fontSizeSp.toInt()} sp"
        HostSetting.ColorScheme -> schemeLabel(prefs.colorScheme)
        HostSetting.ExtraKeys -> onOff(prefs.showExtraKeys)
        HostSetting.KeepScreenOn -> onOff(prefs.keepScreenOn)
        HostSetting.Clipboard -> clipboardLabel(prefs.clipboardWritePolicy)
        HostSetting.Bell -> bellLabel(prefs.bell)
        HostSetting.AutoConnect -> onOff(prefs.autoConnect)
        HostSetting.WorkingDirectory -> prefs.defaultWorkingDirectory
    }

@Composable
private fun onOff(value: Boolean) = stringResource(if (value) R.string.ts_on else R.string.ts_off)

// ---- blocks -------------------------------------------------------------------------------------

/** Title, hint and, on the host screen, the "Use app default" switch around [control]. */
@Composable
private fun SettingBlock(
    setting: HostSetting,
    title: Int,
    hint: Int?,
    state: TerminalSettingsFormState,
    callbacks: TerminalSettingsCallbacks,
    control: @Composable () -> Unit,
) {
    val hostMode = state.overridden != null
    val custom = state.overridden?.contains(setting) ?: true
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
        if (hint != null) {
            Text(
                stringResource(hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (hostMode) {
            val defaults = state.appDefaults ?: state.values
            UseDefaultRow(setting, !custom, appDefaultSummary(setting, defaults), callbacks.onUseAppDefault)
        }
        if (custom) control()
    }
}

@Composable
private fun SwitchBlock(
    setting: HostSetting,
    title: Int,
    hint: Int,
    state: TerminalSettingsFormState,
    callbacks: TerminalSettingsCallbacks,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    SettingBlock(setting, title, hint, state, callbacks) {
        Row(
            Modifier
                .fillMaxWidth()
                .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
                .testTag("switch_${setting.name}"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(onOff(checked), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = checked, onCheckedChange = null)
        }
    }
}

@Composable
private fun UseDefaultRow(
    setting: HostSetting,
    useDefault: Boolean,
    summary: String,
    onChange: (HostSetting, Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = useDefault, role = Role.Switch, onValueChange = { onChange(setting, it) })
            .testTag("use_default_${setting.name}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.ts_use_app_default, summary),
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Switch(checked = useDefault, onCheckedChange = null)
    }
}

// ---- controls -----------------------------------------------------------------------------------

@Composable
private fun FontSizeControl(
    value: Float,
    onChange: (Float) -> Unit,
) {
    // Keeps its own value while dragging and saves once on release, so storage isn't written per pixel.
    var draft by remember(value) { mutableFloatStateOf(value) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Slider(
            value = draft.coerceIn(MIN_TERMINAL_FONT_SP, MAX_TERMINAL_FONT_SP),
            onValueChange = { draft = it },
            onValueChangeFinished = { onChange(draft) },
            valueRange = MIN_TERMINAL_FONT_SP..MAX_TERMINAL_FONT_SP,
            steps = (MAX_TERMINAL_FONT_SP - MIN_TERMINAL_FONT_SP).toInt() - 1,
            modifier = Modifier.weight(1f).testTag("font_slider"),
        )
        Text(
            stringResource(R.string.ts_font_size_value, draft.toInt()),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorSchemeControl(
    selected: TerminalColorScheme,
    onChange: (TerminalColorScheme) -> Unit,
) {
    val appDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TerminalColorScheme.entries.forEach { scheme ->
            val theme = TerminalThemes.forScheme(scheme, appDark)
            FilterChip(
                selected = scheme == selected,
                onClick = { onChange(scheme) },
                label = { Text(schemeLabel(scheme)) },
                leadingIcon = {
                    // Swatch: the scheme's background with its foreground and a few ANSI colors.
                    Box(
                        Modifier
                            .size(22.dp)
                            .background(Color(theme.background), CircleShape)
                            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                            listOf(theme.foreground, theme.palette[1], theme.palette[4]).forEach {
                                Box(Modifier.size(4.dp).background(Color(it), CircleShape))
                            }
                        }
                    }
                },
                modifier = Modifier.testTag("scheme_${scheme.name}"),
            )
        }
    }
}

@Composable
private fun <T> Segmented(
    options: List<T>,
    selected: T,
    onChange: (T) -> Unit,
    tag: String,
    label: @Composable (T) -> String,
) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onChange(option) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                modifier = Modifier.testTag("${tag}_$index"),
            ) { Text(label(option)) }
        }
    }
}

@Composable
private fun WorkingDirectoryControl(
    value: String,
    onChange: (String) -> Unit,
) {
    var draft by remember(value) { mutableStateOf(value) }
    val commit by rememberUpdatedState(onChange)
    // Saved shortly after typing stops, not per keystroke.
    LaunchedEffect(draft) {
        if (draft != value) {
            delay(WORKING_DIRECTORY_DEBOUNCE_MS)
            commit(draft)
        }
    }
    OutlinedTextField(
        value = draft,
        onValueChange = { draft = it },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
        modifier = Modifier.fillMaxWidth().testTag("working_directory"),
    )
}

private const val WORKING_DIRECTORY_DEBOUNCE_MS = 600L

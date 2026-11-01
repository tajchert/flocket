@file:Suppress("ktlint:standard:max-line-length", "ktlint:compose:multiple-emitters-check")

package com.mtajchert.flocket.feature.settings.impl

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mtajchert.flocket.core.data.ThemePreference
import com.mtajchert.flocket.core.ui.component.CenteredContent
import com.mtajchert.flocket.core.ui.component.PublicKeyCard

@Immutable
data class AppInfo(
    val versionName: String,
    val versionCode: Long,
)

@Immutable
class SettingsCallbacks(
    val onTheme: (ThemePreference) -> Unit = {},
    val onDynamicColor: (Boolean) -> Unit = {},
    val onFontSize: (Float) -> Unit = {},
    val onExtraKeys: (Boolean) -> Unit = {},
    val onKeepScreenOn: (Boolean) -> Unit = {},
    val onKeepConnected: (Boolean) -> Unit = {},
    val onGenerateKey: () -> Unit = {},
    val onImportKey: () -> Unit = {},
    val onDeleteKey: (KeyItem) -> Unit = {},
    val onHosts: () -> Unit = {},
    val onLicenses: () -> Unit = {},
    val onGithub: () -> Unit = {},
    val onHerdr: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    appInfo: AppInfo,
    callbacks: SettingsCallbacks,
    modifier: Modifier = Modifier,
    dynamicColorAvailable: Boolean = true,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_title)) }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        CenteredContent(Modifier.padding(padding), maxWidth = 640.dp) {
            LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                item { AppearanceSection(state, callbacks, dynamicColorAvailable) }
                item { TerminalSection(state, callbacks) }
                item { ConnectionSection(state, callbacks) }
                item { KeysSection(state.keys, callbacks) }
                item { HostsSection(callbacks) }
                item { AboutSection(appInfo, callbacks) }
            }
        }
    }
}

@Composable
internal fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
    )
}

@Composable
private fun SwitchRow(
    title: String,
    hint: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    ListItem(
        modifier =
            Modifier.toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            ),
        headlineContent = { Text(title) },
        supportingContent = { Text(hint) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
    )
}

@Composable
private fun AppearanceSection(
    state: SettingsUiState,
    callbacks: SettingsCallbacks,
    dynamicColorAvailable: Boolean,
) {
    SectionHeader(stringResource(R.string.section_appearance))
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.theme_label), style = MaterialTheme.typography.bodyLarge)
        val options =
            listOf(
                ThemePreference.System to R.string.theme_system,
                ThemePreference.Light to R.string.theme_light,
                ThemePreference.Dark to R.string.theme_dark,
            )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, (theme, label) ->
                SegmentedButton(
                    selected = state.preferences.theme == theme,
                    onClick = { callbacks.onTheme(theme) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                ) { Text(stringResource(label)) }
            }
        }
    }
    SwitchRow(
        title = stringResource(R.string.dynamic_color),
        hint =
            stringResource(
                if (dynamicColorAvailable) R.string.dynamic_color_hint else R.string.dynamic_color_unavailable,
            ),
        checked = state.preferences.dynamicColor && dynamicColorAvailable,
        onCheckedChange = callbacks.onDynamicColor,
        enabled = dynamicColorAvailable,
    )
}

@Composable
private fun TerminalSection(
    state: SettingsUiState,
    callbacks: SettingsCallbacks,
) {
    SectionHeader(stringResource(R.string.section_terminal))
    // The slider keeps its own value while dragging and saves once on release, so DataStore isn't written per pixel.
    var draft by remember(
        state.preferences.terminalFontSize,
    ) { mutableFloatStateOf(state.preferences.terminalFontSize) }
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.font_size),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(R.string.font_size_value, draft.toInt()),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = draft,
            onValueChange = { draft = it },
            onValueChangeFinished = { callbacks.onFontSize(draft) },
            valueRange = SettingsViewModel.MIN_FONT_SP..SettingsViewModel.MAX_FONT_SP,
            steps = (SettingsViewModel.MAX_FONT_SP - SettingsViewModel.MIN_FONT_SP).toInt() - 1,
        )
        val previewDescription = stringResource(R.string.font_preview_description)
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = previewDescription },
        ) {
            Text(
                stringResource(R.string.font_preview),
                fontFamily = FontFamily.Monospace,
                fontSize = draft.sp,
                lineHeight = (draft * 1.3f).sp,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
    SwitchRow(
        stringResource(R.string.extra_keys),
        stringResource(R.string.extra_keys_hint),
        state.preferences.showExtraKeys,
        callbacks.onExtraKeys,
    )
    SwitchRow(
        stringResource(R.string.keep_screen_on),
        stringResource(R.string.keep_screen_on_hint),
        state.preferences.keepScreenOnInTerminal,
        callbacks.onKeepScreenOn,
    )
}

@Composable
internal fun ConnectionSection(
    state: SettingsUiState,
    callbacks: SettingsCallbacks,
) {
    SectionHeader(stringResource(R.string.section_connection))
    SwitchRow(
        stringResource(R.string.keep_connected),
        stringResource(R.string.keep_connected_hint),
        state.preferences.keepConnectedInBackground,
        callbacks.onKeepConnected,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun KeysSection(
    keys: List<KeyItem>,
    callbacks: SettingsCallbacks,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        SectionHeader(stringResource(R.string.section_keys))
        Text(
            stringResource(R.string.keys_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (keys.isEmpty()) {
                Text(stringResource(R.string.keys_empty), style = MaterialTheme.typography.bodyMedium)
            }
            keys.forEach { item -> KeyCard(item, callbacks.onDeleteKey) }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = callbacks.onGenerateKey) {
                    Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                    Text(stringResource(R.string.keys_generate), Modifier.padding(start = 8.dp))
                }
                OutlinedButton(onClick = callbacks.onImportKey) {
                    Icon(Icons.Outlined.FileOpen, null, Modifier.size(18.dp))
                    Text(stringResource(R.string.keys_import), Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun KeyCard(
    item: KeyItem,
    onDelete: (KeyItem) -> Unit,
) {
    var expanded by rememberSaveable(item.key.alias) { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Key, null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(
                        item.key.label,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        item.key.keyType,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { onDelete(item) }) {
                    Icon(
                        Icons.Outlined.DeleteOutline,
                        stringResource(R.string.key_delete_description, item.key.label),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Text(
                item.key.fingerprint,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp),
            )
            Text(
                if (item.usedBy.isEmpty()) {
                    stringResource(
                        R.string.key_unused,
                    )
                } else {
                    stringResource(R.string.key_used_by, item.usedBy.joinToString())
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(end = 8.dp),
            )
            androidx.compose.material3.TextButton(onClick = { expanded = !expanded }) {
                Icon(
                    if (expanded) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    null,
                    Modifier.size(18.dp),
                )
                Text(
                    stringResource(if (expanded) R.string.key_hide_public else R.string.key_show_public),
                    Modifier.padding(start = 8.dp),
                )
            }
            if (expanded) PublicKeyCard(item.key, Modifier.padding(end = 8.dp, bottom = 8.dp))
        }
    }
}

@Composable
private fun HostsSection(callbacks: SettingsCallbacks) {
    SectionHeader(stringResource(R.string.section_hosts))
    ListItem(
        modifier = Modifier.clickableItem(callbacks.onHosts),
        leadingContent = { Icon(Icons.Outlined.Dns, null) },
        headlineContent = { Text(stringResource(R.string.hosts_shortcut)) },
        supportingContent = { Text(stringResource(R.string.hosts_shortcut_hint)) },
    )
}

@Composable
private fun AboutSection(
    info: AppInfo,
    callbacks: SettingsCallbacks,
) {
    SectionHeader(stringResource(R.string.section_about))
    ListItem(
        headlineContent = { Text(stringResource(R.string.about_version)) },
        supportingContent = { Text(stringResource(R.string.about_version_value, info.versionName, info.versionCode)) },
    )
    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
    ListItem(
        modifier = Modifier.clickableItem(callbacks.onLicenses),
        leadingContent = { Icon(Icons.Outlined.Description, null) },
        headlineContent = { Text(stringResource(R.string.about_licenses)) },
        supportingContent = { Text(stringResource(R.string.about_licenses_hint)) },
    )
    ListItem(
        modifier = Modifier.clickableItem(callbacks.onGithub),
        leadingContent = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, null) },
        headlineContent = { Text(stringResource(R.string.about_github)) },
        supportingContent = { Text(stringResource(R.string.github_url)) },
    )
    ListItem(
        modifier = Modifier.clickableItem(callbacks.onHerdr),
        leadingContent = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, null) },
        headlineContent = { Text(stringResource(R.string.about_herdr)) },
        supportingContent = { Text(stringResource(R.string.about_herdr_hint)) },
    )
}

private fun Modifier.clickableItem(onClick: () -> Unit): Modifier =
    this.then(Modifier.clickable(role = Role.Button, onClick = onClick))

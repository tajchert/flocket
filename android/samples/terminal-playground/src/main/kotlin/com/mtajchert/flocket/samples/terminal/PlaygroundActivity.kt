package com.mtajchert.flocket.samples.terminal

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mtajchert.flocket.core.designsystem.theme.FlocketTheme
import com.mtajchert.flocket.core.session.AttachMode
import com.mtajchert.flocket.core.session.TerminalSize
import com.mtajchert.flocket.core.terminal.controller.TerminalController
import com.mtajchert.flocket.core.terminal.engine.TerminalEngineFactory
import com.mtajchert.flocket.core.terminal.ghostty.GhosttyTerminalEngine
import com.mtajchert.flocket.core.terminal.ui.TerminalExtraKeys
import com.mtajchert.flocket.core.terminal.ui.TerminalSurface
import com.mtajchert.flocket.core.terminal.ui.TerminalThemes
import com.mtajchert.flocket.core.terminal.ui.TerminalView
import kotlinx.coroutines.flow.MutableStateFlow

/** Owns the controller across configuration changes (the production screen does the same). */
class PlaygroundViewModel : ViewModel() {
    val stream = PlaygroundStream(viewModelScope, TerminalSize(100, 30))
    val controller =
        TerminalController(
            engineFactory = TerminalEngineFactory { c, r -> GhosttyTerminalEngine(c, r) },
            theme = TerminalThemes.Dark,
        )
    val mode = MutableStateFlow(PlaygroundMode.Echo)
    val owner = MutableStateFlow(false)
    val dark = MutableStateFlow(true)
    val fontSp = MutableStateFlow(TerminalView.DEFAULT_FONT_SP)
    private var started = false

    fun startOnce(
        mode: PlaygroundMode,
        owner: Boolean,
    ) {
        if (started) return
        started = true
        this.owner.value = owner
        controller.bind(stream, if (owner) AttachMode.Owner else AttachMode.Passive)
        setMode(mode)
    }

    fun setMode(mode: PlaygroundMode) {
        this.mode.value = mode
        stream.start(mode)
    }

    fun toggleOwner() {
        val o = !owner.value
        owner.value = o
        controller.bind(stream, if (o) AttachMode.Owner else AttachMode.Passive)
    }

    override fun onCleared() {
        controller.close()
    }
}

class PlaygroundActivity : ComponentActivity() {
    private val vm: PlaygroundViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val mode =
            intent.getStringExtra(EXTRA_MODE)?.let { m ->
                PlaygroundMode.entries.firstOrNull { it.name.equals(m, ignoreCase = true) }
            }
                ?: PlaygroundMode.Echo
        vm.startOnce(mode, owner = intent.getBooleanExtra(EXTRA_OWNER, false))
        setContent { PlaygroundScreen(vm) }
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_OWNER = "owner"
    }
}

@Composable
private fun PlaygroundScreen(vm: PlaygroundViewModel) {
    val dark by vm.dark.collectAsState()
    val mode by vm.mode.collectAsState()
    val owner by vm.owner.collectAsState()
    val font by vm.fontSp.collectAsState()
    val grid by vm.controller.gridSize.collectAsState()
    val isOwner by vm.controller.isOwner.collectAsState()
    val title by vm.controller.title.collectAsState()
    var clipboardRequest by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current
    FlocketTheme(darkTheme = dark, dynamicColor = false) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp)) {
                PlaygroundMode.entries.forEach { m ->
                    FilterChip(selected = m == mode, onClick = {
                        vm.setMode(m)
                    }, label = { Text(m.name) }, modifier = Modifier.padding(end = 4.dp))
                }
                TextButton(onClick = vm::toggleOwner) { Text(if (owner) "Owner" else "Passive") }
                TextButton(onClick = { vm.controller.fitToScreen() }) { Text("Fit") }
                TextButton(onClick = { vm.fontSp.value = font - 1 }) { Text("A-") }
                TextButton(onClick = { vm.fontSp.value = font + 1 }) { Text("A+") }
                TextButton(onClick = { vm.dark.value = !dark }) { Text(if (dark) "Light" else "Dark") }
            }
            Text(
                "${grid.cols}x${grid.rows} · ${if (isOwner) "size owner" else "passive"} · ${"%.0f".format(
                    font,
                )}sp · $title",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            TerminalSurface(
                controller = vm.controller,
                theme = TerminalThemes.forDarkMode(dark),
                fontSizeSp = font,
                onFontSizeChange = { vm.fontSp.value = it },
                onClipboardWriteRequest = { clipboardRequest = it },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            TerminalExtraKeys(vm.controller)
        }
        clipboardRequest?.let { text ->
            AlertDialog(
                onDismissRequest = { clipboardRequest = null },
                title = { Text("Allow copy to clipboard?") },
                text = { Text("The program wants to copy ${text.length} characters.") },
                confirmButton = {
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(text))
                        clipboardRequest = null
                    }) { Text("Allow") }
                },
                dismissButton = { TextButton(onClick = { clipboardRequest = null }) { Text("Deny") } },
            )
        }
    }
}

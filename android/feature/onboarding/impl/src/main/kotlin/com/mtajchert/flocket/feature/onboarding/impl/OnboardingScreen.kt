@file:Suppress("ktlint:standard:max-line-length")

package com.mtajchert.flocket.feature.onboarding.impl

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.FrontHand
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mtajchert.flocket.core.designsystem.theme.FlocketCorners
import com.mtajchert.flocket.core.designsystem.theme.flocketColors
import com.mtajchert.flocket.core.ui.component.CenteredContent
import com.mtajchert.flocket.core.ui.component.ConnectionStateBadge
import com.mtajchert.flocket.core.ui.component.ErrorBanner
import com.mtajchert.flocket.core.ui.component.HostKeyChangedDialog
import com.mtajchert.flocket.core.ui.component.HostKeyTrustDialog
import com.mtajchert.flocket.core.ui.component.ImportKeyDialog
import com.mtajchert.flocket.core.ui.component.KeySource
import com.mtajchert.flocket.core.ui.component.KeySourceFields
import com.mtajchert.flocket.core.ui.component.PublicKeyCard
import com.mtajchert.flocket.core.ui.component.TerminalCommandBlock
import com.mtajchert.flocket.core.ui.component.rememberNowMs
import com.mtajchert.flocket.core.ui.error.UiErrorAction
import com.mtajchert.flocket.core.ui.hostform.HostForm
import com.mtajchert.flocket.core.ui.hostform.HostFormState
import kotlinx.coroutines.launch

/** Everything the onboarding UI can do. Defaults are no-ops so previews and tests stay short. */
data class OnboardingActions(
    val onNext: () -> Unit = {},
    val onBack: () -> Unit = {},
    val onGenerateKey: () -> Unit = {},
    val onToggleImport: () -> Unit = {},
    val onImportPem: (String) -> Unit = {},
    val onImportSource: (KeySource) -> Unit = {},
    val onPickKeyFile: () -> Unit = {},
    val onImportPassphrase: (String) -> Unit = {},
    val onImportKey: () -> Unit = {},
    val onOpenHostImport: () -> Unit = {},
    val onDismissHostImport: () -> Unit = {},
    val onImportLabel: (String) -> Unit = {},
    val onUsePassword: () -> Unit = {},
    val onFormChange: (HostFormState) -> Unit = {},
    val onTrust: () -> Unit = {},
    val onDismissTrust: () -> Unit = {},
    val onDismissChanged: () -> Unit = {},
    val onForgetPinned: () -> Unit = {},
    val onSubmitPassword: (CharArray) -> Unit = {},
    val onDismissPassword: () -> Unit = {},
    val onErrorAction: (UiErrorAction) -> Unit = {},
    val onEditHost: () -> Unit = {},
    val onSkip: () -> Unit = {},
)

private val ContentMaxWidth = 600.dp

@Composable
fun OnboardingScreen(
    state: OnboardingUiState,
    actions: OnboardingActions,
    modifier: Modifier = Modifier,
    nowMs: Long = rememberNowMs(),
) {
    BackHandler(enabled = state.canGoBack, onBack = actions.onBack)
    val showChrome = state.step != OnboardingStep.Welcome
    Scaffold(
        modifier = modifier,
        topBar = {
            if (showChrome) StepBar(state, actions)
        },
        bottomBar = { Footer(state, actions) },
    ) { padding ->
        AnimatedContent(
            targetState = state.step,
            modifier = Modifier.padding(padding).fillMaxSize(),
            transitionSpec = {
                val forward = targetState.ordinal > initialState.ordinal
                val dir = if (forward) 1 else -1
                (slideInHorizontally { it / 5 * dir } + fadeIn()) togetherWith
                    (slideOutHorizontally { -it / 5 * dir } + fadeOut()) using
                    SizeTransform(clip = false)
            },
            label = "step",
        ) { step ->
            when (step) {
                OnboardingStep.Welcome -> WelcomePage()
                OnboardingStep.Prerequisites -> PrerequisitesPage()
                OnboardingStep.DeviceKey -> DeviceKeyPage(state, actions)
                OnboardingStep.AddHost -> AddHostPage(state, actions)
                OnboardingStep.Connect -> ConnectPage(state, actions, nowMs)
            }
        }
    }
    ConnectDialogs(state, actions)
}

@Composable
private fun StepBar(
    state: OnboardingUiState,
    actions: OnboardingActions,
) {
    Column(
        Modifier.windowInsetsPadding(
            WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        ),
    ) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.canGoBack) {
                IconButton(onClick = actions.onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.onboarding_back),
                    )
                }
            } else {
                Spacer(Modifier.width(16.dp))
            }
            Text(
                stringResource(R.string.onboarding_step_of, state.stepIndex + 1, state.stepCount),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            repeat(state.stepCount) { i ->
                val color by animateColorAsState(
                    if (i <=
                        state.stepIndex
                    ) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.primaryContainer
                    },
                    label = "segment",
                )
                Box(Modifier.weight(1f).height(4.dp).background(color, RoundedCornerShape(2.dp)))
            }
        }
    }
}

@Composable
private fun Footer(
    state: OnboardingUiState,
    actions: OnboardingActions,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Box(
            Modifier.fillMaxWidth().windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
            ),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (state.step) {
                    OnboardingStep.Welcome -> {
                        PrimaryButton(stringResource(R.string.welcome_get_started), actions.onNext)
                    }

                    OnboardingStep.Prerequisites -> {
                        PrimaryButton(stringResource(R.string.prereq_continue), actions.onNext)
                    }

                    OnboardingStep.DeviceKey -> {
                        val ready = state.selectedKey != null
                        TextButton(onClick = actions.onUsePassword) { Text(stringResource(R.string.key_use_password)) }
                        PrimaryButton(
                            text =
                                stringResource(
                                    when {
                                        ready -> R.string.key_continue
                                        state.generatingKey -> R.string.key_generating
                                        else -> R.string.key_generate
                                    },
                                ),
                            onClick = if (ready) actions.onNext else actions.onGenerateKey,
                            enabled = !state.generatingKey,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    OnboardingStep.AddHost -> {
                        PrimaryButton(stringResource(R.string.host_save), actions.onNext, enabled = !state.saving)
                    }

                    OnboardingStep.Connect -> {
                        if (!state.connect.connected) {
                            TextButton(
                                onClick = actions.onSkip,
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            ) {
                                Text(stringResource(R.string.connect_finish_later))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The design's full-width 56dp pill. In a [Row] pass `Modifier.weight(1f)` to fill the rest. */
@Composable
private fun RowScope.PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.weight(1f),
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 56.dp),
    ) { Text(text, style = MaterialTheme.typography.labelLarge.copy(fontSize = 16.sp)) }
}

/** Scrollable, centered page body with the standard padding. */
@Composable
private fun Page(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    CenteredContent(modifier, maxWidth = ContentMaxWidth) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) { content() }
    }
}

@Composable
private fun HeroIcon(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Int = 72,
) {
    Surface(
        modifier.size(size.dp),
        shape = RoundedCornerShape((size * 0.3f).dp),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size((size / 2).dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun WelcomePage() {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val twoPane = maxWidth >= 840.dp
        if (twoPane) {
            Row(
                Modifier.fillMaxSize().padding(48.dp),
                horizontalArrangement = Arrangement.spacedBy(64.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    HeroIcon(Icons.Outlined.Terminal, size = 96)
                    WelcomeHeadline(MaterialTheme.typography.displayMedium.copy(letterSpacing = (-0.5).sp))
                }
                Column(Modifier.weight(1f)) { ValuePoints() }
            }
        } else {
            Page {
                Column(
                    Modifier.fillMaxWidth().padding(top = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(28.dp),
                ) {
                    HeroIcon(Icons.Outlined.Terminal)
                    WelcomeHeadline(
                        MaterialTheme.typography.displaySmall.copy(
                            fontSize = 40.sp,
                            lineHeight = 44.sp,
                            letterSpacing = (-0.5).sp,
                        ),
                    )
                    ValuePoints()
                }
            }
        }
    }
}

@Composable
private fun WelcomeHeadline(headline: TextStyle) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.welcome_tagline), style = headline)
        Text(
            stringResource(R.string.welcome_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ValuePoints() {
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        ValuePoint(
            Icons.Outlined.FrontHand,
            R.string.welcome_point_overview_title,
            R.string.welcome_point_overview_body,
        )
        ValuePoint(Icons.Outlined.Bolt, R.string.welcome_point_answer_title, R.string.welcome_point_answer_body)
        ValuePoint(Icons.Outlined.Lock, R.string.welcome_point_private_title, R.string.welcome_point_private_body)
    }
}

@Composable
private fun ValuePoint(
    icon: ImageVector,
    title: Int,
    body: Int,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Surface(
            Modifier.size(40.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp))
            Text(
                stringResource(body),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 19.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Step page title (30sp) and body (15sp), as in the design. */
@Composable
private fun StepHeader(
    title: String,
    body: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium.copy(fontSize = 30.sp, lineHeight = 35.sp))
        if (body.isNotEmpty()) {
            Text(
                body,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 22.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PrerequisitesPage() {
    val uriHandler = LocalUriHandler.current
    val url = stringResource(R.string.prereq_url)
    val command = stringResource(R.string.prereq_check_command)
    Page {
        StepHeader(stringResource(R.string.prereq_title), stringResource(R.string.prereq_body))
        TextButton(onClick = { uriHandler.openUri(url) }) { Text(stringResource(R.string.prereq_link)) }
        Surface(
            Modifier.fillMaxWidth(),
            shape = FlocketCorners.CardLarge,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.prereq_check_title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.prereq_check_body), style = MaterialTheme.typography.bodyMedium)
                CopyableCommand(command)
            }
        }
        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(
                    Icons.Outlined.Wifi,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.prereq_tip_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Text(
                        stringResource(R.string.prereq_tip_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }
    }
}

@Composable
private fun CopyableCommand(command: String) {
    val clipboard = androidx.compose.ui.platform.LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TerminalCommandBlock(command, Modifier.fillMaxWidth())
        Button(
            onClick = {
                scope.launch {
                    clipboard.setClipEntry(
                        androidx.compose.ui.platform.ClipEntry(
                            android.content.ClipData.newPlainText("Flocket", command),
                        ),
                    )
                    copied = true
                }
            },
            modifier = Modifier.heightIn(min = 40.dp),
        ) {
            Icon(
                if (copied) Icons.Outlined.Done else Icons.Outlined.ContentCopy,
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.IconSize),
            )
            Text(
                stringResource(if (copied) R.string.prereq_copied else R.string.prereq_copy),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun DeviceKeyPage(
    state: OnboardingUiState,
    actions: OnboardingActions,
) {
    Page {
        StepHeader(stringResource(R.string.key_title), stringResource(R.string.key_body))
        state.error?.let { ErrorBanner(it) }
        val key = state.selectedKey
        if (key != null) {
            Text(
                stringResource(R.string.key_ready),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            PublicKeyCard(key, showTitle = false)
        } else {
            if (state.generatingKey) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            OutlinedButton(onClick = actions.onToggleImport, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (state.importOpen) R.string.key_import_hide else R.string.key_import))
            }
            if (state.importOpen) {
                Column(Modifier.animateContentSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    KeySourceFields(
                        source = state.importSource,
                        fileName = state.importFileName,
                        pem = state.importPem,
                        notAKey = state.importNotAKey,
                        onPickFile = actions.onPickKeyFile,
                        onPemChange = actions.onImportPem,
                        onSourceChange = actions.onImportSource,
                        enabled = !state.generatingKey,
                    )
                    OutlinedTextField(
                        value = state.importPassphrase,
                        onValueChange = actions.onImportPassphrase,
                        label = { Text(stringResource(R.string.key_import_passphrase)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions =
                            KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                autoCorrectEnabled = false,
                            ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = actions.onImportKey,
                        enabled = state.importPem.isNotBlank() && !state.importNotAKey && !state.generatingKey,
                    ) {
                        Text(stringResource(R.string.key_import_action))
                    }
                }
            }
        }
    }
}

@Composable
private fun AddHostPage(
    state: OnboardingUiState,
    actions: OnboardingActions,
) {
    if (state.hostImportOpen) {
        ImportKeyDialog(
            state = state.importDialog,
            onChange = { label, pem, passphrase ->
                label?.let(actions.onImportLabel)
                pem?.let(actions.onImportPem)
                passphrase?.let(actions.onImportPassphrase)
            },
            onSource = actions.onImportSource,
            onPickFile = actions.onPickKeyFile,
            onConfirm = actions.onImportKey,
            onDismiss = actions.onDismissHostImport,
        )
    }
    Page {
        StepHeader(stringResource(R.string.host_title), stringResource(R.string.host_body))
        // While the import dialog is open its own error shows there.
        state.error?.takeUnless { state.hostImportOpen }?.let { ErrorBanner(it) }
        HostForm(
            state = state.form,
            errors = state.formErrors,
            keys = state.keys,
            onChange = actions.onFormChange,
            onImportKey = actions.onOpenHostImport,
            onCreateKey = actions.onGenerateKey,
            isCreatingKey = state.generatingKey,
        )
    }
}

@Composable
private fun ConnectPage(
    state: OnboardingUiState,
    actions: OnboardingActions,
    nowMs: Long,
) {
    val connect = state.connect
    val uriHandler = LocalUriHandler.current
    val helpUrl = stringResource(R.string.connect_install_url)
    Page {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (connect.connected) {
                Surface(Modifier.size(56.dp), shape = CircleShape, color = MaterialTheme.flocketColors.doneContainer) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Outlined.Done,
                            contentDescription = null,
                            modifier = Modifier.size(30.dp),
                            tint = MaterialTheme.flocketColors.onDoneContainer,
                        )
                    }
                }
                StepHeader(
                    stringResource(R.string.connect_success_title_host, connect.hostLabel),
                    connect.coreName?.let { stringResource(R.string.connect_success_body, it) }
                        ?: stringResource(R.string.connect_success_body_unnamed),
                )
            } else {
                if (connect.error == null) CircularProgressIndicator(Modifier.size(48.dp))
                StepHeader(
                    stringResource(R.string.connect_title, connect.hostLabel),
                    if (connect.error == null) stringResource(R.string.connect_progress) else "",
                )
                ConnectionStateBadge(connect.connection, nowMs = nowMs)
            }
        }
        connect.error?.let { error ->
            ErrorBanner(error, onAction = {
                if (it ==
                    UiErrorAction.InstallHelp
                ) {
                    uriHandler.openUri(helpUrl)
                } else {
                    actions.onErrorAction(it)
                }
            })
            OutlinedButton(onClick = actions.onEditHost, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.connect_edit_host))
            }
        }
    }
}

@Composable
private fun ConnectDialogs(
    state: OnboardingUiState,
    actions: OnboardingActions,
) {
    val connect = state.connect
    if (state.step != OnboardingStep.Connect) return
    connect.trustPrompt?.let {
        HostKeyTrustDialog(
            it.host,
            it.keyType,
            it.fingerprint,
            onTrust = actions.onTrust,
            onDismiss = actions.onDismissTrust,
        )
    }
    connect.changedPrompt?.let {
        HostKeyChangedDialog(
            it.host,
            it.keyType,
            it.expectedFingerprint,
            it.actualFingerprint,
            onForgetPinnedKey = actions.onForgetPinned,
            onDismiss = actions.onDismissChanged,
        )
    }
    if (connect.passwordPrompt) {
        var password by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = actions.onDismissPassword,
            title = { Text(stringResource(R.string.connect_password_title, connect.hostLabel)) },
            text = {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.connect_password_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { actions.onSubmitPassword(password.toCharArray()) },
                    enabled = password.isNotEmpty(),
                ) {
                    Text(stringResource(R.string.connect_password_connect))
                }
            },
            dismissButton = {
                TextButton(onClick = actions.onDismissPassword) {
                    Text(stringResource(com.mtajchert.flocket.core.ui.R.string.action_cancel))
                }
            },
        )
    }
}

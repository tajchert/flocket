package com.mtajchert.flocket.core.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mtajchert.flocket.core.ui.R
import com.mtajchert.flocket.core.ui.error.UiError
import com.mtajchert.flocket.core.ui.error.UiErrorAction
import com.mtajchert.flocket.core.ui.error.asString

/** Centered explanation with an optional primary and secondary action. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
    secondaryLabel: String? = null,
    onSecondary: () -> Unit = {},
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp).widthIn(max = 420.dp),
        )
        if (actionLabel != null) {
            Button(onClick = onAction, modifier = Modifier.padding(top = 24.dp)) { Text(actionLabel) }
        }
        if (secondaryLabel != null) {
            OutlinedButton(onClick = onSecondary, modifier = Modifier.padding(top = 8.dp)) { Text(secondaryLabel) }
        }
    }
}

/** Full-area error from a [UiError]. The primary action is offered through [onAction]. */
@Composable
fun ErrorState(
    error: UiError,
    modifier: Modifier = Modifier,
    onAction: (UiErrorAction) -> Unit = {},
) {
    val action = error.primaryAction
    EmptyState(
        icon = Icons.Outlined.ErrorOutline,
        title = error.title.asString(),
        message = listOfNotNull(error.message.asString(), error.detail).joinToString("\n"),
        modifier = modifier,
        actionLabel = action?.let { stringResource(it.label) },
        onAction = { action?.let(onAction) },
    )
}

/** Inline error card (inside a form or detail screen). */
@Composable
fun ErrorBanner(
    error: UiError,
    modifier: Modifier = Modifier,
    onAction: (UiErrorAction) -> Unit = {},
) {
    val action = error.primaryAction
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.ErrorOutline,
                    contentDescription = stringResource(R.string.error_state_icon_description),
                )
                Text(error.title.asString(), style = MaterialTheme.typography.titleMedium)
            }
            Text(error.message.asString(), style = MaterialTheme.typography.bodyMedium)
            if (error.detail != null) {
                Text(error.detail, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
            if (action != null) {
                OutlinedButton(onClick = { onAction(action) }) { Text(stringResource(action.label)) }
            }
        }
    }
}

@Composable
fun LoadingState(
    modifier: Modifier = Modifier,
    label: String = stringResource(R.string.loading),
) {
    Column(
        modifier = modifier.fillMaxSize().semantics { contentDescription = label },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
    }
}

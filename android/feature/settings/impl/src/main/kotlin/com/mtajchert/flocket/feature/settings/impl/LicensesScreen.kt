package com.mtajchert.flocket.feature.settings.impl

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mtajchert.flocket.core.ui.component.CenteredContent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(
    notices: Notices,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.licenses_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.settings_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        CenteredContent(Modifier.padding(padding), maxWidth = 720.dp) {
            LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                item {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        notices.intro.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    }
                    SectionHeader(stringResource(R.string.licenses_libraries))
                }
                items(notices.rows, key = { it.library }) { row ->
                    ListItem(
                        headlineContent = { Text(row.library) },
                        supportingContent = { Text(row.use) },
                        trailingContent = { Text(row.license, style = MaterialTheme.typography.labelMedium) },
                    )
                }
                if (notices.footnotes.isNotEmpty()) {
                    item {
                        Column(Modifier.padding(16.dp)) {
                            notices.footnotes.forEach {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

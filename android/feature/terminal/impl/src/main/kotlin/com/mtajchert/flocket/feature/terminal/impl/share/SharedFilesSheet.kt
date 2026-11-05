package com.mtajchert.flocket.feature.terminal.impl.share

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mtajchert.flocket.feature.terminal.impl.R

/** Bottom sheet with every share of this terminal. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharedFilesSheet(
    shares: List<ShareItem>,
    nowEpochSeconds: Long,
    actions: ShareActions,
    downloading: Map<String, Float?> = emptyMap(),
) {
    ModalBottomSheet(onDismissRequest = actions.onHideSheet) {
        SharedFilesContent(shares, nowEpochSeconds, actions, downloading = downloading)
    }
}

/** The sheet's body, stateless for tests and previews. Newest first as given. */
@Composable
fun SharedFilesContent(
    shares: List<ShareItem>,
    nowEpochSeconds: Long,
    actions: ShareActions,
    modifier: Modifier = Modifier,
    downloading: Map<String, Float?> = emptyMap(),
) {
    Column(modifier.navigationBarsPadding().testTag("shared_files")) {
        Text(
            stringResource(R.string.share_sheet_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        if (shares.isEmpty()) {
            Text(
                stringResource(R.string.share_sheet_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            )
        }
        LazyColumn {
            items(shares, key = { it.id }) { share ->
                ListItem(
                    headlineContent = {
                        Text(
                            if (share.fileCount ==
                                1
                            ) {
                                share.name
                            } else {
                                pluralStringResource(R.plurals.share_files_count, share.fileCount, share.fileCount)
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    supportingContent = {
                        val note = share.note?.takeIf { it.isNotBlank() }
                        val active = share.paths.firstOrNull { it in downloading }
                        Text(
                            when {
                                active == null -> {
                                    note ?: share.paths.firstOrNull().orEmpty()
                                }

                                downloading[active] == null -> {
                                    stringResource(R.string.share_viewer_loading)
                                }

                                else -> {
                                    stringResource(
                                        R.string.share_downloading_percent,
                                        (
                                            downloading.getValue(active)!! *
                                                100
                                        ).toInt(),
                                    )
                                }
                            },
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    trailingContent = { Text(relativeTime(share.atEpochSeconds, nowEpochSeconds)) },
                    modifier = Modifier.fillMaxWidth().clickable { actions.onOpen(share.id) },
                )
            }
        }
    }
}

@Composable
internal fun relativeTime(
    atEpochSeconds: Long,
    nowEpochSeconds: Long,
): String =
    if (nowEpochSeconds - atEpochSeconds < 60) {
        stringResource(R.string.share_time_just_now)
    } else {
        DateUtils
            .getRelativeTimeSpanString(
                atEpochSeconds * 1000,
                nowEpochSeconds * 1000,
                DateUtils.MINUTE_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE,
            ).toString()
    }

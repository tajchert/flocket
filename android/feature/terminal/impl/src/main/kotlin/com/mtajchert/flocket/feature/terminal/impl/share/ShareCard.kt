package com.mtajchert.flocket.feature.terminal.impl.share

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mtajchert.flocket.feature.terminal.impl.R

/** A new share of this terminal: what it is, the agent's note, and Open / Dismiss. Drawn over the terminal. */
@Composable
fun ShareCard(
    share: ShareItem,
    actions: ShareActions,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = colors.secondaryContainer,
        contentColor = colors.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
        shadowElevation = 6.dp,
        modifier = modifier.fillMaxWidth().testTag("share_card"),
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Outlined.AttachFile, null, Modifier.size(20.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text =
                        if (share.fileCount == 1) {
                            stringResource(R.string.share_card_title_one, share.name)
                        } else {
                            pluralStringResource(R.plurals.share_card_title_many, share.fileCount, share.fileCount)
                        },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                share.note?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            TextButton(onClick = { actions.onDismiss(share.id) }) { Text(stringResource(R.string.share_card_dismiss)) }
            TextButton(onClick = { actions.onOpen(share.id) }) { Text(stringResource(R.string.share_card_open)) }
        }
    }
}

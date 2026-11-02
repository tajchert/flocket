package com.mtajchert.flocket.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mtajchert.flocket.core.designsystem.theme.FlocketCorners
import com.mtajchert.flocket.core.model.AgentStatus

/**
 * The design's agent mark: the real [AgentIcon] in a rounded-square tile (radius about 28% of [size];
 * 36dp in list rows, 20dp in session tabs) with the status badge at the bottom-right: a circle of
 * `size * 2/3` in the solid status color, a filled status icon, and a 2dp ring in [ringColor].
 * The badge overhangs the tile by about a fifth of [size], so leave room around it.
 *
 * @param agentId an AgentCatalog id; null or unknown draws the generic terminal tile.
 * @param status drives the badge color and icon (see [agentStatusStyle]).
 * @param showBadge false hides the badge (tabs, tiny sizes, or where a [AgentStatusChip] sits next to it).
 * @param ringColor the background behind the tile so the ring blends in (a card or surface color).
 * @param contentDescription for the tile; null when the row text already names the agent.
 * @param badgeContentDescription for the badge; defaults to the status word. Null hides it from TalkBack.
 */
@Composable
fun AgentTile(
    agentId: String?,
    status: AgentStatus,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    showBadge: Boolean = true,
    ringColor: Color = MaterialTheme.colorScheme.background,
    contentDescription: String? = null,
    badgeContentDescription: String? = agentStatusStyle(status).label,
) {
    val style = agentStatusStyle(status)
    val badgeSize = size * 2 / 3
    val overhang = size * 0.2f
    Box(modifier = modifier.size(size)) {
        AgentIcon(
            agentId = agentId,
            size = size,
            contentDescription = contentDescription,
            cornerRadius = FlocketCorners.tileRadius(size),
        )
        if (showBadge) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .offset(overhang, overhang)
                        .size(badgeSize)
                        .background(style.badge, CircleShape)
                        .border(2.dp, ringColor, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = style.filledIcon,
                    contentDescription = badgeContentDescription,
                    tint = ringColor,
                    modifier = Modifier.size(badgeSize * 0.6f),
                )
            }
        }
    }
}

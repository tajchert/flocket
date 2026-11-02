package com.mtajchert.flocket.core.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.FrontHand
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mtajchert.flocket.core.designsystem.theme.FlocketCorners
import com.mtajchert.flocket.core.designsystem.theme.flocketColors
import com.mtajchert.flocket.core.ui.R

/**
 * Amber pill "N need you" with a filled hand icon. [label] is a parameter so the terminal top bar can
 * show just the number (`label = count.toString()`) with [trailingArrow] = true. Clickable (48dp
 * touch target, button role) when [onClick] is set, plain otherwise.
 */
@Composable
fun NeedYouPill(
    count: Int,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    label: String = pluralStringResource(R.plurals.need_you_count, count, count),
    trailingArrow: Boolean = false,
) {
    val colors = MaterialTheme.flocketColors
    val click =
        if (onClick !=
            null
        ) {
            Modifier.minimumInteractiveComponentSize().clickable(role = Role.Button, onClick = onClick)
        } else {
            Modifier
        }
    Surface(
        modifier = modifier.then(click),
        shape = FlocketCorners.Chip,
        color = colors.attentionContainer,
        contentColor = colors.onAttentionContainer,
    ) {
        Row(
            modifier = Modifier.heightIn(min = 32.dp).padding(start = 10.dp, end = if (trailingArrow) 6.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Filled.FrontHand, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(label, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold))
            if (trailingArrow) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/**
 * Small count pill (16dp min height, 8dp radius, 10sp bold) for filter chips and nav items.
 * [color] is the background (default amber; pass `colorScheme.error` for the nav badge) and the
 * text uses the app background. Counts above 99 show "99+". Decorative: put the meaning in the
 * parent's semantics.
 */
@Composable
fun CountBadge(
    n: Int,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.flocketColors.attention,
) {
    Box(
        modifier =
            modifier
                .widthIn(min = 16.dp)
                .heightIn(min = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(color = color, contentColor = MaterialTheme.colorScheme.background, shape = RoundedCornerShape(8.dp)) {
            Text(
                text = if (n > 99) "99+" else n.toString(),
                modifier = Modifier.widthIn(min = 16.dp).padding(horizontal = 4.dp),
                style =
                    MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        lineHeight = 16.sp,
                    ),
                textAlign = TextAlign.Center,
            )
        }
    }
}

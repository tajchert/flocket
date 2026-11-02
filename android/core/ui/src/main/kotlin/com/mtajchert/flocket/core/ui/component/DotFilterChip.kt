package com.mtajchert.flocket.core.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.mtajchert.flocket.core.designsystem.theme.FlocketCorners

/**
 * Filter chip of the inbox: 32dp tall (48dp touch target), 8dp radius, optional 8dp color dot
 * ([dotColor]) before the [label] and optional [count] badge after it. Selected = secondary
 * container, unselected = outlined. Selection is exposed to TalkBack (checkbox role, like M3 chips).
 */
@Composable
fun DotFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dotColor: Color? = null,
    count: Int? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier =
            modifier
                .minimumInteractiveComponentSize()
                .selectable(selected = selected, role = Role.Checkbox, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = FlocketCorners.Chip,
            color = if (selected) scheme.secondaryContainer else Color.Transparent,
            contentColor = if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
            border = if (selected) null else BorderStroke(1.dp, scheme.outlineVariant),
        ) {
            Row(
                modifier = Modifier.heightIn(min = 32.dp).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (dotColor != null) Box(Modifier.size(8.dp).background(dotColor, CircleShape))
                Text(label, style = MaterialTheme.typography.labelLarge)
                if (count != null) CountBadge(count)
            }
        }
    }
}

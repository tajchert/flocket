package com.mtajchert.flocket.core.designsystem

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.mtajchert.flocket.core.designsystem.component.FlocketButton
import com.mtajchert.flocket.core.designsystem.component.FlocketCard
import com.mtajchert.flocket.core.designsystem.component.FlocketElevatedCard
import com.mtajchert.flocket.core.designsystem.component.FlocketEmptyState
import com.mtajchert.flocket.core.designsystem.component.FlocketErrorState
import com.mtajchert.flocket.core.designsystem.component.FlocketLoadingIndicator
import com.mtajchert.flocket.core.designsystem.component.FlocketOutlinedButton
import com.mtajchert.flocket.core.designsystem.component.FlocketSmallLoadingIndicator
import com.mtajchert.flocket.core.designsystem.component.FlocketTextButton
import com.mtajchert.flocket.core.designsystem.component.FlocketTopAppBar
import com.mtajchert.flocket.core.designsystem.theme.FlocketTheme

@Preview(name = "Light", showBackground = true, widthDp = 360)
@Preview(
    name = "Dark",
    showBackground = true,
    widthDp = 360,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
private annotation class PreviewComponentThemes

@PreviewTest
@PreviewComponentThemes
@Composable
private fun ButtonsScreenshotTest() {
    FlocketTheme(dynamicColor = false) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FlocketButton(onClick = {}, text = "Primary")
            FlocketButton(onClick = {}, text = "Loading", isLoading = true)
            FlocketButton(onClick = {}, text = "Disabled", enabled = false)
            FlocketOutlinedButton(onClick = {}, text = "Outlined")
            FlocketTextButton(onClick = {}, text = "Text")
        }
    }
}

@PreviewTest
@PreviewComponentThemes
@Composable
private fun CardsScreenshotTest() {
    FlocketTheme(dynamicColor = false) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FlocketCard(onClick = {}) {
                Text("Standard card", style = MaterialTheme.typography.titleMedium)
                Text("Supporting content")
            }
            FlocketElevatedCard {
                Text("Elevated card", style = MaterialTheme.typography.titleMedium)
                Text("Prominent supporting content")
            }
        }
    }
}

@PreviewTest
@PreviewComponentThemes
@Composable
private fun LoadingIndicatorsScreenshotTest() {
    FlocketTheme(dynamicColor = false) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            FlocketLoadingIndicator(modifier = Modifier.size(80.dp))
            FlocketSmallLoadingIndicator()
        }
    }
}

@PreviewTest
@Preview(name = "Empty and error", showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun EmptyAndErrorStatesScreenshotTest() {
    FlocketTheme(dynamicColor = false) {
        Column {
            FlocketEmptyState(
                icon = Icons.Outlined.Inbox,
                title = "Nothing here",
                description = "Create the first item to get started.",
                actionLabel = "Create item",
                onAction = {},
                modifier = Modifier.weight(1f),
            )
            FlocketErrorState(
                message = "The content could not be loaded.",
                onRetry = {},
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@PreviewTest
@PreviewComponentThemes
@Composable
private fun TopAppBarsScreenshotTest() {
    FlocketTheme(dynamicColor = false) {
        Column {
            FlocketTopAppBar(title = "Overview")
            FlocketTopAppBar(title = "Details", onNavigationClick = {})
        }
    }
}

package com.mtajchert.flocket.core.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Readable content column: full width on phones, centered with a max width on tablets and in landscape. */
@Composable
fun CenteredContent(
    modifier: Modifier = Modifier,
    maxWidth: Dp = 600.dp,
    contentAlignment: Alignment = Alignment.TopCenter,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.fillMaxSize(), contentAlignment = contentAlignment) {
        Box(Modifier.widthIn(max = maxWidth).fillMaxSize(), content = content)
    }
}

package com.mtajchert.flocket.feature.hosts.impl

import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.runtime.Composable

/** True when the list-detail scene shows two panes, so the detail pane must not draw a back arrow. */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
internal fun rememberIsMultiPane(): Boolean =
    calculatePaneScaffoldDirective(currentWindowAdaptiveInfoV2()).maxHorizontalPartitions > 1

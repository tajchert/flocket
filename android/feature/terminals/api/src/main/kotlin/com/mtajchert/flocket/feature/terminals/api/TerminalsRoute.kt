package com.mtajchert.flocket.feature.terminals.api

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** Top-level terminals home (list pane on wide windows, start destination after onboarding). */
@Serializable
data object TerminalsRoute : NavKey

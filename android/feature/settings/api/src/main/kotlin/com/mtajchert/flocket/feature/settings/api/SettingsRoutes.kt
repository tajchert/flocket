package com.mtajchert.flocket.feature.settings.api

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** Top-level settings. */
@Serializable
data object SettingsRoute : NavKey

/** Open-source licenses. Full-screen. */
@Serializable
data object LicensesRoute : NavKey

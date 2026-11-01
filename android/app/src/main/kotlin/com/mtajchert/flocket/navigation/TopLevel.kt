package com.mtajchert.flocket.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation3.runtime.NavKey
import com.mtajchert.flocket.R
import com.mtajchert.flocket.feature.hosts.api.HostEditRoute
import com.mtajchert.flocket.feature.hosts.api.HostsRoute
import com.mtajchert.flocket.feature.onboarding.api.OnboardingRoute
import kotlinx.serialization.Serializable

/** Start destination. Placeholder until `feature:terminals:impl` contributes the real entry. */
@Serializable
data object TerminalsRoute : NavKey

/** Placeholder until `feature:settings:impl` contributes the real entry. */
@Serializable
data object SettingsRoute : NavKey

/** A destination of the navigation bar (compact) or rail (medium and expanded). */
data class TopLevelDestination(
    val key: NavKey,
    @StringRes val label: Int,
    val icon: ImageVector,
)

val TopLevelDestinations =
    listOf(
        TopLevelDestination(TerminalsRoute, R.string.nav_terminals, Icons.Outlined.Terminal),
        TopLevelDestination(HostsRoute, R.string.nav_hosts, Icons.Outlined.Dns),
        TopLevelDestination(SettingsRoute, R.string.nav_settings, Icons.Outlined.Settings),
    )

/** Routes that cover the whole window; the navigation bar/rail is hidden while one is on top. */
fun NavKey?.isFullScreen(): Boolean = this is OnboardingRoute || this is HostEditRoute

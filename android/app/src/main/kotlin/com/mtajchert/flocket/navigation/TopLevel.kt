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
import com.mtajchert.flocket.feature.settings.api.LicensesRoute
import com.mtajchert.flocket.feature.settings.api.SettingsRoute
import com.mtajchert.flocket.feature.terminal.api.TerminalRoute
import com.mtajchert.flocket.feature.terminals.api.TerminalsRoute
import kotlinx.serialization.Serializable

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

/**
 * Routes that cover the whole window; the navigation bar/rail is hidden while one is on top.
 *
 * The terminal covers it when it is the only pane ([singlePane]), in focus mode ([focusMode]) and on
 * compact-height windows ([compactHeight], e.g. a landscape phone) where the rail would eat width and
 * the terminal needs every row.
 */
fun NavKey?.isFullScreen(
    singlePane: Boolean,
    focusMode: Boolean = false,
    compactHeight: Boolean = false,
): Boolean =
    this is OnboardingRoute ||
        this is HostEditRoute ||
        this is LicensesRoute ||
        // A bottom bar would also sit under the IME and leave a gap above it.
        (this is TerminalRoute && (singlePane || focusMode || compactHeight))

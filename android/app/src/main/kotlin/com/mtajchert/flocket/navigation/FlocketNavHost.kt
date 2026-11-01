package com.mtajchert.flocket.navigation

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.mtajchert.flocket.R
import com.mtajchert.flocket.core.ui.nav.EntryProviderInstaller
import com.mtajchert.flocket.core.ui.nav.Navigator
import com.mtajchert.flocket.feature.onboarding.api.OnboardingRoute
import com.mtajchert.flocket.feature.terminal.api.TerminalRoute
import com.mtajchert.flocket.feature.terminals.api.TerminalsRoute

/**
 * Navigation 3 host for the whole app: owns the single back stack, hands features a [Navigator] and
 * collects their entries from the Hilt-provided [installers]. See docs/ARCHITECTURE.md (Navigation).
 *
 * The root of the back stack is the selected top-level destination; switching tabs replaces the stack.
 * [NavigationSuiteScaffold] shows a bottom bar on compact windows and a rail on wider ones, and hides
 * itself on full-screen routes (see [isFullScreen]).
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun FlocketNavHost(
    installers: Set<EntryProviderInstaller>,
    showOnboarding: Boolean,
    modifier: Modifier = Modifier,
    backStack: NavBackStack<NavKey> = rememberNavBackStack(if (showOnboarding) OnboardingRoute else TerminalsRoute),
) {
    val navigator = remember(backStack) { BackStackNavigator(backStack) }

    // The spacer override removes the gap between panes (b/418201867); drop it once fixed upstream.
    val adaptiveInfo = currentWindowAdaptiveInfoV2()
    val directive =
        remember(adaptiveInfo) {
            calculatePaneScaffoldDirective(adaptiveInfo).copy(horizontalPartitionSpacerSize = 0.dp)
        }
    val listDetailStrategy = rememberListDetailSceneStrategy<NavKey>(directive = directive)

    val current = backStack.lastOrNull()
    val selectedRoot = backStack.firstOrNull()
    val layoutType =
        if (current.isFullScreen()) {
            NavigationSuiteType.None
        } else {
            NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(adaptiveInfo)
        }

    NavigationSuiteScaffold(
        modifier = modifier,
        layoutType = layoutType,
        navigationSuiteItems = {
            TopLevelDestinations.forEach { destination ->
                item(
                    selected = destination.key == selectedRoot,
                    onClick = { navigator.selectTopLevel(destination.key) },
                    icon = { Icon(destination.icon, contentDescription = null) },
                    label = { Text(stringResource(destination.label)) },
                )
            }
        },
    ) {
        NavDisplay(
            backStack = backStack,
            onBack = { navigator.back() },
            sceneStrategies = listOf(listDetailStrategy),
            // Overriding the decorators replaces the defaults, so the saveable-state one is listed too.
            entryDecorators =
                listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    rememberViewModelStoreNavEntryDecorator(),
                ),
            entryProvider =
                entryProvider {
                    installers.forEach { it.install(this, navigator) }
                },
        )
    }
}

private class BackStackNavigator(
    private val backStack: NavBackStack<NavKey>,
) : Navigator {
    override val current: NavKey? get() = backStack.lastOrNull()

    override fun navigate(key: NavKey) {
        val last = backStack.lastOrNull()
        if (last == key) return
        // Re-selecting the open terminal keeps its screen (and its attached stream).
        if (key is TerminalRoute && last is TerminalRoute && key.isSameTerminal(last)) return
        // Picking another terminal in a two-pane layout swaps the detail pane instead of piling up the back stack.
        if (key is TerminalRoute && last is TerminalRoute && backStack.size > 1) backStack.removeLastOrNull()
        backStack.add(key)
    }

    override fun back() {
        if (backStack.size > 1) backStack.removeLastOrNull()
    }

    override fun goHome() = replaceRoot(TerminalsRoute)

    fun selectTopLevel(key: NavKey) {
        if (backStack.size == 1 && backStack.first() == key) return
        replaceRoot(key)
    }

    private fun replaceRoot(key: NavKey) {
        backStack.add(key)
        while (backStack.size > 1) backStack.removeAt(0)
    }
}

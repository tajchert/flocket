package com.mtajchert.flocket.navigation

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.WindowAdaptiveInfo
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import androidx.window.core.layout.WindowSizeClass
import com.mtajchert.flocket.R
import com.mtajchert.flocket.core.ui.nav.EntryProviderInstaller
import com.mtajchert.flocket.core.ui.nav.LocalPaneLayout
import com.mtajchert.flocket.core.ui.nav.LocalSidebarToggle
import com.mtajchert.flocket.core.ui.nav.Navigator
import com.mtajchert.flocket.core.ui.nav.PaneLayout
import com.mtajchert.flocket.core.ui.nav.SidebarToggle
import com.mtajchert.flocket.feature.onboarding.api.OnboardingRoute
import com.mtajchert.flocket.feature.terminal.api.TerminalRoute
import com.mtajchert.flocket.feature.terminals.api.TerminalsRoute

/**
 * Navigation 3 host for the whole app: owns the single back stack, hands features a [Navigator] and
 * collects their entries from the Hilt-provided [installers]. See docs/ARCHITECTURE.md (Navigation).
 *
 * The root of the back stack is the selected top-level destination; switching tabs replaces the stack.
 * [NavigationSuiteScaffold] shows a bottom bar on compact windows and a rail on wider ones, and hides
 * itself on full-screen routes (see [isFullScreen]), including the terminal on single-pane windows.
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
    // Sidebar width (Ruling 8): 300dp on expanded windows, 280dp on other multi-pane ones; the detail gets the rest.
    val adaptiveInfo = currentWindowAdaptiveInfoV2()
    val windowSizeClass = adaptiveInfo.windowSizeClass
    val expandedWidth = windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)
    val compactHeight = !windowSizeClass.isHeightAtLeastBreakpoint(WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND)
    val baseDirective =
        remember(adaptiveInfo) {
            calculatePaneScaffoldDirective(adaptiveInfo).copy(
                horizontalPartitionSpacerSize = 0.dp,
                defaultPanePreferredWidth = if (expandedWidth) SidebarWidthExpanded else SidebarWidth,
            )
        }
    // Measured BEFORE focus mode narrows the directive: it says what the window can do, not what is shown.
    val multiPane = baseDirective.maxHorizontalPartitions > 1

    val current = backStack.lastOrNull()
    val selectedRoot = backStack.firstOrNull()

    // Focus mode (Ruling 7): the terminal takes the full width. Process death resets it (saveable survives
    // config changes and fold/unfold only). It means nothing on single-pane windows or off a terminal.
    var focusRequested by rememberSaveable { mutableStateOf(false) }
    val focusMode = focusRequested && multiPane && current is TerminalRoute
    LaunchedEffect(multiPane, current is TerminalRoute) {
        if (!multiPane || current !is TerminalRoute) focusRequested = false
    }
    val directive = if (focusMode) baseDirective.copy(maxHorizontalPartitions = 1) else baseDirective
    val listDetailStrategy = rememberListDetailSceneStrategy<NavKey>(directive = directive)

    val paneLayout =
        remember(multiPane, focusMode, compactHeight) {
            PaneLayout(multiPane = multiPane, sidebarVisible = !focusMode, compactHeight = compactHeight)
        }
    val sidebarToggle = remember { SidebarToggle { visible -> focusRequested = !visible } }

    val layoutType =
        if (current.isFullScreen(singlePane = !multiPane, focusMode = focusMode, compactHeight = compactHeight)) {
            NavigationSuiteType.None
        } else {
            navigationSuiteType(adaptiveInfo, multiPane = multiPane, compactHeight = compactHeight)
        }

    CompositionLocalProvider(
        LocalPaneLayout provides paneLayout,
        LocalSidebarToggle provides sidebarToggle,
    ) {
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
                // Back leaves focus mode (restores the sidebar) before it pops anything.
                onBack = { if (focusRequested) focusRequested = false else navigator.back() },
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
}

/**
 * The default picks a bottom bar for compact-height windows, which on a landscape phone eats a quarter
 * of the height the list and terminal need. A wide, short window gets the rail instead.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
internal fun navigationSuiteType(
    adaptiveInfo: WindowAdaptiveInfo,
    multiPane: Boolean,
    compactHeight: Boolean,
): NavigationSuiteType =
    if (multiPane && compactHeight) {
        NavigationSuiteType.NavigationRail
    } else {
        NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(adaptiveInfo)
    }

private val SidebarWidth = 280.dp
private val SidebarWidthExpanded = 300.dp

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

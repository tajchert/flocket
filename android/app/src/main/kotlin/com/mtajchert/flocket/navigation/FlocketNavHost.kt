package com.mtajchert.flocket.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DrawerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailDefaults
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.WindowAdaptiveInfo
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirectiveWithTwoPanesOnMediumWidth
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuite
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldLayout
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldValue
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.material3.adaptive.navigationsuite.rememberNavigationSuiteScaffoldState
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
import com.mtajchert.flocket.core.designsystem.theme.FlocketCorners
import com.mtajchert.flocket.core.ui.component.CountBadge
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
 * The rail also carries the New terminal button ([onNewTerminal]); list-detail panes are drawn as cards
 * ([rememberPaneCardEntryDecorator]).
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun FlocketNavHost(
    installers: Set<EntryProviderInstaller>,
    showOnboarding: Boolean,
    modifier: Modifier = Modifier,
    /** Terminals needing the user across all hosts; shown as a badge on the Terminals item. */
    needsYouCount: Int = 0,
    /** Opens the terminals list's New terminal sheet (the rail's button), after Terminals is shown. */
    onNewTerminal: () -> Unit = {},
    backStack: NavBackStack<NavKey> = rememberNavBackStack(if (showOnboarding) OnboardingRoute else TerminalsRoute),
) {
    val navigator = remember(backStack) { BackStackNavigator(backStack) }

    val adaptiveInfo = currentWindowAdaptiveInfoV2()
    val compactHeight =
        !adaptiveInfo.windowSizeClass.isHeightAtLeastBreakpoint(WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND)
    val baseDirective = remember(adaptiveInfo) { paneScaffoldDirective(adaptiveInfo) }
    // Measured BEFORE focus mode narrows the directive: it says what the window can do, not what is shown.
    val multiPane = baseDirective.maxHorizontalPartitions > 1

    val current = backStack.lastOrNull()
    val selectedRoot = backStack.firstOrNull()

    // Focus mode (Ruling 7): the terminal takes the full width. Process death resets it (saveable survives
    // config changes and fold/unfold only). It means nothing on single-pane windows or off a terminal.
    var focusRequested by rememberSaveable { mutableStateOf(false) }
    val onTerminal = current is TerminalRoute
    val focusMode = isFocusMode(focusRequested, multiPane, onTerminal)
    LaunchedEffect(multiPane, onTerminal) {
        if (shouldResetFocus(multiPane, onTerminal)) focusRequested = false
    }
    val directive = if (focusMode) baseDirective.copy(maxHorizontalPartitions = 1) else baseDirective
    // On a multi-pane window the list-detail scene also lays out a single pane, so focus mode is a change of
    // the scaffold's value (the list slides away, the terminal widens) rather than a swap to another scene.
    val listDetailStrategy =
        rememberListDetailSceneStrategy<NavKey>(shouldHandleSinglePaneLayout = multiPane, directive = directive)

    // Full-screen routes drop the navigation suite at once; focus mode slides the rail away (suiteState).
    val layoutType =
        if (current.isFullScreen(singlePane = !multiPane, compactHeight = compactHeight)) {
            NavigationSuiteType.None
        } else {
            navigationSuiteType(adaptiveInfo, multiPane = multiPane, compactHeight = compactHeight)
        }
    val suiteState =
        rememberNavigationSuiteScaffoldState(
            if (focusMode) NavigationSuiteScaffoldValue.Hidden else NavigationSuiteScaffoldValue.Visible,
        )
    LaunchedEffect(focusMode) { if (focusMode) suiteState.hide() else suiteState.show() }
    // Still true while the rail slides away in focus mode, so neither the rail nor the list swaps its New button.
    val railShowing = layoutType == NavigationSuiteType.NavigationRail

    val paneLayout =
        remember(multiPane, focusMode, compactHeight, railShowing) {
            PaneLayout(
                multiPane = multiPane,
                sidebarVisible = !focusMode,
                compactHeight = compactHeight,
                navigationRail = railShowing,
            )
        }
    val sidebarToggle = remember { SidebarToggle { visible -> focusRequested = !visible } }

    // The rail's New terminal button opens the list's sheet; it keeps a terminal open beside the list.
    val newTerminal = {
        if (selectedRoot != TerminalsRoute) navigator.selectTopLevel(TerminalsRoute)
        onNewTerminal()
    }

    CompositionLocalProvider(
        LocalPaneLayout provides paneLayout,
        LocalSidebarToggle provides sidebarToggle,
    ) {
        // NavigationSuiteScaffold, unrolled so the rail can carry the New terminal button as its header.
        Surface(
            modifier = modifier,
            color = NavigationSuiteScaffoldDefaults.containerColor,
            contentColor = NavigationSuiteScaffoldDefaults.contentColor,
        ) {
            NavigationSuiteScaffoldLayout(
                navigationSuite = {
                    if (railShowing) {
                        FlocketNavigationRail(selectedRoot, needsYouCount, navigator::selectTopLevel, newTerminal)
                    } else {
                        NavigationSuite(layoutType = layoutType) {
                            TopLevelDestinations.forEach { destination ->
                                item(
                                    selected = destination.key == selectedRoot,
                                    onClick = { navigator.selectTopLevel(destination.key) },
                                    icon = { DestinationIcon(destination, selectedRoot, needsYouCount) },
                                    label = { Text(stringResource(destination.label)) },
                                )
                            }
                        }
                    }
                },
                layoutType = layoutType,
                state = suiteState,
            ) {
                Box(
                    Modifier.consumeWindowInsets(if (focusMode) WindowInsets(0) else navigationSuiteInsets(layoutType)),
                ) {
                    NavDisplay(
                        backStack = backStack,
                        // Back leaves focus mode (restores the sidebar) before it pops anything.
                        onBack = { if (backLeavesFocus(focusRequested)) focusRequested = false else navigator.back() },
                        sceneStrategies = listOf(listDetailStrategy),
                        // Overriding the decorators replaces the defaults, so the saveable-state one is listed too.
                        entryDecorators =
                            listOf(
                                rememberSaveableStateHolderNavEntryDecorator(),
                                rememberViewModelStoreNavEntryDecorator(),
                                rememberPaneCardEntryDecorator(),
                            ),
                        entryProvider =
                            entryProvider {
                                installers.forEach { it.install(this, navigator) }
                            },
                    )
                }
            }
        }
    }
}

/** The rail of multi-pane windows: the New terminal button as its header, then the top-level destinations. */
@Composable
internal fun FlocketNavigationRail(
    selectedRoot: NavKey?,
    needsYouCount: Int,
    onSelect: (NavKey) -> Unit,
    onNewTerminal: () -> Unit,
) {
    NavigationRail(header = { NewTerminalRailButton(onNewTerminal) }) {
        TopLevelDestinations.forEach { destination ->
            NavigationRailItem(
                selected = destination.key == selectedRoot,
                onClick = { onSelect(destination.key) },
                icon = { DestinationIcon(destination, selectedRoot, needsYouCount) },
                label = { Text(stringResource(destination.label)) },
            )
        }
    }
}

/** A destination's icon, filled when selected; Terminals carries the needs-you count. */
@Composable
private fun DestinationIcon(
    destination: TopLevelDestination,
    selectedRoot: NavKey?,
    needsYouCount: Int,
) {
    val icon = if (destination.key == selectedRoot) destination.selectedIcon else destination.icon
    if (destination.key == TerminalsRoute && needsYouCount > 0) {
        BadgedBox(badge = { CountBadge(needsYouCount, color = MaterialTheme.colorScheme.error) }) {
            Icon(icon, contentDescription = null)
        }
    } else {
        Icon(icon, contentDescription = null)
    }
}

/** The design's rail FAB: 56dp, 16dp corners, flat on the rail. */
@Composable
private fun NewTerminalRailButton(onClick: () -> Unit) {
    FloatingActionButton(
        onClick = onClick,
        modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
        shape = FlocketCorners.Fab,
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        elevation = FloatingActionButtonDefaults.bottomAppBarFabElevation(),
    ) {
        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.nav_new_terminal))
    }
}

/** The window insets the navigation suite pads for, which the content must not pad for again. */
@Composable
private fun navigationSuiteInsets(layoutType: NavigationSuiteType): WindowInsets =
    when (layoutType) {
        NavigationSuiteType.NavigationBar -> NavigationBarDefaults.windowInsets.only(WindowInsetsSides.Bottom)
        NavigationSuiteType.NavigationRail -> NavigationRailDefaults.windowInsets.only(WindowInsetsSides.Start)
        NavigationSuiteType.NavigationDrawer -> DrawerDefaults.windowInsets.only(WindowInsetsSides.Start)
        else -> WindowInsets(0)
    }

/**
 * How many panes the window gets and how wide the list is. Medium widths (unfolded foldables, small
 * tablets) get two panes, as the default directive only splits expanded windows.
 *
 * The spacer override removes the gap between panes (b/418201867); drop it once fixed upstream.
 * Sidebar width (Ruling 8): 300dp on expanded windows, 280dp on other multi-pane ones; the detail gets the rest.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
internal fun paneScaffoldDirective(adaptiveInfo: WindowAdaptiveInfo): PaneScaffoldDirective {
    val expandedWidth =
        adaptiveInfo.windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)
    return calculatePaneScaffoldDirectiveWithTwoPanesOnMediumWidth(adaptiveInfo).copy(
        horizontalPartitionSpacerSize = 0.dp,
        defaultPanePreferredWidth = if (expandedWidth) SidebarWidthExpanded else SidebarWidth,
    )
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

/** Focus mode (hidden sidebar) only exists on a multi-pane window with a terminal on top. */
internal fun isFocusMode(
    requested: Boolean,
    multiPane: Boolean,
    onTerminal: Boolean,
): Boolean = requested && multiPane && onTerminal

/** The saved focus request is dropped when focus mode can no longer apply. */
internal fun shouldResetFocus(
    multiPane: Boolean,
    onTerminal: Boolean,
): Boolean = !multiPane || !onTerminal

/** Back leaves focus mode (restoring the sidebar, keeping the terminal) before it pops the stack. */
internal fun backLeavesFocus(focusRequested: Boolean): Boolean = focusRequested

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

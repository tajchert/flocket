package com.mtajchert.flocket.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
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
import kotlinx.serialization.Serializable

/** Placeholder start destination until the feature modules contribute their entries. */
@Serializable
data object HomeRoute : NavKey

/**
 * Navigation 3 host for the whole app.
 *
 * A single back stack drives every window size. [rememberListDetailSceneStrategy] lets destinations that
 * declare a list or detail pane role render side by side on wide windows (tablet, unfolded foldable) and one
 * at a time on a phone; features never branch on screen size themselves.
 *
 * Features contribute entries from their `:impl` modules with an extension on `EntryProviderScope<NavKey>`:
 * ```kotlin
 * entryProvider = entryProvider {
 *     homeEntry()
 *     hostsEntries(onNavigate = { backStack.add(it) }, onBack = { backStack.removeLastOrNull() })
 * }
 * ```
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun FlocketNavHost(
    modifier: Modifier = Modifier,
    backStack: NavBackStack<NavKey> = rememberNavBackStack(HomeRoute),
) {
    // The spacer override removes the gap between panes (b/418201867); drop it once fixed upstream.
    val windowAdaptiveInfo = currentWindowAdaptiveInfoV2()
    val directive =
        remember(windowAdaptiveInfo) {
            calculatePaneScaffoldDirective(windowAdaptiveInfo).copy(horizontalPartitionSpacerSize = 0.dp)
        }
    val listDetailStrategy = rememberListDetailSceneStrategy<NavKey>(directive = directive)

    NavDisplay(
        backStack = backStack,
        modifier = modifier,
        onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
        sceneStrategies = listOf(listDetailStrategy),
        // Overriding the decorators replaces the defaults, so the saveable-state one is listed too.
        entryDecorators =
            listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
        entryProvider =
            entryProvider {
                entry<HomeRoute> { HomePlaceholder() }
            },
    )
}

@Composable
private fun HomePlaceholder(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
    }
}

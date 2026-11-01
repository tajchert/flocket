@file:Suppress("ktlint:standard:max-line-length")

package com.mtajchert.flocket.core.ui.nav

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey

/** What features may do to navigation. `:app` owns the back stack and implements this. */
interface Navigator {
    fun navigate(key: NavKey)

    fun back()

    /** Go to the start destination (Terminals) and clear everything above it. */
    fun goHome()
}

/**
 * A feature's contribution to the app's Navigation 3 graph. `feature:x:impl` provides one with Hilt:
 * ```kotlin
 * @Provides @IntoSet fun installer(): EntryProviderInstaller = EntryProviderInstaller { scope, nav -> scope.entry<MyRoute> { ... } }
 * ```
 * `:app` collects the set and never needs to know feature internals.
 */
fun interface EntryProviderInstaller {
    fun install(
        scope: EntryProviderScope<NavKey>,
        navigator: Navigator,
    )
}

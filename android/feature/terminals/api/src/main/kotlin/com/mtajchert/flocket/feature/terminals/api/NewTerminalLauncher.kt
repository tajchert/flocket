package com.mtajchert.flocket.feature.terminals.api

/**
 * Asks the terminals list to open its "New terminal" sheet from outside the feature: the navigation rail's
 * button in `:app`. The caller first shows [TerminalsRoute]; the list picks the request up once it is composed
 * (a request made before that waits, later ones replace it).
 */
fun interface NewTerminalLauncher {
    fun requestNewTerminal()
}

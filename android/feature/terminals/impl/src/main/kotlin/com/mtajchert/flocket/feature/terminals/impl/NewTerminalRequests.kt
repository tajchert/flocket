package com.mtajchert.flocket.feature.terminals.impl

import com.mtajchert.flocket.feature.terminals.api.NewTerminalLauncher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Carries "New terminal" requests from outside the feature (the rail button) to the terminals list. Conflated:
 * a request waits until the list is composed, and a second tap before then does not open the sheet twice.
 */
@Singleton
class NewTerminalRequests
    @Inject
    constructor() : NewTerminalLauncher {
        private val channel = Channel<Unit>(Channel.CONFLATED)

        /** One element per request; collect it from one place only. */
        val requests: Flow<Unit> = channel.receiveAsFlow()

        override fun requestNewTerminal() {
            channel.trySend(Unit)
        }
    }

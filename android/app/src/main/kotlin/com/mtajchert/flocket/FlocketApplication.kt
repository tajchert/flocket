package com.mtajchert.flocket

import android.app.Application
import com.mtajchert.flocket.core.data.ConnectionManager
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/** Application entry point; `@HiltAndroidApp` generates the Hilt component root. */
@HiltAndroidApp
class FlocketApplication : Application() {
    /** Injected eagerly so the connection lifecycle (foreground, network, sessions) runs from process start. */
    @Inject lateinit var connectionManager: ConnectionManager
}

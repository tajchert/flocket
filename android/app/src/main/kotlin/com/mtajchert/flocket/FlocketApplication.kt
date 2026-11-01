package com.mtajchert.flocket

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/** Application entry point; `@HiltAndroidApp` generates the Hilt component root. */
@HiltAndroidApp
class FlocketApplication : Application()

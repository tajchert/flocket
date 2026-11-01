package com.mtajchert.flocket

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.mtajchert.flocket.core.designsystem.theme.FlocketTheme
import com.mtajchert.flocket.navigation.FlocketNavHost
import dagger.hilt.android.AndroidEntryPoint

/** Single Activity: every screen is a Navigation 3 destination rendered by [FlocketNavHost]. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // The splash screen must be installed before super.onCreate().
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FlocketTheme {
                FlocketNavHost()
            }
        }
    }
}

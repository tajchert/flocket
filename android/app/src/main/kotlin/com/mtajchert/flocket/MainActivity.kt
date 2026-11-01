package com.mtajchert.flocket

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mtajchert.flocket.core.designsystem.theme.FlocketTheme
import com.mtajchert.flocket.core.ui.nav.EntryProviderInstaller
import com.mtajchert.flocket.navigation.AppViewModel
import com.mtajchert.flocket.navigation.FlocketNavHost
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** Single Activity: every screen is a Navigation 3 destination rendered by [FlocketNavHost]. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var installers: Set<@JvmSuppressWildcards EntryProviderInstaller>

    override fun onCreate(savedInstanceState: Bundle?) {
        // The splash screen must be installed before super.onCreate().
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FlocketTheme {
                val appViewModel: AppViewModel = hiltViewModel()
                val showOnboarding by appViewModel.showOnboarding.collectAsStateWithLifecycle()
                splash.setKeepOnScreenCondition { showOnboarding == null }
                Surface {
                    showOnboarding?.let { FlocketNavHost(installers = installers, showOnboarding = it) }
                }
            }
        }
    }
}

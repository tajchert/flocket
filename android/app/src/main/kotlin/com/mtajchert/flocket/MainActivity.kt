package com.mtajchert.flocket

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mtajchert.flocket.core.data.ThemePreference
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
        setContent {
            val appViewModel: AppViewModel = hiltViewModel()
            val prefs by appViewModel.userPreferences.collectAsStateWithLifecycle()
            val darkTheme =
                when (prefs.theme) {
                    ThemePreference.System -> isSystemInDarkTheme()
                    ThemePreference.Light -> false
                    ThemePreference.Dark -> true
                }
            DisposableEffect(darkTheme) {
                val transparent = Color.TRANSPARENT
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(transparent, transparent) { darkTheme },
                    navigationBarStyle = SystemBarStyle.auto(transparent, transparent) { darkTheme },
                )
                onDispose {}
            }
            FlocketTheme(darkTheme = darkTheme, dynamicColor = prefs.dynamicColor) {
                val showOnboarding by appViewModel.showOnboarding.collectAsStateWithLifecycle()
                splash.setKeepOnScreenCondition { showOnboarding == null }
                Surface {
                    showOnboarding?.let { FlocketNavHost(installers = installers, showOnboarding = it) }
                }
            }
        }
    }
}

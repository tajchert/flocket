package com.mtajchert.flocket.feature.onboarding.api

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** The first-run flow; also reachable later from the empty Hosts screen. Full-screen. */
@Serializable
data object OnboardingRoute : NavKey

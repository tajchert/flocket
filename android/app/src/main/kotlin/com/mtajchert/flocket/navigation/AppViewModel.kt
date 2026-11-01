package com.mtajchert.flocket.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mtajchert.flocket.core.data.PreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Decides the start destination once per process: onboarding on first run, Terminals afterwards. */
@HiltViewModel
class AppViewModel
    @Inject
    constructor(
        preferences: PreferencesRepository,
    ) : ViewModel() {
        /** Null while preferences load; then true when onboarding must be shown first. Emitted once. */
        val showOnboarding: StateFlow<Boolean?> =
            flow { emit(!preferences.preferences.first().onboardingCompleted) }
                .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    }

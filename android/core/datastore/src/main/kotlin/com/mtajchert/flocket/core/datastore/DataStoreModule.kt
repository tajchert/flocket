package com.mtajchert.flocket.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

private val Context.userPreferencesStore: DataStore<Preferences> by preferencesDataStore(
    name = "user_preferences",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

@Module
@InstallIn(SingletonComponent::class)
object DataStoreModule {
    @Provides
    @Singleton
    fun providePreferencesDataSource(
        @ApplicationContext context: Context,
    ): PreferencesDataSource = PreferencesDataSource(context.userPreferencesStore)
}

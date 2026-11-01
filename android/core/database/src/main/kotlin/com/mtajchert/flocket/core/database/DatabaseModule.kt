package com.mtajchert.flocket.core.database

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
    ): FlocketDatabase =
        Room
            .databaseBuilder(context, FlocketDatabase::class.java, FlocketDatabase.NAME)
            .setDriver(AndroidSQLiteDriver())
            .build()

    @Provides
    fun provideHostDao(db: FlocketDatabase): HostDao = db.hostDao()

    @Provides
    fun providePinnedHostKeyDao(db: FlocketDatabase): PinnedHostKeyDao = db.pinnedHostKeyDao()
}

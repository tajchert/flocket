package com.mtajchert.flocket.core.security

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SecurityModule {
    @Provides
    @Singleton
    fun provideKeyVault(
        @ApplicationContext context: Context,
    ): KeyVault = KeystoreKeyVault(KeystoreSecretCipher(), File(context.filesDir, "vault"))
}

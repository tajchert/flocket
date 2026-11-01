package com.mtajchert.flocket.keepalive

import com.mtajchert.flocket.core.data.KeepAliveService
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
internal interface KeepAliveModule {
    @Binds
    fun bindKeepAliveService(impl: AndroidKeepAliveService): KeepAliveService
}

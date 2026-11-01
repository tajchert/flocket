package com.mtajchert.flocket.feature.terminals.impl

import android.content.Context
import androidx.core.content.edit
import com.mtajchert.flocket.core.model.HostId
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Remembers the last working directory used per host to prefill the new-terminal form. */
interface LastCwdStore {
    fun get(hostId: HostId): String?

    fun put(
        hostId: HostId,
        cwd: String,
    )
}

class InMemoryLastCwdStore : LastCwdStore {
    private val map = mutableMapOf<HostId, String>()

    override fun get(hostId: HostId): String? = map[hostId]

    override fun put(
        hostId: HostId,
        cwd: String,
    ) {
        map[hostId] = cwd
    }
}

private class PrefsLastCwdStore(
    context: Context,
) : LastCwdStore {
    private val prefs = context.getSharedPreferences("terminals_last_cwd", Context.MODE_PRIVATE)

    override fun get(hostId: HostId): String? = prefs.getString(hostId.value, null)

    override fun put(
        hostId: HostId,
        cwd: String,
    ) = prefs.edit { putString(hostId.value, cwd) }
}

@Module
@InstallIn(SingletonComponent::class)
object LastCwdModule {
    @Provides
    @Singleton
    fun lastCwdStore(
        @ApplicationContext context: Context,
    ): LastCwdStore = PrefsLastCwdStore(context)
}

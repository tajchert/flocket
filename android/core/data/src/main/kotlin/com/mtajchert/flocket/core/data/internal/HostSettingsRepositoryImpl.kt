package com.mtajchert.flocket.core.data.internal

import com.mtajchert.flocket.core.common.result.AppResult
import com.mtajchert.flocket.core.common.result.appResult
import com.mtajchert.flocket.core.data.AutoConnectSettings
import com.mtajchert.flocket.core.data.HostSettingsRepository
import com.mtajchert.flocket.core.data.PreferencesRepository
import com.mtajchert.flocket.core.database.HostSettingsDao
import com.mtajchert.flocket.core.database.HostSettingsEntity
import com.mtajchert.flocket.core.model.BellMode
import com.mtajchert.flocket.core.model.ClipboardWritePolicy
import com.mtajchert.flocket.core.model.EffectiveHostSettings
import com.mtajchert.flocket.core.model.HostId
import com.mtajchert.flocket.core.model.HostSettingsOverrides
import com.mtajchert.flocket.core.model.TerminalColorScheme
import com.mtajchert.flocket.core.model.resolve
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class HostSettingsRepositoryImpl(
    private val dao: HostSettingsDao,
    private val preferences: PreferencesRepository,
) : HostSettingsRepository {
    // Read-modify-write of one row must not interleave (a pinch and a toggle at once).
    private val writeLock = Mutex()

    override fun overrides(hostId: HostId): Flow<HostSettingsOverrides> =
        dao.observe(hostId.value).map { it?.toOverrides() ?: HostSettingsOverrides() }.distinctUntilChanged()

    override fun effective(hostId: HostId): Flow<EffectiveHostSettings> =
        combine(preferences.preferences.map { it.terminal }, overrides(hostId)) { app, o -> resolve(app, o) }
            .distinctUntilChanged()

    override val customizedHosts: Flow<Set<HostId>> =
        dao
            .observeAll()
            .map { rows -> rows.filterNot { it.toOverrides().isEmpty }.map { HostId(it.hostId) }.toSet() }
            .distinctUntilChanged()

    override val autoConnect: Flow<AutoConnectSettings> =
        combine(preferences.preferences.map { it.terminal.autoConnect }, dao.observeAll()) { default, rows ->
            AutoConnectSettings(
                default,
                rows.mapNotNull { r -> r.autoConnect?.let { HostId(r.hostId) to it } }.toMap(),
            )
        }.distinctUntilChanged()

    override suspend fun update(
        hostId: HostId,
        transform: (HostSettingsOverrides) -> HostSettingsOverrides,
    ): AppResult<Unit> =
        appResult {
            writeLock.withLock {
                val next = transform(dao.get(hostId.value)?.toOverrides() ?: HostSettingsOverrides())
                // A row with nothing set is the same as no row: keeps "Custom" honest.
                if (next.isEmpty) dao.delete(hostId.value) else dao.upsert(next.toEntity(hostId))
            }
        }

    override suspend fun resetAll(hostId: HostId): AppResult<Unit> =
        appResult {
            writeLock.withLock { dao.delete(hostId.value) }
        }

    override suspend fun setFontSize(
        hostId: HostId,
        sizeSp: Float,
    ): AppResult<Unit> =
        appResult {
            writeLock.withLock {
                val current = dao.get(hostId.value)
                if (current?.fontSizeSp != null) {
                    dao.upsert(current.copy(fontSizeSp = sizeSp))
                } else {
                    preferences.update { it.copy(terminal = it.terminal.copy(fontSizeSp = sizeSp)) }
                }
            }
        }
}

internal fun HostSettingsEntity.toOverrides() =
    HostSettingsOverrides(
        fontSizeSp = fontSizeSp,
        colorScheme = colorScheme?.let { n -> TerminalColorScheme.entries.firstOrNull { it.name == n } },
        showExtraKeys = showExtraKeys,
        keepScreenOn = keepScreenOn,
        clipboardWritePolicy =
            clipboardWritePolicy?.let { n ->
                ClipboardWritePolicy.entries.firstOrNull { it.name == n }
            },
        bell = bell?.let { n -> BellMode.entries.firstOrNull { it.name == n } },
        autoConnect = autoConnect,
        defaultWorkingDirectory = defaultWorkingDirectory?.takeIf { it.isNotBlank() },
    )

internal fun HostSettingsOverrides.toEntity(hostId: HostId) =
    HostSettingsEntity(
        hostId = hostId.value,
        fontSizeSp = fontSizeSp,
        colorScheme = colorScheme?.name,
        showExtraKeys = showExtraKeys,
        keepScreenOn = keepScreenOn,
        clipboardWritePolicy = clipboardWritePolicy?.name,
        bell = bell?.name,
        autoConnect = autoConnect,
        defaultWorkingDirectory = defaultWorkingDirectory?.takeIf { it.isNotBlank() },
    )

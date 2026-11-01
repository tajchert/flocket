package com.mtajchert.flocket.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Remembers which agent shares the user has seen, by share id. Ids are kept in insertion order and capped at
 * [MAX_IDS] (the oldest are dropped). Also remembers which sources (host + machine) were read before, so a
 * first read can hide old entries. Works on primitives so this module needs no model types.
 */
class ShareSeenDataSource(
    private val store: DataStore<Preferences>,
) {
    val seen: Flow<Set<String>> = store.data.map { decode(it[SEEN]).toSet() }

    suspend fun markSeen(ids: Collection<String>) {
        store.edit { p -> p[SEEN] = encode(append(decode(p[SEEN]), ids)) }
    }

    /**
     * First read of [sourceKey]: marks the entries older than [HIDE_AFTER_SECONDS] (by [nowEpochSeconds]) seen
     * and returns true. Later calls change nothing and return false. [entries] are (share id, epoch seconds).
     */
    suspend fun firstRead(
        sourceKey: String,
        entries: List<Pair<String, Long>>,
        nowEpochSeconds: Long,
    ): Boolean {
        var first = false
        store.edit { p ->
            val sources = p[SOURCES].orEmpty()
            if (sourceKey in sources) return@edit
            first = true
            p[SOURCES] = sources + sourceKey
            val old =
                entries
                    .filter { (_, at) -> at < nowEpochSeconds - HIDE_AFTER_SECONDS }
                    .sortedBy { (_, at) -> at }
                    .map { (id, _) -> id }
            if (old.isNotEmpty()) p[SEEN] = encode(append(decode(p[SEEN]), old))
        }
        return first
    }

    private fun append(
        current: List<String>,
        ids: Collection<String>,
    ): List<String> {
        val added = ids.filter { '\n' !in it && it.isNotEmpty() }.distinct()
        return (current.filterNot { it in added } + added).takeLast(MAX_IDS)
    }

    private fun decode(raw: String?): List<String> = raw?.split('\n')?.filter { it.isNotEmpty() }.orEmpty()

    private fun encode(ids: List<String>): String = ids.joinToString("\n")

    companion object {
        const val MAX_IDS = 1000
        const val HIDE_AFTER_SECONDS = 10 * 60L
        private val SEEN = stringPreferencesKey("seen_share_ids")
        private val SOURCES = stringSetPreferencesKey("share_sources_read")
    }
}

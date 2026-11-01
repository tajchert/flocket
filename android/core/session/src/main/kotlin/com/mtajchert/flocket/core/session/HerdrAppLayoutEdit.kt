package com.mtajchert.flocket.core.session

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The one edit Flocket makes to the desktop app's `herdr-app` document (protocol/SPEC.md §9.1,
 * ADR-0008): set the `name` of one space or tab, found by id.
 *
 * Works on the raw JSON tree, copy-on-write: only the objects on the path to the changed `name`
 * are rebuilt, and every other field (unknown ones included) is kept as it was. The first space
 * with the id is the one edited (a repeated id addresses only its first occurrence), and a tab is
 * looked up only inside that space. Entries that are not objects or have no string id are skipped;
 * a blank id never matches. Never throws.
 */
object HerdrAppLayoutEdit {
    sealed interface Result {
        /** The whole document with the one `name` replaced. */
        data class Edited(
            val value: JsonObject,
        ) : Result

        /** The space (or tab) id is not in the document. */
        data object Gone : Result

        /** Null value, not an object, or no `spaces` array. */
        data object NotALayout : Result
    }

    /**
     * Sets the trimmed [name] on space [spaceId], or on its tab [tabId] when given. An empty name
     * is written as `""`, which the desktop shows as the automatic name again.
     */
    fun rename(
        value: JsonElement?,
        spaceId: String,
        tabId: String?,
        name: String,
    ): Result {
        val doc = value as? JsonObject ?: return Result.NotALayout
        val spaces = doc[SPACES] as? JsonArray ?: return Result.NotALayout
        val newName = JsonPrimitive(name.trim())
        val si = spaces.indexOfId(spaceId) ?: return Result.Gone
        val space = spaces[si] as JsonObject
        val editedSpace =
            if (tabId == null) {
                space.with(NAME, newName)
            } else {
                val tabs = space[TABS] as? JsonArray ?: return Result.Gone
                val ti = tabs.indexOfId(tabId) ?: return Result.Gone
                space.with(TABS, tabs.replace(ti, (tabs[ti] as JsonObject).with(NAME, newName)))
            }
        return Result.Edited(doc.with(SPACES, spaces.replace(si, editedSpace)))
    }

    private const val SPACES = "spaces"
    private const val TABS = "tabs"
    private const val NAME = "name"
    private const val ID = "id"

    /** Index of the first object entry whose string `id` is [id]; null when none (or [id] is blank). */
    private fun JsonArray.indexOfId(id: String): Int? {
        if (id.isBlank()) return null
        return indexOfFirst { e ->
            val v = (e as? JsonObject)?.get(ID) as? JsonPrimitive
            v != null && v.isString && v.content == id
        }.takeIf { it >= 0 }
    }

    /** A copy with [key] set to [value]; the field keeps its place, a new field goes last. */
    private fun JsonObject.with(
        key: String,
        value: JsonElement,
    ) = JsonObject(LinkedHashMap(this).apply { put(key, value) })

    private fun JsonArray.replace(
        index: Int,
        value: JsonElement,
    ) = JsonArray(toMutableList().apply { set(index, value) })
}

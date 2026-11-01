package com.mtajchert.flocket.core.session

import com.mtajchert.flocket.core.model.DesktopLayout
import com.mtajchert.flocket.core.model.DesktopSpace
import com.mtajchert.flocket.core.model.DesktopTab
import com.mtajchert.flocket.core.model.TerminalAddress
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Reads the desktop app's `herdr-app` document (protocol/SPEC.md §9.1) into a [DesktopLayout].
 *
 * The document belongs to herdr-app and has no published schema, so this is deliberately tolerant:
 * unknown fields are ignored, missing ids/names become blank, non-object entries are skipped, and
 * pane leaves other than `{"terminal": "<address>"}` (mods, unknown kinds) are dropped. Pane trees
 * are walked depth-first in document order. The focused pane follows each split's `active` child
 * id from the tab's `root`; when the chain breaks or ends on a non-terminal, it is null.
 *
 * Never throws: a value that is not a document (null, deleted, wrong type) gives null.
 */
object HerdrAppLayoutParser {
    const val DOCUMENT = "herdr-app"

    /** Deeper trees are cut off here (a damaged document must not blow the stack). */
    private const val MAX_DEPTH = 64

    fun parse(value: JsonElement?): DesktopLayout? =
        try {
            parseOrNull(value)
        } catch (e: Exception) {
            // Defensive only: the walk below avoids every throwing accessor.
            null
        }

    private fun parseOrNull(value: JsonElement?): DesktopLayout? {
        val doc = value as? JsonObject ?: return null
        val spaces = doc["spaces"] as? JsonArray ?: return null
        return DesktopLayout(spaces.mapNotNull { (it as? JsonObject)?.let(::space) })
    }

    private fun space(o: JsonObject) =
        DesktopSpace(
            id = o.string("id").orEmpty(),
            name = o.string("name").orEmpty(),
            folder = o.string("folder")?.takeIf { it.isNotBlank() },
            tabs = (o["tabs"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::tab) },
        )

    private fun tab(o: JsonObject): DesktopTab {
        val root = o["root"] as? JsonObject
        val panes = mutableListOf<TerminalAddress>()
        if (root != null) collect(root, 0, panes)
        return DesktopTab(
            id = o.string("id").orEmpty(),
            name = o.string("name").orEmpty(),
            panes = panes,
            activePane = root?.let(::active),
        )
    }

    private fun collect(
        node: JsonObject,
        depth: Int,
        out: MutableList<TerminalAddress>,
    ) {
        if (depth > MAX_DEPTH) return
        terminalOf(node)?.let { out += it }
        for (child in (node["children"] as? JsonArray).orEmpty()) {
            (child as? JsonObject)?.let { collect(it, depth + 1, out) }
        }
    }

    private fun active(root: JsonObject): TerminalAddress? {
        var node = root
        repeat(MAX_DEPTH) {
            terminalOf(node)?.let { return it }
            val children = (node["children"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            if (children.isEmpty()) return null
            val id = node.string("active") ?: return null
            node = children.firstOrNull { it.string("id") == id } ?: return null
        }
        return null
    }

    private fun terminalOf(node: JsonObject): TerminalAddress? {
        val content = node["content"] as? JsonObject ?: return null
        return content.string("terminal")?.takeIf { it.isNotBlank() }?.let(::TerminalAddress)
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}

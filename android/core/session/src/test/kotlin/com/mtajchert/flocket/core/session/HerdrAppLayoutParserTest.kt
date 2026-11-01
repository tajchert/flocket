package com.mtajchert.flocket.core.session

import com.mtajchert.flocket.core.model.DesktopLayout
import com.mtajchert.flocket.core.model.TerminalAddress
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class HerdrAppLayoutParserTest {
    private fun json(s: String): JsonElement = Json.parseToJsonElement(s)

    private fun parse(s: String): DesktopLayout? = HerdrAppLayoutParser.parse(json(s))

    private fun a(vararg v: String) = v.map { TerminalAddress(it) }

    @Test
    fun `parses the synthetic fixture`() {
        val dir = File(System.getProperty("flocket.fixtures.dir") ?: error("flocket.fixtures.dir not set"))
        val reply = json(File(dir, "herdr-app-layout.json").readText()).jsonObject
        val layout = HerdrAppLayoutParser.parse(reply["value"])!!
        assertEquals(listOf("sp1a2b", "sp3c4d"), layout.spaces.map { it.id })
        val (first, second) = layout.spaces
        assertEquals("", first.name)
        assertEquals("/Users/me/code/flocket", first.folder)
        assertEquals(listOf("fix login", ""), first.tabs.map { it.name })
        // Depth-first, document order; the mod pane and the empty block are dropped.
        assertEquals(a("t7", "t9"), first.tabs[0].panes)
        assertEquals(TerminalAddress("t9"), first.tabs[0].activePane) // row n1 -> stack n3 -> n5
        assertEquals(a("t12", "t13"), first.tabs[1].panes)
        assertNull(first.tabs[1].activePane) // a column without `active`
        assertEquals("infra", second.name)
        assertEquals(a("t2"), second.tabs[0].panes)
        assertEquals(TerminalAddress("t2"), second.tabs[0].activePane) // root is the leaf
        assertEquals(emptyList<TerminalAddress>(), second.tabs[1].panes)
        assertNull(second.tabs[1].activePane)
    }

    @Test
    fun `absent, deleted or wrong-typed documents give null`() {
        assertNull(HerdrAppLayoutParser.parse(null))
        assertNull(HerdrAppLayoutParser.parse(JsonNull))
        assertNull(parse("[]"))
        assertNull(parse("\"spaces\""))
        assertNull(parse("""{"spaces": {"id": "x"}}"""))
        assertNull(parse("""{"other": 1}"""))
    }

    @Test
    fun `an empty spaces list is an empty layout`() {
        assertEquals(DesktopLayout(emptyList()), parse("""{"spaces": []}"""))
    }

    @Test
    fun `missing and wrong-typed fields fall back instead of failing`() {
        val l =
            parse(
                """
                {"spaces": [
                  1, null, "x",
                  {"tabs": [7, {"root": {"content": {"terminal": "t1"}}}]},
                  {"id": 5, "name": ["n"], "folder": "  ", "tabs": "nope"},
                  {"id": "s3", "tabs": [{"id": "t", "root": "leaf"}, {"id": "u", "root": {"children": [
                    {"content": {"terminal": 7}},
                    {"content": {"terminal": ""}},
                    {"content": "t9"},
                    {"content": {"agent": "t4"}},
                    {"content": {"terminal": "rohan/t2", "extra": true}}
                  ]}}]}
                ]}
                """,
            )!!
        assertEquals(3, l.spaces.size)
        assertEquals("", l.spaces[0].id)
        assertEquals(
            a("t1"),
            l.spaces[0]
                .tabs
                .single()
                .panes,
        )
        assertEquals(
            TerminalAddress("t1"),
            l.spaces[0]
                .tabs
                .single()
                .activePane,
        )
        assertEquals("", l.spaces[1].name)
        assertNull(l.spaces[1].folder)
        assertEquals(emptyList<Any>(), l.spaces[1].tabs)
        assertEquals(emptyList<TerminalAddress>(), l.spaces[2].tabs[0].panes)
        // The parser keeps addresses as written; buildWorkspace decides what a local layout may claim.
        assertEquals(a("rohan/t2"), l.spaces[2].tabs[1].panes)
    }

    @Test
    fun `a broken active chain gives no active pane`() {
        val l =
            parse(
                """
                {"spaces": [{"id": "s", "tabs": [
                  {"id": "a", "root": {"active": "missing", "children": [{"id": "x", "content": {"terminal": "t1"}}]}},
                  {"id": "b", "root": {"active": "m", "children": [{"id": "m", "content": {"mod": "files"}}]}},
                  {"id": "c", "root": {"active": 3, "children": [{"id": "3", "content": {"terminal": "t2"}}]}}
                ]}]}
                """,
            )!!
        assertEquals(listOf(null, null, null), l.spaces[0].tabs.map { it.activePane })
        assertEquals(a("t1"), l.spaces[0].tabs[0].panes)
    }

    @Test
    fun `nested children are walked depth first in document order`() {
        val l =
            parse(
                """
                {"spaces": [{"id": "s", "tabs": [{"id": "t", "root": {"active": "b", "children": [
                  {"id": "a", "children": [{"content": {"terminal": "t1"}}, {"children": [{"content": {"terminal": "t2"}}]}]},
                  {"id": "b", "active": "c", "children": [{"content": {"terminal": "t3"}}, {"id": "c", "content": {"terminal": "t4"}}]}
                ]}}]}]}
                """,
            )!!
        val tab = l.spaces[0].tabs[0]
        assertEquals(a("t1", "t2", "t3", "t4"), tab.panes)
        assertEquals(TerminalAddress("t4"), tab.activePane)
    }

    @Test
    fun `a pathologically deep tree is cut off without throwing`() {
        val depth = 150 // well past the cut-off, within the JSON reader's own nesting limit
        val sb = StringBuilder()
        repeat(depth) { sb.append("""{"id":"n$it","active":"n${it + 1}","children":[""") }
        sb.append("""{"id":"n$depth","content":{"terminal":"t1"}}""")
        repeat(depth) { sb.append("]}") }
        val doc = """{"spaces":[{"id":"s","tabs":[{"id":"t","root":$sb}]}]}"""
        val element = json(doc)
        val tab = HerdrAppLayoutParser.parse(element)!!.spaces[0].tabs[0]
        assertEquals(emptyList<TerminalAddress>(), tab.panes)
        assertNull(tab.activePane)
    }
}

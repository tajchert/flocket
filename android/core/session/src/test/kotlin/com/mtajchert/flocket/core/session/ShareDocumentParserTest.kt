package com.mtajchert.flocket.core.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareDocumentParserTest {
    private fun parse(
        json: String,
        machine: String? = null,
    ) = ShareDocumentParser.parse(Json.parseToJsonElement(json), machine)

    @Test
    fun `parses the helper's shape newest first`() {
        val shares =
            parse(
                """{"shares":[
                {"at":100,"id":"aa","note":"hello","paths":["/a.png","/b.txt"],"terminal":"t3"},
                {"at":300,"id":"cc","paths":["/c"],"terminal":"t1"},
                {"at":200,"id":"bb","paths":["/d"],"terminal":"t2"}],"v":1}""",
            )
        assertEquals(listOf("cc", "bb", "aa"), shares.map { it.id })
        val aa = shares.last()
        assertEquals("t3", aa.address.value)
        assertNull(aa.machine)
        assertEquals(listOf("/a.png", "/b.txt"), aa.paths)
        assertEquals("hello", aa.note)
        assertEquals(100L, aa.atEpochSeconds)
        assertNull(shares.first().note)
    }

    @Test
    fun `ignores unknown fields`() {
        val shares =
            parse("""{"v":2,"x":1,"shares":[{"id":"a","terminal":"t1","paths":["/p"],"at":1,"extra":{"k":1}}]}""")
        assertEquals(listOf("a"), shares.map { it.id })
    }

    @Test
    fun `skips an entry without id or paths but keeps the others`() {
        val shares =
            parse(
                """{"shares":[{"terminal":"t1","paths":["/p"],"at":1},{"id":"b","terminal":"t1","at":1},
                {"id":"c","terminal":"t1","paths":[],"at":1},7,{"id":"d","terminal":"t1","paths":["/p"],"at":2}]}""",
            )
        assertEquals(listOf("d"), shares.map { it.id })
    }

    @Test
    fun `null or foreign value is empty`() {
        assertTrue(ShareDocumentParser.parse(null, null).isEmpty())
        assertTrue(ShareDocumentParser.parse(JsonNull, null).isEmpty())
        assertTrue(ShareDocumentParser.parse(JsonPrimitive("x"), null).isEmpty())
        assertTrue(parse("""{"v":1}""").isEmpty())
        assertTrue(parse("""{"shares":"nope"}""").isEmpty())
        assertTrue(parse("[1,2]").isEmpty())
    }

    @Test
    fun `remote machine prefixes the address`() {
        val s = parse("""{"shares":[{"id":"a","terminal":"t3","paths":["/p"],"at":1}]}""", machine = "rohan").single()
        assertEquals("rohan/t3", s.address.value)
        assertEquals("rohan", s.machine)
    }
}

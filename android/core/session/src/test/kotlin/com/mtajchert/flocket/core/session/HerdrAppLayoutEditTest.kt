package com.mtajchert.flocket.core.session

import com.mtajchert.flocket.core.session.HerdrAppLayoutEdit.Result
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class HerdrAppLayoutEditTest {
    private fun json(s: String): JsonElement = Json.parseToJsonElement(s)

    private val fixture: JsonObject by lazy {
        val dir = File(System.getProperty("flocket.fixtures.dir") ?: error("flocket.fixtures.dir not set"))
        json(File(dir, "herdr-app-layout.json").readText()).jsonObject["value"]!!.jsonObject
    }

    /** This object with [key] set to [value], every other field kept. */
    private fun JsonObject.with(
        key: String,
        value: JsonElement,
    ) = JsonObject(this + (key to value))

    /** This array with element [i] replaced by [transform] of it. */
    private fun JsonArray.edit(
        i: Int,
        transform: (JsonObject) -> JsonObject,
    ) = JsonArray(mapIndexed { n, e -> if (n == i) transform(e.jsonObject) else e })

    private fun edited(r: Result): JsonObject = (r as Result.Edited).value

    @Test
    fun `renames one tab and keeps everything else`() {
        val result = edited(HerdrAppLayoutEdit.rename(fixture, "sp1a2b", "tb02", "tests"))
        val expected =
            fixture.with(
                "spaces",
                fixture["spaces"]!!.jsonArray.edit(0) { space ->
                    space.with("tabs", space["tabs"]!!.jsonArray.edit(1) { it.with("name", JsonPrimitive("tests")) })
                },
            )
        assertEquals(expected, result)
        // The unknown top-level field, `look`, `size` and the `mod` pane all survive.
        assertEquals(fixture["future_top_level"], result["future_top_level"])
        assertEquals(fixture["spaces"]!!.jsonArray[1], result["spaces"]!!.jsonArray[1])
    }

    @Test
    fun `renames a space`() {
        val result = edited(HerdrAppLayoutEdit.rename(fixture, "sp3c4d", null, "ops"))
        val expected =
            fixture.with(
                "spaces",
                fixture["spaces"]!!.jsonArray.edit(1) { it.with("name", JsonPrimitive("ops")) },
            )
        assertEquals(expected, result)
    }

    @Test
    fun `empty name writes empty string`() {
        val result = edited(HerdrAppLayoutEdit.rename(fixture, "sp1a2b", "tb01", "   "))
        val tab =
            result["spaces"]!!
                .jsonArray[0]
                .jsonObject["tabs"]!!
                .jsonArray[0]
                .jsonObject
        assertEquals(JsonPrimitive(""), tab["name"])
    }

    @Test
    fun `name is trimmed`() {
        val result = edited(HerdrAppLayoutEdit.rename(fixture, "sp3c4d", null, "  ops \n"))
        assertEquals(JsonPrimitive("ops"), result["spaces"]!!.jsonArray[1].jsonObject["name"])
    }

    @Test
    fun `a space or tab without a name field gets one`() {
        val doc = json("""{"spaces":[{"id":"sp","tabs":[{"id":"tb","root":{}}]}]}""")
        val result = edited(HerdrAppLayoutEdit.rename(doc, "sp", "tb", "x"))
        assertEquals(
            json("""{"spaces":[{"id":"sp","tabs":[{"id":"tb","root":{},"name":"x"}]}]}"""),
            result,
        )
    }

    @Test
    fun `unknown space or tab id is Gone`() {
        assertEquals(Result.Gone, HerdrAppLayoutEdit.rename(fixture, "nope", null, "x"))
        assertEquals(Result.Gone, HerdrAppLayoutEdit.rename(fixture, "nope", "tb01", "x"))
        // A tab is looked up only inside its own space.
        assertEquals(Result.Gone, HerdrAppLayoutEdit.rename(fixture, "sp3c4d", "tb01", "x"))
        assertEquals(Result.Gone, HerdrAppLayoutEdit.rename(fixture, "sp1a2b", "nope", "x"))
        // Damaged entries are skipped, never matched or thrown on.
        val damaged = json("""{"spaces":[1,"sp",{"name":"no id"},{"id":7},{"id":"sp","tabs":"not a list"}]}""")
        assertEquals(Result.Gone, HerdrAppLayoutEdit.rename(damaged, "sp", "tb", "x"))
        assertEquals(Result.Gone, HerdrAppLayoutEdit.rename(damaged, "7", null, "x"))
        assertEquals(Result.Gone, HerdrAppLayoutEdit.rename(json("""{"spaces":[]}"""), "sp", null, "x"))
        // A blank id never addresses anything (Task 1 gives id-less entries no LayoutRef).
        val blank = json("""{"spaces":[{"id":"","name":"","tabs":[{"id":"","name":""}]}]}""")
        assertEquals(Result.Gone, HerdrAppLayoutEdit.rename(blank, "", null, "x"))
        assertEquals(Result.Gone, HerdrAppLayoutEdit.rename(blank, "", "", "x"))
    }

    @Test
    fun `null, non-object or spaces-less value is NotALayout`() {
        assertEquals(Result.NotALayout, HerdrAppLayoutEdit.rename(null, "sp", null, "x"))
        assertEquals(Result.NotALayout, HerdrAppLayoutEdit.rename(JsonNull, "sp", null, "x"))
        assertEquals(Result.NotALayout, HerdrAppLayoutEdit.rename(json("[]"), "sp", null, "x"))
        assertEquals(Result.NotALayout, HerdrAppLayoutEdit.rename(json("\"spaces\""), "sp", null, "x"))
        assertEquals(Result.NotALayout, HerdrAppLayoutEdit.rename(json("""{"other":1}"""), "sp", null, "x"))
        assertEquals(Result.NotALayout, HerdrAppLayoutEdit.rename(json("""{"spaces":{"id":"sp"}}"""), "sp", null, "x"))
    }

    @Test
    fun `repeated space id edits the first only`() {
        val doc =
            json(
                """{"spaces":[{"id":"sp","name":"a","tabs":[{"id":"tb","name":"1"}]},""" +
                    """{"id":"sp","name":"b","tabs":[{"id":"tb","name":"2"}]}]}""",
            )
        assertEquals(
            json(
                """{"spaces":[{"id":"sp","name":"x","tabs":[{"id":"tb","name":"1"}]},""" +
                    """{"id":"sp","name":"b","tabs":[{"id":"tb","name":"2"}]}]}""",
            ),
            edited(HerdrAppLayoutEdit.rename(doc, "sp", null, "x")),
        )
        assertEquals(
            json(
                """{"spaces":[{"id":"sp","name":"a","tabs":[{"id":"tb","name":"y"}]},""" +
                    """{"id":"sp","name":"b","tabs":[{"id":"tb","name":"2"}]}]}""",
            ),
            edited(HerdrAppLayoutEdit.rename(doc, "sp", "tb", "y")),
        )
        // The first space wins even when only a later one has the tab: that tab is not addressable.
        val second = json("""{"spaces":[{"id":"sp","tabs":[]},{"id":"sp","tabs":[{"id":"tb2"}]}]}""")
        assertEquals(Result.Gone, HerdrAppLayoutEdit.rename(second, "sp", "tb2", "z"))
    }
}

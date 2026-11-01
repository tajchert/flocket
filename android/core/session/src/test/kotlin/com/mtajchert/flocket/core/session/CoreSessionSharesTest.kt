package com.mtajchert.flocket.core.session

import com.mtajchert.flocket.core.protocol.testing.FakeCore
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/** `flocket-share` document tracking in [CoreSessionImpl]. */
class CoreSessionSharesTest {
    private suspend fun TestScope.eventually(
        what: String = "condition",
        cond: () -> Boolean,
    ) {
        repeat(2_000) {
            if (cond()) return
            delay(1)
        }
        fail("timed out waiting for $what")
    }

    private fun shareDoc(vararg entries: Triple<String, String, Long>): JsonElement =
        buildJsonObject {
            put("v", 1)
            put(
                "shares",
                buildJsonArray {
                    entries.forEach { (id, terminal, at) ->
                        add(
                            buildJsonObject {
                                put("at", at)
                                put("id", id)
                                put("paths", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("/f/$id")) })
                                put("terminal", terminal)
                            },
                        )
                    }
                },
            )
        }

    /** Documents served per machine (key "" = local) for `flocket-share`; machines by name (all connected). */
    private class Remote(
        val machines: MutableSet<String> = ConcurrentHashMap.newKeySet(),
        val docs: MutableMap<String, JsonElement> = ConcurrentHashMap(),
        val version: MutableMap<String, Long> = ConcurrentHashMap(),
    )

    private fun TestScope.host(r: Remote) =
        FakeHost(backgroundScope).apply {
            terminals += FakeCore.terminal("t1")
            configure = { c ->
                c.handlers["machine.list"] = {
                    buildJsonObject {
                        put(
                            "machines",
                            buildJsonArray {
                                r.machines.sorted().forEach { name ->
                                    add(
                                        buildJsonObject {
                                            put("name", name)
                                            put("state", "connected")
                                        },
                                    )
                                }
                            },
                        )
                    }
                }
                c.handlers["doc.get"] = { p ->
                    val on = p["on"]?.jsonPrimitive?.contentOrNull ?: ""
                    val isShare = p["name"]?.jsonPrimitive?.contentOrNull == "flocket-share"
                    buildJsonObject {
                        put("value", if (isShare) r.docs[on] ?: JsonNull else JsonNull)
                        put("version", r.version[on] ?: 1L)
                    }
                }
            }
        }

    private fun FakeCore.shareGets(machine: String?) =
        callsOf("doc.get").filter {
            it.params["name"]?.jsonPrimitive?.contentOrNull == "flocket-share" &&
                it.params["on"]?.jsonPrimitive?.contentOrNull == machine
        }

    private fun CoreSession.shareIds() = state.value.shares.map { it.id }

    private suspend fun FakeCore.shareEvent(
        machine: String?,
        version: Long = 2,
    ) = pushEvent(
        "doc",
        data =
            buildJsonObject {
                put("name", "flocket-share")
                put("version", version)
            },
        machine = machine,
    )

    @Test
    fun `shares load after subscribe`() =
        runTest {
            val r = Remote()
            r.docs[""] = shareDoc(Triple("a", "t1", 10), Triple("b", "t1", 20))
            val host = host(r)
            val s = host.session(this)
            s.start()
            s.awaitOnline()
            eventually { s.shareIds() == listOf("b", "a") }
            val methods = host.core.calls.map { it.method }
            assertEquals(true, methods.indexOf("events.subscribe") < methods.indexOf("doc.get"))
            assertEquals(
                "t1",
                s.state.value.shares
                    .first()
                    .address.value,
            )
            s.stop()
        }

    @Test
    fun `doc event for flocket-share refetches`() =
        runTest {
            val r = Remote()
            r.docs[""] = shareDoc(Triple("a", "t1", 10))
            val host = host(r)
            val s = host.session(this)
            s.start()
            s.awaitOnline()
            eventually { s.shareIds() == listOf("a") }
            r.docs[""] = shareDoc(Triple("c", "t1", 30), Triple("a", "t1", 10))
            host.core.shareEvent(null)
            eventually { s.shareIds() == listOf("c", "a") }
            assertEquals(2, host.core.shareGets(null).size)
            s.stop()
        }

    @Test
    fun `doc event from rohan refetches with on=rohan`() =
        runTest {
            val r = Remote()
            r.machines += "rohan"
            r.docs["rohan"] = shareDoc(Triple("a", "t7", 10))
            val host = host(r)
            val s = host.session(this)
            s.start()
            s.awaitOnline()
            eventually { s.shareIds() == listOf("a") }
            assertEquals(
                "rohan/t7",
                s.state.value.shares
                    .single()
                    .address.value,
            )
            r.docs["rohan"] = shareDoc(Triple("b", "t8", 20), Triple("a", "t7", 10))
            host.core.shareEvent("rohan")
            eventually { s.shareIds() == listOf("b", "a") }
            runCurrent()
            assertEquals(2, host.core.shareGets("rohan").size)
            assertEquals(1, host.core.shareGets(null).size)
            s.stop()
        }

    @Test
    fun `missed refetches`() =
        runTest {
            val r = Remote()
            r.docs[""] = shareDoc(Triple("a", "t1", 10))
            val host = host(r)
            val s = host.session(this)
            s.start()
            s.awaitOnline()
            eventually { s.shareIds() == listOf("a") }
            r.docs[""] = shareDoc(Triple("z", "t1", 99))
            host.core.pushMissed()
            eventually { s.shareIds() == listOf("z") }
            s.stop()
        }

    @Test
    fun `version going backwards is trusted`() =
        runTest {
            val r = Remote()
            r.version[""] = 9
            r.docs[""] = shareDoc(Triple("a", "t1", 10))
            val host = host(r)
            val s = host.session(this)
            s.start()
            s.awaitOnline()
            eventually { s.shareIds() == listOf("a") }
            r.version[""] = 2
            r.docs[""] = shareDoc(Triple("b", "t1", 5))
            host.core.shareEvent(null, version = 2)
            eventually { s.shareIds() == listOf("b") }
            s.stop()
        }

    @Test
    fun `same id from two machines appears once`() =
        runTest {
            val r = Remote()
            r.machines += listOf("rohan", "gondor")
            r.docs[""] = shareDoc(Triple("dup", "t1", 10))
            r.docs["rohan"] = shareDoc(Triple("dup", "t7", 10), Triple("r", "t7", 50))
            r.docs["gondor"] = shareDoc(Triple("dup", "t2", 10), Triple("g", "t2", 40))
            val host = host(r)
            val s = host.session(this)
            s.start()
            s.awaitOnline()
            eventually { s.shareIds() == listOf("r", "g", "dup") }
            assertEquals(
                "t1",
                s.state.value.shares
                    .last()
                    .address.value,
            ) // local wins
            s.stop()
        }

    @Test
    fun `a machine that leaves the list loses its shares`() =
        runTest {
            val r = Remote()
            r.machines += "rohan"
            r.docs[""] = shareDoc(Triple("a", "t1", 10))
            r.docs["rohan"] = shareDoc(Triple("r", "t7", 50))
            val host = host(r)
            val s = host.session(this)
            s.start()
            s.awaitOnline()
            eventually { s.shareIds() == listOf("r", "a") }
            r.machines.remove("rohan")
            host.core.pushMissed()
            eventually { s.shareIds() == listOf("a") }
            s.stop()
        }
}

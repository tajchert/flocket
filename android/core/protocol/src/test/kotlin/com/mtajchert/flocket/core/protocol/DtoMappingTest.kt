package com.mtajchert.flocket.core.protocol

import com.mtajchert.flocket.core.model.AgentState
import com.mtajchert.flocket.core.model.AgentStatus
import com.mtajchert.flocket.core.model.CoreEndpoint
import com.mtajchert.flocket.core.model.HostId
import com.mtajchert.flocket.core.model.Liveness
import com.mtajchert.flocket.core.protocol.dto.TerminalDto
import com.mtajchert.flocket.core.protocol.dto.toRecord
import com.mtajchert.flocket.core.protocol.rpc.ProtocolJson
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DtoMappingTest {
    private val endpoint = CoreEndpoint(HostId("h1"), "s")

    private fun dto(json: String) = TerminalDto.parse(ProtocolJson.parseToJsonElement(json).jsonObject)

    private val base =
        """"address":"t1b","argv":["claude"],"cwd":"/x","created_ms":5,""" +
            """"cols":80,"rows":24,"labels":{"where":"a / b"}"""

    @Test
    fun `live terminal with pid maps to Running and no status is Unknown not idle`() {
        val r = dto("""{$base,"state":"live","pid":12,"status":null,"future_field":{"a":1}}""").toRecord(endpoint)
        assertEquals(Liveness.Running, r.liveness)
        assertEquals(AgentStatus.Unknown, r.status)
        assertEquals("t1b", r.address.value)
        assertEquals("a / b", r.where)
    }

    @Test
    fun `absent status field is Unknown as well`() {
        assertEquals(AgentStatus.Unknown, dto("""{$base}""").toRecord(endpoint).status)
    }

    @Test
    fun `live without pid maps to Waiting with the reason`() {
        val r = dto("""{$base,"state":"live","pid":null,"reason":"holder_lost"}""").toRecord(endpoint)
        assertEquals(Liveness.Waiting("holder_lost"), r.liveness)
    }

    @Test
    fun `finished keeps exit data even though pid is still reported`() {
        val r =
            dto(
                """{$base,"state":"finished","pid":9,"exit_code":3,"signal":null,"reason":"exited","finished_ms":99}""",
            ).toRecord(endpoint)
        assertEquals(Liveness.Finished("exited", 3, null, 99), r.liveness)
    }

    @Test
    fun `unknown status words map to Other with the raw word preserved`() {
        val r =
            dto(
                """{$base,"state":"live","pid":1,"status":{"state":"pondering","app":"claude","msg":"hm","kind":"newkind"}}""",
            ).toRecord(endpoint)
        val s = r.status as AgentStatus.Reported
        assertEquals(AgentState.Other, s.state)
        assertEquals("pondering", s.rawState)
        assertEquals("claude", s.app)
        assertEquals("hm", s.message)
        assertEquals("claude", r.agentLabel)
    }

    @Test
    fun `known status with block kind and progress`() {
        val s =
            dto(
                """{$base,"pid":1,"status":{"state":"blocked","kind":"permission","progress":40}}""",
            ).toRecord(endpoint).status as AgentStatus.Reported
        assertEquals(AgentState.Blocked, s.state)
        assertEquals(40, s.progress)
    }

    @Test
    fun `raw keeps unknown fields and foreground maps`() {
        val d = dto("""{$base,"pid":1,"mystery":[1,2],"foreground":{"pid":5,"name":"vim","argv":["vim"],"cwd":"/y"}}""")
        assertTrue(d.raw.containsKey("mystery"))
        val r = d.toRecord(endpoint)
        assertEquals("/y", r.displayCwd)
        assertNull(dto("""{$base}""").foreground)
    }
}

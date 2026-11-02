package io.github.magnusbvillumsen.herdrpocket

import io.github.magnusbvillumsen.herdrpocket.api.Agent
import io.github.magnusbvillumsen.herdrpocket.api.AgentStatus
import io.github.magnusbvillumsen.herdrpocket.api.AgentsResponse
import io.github.magnusbvillumsen.herdrpocket.api.ErrorResponse
import io.github.magnusbvillumsen.herdrpocket.api.HubJson
import io.github.magnusbvillumsen.herdrpocket.api.ImportantRequest
import io.github.magnusbvillumsen.herdrpocket.api.KeysRequest
import io.github.magnusbvillumsen.herdrpocket.api.Machine
import io.github.magnusbvillumsen.herdrpocket.api.PromptRequest
import io.github.magnusbvillumsen.herdrpocket.api.PushRequest
import io.github.magnusbvillumsen.herdrpocket.api.ScreenResponse
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelsTest {
    @Test
    fun agentFromProtocolDoc() {
        val json = """
            {
              "machine": "thor",
              "pane": "w1:p2",
              "agent": "claude",
              "status": "working",
              "since": 1793600000,
              "title": "Fix the build",
              "cwd": "/home/magnus/dev/game",
              "workspace": "game",
              "important": false
            }
        """
        assertEquals(
            Agent("thor", "w1:p2", "claude", AgentStatus.Working, 1793600000, "Fix the build", "/home/magnus/dev/game", "game", false),
            HubJson.decodeFromString(Agent.serializer(), json),
        )
    }

    @Test
    fun everyStatus() {
        val all = mapOf(
            "idle" to AgentStatus.Idle,
            "working" to AgentStatus.Working,
            "blocked" to AgentStatus.Blocked,
            "done" to AgentStatus.Done,
            "unknown" to AgentStatus.Unknown,
            "sleeping" to AgentStatus.Unknown,
        )
        for ((wire, status) in all) {
            val a = HubJson.decodeFromString(Agent.serializer(), """{"machine":"m","pane":"p","status":"$wire"}""")
            assertEquals(status, a.status, wire)
        }
    }

    @Test
    fun agentWithOnlyIdentityAndExtraFields() {
        val a = HubJson.decodeFromString(Agent.serializer(), """{"machine":"m","pane":"p","title":null,"future":[1,2]}""")
        assertEquals(Agent("m", "p"), a)
    }

    @Test
    fun agentsResponse() {
        val json = """
            {"agents": [
               {"machine":"thor","pane":"w1:p2","agent":"claude","status":"blocked","since":5,"title":"","cwd":"/x","workspace":"","important":true}
             ],
             "machines": [{"name":"thor","connected":true},{"name":"loki","connected":false}]}
        """
        val r = HubJson.decodeFromString(AgentsResponse.serializer(), json)
        assertEquals(1, r.agents.size)
        assertEquals(AgentStatus.Blocked, r.agents[0].status)
        assertTrue(r.agents[0].important)
        assertEquals(listOf(Machine("thor", true), Machine("loki", false)), r.machines)
    }

    @Test
    fun emptyAgentsResponse() {
        assertEquals(AgentsResponse(), HubJson.decodeFromString(AgentsResponse.serializer(), """{"agents":[],"machines":[]}"""))
    }

    @Test
    fun screenAndEmptyAndError() {
        assertEquals("a\nb", HubJson.decodeFromString(ScreenResponse.serializer(), """{"text":"a\nb"}""").text)
        assertEquals(JsonObject(emptyMap()), HubJson.decodeFromString(JsonObject.serializer(), "{}"))
        assertEquals("machine thor is not connected", HubJson.decodeFromString(ErrorResponse.serializer(), """{"error":"machine thor is not connected"}""").error)
    }

    @Test
    fun requestBodies() {
        assertEquals("""{"text":"run the tests"}""", HubJson.encodeToString(PromptRequest.serializer(), PromptRequest("run the tests")))
        assertEquals("""{"keys":["Enter"]}""", HubJson.encodeToString(KeysRequest.serializer(), KeysRequest(listOf("Enter"))))
        assertEquals("""{"important":true}""", HubJson.encodeToString(ImportantRequest.serializer(), ImportantRequest(true)))
        assertEquals("""{"endpoint":"https://ntfy.sh/upX?up=1"}""", HubJson.encodeToString(PushRequest.serializer(), PushRequest("https://ntfy.sh/upX?up=1")))
    }
}

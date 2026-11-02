package io.github.magnusbvillumsen.herdrpocket

import io.github.magnusbvillumsen.herdrpocket.api.AgentStatus
import io.github.magnusbvillumsen.herdrpocket.api.HubClient
import io.github.magnusbvillumsen.herdrpocket.api.HubConfig
import io.github.magnusbvillumsen.herdrpocket.api.HubException
import io.github.magnusbvillumsen.herdrpocket.platform.ConfigStore
import io.github.magnusbvillumsen.herdrpocket.state.HubStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.github.magnusbvillumsen.herdrpocket.state.Connection
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HubClientTest {
    private val cfg = HubConfig("http://hub.test:8790", "tok")
    private val seen = mutableListOf<HttpRequestData>()

    private fun client(reply: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): HubClient =
        HubClient(cfg, MockEngine { req -> seen += req; reply(req) })

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun HttpRequestData.bodyText(): String = when (val b = body) {
        is OutgoingContent.ByteArrayContent -> b.bytes().decodeToString()
        is OutgoingContent.NoContent -> ""
        else -> error("unexpected body $b")
    }

    @Test
    fun agentsSendsBearerToken() = runTest {
        val c = client { json("""{"agents":[{"machine":"thor","pane":"w1:p2","status":"working"}],"machines":[{"name":"thor","connected":true}]}""") }
        val r = c.agents()
        assertEquals(AgentStatus.Working, r.agents.single().status)
        val req = seen.single()
        assertEquals(HttpMethod.Get, req.method)
        assertEquals("http://hub.test:8790/api/agents", req.url.toString())
        assertEquals("Bearer tok", req.headers[HttpHeaders.Authorization])
    }

    @Test
    fun screenEncodesPathSegments() = runTest {
        val c = client { json("""{"text":"hello"}""") }
        assertEquals("hello", c.screen("thor", "w1/p2 x", 300))
        val u = seen.single().url
        assertEquals("/api/agents/thor/w1%2Fp2%20x/screen", u.encodedPath)
        assertEquals("300", u.parameters["lines"])
    }

    @Test
    fun writesUseTheDocumentedMethodsAndBodies() = runTest {
        val c = client { json("{}") }
        c.prompt("thor", "w1:p2", "go on")
        c.keys("thor", "w1:p2", listOf("Enter"))
        c.setImportant("thor", "w1:p2", true)
        c.registerPush("https://ntfy.test/up1?up=1")
        c.unregisterPush("https://ntfy.test/up1?up=1")
        assertEquals(
            listOf(
                Triple(HttpMethod.Post, "/api/agents/thor/w1:p2/prompt", """{"text":"go on"}"""),
                Triple(HttpMethod.Post, "/api/agents/thor/w1:p2/keys", """{"keys":["Enter"]}"""),
                Triple(HttpMethod.Put, "/api/agents/thor/w1:p2/important", """{"important":true}"""),
                Triple(HttpMethod.Post, "/api/push", """{"endpoint":"https://ntfy.test/up1?up=1"}"""),
                Triple(HttpMethod.Delete, "/api/push", """{"endpoint":"https://ntfy.test/up1?up=1"}"""),
            ),
            seen.map { Triple(it.method, it.url.encodedPath.replace("%3A", ":"), it.bodyText()) },
        )
        assertTrue(seen.all { it.body.contentType.toString().startsWith("application/json") })
    }

    @Test
    fun emptyBodyCountsAsOk() = runTest {
        client { respond("", HttpStatusCode.OK) }.keys("m", "p", listOf("esc"))
    }

    @Test
    fun errorsCarryStatusAndHubMessage() = runTest {
        val c = client { json("""{"error":"thor is not connected"}""", HttpStatusCode.ServiceUnavailable) }
        val e = assertFailsWith<HubException> { c.screen("thor", "p") }
        assertEquals(503, e.status)
        assertEquals("machine is disconnected: thor is not connected", e.message)

        val t = client { respond("nope", HttpStatusCode.Unauthorized) }
        assertEquals(401, assertFailsWith<HubException> { t.agents() }.status)

        val g = client { json("""{"error":"timeout"}""", HttpStatusCode.GatewayTimeout) }
        assertEquals("machine did not answer in time: timeout", assertFailsWith<HubException> { g.prompt("m", "p", "x") }.message)
    }

    @Test
    fun badJsonIsAHubException() = runTest {
        val c = client { json("""{"agents": 3}""") }
        assertFailsWith<HubException> { c.agents() }
    }

    // SSE and the store's live loop need a real engine (MockEngine lacks SSE): see FakeHubTest.

    @Test
    fun storeWithoutConfigIsNotPaired() = runTest {
        val configs = object : ConfigStore {
            override val config: StateFlow<HubConfig?> = MutableStateFlow(null)
            override fun save(config: HubConfig) = Unit
        }
        val store = HubStore(configs)
        val job = launch { store.run() }
        val s = store.state.first { it.connection == Connection.NotPaired }
        job.cancelAndJoin()
        assertFalse(s.loaded)
        assertEquals(null, store.client())
    }
}

package io.github.magnusbvillumsen.herdrpocket

import io.github.magnusbvillumsen.herdrpocket.api.AgentKey
import io.github.magnusbvillumsen.herdrpocket.api.AgentStatus
import io.github.magnusbvillumsen.herdrpocket.api.HubClient
import io.github.magnusbvillumsen.herdrpocket.api.HubConfig
import io.github.magnusbvillumsen.herdrpocket.api.HubException
import io.github.magnusbvillumsen.herdrpocket.platform.ConfigStore
import io.github.magnusbvillumsen.herdrpocket.state.Connection
import io.github.magnusbvillumsen.herdrpocket.state.HubStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.URI
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Runs the real client (OkHttp engine) against tools/fakehub.py. Needs python3 on PATH. */
class FakeHubTest {
    private lateinit var hub: Process
    private lateinit var base: String

    @BeforeTest
    fun start() {
        val script = File("../tools/fakehub.py").takeIf { it.exists() } ?: File("tools/fakehub.py")
        hub = ProcessBuilder("python3", script.path, "0", "tok").redirectErrorStream(true).start()
        val port = hub.inputStream.bufferedReader().readLine() ?: error("fake hub did not start")
        base = "http://127.0.0.1:${port.trim()}"
    }

    @AfterTest
    fun stop() {
        hub.destroy()
    }

    private fun client(token: String = "tok") = HubClient(HubConfig(base, token))

    private fun hubLog(): JsonObject = Json.parseToJsonElement(URI("$base/_log").toURL().readText()).jsonObject

    private fun pushes(log: JsonObject): List<String> = log["pushes"]!!.jsonArray.map { it.jsonPrimitive.content }

    @Test
    fun readsAndWrites() = runBlocking {
        client().use { c ->
            val r = c.agents()
            assertEquals(3, r.agents.size)
            assertEquals(listOf(true, false), r.machines.map { it.connected })

            val screen = c.screen("thor", "w1:p2", 300)
            assertTrue("building w1:p2" in screen, screen)
            assertTrue("lines=300" in screen, screen)

            c.prompt("thor", "w1:p2", "carry on, æøå")
            c.keys("thor", "w1:p2", listOf("Enter"))
            c.setImportant("thor", "w1:p2", true)
            c.registerPush("https://ntfy.example/upABC?up=1")
            assertTrue(c.agents().agents.first { it.pane == "w1:p2" }.important)

            val log = hubLog()
            val bodies = log["requests"]!!.jsonArray.map { it.jsonObject["body"]!!.jsonPrimitive.content }
            assertTrue("""{"text":"carry on, æøå"}""" in bodies, bodies.toString())
            assertTrue("""{"keys":["Enter"]}""" in bodies, bodies.toString())
            assertTrue("""{"important":true}""" in bodies, bodies.toString())
            assertEquals(listOf("https://ntfy.example/upABC?up=1"), pushes(log))
            c.unregisterPush("https://ntfy.example/upABC?up=1")
            assertEquals(emptyList(), pushes(hubLog()))
        }
    }

    @Test
    fun errors() = runBlocking {
        client().use { c ->
            val e = assertFailsWith<HubException> { c.screen("loki", "w2:p1") }
            assertEquals(503, e.status)
            assertEquals("machine is disconnected: loki is not connected", e.message)
        }
        client("wrong").use { c ->
            assertEquals(401, assertFailsWith<HubException> { c.agents() }.status)
            assertEquals(401, assertFailsWith<HubException> { c.events().toList() }.status)
        }
        HubClient(HubConfig("http://127.0.0.1:1", "tok")).use { c ->
            assertEquals(null, assertFailsWith<HubException> { c.agents() }.status)
        }
    }

    @Test
    fun eventStream() = runBlocking {
        client().use { c ->
            val first = withTimeout(5_000) { c.events().take(1).toList() }.single()
            assertEquals(AgentStatus.Blocked, first.agents.first { it.pane == "w1:p3" }.status)
        }
    }

    @Test
    fun storeStaysLiveAcrossReconnects() = runBlocking {
        val configs = object : ConfigStore {
            override val config: StateFlow<HubConfig?> = MutableStateFlow(HubConfig(base, "tok"))
            override fun save(config: HubConfig) = Unit
        }
        val store = HubStore(configs)
        val job = CoroutineScope(Dispatchers.Default).launch { store.run() }
        try {
            val live = withTimeout(5_000) { store.state.first { it.connection == Connection.Live } }
            assertEquals(listOf("thor", "loki"), live.groups.map { it.machine })
            assertEquals(listOf("w1:p3", "w1:p2"), live.groups[0].agents.map { it.pane })

            val key = AgentKey("thor", "w1:p2")
            store.setImportant(key, true)
            // The fake hub closes each stream within a second; the change must survive the reconnect.
            Thread.sleep(2_500)
            val after = withTimeout(5_000) { store.state.first { it.connection == Connection.Live } }
            assertTrue(after.agent(key)!!.important)
            val reconnects = hubLog()["requests"]!!.jsonArray.count { it.jsonObject["path"].toString() == """["api","events"]""" }
            assertTrue(reconnects >= 2, "expected the store to reconnect, saw $reconnects connections")
        } finally {
            job.cancelAndJoin()
            store.client()?.close()
        }
    }
}

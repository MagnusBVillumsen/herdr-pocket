package io.github.magnusbvillumsen.herdrpocket

import io.github.magnusbvillumsen.herdrpocket.api.AgentKey
import io.github.magnusbvillumsen.herdrpocket.api.AgentStatus
import io.github.magnusbvillumsen.herdrpocket.push.NotificationText
import io.github.magnusbvillumsen.herdrpocket.push.PushKind
import io.github.magnusbvillumsen.herdrpocket.push.notificationText
import io.github.magnusbvillumsen.herdrpocket.push.parsePushPayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PushPayloadTest {
    @Test
    fun finishedFromProtocolDoc() {
        val p = assertNotNull(
            parsePushPayload(
                """{"machine": "thor", "pane": "w1:p2", "agent": "claude",
                    "status": "done", "title": "Fix the build", "event": "finished"}""",
            ),
        )
        assertEquals(AgentKey("thor", "w1:p2"), p.key)
        assertEquals(AgentStatus.Done, p.status)
        assertEquals(NotificationText(PushKind.Finished, "thor · claude finished: Fix the build", "w1:p2"), notificationText(p))
    }

    @Test
    fun finishedToIdle() {
        val p = assertNotNull(parsePushPayload("""{"machine":"thor","pane":"p","agent":"codex","status":"idle","title":"","event":"finished"}"""))
        assertEquals("thor · codex finished", notificationText(p).title)
    }

    @Test
    fun needsYou() {
        for (event in listOf("needs you", "blocked", "needs_you")) {
            val p = assertNotNull(parsePushPayload("""{"machine":"thor","pane":"w2:p1","agent":"claude","status":"blocked","title":"Deploy","event":"$event"}"""))
            val t = notificationText(p)
            assertEquals(PushKind.NeedsYou, t.kind)
            assertEquals("thor · claude needs you: Deploy", t.title)
        }
    }

    @Test
    fun missingAgentFallsBackToPane() {
        val p = assertNotNull(parsePushPayload("""{"machine":"thor","pane":"w1:p3","status":"blocked"}"""))
        assertEquals("thor · w1:p3 needs you", notificationText(p).title)
    }

    @Test
    fun garbage() {
        assertNull(parsePushPayload("not json"))
        assertNull(parsePushPayload("""{"pane":"x"}"""))
        assertNull(parsePushPayload(""))
    }
}

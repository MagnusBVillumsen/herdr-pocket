package io.github.magnusbvillumsen.herdrpocket

import io.github.magnusbvillumsen.herdrpocket.api.HubConfig
import io.github.magnusbvillumsen.herdrpocket.api.hubConfigOf
import io.github.magnusbvillumsen.herdrpocket.api.normalizeHubUrl
import io.github.magnusbvillumsen.herdrpocket.api.parsePairLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class PairLinkTest {
    @Test
    fun percentEncoded() {
        assertEquals(
            HubConfig("http://heimdall:8790", "s3cr3t"),
            parsePairLink("herdr-pocket://pair?url=http%3A%2F%2Fheimdall%3A8790&token=s3cr3t"),
        )
    }

    @Test
    fun rawUrlAndReversedOrder() {
        assertEquals(
            HubConfig("https://hub.tail1234.ts.net", "abc"),
            parsePairLink("herdr-pocket://pair?token=abc&url=https://hub.tail1234.ts.net/"),
        )
    }

    @Test
    fun tokenKeepsPlusAndDecodesEscapes() {
        assertEquals("a+b/c=", parsePairLink("herdr-pocket://pair?url=http://h:1&token=a+b%2Fc%3D")?.token)
    }

    @Test
    fun pathPrefixAndTrailingSlash() {
        assertEquals("http://h:1/pocket", parsePairLink("herdr-pocket://pair/?url=http%3A%2F%2Fh%3A1%2Fpocket%2F&token=t")?.url)
    }

    @Test
    fun rejects() {
        assertNull(parsePairLink("herdr-pocket://pair?url=http://h:1"))
        assertNull(parsePairLink("herdr-pocket://pair?token=t"))
        assertNull(parsePairLink("herdr-pocket://pair?url=ftp://h&token=t"))
        assertNull(parsePairLink("herdr-pocket://pair?url=http://h:1&token="))
        assertNull(parsePairLink("herdr-pocket://other?url=http://h:1&token=t"))
        assertNull(parsePairLink("https://example.com/pair?url=http://h:1&token=t"))
        assertNull(parsePairLink("herdr-pocket://pairing?url=http://h:1&token=t"))
    }

    @Test
    fun caseInsensitiveScheme() {
        assertEquals(HubConfig("http://h:1", "t"), parsePairLink("HERDR-POCKET://pair?url=http://h:1&token=t"))
    }

    @Test
    fun manualEntry() {
        assertEquals(HubConfig("http://heimdall:8790", "tok"), hubConfigOf(" http://heimdall:8790/ ", " tok "))
        assertNull(hubConfigOf("heimdall:8790", "tok"))
        assertNull(hubConfigOf("http://", "tok"))
        assertNull(normalizeHubUrl("http://h:1/?x=1"))
        assertNull(normalizeHubUrl("http://h :1"))
    }

    @Test
    fun tokenStaysOutOfToString() {
        assertFalse("secret" in HubConfig("http://h", "secret").toString())
    }
}

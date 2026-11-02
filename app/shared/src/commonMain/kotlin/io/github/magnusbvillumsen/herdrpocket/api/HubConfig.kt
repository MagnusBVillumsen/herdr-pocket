package io.github.magnusbvillumsen.herdrpocket.api

data class HubConfig(val url: String, val token: String) {
    override fun toString(): String = "HubConfig(url=$url, token=***)"
}

/** Trims whitespace and trailing slashes; returns null unless it is an http(s) URL with a host. */
fun normalizeHubUrl(raw: String): String? {
    val url = raw.trim().trimEnd('/')
    val rest = when {
        url.startsWith("http://", ignoreCase = true) -> url.substring(7)
        url.startsWith("https://", ignoreCase = true) -> url.substring(8)
        else -> return null
    }
    val host = rest.substringBefore('/').substringBefore('?')
    if (host.isEmpty() || rest.contains('?') || rest.contains('#') || rest.any { it.isWhitespace() }) return null
    return url
}

fun hubConfigOf(url: String, token: String): HubConfig? {
    val u = normalizeHubUrl(url) ?: return null
    val t = token.trim()
    if (t.isEmpty()) return null
    return HubConfig(u, t)
}

const val PAIR_SCHEME = "herdr-pocket"

/**
 * Parses `herdr-pocket://pair?url=…&token=…`. Parameters are percent-decoded, but a raw
 * `http://host:port` value is accepted too, since the hub URL needs no escaping to be unambiguous.
 */
fun parsePairLink(link: String): HubConfig? {
    val prefix = "$PAIR_SCHEME://pair"
    val s = link.trim()
    if (!s.startsWith(prefix, ignoreCase = true)) return null
    val after = s.substring(prefix.length).removePrefix("/")
    if (!after.startsWith("?")) return null
    val params = after.substring(1).substringBefore('#')
        .split('&')
        .filter { it.isNotEmpty() }
        .associate { part ->
            val k = part.substringBefore('=')
            val v = if ('=' in part) part.substringAfter('=') else ""
            percentDecode(k) to percentDecode(v)
        }
    return hubConfigOf(params["url"] ?: return null, params["token"] ?: return null)
}

// '+' stays a plus: tokens may be base64, and the link is not form-encoded.
internal fun percentDecode(s: String): String {
    if ('%' !in s) return s
    val out = StringBuilder(s.length)
    val pending = ArrayList<Byte>()
    fun flush() {
        if (pending.isNotEmpty()) {
            out.append(pending.toByteArray().decodeToString())
            pending.clear()
        }
    }
    var i = 0
    while (i < s.length) {
        val hi = if (s[i] == '%' && i + 2 < s.length) hexValue(s[i + 1]) else -1
        val lo = if (hi >= 0) hexValue(s[i + 2]) else -1
        if (lo >= 0) {
            pending.add(((hi shl 4) or lo).toByte())
            i += 3
        } else {
            flush()
            out.append(s[i])
            i++
        }
    }
    flush()
    return out.toString()
}

private fun hexValue(c: Char): Int = when (c) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> c - 'a' + 10
    in 'A'..'F' -> c - 'A' + 10
    else -> -1
}

package io.github.magnusbvillumsen.herdrpocket.api

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.SSEClientException
import io.ktor.client.plugins.sse.sse
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.URLBuilder
import io.ktor.http.appendPathSegments
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject

class HubException(val status: Int?, message: String, cause: Throwable? = null) : Exception(message, cause)

/** The app side of the hub API in docs/protocol.md. */
class HubClient(
    private val config: HubConfig,
    engine: HttpClientEngine? = null,
) : AutoCloseable {
    private val http: HttpClient = if (engine != null) HttpClient(engine) { setup() } else HttpClient { setup() }

    private fun HttpClientConfig<*>.setup() {
        expectSuccess = false
        install(SSE)
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 20_000
            // The hub pings every 20 s, so a minute of silence means the connection is dead.
            socketTimeoutMillis = 60_000
        }
        defaultRequest {
            header(HttpHeaders.Authorization, "Bearer ${config.token}")
        }
    }

    suspend fun agents(): AgentsResponse = call(HttpMethod.Get, AgentsResponse.serializer(), "api", "agents")

    /**
     * One SSE connection to `/api/events`. Emits every `agents` event and completes when the
     * hub closes the stream; reconnecting is up to the caller.
     */
    fun events(): Flow<AgentsResponse> = channelFlow {
        try {
            http.sse(request = {
                url(endpoint("api", "events"))
                timeout { requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS }
            }) {
                incoming.collect { ev ->
                    val data = ev.data
                    if (ev.event == "agents" && data != null) send(decode(AgentsResponse.serializer(), data))
                }
            }
        } catch (e: SSEClientException) {
            val r = e.response
            throw HubException(r?.status?.value, r?.let { errorMessage(it) } ?: (e.message ?: "event stream failed"), e)
        }
    }

    suspend fun screen(machine: String, pane: String, lines: Int = 300): String {
        val u = URLBuilder(endpoint("api", "agents", machine, pane, "screen"))
            .apply { parameters.append("lines", lines.toString()) }
            .buildString()
        return send(HttpMethod.Get, u, ScreenResponse.serializer()).text
    }

    /** The whole conversation from the agent's transcript; empty when there is none. */
    suspend fun history(machine: String, pane: String): String =
        send(HttpMethod.Get, endpoint("api", "agents", machine, pane, "history"), ScreenResponse.serializer()).text

    suspend fun prompt(machine: String, pane: String, text: String) {
        call(HttpMethod.Post, JsonObject.serializer(), "api", "agents", machine, pane, "prompt") {
            json(PromptRequest.serializer(), PromptRequest(text))
        }
    }

    suspend fun keys(machine: String, pane: String, keys: List<String>) {
        call(HttpMethod.Post, JsonObject.serializer(), "api", "agents", machine, pane, "keys") {
            json(KeysRequest.serializer(), KeysRequest(keys))
        }
    }

    suspend fun setImportant(machine: String, pane: String, important: Boolean) {
        call(HttpMethod.Put, JsonObject.serializer(), "api", "agents", machine, pane, "important") {
            json(ImportantRequest.serializer(), ImportantRequest(important))
        }
    }

    suspend fun registerPush(endpoint: String) {
        call(HttpMethod.Post, JsonObject.serializer(), "api", "push") {
            json(PushRequest.serializer(), PushRequest(endpoint))
        }
    }

    suspend fun unregisterPush(endpoint: String) {
        call(HttpMethod.Delete, JsonObject.serializer(), "api", "push") {
            json(PushRequest.serializer(), PushRequest(endpoint))
        }
    }

    override fun close() = http.close()

    private fun endpoint(vararg segments: String): String =
        URLBuilder(config.url).apply { appendPathSegments(segments.toList(), encodeSlash = true) }.buildString()

    private suspend fun <T> call(
        method: HttpMethod,
        result: KSerializer<T>,
        vararg segments: String,
        body: HttpRequestBuilder.() -> Unit = {},
    ): T = send(method, endpoint(*segments), result, body)

    private suspend fun <T> send(
        method: HttpMethod,
        url: String,
        result: KSerializer<T>,
        body: HttpRequestBuilder.() -> Unit = {},
    ): T {
        val r = try {
            http.request(url) {
                this.method = method
                body()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw HubException(null, e.message ?: e::class.simpleName ?: "request failed", e)
        }
        if (!r.status.isSuccess()) throw HubException(r.status.value, errorMessage(r))
        val text = r.bodyAsText()
        return decode(result, text.ifBlank { "{}" })
    }

    private fun <B> HttpRequestBuilder.json(serializer: KSerializer<B>, value: B) {
        contentType(ContentType.Application.Json)
        setBody(HubJson.encodeToString(serializer, value))
    }

    private fun <T> decode(serializer: KSerializer<T>, text: String): T = try {
        HubJson.decodeFromString(serializer, text)
    } catch (e: SerializationException) {
        throw HubException(null, "unexpected reply from hub: ${e.message}", e)
    } catch (e: IllegalArgumentException) {
        throw HubException(null, "unexpected reply from hub: ${e.message}", e)
    }

    private suspend fun errorMessage(r: HttpResponse): String {
        val text = runCatching { r.bodyAsText() }.getOrDefault("")
        val parsed = runCatching { HubJson.decodeFromString(ErrorResponse.serializer(), text).error }.getOrNull()
        return describeStatus(r.status.value, parsed ?: text.take(200).ifBlank { null })
    }
}

internal fun describeStatus(status: Int, detail: String?): String {
    val base = when (status) {
        401, 403 -> "token rejected by hub"
        404 -> "not found"
        503 -> "machine is disconnected"
        504 -> "machine did not answer in time"
        else -> "hub answered $status"
    }
    return if (detail.isNullOrBlank() || detail == base) base else "$base: $detail"
}

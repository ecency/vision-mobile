package com.ecency.core.api.rpc

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray

/** Raw HTTP answer from one node: status code and body text. */
data class HttpResult(
    val status: Int,
    val body: String,
    /** Response headers by lower-cased name, where the transport provides them (the auth transport does; node calls carry `retry-after` only). */
    val headers: Map<String, String> = emptyMap(),
) {
    constructor(status: Int, body: String) : this(status, body, emptyMap())
}

/**
 * Sends one JSON-RPC body to one node. Injectable so tests run against a fake.
 *
 * Implementations throw on network failure (DNS, TLS, connection reset, ...) and must be
 * cancellable: [HiveRpcClient] enforces timeouts by cancelling the calling coroutine.
 */
fun interface RpcTransport {
    /** Declared to throw so a transport written in Swift can report a network error as an Error. */
    @Throws(Exception::class)
    suspend fun post(url: String, body: String): HttpResult
}

/**
 * Production transport over Ktor (OkHttp on Android, NSURLSession on iOS).
 *
 * Bodies larger than [maxResponseBytes] are cut off and reported as a node failure, so a
 * misbehaving node cannot exhaust memory.
 */
class KtorTransport(
    private val client: HttpClient,
    private val maxResponseBytes: Long = DEFAULT_MAX_RESPONSE_BYTES,
) : RpcTransport {
    constructor() : this(platformHttpClient())

    @Throws(Exception::class)
    override suspend fun post(url: String, body: String): HttpResult {
        val response = client.post(url) { setBody(TextContent(body, ContentType.Application.Json)) }
        val bytes = response.bodyAsChannel().readRemaining(maxResponseBytes + 1).readByteArray()
        if (bytes.size > maxResponseBytes) throw ResponseTooLargeException(url, maxResponseBytes)
        // the one header the node pool reads: a 429's Retry-After (NodeHealthTracker.recordRateLimit)
        val retryAfter = response.headers["Retry-After"]
        return HttpResult(response.status.value, bytes.decodeToString(), if (retryAfter != null) mapOf("retry-after" to retryAfter) else emptyMap())
    }

    fun close() = client.close()

    companion object {
        /** A large bridge.get_discussion is a few MB; 32 MB leaves ample headroom. */
        const val DEFAULT_MAX_RESPONSE_BYTES: Long = 32L * 1024 * 1024
    }
}

/** Thrown by [KtorTransport] for an oversized body; the client treats it as a node failure. */
class ResponseTooLargeException(url: String, limit: Long) : Exception("response from $url exceeds $limit bytes")

/**
 * The platform's default Ktor client: OkHttp engine on Android, Darwin engine on iOS.
 * Engine read/request timeouts are disabled or set above the client's own per-attempt
 * timeouts, which are the ones that must fire (see [HiveRpcClient]).
 */
expect fun platformHttpClient(): HttpClient

/**
 * The platform client for websockets: [platformHttpClient]'s settings with a connect timeout of [connectTimeoutMillis]
 * where the engine has one (OkHttp; NSURLSession has none of its own, the caller's bound is the one there).
 */
expect fun platformWebSocketClient(connectTimeoutMillis: Long): HttpClient

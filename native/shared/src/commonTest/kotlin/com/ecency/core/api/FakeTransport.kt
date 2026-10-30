package com.ecency.core.api

import com.ecency.core.api.rpc.HiveRpcClient
import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.api.rpc.RpcTransport
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** One request the fake received. */
data class Sent(val node: String, val body: String) {
    val json: JsonObject get() = Json.parseToJsonElement(body).jsonObject
    val method: String get() = json.getValue("method").jsonPrimitive.content
    val id: Long get() = json.getValue("id").jsonPrimitive.long
    val params: JsonElement get() = json.getValue("params")
}

/** Scripted transport: [handler] answers each request; every request is recorded in [sent]. */
class FakeTransport(private val handler: suspend FakeTransport.(Sent) -> HttpResult) : RpcTransport {
    private val lock = Mutex()
    private val log = mutableListOf<Sent>()

    /** A snapshot of every request so far, in order. */
    val sent: List<Sent> get() = log.toList()

    override suspend fun post(url: String, body: String): HttpResult {
        val request = Sent(url, body)
        lock.withLock { log += request }
        return handler(request)
    }

    fun methods(): List<String> = sent.map { it.method }

    companion object {
        fun result(request: Sent, resultJson: String) =
            HttpResult(200, """{"jsonrpc":"2.0","result":$resultJson,"id":${request.id}}""")

        fun error(request: Sent, code: Int, message: String, data: String = "null") =
            HttpResult(200, """{"jsonrpc":"2.0","error":{"code":$code,"message":${Json.encodeToString(message)},"data":$data},"id":${request.id}}""")

        /** Answers by method name from [results] (JSON text); anything else is an HTTP 500. */
        fun byMethod(results: Map<String, String>) = FakeTransport { request ->
            results[request.method]?.let { result(request, it) } ?: HttpResult(500, "no fixture for ${request.method}")
        }
    }
}

val NODES = listOf("https://node-a.example", "https://node-b.example", "https://node-c.example")

/** A client over [NODES] with short timeouts and no pause between attempts (so virtual time is exact). */
fun clientWith(transport: FakeTransport, now: () -> Long = { 0L }, retry: Int = 5) =
    HiveRpcClient(NODES, transport, readTimeoutMillis = 1_000, broadcastTimeoutMillis = 2_000, nowMillis = now, retry = retry, jitter = {})

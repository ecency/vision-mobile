package com.ecency.core.api.rpc

import com.ecency.core.api.EcencyException
import com.ecency.core.api.InvalidInputException
import com.ecency.core.api.json.SafeJson
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.ceil
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Node pools, mirroring the RN app (`src/constants/options/api.ts`). */
object HiveNodes {
    /** The app's SERVER_LIST, in the app's order. */
    val DEFAULT: List<String> = listOf(
        "https://api.deathwing.me",
        "https://rpc.mahdiyari.info",
        "https://api.openhive.network",
        "https://api.c0ff33a.uk",
        "https://api.hive.blog",
        "https://api.syncad.com",
    )

    /**
     * Nodes that must never serve the app: they answer condenser_api.get_accounts with
     * posting_json_metadata stripped, which silently empties wallet token settings.
     */
    val BLOCKED: List<String> = listOf("https://techcoderx.com", "https://hiveapi.actifit.io")

    private fun normalize(node: String) = node.trim().trimEnd('/').lowercase()

    fun isBlocked(node: String): Boolean = BLOCKED.any { normalize(it) == normalize(node) }

    private val HTTP = Regex("^https?://.+", RegexOption.IGNORE_CASE)

    /** Drops blocked nodes, anything but http(s) URLs, duplicates and trailing slashes, keeping order (SDK setNodes). */
    fun sanitize(nodes: List<String>): List<String> =
        nodes.map { it.trim().trimEnd('/') }.filter { HTTP.matches(it) && !isBlocked(it) }.distinctBy(::normalize)

    /**
     * A node from the published list must be https and name no user or password (`https://user@host` would send
     * them, and can disguise the host); any port is fine.
     */
    fun isSafeRemote(url: String): Boolean {
        if (!url.startsWith("https://", ignoreCase = true) || url.any { it.isWhitespace() }) return false
        val authority = url.substring("https://".length).takeWhile { it != '/' && it != '?' && it != '#' }
        return authority.isNotEmpty() && '@' !in authority && !authority.startsWith(":")
    }

    /**
     * A server the user saved, picked or brought from the RN app: one node that passes [sanitize] and the same rule
     * as the published list ([isSafeRemote]: https, no user or password), normalized; null for anything else.
     */
    fun userServer(raw: String?): String? = raw?.let { sanitize(listOf(it)).singleOrNull() }?.takeIf(::isSafeRemote)

    /** Where the RN app reads the node pool (`getNodes`); its `hived` list, or the body itself when it is a list. */
    const val REMOTE_LIST_URL = "https://ecency.com/public-nodes.json"

    /**
     * RN getNodes: the published list without blocked nodes, or null when the body is not a non-empty list of
     * names (the caller then keeps [DEFAULT]). A list that held only blocked nodes is null too.
     */
    fun parseRemote(body: String): List<String>? = parseRemote(SafeJson.parseOrNull(body))

    fun parseRemote(root: JsonElement?): List<String>? {
        root ?: return null
        val list = ((root as? JsonObject)?.get("hived") ?: root) as? JsonArray ?: return null
        val names = list.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        return sanitize(names).filter(::isSafeRemote).takeIf { it.isNotEmpty() }
    }

    /**
     * RN initSdkConfig: the user's server (when one is saved, not blocked and a [userServer]) goes in front of
     * [fetched] unless it is already in it, where it keeps its place. The health ranking then orders the pool, as
     * in the SDK. An unsafe saved value is ignored: the pool is the list alone.
     */
    fun pool(saved: String?, fetched: List<String>): List<String> {
        val chosen = userServer(saved)
        val base = sanitize(fetched)
        return sanitize(if (chosen != null && base.none { normalize(it) == normalize(chosen) }) listOf(chosen) + base else base)
    }

    /** RN settings `_changeApi`: the chosen server first, then the rest of the list. */
    fun withFirst(server: String, list: List<String>): List<String> = sanitize(listOf(server) + list)
}

/** Base class for everything the data layer throws about talking to Hive nodes. */
sealed class HiveApiException(message: String, cause: Throwable? = null) : EcencyException(message, cause)

/** The node answered with a JSON-RPC error object: the API or the chain rejected the call. */
class RpcErrorException(
    val method: String,
    val node: String,
    val code: Int,
    val rpcMessage: String,
    /** The error's `data` member as JSON text, if any (hived puts assertion details there). */
    val data: String?,
) : HiveApiException("$method failed on $node: $code $rpcMessage")

/** One node could not serve the call (network error, HTTP 429/5xx, garbage body, sick API). */
class NodeFailureException(
    val node: String,
    val reason: String,
    /**
     * False only when the request certainly never reached the node (connection refused, DNS
     * failure, connect timeout, HTTP 429). True for anything else after the body may have been
     * sent: a 5xx or gateway error (a proxy can fail after hived took the transaction), a body
     * that is not a reply, a connection that broke mid-request.
     */
    val mayHaveReachedNode: Boolean = false,
    cause: Throwable? = null,
    /** What the failure says about the node, for its health ([NodeHealthTracker]). */
    val scope: Scope = Scope.NODE,
    /** HTTP 429's `Retry-After`, in ms; 0 when it had none. */
    val retryAfterMillis: Long = 0,
) : HiveApiException("$node: $reason", cause) {
    /** As the SDK records it: against the node, against the API on the node, as a rate limit. */
    enum class Scope { NODE, API, RATE_LIMIT }
}

/** One attempt ran past its time budget. The node may have received the request. */
class RpcTimeoutException(val node: String, val method: String, val timeoutMillis: Long) :
    HiveApiException("$method timed out after ${timeoutMillis}ms on $node")

/** No attempt answered usefully. [failures] holds one entry per attempt, in order (reads may ask a node twice). */
class AllNodesFailedException(val method: String, val failures: List<HiveApiException>) :
    HiveApiException("$method failed on all ${failures.size} nodes: ${failures.lastOrNull()?.message}", failures.lastOrNull())

/**
 * JSON-RPC 2.0 client over a pool of Hive nodes, with the node selection and failover of @ecency/sdk 2.4.26
 * (hive-tx `callRPC` / `callRPCBroadcast`) as the RN app configures it (10 s read timeout).
 *
 * Every call orders the pool by node health ([health], [NodeHealthTracker]): healthy nodes first, fastest first
 * once measured, so the node that keeps answering well is the one asked ("sticky" by measurement, not by a
 * fixed pointer).
 *
 * Reads ([call]) make up to [retry] + 1 attempts, each on the healthiest node not yet tried in this round, and
 * start a new round over the pool when every node was tried (a pool of one node is asked again). No new
 * attempt starts after [budgetFactor] x the read timeout (20 s), and 50 to 100 ms of jitter separates attempts.
 * They fail over on network errors, timeouts, HTTP 429/5xx, bodies that are not a JSON-RPC reply to the request,
 * answers the caller's validator rejects, and node-level JSON-RPC errors (the codes @ecency/sdk treats as "this
 * node is sick"). Other JSON-RPC errors are real answers and are thrown at once as [RpcErrorException]. A node
 * measured on a method gets min(timeout, max(2 s, 4 x its EWMA for that method)) per attempt.
 *
 * Broadcasts ([broadcast]) try each node at most once and never fail over after the chain has
 * rejected the transaction; see [broadcast] for the deadline rule.
 *
 * Each attempt is bounded by its own timeout. Cancelling the calling coroutine (an outer
 * withTimeout included) cancels the HTTP request and propagates as cancellation, never as a
 * node failure.
 */
@OptIn(ExperimentalTime::class, ExperimentalAtomicApi::class)
class HiveRpcClient(
    nodes: List<String> = HiveNodes.DEFAULT,
    private val transport: RpcTransport = KtorTransport(),
    /** Per attempt, for reads. The RN app sets 10 s (`hiveTxConfig.timeout`). */
    val readTimeoutMillis: Long = 10_000,
    /** Per attempt, for broadcasts (the SDK's broadcastTimeout). */
    val broadcastTimeoutMillis: Long = 15_000,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    /** Read retries after the first attempt (the SDK's `config.retry`). */
    val retry: Int = 5,
    /** No new read attempt after this many read timeouts from the start (`resilience.totalBudgetFactor`). */
    val budgetFactor: Int = 2,
    /** The pause between read attempts (SDK jitterDelay: 50 to 100 ms). Tests pass none. */
    private val jitter: suspend () -> Unit = { delay(50 + Random.nextLong(51)) },
) {
    @Volatile
    var nodes: List<String> = HiveNodes.sanitize(nodes)
        private set

    /** Shared by reads and broadcasts, as the SDK's rpcHealthTracker. */
    val health = NodeHealthTracker(nowMillis)

    private val nextId = AtomicLong(0)

    init {
        if (this.nodes.isEmpty()) throw InvalidInputException("no usable Hive nodes")
    }

    /** The node the next call would start with (no re-probe is spent on asking). */
    val currentNode: String get() = health.orderedNodes(nodes, probe = false).first()

    /** Replaces the pool (e.g. a user-chosen node first). Blocked nodes are dropped; what was learned about each node stays. */
    @Throws(EcencyException::class)
    fun setNodes(newNodes: List<String>) {
        val sanitized = HiveNodes.sanitize(newNodes)
        if (sanitized.isEmpty()) throw InvalidInputException("no usable Hive nodes")
        nodes = sanitized
    }

    /** Builds the exact request body sent to a node. */
    internal fun requestBody(method: String, params: JsonElement, id: Long): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("method", method)
        put("params", params)
        put("id", id)
    }.toString()

    /**
     * A read call with failover. Returns the `result` member (JsonNull when the node returned null).
     *
     * [timeoutMillis], when given, is the exact per-attempt bound (no adaptive shortening). [validate] rejects
     * a well-formed answer the caller knows is impossible (SDK `validate`, e.g. `get_accounts` must be a list):
     * the node's API cools down and the next node is asked.
     */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun call(
        method: String,
        params: JsonElement = JsonArray(emptyList()),
        timeoutMillis: Long? = null,
        validate: ((JsonElement) -> Boolean)? = null,
    ): JsonElement {
        val explicit = timeoutMillis != null
        val ceiling = timeoutMillis ?: readTimeoutMillis
        val api = NodeHealthTracker.apiOf(method)
        val deadline = nowMillis() + budgetFactor * ceiling
        val tried = mutableSetOf<String>()
        val failures = mutableListOf<HiveApiException>()
        for (attempt in 0..retry) {
            if (attempt > 0 && nowMillis() >= deadline) break
            val ordered = health.orderedNodes(nodes, api)
            val node = ordered.firstOrNull { it !in tried } ?: ordered.first().also { tried.clear() }
            tried += node
            val started = nowMillis()
            try {
                val result = attempt(node, method, params, attemptTimeout(node, method, ceiling, explicit))
                if (validate != null && !validate(result)) {
                    health.recordDefectiveResponse(node, api)
                    failures += NodeFailureException(node, "response validation failed for $method", scope = NodeFailureException.Scope.API)
                } else {
                    health.recordSuccess(node, api, nowMillis() - started, method)
                    recordHeadBlock(node, method, result)
                    return result
                }
            } catch (e: RpcErrorException) {
                if (!isNodeLevelError(e.code, e.rpcMessage)) throw e
                record(node, e, api)
                health.recordSlowFailure(node, nowMillis() - started, method)
                failures += e
            } catch (e: NodeFailureException) {
                record(node, e, api)
                health.recordSlowFailure(node, nowMillis() - started, method)
                failures += e
            } catch (e: RpcTimeoutException) {
                record(node, e, api)
                health.recordSlowFailure(node, nowMillis() - started, method)
                failures += e
            }
            if (attempt < retry) jitter()
        }
        val last = failures.last()
        if (failures.all { it is RpcErrorException }) throw last
        throw AllNodesFailedException(method, failures)
    }

    /**
     * One request to [node] alone: no failover, no retry, nothing recorded about its health (RN settings' server
     * check fetches the node directly too). Throws what an attempt throws.
     */
    internal suspend fun ask(node: String, method: String, params: JsonElement, timeoutMillis: Long): JsonElement =
        attempt(node, method, params, timeoutMillis)

    private fun HiveApiException.nodeOf(): String = when (this) {
        is NodeFailureException -> node
        is RpcTimeoutException -> node
        is RpcErrorException -> node
        else -> currentNode
    }

    /** SDK adaptiveAttemptTimeout: a node measured on [method] is given 4 x its EWMA (at least 2 s, at most [ceiling]). */
    private fun attemptTimeout(node: String, method: String, ceiling: Long, explicit: Boolean): Long {
        if (explicit) return ceiling
        val ewma = health.usableLatencyMs(node, method) ?: return ceiling
        return ceil(minOf(ceiling.toDouble(), maxOf(ADAPTIVE_FLOOR_MILLIS.toDouble(), ADAPTIVE_FACTOR * ewma))).toLong()
    }

    /** SDK recordError: what a failure says about the node. */
    private fun record(node: String, e: HiveApiException, api: String) {
        when (e) {
            is NodeFailureException -> when (e.scope) {
                NodeFailureException.Scope.RATE_LIMIT -> health.recordRateLimit(node, e.retryAfterMillis.takeIf { it > 0 })
                NodeFailureException.Scope.API -> health.recordFailure(node, api)
                NodeFailureException.Scope.NODE -> health.recordFailure(node)
            }
            is RpcErrorException -> health.recordFailure(node, api)
            else -> health.recordFailure(node)
        }
    }

    /** SDK tryRecordHeadBlock: a `get_dynamic_global_properties` answer tells how far the node is. */
    private fun recordHeadBlock(node: String, method: String, result: JsonElement) {
        if (!method.contains("get_dynamic_global_properties")) return
        val block = ((result as? JsonObject)?.get("head_block_number") as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: return
        health.recordHeadBlock(node, block)
    }

    /**
     * Sends a signed transaction (`condenser_api.broadcast_transaction`, as the SDK does).
     *
     * A JSON-RPC error is the chain's verdict and is thrown at once, never re-sent elsewhere.
     * Otherwise the same signed transaction moves to the next node (Hive dedupes by transaction
     * id), following the SDK's isBroadcastSafeToRetry:
     * - failures where the request certainly never reached a node (connection refused, DNS,
     *   HTTP 429) fail over freely;
     * - after any other failure it may have reached one (a timeout, HTTP 5xx, a gateway error
     *   or HTML page, a connection reset mid-request): every further failover happens only while
     *   [failoverUntilMillis] has not passed. Past it a later node could answer "expired" for
     *   a transaction the earlier node included.
     * When the transaction may have landed, that uncertain failure is what gets thrown, and a
     * later "Duplicate transaction" answer must be read as success (HiveBroadcaster does).
     */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun broadcast(method: String, params: JsonElement, failoverUntilMillis: Long): JsonElement {
        val api = NodeHealthTracker.apiOf(method)
        val tried = mutableSetOf<String>()
        var lastFailure: HiveApiException? = null
        var uncertain: HiveApiException? = null
        while (true) {
            if (uncertain != null && nowMillis() >= failoverUntilMillis) throw uncertain
            // re-ordered before each attempt, so what the last failure taught is used at once
            val node = health.orderedNodes(nodes, api).firstOrNull { it !in tried } ?: break
            tried += node
            try {
                val result = attempt(node, method, params, broadcastTimeoutMillis)
                // no latency sample: a broadcast's round trip says nothing about the node's read speed
                health.recordSuccess(node, api)
                return result
            } catch (e: RpcErrorException) {
                // The chain's verdict, from a node that is fine. Except after an uncertain attempt: an earlier node
                // may have taken the transaction, and a later one (lagging, say, with a TaPoS assert) does not know.
                // Its answer is no verdict on that send; "Duplicate transaction" stays the answer it is (success).
                val earlier = uncertain
                if (earlier != null && !e.rpcMessage.contains("Duplicate transaction", ignoreCase = true)) {
                    throw NodeFailureException(
                        earlier.nodeOf(), "an earlier send may have landed; then $node answered: ${e.code} ${e.rpcMessage}",
                        mayHaveReachedNode = true, cause = e,
                    )
                }
                throw e
            } catch (e: NodeFailureException) {
                record(node, e, api)
                lastFailure = e
                if (e.mayHaveReachedNode && uncertain == null) uncertain = e
            } catch (e: RpcTimeoutException) {
                record(node, e, api)
                lastFailure = e
                if (uncertain == null) uncertain = e
            }
        }
        throw uncertain ?: lastFailure ?: AllNodesFailedException(method, emptyList())
    }

    private suspend fun attempt(node: String, method: String, params: JsonElement, timeoutMillis: Long): JsonElement {
        val id = nextId.incrementAndFetch()
        val body = requestBody(method, params, id)
        val response = try {
            // Only OUR timeout reads as null here. A caller's cancellation (an outer withTimeout
            // included) is thrown through and never mistaken for a slow node.
            withTimeoutOrNull(timeoutMillis) { transport.post(node, body) }
                ?: throw RpcTimeoutException(node, method, timeoutMillis)
        } catch (e: HiveApiException) {
            throw e
        } catch (e: CancellationException) {
            // Our caller cancelled (or its outer timeout fired): that propagates. A cancellation the transport
            // raised on its own while we are still active is a failed request like any other.
            currentCoroutineContext().ensureActive()
            throw NodeFailureException(node, "request cancelled by the transport: ${e.message ?: e::class.simpleName}", mayHaveReachedNode = true, cause = e)
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            throw classifyTransportError(node, method, timeoutMillis, e)
        }
        return parseResponse(node, method, id, response)
    }

    private fun parseResponse(node: String, method: String, id: Long, response: HttpResult): JsonElement {
        if (response.status == 429) {
            throw NodeFailureException(
                node, "HTTP 429 rate limited",
                scope = NodeFailureException.Scope.RATE_LIMIT,
                retryAfterMillis = parseRetryAfterMillis(response.headers["retry-after"], nowMillis()),
            )
        }
        // a proxy may answer 5xx after the node took the request
        if (response.status in 500..599) {
            throw NodeFailureException(node, "HTTP ${response.status}", mayHaveReachedNode = true, scope = NodeFailureException.Scope.API)
        }
        val parsed = SafeJson.parseOrNull(response.body) as? JsonObject
        val isReply = parsed != null &&
            (parsed["jsonrpc"] as? JsonPrimitive)?.takeIf { it.isString }?.content == "2.0" &&
            // the id we sent is a number: "1" (a string) is not a reply to it
            (parsed["id"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull == id
        if (parsed == null || !isReply) {
            throw NodeFailureException(node, "HTTP ${response.status}, not a JSON-RPC reply to this request", mayHaveReachedNode = true)
        }
        parsed["error"]?.let { error ->
            val obj = error as? JsonObject
            val code = (obj?.get("code") as? JsonPrimitive)?.intOrNull ?: 0
            val message = (obj?.get("message") as? JsonPrimitive)?.content ?: error.toString()
            throw RpcErrorException(method, node, code, message, obj?.get("data")?.toString())
        }
        if ("result" !in parsed) throw NodeFailureException(node, "reply has neither result nor error", mayHaveReachedNode = true)
        return parsed.getValue("result")
    }

    companion object {
        /** SDK resilience.adaptiveTimeoutFloorMs / adaptiveTimeoutFactor. */
        const val ADAPTIVE_FLOOR_MILLIS = 2_000L
        const val ADAPTIVE_FACTOR = 4.0

        /** `get_accounts` always answers a list; null in a good envelope is a node fault (SDK validator). */
        val IS_ARRAY: (JsonElement) -> Boolean = { it is JsonArray }

        /** A `Retry-After` longer than a day is read as a day (no overflow, no node parked for good). */
        const val MAX_RETRY_AFTER_MILLIS = 24 * 60 * 60_000L

        /**
         * SDK parseRetryAfterMs: delay-seconds ("120") or an HTTP date ("Wed, 21 Oct 2026 07:28:00 GMT"), as ms
         * from [nowMillis]; 0 when absent, unreadable or past.
         */
        fun parseRetryAfterMillis(header: String?, nowMillis: Long): Long {
            val value = header?.trim()?.takeIf { it.isNotEmpty() } ?: return 0
            value.toDoubleOrNull()?.let { secs ->
                return if (secs.isFinite() && secs > 0) minOf(secs, MAX_RETRY_AFTER_MILLIS / 1000.0).times(1000).toLong() else 0
            }
            val at = HttpDate.parseMillis(value) ?: return 0
            return (at - nowMillis).coerceIn(0, MAX_RETRY_AFTER_MILLIS)
        }

        /** Same rule as @ecency/sdk isNodeLevelRPCError: the node, not the request, is at fault. */
        fun isNodeLevelError(code: Int, message: String): Boolean = when {
            code == -32603 || code == -32601 -> true
            code in -32099..-32000 -> true
            code == -32602 -> Regex("unable to parse|endpoint data|internal", RegexOption.IGNORE_CASE).containsMatchIn(message)
            else -> false
        }

        private fun describe(e: Throwable): String {
            val parts = mutableListOf<String>()
            var t: Throwable? = e
            var depth = 0
            while (t != null && depth < 6) {
                parts += "${t::class.simpleName} ${t.message.orEmpty()}"
                t = t.cause
                depth++
            }
            return parts.joinToString(" | ")
        }

        // JVM/OkHttp names and NSURLErrorDomain codes for failures before the request was sent:
        // -1003 cannot find host, -1004 cannot connect, -1006 DNS lookup failed, -1009 offline.
        private val PRE_CONNECTION = Regex(
            "ConnectException|UnknownHost|NoRouteToHost|ConnectTimeout|connect timed out|Connection refused|Unable to resolve host|" +
                "Failed to connect|ECONNREFUSED|ENOTFOUND|EHOSTUNREACH|Code=-1003|Code=-1004|Code=-1006|Code=-1009",
            RegexOption.IGNORE_CASE,
        )

        // A socket read timeout or NSURLErrorTimedOut (-1001) is a timeout, not a node error.
        private val TIMEOUT = Regex("SocketTimeout|RequestTimeout|timed out|Code=-1001", RegexOption.IGNORE_CASE)

        internal fun classifyTransportError(node: String, method: String, timeoutMillis: Long, e: Exception): HiveApiException {
            val text = describe(e)
            return when {
                PRE_CONNECTION.containsMatchIn(text) ->
                    NodeFailureException(node, "connection failed: ${e.message ?: e::class.simpleName}", mayHaveReachedNode = false, cause = e)
                TIMEOUT.containsMatchIn(text) -> RpcTimeoutException(node, method, timeoutMillis)
                else -> NodeFailureException(node, "network error: ${e.message ?: e::class.simpleName}", mayHaveReachedNode = true, cause = e)
            }
        }
    }
}

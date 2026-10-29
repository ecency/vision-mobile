package com.ecency.core.api.rpc

/**
 * Per-node health, ported from @ecency/sdk 2.4.26 `NodeHealthTracker` (hive-tx `helpers/call.ts`), which orders
 * the node pool for every read and broadcast of the RN app. The numbers are the SDK's ([Tuning]).
 *
 * A node is unhealthy, and goes to the back of the order (never out of it), while it is rate limited, after 3
 * failures in a row within 30 s, while one API is cooling down on it (2 failures of that API within 30 s, or
 * one response that failed the caller's validation: 60 s), or while its last head block is more than 30 blocks
 * behind the median of the nodes seen in the last 2 minutes. Healthy nodes are ranked by an EWMA of their
 * round trips once they have 3 fresh samples; until then they keep the pool's order. One ordering per minute
 * may put a node that was not sampled for a minute first, so a node that recovered can climb back.
 *
 * Thread safe; every method is quick and never suspends. The clock is the client's, so tests drive it.
 */
class NodeHealthTracker(private val nowMillis: () -> Long) {
    /** The SDK's constants, by their SDK names. */
    object Tuning {
        const val RATE_LIMIT_BASE_MS = 10_000L
        const val RATE_LIMIT_MAX_MS = 60_000L
        const val RATE_LIMIT_STREAK_RESET_MS = 120_000L
        const val MAX_API_FAILURES_BEFORE_COOLDOWN = 2
        const val API_COOLDOWN_MS = 60_000L
        const val API_FAILURE_WINDOW_MS = 30_000L
        const val NODE_FAILURES_BEFORE_UNHEALTHY = 3
        const val NODE_FAILURE_WINDOW_MS = 30_000L
        const val HEAD_BLOCK_MAX_AGE_MS = 120_000L
        const val STALE_BLOCK_THRESHOLD = 30L
        const val LATENCY_EWMA_ALPHA = 0.3
        const val LATENCY_MIN_SAMPLES = 3
        const val LATENCY_MAX_AGE_MS = 5 * 60_000L
        const val LATENCY_REPROBE_MS = 60_000L
        const val LATENCY_UNPROVEN_PRIOR_MS = 1_000.0
        const val LATENCY_SLOW_FAILURE_MS = 2_000L
    }

    private class ApiFailure(var count: Int = 0, var cooldownUntil: Long = 0, var lastFailureTime: Long = 0, var defective: Boolean = false)

    private class Profile(var ewmaMs: Double, var sampleCount: Int, var updatedAt: Long)

    private class Health(now: Long) {
        var consecutiveFailures = 0
        var lastFailureTime = 0L
        var rateLimitedUntil = 0L
        var rateLimitStreak = 0
        var lastRateLimitAt = 0L
        val apiFailures = mutableMapOf<String, ApiFailure>()
        var headBlock = 0L
        var headBlockUpdatedAt = 0L
        var ewmaLatencyMs: Double? = null
        var latencySampleCount = 0
        var latencyUpdatedAt = 0L

        // stamped at creation so a never-sampled node is not "overdue for a re-probe" on its first ordering
        var lastProbeAt = now
        val apiLatency = mutableMapOf<String, Profile>()
    }

    private val health = mutableMapOf<String, Health>()

    // Sections are a few map operations and never suspend; a real lock, so a waiting thread sleeps instead of spinning.
    private val lock = PlatformLock()

    private inline fun <T> locked(crossinline block: () -> T): T = lock.withLock { block() }

    private fun getOrCreate(node: String, now: Long): Health = health.getOrPut(node) { Health(now) }

    /** A good answer: clears the failure streak and the API's strikes (not an active validation cooldown). */
    fun recordSuccess(node: String, api: String? = null, durationMs: Long? = null, profileKey: String? = null) = locked {
        val now = nowMillis()
        val h = getOrCreate(node, now)
        h.consecutiveFailures = 0
        // an active rate-limit window is not cleared: a request already in flight may answer after a fresh 429
        h.rateLimitStreak = 0
        if (api != null) {
            val f = h.apiFailures[api]
            if (f == null || !(f.defective && f.cooldownUntil > now)) h.apiFailures.remove(api)
        }
        if (durationMs != null && durationMs >= 0) recordLatency(h, durationMs.toDouble(), profileKey ?: api, now)
    }

    /** A failed call that was slow (2 s or more) is a latency sample too; an instant refusal is not. */
    fun recordSlowFailure(node: String, durationMs: Long, profileKey: String? = null) = locked {
        if (durationMs < Tuning.LATENCY_SLOW_FAILURE_MS) return@locked
        val now = nowMillis()
        recordLatency(getOrCreate(node, now), durationMs.toDouble(), profileKey, now)
    }

    /**
     * The node's EWMA for [profileKey] (a full method name), or its overall one without a key, once it has 3
     * samples from the last 5 minutes; null otherwise. A key never falls back to the overall profile.
     */
    fun usableLatencyMs(node: String, profileKey: String? = null): Double? = locked {
        val h = health[node] ?: return@locked null
        val now = nowMillis()
        if (profileKey != null) {
            val p = h.apiLatency[profileKey] ?: return@locked null
            if (p.sampleCount >= Tuning.LATENCY_MIN_SAMPLES && now - p.updatedAt <= Tuning.LATENCY_MAX_AGE_MS) p.ewmaMs else null
        } else {
            if (isLatencyUsable(h, now)) h.ewmaLatencyMs else null
        }
    }

    private fun recordLatency(h: Health, durationMs: Double, profileKey: String?, now: Long) {
        if (h.latencyUpdatedAt > 0 && now - h.latencyUpdatedAt > Tuning.LATENCY_MAX_AGE_MS) {
            h.ewmaLatencyMs = null
            h.latencySampleCount = 0
            h.apiLatency.clear()
        }
        h.ewmaLatencyMs = h.ewmaLatencyMs?.let { Tuning.LATENCY_EWMA_ALPHA * durationMs + (1 - Tuning.LATENCY_EWMA_ALPHA) * it } ?: durationMs
        h.latencySampleCount++
        h.latencyUpdatedAt = now
        if (profileKey != null) {
            val p = h.apiLatency[profileKey]
            if (p == null || now - p.updatedAt > Tuning.LATENCY_MAX_AGE_MS) {
                h.apiLatency[profileKey] = Profile(durationMs, 1, now)
            } else {
                p.ewmaMs = Tuning.LATENCY_EWMA_ALPHA * durationMs + (1 - Tuning.LATENCY_EWMA_ALPHA) * p.ewmaMs
                p.sampleCount++
                p.updatedAt = now
            }
        }
    }

    /**
     * A failure. With [api] it counts against that API on the node only (a node may lack one API plugin);
     * without, against the node as a whole (transport errors, timeouts, garbage bodies).
     */
    fun recordFailure(node: String, api: String? = null) = locked {
        val now = nowMillis()
        val h = getOrCreate(node, now)
        if (api != null) {
            val f = h.apiFailures.getOrPut(api) { ApiFailure() }
            // strikes far apart, or from before an expired cooldown, start over
            if ((f.cooldownUntil > 0 && f.cooldownUntil <= now) || (f.lastFailureTime > 0 && now - f.lastFailureTime > Tuning.API_FAILURE_WINDOW_MS)) {
                f.count = 0
                f.cooldownUntil = 0
            }
            f.count++
            f.lastFailureTime = now
            if (f.count >= Tuning.MAX_API_FAILURES_BEFORE_COOLDOWN) f.cooldownUntil = now + Tuning.API_COOLDOWN_MS
        } else {
            h.consecutiveFailures++
            h.lastFailureTime = now
        }
    }

    /** A well-formed answer the caller knows is impossible: the API cools down on this node at once, and a success does not lift it. */
    fun recordDefectiveResponse(node: String, api: String) = locked {
        val now = nowMillis()
        val f = getOrCreate(node, now).apiFailures.getOrPut(api) { ApiFailure() }
        f.count = maxOf(f.count + 1, Tuning.MAX_API_FAILURES_BEFORE_COOLDOWN)
        f.lastFailureTime = now
        f.cooldownUntil = now + Tuning.API_COOLDOWN_MS
        f.defective = true
    }

    /**
     * HTTP 429. A `Retry-After` ([retryAfterMs] > 0) is honoured exactly; without one the cooldown doubles from
     * 10 s up to 60 s while 429s keep coming (the streak resets after 2 quiet minutes or a success), and never
     * shortens a longer window already set.
     */
    fun recordRateLimit(node: String, retryAfterMs: Long? = null) = locked {
        val now = nowMillis()
        val h = getOrCreate(node, now)
        if (h.rateLimitStreak > 0 && now - h.lastRateLimitAt > Tuning.RATE_LIMIT_STREAK_RESET_MS) h.rateLimitStreak = 0
        val hasHeader = retryAfterMs != null && retryAfterMs > 0
        if (hasHeader) {
            h.rateLimitedUntil = now + minOf(retryAfterMs!!, HiveRpcClient.MAX_RETRY_AFTER_MILLIS)
        } else {
            val cooldown = minOf(Tuning.RATE_LIMIT_BASE_MS shl minOf(h.rateLimitStreak, 20), Tuning.RATE_LIMIT_MAX_MS)
            h.rateLimitStreak++
            h.rateLimitedUntil = maxOf(h.rateLimitedUntil, now + cooldown)
        }
        h.lastRateLimitAt = now
        h.consecutiveFailures++
        h.lastFailureTime = now
    }

    /** The head block a node reported (from `get_dynamic_global_properties`). */
    fun recordHeadBlock(node: String, blockNum: Long) = locked {
        if (blockNum <= 0) return@locked
        val now = nowMillis()
        val h = getOrCreate(node, now)
        h.headBlock = blockNum
        h.headBlockUpdatedAt = now
    }

    /** The lower median of the head blocks seen in the last 2 minutes; 0 with fewer than two. */
    private fun consensusHeadBlock(now: Long): Long {
        val recent = health.values.filter { it.headBlock > 0 && now - it.headBlockUpdatedAt <= Tuning.HEAD_BLOCK_MAX_AGE_MS }.map { it.headBlock }.sorted()
        if (recent.size < 2) return 0
        return recent[(recent.size - 1) / 2]
    }

    fun isNodeHealthy(node: String, api: String? = null): Boolean = locked { healthy(node, api, nowMillis()) }

    private fun healthy(node: String, api: String?, now: Long): Boolean {
        val h = health[node] ?: return true
        if (h.rateLimitedUntil > now) return false
        if (h.consecutiveFailures >= Tuning.NODE_FAILURES_BEFORE_UNHEALTHY && now - h.lastFailureTime < Tuning.NODE_FAILURE_WINDOW_MS) return false
        if (api != null && (h.apiFailures[api]?.cooldownUntil ?: 0) > now) return false
        val best = consensusHeadBlock(now)
        if (best > 0 && h.headBlock > 0 && now - h.headBlockUpdatedAt <= Tuning.HEAD_BLOCK_MAX_AGE_MS && best - h.headBlock > Tuning.STALE_BLOCK_THRESHOLD) {
            return false
        }
        return true
    }

    /**
     * [nodes] best first: healthy ones by latency (pool order while unproven, and on ties), then the unhealthy
     * ones in pool order. With [probe], may put one node overdue for a re-probe first (SDK getOrderedNodes);
     * without, only reads (for display).
     */
    fun orderedNodes(nodes: List<String>, api: String? = null, probe: Boolean = true): List<String> = locked {
        val now = nowMillis()
        val (good, bad) = nodes.partition { healthy(it, api, now) }
        if (good.size <= 1) return@locked good + bad
        val ordered = good.withIndex().sortedWith(compareBy<IndexedValue<String>>({ score(it.value, now) }, { it.index })).map { it.value }
        val candidate = if (probe) reprobeCandidate(good, now) else null
        if (candidate != null && ordered.first() != candidate) listOf(candidate) + ordered.filter { it != candidate } + bad else ordered + bad
    }

    private fun isLatencyUsable(h: Health?, now: Long): Boolean =
        h?.ewmaLatencyMs != null && h.latencySampleCount >= Tuning.LATENCY_MIN_SAMPLES && now - h.latencyUpdatedAt <= Tuning.LATENCY_MAX_AGE_MS

    private fun score(node: String, now: Long): Double {
        val h = health[node]
        return if (isLatencyUsable(h, now)) h!!.ewmaLatencyMs!! else Tuning.LATENCY_UNPROVEN_PRIOR_MS
    }

    private fun reprobeCandidate(good: List<String>, now: Long): String? {
        val threshold = now - Tuning.LATENCY_REPROBE_MS
        var candidate: String? = null
        var candidateTouch = Long.MAX_VALUE
        for (n in good) {
            val h = getOrCreate(n, now)
            val touch = maxOf(h.latencyUpdatedAt, h.lastProbeAt)
            if (touch <= threshold && touch < candidateTouch) {
                candidate = n
                candidateTouch = touch
            }
        }
        candidate?.let { getOrCreate(it, now).lastProbeAt = now }
        return candidate
    }

    companion object {
        /** The API of a method name: "rc_api.find_rc_accounts" is "rc_api". */
        fun apiOf(method: String): String = method.indexOf('.').let { if (it > 0) method.substring(0, it) else method }
    }
}

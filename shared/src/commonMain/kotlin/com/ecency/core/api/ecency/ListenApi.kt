package com.ecency.core.api.ecency

import com.ecency.core.api.EcencyHttpException
import com.ecency.core.api.EcencyTimeoutException
import com.ecency.core.api.model.arr
import com.ecency.core.api.model.double
import com.ecency.core.api.model.int
import com.ecency.core.api.model.obj
import com.ecency.core.api.model.str
import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.auth.AuthHttp
import com.ecency.core.auth.AuthHttpRequest
import com.ecency.core.auth.EcencyPrivateApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.cancellation.CancellationException

/** One language of a post as the server analysed it (SDK `ListenLanguage`). */
data class ListenLanguage(val lang: String, val share: Double, val seconds: Double)

/** A ready track (SDK `ListenTrack`): its [url] is signed and lasts about an hour. */
data class ListenTrack(val lang: String, val seconds: Double, val url: String)

/**
 * SDK `ListenStatus`: where a post's natural-voice audio is, for this reader. [status] is the server's word
 * ("none", "analyzing", "preparing", "ready", "failed", "unsupported"); an unknown word is kept as it came and
 * treated like "none". [tracks] come only for a reader who unlocked the post, once the audio is ready.
 */
data class ListenStatus(
    val cost: Double,
    val unlocked: Boolean,
    val status: String,
    val languages: List<ListenLanguage>,
    val tracks: List<ListenTrack> = emptyList(),
    /** While preparing, once the worker holds the job: the share of the text done (0..1). */
    val progress: Double? = null,
    /** While preparing, before the worker holds the job: audio jobs waiting ahead (0 = next). */
    val queuedAhead: Int? = null,
) {
    val preparing: Boolean get() = status == PREPARING || status == ANALYZING

    companion object {
        const val NONE = "none"
        const val ANALYZING = "analyzing"
        const val PREPARING = "preparing"
        const val READY = "ready"
        const val FAILED = "failed"
        const val UNSUPPORTED = "unsupported"

        /** What the bar shows before the server answered (RN's `view` fallback). */
        val EMPTY = ListenStatus(0.0, false, NONE, emptyList())

        /**
         * Reads an answer. Every field is untrusted: a wrong type reads as absent, a track without an https
         * URL or a language code is dropped (the platform player is never handed another scheme).
         */
        fun parse(text: String): ListenStatus? {
            val o = runCatching { Json.parseToJsonElement(text) }.getOrNull().obj() ?: return null
            return ListenStatus(
                cost = o.double("cost")?.takeIf { it.isFinite() && it >= 0 && it < 1e9 } ?: 0.0,
                unlocked = (o["unlocked"] as? JsonPrimitive)?.let { !it.isString && it.content == "true" } == true,
                status = o.str("status")?.takeIf { it.length <= 32 } ?: NONE,
                languages = o["languages"].arr().orEmpty().mapNotNull { e ->
                    val l = e.obj() ?: return@mapNotNull null
                    ListenLanguage(lang(l) ?: return@mapNotNull null, finite(l.double("share")), finite(l.double("seconds")))
                },
                tracks = o["tracks"].arr().orEmpty().mapNotNull { e ->
                    val t = e.obj() ?: return@mapNotNull null
                    val url = t.str("url")?.takeIf { it.startsWith("https://") && it.none { c -> c.isWhitespace() || c.code < 0x20 } }
                        ?: return@mapNotNull null
                    ListenTrack(lang(t) ?: return@mapNotNull null, finite(t.double("seconds")), url)
                },
                progress = o.double("progress")?.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0),
                queuedAhead = o.int("queued_ahead")?.takeIf { it >= 0 },
            )
        }

        private fun lang(o: JsonObject): String? = o.str("lang")?.takeIf { it.isNotEmpty() && it.length <= 16 }

        private fun finite(v: Double?): Double = v?.takeIf { it.isFinite() && it >= 0 } ?: 0.0
    }
}

/** A refused unlock: the HTTP [status] with what a 402 says about the balance (SDK error `status` and `data`). */
class ListenUnlockException(status: Int, val required: Double? = null, val available: Double? = null) :
    EcencyHttpException(status, "listen unlock failed with status $status")

/**
 * The SDK's Listen calls on Ecency's private API (`getListenStatusQueryOptions`, `listenUnlockRequest`). The
 * reader's access token is passed in by the caller ([com.ecency.core.app.ListenModel]), which knows whose it is.
 */
class ListenApi(
    private val http: AuthHttp,
    private val host: String,
    /** The SDK's 2 s between retries; tests pass their own. */
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    /**
     * `POST /private-api/listen-status {code?, author, permlink, updated?}`. Anonymous without [code]. Read only.
     * @throws EcencyHttpException with the status of a refused answer (401: the token expired), 0 on timeout.
     */
    @Throws(Exception::class)
    suspend fun status(author: String, permlink: String, code: String?, updated: String?): ListenStatus {
        val body = buildJsonObject {
            code?.let { put("code", it) }
            put("author", author)
            put("permlink", permlink)
            updated?.takeIf { it.isNotEmpty() }?.let { put("updated", it) }
        }
        val response = send(body)
        if (response.status !in 200..299) throw EcencyHttpException(response.status, "Failed to fetch listen status: ${response.status}")
        return ListenStatus.parse(response.body) ?: throw EcencyHttpException(response.status, "malformed listen status")
    }

    /**
     * SDK `listenUnlockRequest`: `POST /private-api/listen {code, author, permlink, idempotency_key, updated?}`.
     * Spends Points. Only a lost answer (no answer at all, or a gateway status) is sent again, with the SAME
     * key, at most [MAX_RETRIES] times: the server replays a key instead of charging twice.
     * @throws ListenUnlockException for any other refusal (402 not enough Points, 401 the token expired, ...).
     */
    @Throws(Exception::class)
    suspend fun unlock(code: String, author: String, permlink: String, updated: String?, idempotencyKey: String): ListenStatus {
        val body = buildJsonObject {
            put("code", code)
            put("author", author)
            put("permlink", permlink)
            put("idempotency_key", idempotencyKey)
            updated?.takeIf { it.isNotEmpty() }?.let { put("updated", it) }
        }
        var attempt = 0
        while (true) {
            val response = try {
                send(body, "listen")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (attempt < MAX_RETRIES) {
                    attempt++
                    sleep(RETRY_DELAY_MILLIS)
                    continue
                }
                throw e
            }
            if (response.status in 200..299) {
                // an unreadable 200 says nothing about the charge: the caller treats it as a lost answer
                return ListenStatus.parse(response.body) ?: throw EcencyHttpException(502, "malformed listen answer")
            }
            if (response.status in GATEWAY_STATUSES && attempt < MAX_RETRIES) {
                attempt++
                sleep(RETRY_DELAY_MILLIS)
                continue
            }
            val o = runCatching { Json.parseToJsonElement(response.body) }.getOrNull().obj()
            throw ListenUnlockException(
                response.status,
                o?.double("required")?.takeIf { it.isFinite() && it >= 0 },
                o?.double("available")?.takeIf { it.isFinite() && it >= 0 },
            )
        }
    }

    private suspend fun send(body: JsonObject, path: String = "listen-status"): HttpResult =
        withTimeoutOrNull(EcencyPrivateApi.TIMEOUT_MILLIS) {
            http.send(AuthHttpRequest("POST", "$host/private-api/$path", mapOf("Content-Type" to "application/json"), body.toString()))
        } ?: throw EcencyTimeoutException()

    companion object {
        /** Answers that leave the outcome unknown (SDK `GATEWAY_STATUSES`). */
        val GATEWAY_STATUSES = setOf(502, 504, 520, 522, 524)
        const val MAX_RETRIES = 2
        const val RETRY_DELAY_MILLIS = 2_000L
    }
}

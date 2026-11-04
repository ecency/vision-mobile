package com.ecency.core.app

import com.ecency.core.api.model.asDouble
import com.ecency.core.api.model.double
import com.ecency.core.api.model.int
import com.ecency.core.api.model.long
import com.ecency.core.api.model.obj
import com.ecency.core.api.model.str
import com.ecency.core.api.rpc.HttpResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.cancellation.CancellationException

/**
 * One thing the user asked for that costs Ecency points (an AI assist run, an AI image, a transcription).
 * It has ONE idempotency [key], made once, and keeps it until the result is delivered or the user gives it
 * up: every request for this intent, whenever it is sent, carries the same key, so the server answers a
 * repeat from what it already did and never charges twice.
 */
data class PaidIntent(
    val kind: String,
    val owner: String,
    val key: String,
    /** What was asked for, so the very same request can be sent again after the app was killed. */
    val payload: JsonObject,
    val createdMillis: Long,
) {
    fun encode(): String = buildJsonObject {
        put("kind", kind)
        put("owner", owner)
        put("key", key)
        put("payload", payload)
        put("created", createdMillis)
    }.toString()

    /**
     * The server replays a key for 24 hours; past that the same key would be a new, charged request. The age is
     * read off the device's clock, so the margin is wide, and a record that claims to be from the future (the
     * clock was set back since) is not trusted to be young.
     */
    fun isFresh(nowMillis: Long): Boolean = createdMillis <= nowMillis + CLOCK_SKEW_MILLIS && nowMillis - createdMillis <= MAX_AGE_MILLIS

    companion object {
        /** Four hours short of the server's 24 h replay window. */
        const val MAX_AGE_MILLIS = 20L * 60 * 60 * 1000
        const val CLOCK_SKEW_MILLIS = 5L * 60 * 1000

        /** The server's validator: `[A-Za-z0-9_-]{8,64}`. */
        fun isValidKey(key: String): Boolean =
            key.length in 8..64 && key.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' }

        fun decode(text: String?, kind: String, owner: String): PaidIntent? {
            val o = runCatching { Json.parseToJsonElement(text ?: return null) }.getOrNull().obj() ?: return null
            val key = o.str("key")?.takeIf(::isValidKey) ?: return null
            // a record is only ever used for the account and the kind it was written for
            if (o.str("kind") != kind || o.str("owner") != owner) return null
            return PaidIntent(kind, owner, key, o["payload"].obj() ?: return null, o.long("created") ?: 0)
        }
    }
}

/** What the server said about a paid request. */
sealed interface PaidOutcome<out T> {
    data class Delivered<T>(val value: T) : PaidOutcome<T>

    /**
     * HTTP 202 (`delivery_pending`, images) or 409 (`in_progress`): the request with this key was taken, the
     * points are spent or about to be, and the result is not ready. Asking again with the SAME key fetches it.
     */
    data class Pending(val retryAfterSeconds: Int) : PaidOutcome<Nothing>

    /**
     * A definite no. Not charged (400, 402, 429, 503 and the 500s the server names as refunded or unpaid), or
     * charged without a refund (422, content policy). Either way this intent is over.
     */
    data class Refused(val status: Int, val error: String? = null, val required: Double? = null, val available: Double? = null) : PaidOutcome<Nothing>

    /**
     * No answer (timeout, connection lost) or one that says nothing about the charge (a bare 5xx from the edge,
     * an unreadable 200). The points may be spent. Only the same key may be sent again.
     */
    data object Unknown : PaidOutcome<Nothing>
}

object PaidCalls {
    /** 500 answers whose body says what happened to the points: refunded or never taken. */
    private val SETTLED_ERRORS = setOf("generation_failed", "transcription_failed", "server_error", "ai_unavailable")

    /** The refusal the client itself makes when the account that owns the intent is no longer signed in. */
    const val ACCOUNT_CHANGED = -1

    /** Nothing left the device for this request ([PaidNotSent]): it cannot have been charged by it. */
    const val NOT_SENT = -2

    const val DEFAULT_RETRY_SECONDS = 5

    /** How often a pending request is asked for again by itself before the user has to. */
    const val MAX_POLLS = 12

    /** Reads an answer. Every field is untrusted: wrong types are treated as absent. */
    fun <T> classify(response: HttpResult, parse: (JsonElement) -> T?): PaidOutcome<T> {
        val json = runCatching { Json.parseToJsonElement(response.body) }.getOrNull()
        val o = json.obj()
        return when (val status = response.status) {
            200 -> json?.let(parse)?.let { PaidOutcome.Delivered(it) } ?: PaidOutcome.Unknown
            202, 409 -> PaidOutcome.Pending((o?.int("retry_after") ?: DEFAULT_RETRY_SECONDS).coerceIn(2, 60))
            in 400..499 -> PaidOutcome.Refused(status, o?.str("error"), o?.get("required").finite(), o?.get("available").finite())
            in 500..599 -> o?.str("error")?.takeIf { it in SETTLED_ERRORS }?.let { PaidOutcome.Refused(status, it) } ?: PaidOutcome.Unknown
            else -> PaidOutcome.Unknown
        }
    }

    private fun JsonElement?.finite(): Double? = asDouble()?.takeIf { it.isFinite() && it >= 0 }

    /** A cost or balance from the server: a finite number that is not negative, else null. */
    fun amount(o: JsonObject, key: String): Double? = o.double(key)?.takeIf { it.isFinite() && it >= 0 }

    /** RN `parseFloat(String(points).replace(/,/g, ''))`, null when it is not a number. */
    fun points(text: String?): Double? = text?.replace(",", "")?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }

    /**
     * A balance as every paid sheet shows it (AI assist, AI image, dictation): thousands grouped, three decimals,
     * "1,250.500". Costs keep [format]. Cut, never rounded up: it must not show more than the account holds (the
     * small addition only undoes the binary error of a value like 999.999).
     */
    fun balance(amount: Double): String {
        val thousandths = kotlin.math.floor(amount.coerceIn(0.0, 9e14) * 1000 + 1e-4).toLong()
        val whole = (thousandths / 1000).toString().reversed().chunked(3).joinToString(",").reversed()
        return "$whole.${(thousandths % 1000).toString().padStart(3, '0')}"
    }

    /** [balance] of the server's text ("1250.5", "1,250.500"); a text that is not a number is shown as it came. */
    fun balance(text: String): String = points(text)?.let(::balance) ?: text

    /** "5", "1.5": a point amount as RN prints a JS number. */
    fun format(amount: Double): String = if (amount == kotlin.math.floor(amount) && amount < 1e15) amount.toLong().toString() else amount.toString()
}

/**
 * Where unfinished paid intents wait: one per account and kind, in the editor's local store (private files,
 * cleared at logout, never backed up). Written before the first request leaves, removed when the result was
 * delivered or the user gave it up.
 */
class PaidIntents internal constructor(private val env: AppEnvironment) {
    /**
     * One place per account, kind and [scope]: the editor (its draft, reply or edit) the request was made in. Two
     * editors of one account never share one, so neither can overwrite or delete the other's unanswered request.
     */
    private fun slot(kind: String, scope: String) = "paid:$kind:$scope"

    suspend fun load(owner: String, kind: String, scope: String): PaidIntent? {
        val intent = PaidIntent.decode(env.localDrafts.loadRaw(owner, slot(kind, scope)), kind, owner) ?: return null
        if (intent.isFresh(env.nowMillis())) return intent
        // past the server's replay window the key no longer finds the old request: asking again would pay again
        env.localDrafts.delete(owner, slot(kind, scope))
        return null
    }

    /** A new intent with a new key. False when the device refused to store it (it then lives in memory only). */
    suspend fun begin(owner: String, kind: String, scope: String, payload: JsonObject): Pair<PaidIntent, Boolean> {
        val intent = PaidIntent(kind, owner, env.idempotencyKey(), payload, env.nowMillis())
        return intent to env.localDrafts.saveRaw(owner, slot(kind, scope), intent.encode())
    }

    /** Removes [intent]'s record, and only its own: a record with another key is someone else's and stays. */
    suspend fun finish(intent: PaidIntent, scope: String) {
        val stored = PaidIntent.decode(env.localDrafts.loadRaw(intent.owner, slot(intent.kind, scope)), intent.kind, intent.owner)
        if (stored == null || stored.key == intent.key) env.localDrafts.delete(intent.owner, slot(intent.kind, scope))
    }
}

/** Thrown by a paid request's sender when it failed before anything left the device (a file that cannot be read). */
class PaidNotSent(message: String) : Exception(message)

/**
 * Sends a paid request for an intent. Rules that hold for every caller:
 * - one request per call of [ask]: a failure, a timeout or a lost answer is never sent again by itself;
 * - a pending answer (202, 409) is asked for again with the same key, a bounded number of times;
 * - nothing is sent when the account that owns the intent is not the one signed in.
 */
internal class PaidRunner<T>(
    private val env: AppEnvironment,
    private val send: suspend (PaidIntent) -> HttpResult,
    private val parse: (JsonElement) -> T?,
) {
    suspend fun ask(intent: PaidIntent, onPending: () -> Unit = {}): PaidOutcome<T> {
        var polls = 0
        while (true) {
            val outcome = once(intent)
            if (outcome !is PaidOutcome.Pending || polls >= PaidCalls.MAX_POLLS) return outcome
            polls++
            onPending()
            delay(outcome.retryAfterSeconds * 1000L)
        }
    }

    private suspend fun once(intent: PaidIntent): PaidOutcome<T> {
        // the charge belongs to the account that started it: never sent while another one is signed in
        val current = withContext(env.dispatchers.main) { env.session.current.username }
        if (current != intent.owner) return PaidOutcome.Refused(PaidCalls.ACCOUNT_CHANGED)
        val response = try {
            send(intent)
        } catch (e: CancellationException) {
            throw e
        } catch (e: PaidNotSent) {
            // nothing left the device: not "unknown", and nothing this call could have been charged for
            return PaidOutcome.Refused(PaidCalls.NOT_SENT)
        } catch (e: com.ecency.core.auth.AuthException.NotSignedIn) {
            // no session to send with: nothing went out now; an earlier request for this intent may still be fetched
            return PaidOutcome.Refused(401)
        } catch (e: Exception) {
            // timeout or transport error: the request may have arrived
            return PaidOutcome.Unknown
        }
        return PaidCalls.classify(response, parse)
    }
}

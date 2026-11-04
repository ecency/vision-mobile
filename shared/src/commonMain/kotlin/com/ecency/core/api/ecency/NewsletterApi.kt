package com.ecency.core.api.ecency

import com.ecency.core.api.EcencyHttpException
import com.ecency.core.api.model.obj
import com.ecency.core.api.model.str
import com.ecency.core.auth.EcencyPrivateApi
import com.ecency.core.render.js.Js
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One email digest subscription of an account (SDK DigestSubscription). */
data class DigestSubscription(
    val id: String,
    val email: String,
    /** own, community, creator, site or tag. */
    val type: String,
    val target: String,
    /** weekly or monthly. */
    val cadence: String,
    /** active, pending_confirmation, suppressed or ended. */
    val status: String,
)

/** What the relay said to a subscribe (SDK DigestSubscribeResult.status). */
enum class DigestSubscribeStatus { ACTIVE, PENDING_CONFIRMATION, REFUSED }

/**
 * Ecency's newsletter relay as @ecency/sdk calls it (`subscribeDigestRequest`, `getDigestSubscriptionsRequest`,
 * `leaveDigestRequest`): `{host}/api/newsletter/...`, the signed-in account proven by its `code` (no captcha).
 * Every call goes through [EcencyPrivateApi.relay], so it is bounded at 30 s and carries the owner's own code.
 * A status outside 2xx and an answer that is not a JSON object are errors with that status, as in the SDK.
 */
class NewsletterApi(private val api: EcencyPrivateApi) {
    private fun read(status: Int, body: String): JsonObject {
        val json = try {
            Json.parseToJsonElement(body)
        } catch (e: Exception) {
            null
        }
        if (status !in 200..299) throw EcencyHttpException(status, json.obj()?.str("error")?.take(200) ?: "Request failed ($status)")
        return json.obj() ?: throw EcencyHttpException(status, "Unexpected response ($status)")
    }

    /** The account's subscriptions; rows that are not well formed are left out. */
    @Throws(Exception::class)
    suspend fun subscriptions(username: String): List<DigestSubscription> {
        val response = api.relay("GET", "newsletter/subscriptions", username)
        val rows = read(response.status, response.body)["subscriptions"] as? JsonArray ?: return emptyList()
        return rows.take(MAX_ROWS).mapNotNull { row ->
            val o = row.obj() ?: return@mapNotNull null
            DigestSubscription(
                id = o.str("id")?.takeIf { it.isNotEmpty() && it.length <= 200 } ?: return@mapNotNull null,
                email = o.str("email").orEmpty().take(320),
                type = o.str("type") ?: return@mapNotNull null,
                target = o.str("target") ?: return@mapNotNull null,
                cadence = o.str("cadence").orEmpty(),
                status = o.str("status").orEmpty(),
            )
        }
    }

    /** RN _handleSubscribe: `{email, type, target, cadence, source: "mobile-app", code}`. */
    @Throws(Exception::class)
    suspend fun subscribe(username: String, email: String, type: String, target: String, cadence: String): DigestSubscribeStatus {
        val fields = buildJsonObject {
            put("email", email)
            put("type", type)
            put("target", target)
            put("cadence", cadence)
            put("source", SOURCE)
        }
        val response = api.relay("POST", "newsletter/subscribe", username, fields)
        return when (read(response.status, response.body).str("status")) {
            "refused" -> DigestSubscribeStatus.REFUSED
            "pending_confirmation" -> DigestSubscribeStatus.PENDING_CONFIRMATION
            else -> DigestSubscribeStatus.ACTIVE
        }
    }

    @Throws(Exception::class)
    suspend fun leave(username: String, id: String) {
        val response = api.relay("DELETE", "newsletter/subscriptions/${encode(id)}", username)
        read(response.status, response.body)
    }

    /**
     * SDK getNewsletterSenderRequest: `GET /api/newsletter/sender?type=&target=` (the list owner only); the live,
     * mailable subscribers per cadence, or null when the answer has none.
     */
    @Throws(Exception::class)
    suspend fun senderSubscribers(username: String, type: String, target: String): Pair<Int, Int>? {
        val response = api.relay("GET", "newsletter/sender?type=${encode(type)}&target=${encode(target)}", username)
        val subscribers = read(response.status, response.body)["subscribers"].obj() ?: return null
        fun count(k: String) = (subscribers[k] as? JsonPrimitive)?.content?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }?.toInt() ?: 0
        return count("weekly") to count("monthly")
    }

    /** SDK unsubscribeAllDigestsRequest: `POST /api/newsletter/unsubscribe-all {email, code}`, no mail to [email] again. */
    @Throws(Exception::class)
    suspend fun unsubscribeAll(username: String, email: String) {
        val response = api.relay("POST", "newsletter/unsubscribe-all", username, buildJsonObject { put("email", email) })
        read(response.status, response.body)
    }

    companion object {
        /** RN MOBILE_DIGEST_SOURCE: the value the relay's allowlist has for this app. */
        const val SOURCE = "mobile-app"
        const val MAX_ROWS = 500
        private const val UNRESERVED = "-_.!~*'()"

        /** JS encodeURIComponent. */
        fun encode(text: String): String {
            val sb = StringBuilder()
            for (b in Js.utf8(text)) {
                val c = (b.toInt() and 0xff).toChar()
                if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in UNRESERVED) sb.append(c)
                else sb.append('%').append("0123456789ABCDEF"[(b.toInt() shr 4) and 0xf]).append("0123456789ABCDEF"[b.toInt() and 0xf])
            }
            return sb.toString()
        }

        /** RN findDigestSubscription: the row of one list; the target compares without case. */
        fun find(subscriptions: List<DigestSubscription>, type: String, target: String): DigestSubscription? =
            subscriptions.firstOrNull { it.type == type && Js.lower(it.target) == Js.lower(target) }

        /** RN knownDigestAddress: the address the service already holds for the account, from any row. */
        fun knownAddress(subscriptions: List<DigestSubscription>): String? = subscriptions.firstOrNull { it.email.isNotEmpty() }?.email

        /** RN EMAIL_RE `/^\S+@\S+\.\S+$/` over the trimmed input: enough to catch a typo; the relay validates. */
        fun looksLikeEmail(input: String): Boolean {
            val t = Js.trim(input)
            if (t.isEmpty() || t.any { Js.isSpace(it) }) return false
            // some "@" after the first character, and a "." at least two after it and not last
            val at = t.indexOf('@', 1)
            val dot = t.lastIndexOf('.', t.length - 2)
            return at >= 1 && dot >= at + 2
        }
    }
}

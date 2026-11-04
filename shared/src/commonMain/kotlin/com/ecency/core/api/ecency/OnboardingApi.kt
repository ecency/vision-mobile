package com.ecency.core.api.ecency

import com.ecency.core.api.EcencyException
import com.ecency.core.api.EcencyHttpException
import com.ecency.core.api.EcencyTimeoutException
import com.ecency.core.api.json.SafeJson
import com.ecency.core.api.model.arr
import com.ecency.core.api.model.obj
import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.auth.AuthHttp
import com.ecency.core.auth.AuthHttpRequest
import com.ecency.core.auth.EcencyPrivateApi
import com.ecency.core.auth.EcencyTokenApi
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.coroutines.cancellation.CancellationException

/**
 * The signup endpoint refused the request. [status] is the HTTP status (0: no answer); [code] and [serverMessage]
 * are the backend's `code` and `message` when its body carried them (RN reads `data.code`, `data.message`).
 */
class SignupException(val status: Int, val code: String?, val serverMessage: String?, cause: Throwable? = null) :
    EcencyException("signup failed with status $status", cause)

/** One referred account (`/private-api/referrals/<user>`, RN convertReferral). */
data class Referral(
    val id: Long,
    val referredUsername: String,
    val isRewarded: Boolean,
    /** The backend's `created` text as sent; empty when missing. */
    val created: String,
)

/** A page of referrals; [nextMaxId] is the SDK's next page parameter (the last row's numeric id), null at the end. */
data class ReferralPage(val items: List<Referral>, val nextMaxId: Long?)

/** `/private-api/referrals/<user>/stats`. */
data class ReferralStats(val total: Int, val rewarded: Int)

/**
 * One announcement of `/private-api/announcements`. Everything in it is the server's text and is treated as
 * untrusted: the texts are capped single values, the link is only a candidate for the link router.
 */
data class Announcement(
    val id: String,
    val title: String,
    val description: String,
    val buttonText: String,
    val buttonLink: String,
    /** Only for signed-in users. */
    val auth: Boolean,
    /** A Hive proposal prompt (`proposal_ids` not empty): RN skips these on mobile. */
    val isProposal: Boolean,
)

/**
 * One feature spotlight of `/private-api/spotlights` (SDK Spotlight). Untrusted like [Announcement]: capped texts,
 * the link only a candidate for the same safe target rules.
 */
data class Spotlight(
    val id: String,
    val title: String,
    val description: String,
    val buttonText: String,
    val buttonLink: String,
    /** Only for signed-out visitors (RN shows the others to signed-in users only). */
    val guestsOnly: Boolean,
    /** `platforms`; null: every platform. */
    val platforms: List<String>?,
    /** `start` as epoch millis, 0 when absent or unreadable (RN `new Date(start).getTime()`, NaN compared as 0). */
    val startMillis: Long,
    val weight: Double,
    /** `locales[lang]`: the title, description and button text in another language. */
    val locales: Map<String, SpotlightTexts>,
)

data class SpotlightTexts(val title: String, val description: String, val buttonText: String)

/**
 * The Ecency calls of the account gaps, as the RN app and @ecency/sdk make them: signup, referrals,
 * announcements (all without a token, as RN's `ecencyApi` no-token list), the deletion request (with the
 * account's `code`) and the update check's release lookup on GitHub. Every call gives up after 30 s through
 * the transport; none is retried here, and signup and the deletion request must never be.
 */
class OnboardingApi(
    private val http: AuthHttp,
    private val private: EcencyPrivateApi,
    val host: String = EcencyTokenApi.DEFAULT_HOST,
    val releasesUrl: String = RELEASES_URL,
) {
    /**
     * SDK signUp: `POST /private-api/account-create {username, email, referral, captcha_token}`. Returns the
     * 2xx status. Not idempotent: one request per call, no retry.
     * @throws SignupException for any other status or a lost answer.
     */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun signUp(username: String, email: String, referral: String, captchaToken: String): Int {
        val body = buildJsonObject {
            put("username", username)
            put("email", email)
            put("referral", referral)
            put("captcha_token", captchaToken)
        }
        val result = try {
            http.send(AuthHttpRequest("POST", "$host/private-api/account-create", JSON_HEADERS, body.toString()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw SignupException((e as? com.ecency.core.api.EcencyHttpException)?.takeIf { it.isRedirect }?.status ?: 0, null, null, e)
        }
        if (result.status in 200..299) return result.status
        val data = SafeJson.parseOrNull(result.body) as? JsonObject
        throw SignupException(result.status, data?.get("code").scalarText(), data?.get("message").scalarText())
    }

    /** SDK getReferralsInfiniteQueryOptions: `GET /private-api/referrals/<user>[?max_id=]`. */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun referrals(username: String, maxId: Long?): ReferralPage {
        val url = "$host/private-api/referrals/$username" + (maxId?.let { "?max_id=$it" } ?: "")
        val rows = get(url).arr().orEmpty()
        val items = rows.mapNotNull { row ->
            val o = row as? JsonObject ?: return@mapNotNull null
            Referral(
                id = (o["id"] as? JsonPrimitive)?.longOrNull ?: 0,
                referredUsername = o["username"].scalarText().orEmpty(),
                isRewarded = o["rewarded"].truthy(),
                created = o["created"].scalarText().orEmpty(),
            )
        }
        // SDK getNextPageParam: the last row's id when it is a number
        val last = (rows.lastOrNull() as? JsonObject)?.get("id") as? JsonPrimitive
        return ReferralPage(items, last?.takeIf { !it.isString }?.longOrNull)
    }

    /** SDK getReferralsStatsQueryOptions: `GET /private-api/referrals/<user>/stats`; an empty answer is an error. */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun referralStats(username: String): ReferralStats {
        val o = get("$host/private-api/referrals/$username/stats").obj() ?: throw EcencyHttpException(200, "No Referrals for this user!")
        fun count(field: String) = ((o[field] as? JsonPrimitive)?.doubleOrNull ?: 0.0).toInt()
        return ReferralStats(count("total"), count("rewarded"))
    }

    /** SDK getAnnouncementsQueryOptions: `GET /private-api/announcements` (no token). Malformed rows are dropped. */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun announcements(): List<Announcement> = get("$host/private-api/announcements").arr().orEmpty().mapNotNull(::announcement)

    /** SDK getSpotlightsQueryOptions: `GET /private-api/spotlights` (no token, the server filters the date window). */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun spotlights(): List<Spotlight> = get("$host/private-api/spotlights").arr().orEmpty().mapNotNull(::spotlight)

    /**
     * RN deleteAccount: `POST /private-api/request-delete {username, data: "", code}` with [username]'s code.
     * Not idempotent: one request, no retry.
     */
    @Throws(Exception::class)
    suspend fun requestDelete(username: String) {
        val result = private.post("request-delete", username, buildJsonObject { put("username", username); put("data", "") })
        if (result.status !in 200..299) throw EcencyHttpException(result.status, "HTTP ${result.status}")
    }

    /** RN fetchLatestAppVersion: the `tag_name` of the repository's latest GitHub release. */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun latestReleaseTag(url: String = releasesUrl): String =
        (get(url).obj()?.get("tag_name") as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() && it.length <= 64 }
            ?: throw EcencyHttpException(200, "Tag name not available")

    /**
     * The App Store page of [lookupUrl]'s bundle id (Apple's lookup: `results[0].trackViewUrl`), or null when the
     * app is not listed or the answer is not a store page.
     */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun appStorePage(lookupUrl: String): String? =
        ((get(lookupUrl).obj()?.get("results").arr()?.firstOrNull() as? JsonObject)?.get("trackViewUrl") as? JsonPrimitive)
            ?.takeIf { it.isString }?.content?.substringBefore('?')

    private suspend fun get(url: String): JsonElement? {
        val result: HttpResult = try {
            withTimeoutOrNull(EcencyPrivateApi.TIMEOUT_MILLIS) { http.send(AuthHttpRequest("GET", url, JSON_HEADERS)) } ?: throw EcencyTimeoutException()
        } catch (e: CancellationException) {
            throw e
        } catch (e: EcencyException) {
            throw e
        } catch (e: Exception) {
            throw EcencyHttpException.ofSendFailure(e)
        }
        if (result.status !in 200..299) throw EcencyHttpException(result.status, "HTTP ${result.status}")
        if (result.body.isBlank()) return null
        return SafeJson.parseOrNull(result.body)
    }

    companion object {
        /** RN githubApi: the app's releases. */
        const val RELEASES_URL = "https://api.github.com/repos/ecency/vision-mobile/releases/latest"

        const val MAX_TITLE = 200
        const val MAX_DESCRIPTION = 2000
        const val MAX_BUTTON = 60
        const val MAX_LINK = 2048

        private val JSON_HEADERS = mapOf("Content-Type" to "application/json")

        private fun JsonElement?.scalarText(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

        /** JS truthiness of the value RN puts through `!!`. */
        private fun JsonElement?.truthy(): Boolean = when (this) {
            null, is JsonNull -> false
            is JsonPrimitive -> if (isString) content.isNotEmpty() else booleanOrNull ?: ((doubleOrNull ?: 0.0) != 0.0)
            is JsonArray, is JsonObject -> true
        }

        /** Control characters out, whitespace runs kept as single spaces (new lines stay in a description), capped. */
        internal fun clean(text: String?, max: Int, keepLines: Boolean = false): String {
            val t = text.orEmpty().filter { it == '\n' && keepLines || (it >= ' ' && it != '\u007F' && it !in '\u0080'..'\u009F' && it != ' ' && it != ' ' && it !in '‪'..'‮' && it !in '⁦'..'⁩') }
            return t.trim().take(max)
        }

        /** A spotlight row, or null when it has no id, no title or no button (SDK: all three are required). */
        internal fun spotlight(row: JsonElement): Spotlight? {
            val o = row as? JsonObject ?: return null
            val id = clean(o["id"].scalarText(), 64).takeIf { it.isNotEmpty() } ?: return null
            fun copy(c: JsonObject) = SpotlightTexts(
                clean(c["title"].scalarText(), MAX_TITLE),
                clean(c["description"].scalarText(), MAX_DESCRIPTION, keepLines = true),
                clean(c["button_text"].scalarText(), MAX_BUTTON),
            )
            val base = copy(o)
            if (base.title.isEmpty() || base.buttonText.isEmpty()) return null
            val link = o["button_link"].scalarText().orEmpty()
            val locales = (o["locales"] as? JsonObject)?.entries?.take(64)?.mapNotNull { (lang, v) ->
                val c = (v as? JsonObject)?.let(::copy) ?: return@mapNotNull null
                // a translation missing a field keeps the base text for it
                lang.take(16) to SpotlightTexts(c.title.ifEmpty { base.title }, c.description.ifEmpty { base.description }, c.buttonText.ifEmpty { base.buttonText })
            }?.toMap().orEmpty()
            return Spotlight(
                id = id,
                title = base.title,
                description = base.description,
                buttonText = base.buttonText,
                buttonLink = if (link.length <= MAX_LINK && link.none { it < ' ' }) link else "",
                guestsOnly = o["guestsOnly"].truthy(),
                platforms = (o["platforms"] as? JsonArray)?.mapNotNull { it.scalarText() },
                startMillis = o["start"].scalarText()?.let { com.ecency.core.editor.EditorMeta.isoMillis(it) } ?: 0L,
                weight = (o["weight"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
                locales = locales,
            )
        }

        /** An announcement row, or null when it has no id or nothing to show. */
        internal fun announcement(row: JsonElement): Announcement? {
            val o = row as? JsonObject ?: return null
            val id = clean(o["id"].scalarText(), 64).takeIf { it.isNotEmpty() } ?: return null
            val title = clean(o["title"].scalarText(), MAX_TITLE)
            val description = clean(o["description"].scalarText(), MAX_DESCRIPTION, keepLines = true)
            if (title.isEmpty() && description.isEmpty()) return null
            val link = o["button_link"].scalarText().orEmpty()
            return Announcement(
                id = id,
                title = title,
                description = description,
                buttonText = clean(o["button_text"].scalarText(), MAX_BUTTON),
                // an oversized or multi-line link is no link
                buttonLink = if (link.length <= MAX_LINK && link.none { it < ' ' }) link else "",
                auth = o["auth"].truthy(),
                isProposal = (o["proposal_ids"] as? JsonArray)?.isNotEmpty() == true,
            )
        }
    }
}

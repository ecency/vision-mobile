package com.ecency.core.api.ecency

import com.ecency.core.api.EcencyHttpException
import com.ecency.core.api.failure
import com.ecency.core.api.EcencyTimeoutException
import com.ecency.core.api.model.obj
import com.ecency.core.api.model.str
import com.ecency.core.auth.AuthHttp
import com.ecency.core.auth.AuthHttpRequest
import com.ecency.core.auth.EcencyPrivateApi
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlin.coroutines.cancellation.CancellationException

/** A claim's answer: the blog's address and whether this claim made it ([created] false: it was there already). */
data class BlogClaim(val blogUrl: String, val created: Boolean)

/**
 * The free blog that comes with Ecency Pro (#3636), as web's `features/pro/pro-blog-claim.tsx` does it: whether the
 * member's blog exists (the hosting API's public `GET /v1/tenants/{username}`, web `hostingApi.tenant`) and the
 * claim (`POST {host}/api/hosting/claim-blog {code, title?, description?}`, the account's access token as `code`),
 * which Ecency checks against the Pro roster and which returns an existing blog unchanged.
 */
class HostingApi(
    private val http: AuthHttp,
    private val privateApi: EcencyPrivateApi,
    private val hostingHost: String = DEFAULT_HOST,
) {
    /** Without the optional host (Swift sees no default arguments). */
    constructor(http: AuthHttp, privateApi: EcencyPrivateApi) : this(http, privateApi, DEFAULT_HOST)

    /**
     * The address of [username]'s blog, or null when there is none to show: unknown (404) or abandoned (web: a
     * claim revives an abandoned reservation, so it is claimable).
     * @throws EcencyHttpException for any other answer, 0 on timeout.
     */
    @Throws(Exception::class)
    suspend fun blog(username: String): String? {
        val name = username.lowercase()
        val url = "${hostingHost.trimEnd('/')}/v1/tenants/$name"
        val response = withTimeoutOrNull(PROBE_TIMEOUT_MILLIS) {
            try {
                http.send(AuthHttpRequest("GET", url, mapOf("Accept" to "application/json"), null))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw EcencyHttpException.ofSendFailure(e)
            }
        } ?: throw EcencyTimeoutException()
        if (response.status == 404) return null
        if (response.status !in 200..299) throw response.failure("blog lookup failed with status ${response.status}")
        val o = runCatching { Json.parseToJsonElement(response.body) }.getOrNull().obj()
            ?: throw EcencyHttpException(response.status, "malformed blog lookup")
        if (o.str("subscriptionStatus") == ABANDONED) return null
        return safeUrl(o.str("blogUrl")) ?: blogUrl(name)
    }

    /**
     * Claims [username]'s free blog with its [title] and [description] (blank ones are left out, as web leaves them
     * undefined). Sent once, and once more only after a 401 that renewed the session ([EcencyPrivateApi.relay]).
     * @throws EcencyHttpException with the status of a refused answer (403 not a member, 503 or 502 unavailable).
     */
    @Throws(Exception::class)
    suspend fun claim(username: String, title: String, description: String): BlogClaim {
        val fields = buildMap {
            title.trim().takeIf { it.isNotEmpty() }?.let { put("title", JsonPrimitive(it.take(TITLE_MAX))) }
            description.trim().takeIf { it.isNotEmpty() }?.let { put("description", JsonPrimitive(it.take(DESCRIPTION_MAX))) }
        }
        val response = privateApi.relay("POST", CLAIM_PATH, username, JsonObject(fields))
        if (response.status !in 200..299) throw response.failure("blog claim failed with status ${response.status}")
        val o = runCatching { Json.parseToJsonElement(response.body) }.getOrNull().obj()
        val created = (o?.get("created") as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
        val url = safeUrl(o?.get("tenant").obj()?.str("blogUrl")) ?: blogUrl(username)
        // web: only an explicit `created: false` is a blog that was there already
        return BlogClaim(url, created != false)
    }

    companion object {
        /** web `NEXT_PUBLIC_HOSTING_API` default. */
        const val DEFAULT_HOST = "https://api.blogs.ecency.com"
        const val BLOG_DOMAIN = "blogs.ecency.com"
        const val CLAIM_PATH = "hosting/claim-blog"
        const val ABANDONED = "abandoned"

        /** web's prefill caps. */
        const val TITLE_MAX = 100
        const val DESCRIPTION_MAX = 500

        /** web's `settleTimeoutMs`: a probe that never answers degrades to claimable. */
        const val PROBE_TIMEOUT_MILLIS = 10_000L

        /** web's fallback address: `https://{username}.blogs.ecency.com`. */
        fun blogUrl(username: String): String = "https://${username.lowercase()}.$BLOG_DOMAIN"

        /**
         * The server's address, only as an https URL on the blog domain (opened in the browser), without spaces,
         * control characters or backslashes; anything else is the default address. The host may hold only letters,
         * digits, dots and hyphens, so no credentials, port or character a browser reads as the host's end (a
         * backslash is a slash to a browser: `https://example.org\x.blogs.ecency.com` opens example.org).
         */
        fun safeUrl(url: String?): String? {
            val u = url?.trim()?.takeIf {
                it.startsWith("https://") && it.length in 9..512 && it.none { c -> c.isWhitespace() || c.code < 0x20 || c == '\\' }
            } ?: return null
            val host = u.removePrefix("https://").substringBefore('/').substringBefore('?').substringBefore('#').lowercase()
            val plain = host.isNotEmpty() && host.all { it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '-' }
            return u.takeIf { plain && host.endsWith(".$BLOG_DOMAIN") }
        }
    }
}

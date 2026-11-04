package com.ecency.core.auth

import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.api.rpc.platformHttpClient
import com.ecency.core.api.EcencyHttpException
import com.ecency.core.api.EcencyTimeoutException
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.client.plugins.onUpload
import io.ktor.http.content.TextContent
import kotlinx.coroutines.withTimeoutOrNull

/** One HTTP request of the auth layer (token exchange, HiveSigner API). */
data class AuthHttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    /** JSON body, or null for none. */
    val body: String? = null,
    /** A binary body with its [contentType] (a multipart form, an upload chunk); used instead of [body]. */
    val bytes: ByteArray? = null,
    val contentType: String? = null,
    /** A longer bound than the transport's own for this call (an upload), capped at [KtorAuthHttp.MAX_TIMEOUT_MILLIS]. */
    val timeoutMillis: Long? = null,
    /** Called as the body goes out, with the bytes sent so far (an upload's stall watchdog). */
    val onSent: ((Long) -> Unit)? = null,
) {
    constructor(method: String, url: String, headers: Map<String, String>, body: String?) : this(method, url, headers, body, null, null, null, null)

    /** Never prints headers or body: they carry tokens and codes. */
    override fun toString(): String = "AuthHttpRequest($method $url)"
}

/** Sends auth requests. Injectable so tests run against a fake; implementations throw on network failure. */
fun interface AuthHttp {
    @Throws(Exception::class)
    suspend fun send(request: AuthHttpRequest): HttpResult
}

/**
 * [AuthHttp] over Ktor (OkHttp on Android, NSURLSession on iOS). Requests to Ecency's own hosts carry [userAgent],
 * the app's client identity, as the RN app's HTTP client does for Ecency API calls; requests to any other host go
 * out with the platform's default ([ClientIdentity.headers]). The apps pass their build's value.
 *
 * Two rules hold for every caller (token exchange, HiveSigner's API, the private API, search, waves, translation,
 * polls, lists):
 *  - no redirect is followed. Every call here is a direct API URL; a redirect would carry the request's headers
 *    (the user agent among them) and its body to whatever host the answer names. A 3xx is an error
 *    ([EcencyHttpException] with its status).
 *  - no call waits longer than [timeoutMillis] for its answer ([EcencyTimeoutException]). The Android client has
 *    no read, write or call timeout of its own (the Hive RPC client sets its own per attempt), so without this a
 *    stalled connection is a spinner forever. A write that timed out may still have reached the server.
 */
class KtorAuthHttp(
    client: HttpClient,
    private val userAgent: String = ClientIdentity.DEFAULT_USER_AGENT,
    private val timeoutMillis: Long = TIMEOUT_MILLIS,
) : AuthHttp {
    constructor() : this(platformHttpClient())

    constructor(userAgent: String) : this(platformHttpClient(), userAgent)

    private val client = client.config { followRedirects = false }

    @Throws(Exception::class)
    override suspend fun send(request: AuthHttpRequest): HttpResult {
        val bound = request.timeoutMillis?.coerceIn(1, MAX_TIMEOUT_MILLIS) ?: timeoutMillis
        val result = withTimeoutOrNull(bound) {
            val response = client.request(request.url) {
                method = HttpMethod.parse(request.method)
                ClientIdentity.headers(request, userAgent).forEach { (k, v) -> header(k, v) }
                val bytes = request.bytes
                request.onSent?.let { report -> onUpload { sent, _ -> report(sent) } }
                if (bytes != null) {
                    setBody(io.ktor.http.content.ByteArrayContent(bytes, request.contentType?.let(ContentType::parse) ?: ContentType.Application.OctetStream))
                } else {
                    request.body?.let { setBody(TextContent(it, ContentType.Application.Json)) }
                }
            }
            HttpResult(response.status.value, response.bodyAsText(), response.headers.entries().associate { (name, values) -> name.lowercase() to values.firstOrNull().orEmpty() })
        } ?: throw EcencyTimeoutException()
        // the address a redirect names stays out of the message: it is not ours and may carry a token
        if (result.status in 300..399) throw EcencyHttpException(result.status, "Unexpected redirect (${result.status})")
        return result
    }

    companion object {
        /** Every call through this transport gives up after this long. */
        const val TIMEOUT_MILLIS = 30_000L

        /** The longest a single call may ask for ([AuthHttpRequest.timeoutMillis]). */
        const val MAX_TIMEOUT_MILLIS = 10 * 60_000L
    }
}

/** How the app names itself to the first-party APIs. */
object ClientIdentity {
    /** Used when a build configures none (local and test builds). */
    const val DEFAULT_USER_AGENT = "Ecency/${com.ecency.core.EcencyCore.VERSION}"

    /**
     * The only hosts the app names itself to, the first-party hosts: ecency.com and hivesigner.com, each with
     * every true subdomain (the app calls ecency.com, images, i, translate and poll under it, the image and API
     * hosts are build settings that stay under that domain, and sign-in goes through HiveSigner's API), https on
     * the default port only. A look-alike domain is not one. The build's user agent is never sent to anyone
     * else (3Speak, GitHub...), who get the platform's default.
     */
    val FIRST_PARTY_HOSTS: List<String> = listOf("ecency.com", "hivesigner.com")

    fun isFirstPartyHost(url: String): Boolean {
        // Only a plain `https://host[:port]/...` is judged: a backslash, userinfo or a control character is read
        // differently by different HTTP stacks, and the request goes where the stack reads it, not where we do.
        val authority = url.substringAfter("://", "").substringBefore('/').substringBefore('?').substringBefore('#')
        if (authority.isEmpty() || '@' in authority || url.any { it == '\\' || it <= ' ' }) return false
        val parsed = com.ecency.core.render.url.WebUrl.parse(url) ?: return false
        if (parsed.scheme != "https") return false
        // the default port only (the parser drops an explicit 443): nothing first-party is served on another one
        if (parsed.port != null) return false
        val host = parsed.hostname.lowercase()
        // every label has to be there: ".ecency.com" or "a..ecency.com" is not a subdomain of anything
        if (host.split('.').any { it.isEmpty() }) return false
        return FIRST_PARTY_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    /**
     * The headers [request] goes out with: its own, plus the app's user agent when it goes to a first-party host
     * ([isFirstPartyHost]) and does not name one itself.
     */
    fun headers(request: AuthHttpRequest, userAgent: String): Map<String, String> =
        if (!isFirstPartyHost(request.url) || request.headers.keys.any { it.equals("User-Agent", ignoreCase = true) }) request.headers
        else request.headers + ("User-Agent" to userAgent)

    /** The build's value, or [DEFAULT_USER_AGENT] when it is blank or holds anything a header cannot. */
    fun userAgent(configured: String?): String {
        val v = configured?.trim().orEmpty()
        return if (v.isEmpty() || v.length > 200 || v.any { it < ' ' || it > '~' }) DEFAULT_USER_AGENT else v
    }
}

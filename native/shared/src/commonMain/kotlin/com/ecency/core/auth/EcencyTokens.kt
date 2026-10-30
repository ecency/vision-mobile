package com.ecency.core.auth

import kotlinx.coroutines.CancellationException
import com.ecency.core.api.EcencyHttpException
import com.ecency.core.api.EcencyTimeoutException
import com.ecency.core.api.rpc.HttpResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * An account's Ecency API session: the HiveSigner access token Ecency's private API takes as `code`,
 * the refresh token and when the access token expires. Never prints its tokens.
 */
data class TokenSession(
    val username: String,
    val accessToken: String,
    val refreshToken: String?,
    /** Epoch milliseconds; 0 when unknown. */
    val expiresAtMillis: Long,
) {
    fun isExpired(nowMillis: Long): Boolean = expiresAtMillis in 1..<nowMillis

    override fun toString(): String = "TokenSession(username=$username, expiresAtMillis=$expiresAtMillis, tokens=<redacted>)"
}

/**
 * Ecency's token endpoint, as @ecency/sdk `hsTokenRenew` calls it:
 * `POST {host}/auth-api/hs-token-refresh` with `{"code": <code or refresh token>}`, answering
 * `{username, access_token, refresh_token, expires_in}`.
 *
 * One endpoint serves both the first exchange (a HiveSigner code: from the OAuth redirect, a locally
 * signed [HiveSignerMessages.code] for key logins, or a HiveAuth code) and every refresh (the refresh
 * token). Like RN `getSCAccessToken`, a failed call is retried 3 times after 200, 400 and 800 ms, but only
 * for network errors and 5xx answers (RN retries everything).
 */
class EcencyTokenApi(
    private val http: AuthHttp,
    val host: String = DEFAULT_HOST,
    private val retryDelaysMillis: List<Long> = listOf(200, 400, 800),
) {
    /** @throws AuthException.TokenExchangeFailed after the last retry. */
    @Throws(AuthException::class, CancellationException::class)
    suspend fun exchange(code: String, nowMillis: Long): TokenSession {
        var last: AuthException.TokenExchangeFailed? = null
        for (attempt in 0..retryDelaysMillis.size) {
            if (attempt > 0) delay(retryDelaysMillis[attempt - 1])
            try {
                return once(code, nowMillis)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AuthException.TokenExchangeFailed) {
                // Only a network error or a server error may pass on retry; a refusal (4xx) or a bad body will not.
                if (e.status != 0 && e.status < 500) throw e
                // A connection that gave no answer for the transport's whole bound is not tried again: the user
                // has waited long enough, and the sign-in button comes back with an error.
                if (e.cause is EcencyTimeoutException) throw e
                last = e
            }
        }
        throw last!!
    }

    private suspend fun once(code: String, nowMillis: Long): TokenSession {
        val body = buildJsonObject { put("code", code) }.toString()
        val response = try {
            http.send(AuthHttpRequest("POST", "$host$PATH", mapOf("Content-Type" to "application/json"), body))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // a redirect the transport refused is an answer: its status is kept, so it is not retried as a lost connection
            throw AuthException.TokenExchangeFailed((e as? com.ecency.core.api.EcencyHttpException)?.takeIf { it.isRedirect }?.status ?: 0, e)
        }
        if (response.status !in 200..299) throw AuthException.TokenExchangeFailed(response.status)
        val o = runCatching { Json.parseToJsonElement(response.body).jsonObject }.getOrNull()
            ?: throw AuthException.TokenExchangeFailed(response.status)
        val token = o.text("access_token")?.takeIf { it.isNotEmpty() } ?: throw AuthException.TokenExchangeFailed(response.status)
        val username = o.text("username")?.takeIf { it.isNotEmpty() } ?: throw AuthException.TokenExchangeFailed(response.status)
        val expiresIn = runCatching { o["expires_in"]?.jsonPrimitive?.longOrNull }.getOrNull()
        return TokenSession(
            username = username,
            accessToken = token,
            refreshToken = o.text("refresh_token")?.takeIf { it.isNotEmpty() },
            expiresAtMillis = expiresIn?.let { nowMillis + it * 1000 } ?: 0,
        )
    }

    companion object {
        /** @ecency/sdk CONFIG.privateApiHost default; the RN app sets ECENCY_BACKEND_API. */
        const val DEFAULT_HOST = "https://ecency.com"
        const val PATH = "/auth-api/hs-token-refresh"

        private fun JsonObject.text(k: String) = runCatching { get(k)?.jsonPrimitive?.contentOrNull }.getOrNull()
    }
}

/** The Ecency `code` for an account, attached to every private API call. */
fun interface EcencyCodeProvider {
    @Throws(Exception::class)
    suspend fun code(username: String): String?
}

/**
 * Calls Ecency's private API the way @ecency/sdk does: `POST {host}/private-api/<path>` with a JSON body
 * that carries the account's access token as `code`.
 */
class EcencyPrivateApi(
    private val http: AuthHttp,
    private val codes: EcencyCodeProvider,
    val host: String = EcencyTokenApi.DEFAULT_HOST,
) {
    /**
     * Gives up after [TIMEOUT_MILLIS] with an [EcencyHttpException] of status 0 ("timeout"): the shared HTTP
     * client has no read, write or call timeout of its own on Android (and RN sets none), so without this bound a
     * stalled connection would hold a draft save, a schedule or a list forever. A write that timed out may still
     * have reached the server; callers treat it like any other lost answer.
     *
     * @throws AuthException.NotSignedIn when [username] has no session.
     */
    @Throws(Exception::class)
    suspend fun post(path: String, username: String, fields: JsonObject = JsonObject(emptyMap())): HttpResult = post(path, username, fields, TIMEOUT_MILLIS)

    /**
     * As [post] with its own bound: the paid AI calls wait [PAID_TIMEOUT_MILLIS], longer than the gateway's 120 s,
     * so a slow generation is an answer and not "no answer". Everything else keeps [TIMEOUT_MILLIS].
     */
    @Throws(Exception::class)
    suspend fun post(path: String, username: String, fields: JsonObject, timeoutMillis: Long): HttpResult {
        val code = codes.code(username) ?: throw AuthException.NotSignedIn()
        return withTimeoutOrNull(timeoutMillis) {
            http.send(request(path, code, fields).let { if (timeoutMillis == TIMEOUT_MILLIS) it else it.copy(timeoutMillis = timeoutMillis) })
        } ?: throw EcencyTimeoutException()
    }

    /**
     * `POST {host}/private-api/<path>` as a multipart form (the SDK's FormData): [fields], the account's `code`,
     * and one file part. Bounded by [timeoutMillis]; like [post] it is sent once.
     */
    @Throws(Exception::class)
    suspend fun postMultipart(
        path: String,
        username: String,
        fields: Map<String, String>,
        fileField: String,
        fileName: String,
        fileType: String,
        file: ByteArray,
        boundary: String,
        timeoutMillis: Long = UPLOAD_TIMEOUT_MILLIS,
    ): HttpResult {
        val code = codes.code(username) ?: throw AuthException.NotSignedIn()
        val body = Multipart.encode(mapOf("code" to code) + fields, fileField, fileName, fileType, file, boundary)
        return withTimeoutOrNull(timeoutMillis) {
            http.send(
                AuthHttpRequest(
                    "POST", "$host/private-api/${path.trimStart('/')}", emptyMap(), null,
                    bytes = body, contentType = "multipart/form-data; boundary=$boundary", timeoutMillis = timeoutMillis,
                ),
            )
        } ?: throw EcencyTimeoutException()
    }

    /**
     * Ecency's relays next to the private API (`{host}/api/<path>`, the newsletter relay): as the SDK calls them,
     * a POST carries the account's `code` in its JSON body, a GET or DELETE in the `X-HS-Token` header. Bounded
     * like [post] ([TIMEOUT_MILLIS]) and sent once.
     *
     * @throws AuthException.NotSignedIn when [username] has no session.
     */
    @Throws(Exception::class)
    suspend fun relay(method: String, path: String, username: String, fields: JsonObject? = null): HttpResult {
        val code = codes.code(username) ?: throw AuthException.NotSignedIn()
        val url = "$host/api/${path.trimStart('/')}"
        val request = if (method == "POST") {
            AuthHttpRequest("POST", url, mapOf("Content-Type" to "application/json"), JsonObject(fields.orEmpty() + ("code" to JsonPrimitive(code))).toString())
        } else {
            AuthHttpRequest(method, url, mapOf("X-HS-Token" to code), null)
        }
        return withTimeoutOrNull(TIMEOUT_MILLIS) { http.send(request) } ?: throw EcencyTimeoutException()
    }

    /** The request [post] sends, for callers with their own transport. */
    fun request(path: String, code: String, fields: JsonObject): AuthHttpRequest {
        val body = JsonObject(fields + ("code" to JsonPrimitive(code)))
        return AuthHttpRequest("POST", "$host/private-api/${path.trimStart('/')}", mapOf("Content-Type" to "application/json"), body.toString())
    }

    companion object {
        /** Every private API call gives up after this long. */
        const val TIMEOUT_MILLIS = 30_000L

        /** A call that carries a file (an audio clip) and waits for its processing. */
        const val UPLOAD_TIMEOUT_MILLIS = 130_000L

        /** A paid AI call: above the gateway's own 120 s wait for the generation. */
        const val PAID_TIMEOUT_MILLIS = 130_000L
    }
}

/** A `multipart/form-data` body: text fields, then one file. */
object Multipart {
    fun encode(fields: Map<String, String>, fileField: String, fileName: String, fileType: String, file: ByteArray, boundary: String): ByteArray {
        fun clean(s: String) = s.filter { it != '"' && it != '\r' && it != '\n' }
        val head = StringBuilder()
        for ((name, value) in fields) {
            head.append("--").append(boundary).append("\r\n")
            head.append("Content-Disposition: form-data; name=\"").append(clean(name)).append("\"\r\n\r\n")
            head.append(value).append("\r\n")
        }
        head.append("--").append(boundary).append("\r\n")
        head.append("Content-Disposition: form-data; name=\"").append(clean(fileField)).append("\"; filename=\"").append(clean(fileName)).append("\"\r\n")
        head.append("Content-Type: ").append(clean(fileType)).append("\r\n\r\n")
        return head.toString().encodeToByteArray() + file + "\r\n--$boundary--\r\n".encodeToByteArray()
    }
}

package com.ecency.core.auth

import com.ecency.core.render.url.WebUrl
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * HiveSigner client settings. [RN] is what the RN app uses (`constants/hsOptions.ts`): app `ecency.app` and
 * the loopback redirect `http://127.0.0.1:3000/auth`, which the WebView never loads; it only reads the
 * query off the navigation attempt. The native apps may keep it (same WebView pattern) or register their own.
 */
data class HiveSignerConfig(
    val baseUrl: String = "https://hivesigner.com/",
    val clientId: String = HiveSignerMessages.APP,
    val redirectUri: String = "http://127.0.0.1:3000/auth",
    val scope: String = "vote,comment,delete_comment,comment_options,custom_json,claim_reward_balance,account_update,offline",
) {
    init {
        require(baseUrl.endsWith("/")) { "baseUrl must end with /" }
    }

    companion object {
        val RN = HiveSignerConfig()
    }
}

/** What a navigation inside the HiveSigner login page means. */
sealed interface HiveSignerCallback {
    /** Authorization code (RN loginWithSC2 exchanges it at Ecency's token endpoint). */
    data class Code(val code: String) : HiveSignerCallback

    /** Implicit grant: an access token handed back directly. */
    data class AccessToken(val accessToken: String, val username: String?, val expiresInSeconds: Long?) : HiveSignerCallback {
        override fun toString(): String = "AccessToken(username=$username, expiresInSeconds=$expiresInSeconds, token=<redacted>)"
    }

    data class Error(val error: String, val description: String?) : HiveSignerCallback
}

/** The outcome HiveSigner hands back after hot signing (RN `parseHiveSignerSignResult`). */
data class HiveSignerSignResult(val id: String? = null, val blockNum: Long? = null, val trxNum: Long? = null)

/** Pure HiveSigner URL builders and parsers. The WebView or Custom Tab belongs to the apps. */
object HiveSigner {
    /** RN hiveSigner.tsx: `{base}oauth2/authorize?client_id&redirect_uri&response_type=code&scope`. */
    fun authorizeUrl(config: HiveSignerConfig = HiveSignerConfig.RN): String =
        "${config.baseUrl}oauth2/authorize?client_id=${config.clientId}" +
            "&redirect_uri=${UriComponent.encode(config.redirectUri)}&response_type=code&scope=${UriComponent.encode(config.scope)}"

    /**
     * Reads a navigation [url] in the login page. Only a navigation to the configured redirect URI counts
     * (its exact text followed by `?`, `#` or nothing), and parameters are matched by exact name: `code`,
     * else `access_token` (with `username`, `expires_in`), else `error`. RN matched `?code=` anywhere in
     * any URL; this is stricter. Null when the navigation is not a callback.
     */
    fun parseCallback(url: String, config: HiveSignerConfig = HiveSignerConfig.RN): HiveSignerCallback? {
        val params = redirectParams(url, config) ?: return null
        params["code"]?.takeIf { it.isNotEmpty() }?.let { return HiveSignerCallback.Code(it) }
        params["access_token"]?.takeIf { it.isNotEmpty() }?.let {
            return HiveSignerCallback.AccessToken(it, params["username"], params["expires_in"]?.toLongOrNull())
        }
        params["error"]?.let { return HiveSignerCallback.Error(it, params["error_description"]) }
        return null
    }

    /** Query parameters (first value per name) of a navigation to the redirect URI, else null. */
    private fun redirectParams(url: String, config: HiveSignerConfig): Map<String, String>? {
        if (!url.startsWith(config.redirectUri)) return null
        val next = url.getOrNull(config.redirectUri.length)
        if (next != null && next != '?' && next != '#') return null
        val parsed = WebUrl.parse(url) ?: return null
        return parsed.searchParams.groupBy({ it.first }, { it.second }).mapValues { it.value.first() }
    }

    /**
     * The hot-signing page for a hive-uri (RN hiveSignerModal): `{base}sign/...` plus the redirect URI, so
     * HiveSigner hands the transaction id back on the redirect ([parseSignResult]).
     */
    fun hotSignUrl(hiveUri: String, config: HiveSignerConfig = HiveSignerConfig.RN): String {
        require(hiveUri.startsWith(HiveUri.PREFIX)) { "not a hive:// uri" }
        val rest = hiveUri.substring(HiveUri.PREFIX.length)
        return "${config.baseUrl}$rest${if ('?' in hiveUri) "&" else "?"}redirect_uri=${UriComponent.encode(config.redirectUri)}"
    }

    /** Hot signing of operations: [hotSignUrl] of `hive://sign/ops/...` (RN mobilePlatformAdapter). */
    fun hotSignUrl(operations: JsonArray, config: HiveSignerConfig = HiveSignerConfig.RN): String =
        hotSignUrl(HiveUri.encodeOps(operations), config)

    /**
     * RN parseHiveSignerSignResult, with exact matching: the redirect URI carrying `id` (plus `block`,
     * `txn`), or a page on the HiveSigner host itself whose path is exactly `/sign/success` or whose query
     * has `success=true`. Null when [url] says nothing about the outcome.
     */
    fun parseSignResult(url: String?, config: HiveSignerConfig = HiveSignerConfig.RN): HiveSignerSignResult? {
        if (url.isNullOrEmpty()) return null
        redirectParams(url, config)?.let { params ->
            val id = params["id"]?.takeIf { it.isNotEmpty() } ?: return null
            fun positive(k: String) = params[k]?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }?.toLong()
            return HiveSignerSignResult(id, positive("block"), positive("txn"))
        }
        val parsed = WebUrl.parse(url) ?: return null
        val base = WebUrl.parse(config.baseUrl) ?: return null
        if (parsed.protocol != base.protocol || parsed.host != base.host) return null
        if (parsed.pathname == "/sign/success") return HiveSignerSignResult()
        if (parsed.searchParams.any { it.first == "success" && it.second == "true" }) return HiveSignerSignResult()
        return null
    }
}

/**
 * HiveSigner's REST API with an access token, as the `hivesigner` JS client calls it: `POST {api}/api/<route>`
 * with the raw token in `Authorization`. A non-200 status or an `error` member is an error.
 */
class HiveSignerApi(private val http: AuthHttp, private val apiUrl: String = "https://hivesigner.com") {
    /** Posting-authority broadcast (SDK token path). Returns the `result` member. */
    @Throws(Exception::class)
    suspend fun broadcast(accessToken: String, operations: JsonArray): JsonElement? =
        send("broadcast", accessToken, buildJsonObject { put("operations", operations) })["result"]

    /** The token's account (`/api/me`): its `user` name. */
    @Throws(Exception::class)
    suspend fun me(accessToken: String): String? =
        send("me", accessToken, JsonObject(emptyMap()))["user"]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }

    private suspend fun send(route: String, token: String, body: JsonObject): JsonObject {
        val response = http.send(
            AuthHttpRequest(
                "POST",
                "$apiUrl/api/$route",
                mapOf("Accept" to "application/json, text/plain, */*", "Content-Type" to "application/json", "Authorization" to token),
                body.toString(),
            ),
        )
        val json = runCatching { Json.parseToJsonElement(response.body).jsonObject }.getOrNull()
        val error = json?.get("error")
        if (response.status != 200 || json == null || (error != null && error !is kotlinx.serialization.json.JsonNull)) {
            fun text(e: JsonElement?) = runCatching { e?.jsonPrimitive?.contentOrNull }.getOrNull()
            throw AuthException.HiveSignerApi(response.status, text(error), text(json?.get("error_description")))
        }
        return json
    }
}

/** JavaScript `encodeURIComponent`. */
internal object UriComponent {
    private const val UNRESERVED = "-_.!~*'()"

    fun encode(text: String): String = buildString {
        for (b in text.encodeToByteArray()) {
            val c = b.toInt() and 0xff
            val ch = c.toChar()
            if (c < 0x80 && (ch.isLetterOrDigit() || ch in UNRESERVED)) append(ch)
            else append('%').append(HEX[c shr 4]).append(HEX[c and 0xf])
        }
    }

    /** JavaScript `decodeURIComponent`: throws on a malformed escape or invalid UTF-8. */
    fun decode(text: String): String {
        if ('%' !in text) return text
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            if (text[i] != '%') {
                out.append(text[i++])
                continue
            }
            val bytes = mutableListOf<Byte>()
            while (i < text.length && text[i] == '%') {
                val hex = text.substring(i + 1, minOf(i + 3, text.length))
                require(hex.length == 2 && hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) { "malformed URI escape" }
                bytes += hex.toInt(16).toByte()
                i += 3
            }
            out.append(bytes.toByteArray().decodeToString(throwOnInvalidSequence = true))
        }
        return out.toString()
    }

    private const val HEX = "0123456789ABCDEF"
}

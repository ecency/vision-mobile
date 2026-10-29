package com.ecency.core.auth

import com.ecency.core.render.url.WebUrl
import com.ecency.core.secure.KeyVault
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64

/**
 * Sign-in, signing and auth hand-off links, ported from RN `hooks/useLinkProcessor.tsx`,
 * `utils/authRequest.ts`, `utils/parseAuthUrl.ts`, `utils/hive-uri.ts` and the login screen's code check.
 * Parsing only: the app confirms with the user and acts.
 */
sealed interface AuthLink {
    /**
     * `hive://sign/...` or `ecency://sign/...`, normalized to hive:// and decoded. Its callback, if any,
     * passed [AuthLinks.isAcceptableCallback].
     */
    data class HiveSign(val uri: String, val decoded: HiveUri.Decoded) : AuthLink

    /** `ecency://auth-request`: answer another app with a sign-in proof (never a key). */
    data class AuthRequest(val callback: String, val requestId: String?, val username: String?) : AuthLink

    /** `ecency://login`: another app asks for the posting key of [username]. */
    data class PostingKeyRequest(val username: String, val callback: String, val requestId: String?) : AuthLink

    /** `ecency://auth-transfer`: HiveSigner tokens handed over from the web app. */
    data class AuthTransfer(
        val username: String,
        val accessToken: String,
        val refreshToken: String,
        val expiresInSeconds: Long,
    ) : AuthLink {
        override fun toString(): String = "AuthTransfer(username=$username, expiresInSeconds=$expiresInSeconds, tokens=<redacted>)"
    }

    /**
     * `ecency://transfer`. With amount and asset it signs [hiveUri]; without, the app opens the transfer
     * screen for [to] ([hiveUri] null and [invalid] false).
     */
    data class Transfer(
        val to: String,
        val from: String?,
        val asset: String,
        val amount: String,
        val memo: String,
        val callback: String?,
        val requestId: String?,
        val hiveUri: String?,
        /** True when the link has amount and asset but no valid transfer could be built. */
        val invalid: Boolean,
    ) : AuthLink {
        val isRecipientOnly: Boolean get() = !invalid && hiveUri == null
    }

    /** `https://ecency.com/auth?username&code`: passwordless login with a HiveSigner code (web app). */
    data class CodeLogin(val username: String?, val code: String?) : AuthLink

    /** `https://ecency.com/signup?referral&username&email`. */
    data class Signup(val referredUser: String?, val username: String?, val email: String?) : AuthLink

    /**
     * RN parsePurchaseUrl: `https://ecency.com/purchase?type=boost|points|account&username&product_id&email&referral`
     * (the web app's "buy in the app" links and QR codes).
     */
    data class Purchase(val type: String?, val username: String?, val productId: String?, val email: String?, val referral: String?) : AuthLink

    /**
     * A recognised auth link that cannot be used; answer [callback] with an error when there is one. The
     * callback is only kept when it passed [AuthLinks.isAcceptableCallback].
     */
    data class Invalid(val reason: String, val callback: String? = null, val requestId: String? = null) : AuthLink
}

object AuthLinks {
    /**
     * Classifies [url] in RN's order: auth-transfer, auth-request, login, transfer, hive://, then the /auth and
     * /signup web paths. Null when it is not an auth link (the regular link router handles it).
     */
    fun parse(url: String): AuthLink? {
        val parsed = WebUrl.parse(url.trim())
        if (parsed != null && parsed.protocol.lowercase() == "ecency:") {
            when (parsed.hostname.lowercase()) {
                "auth-transfer" -> return authTransfer(parsed)
                "auth-request" -> return parseAuthRequest(url) ?: AuthLink.Invalid("invalid_request")
                "login" -> return postingKeyRequest(parsed)
                "transfer" -> return transfer(parsed)
            }
        }
        if (HiveUri.isHiveUri(url)) return hiveSign(HiveUri.normalize(url))
        if (parsed != null && isEcencyWebUrl(url)) {
            when (parsed.pathname) {
                "/auth" -> return AuthLink.CodeLogin(parsed.param("username"), parsed.param("code"))
                "/signup" -> return AuthLink.Signup(parsed.param("referral"), parsed.param("username"), parsed.param("email"))
                "/purchase" -> return AuthLink.Purchase(
                    parsed.param("type"), parsed.param("username"), parsed.param("product_id"), parsed.param("email"), parsed.param("referral"),
                )
            }
        }
        return null
    }

    /** A Hive account name: lowercase, 3 to 16 characters (RN isHiveAccountName). */
    fun isHiveAccountName(name: String): Boolean = KeyVault.isValidUsername(name)

    /** RN isAuthRequestDeeplink. */
    fun isAuthRequest(url: String): Boolean = WebUrl.parse(url)?.let {
        it.protocol.lowercase() == "ecency:" && it.hostname.lowercase() == "auth-request"
    } ?: false

    /** A third-party app's own scheme name (RFC 3986 scheme characters). */
    private val APP_SCHEME = Regex("^[a-z][a-z0-9+.-]{0,63}$")

    /**
     * Schemes the system, a browser or this app handles itself: never an app's own callback. Covers the web and
     * file schemes, Android (intent, android-app, market, content), iOS (itms*, x-safari-*, x-apple-*, prefs,
     * app-settings, shortcuts), the browsers' own schemes, phone, mail and maps, and Ecency's and Hive's links.
     */
    private val SYSTEM_SCHEMES = setOf(
        "http", "https", "ftp", "ftps", "sftp", "ws", "wss", "file", "data", "blob", "about", "javascript", "vbscript",
        "view-source", "chrome", "resource", "content", "intent", "android-app", "market", "itms", "itms-apps",
        "itms-appss", "itms-services", "itms-books", "itms-bookss", "itmss", "prefs", "app-settings", "app-prefs",
        "shortcuts", "googlechrome", "googlechromes", "firefox", "opera-http", "opera-https", "brave", "edge",
        "microsoft-edge", "duckduckgo", "mailto", "message", "tel", "telprompt", "sms", "smsto", "mms", "mmsto",
        "facetime", "facetime-audio", "callto", "sip", "sips", "geo", "maps", "comgooglemaps", "ecency", "esteem", "hive",
    )
    private val SYSTEM_PREFIXES = listOf("x-safari-", "x-apple-", "ms-", "opera-", "itms")

    /**
     * Allow-list (RN #3579, which only refused a list of schemes and let android-app:, itms-services: or
     * x-safari-https: through): `https://host...`, or a third-party app's own scheme in `name://...` form whose
     * name is not a scheme the system, a browser or this app handles ([SYSTEM_SCHEMES]). Anything else, opaque
     * forms (`name:rest`) included, is refused.
     */
    fun isAcceptableCallback(callback: String): Boolean {
        val u = WebUrl.parse(callback) ?: return false
        val scheme = u.protocol.removeSuffix(":").lowercase()
        val rest = callback.trimStart().drop(scheme.length + 1)
        // exactly two slashes, then the authority (WHATWG reads "https:///x" and "https:\\x" as other hosts)
        if (!rest.startsWith("//") || rest.getOrNull(2) == '/' || rest.getOrNull(2) == '\\') return false
        if (scheme == "https") return u.hostname.isNotEmpty()
        return APP_SCHEME.matches(scheme) && scheme !in SYSTEM_SCHEMES && SYSTEM_PREFIXES.none { scheme.startsWith(it) }
    }

    /** RN callbackAudience: scheme and host of the callback, lowercased. */
    fun callbackAudience(callback: String): String =
        WebUrl.parse(callback)?.let { "${it.protocol}//${it.host}".lowercase() } ?: callback

    /** RN parseAuthRequestDeeplink. */
    fun parseAuthRequest(url: String): AuthLink.AuthRequest? {
        val u = WebUrl.parse(url) ?: return null
        val callback = u.param("callback").orEmptyNull() ?: u.param("redirect_uri").orEmptyNull() ?: u.param("return_url").orEmptyNull()
        if (callback == null || !isAcceptableCallback(callback)) return null
        val username = (u.param("username") ?: "").replace(Regex("^@"), "").trim().lowercase()
        if (username.isNotEmpty() && !isHiveAccountName(username)) return null
        return AuthLink.AuthRequest(callback, u.param("request_id"), username.ifEmpty { null })
    }

    /**
     * `ecency://login?username&callback|redirect_uri|return_url&request_id`. RN answers any callback; the
     * port also requires [isAcceptableCallback], since the success answer carries the posting key.
     */
    private fun postingKeyRequest(u: WebUrl): AuthLink {
        val callback = u.param("callback").orEmptyNull() ?: u.param("redirect_uri").orEmptyNull() ?: u.param("return_url").orEmptyNull()
        val requestId = u.param("request_id")
        val raw = u.param("username").orEmptyNull()
        if (raw == null || callback == null) return AuthLink.Invalid("invalid_params")
        if (!isAcceptableCallback(callback)) return AuthLink.Invalid("invalid_callback")
        val username = raw.replace(Regex("^@"), "").trim().lowercase()
        if (!isHiveAccountName(username)) return AuthLink.Invalid("invalid_params")
        return AuthLink.PostingKeyRequest(username, callback, requestId)
    }

    /** A signing link: decodable, and any `cb=` callback acceptable (else Invalid without a callback). */
    private fun hiveSign(uri: String): AuthLink {
        val decoded = try {
            HiveUri.decode(uri)
        } catch (e: AuthException.InvalidLink) {
            return AuthLink.Invalid("invalid_link")
        }
        val cb = decoded.params.callback
        if (cb != null && !isAcceptableCallback(cb)) return AuthLink.Invalid("invalid_callback")
        return AuthLink.HiveSign(uri, decoded)
    }

    private fun authTransfer(u: WebUrl): AuthLink {
        val username = u.param("username").orEmptyNull()
        val access = u.param("accessToken").orEmptyNull()
        val refresh = u.param("refreshToken").orEmptyNull()
        if (username == null || access == null || refresh == null || !isHiveAccountName(username)) return AuthLink.Invalid("invalid_params")
        val expires = Regex("^\\s*[+-]?\\d+").find(u.param("expiresIn") ?: "86400")?.value?.trim()?.toLongOrNull() ?: 86400
        return AuthLink.AuthTransfer(username, access, refresh, expires)
    }

    private fun transfer(u: WebUrl): AuthLink {
        val requestId = u.param("request_id")
        val rawCallback = u.param("callback").orEmptyNull()
        if (rawCallback != null && !isAcceptableCallback(rawCallback)) return AuthLink.Invalid("invalid_callback", null, requestId)
        val callback = rawCallback
        val to = u.param("to") ?: ""
        if (to.isEmpty()) return AuthLink.Invalid("invalid_params", callback, requestId)
        val from = u.param("from") ?: ""
        val asset = u.param("asset") ?: ""
        val amount = u.param("amount") ?: ""
        val memo = u.param("memo") ?: ""
        if (amount.isEmpty() || asset.isEmpty()) {
            return AuthLink.Transfer(to.replace(Regex("^@"), "").trim(), null, asset.ifEmpty { "HIVE" }, amount, memo, callback, requestId, null, false)
        }
        val uri = transferHiveUri(to, from, asset, amount, memo, callback)
        val normalizedFrom = from.replace(Regex("^@"), "").trim().ifEmpty { null }
        return AuthLink.Transfer(to, normalizedFrom, asset, amount, memo, callback, requestId, uri, uri == null)
    }

    /** RN _buildEcencyTransferHiveUri: a HIVE/HBD transfer or an Ecency Points custom_json. */
    fun transferHiveUri(to: String, from: String, asset: String, amount: String, memo: String, callback: String?): String? {
        val normalizedTo = to.replace(Regex("^@"), "").trim()
        val normalizedFrom = from.replace(Regex("^@"), "").trim()
        val parsed = Regex("^[\\s]*([+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?)").find(amount)?.groupValues?.get(1)?.toDoubleOrNull()
        if (normalizedTo.isEmpty() || parsed == null || parsed <= 0) return null
        val formatted = HiveUriTx.formatAmount(amount.trimStart().substringBefore(' ') + " HIVE")?.substringBefore(' ') ?: return null
        val params = HiveUri.Params(callback = callback)
        val signer = normalizedFrom.ifEmpty { "__signer" }
        return when (val lower = asset.trim().lowercase()) {
            "points" -> HiveUri.encodeOps(
                buildJsonArray {
                    add(
                        buildJsonArray {
                            add(JsonPrimitive("custom_json"))
                            add(
                                buildJsonObject {
                                    put("required_auths", buildJsonArray { add(JsonPrimitive(signer)) })
                                    put("required_posting_auths", JsonArray(emptyList()))
                                    put("id", "ecency_point_transfer")
                                    put(
                                        "json",
                                        buildJsonObject {
                                            put("sender", signer)
                                            put("receiver", normalizedTo)
                                            put("amount", "$formatted POINT")
                                            put("memo", memo)
                                        }.toString(),
                                    )
                                },
                            )
                        },
                    )
                },
                params,
            )
            "hive", "hbd" -> HiveUri.encodeOps(
                buildJsonArray {
                    add(
                        buildJsonArray {
                            add(JsonPrimitive("transfer"))
                            add(
                                buildJsonObject {
                                    put("from", signer)
                                    put("to", normalizedTo)
                                    put("amount", "$formatted ${lower.uppercase()}")
                                    put("memo", memo)
                                },
                            )
                        },
                    )
                },
                params,
            )
            else -> null
        }
    }

    /**
     * RN _openCallback's URL: [payload] entries with a non-empty value and the request id set as query
     * parameters (URLSearchParams.set). Null when [callback] is not a URL.
     */
    fun callbackUrl(callback: String, requestId: String?, payload: List<Pair<String, String>>): String? {
        val u = WebUrl.parse(callback) ?: return null
        payload.forEach { (k, v) -> if (v.isNotEmpty()) u.setSearchParam(k, v) }
        if (!requestId.isNullOrEmpty()) u.setSearchParam("request_id", requestId)
        return u.href
    }

    /** RN _getRequesterLabel: scheme, host and path of the callback, as shown to the user. */
    fun requesterLabel(callback: String): String {
        val u = WebUrl.parse(callback) ?: return callback
        val scheme = if (u.protocol.isNotEmpty()) "${u.protocol}//" else ""
        val path = u.pathname.takeIf { it.isNotEmpty() && it != "/" } ?: ""
        return "$scheme${u.host}$path".ifEmpty { callback }
    }

    /** Why a web login code cannot be used (RN login screen `_confirmCodeLogin`, plus account and clock checks). */
    enum class CodeProblem { MALFORMED, EXPIRED, FUTURE, WRONG_ACCOUNT }

    /**
     * RN _confirmCodeLogin: the code is base64 JSON (read up to its last `}`) whose `timestamp` (seconds)
     * is less than 7 days old. Stricter than RN: its first author must be [username] and the timestamp may
     * not be more than 1 hour ahead of [nowMillis] (phones with a slow clock still sign in). Null when it is usable.
     */
    fun checkLoginCode(code: String, username: String, nowMillis: Long): CodeProblem? {
        val text = runCatching { Base64.Default.decode(code.trim()).decodeToString() }
            .recoverCatching { B64u.decode(code.trim()) }.getOrNull() ?: return CodeProblem.MALFORMED
        val end = text.lastIndexOf('}')
        if (end < 0) return CodeProblem.MALFORMED
        val obj = runCatching { Json.parseToJsonElement(text.substring(0, end + 1)).jsonObject }.getOrNull()
            ?: return CodeProblem.MALFORMED
        val timestamp = runCatching { obj["timestamp"]?.jsonPrimitive?.doubleOrNull }.getOrNull()
        if (timestamp == null || timestamp == 0.0) return CodeProblem.EXPIRED
        if (timestamp * 1000 + CODE_LIFETIME_MILLIS < nowMillis) return CodeProblem.EXPIRED
        if (timestamp * 1000 > nowMillis + CLOCK_SKEW_MILLIS) return CodeProblem.FUTURE
        val author = runCatching { obj["authors"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.content }.getOrNull()
        return if (author != username) CodeProblem.WRONG_ACCOUNT else null
    }

    private const val CODE_LIFETIME_MILLIS = 604_800_000L
    private const val CLOCK_SKEW_MILLIS = 60 * 60_000L

    private val ECENCY_WEB = Regex("^https?://(www\\.)?(ecency\\.com|esteem\\.app|estm\\.to)(/|$)", RegexOption.IGNORE_CASE)

    /**
     * /auth and /signup only on Ecency's own hosts. RN's parseAuthUrl accepts any host; the OS only hands the
     * app verified Ecency links, and the port keeps other hosts out explicitly.
     */
    private fun isEcencyWebUrl(url: String) = ECENCY_WEB.containsMatchIn(url.trim())

    private fun WebUrl.param(name: String): String? = searchParams.firstOrNull { it.first == name }?.second
    private fun String?.orEmptyNull(): String? = this?.takeIf { it.isNotEmpty() }
}

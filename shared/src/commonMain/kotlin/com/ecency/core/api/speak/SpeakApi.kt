package com.ecency.core.api.speak

import com.ecency.core.api.model.obj
import com.ecency.core.api.model.str
import com.ecency.core.auth.AuthHttp
import com.ecency.core.auth.AuthHttpRequest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What Ecency's 3Speak proxy hands out for one upload. */
data class SpeakUploadToken(val token: String, val endpoint: String, val permlink: String?, val embedUrl: String?)

/**
 * RN `providers/speak`: Ecency's proxy routes for 3Speak (`<host>/api/threespeak/...`, with the account's
 * `code`), so 3Speak's API key never leaves the server. The video itself goes straight to 3Speak with the
 * token from here ([TusClient]).
 */
class SpeakApi(private val http: AuthHttp, private val code: suspend (username: String) -> String?, private val host: String) {
    private suspend fun post(path: String, username: String, fields: Map<String, kotlinx.serialization.json.JsonElement>): com.ecency.core.api.rpc.HttpResult {
        val code = code(username) ?: throw TusException(401, "not signed in")
        val body = buildJsonObject {
            fields.forEach { (k, v) -> put(k, v) }
            put("code", code)
        }.toString()
        return withTimeoutOrNull(TIMEOUT_MILLIS) {
            http.send(AuthHttpRequest("POST", "$host/api/threespeak/$path", mapOf("Content-Type" to "application/json"), body))
        } ?: throw TusException(0, "timeout")
    }

    /** RN requestUploadToken: `{owner, isShort, code}` gives `{token, upload_url, permlink, embed_url}`. */
    suspend fun uploadToken(owner: String, isShort: Boolean): SpeakUploadToken {
        val response = post("upload-token", owner, mapOf("owner" to JsonPrimitive(owner), "isShort" to JsonPrimitive(isShort)))
        if (response.status !in 200..299) throw TusException(response.status, "no upload token")
        val o = runCatching { Json.parseToJsonElement(response.body) }.getOrNull().obj() ?: throw TusException(0, "no upload token")
        val token = o.str("token")?.takeIf { it.isNotEmpty() && it.length <= 4096 && it.none { c -> c <= ' ' } } ?: throw TusException(0, "no upload token")
        // RN: the address the proxy names, else 3Speak's default. Either way it must be 3Speak's.
        val endpoint = (o.str("upload_url")?.takeIf { it.isNotEmpty() } ?: "$EMBED_ENDPOINT/uploads").takeIf(::isSpeakHost) ?: throw TusException(0, "upload address refused")
        return SpeakUploadToken(token, endpoint, o.str("permlink")?.takeIf(::isPermlink), o.str("embed_url")?.takeIf(::isEmbedUrl))
    }

    /** RN setVideoThumbnail: `{permlink, thumbnail_url, code}`; failures are not critical. */
    suspend fun setThumbnail(owner: String, permlink: String, thumbnailUrl: String): Boolean = try {
        post("thumbnail", owner, mapOf("permlink" to JsonPrimitive(permlink), "thumbnail_url" to JsonPrimitive(thumbnailUrl))).status in 200..299
    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    /**
     * RN linkVideoToHive: `{permlink, hive_author, hive_permlink, hive_tags, code}` ties an uploaded video to the
     * wave that embeds it, so it shows in 3Speak's own feeds (Shorts). Not critical: the wave is published
     * either way. The same request may be sent again (it names the same video and the same wave). The HTTP
     * status, 0 when no answer came, 401 without a session.
     */
    suspend fun linkHive(owner: String, videoPermlink: String, hivePermlink: String, hiveTags: List<String>): Int = try {
        post(
            "link-hive", owner,
            mapOf(
                "permlink" to JsonPrimitive(videoPermlink), "hive_author" to JsonPrimitive(owner), "hive_permlink" to JsonPrimitive(hivePermlink),
                "hive_tags" to kotlinx.serialization.json.JsonArray(hiveTags.map(::JsonPrimitive)),
            ),
        ).status
    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
        throw e
    } catch (e: TusException) {
        e.status
    } catch (e: Exception) {
        0
    }

    companion object {
        const val TIMEOUT_MILLIS = 30_000L

        /** RN EMBED_ENDPOINT. */
        const val EMBED_ENDPOINT = "https://embed.3speak.tv"

        private fun host(url: String): String? {
            if (!url.startsWith("https://") || url.length > 2048) return null
            if (url.any { it <= ' ' || it in "()[]<>\"\\" || it.code == 0x7f }) return null
            val authority = url.removePrefix("https://").substringBefore('/').substringBefore('?').substringBefore('#')
            if (authority.isEmpty() || '@' in authority || ':' in authority) return null
            return authority.lowercase()
        }

        /** https on 3speak.tv or one of its subdomains, default port. */
        fun isSpeakHost(url: String): Boolean = host(url)?.let { it == "3speak.tv" || it.endsWith(".3speak.tv") } == true

        /** An address that may go into a post body as a 3Speak embed: 3Speak's host, an /embed path, nothing that breaks markdown. */
        fun isEmbedUrl(url: String): Boolean = isSpeakHost(url) && com.ecency.core.editor.EditorMeta.hasThreeSpeakEmbed(url)

        fun isPermlink(text: String): Boolean = text.isNotEmpty() && text.length <= 256 && text.all { it.isLetterOrDigit() || it == '-' || it == '_' }

        /** RN extractPermlink: `?v=owner/permlink`, `@owner/permlink`, else the last path segment. */
        fun permlinkOf(embedUrl: String): String? {
            val v = Regex("[?&]v=([^&]+)").find(embedUrl)?.groupValues?.get(1)?.split('/')?.last()
            if (!v.isNullOrEmpty()) return v.takeIf(::isPermlink)
            Regex("@[^/]+/([a-zA-Z0-9]+)").find(embedUrl)?.groupValues?.get(1)?.let { return it }
            return embedUrl.substringAfterLast('/').substringBefore('?').substringBefore('#').takeIf(::isPermlink)
        }
    }
}

package com.ecency.core.api

import com.ecency.core.api.json.SafeJson
import com.ecency.core.api.model.arr
import com.ecency.core.api.model.obj
import com.ecency.core.api.model.str
import com.ecency.core.auth.AuthHttp
import com.ecency.core.auth.AuthHttpRequest
import com.ecency.core.auth.EcencyPrivateApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.coroutines.cancellation.CancellationException

/** An Ecency HTTP endpoint answered with [status] (0: the request never got an answer). */
open class EcencyHttpException(val status: Int, message: String = "Ecency API error $status", cause: Throwable? = null) : EcencyException(message, cause) {
    val isNetwork: Boolean get() = status == 0 || status >= 500 || status == 429

    /** The transport refused to follow a redirect ([status] 3xx): an answer, not a lost connection. Never retried by itself. */
    val isRedirect: Boolean get() = status in 300..399

    companion object {
        /**
         * What a send that threw is reported as. A redirect the transport refused keeps its status and message
         * (asking again gets the same answer, and "network error" would send the user looking at their
         * connection); anything else never got an answer: status 0.
         */
        fun ofSendFailure(e: Exception): EcencyHttpException =
            if (e is EcencyHttpException && e.isRedirect) e else EcencyHttpException(0, "network error", e)
    }
}

/** No answer within the caller's bound (status 0, message "timeout"). The request may still have reached the server. */
class EcencyTimeoutException : EcencyHttpException(0, "timeout")

/** One version of a post from `/private-api/comment-history`, its body already rebuilt from patches. */
data class HistoryVersion(val version: Int, val title: String, val body: String, val tags: List<String>, val timestamp: String)

/** One "Read next" entry. */
data class SimilarEntry(val author: String, val permlink: String, val title: String, val imageUrl: String?, val created: String)

/** LibreTranslate's answer: the text and, for "auto", the language it detected. */
data class Translation(val text: String, val detectedLanguage: String?)

/**
 * Ecency's own HTTP services the reading features use, with the paths, fields and hosts @ecency/sdk and the
 * RN app use: the public reading endpoints of the private API on ecency.com (calls that carry the account's
 * session `code` are [com.ecency.core.api.ecency.EcencyAccountApi]'s),
 * LibreTranslate on translate.ecency.com and the polls API on poll.ecency.com.
 */
class EcencyRestApi(
    private val http: AuthHttp,
    val host: String = "https://ecency.com",
    val translateHost: String = "https://translate.ecency.com",
    val pollsHost: String = "https://poll.ecency.com",
) {
    // ---- public ----

    /** SDK getBotsQueryOptions: `GET /private-api/public/bots`, account names. */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun bots(): Set<String> = get("$host/private-api/public/bots").arr().orEmpty()
        .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()

    /** SDK getPromotedPostsQuery("feed"): `GET /private-api/promoted-entries`, bridge-shaped entries. */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun promotedEntries(): List<com.ecency.core.api.model.Post> =
        get("$host/private-api/promoted-entries").arr().orEmpty().mapNotNull(com.ecency.core.api.model.Post::fromJson)

    /**
     * SDK getSimilarEntriesQueryOptions: `POST /search-api/similar`, entries filtered as the SDK does (not the post
     * itself, no nsfw tag, one per author, at most 4).
     */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun similarEntries(
        author: String,
        permlink: String,
        title: String,
        body: String,
        tags: List<String>,
        since: String,
    ): List<SimilarEntry> {
        val o = send(
            AuthHttpRequest(
                "POST", "$host/search-api/similar",
                mapOf("Content-Type" to "application/json", "X-Ecency-Client" to "ecency-sdk"),
                buildJsonObject {
                    put("author", author)
                    put("permlink", permlink)
                    put("title", title)
                    put("body", body)
                    put("tags", JsonArray(tags.map { JsonPrimitive(it) }))
                    put("since", since)
                }.toString(),
            ),
        )
        val seen = mutableSetOf<String>()
        val out = mutableListOf<SimilarEntry>()
        for (r in o.list("results")) {
            if (out.size >= 4) break
            val a = r.string("author") ?: continue
            val p = r.string("permlink") ?: continue
            val entryTags = r.list("tags").mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            if (p == permlink || "nsfw" in entryTags || !seen.add(a)) continue
            out += SimilarEntry(a, p, r.string("title") ?: "", r.string("img_url"), r.string("created_at") ?: "")
        }
        return out
    }

    /**
     * SDK getCommentHistoryQueryOptions: the raw version list. Bodies may be diff-match-patch patches; rebuild
     * them with [EditHistory.rebuild] off the main thread (untrusted input, CPU work).
     */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun commentHistory(author: String, permlink: String): List<JsonObject> {
        val o = post("$host/private-api/comment-history", buildJsonObject {
            put("author", author)
            put("permlink", permlink)
            put("onlyMeta", "")
        }).obj() ?: return emptyList()
        return o["list"].arr().orEmpty().mapNotNull { it.obj() }
    }

    /** LibreTranslate `/translate` for one chunk (format text, no key), as RN translateText. */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun translate(text: String, source: String, target: String): Translation {
        val o = post("$translateHost/translate", buildJsonObject {
            put("q", text)
            put("source", source)
            put("target", target)
            put("format", "text")
            put("api_key", "")
        }).obj() ?: throw EcencyHttpException(200, "malformed translation")
        val translated = o.str("translatedText") ?: throw EcencyHttpException(200, "malformed translation")
        return Translation(translated, o["detectedLanguage"].obj()?.str("language"))
    }

    /** LibreTranslate `/detect`: languages ranked by confidence; empty on any failure (RN). */
    @Throws(CancellationException::class)
    suspend fun detect(sample: String): List<String> = try {
        post("$translateHost/detect", buildJsonObject {
            put("q", sample.take(800))
            put("api_key", "")
        }).arr().orEmpty().mapNotNull { it.obj()?.str("language") }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }

    /** SDK getPollQueryOptions: PostgREST `rpc/poll`, first row; null when the post has no poll there. */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun poll(author: String, permlink: String): PollResults? {
        val url = "$pollsHost/rpc/poll?author=eq.${encode(author)}&permlink=eq.${encode(permlink)}"
        val row = get(url).arr()?.firstOrNull()?.obj() ?: return null
        return PollResults.fromJson(row)
    }

    // ---- plumbing ----

    @Throws(EcencyException::class, CancellationException::class)
    suspend fun get(url: String): JsonElement? = send(AuthHttpRequest("GET", url, mapOf("Content-Type" to "application/json")))

    @Throws(EcencyException::class, CancellationException::class)
    suspend fun post(url: String, body: JsonObject): JsonElement? =
        send(AuthHttpRequest("POST", url, mapOf("Content-Type" to "application/json"), body.toString()))

    private suspend fun send(request: AuthHttpRequest): JsonElement? {
        val result = try {
            http.send(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw EcencyHttpException.ofSendFailure(e)
        }
        return parse(result.status, result.body)
    }

    private fun parse(status: Int, body: String): JsonElement? {
        if (status !in 200..299) throw EcencyHttpException(status, "HTTP $status")
        if (body.isBlank()) return null
        return SafeJson.parseOrNull(body)
    }

    companion object {
        /** encodeURIComponent. */
        fun encode(value: String): String = buildString {
            for (b in value.encodeToByteArray()) {
                val c = b.toInt() and 0xff
                val ch = c.toChar()
                if (ch.isLetterOrDigit() && c < 128 || ch in "-_.!~*'()") append(ch)
                else append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 15])
            }
        }

        internal fun JsonElement?.string(key: String): String? = (this.obj()?.get(key) as? JsonPrimitive)?.contentOrNull
        internal fun JsonElement?.int(key: String): Int? = (this.obj()?.get(key) as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() }
        internal fun JsonElement?.long(key: String): Long? = (this.obj()?.get(key) as? JsonPrimitive)?.longOrNull
        internal fun JsonElement?.double(key: String): Double? = (this.obj()?.get(key) as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }
        internal fun JsonElement?.list(key: String): JsonArray = (this.obj()?.get(key) as? JsonArray) ?: JsonArray(emptyList())
    }
}

/**
 * RN useBadActors (SDK getBadActorsQueryOptions): the watchmen list of phishing accounts, one name per line.
 * RN warns (does not block) when a transfer or delegation goes to one of them; the wallet screens use it.
 */
object BadActors {
    const val URL = "https://raw.githubusercontent.com/openhive-network/watchmen/main/output/flat/badactors.txt"

    /** SDK: split on newlines, empty lines dropped (no trimming, as the SDK). */
    fun parse(text: String): Set<String> = text.split('\n').filter { it.isNotEmpty() }.toSet()

    /** RN transfer check: the destination trimmed, lowercased, split on spaces and commas (several recipients). */
    fun flagged(destination: String, actors: Set<String>): Boolean =
        destination.trim().lowercase().split(Regex("[\\s,]+")).any { it.isNotEmpty() && it in actors }

    @Throws(EcencyException::class, CancellationException::class)
    suspend fun load(http: AuthHttp): Set<String> {
        val result = try {
            http.send(AuthHttpRequest("GET", URL))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw EcencyHttpException.ofSendFailure(e)
        }
        if (result.status !in 200..299) throw EcencyHttpException(result.status, "HTTP ${result.status}")
        return parse(result.body)
    }
}

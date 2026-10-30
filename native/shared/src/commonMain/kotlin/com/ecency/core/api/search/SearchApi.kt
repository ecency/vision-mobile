package com.ecency.core.api.search

import com.ecency.core.api.EcencyException
import com.ecency.core.api.json.SafeJson
import com.ecency.core.api.model.arr
import com.ecency.core.api.model.double
import com.ecency.core.api.model.int
import com.ecency.core.api.model.long
import com.ecency.core.api.model.obj
import com.ecency.core.api.model.str
import com.ecency.core.api.model.string
import com.ecency.core.api.model.stringList
import com.ecency.core.auth.AuthHttp
import com.ecency.core.auth.AuthHttpRequest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.cancellation.CancellationException

/** One hit of the search API (SDK `SearchResult`), the fields the RN posts tab shows. */
data class SearchHit(
    val author: String,
    val permlink: String,
    val title: String,
    val category: String,
    /** RN shows `summary || body_marked` with the <mark> tags removed; empty when the API sent neither. */
    val snippet: String,
    /** UTC "YYYY-MM-DDTHH:MM:SS". */
    val created: String,
    /** RN `Math.floor(author_rep || author_reputation)`. */
    val reputation: Int,
    /** RN shows `$ <payout>` when the field is present. */
    val payout: Double?,
    /** RN `up_votes || stats.total_votes`. */
    val votes: Long,
    val children: Long,
    val imageUrl: String,
    val depth: Int,
    val tags: List<String>,
) {
    val key: String get() = "$author/$permlink"

    companion object {
        fun fromJson(e: JsonElement): SearchHit? {
            val o = e.obj() ?: return null
            val author = o.str("author")?.takeIf { it.isNotBlank() } ?: return null
            val permlink = o.str("permlink")?.takeIf { it.isNotBlank() } ?: return null
            val rep = (o.double("author_rep")?.takeIf { it != 0.0 } ?: o.double("author_reputation")) ?: 25.0
            val snippet = (o.str("summary")?.takeIf { it.isNotEmpty() } ?: o.str("body_marked").orEmpty())
                .replace("<mark>", "").replace("</mark>", "")
            return SearchHit(
                author = author,
                permlink = permlink,
                title = o.string("title"),
                category = o.string("category"),
                snippet = snippet,
                created = o.string("created_at").take(19),
                reputation = kotlin.math.floor(rep).toInt(),
                payout = o.double("payout"),
                votes = o.long("up_votes")?.takeIf { it != 0L } ?: (o["stats"].obj()?.long("total_votes") ?: 0L),
                children = o.long("children") ?: 0,
                imageUrl = o.string("img_url"),
                depth = o.int("depth") ?: 0,
                tags = o["tags"].stringList(),
            )
        }
    }
}

/** One page of the search API and the cursor (`scroll_id`) for the next. */
data class SearchPage(val hits: List<SearchHit>, val total: Long, val scrollId: String?)

/** The search API refused or failed. [status] 0 is a transport failure; a 4xx is a rejected query (not retried). */
class SearchApiException(val status: Int, val detail: String?) :
    EcencyException("search failed (${if (status == 0) "network" else status.toString()})${detail?.let { ": $it" } ?: ""}") {
    /** 408 and 429 describe timing, not the query (SDK searchRetryPolicy). */
    val isRejected: Boolean get() = status in 400..499 && status != 408 && status != 429
}

/**
 * Ecency's search API (`POST {host}/search-api/search`) as @ecency/sdk `getSearchApiInfiniteQueryOptions` calls it,
 * with the SDK's client headers. The app's user agent is added by the transport ([com.ecency.core.auth.KtorAuthHttp]).
 */
class SearchApi(
    private val http: AuthHttp,
    private val host: String = DEFAULT_HOST,
) {
    /** One page; [scrollId] from the previous page continues it. [since] is "YYYY-MM-DDTHH:MM:SS". */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun search(q: String, sort: SearchSort, hideLow: Boolean = false, since: String? = null, scrollId: String? = null): SearchPage {
        val body = buildJsonObject {
            put("q", q)
            put("sort", sort.value)
            put("hide_low", hideLow)
            since?.let { put("since", it) }
            scrollId?.let { put("scroll_id", it) }
        }
        val result = try {
            http.send(AuthHttpRequest("POST", "$host/search-api/search", headers(), body.toString()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // a redirect the transport refused keeps its status: it is an answer, not a lost connection
            throw SearchApiException((e as? com.ecency.core.api.EcencyHttpException)?.takeIf { it.isRedirect }?.status ?: 0, e.message)
        }
        val json = SafeJson.parseOrNull(result.body)
        if (result.status !in 200..299) throw SearchApiException(result.status, errorDetail(json, result.body))
        val o = json as? JsonObject
        val results = o?.get("results").arr() ?: throw SearchApiException(result.status, "unexpected response")
        return SearchPage(
            hits = results.mapNotNull(SearchHit::fromJson),
            total = o?.long("hits") ?: 0,
            scrollId = o?.str("scroll_id")?.takeIf { it.isNotEmpty() },
        )
    }

    /** The headers every search call carries (tests check them). */
    fun headers(): Map<String, String> = mapOf(
        "Content-Type" to "application/json",
        "X-Ecency-Client" to CLIENT_ID,
    )

    private fun errorDetail(json: JsonElement?, raw: String): String? =
        (json as? JsonObject)?.let { it.str("error") ?: it.str("message") } ?: raw.take(200).ifBlank { null }

    companion object {
        const val DEFAULT_HOST = "https://ecency.com"

        /** @ecency/sdk `CONFIG.clientId` default; the RN app never changes it. */
        const val CLIENT_ID = "ecency-sdk"
    }
}

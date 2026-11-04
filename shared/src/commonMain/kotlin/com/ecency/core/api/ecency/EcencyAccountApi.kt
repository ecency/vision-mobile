package com.ecency.core.api.ecency

import com.ecency.core.api.EcencyException
import com.ecency.core.api.InvalidInputException
import com.ecency.core.api.model.arr
import com.ecency.core.api.model.bool
import com.ecency.core.api.model.double
import com.ecency.core.api.model.int
import com.ecency.core.api.model.obj
import com.ecency.core.api.model.str
import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.auth.AuthException
import com.ecency.core.render.js.Js
import com.ecency.core.auth.AuthHttp
import com.ecency.core.auth.AuthHttpRequest
import com.ecency.core.auth.EcencyPrivateApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import com.ecency.core.api.EcencyHttpException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.cancellation.CancellationException

/** A post the user bookmarked (`/private-api/bookmarks`). */
data class Bookmark(val id: String, val author: String, val permlink: String, val timestamp: Double = 0.0)

/** An account the user marked as favourite (`/private-api/favorites`). */
data class Favorite(val id: String, val account: String, val timestamp: Double = 0.0)

/** A hashtag the user follows (`/private-api/favorite-tags`). */
data class FavoriteTag(val tag: String, val id: String = "", val timestamp: Double = 0.0)

/** One page of a `format=wrapped` list. [nextOffset] is null at the end. */
data class WrappedPage<T>(val items: List<T>, val nextOffset: Int?)

/**
 * The Ecency private API calls of the profile area, as @ecency/sdk makes them for the RN app: bookmarks,
 * favourite accounts, followed tags (paged with `format=wrapped`), report and the Pro member list. Calls for
 * an account carry its `code` ([EcencyPrivateApi]); the Pro list is public.
 */
class EcencyAccountApi(
    private val private: EcencyPrivateApi,
    private val http: AuthHttp,
    private val host: String = private.host,
    private val nowMillis: () -> Long,
) {
    // ---- bookmarks ----

    @Throws(EcencyException::class, CancellationException::class)
    suspend fun bookmarks(username: String, offset: Int, limit: Int = PAGE_SIZE): WrappedPage<Bookmark> =
        wrapped(call(username, "bookmarks?format=wrapped&offset=$offset&limit=$limit"), limit) { o ->
            val author = o.str("author") ?: return@wrapped null
            val permlink = o.str("permlink") ?: return@wrapped null
            Bookmark(o.str("_id").orEmpty(), author, permlink, o.double("timestamp") ?: 0.0)
        }

    @Throws(EcencyException::class, CancellationException::class)
    suspend fun addBookmark(username: String, author: String, permlink: String) {
        call(username, "bookmarks-add", buildJsonObject { put("author", author); put("permlink", permlink) })
    }

    @Throws(EcencyException::class, CancellationException::class)
    suspend fun deleteBookmark(username: String, id: String) {
        call(username, "bookmarks-delete", buildJsonObject { put("id", id) })
    }

    // ---- favourite accounts ----

    @Throws(EcencyException::class, CancellationException::class)
    suspend fun favorites(username: String, offset: Int, limit: Int = PAGE_SIZE): WrappedPage<Favorite> =
        wrapped(call(username, "favorites?format=wrapped&offset=$offset&limit=$limit"), limit) { o ->
            val account = o.str("account") ?: return@wrapped null
            Favorite(o.str("_id").orEmpty(), account, o.double("timestamp") ?: 0.0)
        }

    /** `favorites-check`: whether [username] marked [account] as favourite. The answer must be a boolean. */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun isFavorite(username: String, account: String): Boolean {
        val result = call(username, "favorites-check", buildJsonObject { put("account", account) })
        val p = result as? JsonPrimitive
        if (p == null || p.isString) throw EcencyHttpException(200, "favorites-check returned no boolean")
        return when (p.content) {
            "true" -> true
            "false" -> false
            else -> throw EcencyHttpException(200, "favorites-check returned no boolean")
        }
    }

    @Throws(EcencyException::class, CancellationException::class)
    suspend fun addFavorite(username: String, account: String) {
        call(username, "favorites-add", buildJsonObject { put("account", account) })
    }

    @Throws(EcencyException::class, CancellationException::class)
    suspend fun deleteFavorite(username: String, account: String) {
        call(username, "favorites-delete", buildJsonObject { put("account", account) })
    }

    // ---- followed tags ----

    @Throws(EcencyException::class, CancellationException::class)
    suspend fun favoriteTags(username: String, offset: Int, limit: Int = TAGS_PAGE_SIZE): WrappedPage<FavoriteTag> =
        wrapped(call(username, "favorite-tags?format=wrapped&offset=$offset&limit=$limit"), limit) { o ->
            val tag = o.str("tag") ?: return@wrapped null
            FavoriteTag(tag, o.str("_id").orEmpty(), o.double("timestamp") ?: 0.0)
        }

    @Throws(EcencyException::class, CancellationException::class)
    suspend fun addFavoriteTag(username: String, tag: String) = tagRequest("favorite-tags-add", username, tag)

    @Throws(EcencyException::class, CancellationException::class)
    suspend fun deleteFavoriteTag(username: String, tag: String) = tagRequest("favorite-tags-delete", username, tag)

    /** `favorite-tags-check` {code, tag}: whether [username] follows [tag] (the tag screen's follow button). */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun isFavoriteTag(username: String, tag: String): Boolean {
        val normalized = normalizeTag(tag) ?: throw InvalidInputException("invalid tag")
        val p = call(username, "favorite-tags-check", buildJsonObject { put("tag", normalized) }) as? JsonPrimitive
        return p != null && !p.isString && p.content == "true"
    }

    private suspend fun tagRequest(route: String, username: String, tag: String) {
        val normalized = normalizeTag(tag) ?: throw InvalidInputException("invalid tag")
        call(username, route, buildJsonObject { put("tag", normalized) })
    }

    // ---- report, Pro ----

    /** RN addReport("account", author, reporter): `/private-api/report` with the reporter's code. */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun reportAccount(reporter: String, account: String) {
        call(reporter, "report", buildJsonObject { put("type", "account"); put("author", account); put("reporter", reporter) })
    }

    /** RN addReport("post", author, reporter, permlink), with the reporter's code like every private API call of RN. */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun reportPost(reporter: String, author: String, permlink: String) {
        call(reporter, "report", buildJsonObject { put("type", "post"); put("author", author); put("reporter", reporter); put("permlink", permlink) })
    }

    private val proLock = Mutex()
    private var pro: Pair<Long, Set<String>>? = null

    /**
     * Lowercased usernames of Ecency Pro members (public `GET /private-api/pro-members`), cached 5 minutes; [fresh]
     * skips this cache (after a Pro purchase; the server's own edge cache may still answer the earlier list).
     */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun proMembers(fresh: Boolean = false): Set<String> = proLock.withLock {
        if (!fresh) pro?.takeIf { nowMillis() - it.first < PRO_TTL_MILLIS }?.let { return@withLock it.second }
        val response = send(AuthHttpRequest("GET", "$host/private-api/pro-members", mapOf("Content-Type" to "application/json")))
        val members = parse(response).obj()?.get("members").arr().orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.lowercase() }.toSet()
        pro = nowMillis() to members
        members
    }

    // ---- plumbing ----

    private suspend fun call(username: String, path: String, fields: JsonObject = JsonObject(emptyMap())): JsonElement? {
        val response = try {
            private.post(path, username, fields)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AuthException) {
            throw e
        } catch (e: EcencyException) {
            throw e
        } catch (e: Exception) {
            throw EcencyHttpException.ofSendFailure(e)
        }
        return parse(response)
    }

    private suspend fun send(request: AuthHttpRequest): HttpResult = try {
        http.send(request)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw EcencyHttpException.ofSendFailure(e)
    }

    private fun parse(response: HttpResult): JsonElement? {
        if (response.status !in 200..299) throw EcencyHttpException(response.status)
        return runCatching { Json.parseToJsonElement(response.body) }.getOrNull()
    }

    /** SDK normalizeToWrappedResponse: a wrapped page, or an old-style plain array (then there is no next page). */
    private fun <T> wrapped(result: JsonElement?, limit: Int, item: (JsonObject) -> T?): WrappedPage<T> {
        val o = result as? JsonObject
        val data = o?.get("data")
        if (o != null && data is kotlinx.serialization.json.JsonArray && o.containsKey("pagination")) {
            val p = o["pagination"].obj()
            val next = if (p?.bool("has_next") == true) (p.int("offset") ?: 0) + (p.int("limit") ?: limit) else null
            return WrappedPage(data.mapNotNull { it.obj()?.let(item) }, next)
        }
        return WrappedPage(result.arr().orEmpty().mapNotNull { it.obj()?.let(item) }, null)
    }

    companion object {
        /** RN useGetBookmarksQuery / useGetFavouritesQuery page size. */
        const val PAGE_SIZE = 20

        /** RN FOLLOWED_TAGS_PAGE_SIZE: the server's cap, so one page is the whole list. */
        const val TAGS_PAGE_SIZE = 100

        const val PRO_TTL_MILLIS = 5 * 60 * 1000L

        private val TAG_PATTERN = Regex("^[a-z0-9-]{1,32}$")
        private val COMMUNITY_PATTERN = Regex("^hive-\\d+$")

        /** SDK normalizeTag: trimmed, lowercased, one leading # dropped; null for invalid tags and communities. */
        fun normalizeTag(raw: String): String? {
            var tag = Js.toLowerCase(Js.trim(raw))
            if (tag.startsWith("#")) tag = tag.substring(1)
            if (!TAG_PATTERN.matches(tag) || COMMUNITY_PATTERN.matches(tag)) return null
            return tag
        }
    }
}

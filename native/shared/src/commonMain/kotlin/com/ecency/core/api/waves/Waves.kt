package com.ecency.core.api.waves

import com.ecency.core.api.EcencyException
import com.ecency.core.api.EcencyHttpException
import com.ecency.core.api.json.SafeJson
import com.ecency.core.api.model.Post
import com.ecency.core.api.model.arr
import com.ecency.core.api.model.obj
import com.ecency.core.api.model.str
import com.ecency.core.auth.AuthHttp
import com.ecency.core.auth.AuthHttpRequest
import com.ecency.core.l10n.L
import com.ecency.core.render.blocks.SafeTarget
import com.ecency.core.render.js.Js
import com.ecency.core.render.url.WebUrl
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.coroutines.cancellation.CancellationException

/** RN `src/constants/waves.ts`: the accounts whose root posts are waves containers, and the picker's sources. */
object Waves {
    /** Hosts new waves are posted into, in priority order: the first one with a live container wins. */
    const val PRIMARY_HOST = "hive.flow"
    const val FALLBACK_HOST = "ecency.waves"
    val HOSTS: List<String> = listOf(PRIMARY_HOST, FALLBACK_HOST)

    /** Every container account across the integrated apps: a comment under one of them is a wave. */
    val CONTAINER_HOSTS: List<String> = listOf(PRIMARY_HOST, FALLBACK_HOST, "leothreads", "peak.snaps", "liketu.moments", "liketu.speak")

    /** The cross-container reels feed, pinned like a source but backed by `waves/shorts`. */
    const val SHORTS_SOURCE = "shorts"

    data class Source(val host: String, val label: String)

    /** The sources the picker offers, in order (`hive.flow` is deliberately not offered). */
    val SOURCE_OPTIONS: List<Source> get() = listOf(
        Source(SHORTS_SOURCE, L.native.waves.shorts),
        Source("ecency.waves", L.profile.waves),
        Source("leothreads", "Threads"),
        Source("peak.snaps", "Snaps"),
        Source("liketu.moments", "Moments"),
        Source("liketu.speak", "Speaks"),
    )

    fun isWavesHost(account: String?): Boolean = !account.isNullOrEmpty() && account in CONTAINER_HOSTS

    fun sourceLabel(host: String): String = SOURCE_OPTIONS.firstOrNull { it.host == host }?.label ?: host

    /** RN wavesTagPickerModal normalizeTag: trimmed, lowercased, leading `#` dropped, only `a-z0-9-` kept. */
    fun normalizeTag(raw: String?): String {
        val lowered = Js.toLowerCase(Js.trim(raw.orEmpty())).trimStart('#')
        return lowered.filter { it in 'a'..'z' || it in '0'..'9' || it == '-' }
    }

    /** The composer's limit on the typed text (RN MAX_BODY_LENGTH, UTF-16 units as `String.length`). */
    const val MAX_BODY_LENGTH = 250

    /** RN WAVE_COMPOSE_TEXT_MAX: the bound on a share link's text, in code points. */
    const val COMPOSE_TEXT_MAX = 2000

    private val COMPOSE_HOSTS = setOf("ecency.com", "www.ecency.com", "esteem.app", "www.esteem.app", "estm.to", "www.estm.to")

    /**
     * RN parseWavesComposeUrl: the Waves tab's own link (`ecency.com/waves`, any query). Returns the `text` to
     * open the composer with (trimmed, cut to [COMPOSE_TEXT_MAX] code points, empty for a plain link), or null
     * when [url] is not that link.
     */
    fun composeText(url: String): String? {
        if (url.isEmpty()) return null
        val rewritten = Regex("^(ecency|esteem)://", RegexOption.IGNORE_CASE).replaceFirst(url, "https://ecency.com/")
        val parsed = WebUrl.parse(rewritten) ?: return null
        if (parsed.scheme != "http" && parsed.scheme != "https") return null
        if (parsed.port != null || parsed.hostname.lowercase() !in COMPOSE_HOSTS) return null
        val path = parsed.pathname.lowercase()
        if (path != "/waves" && path != "/waves/") return null
        val text = Js.trim(parsed.searchParams.firstOrNull { it.first == "text" }?.second.orEmpty())
        return takeCodePoints(text, COMPOSE_TEXT_MAX)
    }

    /** The first [count] code points of [text] (`Array.from(text).slice(0, count).join('')`). */
    internal fun takeCodePoints(text: String, count: Int): String {
        var units = 0
        var points = 0
        while (units < text.length && points < count) {
            units += if (text[units].isHighSurrogate() && units + 1 < text.length && text[units + 1].isLowSurrogate()) 2 else 1
            points++
        }
        return text.substring(0, units)
    }

    /** `Array.from(text).length`. */
    internal fun codePointCount(text: String): Int {
        var units = 0
        var points = 0
        while (units < text.length) {
            units += if (text[units].isHighSurrogate() && units + 1 < text.length && text[units + 1].isLowSurrogate()) 2 else 1
            points++
        }
        return points
    }
}

/** SDK WavesFeedParams, normalized as the SDK does ([normalized]). */
data class WavesFeedParams(
    /** One or more container accounts; empty for the full combined feed. */
    val containers: List<String> = emptyList(),
    val tag: String? = null,
    /** Only waves from accounts this user follows. */
    val following: String? = null,
    /** Only this author's waves (the profile tab). */
    val author: String? = null,
    /** The viewer: the backend drops the authors they mute. */
    val observer: String? = null,
    val limit: Int = DEFAULT_LIMIT,
) {
    /** SDK normalizeParams: tag trimmed, names trimmed and lowercased, blanks dropped. */
    fun normalized(): WavesFeedParams = copy(
        tag = tag?.let(Js::trim)?.takeIf { it.isNotEmpty() },
        following = following?.let(Js::trim)?.let(Js::toLowerCase)?.takeIf { it.isNotEmpty() },
        author = author?.let(Js::trim)?.let(Js::toLowerCase)?.takeIf { it.isNotEmpty() },
        observer = observer?.let(Js::trim)?.let(Js::toLowerCase)?.takeIf { it.isNotEmpty() },
    )

    companion object {
        const val DEFAULT_LIMIT = 20
    }
}

/** The 3Speak video a short embeds (SDK ShortVideo). */
data class ShortVideo(
    val platform: String,
    val author: String,
    val permlink: String,
    val embedUrl: String,
    val thumbnailUrl: String?,
    val durationSecs: Int?,
)

/**
 * One row of the waves or shorts feed, normalized as SDK normalizeWaveEntryFromApi: `created` falls back to the
 * row's `timestamp`, payout strings to the Hive defaults, `host` to "". The feed rows are lightweight: no
 * `active_votes`, `parent_author` or reputation, a `net_votes` count instead.
 */
data class WaveEntry(
    val post: Post,
    /** The container account the wave lives under ("" when the row does not say). */
    val host: String,
    /** Opaque keyset cursor pointing just past this row. */
    val cursor: String?,
    /** `created` as the SDK leaves it (the row's `created`, else its `timestamp`). */
    val rawCreated: String?,
    val netVotes: Int?,
    val tipCount: Int,
    /** The row carried `active_votes` (promoted entries do; feed rows do not). */
    val hasActiveVotes: Boolean,
    val hasReputation: Boolean,
    val video: ShortVideo? = null,
    /**
     * The waves API flagged the wave as explicit (`nsfw: true`: its image check found an explicit picture, or the
     * author tagged it nsfw). Only a JSON `true` counts; a missing or odd flag is not a verdict.
     */
    val flaggedNsfw: Boolean = false,
) {
    companion object {
        fun fromJson(element: JsonElement?): WaveEntry? {
            val o = element.obj() ?: return null
            val rawCreated = o.str("created") ?: o.str("timestamp")
            val created = rawCreated?.let(::chainTime).orEmpty()
            val post = Post.fromJson(o) ?: return null
            val host = o.str("host").orEmpty()
            val video = o["video"].obj()?.let { v ->
                val author = v.str("author") ?: return@let null
                val permlink = v.str("permlink") ?: return@let null
                ShortVideo(
                    platform = v.str("platform").orEmpty(),
                    author = author,
                    permlink = permlink,
                    embedUrl = v.str("embed_url").orEmpty(),
                    thumbnailUrl = v.str("thumbnail_url"),
                    durationSecs = (v["duration_secs"] as? JsonPrimitive)?.intOrNull,
                )
            }
            return WaveEntry(
                // a feed row has no parent: its container account is the host, which is what makes it a wave
                post = post.copy(
                    created = created,
                    updated = post.updated.ifEmpty { created },
                    parentAuthor = post.parentAuthor ?: host.takeIf { it.isNotEmpty() },
                    depth = if (post.depth == 0) 1 else post.depth,
                ),
                host = host,
                cursor = o.str("_cursor"),
                rawCreated = rawCreated,
                netVotes = (o["net_votes"] as? JsonPrimitive)?.intOrNull,
                tipCount = (o["tip_count"] as? JsonPrimitive)?.intOrNull ?: 0,
                hasActiveVotes = o["active_votes"].arr() != null,
                hasReputation = o["author_reputation"] is JsonPrimitive,
                video = video,
                flaggedNsfw = (o["nsfw"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == true,
            )
        }

        private val ISO = Regex("""^(\d{4}-\d{2}-\d{2})[T ](\d{2}:\d{2}:\d{2})(?:\.\d+)?(Z|[+-]\d{2}:?\d{2})?$""")

        /**
         * The feed's ISO timestamp ("2026-10-12T16:40:57+00:00") as chain time ("2026-10-12T16:40:57", UTC). A
         * non-zero offset is applied; anything unreadable is "".
         */
        fun chainTime(value: String): String {
            val m = ISO.matchEntire(value.trim()) ?: return ""
            val local = "${m.groupValues[1]}T${m.groupValues[2]}"
            val zone = m.groupValues[3]
            if (zone.isEmpty() || zone == "Z") return local
            val sign = if (zone[0] == '-') -1 else 1
            val digits = zone.drop(1).replace(":", "")
            val offset = sign * (digits.take(2).toInt() * 3600 + digits.drop(2).toInt() * 60)
            if (offset == 0) return local
            return try {
                com.ecency.core.hive.ChainTime.fromEpochSeconds(com.ecency.core.hive.ChainTime.toEpochSeconds(local) - offset)
            } catch (e: Exception) {
                ""
            }
        }
    }
}

/** One tag of `waves/trending/tags`. */
data class WaveTrendingTag(val tag: String, val posts: Int)

/**
 * The waves endpoints of Ecency's API as @ecency/sdk calls them: the combined cross-container feed and its
 * shorts variant (keyset paging), promoted waves and trending tags. All public GETs; the URLs are built the
 * way `URL.searchParams` builds them and are pinned by golden vectors from the SDK.
 */
class WavesApi(private val http: AuthHttp, val host: String = "https://ecency.com") {

    /** SDK getWavesFeedQueryOptions: one page of `GET /private-api/waves/feed`. */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun feed(params: WavesFeedParams, cursor: String? = null): List<WaveEntry> =
        rows(get(feedUrl(host, params, cursor), "Failed to fetch waves feed"))

    /** SDK getShortsFeedQueryOptions: one page of `GET /private-api/waves/shorts` (no `following` filter). */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun shorts(params: WavesFeedParams, cursor: String? = null): List<WaveEntry> =
        rows(get(shortsUrl(host, params, cursor), "Failed to fetch shorts feed"))

    /** SDK getPromotedPostsQuery("waves"): bridge-shaped entries with votes and reputation. */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun promoted(): List<WaveEntry> = rows(get(promotedUrl(host), "Failed to fetch promoted waves"))

    /** SDK getWavesTrendingTagsQueryOptions; like the SDK, any failure is an empty list. */
    @Throws(CancellationException::class)
    suspend fun trendingTags(container: String? = null, hours: Int = 24): List<WaveTrendingTag> = try {
        get(trendingTagsUrl(host, container, hours), "Failed to fetch waves trending tags").arr().orEmpty().mapNotNull {
            val o = it.obj() ?: return@mapNotNull null
            WaveTrendingTag(o.str("tag") ?: return@mapNotNull null, (o["posts"] as? JsonPrimitive)?.intOrNull ?: 0)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }

    /**
     * RN resolveThreeSpeakHls: the HLS stream of a 3Speak video from its embed API (`videoUrl`, then the first
     * two fallbacks), or null when it cannot be resolved. Only an https URL without credentials is returned.
     */
    @Throws(CancellationException::class)
    suspend fun threeSpeakStream(author: String, permlink: String): String? = try {
        val o = get(threeSpeakEmbedApi(author, permlink), "3Speak embed").obj()
        listOf("videoUrl", "videoUrlFallback1", "videoUrlFallback2")
            .firstNotNullOfOrNull { key -> o?.get(key)?.let { it as? JsonPrimitive }?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotEmpty() } }
            ?.let(::safeStream)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private fun rows(element: JsonElement?): List<WaveEntry> = element.arr().orEmpty().mapNotNull(WaveEntry::fromJson)

    private suspend fun get(url: String, what: String): JsonElement? {
        val result = try {
            http.send(AuthHttpRequest("GET", url))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw EcencyHttpException.ofSendFailure(e)
        }
        if (result.status !in 200..299) throw EcencyHttpException(result.status, "$what: ${result.status}")
        if (result.body.isBlank()) return null
        return SafeJson.parseOrNull(result.body)
    }

    companion object {
        fun feedUrl(host: String, params: WavesFeedParams, cursor: String?): String {
            val p = params.normalized()
            val url = WebUrl.parse("$host/private-api/waves/feed") ?: throw EcencyHttpException(0, "bad host")
            url.setSearchParam("limit", p.limit.toString())
            if (!cursor.isNullOrEmpty()) url.setSearchParam("cursor", cursor)
            p.containers.forEach { url.appendSearchParam("container", it) }
            p.tag?.let { url.setSearchParam("tag", it) }
            p.following?.let { url.setSearchParam("following", it) }
            p.author?.let { url.setSearchParam("author", it) }
            p.observer?.let { url.setSearchParam("observer", it) }
            return url.href
        }

        fun shortsUrl(host: String, params: WavesFeedParams, cursor: String?): String {
            val p = params.normalized()
            val url = WebUrl.parse("$host/private-api/waves/shorts") ?: throw EcencyHttpException(0, "bad host")
            url.setSearchParam("limit", p.limit.toString())
            if (!cursor.isNullOrEmpty()) url.setSearchParam("cursor", cursor)
            p.containers.forEach { url.appendSearchParam("container", it) }
            p.tag?.let { url.setSearchParam("tag", it) }
            p.author?.let { url.setSearchParam("author", it) }
            p.observer?.let { url.setSearchParam("observer", it) }
            return url.href
        }

        fun promotedUrl(host: String): String = "$host/private-api/promoted-entries?short_content=1"

        fun trendingTagsUrl(host: String, container: String?, hours: Int): String {
            val url = WebUrl.parse("$host/private-api/waves/trending/tags") ?: throw EcencyHttpException(0, "bad host")
            container?.let(Js::trim)?.takeIf { it.isNotEmpty() }?.let { url.setSearchParam("container", it) }
            url.setSearchParam("hours", hours.toString())
            return url.href
        }

        fun threeSpeakEmbedApi(author: String, permlink: String): String =
            "https://play.3speak.tv/api/embed?v=${com.ecency.core.api.EcencyRestApi.encode(author)}/${com.ecency.core.api.EcencyRestApi.encode(permlink)}"

        /** RN's web player fallback for a short whose stream cannot be resolved or played. */
        fun threeSpeakWatchUrl(author: String, permlink: String): String =
            "https://play.3speak.tv/watch?v=${com.ecency.core.api.EcencyRestApi.encode(author)}/${com.ecency.core.api.EcencyRestApi.encode(permlink)}&mode=iframe&autoplay=true"

        /** Only https, no credentials: the stream goes straight into the platform player. */
        internal fun safeStream(url: String): String? =
            SafeTarget.httpUrl(url)?.takeIf { it.startsWith("https://", ignoreCase = true) }

        /**
         * SDK getNextPageParam: a page shorter than [limit] ends the feed; a full page continues from the cursor
         * of its last row, and ends too when that row has none.
         */
        fun nextCursor(limit: Int, cursors: List<String?>): String? =
            if (cursors.size < limit) null else cursors.lastOrNull()
    }
}

/** Keyset paging over the waves or shorts feed. Not thread-safe: one model drives one pager. */
class WavesPager internal constructor(
    private val limit: Int,
    private val fetch: suspend (cursor: String?) -> List<WaveEntry>,
) {
    private var cursor: String? = null
    private var started = false

    var isEndReached: Boolean = false
        private set

    /** The next page; empty once the end was reached. */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun loadMore(): List<WaveEntry> {
        if (isEndReached) return emptyList()
        val page = fetch(if (started) cursor else null)
        started = true
        val next = WavesApi.nextCursor(limit, page.map { it.cursor })
        // a node that hands back the cursor it was given would page forever
        if (next == null || next == cursor) isEndReached = true
        cursor = next
        return page
    }
}

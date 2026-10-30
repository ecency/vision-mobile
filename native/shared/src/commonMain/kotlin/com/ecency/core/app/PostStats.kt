package com.ecency.core.app

import com.ecency.core.api.EcencyRestApi
import com.ecency.core.api.model.Post
import com.ecency.core.api.model.arr
import com.ecency.core.api.model.obj
import com.ecency.core.hive.ChainTime
import com.ecency.core.l10n.L
import com.ecency.core.l10n.LocaleFormat
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.coroutines.cancellation.CancellationException

/** RN en-US `stats.*` and the post view count's accessibility copy. */
object StatsCopy {
    val VISITORS: String get() = L.stats.viewers
    val VIEWS: String get() = L.stats.pageviews
    val MINUTES: String get() = L.stats.duration
    val PROMOTE_TITLE: String get() = L.stats.promoteTitle
    val PROMOTE_MESSAGE: String get() = L.stats.promoteMessage
    val PROMOTE: String get() = L.stats.promote
    val OTHER_DEVICE: String get() = L.native.stats.otherDevice
    val STATS_HINT: String get() = L.post.a11yStatsHint // post.a11y_stats_hint

    /** post.a11y_views */
    fun views(count: Long) = L.post.a11yViews(count = count.toInt())
}

/** RN PostStats (Plausible): visitors, visits (shown as "Views"), pageviews and visit_duration (seconds). */
data class PostStatsValues(val visitors: Long = 0, val visits: Long = 0, val pageviews: Long = 0, val visitDuration: Long = 0)

/** One row of the device breakdown: the device and its share of the pageviews (0..1). */
data class DeviceShare(val device: String, val share: Double)

data class PostStatsState(
    val loading: Boolean = true,
    val stats: PostStatsValues = PostStatsValues(),
    val devicesLoading: Boolean = false,
    val devices: List<DeviceShare> = emptyList(),
) {
    /** RN getAbbreviatedNumber(visits). */
    val viewsText: String get() = PostStatsModel.abbreviated(stats.visits)

    /**
     * The "Minutes" value. RN shows `floor(visit_duration / 1000)` under "Minutes", reading Plausible's seconds as
     * milliseconds (always about 0); here the seconds are turned into minutes.
     */
    val minutes: Long get() = stats.visitDuration / 60
}

/**
 * RN postDisplayView's view count and postStatsModal: `POST {ecency}/api/stats` (a read of the Plausible proxy,
 * nothing recorded) for the post's path without its category, the post's lifetime as `date_range`; the device
 * breakdown (`visit:device`) only when the modal opens. Any failure or empty answer reads as zeros, as RN.
 */
class PostStatsModel internal constructor(private val env: AppEnvironment, private val post: Post) :
    StateModel<PostStatsState>(PostStatsState(), env.dispatchers) {
    private var started = false
    private var devicesJob: Job? = null

    fun start() {
        if (started) return
        started = true
        scope.launch {
            val stats = query(emptyList())?.let { parse(it).firstOrNull()?.second } ?: PostStatsValues()
            setState { it.copy(loading = false, stats = stats) }
        }
    }

    /** The modal opened: the per-device breakdown, sorted by pageviews. */
    fun loadDevices() {
        if (devicesJob != null) return
        setState { it.copy(devicesLoading = true) }
        devicesJob = scope.launch {
            val rows = query(listOf("visit:device"))?.let(::parse).orEmpty().sortedByDescending { it.second.pageviews }
            val total = rows.sumOf { it.second.pageviews }.takeIf { it > 0 } ?: 1
            setState { s ->
                s.copy(devicesLoading = false, devices = rows.map { (device, v) -> DeviceShare(if (device == "(not set)" || device.isEmpty()) StatsCopy.OTHER_DEVICE else device, v.pageviews.toDouble() / total) })
            }
        }
    }

    /** RN's PROMOTE POST: the Redeem screen for this post. */
    val promoteScreen: AppScreen get() = AppScreen.Redeem("promote", "${post.author}/${post.permlink}")

    private suspend fun query(dimensions: List<String>): JsonElement? = try {
        env.rest.post("${env.rest.host}/api/stats", body(post, dimensions, env.nowMillis()))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    companion object {
        val METRICS = listOf("visitors", "visits", "pageviews", "visit_duration")

        /** RN stripCategoryFromPostPath: `/<category>/@a/p` is `/@a/p`. */
        fun stripCategory(path: String): String {
            if (path.length < 2 || path[0] != '/' || path[1] == '@' || path[1] == '/') return path
            val slash = path.indexOf('/', 1)
            return if (slash > 0 && path.startsWith("/@", slash)) path.substring(slash) else path
        }

        /** The request body of SDK getStatsQueryOptions. */
        fun body(post: Post, dimensions: List<String>, nowMillis: Long) = buildJsonObject {
            putJsonArray("metrics") { METRICS.forEach { add(JsonPrimitive(it)) } }
            put("url", EcencyRestApi.encode(stripCategory(post.url.ifEmpty { "/@${post.author}/${post.permlink}" })))
            putJsonArray("dimensions") { dimensions.forEach { add(JsonPrimitive(it)) } }
            put("filterBy", "event:page")
            // RN getPostStatsDateRange: the post's creation day through today (UTC)
            if (post.created.isNotEmpty()) {
                put("date_range", buildJsonArray {
                    add(JsonPrimitive(post.created.substringBefore('T')))
                    add(JsonPrimitive(ChainTime.fromEpochSeconds(nowMillis / 1000).substringBefore('T')))
                })
            }
        }

        /** RN parsePostStatsResponse per result, metrics mapped by the query's metric names; rows without all metrics are dropped. */
        fun parse(json: JsonElement): List<Pair<String, PostStatsValues>> {
            val o = json.obj() ?: return emptyList()
            val names = o["query"].obj()?.get("metrics").arr()?.map { (it as? JsonPrimitive)?.contentOrNull } ?: METRICS
            return o["results"].arr().orEmpty().mapNotNull { r ->
                val ro = r.obj() ?: return@mapNotNull null
                val metrics = ro["metrics"] as? JsonArray ?: return@mapNotNull null
                if (metrics.size < METRICS.size) return@mapNotNull null
                fun v(name: String) = names.indexOf(name).takeIf { it >= 0 }?.let { i -> (metrics.getOrNull(i) as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toLong() } ?: 0
                val device = (ro["dimensions"].arr()?.firstOrNull() as? JsonPrimitive)?.contentOrNull.orEmpty()
                device to PostStatsValues(v("visitors"), v("visits"), v("pageviews"), v("visit_duration"))
            }
        }

        /** RN getAbbreviatedNumber: 1.2K, 0.1M from 100K, 1M; in the app language ([LocaleFormat.compact]). */
        fun abbreviated(n: Long): String = when {
            n >= 100_000 -> LocaleFormat.compact(n.toDouble(), 1, 6)
            n >= 1_000 -> LocaleFormat.compact(n.toDouble(), 1, 3)
            else -> LocaleFormat.toFixed(n.toDouble(), 0)
        }
    }
}

package com.ecency.core.app

import com.ecency.core.api.SimilarEntry
import com.ecency.core.api.model.Mana
import com.ecency.core.api.model.Post
import com.ecency.core.hive.CustomJson
import com.ecency.core.api.broadcast.OperationJson
import com.ecency.core.l10n.L
import com.ecency.core.l10n.L10n
import com.ecency.core.l10n.LocaleFormat
import com.ecency.core.l10n.TimeUnit
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.cancellation.CancellationException

data class QuickProfileState(
    val username: String,
    val avatarUrl: String = "",
    val displayName: String? = null,
    val about: String? = null,
    /** RN "Joined {time} ago", "---" until loaded. */
    val joined: String = "---",
    val followers: Int? = null,
    val following: Int? = null,
    val posts: Int? = null,
    /** "87%" (RN toFixed(0)). */
    val resourceCredits: String? = null,
    val reputation: Int? = null,
    /** 0..100, the avatar ring. */
    val votingPower: Double? = null,
    val isLoading: Boolean = false,
    val isFollowing: Boolean = false,
    val isMuted: Boolean = false,
    val isFavorite: Boolean = false,
    val isOwn: Boolean = false,
    val actionRunning: Boolean = false,
    /** Signed in: the action row shows (RN). */
    val showActions: Boolean = false,
)

/**
 * RN quickProfileModal: account, follow counts, resource credits and voting power, and for a signed-in viewer
 * the relationship and favourite state, with follow and favourite actions. The full profile screen is another
 * wave's; "VIEW FULL PROFILE" opens [AppScreen.Profile].
 */
class QuickProfileModel internal constructor(private val env: AppEnvironment, username: String) :
    StateModel<QuickProfileState>(QuickProfileState(username, PostCard.avatarUrl(username, env.proxyBase, "medium")), env.dispatchers) {
    private var job: Job? = null

    fun start() {
        if (job != null) return
        val user = current.username
        val me = env.session.current.username
        setState { it.copy(isLoading = true, isOwn = me == user, showActions = me != null) }
        job = scope.launch {
            coroutineScope {
                val account = async { quiet { env.api.accounts.getAccount(user) } }
                val profile = async { quiet { env.api.accounts.getProfile(user, me) } }
                val counts = async { quiet { env.api.hive.followCount(user) } }
                val rc = async { quiet { env.api.hive.rcAccount(user) } }
                val relation = async { if (me != null && me != user) quiet { env.api.hive.relationship(me, user) } else null }
                val favorite = async { if (me != null && me != user) quiet { env.accountApi.isFavorite(me, user) } else null }
                val a = account.await()
                val p = profile.await()
                val c = counts.await()
                val r = rc.await()
                val rel = relation.await()
                val fav = favorite.await()
                val now = env.nowMillis()
                setState {
                    it.copy(
                        isLoading = false,
                        displayName = a?.profile?.displayName,
                        about = (p?.profile?.about ?: a?.profile?.about)?.takeIf { s -> s.isNotBlank() },
                        joined = a?.created?.takeIf { c -> c.isNotEmpty() }?.let { c -> L.profile.joined(time = joinedAgo(PostCard.epochMillis(c), now)) } ?: "---",
                        posts = a?.postCount,
                        reputation = p?.reputation ?: a?.reputation,
                        votingPower = a?.let { acc -> Mana.votingPowerPercent(acc, now) },
                        followers = c?.followers,
                        following = c?.following,
                        resourceCredits = r?.let { v -> LocaleFormat.percent(com.ecency.core.api.model.Mana.rcPercent(v, now), 0, 0) },
                        isFollowing = rel?.follows ?: false,
                        isMuted = rel?.ignores ?: false,
                        isFavorite = fav ?: false,
                    )
                }
            }
        }
    }

    /** RN actionPanel follow (the sheet only follows; the button is disabled once following). */
    fun follow() {
        val me = env.session.current.username ?: return
        val s = current
        if (s.isOwn || s.isFollowing || s.actionRunning) return
        setState { it.copy(actionRunning = true) }
        scope.launch {
            try {
                val json = JsonArray(
                    listOf(
                        JsonPrimitive("follow"),
                        buildJsonObject {
                            put("follower", me)
                            put("following", s.username)
                            put("what", buildJsonArray { add(JsonPrimitive("blog")) })
                        },
                    ),
                ).toString()
                env.signing.broadcast(me, JsonArray(listOf(OperationJson.encode(CustomJson(emptyList(), listOf(me), "follow", json)))))
                // the account changed while signing: this result belongs to the previous one (no state, no list, no toast)
                if (env.session.current.username != me) {
                    setState { it.copy(actionRunning = false) }
                    return@launch
                }
                setState { it.copy(actionRunning = false, isFollowing = true, isMuted = false, followers = it.followers?.plus(1)) }
                // following replaces an ignore on chain: the account is no longer in the ignore list (RN)
                env.setAuthorMuted(s.username, false)
                env.ui.toast(L.alert.successFollow)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val error = AppError.fromBroadcast(e)
                setState { it.copy(actionRunning = false) }
                if (error.kind != ErrorKind.CANCELLED && !env.offerRcIfShort(e)) env.ui.toast(L.native.profileSheet.fail(message = error.message))
            }
        }
    }

    /** RN favourite toggle (Ecency private API). */
    fun toggleFavorite() {
        val me = env.session.current.username ?: return
        val s = current
        if (s.isOwn || s.actionRunning) return
        setState { it.copy(actionRunning = true) }
        scope.launch {
            try {
                if (!s.isFavorite) env.accountApi.addFavorite(me, s.username) else env.accountApi.deleteFavorite(me, s.username)
                setState { it.copy(actionRunning = false, isFavorite = !s.isFavorite) }
                env.ui.toast(PostActionsModel.SUCCESS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setState { it.copy(actionRunning = false) }
                env.ui.toast(PostActionsModel.FAIL)
            }
        }
    }

    private suspend fun <T> quiet(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    companion object {
        /**
         * RN getTimeFromNowNative: the largest whole unit ("3 years", "5 days"; RN's English plural even for 1), in
         * the app language from en-US on (CLDR durations: "3 Jahre", "1 year").
         */
        fun joinedAgo(thenMillis: Long, nowMillis: Long): String {
            val s = ((nowMillis - thenMillis) / 1000).coerceAtLeast(0).toDouble()
            val units = listOf(
                TimeUnit.YEAR to 365.0 * 86400, TimeUnit.MONTH to 30.0 * 86400, TimeUnit.WEEK to 7.0 * 86400,
                TimeUnit.DAY to 86400.0, TimeUnit.HOUR to 3600.0, TimeUnit.MINUTE to 60.0, TimeUnit.SECOND to 1.0,
            )
            val english = L10n.language.value == L10n.SOURCE
            for ((unit, size) in units) {
                val v = kotlin.math.round(s / size).toLong()
                // RN writes "1 years"; the plural is RN's, kept for en-US
                if (v >= 1) return if (english) "$v ${unit.name.lowercase()}s" else LocaleFormat.duration(v, unit)
            }
            return LocaleFormat.duration(0, TimeUnit.SECOND)
        }
    }
}

data class SimilarState(val entries: List<SimilarEntry> = emptyList()) {
    /** RN renders nothing below two entries. */
    val visible: Boolean get() = entries.size >= 2
}

/** RN similarEntries ("Read next"): top-level posts only, two entries. */
class SimilarEntriesModel internal constructor(private val env: AppEnvironment) : StateModel<SimilarState>(SimilarState(), env.dispatchers) {
    private var loadedFor: String? = null

    fun start(post: Post) {
        if (!post.parentAuthor.isNullOrEmpty() || post.depth != 0) return
        if (loadedFor == post.key.toString()) return
        loadedFor = post.key.toString()
        scope.launch {
            val since = com.ecency.core.hive.ChainTime.fromEpochSeconds((env.nowMillis() - 4368L * 3600_000) / 1000)
            val body = kotlinx.coroutines.withContext(env.dispatchers.render) { strip(post.body) }
            val entries = try {
                env.rest.similarEntries(post.author, post.permlink, post.title, body, post.metadata.tags.filter { it.isNotEmpty() }, since)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            setState { SimilarState(entries.take(2)) }
        }
    }

    companion object {
        /** SDK similar-entries body: no images, link text kept, no tags or bare URLs, spaces collapsed, 3000 chars. */
        fun strip(body: String): String = body
            .replace(Regex("!\\[[^\\]]*\\]\\([^)]*\\)"), " ")
            .replace(Regex("\\[([^\\]]*)\\]\\([^)]*\\)"), "$1")
            .replace(Regex("<[^>]*>"), " ")
            .replace(Regex("https?://\\S+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(3000)
    }
}

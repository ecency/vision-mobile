package com.ecency.core.app

import com.ecency.core.api.model.Post
import com.ecency.core.api.model.PostKey
import com.ecency.core.api.waves.WaveEntry
import com.ecency.core.api.waves.WaveTrendingTag
import com.ecency.core.api.waves.Waves
import com.ecency.core.api.waves.WavesApi
import com.ecency.core.api.waves.WavesFeedParams
import com.ecency.core.api.waves.WavesPager
import com.ecency.core.l10n.L
import com.ecency.core.render.blocks.Block
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/** Which slice of the combined waves feed a list shows (RN: every tab is a filter on one feed). */
sealed interface WavesFeedKind {
    /** Stable key of the tab or slot showing this feed. */
    val key: String

    data object ForYou : WavesFeedKind {
        override val key = "for-you"
    }

    /** Waves from the accounts the signed-in user follows. Empty while signed out. */
    data object Following : WavesFeedKind {
        override val key = "following"
    }

    data class Tag(val tag: String) : WavesFeedKind {
        override val key = "tag:$tag"
    }

    /** One container account ("Waves", "Threads", "Snaps"...). */
    data class Container(val host: String) : WavesFeedKind {
        override val key = "container:$host"
    }

    /** One author's waves (the profile tab): no observer, no mute filter, no promoted waves, as RN. */
    data class Author(val name: String) : WavesFeedKind {
        override val key = "author:$name"
    }

    /** RN: promoted waves only on the unfiltered feeds. */
    val injectsPromoted: Boolean get() = this is ForYou || this is Following

    val appliesMuteFilter: Boolean get() = this !is Author

    /** The request for [user] (null when signed out); null when this feed needs an account and there is none. */
    fun params(user: String?): WavesFeedParams? = when (this) {
        ForYou -> WavesFeedParams(observer = user)
        Following -> user?.let { WavesFeedParams(following = it, observer = it) }
        is Tag -> WavesFeedParams(tag = tag, observer = user)
        is Container -> WavesFeedParams(containers = listOf(host), observer = user)
        is Author -> WavesFeedParams(author = name)
    }
}

/** One wave as the list draws it (RN's comment view for a wave), built off the main thread. */
data class WaveItem(
    val post: Post,
    /** Header and action data (author, avatar, age, payout, votes, replies); summary and image are unused. */
    val card: PostCard,
    val blocks: List<Block>,
    /** The container account ("ecency.waves", "leothreads"...). */
    val host: String,
    val isPromoted: Boolean,
    /** Published from this device a moment ago; the feed has not indexed it yet. */
    val isLocal: Boolean,
    val tipCount: Int,
    /** The author's reputation when the row carries one (feed rows do not). */
    val reputation: Int?,
    /** RN isFromEcency: json_metadata.app names an Ecency client. */
    val isFromEcency: Boolean,
) {
    val key: PostKey get() = post.key
}

data class WavesFeedState(
    val items: List<WaveItem> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val endReached: Boolean = false,
    val error: AppError? = null,
    val loaded: Boolean = false,
    /** Pages loaded since the last refresh (UIs key "near the end" on it). */
    val pages: Int = 0,
    /** This feed needs an account and nobody is signed in (the Following tab). */
    val needsLogin: Boolean = false,
)

internal object WaveItems {
    /** RN commentView isFromEcency. */
    fun isFromEcency(app: String?): Boolean = app.orEmpty().substringBefore('/').lowercase().contains("ecency")

    /** The entry's post with what the lightweight feed row leaves out filled in for the card. */
    fun post(entry: WaveEntry): Post {
        val post = entry.post
        // feed rows carry a net vote count instead of active_votes
        return if (!entry.hasActiveVotes && entry.netVotes != null) {
            post.copy(stats = post.stats.copy(totalVotes = entry.netVotes.coerceAtLeast(0)))
        } else {
            post
        }
    }

    /** Runs on [AppDispatchers.render]. */
    suspend fun build(env: AppEnvironment, entry: WaveEntry, user: String?, promoted: Boolean, local: Boolean, nsfwWarn: Boolean): WaveItem {
        val post = post(entry)
        val card = Cards.of(post, env.renderer, user, env.localVotes.get(post.key), env.proxyBase, promoted = promoted, nsfwWarn = nsfwWarn)
            // a wave the API flagged is covered like a tagged one ([WaveNsfw])
            .let { if (nsfwWarn && entry.flaggedNsfw && !it.isNsfw) it.copy(isNsfw = true) else it }
        if (entry.flaggedNsfw) env.mutedReveals.markFlaggedWave(post.key)
        return WaveItem(
            post = post,
            card = card,
            blocks = env.renderBody(post),
            host = entry.host.ifEmpty { post.parentAuthor.orEmpty() },
            isPromoted = promoted,
            isLocal = local,
            tipCount = entry.tipCount,
            reputation = post.authorReputation.takeIf { entry.hasReputation },
            isFromEcency = isFromEcency(post.metadata.app),
        )
    }

    /**
     * RN useWavesQuery: promoted waves interleaved at the web feed's cadence, the first after the 2nd organic
     * wave, then one after every 4th, until the queue drains; an organic copy of a promoted wave is dropped.
     */
    fun <T> interleave(organic: List<T>, promoted: List<T>, key: (T) -> PostKey): List<T> {
        if (promoted.isEmpty()) return organic
        val promotedKeys = promoted.map(key).toHashSet()
        val queue = ArrayDeque(promoted)
        val out = ArrayList<T>(organic.size + promoted.size)
        organic.filter { key(it) !in promotedKeys }.forEachIndexed { index, item ->
            out += item
            if (index % 4 == 1 && queue.isNotEmpty()) out += queue.removeFirst()
        }
        return out
    }
}

/**
 * One waves list. A single keyset-paged query backs it ([WavesApi.feed]); on top come RN's visibility filter
 * (the viewer's mutes, muted or gray waves, bot authors, "Always hide" NSFW), promoted waves, waves just
 * published from this device, optimistic votes and the 60 s refetch ([setActive]).
 *
 * The list belongs to the account that loaded it: on any account change everything shown is dropped at once and
 * an answer still on its way for the previous account is ignored, so nothing loaded for one account (their
 * following feed, their mutes, their votes) is ever shown to the next.
 */
class WavesFeedModel internal constructor(
    private val env: AppEnvironment,
    val kind: WavesFeedKind,
) : StateModel<WavesFeedState>(WavesFeedState(), env.dispatchers) {

    private var pager: WavesPager? = null
    private var job: Job? = null
    private var pollJob: Job? = null

    /** Bumped on every account change; work started before it never lands. */
    private var generation = 0
    private var started = false

    private var organic: List<WaveItem> = emptyList()
    private var promoted: List<WaveItem> = emptyList()

    /** The post behind a wave of this list (for the options menu and the reply sheet). */
    fun post(key: PostKey): Post? = current.items.firstOrNull { it.key == key }?.post

    private fun display(): List<WaveItem> {
        env.waves.markWaves(organic.map { it.key } + promoted.map { it.key })
        val muted: (WaveItem) -> Boolean = { kind.appliesMuteFilter && it.post.author in env.mutedAuthors }
        val shownOrganic = organic.filterNot(muted)
        val shownPromoted = if (kind.injectsPromoted) promoted.filterNot(muted) else emptyList()
        val list = WaveItems.interleave(shownOrganic, shownPromoted) { it.key }
        if (kind != WavesFeedKind.ForYou) return list
        val keys = list.map { it.key }.toHashSet()
        return env.waves.localItems(env.session.current.username).filter { it.key !in keys } + list
    }

    private fun redisplay() {
        val next = display()
        setState { if (next == it.items) it else it.copy(items = next) }
    }

    init {
        scope.launch {
            var previous = env.localVotes.state.value
            env.localVotes.state.drop(1).collect { local ->
                val changed = Cards.changedKeys(previous, local)
                previous = local
                if (changed.isEmpty()) return@collect
                val user = env.session.current.username
                val update: (WaveItem) -> WaveItem? = { item -> item.copy(card = Cards.withVote(item.card, item.post, user, local[item.key])) }
                organic = Cards.replaceChanged(organic, changed, { it.key }, update)
                promoted = Cards.replaceChanged(promoted, changed, { it.key }, update)
                env.waves.updateLocal(changed, update)
                setState { s ->
                    val items = Cards.replaceChanged(s.items, changed, { it.key }, update)
                    if (items === s.items) s else s.copy(items = items)
                }
            }
        }
        scope.launch {
            env.events.collect { event ->
                when (event) {
                    is AppEvent.SessionChanged -> onAccountChanged()
                    is AppEvent.DataWiped -> onAccountChanged()
                    is AppEvent.PostDeleted -> if (current.items.any { it.key == event.key }) {
                        // a wave published a moment ago has a local copy too: it goes with the wave
                        env.waves.forget(event.key)
                        organic = organic.filter { it.key != event.key }
                        promoted = promoted.filter { it.key != event.key }
                        redisplay()
                    }
                    is AppEvent.AuthorMuted, AppEvent.MutesLoaded -> redisplay()
                    is AppEvent.WavePosted -> if (kind == WavesFeedKind.ForYou) redisplay()
                    else -> {}
                }
            }
        }
    }

    /** The account changed: nothing of the previous one stays on screen, and the feed loads again if it was in use. */
    private fun onAccountChanged() {
        generation++
        job?.cancel()
        job = null
        pager = null
        organic = emptyList()
        promoted = emptyList()
        setState { WavesFeedState() }
        if (started) load(reset = true, refreshing = false)
    }

    /** Loads the first page unless already loaded or loading. */
    fun start() {
        started = true
        if (current.loaded || job?.isActive == true) return
        load(reset = true, refreshing = false)
    }

    /** Loads the first page again; the list stays visible meanwhile (pull to refresh). */
    fun refresh() {
        started = true
        load(reset = true, refreshing = current.items.isNotEmpty())
    }

    @Throws(CancellationException::class)
    suspend fun refreshAndWait() {
        refresh()
        scope.async { state.first { !it.isRefreshing && !it.isLoading } }.await()
    }

    fun loadMore() {
        val s = current
        if (job?.isActive == true || s.endReached || s.error != null || !s.loaded) return
        load(reset = false, refreshing = false)
    }

    fun retry() {
        if (job?.isActive == true) return
        val reset = !current.loaded || pager == null
        load(reset = reset, refreshing = reset && current.items.isNotEmpty())
    }

    /**
     * RN `refetchInterval: 60000`: while the list is on screen ([active]) the first page is read again every
     * minute; new waves go on top and the waves already shown take the fresh counts.
     */
    fun setActive(active: Boolean) {
        if (!active) {
            pollJob?.cancel()
            pollJob = null
            return
        }
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (true) {
                delay(REFETCH_MILLIS)
                refetchFirstPage()
            }
        }
    }

    private suspend fun refetchFirstPage() {
        if (!current.loaded || job?.isActive == true) return
        val user = env.session.current.username
        val params = kind.params(user) ?: return
        val startedIn = generation
        val fresh = try {
            build(visible(env.waves.api.feed(params)), user, promoted = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return // the list stays as it is; the next tick tries again
        }
        if (startedIn != generation || job?.isActive == true) return
        val byKey = fresh.associateBy { it.key }
        val known = organic.map { it.key }.toHashSet()
        organic = fresh.filter { it.key !in known } + organic.map { byKey[it.key] ?: it }
        redisplay()
    }

    /**
     * RN isVisibleWave (mutes, bots, the API's explicit flag: [WaveNsfw.isHidden]), plus the app's "Always hide"
     * NSFW setting for tagged posts as on the other feeds.
     */
    private suspend fun visible(entries: List<WaveEntry>): List<WaveEntry> {
        val bots = env.botAuthors()
        val nsfw = NsfwMode.of(env.prefs)
        val viewer = env.session.current.username
        return entries.filter { e ->
            !(kind.appliesMuteFilter && e.post.author in env.mutedAuthors) &&
                !e.post.isMuted &&
                e.post.author !in bots &&
                !WaveNsfw.isHidden(e, nsfw, viewer) &&
                (nsfw != NsfwMode.HIDE || !NsfwMode.isNsfw(e.post))
        }
    }

    private suspend fun build(entries: List<WaveEntry>, user: String?, promoted: Boolean): List<WaveItem> {
        val warn = NsfwMode.of(env.prefs) == NsfwMode.WARN
        return withContext(dispatchers.render) {
            entries.distinctBy { it.post.key }.map { WaveItems.build(env, it, user, promoted, local = false, nsfwWarn = warn) }
        }
    }

    private fun loadPromoted(startedIn: Int, user: String?) {
        scope.launch {
            val items = try {
                build(visible(env.waves.api.promoted()), user, promoted = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@launch
            }
            if (startedIn != generation) return@launch
            promoted = items
            redisplay()
        }
    }

    private fun load(reset: Boolean, refreshing: Boolean) {
        job?.cancel()
        val user = env.session.current.username
        val params = kind.params(user)
        if (params == null) {
            pager = null
            organic = emptyList()
            promoted = emptyList()
            setState { WavesFeedState(needsLogin = true) }
            return
        }
        if (reset) pager = null
        val activePager = pager ?: WavesPager(params.limit) { cursor -> env.waves.api.feed(params, cursor) }.also { pager = it }
        setState {
            it.copy(
                isLoading = reset && !refreshing,
                isRefreshing = refreshing,
                isLoadingMore = !reset,
                error = null,
                needsLogin = false,
                items = if (reset && !refreshing) emptyList() else it.items,
            )
        }
        val startedIn = generation
        job = scope.launch {
            try {
                val items = build(visible(activePager.loadMore()), user, promoted = false)
                if (startedIn != generation) return@launch
                val known = if (reset) emptySet() else organic.map { it.key }.toHashSet()
                organic = if (reset) items else organic + items.filter { it.key !in known }
                if (reset && kind.injectsPromoted) loadPromoted(startedIn, user)
                if (reset) env.waves.forgetIndexed(organic.map { it.key })
                setState {
                    it.copy(
                        items = display(),
                        isLoading = false,
                        isRefreshing = false,
                        isLoadingMore = false,
                        endReached = activePager.isEndReached,
                        loaded = true,
                        pages = if (reset) 1 else it.pages + 1,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (startedIn != generation) return@launch
                if (reset) pager = null
                setState { it.copy(isLoading = false, isRefreshing = false, isLoadingMore = false, error = AppError.from(e)) }
            }
        }
    }

    companion object {
        const val REFETCH_MILLIS = 60_000L
    }
}

/** One tab of the Waves screen. */
data class WavesTab(val key: String, val title: String) {
    val isShorts: Boolean get() = key == SHORTS_KEY

    companion object {
        const val FOR_YOU = "for-you"
        const val FOLLOWING = "following"
        const val SHORTS_KEY = "container:${Waves.SHORTS_SOURCE}"
    }
}

data class WavesTabsState(
    val tabs: List<WavesTab>,
    val selected: String = WavesTab.FOR_YOU,
    /** RN activeTag: a tapped hashtag filters the For you tab until its chip is closed. */
    val activeTag: String? = null,
    val pinnedTags: List<String> = emptyList(),
    val pinnedContainers: List<String> = emptyList(),
    /** Trending tags for the picker, already normalized and without the pinned ones (at most 24). */
    val trendingTags: List<String> = emptyList(),
) {
    val showsShorts: Boolean get() = selected == WavesTab.SHORTS_KEY

    /** RN hides the compose button on the Shorts tab. */
    val showsCompose: Boolean get() = !showsShorts

    /** The feed slot on screen: the tag overlay's own slot while a hashtag filter is on. */
    val feedKey: String get() = if (selected == WavesTab.FOR_YOU && activeTag != null) "tag" else selected
}

/** What a tap on a tab led to. */
enum class WavesTabTap { SELECTED, SCROLL_TO_TOP, NEEDS_LOGIN }

/**
 * The Waves screen's tabs (RN wavesScreen + wavesTabBar + wavesTagPickerModal): For you, Following, then the
 * pinned sources and the pinned tags. Pins are kept in [Prefs] (RN redux `customTabs`, device-wide). The lists
 * of the visited tabs stay alive with their place, as RN's TabView keeps its scenes.
 */
class WavesTabsModel internal constructor(private val env: AppEnvironment) :
    StateModel<WavesTabsState>(initial(env.prefs), env.dispatchers) {

    private val feeds = mutableMapOf<String, WavesFeedModel>()
    private var shorts: ShortsModel? = null
    private var rawTrending: List<WaveTrendingTag> = emptyList()

    init {
        scope.launch {
            env.events.collect { event ->
                // RN: the Following tab needs an account; a hashtag filter belongs to the reader who set it
                if (event is AppEvent.SessionChanged || event is AppEvent.DataWiped) {
                    val signedOut = (event as? AppEvent.SessionChanged)?.username == null
                    setState { s ->
                        s.copy(selected = if (s.selected == WavesTab.FOLLOWING && signedOut) WavesTab.FOR_YOU else s.selected, activeTag = null)
                    }
                    feeds.remove("tag")?.close()
                }
            }
        }
    }

    /** The list for the feed slot [key] ([WavesTabsState.feedKey]); null for the Shorts tab and unknown keys. */
    fun feed(key: String): WavesFeedModel? {
        feeds[key]?.let { return it }
        val s = current
        val kind: WavesFeedKind = when {
            key == WavesTab.FOR_YOU -> WavesFeedKind.ForYou
            key == WavesTab.FOLLOWING -> WavesFeedKind.Following
            key == "tag" -> WavesFeedKind.Tag(s.activeTag ?: return null)
            key == WavesTab.SHORTS_KEY -> return null
            key.startsWith("tag:") && key.removePrefix("tag:") in s.pinnedTags -> WavesFeedKind.Tag(key.removePrefix("tag:"))
            key.startsWith("container:") && key.removePrefix("container:") in s.pinnedContainers -> WavesFeedKind.Container(key.removePrefix("container:"))
            else -> return null
        }
        return WavesFeedModel(env, kind).also { feeds[key] = it }
    }

    /** The reels behind the Shorts tab. */
    fun shorts(): ShortsModel = shorts ?: ShortsModel(env).also { shorts = it }

    /** RN _handleTabChange. */
    fun select(key: String): WavesTabTap {
        val s = current
        if (s.tabs.none { it.key == key }) return WavesTabTap.SELECTED
        if (key == WavesTab.FOLLOWING && env.session.current.username == null) return WavesTabTap.NEEDS_LOGIN
        if (key == s.selected && s.activeTag == null) return WavesTabTap.SCROLL_TO_TOP
        val clearsTag = key == WavesTab.FOLLOWING || key.startsWith("tag:") || key.startsWith("container:")
        if (clearsTag && s.activeTag != null) feeds.remove("tag")?.close()
        setState { it.copy(selected = key, activeTag = if (clearsTag) null else it.activeTag) }
        return WavesTabTap.SELECTED
    }

    /** RN _handleTagFilter: a hashtag tapped inside a wave filters the For you tab. */
    fun filterByTag(tag: String) {
        val clean = tag.trim().removePrefix("#")
        if (clean.isEmpty()) return
        if (current.activeTag != clean) feeds.remove("tag")?.close()
        setState { it.copy(selected = WavesTab.FOR_YOU, activeTag = clean) }
    }

    fun clearTag() {
        feeds.remove("tag")?.close()
        setState { it.copy(activeTag = null) }
    }

    /** The picker's `+`: pins a tag as a tab (RN normalizeTag; blanks and repeats are ignored). */
    fun addTag(raw: String) {
        val tag = Waves.normalizeTag(raw)
        if (tag.isEmpty() || tag in current.pinnedTags) return
        pin(current.pinnedTags + tag, current.pinnedContainers)
    }

    fun removeTag(tag: String) = pin(current.pinnedTags - tag, current.pinnedContainers)

    fun toggleContainer(host: String) {
        if (Waves.SOURCE_OPTIONS.none { it.host == host }) return
        val pinned = current.pinnedContainers
        pin(current.pinnedTags, if (host in pinned) pinned - host else pinned + host)
    }

    /** Whether [raw] would pin a tag (the picker's add button). */
    fun canAdd(raw: String): Boolean = Waves.normalizeTag(raw).isNotEmpty()

    /** RN: trending tags load when the picker opens. */
    fun loadTrending() {
        scope.launch {
            rawTrending = env.waves.api.trendingTags(null, 24)
            setState { it.copy(trendingTags = trending(it.pinnedTags)) }
        }
    }

    private fun trending(pinned: List<String>): List<String> =
        rawTrending.map { Waves.normalizeTag(it.tag) }.filter { it.isNotEmpty() && it !in pinned }.take(MAX_TRENDING)

    private fun pin(tags: List<String>, containers: List<String>) {
        env.prefs.putString(TAGS_KEY, tags.joinToString(",").ifEmpty { null })
        env.prefs.putString(CONTAINERS_KEY, containers.joinToString(",").ifEmpty { null })
        val tabs = tabs(tags, containers)
        // a tab that was unpinned takes its list with it
        feeds.keys.filter { key -> key != "tag" && tabs.none { it.key == key } }.forEach { feeds.remove(it)?.close() }
        if (tabs.none { it.isShorts }) {
            shorts?.close()
            shorts = null
        }
        setState { s ->
            // RN: the active tab was removed from the picker, fall back to For you
            s.copy(
                tabs = tabs, pinnedTags = tags, pinnedContainers = containers, trendingTags = trending(tags),
                selected = if (tabs.any { it.key == s.selected }) s.selected else WavesTab.FOR_YOU,
            )
        }
    }

    override fun close() {
        feeds.values.forEach { it.close() }
        feeds.clear()
        shorts?.close()
        shorts = null
        super.close()
    }

    companion object {
        const val TAGS_KEY = "waves.tags"
        const val CONTAINERS_KEY = "waves.containers"
        const val MAX_TRENDING = 24

        /** RN SCROLL_POPUP_THRESHOLD: the scroll-to-top button shows past this offset while scrolling up. */
        const val SCROLL_POPUP_THRESHOLD = 5000

        private fun stored(prefs: Prefs, key: String): List<String> =
            prefs.getString(key).orEmpty().split(',').filter { it.isNotEmpty() }.distinct()

        internal fun tabs(tags: List<String>, containers: List<String>): List<WavesTab> =
            listOf(WavesTab(WavesTab.FOR_YOU, L.waves.forYou), WavesTab(WavesTab.FOLLOWING, L.waves.following)) +
                containers.map { WavesTab("container:$it", Waves.sourceLabel(it)) } +
                tags.map { WavesTab("tag:$it", "#$it") }

        private fun initial(prefs: Prefs): WavesTabsState {
            val tags = stored(prefs, TAGS_KEY).map(Waves::normalizeTag).filter { it.isNotEmpty() }.distinct()
            val containers = stored(prefs, CONTAINERS_KEY).filter { host -> Waves.SOURCE_OPTIONS.any { it.host == host } }
            return WavesTabsState(tabs = tabs(tags, containers), pinnedTags = tags, pinnedContainers = containers)
        }
    }
}

/**
 * A wave the waves API flagged as explicit ([WaveEntry.flaggedNsfw]) under the viewer's NSFW setting (RN #3586):
 * "Always show" shows it, "Always warn" keeps it in the list with its media covered and a per-wave reveal
 * ([WaveItems.build] marks its card NSFW; the text stays readable), "Always hide" leaves it out. RN `utils/waveNsfw` hides it under "Always
 * warn" too (it had no cover). The viewer's own wave is never left out (missing from their own profile it would
 * read as lost). The shared feeds never carry one; this matters for an author's waves (the profile tab) and
 * promoted waves.
 */
object WaveNsfw {
    fun isHidden(entry: WaveEntry, mode: NsfwMode, viewer: String?): Boolean =
        entry.flaggedNsfw && mode == NsfwMode.HIDE && !(viewer != null && entry.post.author == viewer)
}

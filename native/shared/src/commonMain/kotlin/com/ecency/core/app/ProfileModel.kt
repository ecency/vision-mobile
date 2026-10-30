package com.ecency.core.app

import com.ecency.core.api.AccountPostsSort
import com.ecency.core.api.broadcast.OperationJson
import com.ecency.core.api.model.Account
import com.ecency.core.api.model.FollowCount
import com.ecency.core.api.model.JsValue
import com.ecency.core.api.model.Mana
import com.ecency.core.api.model.Profile
import com.ecency.core.api.model.RcAccount
import com.ecency.core.api.model.Relationship
import com.ecency.core.api.repository.FeedSource
import com.ecency.core.hive.CustomJson
import com.ecency.core.hive.KeyRole
import com.ecency.core.l10n.L
import com.ecency.core.render.ImageProxy
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlin.coroutines.cancellation.CancellationException

/** The RN profile tabs (constants/options/filters.ts PROFILE_SCREEN_FILTER_MAP), in their labels. */
enum class ProfileTab(val key: String, private val labelText: () -> String) {
    BLOG("blog", { L.home.blog }),
    POSTS("posts", { L.home.posts }),
    WAVES("waves", { L.profile.waves }),
    COMMENTS("comments", { L.search.comments }),
    REPLIES("replies", { L.profile.replies }),
    WALLET("wallet", { L.profile.wallet }),
    ;

    val label: String get() = labelText()

    /** The bridge sort this tab lists, or null for the tabs another wave builds (waves, wallet). */
    val sort: AccountPostsSort?
        get() = when (this) {
            BLOG -> AccountPostsSort.BLOG
            POSTS -> AccountPostsSort.POSTS
            COMMENTS -> AccountPostsSort.COMMENTS
            REPLIES -> AccountPostsSort.REPLIES
            WAVES, WALLET -> null
        }

    /** Comments and replies render as comment rows, not post cards (RN CommentsTabContent). */
    val isComments: Boolean get() = this == COMMENTS || this == REPLIES

    companion object {
        /** RN DEFAULT_OWN_PROFILE_FILTERS / DEFAULT_PROFILE_FILTERS. */
        fun defaults(own: Boolean): List<ProfileTab> =
            if (own) listOf(BLOG, POSTS, WAVES, REPLIES) else listOf(BLOG, POSTS, WAVES, COMMENTS)

        /**
         * RN _renderTabs with a deep link filter: a tab already there is selected; another known tab replaces
         * the last one and is selected (RN selects index 2 there, which is the waves tab: a bug not kept).
         */
        fun forSection(own: Boolean, section: String): Pair<List<ProfileTab>, Int> {
            val tabs = defaults(own)
            val key = if (section == "points") "wallet" else section
            val tab = entries.firstOrNull { it.key == key } ?: return tabs to 0
            val index = tabs.indexOf(tab)
            if (index >= 0) return tabs to index
            return (tabs.dropLast(1) + tab) to tabs.size - 1
        }
    }
}

/** The RN profile summary (components/profileSummary), ready to draw. */
data class ProfileHeader(
    val username: String,
    /** The profile name, else the username (RN `displayName || username`). */
    val displayName: String,
    val about: String?,
    val location: String?,
    val website: String?,
    /** [website] as a link to open, or null when it is not one. */
    val websiteUrl: String?,
    /** Proxied 640x320 cover, or empty for the default cover. */
    val coverUrl: String,
    val avatarUrl: String,
    val joined: CalendarDate?,
    val reputation: Int,
    val followers: Int,
    val following: Int,
    /** "87.3" */
    val votingPower: String,
    val resourceCredits: String,
    val votingPowerHours: Int?,
    val resourceCreditsHours: Int?,
    /** The pinned post's permlink (profile metadata), if any. */
    val pinned: String?,
) {
    val followersText: String get() = ProfileFormat.count(followers)
    val followingText: String get() = ProfileFormat.count(following)
    val votingPowerText: String get() = ProfileFormat.barText(L.profile.votingPower, votingPower, votingPowerHours)
    val resourceCreditsText: String get() = ProfileFormat.barText(L.profile.resourceCredits, resourceCredits, resourceCreditsHours)
}

/** The "more" menu of someone else's profile (RN profileSummary dropdown), in RN order. */
enum class ProfileMenuItem { FAVORITE, DELEGATE, MUTE, REPORT }

data class ProfileState(
    val username: String,
    val isOwn: Boolean,
    val loggedIn: Boolean,
    val header: ProfileHeader? = null,
    /** First load running (RN placeholder). */
    val isLoading: Boolean = false,
    val loaded: Boolean = false,
    val error: AppError? = null,
    val notFound: Boolean = false,
    val isFollowing: Boolean = false,
    /** RN shows a muted icon instead of the tabs. */
    val isMuted: Boolean = false,
    val isFavorite: Boolean = false,
    val isPro: Boolean = false,
    /** An action is running (RN isProfileLoading: buttons disabled, a spinner instead of the menu). */
    val busy: Boolean = false,
    val tabs: List<ProfileTab> = ProfileTab.defaults(isOwn),
    val selectedTab: Int = 0,
    /** A failed action, shown as RN's "Fail!" alert until [ProfileModel.dismissAlert]. */
    val alert: AppError? = null,
) {
    /** Follow, Message, email digest and the menu: signed in, on someone else's profile. */
    val showsActions: Boolean get() = loggedIn && !isOwn

    val menu: List<ProfileMenuItem> get() = if (showsActions) ProfileMenuItem.entries.toList() else emptyList()

    fun menuLabel(item: ProfileMenuItem): String = when (item) {
        ProfileMenuItem.FAVORITE -> if (isFavorite) L.user.removeFromFavourites else L.user.addToFavourites
        ProfileMenuItem.DELEGATE -> L.user.delegate
        ProfileMenuItem.MUTE -> if (isMuted) L.user.unmute else L.user.mute
        ProfileMenuItem.REPORT -> L.user.report
    }

    val followLabel: String get() = if (isFollowing) L.user.unfollow else L.user.follow

    val tab: ProfileTab get() = tabs.getOrElse(selectedTab) { tabs.first() }
}

/**
 * One profile screen (RN profileContainer + profileView): the header, follow / mute / favourite / report
 * actions and the tab feeds. The observer is the signed-in account; a session change reloads in place.
 */
class ProfileModel internal constructor(
    private val env: AppEnvironment,
    val username: String,
    private val section: String,
) : StateModel<ProfileState>(initialState(env, username, section), env.dispatchers) {

    private val feeds = mutableMapOf<ProfileTab, FeedModel>()
    private var job: Job? = null
    private var actionJob: Job? = null
    private var account: Account? = null
    private var rc: RcAccount? = null

    init {
        // the observer: the signed-in account once the session was read, and every change after that
        scope.launch {
            env.session.state.map { if (it.isLoading) LOADING else it.username }.distinctUntilChanged().drop(1).collect { user ->
                if (user == LOADING) return@collect
                val own = user == username
                val (tabs, selected) = ProfileTab.forSection(own, section)
                setState {
                    it.copy(
                        isOwn = own, loggedIn = user != null, isFollowing = false, isMuted = false, isFavorite = false, busy = false,
                        tabs = tabs, selectedTab = if (tabs == it.tabs) it.selectedTab else selected,
                    )
                }
                if (current.loaded || job?.isActive == true) load(refresh = false)
            }
        }
        scope.launch {
            env.events.collect { event ->
                // the edit put the new account in the cache: read it from there (the node may not have the block yet)
                if (event is AppEvent.ProfileUpdated && event.username == username) load(refresh = false)
            }
        }
    }

    /** The feed of [tab] (blog, posts, comments, replies), created on first use and closed with this model. */
    fun feed(tab: ProfileTab): FeedModel? {
        val sort = tab.sort ?: return null
        return feeds.getOrPut(tab) { env.feedModel(FeedSource.Account(username, sort)) }
    }

    private var wavesModel: WavesFeedModel? = null

    /**
     * The Waves tab's list (RN wavesTabContent): this author's waves across every container, without the mute
     * filter and without promoted waves. Owned and closed by this model, like the tab feeds.
     */
    fun waves(): WavesFeedModel = wavesModel ?: env.authorWavesModel(username).also { wavesModel = it }

    fun selectTab(index: Int) {
        if (index in current.tabs.indices) setState { it.copy(selectedTab = index) }
    }

    fun start() {
        if (current.loaded || job?.isActive == true) return
        load(refresh = false)
    }

    /** Pull to refresh: the header and the open tab. */
    fun refresh() {
        load(refresh = true)
        feeds[current.tab]?.refresh()
        if (current.tab == ProfileTab.WAVES) wavesModel?.refresh()
    }

    fun retry() = load(refresh = true)

    private fun load(refresh: Boolean) {
        job?.cancel()
        setState { it.copy(isLoading = it.header == null, error = null) }
        job = scope.launch {
            try {
                env.session.awaitLoaded()
                val observer = env.session.current.username
                val acc = env.api.accounts.getAccount(username, refresh)
                if (acc == null) {
                    setState { it.copy(isLoading = false, loaded = true, notFound = true, error = AppError(ErrorKind.NOT_FOUND, L.native.profile.accountNotFound, false)) }
                    return@launch
                }
                val own = observer == username
                val loaded = coroutineScope {
                    val profile = async { quiet { env.api.accounts.getProfile(username, refresh = refresh) } }
                    val rcAccount = async { quiet { env.api.hive.rcAccount(username) } }
                    val counts = async { quiet { env.api.hive.followCount(username) } }
                    val relation = async { if (observer != null && !own) quiet { env.api.hive.relationship(observer, username) } else null }
                    val favorite = async { if (observer != null && !own) quiet { env.accountApi.isFavorite(observer, username) } else null }
                    val pro = async { quiet { username.lowercase() in env.accountApi.proMembers() } }
                    Loaded(profile.await(), rcAccount.await(), counts.await(), relation.await(), favorite.await(), pro.await())
                }
                account = acc
                rc = loaded.rc
                val header = header(acc, loaded.profile, loaded.counts, loaded.rc)
                setState {
                    it.copy(
                        header = header,
                        isLoading = false,
                        loaded = true,
                        notFound = false,
                        isOwn = own,
                        loggedIn = observer != null,
                        isFollowing = loaded.relation?.follows ?: (if (own) false else it.isFollowing),
                        isMuted = loaded.relation?.ignores ?: (if (own) false else it.isMuted),
                        isFavorite = loaded.favorite ?: it.isFavorite,
                        isPro = loaded.pro == true,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setState { it.copy(isLoading = false, loaded = true, error = AppError.from(e)) }
            }
        }
    }

    private class Loaded(
        val profile: Profile?,
        val rc: RcAccount?,
        val counts: FollowCount?,
        val relation: Relationship?,
        val favorite: Boolean?,
        val pro: Boolean?,
    )

    private fun header(acc: Account, profile: Profile?, counts: FollowCount?, rc: RcAccount?): ProfileHeader {
        val now = env.nowMillis()
        val vp = Mana.oneDecimal(Mana.votingPowerPercent(acc, now))
        val rcPercent = Mana.oneDecimal(Mana.rcPercent(rc, now))
        val p = acc.profile
        val pinned = (ProfileMetadataReader.profile(acc)["pinned"] as? JsValue.Str)?.value?.takeIf { it.isNotEmpty() }
        return ProfileHeader(
            username = username,
            displayName = p.displayName ?: username,
            about = p.about,
            location = p.location,
            website = p.website,
            websiteUrl = ProfileFormat.websiteUrl(p.website),
            coverUrl = p.coverImage?.let { ImageProxy(env.proxyBase).proxify(it, 640, 320) }.orEmpty(),
            avatarUrl = env.avatars.url(username, "large", env.proxyBase),
            joined = ProfileFormat.joined(acc.created),
            reputation = profile?.reputation ?: acc.reputation,
            // RN reads get_follow_count; the bridge profile's stats stand in when that call failed
            followers = counts?.followers ?: profile?.followers ?: 0,
            following = counts?.following ?: profile?.following ?: 0,
            votingPower = vp,
            resourceCredits = rcPercent,
            votingPowerHours = ProfileFormat.hoursToFull(vp),
            resourceCreditsHours = ProfileFormat.hoursToFull(rcPercent),
            pinned = pinned,
        )
    }

    // ---- actions (RN profileContainer) ----

    /** Follow, or unfollow when following (RN _handleFollowUnfollowUser). */
    fun toggleFollow() {
        val s = current
        if (!canAct(s)) return
        val follow = !s.isFollowing
        act(followOp(if (follow) "blog" else null), if (follow) L.alert.successFollow else L.alert.successUnfollow) { st ->
            // following replaces an ignore on chain (one "what" per pair)
            st.copy(isFollowing = follow, isMuted = if (follow) false else st.isMuted)
        }
    }

    /** Mute (ignore), or unmute (RN _handleMuteUnmuteUser). */
    fun toggleMute() {
        val s = current
        if (!canAct(s)) return
        val mute = !s.isMuted
        // RN unmutes with the unfollow operation and its "Unfollow success!" toast, and keeps showing the
        // muted view until a reload; here the state and the toast say what happened
        act(followOp(if (mute) "ignore" else null), if (mute) L.alert.successMute else L.alert.successUnmute) { st ->
            st.copy(isMuted = mute, isFollowing = false)
        }
    }

    /** Add to or remove from favourites (Ecency API). */
    fun toggleFavorite() {
        val s = current
        val user = env.session.current.username
        if (!canAct(s) || user == null) return
        val add = !s.isFavorite
        setState { it.copy(busy = true) }
        actionJob = scope.launch {
            try {
                if (add) env.accountApi.addFavorite(user, username) else env.accountApi.deleteFavorite(user, username)
                setState { it.copy(busy = false, isFavorite = add) }
                env.ui.toast(L.alert.success)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setState { it.copy(busy = false, alert = AppError.from(e)) }
            }
        }
    }

    /**
     * Report the account, after the UI asked "Are you sure you want report?". "Reported objectionable content"
     * only when the report went through (RN toasts it either way, #3599); a failure shows the error and does not
     * mute. A report that went through mutes the account, as RN.
     */
    fun report() {
        val s = current
        val user = env.session.current.username
        if (!canAct(s) || user == null) return
        setState { it.copy(busy = true) }
        actionJob = scope.launch {
            val ok = try {
                env.accountApi.reportAccount(user, username)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setState { it.copy(busy = false, alert = AppError.from(e)) }
                return@launch
            }
            env.ui.toast(L.report.added)
            // RN auto-mutes the reported account
            val ops = if (ok && !current.isMuted) followOp("ignore") else null
            if (ops == null) setState { it.copy(busy = false) }
            else perform(ops, L.alert.successMute) { st -> st.copy(isMuted = true, isFollowing = false) }
        }
    }

    fun dismissAlert() = setState { it.copy(alert = null) }

    private fun canAct(s: ProfileState): Boolean =
        s.showsActions && !s.busy && env.session.current.username != null && actionJob?.isActive != true

    /** SDK buildFollowOp / buildUnfollowOp / buildIgnoreOp: what = [what] or nothing. */
    private fun followOp(what: String?): JsonArray? {
        val follower = env.session.current.username ?: return null
        val payload = JsValue.Arr(
            listOf(
                JsValue.Str("follow"),
                JsValue.Obj(
                    mapOf(
                        "follower" to JsValue.Str(follower),
                        "following" to JsValue.Str(username),
                        "what" to JsValue.Arr(listOfNotNull(what?.let { JsValue.Str(it) })),
                    ),
                ),
            ),
        )
        val op = CustomJson(emptyList(), listOf(follower), "follow", JsValue.stringify(payload)!!)
        return JsonArray(listOf(OperationJson.encode(op)))
    }

    private fun act(ops: JsonArray?, success: String, update: (ProfileState) -> ProfileState) {
        if (ops == null || env.session.current.username == null) return
        setState { it.copy(busy = true) }
        actionJob = scope.launch { perform(ops, success, update) }
    }

    /** Signs and broadcasts [ops] as the signed-in account, then applies [update] (busy is cleared either way). */
    private suspend fun perform(ops: JsonArray, success: String, update: (ProfileState) -> ProfileState) {
        val user = env.session.current.username ?: return setState { it.copy(busy = false) }
        try {
            env.signing.broadcast(user, ops, KeyRole.POSTING)
            // the account changed while signing: this result belongs to the previous one (no state, no toast)
            if (env.session.current.username != user) return setState { it.copy(busy = false) }
            setState { update(it).copy(busy = false) }
            // the app-wide ignore list follows every mute, unmute and follow made here (RN currentAccount.mutes)
            env.setAuthorMuted(username, current.isMuted)
            env.ui.toast(success)
            refreshCounts()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val error = AppError.from(e)
            // RN profileContainer: out of RC is the app-wide offer (top up or boost), not a dead-end alert
            val offered = error.kind != ErrorKind.CANCELLED && env.offerRcIfShort(e)
            setState { it.copy(busy = false, alert = if (error.kind == ErrorKind.CANCELLED || offered) null else error) }
        }
    }

    /** Follower counts after a follow change (the node may take a block to show it). */
    private suspend fun refreshCounts() {
        val counts = quiet { env.api.hive.followCount(username) } ?: return
        setState { s -> s.copy(header = s.header?.copy(followers = counts.followers, following = counts.following)) }
    }

    override fun close() {
        feeds.values.forEach { it.close() }
        feeds.clear()
        wavesModel?.close()
        wavesModel = null
        super.close()
    }

    companion object {
        /** A session still being read (no observer known yet). */
        private const val LOADING = "\u0000loading"

        private fun initialState(env: AppEnvironment, username: String, section: String): ProfileState {
            val observer = env.session.current.username
            val own = observer == username
            val (tabs, selected) = ProfileTab.forSection(own, section)
            return ProfileState(username = username, isOwn = own, loggedIn = observer != null, tabs = tabs, selectedTab = selected)
        }

        private suspend fun <T> quiet(block: suspend () -> T): T? = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
}

/** The raw profile object of an account's posting metadata (what a profile edit starts from). */
internal object ProfileMetadataReader {
    fun profile(account: Account): JsValue.Obj = com.ecency.core.api.model.ProfileMetadata.parseProfile(account.postingJsonMetadata)
}

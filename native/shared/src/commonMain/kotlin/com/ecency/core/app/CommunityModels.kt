package com.ecency.core.app

import com.ecency.core.api.RankedSort
import com.ecency.core.api.broadcast.CommunityProps
import com.ecency.core.api.community.Community
import com.ecency.core.api.community.CommunityActivity
import com.ecency.core.api.community.CommunityNames
import com.ecency.core.api.community.CommunityRole
import com.ecency.core.api.community.CommunitySort
import com.ecency.core.api.community.Subscriber
import com.ecency.core.api.repository.FeedSource
import com.ecency.core.api.broadcast.codePointCount
import com.ecency.core.l10n.L
import com.ecency.core.render.blocks.SafeTarget
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** A community row with the current account's join state (RN communitiesListItem). */
data class CommunityRow(
    val name: String,
    val title: String,
    val about: String,
    val subscribers: Long,
    val authors: Long,
    val posts: Long,
    val isNsfw: Boolean,
    val subscribed: Boolean,
    /** A join or leave is in flight (RN shows a spinner instead of the button). */
    val pending: Boolean,
) {
    /** RN "<subscribers> Members • <authors> Posters • <posts> Posts". */
    val stats: String get() = CommunityCopy.stats(subscribers, authors, posts)
}

/** A row of the joined tab (RN subscribedCommunitiesList). */
data class JoinedRow(val name: String, val title: String, val role: String, val subscribed: Boolean, val pending: Boolean)

/** RN en-US copy shared by the community screens. */
object CommunityCopy {
    val JOIN: String get() = L.chats.join
    val LEAVE: String get() = L.searchResult.communities.unsubscribe
    val TITLE: String get() = L.home.communities
    val JOINED_TAB: String get() = L.communities.joined
    val DISCOVER_TAB: String get() = L.communities.discover
    val NO_COMMUNITIES: String get() = L.communities.noCommunities
    val DISCOVER_COMMUNITIES: String get() = L.communities.discoverCommunities
    val DETAILS: String get() = L.community.details
    val NEW_POST: String get() = L.community.newPost
    val NEWSLETTER: String get() = L.newsletter.communityButton
    val MANAGE: String get() = L.community.manage
    val MANAGE_MEMBERS: String get() = L.community.manageMembers
    val MANAGE_SETTINGS: String get() = L.community.manageSettings
    val MANAGE_ACTIVITIES: String get() = L.community.manageActivities
    val SET_ROLE: String get() = L.community.setRole
    val ASSIGN_ROLE: String get() = L.community.assignRole
    val ASSIGN_ROLE_HINT: String get() = L.community.assignRoleHint
    val USERNAME_PLACEHOLDER: String get() = L.community.usernamePlaceholder
    val INVALID_USERNAME: String get() = L.community.invalidUsername
    val NO_MEMBERS: String get() = L.chats.noMembers
    val MEMBERS_INCOMPLETE: String get() = L.community.membersIncomplete
    val MEMBERS_LOAD_FAILED: String get() = L.community.membersLoadFailed
    val NO_ACTIVITIES: String get() = L.community.noActivities
    val ACTIVITIES_LOAD_FAILED: String get() = L.community.activitiesLoadFailed
    val CANCEL: String get() = L.alert.cancel
    val FAIL: String get() = L.alert.fail
    val SUCCESSFUL: String get() = L.alert.successful

    fun stats(subscribers: Long, authors: Long, posts: Long) = L.native.community.stats(subscribers = subscribers.toInt(), authors = authors.toInt(), posts = posts.toInt())

    /** RN header titles: "<title> community", "<title> members", "<title> activity". */
    fun communityTitle(title: String) = L.native.community.communityTitle(title = title).trim()
    fun membersTitle(title: String) = L.native.community.membersTitle(title = title)
    fun activitiesTitle(title: String) = L.native.community.activitiesTitle(title = title)
}

/** Feed tabs of a community (RN DEFAULT_COMMUNITY_FILTERS with COMMUNITY_SCREEN_FILTER_MAP labels). */
enum class CommunityTab(val sort: RankedSort, val filter: String, private val labelText: () -> String) {
    TRENDING(RankedSort.TRENDING, "trending", { L.home.trending }),
    HOT(RankedSort.HOT, "hot", { L.home.hot }),
    NEW(RankedSort.CREATED, "created", { L.home.new }),
    ;

    val label: String get() = labelText()

    companion object {
        /** RN _getSelectedIndex: the tab named by a link's filter, else the first. */
        fun of(filter: String?): CommunityTab = entries.firstOrNull { it.filter == filter } ?: TRENDING
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Communities screen (RN screens/communities): Membership and Discover tabs

data class CommunitiesState(
    val joined: List<JoinedRow> = emptyList(),
    val discover: List<CommunityRow> = emptyList(),
    val joinedLoading: Boolean = false,
    val discoverLoading: Boolean = false,
    val joinedError: AppError? = null,
    val discoverError: AppError? = null,
    val loggedIn: Boolean = false,
)

/**
 * RN communitiesContainer: the account's subscriptions (sorted by title) and the top 50 communities by rank
 * with their join state. Joins and leaves go through [CommunityService] and show on every list at once.
 */
class CommunitiesModel(private val env: AppEnvironment) : StateModel<CommunitiesState>(CommunitiesState(), env.dispatchers) {
    private var discover: List<Community> = emptyList()
    private var job: Job? = null

    init {
        scope.launch { env.communities.state.collect { rebuild() } }
        scope.launch {
            env.events.collect {
                if (it is AppEvent.SessionChanged) {
                    // the rows were fetched for the previous observer: its join state must not show for this account
                    discover = discover.map { c -> c.copy(observerRole = null, observerSubscribed = null) }
                    rebuild()
                    load()
                }
            }
        }
    }

    fun start() {
        if (job != null) return
        load()
    }

    /** Pull to refresh (both lists). */
    fun refresh() {
        env.communities.refresh()
        load()
    }

    /** False when signed out: the UI opens the login screen. */
    fun toggle(row: CommunityRow): Boolean = env.communities.toggleSubscription(row.name, row.title, row.subscribed)

    fun toggleJoined(row: JoinedRow): Boolean = env.communities.toggleSubscription(row.name, row.title, row.subscribed)

    private fun load() {
        env.communities.start()
        job?.cancel()
        setState { it.copy(discoverLoading = true, discoverError = null) }
        job = scope.launch {
            try {
                discover = env.communityApi.communities(CommunitySort.RANK, null, DISCOVER_LIMIT, env.session.current.username)
                setState { it.copy(discoverLoading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setState { it.copy(discoverLoading = false, discoverError = AppError.from(e)) }
            }
            rebuild()
        }
    }

    private fun rebuild() {
        val subs = env.communities.current
        setState { s ->
            s.copy(
                loggedIn = env.session.current.isLoggedIn,
                joined = subs.joined.map { (sub, joined) -> JoinedRow(sub.community, sub.title, sub.role, joined, sub.community in subs.pending) },
                joinedLoading = subs.loading && !subs.loaded,
                joinedError = subs.error,
                discover = discover.map { row(it, subs) },
            )
        }
    }

    companion object {
        /** RN fetches 50 communities by rank. */
        const val DISCOVER_LIMIT = 50

        internal fun row(c: Community, subs: SubscriptionsState) = CommunityRow(
            c.name, c.title, c.about, c.subscribers, c.numAuthors, c.numPending, c.isNsfw,
            subscribed = subs.isSubscribed(c.name, c.observerSubscribed),
            pending = c.name in subs.pending,
        )
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Community page (RN screens/community): header, details card, join / new post / newsletter, feed tabs

data class CommunityState(
    val name: String,
    val community: Community? = null,
    val loading: Boolean = false,
    val error: AppError? = null,
    val subscribed: Boolean = false,
    val pending: Boolean = false,
    val loggedIn: Boolean = false,
    /** Owner, admin or mod: the header shows the manage button (RN shield icon). */
    val isModerator: Boolean = false,
) {
    /** RN header: "<title> community" (empty title while loading). */
    val headerTitle: String get() = CommunityCopy.communityTitle(community?.title.orEmpty())
    val stats: String? get() = community?.let { CommunityCopy.stats(it.subscribers, it.numAuthors, it.numPending) }
}

/** What a moderator picks in the manage sheet (RN communityManageSheet rows, in order). */
enum class CommunityManageAction(private val labelText: () -> String, val icon: AppIcon) {
    MEMBERS({ CommunityCopy.MANAGE_MEMBERS }, AppIcon.MEMBERS),
    SETTINGS({ CommunityCopy.MANAGE_SETTINGS }, AppIcon.SETTINGS),
    ACTIVITIES({ CommunityCopy.MANAGE_ACTIVITIES }, AppIcon.HISTORY),
    ;

    val label: String get() = labelText()
}

/**
 * RN communityContainer: the community with the observer's context, the join state (this session's change, else
 * the subscription list, else the community's own context) and moderator authority from the team. The feed tabs
 * are ordinary [FeedModel]s of [feedSource].
 */
class CommunityModel(private val env: AppEnvironment, val name: String) :
    StateModel<CommunityState>(CommunityState(name), env.dispatchers) {
    private var job: Job? = null

    init {
        scope.launch { env.communities.state.collect { rebuild() } }
        scope.launch {
            env.events.collect {
                if (it is AppEvent.SessionChanged) {
                    // fetched for the previous observer: until the reload answers, the new account's own list decides
                    setState { s -> s.copy(community = s.community?.copy(observerRole = null, observerSubscribed = null)) }
                    rebuild()
                    if (job != null) load()
                }
            }
        }
    }

    fun start() {
        if (job != null) return
        load()
    }

    fun refresh() = load()

    fun retry() = load()

    /** The feed of one tab. */
    fun feedSource(tab: CommunityTab): FeedSource = FeedSource.Ranked(tab.sort, name)

    /** Join or leave; false when signed out (the UI opens the login screen). */
    fun toggleSubscription(): Boolean = env.communities.toggleSubscription(name, current.community?.title ?: name, current.subscribed)

    /** The screen for a manage sheet pick. */
    fun screenFor(action: CommunityManageAction): AppScreen {
        val title = current.community?.title.orEmpty()
        return when (action) {
            CommunityManageAction.MEMBERS -> AppScreen.CommunityMembers(name, title)
            CommunityManageAction.SETTINGS -> AppScreen.CommunitySettings(name)
            CommunityManageAction.ACTIVITIES -> AppScreen.CommunityActivities(name, title)
        }
    }

    /** RN New Post: the editor for this community (a later wave; its placeholder carries the community). */
    val newPostScreen: AppScreen get() = AppScreen.of(RouteName.EDITOR, name)

    /** RN newsletter digest sheet for the community (a later wave). */
    val newsletterScreen: AppScreen get() = AppScreen.of(RouteName.EMAIL_DIGESTS, name)

    private fun load() {
        env.communities.start()
        job?.cancel()
        setState { it.copy(loading = it.community == null, error = null) }
        job = scope.launch {
            try {
                val c = env.communityApi.community(name, env.session.current.username)
                setState { it.copy(community = c, loading = false, error = if (c == null) AppError(ErrorKind.NOT_FOUND, L.native.community.communityNotFound, false) else null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setState { it.copy(loading = false, error = AppError.from(e)) }
            }
            rebuild()
        }
    }

    private fun rebuild() {
        val subs = env.communities.current
        val user = env.session.current.username
        setState { s ->
            s.copy(
                loggedIn = user != null,
                subscribed = user != null && subs.isSubscribed(name, s.community?.observerSubscribed),
                pending = name in subs.pending,
                isModerator = s.community?.isModerator(user) == true,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Members and roles (RN screens/communityMembers, communityRoleEditSheet)

data class MemberRow(val account: String, val role: CommunityRole, val title: String, val editable: Boolean) {
    val roleLabel: String get() = role.label
}

data class CommunityMembersState(
    val name: String,
    val title: String,
    val members: List<MemberRow> = emptyList(),
    /** Roles the current account may hand out; empty for non-moderators (no add button, no editable chips). */
    val assignableRoles: List<CommunityRole> = emptyList(),
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val endReached: Boolean = false,
    val error: AppError? = null,
    /** A role change in flight, by account. */
    val saving: Set<String> = emptySet(),
    /** RN Alert "Fail!" with the error's message, until [CommunityMembersModel.dismissAlert]. */
    val alert: String? = null,
) {
    val headerTitle: String get() = CommunityCopy.membersTitle(title.ifEmpty { name })

    /** RN: an empty roster says "no members" only when nothing failed. */
    val emptyText: String? get() = when {
        loading || members.isNotEmpty() -> null
        error != null -> CommunityCopy.MEMBERS_LOAD_FAILED
        else -> CommunityCopy.NO_MEMBERS
    }

    /** RN footer: a partially loaded roster must not read as complete. */
    val footerNote: String? get() = if (error != null && members.isNotEmpty()) CommunityCopy.MEMBERS_INCOMPLETE else null
}

/**
 * RN communityMembersScreen: the team (authoritative roles) then every subscriber, the community account itself
 * dropped, sorted owner, admin, mod, member, guest, muted, then by name. Roles a moderator may assign follow the
 * SDK roleMap; a row is editable only when its current role is one of them and it is not the moderator's own.
 */
class CommunityMembersModel(private val env: AppEnvironment, val name: String, title: String) :
    StateModel<CommunityMembersState>(CommunityMembersState(name, title), env.dispatchers) {
    private var team: List<com.ecency.core.api.community.TeamMember> = emptyList()
    private var subscribers: List<Subscriber> = emptyList()
    private var cursor: String? = null
    private var patched: Map<String, String> = emptyMap()
    private var job: Job? = null

    fun start() {
        if (job != null) return
        load(refresh = false)
    }

    fun refresh() = load(refresh = true)

    fun loadMore() {
        val s = current
        if (job?.isActive == true || s.endReached || cursor == null) return
        setState { it.copy(loadingMore = true) }
        job = scope.launch {
            try {
                val page = env.communityApi.subscribers(name, cursor)
                subscribers = subscribers + page.items
                cursor = page.next
                setState { it.copy(loadingMore = false, endReached = page.next == null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setState { it.copy(loadingMore = false, error = AppError.from(e)) }
            }
            rebuild()
        }
    }

    /**
     * Sets [account]'s role; on success the row updates in place (hivemind lags behind the broadcast). Hivemind
     * drops a setRole it does not accept without telling the sender, so the same checks run here first and a
     * refusal shows its reason instead of a broadcast: the target's current role comes from the roster, or from
     * `get_community_context` for an account that is not on it (typed in the assign sheet).
     */
    fun setRole(account: String, role: CommunityRole) {
        if (account in current.saving) return
        setState { it.copy(saving = it.saving + account) }
        scope.launch {
            try {
                val me = env.session.current.username
                val actor = team.firstOrNull { it.account == me }?.let { CommunityRole.of(patched[it.account] ?: it.role) }
                val known = patched[account] ?: team.firstOrNull { it.account == account }?.role ?: subscribers.firstOrNull { it.account == account }?.role
                val currentRole = CommunityRole.of((known ?: env.communityApi.role(account, name)).ifEmpty { "guest" }) ?: CommunityRole.GUEST
                // hivemind counts every role of mod or above except the target's own (its moderator limit)
                val otherModerators = team.count { it.account != account && (CommunityRole.of(patched[it.account] ?: it.role)?.rank ?: 0) >= CommunityRole.MOD.rank } +
                    patched.count { (who, r) -> who != account && team.none { it.account == who } && (CommunityRole.of(r)?.rank ?: 0) >= CommunityRole.MOD.rank }
                CommunityRole.setRoleRefusal(actor, currentRole, role, self = account == me, otherModerators = otherModerators)?.let { reason ->
                    setState { it.copy(saving = it.saving - account, alert = reason) }
                    return@launch
                }
                env.communities.setRole(name, account, role.value)
                patched = patched + (account to role.value)
                env.ui.toast(CommunityCopy.SUCCESSFUL)
                setState { it.copy(saving = it.saving - account) }
                rebuild()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val error = AppError.from(e)
                setState { it.copy(saving = it.saving - account, alert = if (error.kind == ErrorKind.CANCELLED) null else error.message) }
            }
        }
    }

    fun dismissAlert() = setState { it.copy(alert = null) }

    private fun load(refresh: Boolean) {
        job?.cancel()
        setState { it.copy(loading = !refresh && it.members.isEmpty(), refreshing = refresh, error = null) }
        job = scope.launch {
            var failure: AppError? = null
            try {
                val community = env.communityApi.community(name, env.session.current.username)
                team = community?.team.orEmpty()
                if (current.title.isEmpty() && community != null) setState { it.copy(title = community.title) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = AppError.from(e)
            }
            try {
                val page = env.communityApi.subscribers(name, null)
                subscribers = page.items
                cursor = page.next
                setState { it.copy(endReached = page.next == null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = failure ?: AppError.from(e)
            }
            if (refresh) patched = emptyMap()
            setState { it.copy(loading = false, refreshing = false, error = failure) }
            rebuild()
        }
    }

    private fun rebuild() {
        val me = env.session.current.username
        val myRole = team.firstOrNull { it.account == me }?.let { CommunityRole.of(patched[it.account] ?: it.role) }
        val assignable = CommunityRole.assignable(myRole)
        val members = merge(team.map { Triple(it.account, it.role, it.title) } + subscribers.map { Triple(it.account, it.role, it.title) }, patched)
            .map { (account, role, title) -> MemberRow(account, role, title, assignable.isNotEmpty() && account != me && role in assignable) }
        setState { it.copy(members = members, assignableRoles = assignable) }
    }

    companion object {
        /** Team first (its role wins), then subscribers; community accounts dropped; sorted by role, then name. */
        internal fun merge(rows: List<Triple<String, String, String>>, patched: Map<String, String> = emptyMap()): List<Triple<String, CommunityRole, String>> {
            val byAccount = LinkedHashMap<String, Triple<String, CommunityRole, String>>()
            for ((account, role, title) in rows) {
                if (account.isEmpty() || CommunityNames.looksLikeCommunityAccount(account) || account in byAccount) continue
                val r = CommunityRole.of(patched[account] ?: role.ifEmpty { "guest" }) ?: CommunityRole.GUEST
                byAccount[account] = Triple(account, r, title)
            }
            return byAccount.values.sortedWith(compareBy<Triple<String, CommunityRole, String>> { it.second.ordinal }.thenBy { it.first })
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Community settings (RN screens/communitySettings)

data class CommunitySettingsForm(
    val title: String = "",
    val about: String = "",
    val lang: String = "",
    val description: String = "",
    /** The rules. */
    val rules: String = "",
    val isNsfw: Boolean = false,
) {
    // Hivemind `_read_props`, on the trimmed values [props] sends (it refuses padded strings); lengths are code
    // points, as Python counts. RN's own rules (title, about and language required) stay on top.

    /** Required, 3 to 20 characters, not starting with @ or #. */
    val titleError: String? get() {
        val t = title.trim()
        val n = t.codePointCount()
        return when {
            t.isEmpty() -> REQUIRED
            n < TITLE_MIN || n > TITLE_MAX -> L.native.plurals.communitySettings.titleLength(min = TITLE_MIN.toString(), max = TITLE_MAX)
            t[0] == '@' || t[0] == '#' -> L.native.community.cannotStartWithOr
            else -> null
        }
    }
    val aboutError: String? get() = about.trim().let { if (it.isEmpty()) REQUIRED else tooLong(it, ABOUT_MAX) }
    val langError: String? get() = lang.trim().let {
        when {
            it.isEmpty() -> REQUIRED
            it !in LANGS -> L.native.community.useATwoLetter
            else -> null
        }
    }
    val descriptionError: String? get() = tooLong(description.trim(), TEXT_MAX)
    val rulesError: String? get() = tooLong(rules.trim(), TEXT_MAX)
    val isValid: Boolean get() = listOf(titleError, aboutError, langError, descriptionError, rulesError).all { it == null }

    private fun tooLong(value: String, max: Int): String? = if (value.codePointCount() > max) L.native.community.atMostCharacters(max = max) else null

    /** Trimmed on the way out, as RN and web do. */
    fun props() = CommunityProps(title.trim(), about.trim(), lang.trim(), description.trim(), rules.trim(), isNsfw)

    companion object {
        const val TITLE_MIN = 3
        const val TITLE_MAX = 20
        const val ABOUT_MAX = 120
        const val TEXT_MAX = 1000
        val REQUIRED: String get() = L.communitySettings.required

        /** Hivemind's LANGS (ISO 639-1 as it lists them). */
        val LANGS: Set<String> = (
            "ab,aa,af,ak,sq,am,ar,an,hy,as,av,ae,ay,az,bm,ba,eu,be,bn,bh,bi," +
                "bs,br,bg,my,ca,ch,ce,ny,zh,cv,kw,co,cr,hr,cs,da,dv,nl,dz,en,eo," +
                "et,ee,fo,fj,fi,fr,ff,gl,ka,de,el,gn,gu,ht,ha,he,hz,hi,ho,hu,ia," +
                "id,ie,ga,ig,ik,io,is,it,iu,ja,jv,kl,kn,kr,ks,kk,km,ki,rw,ky,kv," +
                "kg,ko,ku,kj,la,lb,lg,li,ln,lo,lt,lu,lv,gv,mk,mg,ms,ml,mt,mi,mr," +
                "mh,mn,na,nv,nd,ne,ng,nb,nn,no,ii,nr,oc,oj,cu,om,or,os,pa,pi,fa," +
                "pl,ps,pt,qu,rm,rn,ro,ru,sa,sc,sd,se,sm,sg,sr,gd,sn,si,sk,sl,so," +
                "st,es,su,sw,ss,sv,ta,te,tg,th,ti,bo,tk,tl,tn,to,tr,ts,tt,tw,ty," +
                "ug,uk,ur,uz,ve,vi,vo,wa,cy,wo,fy,xh,yi,yo,za"
            ).split(',').toSet()

        fun of(c: Community) = CommunitySettingsForm(c.title, c.about, c.lang, c.description, c.flagText, c.isNsfw)
    }
}

data class CommunitySettingsState(
    val name: String,
    val form: CommunitySettingsForm = CommunitySettingsForm(),
    val loading: Boolean = false,
    val error: AppError? = null,
    /** Owner or admin; anyone else sees the settings read-only with RN's notice. */
    val canEdit: Boolean = false,
    val saving: Boolean = false,
    /** Saved: the UI goes back (RN navigation.goBack after the toast). */
    val saved: Boolean = false,
    val alert: String? = null,
) {
    val notice: String? get() = if (!canEdit && !loading) OWNER_ADMIN_ONLY else null

    companion object {
        val HEADER: String get() = L.community.manageSettings
        val OWNER_ADMIN_ONLY: String get() = L.communitySettings.ownerAdminOnly
        val SAVE: String get() = L.beneficiaryModal.save
        // field labels (RN en-US community_settings.*)
        val TITLE_LABEL: String get() = L.communitySettings.titleLabel
        val ABOUT_LABEL: String get() = L.profile.edit.about
        val LANG_LABEL: String get() = L.communitySettings.lang
        val DESCRIPTION_LABEL: String get() = L.communitySettings.description
        val RULES_LABEL: String get() = L.communitySettings.rules
        val NSFW_LABEL: String get() = L.communitySettings.nsfw
    }
}

/** RN communitySettingsScreen: the props form, editable by owner and admin, saved as one updateProps. */
class CommunitySettingsModel(private val env: AppEnvironment, val name: String) :
    StateModel<CommunitySettingsState>(CommunitySettingsState(name), env.dispatchers) {
    private var started = false
    private var loadJob: Job? = null

    fun start() {
        if (started) return
        started = true
        load()
    }

    fun retry() = load()

    fun update(form: CommunitySettingsForm) {
        if (!current.canEdit || current.saving) return
        setState { it.copy(form = form) }
    }

    fun save() {
        val s = current
        if (!s.canEdit || s.saving || !s.form.isValid) return
        setState { it.copy(saving = true) }
        scope.launch {
            try {
                env.communities.updateProps(name, s.form.props())
                env.ui.toast(CommunityCopy.SUCCESSFUL)
                setState { it.copy(saving = false, saved = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val error = AppError.from(e)
                setState { it.copy(saving = false, alert = if (error.kind == ErrorKind.CANCELLED) null else error.message) }
            }
        }
    }

    fun dismissAlert() = setState { it.copy(alert = null) }

    private fun load() {
        loadJob?.cancel() // a retry never races the load it replaces
        setState { it.copy(loading = true, error = null) }
        loadJob = scope.launch {
            try {
                val c = env.communityApi.community(name, env.session.current.username)
                if (c == null) {
                    setState { it.copy(loading = false, error = AppError(ErrorKind.NOT_FOUND, L.native.community.communityNotFound, false)) }
                } else {
                    setState { it.copy(loading = false, form = CommunitySettingsForm.of(c), canEdit = c.canEditSettings(env.session.current.username)) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setState { it.copy(loading = false, error = AppError.from(e)) }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Activity log (RN screens/communityActivities)

/** One piece of an activity message: plain text, or a tappable `@account` / `@author/permlink`. */
data class ActivityPart(val text: String, val account: String? = null, val permlink: String? = null) {
    val isLink: Boolean get() = account != null
}

data class ActivityRow(val id: String, val actor: String?, val parts: List<ActivityPart>, val date: String, val epochMillis: Long)

data class CommunityActivitiesState(
    val name: String,
    val title: String,
    val items: List<ActivityRow> = emptyList(),
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val endReached: Boolean = false,
    val error: AppError? = null,
) {
    val headerTitle: String get() = CommunityCopy.activitiesTitle(title.ifEmpty { name })
    val emptyText: String? get() = when {
        loading || items.isNotEmpty() -> null
        error != null -> CommunityCopy.ACTIVITIES_LOAD_FAILED
        else -> CommunityCopy.NO_ACTIVITIES
    }
}

/** RN communityActivitiesScreen: `bridge.account_notifications` of the community, 50 a page, mentions tappable. */
class CommunityActivitiesModel(private val env: AppEnvironment, val name: String, title: String) :
    StateModel<CommunityActivitiesState>(CommunityActivitiesState(name, title), env.dispatchers) {
    private var cursor: String? = null
    private var job: Job? = null

    fun start() {
        if (job != null) return
        load(reset = true, refreshing = false)
    }

    fun refresh() = load(reset = true, refreshing = true)

    fun loadMore() {
        if (job?.isActive == true || current.endReached || cursor == null || current.error != null) return
        load(reset = false, refreshing = false)
    }

    fun retry() = load(reset = current.items.isEmpty(), refreshing = false)

    private fun load(reset: Boolean, refreshing: Boolean) {
        job?.cancel()
        setState { it.copy(loading = reset && !refreshing && it.items.isEmpty(), refreshing = refreshing, loadingMore = !reset, error = null) }
        val from = if (reset) null else cursor
        job = scope.launch {
            try {
                val page = env.communityApi.activities(name, com.ecency.core.api.community.CommunityApi.ACTIVITY_PAGE_SIZE, from)
                cursor = page.next
                val rows = page.items.map(::row)
                setState { s ->
                    val items = if (reset) rows else (s.items + rows).distinctBy { it.id }
                    s.copy(items = items, loading = false, refreshing = false, loadingMore = false, endReached = page.next == null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setState { it.copy(loading = false, refreshing = false, loadingMore = false, error = AppError.from(e)) }
            }
        }
    }

    companion object {
        /** RN MENTION_REGEX: accounts, and post references of the form @author/permlink. */
        private val MENTION = Regex("@[\\w.\\d-]+(?:/[\\w.\\d-]+)?", RegexOption.IGNORE_CASE)

        /**
         * RN split: text and mention parts in order; the actor is the first mention's account. A mention only
         * becomes a link when [SafeTarget] accepts it as an account or a post (trailing sentence punctuation is
         * left as text); anything else stays plain text.
         */
        fun parts(msg: String): List<ActivityPart> {
            val out = mutableListOf<ActivityPart>()
            val text = StringBuilder()
            fun flush() {
                if (text.isNotEmpty()) out += ActivityPart(text.toString())
                text.clear()
            }
            var last = 0
            for (m in MENTION.findAll(msg)) {
                text.append(msg, last, m.range.first)
                last = m.range.last + 1
                val token = m.value.trimEnd('.', '-')
                val ref = token.drop(1)
                val author = ref.substringBefore('/')
                val permlink = ref.substringAfter('/', "").ifEmpty { null }
                val link = if (permlink == null) {
                    SafeTarget.account(author)?.let { ActivityPart(token, it) }
                } else {
                    SafeTarget.post(author, permlink)?.let { ActivityPart(token, it.author, it.permlink) }
                }
                if (link == null) {
                    text.append(m.value)
                } else {
                    flush()
                    out += link
                    text.append(m.value.substring(token.length))
                }
            }
            text.append(msg, last, msg.length)
            flush()
            return out
        }

        internal fun row(a: CommunityActivity): ActivityRow {
            val parts = parts(a.msg)
            return ActivityRow(a.id, parts.firstOrNull { it.isLink }?.account, parts, a.date, PostCard.epochMillis(a.date))
        }
    }
}

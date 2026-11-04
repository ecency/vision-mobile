package com.ecency.core.app

import com.ecency.core.api.broadcast.CommunityOps
import com.ecency.core.api.broadcast.CommunityProps
import com.ecency.core.api.broadcast.codePointCount
import com.ecency.core.api.broadcast.takeCodePoints
import com.ecency.core.api.community.CommunityApi
import com.ecency.core.api.community.Subscription
import com.ecency.core.auth.AuthException
import com.ecency.core.hive.CustomJson
import com.ecency.core.l10n.L
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/** The signed-in account's community subscriptions, with joins and leaves applied before hivemind shows them. */
data class SubscriptionsState(
    val username: String? = null,
    /** From `bridge.list_all_subscriptions`, sorted by title (RN), plus communities joined here since. */
    val items: List<Subscription> = emptyList(),
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val error: AppError? = null,
    /** Joined (true) or left (false) in this session; wins over the list until hivemind agrees. */
    val overrides: Map<String, Boolean> = emptyMap(),
    /** Communities with a join or leave in flight. */
    val pending: Set<String> = emptySet(),
) {
    /**
     * Whether the account is subscribed to [community]: this session's join or leave first, then the community's own
     * observer [context] when the caller has one, then the list.
     */
    fun isSubscribed(community: String, context: Boolean? = null): Boolean {
        overrides[community]?.let { return it }
        // a community fetched with the account as observer says it itself, fresher than the list
        context?.let { return it }
        return items.any { it.community == community }
    }

    /** RN's joined tab: every subscription with its current join state (a left one stays, showing Join). */
    val joined: List<Pair<Subscription, Boolean>> get() = items.map { it to isSubscribed(it.community) }
}

/**
 * Communities for every screen, one per app: the subscriptions of the signed-in account (RN redux
 * `communities.subscribedCommunities` plus the `subscribedCommunities` cache) and the community operations.
 *
 * Join and leave ([toggleSubscription]) post RN's toasts. The moderation calls ([setRole], [updateProps],
 * [pinPost], [mutePost], [muteUser], [flagPost]) suspend until the broadcast finished and throw [AppException]
 * on failure (kind CANCELLED when the user closed a signing prompt); the post menu (feed wave) and the
 * community screens share them. Every operation is built by [CommunityOps].
 */
class CommunityService internal constructor(private val env: AppEnvironment) :
    StateModel<SubscriptionsState>(SubscriptionsState(), env.dispatchers) {

    val api: CommunityApi get() = env.communityApi
    private var job: Job? = null

    init {
        scope.launch {
            env.events.collect { event ->
                if (event is AppEvent.SessionChanged && event.username != current.username) {
                    job?.cancel()
                    setState { SubscriptionsState(username = event.username) }
                    if (event.username != null) load()
                }
            }
        }
    }

    /** Loads the subscriptions of the signed-in account once (RN usePostLogin, communities screen). */
    fun start() {
        val user = env.session.current.username
        if (user == null) {
            if (current.username != null) setState { SubscriptionsState() }
            return
        }
        if (current.username == user && (current.loaded || current.loading)) return
        if (current.username != user) setState { SubscriptionsState(username = user) }
        load()
    }

    /** Loads the list again (pull to refresh on the joined tab). */
    fun refresh() {
        if (env.session.current.username == null) return
        load()
    }

    private fun load() {
        val user = env.session.current.username ?: return
        job?.cancel()
        setState { it.copy(username = user, loading = true, error = null) }
        job = scope.launch {
            try {
                val fetched = api.subscriptions(user).sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
                if (env.session.current.username != user) return@launch
                setState { s ->
                    // a join or leave hivemind now reflects is no longer an override
                    val names = fetched.map { it.community }.toSet()
                    val overrides = s.overrides.filter { (name, joined) -> (name in names) != joined }
                    // communities joined here that the list does not show yet stay in it
                    val extra = s.items.filter { it.community !in names && overrides[it.community] != null }
                    s.copy(items = fetched + extra, overrides = overrides, loaded = true, loading = false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setState { it.copy(loading = false, error = AppError.from(e)) }
            }
        }
    }

    /**
     * Joins [community] when not subscribed, else leaves it (RN useCommunitySubscriptionAction). Returns false when
     * nobody is signed in (the UI opens the login screen) or a change for it is already running.
     */
    fun toggleSubscription(community: String, title: String = community): Boolean = toggleSubscription(community, title, null)

    /** As above, with the join state the screen shows ([shown]; RN passes the row's `isSubscribed`). */
    fun toggleSubscription(community: String, title: String, shown: Boolean?): Boolean {
        val user = env.session.current.username ?: return false
        if (community in current.pending) return false
        val leaving = shown ?: current.isSubscribed(community)
        setState { it.copy(pending = it.pending + community) }
        scope.launch {
            try {
                val op = if (leaving) CommunityOps.unsubscribe(user, community) else CommunityOps.subscribe(user, community)
                env.signing.broadcast(user, CommunityOps.ops(op))
                if (env.session.current.username != user) return@launch
                setState { s ->
                    val items = if (!leaving && s.items.none { it.community == community }) {
                        s.items + Subscription(community, title)
                    } else {
                        s.items
                    }
                    s.copy(items = items, overrides = s.overrides + (community to !leaving), pending = s.pending - community)
                }
                env.ui.toast(if (leaving) SUCCESS_LEAVE else SUCCESS_SUBSCRIBE)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setState { it.copy(pending = it.pending - community) }
                if (AppError.from(e).kind != ErrorKind.CANCELLED) env.ui.toast(if (leaving) FAIL_LEAVE else FAIL_SUBSCRIBE)
            }
        }
        return true
    }

    // ---- moderation (owner, admin and mod; hivemind decides) ----

    /** RN useSetCommunityRoleMutation. */
    @Throws(AppException::class, CancellationException::class)
    suspend fun setRole(community: String, account: String, role: String) =
        send { user -> CommunityOps.setRole(user, community, account, role) }

    /** RN useUpdateCommunityMutation (owner and admin). */
    @Throws(AppException::class, CancellationException::class)
    suspend fun updateProps(community: String, props: CommunityProps) =
        send { user -> CommunityOps.updateProps(user, community, props) }

    /**
     * RN usePinPostMutation: pin, or unpin when [pin] is false. [asUser] (here and below) is the account the
     * action was asked of: when the session moved to another account meanwhile, nothing is signed.
     */
    @Throws(AppException::class, CancellationException::class)
    suspend fun pinPost(community: String, author: String, permlink: String, pin: Boolean, asUser: String? = null) =
        send(asUser) { user -> CommunityOps.pinPost(user, community, author, permlink, pin) }

    /** RN useMutePostMutation: hivemind wants a reason ([ModNotes.MAX_LENGTH]) on mute and unmute alike. */
    @Throws(AppException::class, CancellationException::class)
    suspend fun mutePost(community: String, author: String, permlink: String, notes: String, mute: Boolean, asUser: String? = null) =
        send(asUser) { user -> CommunityOps.mutePost(user, community, author, permlink, notes, mute) }

    @Throws(AppException::class, CancellationException::class)
    suspend fun muteUser(community: String, account: String, notes: String, mute: Boolean, asUser: String? = null) =
        send(asUser) { user -> CommunityOps.muteUser(user, community, account, notes, mute) }

    @Throws(AppException::class, CancellationException::class)
    suspend fun flagPost(community: String, author: String, permlink: String, notes: String, asUser: String? = null) =
        send(asUser) { user -> CommunityOps.flagPost(user, community, author, permlink, notes) }

    private suspend fun send(asUser: String? = null, build: (String) -> CustomJson) {
        val user = env.session.current.username ?: throw AppException(AppError.from(AuthException.NotSignedIn()))
        if (asUser != null && asUser != user) throw AppException(AppError(ErrorKind.CANCELLED, L.native.communityService.theAccountChanged, false))
        try {
            val op = build(user)
            withContext(dispatchers.main) {
                // the session is read again on the main thread, right before signing
                if (env.session.current.username != user) throw AppException(AppError(ErrorKind.CANCELLED, L.native.communityService.theAccountChanged, false))
                env.signing.broadcast(user, CommunityOps.ops(op))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AppException) {
            throw e
        } catch (e: Exception) {
            throw AppException(AppError.fromBroadcast(e), e)
        }
    }

    companion object {
        // RN en-US alert.*
        val SUCCESS_SUBSCRIBE: String get() = L.alert.successSubscribe
        val SUCCESS_LEAVE: String get() = L.alert.successLeave
        val FAIL_SUBSCRIBE: String get() = L.alert.failSubscribe
        val FAIL_LEAVE: String get() = L.alert.failLeave
        val SUCCESSFUL: String get() = L.alert.successful
    }
}

/** RN modNotesSheet: the reason community moderation operations carry. */
object ModNotes {
    /** Web caps the field at 120 characters; RN keeps the two consistent. */
    const val MAX_LENGTH = 120

    /**
     * What to send, or null when the reason is blank (the confirm button stays disabled): trimmed, at most
     * [MAX_LENGTH] code points (hivemind counts those), never cut inside a surrogate pair.
     */
    fun notes(input: String): String? = input.trim().takeCodePoints(MAX_LENGTH).trim().takeIf { it.isNotEmpty() }

    /** What the field keeps of typed or pasted text: at most [MAX_LENGTH] code points. */
    fun limit(input: String): String = input.takeCodePoints(MAX_LENGTH)

    /** The counter under the field, in code points. */
    fun count(input: String): Int = input.codePointCount()

    /** RN en-US copy for the mute and unmute prompts. */
    fun title(unmute: Boolean) = if (unmute) L.community.unmutePostTitle else L.community.mutePostTitle
    fun placeholder(unmute: Boolean) = if (unmute) L.community.unmutePostReason else L.community.mutePostReason
    fun confirmLabel(unmute: Boolean) = if (unmute) L.postDropdown.unmutePost else L.postDropdown.mutePost
    val DEFAULT_TITLE: String get() = L.modNotes.title
    val DEFAULT_PLACEHOLDER: String get() = L.modNotes.placeholder
}

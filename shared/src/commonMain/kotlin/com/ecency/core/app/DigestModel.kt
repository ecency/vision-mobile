package com.ecency.core.app

import com.ecency.core.api.ecency.DigestSubscribeStatus
import com.ecency.core.api.ecency.DigestSubscription
import com.ecency.core.api.ecency.NewsletterApi
import com.ecency.core.l10n.L
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlin.coroutines.cancellation.CancellationException

/** RN newsletterDigestSheet: one email digest list of one account, as the sheet shows it. */
data class DigestState(
    /** The sheet shows. */
    val open: Boolean = false,
    /** The account the sheet belongs to; every request is this account's. */
    val owner: String? = null,
    /** own, creator, community or site. */
    val type: String = "own",
    val target: String = "",
    /** RN payload.firstPublish: "Congrats on your first post!" instead of the list's own title. */
    val firstPublish: Boolean = false,
    /** RN subscriptionsQuery.isLoading: the form must not show before the lookup answered. */
    val loading: Boolean = false,
    /** RN subscriptionsQuery.isError. */
    val unavailable: Boolean = false,
    val subscriptions: List<DigestSubscription> = emptyList(),
    val email: String = "",
    /** The cadence tapped in this sheet; null: the subscription's, else weekly. */
    val cadence: String? = null,
    val subscribing: Boolean = false,
    val leaving: Boolean = false,
    /** A new address got a confirmation mail: the "Check your inbox" state. */
    val checkInboxEmail: String? = null,
    /** RN Alert.alert(title only). */
    val alert: String? = null,
) {
    /** RN findDigestSubscription(type, target). */
    val subscription: DigestSubscription? get() = NewsletterApi.find(subscriptions, type, target)

    /** RN `subscription?.email || knownDigestAddress(...)`. */
    val knownAddress: String? get() = subscription?.email?.takeIf { it.isNotEmpty() } ?: NewsletterApi.knownAddress(subscriptions)

    val effectiveCadence: String get() = cadence ?: subscription?.cadence ?: "weekly"

    val needsEmailInput: Boolean get() = knownAddress == null

    val emailValid: Boolean get() = NewsletterApi.looksLikeEmail(email)

    val isPending: Boolean get() = subscribing || leaving

    val isActive: Boolean get() = subscription?.status == "active"

    val isPendingConfirmation: Boolean get() = subscription?.status == "pending_confirmation"

    private val cadenceUnchanged: Boolean get() = isActive && effectiveCadence == subscription?.cadence

    /** RN primaryLabelId as its en-US text: newsletter.resend, update or subscribe. */
    val primaryLabel: String
        get() = when {
            isPendingConfirmation && effectiveCadence == subscription?.cadence -> DigestCopy.RESEND
            isActive || isPendingConfirmation -> DigestCopy.UPDATE
            else -> DigestCopy.SUBSCRIBE
        }

    /** RN isDisable of the primary button, negated. */
    val canSubmit: Boolean get() = !(isPending || (needsEmailInput && !emailValid) || cadenceUnchanged)

    /** RN `!!subscription`: "Leave digest" shows. */
    val showsLeave: Boolean get() = subscription != null

    /** The address a subscribe sends: the known one, else the typed one (trimmed). */
    val address: String get() = knownAddress ?: com.ecency.core.render.js.Js.trim(email)

    val title: String get() = if (firstPublish) DigestCopy.FIRST_PUBLISH_TITLE else DigestCopy.listLabel(type, target)

    val body: String get() = if (firstPublish) DigestCopy.FIRST_PUBLISH_BODY else DigestCopy.body(type, target)

    val status: String?
        get() = when {
            isActive -> DigestCopy.STATUS_ACTIVE
            isPendingConfirmation -> DigestCopy.STATUS_PENDING
            else -> null
        }
}

/** RN en-US `newsletter.*`. */
object DigestCopy {
    val FIRST_PUBLISH_TITLE: String get() = L.newsletter.firstPublishTitle
    val FIRST_PUBLISH_BODY: String get() = L.newsletter.firstPublishBody
    val LIST_OWN: String get() = L.newsletter.listOwn
    val LIST_SITE: String get() = L.newsletter.listSite
    val STATUS_ACTIVE: String get() = L.newsletter.statusActive
    val STATUS_PENDING: String get() = L.newsletter.statusPending
    val WEEKLY: String get() = L.newsletter.cadenceWeekly
    val MONTHLY: String get() = L.newsletter.cadenceMonthly
    val EMAIL_PLACEHOLDER: String get() = L.newsletter.emailPlaceholder
    val SUBSCRIBE: String get() = L.newsletter.subscribe
    val UPDATE: String get() = L.newsletter.update
    val RESEND: String get() = L.newsletter.resend
    val LEAVE: String get() = L.newsletter.leave
    val CANCEL: String get() = L.newsletter.cancel
    val OK: String get() = L.newsletter.ok
    val CHECK_INBOX_TITLE: String get() = L.newsletter.checkInboxTitle
    val SUBSCRIBED: String get() = L.newsletter.subscribed
    val LEFT: String get() = L.newsletter.left
    val REFUSED: String get() = L.newsletter.refused
    val UNAVAILABLE: String get() = L.newsletter.unavailable
    val TOO_MANY: String get() = L.newsletter.tooMany
    val FAIL: String get() = L.newsletter.fail

    fun checkInboxBody(email: String) = L.newsletter.checkInboxBody(email = email)

    /** RN _listLabel (a community's title is not known here: its name stands in, as RN's fallback). */
    fun listLabel(type: String, target: String): String = when (type) {
        "own" -> LIST_OWN
        "site" -> LIST_SITE
        "community" -> target
        else -> "@$target"
    }

    /** RN newsletter.body_<type>. */
    fun body(type: String, target: String): String = when (type) {
        "own" -> L.newsletter.bodyOwn
        "creator" -> L.newsletter.bodyCreator(list = listLabel(type, target))
        "community" -> L.newsletter.bodyCommunity(list = listLabel(type, target))
        else -> L.newsletter.bodySite
    }
}

/**
 * RN `newsletterDigestSheet` and `utils/firstPublishDigest.ts`: subscribing to, changing and leaving one email
 * digest list, and the one-time offer of the own-notifications digest after an account's first post. One per app
 * ([AppEnvironment.digest]); the apps show a sheet while [DigestState.open].
 *
 * - Settings-like, not paid: nothing here costs points, and a request is simply sent again when the user taps again.
 * - Account-bound: the sheet belongs to the account that opened it. Its requests carry that account's own code
 *   (through [com.ecency.core.auth.EcencyPrivateApi.relay], bounded at 30 s), nothing is sent while another
 *   account is signed in, an answer that arrives after the account changed is dropped, and any account change
 *   closes the sheet.
 */
class DigestModel internal constructor(private val env: AppEnvironment) : StateModel<DigestState>(DigestState(), env.dispatchers) {
    /** Replaceable in tests. */
    var api: NewsletterApi = NewsletterApi(com.ecency.core.auth.EcencyPrivateApi(env.auth.http, env.accounts, env.auth.tokenHost))

    private var generation = 0
    private var job: Job? = null

    /** The app's one model: screens do not own it. */
    override fun close() {}

    /** The session's subscriptions of one viewer (RN's query cache), so a post open does not ask again. Main thread. */
    private var cached: Pair<String, List<com.ecency.core.api.ecency.DigestSubscription>>? = null

    private val _revision = kotlinx.coroutines.flow.MutableStateFlow(0)

    /** Bumped whenever the cached subscriptions are dropped (subscribe, leave, stop all, account change). */
    val revision: kotlinx.coroutines.flow.StateFlow<Int> get() = _revision

    /** [viewer]'s subscriptions, from the session cache or the relay (kept only while [viewer] is signed in). */
    internal suspend fun subscriptionsOf(viewer: String): List<com.ecency.core.api.ecency.DigestSubscription> {
        cached?.takeIf { it.first == viewer }?.let { return it.second }
        val rev = _revision.value
        val rows = api.subscriptions(viewer)
        if (rev == _revision.value && env.session.current.username == viewer) cached = viewer to rows
        return rows
    }

    /** A subscription changed (or the account did): the next lookup asks the relay again. Main thread. */
    fun invalidateSubscriptions() {
        cached = null
        _revision.value++
    }

    init {
        scope.launch {
            env.events.collect { event ->
                if (event is AppEvent.SessionChanged || event == AppEvent.DataWiped) {
                    reset()
                    invalidateSubscriptions()
                }
            }
        }
    }

    /** The account changed (or the sheet was closed): whatever was on its way no longer lands. Main thread. */
    internal fun reset() {
        generation++
        job?.cancel()
        job = null
        setState { DigestState() }
    }

    private fun ownerSignedIn(owner: String?) = owner != null && env.session.current.username == owner

    /**
     * RN `SheetManager.show(NEWSLETTER_DIGEST, { payload: { type, target, firstPublish } })` for the signed-in
     * account. Signed out it does nothing: the relay needs the account.
     */
    fun show(type: String, target: String, firstPublish: Boolean = false) {
        val owner = env.session.current.username ?: return
        reset()
        setState { DigestState(open = true, owner = owner, type = type, target = target, firstPublish = firstPublish, loading = true) }
        load(owner)
    }

    private fun load(owner: String) {
        val startedIn = generation
        job = scope.launch {
            try {
                val rows = api.subscriptions(owner)
                if (ownerSignedIn(owner)) cached = owner to rows
                if (startedIn != generation || !ownerSignedIn(owner)) return@launch
                setState { it.copy(loading = false, unavailable = false, subscriptions = rows) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (startedIn != generation) return@launch
                setState { it.copy(loading = false, unavailable = true) }
            }
        }
    }

    fun setEmail(email: String) = setState { if (it.isPending) it else it.copy(email = email.take(320)) }

    fun setCadence(cadence: String) {
        if (cadence != "weekly" && cadence != "monthly") return
        setState { if (it.isPending) it else it.copy(cadence = cadence) }
    }

    fun clearAlert() = setState { it.copy(alert = null) }

    /** RN _handleSubscribe. */
    fun subscribe() {
        val s = current
        val owner = s.owner ?: return
        if (!s.open || s.loading || s.unavailable || !s.canSubmit) return
        if (!ownerSignedIn(owner)) {
            reset()
            return
        }
        val address = s.address
        if (address.isEmpty()) return
        val cadence = s.effectiveCadence
        val startedIn = generation
        setState { it.copy(subscribing = true, alert = null) }
        scope.launch {
            try {
                val status = api.subscribe(owner, address, s.type, s.target, cadence)
                if (status != DigestSubscribeStatus.REFUSED) invalidateSubscriptions()
                if (startedIn != generation) return@launch
                when (status) {
                    DigestSubscribeStatus.REFUSED -> setState { it.copy(subscribing = false, alert = DigestCopy.REFUSED) }
                    DigestSubscribeStatus.PENDING_CONFIRMATION -> setState { it.copy(subscribing = false, checkInboxEmail = address) }
                    DigestSubscribeStatus.ACTIVE -> {
                        env.ui.toast(DigestCopy.SUBSCRIBED)
                        reset()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (startedIn != generation) return@launch
                val status = (e as? com.ecency.core.api.EcencyHttpException)?.status
                setState { it.copy(subscribing = false, alert = if (status == 429) DigestCopy.TOO_MANY else DigestCopy.FAIL) }
            }
        }
    }

    /** RN _handleLeave. */
    fun leave() {
        val s = current
        val owner = s.owner ?: return
        val subscription = s.subscription ?: return
        if (!s.open || s.isPending) return
        if (!ownerSignedIn(owner)) {
            reset()
            return
        }
        val startedIn = generation
        setState { it.copy(leaving = true, alert = null) }
        scope.launch {
            try {
                api.leave(owner, subscription.id)
                invalidateSubscriptions()
                if (startedIn != generation) return@launch
                env.ui.toast(DigestCopy.LEFT)
                reset()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (startedIn != generation) return@launch
                setState { it.copy(leaving = false, alert = DigestCopy.FAIL) }
            }
        }
    }

    /** Cancel, the "Check your inbox" OK, a swipe or a tap outside: the sheet goes; a request on its way is dropped. */
    fun dismiss() = reset()

    // ---- RN utils/firstPublishDigest.ts ----

    private fun flagKey(username: String) = "$OFFERED_PREFIX$username"

    /** RN's `first_publish_digest_<user>` flag: the offer was made (or can never apply) for this account on this device. */
    fun wasOffered(username: String): Boolean = env.prefs.getString(flagKey(username)) != null

    /** Accounts whose post count could not be read in this app run (failed or timed out): they are not waited for again. */
    private val countUnreadable = mutableSetOf<String>()

    /**
     * Before a new post goes out: whether this would be [owner]'s first post (RN reads `post_count` of the cached
     * account, the value from before the publish). Null when it is not known; asking never fails or holds up the
     * publish for more than [COUNT_TIMEOUT_MILLIS], and only once per account and app run: after a read that
     * failed or timed out the answer stays "not known" without another wait, so an account whose node keeps
     * failing does not pay the bound on every publish. An account that already has posts is remembered for good,
     * so it is not asked about again. Main thread only.
     */
    internal suspend fun postCountBeforePublish(owner: String): Int? {
        if (wasOffered(owner) || owner in countUnreadable) return null
        var answered = false
        val count = try {
            withTimeoutOrNull(COUNT_TIMEOUT_MILLIS) {
                val rows = env.api.client.call("condenser_api.get_accounts", buildJsonArray { add(buildJsonArray { add(JsonPrimitive(owner)) }) }) as? JsonArray
                answered = true
                val row = rows?.firstOrNull() as? JsonObject
                // RN: a missing post_count is "don't know", never zero
                row?.takeIf { (it["name"] as? JsonPrimitive)?.content == owner }?.let { (it["post_count"] as? JsonPrimitive)?.takeIf { p -> !p.isString }?.content?.toIntOrNull() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (!answered) countUnreadable += owner
        if (count != null && count > 0) env.prefs.putString(flagKey(owner), "has-posts")
        return count
    }

    /**
     * RN maybeOfferFirstPublishDigest, after a new post was published: one second later (past the navigation),
     * once per account. Never part of the publish: it runs apart from it and its failures are swallowed. An
     * account change in that second skips the offer without spending it; the flag is written before the sheet
     * shows, so a crash can never offer twice.
     */
    internal fun maybeOfferFirstPublish(owner: String, postCountBefore: Int?) {
        if (!shouldOffer(postCountBefore, wasOffered(owner))) return
        env.background.launch {
            try {
                delay(OFFER_DELAY_MILLIS)
                if (env.session.current.username != owner) return@launch
                if (!shouldOffer(postCountBefore, wasOffered(owner))) return@launch
                env.prefs.putString(flagKey(owner), env.nowMillis().toString())
                show("own", owner, firstPublish = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // RN: the offer must never break anything
            }
        }
    }

    companion object {
        const val OFFERED_PREFIX = "digest.firstPublishOffered."
        const val OFFER_DELAY_MILLIS = 1000L
        const val COUNT_TIMEOUT_MILLIS = 3000L

        /** RN shouldOfferFirstPublishDigest: not offered before, and a post count that is known to be zero. */
        fun shouldOffer(postCount: Int?, alreadyOffered: Boolean): Boolean = !alreadyOffered && postCount == 0
    }
}

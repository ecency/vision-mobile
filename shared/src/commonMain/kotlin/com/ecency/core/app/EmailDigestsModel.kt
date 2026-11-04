package com.ecency.core.app

import com.ecency.core.api.EcencyHttpException
import com.ecency.core.api.ecency.DigestSubscription
import com.ecency.core.api.ecency.NewsletterApi
import com.ecency.core.l10n.L
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** RN en-US `newsletter.*` of the email digests screen. */
object EmailDigestsCopy {
    val TITLE: String get() = L.newsletter.screenTitle
    val EMPTY: String get() = L.newsletter.empty
    val DISCOVER: String get() = L.newsletter.discover
    val STOP_ALL: String get() = L.newsletter.stopAll
    val STOP_ALL_TITLE: String get() = L.newsletter.stopAllTitle
    val STOP_ALL_DONE: String get() = L.newsletter.stopAllDone
    val MANAGE: String get() = L.newsletter.manage

    fun stopAllBody(email: String) = L.newsletter.stopAllBody(email = email)

    /** RN `newsletter.cadence_<c>` and `newsletter.status_short_<s>` (the status itself when unknown). */
    fun meta(s: DigestSubscription): String {
        val cadence = when (s.cadence) {
            "weekly" -> DigestCopy.WEEKLY
            "monthly" -> DigestCopy.MONTHLY
            else -> s.cadence
        }
        val status = when (s.status) {
            "active" -> L.newsletter.statusShortActive
            "pending_confirmation" -> L.newsletter.statusShortPendingConfirmation
            "suppressed" -> L.newsletter.statusShortSuppressed
            "ended" -> L.newsletter.statusShortEnded
            else -> s.status
        }
        return "$cadence • $status"
    }

    /** RN `_rowLabel`. */
    fun rowLabel(s: DigestSubscription): String = when (s.type) {
        "own" -> DigestCopy.LIST_OWN
        "site" -> DigestCopy.LIST_SITE
        "creator" -> "@${s.target}"
        else -> s.target
    }
}

/** The subscriptions of one address (RN groups rows by `email`, in the order they came). */
data class DigestAddress(val email: String, val rows: List<DigestSubscription>)

data class EmailDigestsState(
    /** The account the rows belong to; rows are drawn only while it is the signed-in one. */
    val account: String? = null,
    val loading: Boolean = false,
    /** RN isError: 503 "not available right now", anything else "something went wrong". */
    val error: String? = null,
    val subscriptions: List<DigestSubscription> = emptyList(),
    /** The address whose "Stop all" is on its way. */
    val stopping: String? = null,
    /** RN Alert.alert after a failed "Stop all". */
    val alert: String? = null,
) {
    val addresses: List<DigestAddress>
        get() = subscriptions.groupBy { it.email }.map { (email, rows) -> DigestAddress(email, rows) }

    val isEmpty: Boolean get() = !loading && error == null && subscriptions.isEmpty()

    /** RN's "Get more by email" rows: own (needs the account) and the Ecency newsletter, when not subscribed. */
    val showsOwnOffer: Boolean get() = account != null && NewsletterApi.find(subscriptions, "own", account) == null
    val showsSiteOffer: Boolean get() = NewsletterApi.find(subscriptions, "site", SITE_TARGET) == null
    val showsDiscover: Boolean get() = !loading && error == null && (showsOwnOffer || showsSiteOffer)

    companion object {
        const val SITE_TARGET = "ecency"
    }
}

/**
 * RN emailDigestsScreen: every digest subscription of the signed-in account across its addresses. A row or an offer
 * opens the digest sheet ([DigestModel]); the list loads again when that sheet closes (RN shares the query cache).
 * "Stop all" for one address (`unsubscribe-all`) after the UI's confirmation. Account-bound like the sheet: an
 * account change empties it and loads the new account's, a late answer for the previous one is dropped.
 */
class EmailDigestsModel internal constructor(private val env: AppEnvironment) : StateModel<EmailDigestsState>(EmailDigestsState(), env.dispatchers) {
    /** Replaceable in tests. */
    var api: NewsletterApi
        get() = env.digest.api
        set(value) {
            env.digest.api = value
        }

    private var job: Job? = null
    private var generation = 0
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            env.events.collect { event ->
                if ((event is AppEvent.SessionChanged && event.username != current.account) || event == AppEvent.DataWiped) {
                    setState { EmailDigestsState() }
                    load()
                }
            }
        }
        // the sheet closed after a row or an offer: what it changed shows here
        scope.launch {
            env.digest.state.map { it.open }.distinctUntilChanged().drop(1).collect { open -> if (!open) load(quiet = true) }
        }
        load()
    }

    fun retry() = load()

    /** A row ("Manage") or an offer ("Subscribe"): RN's digest sheet for that list. */
    fun open(type: String, target: String) {
        if (current.account == null || current.account != env.session.current.username) return
        env.digest.show(type, target)
    }

    private fun load(quiet: Boolean = false) {
        job?.cancel()
        val startedIn = ++generation
        if (!quiet) setState { it.copy(loading = true, error = null) }
        job = scope.launch {
            env.session.awaitLoaded()
            val user = env.session.current.username
            if (user == null) {
                setState { EmailDigestsState() }
                return@launch
            }
            setState { if (it.account == user) it.copy(loading = !quiet || it.error != null, error = null) else EmailDigestsState(account = user, loading = true) }
            try {
                val rows = api.subscriptions(user)
                if (startedIn != generation || env.session.current.username != user) return@launch
                setState { it.copy(loading = false, error = null, subscriptions = rows) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (startedIn != generation) return@launch
                val status = (e as? EcencyHttpException)?.status
                setState { it.copy(loading = false, error = if (status == 503) DigestCopy.UNAVAILABLE else DigestCopy.FAIL) }
            }
        }
    }

    /** RN `_handleStopAll`'s confirmed branch: no mail to [email] again, toast, list again; an alert on failure. */
    fun stopAll(email: String) {
        val s = current
        val user = s.account ?: return
        if (s.stopping != null || user != env.session.current.username || s.subscriptions.none { it.email == email }) return
        setState { it.copy(stopping = email, alert = null) }
        fun stillOwner() = current.account == user && env.session.current.username == user
        scope.launch {
            try {
                api.unsubscribeAll(user, email)
                env.digest.invalidateSubscriptions()
                if (!stillOwner()) return@launch
                setState { it.copy(stopping = null) }
                env.ui.toast(EmailDigestsCopy.STOP_ALL_DONE)
                load(quiet = true)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (!stillOwner()) return@launch
                setState { it.copy(stopping = null, alert = DigestCopy.FAIL) }
            }
        }
    }

    fun clearAlert() = setState { it.copy(alert = null) }
}

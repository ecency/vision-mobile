package com.ecency.core.app

import com.ecency.core.api.model.Account
import com.ecency.core.hive.KeyRole
import com.ecency.core.l10n.L
import com.ecency.core.l10n.LocaleFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Where an account stands in Hive governance (#3724). */
enum class GovernanceStatus {
    /** Votes or a proxy that count for more than [Governance.EXPIRING_MILLIS], or nothing the app can read. */
    ACTIVE,

    /** The votes or the proxy expire within [Governance.EXPIRING_MILLIS] (or are past it and not cleared yet). */
    EXPIRING,

    /** Expired, or never cast: no proxy, no witness votes, and the chain's expired value. */
    INACTIVE,
}

/** What the card offers to sign: a proxy to @ecency, or renewing that proxy. */
enum class GovernanceAction { SET_PROXY, RENEW }

/** The card's dismiss choices: for good (this state of this account), or for [GovernanceCardModel.RE_REQUEST_MILLIS]. */
enum class GovernanceDismiss { FOREVER, LATER }

/**
 * Hive governance expiry (HF25, hived `remove_expired_governance_votes`): 365 days after an account's last witness
 * vote, proposal vote or proxy change, the chain clears its witness votes, proposal votes and proxy, and sets
 * `governance_vote_expiration_ts` to [EXPIRED_TS], the value an account that never voted also has.
 */
object Governance {
    /** `governance_vote_expiration_ts` of an expired account, and of one that never voted. */
    const val EXPIRED_TS = "1969-12-31T23:59:59"

    /** The card warns this long before the expiry: 30 days. */
    const val EXPIRING_MILLIS = 30L * 86_400_000L

    /** The expiry instant, or null for the expired value and anything unreadable. */
    fun expiresAt(account: Account): Long? {
        val text = account.governanceVoteExpiration
        val millis = runCatching { kotlin.time.Instant.parse(if (text.endsWith("Z")) text else "${text}Z").toEpochMilliseconds() }.getOrNull()
        return millis?.takeIf { it > 0 }
    }

    fun status(account: Account, nowMillis: Long): GovernanceStatus {
        // the suggested proxy itself, and an account that declined voting rights (the chain refuses its proxy)
        if (account.name == GovernanceCardModel.PROXY || !account.canVote) return GovernanceStatus.ACTIVE
        if (account.governanceVoteExpiration == EXPIRED_TS) {
            return if (account.proxy.isEmpty() && account.witnessVotes.isEmpty()) GovernanceStatus.INACTIVE else GovernanceStatus.ACTIVE
        }
        val at = expiresAt(account) ?: return GovernanceStatus.ACTIVE
        return if (at - nowMillis <= EXPIRING_MILLIS) GovernanceStatus.EXPIRING else GovernanceStatus.ACTIVE
    }
}

data class GovernanceCardState(
    val username: String? = null,
    val status: GovernanceStatus = GovernanceStatus.ACTIVE,
    val expiresAt: Long? = null,
    /** The account's proxy (empty: none). */
    val proxy: String = "",
    /** @ecency's own proxy, read from the chain: whose witness votes a proxy to @ecency follows (null: its own). */
    val ecencyProxy: String? = null,
    val visible: Boolean = false,
    val sending: Boolean = false,
    /** Signed in this run: the card says thanks and has no buttons. */
    val done: Boolean = false,
    /** The confirm sheet is open. */
    val confirming: Boolean = false,
    /** The dismiss choice is open. */
    val askingDismiss: Boolean = false,
    /** What a dismissal names: "inactive", or "expiring." and the expiry (next year's expiring card still shows). */
    val episode: String? = null,
) {
    /** Inactive: the proxy offer. Expiring with a proxy to @ecency: renewing it. Otherwise only "Vote yourself". */
    val action: GovernanceAction? get() = when {
        done -> null
        status == GovernanceStatus.INACTIVE -> GovernanceAction.SET_PROXY
        status == GovernanceStatus.EXPIRING && proxy == GovernanceCardModel.PROXY -> GovernanceAction.RENEW
        else -> null
    }

    val title: String get() = when {
        done -> GovernanceCopy.TITLE_DONE
        status == GovernanceStatus.INACTIVE -> GovernanceCopy.TITLE_INACTIVE
        else -> GovernanceCopy.TITLE_EXPIRING
    }

    val body: String get() {
        val date = expiresAt?.let(LocaleFormat::date).orEmpty()
        return when {
            done -> GovernanceCopy.BODY_DONE
            status == GovernanceStatus.INACTIVE -> GovernanceCopy.BODY_INACTIVE
            action == GovernanceAction.RENEW -> L.native.governance.bodyExpiringRenew(date = date)
            else -> L.native.governance.bodyExpiringVote(date = date)
        }
    }

    /** The action button's label. */
    val actionLabel: String get() = if (action == GovernanceAction.RENEW) GovernanceCopy.RENEW else GovernanceCopy.SET_PROXY

    val sheetTitle: String get() = if (action == GovernanceAction.RENEW) GovernanceCopy.RENEW_TITLE else GovernanceCopy.CONFIRM_TITLE

    /** What the confirm sheet says before anything is signed. */
    val sheetPoints: List<String> get() {
        val follows = ecencyProxy?.let { L.native.governance.pointFollows(name = it) } ?: GovernanceCopy.POINT_OWN
        return if (action == GovernanceAction.RENEW) {
            listOf(GovernanceCopy.RENEW_BODY, follows, GovernanceCopy.POINT_REMOVE)
        } else {
            listOf(GovernanceCopy.POINT_VOTES, follows, GovernanceCopy.POINT_REPLACED, GovernanceCopy.POINT_REMOVE, GovernanceCopy.POINT_LAPSE)
        }
    }

    val confirmLabel: String get() = if (action == GovernanceAction.RENEW) GovernanceCopy.RENEW_CONFIRM else GovernanceCopy.CONFIRM
}

/** native.governance.* */
object GovernanceCopy {
    val TITLE_INACTIVE: String get() = L.native.governance.titleInactive
    val BODY_INACTIVE: String get() = L.native.governance.bodyInactive
    val TITLE_EXPIRING: String get() = L.native.governance.titleExpiring
    val TITLE_DONE: String get() = L.native.governance.titleDone
    val BODY_DONE: String get() = L.native.governance.bodyDone
    val SET_PROXY: String get() = L.native.governance.setProxy
    val RENEW: String get() = L.native.governance.renew
    val VOTE_YOURSELF: String get() = L.native.governance.voteYourself
    val DISMISS: String get() = L.native.governance.dismiss
    val DISMISS_TITLE: String get() = L.native.governance.dismissTitle
    val DONT_SHOW_AGAIN: String get() = L.native.governance.dontShowAgain
    val LATER: String get() = L.alert.remindLater
    val CANCEL: String get() = L.alert.cancel
    val CONFIRM_TITLE: String get() = L.native.governance.confirmTitle
    val RENEW_TITLE: String get() = L.native.governance.renewTitle
    val POINT_VOTES: String get() = L.native.governance.pointVotes
    val POINT_OWN: String get() = L.native.governance.pointOwn
    val POINT_REPLACED: String get() = L.native.governance.pointReplaced
    val POINT_REMOVE: String get() = L.native.governance.pointRemove
    val POINT_LAPSE: String get() = L.native.governance.pointLapse
    val RENEW_BODY: String get() = L.native.governance.renewBody
    val CONFIRM: String get() = L.native.governance.confirm
    val RENEW_CONFIRM: String get() = L.native.governance.renewConfirm
    val FAIL: String get() = L.alert.fail
    val THANK_YOU: String get() = L.alert.thankyou
}

/**
 * The governance card on the signed-in account's wallet (#3724).
 *
 * - Shown for an [GovernanceStatus.INACTIVE] or [GovernanceStatus.EXPIRING] account that did not hide it: "Don't show
 *   again" per account and state (an expiring card per expiry date, so next year's still shows), "Remind Later" for
 *   3 days, in device preferences a wipe removes.
 * - Set proxy signs `account_witness_proxy {account, proxy: "ecency"}`; Renew signs clearing then setting it in one
 *   transaction (the chain refuses to set the same proxy again, and each change renews the expiry). Active authority
 *   through the wallet's sends (key, HiveSigner, HiveAuth, the active key upgrade), one action id, never retried by
 *   itself. Success: the card says thanks for the rest of the run; the next start reads the account again.
 * - An account change reads the new account.
 */
class GovernanceCardModel internal constructor(private val env: AppEnvironment) :
    StateModel<GovernanceCardState>(GovernanceCardState(), env.dispatchers) {

    private var loadJob: Job? = null
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            env.events.collect { e -> if (e is AppEvent.SessionChanged || e is AppEvent.DataWiped) reload() }
        }
        reload()
    }

    /** Reads the account again (pull to refresh); the card stays as it is until the answer lands. */
    fun refresh() = reload()

    /** The wallet opened: the first time starts the card, later ones read the account again ("Remind Later" ends). */
    fun shown() = if (!started) start() else reload()

    private fun reload() {
        loadJob?.cancel()
        setState { s -> if (s.username == env.session.current.username) s else GovernanceCardState(username = env.session.current.username) }
        loadJob = scope.launch {
            try {
                env.session.awaitLoaded()
                val user = env.session.current.username
                if (current.username != user) setState { GovernanceCardState(username = user) }
                if (user == null || current.done) return@launch
                val accounts = env.api.hive.accounts(listOf(user, PROXY))
                if (env.session.current.username != user) return@launch
                val account = accounts.firstOrNull { it.name == user } ?: return@launch
                val ecency = accounts.firstOrNull { it.name == PROXY }
                val status = Governance.status(account, env.nowMillis())
                val episode = if (status == GovernanceStatus.EXPIRING) "expiring.${account.governanceVoteExpiration}" else INACTIVE
                // the votes count again: a "Don't show again" was about that lapse, not the next one
                if (status == GovernanceStatus.ACTIVE) env.prefs.putString(key(user, INACTIVE), null)
                setState { s ->
                    if (s.username != user) {
                        s
                    } else {
                        s.copy(
                            status = status,
                            expiresAt = Governance.expiresAt(account),
                            proxy = account.proxy,
                            ecencyProxy = ecency?.proxy?.takeIf { it.isNotEmpty() },
                            episode = episode,
                            visible = status != GovernanceStatus.ACTIVE && !hidden(user, episode),
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ErrorReports.quiet(e, "governance.load")
                // the card stays hidden when the account cannot be read
            }
        }
    }

    private fun hidden(user: String, episode: String): Boolean {
        val meta = env.prefs.getString(key(user, episode)) ?: return false
        if (meta == FOREVER) return true
        val dismissedAt = meta.toLongOrNull() ?: return false
        return dismissedAt + RE_REQUEST_MILLIS > env.nowMillis()
    }

    /** Opens the confirm sheet when the card has something to sign. */
    fun askConfirm() {
        val s = current
        if (s.visible && s.action != null && !s.sending) setState { it.copy(confirming = true) }
    }

    /** Closes the sheet, never while it signs and sends. */
    fun cancelConfirm() {
        if (!current.sending) setState { it.copy(confirming = false) }
    }

    /** Signs what the open sheet says. */
    fun confirm() {
        val s = current
        val user = s.username ?: return
        val action = s.action ?: return
        if (!s.confirming || s.sending || env.session.current.username != user) return
        setState { it.copy(sending = true) }
        val proxies = if (action == GovernanceAction.RENEW) listOf("", PROXY) else listOf(PROXY)
        val ops = buildJsonArray { proxies.forEach { add(proxyOp(user, it)) } }
        val actionId = env.idempotencyKey()
        scope.launch {
            val final = CompletableDeferred<WalletSend>()
            env.walletSends.send(user, ops, KeyRole.ACTIVE, actionId) { final.complete(it) }
            // the chain cannot tell yet: the send keeps asking in the app (WalletSends); the card says so once
            val checking = launch {
                env.walletSends.state.first { it.sends[actionId]?.phase is SendPhase.Checking }
                env.ui.toast(com.ecency.core.api.broadcast.BroadcastException.UNDECIDED_MESSAGE)
                // the app keeps asking the chain: the sheet gives way, the card waits with its button off
                setState { if (it.username == user) it.copy(confirming = false) else it }
            }
            val result = try {
                final.await()
            } finally {
                checking.cancel()
                withContext(NonCancellable + env.dispatchers.main) { env.walletSends.disown(actionId) }
            }
            if (current.username != user) return@launch
            when (val phase = result.phase) {
                is SendPhase.Done -> {
                    setState { it.copy(sending = false, confirming = false, done = true, visible = true) }
                    env.ui.toast(GovernanceCopy.THANK_YOU)
                }
                is SendPhase.Unconfirmed -> {
                    setState { it.copy(sending = false, confirming = false) }
                    env.ui.toast(phase.message)
                }
                is SendPhase.Failed -> {
                    setState { it.copy(sending = false, confirming = false) }
                    val message = phase.error.text
                    env.ui.toast(if (message.isNotEmpty()) "${GovernanceCopy.FAIL}: $message" else GovernanceCopy.FAIL, phase.error)
                    // the chain may stand elsewhere than the card read it (a proxy cleared meanwhile): read it again
                    reload()
                }
                else -> setState { it.copy(sending = false) }
            }
        }
    }

    fun askDismiss() {
        if (current.visible && !current.done) setState { it.copy(askingDismiss = true) }
    }

    /** Hides the card for good or for 3 days; null closes the choice without changing anything. */
    fun dismiss(choice: GovernanceDismiss?) {
        val s = current
        setState { it.copy(askingDismiss = false) }
        val user = s.username ?: return
        val episode = s.episode ?: return
        when (choice) {
            GovernanceDismiss.FOREVER -> env.prefs.putString(key(user, episode), FOREVER)
            GovernanceDismiss.LATER -> env.prefs.putString(key(user, episode), env.nowMillis().toString())
            null -> return
        }
        setState { it.copy(visible = false) }
    }

    private fun proxyOp(user: String, proxy: String): JsonArray = buildJsonArray {
        add(JsonPrimitive("account_witness_proxy"))
        add(buildJsonObject { put("account", user); put("proxy", proxy) })
    }

    companion object {
        /** The account the card suggests as proxy. */
        const val PROXY = "ecency"

        /** "Vote yourself": the witnesses page, in the in-app browser. */
        const val VOTE_URL = "https://ecency.com/witnesses"

        /** "Remind Later": 3 days, as the proposal card. */
        const val RE_REQUEST_MILLIS = 259_200_000L

        /** Device preference per account and state; a wipe removes it ([AccountPrefs]). */
        const val KEY_PREFIX = "governance.card."
        private const val FOREVER = "forever"
        private const val INACTIVE = "inactive"

        fun key(user: String, episode: String) = "$KEY_PREFIX${user}.$episode"
    }
}

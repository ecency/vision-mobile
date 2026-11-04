package com.ecency.core.app

import com.ecency.core.api.EcencyException
import com.ecency.core.api.rpc.HiveRpcClient
import com.ecency.core.hive.KeyRole
import com.ecency.core.l10n.L
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** A Hive proposal as `database_api.list_proposals` lists it (the fields the app reads). */
data class Proposal(val id: Long, val creator: String, val status: String)

/** SDK getProposalsQueryOptions and getUserProposalVotesQueryOptions. */
class ProposalApi internal constructor(private val client: HiveRpcClient) {
    /** Every proposal (500, by total votes, descending), expired ones last. */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun proposals(): List<Proposal> {
        val result = client.call(
            "database_api.list_proposals",
            buildJsonObject {
                putJsonArray("start") { add(JsonPrimitive(-1)) }
                put("limit", 500)
                put("order", "by_total_votes")
                put("order_direction", "descending")
                put("status", "all")
            },
        )
        val list = ((result as? JsonObject)?.get("proposals") as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = (o["proposal_id"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: (o["id"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: return@mapNotNull null
            Proposal(id, (o["creator"] as? JsonPrimitive)?.contentOrNull.orEmpty(), (o["status"] as? JsonPrimitive)?.contentOrNull.orEmpty())
        }
        return list.filter { it.status != "expired" } + list.filter { it.status == "expired" }
    }

    /** The proposal ids [voter] votes for (votable proposals, by_voter_proposal, only [voter]'s rows). */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun votedIds(voter: String): Set<Long> {
        if (voter.isEmpty()) return emptySet()
        val result = client.call(
            "database_api.list_proposal_votes",
            buildJsonObject {
                putJsonArray("start") { add(JsonPrimitive(voter)) }
                put("limit", 1000)
                put("order", "by_voter_proposal")
                put("order_direction", "ascending")
                put("status", "votable")
            },
        )
        return ((result as? JsonObject)?.get("proposal_votes") as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            if ((o["voter"] as? JsonPrimitive)?.contentOrNull != voter) return@mapNotNull null
            ((o["proposal"] as? JsonObject)?.get("proposal_id") as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
        }.toSet()
    }
}

/** RN ProposalVoteRequest: the card on the main feed's first tab. */
data class ProposalCardState(
    val username: String? = null,
    /** The newest votable @ecency proposal. */
    val proposalId: Long? = null,
    val visible: Boolean = false,
    val voting: Boolean = false,
    /** Voted in this run: the card says thanks ([title], [body]) and has no buttons. */
    val voted: Boolean = false,
    /** The "Dismiss Vote Request" choice is open. */
    val askingDismiss: Boolean = false,
) {
    val title: String get() = if (voted) ProposalCopy.TITLE_VOTED else ProposalCopy.TITLE
    val body: String get() = if (voted) ProposalCopy.DESC_VOTED else ProposalCopy.DESC
}

/** RN en-US proposal.* */
object ProposalCopy {
    val TITLE: String get() = L.proposal.title
    val DESC: String get() = L.proposal.desc
    val TITLE_VOTED: String get() = L.proposal.titleVoted
    val DESC_VOTED: String get() = L.proposal.descVoted
    val VOTE: String get() = L.proposal.btnVote
    val DISMISS: String get() = L.wavesOnboarding.dismiss
    val DISMISS_TITLE: String get() = L.proposal.titleActionDismiss
    val FOREVER: String get() = L.proposal.btnIgnore
    val LATER: String get() = L.alert.remindLater
    val THANK_YOU: String get() = L.alert.thankyou
    val FAIL: String get() = L.alert.fail
}

/** RN `_remindLater` choices. */
enum class ProposalDismiss { FOREVER, LATER }

/**
 * RN ProposalVoteRequest, useActiveProposalMetaQuery, useProposalVotedQuery and useProposalVoteMutation.
 *
 * - Shown only signed in, for the newest @ecency proposal that has not expired, when the account has not voted
 *   for it, did not choose "Forever" and did not choose "Remind Later" in the last 3 days (RN proposalsVoteMeta,
 *   here device preferences keyed by proposal and account, which a wipe removes).
 * - "Cast My Vote" signs update_proposal_votes {voter, [id], approve true} with the active authority (auth upgrade,
 *   HiveSigner, HiveAuth) as one action with its own id; never retried by itself. Success: "Thank You!", the card
 *   says thanks for the rest of the run, the vote is remembered as processed. Failure: "Fail!: <message>".
 * - An account change resets the card for the new account.
 */
class ProposalVoteModel internal constructor(private val env: AppEnvironment) :
    StateModel<ProposalCardState>(ProposalCardState(), env.dispatchers) {

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

    /** Asks again (pull to refresh). */
    fun refresh() = reload()

    private fun reload() {
        loadJob?.cancel()
        setState { ProposalCardState(username = env.session.current.username) }
        loadJob = scope.launch {
            try {
                // at launch the accounts may still be read: the card belongs to whoever is signed in after that
                env.session.awaitLoaded()
                val user = env.session.current.username
                setState { if (it.username == user) it else ProposalCardState(username = user) }
                if (user == null) return@launch
                val proposal = env.proposalApi.proposals().filter { it.creator == ECENCY && it.status != "expired" }.maxByOrNull { it.id }
                    ?: return@launch
                val voted = env.proposalApi.votedIds(user)
                if (env.session.current.username != user) return@launch
                setState { s ->
                    if (s.username != user) s else s.copy(proposalId = proposal.id, visible = proposal.id !in voted && !hiddenByChoice(proposal.id, user))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // RN: the card stays hidden when its queries fail
            }
        }
    }

    private fun hiddenByChoice(id: Long, user: String): Boolean {
        val meta = env.prefs.getString(key(id, user)) ?: return false
        if (meta == PROCESSED) return true
        val dismissedAt = meta.toLongOrNull() ?: return false
        return dismissedAt + RE_REQUEST_MILLIS > env.nowMillis()
    }

    /** RN _voteAction. */
    fun vote() {
        val s = current
        val user = s.username ?: return
        val id = s.proposalId ?: return
        if (s.voting || s.voted || env.session.current.username != user) return
        setState { it.copy(voting = true) }
        val ops = buildJsonArray {
            add(
                buildJsonArray {
                    add(JsonPrimitive("update_proposal_votes"))
                    add(
                        buildJsonObject {
                            put("voter", user)
                            putJsonArray("proposal_ids") { add(JsonPrimitive(id)) }
                            put("approve", true)
                            putJsonArray("extensions") {}
                        },
                    )
                },
            )
        }
        val actionId = env.idempotencyKey()
        scope.launch {
            val final = CompletableDeferred<WalletSend>()
            env.walletSends.send(user, ops, KeyRole.ACTIVE, actionId) { final.complete(it) }
            // the chain cannot tell yet: the send keeps asking in the app (WalletSends); the card says so once
            val checking = launch {
                env.walletSends.state.first { it.sends[actionId]?.phase is SendPhase.Checking }
                env.ui.toast(com.ecency.core.api.broadcast.BroadcastException.UNDECIDED_MESSAGE)
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
                    env.prefs.putString(key(id, user), PROCESSED)
                    setState { it.copy(voting = false, voted = true, visible = true) }
                    env.ui.toast(ProposalCopy.THANK_YOU)
                }
                is SendPhase.Cancelled -> setState { it.copy(voting = false) }
                is SendPhase.Unconfirmed -> {
                    setState { it.copy(voting = false) }
                    env.ui.toast(phase.message)
                }
                is SendPhase.Failed -> {
                    setState { it.copy(voting = false) }
                    val message = phase.error.message
                    env.ui.toast(if (message.isNotEmpty()) "${ProposalCopy.FAIL}: $message" else ProposalCopy.FAIL)
                }
                else -> setState { it.copy(voting = false) }
            }
        }
    }

    /** RN Dismiss: the choice sheet. */
    fun askDismiss() {
        if (current.proposalId != null && current.username != null) setState { it.copy(askingDismiss = true) }
    }

    /** RN ignore (processed) or later (dismissed now); null closes the choice without changing anything. */
    fun dismiss(choice: ProposalDismiss?) {
        val s = current
        setState { it.copy(askingDismiss = false) }
        val user = s.username ?: return
        val id = s.proposalId ?: return
        when (choice) {
            ProposalDismiss.FOREVER -> env.prefs.putString(key(id, user), PROCESSED)
            ProposalDismiss.LATER -> env.prefs.putString(key(id, user), env.nowMillis().toString())
            null -> return
        }
        setState { it.copy(visible = false) }
    }

    companion object {
        const val ECENCY = "ecency"

        /** RN RE_REQUEST_INTERVAL: 3 days. */
        const val RE_REQUEST_MILLIS = 259_200_000L

        /** Device preference per proposal and account; a wipe removes it ([AccountPrefs]). */
        const val KEY_PREFIX = "proposal.vote."
        private const val PROCESSED = "processed"

        fun key(id: Long, user: String) = "$KEY_PREFIX${id}_$user"
    }
}

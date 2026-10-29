package com.ecency.core.app

import com.ecency.core.api.broadcast.BroadcastException
import com.ecency.core.api.broadcast.OperationJson
import com.ecency.core.hive.Vote
import com.ecency.core.l10n.L
import kotlinx.serialization.json.JsonArray
import com.ecency.core.api.model.PostKey
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** One post's vote as this device sees it. */
data class VoteStatus(
    val key: PostKey,
    /** Basis points: 10000 full upvote, negative downvote, 0 unvote. */
    val weight: Int,
    val inFlight: Boolean,
    /** The last attempt failed; the optimistic vote was rolled back. */
    val error: AppError?,
)

data class VoteState(val statuses: Map<PostKey, VoteStatus> = emptyMap()) {
    fun status(author: String, permlink: String): VoteStatus? = statuses[PostKey(author, permlink)]
}

/**
 * Votes with optimistic display: the vote shows in every feed and post at once
 * ([AppEnvironment.localVotes]) and is rolled back if the broadcast fails.
 *
 * Retry after a network failure with [retry] (or [vote] with the same weight): the broadcaster
 * resumes the same signed transaction (PendingBroadcast), so a vote that did land is never
 * sent as a second transaction, and an "identical vote" answer then counts as success.
 */
class VoteModel internal constructor(private val env: AppEnvironment) : StateModel<VoteState>(VoteState(), env.dispatchers) {

    /** Votes on [author]/[permlink]. Ignored while a vote on the same post is in flight. */
    fun vote(author: String, permlink: String, weight: Int) {
        val key = PostKey(author, permlink)
        if (current.statuses[key]?.inFlight == true) return
        if (weight !in -10000..10000) {
            update(key, VoteStatus(key, weight, false, AppError(ErrorKind.INVALID_INPUT, L.native.vote.voteWeightMustBe, false)))
            return
        }
        val previous = env.localVotes.get(key)
        env.localVotes.set(key, LocalVote(weight, pending = true))
        update(key, VoteStatus(key, weight, inFlight = true, error = null))
        val session = generation
        // the account that voted is the one signed in now, not whoever is signed in when the coroutine runs
        val voter = env.session.current.username
        scope.launch {
            try {
                // the account changed before the broadcast started: never sign this vote as the next account
                if (session != generation) return@launch
                if (voter == null) throw AppException(AppError(ErrorKind.AUTH, L.native.vote.pleaseSignInFirst, false))
                try {
                    // key accounts sign locally (a retry resends the same transaction), HiveSigner and HiveAuth
                    // accounts sign in their own app: SigningRouter decides
                    val signed = env.signing.broadcast(voter, JsonArray(listOf(OperationJson.encode(Vote(voter, author, permlink, weight)))))
                    // SDK useVote: the vote's points report (120), for a removed vote (weight 0) too (RN parity; the
                    // server decides what earns)
                    env.pointActivities.recordBroadcast(voter, PointActivity.VOTE, signed)
                    // RN upvotePopover: a vote that went out (not a removed one) is the other moment the rating prompt may be asked for
                    if (weight != 0 && session == generation) env.maybeRequestReview()
                } catch (e: BroadcastException.DuplicateVote) {
                    // already on chain with this weight: what the user wanted
                }
                // the account changed meanwhile: this result belongs to a session that is gone
                if (session != generation) return@launch
                env.localVotes.set(key, LocalVote(weight, pending = false))
                env.api.posts.invalidate(key)
                setState { VoteState(it.statuses - key) }
            } catch (e: CancellationException) {
                if (session == generation) rollback(key, previous)
                throw e
            } catch (e: Exception) {
                if (session != generation) return@launch
                rollback(key, previous)
                val error = AppError.fromBroadcast(e)
                // the user closed the signing screen: nothing to report; out of RC: RN's offer instead of the error
                if (error.kind == ErrorKind.CANCELLED || env.offerRcIfShort(e)) setState { VoteState(it.statuses - key) }
                else update(key, VoteStatus(key, weight, inFlight = false, error = error))
            }
        }
    }

    /** Bumped on every session change; results of votes started before it are ignored. */
    private var generation = 0

    /** Sends the failed vote on [author]/[permlink] again, same weight. */
    fun retry(author: String, permlink: String) {
        val status = current.statuses[PostKey(author, permlink)] ?: return
        if (status.inFlight || status.error == null) return
        vote(author, permlink, status.weight)
    }

    /** Hides the error of a failed vote. */
    fun dismissError(author: String, permlink: String) {
        val key = PostKey(author, permlink)
        setState { s -> if (s.statuses[key]?.inFlight == false) VoteState(s.statuses - key) else s }
    }

    /**
     * Forgets every status (sign-in, sign-out, wipe). Votes still in flight finish on chain, but their
     * results (success or rollback) no longer touch [AppEnvironment.localVotes] or this state, so a vote
     * of the previous account never shows up again for the next one.
     */
    internal fun reset() {
        generation++
        setState { VoteState() }
    }

    private fun rollback(key: PostKey, previous: LocalVote?) {
        if (previous == null) env.localVotes.remove(key) else env.localVotes.set(key, previous)
    }

    private fun update(key: PostKey, status: VoteStatus) = setState { VoteState(it.statuses + (key to status)) }
}

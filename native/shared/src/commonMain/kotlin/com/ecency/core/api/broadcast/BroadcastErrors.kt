package com.ecency.core.api.broadcast

import com.ecency.core.api.EcencyException
import com.ecency.core.api.rpc.AllNodesFailedException
import com.ecency.core.api.rpc.HiveApiException
import com.ecency.core.api.rpc.NodeFailureException
import com.ecency.core.api.rpc.RpcErrorException
import com.ecency.core.api.rpc.RpcTimeoutException
import com.ecency.core.l10n.L

/**
 * Why a broadcast failed, classified like @ecency/sdk's parseChainError so the UI can react
 * (ask for a key, explain RC, offer a retry) instead of showing raw node text.
 */
sealed class BroadcastException(message: String, cause: Throwable? = null) : EcencyException(message, cause) {
    /** The vote is identical to the one already on chain. */
    class DuplicateVote(cause: Throwable) : BroadcastException(L.native.broadcastErrors.duplicateVote, cause)

    /** Not enough Resource Credits (or bandwidth) to transact. */
    class InsufficientResourceCredits(cause: Throwable) :
        BroadcastException(L.native.broadcastErrors.insufficientRc, cause)

    /** The key used cannot sign this operation. */
    class MissingAuthority(cause: Throwable) : BroadcastException(L.native.broadcastErrors.missingAuthority, cause)

    /** Hive allows one comment per account every 3 seconds. */
    class CommentTooSoon(cause: Throwable) : BroadcastException(L.native.broadcastErrors.commentTooSoon, cause)

    /** Voting or editing after payout is not allowed. */
    class PaidOut(cause: Throwable) : BroadcastException(L.native.broadcastErrors.paidOut, cause)

    /** The operations were refused before anything was sent (vote weight, beneficiaries, empty...). */
    class InvalidOperation(message: String, cause: Throwable? = null) : BroadcastException(message, cause)

    /** The signed transaction is over Hive's 64 KB limit. */
    class TooLarge(val size: Int) :
        BroadcastException(L.native.broadcastErrors.tooLarge(size = size, max = HiveBroadcaster.MAX_TRANSACTION_SIZE.toString()))

    /**
     * No node could be reached, or none answered in time: the transaction may or may not have
     * landed. Retry ONLY through [pending] (`pending.send()`), or by calling the same
     * `vote`/`comment` again with the same arguments or the same draft: both re-send the same
     * signed transaction (or confirm it landed) instead of creating a second one. Never build a
     * new reply draft for a retry; it would get a new permlink and could post twice.
     */
    class Network(
        cause: Throwable,
        val pending: PendingBroadcast? = null,
        /**
         * True when an earlier send may have gone through and the chain could not yet say whether it did. Nothing
         * new was signed; the user should check their wallet or history before trying again (a retry through
         * [pending] asks the chain again first).
         */
        val undecided: Boolean = false,
    ) : BroadcastException(if (undecided) UNDECIDED_MESSAGE else L.native.broadcastErrors.networkErrorPleaseCheck, cause) {
        constructor(cause: Throwable, pending: PendingBroadcast?) : this(cause, pending, false)
    }

    /**
     * The end state of an undecided send ([Network.undecided]): long after the transaction [txId] expired the chain
     * still cannot say whether it went in, so this action is closed without sending anything again. The user checks
     * their wallet; doing the action again from the screen (a new action) sends it anew.
     */
    class Unconfirmed(val txId: String, cause: Throwable? = null) : BroadcastException(UNCONFIRMED_MESSAGE, cause)

    /** Any other rejection; [message] is the node's text, truncated. */
    class Rejected(message: String, cause: Throwable) : BroadcastException(message, cause)

    companion object {
        val UNCONFIRMED_MESSAGE: String get() = L.native.broadcastErrors.unconfirmedMessage

        val UNDECIDED_MESSAGE: String get() = L.native.broadcastErrors.undecidedMessage

        private fun has(text: String, pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(text)

        /** Maps a failure from [com.ecency.core.api.rpc.HiveRpcClient] to a typed error. */
        fun from(e: HiveApiException, pending: PendingBroadcast? = null): BroadcastException {
            if (e is NodeFailureException || e is RpcTimeoutException || e is AllNodesFailedException) return Network(e, pending)
            val text = if (e is RpcErrorException) "${e.rpcMessage} ${e.data.orEmpty()}" else e.message.orEmpty()
            return when {
                has(text, "please wait to transact|insufficient rc|rc mana|rc account|resource credits|bandwidth") ->
                    InsufficientResourceCredits(e)
                has(text, "you may only post once every") -> CommentTooSoon(e)
                has(text, "your current vote on this comment is identical") -> DuplicateVote(e)
                has(text, "votes evaluating for comment that is paid out is forbidden|comment_cashout") -> PaidOut(e)
                has(text, "missing (required )?(posting|active|owner) authority") -> MissingAuthority(e)
                else -> Rejected(((e as? RpcErrorException)?.rpcMessage ?: e.message.orEmpty()).take(150), e)
            }
        }

        internal fun isExpiredOrTapos(e: RpcErrorException): Boolean =
            has("${e.rpcMessage} ${e.data.orEmpty()}", "expiration|expired|tapos")
    }
}

package com.ecency.core.api.broadcast

import com.ecency.core.api.EcencyException
import com.ecency.core.api.rpc.AllNodesFailedException
import com.ecency.core.api.rpc.HiveApiException
import com.ecency.core.api.rpc.NodeFailureException
import com.ecency.core.api.rpc.RpcErrorException
import com.ecency.core.api.rpc.RpcTimeoutException
import com.ecency.core.l10n.L
import com.ecency.core.l10n.LocaleFormat

/**
 * Why a broadcast failed, classified like @ecency/sdk's parseChainError so the UI can react
 * (ask for a key, explain RC, offer a retry) instead of showing raw node text.
 */
sealed class BroadcastException(message: String, cause: Throwable? = null) : EcencyException(message, cause) {
    /** The vote is identical to the one already on chain. */
    class DuplicateVote(cause: Throwable) : BroadcastException(ChainRefusal.IDENTICAL_VOTE.message, cause)

    /** Not enough Resource Credits to transact ([ChainRefusal.RESOURCE_CREDITS], RN's RC_SHORTFALL). */
    class InsufficientResourceCredits(cause: Throwable) :
        BroadcastException(ChainRefusal.RESOURCE_CREDITS.message, cause)

    /** The key used cannot sign this operation: [refusal] names the role it needs. */
    class MissingAuthority(cause: Throwable, val refusal: ChainRefusal = ChainRefusal.MISSING_POSTING) :
        BroadcastException(refusal.message, cause)

    /** A cooldown: one post every 5 minutes, one comment every 3 seconds, one edit per block ([refusal]). */
    class CommentTooSoon(cause: Throwable, val refusal: ChainRefusal = ChainRefusal.POST_COOLDOWN) :
        BroadcastException(refusal.message, cause)

    /** Voting, deleting or changing the payout options after payout is not allowed. */
    class PaidOut(cause: Throwable) : BroadcastException(ChainRefusal.PAID_OUT.message, cause)

    /** Any other refusal hived names ([ChainRefusal]): a title too long, a beneficiary list, funds... */
    class Refused(val refusal: ChainRefusal, cause: Throwable) : BroadcastException(refusal.message, cause)

    /** The operations were refused before anything was sent (vote weight, beneficiaries, empty...). */
    class InvalidOperation(message: String, cause: Throwable? = null) : BroadcastException(message, cause)

    /** The signed transaction is over Hive's 64 KB limit. */
    class TooLarge(val size: Int) : BroadcastException(
        L.native.chainRefusal.tooLarge(
            size = LocaleFormat.integer(((size + 1023) / 1024).toLong()),
            max = LocaleFormat.integer((HiveBroadcaster.MAX_TRANSACTION_SIZE / 1024).toLong()),
        ),
    )

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
            val refusal = if (e is RpcErrorException) ChainRefusal.of(e.rpcMessage, e.data) else ChainRefusal.of(e.message)
            return when (refusal) {
                null -> Rejected(((e as? RpcErrorException)?.rpcMessage ?: e.message.orEmpty()).take(150), e)
                else -> of(refusal, e)
            }
        }

        /** The typed error for a [refusal] read from [cause] (a node, HiveSigner or HiveAuth failure). */
        fun of(refusal: ChainRefusal, cause: Throwable): BroadcastException = when (refusal) {
            ChainRefusal.RESOURCE_CREDITS -> InsufficientResourceCredits(cause)
            ChainRefusal.POST_COOLDOWN, ChainRefusal.COMMENT_COOLDOWN, ChainRefusal.EDIT_COOLDOWN -> CommentTooSoon(cause, refusal)
            ChainRefusal.IDENTICAL_VOTE -> DuplicateVote(cause)
            ChainRefusal.PAID_OUT -> PaidOut(cause)
            ChainRefusal.MISSING_POSTING, ChainRefusal.MISSING_ACTIVE, ChainRefusal.MISSING_OWNER -> MissingAuthority(cause, refusal)
            else -> Refused(refusal, cause)
        }

        internal fun isExpiredOrTapos(e: RpcErrorException): Boolean =
            has("${e.rpcMessage} ${e.data.orEmpty()}", "expiration|expired|tapos")
    }
}

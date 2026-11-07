package com.ecency.core.api.broadcast

import com.ecency.core.l10n.L

/**
 * What the chain refused, read from any text a failure carries: the node's message and `data`, HiveSigner's
 * `error_description`, a HiveAuth wallet's reason (issue #3660). The patterns are hived's own assertion texts (1.28.8:
 * `hive_evaluator_social.cpp`, `hive_operations.cpp`, `validation.hpp`, `rc_utility.cpp`); each refusal says what
 * happened and what to do. The first match wins, so the more specific patterns come first.
 */
enum class ChainRefusal(private val pattern: Regex) {
    /** RN `rcError.ts` RC_SHORTFALL, the one rule for the error kind and the Top up / Boost offer. */
    RESOURCE_CREDITS(Regex("please wait to transact|has [0-9]+ RC, needs [0-9]+ RC", RegexOption.IGNORE_CASE)),
    POST_COOLDOWN(Regex("you may only post once", RegexOption.IGNORE_CASE)),
    COMMENT_COOLDOWN(Regex("you may only comment once", RegexOption.IGNORE_CASE)),
    EDIT_COOLDOWN(Regex("one comment edit per block", RegexOption.IGNORE_CASE)),
    IDENTICAL_VOTE(Regex("current vote on this comment is identical|already voted in a similar way", RegexOption.IGNORE_CASE)),
    VOTE_TOO_SMALL(
        Regex(
            "voting weight is too small|does not have enough power to vote|does not have voting power|vote weight cannot be 0|cannot vote with 0 rshares",
            RegexOption.IGNORE_CASE,
        ),
    ),
    PAID_OUT(
        Regex(
            "comment that is paid out is forbidden|cannot delete comment after payout|discussion is frozen|comment_cashout",
            RegexOption.IGNORE_CASE,
        ),
    ),
    DELETE_VOTED(Regex("cannot delete a comment with net positive votes", RegexOption.IGNORE_CASE)),
    DELETE_REPLIES(Regex("cannot delete a comment with replies|children == 0", RegexOption.IGNORE_CASE)),
    TITLE_TOO_LONG(Regex("title size limit exceeded", RegexOption.IGNORE_CASE)),
    BODY_EMPTY(Regex("body is empty", RegexOption.IGNORE_CASE)),
    NOT_UTF8(Regex("not formatted in utf-?8|not valid utf-?8|must be utf-?8", RegexOption.IGNORE_CASE)),
    OPTIONS_LOCKED(
        Regex(
            "already has beneficiaries specified|must not have been voted on before specifying beneficiaries|requires the comment to have no rshares",
            RegexOption.IGNORE_CASE,
        ),
    ),
    BENEFICIARIES_REFUSED(Regex("beneficiaries must be specified in sorted order|beneficiary .* must exist|at least one beneficiary", RegexOption.IGNORE_CASE)),
    PAYOUT_RAISED(Regex("cannot accept a greater (payout|percent)|cannot increase payout", RegexOption.IGNORE_CASE)),
    INSUFFICIENT_FUNDS(Regex("does not have sufficient (funds|hive power)", RegexOption.IGNORE_CASE)),
    HIVESIGNER_NO_PERMISSION(Regex("permission to broadcast", RegexOption.IGNORE_CASE)),
    MISSING_POSTING(Regex("missing (required )?posting authority", RegexOption.IGNORE_CASE)),
    MISSING_ACTIVE(Regex("missing (required )?active authority", RegexOption.IGNORE_CASE)),
    MISSING_OWNER(Regex("missing (required )?owner authority", RegexOption.IGNORE_CASE)),
    ;

    /** What the user reads: what happened and what to do. Copy, read when shown. */
    val message: String get() = when (this) {
        RESOURCE_CREDITS -> L.native.chainRefusal.resourceCredits
        POST_COOLDOWN -> L.native.chainRefusal.postCooldown
        COMMENT_COOLDOWN -> L.native.chainRefusal.commentCooldown
        EDIT_COOLDOWN -> L.native.chainRefusal.editCooldown
        IDENTICAL_VOTE -> L.native.chainRefusal.identicalVote
        VOTE_TOO_SMALL -> L.native.chainRefusal.voteTooSmall
        PAID_OUT -> L.native.chainRefusal.paidOut
        DELETE_VOTED -> L.native.chainRefusal.deleteVoted
        DELETE_REPLIES -> L.native.chainRefusal.deleteReplies
        TITLE_TOO_LONG -> L.native.chainRefusal.titleTooLong
        BODY_EMPTY -> L.native.chainRefusal.bodyEmpty
        NOT_UTF8 -> L.native.chainRefusal.notUtf8
        OPTIONS_LOCKED -> L.native.chainRefusal.optionsLocked
        BENEFICIARIES_REFUSED -> L.native.chainRefusal.beneficiariesRefused
        PAYOUT_RAISED -> L.native.chainRefusal.payoutRaised
        INSUFFICIENT_FUNDS -> L.native.chainRefusal.insufficientFunds
        HIVESIGNER_NO_PERMISSION -> L.native.chainRefusal.hiveSignerNoPermission
        MISSING_POSTING -> L.native.chainRefusal.missingPosting
        MISSING_ACTIVE -> L.native.chainRefusal.missingActive
        MISSING_OWNER -> L.native.chainRefusal.missingOwner
    }

    /** Whether [text] carries this refusal. */
    fun matches(text: String?): Boolean = !text.isNullOrBlank() && pattern.containsMatchIn(text)

    companion object {
        /** The refusal [texts] carry (null and blank ones skipped), or null when none is known. */
        fun of(vararg texts: String?): ChainRefusal? {
            return entries.firstOrNull { refusal -> texts.any { refusal.matches(it) } }
        }

        /** The refusal anywhere in [error]'s cause chain (its messages, and a node error's `data`). */
        fun of(error: Throwable?): ChainRefusal? {
            var e = error
            var depth = 0
            while (e != null && depth++ < 8) {
                val data = (e as? com.ecency.core.api.rpc.RpcErrorException)?.data
                of(e.message, data)?.let { return it }
                e = e.cause
            }
            return null
        }
    }
}

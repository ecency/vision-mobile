package com.ecency.core.api.broadcast

import com.ecency.core.hive.AccountUpdate2
import com.ecency.core.hive.AccountUpdatePosting
import com.ecency.core.hive.Comment
import com.ecency.core.hive.CommentOptions
import com.ecency.core.hive.CustomJson
import com.ecency.core.hive.DeleteComment
import com.ecency.core.hive.Operation
import com.ecency.core.hive.Vote

/**
 * Operations whose repetition leaves the chain as one send would (the chain refuses or ignores a second identical
 * one), so the operations alone may serve as their retry key and a long-expired key may sign anew. Anything else
 * (transfers, delegations, power ups, conversions, claims, Hive Engine or Points custom_json with active auths, ...)
 * needs an action id ([HiveBroadcaster.broadcastAction]).
 */
object RepeatSafeOps {
    /** Posting-only custom_json ids of social actions (following, reblogs, communities, notifications, polls). */
    private val CUSTOM_JSON_IDS = setOf("follow", "reblog", "community", "notify", "ecency_notify", "polls")

    fun all(ops: List<Operation>): Boolean = ops.isNotEmpty() && ops.all { op ->
        when (op) {
            is Vote, is Comment, is CommentOptions, is DeleteComment -> true
            // account_update / account_update2 set state (metadata, an authority) to given values: applying the same
            // operation twice leaves the account exactly as once (state-idempotent), unlike anything that moves value
            is AccountUpdate2, is AccountUpdatePosting -> true
            is CustomJson -> op.requiredAuths.isEmpty() && op.id in CUSTOM_JSON_IDS
            else -> false
        }
    }
}

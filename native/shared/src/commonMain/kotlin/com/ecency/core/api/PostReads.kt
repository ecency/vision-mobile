package com.ecency.core.api

import com.ecency.core.api.rpc.HiveRpcClient
import com.ecency.core.api.model.arr
import com.ecency.core.api.model.obj
import com.ecency.core.api.model.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlin.coroutines.cancellation.CancellationException

/** A row of the cross post picker: a subscribed community's id ("hive-123456") and title. */
data class SubscribedCommunity(val name: String, val title: String)

/** `condenser_api.get_reblogged_by`: accounts that reblogged the post (the author included by hived). */
@Throws(EcencyException::class, CancellationException::class)
suspend fun HiveApi.rebloggedBy(author: String, permlink: String): List<String> = guarded {
    client.call(
        "condenser_api.get_reblogged_by",
        buildJsonArray {
            add(JsonPrimitive(author))
            add(JsonPrimitive(permlink))
        },
    ).arr().orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull ?: it.obj()?.str("account") }
}

/** The account's raw posting_json_metadata (fresh, never cached): the base for profile updates. */
@Throws(EcencyException::class, CancellationException::class)
suspend fun HiveApi.postingJsonMetadata(account: String): String? = guarded {
    client.call("condenser_api.get_accounts", buildJsonArray { add(buildJsonArray { add(JsonPrimitive(account)) }) }, validate = HiveRpcClient.IS_ARRAY)
        .arr().orEmpty().firstOrNull { it.obj()?.str("name") == account }?.obj()?.str("posting_json_metadata")
}

/** One vote from `condenser_api.get_active_votes`. [time] is chain UTC "YYYY-MM-DDTHH:MM:SS". */
data class ActiveVoteDetail(val voter: String, val rshares: Long, val percent: Int, val time: String)

/** `condenser_api.get_active_votes [author, permlink]`, the RN voters screen's source. */
@Throws(EcencyException::class, CancellationException::class)
suspend fun HiveApi.activeVotes(author: String, permlink: String): List<ActiveVoteDetail> = guarded {
    client.call(
        "condenser_api.get_active_votes",
        buildJsonArray {
            add(JsonPrimitive(author))
            add(JsonPrimitive(permlink))
        },
    ).arr().orEmpty().mapNotNull { v ->
        val o = v.obj() ?: return@mapNotNull null
        val voter = o.str("voter") ?: return@mapNotNull null
        fun num(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
        ActiveVoteDetail(voter, (o["rshares"] as? JsonPrimitive)?.contentOrNull?.let { it.toLongOrNull() ?: it.toDoubleOrNull()?.toLong() } ?: 0, num("percent")?.toInt() ?: 0, o.str("time") ?: "")
    }
}

/**
 * SDK getMutedUsersQueryOptions: the accounts [account] ignores, `condenser_api.get_following` with type
 * "ignore" in pages of 1000, at most 20 pages.
 */
@Throws(EcencyException::class, CancellationException::class)
suspend fun HiveApi.mutedUsers(account: String): List<String> = guarded {
    val out = mutableListOf<String>()
    var start = ""
    repeat(20) {
        val page = client.call(
            "condenser_api.get_following",
            buildJsonArray {
                add(JsonPrimitive(account))
                add(JsonPrimitive(start))
                add(JsonPrimitive("ignore"))
                add(JsonPrimitive(1000))
            },
        ).arr().orEmpty().mapNotNull { it.obj()?.str("following") }
        if (page.isEmpty()) return@guarded out
        val fresh = if (page.first() == start) page.drop(1) else page
        if (fresh.isEmpty()) return@guarded out
        out += fresh
        if (page.size < 1000) return@guarded out
        start = fresh.last()
    }
    out
}

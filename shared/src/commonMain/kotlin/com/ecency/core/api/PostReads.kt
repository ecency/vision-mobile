package com.ecency.core.api

import com.ecency.core.api.rpc.HiveRpcClient
import com.ecency.core.api.model.arr
import com.ecency.core.api.model.obj
import com.ecency.core.api.model.str
import com.ecency.core.api.model.ObserverVote
import com.ecency.core.api.model.Post
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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

/** One vote on a post (a voters list row). [time] is chain UTC "YYYY-MM-DDTHH:MM:SS". */
data class ActiveVoteDetail(val voter: String, val rshares: Long, val percent: Int, val time: String)

/** Page size of `bridge.list_votes` and `database_api.list_votes` (SDK LIST_VOTES_PAGE_SIZE). */
private const val LIST_VOTES_PAGE = 1000

/** At most this many pages for one post, so a misbehaving node cannot loop (SDK MAX_LIST_VOTES_PAGES). */
private const val LIST_VOTES_MAX_PAGES = 100

/** `database_api.list_votes` by_comment_voter from [voter] (inclusive, a lower-bound seek). */
private fun databaseListVotes(author: String, permlink: String, voter: String, limit: Int) = buildJsonObject {
    put("start", buildJsonArray { add(JsonPrimitive(author)); add(JsonPrimitive(permlink)); add(JsonPrimitive(voter)) })
    put("limit", limit)
    put("order", "by_comment_voter")
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/**
 * SDK getEntryVote: [voter]'s vote on the post, or null when the account has not voted. The seek returns the NEXT row
 * (another voter's or another post's) for an account that did not vote, so the row must match all three; a removed
 * vote (percent 0) is null too. `database_api` is hived's own state, so it does not lag like hivemind.
 */
@Throws(EcencyException::class, CancellationException::class)
suspend fun HiveApi.ownVote(author: String, permlink: String, voter: String): ObserverVote? = guarded {
    val row = (client.call("database_api.list_votes", databaseListVotes(author, permlink, voter, 1)) as? JsonObject)
        ?.get("votes")?.let { it as? JsonArray }?.firstOrNull() as? JsonObject ?: return@guarded null
    if (row.text("author") != author || row.text("permlink") != permlink || row.text("voter") != voter) return@guarded null
    val percent = Post.jsLong(row["vote_percent"]).toInt()
    if (percent == 0) null else ObserverVote(Post.jsLong(row["rshares"]), percent)
}

/**
 * SDK getEntryVotes, every vote on a post for the voters screen (entries no longer carry the list, and v1 lists and
 * `get_active_votes` stop at 1000): `bridge.list_votes` pages (start exclusive) until a short page or one with no new
 * voter. A node without it answers a `database_api.list_votes` page, and the list is then read through that method
 * only (the two order differently, so they are never combined).
 */
@Throws(EcencyException::class, CancellationException::class)
suspend fun HiveApi.entryVotes(author: String, permlink: String): List<ActiveVoteDetail> = guarded {
    val votes = mutableListOf<ActiveVoteDetail>()
    val seen = mutableSetOf<String>()
    var start: String? = null
    for (page in 0 until LIST_VOTES_MAX_PAGES) {
        val params = buildJsonObject {
            put("author", author)
            put("permlink", permlink)
            put("limit", LIST_VOTES_PAGE)
            start?.let { put("start", it) }
        }
        val result = client.callWithFallback(
            "bridge.list_votes", params, "database_api.list_votes", databaseListVotes(author, permlink, "", LIST_VOTES_PAGE),
        ).result
        // by shape: a `{votes}` body is a database_api page, an array a list_votes page
        val dbPage = (result as? JsonObject)?.get("votes") as? JsonArray
        if (result !is JsonArray) return@guarded votesFromDatabase(author, permlink, dbPage?.takeIf { page == 0 })
        var added = 0
        for (row in result) {
            val o = row as? JsonObject ?: continue
            val voter = o.text("voter") ?: continue
            if (!seen.add(voter)) continue
            votes += ActiveVoteDetail(voter, Post.jsLong(o["rshares"]), Post.jsLong(o["percent"]).toInt(), o.text("time").orEmpty())
            added++
        }
        if (result.size < LIST_VOTES_PAGE || added == 0) break
        start = (result.last() as? JsonObject)?.text("voter")
    }
    votes
}

/**
 * SDK getEntryVotesFromDatabase: `database_api.list_votes` pages. The start is inclusive, so a page repeats the last
 * voter of the one before (skipped); the index runs on into the next post, whose first row ends the list.
 */
private suspend fun HiveApi.votesFromDatabase(author: String, permlink: String, firstPage: JsonArray?): List<ActiveVoteDetail> {
    val votes = mutableListOf<ActiveVoteDetail>()
    val seen = mutableSetOf<String>()
    var start = ""
    var page = firstPage
    for (n in 0 until LIST_VOTES_MAX_PAGES) {
        val rows = page ?: ((client.call("database_api.list_votes", databaseListVotes(author, permlink, start, LIST_VOTES_PAGE)) as? JsonObject)
            ?.get("votes") as? JsonArray).orEmpty()
        var added = 0
        var ended = false
        for (row in rows) {
            val o = row as? JsonObject
            if (o == null || o.text("author") != author || o.text("permlink") != permlink) {
                ended = true
                break
            }
            val voter = o.text("voter") ?: continue
            if (!seen.add(voter)) continue
            votes += ActiveVoteDetail(voter, Post.jsLong(o["rshares"]), Post.jsLong(o["vote_percent"]).toInt(), o.text("last_update").orEmpty())
            added++
        }
        if (ended || rows.size < LIST_VOTES_PAGE || added == 0) break
        start = (rows.last() as? JsonObject)?.text("voter").orEmpty()
        page = null
    }
    return votes
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


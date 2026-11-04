package com.ecency.core.api.model

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.pow

/** `author/permlink`, the identity of a post or comment. */
data class PostKey(val author: String, val permlink: String) {
    override fun toString(): String = "$author/$permlink"

    companion object {
        /** Parses "author/permlink" (a leading "@" is allowed). Null when malformed. */
        fun parse(value: String?): PostKey? {
            val parts = value?.trim()?.removePrefix("@")?.split("/") ?: return null
            if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) return null
            return PostKey(parts[0], parts[1])
        }
    }
}

data class ActiveVote(
    val voter: String,
    /** Can be negative (downvote). */
    val rshares: Long,
)

data class PostStats(
    val hide: Boolean = false,
    val gray: Boolean = false,
    val totalVotes: Int = 0,
    val flagWeight: Double = 0.0,
    /** Pinned in its community; the bridge puts pinned posts first on a community's first page. */
    val isPinned: Boolean = false,
    /** Pinned on the author's blog (`is_pinned_blog`); null when the entry does not say. */
    val isPinnedBlog: Boolean? = null,
)

data class PostBeneficiary(val account: String, val weight: Int)

/**
 * A post or comment as the bridge API returns it (`bridge.get_ranked_posts`, `get_account_posts`,
 * `get_post`, `get_discussion`). Missing fields take the same defaults as @ecency/sdk's
 * validateEntry, so the UI never has to null-check what the SDK guarantees.
 */
data class Post(
    val author: String,
    val permlink: String,
    val category: String = "",
    val title: String = "",
    val body: String = "",
    val metadata: PostMetadata = PostMetadata(),
    /** UTC, "YYYY-MM-DDTHH:MM:SS". */
    val created: String = "",
    val updated: String = "",
    val depth: Int = 0,
    /** Number of replies at any depth below this entry. */
    val children: Int = 0,
    val parentAuthor: String? = null,
    val parentPermlink: String? = null,
    val url: String = "",
    /** Display reputation, floored as the app shows it (bridge 64.7 is 64; raw 0 is 25). */
    val authorReputation: Int = 25,
    val netRshares: Long = 0,
    /** Total payout in HBD as computed by the bridge. */
    val payout: Double = 0.0,
    val pendingPayoutValue: String = "0.000 HBD",
    val authorPayoutValue: String = "0.000 HBD",
    val curatorPayoutValue: String = "0.000 HBD",
    val maxAcceptedPayout: String = "1000000.000 HBD",
    val percentHbd: Int = 0,
    val promoted: String = "0.000 HBD",
    val isPaidout: Boolean = false,
    val payoutAt: String = "",
    val activeVotes: List<ActiveVote> = emptyList(),
    /** Direct replies, in the bridge's order. Only filled by `get_discussion`. */
    val replies: List<PostKey> = emptyList(),
    val community: String? = null,
    val communityTitle: String? = null,
    val authorRole: String? = null,
    val authorTitle: String? = null,
    val stats: PostStats = PostStats(),
    val reblogs: Int = 0,
    /** Accounts that reblogged this into the requested blog feed (account feeds only). */
    val rebloggedBy: List<String> = emptyList(),
    val beneficiaries: List<PostBeneficiary> = emptyList(),
    val blacklists: List<String> = emptyList(),
    /** For a cross-post, the original entry once resolved (see FeedRepository). */
    val crossPostOriginal: Post? = null,
    /**
     * pending + author + curator payout in HBD, as the app's total_payout; entries that carry
     * only a numeric payout (no max_accepted_payout) use that number, as the app does.
     */
    val totalPayout: Double = assetAmount(pendingPayoutValue) + assetAmount(authorPayoutValue) + assetAmount(curatorPayoutValue),
    /** Why the app would de-emphasize this entry (the SDK's getContentModerationReason), or null. */
    val moderationReason: ModerationReason? = null,
) {
    val key: PostKey get() = PostKey(author, permlink)

    /** The app's isMuted: any moderation reason applies. */
    val isMuted: Boolean get() = moderationReason != null

    /** Payout declined by the author (max_accepted_payout of zero). */
    val isDeclinedPayout: Boolean get() = assetAmount(maxAcceptedPayout) == 0.0

    /** A cross-post wrapper: its body is a link to [PostMetadata.originalAuthor]/[PostMetadata.originalPermlink]. */
    val isCrossPost: Boolean
        get() = metadata.originalAuthor != null && metadata.originalPermlink != null &&
            metadata.tags.firstOrNull() == "cross-post"

    val isDownvoted: Boolean get() = netRshares < 0

    fun voteOf(voter: String): ActiveVote? = activeVotes.firstOrNull { it.voter == voter }

    companion object {
        /** Parses one bridge entry. Null only when it has no author or permlink. */
        fun fromJson(element: JsonElement?): Post? {
            val o = element.obj() ?: return null
            val author = o.str("author")?.takeIf { it.isNotBlank() } ?: return null
            val permlink = o.str("permlink")?.takeIf { it.isNotBlank() } ?: return null
            val stats = o["stats"].obj()
            val activeVotes = o["active_votes"].arr().orEmpty().mapNotNull(::parseVote)
            val rawReputation = o.double("author_reputation")
            val netRshares = o.long("net_rshares") ?: 0
            val body = o.string("body")
            val pending = assetString(o["pending_payout_value"]) ?: "0.000 HBD"
            val authorPayout = assetString(o["author_payout_value"]) ?: "0.000 HBD"
            val curatorPayout = assetString(o["curator_payout_value"]) ?: "0.000 HBD"
            val numericPayout = o.double("payout") ?: o.double("total_payout")
            val numericOnly = numericPayout != null && o["max_accepted_payout"].let { it == null || it is kotlinx.serialization.json.JsonNull }
            val postStats = PostStats(
                hide = stats?.bool("hide") ?: false,
                gray = stats?.bool("gray") ?: false,
                // hivemind's count when present, else active_votes (the SDK's countVotes)
                totalVotes = stats?.int("total_votes") ?: activeVotes.size,
                flagWeight = stats?.double("flag_weight") ?: 0.0,
                isPinned = stats?.bool("is_pinned") ?: false,
                isPinnedBlog = stats?.bool("is_pinned_blog"),
            )
            return Post(
                author = author,
                permlink = permlink,
                category = o.string("category"),
                title = o.string("title"),
                body = body,
                metadata = PostMetadata.parse(o["json_metadata"]),
                created = o.string("created"),
                updated = o.string("updated"),
                depth = o.int("depth") ?: 0,
                children = o.int("children") ?: 0,
                parentAuthor = o.str("parent_author"),
                parentPermlink = o.str("parent_permlink"),
                url = o.string("url"),
                authorReputation = Reputation.display(rawReputation ?: 0.0),
                netRshares = netRshares,
                payout = o.double("payout") ?: 0.0,
                pendingPayoutValue = pending,
                authorPayoutValue = authorPayout,
                curatorPayoutValue = curatorPayout,
                maxAcceptedPayout = assetString(o["max_accepted_payout"]) ?: "1000000.000 HBD",
                percentHbd = o.int("percent_hbd") ?: 0,
                promoted = assetString(o["promoted"]) ?: "0.000 HBD",
                isPaidout = o.bool("is_paidout") ?: false,
                payoutAt = o.string("payout_at"),
                activeVotes = activeVotes,
                replies = o["replies"].arr().orEmpty().mapNotNull(::parseReplyKey),
                community = o.str("community")?.takeIf { it.isNotBlank() },
                communityTitle = o.str("community_title")?.takeIf { it.isNotBlank() },
                authorRole = o.str("author_role")?.takeIf { it.isNotBlank() },
                authorTitle = o.str("author_title")?.takeIf { it.isNotBlank() },
                stats = postStats,
                // `reblogs` is a count on the bridge; some nodes send a list of accounts instead.
                reblogs = o.int("reblogs") ?: o["reblogs"].arr()?.size ?: 0,
                rebloggedBy = o["reblogged_by"].stringList(),
                beneficiaries = o["beneficiaries"].arr().orEmpty().mapNotNull { b ->
                    val bo = b.obj() ?: return@mapNotNull null
                    val account = bo.str("account") ?: return@mapNotNull null
                    PostBeneficiary(account, bo.int("weight") ?: 0)
                },
                blacklists = o["blacklists"].stringList(),
                totalPayout = if (numericOnly) numericPayout else
                    assetAmount(pending) + assetAmount(authorPayout) + assetAmount(curatorPayout),
                moderationReason = Moderation.reason(postStats, netRshares, rawReputation, body),
            )
        }

        private fun parseVote(element: JsonElement): ActiveVote? {
            val o = element.obj() ?: return null
            val voter = o.str("voter")?.takeIf { it.isNotBlank() } ?: return null
            return ActiveVote(voter, o.long("rshares") ?: 0)
        }

        private fun parseReplyKey(element: JsonElement): PostKey? = when (element) {
            is JsonPrimitive -> if (element.isString) PostKey.parse(element.content) else null
            is JsonObject -> {
                val a = element.str("author")
                val p = element.str("permlink")
                if (a.isNullOrBlank() || p.isNullOrBlank()) null else PostKey(a, p)
            }
            else -> null
        }

        private val ASSET = Regex("""^(-?\d+(?:\.\d+)?)\s*([A-Za-z]+)$""")

        /** "1.234 HBD" as is; a NAI object ({amount, precision, nai}) rendered as HBD/HIVE text. */
        private fun assetString(element: JsonElement?): String? = when (element) {
            is JsonPrimitive -> element.content.trim().takeIf { element.isString && ASSET.matches(it) }
            is JsonObject -> {
                val amount = element.long("amount")
                val precision = element.int("precision")
                val symbol = when (element.str("nai")) {
                    "@@000000013" -> "HBD"
                    "@@000000021" -> "HIVE"
                    "@@000000037" -> "VESTS"
                    else -> null
                }
                if (amount == null || precision == null || symbol == null || precision !in 0..9) null
                else {
                    val value = amount / 10.0.pow(precision)
                    "${formatFixed(value, precision)} $symbol"
                }
            }
            else -> null
        }

        /** The numeric part of an asset string, 0 when unparseable. */
        fun assetAmount(value: String): Double =
            ASSET.matchEntire(value.trim())?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0

        private fun formatFixed(value: Double, decimals: Int): String {
            val scale = 10.0.pow(decimals)
            val units = kotlin.math.round(value * scale).toLong()
            val negative = units < 0
            val digits = (if (negative) -units else units).toString().padStart(decimals + 1, '0')
            val whole = digits.dropLast(decimals)
            val fraction = digits.takeLast(decimals)
            return (if (negative) "-" else "") + whole + if (decimals > 0) ".$fraction" else ""
        }
    }
}

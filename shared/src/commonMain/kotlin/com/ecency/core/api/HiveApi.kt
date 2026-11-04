package com.ecency.core.api

import com.ecency.core.api.model.Account
import com.ecency.core.api.model.FollowCount
import com.ecency.core.api.model.RcAccount
import com.ecency.core.api.model.Relationship
import com.ecency.core.api.model.int
import com.ecency.core.api.model.Post
import com.ecency.core.api.model.Profile
import com.ecency.core.api.model.arr
import com.ecency.core.api.model.obj
import com.ecency.core.api.model.long
import com.ecency.core.api.model.str
import com.ecency.core.api.rpc.HiveRpcClient
import com.ecency.core.api.rpc.NodeFailureException
import com.ecency.core.api.rpc.RpcErrorException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** `bridge.get_ranked_posts` sorts. */
enum class RankedSort(val apiValue: String) {
    TRENDING("trending"),
    HOT("hot"),
    CREATED("created"),
    PAYOUT("payout"),
    PAYOUT_COMMENTS("payout_comments"),
    MUTED("muted"),
}

/** `bridge.get_account_posts` sorts. FEED is the app's "friends" feed. */
enum class AccountPostsSort(val apiValue: String) {
    BLOG("blog"),
    FEED("feed"),
    POSTS("posts"),
    COMMENTS("comments"),
    REPLIES("replies"),
    PAYOUT("payout"),
}

/** Where a page starts: the last entry of the previous page (the bridge excludes it). */
data class PageCursor(val author: String, val permlink: String)

/** What a transaction's TaPoS fields and the broadcast need from the chain head. */
data class ChainHead(
    val headBlockNumber: Long,
    val headBlockId: String,
    /** UTC "YYYY-MM-DDTHH:MM:SS". */
    val time: String,
)

/**
 * Typed wrappers over the Hive RPC methods the app reads, with the same parameters
 * @ecency/sdk sends. Null or absent optional parameters are left out of the request, as
 * JSON.stringify drops `undefined` in the app (an empty observer string would hide the
 * viewer's own votes).
 */
class HiveApi(val client: HiveRpcClient) {

    @Throws(EcencyException::class, CancellationException::class)
    suspend fun rankedPosts(
        sort: RankedSort,
        tag: String = "",
        cursor: PageCursor? = null,
        limit: Int = 20,
        observer: String? = null,
    ): List<Post> = posts(
        client.call(
            "bridge.get_ranked_posts",
            buildJsonObject {
                put("sort", sort.apiValue)
                cursor?.let {
                    put("start_author", it.author)
                    put("start_permlink", it.permlink)
                }
                put("limit", limit)
                put("tag", tag)
                observer?.takeIf { it.isNotBlank() }?.let { put("observer", it) }
            },
        ),
    )

    @Throws(EcencyException::class, CancellationException::class)
    suspend fun accountPosts(
        account: String,
        sort: AccountPostsSort,
        cursor: PageCursor? = null,
        limit: Int = 20,
        observer: String? = null,
    ): List<Post> = posts(
        client.call(
            "bridge.get_account_posts",
            buildJsonObject {
                put("sort", sort.apiValue)
                put("account", account)
                cursor?.let {
                    put("start_author", it.author)
                    put("start_permlink", it.permlink)
                }
                put("limit", limit)
                observer?.takeIf { it.isNotBlank() }?.let { put("observer", it) }
            },
        ),
    )

    /** `bridge.get_post`. Null when the post does not exist. */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun post(author: String, permlink: String, observer: String? = null): Post? = notFoundAsNull {
        Post.fromJson(
            client.call(
                "bridge.get_post",
                buildJsonObject {
                    put("author", author)
                    put("permlink", permlink)
                    observer?.takeIf { it.isNotBlank() }?.let { put("observer", it) }
                },
            ),
        )
    }

    /** `bridge.get_discussion`: the root and every reply below it, keyed "author/permlink". */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun discussion(author: String, permlink: String, observer: String): List<Post>? = notFoundAsNull {
        val result = client.call(
            "bridge.get_discussion",
            buildJsonObject {
                put("author", author)
                put("permlink", permlink)
                put("observer", observer)
            },
        )
        when (result) {
            is JsonObject -> result.values.mapNotNull(Post::fromJson)
            is JsonArray -> result.mapNotNull(Post::fromJson)
            else -> emptyList()
        }
    }

    /** `bridge.get_profile`. Null when the account does not exist. */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun profile(account: String, observer: String? = null): Profile? = notFoundAsNull {
        Profile.fromJson(
            client.call(
                "bridge.get_profile",
                buildJsonObject {
                    put("account", account)
                    observer?.takeIf { it.isNotBlank() }?.let { put("observer", it) }
                },
            ),
        )
    }

    /** `condenser_api.get_accounts`. Unknown names are simply absent from the result. */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun accounts(names: List<String>): List<Account> {
        if (names.isEmpty()) return emptyList()
        val result = client.call(
            "condenser_api.get_accounts",
            buildJsonArray { add(buildJsonArray { names.forEach { add(JsonPrimitive(it)) } }) },
            validate = HiveRpcClient.IS_ARRAY,
        )
        return result.arr().orEmpty().mapNotNull(Account::fromJson)
    }

    /** `bridge.get_profiles`: profile headers for several accounts (no observer, as the SDK's friends list). */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun profiles(names: List<String>): List<Profile> {
        if (names.isEmpty()) return emptyList()
        val result = client.call(
            "bridge.get_profiles",
            buildJsonObject { put("accounts", buildJsonArray { names.forEach { add(JsonPrimitive(it)) } }) },
        )
        return result.arr().orEmpty().mapNotNull(Profile::fromJson)
    }

    /** `bridge.get_relationship_between_accounts` [observer, target]; all false when the node answers null. */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun relationship(observer: String, target: String): Relationship = Relationship.fromJson(
        client.call(
            "bridge.get_relationship_between_accounts",
            buildJsonArray {
                add(JsonPrimitive(observer))
                add(JsonPrimitive(target))
            },
        ),
    )

    /** `condenser_api.get_follow_count`. */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun followCount(account: String): FollowCount {
        val o = client.call("condenser_api.get_follow_count", buildJsonArray { add(JsonPrimitive(account)) }).obj()
            ?: throw NodeFailureException(client.currentNode, "malformed follow count")
        return FollowCount(o.int("follower_count") ?: 0, o.int("following_count") ?: 0)
    }

    /**
     * One page of `condenser_api.get_followers` (who follows [account]) or `get_following` (whom it follows),
     * "blog" follows only, as the SDK's friends list: account names in the node's order. The node starts AT
     * [start] (inclusive); null starts at the beginning.
     */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun follows(account: String, following: Boolean, start: String?, limit: Int = 100): List<String> {
        val result = client.call(
            if (following) "condenser_api.get_following" else "condenser_api.get_followers",
            buildJsonArray {
                add(JsonPrimitive(account))
                add(start?.takeIf { it.isNotEmpty() }?.let(::JsonPrimitive) ?: JsonNull)
                add(JsonPrimitive("blog"))
                add(JsonPrimitive(limit))
            },
        )
        return result.arr().orEmpty().mapNotNull { row -> row.obj()?.str(if (following) "following" else "follower") }
    }

    /** `rc_api.find_rc_accounts` for one account; null when the node has none. */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun rcAccount(account: String): RcAccount? {
        val result = client.call(
            "rc_api.find_rc_accounts",
            buildJsonObject { put("accounts", buildJsonArray { add(JsonPrimitive(account)) }) },
        ).obj()
        return result?.get("rc_accounts").arr()?.firstOrNull()?.let(RcAccount::fromJson)
    }

    /**
     * `condenser_api.get_content`: what the chain itself holds (no hivemind lag); null when there is no such post.
     * The one "is this permlink on chain, and with which body" read behind every publish retry: the editor's
     * pending publish, the reply composer and a wave all ask here before anything is sent again.
     */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun content(author: String, permlink: String): ChainContent? {
        val result = client.call(
            "condenser_api.get_content",
            buildJsonArray {
                add(JsonPrimitive(author))
                add(JsonPrimitive(permlink))
            },
        ).obj()
        // condenser answers a missing post with an empty-author object rather than an error
        if (result?.str("author") != author || result.str("permlink") != permlink) return null
        // json_metadata arrives as text from condenser (as an object from some nodes)
        val meta = result["json_metadata"].let { m -> (m as? JsonPrimitive)?.takeIf { it.isString }?.let { com.ecency.core.api.json.SafeJson.parseOrNull(it.content) } ?: m }.obj()
        val tags = (meta?.get("tags") as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        return ChainContent(author, permlink, result.str("title").orEmpty(), result.str("body").orEmpty(), result.str("parent_author").orEmpty(), result.str("parent_permlink").orEmpty(), tags)
    }

    /**
     * `transaction_status_api.find_transaction` (as hive-tx checkStatus): where [txId] is, e.g. "within_mempool",
     * "within_irreversible_block", "expired_reversible", "unknown", "too_old". Null when the answer has no status.
     * With the expiration given, a node that never saw the transaction answers "expired_*" once it is past.
     */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun transactionStatus(txId: String, expiration: String): String? =
        (client.call(
            "transaction_status_api.find_transaction",
            buildJsonObject {
                put("transaction_id", txId)
                put("expiration", expiration)
            },
        ) as? JsonObject)?.str("status")

    @Throws(EcencyException::class, CancellationException::class)
    suspend fun contentExists(author: String, permlink: String): Boolean = content(author, permlink) != null

    @Throws(EcencyException::class, CancellationException::class)
    suspend fun chainHead(): ChainHead {
        val props = client.call("condenser_api.get_dynamic_global_properties").obj()
        val number = props?.long("head_block_number")
        val id = props?.str("head_block_id")?.takeIf { Regex("^[0-9a-f]{40}$").matches(it) }
        if (number == null || id == null) {
            throw NodeFailureException(client.currentNode, "malformed dynamic global properties")
        }
        return ChainHead(number, id, props.str("time") ?: "")
    }

    /** `previous` of a block header: the id of the block before [blockNumber]. */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun previousBlockId(blockNumber: Long): String? =
        client.call("condenser_api.get_block_header", buildJsonArray { add(JsonPrimitive(blockNumber)) })
            .obj()?.str("previous")

    private fun posts(result: JsonElement): List<Post> = when (result) {
        is JsonArray -> result.mapNotNull(Post::fromJson)
        else -> emptyList() // null or a non-array: the SDK reads that as "no results"
    }

    private inline fun <T> notFoundAsNull(block: () -> T?): T? = try {
        block()
    } catch (e: RpcErrorException) {
        if (isNotFound(e)) null else throw e
    }

    companion object {
        internal fun isNotFound(e: RpcErrorException): Boolean =
            Regex("does not exist|not found|unknown account", RegexOption.IGNORE_CASE).containsMatchIn(e.rpcMessage)
    }
}

/** A post or comment as `condenser_api.get_content` returns it. */
data class ChainContent(
    val author: String, val permlink: String, val title: String, val body: String, val parentAuthor: String, val parentPermlink: String,
    /** `json_metadata.tags`, empty when the metadata has none or cannot be read. */
    val tags: List<String> = emptyList(),
)

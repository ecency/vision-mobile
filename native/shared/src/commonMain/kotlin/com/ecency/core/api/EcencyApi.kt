package com.ecency.core.api

import com.ecency.core.api.broadcast.HiveBroadcaster
import com.ecency.core.api.repository.AccountRepository
import com.ecency.core.api.repository.FeedRepository
import com.ecency.core.api.repository.PostRepository
import com.ecency.core.api.rpc.HiveNodes
import com.ecency.core.api.rpc.HiveRpcClient
import com.ecency.core.api.rpc.KtorTransport
import com.ecency.core.api.rpc.RpcTransport
import com.ecency.core.hive.PrivateKey

/**
 * The data layer in one object, for the app to create once and share.
 *
 * ```
 * val ecency = EcencyApi()
 * val page = ecency.feeds.page(FeedSource.Ranked(RankedSort.TRENDING))
 * val discussion = ecency.posts.getDiscussion("alice", "hello-world")
 * ecency.broadcaster.vote("bob", "alice", "hello-world", 10000, postingKey)
 * ```
 *
 * Every public suspend function of the data layer is `@Throws(EcencyException, CancellationException)`:
 * from Swift, failures are thrown errors whose `KotlinException` is an [EcencyException].
 */
class EcencyApi @Throws(EcencyException::class) constructor(
    nodes: List<String>,
    transport: RpcTransport,
) {
    /** The app's default node pool over the platform HTTP client. */
    @Throws(EcencyException::class)
    constructor() : this(HiveNodes.DEFAULT, KtorTransport())

    /** A custom node pool (e.g. a user-chosen node first) over the platform HTTP client. */
    @Throws(EcencyException::class)
    constructor(nodes: List<String>) : this(nodes, KtorTransport())

    val client = HiveRpcClient(nodes, transport)
    val hive = HiveApi(client)
    val posts = PostRepository(hive)
    val feeds = FeedRepository(hive, posts)
    val accounts = AccountRepository(hive)
    val broadcaster = HiveBroadcaster(hive)

    companion object {
        /** Parses a WIF private key, throwing [InvalidInputException] (never crashing Swift) when malformed. */
        @Throws(EcencyException::class)
        fun privateKeyFromWif(wif: String): PrivateKey = guarded {
            try {
                PrivateKey.fromWif(wif.trim())
            } catch (e: Exception) {
                throw InvalidInputException("invalid private key", e)
            }
        }
    }
}

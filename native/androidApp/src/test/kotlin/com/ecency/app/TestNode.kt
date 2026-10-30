package com.ecency.app

import com.ecency.core.api.EcencyApi
import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.api.rpc.RpcTransport
import com.ecency.core.app.AppDispatchers
import com.ecency.core.app.AppEnvironment
import com.ecency.core.app.InterimPostBodyRenderer
import com.ecency.core.hive.PrivateKey
import com.ecency.core.secure.AuthType
import com.ecency.core.secure.KeyVault
import com.ecency.core.secure.KeyVaultAsync
import com.ecency.core.secure.SecureStore
import com.ecency.core.secure.VaultAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Collections

/** In-memory secure store for UI tests. */
class TestStore : SecureStore {
    val map = Collections.synchronizedMap(linkedMapOf<String, ByteArray>())

    /** Every read fails (an unreadable Keystore), so the app lock cannot decide and keeps content hidden. */
    @Volatile var failReads = false

    /** Holds every read until counted down (accounts still loading at a cold start). */
    @Volatile var readGate: java.util.concurrent.CountDownLatch? = null
    override fun put(name: String, value: ByteArray) { map[name] = value.copyOf() }
    override fun get(name: String): ByteArray? {
        if (failReads) throw IllegalStateException("store unreadable (test)")
        readGate?.await(20, java.util.concurrent.TimeUnit.SECONDS)
        return map[name]?.copyOf()
    }
    override fun delete(name: String) { map.remove(name) }
    override fun names(): Set<String> = map.keys.toSet()
    override fun wipe() = map.clear()
    override fun <T> withLock(block: () -> T): T = synchronized(this) { block() }
}

/**
 * A scripted Hive node for UI tests (no network, nothing broadcast anywhere): two ranked posts per
 * sort, one post with a comment, a "tester" account whose posting key is [key], broadcasts that
 * succeed unless [broadcastDown], and an optional [broadcastGate] that holds every broadcast.
 */
class TestNode {
    val key: PrivateKey = PrivateKey.fromSeed("ecency-native-spike-ui-test-throwaway")
    val sent: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())
    val store = TestStore()
    @Volatile var feedDown = false

    /** Accounts `condenser_api.lookup_accounts` knows. */
    @Volatile var existingAccounts = listOf("spike-bob", "tester")

    /** What `bridge.get_account_posts` answers (the waves container lookup). */
    @Volatile var accountPosts = "[]"
    @Volatile var broadcastDown = false
    @Volatile var broadcastGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    /** Answers a method before the fixtures below (wallet reads); null falls through. */
    @Volatile var extra: ((String, kotlinx.serialization.json.JsonObject) -> String?)? = null

    /** Extra markdown appended to the post body (e.g. images for the gallery). */
    @Volatile var postBodyExtra = ""

    /** Extra markdown appended to the reply's body (carol/re-one). */
    @Volatile var commentBodyExtra = ""

    /** More `json_metadata` fields for every entry (`"app":"ecency/4"` ...), comma first. */
    @Volatile var metadataExtra = ""

    /** A `json_metadata.image` for every entry (the card image; `{permlink}` is replaced), none by default. */
    @Volatile var metadataImage: String? = null

    /** The second ranked post comes back muted by its community (stats.gray). */
    @Volatile var secondMuted = false

    fun entry(author: String, permlink: String, parent: Pair<String, String>? = null, votes: List<String> = emptyList(), children: Int = 0, extra: String = "", gray: Boolean = false) = """{
        "author":"$author","permlink":"$permlink",
        ${parent?.let { """"parent_author":"${it.first}","parent_permlink":"${it.second}",""" } ?: ""}
        "category":"hive-100001","title":"Title $permlink","body":"Body of $permlink with **bold** text$extra",
        "json_metadata":{"tags":["test"],"description":"Summary of $permlink"${metadataImage?.let { ""","image":["${it.replace("{permlink}", permlink)}"]""" } ?: ""}$metadataExtra},
        "created":"2026-10-06T10:00:00","updated":"2026-10-06T10:00:00","depth":${if (parent == null) 0 else 1},"children":$children,
        "net_rshares":1000,"author_reputation":60.0,"payout":1.5,"is_paidout":false,"payout_at":"2026-10-13T10:00:00",
        "pending_payout_value":"1.500 HBD","author_payout_value":"0.000 HBD","curator_payout_value":"0.000 HBD",
        "promoted":"0.000 HBD","max_accepted_payout":"1000000.000 HBD","percent_hbd":10000,"replies":[],
        "active_votes":[${votes.joinToString(",") { """{"voter":"$it","rshares":100}""" }}],
        "community":"hive-100001","community_title":"Test Community",
        "stats":{"hide":false,"gray":$gray,"total_votes":${votes.size},"flag_weight":0.0},"reblogs":0,"beneficiaries":[],"blacklists":[],
        "url":"/hive-100001/@$author/$permlink"}"""

    private fun root() = entry("alice", "trending-one", votes = listOf("x"), children = 1, extra = postBodyExtra)

    val transport = RpcTransport { _, body ->
        sent += body
        val req = Json.parseToJsonElement(body).jsonObject
        val method = req["method"]!!.jsonPrimitive.content
        val id = req["id"]!!.jsonPrimitive.content
        fun ok(result: String) = HttpResult(200, """{"jsonrpc":"2.0","result":$result,"id":$id}""")
        extra?.invoke(method, req)?.let { return@RpcTransport ok(it) }
        when (method) {
            "bridge.get_ranked_posts" -> {
                if (feedDown) return@RpcTransport HttpResult(503, "down")
                val sort = req["params"]!!.jsonObject["sort"]!!.jsonPrimitive.content
                if (req["params"]!!.jsonObject["start_author"] != null) ok("[]")
                else ok("[${entry("alice", "$sort-one", votes = listOf("x"), children = 1, extra = if (sort == "trending") postBodyExtra else "")},${entry("bob", "$sort-two", gray = secondMuted)}]")
            }
            "bridge.get_account_posts" -> ok(accountPosts)
            // the register screen's availability lookup: names from the prefix on, as the chain answers
            "condenser_api.lookup_accounts" -> ok(existingAccounts.filter { it >= (req["params"] as kotlinx.serialization.json.JsonArray)[0].jsonPrimitive.content }.joinToString(",", "[", "]") { "\"$it\"" })
            "bridge.get_post" -> ok(root())
            "bridge.get_discussion" -> ok(
                """{"alice/trending-one":${root()},""" +
                    """"carol/re-one":${entry("carol", "re-one", parent = "alice" to "trending-one", extra = commentBodyExtra)}}""",
            )
            "condenser_api.get_accounts" -> ok(
                """[{"name":"tester","posting":{"weight_threshold":1,"account_auths":[],"key_auths":[["${key.publicKey()}",1]]},"memo_key":"${key.publicKey()}"}]""",
            )
            "condenser_api.get_dynamic_global_properties" -> ok(
                """{"head_block_number":100003,"head_block_id":"000186a3${"11".repeat(16)}","time":"2026-10-06T11:59:00"}""",
            )
            "condenser_api.get_block_header" -> ok("""{"previous":"000186a0${"22".repeat(16)}"}""")
            "condenser_api.get_content" -> ok("""{"author":"","permlink":""}""")
            // feed, posts and reading
            "condenser_api.get_reblogged_by" -> ok("""["alice","carol"]""")
            "condenser_api.get_active_votes" -> ok(
                """[{"voter":"x","rshares":"1000","percent":10000,"time":"2026-10-06T10:30:00"},{"voter":"y","rshares":-200,"percent":-5000,"time":"2026-10-06T11:00:00"}]""",
            )
            "bridge.get_community" -> ok("""{"name":"hive-100001","title":"Test Community","team":[["tester","mod",""]]}""")
            "condenser_api.get_feed_history" -> ok("""{"current_median_history":{"base":"0.250 HBD","quote":"1.000 HIVE"}}""")
            "condenser_api.get_reward_fund" -> ok("""{"recent_claims":"600000000000000000","reward_balance":"800000.000 HIVE"}""")
            "database_api.get_hardfork_properties" -> ok("""{"last_hardfork":28}""")
            "bridge.list_all_subscriptions" -> ok("""[["hive-100001","Test Community","member",""],["hive-100002","Another","guest",""]]""")
            "condenser_api.broadcast_transaction" -> {
                broadcastGate?.await()
                if (broadcastDown) HttpResult(503, "") else ok("null")
            }
            else -> HttpResult(500, "no fixture")
        }
    }

    val auth = TestAuth()

    fun env() = AppEnvironment(
        api = EcencyApi(listOf("https://node-a.example"), transport),
        vault = KeyVaultAsync(KeyVault(store), Dispatchers.IO),
        renderer = InterimPostBodyRenderer("https://i.ecency.com"),
        dispatchers = AppDispatchers(Dispatchers.Main, Dispatchers.Default, Dispatchers.IO),
        proxyBase = "https://i.ecency.com",
        debugKeyImport = true,
        nowMillis = { 1_791_288_000_000L },
        auth = auth.services,
    ).also {
        // signed-in readers get RN's feed tabs (Following first); these flows read the trending feed, so it leads
        it.prefs.putString(com.ecency.core.app.FeedTabs.PREFS_KEY, "trending,hot,created,friends,communities")
        // this version's welcome screen was already passed (tests of the welcome screen clear it again)
        it.welcome.accept(consent = true)
    }

    fun signIn() {
        val vault = KeyVault(store)
        vault.save(VaultAccount("tester", AuthType.POSTING_KEY, postingKey = key.toWif()))
        vault.setCurrentUsername("tester")
    }

    fun broadcasts() = sent.filter { "broadcast_transaction" in it }
}

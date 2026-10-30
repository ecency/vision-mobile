package com.ecency.core.app

import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.api.rpc.RpcTransport
import com.ecency.core.hive.KeyRole
import com.ecency.core.hive.PrivateKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Debug demo runs only (Android `--ez demoAuth true`; iOS has its own `FakeHiveTransport`): a Hive node that is
 * not there. It answers the read calls the screens make from synthetic fixtures (made-up accounts and text, no
 * real content), so signed-in screens render offline and the seeded demo accounts, which do not exist on chain,
 * never reach a real node as an observer. Every other call, broadcasts included, is refused: nothing a demo run
 * does can reach a chain.
 */
class DemoHiveTransport(private val nowSeconds: () -> Long = { 0L }) : RpcTransport {
    override suspend fun post(url: String, body: String): HttpResult {
        // the wallet's reads (and, for the "check your wallet" demo, a broadcast held open)
        val request = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
        val method = (request?.get("method") as? JsonPrimitive)?.content
        if (request != null && method != null) {
            DemoWallet.rpc(method, request["params"])?.let { result ->
                return HttpResult(200, buildJsonObject { put("jsonrpc", "2.0"); put("result", result); put("id", request["id"] ?: JsonPrimitive(0)) }.toString())
            }
        }
        return answer(body)
    }

    fun answer(requestBody: String): HttpResult {
        val request = runCatching { Json.parseToJsonElement(requestBody) as? JsonObject }.getOrNull() ?: return HttpResult(400, "bad request")
        val method = (request["method"] as? JsonPrimitive)?.content ?: return HttpResult(400, "bad request")
        val id = request["id"] ?: JsonPrimitive(0)
        val named = request["params"] as? JsonObject
        val listed = request["params"] as? JsonArray
        fun text(key: String): String? = (named?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
        fun firstPage() = text("start_author").isNullOrEmpty()
        // broadcasts: refused as the chain refuses one (a JSON-RPC error), never like an unreachable node, which
        // could mean the transaction landed and would leave the send undecided
        if (method.endsWith(".broadcast_transaction") || method.endsWith(".broadcast_transaction_synchronous")) {
            return HttpResult(200, buildJsonObject {
                put("jsonrpc", "2.0")
                put("error", buildJsonObject { put("code", -32000); put("message", "demo transport: broadcasts are not available offline") })
                put("id", id)
            }.toString())
        }
        val result: JsonElement = when (method) {
            "bridge.get_ranked_posts" -> if (firstPage()) feed(null) else JsonArray(emptyList())
            "bridge.get_account_posts" -> if (firstPage()) feed(text("account") ?: AUTHORS[0]) else JsonArray(emptyList())
            "bridge.get_post" -> {
                val author = text("author").orEmpty()
                val permlink = text("permlink").orEmpty()
                discussion(author, permlink)["$author/$permlink"] ?: entry(author, permlink)
            }
            "bridge.get_discussion" -> JsonObject(discussion(text("author").orEmpty(), text("permlink").orEmpty()))
            "condenser_api.get_accounts" -> JsonArray(
                (listed?.firstOrNull() as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }.filter { it in AUTHORS }.map(::account),
            )
            "bridge.get_profile" -> text("account")?.takeIf { it in AUTHORS }?.let(::profile) ?: JsonNull
            "bridge.get_profiles" -> JsonArray((named?.get("accounts") as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }.filter { it in AUTHORS }.map(::profile))
            "condenser_api.get_follow_count" -> buildJsonObject { put("account", AUTHORS[0]); put("follower_count", 1234); put("following_count", 56) }
            "bridge.get_relationship_between_accounts" -> buildJsonObject {
                listOf("follows", "ignores", "blacklists", "follows_muted", "follows_blacklists").forEach { put(it, false) }
            }
            "rc_api.find_rc_accounts" -> buildJsonObject {
                putJsonArray("rc_accounts") {
                    add(buildJsonObject {
                        put("account", AUTHORS[0]); put("max_rc", "1000")
                        putJsonObject("rc_manabar") { put("current_mana", "640"); put("last_update_time", nowSeconds()) }
                    })
                }
            }
            "condenser_api.get_followers", "condenser_api.get_following" -> {
                val target = (listed?.getOrNull(0) as? JsonPrimitive)?.content ?: AUTHORS[0]
                val start = listed?.getOrNull(1)
                val following = method.endsWith("following")
                // the ignore list is empty; the follow lists are everyone else, one page
                val what = (listed?.getOrNull(2) as? JsonPrimitive)?.content
                if (what == "ignore" || (start != null && start !is JsonNull && (start as? JsonPrimitive)?.content?.isNotEmpty() == true)) JsonArray(emptyList())
                else JsonArray(AUTHORS.filter { it != target }.map { other ->
                    buildJsonObject {
                        put("follower", if (following) target else other); put("following", if (following) other else target)
                        putJsonArray("what") { add("blog") }
                    }
                })
            }
            "bridge.get_community" -> community(text("name") ?: COMMUNITY, "Spike Community")
            "bridge.list_communities" -> buildJsonArray { add(community(COMMUNITY, "Spike Community")); add(community("hive-100002", "Spike Photos")) }
            "bridge.list_all_subscriptions" -> buildJsonArray { add(buildJsonArray { add(COMMUNITY); add("Spike Community"); add("admin"); add("") }) }
            "bridge.list_subscribers" -> if (named?.get("last") == null) JsonArray(AUTHORS.map { user ->
                buildJsonArray { add(user); add(if (user == "spike-bob") "member" else "guest"); add(JsonNull); add("2026-01-01T00:00:00") }
            }) else JsonArray(emptyList())
            "bridge.account_notifications" -> if (named?.get("last_id") == null) JsonArray((1..3).map { i ->
                buildJsonObject {
                    put("id", "${100 - i}"); put("type", "subscribe"); put("msg", "@${AUTHORS[i]} subscribed to Spike Community")
                    put("date", "2026-10-01T10:0$i:00"); put("score", 50); put("url", "c/$COMMUNITY")
                }
            }) else JsonArray(emptyList())
            "condenser_api.lookup_accounts" -> {
                val prefix = (listed?.firstOrNull() as? JsonPrimitive)?.content.orEmpty()
                JsonArray(AUTHORS.filter { it >= prefix }.map(::JsonPrimitive))
            }
            "condenser_api.get_active_votes" -> JsonArray((0 until 8).map { i ->
                buildJsonObject {
                    put("voter", AUTHORS[i % AUTHORS.size] + if (i >= AUTHORS.size) "-$i" else "")
                    put("rshares", if (i == 7) -200 else (8 - i) * 1000); put("percent", if (i == 7) -10000 else 10000 - i * 1000)
                    put("time", "2026-10-0${1 + i % 5}T12:00:00")
                }
            })
            "condenser_api.get_reblogged_by" -> buildJsonArray { add("spike-bob"); add("spike-carol"); add("spike-dave") }
            // the proposal card (ProposalVoteModel): one votable @ecency proposal nobody voted for
            "database_api.list_proposals" -> buildJsonObject {
                put("proposals", buildJsonArray { add(buildJsonObject { put("id", 1); put("proposal_id", 342); put("creator", "ecency"); put("status", "active") }) })
            }
            "database_api.list_proposal_votes" -> buildJsonObject { put("proposal_votes", JsonArray(emptyList())) }
            // other chain state: refused, like an unreachable node
            else -> return HttpResult(503, "demo transport: $method is not available offline")
        }
        return HttpResult(200, buildJsonObject { put("jsonrpc", "2.0"); put("result", result); put("id", id) }.toString())
    }

    private fun community(name: String, title: String) = buildJsonObject {
        put("id", 1); put("name", name); put("title", title); put("about", "Synthetic community for the native app's demo runs."); put("lang", "en")
        put("description", "A made-up community: nothing here is real content."); put("flag_text", "Be kind."); put("is_nsfw", false); put("type_id", 1)
        putJsonArray("team") {
            add(buildJsonArray { add(name); add("owner"); add("") })
            add(buildJsonArray { add("spike-alice"); add("admin"); add("") })
            add(buildJsonArray { add("spike-carol"); add("mod"); add("Helper") })
        }
        putJsonObject("context") { put("role", "admin"); put("subscribed", true); put("title", "") }
        put("subscribers", 1200); put("num_authors", 40); put("num_pending", 280); put("created_at", "2020-01-01 00:00:00")
    }

    private fun key(user: String, role: KeyRole) = PrivateKey.fromLogin(user, PASSWORD, role).publicKey().toString()

    private fun account(user: String) = buildJsonObject {
        fun auth(role: KeyRole) = buildJsonObject {
            put("weight_threshold", 1); putJsonArray("account_auths") {}
            putJsonArray("key_auths") { add(buildJsonArray { add(key(user, role)); add(1) }) }
        }
        put("name", user); put("owner", auth(KeyRole.OWNER)); put("active", auth(KeyRole.ACTIVE)); put("posting", auth(KeyRole.POSTING)); put("memo_key", key(user, KeyRole.MEMO))
        put("json_metadata", "")
        put("posting_json_metadata", buildJsonObject {
            putJsonObject("profile") {
                put("name", user.replace("spike-", "Spike ").split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } })
                put("about", "Synthetic test account for the native app."); put("location", "Testnet"); put("website", "example.com"); put("version", 2)
                put("tokens", DemoWallet.PROFILE_TOKENS)
            }
        }.toString())
        put("reputation", 0); put("post_count", 6); put("created", "2021-03-14T09:00:00")
        // the same stake as the iOS fixture, so the voting power (873000000 mana of 1000 VESTS) reads 87.3 on both
        put("vesting_shares", "1000.000000 VESTS"); put("delegated_vesting_shares", "0.000000 VESTS"); put("received_vesting_shares", "0.000000 VESTS")
        put("vesting_withdraw_rate", "0.000000 VESTS"); put("to_withdraw", 0); put("withdrawn", 0)
        DemoWallet.accountFields().forEach { (k, v) -> put(k, v) }
        putJsonObject("voting_manabar") { put("current_mana", "873000000"); put("last_update_time", nowSeconds()) }
    }

    private fun profile(user: String) = buildJsonObject {
        put("name", user); put("created", "2021-03-14T09:00:00"); put("active", "2026-10-01T10:00:00"); put("post_count", 6); put("reputation", 64.5)
        putJsonObject("stats") { put("followers", 1234); put("following", 56) }
        putJsonObject("metadata") {
            putJsonObject("profile") {
                put("name", user.replace("spike-", "Spike ").split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } })
                put("about", "Synthetic test account for the native app."); put("location", "Testnet"); put("website", "http://example.com")
            }
        }
    }

    private fun feed(author: String?) = JsonArray((1..6).map { i ->
        entry(author ?: AUTHORS[(i - 1) % AUTHORS.size], "fake-post-$i", title = "Fixture post number $i", children = 4, votes = i * 3)
    })

    /** Root plus comments: two top-level threads, one nested past level 3 (shows "more replies"). */
    private fun discussion(rootAuthor: String, rootPermlink: String): Map<String, JsonObject> {
        val root = rootAuthor to rootPermlink
        val all = mutableListOf(
            entry(rootAuthor, rootPermlink, title = "Fixture post", children = 5, votes = 9, replies = listOf("spike-bob/re-c1", "spike-carol/re-c2")),
            entry("spike-bob", "re-c1", parent = root, depth = 1, body = "First fixture comment.", replies = listOf("spike-dave/re-r1")),
            entry("spike-dave", "re-r1", parent = "spike-bob" to "re-c1", depth = 2, body = "A reply at level two.", replies = listOf("spike-erin/re-r2")),
            entry("spike-erin", "re-r2", parent = "spike-dave" to "re-r1", depth = 3, body = "A reply at level three.", replies = listOf("spike-frank/re-r3")),
            entry("spike-frank", "re-r3", parent = "spike-erin" to "re-r2", depth = 4, body = "Hidden below level three."),
            entry("spike-carol", "re-c2", parent = root, depth = 1, body = "Second fixture comment.", votes = 2),
        )
        if (rootAuthor == "spike-bob" && rootPermlink == "re-c1") all.removeAt(0)
        return all.associateBy { "${(it["author"] as JsonPrimitive).content}/${(it["permlink"] as JsonPrimitive).content}" }
    }

    private fun entry(
        author: String, permlink: String, title: String = "", parent: Pair<String, String>? = null, depth: Int = 0,
        body: String = BODY, children: Int? = null, votes: Int = 0, replies: List<String> = emptyList(),
    ) = buildJsonObject {
        put("author", author); put("permlink", permlink); put("category", COMMUNITY); put("title", title); put("body", body)
        putJsonObject("json_metadata") {
            putJsonArray("tags") { add(COMMUNITY); add("spike") }
            put("app", "ecency/4.0.0-mobile"); put("format", "markdown+html")
        }
        put("created", "2026-10-01T10:00:00"); put("updated", "2026-10-01T10:00:00"); put("depth", depth)
        put("children", children ?: replies.size); put("net_rshares", 1000); put("author_reputation", 60.0); put("payout", 1.234)
        put("is_paidout", false); put("payout_at", "2026-10-08T10:00:00"); put("pending_payout_value", "1.234 HBD")
        put("author_payout_value", "0.000 HBD"); put("curator_payout_value", "0.000 HBD"); put("promoted", "0.000 HBD")
        put("max_accepted_payout", "1000000.000 HBD"); put("percent_hbd", 10000)
        putJsonArray("replies") { replies.forEach { add(it) } }
        putJsonArray("active_votes") { repeat(votes) { add(buildJsonObject { put("voter", "spike-voter$it"); put("rshares", 100) }) } }
        put("community", COMMUNITY); put("community_title", "Spike Community"); put("author_role", "guest"); put("author_title", "")
        putJsonObject("stats") { put("hide", false); put("gray", false); put("total_votes", votes); put("flag_weight", 0.0) }
        put("reblogs", 0); putJsonArray("beneficiaries") {}; putJsonArray("blacklists") {}; put("url", "/$COMMUNITY/@$author/$permlink")
        if (parent != null) {
            put("parent_author", parent.first); put("parent_permlink", parent.second)
        }
    }

    companion object {
        val AUTHORS = listOf("spike-alice", "spike-bob", "spike-carol", "spike-dave", "spike-erin", "spike-frank")
        const val COMMUNITY = "hive-100001"

        /** The throwaway master password every demo account's keys derive from (public, controls nothing). */
        const val PASSWORD = "P5UiTestThrowawayPasswordNotReal"

        /** Markdown for every demo post: a heading, text, a mention, an external link and emphasis. */
        const val BODY = "## Spike fixture\n\nThis post is synthetic test content for the native app. Hi @spike-bob, thanks for reading.\n\n" +
            "[Example link](https://example.com/spike)\n\nA second paragraph with **bold** and *italic* text, long enough to wrap over a few lines on a phone screen."
    }
}

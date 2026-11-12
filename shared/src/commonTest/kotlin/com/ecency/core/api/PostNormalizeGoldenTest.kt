package com.ecency.core.api

import com.ecency.core.api.model.ObserverVote
import com.ecency.core.api.model.Post
import com.ecency.core.api.rpc.HiveRpcClient
import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.api.rpc.RpcErrorException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The SDK's own answers (tools/golden/generate-bridge-v2.mjs, BridgeV2Goldens.kt) for the bridge v2 port: the
 * normalizer over v1 and v2 responses, isCastVote and voteDirection, the per-node fallback and error classifier, the
 * one-account vote lookup and the voters list.
 */
class PostNormalizeGoldenTest {
    private val golden = Json.parseToJsonElement(BridgeV2Goldens.JSON).jsonObject

    private fun JsonElement.str(key: String): String? = (jsonObject[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun voteJson(vote: ObserverVote?): JsonElement =
        if (vote == null) JsonNull else buildJsonObject {
            put("rshares", vote.rshares?.let(::JsonPrimitive) ?: JsonNull)
            put("percent", vote.percent?.let(::JsonPrimitive) ?: JsonNull)
        }

    /** A golden vote ({rshares, percent} with numbers that may be written as 1.0) as JSON the same way. */
    private fun canonical(vote: JsonElement?): JsonElement = when (vote) {
        null, JsonNull -> JsonNull
        is JsonPrimitive -> vote
        else -> voteJson(ObserverVote(vote.jsonObject["rshares"]?.jsonPrimitive?.longOrNull, vote.jsonObject["percent"]?.jsonPrimitive?.intOrNull))
    }

    private fun direction(d: Int): JsonElement = when (d) {
        1 -> JsonPrimitive("up")
        -1 -> JsonPrimitive("down")
        else -> JsonNull
    }

    /** `count` rows spike-v0000... with rshares 1000 + i; the observer's row replaces row `observerAt` (generator's expandRows). */
    private fun rows(spec: JsonObject, observer: String): JsonArray {
        val count = spec.getValue("count").jsonPrimitive.int
        val at = spec["observerAt"]?.jsonPrimitive?.intOrNull
        return JsonArray(
            (0 until count).map { i ->
                if (i == at) JsonObject(mapOf("voter" to JsonPrimitive(observer)) + spec.getValue("observerRow").jsonObject)
                else buildJsonObject {
                    put("voter", "spike-v" + i.toString().padStart(4, '0'))
                    put("rshares", 1000 + i)
                }
            },
        )
    }

    private fun rawOf(case: JsonObject, observer: String): JsonObject {
        case["raw"]?.let { return it.jsonObject }
        val spec = case.getValue("rows").jsonObject
        val base = golden.getValue("v1Base").jsonObject.toMutableMap()
        base["active_votes"] = rows(spec.getValue("rows").jsonObject, observer)
        if (spec["noStats"]?.jsonPrimitive?.booleanOrNull == true) base.remove("stats")
        else base["stats"] = JsonObject(base.getValue("stats").jsonObject + ("total_votes" to spec.getValue("totalVotes")))
        return JsonObject(base)
    }

    private fun voteOf(post: Post, user: String): JsonElement =
        if (!post.knowsVoteOf(user)) JsonPrimitive("unknown") else voteJson(post.voteOf(user))

    @Test
    fun normalizerMatchesTheSdkOnV1AndV2Responses() {
        val cases = golden.getValue("normalize").jsonArray
        assertTrue(cases.size >= 30)
        val mismatches = mutableListOf<String>()
        for (c in cases) {
            val case = c.jsonObject
            val name = case.str("name")!!
            val observer = case.str("observer")!!
            val complete = case.getValue("complete").jsonPrimitive.booleanOrNull == true
            val out = case.getValue("out").jsonObject
            val post = Post.fromJson(rawOf(case, observer), observer, completeVoteList = complete)!!
            val got = mapOf(
                "observer_vote" to voteJson(post.observerVote),
                "observer_vote_for" to (post.observerVoteFor?.let(::JsonPrimitive) ?: JsonNull),
                "total_votes" to JsonPrimitive(post.stats.totalVotes),
                "vote_of_observer" to voteOf(post, observer),
                "vote_of_other" to voteOf(post, "spike-nobody"),
                "direction" to direction(post.voteOf(observer)?.direction ?: 0),
            )
            val want = mapOf(
                "observer_vote" to canonical(out["observer_vote"]),
                "observer_vote_for" to (out["observer_vote_for"] ?: JsonNull),
                "total_votes" to JsonPrimitive(out.getValue("total_votes").jsonPrimitive.int),
                "vote_of_observer" to canonical(out["vote_of_observer"]),
                "vote_of_other" to canonical(out["vote_of_other"]),
                "direction" to (out["direction"] ?: JsonNull),
            )
            if (got != want) mismatches += "$name:\n  got  $got\n  want $want"
            // everything else passes through
            assertEquals("spike-author", post.author, name)
            assertEquals(359391106419416L, post.netRshares, name)
        }
        assertTrue(mismatches.isEmpty(), mismatches.joinToString("\n"))
    }

    @Test
    fun isCastVoteAndDirectionMatchTheSdk() {
        for (c in golden.getValue("casts").jsonArray) {
            val case = c.jsonObject
            val vote = case["vote"]?.takeIf { it != JsonNull }?.jsonObject
                ?.let { ObserverVote(it["rshares"]?.jsonPrimitive?.longOrNull, it["percent"]?.jsonPrimitive?.intOrNull) }
            assertEquals(case.getValue("cast").jsonPrimitive.booleanOrNull, vote?.isCast ?: false, "$vote")
            assertEquals(case["direction"] ?: JsonNull, direction(vote?.direction ?: 0), "$vote")
        }
    }

    @Test
    fun fallbackAndErrorClassifierMatchTheSdk() = runTest {
        val a = "https://node-a.example"
        val b = "https://node-b.example"
        for (s in golden.getValue("fallback").jsonArray) {
            val scenario = s.jsonObject
            val name = scenario.str("name")!!
            val answer = scenario.getValue("answer").jsonObject
            val transport = FakeTransport { req ->
                if (req.node == a && req.method.endsWith("_v2")) {
                    HttpResult(200, buildJsonObject { put("jsonrpc", "2.0"); put("id", req.id); put("error", answer.getValue("error")) }.toString())
                } else {
                    FakeTransport.result(req, buildJsonObject { put("answeredBy", req.method) }.toString())
                }
            }
            val client = HiveRpcClient(listOf(a, b), transport, readTimeoutMillis = 1_000, nowMillis = { 0L }, jitter = {})
            for (r in scenario.getValue("reads").jsonArray) {
                val read = r.jsonObject
                val endpoint = read.str("endpoint")!!
                val before = transport.sent.size
                val params = buildJsonObject { put("author", "spike-author"); put("permlink", "spike-post"); put("observer", "spike-me") }
                val outcome = try {
                    "answered " + client.callWithFallback("bridge.${endpoint}_v2", params, "bridge.$endpoint").method
                } catch (e: RpcErrorException) {
                    "threw ${e.code}"
                }
                val calls = transport.sent.drop(before).map { listOf(if (it.node == a) "A" else "B", it.method) }
                val want = read.getValue("calls").jsonArray.map { call -> call.jsonArray.map { it.jsonPrimitive.content } }
                assertEquals(want, calls, "$name: $endpoint calls")
                assertEquals(read.str("outcome"), outcome, "$name: $endpoint")
            }
        }
    }

    @Test
    fun ownVoteLookupMatchesTheSdk() = runTest {
        for (l in golden.getValue("lookups").jsonArray) {
            val lookup = l.jsonObject
            val name = lookup.str("name")!!
            val transport = FakeTransport { req ->
                FakeTransport.result(req, buildJsonObject { put("votes", lookup.getValue("votes")) }.toString())
            }
            val vote = HiveApi(clientWith(transport)).ownVote("spike-author", "spike-post", "spike-me")
            val request = lookup.getValue("request").jsonObject
            assertEquals(listOf(request.str("method") to request.getValue("params")), transport.sent.map { it.method to it.params }, name)
            assertEquals(canonical(lookup["vote"]), voteJson(vote), name)
        }
    }

    @Test
    fun votersListMatchesTheSdk() = runTest {
        for (v in golden.getValue("voters").jsonArray) {
            val case = v.jsonObject
            val name = case.str("name")!!
            val spec = case.getValue("spec").jsonObject
            val count = spec.getValue("count").jsonPrimitive.int
            val next = spec["next"]?.jsonPrimitive?.intOrNull ?: 0
            val mode = spec.str("listVotes")
            fun voter(i: Int) = "spike-v" + i.toString().padStart(4, '0')
            val list = (0 until count).map { i ->
                buildJsonObject { put("voter", voter(i)); put("rshares", 10 + i); put("percent", 10000); put("weight", 5 + i); put("time", "2026-11-07T06:45:09") }
            }
            val db = (0 until count + next).map { i ->
                buildJsonObject {
                    // the next post's rows follow in the index, with voters this post does not have
                    put("voter", voter(i)); put("author", "spike-author")
                    put("permlink", if (i < count) "spike-post" else "spike-post-2"); put("rshares", 10 + i)
                    put("vote_percent", 10000); put("weight", 5 + i); put("last_update", "2026-11-07T06:45:09")
                }
            }
            val transport = FakeTransport { req ->
                val p = req.params as? JsonObject ?: return@FakeTransport HttpResult(500, "not this script's request")
                val limit = p.getValue("limit").jsonPrimitive.int
                if (req.method == "bridge.list_votes") {
                    if (mode == "unknown") return@FakeTransport FakeTransport.error(req, -32602, "Assert Exception:unknown method: bridge_apilist_votes")
                    val start = p["start"]?.jsonPrimitive?.content
                    val from = if (mode == "repeat" || start == null) 0 else list.indexOfFirst { it.str("voter")!! > start }
                    FakeTransport.result(req, JsonArray(if (from < 0) emptyList() else list.drop(from).take(limit)).toString())
                } else {
                    val start = p.getValue("start").jsonArray[2].jsonPrimitive.content
                    val from = if (start.isEmpty()) 0 else db.indexOfFirst { it.str("voter")!! >= start }
                    FakeTransport.result(req, buildJsonObject { put("votes", JsonArray(if (from < 0) emptyList() else db.drop(from).take(limit))) }.toString())
                }
            }
            val votes = HiveApi(clientWith(transport)).entryVotes("spike-author", "spike-post")
            val want = case.getValue("requests").jsonArray.map { it.jsonObject.str("method") to it.jsonObject.getValue("params") }
            assertEquals(want, transport.sent.map { it.method to it.params }, name)
            assertEquals(case.getValue("count").jsonPrimitive.int, votes.size, name)
            for ((key, got) in listOf("first" to votes.firstOrNull(), "last" to votes.lastOrNull())) {
                val w = case.getValue(key).jsonObject
                assertEquals(w.str("voter"), got?.voter, "$name $key")
                assertEquals(w.getValue("rshares").jsonPrimitive.longOrNull, got?.rshares, "$name $key")
                assertEquals(w.getValue("percent").jsonPrimitive.intOrNull, got?.percent, "$name $key")
                assertEquals(w.str("time"), got?.time, "$name $key")
            }
        }
    }
}

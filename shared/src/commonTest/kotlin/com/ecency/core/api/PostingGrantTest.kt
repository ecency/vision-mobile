package com.ecency.core.api

import com.ecency.core.api.broadcast.OperationJson
import com.ecency.core.api.broadcast.PostingGrant
import com.ecency.core.api.broadcast.PostingGrantRefused
import com.ecency.core.auth.OperationAuthority
import com.ecency.core.hive.GoldenVectors
import com.ecency.core.hive.KeyRole
import com.ecency.core.hive.PrivateKey
import com.ecency.core.hive.Transaction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The posting authority grant against the SDK's builder and hive-tx (tools/golden/generate-grant.mjs). Never broadcast. */
class PostingGrantTest {
    private val key = PrivateKey.fromWif(GoldenVectors.WIF)
    private val v = GrantGoldenVectors.grants

    private fun account(i: Int = 0) = Json.parseToJsonElement(v[i].accountJson).jsonObject

    @Test
    fun theGrantIsByteIdenticalToTheSdkAndSignsTheSame() {
        assertEquals(5, v.size)
        v.forEach { g ->
            val op = PostingGrant.build(g.username, Json.parseToJsonElement(g.accountJson))!!
            val encoded = OperationJson.encode(op)
            assertEquals(g.opJson, encoded.toString(), g.name)
            val tx = Transaction(GoldenVectors.REF_BLOCK_NUM, GoldenVectors.REF_BLOCK_PREFIX, GoldenVectors.EXPIRATION, listOf(op))
            assertEquals(g.txId, tx.id(), g.name)
            assertEquals(g.signature, tx.sign(key).toHex(), g.name)
            assertEquals(op, OperationJson.decodeOwn(encoded), g.name)
            assertEquals(KeyRole.ACTIVE, OperationAuthority.required(JsonArray(listOf(encoded))), "an authority change needs the active key")
            assertFalse(OperationJson.canDecode(encoded), "a link can never ask the app to sign an authority change")
        }
    }

    @Test
    fun everythingTheAccountHadIsKeptAndOnlyTheGrantIsAdded() {
        v.forEach { g ->
            val before = Json.parseToJsonElement(g.accountJson).jsonObject
            val posting = before["posting"]!!.jsonObject
            val op = PostingGrant.build(g.username, before)!!
            assertEquals(posting["weight_threshold"]!!.jsonPrimitive.content.toLong(), op.posting.weightThreshold, g.name)
            // keys: same entries, same order, same weights
            assertEquals(posting["key_auths"]!!.jsonArray.map { it.jsonArray[0].jsonPrimitive.content to it.jsonArray[1].jsonPrimitive.content.toInt() }, op.posting.keyAuths.map { it.name to it.weight }, g.name)
            val accountsBefore = posting["account_auths"]!!.jsonArray.map { it.jsonArray[0].jsonPrimitive.content to it.jsonArray[1].jsonPrimitive.content.toInt() }
            val accountsAfter = op.posting.accountAuths.map { it.name to it.weight }
            assertEquals(accountsBefore.toSet() + ("ecency.app" to op.posting.weightThreshold.toInt()), accountsAfter.toSet(), g.name)
            assertEquals(accountsAfter.sortedBy { it.first }, accountsAfter, "sorted by name, as hived requires")
            assertEquals(before["memo_key"]!!.jsonPrimitive.content, op.memoKey, g.name)
            assertEquals(before["json_metadata"]!!.jsonPrimitive.content, op.jsonMetadata, "the legacy metadata is written back unchanged")
            // the operation carries no owner and no active authority at all
            val body = OperationJson.encode(op)[1].jsonObject
            assertEquals(setOf("account", "posting", "memo_key", "json_metadata"), body.keys, g.name)
        }
    }

    @Test
    fun anAccountThatAlreadyGrantsItNeedsNothing() {
        val granted = account(1).edit("posting") { p -> p.edit("account_auths") { JsonArray(it.jsonArray + Json.parseToJsonElement("""["ecency.app",1]""")) } }
        assertNull(PostingGrant.build("spike-voter", granted))
    }

    private fun JsonElement.edit(key: String, change: (JsonElement) -> JsonElement?): JsonObject {
        val o = jsonObject
        val next = change(o.getValue(key))
        return JsonObject(if (next == null) o - key else o + (key to next))
    }

    private fun JsonElement.editObject(key: String, change: (JsonObject) -> JsonObject?): JsonObject = edit(key) { change(it.jsonObject) }

    private fun JsonElement.edit(key: String, inner: String, change: (JsonElement) -> JsonElement?): JsonObject = edit(key) { it.edit(inner, change) }

    @Test
    fun anythingThatDoesNotLookLikeACompleteAccountIsRefused() {
        val good = account(0)
        val name = v[0].username
        val oneKey = good["posting"]!!.jsonObject["key_auths"]!!.jsonArray[0].jsonArray
        val bad: Map<String, JsonElement?> = mapOf(
            "no row at all" to null,
            "not an object" to JsonPrimitive("spike-voter"),
            "another account's row" to good.edit("name") { JsonPrimitive("someone-else") },
            "no posting authority" to good.edit("posting") { null },
            "posting stripped to nothing" to good.edit("posting", "key_auths") { JsonArray(emptyList()) },
            "posting without account_auths" to good.edit("posting", "account_auths") { null },
            "posting with a field hived does not have" to good.edit("posting") { JsonObject(it.jsonObject + ("extra" to JsonPrimitive(1))) },
            "threshold zero" to good.edit("posting", "weight_threshold") { JsonPrimitive(0) },
            "threshold as text" to good.edit("posting", "weight_threshold") { JsonPrimitive("1") },
            "threshold the keys cannot reach" to good.edit("posting", "weight_threshold") { JsonPrimitive(5) },
            "a key with weight zero" to good.edit("posting", "key_auths") { JsonArray(listOf(JsonArray(listOf(oneKey[0], JsonPrimitive(0))))) },
            "a key that is not a key" to good.edit("posting", "key_auths") { JsonArray(listOf(JsonArray(listOf(JsonPrimitive("STMnotAKeyAtAll"), JsonPrimitive(1))))) },
            "a key with a broken checksum" to good.edit("posting", "key_auths") { JsonArray(listOf(JsonArray(listOf(JsonPrimitive(oneKey[0].jsonPrimitive.content.dropLast(1) + "x"), JsonPrimitive(1))))) },
            "the same key twice" to good.edit("posting", "key_auths") { JsonArray(listOf(oneKey, oneKey)) },
            "a key entry of three" to good.edit("posting", "key_auths") { JsonArray(listOf(JsonArray(oneKey + JsonPrimitive(1)))) },
            "no owner authority" to good.edit("owner") { null },
            "an empty active authority" to good.edit("active", "key_auths") { JsonArray(emptyList()) },
            "no memo key" to good.edit("memo_key") { null },
            "a memo key that is not a key" to good.edit("memo_key") { JsonPrimitive("STM-not-a-key") },
            "no json_metadata" to good.edit("json_metadata") { null },
        )
        bad.forEach { (why, row) -> assertFailsWith<PostingGrantRefused>(why) { PostingGrant.build(name, row) } }
        // 40 members is hived's limit: no room for one more
        val full = good.edit("posting", "account_auths") { JsonArray((1..39).map { JsonArray(listOf(JsonPrimitive("acc-" + it.toString().padStart(2, '0')), JsonPrimitive(1))) }) }
        assertTrue(assertFailsWith<PostingGrantRefused> { PostingGrant.build(name, full) }.message!!.contains("no room"))
    }

    @Test
    fun listsOutOfHivedsOrderSerializeLikeTheSortedOnes() {
        val g = GrantGoldenVectors.unsorted
        val row = Json.parseToJsonElement(g.accountJson)
        val keysAsGiven = row.jsonObject["posting"]!!.jsonObject["key_auths"]!!.jsonArray.map { it.jsonArray[0].jsonPrimitive.content }
        assertTrue(keysAsGiven != keysAsGiven.sorted(), "the fixture row really is out of order")
        val op = PostingGrant.build(g.username, row)!!
        assertEquals(g.opJson, OperationJson.encode(op).toString())
        val tx = Transaction(GoldenVectors.REF_BLOCK_NUM, GoldenVectors.REF_BLOCK_PREFIX, GoldenVectors.EXPIRATION, listOf(op))
        assertEquals(g.txId, tx.id())
        assertEquals(g.signature, tx.sign(key).toHex())
        // the serializer sorts by itself too: an operation handed over unsorted signs the same bytes
        val shuffled = op.copy(posting = op.posting.copy(accountAuths = op.posting.accountAuths.reversed(), keyAuths = op.posting.keyAuths.reversed()))
        assertEquals(g.txId, Transaction(GoldenVectors.REF_BLOCK_NUM, GoldenVectors.REF_BLOCK_PREFIX, GoldenVectors.EXPIRATION, listOf(shuffled)).id())
        // weights travel with their keys
        val weights = row.jsonObject["posting"]!!.jsonObject["key_auths"]!!.jsonArray.associate { it.jsonArray[0].jsonPrimitive.content to it.jsonArray[1].jsonPrimitive.content.toInt() }
        op.posting.keyAuths.forEach { assertEquals(weights.getValue(it.name), it.weight) }
    }

    @Test
    fun theRowMustBeThisAccountsAuthorityAndRefusalsSayWhatIsWrong() {
        val good = account(0)
        val name = v[0].username
        val ownKey = good["posting"]!!.jsonObject["key_auths"]!!.jsonArray[0].jsonArray[0].jsonPrimitive.content
        assertTrue(PostingGrant.build(name, good, ownPostingKey = ownKey, expectedKeys = listOf(ownKey), expectedAccounts = emptyList()) != null)
        assertTrue(assertFailsWith<PostingGrantRefused> { PostingGrant.build(name, good, ownPostingKey = good["memo_key"]!!.jsonPrimitive.content) }.message!!.contains("own posting key"))
        assertTrue(assertFailsWith<PostingGrantRefused> { PostingGrant.build(name, good, expectedKeys = listOf(ownKey), expectedAccounts = listOf("someone")) }.message!!.contains("disagree"))
        assertTrue(assertFailsWith<PostingGrantRefused> { PostingGrant.build(name, good, expectedKeys = emptyList()) }.message!!.contains("disagree"))
        val zero = good.edit("posting", "account_auths") { Json.parseToJsonElement("""[["frozen.app",0]]""") }
        assertTrue(assertFailsWith<PostingGrantRefused> { PostingGrant.build(name, zero) }.message!!.contains("weight 0"))
        val high = good.edit("posting", "weight_threshold") { JsonPrimitive(70000) }.edit("posting", "account_auths") { Json.parseToJsonElement("""[["big.app",65535],["big2.app",65535]]""") }
        assertTrue(assertFailsWith<PostingGrantRefused> { PostingGrant.build(name, high) }.message!!.contains("threshold is too high"))
    }

    @Test
    fun linksAndOtherAuthorityChangesStayRefused() {
        val op = Json.parseToJsonElement(v[0].opJson).jsonArray
        val body = op[1].jsonObject
        val withActive = JsonArray(listOf(op[0], JsonObject(body + ("active" to body.getValue("posting")))))
        val withOwner = JsonArray(listOf(op[0], JsonObject(body + ("owner" to body.getValue("posting")))))
        listOf(withActive, withOwner).forEach {
            assertFailsWith<com.ecency.core.api.broadcast.BroadcastException> { OperationJson.decodeOwn(it) }
            assertFalse(OperationJson.canDecode(it))
        }
        assertFailsWith<com.ecency.core.api.broadcast.BroadcastException> { OperationJson.decodeOwn(JsonArray(listOf(op[0], JsonObject(body - "posting")))) }
    }
}

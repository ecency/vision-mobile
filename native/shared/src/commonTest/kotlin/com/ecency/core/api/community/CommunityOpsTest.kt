package com.ecency.core.api.community

import com.ecency.core.api.InvalidInputException
import com.ecency.core.api.broadcast.CommunityOps
import com.ecency.core.api.broadcast.CommunityProps
import com.ecency.core.api.broadcast.JsJson
import com.ecency.core.api.broadcast.OperationJson
import com.ecency.core.api.broadcast.codePointCount
import com.ecency.core.api.broadcast.takeCodePoints
import com.ecency.core.hive.GoldenVectors
import com.ecency.core.hive.PrivateKey
import com.ecency.core.hive.Transaction
import com.ecency.core.hive.toHex
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Community operations against @ecency/sdk's builders (tools/golden/generate-communities.mjs). */
class CommunityOpsTest {
    private val key = PrivateKey.fromWif(GoldenVectors.WIF)

    private fun tx(v: OpVector) = Transaction(
        refBlockNum = GoldenVectors.REF_BLOCK_NUM,
        refBlockPrefix = GoldenVectors.REF_BLOCK_PREFIX,
        expiration = GoldenVectors.EXPIRATION,
        operations = listOf(v.build()),
    )

    @Test
    fun customJsonIsByteIdenticalToTheSdk() {
        assertTrue(CommunityGoldenVectors.ops.size >= 14)
        CommunityGoldenVectors.ops.forEach { v ->
            val op = v.build()
            assertEquals(v.json, op.json, v.name)
            assertEquals("community", op.id, v.name)
            assertEquals(Json.parseToJsonElement(v.opJson), OperationJson.encode(op), v.name)
        }
    }

    @Test
    fun transactionIdsAndSignaturesMatchTheSdk() {
        CommunityGoldenVectors.ops.forEach { v ->
            assertEquals(v.txId, tx(v).id(), v.name)
            assertEquals(v.signature, tx(v).sign(key).toHex(), v.name)
        }
    }

    @Test
    fun postingAuthorityOnly() {
        CommunityGoldenVectors.ops.forEach { v ->
            val op = v.build()
            assertEquals(emptyList(), op.requiredAuths, v.name)
            assertEquals(listOf("spike-mod"), op.requiredPostingAuths, v.name)
        }
    }

    @Test
    fun missingParametersAreRefusedLikeTheSdk() {
        assertFailsWith<InvalidInputException> { CommunityOps.subscribe("", "hive-1") }
        assertFailsWith<InvalidInputException> { CommunityOps.unsubscribe("alice", "") }
        assertFailsWith<InvalidInputException> { CommunityOps.setRole("alice", "hive-1", "bob", "") }
        assertFailsWith<InvalidInputException> { CommunityOps.pinPost("alice", "hive-1", "bob", "", true) }
        assertFailsWith<InvalidInputException> { CommunityOps.mutePost("alice", "hive-1", "", "p", "n", true) }
        assertFailsWith<InvalidInputException> { CommunityOps.updateProps("alice", "", CommunityProps("t", "a", "en", "", "", false)) }
    }

    @Test
    fun notesFollowHivemindsRules() {
        // hivemind: "notes cannot be blank" on mute, unmute and flag alike; no padding; at most 120 code points
        assertFailsWith<InvalidInputException> { CommunityOps.mutePost("alice", "hive-1", "bob", "p", "", true) }
        assertFailsWith<InvalidInputException> { CommunityOps.mutePost("alice", "hive-1", "bob", "p", "  ", false) }
        assertFailsWith<InvalidInputException> { CommunityOps.flagPost("alice", "hive-1", "bob", "p", "") }
        assertFailsWith<InvalidInputException> { CommunityOps.muteUser("alice", "hive-1", "bob", "\n", true) }
        assertFailsWith<InvalidInputException> { CommunityOps.mutePost("alice", "hive-1", "bob", "p", " spam", true) }
        assertFailsWith<InvalidInputException> { CommunityOps.mutePost("alice", "hive-1", "bob", "p", "x".repeat(121), true) }
        CommunityOps.mutePost("alice", "hive-1", "bob", "p", "x".repeat(120), true)
        CommunityOps.mutePost("alice", "hive-1", "bob", "p", "\uD83D\uDC1D".repeat(120), false) // 120 emoji are 120 code points
    }

    @Test
    fun codePointsNotUtf16Units() {
        val bee = "\uD83D\uDC1D"
        assertEquals(3, "a${bee}b".codePointCount())
        assertEquals(2, "\uD83D$bee".codePointCount()) // a lone half counts as one
        assertEquals("a$bee", "a${bee}b".takeCodePoints(2))
        assertEquals("a", "a${bee}b".takeCodePoints(1)) // never half a pair
        assertEquals("", "abc".takeCodePoints(0))
        assertEquals("abc", "abc".takeCodePoints(9))
    }

    @Test
    fun quoteMatchesJsonStringify() {
        assertEquals("\"a\\\"b\\\\c\\n\\t\\b\\f\\r\\u0001\\u001f\u007f\"", JsJson.quote("a\"b\\c\n\t\b\u000C\r\u0001\u001f\u007f"))
        assertEquals("\"\uD83D\uDC1D\"", JsJson.quote("\uD83D\uDC1D")) // a pair stays as is
        assertEquals("\"\\ud83d x \\udc1d\"", JsJson.quote("\uD83D x \uDC1D")) // lone halves are escaped
        assertEquals("\"\u2028/\"", JsJson.quote("\u2028/")) // JSON.stringify leaves both alone
    }

    @Test
    fun opsArrayForTheSigningModel() {
        val ops = CommunityOps.ops(CommunityOps.subscribe("alice", "hive-1"), CommunityOps.unsubscribe("alice", "hive-2"))
        assertEquals(2, ops.size)
        assertEquals("\"custom_json\"", (ops[0] as kotlinx.serialization.json.JsonArray)[0].toString())
    }
}

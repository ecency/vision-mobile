package com.ecency.core.math

import com.ecency.core.hive.RcComment
import com.ecency.core.hive.RcCommentOptions
import com.ecency.core.hive.RcCost
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [UBig] against JS BigInt and the RC estimate against @ecency/sdk ([RcGoldenVectors]). */
class RcCostTest {
    private fun big(text: String) = UBig.parse(text)!!

    @Test
    fun bigIntegerOperationsMatchBigInt() {
        for (v in RcGoldenVectors.ops) {
            val a = big(v.a)
            val b = big(v.b)
            assertEquals(v.a, a.toString(), "digits")
            assertEquals(v.sum, (a + b).toString(), "${v.a} + ${v.b}")
            assertEquals(v.sum, (b + a).toString())
            assertEquals(v.product, (a * b).toString(), "${v.a} * ${v.b}")
            assertEquals(v.product, (b * a).toString())
            if (v.quotient == null) assertFailsWith<ArithmeticException> { a / b } else assertEquals(v.quotient, (a / b).toString(), "${v.a} / ${v.b}")
            assertEquals(v.shifted, (a shr v.shift).toString(), "${v.a} >> ${v.shift}")
            assertEquals(v.asNumber, a.toDouble(), "Number(${v.a})")
            assertEquals(v.compare, a.compareTo(b).coerceIn(-1, 1), "${v.a} <=> ${v.b}")
            assertEquals(v.compare == 0, a == b)
        }
    }

    @Test
    fun readingNumbersMatchesBigInt() {
        for ((d, digits) in RcGoldenVectors.doubles) assertEquals(digits, UBig.ofDouble(d)!!.toString(), "$d")
        assertNull(UBig.ofDouble(-1.0))
        assertNull(UBig.ofDouble(Double.NaN))
        assertNull(UBig.ofDouble(Double.POSITIVE_INFINITY))
        for (bad in listOf("", "-1", "1.5", " 1", "0x10", "1e3")) assertNull(UBig.parse(bad), bad)
        assertEquals("0", big("000").toString())
        assertTrue(big("0").isZero)
        assertEquals(65, big("18446744073709551616").bitLength)
        assertEquals("18446744073709551615", UBig.of(ULong.MAX_VALUE).toString())
    }

    @Test
    fun resourceCostsMatchTheSdk() {
        for (v in RcGoldenVectors.costs) {
            assertEquals(v.cost, RcCost.resourceCost(big(v.coeffA), big(v.coeffB), v.shift, v.pool, v.count, v.regenShare), "a=${v.coeffA} b=${v.coeffB} shift=${v.shift} pool=${v.pool} count=${v.count} regen=${v.regenShare}")
        }
    }

    @Test
    fun commentEstimatesMatchTheSdk() {
        val params = Json.parseToJsonElement(RcGoldenVectors.PARAMS).jsonObject
        val stats = Json.parseToJsonElement(RcGoldenVectors.STATS).jsonObject
        var warned = 0
        for (v in RcGoldenVectors.comments) {
            val op = RcComment(v.author, v.permlink, v.parentAuthor, v.parentPermlink, v.title, v.body, v.jsonMetadata)
            val options = v.beneficiaries?.let { RcCommentOptions(it.takeIf { v.hasBeneficiaryExtension }) }
            assertEquals(v.transactionBytes, RcCost.transactionBytes(op, options), "bytes of ${v.permlink} with ${v.body.length} chars")
            val account = buildJsonObject {
                put("max_rc", v.maxRc)
                put("rc_manabar", buildJsonObject { put("current_mana", v.currentMana); put("last_update_time", v.lastUpdate) })
            }
            val r = RcCost.precheck(account, stats, params, op, options, RcGoldenVectors.NOW_MILLIS)
            assertEquals(v.ready, r.ready)
            assertEquals(v.cost, r.cost, "cost of ${v.permlink} with ${v.body.length} chars")
            assertEquals(v.mana, r.currentMana)
            assertEquals(v.estimatedCost, r.estimatedCost)
            assertEquals(v.willLikelyFail, r.willLikelyFail)
            assertEquals(v.deficit, r.deficit)
            if (r.willLikelyFail) warned++
        }
        assertTrue(warned in 1 until RcGoldenVectors.comments.size, "the vectors hold both outcomes")
    }

    @Test
    fun missingOrUnreadableInputsAreNeverAnAnswer() {
        val params = Json.parseToJsonElement(RcGoldenVectors.PARAMS).jsonObject
        val stats = Json.parseToJsonElement(RcGoldenVectors.STATS).jsonObject
        val op = RcComment("a", "p", "", "hive-125125", "T", "body", "{}")
        val account = buildJsonObject {
            put("max_rc", "10")
            put("rc_manabar", buildJsonObject { put("current_mana", "1"); put("last_update_time", 1791288000) })
        }
        fun check(a: JsonObject?, s: JsonObject?, p: JsonObject?) {
            val r = RcCost.precheck(a, s, p, op, null, RcGoldenVectors.NOW_MILLIS)
            assertFalse(r.ready)
            assertFalse(r.willLikelyFail)
        }
        check(null, stats, params)
        check(account, null, params)
        check(account, stats, null)
        check(account, JsonObject(stats - "pool"), params)
        check(account, stats, JsonObject(params - "size_info"))
        check(account, JsonObject(stats + ("regen" to kotlinx.serialization.json.JsonPrimitive("fast"))), params)
        check(buildJsonObject { put("max_rc", "x") }, stats, params)
        assertTrue(RcCost.precheck(account, stats, params, op, null, RcGoldenVectors.NOW_MILLIS).ready)
        assertEquals(1, RcCost.varintByteLength(127))
        assertEquals(2, RcCost.varintByteLength(128))
        assertEquals(3, RcCost.varintByteLength(16384))
    }
}

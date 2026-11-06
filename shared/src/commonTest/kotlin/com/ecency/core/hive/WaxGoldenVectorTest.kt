package com.ecency.core.hive

import com.ecency.core.api.GrantGoldenVectors
import com.ecency.core.api.broadcast.OperationJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * #3613: every transaction the @ecency/sdk vectors pin, against Hive's own serializer too (@hiveio/wax, hived's
 * protocol code; tools/golden/generate-wax.mjs). The Kotlin bytes, transaction id and digest must equal wax's, and
 * wax must agree with the SDK vectors: a disagreement is a finding about one of the three, never something to adjust.
 */
class WaxGoldenVectorTest {
    /** One SDK vector: the Kotlin operations, and the SDK's bytes (where the vector has them) and transaction id. */
    private class Sdk(val operations: List<Operation>, val serialized: String?, val txId: String)

    private fun walletVectors(source: String, list: List<WalletVector>) = list.map {
        (source to it.name) to Sdk((Json.parseToJsonElement(it.operationsJson) as JsonArray).map { op -> OperationJson.decode(op as JsonArray) }, it.serialized, it.txId)
    }

    private val sdk: Map<Pair<String, String>, Sdk> by lazy {
        (
            GoldenVectors.transactions.map { ("GoldenVectors" to it.name) to Sdk(it.operations, it.serialized, it.txId) } +
                walletVectors("WalletGoldenVectors", WalletGoldenVectors.transactions) +
                walletVectors("PlatformGoldenVectors", PlatformGoldenVectors.transactions) +
                walletVectors("LinkOpGoldenVectors", LinkOpGoldenVectors.transactions) +
                (GrantGoldenVectors.grants + GrantGoldenVectors.unsorted).map {
                    ("GrantGoldenVectors" to it.name) to Sdk(listOf(OperationJson.decodeOwn(Json.parseToJsonElement(it.opJson) as JsonArray)), null, it.txId)
                }
            ).toMap()
    }

    @Test
    fun waxCoversEveryTransactionTheSdkVectorsPin() {
        assertEquals(sdk.keys.sortedBy { it.toString() }, WaxGoldenVectors.transactions.map { it.source to it.name }.sortedBy { it.toString() })
    }

    @Test
    fun kotlinSerializesAndHashesEveryTransactionLikeWaxAndTheSdk() {
        WaxGoldenVectors.transactions.forEach { w ->
            val what = "${w.source} ${w.name}"
            val v = sdk.getValue(w.source to w.name)
            val tx = Transaction(GoldenVectors.REF_BLOCK_NUM, GoldenVectors.REF_BLOCK_PREFIX, GoldenVectors.EXPIRATION, v.operations)
            // wax and the SDK first: a mismatch here is between the two references, not the app
            v.serialized?.let { assertEquals(it, w.serialized, "$what: wax and SDK bytes") }
            assertEquals(v.txId, w.txId, "$what: wax and SDK transaction id")
            assertEquals(w.serialized, tx.serialize().toHex(), "$what: Kotlin bytes")
            assertEquals(w.txId, tx.id(), "$what: Kotlin transaction id")
            assertEquals(w.digest, tx.digest().toHex(), "$what: Kotlin digest")
        }
    }
}

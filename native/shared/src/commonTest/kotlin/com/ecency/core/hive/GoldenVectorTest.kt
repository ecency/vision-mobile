package com.ecency.core.hive

import kotlin.test.Test
import kotlin.test.assertEquals

/** Byte-for-byte parity with hived serialization and @ecency/sdk signing (see native/tools/golden). */
class GoldenVectorTest {
    private val key = PrivateKey.fromWif(GoldenVectors.WIF)

    private fun tx(vector: TxVector) = Transaction(
        refBlockNum = GoldenVectors.REF_BLOCK_NUM,
        refBlockPrefix = GoldenVectors.REF_BLOCK_PREFIX,
        expiration = GoldenVectors.EXPIRATION,
        operations = vector.operations,
    )

    @Test
    fun serializationMatchesHived() {
        GoldenVectors.transactions.forEach { v ->
            assertEquals(v.serialized, tx(v).serialize().toHex(), v.name)
        }
    }

    @Test
    fun transactionIdAndDigestMatchTheSdk() {
        GoldenVectors.transactions.forEach { v ->
            assertEquals(v.txId, tx(v).id(), v.name)
            assertEquals(v.digest, tx(v).digest().toHex(), v.name)
        }
    }

    @Test
    fun signaturesMatchTheSdkDeterministicSignature() {
        GoldenVectors.transactions.forEach { v ->
            assertEquals(v.signature, tx(v).sign(key).toHex(), v.name)
        }
    }

    @Test
    fun sdkSignaturesRecoverToTheSigner() {
        GoldenVectors.transactions.forEach { v ->
            val digest = v.digest.hexToBytes()
            assertEquals(GoldenVectors.PUBLIC_KEY, Signature.fromHex(v.sdkSignature).recover(digest).toString(), v.name)
            assertEquals(GoldenVectors.PUBLIC_KEY, Signature.fromHex(v.signature).recover(digest).toString(), v.name)
        }
    }
}

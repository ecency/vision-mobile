package com.ecency.core.hive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** #3633: a `#` memo is encrypted as every Hive wallet does, byte for byte with @ecency/sdk and dhive. */
class MemoTest {
    @Test
    fun anEncryptedMemoMatchesTheSdkByteForByte() {
        for (v in MEMO_VECTORS) {
            val from = PrivateKey.fromSeed(v.fromSeed)
            val to = PrivateKey.fromSeed(v.toSeed).publicKey()
            assertEquals(v.encoded, Memo.encode(from, to, v.memo, v.nonce), v.memo)
        }
    }

    @Test
    fun theRecipientAndTheSenderReadTheSdksMemoBack() {
        for (v in MEMO_VECTORS) {
            assertEquals(v.memo, Memo.decode(PrivateKey.fromSeed(v.toSeed), v.encoded), v.memo)
            assertEquals(v.memo, Memo.decode(PrivateKey.fromSeed(v.fromSeed), v.encoded), v.memo)
        }
    }

    @Test
    fun aFreshNonceGivesAnotherCipherTextThatStillReadsBack() {
        val from = PrivateKey.fromSeed("ecency-native-memo-golden-throwaway-1")
        val to = PrivateKey.fromSeed("ecency-native-memo-golden-throwaway-2")
        val a = Memo.encode(from, to.publicKey(), "#same")
        val b = Memo.encode(from, to.publicKey(), "#same")
        assertNotEquals(a, b)
        assertTrue(a.startsWith("#"))
        assertEquals("#same", Memo.decode(to, a))
        assertEquals("#same", Memo.decode(to, b))
    }

    @Test
    fun onlyAHashMemoIsEncryptedAndAStrangersKeyCannotReadIt() {
        val from = PrivateKey.fromSeed("ecency-native-memo-golden-throwaway-1")
        val to = PrivateKey.fromSeed("ecency-native-memo-golden-throwaway-2")
        assertFailsWith<IllegalArgumentException> { Memo.encode(from, to.publicKey(), "plain") }
        val v = MEMO_VECTORS.first()
        assertFailsWith<IllegalArgumentException> { Memo.decode(PrivateKey.fromSeed("someone-else"), v.encoded) }
    }
}

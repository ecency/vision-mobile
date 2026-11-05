package com.ecency.core.hive

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
    /** Review: a pasted memo that only looks encrypted (a cut or padded cipher text) is not taken as encrypted. */
    @Test
    fun onlyAWholeCipherTextCountsAsEncrypted() {
        for (v in MEMO_VECTORS) assertTrue(Memo.isEncrypted(v.encoded), v.memo)
        val raw = Base58.decode(MEMO_VECTORS.first().encoded.substring(1))
        assertFalse(Memo.isEncrypted("#" + Base58.encode(raw.copyOfRange(0, raw.size - 1))), "one byte short")
        assertFalse(Memo.isEncrypted("#" + Base58.encode(raw + byteArrayOf(0))), "one byte too many")
        assertFalse(Memo.isEncrypted("#a plain note"))
        assertFalse(Memo.isEncrypted("plain"))
    }

    @Test
    fun aTransferWithAPlainHashMemoIsFound() {
        fun op(name: String, memo: String) = Json.parseToJsonElement("""["$name",{"from":"a","to":"b","amount":"1.000 HIVE","memo":${Json.encodeToString(memo)}}]""")
        for (name in listOf("transfer", "recurrent_transfer", "transfer_to_savings", "transfer_from_savings")) {
            assertTrue(Memo.hasPlainHashMemo(op(name, "#secret")), name)
            assertFalse(Memo.hasPlainHashMemo(op(name, MEMO_VECTORS.first().encoded)), name)
            assertFalse(Memo.hasPlainHashMemo(op(name, "public")), name)
        }
        assertFalse(Memo.hasPlainHashMemo(op("custom_json", "#not a memo")))
    }
}

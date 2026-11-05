package com.ecency.core.hive

import com.ecency.core.legacy.aesCbcDecrypt
import com.ecency.core.legacy.aesCbcEncrypt
import org.kotlincrypto.hash.sha2.SHA512

/**
 * Encrypted transfer memos (#3633), the format every Hive wallet reads (dhive and @ecency/sdk `Memo.encode`, pinned by
 * MemoGoldenVectors): a memo that starts with `#` is encrypted with the sender's memo key for the recipient's memo
 * public key, and either of the two keys reads it back.
 *
 * The cipher: the shared secret S ([PrivateKey.sharedSecret]); K = SHA-512(nonce, little endian, then S); AES-256-CBC
 * with key K[0..32] and iv K[32..48] over the memo text as a length-prefixed string; a check of the first four bytes of
 * SHA-256(K). On chain: `#` and base58 of (from key, to key, nonce, check, length-prefixed cipher text).
 */
object Memo {
    /** [memo] starts with `#` (the `#` itself is not encrypted). [nonce]: a fresh random one unless a test pins it. */
    fun encode(from: PrivateKey, to: PublicKey, memo: String, nonce: Long = randomNonce()): String {
        require(memo.startsWith("#")) { "only a memo starting with # is encrypted" }
        val plain = HiveWriter().apply { string(memo.substring(1)) }.toByteArray()
        val k = cipherKey(from.sharedSecret(to), nonce)
        val encrypted = aesCbcEncrypt(k.copyOfRange(0, 32), k.copyOfRange(32, 48), plain)
        val w = HiveWriter()
        w.bytes(from.publicKey().compressed)
        w.bytes(to.compressed)
        w.i64(nonce)
        w.bytes(check(k))
        w.varint32(encrypted.size)
        w.bytes(encrypted)
        return "#" + Base58.encode(w.toByteArray())
    }

    /**
     * [memo] is an encrypted memo's text (`#`, then base58 of two keys, nonce, check and cipher), not a plain `#` memo:
     * two compressed keys, then a cipher text whose length prefix matches what follows and fills whole AES blocks.
     */
    fun isEncrypted(memo: String): Boolean {
        if (!memo.startsWith("#") || memo.length < MIN_ENCODED || memo.any { it.isWhitespace() }) return false
        val raw = runCatching { Base58.decode(memo.substring(1)) }.getOrNull() ?: return false
        if (raw.size < HEADER + 17) return false
        fun isKey(at: Int) = raw[at].toInt() == 2 || raw[at].toInt() == 3
        if (!isKey(0) || !isKey(33)) return false
        var at = HEADER
        var length = 0L
        var shift = 0
        while (true) {
            if (at >= raw.size || shift > 28) return false
            val b = raw[at++].toInt() and 0xff
            length = length or ((b and 0x7f).toLong() shl shift)
            if (b < 0x80) break
            shift += 7
        }
        return length > 0 && length % 16 == 0L && at + length == raw.size.toLong()
    }

    /**
     * A transfer-like operation (`[name, body]`) whose memo starts with `#` but is not encrypted: signed as it is, it
     * would put text the sender meant to keep private on chain. Websites and signing links have no memo key lookup,
     * so such an operation is refused there (#3633).
     */
    fun hasPlainHashMemo(op: kotlinx.serialization.json.JsonElement): Boolean {
        val pair = op as? kotlinx.serialization.json.JsonArray ?: return false
        val name = (pair.getOrNull(0) as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
        if (name !in MEMO_OPS) return false
        val memo = ((pair.getOrNull(1) as? kotlinx.serialization.json.JsonObject)?.get("memo") as? kotlinx.serialization.json.JsonPrimitive)
            ?.takeIf { it.isString }?.content ?: return false
        return memo.startsWith("#") && !isEncrypted(memo)
    }

    private val MEMO_OPS = setOf("transfer", "recurrent_transfer", "transfer_to_savings", "transfer_from_savings")

    /** Reads an encrypted memo with the sender's or the recipient's memo key; throws for another key or a damaged memo. */
    fun decode(key: PrivateKey, memo: String): String {
        require(memo.startsWith("#")) { "not an encrypted memo" }
        val raw = Base58.decode(memo.substring(1))
        var at = 0
        fun take(n: Int): ByteArray {
            require(at + n <= raw.size) { "damaged memo" }
            return raw.copyOfRange(at, at + n).also { at += n }
        }
        fun varint(): Int {
            var value = 0
            var shift = 0
            while (true) {
                val b = take(1)[0].toInt() and 0xff
                value = value or ((b and 0x7f) shl shift)
                if (b < 0x80) return value
                shift += 7
                require(shift <= 28) { "damaged memo" }
            }
        }
        val fromKey = PublicKey(take(33))
        val toKey = PublicKey(take(33))
        val nonceBytes = take(8)
        val nonce = (0 until 8).fold(0L) { acc, i -> acc or ((nonceBytes[i].toLong() and 0xff) shl (8 * i)) }
        val checkBytes = take(4)
        val cipher = take(varint())
        // the reader is one side; the secret is with the other side's public key
        val other = if (key.publicKey().compressed.contentEquals(fromKey.compressed)) toKey else fromKey
        val k = cipherKey(key.sharedSecret(other), nonce)
        require(check(k).contentEquals(checkBytes)) { "this key cannot read the memo" }
        val plain = aesCbcDecrypt(k.copyOfRange(0, 32), k.copyOfRange(32, 48), cipher)
        at = 0
        val text = plain.let { p ->
            var length = 0
            var shift = 0
            var i = 0
            while (true) {
                val b = p[i++].toInt() and 0xff
                length = length or ((b and 0x7f) shl shift)
                if (b < 0x80) break
                shift += 7
            }
            p.copyOfRange(i, i + length).decodeToString()
        }
        return "#$text"
    }

    /** from and to keys (33 each), nonce (8), check (4). */
    private const val HEADER = 33 + 33 + 8 + 4

    /** `#` and the base58 of the smallest memo: the header, a length byte and one AES block. */
    private const val MIN_ENCODED = 100

    private fun cipherKey(secret: ByteArray, nonce: Long): ByteArray {
        val w = HiveWriter()
        w.i64(nonce)
        w.bytes(secret)
        return SHA512().digest(w.toByteArray())
    }

    private fun check(k: ByteArray): ByteArray = sha256(k).copyOf(4)

    private fun randomNonce(): Long = com.ecency.core.auth.secureRandomBytes(8).fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xff) }
}

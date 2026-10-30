package com.ecency.core.hive

import fr.acinq.secp256k1.Secp256k1

const val HIVE_ADDRESS_PREFIX = "STM"

enum class KeyRole(val value: String) { OWNER("owner"), ACTIVE("active"), POSTING("posting"), MEMO("memo") }

/** A secp256k1 private key. Its string form never contains the key. */
class PrivateKey private constructor(private val secret: ByteArray) {
    init {
        require(secret.size == 32 && Secp256k1.secKeyVerify(secret)) { "invalid private key" }
    }

    fun toWif(): String {
        val payload = byteArrayOf(WIF_VERSION) + secret
        return Base58.encode(payload + doubleSha256(payload).copyOf(4))
    }

    fun publicKey(): PublicKey = PublicKey(Secp256k1.pubKeyCompress(Secp256k1.pubkeyCreate(secret)))

    /**
     * Signs a 32-byte digest. Deterministic (RFC 6979) and low-S, encoded as hived expects:
     * header byte (recovery id + 31) followed by the 64-byte compact signature.
     */
    fun sign(digest: ByteArray): Signature {
        require(digest.size == 32) { "expected a 32-byte digest" }
        val compact = Secp256k1.sign(digest, secret)
        val expected = publicKey()
        val recovery = (0..3).first { id ->
            runCatching { Signature(id, compact).recover(digest) == expected }.getOrDefault(false)
        }
        return Signature(recovery, compact)
    }

    override fun toString(): String = "PrivateKey(<redacted>)"

    companion object {
        private const val WIF_VERSION = 0x80.toByte()

        fun fromWif(wif: String): PrivateKey {
            val raw = Base58.decode(wif)
            require(raw.size == 37 && raw[0] == WIF_VERSION) { "invalid WIF" }
            val payload = raw.copyOf(33)
            require(doubleSha256(payload).copyOf(4).contentEquals(raw.copyOfRange(33, 37))) { "WIF checksum mismatch" }
            return PrivateKey(payload.copyOfRange(1, 33))
        }

        /** sha256(UTF-8 seed), as PrivateKey.fromSeed in the JS SDK for non-hex seeds. */
        fun fromSeed(seed: String): PrivateKey = PrivateKey(sha256(seed.encodeToByteArray()))

        /** Hive master-password derivation: sha256(username + role + password). */
        fun fromLogin(username: String, password: String, role: KeyRole): PrivateKey =
            fromSeed(username + role.value + password)
    }
}

/** A compressed secp256k1 public key, shown as STM + base58(key + ripemd160 checksum). */
class PublicKey internal constructor(val compressed: ByteArray) {
    init {
        require(compressed.size == 33) { "expected a 33-byte compressed public key" }
    }

    override fun toString(): String =
        HIVE_ADDRESS_PREFIX + Base58.encode(compressed + Ripemd160.digest(compressed).copyOf(4))

    override fun equals(other: Any?): Boolean = other is PublicKey && other.compressed.contentEquals(compressed)

    override fun hashCode(): Int = compressed.contentHashCode()

    companion object {
        fun fromString(value: String): PublicKey {
            require(value.startsWith(HIVE_ADDRESS_PREFIX)) { "public key must start with $HIVE_ADDRESS_PREFIX" }
            val raw = Base58.decode(value.removePrefix(HIVE_ADDRESS_PREFIX))
            require(raw.size == 37) { "invalid public key length" }
            val key = raw.copyOf(33)
            require(Ripemd160.digest(key).copyOf(4).contentEquals(raw.copyOfRange(33, 37))) { "public key checksum mismatch" }
            return PublicKey(key)
        }
    }
}

/** A recoverable signature: header (recovery id + 31) and 64-byte compact r||s. */
class Signature internal constructor(val recovery: Int, val compact: ByteArray) {
    init {
        require(recovery in 0..3 && compact.size == 64) { "invalid signature" }
    }

    fun toHex(): String = (byteArrayOf((recovery + 31).toByte()) + compact).toHex()

    fun recover(digest: ByteArray): PublicKey =
        PublicKey(Secp256k1.pubKeyCompress(Secp256k1.ecdsaRecover(compact, digest, recovery)))

    override fun toString(): String = toHex()

    companion object {
        fun fromHex(hex: String): Signature {
            val raw = hex.hexToBytes()
            require(raw.size == 65) { "signature must be 65 bytes" }
            val header = raw[0].toInt() and 0xff
            require(header in 31..34) { "only compressed-key signatures are supported" }
            return Signature(header - 31, raw.copyOfRange(1, 65))
        }
    }
}

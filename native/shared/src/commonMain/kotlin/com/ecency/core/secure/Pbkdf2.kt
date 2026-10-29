package com.ecency.core.secure

import org.kotlincrypto.hash.sha2.SHA256

/**
 * PBKDF2-HMAC-SHA256 (RFC 8018). [derive] uses the platform's native implementation (CommonCrypto on iOS,
 * `SecretKeyFactory` on Android 8+), which is several times faster than Kotlin/Native code, and falls back
 * to [hmacSha256] (common Kotlin) where there is none (Android 7, JVM host tests). Both give the same bytes.
 */
object Pbkdf2 {
    private const val BLOCK = 64
    private const val HLEN = 32

    /** PBKDF2-HMAC-SHA256: the platform implementation when there is one, else [hmacSha256]. */
    fun derive(password: ByteArray, salt: ByteArray, iterations: Int, keyLength: Int): ByteArray {
        require(iterations >= 1) { "iterations must be positive" }
        require(keyLength in 1..(HLEN * 255)) { "invalid key length" }
        return platformPbkdf2HmacSha256(password, salt, iterations, keyLength) ?: hmacSha256(password, salt, iterations, keyLength)
    }

    /** The platform implementation; null where there is none (or it refused the input). */
    fun platform(password: ByteArray, salt: ByteArray, iterations: Int, keyLength: Int): ByteArray? =
        platformPbkdf2HmacSha256(password, salt, iterations, keyLength)

    /** The common Kotlin implementation. The HMAC states are keyed once and copied per iteration. */
    fun hmacSha256(password: ByteArray, salt: ByteArray, iterations: Int, keyLength: Int): ByteArray {
        require(iterations >= 1) { "iterations must be positive" }
        require(keyLength in 1..(HLEN * 255)) { "invalid key length" }
        val key = if (password.size > BLOCK) SHA256().digest(password) else password
        val ipad = ByteArray(BLOCK) { i -> ((if (i < key.size) key[i].toInt() else 0) xor 0x36).toByte() }
        val opad = ByteArray(BLOCK) { i -> ((if (i < key.size) key[i].toInt() else 0) xor 0x5c).toByte() }
        val inner = SHA256().apply { update(ipad) }
        val outer = SHA256().apply { update(opad) }
        ipad.fill(0)
        opad.fill(0)

        fun mac(message: ByteArray, out: ByteArray) {
            val i = inner.copy()
            i.update(message)
            i.digestInto(out, 0)
            val o = outer.copy()
            o.update(out)
            o.digestInto(out, 0)
        }

        val result = ByteArray(keyLength)
        val u = ByteArray(HLEN)
        val t = ByteArray(HLEN)
        var block = 1
        var offset = 0
        while (offset < keyLength) {
            val first = salt + byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte())
            mac(first, u)
            u.copyInto(t)
            for (n in 1 until iterations) {
                mac(u, u)
                for (j in 0 until HLEN) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
            }
            val take = minOf(HLEN, keyLength - offset)
            t.copyInto(result, offset, 0, take)
            offset += take
            block++
        }
        u.fill(0)
        t.fill(0)
        return result
    }
}

/** True when [a] and [b] are equal; time depends only on the lengths, never on where they differ. */
internal fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
    var diff = a.size xor b.size
    for (i in 0 until maxOf(a.size, b.size)) {
        diff = diff or ((a.getOrElse(i) { 0 }.toInt() xor b.getOrElse(i) { 0 }.toInt()) and 0xff)
    }
    return diff == 0
}

/** PBKDF2-HMAC-SHA256 from the platform, or null when it has none or cannot take this input. */
internal expect fun platformPbkdf2HmacSha256(password: ByteArray, salt: ByteArray, iterations: Int, keyLength: Int): ByteArray?

/** Cryptographically secure random bytes from the platform (SecureRandom, SecRandomCopyBytes). */
internal expect fun secureRandomBytes(count: Int): ByteArray

/**
 * Milliseconds on a clock that never goes backwards and keeps counting while the device sleeps
 * (Android elapsedRealtime, Darwin CLOCK_MONOTONIC_RAW). Changing the wall clock cannot move it, so it
 * is what the app lock times the background with.
 */
expect fun monotonicMillis(): Long

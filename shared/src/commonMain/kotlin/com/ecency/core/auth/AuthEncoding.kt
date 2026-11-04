package com.ecency.core.auth

import com.ecency.core.legacy.CryptoJsAes
import org.kotlincrypto.hash.md.MD5
import kotlin.io.encoding.Base64

/**
 * The URL-safe base64 hive-uri and HiveSigner use (`b64uEnc` in the RN app and in hive-uri):
 * standard base64 of the UTF-8 bytes with `+` as `-`, `/` as `_` and `=` as `.`.
 */
object B64u {
    fun encode(text: String): String = Base64.Default.encode(text.encodeToByteArray())
        .map { when (it) { '+' -> '-'; '/' -> '_'; '=' -> '.'; else -> it } }.joinToString("")

    /** @throws IllegalArgumentException when [value] is not b64u or not UTF-8. */
    fun decode(value: String): String = Base64.Default
        .decode(value.map { when (it) { '-' -> '+'; '_' -> '/'; '.' -> '='; else -> it } }.joinToString(""))
        .decodeToString(throwOnInvalidSequence = true)
}

/** JavaScript number text for the values the auth messages carry. */
internal object JsNumber {
    /**
     * `String(millis / 1000)` as JavaScript prints it, e.g. 1759800000123 is "1759800000.123" and
     * 1759800000000 is "1759800000". Signed HiveSigner messages carry this exact text, and the receiver
     * re-serializes the parsed number, so it must match JS byte for byte.
     */
    fun secondsFromMillis(millis: Long): String {
        require(millis >= 0) { "negative time" }
        val whole = millis / 1000
        val fraction = (millis % 1000).toString().padStart(3, '0').trimEnd('0')
        return if (fraction.isEmpty()) whole.toString() else "$whole.$fraction"
    }
}

/**
 * crypto-js `AES.encrypt(text, passphrase)` / `AES.decrypt`: OpenSSL "Salted__" format, key and IV from
 * EVP_BytesToKey (MD5, one round), AES-256-CBC with PKCS#7 padding. HiveAuth encrypts every payload this
 * way with the session's auth key.
 */
object CryptoJsCipher {
    private val SALTED = "Salted__".encodeToByteArray()

    fun encrypt(plain: String, passphrase: String): String = encrypt(plain, passphrase, secureRandomBytes(8))

    internal fun encrypt(plain: String, passphrase: String, salt: ByteArray): String {
        require(salt.size == 8) { "salt must be 8 bytes" }
        val derived = evpBytesToKey(passphrase.encodeToByteArray(), salt)
        val cipher = aesCbcEncrypt(derived.copyOf(32), derived.copyOfRange(32, 48), plain.encodeToByteArray())
        return Base64.Default.encode(SALTED + salt + cipher)
    }

    /** @throws IllegalArgumentException for anything that is not a ciphertext under [passphrase]. */
    fun decrypt(openSslBase64: String, passphrase: String): String = try {
        CryptoJsAes.decrypt(openSslBase64, passphrase)
    } catch (e: IllegalArgumentException) {
        throw e
    } catch (e: Exception) {
        throw IllegalArgumentException("decryption failed", e)
    }

    private fun evpBytesToKey(password: ByteArray, salt: ByteArray): ByteArray {
        var derived = ByteArray(0)
        var block = ByteArray(0)
        while (derived.size < 48) {
            block = MD5().digest(block + password + salt)
            derived += block
        }
        return derived.copyOf(48)
    }
}

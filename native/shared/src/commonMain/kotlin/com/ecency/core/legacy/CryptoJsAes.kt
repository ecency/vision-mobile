package com.ecency.core.legacy

import org.kotlincrypto.hash.md.MD5
import kotlin.io.encoding.Base64

/**
 * Decrypts crypto-js `AES.encrypt(text, passphrase)` output: OpenSSL "Salted__" format,
 * key and IV from EVP_BytesToKey (MD5, one iteration), AES-256-CBC with PKCS#7 padding.
 */
internal object CryptoJsAes {
    private val SALTED = "Salted__".encodeToByteArray()

    fun decrypt(openSslBase64: String, passphrase: String): String {
        val raw = Base64.decode(openSslBase64)
        require(raw.size > 16 && raw.copyOf(8).contentEquals(SALTED)) { "not a crypto-js ciphertext" }
        val salt = raw.copyOfRange(8, 16)
        val derived = evpBytesToKey(passphrase.encodeToByteArray(), salt, 48)
        val plain = aesCbcDecrypt(derived.copyOf(32), derived.copyOfRange(32, 48), raw.copyOfRange(16, raw.size))
        return plain.decodeToString(throwOnInvalidSequence = true)
    }

    private fun evpBytesToKey(password: ByteArray, salt: ByteArray, length: Int): ByteArray {
        var derived = ByteArray(0)
        var block = ByteArray(0)
        while (derived.size < length) {
            block = MD5().digest(block + password + salt)
            derived += block
        }
        return derived.copyOf(length)
    }
}

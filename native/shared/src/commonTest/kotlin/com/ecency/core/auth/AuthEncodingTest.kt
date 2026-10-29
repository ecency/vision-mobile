package com.ecency.core.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class AuthEncodingTest {
    @Test
    fun b64uMatchesTheRnEncoder() {
        AuthGoldenVectors.b64u.forEach { (plain, encoded) ->
            assertEquals(encoded, B64u.encode(plain), plain)
            assertEquals(plain, B64u.decode(encoded))
        }
    }

    @Test
    fun b64uRejectsGarbage() {
        assertFailsWith<IllegalArgumentException> { B64u.decode("!!!") }
    }

    @Test
    fun secondsPrintLikeJavaScript() {
        assertEquals("1759800000.123", JsNumber.secondsFromMillis(1759800000123))
        assertEquals("1759800000", JsNumber.secondsFromMillis(1759800000000))
        assertEquals("1759800000.1", JsNumber.secondsFromMillis(1759800000100))
        assertEquals("1759800000.05", JsNumber.secondsFromMillis(1759800000050))
        assertEquals("0.001", JsNumber.secondsFromMillis(1))
    }

    @Test
    fun decryptsCryptoJsCiphertexts() {
        (AuthGoldenVectors.cryptoJsDecrypt + AuthGoldenVectors.cryptoJsFixedSalt).forEach {
            assertEquals(it.plain, CryptoJsCipher.decrypt(it.cipher, it.passphrase))
        }
    }

    @Test
    fun encryptsExactlyLikeCryptoJsForAGivenSalt() {
        val salt = AuthGoldenVectors.FIXED_SALT_HEX.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        AuthGoldenVectors.cryptoJsFixedSalt.forEach {
            assertEquals(it.cipher, CryptoJsCipher.encrypt(it.plain, it.passphrase, salt), it.plain)
        }
    }

    @Test
    fun randomSaltRoundTripsAndDiffers() {
        val a = CryptoJsCipher.encrypt("payload", AuthGoldenVectors.AUTH_KEY)
        val b = CryptoJsCipher.encrypt("payload", AuthGoldenVectors.AUTH_KEY)
        assertNotEquals(a, b)
        assertEquals("payload", CryptoJsCipher.decrypt(a, AuthGoldenVectors.AUTH_KEY))
    }

    @Test
    fun wrongPassphraseOrGarbageFails() {
        val c = AuthGoldenVectors.cryptoJsDecrypt.first()
        assertFailsWith<IllegalArgumentException> { CryptoJsCipher.decrypt(c.cipher, "another-key") }
        assertFailsWith<IllegalArgumentException> { CryptoJsCipher.decrypt("bm90IGEgY2lwaGVy", c.passphrase) }
    }
}

package com.ecency.core.auth

import com.ecency.core.hive.KeyRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HiveSignerMessagesTest {
    private val user = AuthGoldenVectors.USER
    private val key = TestKeys.key(KeyRole.POSTING)

    @Test
    fun messagesAndCodesMatchTheRnHelperByteForByte() {
        AuthGoldenVectors.messages.forEach { v ->
            assertEquals(v.message, HiveSignerMessages.message(v.type, v.audience, user, v.millis))
            val built = if (v.type == "code") HiveSignerMessages.code(user, key, v.millis)
            else HiveSignerMessages.loginProof(user, key, v.audience!!, v.millis)
            assertEquals(v.code, built, v.type)
        }
    }

    @Test
    fun sdkSignaturesRecoverToThePostingKey() {
        AuthGoldenVectors.messages.forEach { v ->
            assertEquals(AuthGoldenVectors.POSTING_PUBLIC, HiveSignerMessages.recoverSigner(v.message, v.sdkSignature).toString())
        }
    }

    @Test
    fun decodesACode() {
        val v = AuthGoldenVectors.messages.first { it.audience != null }
        val d = HiveSignerMessages.decode(v.code)
        assertEquals("login", d.type)
        assertEquals("ecency.app", d.app)
        assertEquals("honeyback://hive", d.audience)
        assertEquals(listOf(user), d.authors)
        assertEquals(v.millis / 1000.0, d.timestamp)
        assertEquals(v.message, d.unsignedJson)
        assertEquals(AuthGoldenVectors.POSTING_PUBLIC, HiveSignerMessages.recoverSigner(d.unsignedJson, d.signatures.single()).toString())
        assertFailsWith<AuthException.InvalidLink> { HiveSignerMessages.decode("%%%") }
        assertFailsWith<AuthException.InvalidLink> { HiveSignerMessages.decode(B64u.encode("[1]")) }
    }

    @Test
    fun hiveAuthChallengeAndCodeMatchRn() {
        val challenge = HiveSignerMessages.hiveAuthChallenge(user, AuthGoldenVectors.HIVE_AUTH_MILLIS)
        assertEquals(AuthGoldenVectors.HIVE_AUTH_CHALLENGE, challenge)
        assertEquals(AuthGoldenVectors.HIVE_AUTH_SIGNATURE, HiveSignerMessages.sign(challenge, key))
        assertEquals(AuthGoldenVectors.HIVE_AUTH_CODE, HiveSignerMessages.hiveAuthCode(challenge, AuthGoldenVectors.HIVE_AUTH_SIGNATURE))
        assertEquals("refresh", HiveSignerMessages.decode(AuthGoldenVectors.HIVE_AUTH_CODE, standardBase64 = true).type)
    }
}

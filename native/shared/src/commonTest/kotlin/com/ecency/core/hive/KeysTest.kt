package com.ecency.core.hive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class KeysTest {
    @Test
    fun seedDerivesTheSdkKey() {
        val key = PrivateKey.fromSeed(GoldenVectors.SEED)
        assertEquals(GoldenVectors.WIF, key.toWif())
        assertEquals(GoldenVectors.PUBLIC_KEY, key.publicKey().toString())
    }

    @Test
    fun wifRoundTrips() {
        assertEquals(GoldenVectors.WIF, PrivateKey.fromWif(GoldenVectors.WIF).toWif())
    }

    @Test
    fun loginDerivationMatchesTheSdkForEveryRole() {
        GoldenVectors.logins.forEach { v ->
            val role = KeyRole.entries.first { it.value == v.role }
            val key = PrivateKey.fromLogin(GoldenVectors.LOGIN_USER, GoldenVectors.LOGIN_PASSWORD, role)
            assertEquals(v.wif, key.toWif(), v.role)
            assertEquals(v.publicKey, key.publicKey().toString(), v.role)
        }
    }

    @Test
    fun publicKeyStringRoundTrips() {
        assertEquals(GoldenVectors.PUBLIC_KEY, PublicKey.fromString(GoldenVectors.PUBLIC_KEY).toString())
    }

    @Test
    fun corruptedKeysAreRejected() {
        val wif = GoldenVectors.WIF
        val badWif = wif.dropLast(1) + if (wif.last() == 'a') 'b' else 'a'
        assertFailsWith<IllegalArgumentException> { PrivateKey.fromWif(badWif) }
        val pub = GoldenVectors.PUBLIC_KEY
        val badPub = pub.dropLast(1) + if (pub.last() == 'a') 'b' else 'a'
        assertFailsWith<IllegalArgumentException> { PublicKey.fromString(badPub) }
        assertFailsWith<IllegalArgumentException> { PublicKey.fromString("TST" + pub.drop(3)) }
    }

    @Test
    fun privateKeyNeverPrintsItself() {
        assertFalse(PrivateKey.fromWif(GoldenVectors.WIF).toString().contains(GoldenVectors.WIF))
    }
}

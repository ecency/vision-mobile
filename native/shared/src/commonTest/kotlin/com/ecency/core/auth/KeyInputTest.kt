package com.ecency.core.auth

import com.ecency.core.hive.KeyRole
import com.ecency.core.hive.PrivateKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * RN #3600: keys and passwords copied from chat apps carry spaces, newlines, NBSP and zero-width characters.
 * Every key entry goes through [KeyInput.secret]; a WIF with any of them is still that WIF, never a master password.
 */
class KeyInputTest {
    private val user = AuthGoldenVectors.USER
    private val wif = TestKeys.wif(KeyRole.ACTIVE)
    private val pub = TestKeys.pub(KeyRole.ACTIVE)

    /** The issue's table plus the other invisible characters chat apps and editors add. */
    private val dirty = mapOf(
        "trailing space" to "$wif ",
        "trailing newline" to "$wif\n",
        "trailing CRLF" to "$wif\r\n",
        "leading space" to " $wif",
        "leading tab" to "\t$wif",
        "trailing NBSP" to "$wif ",
        "leading NBSP" to " $wif",
        "trailing zero-width space" to "$wif​",
        "leading zero-width space" to "​$wif",
        "zero-width non-joiner" to "$wif‌",
        "zero-width joiner" to "$wif‍",
        "byte order mark" to "﻿$wif",
        "word joiner" to "$wif⁠",
        "narrow NBSP" to "$wif ",
        "left-to-right mark" to "‎$wif‏",
        "mixed" to " ​ $wif⁠\n ",
    )

    @Test
    fun aWifWithAnyExtraCharacterNormalisesToTheCleanWif() {
        dirty.forEach { (case, input) -> assertEquals(wif, KeyInput.secret(input), case) }
    }

    @Test
    fun aWifWithAnyExtraCharacterSignsInAsThatKeyNotAsAMasterPassword() {
        val clean = KeyLogin.classify(user, wif, TestKeys.authorities())
        dirty.forEach { (case, input) ->
            val r = KeyLogin.classify(user, input, TestKeys.authorities())
            assertFalse(r.isMasterPassword, case)
            assertEquals(clean.keys, r.keys, case)
            assertEquals(clean.authType, r.authType, case)
            assertEquals(clean.keys, KeyLogin.matchForImport(user, input, TestKeys.authorities()).keys, case)
            assertTrue(KeyLogin.isWif(input), case)
            assertTrue(KeyLogin.keyMatchesRole(input, KeyRole.ACTIVE, TestKeys.authorities()), case)
        }
    }

    @Test
    fun aPublicKeyWithAnyExtraCharacterIsStillNamedAsAPublicKey() {
        listOf("$pub​", "﻿$pub", "$pub ", " $pub\n", "⁠$pub‍").forEach { input ->
            assertTrue(KeyLogin.isPublicKey(input))
            assertFailsWith<AuthException.PublicKeyGiven> { KeyLogin.classify(user, input, TestKeys.authorities()) }
            assertFailsWith<AuthException.PublicKeyGiven> { KeyLogin.matchForImport(user, input, TestKeys.authorities()) }
        }
    }

    @Test
    fun aMasterPasswordWithExtraEdgeCharactersDerivesTheSameKeys() {
        val clean = KeyLogin.classify(user, AuthGoldenVectors.PASSWORD, TestKeys.authorities())
        listOf("${AuthGoldenVectors.PASSWORD} ", "${AuthGoldenVectors.PASSWORD}\n", "​${AuthGoldenVectors.PASSWORD} ").forEach { input ->
            val r = KeyLogin.classify(user, input, TestKeys.authorities())
            assertTrue(r.isMasterPassword)
            assertEquals(clean.keys, r.keys)
        }
    }

    /** A key or a generated password ("P" + WIF) carries a checksum, so characters inside it can only be copy noise. */
    @Test
    fun charactersInsideAKeyOrAGeneratedPasswordAreDroppedWhenTheChecksumThenHolds() {
        assertEquals(wif, KeyInput.secret(wif.take(20) + "​" + wif.drop(20)))
        assertEquals(wif, KeyInput.secret(wif.take(10) + " " + wif.drop(10).take(20) + "­" + wif.drop(30)))
        assertEquals(pub, KeyInput.secret(pub.take(25) + "​" + pub.drop(25)))
        val generated = "P" + PrivateKey.fromSeed("ecency-native-key-input-test-throwaway").toWif()
        assertEquals(generated, KeyInput.secret(generated.take(30) + "​" + generated.drop(30)))
        assertEquals(generated, KeyInput.secret(" " + generated.take(30) + "⁠" + generated.drop(30) + "\n"))
    }

    /** A password the user chose may hold spaces or any other character: only its edges are cleaned. */
    @Test
    fun charactersInsideAChosenPasswordAreKept() {
        assertEquals("my long pass phrase", KeyInput.secret("  my long pass phrase​\n"))
        assertEquals("pass​word", KeyInput.secret(" pass​word "))
        assertEquals("P5" + "x".repeat(20) + " y", KeyInput.secret("P5" + "x".repeat(20) + " y"))
        // a broken WIF stays broken: nothing inside it is touched, so the check still calls it an invalid key
        val broken = wif.dropLast(1) + (if (wif.last() == 'a') 'b' else 'a')
        assertEquals(broken.take(20) + "​" + broken.drop(20), KeyInput.secret(broken.take(20) + "​" + broken.drop(20)))
        assertFailsWith<AuthException.InvalidKey> { KeyLogin.classify(user, "$broken​", TestKeys.authorities()) }
    }

    @Test
    fun onlyInvisibleInputIsEmpty() {
        assertEquals("", KeyInput.secret(" ​ ﻿\n"))
        assertFailsWith<AuthException.WrongPassword> { KeyLogin.classify(user, "​ ", TestKeys.authorities()) }
    }

    @Test
    fun usernamesLoseTheSameEdgeCharacters() {
        assertEquals(user, KeyLogin.normalizeUsername("​@Spike-User "))
        assertEquals(user, KeyLogin.normalizeUsername("﻿ spike-user⁠\n"))
        assertEquals(user, KeyLogin.classify("​@Spike-User", wif, TestKeys.authorities()).username)
    }

    /** The login button: invisible-only input does not count as filled (review of RN #3600). */
    @Test
    fun theLoginFormCountsNormalisedInput() {
        assertFalse(KeyLogin.formFilled("\u200B\u200B", wif))
        assertFalse(KeyLogin.formFilled("ab", "\u200B\u00A0\uFEFF"))
        assertFalse(KeyLogin.formFilled(" a\u2060", "x\u200B"))
        assertTrue(KeyLogin.formFilled("ab", "\u200B$wif"))
        assertTrue(KeyLogin.formFilled("@a", "pw"), "RN counts the typed username, @ included")
    }
}

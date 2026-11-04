package com.ecency.core.auth

import com.ecency.core.hive.GoldenVectors
import com.ecency.core.hive.KeyRole
import com.ecency.core.secure.AuthType
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeyLoginTest {
    private val user = AuthGoldenVectors.USER

    @Test
    fun masterPasswordDerivesTheSdkKeys() {
        GoldenVectors.logins.forEach { v ->
            val role = KeyRole.entries.first { it.value == v.role }
            assertEquals(v.wif, TestKeys.wif(role))
            assertEquals(v.publicKey, TestKeys.pub(role))
        }
    }

    @Test
    fun masterPasswordGivesPostingActiveAndMemoAndNothingElse() {
        val r = KeyLogin.classify(" @Spike-User ", AuthGoldenVectors.PASSWORD, TestKeys.authorities())
        assertEquals(user, r.username)
        assertEquals(AuthType.MASTER_PASSWORD, r.authType)
        assertEquals(setOf(KeyRole.POSTING, KeyRole.ACTIVE, KeyRole.MEMO), r.keys.keys, "the derived owner key is not part of the result")
        r.keys.forEach { (role, wif) -> assertEquals(TestKeys.wif(role), wif) }
        assertFalse(TestKeys.wif(KeyRole.OWNER) in r.keys.values)
        assertTrue(r.isMasterPassword)
        assertEquals(KeyRole.entries.toSet(), r.matchedRoles)
        assertEquals(KeyRole.POSTING, r.signingRole, "the session code is signed with the least powerful key that works")
        assertFalse(AuthGoldenVectors.PASSWORD in r.toString())
    }

    @Test
    fun masterPasswordSignsWithThePostingKeyWhateverTheOwnerKeyIs() {
        val r = KeyLogin.classify(user, AuthGoldenVectors.PASSWORD, TestKeys.authorities(owner = listOf(TestKeys.stranger.publicKey().toString())))
        assertEquals(AuthType.MASTER_PASSWORD, r.authType)
        assertEquals(KeyRole.POSTING, r.signingRole)
    }

    @Test
    fun postingWifStoresOnlyThePostingKey() {
        val r = KeyLogin.classify(user, " ${TestKeys.wif(KeyRole.POSTING)}\n", TestKeys.authorities())
        assertEquals(AuthType.POSTING_KEY, r.authType)
        assertEquals(setOf(KeyRole.POSTING), r.keys.keys)
        assertFalse(r.isMasterPassword)
        assertEquals(KeyRole.POSTING, r.signingRole)
    }

    @Test
    fun activeWifIsActiveKeyLogin() {
        val r = KeyLogin.classify(user, TestKeys.wif(KeyRole.ACTIVE), TestKeys.authorities())
        assertEquals(AuthType.ACTIVE_KEY, r.authType)
        assertEquals(setOf(KeyRole.ACTIVE), r.keys.keys)
    }

    @Test
    fun anOwnerWifAloneIsRefusedAtSignInAndAtImport() {
        val e = assertFailsWith<AuthException.OwnerKeyOnly> { KeyLogin.classify(user, TestKeys.wif(KeyRole.OWNER), TestKeys.authorities()) }
        assertFalse(e.fromMasterPassword)
        assertFailsWith<AuthException.OwnerKeyOnly> { KeyLogin.matchForImport(user, TestKeys.wif(KeyRole.OWNER), TestKeys.authorities()) }
        assertFalse(TestKeys.wif(KeyRole.OWNER) in e.toString() + e.message)
    }

    @Test
    fun aKeyThatIsBothOwnerAndActiveIsKeptAsTheActiveKeyOnly() {
        val both = TestKeys.pub(KeyRole.OWNER)
        val r = KeyLogin.classify(user, TestKeys.wif(KeyRole.OWNER), TestKeys.authorities(active = listOf(both)))
        assertEquals(setOf(KeyRole.ACTIVE), r.keys.keys, "stored for the active role; there is no owner slot")
        assertEquals(AuthType.ACTIVE_KEY, r.authType)
        assertEquals(KeyRole.ACTIVE, r.signingRole)
    }

    @Test
    fun aMasterPasswordThatOnlyMatchesTheOwnerKeyIsRefused() {
        val stranger = TestKeys.stranger.publicKey().toString()
        val chain = TestKeys.authorities(active = listOf(stranger), posting = listOf(stranger), memo = stranger)
        val e = assertFailsWith<AuthException.OwnerKeyOnly> { KeyLogin.classify(user, AuthGoldenVectors.PASSWORD, chain) }
        assertTrue(e.fromMasterPassword)
        assertFailsWith<AuthException.OwnerKeyOnly> { KeyLogin.matchForImport(user, AuthGoldenVectors.PASSWORD, chain) }
    }

    @Test
    fun aMasterPasswordKeepsOnlyTheKeysTheChainStillHas() {
        // the posting key was changed since: the password's posting key is derived but not verified, so not kept
        val chain = TestKeys.authorities(posting = listOf(TestKeys.stranger.publicKey().toString()))
        val r = KeyLogin.classify(user, AuthGoldenVectors.PASSWORD, chain)
        assertEquals(setOf(KeyRole.ACTIVE, KeyRole.MEMO), r.keys.keys)
        assertEquals(AuthType.MASTER_PASSWORD, r.authType)
    }

    @Test
    fun aKeyOnSeveralRolesTakesRnsLastMatchAndKeepsEveryRole() {
        val shared = TestKeys.pub(KeyRole.ACTIVE)
        val r = KeyLogin.classify(user, TestKeys.wif(KeyRole.ACTIVE), TestKeys.authorities(posting = listOf(shared)))
        // RN order active, memo, owner, posting: the last match wins.
        assertEquals(AuthType.POSTING_KEY, r.authType)
        assertEquals(setOf(KeyRole.ACTIVE, KeyRole.POSTING), r.keys.keys)
        assertEquals(KeyRole.POSTING, r.signingRole)
    }

    @Test
    fun memoOnlyKeyIsRefused() {
        assertFailsWith<AuthException.MemoKeyOnly> { KeyLogin.classify(user, TestKeys.wif(KeyRole.MEMO), TestKeys.authorities()) }
    }

    @Test
    fun wrongPasswordAndForeignKeyAreTyped() {
        assertFailsWith<AuthException.WrongPassword> { KeyLogin.classify(user, "P5NotThePassword", TestKeys.authorities()) }
        assertFailsWith<AuthException.WrongPassword> { KeyLogin.classify(user, "   ", TestKeys.authorities()) }
        assertFailsWith<AuthException.KeyNotOnAccount> { KeyLogin.classify(user, TestKeys.stranger.toWif(), TestKeys.authorities()) }
    }

    @Test
    fun aPastedPublicKeyIsNamedAtSignInAndImport() {
        val pub = TestKeys.pub(KeyRole.ACTIVE)
        assertFailsWith<AuthException.PublicKeyGiven> { KeyLogin.classify(user, pub, TestKeys.authorities()) }
        assertFailsWith<AuthException.PublicKeyGiven> { KeyLogin.classify(user, "  $pub\n", TestKeys.authorities()) }
        assertFailsWith<AuthException.PublicKeyGiven> { KeyLogin.matchForImport(user, pub, TestKeys.authorities()) }
        assertTrue(KeyLogin.isPublicKey(pub))
        assertFalse(KeyLogin.isPublicKey(TestKeys.wif(KeyRole.ACTIVE)))
        assertFalse(KeyLogin.isPublicKey("STMshort"))
        // review L10: the checksum decides; a password shaped like a key is still a password
        val wrongChecksum = pub.dropLast(1) + (if (pub.last() == 'a') 'b' else 'a')
        assertFalse(KeyLogin.isPublicKey(wrongChecksum))
        assertFalse(KeyLogin.isPublicKey("STM" + "1".repeat(50)))
        assertEquals(listOf(KeyRole.ACTIVE), KeyLogin.rolesOf(pub, TestKeys.authorities()))
        assertEquals(com.ecency.core.app.AppError.PUBLIC_KEY_GIVEN, com.ecency.core.app.AppError.from(AuthException.PublicKeyGiven()).message)
    }

    @Test
    fun wifWithBadChecksumIsInvalidKey() {
        val wif = TestKeys.wif(KeyRole.POSTING)
        val broken = wif.dropLast(1) + (if (wif.last() == 'a') 'b' else 'a')
        assertFalse(KeyLogin.isWif(broken))
        assertFailsWith<AuthException.InvalidKey> { KeyLogin.classify(user, broken, TestKeys.authorities()) }
    }

    @Test
    fun invalidUsernameIsRefused() {
        assertFailsWith<AuthException.InvalidUsername> { KeyLogin.classify("ab", AuthGoldenVectors.PASSWORD, TestKeys.authorities()) }
        assertEquals("good-karma", KeyLogin.normalizeUsername(" @@Good-Karma "))
    }

    @Test
    fun keyMatchesRoleChecksTheChain() {
        val chain = TestKeys.authorities()
        assertTrue(KeyLogin.keyMatchesRole(TestKeys.wif(KeyRole.ACTIVE), KeyRole.ACTIVE, chain))
        assertFalse(KeyLogin.keyMatchesRole(TestKeys.wif(KeyRole.POSTING), KeyRole.ACTIVE, chain))
        assertFalse(KeyLogin.keyMatchesRole("not a key", KeyRole.ACTIVE, chain))
    }

    @Test
    fun parsesGetAccountsJson() {
        val json = Json.parseToJsonElement(
            """{"name":"spike-user","owner":{"key_auths":[["STM1",1]]},"active":{"key_auths":[["STM2",1]]},
            "posting":{"account_auths":[["ecency.app",1]],"key_auths":[["STM3",1],["STM4",1]]},"memo_key":"STM5"}""",
        )
        val a = ChainAuthorities.fromJson(json)!!
        assertEquals(listOf("STM1"), a.owner)
        assertEquals(listOf("STM3", "STM4"), a.posting)
        assertEquals(listOf("STM5"), a.keysFor(KeyRole.MEMO))
        assertTrue(a.grantsPostingTo())
        assertNull(ChainAuthorities.fromJson(Json.parseToJsonElement("""{"owner":{}}""")))
    }

    // ---- weights and thresholds ----

    private fun weighted(thresholds: Map<KeyRole, Int>, weights: Map<KeyRole, Int>) = TestKeys.authorities().copy(
        thresholds = thresholds,
        weights = weights.mapValues { (role, weight) -> mapOf(TestKeys.pub(role) to weight) },
    )

    @Test
    fun aKeyBelowItsRolesThresholdIsRefusedAtLogin() {
        val chain = weighted(mapOf(KeyRole.ACTIVE to 2), mapOf(KeyRole.ACTIVE to 1))
        assertFailsWith<AuthException.KeyBelowThreshold> { KeyLogin.classify(AuthGoldenVectors.USER, TestKeys.wif(KeyRole.ACTIVE), chain) }
        // the auth upgrade's own behaviour is tested through SigningModel (SigningModelTest); this is the rule it calls
        assertFalse(KeyLogin.keyMatchesRole(TestKeys.wif(KeyRole.ACTIVE), KeyRole.ACTIVE, chain))
        // the posting key of the same account still signs alone
        assertEquals(setOf(KeyRole.POSTING), KeyLogin.classify(AuthGoldenVectors.USER, TestKeys.wif(KeyRole.POSTING), chain).matchedRoles)
    }

    @Test
    fun aKeyAtOrAboveTheThresholdCounts() {
        val chain = weighted(mapOf(KeyRole.ACTIVE to 2, KeyRole.POSTING to 3), mapOf(KeyRole.ACTIVE to 2, KeyRole.POSTING to 5))
        assertEquals(setOf(KeyRole.ACTIVE), KeyLogin.classify(AuthGoldenVectors.USER, TestKeys.wif(KeyRole.ACTIVE), chain).matchedRoles)
        assertTrue(KeyLogin.keyMatchesRole(TestKeys.wif(KeyRole.POSTING), KeyRole.POSTING, chain))
    }

    @Test
    fun aMasterPasswordOnlyCountsForTheRolesItCanSignAlone() {
        val chain = weighted(mapOf(KeyRole.OWNER to 2, KeyRole.ACTIVE to 2), mapOf(KeyRole.OWNER to 1, KeyRole.ACTIVE to 1))
        val result = KeyLogin.classify(AuthGoldenVectors.USER, AuthGoldenVectors.PASSWORD, chain)
        assertEquals(setOf(KeyRole.POSTING, KeyRole.MEMO), result.matchedRoles)
        assertEquals(KeyRole.POSTING, result.signingRole, "the session code is signed with a key that can sign")
        // when no role of the password can sign alone, it is refused as such, not as a wrong password
        val all = weighted(mapOf(KeyRole.OWNER to 2, KeyRole.ACTIVE to 2, KeyRole.POSTING to 2), mapOf(KeyRole.OWNER to 1, KeyRole.ACTIVE to 1, KeyRole.POSTING to 1))
            .copy(memo = TestKeys.stranger.publicKey().toString())
        assertFailsWith<AuthException.KeyBelowThreshold> { KeyLogin.classify(AuthGoldenVectors.USER, AuthGoldenVectors.PASSWORD, all) }
    }

    @Test
    fun weightsAndThresholdsAreReadFromTheChainsJson() {
        val pub = TestKeys.pub(KeyRole.ACTIVE)
        val other = TestKeys.stranger.publicKey().toString()
        val json = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"name":"${AuthGoldenVectors.USER}","active":{"weight_threshold":3,"account_auths":[],"key_auths":[["$pub",2],["$other",3]]},""" +
                """"posting":{"account_auths":[],"key_auths":[["$pub",1]]},"owner":{"weight_threshold":1,"key_auths":[["$pub","x"]]},"memo_key":"$pub"}""",
        )
        val chain = ChainAuthorities.fromJson(json)!!
        assertEquals(listOf(pub, other), chain.active)
        assertFalse(chain.canSignAlone(KeyRole.ACTIVE, pub))
        assertTrue(chain.canSignAlone(KeyRole.ACTIVE, other))
        assertFalse(chain.canSignAlone(KeyRole.POSTING, pub), "no readable threshold: not trusted to sign alone")
        assertFalse(chain.canSignAlone(KeyRole.OWNER, pub), "no readable weight: counts as 0")
        assertTrue(chain.canSignAlone(KeyRole.MEMO, pub), "the memo key has no weights")
        assertFalse(chain.canSignAlone(KeyRole.ACTIVE, "STM-not-on-the-account"))
    }

    @Test
    fun aThresholdOrWeightOfZeroOrLessNeverSignsAlone() {
        for ((threshold, weight) in listOf(0 to 0, 0 to 1, -1 to 1, 1 to 0, 1 to -5, -2 to -1)) {
            val chain = weighted(mapOf(KeyRole.ACTIVE to threshold), mapOf(KeyRole.ACTIVE to weight))
            assertFalse(chain.canSignAlone(KeyRole.ACTIVE, TestKeys.pub(KeyRole.ACTIVE)), "threshold $threshold, weight $weight")
            assertFailsWith<AuthException.KeyBelowThreshold> { KeyLogin.classify(user, TestKeys.wif(KeyRole.ACTIVE), chain) }
        }
        assertTrue(weighted(mapOf(KeyRole.ACTIVE to 1), mapOf(KeyRole.ACTIVE to 1)).canSignAlone(KeyRole.ACTIVE, TestKeys.pub(KeyRole.ACTIVE)))
    }

    @Test
    fun theSessionCodeIsSignedWithPostingAndWithActiveOnlyWhenThereIsNoPostingKey() {
        assertEquals(KeyRole.POSTING, KeyLogin.classify(user, AuthGoldenVectors.PASSWORD, TestKeys.authorities()).signingRole)
        assertEquals(KeyRole.ACTIVE, KeyLogin.classify(user, TestKeys.wif(KeyRole.ACTIVE), TestKeys.authorities()).signingRole)
        val both = KeyLogin.classify(user, TestKeys.wif(KeyRole.ACTIVE), TestKeys.authorities(posting = listOf(TestKeys.pub(KeyRole.ACTIVE))))
        assertEquals(KeyRole.POSTING, both.signingRole)
    }

    @Test
    fun aMasterPasswordSaysWhichSigningRolesItNoLongerMatches() {
        val stranger = TestKeys.stranger.publicKey().toString()
        assertEquals(emptySet(), KeyLogin.classify(user, AuthGoldenVectors.PASSWORD, TestKeys.authorities()).missingSigningRoles)
        assertEquals(setOf(KeyRole.POSTING), KeyLogin.classify(user, AuthGoldenVectors.PASSWORD, TestKeys.authorities(posting = listOf(stranger))).missingSigningRoles)
        assertEquals(setOf(KeyRole.ACTIVE), KeyLogin.classify(user, AuthGoldenVectors.PASSWORD, TestKeys.authorities(active = listOf(stranger))).missingSigningRoles)
        // a private key is not a password: nothing is "missing"
        assertEquals(emptySet(), KeyLogin.classify(user, TestKeys.wif(KeyRole.POSTING), TestKeys.authorities()).missingSigningRoles)
    }

    @Test
    fun aKeySharedWithTheOwnerRoleIsFlaggedAndOneSwitchRefusesIt() {
        val chain = TestKeys.authorities(active = listOf(TestKeys.pub(KeyRole.OWNER)))
        val r = KeyLogin.classify(user, TestKeys.wif(KeyRole.OWNER), chain)
        assertTrue(r.sharesOwnerKey)
        assertEquals(setOf(KeyRole.ACTIVE), r.keys.keys)
        assertFalse(KeyLogin.classify(user, TestKeys.wif(KeyRole.ACTIVE), TestKeys.authorities()).sharesOwnerKey)
        assertFalse(KeyLogin.classify(user, AuthGoldenVectors.PASSWORD, TestKeys.authorities()).sharesOwnerKey, "a password derives a key per role")
        assertFalse(KeyLogin.refuseKeySharedWithOwner, "the default: kept, with a warning")
        KeyLogin.refuseKeySharedWithOwner = true
        try {
            assertFailsWith<AuthException.KeySharedWithOwner> { KeyLogin.classify(user, TestKeys.wif(KeyRole.OWNER), chain) }
            assertFailsWith<AuthException.KeySharedWithOwner> { KeyLogin.matchForImport(user, TestKeys.wif(KeyRole.OWNER), chain) }
            // keys that are not the owner key, and passwords, are unaffected
            assertEquals(setOf(KeyRole.ACTIVE), KeyLogin.classify(user, TestKeys.wif(KeyRole.ACTIVE), TestKeys.authorities()).keys.keys)
            assertTrue(KeyLogin.classify(user, AuthGoldenVectors.PASSWORD, TestKeys.authorities()).isMasterPassword)
        } finally {
            KeyLogin.refuseKeySharedWithOwner = false
        }
    }
}

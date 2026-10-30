package com.ecency.core.secure

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeyVaultTest {
    private val store = InMemorySecureStore()
    private val vault = KeyVault(store)

    // Throwaway values; none of these is a real key or token.
    private val full = VaultAccount(
        username = "vault-full",
        authType = AuthType.MASTER_PASSWORD,
        postingKey = "dummy-posting-wif",
        activeKey = "dummy-active-wif",
        memoKey = "dummy-memo-wif",
        accessToken = "dummy-access-token",
        hiveAuthKey = "dummy-has-key",
        hiveAuthToken = "dummy-has-token",
        hiveAuthExpiry = 1_800_000_000_000L,
    )
    private val signer = VaultAccount("vault.signer", AuthType.HIVE_SIGNER, accessToken = "dummy-hs-token")

    @Test
    fun accountsRoundTripEveryField() {
        vault.save(full)
        vault.save(signer)
        assertEquals(full, KeyVault(store).account("vault-full"))
        assertEquals(signer, KeyVault(store).account("vault.signer"))
        assertEquals(listOf("vault-full", "vault.signer"), vault.usernames())
        assertEquals(listOf(full, signer), vault.accounts())
        assertNull(vault.account("nobody"))
    }

    @Test
    fun savingAgainReplacesTheSingleEntry() {
        vault.save(full)
        vault.save(full.copy(postingKey = "dummy-rotated-wif"))
        assertEquals(listOf("vault-full"), vault.usernames())
        assertEquals("dummy-rotated-wif", vault.account("vault-full")?.postingKey)
    }

    @Test
    fun metaIsNotAnAccount() {
        vault.save(signer)
        vault.setCurrentUsername("vault.signer")
        vault.setPinLockEnabled(true)
        vault.markLegacyImported()
        assertEquals(listOf("vault.signer"), vault.usernames())
        assertEquals("vault.signer", vault.currentUsername())
        assertTrue(vault.pinLockEnabled())
        assertTrue(vault.legacyImported())
    }

    @Test
    fun currentAccountMustExistAndClearsOnRemove() {
        assertFailsWith<SecureStoreException.InvalidArgument> { vault.setCurrentUsername("vault-full") }
        vault.save(full)
        vault.save(signer)
        vault.setCurrentUsername("vault-full")
        vault.remove("vault.signer")
        assertEquals("vault-full", vault.currentUsername())
        vault.remove("vault-full")
        assertNull(vault.currentUsername())
        assertTrue(vault.usernames().isEmpty())
    }

    @Test
    fun secretsNeverAppearInNamesOrToString() {
        vault.save(full)
        val secrets = listOfNotNull(
            full.postingKey, full.activeKey, full.memoKey,
            full.accessToken, full.hiveAuthKey, full.hiveAuthToken,
        )
        val printed = full.toString() + vault.accounts().toString() + store.names().toString()
        secrets.forEach { assertFalse(printed.contains(it), "leaks a secret") }
    }

    @Test
    fun oneCorruptAccountDoesNotHideTheOthers() {
        vault.save(signer)
        store.putString("account.bad-one", "{broken")
        assertEquals(listOf(signer), vault.accounts())
        assertEquals(listOf("bad-one"), vault.unreadableUsernames())
    }

    @Test
    fun corruptMetaReadsAsDefaultsAndIsReplaced() {
        vault.save(signer)
        store.putString("vault.meta", "{broken")
        assertNull(vault.currentUsername())
        assertFalse(vault.legacyImported())
        vault.setCurrentUsername("vault.signer")
        assertEquals("vault.signer", vault.currentUsername())
        store.put("vault.meta", byteArrayOf(0xC3.toByte(), 0x28))
        vault.remove("vault.signer")
        assertTrue(vault.usernames().isEmpty())
    }

    @Test
    fun removeClearsCurrentBeforeDeleting() {
        val failingDelete = object : SecureStore by store {
            override fun delete(name: String) = throw SecureStoreException.Unavailable(-1)
        }
        val v = KeyVault(failingDelete)
        v.save(signer)
        v.setCurrentUsername("vault.signer")
        assertFailsWith<SecureStoreException.Unavailable> { v.remove("vault.signer") }
        // A failed remove leaves the account, no longer current; never a deleted account plus an error.
        assertEquals(signer, v.account("vault.signer"))
        assertNull(v.currentUsername())
    }

    @Test
    fun unreadableEntriesAreTypedErrors() {
        store.putString("account.vault-full", "{not json")
        assertEquals("account.vault-full", assertFailsWith<SecureStoreException.Corrupted> { vault.account("vault-full") }.name)

        // An entry whose content names another account is not trusted.
        store.putString("account.vault-other", """{"username":"vault-full","postingKey":"x"}""")
        assertFailsWith<SecureStoreException.Corrupted> { vault.account("vault-other") }

        store.put("account.vault-bytes", byteArrayOf(0xC3.toByte(), 0x28))
        assertFailsWith<SecureStoreException.Corrupted> { vault.account("vault-bytes") }
    }

    @Test
    fun lostDeviceKeyIsRecoverableByWipe() {
        vault.save(full)
        store.keyLost = true
        assertFailsWith<SecureStoreException.KeyInvalidated> { vault.account("vault-full") }
        vault.wipe()
        assertTrue(vault.usernames().isEmpty())
        vault.save(signer)
        assertEquals(signer, vault.account("vault.signer"))
    }

    /** Swift only sees SecureStoreException: nothing else may escape for caller mistakes. */
    @Test
    fun invalidNamesNeverThrowUntypedErrors() {
        val bad = VaultAccount("Not A Hive Name", AuthType.POSTING_KEY, postingKey = "dummy")
        assertFalse(bad.hasValidUsername)
        assertFailsWith<SecureStoreException.InvalidArgument> { vault.save(bad) }
        assertNull(vault.account("Upper.Case"))
        assertNull(vault.account("../escape"))
        vault.remove("Upper.Case")
        assertFailsWith<SecureStoreException.InvalidArgument> { vault.setCurrentUsername("Upper.Case") }
        assertFailsWith<SecureStoreException.InvalidArgument> { store.put("has space", byteArrayOf(1)) }
        assertFailsWith<SecureStoreException.InvalidArgument> { store.put("", byteArrayOf(1)) }
        assertFailsWith<SecureStoreException.InvalidArgument> { store.put("x".repeat(129), byteArrayOf(1)) }
        assertTrue(store.names().isEmpty())
    }

    @Test
    fun asyncWrapperMatchesTheVault() = kotlinx.coroutines.test.runTest {
        val async = KeyVaultAsync(vault, kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        async.save(signer)
        async.setCurrentUsername("vault.signer")
        assertEquals(listOf(signer), async.accounts())
        assertEquals("vault.signer", async.currentUsername())
        async.remove("vault.signer")
        assertNull(async.account("vault.signer"))
    }

    @Test
    fun bytesAndStringsShareTheStore() {
        val bytes = ByteArray(256) { it.toByte() }
        store.put("raw.bytes", bytes)
        assertContentEquals(bytes, store.get("raw.bytes"))
        store.putString("raw.text", "Hive é中🐝")
        assertEquals("Hive é中🐝", store.getString("raw.text"))
        store.put("raw.empty", ByteArray(0))
        assertContentEquals(ByteArray(0), store.get("raw.empty"))
    }

    @Test
    fun hasCredentialNeedsSomethingUsable() {
        assertFalse(VaultAccount("vault-none", AuthType.POSTING_KEY, postingKey = "").hasCredential)
        assertTrue(signer.hasCredential)
        assertTrue(VaultAccount("vault-has", AuthType.HIVE_AUTH, hiveAuthToken = "t").hasCredential)
    }

    // ---- entries of earlier builds: owner key and master password ----

    private val user = "vault-old"
    private val password = "P5ThrowawayOldVaultPasswordNotReal"
    private fun derived(role: com.ecency.core.hive.KeyRole) = com.ecency.core.hive.PrivateKey.fromLogin(user, password, role).toWif()

    /** An entry as an earlier build wrote it: every field, the two retired ones included. */
    private fun oldEntry(posting: String? = "old-posting-wif", active: String? = "old-active-wif", memo: String? = "old-memo-wif", master: String? = password, owner: String? = "old-owner-wif") {
        fun q(v: String?) = v?.let { "\"$it\"" } ?: "null"
        store.putString(
            "account.$user",
            """{"v":1,"username":"$user","authType":"master","masterPassword":${q(master)},"postingKey":${q(posting)},"activeKey":${q(active)},""" +
                """"memoKey":${q(memo)},"ownerKey":${q(owner)},"accessToken":"old-token","hiveAuthKey":null,"hiveAuthToken":null,"hiveAuthExpiry":0}""",
        )
    }

    private fun rawEntry() = store.getString("account.$user")!!

    @Test
    fun anOldEntryLosesItsOwnerKeyAndMasterPasswordAndKeepsEverythingElse() {
        oldEntry()
        assertEquals(listOf(user), vault.dropRetiredSecrets())
        val raw = rawEntry()
        assertFalse(password in raw, "the master password is gone")
        assertFalse("old-owner-wif" in raw, "the owner key is gone")
        assertFalse("masterPassword" in raw || "ownerKey" in raw)
        val account = vault.account(user)!!
        assertEquals("old-posting-wif", account.postingKey)
        assertEquals("old-active-wif", account.activeKey)
        assertEquals("old-memo-wif", account.memoKey)
        assertEquals("old-token", account.accessToken)
        assertEquals(AuthType.MASTER_PASSWORD, account.authType)
        assertTrue(account.hasCredential)
    }

    @Test
    fun cleaningIsIdempotentAndDoesNotTouchACleanVault() {
        oldEntry()
        vault.save(full)
        vault.dropRetiredSecrets()
        val after = rawEntry()
        val writes = store.writes
        assertTrue(vault.dropRetiredSecrets().isEmpty())
        assertTrue(vault.dropRetiredSecrets().isEmpty())
        assertEquals(writes, store.writes, "nothing is written when nothing is left to drop")
        assertEquals(after, rawEntry())
        assertEquals(full, vault.account("vault-full"))
    }

    @Test
    fun aMasterOnlyEntryGetsItsThreeKeysDerivedInTheSameWriteThatDropsThePassword() {
        oldEntry(posting = null, active = null, memo = null, owner = null)
        val before = store.writes
        vault.dropRetiredSecrets()
        assertEquals(before + 1, store.writes, "one write: never a moment without both the password and the keys")
        val account = vault.account(user)!!
        assertEquals(derived(com.ecency.core.hive.KeyRole.POSTING), account.postingKey)
        assertEquals(derived(com.ecency.core.hive.KeyRole.ACTIVE), account.activeKey)
        assertEquals(derived(com.ecency.core.hive.KeyRole.MEMO), account.memoKey)
        val raw = rawEntry()
        assertFalse(password in raw)
        assertFalse(derived(com.ecency.core.hive.KeyRole.OWNER) in raw, "the owner key is not derived into the vault")
    }

    @Test
    fun onlyMissingKeysAreDerivedAStoredKeyIsNeverReplaced() {
        oldEntry(posting = "old-posting-wif", active = null, memo = "")
        vault.dropRetiredSecrets()
        val account = vault.account(user)!!
        assertEquals("old-posting-wif", account.postingKey)
        assertEquals(derived(com.ecency.core.hive.KeyRole.ACTIVE), account.activeKey)
        assertEquals(derived(com.ecency.core.hive.KeyRole.MEMO), account.memoKey)
    }

    @Test
    fun anOldEntryReadBeforeTheCleaningNeverHandsOutTheRetiredSecretsAndASaveKeepsTheAccountUsable() {
        oldEntry(posting = null, active = null, memo = null)
        // some code reads and saves the account before the cleaning ran (a token refresh at start)
        val read = vault.account(user)!!
        assertFalse(password in read.toString())
        assertEquals(derived(com.ecency.core.hive.KeyRole.ACTIVE), read.activeKey, "the keys are there already on read")
        vault.save(read.copy(accessToken = "new-token"))
        val raw = rawEntry()
        assertFalse(password in raw)
        assertFalse("old-owner-wif" in raw)
        assertEquals(derived(com.ecency.core.hive.KeyRole.POSTING), vault.account(user)!!.postingKey)
        assertTrue(vault.dropRetiredSecrets().isEmpty())
    }

    @Test
    fun anOwnerKeyOnlyEntryStaysAsAnAccountWithoutKeys() {
        store.putString("account.$user", """{"v":1,"username":"$user","authType":"owner","ownerKey":"old-owner-wif","accessToken":"old-token","hiveAuthExpiry":0}""")
        vault.dropRetiredSecrets()
        val account = vault.account(user)!!
        assertFalse("old-owner-wif" in rawEntry())
        assertEquals(AuthType.OWNER_KEY, account.authType)
        assertNull(account.postingKey)
        assertEquals("old-token", account.accessToken, "still signed in to Ecency; a posting or active key can be imported")
        assertEquals(listOf(user), vault.usernames())
    }

    @Test
    fun eitherRetiredFieldAloneIsEnoughToCleanAnEntry() {
        store.putString("account.$user", """{"v":1,"username":"$user","authType":"master","masterPassword":"$password","postingKey":"old-posting-wif","hiveAuthExpiry":0}""")
        assertEquals(listOf(user), vault.dropRetiredSecrets())
        assertFalse(password in rawEntry())
        store.putString("account.$user", """{"v":1,"username":"$user","authType":"posting","ownerKey":"old-owner-wif","postingKey":"old-posting-wif","hiveAuthExpiry":0}""")
        assertEquals(listOf(user), vault.dropRetiredSecrets())
        assertFalse("old-owner-wif" in rawEntry())
        assertEquals("old-posting-wif", vault.account(user)!!.postingKey)
    }

    @Test
    fun anUnreadableEntryIsLeftAlone() {
        store.putString("account.$user", "not json, with masterPassword and ownerKey in it")
        assertTrue(vault.dropRetiredSecrets().isEmpty())
        assertEquals("not json, with masterPassword and ownerKey in it", rawEntry())
    }

    @Test
    fun aVaultAccountHasNoPlaceForAnOwnerKeyOrAMasterPassword() {
        vault.save(full)
        val raw = store.getString("account.vault-full")!!
        assertFalse("masterPassword" in raw)
        assertFalse("ownerKey" in raw)
    }

    private fun oldEntryFor(name: String) = store.putString(
        "account.$name",
        """{"v":1,"username":"$name","authType":"master","masterPassword":"$password","postingKey":"old-posting-wif","ownerKey":"old-owner-wif","hiveAuthExpiry":0}""",
    )

    @Test
    fun oneUnreadableEntryNeverStopsTheCleaningOfTheOthersInEitherOrder() {
        for (failure in listOf<SecureStoreException>(SecureStoreException.Corrupted("account.vault-bad"), SecureStoreException.Unavailable(-1))) {
            // names sort: "vault-a" is cleaned before the bad entry is met, "vault-z" after it
            store.wipe()
            store.failingReads.clear()
            oldEntryFor("vault-a")
            oldEntryFor("vault-bad")
            oldEntryFor("vault-z")
            store.failingReads["account.vault-bad"] = failure
            assertEquals(listOf("vault-a", "vault-z"), vault.dropRetiredSecrets().sorted(), failure::class.simpleName)
            for (name in listOf("vault-a", "vault-z")) {
                val raw = store.getString("account.$name")!!
                assertFalse(password in raw || "old-owner-wif" in raw, "$name is clean although vault-bad could not be read")
            }
            // the bad entry is untouched, and cleaned once it can be read again
            store.failingReads.clear()
            assertTrue(password in store.getString("account.vault-bad")!!)
            assertEquals(listOf("vault-bad"), vault.dropRetiredSecrets())
        }
    }

    @Test
    fun aLostDeviceKeyEndsTheCleaningAsAnError() {
        oldEntryFor("vault-a")
        store.failingReads["account.vault-a"] = SecureStoreException.KeyInvalidated()
        assertFailsWith<SecureStoreException.KeyInvalidated> { vault.dropRetiredSecrets() }
    }
}

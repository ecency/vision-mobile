package com.ecency.core.legacy

import com.ecency.core.secure.AuthType
import com.ecency.core.secure.InMemorySecureStore
import com.ecency.core.secure.KeyVault
import com.ecency.core.secure.PinCheck
import com.ecency.core.secure.PinLock
import com.ecency.core.secure.SecureStore
import com.ecency.core.secure.VaultAccount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Dies like a killed process after [putsLeft] successful writes. Intercepts put, which putString uses. */
private class DyingStore(val inner: InMemorySecureStore, var putsLeft: Int) : SecureStore by inner {
    override fun put(name: String, value: ByteArray) {
        if (putsLeft-- <= 0) throw IllegalStateException("killed")
        inner.put(name, value)
    }

    override fun putString(name: String, value: String) = put(name, value.encodeToByteArray())
}

class LegacyMigrationTest {
    private val rnData = mapOf("persist:root" to LegacyFixtures.PERSIST_ROOT, "user" to LegacyFixtures.USERS)
    private val rn = MapLegacyStorage(rnData + ("auth" to "{\"pinCode\":\"dummy-hash\"}") + ("sc_accounts" to "[]"))
    private val store = InMemorySecureStore()
    private val vault = KeyVault(store)

    private fun run(
        storage: LegacyStorage = rn,
        pinKey: String = LegacyFixtures.PIN_KEY,
        target: KeyVault = vault,
    ) = LegacyMigration.run(target, storage, pinKey, LegacyFixtures.DEFAULT_PIN, PIN_ITERATIONS)

    // "spike-owner" is an RN account whose only credential is an owner key (no token, no other key): nothing of
    // it may be stored, so it is skipped like an account without credentials and its owner signs in again
    private val usable = listOf("spike-default", "spike-has", "spike-master", "spike-posting", "spike-signer")

    @Test
    fun everyUsableAccountMovesIntoTheVault() {
        val report = run()
        assertEquals(usable, report.imported.sorted())
        assertEquals(listOf("spike-legacy", "spike-owner"), report.skipped.sorted())
        assertEquals(mapOf("spike-legacy" to listOf("postingKey")), report.unreadable)
        assertEquals(usable, vault.usernames())

        val master = vault.account("spike-master")!!
        assertEquals(AuthType.MASTER_PASSWORD, master.authType)
        assertEquals(LegacyFixtures.POSTING, master.postingKey)
        assertEquals(LegacyFixtures.ACTIVE, master.activeKey)
        assertEquals(LegacyFixtures.MEMO, master.memoKey)
        // RN stored the master password and the owner key; neither reaches the vault
        val masterRaw = store.getString("account.spike-master")!!
        assertFalse(LegacyFixtures.PASSWORD in masterRaw)
        assertFalse(LegacyFixtures.OWNER in masterRaw)
        assertFalse("masterPassword" in masterRaw || "ownerKey" in masterRaw)
        assertEquals(LegacyFixtures.POSTING_ONLY, vault.account("spike-posting")!!.postingKey)
        assertEquals(LegacyFixtures.DEFAULT_POSTING, vault.account("spike-default")!!.postingKey)
        // the owner-key account brings nothing along: no entry, and its key is nowhere in the vault
        assertNull(vault.account("spike-owner"))
        assertTrue(store.entries.values.none { LegacyFixtures.OWNER_ONLY in it.decodeToString() || LegacyFixtures.OWNER in it.decodeToString() })
        assertTrue(store.entries.values.none { LegacyFixtures.PASSWORD in it.decodeToString() })
        assertEquals(AuthType.HIVE_SIGNER, vault.account("spike-signer")!!.authType)
        val has = vault.account("spike-has")!!
        assertEquals(AuthType.HIVE_AUTH, has.authType)
        assertEquals("dummy-has-token", has.hiveAuthToken)
        assertEquals(1791288000000L, has.hiveAuthExpiry)

        assertEquals("spike-posting", vault.currentUsername())
        assertTrue(vault.pinLockEnabled())
        assertTrue(vault.legacyImported())
    }

    @Test
    fun rnSecretsAreDeletedAfterAVerifiedRun() {
        val report = run()
        assertTrue(report.rnSecretsRemoved)
        assertNull(rn.read("user"))
        assertNull(rn.read("auth"))
        assertNull(rn.read("sc_accounts"))
        val root = rn.read("persist:root")!!
        listOf("\\\"pin\\\"", "encUnlockPin", "local").forEach { assertFalse(root.contains(it), "persist:root keeps $it") }
        // Everything else stays.
        assertTrue(root.contains("spike-posting") && root.contains("isPinCodeOpen"))
        assertFalse(LegacyCleanup.hasSecrets(rn))
    }

    @Test
    fun aSecondRunNeitherDuplicatesNorLosesAccounts() {
        run()
        val before = vault.accounts()
        val writes = store.writes
        val again = run(storage = MapLegacyStorage(rnData))
        assertTrue(again.alreadyDone)
        assertTrue(again.imported.isEmpty())
        assertEquals(writes, store.writes, "a finished migration must not write the vault again")
        assertEquals(before, vault.accounts())
        assertEquals(usable, vault.usernames())
    }

    @Test
    fun killedAtEveryWriteResumesToTheFullSet() {
        for (n in 0..14) {
            val mem = InMemorySecureStore()
            val storage = MapLegacyStorage(rnData)
            runCatching { run(storage = storage, target = KeyVault(DyingStore(mem, n))) }
            if (!KeyVault(mem).legacyImported()) assertEquals(rnData["user"], storage.read("user"), "RN data deleted before the mark ($n)")
            run(storage = storage, target = KeyVault(mem))
            assertEquals(usable, KeyVault(mem).usernames(), "killed after $n writes")
            assertNull(storage.read("user"))
            assertEquals(PinCheck.Success, PinLock(KeyVault(mem), PIN_ITERATIONS).verify(LegacyFixtures.UNLOCK_PIN), "PIN after $n writes")
        }
    }

    @Test
    fun theRnUnlockPinBecomesTheVerifier() {
        val report = run()
        assertFalse(report.pinLockDropped)
        val lock = PinLock(KeyVault(store), PIN_ITERATIONS)
        assertTrue(lock.isEnabled())
        assertEquals(PinCheck.Wrong(1, 2), lock.verify(LegacyFixtures.PIN)) // the app PIN is not the unlock PIN
        assertEquals(PinCheck.Success, lock.verify(LegacyFixtures.UNLOCK_PIN))
        // a verifier, never the PIN, and the RN copies of the PIN are gone
        store.entries.values.forEach { assertFalse(LegacyFixtures.UNLOCK_PIN in it.decodeToString()) }
        assertFalse(rn.read("persist:root")!!.contains("encUnlockPin"))
    }

    @Test
    fun theRnBiometricSettingCarriesOverUnboundUntilACorrectPin() {
        val root = LegacyFixtures.PERSIST_ROOT.replace("\\\"isPinCodeOpen\\\":true", "\\\"isPinCodeOpen\\\":true,\\\"isBiometricEnabled\\\":true")
        assertTrue(root.contains("isBiometricEnabled"))
        run(storage = MapLegacyStorage(rnData + ("persist:root" to root)))
        val lock = PinLock(KeyVault(store), PIN_ITERATIONS)
        // on, but not offered until a correct PIN binds it to the biometrics enrolled now
        assertFalse(lock.isBiometricEnabled())
        assertTrue(lock.biometricNeedsBinding())
        lock.bindBiometric("enrolled-now")
        assertTrue(lock.isBiometricEnabled())
    }

    @Test
    fun withoutTheRnBiometricSettingBiometricsStayOff() {
        run()
        assertFalse(PinLock(KeyVault(store), PIN_ITERATIONS).biometricNeedsBinding())
    }

    @Test
    fun withoutEncUnlockPinTheAppPinIsTheUnlockPin() {
        val root = LegacyFixtures.PERSIST_ROOT.replace(Regex(""",\\"encUnlockPin\\":\\"[^\\]+\\""""), "")
        assertFalse(root.contains("encUnlockPin"))
        run(storage = MapLegacyStorage(rnData + ("persist:root" to root)))
        assertEquals(PinCheck.Success, PinLock(KeyVault(store), PIN_ITERATIONS).verify(LegacyFixtures.PIN))
    }

    @Test
    fun aPinThatDoesNotDecryptLeavesTheLockOff() {
        val root = LegacyFixtures.PERSIST_ROOT.replace(Regex("""(\\"encUnlockPin\\":\\")[^\\]+"""), "$1Z2FyYmFnZQ==")
        val report = run(storage = MapLegacyStorage(rnData + ("persist:root" to root)))
        assertTrue(report.pinLockDropped)
        assertFalse(PinLock(KeyVault(store), PIN_ITERATIONS).isEnabled())
        assertFalse(vault.pinLockEnabled())
        assertTrue("spike-posting" in vault.usernames())
    }

    @Test
    fun aLockThatWasOffStaysOffAndStoresNoPin() {
        val root = LegacyFixtures.PERSIST_ROOT.replace("\\\"isPinCodeOpen\\\":true", "\\\"isPinCodeOpen\\\":false")
        assertTrue(root.contains("isPinCodeOpen\\\":false"))
        run(storage = MapLegacyStorage(rnData + ("persist:root" to root)))
        assertFalse(PinLock(KeyVault(store), PIN_ITERATIONS).hasPin())
        assertFalse(vault.pinLockEnabled())
    }

    @Test
    fun anInterruptedRunCompletesWithoutOverwritingTheVault() {
        val changed = VaultAccount("spike-master", AuthType.POSTING_KEY, postingKey = "dummy-native-wif")
        vault.save(changed)
        val report = run()
        assertEquals(listOf("spike-master"), report.kept)
        assertEquals(usable - "spike-master", report.imported.sorted())
        assertEquals(changed, vault.account("spike-master"))
        assertEquals(usable, vault.usernames())
    }

    @Test
    fun accountsRemovedAfterMigrationStayRemoved() {
        run()
        vault.remove("spike-signer")
        run(storage = MapLegacyStorage(rnData))
        assertFalse("spike-signer" in vault.usernames())
    }

    @Test
    fun aCurrentAccountChosenNativelyIsKept() {
        vault.save(VaultAccount("native-user", AuthType.POSTING_KEY, postingKey = "dummy-wif"))
        vault.setCurrentUsername("native-user")
        run()
        assertEquals("native-user", vault.currentUsername())
    }

    @Test
    fun aCorruptVaultEntryIsReplacedByTheRnCopy() {
        store.putString("account.spike-posting", "{broken")
        val report = run()
        assertTrue("spike-posting" in report.imported)
        assertEquals(LegacyFixtures.POSTING_ONLY, vault.account("spike-posting")!!.postingKey)
    }

    @Test
    fun corruptMetaDoesNotBlockTheMigration() {
        store.putString("vault.meta", "{broken")
        assertEquals(usable, run().imported.sorted())
        assertTrue(vault.legacyImported())
    }

    @Test
    fun noRnStorageAtAllIsDoneForGood() {
        val report = run(storage = MapLegacyStorage())
        assertFalse(report.alreadyDone)
        assertTrue(vault.legacyImported())
        assertTrue(vault.usernames().isEmpty())
    }

    @Test
    fun unreadableRnStorageIsNeverMarkedDone() {
        val locked = MapLegacyStorage(rnData).apply { unreadable = true }
        assertFailsWith<LegacyImportError.StorageUnreadable> { run(storage = locked) }
        assertFalse(vault.legacyImported())
        locked.unreadable = false
        assertEquals(usable, run(storage = locked).imported.sorted())
    }

    @Test
    fun aWrongPinKeyWritesNothingDeletesNothingAndCanRetry() {
        assertFailsWith<LegacyImportError.PinUnavailable> { run(pinKey = "not-the-pin-key") }
        assertEquals(0, store.writes)
        assertFalse(vault.legacyImported())
        assertEquals(LegacyFixtures.USERS, rn.read("user"))
        assertEquals(usable, run().imported.sorted())
    }

    @Test
    fun aFailedCleanupIsRetriedByTheNextRun() {
        val flaky = object : LegacyStorage by rn {
            var failRemove = true
            override fun remove(key: String) {
                if (failRemove) throw LegacyImportError.StorageUnreadable()
                rn.remove(key)
            }
        }
        assertFalse(run(storage = flaky).rnSecretsRemoved)
        assertTrue(vault.legacyImported())
        flaky.failRemove = false
        val again = run(storage = flaky)
        assertTrue(again.alreadyDone)
        assertTrue(again.rnSecretsRemoved)
        assertNull(rn.read("user"))
    }

    @Test
    fun theReportNamesAccountsNotSecrets() {
        val printed = run().toString()
        listOf(LegacyFixtures.PASSWORD, LegacyFixtures.POSTING, LegacyFixtures.ACTIVE, LegacyFixtures.POSTING_ONLY)
            .forEach { assertFalse(printed.contains(it)) }
    }

    private companion object {
        const val PIN_ITERATIONS = 16
    }

    @Test
    fun anRnMasterAccountThatLacksAKeyGetsItDerivedFromThePasswordWhichIsNotKept() {
        // the same RN data, but the master account's active and memo keys cannot be read
        val users = kotlinx.serialization.json.Json.parseToJsonElement(LegacyFixtures.USERS) as kotlinx.serialization.json.JsonArray
        val withoutTwoKeys = kotlinx.serialization.json.JsonArray(users.map { u ->
            val o = u as kotlinx.serialization.json.JsonObject
            if ((o["username"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "spike-master") kotlinx.serialization.json.JsonObject(o - "activeKey" - "memoKey") else o
        }).toString()
        val broken = MapLegacyStorage(mapOf("persist:root" to LegacyFixtures.PERSIST_ROOT, "user" to withoutTwoKeys))
        run(storage = broken)
        val master = vault.account("spike-master")!!
        assertEquals(LegacyFixtures.POSTING, master.postingKey, "a key RN stored is kept as it is")
        assertEquals(LegacyFixtures.ACTIVE, master.activeKey, "derived from the password")
        assertEquals(LegacyFixtures.MEMO, master.memoKey)
        assertTrue(store.entries.values.none { LegacyFixtures.PASSWORD in it.decodeToString() || LegacyFixtures.OWNER in it.decodeToString() })
    }
}

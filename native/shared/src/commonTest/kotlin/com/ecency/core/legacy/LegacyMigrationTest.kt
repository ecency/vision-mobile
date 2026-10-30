package com.ecency.core.legacy

import com.ecency.core.secure.AuthType
import com.ecency.core.secure.InMemorySecureStore
import com.ecency.core.secure.KeyVault
import com.ecency.core.secure.PinCheck
import com.ecency.core.secure.PinLock
import com.ecency.core.secure.SecureStore
import com.ecency.core.secure.VaultAccount
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

    /** The same RN data without the two accounts whose RN entries must stay (owner-only, unreadable). */
    private val importableRn by lazy {
        MapLegacyStorage(
            rnData + ("auth" to "{\"pinCode\":\"dummy-hash\"}") + ("sc_accounts" to "[]") + ("user" to
                JsonArray(Json.parseToJsonElement(LegacyFixtures.USERS).jsonArray.filter { it.jsonObject["username"]!!.jsonPrimitive.content in usable }).toString()),
        )
    }
    private val store = InMemorySecureStore()
    private val vault = KeyVault(store)

    private fun run(
        storage: LegacyStorage = rn,
        pinKey: String = LegacyFixtures.PIN_KEY,
        target: KeyVault = vault,
        defaultPin: String? = LegacyFixtures.DEFAULT_PIN,
    ) = LegacyMigration.run(target, storage, pinKey, defaultPin, PIN_ITERATIONS)

    // "spike-owner" is an RN account whose only credential is an owner key (no token, no other key): nothing of
    // it may be stored, so it is skipped like an account without credentials (its RN entry stays, see below)
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
        val rn = importableRn
        val report = run(storage = rn)
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
            assertEquals(listOf("spike-legacy", "spike-owner"), rnUsers(storage).sorted(), "only the kept RN entries stay ($n)")
            assertEquals(PinCheck.Success, PinLock(KeyVault(mem), PIN_ITERATIONS).verify(LegacyFixtures.UNLOCK_PIN), "PIN after $n writes")
        }
    }

    @Test
    fun theRnUnlockPinBecomesTheVerifier() {
        val rn = importableRn
        val report = run(storage = rn)
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
        assertEquals(listOf("spike-legacy", "spike-owner"), rnUsers().sorted())
    }

    @Test
    fun theReportNamesAccountsNotSecrets() {
        val printed = run().toString()
        listOf(LegacyFixtures.PASSWORD, LegacyFixtures.POSTING, LegacyFixtures.ACTIVE, LegacyFixtures.POSTING_ONLY)
            .forEach { assertFalse(printed.contains(it)) }
    }

    private companion object {
        const val PIN_ITERATIONS = 16
        const val NOON_UTC = 1793275200000L // 2026-10-29T12:00:00Z
    }

    /** Usernames left in the RN "user" list. */
    private fun rnUsers(storage: LegacyStorage = rn): List<String> =
        storage.read("user")?.let { Json.parseToJsonElement(it).jsonArray.map { u -> u.jsonObject["username"]!!.jsonPrimitive.content } }.orEmpty()

    private fun rnUser(name: String, storage: LegacyStorage = rn) =
        Json.parseToJsonElement(storage.read("user")!!).jsonArray.single { it.jsonObject["username"]!!.jsonPrimitive.content == name }

    private fun originalUser(name: String) =
        Json.parseToJsonElement(LegacyFixtures.USERS).jsonArray.single { it.jsonObject["username"]!!.jsonPrimitive.content == name }

    @Test
    fun anOwnerOnlyAccountKeepsItsRnEntry() {
        // the vault never stores an owner key, so the RN entry is its only copy: it stays, with the PIN it opens with
        run()
        assertTrue("spike-owner" in rnUsers(), "the owner-only account's RN entry was deleted")
        assertEquals(originalUser("spike-owner"), rnUser("spike-owner"))
        assertTrue(rn.read("persist:root")!!.contains("encUnlockPin"), "the PIN that opens it was deleted")
    }

    @Test
    fun anUnreadableAccountKeepsItsRnEntry() {
        run()
        assertTrue("spike-legacy" in rnUsers(), "the unreadable account's RN entry was deleted")
        assertEquals(originalUser("spike-legacy"), rnUser("spike-legacy"))
    }

    @Test
    fun importedAccountsLeaveTheRnStoreAndKeptOnesSurviveLaterRuns() {
        run()
        assertEquals(listOf("spike-legacy", "spike-owner"), rnUsers().sorted())
        assertNull(rn.read("auth"))
        // a later launch (marked done) cleans again but never the entries kept above
        run()
        assertEquals(listOf("spike-legacy", "spike-owner"), rnUsers().sorted())
    }

    @Test
    fun anRnStoreThatNoLongerOpensAfterTheMarkIsLeftWhole() {
        vault.markLegacyImported()
        val report = run(pinKey = "not-the-pin-key")
        assertTrue(report.alreadyDone)
        assertFalse(report.rnSecretsRemoved)
        assertEquals(LegacyFixtures.USERS, rn.read("user"))
        assertEquals(LegacyFixtures.PERSIST_ROOT, rn.read("persist:root"))
    }

    @Test
    fun theRnRefreshTokenAndExpiryComeAlong() {
        // RN setSCAccount: plain JSON, expireDate is Date.toString(); its accessToken is stale after a refresh
        val sc = """[{"username":"spike-signer","accessToken":"stale","refreshToken":"dummy-rn-refresh",""" +
            """"expireDate":"Thu Oct 29 2026 12:00:00 GMT+0000 (Coordinated Universal Time)"},""" +
            """{"username":"spike-posting","accessToken":"stale","refreshToken":"dummy-rn-refresh-2","expireDate":"Thu Oct 29 2026 17:30:00 GMT+0530"}]"""
        val report = run(storage = MapLegacyStorage(rnData + ("sc_accounts" to sc)))
        assertTrue("spike-signer" in report.imported)
        val sessions = com.ecency.core.auth.VaultAccountStore(vault, store)
        assertEquals(
            com.ecency.core.auth.TokenSession("spike-signer", "dummy-hivesigner-access-token", "dummy-rn-refresh", NOON_UTC),
            sessions.session("spike-signer"),
        )
        assertEquals(
            com.ecency.core.auth.TokenSession("spike-posting", "dummy-ecency-token-2", "dummy-rn-refresh-2", NOON_UTC),
            sessions.session("spike-posting"),
        )
        // a key account without an RN session is marked expired, so its first renewal signs a new one
        assertEquals(com.ecency.core.auth.TokenSession("spike-master", "dummy-ecency-token", null, 1), sessions.session("spike-master"))
        // a HiveSigner account without one keeps its token as it is: nothing could renew it
        val signerOnly = InMemorySecureStore()
        run(storage = MapLegacyStorage(rnData), target = KeyVault(signerOnly))
        assertEquals(
            com.ecency.core.auth.TokenSession("spike-signer", "dummy-hivesigner-access-token", null, 0),
            com.ecency.core.auth.VaultAccountStore(KeyVault(signerOnly), signerOnly).session("spike-signer"),
        )
    }

    @Test
    fun aUserEntryThatIsNotAnAccountIsKeptInTheRnStore() {
        val storage = MapLegacyStorage(rnData + ("user" to "[42," + LegacyFixtures.USERS.removePrefix("[")))
        assertEquals(usable, run(storage = storage).imported.sorted())
        val left = Json.parseToJsonElement(storage.read("user")!!).jsonArray
        assertTrue(left.any { it.toString() == "42" }, "the entry nothing could read was deleted")
        assertTrue(storage.read("persist:root")!!.contains("encUnlockPin"))
    }

    @Test
    fun aKeptEntryThatReadsLaterIsImportedBeforeItsRnCopyGoes() {
        // a build without DEFAULT_PIN cannot read spike-default: imported elsewhere, its entry is kept
        run(defaultPin = null)
        assertNull(vault.account("spike-default"))
        assertTrue("spike-default" in rnUsers())
        // a later build reads it: into the vault first, checked, and only then out of the RN storage
        val again = run()
        assertEquals(LegacyFixtures.DEFAULT_POSTING, vault.account("spike-default")?.postingKey, "a newly readable entry never reached the vault")
        assertEquals(listOf("spike-default"), again.imported)
        assertEquals(listOf("spike-legacy", "spike-owner"), rnUsers().sorted())
    }

    @Test
    fun aKeptEntryThatReadsLaterStaysWhenTheVaultWriteFails() {
        run(defaultPin = null)
        assertTrue("spike-default" in rnUsers())
        run(target = KeyVault(DyingStore(store, 0)))
        assertNull(vault.account("spike-default"))
        assertTrue("spike-default" in rnUsers(), "deleted although the vault never took it")
        assertEquals(originalUser("spike-default"), rnUser("spike-default"))
    }

    @Test
    fun aMalformedScAccountsListDoesNotBlockTheImport() {
        assertEquals(usable, run(storage = MapLegacyStorage(rnData + ("sc_accounts" to "{broken"))).imported.sorted())
        assertNull(com.ecency.core.auth.VaultAccountStore(vault, store).session("spike-signer")!!.refreshToken)
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

    // the vault's "legacy.kept" list, as the migration writes it
    private fun keptList(s: InMemorySecureStore = store) = s.getString("legacy.kept")

    @Test
    fun anUnreadableKeptListDeletesNothing() {
        for (broken in listOf("{broken", "42", "[{\"a\":1}]")) {
            val mem = InMemorySecureStore()
            val storage = MapLegacyStorage(rnData)
            // spike-default is kept: this build cannot read it
            run(storage = storage, target = KeyVault(mem), defaultPin = null)
            assertTrue("spike-default" in rnUsers(storage))
            mem.putString("legacy.kept", broken)
            // a later build reads it, but cannot tell which entries were kept
            run(storage = storage, target = KeyVault(mem))
            assertTrue("spike-default" in rnUsers(storage), "deleted although the kept list could not be read ($broken)")
            assertEquals(originalUser("spike-default"), rnUser("spike-default", storage))
            assertEquals(broken, mem.getString("legacy.kept"), "the unreadable list is left for the next run")
        }
    }

    @Test
    fun aKeptListThatFailsToReadDeletesNothing() {
        val mem = InMemorySecureStore()
        val storage = MapLegacyStorage(rnData)
        run(storage = storage, target = KeyVault(mem), defaultPin = null)
        val failing = object : SecureStore by mem {
            override fun get(name: String): ByteArray? =
                if (name == "legacy.kept") throw com.ecency.core.secure.SecureStoreException.Corrupted(name) else mem.get(name)
            override fun getString(name: String): String? = get(name)?.decodeToString()
        }
        run(storage = storage, target = KeyVault(failing))
        assertTrue("spike-default" in rnUsers(storage), "deleted although the kept list could not be read")
        assertEquals(originalUser("spike-default"), rnUser("spike-default", storage))
    }

    @Test
    fun killedAtEveryWriteOfALaterRunNeverDropsAnEntryTheVaultLacks() {
        for (n in 0..6) {
            val mem = InMemorySecureStore()
            val storage = MapLegacyStorage(rnData)
            run(storage = storage, target = KeyVault(mem), defaultPin = null)
            runCatching { run(storage = storage, target = KeyVault(DyingStore(mem, n))) }
            if (KeyVault(mem).account("spike-default")?.postingKey != LegacyFixtures.DEFAULT_POSTING) {
                assertTrue("spike-default" in rnUsers(storage), "RN entry deleted before the vault held it ($n)")
            }
            run(storage = storage, target = KeyVault(mem))
            assertEquals(LegacyFixtures.DEFAULT_POSTING, KeyVault(mem).account("spike-default")?.postingKey, "killed after $n writes")
            assertEquals(listOf("spike-legacy", "spike-owner"), rnUsers(storage).sorted(), "only the kept RN entries stay ($n)")
        }
    }

    @Test
    fun killedAtEveryWriteOfTheFirstRunNeverDropsAnEntryWhoseKeysTheVaultDoesNotHold() {
        // signed in natively before the import with another key: the vault's wins, so the RN entry must stay
        val native = VaultAccount("spike-master", AuthType.POSTING_KEY, postingKey = "dummy-native-wif")
        for (n in 0..20) {
            val mem = InMemorySecureStore()
            KeyVault(mem).save(native)
            val storage = MapLegacyStorage(rnData)
            runCatching { run(storage = storage, target = KeyVault(DyingStore(mem, n))) }
            assertTrue("spike-master" in rnUsers(storage), "RN entry deleted by the killed run ($n)")
            run(storage = storage, target = KeyVault(mem))
            assertEquals(native, KeyVault(mem).account("spike-master"))
            assertTrue("spike-master" in rnUsers(storage), "RN entry deleted after a kill at write $n")
            assertEquals(originalUser("spike-master"), rnUser("spike-master", storage))
        }
    }

    @Test
    fun aSignedOutAccountIsNotImportedAgainAndItsRnEntryGoes() {
        vault.save(VaultAccount("spike-master", AuthType.POSTING_KEY, postingKey = "dummy-native-wif"))
        run()
        assertTrue("spike-master" in rnUsers(), "kept: its RN keys differ from the vault's")
        // the user signs it out in the app
        LegacyMigration.forget(vault, rn, listOf("spike-master"))
        vault.remove("spike-master")
        assertFalse("spike-master" in rnUsers(), "the signed-out account's RN entry stayed")
        run()
        assertNull(vault.account("spike-master"), "a signed-out account came back")
        assertEquals(listOf("spike-legacy", "spike-owner"), rnUsers().sorted())
        assertFalse("spike-master" in keptList().orEmpty())
    }

    @Test
    fun aSignOutWhoseRnScrubFailedIsStillNotUndone() {
        vault.save(VaultAccount("spike-master", AuthType.POSTING_KEY, postingKey = "dummy-native-wif"))
        run()
        // RN storage cannot be written at the sign-out (or the app dies right after the vault write)
        val stuck = object : LegacyStorage by rn {
            override fun write(key: String, value: String) = throw LegacyImportError.StorageUnreadable()
            override fun remove(key: String) = throw LegacyImportError.StorageUnreadable()
        }
        LegacyMigration.forget(vault, stuck, listOf("spike-master"))
        vault.remove("spike-master")
        assertTrue("spike-master" in rnUsers())
        run()
        assertNull(vault.account("spike-master"), "a signed-out account came back")
        assertFalse("spike-master" in rnUsers(), "the next run scrubs what the sign-out could not")
    }

    @Test
    fun aSignOutWhoseVaultRecordFailedIsStillNotUndone() {
        vault.save(VaultAccount("spike-master", AuthType.POSTING_KEY, postingKey = "dummy-native-wif"))
        run()
        // the vault refuses the record: the RN entry goes at once instead
        LegacyMigration.forget(KeyVault(DyingStore(store, 0)), rn, listOf("spike-master"))
        vault.remove("spike-master")
        run()
        assertNull(vault.account("spike-master"), "a signed-out account came back")
    }

    @Test
    fun aSignOutThatCanRecordNothingFailsBeforeAnythingGoes() {
        vault.save(VaultAccount("spike-master", AuthType.POSTING_KEY, postingKey = "dummy-native-wif"))
        run()
        val stuck = object : LegacyStorage by rn {
            override fun write(key: String, value: String) = throw LegacyImportError.StorageUnreadable()
        }
        assertFailsWith<Exception> { LegacyMigration.forget(KeyVault(DyingStore(store, 0)), stuck, listOf("spike-master")) }
        assertEquals(originalUser("spike-master"), rnUser("spike-master"))
    }

    @Test
    fun anAccountSignedOutBeforeTheImportIsNotImported() {
        // signed in natively while the RN data could not be opened yet, then signed out
        vault.save(VaultAccount("spike-posting", AuthType.POSTING_KEY, postingKey = "dummy-native-wif"))
        val locked = MapLegacyStorage(rnData).apply { unreadable = true }
        LegacyMigration.forget(vault, locked, listOf("spike-posting"))
        vault.remove("spike-posting")
        locked.unreadable = false
        run(storage = locked)
        assertNull(vault.account("spike-posting"), "a signed-out account came back")
        assertFalse("spike-posting" in rnUsers(locked))
    }
}

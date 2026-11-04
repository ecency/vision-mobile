package com.ecency.core.legacy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LegacyImporterTest {
    private val storage = mapOf("persist:root" to LegacyFixtures.PERSIST_ROOT, "user" to LegacyFixtures.USERS)
    private fun import(
        pinKey: String = LegacyFixtures.PIN_KEY,
        data: Map<String, String> = storage,
        defaultPin: String? = LegacyFixtures.DEFAULT_PIN,
    ) = LegacyImporter.import(data::get, pinKey, defaultPin)

    private fun LegacyImport.account(name: String) = accounts.single { it.username == name }

    @Test
    fun cryptoJsCiphertextDecrypts() {
        assertEquals("crypto-js says hi", CryptoJsAes.decrypt(LegacyFixtures.RAW_CIPHERTEXT, "passphrase"))
    }

    @Test
    fun wrongPassphraseNeverReturnsGarbage() {
        val result = runCatching { CryptoJsAes.decrypt(LegacyFixtures.RAW_CIPHERTEXT, "wrong") }
        assertTrue(result.isFailure || result.getOrNull() != "crypto-js says hi")
        val users = storage.getValue("user")
        val anyStamped = Regex("\"postingKey\":\"([^\"]+)\"").find(users)!!.groupValues[1]
        assertNull(LegacyImporter.decryptStamped(anyStamped, "0000"))
    }

    @Test
    fun masterPasswordAccountKeepsEveryKey() {
        val a = import().account("spike-master")
        assertEquals(LegacyAuthType.MASTER_KEY, a.authType)
        assertEquals(LegacyFixtures.PASSWORD, a.masterPassword)
        assertEquals(LegacyFixtures.POSTING, a.postingKey)
        assertEquals(LegacyFixtures.ACTIVE, a.activeKey)
        assertEquals(LegacyFixtures.MEMO, a.memoKey)
        assertEquals(LegacyFixtures.OWNER, a.ownerKey)
        assertEquals("dummy-ecency-token", a.accessToken)
        assertTrue(a.unreadable.isEmpty())
    }

    @Test
    fun postingKeyAccountHasOnlyItsKey() {
        val a = import().account("spike-posting")
        assertEquals(LegacyAuthType.POSTING_KEY, a.authType)
        assertEquals(LegacyFixtures.POSTING_ONLY, a.postingKey)
        assertNull(a.activeKey)
        assertNull(a.masterPassword)
    }

    @Test
    fun hiveSignerAndHiveAuthSessionsSurvive() {
        val signer = import().account("spike-signer")
        assertEquals(LegacyAuthType.HIVE_SIGNER, signer.authType)
        assertEquals("dummy-hivesigner-access-token", signer.accessToken)
        val has = import().account("spike-has")
        assertEquals(LegacyAuthType.HIVE_AUTH, has.authType)
        assertEquals("00000000-dummy-has-key", has.hiveAuthKey)
        assertEquals("dummy-has-token", has.hiveAuthToken)
        assertEquals(1791288000000L, has.hiveAuthExpiry)
    }

    @Test
    fun unstampedLegacyDataIsReportedNotGuessed() {
        val a = import().account("spike-legacy")
        assertNull(a.postingKey)
        assertEquals(listOf("postingKey"), a.unreadable)
    }

    @Test
    fun currentAccountAndPinLockCarryOver() {
        val result = import()
        assertEquals("spike-posting", result.currentUsername)
        assertTrue(result.pinLockEnabled)
        assertEquals(LegacyFixtures.UNLOCK_PIN, result.unlockPin?.value)
        assertEquals("LegacyPin(<redacted>)", result.unlockPin.toString())
        assertEquals(7, result.accounts.size)
    }

    @Test
    fun secretsUnderDefaultPinOrTheOldUnlockPinAreRecovered() {
        val result = import()
        val fresh = result.account("spike-default")
        assertEquals(LegacyFixtures.DEFAULT_POSTING, fresh.postingKey)
        assertEquals("dummy-ecency-token-4", fresh.accessToken)
        assertTrue(fresh.unreadable.isEmpty())
        val owner = result.account("spike-owner")
        assertEquals(LegacyAuthType.OWNER_KEY, owner.authType)
        assertEquals(LegacyFixtures.OWNER_ONLY, owner.ownerKey)
    }

    @Test
    fun withoutDefaultPinThoseFieldsAreReportedNotGuessed() {
        val fresh = import(defaultPin = null).account("spike-default")
        assertNull(fresh.postingKey)
        assertEquals(listOf("postingKey", "accessToken"), fresh.unreadable)
        // The unlock PIN comes from storage, not from the build.
        assertEquals(LegacyFixtures.OWNER_ONLY, import(defaultPin = null).account("spike-owner").ownerKey)
    }

    @Test
    fun noStoredPinFallsBackToDefaultPin() {
        val noPin = storage + ("persist:root" to """{"account":"{}"}""")
        val result = import(data = noPin)
        assertEquals(LegacyFixtures.DEFAULT_POSTING, result.account("spike-default").postingKey)
        assertFailsWith<LegacyImportError.PinUnavailable> { import(data = noPin, defaultPin = null) }
    }

    @Test
    fun unreadableOrMalformedStorageIsTyped() {
        assertFailsWith<LegacyImportError.StorageUnreadable> {
            LegacyImporter.import({ throw IllegalStateException("disk") }, LegacyFixtures.PIN_KEY)
        }
        assertFailsWith<LegacyImportError.Malformed> { import(data = storage + ("user" to "{not json")) }
        assertFailsWith<LegacyImportError.Malformed> { import(data = storage + ("persist:root" to "[1]")) }
    }

    /** The fixture users, only those named. */
    private fun usersNamed(vararg names: String) = kotlinx.serialization.json.JsonArray(
        kotlinx.serialization.json.Json.parseToJsonElement(LegacyFixtures.USERS).let { it as kotlinx.serialization.json.JsonArray }
            .filter { (it as kotlinx.serialization.json.JsonObject)["username"].toString().trim('"') in names },
    ).toString()

    private val rootWithBrokenAppPin =
        LegacyFixtures.PERSIST_ROOT.replace(Regex("""(\\"pin\\":\\")[^\\]+"""), "$1Z2FyYmFnZQ==")

    @Test
    fun anAppPinThatDoesNotDecryptStillLetsTheOtherPinsTry() {
        assertTrue(rootWithBrokenAppPin != LegacyFixtures.PERSIST_ROOT)
        // these two accounts are under DEFAULT_PIN and the old unlock PIN, not the app PIN
        val result = import(data = mapOf("persist:root" to rootWithBrokenAppPin, "user" to usersNamed("spike-default", "spike-owner")))
        assertEquals(LegacyFixtures.DEFAULT_POSTING, result.account("spike-default").postingKey)
        assertEquals(LegacyFixtures.OWNER_ONLY, result.account("spike-owner").ownerKey)
        assertTrue(result.accounts.all { it.unreadable.isEmpty() })
    }

    @Test
    fun anAppPinThatDoesNotDecryptStopsTheImportWhenAnAccountNeededIt() {
        // retry later rather than import without the accounts only the app PIN opens
        assertFailsWith<LegacyImportError.PinUnavailable> { import(data = storage + ("persist:root" to rootWithBrokenAppPin)) }
    }

    @Test
    fun aUserEntryThatIsNotAnAccountIsSkippedNotFatal() {
        val users = "[42," + LegacyFixtures.USERS.removePrefix("[")
        val result = import(data = storage + ("user" to users))
        assertEquals(7, result.accounts.size)
        assertEquals(LegacyFixtures.POSTING, result.account("spike-master").postingKey)
    }

    @Test
    fun wrongPinKeyStopsTheImport() {
        assertFailsWith<LegacyImportError.PinUnavailable> { import(pinKey = "not-the-pin-key") }
    }

    @Test
    fun noRnDataMeansNothingToImport() {
        assertFailsWith<LegacyImportError.NothingToImport> { import(data = emptyMap()) }
    }

    @Test
    fun accountsNeverPrintTheirSecrets() {
        import().accounts.forEach { a ->
            val printed = a.toString()
            listOfNotNull(a.masterPassword, a.postingKey, a.activeKey, a.accessToken, a.hiveAuthToken).forEach {
                assertFalse(printed.contains(it), "${a.username} leaks a secret")
            }
        }
    }
}

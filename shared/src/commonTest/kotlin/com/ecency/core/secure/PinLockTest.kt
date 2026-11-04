package com.ecency.core.secure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

private fun ByteArray.hex() = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

class Pbkdf2Test {
    /** Every implementation this platform has: the common one, the platform one (if any), and [Pbkdf2.derive]. */
    private fun all(password: ByteArray, salt: ByteArray, iterations: Int, keyLength: Int): List<String> =
        listOfNotNull(
            Pbkdf2.hmacSha256(password, salt, iterations, keyLength),
            Pbkdf2.platform(password, salt, iterations, keyLength),
            Pbkdf2.derive(password, salt, iterations, keyLength),
        ).map { it.hex() }

    private fun assertAll(expected: String, password: String, salt: String, iterations: Int, keyLength: Int) =
        all(password.encodeToByteArray(), salt.encodeToByteArray(), iterations, keyLength).forEach { assertEquals(expected, it) }

    @Test
    fun rfc7914Vectors() {
        assertAll(
            "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783",
            "passwd", "salt", 1, 64,
        )
        assertAll(
            "4ddcd8f60b98be21830cee5ef22701f9641a4418d04c0414aeff08876b34ab56a1d425a1225833549adb841b51c9b3176a272bdebba1d078478f62b397f33c8d",
            "Password", "NaCl", 80_000, 64,
        )
    }

    /** Vectors from Python's hashlib.pbkdf2_hmac: a PIN-shaped input, and a key longer than one block. */
    @Test
    fun hashlibVectors() {
        assertAll("dbdf846e33a6f74a95cb69246c91173bd357252615b70d78ade44a1c2101b293", "1234", "ecency-pin-salt!", 1000, 32)
        assertAll("2c1357648009149f57e4d5544c3435bbca87a6b231300fa3abb2a89b50f56ec3eb3e22ce8267e0fe", "k".repeat(100), "salt", 2, 40)
    }

    /** The platform implementation gives the common one's bytes for every PIN shape and binary salts. */
    @Test
    fun platformMatchesTheCommonImplementation() {
        val salts = listOf(ByteArray(16) { it.toByte() }, ByteArray(16) { (255 - it * 7).toByte() }, secureRandomBytes(16))
        for (pin in listOf("0000", "1234", "9999", "0007")) {
            for (salt in salts) {
                val pure = Pbkdf2.hmacSha256(pin.encodeToByteArray(), salt, 1000, 32)
                Pbkdf2.platform(pin.encodeToByteArray(), salt, 1000, 32)?.let { assertEquals(pure.hex(), it.hex(), "PIN $pin") }
                assertEquals(pure.hex(), Pbkdf2.derive(pin.encodeToByteArray(), salt, 1000, 32).hex())
            }
        }
        // a verifier written by one implementation is read by the other
        val v = PinVerifier.create("2468", 1000)
        assertTrue(PinVerifier.decode(v.encode())!!.matches("2468"))
    }

    @Test
    fun constantTimeEqualsComparesWholeArrays() {
        assertTrue(constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3)))
        assertFalse(constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 4)))
        assertFalse(constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2)))
        assertFalse(constantTimeEquals(byteArrayOf(0), byteArrayOf()))
    }

    /** Prints the cost of one check at the default count on this platform, per implementation (not an assertion). */
    @Test
    fun defaultCostIsReported() {
        val salt = ByteArray(16)
        var mark = TimeSource.Monotonic.markNow()
        Pbkdf2.hmacSha256("1234".encodeToByteArray(), salt, PinLock.DEFAULT_ITERATIONS, 32)
        println("PBKDF2 ${PinLock.DEFAULT_ITERATIONS} iterations, common: ${mark.elapsedNow()}")
        mark = TimeSource.Monotonic.markNow()
        val platform = Pbkdf2.platform("1234".encodeToByteArray(), salt, PinLock.DEFAULT_ITERATIONS, 32)
        println("PBKDF2 ${PinLock.DEFAULT_ITERATIONS} iterations, platform: ${if (platform == null) "none" else mark.elapsedNow().toString()}")
    }
}

class PinLockTest {
    private val store = InMemorySecureStore()
    private val vault = KeyVault(store)

    /** A fresh PinLock over the same store: what the app sees after a restart. */
    private fun restarted() = PinLock(KeyVault(store), ITERATIONS)

    private fun signedIn(): PinLock {
        vault.save(VaultAccount("spike-pin", AuthType.POSTING_KEY, postingKey = "5Jdummy"))
        vault.setCurrentUsername("spike-pin")
        return PinLock(vault, ITERATIONS)
    }

    @Test
    fun verifierIsSaltedHashedAndRedacted() {
        val lock = signedIn()
        lock.setPin("1234")
        val stored = store.entries.getValue("pin.verifier").decodeToString()
        assertFalse("1234" in stored)
        assertTrue(stored.startsWith("pbkdf2-sha256\$$ITERATIONS\$"))
        // same PIN, new salt: a different verifier
        val other = PinVerifier.create("1234", ITERATIONS)
        assertNotEquals(stored, other.encode())
        assertTrue(other.matches("1234"))
        assertFalse(other.matches("1235"))
        assertEquals("PinVerifier(<redacted>)", other.toString())
        assertNull(PinVerifier.decode("plain:1234"))
        assertNull(PinVerifier.decode("pbkdf2-sha256\$0\$AAAA\$AAAA"))
    }

    @Test
    fun noStoreEntryEverHoldsThePin() {
        val lock = signedIn()
        lock.setPin("8642")
        lock.verify("1111")
        lock.verify("8642")
        lock.changePin("8642", "9753")
        store.entries.forEach { (name, value) ->
            assertFalse("8642" in value.decodeToString(), name)
            assertFalse("9753" in value.decodeToString(), name)
        }
    }

    @Test
    fun setPinEnablesTheLockAndRejectsBadPins() {
        val lock = signedIn()
        assertFalse(lock.isEnabled())
        listOf("", "123", "12345", "12a4", "١٢٣٤").forEach { bad ->
            assertFailsWith<SecureStoreException.InvalidArgument> { lock.setPin(bad) }
        }
        lock.setPin("1234")
        assertTrue(lock.isEnabled())
        assertEquals(PinCheck.Success, lock.verify("1234"))
        // a second setPin would skip the old-PIN check
        assertFailsWith<SecureStoreException.InvalidArgument> { lock.setPin("5678") }
    }

    @Test
    fun aFlagWithoutAPinIsNotALock() {
        vault.setPinLockEnabled(true)
        val lock = PinLock(vault, ITERATIONS)
        assertFalse(lock.isEnabled())
        assertEquals(PinCheck.NoPin, lock.verify("1234"))
    }

    @Test
    fun wrongPinsCountAndTheThirdWipesEverything() {
        val lock = signedIn()
        lock.setPin("1234")
        assertEquals(PinCheck.Wrong(1, 2), lock.verify("0000"))
        assertEquals(PinCheck.Wrong(2, 1), lock.verify("9999"))
        assertEquals(2, lock.failedAttempts())
        assertEquals(PinCheck.Wiped, lock.verify("1111"))
        assertTrue(vault.accounts().isEmpty())
        assertNull(vault.currentUsername())
        assertFalse(lock.isEnabled())
        assertFalse(lock.hasPin())
        // wiped means migrated: an RN copy left on disk is never imported back
        assertTrue(vault.legacyImported())
        assertEquals(setOf("vault.meta"), store.names())
    }

    @Test
    fun theCounterSurvivesARestart() {
        signedIn().setPin("1234")
        assertEquals(PinCheck.Wrong(1, 2), restarted().verify("0000"))
        assertEquals(PinCheck.Wrong(2, 1), restarted().verify("0000"))
        assertEquals(2, restarted().failedAttempts())
        assertEquals(PinCheck.Wiped, restarted().verify("0000"))
        assertTrue(KeyVault(store).accounts().isEmpty())
    }

    @Test
    fun theAttemptIsCountedBeforeTheCheck() {
        val lock = signedIn()
        lock.setPin("1234")
        // a store that dies on the write after the counter: the counter must already be written
        val counted = mutableListOf<String?>()
        val spy = object : SecureStore by store {
            override fun put(name: String, value: ByteArray) {
                if (name == "pin.failures") counted += value.decodeToString()
                store.put(name, value)
            }

            // delegation would send putString straight to the wrapped store
            override fun putString(name: String, value: String) = put(name, value.encodeToByteArray())
        }
        assertEquals(PinCheck.Success, PinLock(KeyVault(spy), ITERATIONS).verify("1234"))
        assertEquals(listOf<String?>("1", "0"), counted)
    }

    @Test
    fun successResetsTheCounter() {
        val lock = signedIn()
        lock.setPin("1234")
        lock.verify("0000")
        lock.verify("0000")
        assertEquals(PinCheck.Success, restarted().verify("1234"))
        assertEquals(0, restarted().failedAttempts())
        assertEquals(PinCheck.Wrong(1, 2), restarted().verify("0000"))
        assertEquals(1, vault.accounts().size)
    }

    @Test
    fun aDeletedCounterLeavesOneAttempt() {
        signedIn().setPin("1234")
        store.entries.remove("pin.failures")
        assertEquals(2, restarted().failedAttempts())
        assertEquals(PinCheck.Wiped, restarted().verify("0000"))
    }

    @Test
    fun anUnfinishedWipeIsFinishedBeforeAnyCheck() {
        signedIn().setPin("1234")
        store.put("pin.failures", "3".encodeToByteArray())
        assertEquals(PinCheck.Wiped, restarted().verify("1234"))
        assertTrue(vault.accounts().isEmpty())
    }

    @Test
    fun changePinChecksTheOldOneAndCounts() {
        val lock = signedIn()
        lock.setPin("1234")
        assertEquals(PinCheck.Wrong(1, 2), lock.changePin("0000", "5678"))
        assertEquals(PinCheck.Success, lock.changePin("1234", "5678"))
        assertEquals(0, lock.failedAttempts())
        assertEquals(PinCheck.Wrong(1, 2), lock.verify("1234"))
        assertEquals(PinCheck.Success, restarted().verify("5678"))
        assertFailsWith<SecureStoreException.InvalidArgument> { lock.changePin("5678", "12") }
        lock.verify("0000")
        lock.verify("0000")
        assertEquals(PinCheck.Wiped, lock.changePin("0000", "1111"))
        assertTrue(vault.accounts().isEmpty())
    }

    @Test
    fun disableNeedsThePin() {
        val lock = signedIn()
        lock.setPin("1234")
        assertEquals(PinCheck.Wrong(1, 2), lock.disable("0000"))
        assertTrue(lock.isEnabled())
        assertEquals(PinCheck.Success, lock.disable("1234"))
        assertFalse(lock.isEnabled())
        assertFalse(lock.hasPin())
        assertFalse("pin.verifier" in store.names() || "pin.failures" in store.names())
        assertEquals(1, vault.accounts().size)
        lock.setPin("4321")
        assertTrue(restarted().isEnabled())
    }

    @Test
    fun biometricUnlockResetsTheCounterButNeverPassesAnExhaustedOne() {
        val lock = signedIn()
        lock.setPin("1234")
        assertFailsWith<SecureStoreException.InvalidArgument> { lock.unlockWithBiometric(BINDING) }
        assertEquals(PinCheck.Wrong(1, 2), lock.setBiometricEnabled("0000", true) { BINDING })
        assertFalse(lock.isBiometricEnabled())
        assertEquals(PinCheck.Success, lock.setBiometricEnabled("1234", true) { BINDING })
        assertTrue(restarted().isBiometricEnabled())
        lock.verify("0000")
        lock.verify("0000")
        assertEquals(PinCheck.Success, restarted().unlockWithBiometric(BINDING))
        assertEquals(0, lock.failedAttempts())
        store.put("pin.failures", "3".encodeToByteArray())
        assertEquals(PinCheck.Wiped, restarted().unlockWithBiometric(BINDING))
        assertTrue(vault.accounts().isEmpty())
    }

    @Test
    fun clearWithoutPinTurnsEverythingOff() {
        val lock = signedIn()
        lock.setPin("1234")
        lock.setBiometricEnabled("1234", true) { BINDING }
        lock.clearWithoutPin()
        assertFalse(lock.isEnabled())
        assertFalse(vault.biometricUnlockEnabled())
        assertFalse(vault.pinLockEnabled())
    }

    @Test
    fun unreadableMetaKeepsTheLockOn() {
        val lock = signedIn()
        lock.setPin("1234")
        store.putString("vault.meta", "not json")
        assertTrue(restarted().isEnabled(), "a verifier exists but the lock reads as off")
        assertEquals(PinCheck.Wrong(1, 2), restarted().verify("0000"))
    }

    @Test
    fun aNegativeCounterLeavesOneAttempt() {
        signedIn().setPin("1234")
        store.putString("pin.failures", "-100")
        assertEquals(PinLock.MAX_ATTEMPTS - 1, restarted().failedAttempts())
        assertEquals(PinCheck.Wiped, restarted().verify("0000"))
        assertTrue(KeyVault(store).accounts().isEmpty())
    }

    @Test
    fun aWipeThatFailsAfterClearingStillMarksTheMigrationDone() {
        signedIn().setPin("1234")
        // Android: the ciphertext is cleared, then deleting the Keystore key throws
        val failing = object : SecureStore by store {
            override fun wipe() {
                store.wipe()
                throw SecureStoreException.Unavailable(-1)
            }

            override fun putString(name: String, value: String) = store.putString(name, value)
        }
        val lock = PinLock(KeyVault(failing), ITERATIONS)
        assertFailsWith<SecureStoreException.Unavailable> { lock.wipeAll() }
        assertTrue(KeyVault(store).legacyImported())
        assertTrue(KeyVault(store).accounts().isEmpty())
        assertFalse(restarted().isEnabled())
    }

    @Test
    fun aChangedEnrollmentTurnsBiometricUnlockOffAndNeedsThePin() {
        val lock = signedIn()
        lock.setPin("1234")
        lock.verify("0000")
        assertEquals(PinCheck.Success, lock.setBiometricEnabled("1234", true) { BINDING })
        lock.verify("0000")
        assertEquals(PinCheck.BiometricChanged, restarted().unlockWithBiometric("another-enrollment"))
        assertFalse(restarted().isBiometricEnabled())
        assertFalse(vault.biometricUnlockEnabled())
        // nothing unlocked, nothing reset
        assertEquals(1, restarted().failedAttempts())
        assertFailsWith<SecureStoreException.InvalidArgument> { restarted().unlockWithBiometric(BINDING) }
    }

    @Test
    fun aChangedEnrollmentNeverSkipsAPendingWipe() {
        val lock = signedIn()
        lock.setPin("1234")
        lock.setBiometricEnabled("1234", true) { BINDING }
        store.putString("pin.failures", "3")
        assertEquals(PinCheck.Wiped, restarted().unlockWithBiometric("another-enrollment"))
        assertTrue(vault.accounts().isEmpty())
    }

    @Test
    fun biometricsStayOffWithoutABindingOrAfterARevoke() {
        val lock = signedIn()
        lock.setPin("1234")
        assertEquals(PinCheck.Success, lock.setBiometricEnabled("1234", true) { null })
        assertFalse(lock.isBiometricEnabled())
        lock.setBiometricEnabled("1234", true) { BINDING }
        assertTrue(lock.isBiometricEnabled())
        lock.revokeBiometric()
        assertFalse(lock.isBiometricEnabled())
        assertFalse(lock.biometricNeedsBinding())
        assertFalse("pin.biometric" in store.names())
    }

    @Test
    fun theBinderRunsOnlyAfterACorrectPin() {
        val lock = signedIn()
        lock.setPin("1234")
        var calls = 0
        lock.setBiometricEnabled("0000", true) { calls++; BINDING }
        assertEquals(0, calls)
        lock.setBiometricEnabled("1234", true) { calls++; BINDING }
        assertEquals(1, calls)
    }

    @Test
    fun aVerifiedChangeHashesOnce() {
        val lock = signedIn()
        lock.setPin("1234")
        assertEquals(PinCheck.Success, lock.verify("1234"))
        lock.replaceVerifiedPin("5678")
        assertEquals(PinCheck.Success, restarted().verify("5678"))
        assertFailsWith<SecureStoreException.InvalidArgument> { lock.replaceVerifiedPin("12") }
        lock.clearWithoutPin()
        assertFailsWith<SecureStoreException.InvalidArgument> { lock.replaceVerifiedPin("1111") }
    }

    companion object {
        /** Low cost for tests; the default is checked by [Pbkdf2Test.defaultCostIsReported]. */
        const val ITERATIONS = 16
        const val BINDING = "enrolled-at-enable"
    }
}

package com.ecency.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ecency.core.secure.AndroidKeystoreSecureStore
import com.ecency.core.secure.AuthType
import com.ecency.core.secure.KeyVault
import com.ecency.core.secure.SecureStoreException
import com.ecency.core.secure.VaultAccount
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/** Round trips through the real Android Keystore; Robolectric has no AndroidKeyStore provider. */
@RunWith(AndroidJUnit4::class)
class KeystoreSecureStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val names = listOf("test.secure.a", "test.secure.b")

    private fun store(name: String = names[0]) = AndroidKeystoreSecureStore(context, name)
    private fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    @Before
    @After
    fun clean() = names.forEach { store(it).wipe() }

    @Test
    fun valuesRoundTripAcrossInstances() {
        val bytes = ByteArray(1024) { (it * 7).toByte() }
        store().put("raw.bytes", bytes)
        store().putString("raw.text", "throwaway é🐝")
        store().put("raw.empty", ByteArray(0))
        assertArrayEquals(bytes, store().get("raw.bytes"))
        assertEquals("throwaway é🐝", store().getString("raw.text"))
        assertArrayEquals(ByteArray(0), store().get("raw.empty"))
        assertEquals(setOf("raw.bytes", "raw.text", "raw.empty"), store().names())
        assertNull(store().get("missing"))
    }

    @Test
    fun ciphertextOnDiskIsNotThePlaintext() {
        store().putString("raw.text", "dummy-plaintext-marker")
        val onDisk = prefs(names[0]).getString("raw.text", null)!!
        assertFalse(onDisk.contains("dummy-plaintext-marker"))
        // Fresh IV per write: the same value never seals the same way twice.
        store().putString("raw.text", "dummy-plaintext-marker")
        assertFalse(onDisk == prefs(names[0]).getString("raw.text", null))
    }

    @Test
    fun keyIsNonExportableAes() {
        store().putString("raw.text", "x")
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = ks.getKey("${names[0]}.aes", null)
        assertEquals("AES", key.algorithm)
        assertNull("Keystore keys must not expose raw bytes", key.encoded)
    }

    @Test
    fun ciphertextUnderAnotherKeyDoesNotDecrypt() {
        store(names[0]).putString("raw.text", "dummy-a")
        store(names[1]).putString("raw.text", "dummy-b")
        val fromA = prefs(names[0]).getString("raw.text", null)
        prefs(names[1]).edit().putString("raw.text", fromA).commit()
        assertThrows(SecureStoreException.Corrupted::class.java) { store(names[1]).getString("raw.text") }
    }

    @Test
    fun ciphertextMovedToAnotherEntryDoesNotDecrypt() {
        store().putString("entry.one", "dummy-one")
        store().putString("entry.two", "dummy-two")
        val one = prefs(names[0]).getString("entry.one", null)
        prefs(names[0]).edit().putString("entry.two", one).commit()
        assertThrows(SecureStoreException.Corrupted::class.java) { store().getString("entry.two") }
    }

    @Test
    fun tamperedCiphertextIsCorrupted() {
        store().putString("raw.text", "dummy")
        prefs(names[0]).edit().putString("raw.text", "AAAA").commit()
        assertThrows(SecureStoreException.Corrupted::class.java) { store().getString("raw.text") }
        prefs(names[0]).edit().putString("raw.text", "not base64 !!").commit()
        assertThrows(SecureStoreException.Corrupted::class.java) { store().getString("raw.text") }
    }

    @Test
    fun lostKeyIsTypedAndWipeRecovers() {
        store().putString("raw.text", "dummy")
        // What a restore onto a new device looks like: ciphertext present, Keystore key gone.
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry("${names[0]}.aes")
        assertThrows(SecureStoreException.KeyInvalidated::class.java) { store().getString("raw.text") }
        // A new key must not be minted silently next to unreadable values.
        assertThrows(SecureStoreException.KeyInvalidated::class.java) { store().putString("raw.other", "dummy") }
        store().wipe()
        assertTrue(store().names().isEmpty())
        store().putString("raw.text", "dummy-again")
        assertEquals("dummy-again", store().getString("raw.text"))
    }

    @Test
    fun deleteAndWipe() {
        store().putString("a.one", "1")
        store().putString("a.two", "2")
        store().delete("a.one")
        assertEquals(setOf("a.two"), store().names())
        store().wipe()
        assertTrue(store().names().isEmpty())
        assertFalse(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias("${names[0]}.aes"))
    }

    @Test
    fun keyVaultOverTheKeystore() {
        val account = VaultAccount(
            "device-test",
            AuthType.POSTING_KEY,
            postingKey = "dummy-posting-wif",
            accessToken = "dummy-token",
        )
        KeyVault(store()).apply {
            save(account)
            setCurrentUsername("device-test")
        }
        val reopened = KeyVault(store())
        assertEquals(account, reopened.account("device-test"))
        assertEquals("device-test", reopened.currentUsername())
    }

    @Test
    fun buildSecretsAreInjectedIntoDebugBuilds() {
        assertTrue(BuildConfig.PIN_KEY.isNotEmpty())
        assertTrue(BuildConfig.DEFAULT_PIN.isNotEmpty())
    }

    /** Separate instances over one name race the first key creation; nothing may be lost. */
    @Test
    fun separateInstancesRacingTheFirstWriteLoseNothing() {
        repeat(15) { round ->
            store().wipe()
            val start = CountDownLatch(1)
            val failures = ConcurrentHashMap.newKeySet<Throwable>()
            val threads = (0 until 4).map { i ->
                thread {
                    start.await()
                    runCatching { store().putString("e.$i", "dummy-$i") }.onFailure { failures += it }
                }
            }
            start.countDown()
            threads.forEach { it.join() }
            assertTrue("round $round: $failures", failures.isEmpty())
            (0 until 4).forEach { i -> assertEquals("round $round", "dummy-$i", store().getString("e.$i")) }
        }
    }

    /** Read-modify-write of the vault meta from many threads and instances keeps every update. */
    @Test
    fun concurrentMetaUpdatesAreNotLost() {
        KeyVault(store()).save(VaultAccount("race-test", AuthType.POSTING_KEY, postingKey = "dummy"))
        repeat(10) { round ->
            KeyVault(store()).setCurrentUsername(null)
            KeyVault(store()).setPinLockEnabled(false)
            val start = CountDownLatch(1)
            val threads = listOf(
                thread { start.await(); KeyVault(store()).setPinLockEnabled(true) },
                thread { start.await(); KeyVault(store()).setCurrentUsername("race-test") },
                thread { start.await(); KeyVault(store()).markLegacyImported() },
            )
            start.countDown()
            threads.forEach { it.join() }
            val vault = KeyVault(store())
            assertTrue("round $round", vault.pinLockEnabled())
            assertEquals("round $round", "race-test", vault.currentUsername())
            assertTrue("round $round", vault.legacyImported())
        }
    }
}

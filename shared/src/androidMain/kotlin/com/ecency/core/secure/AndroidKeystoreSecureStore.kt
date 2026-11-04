package com.ecency.core.secure

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.io.encoding.Base64

/**
 * [SecureStore] on Android: each value is sealed with AES-256-GCM under a non-exportable key in the
 * Android Keystore (TEE or StrongBox backed where the device has one) and the ciphertext is kept in a
 * private SharedPreferences file. The entry name is the GCM associated data, so a ciphertext copied onto
 * another entry does not decrypt.
 *
 * Keep [name]'s preferences file out of backups (androidApp's backup rules do): a restored ciphertext
 * without its Keystore key is unreadable and surfaces as [SecureStoreException.KeyInvalidated].
 *
 * Every instance over the same [name] shares one process-wide lock, so separate instances cannot race
 * the key creation or interleave writes. Calls do Keystore and disk I/O: keep them off the main thread.
 *
 * @param name selects both the Keystore alias and the preferences file; use one store per purpose.
 */
class AndroidKeystoreSecureStore(context: Context, name: String = DEFAULT_NAME) : SecureStore {
    init {
        SecureStore.requireValidName(name)
    }

    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val alias = "$name.aes"
    private val keyStore: KeyStore by lazy { KeyStore.getInstance(PROVIDER).apply { load(null) } }
    private val lock: Any = LOCKS.computeIfAbsent(name) { Any() }

    override fun <T> withLock(block: () -> T): T = synchronized(lock) { block() }

    override fun put(name: String, value: ByteArray) = withLock {
        SecureStore.requireValidName(name)
        val sealed = keystore { seal(key(createIfMissing = true), name, value) }
        if (!prefs.edit().putString(name, Base64.encode(sealed)).commit()) {
            throw SecureStoreException.Unavailable(STATUS_WRITE_FAILED)
        }
    }

    override fun get(name: String): ByteArray? = withLock {
        SecureStore.requireValidName(name)
        val stored = prefs.getString(name, null) ?: return@withLock null
        val sealed = runCatching { Base64.decode(stored) }.getOrElse { throw SecureStoreException.Corrupted(name, it) }
        keystore {
            try {
                open(key(createIfMissing = false), name, sealed)
            } catch (e: AEADBadTagException) {
                throw SecureStoreException.Corrupted(name, e)
            }
        }
    }

    override fun delete(name: String) = withLock {
        SecureStore.requireValidName(name)
        if (!prefs.edit().remove(name).commit()) throw SecureStoreException.Unavailable(STATUS_WRITE_FAILED)
    }

    override fun names(): Set<String> = withLock { prefs.all.keys.toSet() }

    /**
     * The ciphertext is what holds the data, so clearing it is the wipe. Deleting the key afterwards is
     * best effort: a key left behind protects nothing (no ciphertext) and is reused by the next write.
     */
    override fun wipe() = withLock {
        if (!prefs.edit().clear().commit()) throw SecureStoreException.Unavailable(STATUS_WRITE_FAILED)
        runCatching { if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias) }
        Unit
    }

    /**
     * The store's AES key; only called holding [lock], so the alias check, the "no key next to existing
     * ciphertext" check and the key creation are one step for every instance in the process.
     * A missing key with ciphertext already on disk means the original key is gone (restore to a new
     * device, Keystore reset): creating a fresh one would silently orphan those values.
     */
    private fun key(createIfMissing: Boolean): SecretKey {
        (keyStore.getKey(alias, null) as SecretKey?)?.let { return it }
        if (!createIfMissing || prefs.all.isNotEmpty()) throw SecureStoreException.KeyInvalidated()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).run {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    /** Maps Keystore failures to typed errors; anything already typed passes through. */
    private inline fun <T> keystore(block: () -> T): T = try {
        block()
    } catch (e: SecureStoreException) {
        throw e
    } catch (e: KeyPermanentlyInvalidatedException) {
        throw SecureStoreException.KeyInvalidated(e)
    } catch (e: UnrecoverableKeyException) {
        // Not necessarily permanent (Keystore daemon hiccups have produced it); let the user decide.
        throw SecureStoreException.Unavailable(STATUS_KEYSTORE, e)
    } catch (e: GeneralSecurityException) {
        throw SecureStoreException.Unavailable(STATUS_KEYSTORE, e)
    } catch (e: java.security.ProviderException) {
        throw SecureStoreException.Unavailable(STATUS_KEYSTORE, e)
    }

    internal companion object {
        const val DEFAULT_NAME = "ecency.secure"
        private const val PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val FORMAT_V1: Byte = 1
        private const val IV_SIZE = 12
        private const val TAG_BITS = 128
        const val STATUS_KEYSTORE = -1
        const val STATUS_WRITE_FAILED = -2

        /** One lock per store name for the whole process. */
        private val LOCKS = java.util.concurrent.ConcurrentHashMap<String, Any>()

        /** version (1) | IV (12) | ciphertext + tag; the Keystore picks the IV. */
        fun seal(key: SecretKey, name: String, value: ByteArray): ByteArray {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
            cipher.updateAAD(name.encodeToByteArray())
            val iv = cipher.iv
            check(iv.size == IV_SIZE)
            return byteArrayOf(FORMAT_V1) + iv + cipher.doFinal(value)
        }

        fun open(key: SecretKey, name: String, sealed: ByteArray): ByteArray {
            if (sealed.size < 1 + IV_SIZE + TAG_BITS / 8 || sealed[0] != FORMAT_V1) {
                throw SecureStoreException.Corrupted(name)
            }
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed, 1, IV_SIZE))
            }
            cipher.updateAAD(name.encodeToByteArray())
            return cipher.doFinal(sealed, 1 + IV_SIZE, sealed.size - 1 - IV_SIZE)
        }
    }
}

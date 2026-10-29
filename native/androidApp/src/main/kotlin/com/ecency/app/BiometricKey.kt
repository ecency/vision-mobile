package com.ecency.app

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * The Keystore key that binds biometric unlock to the fingerprints and faces enrolled when it was turned on
 * ([com.ecency.core.secure.BiometricBinder]). It needs a strong biometric for every use and the Keystore
 * invalidates it when a biometric is added or all are removed, so the lock screen's [BiometricPrompt] runs
 * with a cipher from it: an invalidated key means the enrollment changed, and biometric unlock goes off.
 */
object BiometricKey {
    private const val ALIAS = "ecency.biometric.unlock"
    private const val PROVIDER = "AndroidKeyStore"

    /** What the vault stores as the binding on Android; the key itself is the real check. */
    const val BINDING = "android-keystore-v1"

    /** A cipher for the prompt, or why there is none. */
    sealed class Use {
        class Ready(val cipher: Cipher) : Use()

        /** The key was invalidated (enrollment changed) or is gone: biometric unlock must go off. */
        data object Invalidated : Use()

        /** The Keystore failed for another reason; leave biometric unlock as it is and use the PIN now. */
        data object Unavailable : Use()
    }

    /** Creates a fresh key (replacing any old one) and returns [BINDING]; null when it cannot. */
    fun create(): String? = try {
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .apply {
                // every use needs a strong biometric (no device credential, no validity window)
                if (Build.VERSION.SDK_INT >= 30) setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
            }
            .build()
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).run {
            init(spec)
            generateKey()
        }
        BINDING
    } catch (_: Exception) {
        null
    }

    /** A cipher bound to the key, to hand to the biometric prompt as its CryptoObject. */
    fun use(): Use = try {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        val key = keyStore.getKey(ALIAS, null) as SecretKey?
        if (key == null) {
            Use.Invalidated
        } else {
            Use.Ready(Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) })
        }
    } catch (e: Exception) {
        failure(e)
    }

    /**
     * What a Keystore failure while getting or initialising the key means. Only a key the Keystore says is
     * permanently invalidated ([KeyPermanentlyInvalidatedException]: the enrollment changed) turns biometric
     * unlock off; a missing key is handled in [use]. Anything else, [java.security.UnrecoverableKeyException] and other
     * [java.security.InvalidKeyException]s included, may be transient (as `AndroidKeystoreSecureStore` treats them): the PIN is
     * asked this time and the setting stays on.
     */
    internal fun failure(e: Exception): Use = when (e) {
        is KeyPermanentlyInvalidatedException -> Use.Invalidated
        else -> Use.Unavailable
    }

    /** After the prompt succeeded: the authenticated cipher works only if the key was really unlocked by it. */
    fun proves(cipher: Cipher?): Boolean = try {
        cipher != null && cipher.doFinal(ByteArray(16)).isNotEmpty()
    } catch (_: Exception) {
        false
    }
}

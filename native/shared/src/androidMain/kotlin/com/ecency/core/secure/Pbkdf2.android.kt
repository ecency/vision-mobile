package com.ecency.core.secure

import android.os.Build
import android.os.SystemClock
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

private val random = SecureRandom()

/**
 * `PBKDF2WithHmacSHA256` (Android 8+). It takes the password as chars and hashes their UTF-8 bytes, so only
 * passwords that are valid UTF-8 go through it (PINs always are); anything else, Android 7 and the JVM host
 * tests (android.jar stubs) return null and use the common implementation.
 */
internal actual fun platformPbkdf2HmacSha256(password: ByteArray, salt: ByteArray, iterations: Int, keyLength: Int): ByteArray? {
    val sdk = try {
        Build.VERSION.SDK_INT
    } catch (_: Throwable) {
        0
    }
    if (sdk < 26 || password.isEmpty() || salt.isEmpty()) return null
    val text = password.decodeToString()
    if (!text.encodeToByteArray().contentEquals(password)) return null
    val chars = text.toCharArray()
    val spec = PBEKeySpec(chars, salt, iterations, keyLength * 8)
    return try {
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    } catch (_: Exception) {
        null
    } finally {
        spec.clearPassword()
        chars.fill('\u0000')
    }
}

internal actual fun secureRandomBytes(count: Int): ByteArray = ByteArray(count).also { random.nextBytes(it) }

actual fun monotonicMillis(): Long = try {
    SystemClock.elapsedRealtime()
} catch (_: RuntimeException) {
    // host tests without Robolectric: android.jar stubs throw
    System.nanoTime() / 1_000_000
}

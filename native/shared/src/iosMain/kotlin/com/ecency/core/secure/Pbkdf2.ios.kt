package com.ecency.core.secure

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CCKeyDerivationPBKDF
import platform.CoreCrypto.kCCPBKDF2
import platform.CoreCrypto.kCCPRFHmacAlgSHA256
import platform.CoreCrypto.kCCSuccess
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault
import platform.posix.CLOCK_MONOTONIC_RAW
import platform.posix.clock_gettime_nsec_np

/**
 * CommonCrypto `CCKeyDerivationPBKDF`. Its `const char *password` is bridged as a Kotlin String (passed as
 * UTF-8), so only passwords that are valid UTF-8 without NUL bytes go through it (PINs always are); null
 * for anything else, an empty salt, or a failure, and the common implementation is used.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun platformPbkdf2HmacSha256(password: ByteArray, salt: ByteArray, iterations: Int, keyLength: Int): ByteArray? {
    if (password.isEmpty() || salt.isEmpty() || password.any { it == 0.toByte() }) return null
    val text = password.decodeToString()
    if (!text.encodeToByteArray().contentEquals(password)) return null
    val out = ByteArray(keyLength)
    val status = salt.usePinned { s ->
        out.usePinned { o ->
            CCKeyDerivationPBKDF(
                kCCPBKDF2, text, password.size.toULong(),
                s.addressOf(0).reinterpret<UByteVar>(), salt.size.toULong(),
                kCCPRFHmacAlgSHA256, iterations.toUInt(),
                o.addressOf(0).reinterpret<UByteVar>(), keyLength.toULong(),
            )
        }
    }
    if (status != kCCSuccess) {
        out.fill(0)
        return null
    }
    return out
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun secureRandomBytes(count: Int): ByteArray {
    val out = ByteArray(count)
    if (count == 0) return out
    val status = out.usePinned { SecRandomCopyBytes(kSecRandomDefault, count.toULong(), it.addressOf(0)) }
    if (status != errSecSuccess) throw SecureStoreException.Unavailable(status)
    return out
}

/** CLOCK_MONOTONIC_RAW on Darwin keeps counting while the device sleeps (mach_continuous_time). */
@OptIn(ExperimentalForeignApi::class)
actual fun monotonicMillis(): Long = (clock_gettime_nsec_np(CLOCK_MONOTONIC_RAW.toUInt()) / 1_000_000u).toLong()

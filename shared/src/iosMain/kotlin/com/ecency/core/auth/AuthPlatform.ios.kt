package com.ecency.core.auth

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreCrypto.CCCrypt
import platform.CoreCrypto.kCCAlgorithmAES
import platform.CoreCrypto.kCCEncrypt
import platform.CoreCrypto.kCCOptionPKCS7Padding
import platform.CoreCrypto.kCCSuccess
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault
import platform.posix.size_tVar

@OptIn(ExperimentalForeignApi::class)
internal actual fun aesCbcEncrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
    val out = ByteArray(data.size + 16)
    return memScoped {
        val moved = alloc<size_tVar>()
        // An empty plaintext still encrypts to one padding block; pin a 1-byte array so addressOf(0) is valid.
        val input = if (data.isEmpty()) ByteArray(1) else data
        val status = key.usePinned { k ->
            iv.usePinned { i ->
                input.usePinned { d ->
                    out.usePinned { o ->
                        CCCrypt(
                            kCCEncrypt.convert(), kCCAlgorithmAES.convert(), kCCOptionPKCS7Padding.convert(),
                            k.addressOf(0), key.size.convert(), i.addressOf(0),
                            d.addressOf(0), data.size.convert(),
                            o.addressOf(0), out.size.convert(), moved.ptr,
                        )
                    }
                }
            }
        }
        require(status == kCCSuccess) { "AES encryption failed ($status)" }
        out.copyOf(moved.value.toInt())
    }
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun secureRandomBytes(size: Int): ByteArray {
    val out = ByteArray(size)
    if (size == 0) return out
    val status = out.usePinned { SecRandomCopyBytes(kSecRandomDefault, size.convert(), it.addressOf(0)) }
    check(status == errSecSuccess) { "secure random failed ($status)" }
    return out
}

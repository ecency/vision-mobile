package com.ecency.core.legacy

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
import platform.CoreCrypto.kCCDecrypt
import platform.CoreCrypto.kCCEncrypt
import platform.CoreCrypto.kCCOptionPKCS7Padding
import platform.CoreCrypto.kCCSuccess
import platform.posix.size_tVar

internal actual fun aesCbcDecrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
    require(data.isNotEmpty() && data.size % 16 == 0) { "ciphertext must be whole AES blocks" }
    return crypt(false, key, iv, data)
}

internal actual fun aesCbcEncrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray = crypt(true, key, iv, data)

@OptIn(ExperimentalForeignApi::class)
private fun crypt(encrypt: Boolean, key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
    val out = ByteArray(data.size + 16)
    return memScoped {
        val moved = alloc<size_tVar>()
        val status = key.usePinned { k ->
            iv.usePinned { i ->
                data.usePinned { d ->
                    out.usePinned { o ->
                        CCCrypt(
                            (if (encrypt) kCCEncrypt else kCCDecrypt).convert(), kCCAlgorithmAES.convert(), kCCOptionPKCS7Padding.convert(),
                            k.addressOf(0), key.size.convert(), i.addressOf(0),
                            (if (data.isEmpty()) null else d.addressOf(0)), data.size.convert(),
                            o.addressOf(0), out.size.convert(), moved.ptr,
                        )
                    }
                }
            }
        }
        require(status == kCCSuccess) { "AES failed ($status)" }
        out.copyOf(moved.value.toInt())
    }
}

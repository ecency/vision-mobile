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
import platform.CoreCrypto.kCCOptionPKCS7Padding
import platform.CoreCrypto.kCCSuccess
import platform.posix.size_tVar

@OptIn(ExperimentalForeignApi::class)
internal actual fun aesCbcDecrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
    require(data.isNotEmpty() && data.size % 16 == 0) { "ciphertext must be whole AES blocks" }
    val out = ByteArray(data.size + 16)
    return memScoped {
        val moved = alloc<size_tVar>()
        val status = key.usePinned { k ->
            iv.usePinned { i ->
                data.usePinned { d ->
                    out.usePinned { o ->
                        CCCrypt(
                            kCCDecrypt.convert(), kCCAlgorithmAES.convert(), kCCOptionPKCS7Padding.convert(),
                            k.addressOf(0), key.size.convert(), i.addressOf(0),
                            d.addressOf(0), data.size.convert(),
                            o.addressOf(0), out.size.convert(), moved.ptr,
                        )
                    }
                }
            }
        }
        require(status == kCCSuccess) { "AES decryption failed ($status)" }
        out.copyOf(moved.value.toInt())
    }
}

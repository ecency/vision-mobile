package com.ecency.core.hive

import org.kotlincrypto.hash.sha2.SHA256

internal fun ByteArray.toHex(): String =
    joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

internal fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "hex string has an odd length" }
    return ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

internal fun sha256(input: ByteArray): ByteArray = SHA256().digest(input)

internal fun doubleSha256(input: ByteArray): ByteArray = sha256(sha256(input))

package com.ecency.core.auth

/** AES-CBC encryption with PKCS#7 padding, backed by the platform's own crypto. */
internal expect fun aesCbcEncrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray

/** [size] bytes from the platform's cryptographically secure random source. */
internal expect fun secureRandomBytes(size: Int): ByteArray

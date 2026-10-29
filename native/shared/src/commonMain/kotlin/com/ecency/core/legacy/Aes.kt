package com.ecency.core.legacy

/** AES-CBC decryption with PKCS#7 padding, backed by the platform's own crypto. */
internal expect fun aesCbcDecrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray

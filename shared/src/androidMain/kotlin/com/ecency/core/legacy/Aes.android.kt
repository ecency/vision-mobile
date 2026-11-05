package com.ecency.core.legacy

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal actual fun aesCbcDecrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray = aesCbc(Cipher.DECRYPT_MODE, key, iv, data)

internal actual fun aesCbcEncrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray = aesCbc(Cipher.ENCRYPT_MODE, key, iv, data)

/** The formats fix the mode: Hive memos and RN's stored data are AES-256-CBC, and no other mode reads them. */
private fun aesCbc(mode: Int, key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray =
    Cipher.getInstance("AES/CBC/PKCS5Padding").run {
        init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        doFinal(data)
    }

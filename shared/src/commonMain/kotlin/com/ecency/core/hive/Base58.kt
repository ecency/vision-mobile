package com.ecency.core.hive

/** Bitcoin-alphabet Base58, as used by Hive WIF keys and public keys. */
internal object Base58 {
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private val INDEXES = IntArray(128) { -1 }.also { idx -> ALPHABET.forEachIndexed { i, c -> idx[c.code] = i } }

    fun encode(input: ByteArray): String {
        val zeros = input.takeWhile { it.toInt() == 0 }.size
        // Base-256 to base-58 long division.
        val digits = ArrayList<Int>()
        for (byte in input) {
            var carry = byte.toInt() and 0xff
            for (i in digits.indices) {
                carry += digits[i] shl 8
                digits[i] = carry % 58
                carry /= 58
            }
            while (carry > 0) {
                digits.add(carry % 58)
                carry /= 58
            }
        }
        return "1".repeat(zeros) + digits.reversed().joinToString("") { ALPHABET[it].toString() }
    }

    fun decode(input: String): ByteArray {
        val zeros = input.takeWhile { it == '1' }.length
        val bytes = ArrayList<Int>()
        for (c in input) {
            var carry = if (c.code < 128) INDEXES[c.code] else -1
            require(carry >= 0) { "invalid base58 character" }
            for (i in bytes.indices) {
                carry += bytes[i] * 58
                bytes[i] = carry and 0xff
                carry = carry ushr 8
            }
            while (carry > 0) {
                bytes.add(carry and 0xff)
                carry = carry ushr 8
            }
        }
        return ByteArray(zeros) + bytes.reversed().map { it.toByte() }.toByteArray()
    }
}

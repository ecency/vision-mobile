package com.ecency.core.auth

import com.ecency.core.hive.HIVE_ADDRESS_PREFIX
import com.ecency.core.hive.PrivateKey
import com.ecency.core.hive.PublicKey

/**
 * The one normaliser for keys and passwords wherever they are typed or pasted: sign-in, the auth upgrade sheet,
 * the keys screen's import (RN #3600). Copying from chat apps adds spaces, newlines, NBSP and zero-width
 * characters; a WIF carrying any of them used to fail its checksum and fall through to master password
 * derivation, which then matched nothing.
 *
 * - The edges lose every whitespace character (Unicode White_Space, NBSP and the other no-break spaces included),
 *   every invisible format character (zero-width space, joiners, word joiner, byte order mark, soft hyphen,
 *   direction marks, embeddings and isolates), the Hangul fillers and the invisible combining marks (U+034F,
 *   U+17B4, U+17B5).
 * - Inside, nothing is changed unless removing those characters leaves a string with a checksum: a WIF private
 *   key, a public key (STM...) or a generated master password ("P" + WIF, as Hive wallets create them). A
 *   checksum cannot hold by accident, so then the characters were copy noise.
 * - Any other password keeps its interior as typed: Hive derives keys from whatever string the password was, so
 *   a password the user chose may legitimately hold spaces or other characters, and changing it would derive
 *   other keys.
 */
object KeyInput {
    /** Format characters, fillers and the invisible combining marks that copying adds; whitespace is [Char.isWhitespace]. */
    private const val INVISIBLE =
        "­͏؜ᅟᅠ឴឵᠎​‌‍‎‏" +
            "‪‫‬‭‮⁠⁡⁢⁣⁤⁦⁧⁨⁩ㅤ﻿ﾠ"

    /** Whitespace (NBSP, U+2007 and U+202F listed too, whatever a platform's table says) or an invisible character. */
    fun isNoise(c: Char): Boolean = c.isWhitespace() || c == ' ' || c == ' ' || c == ' ' || INVISIBLE.indexOf(c) >= 0

    /** [input] without leading and trailing whitespace or invisible characters. */
    fun edges(input: String): String = input.trim(::isNoise)

    /** A key or master password as entered, cleaned of what copying added (see the class comment). */
    fun secret(input: String): String {
        val edged = edges(input)
        val compact = edged.filterNot(::isNoise)
        return if (compact.length != edged.length && hasChecksum(compact)) compact else edged
    }

    private fun hasChecksum(s: String): Boolean = when {
        s.length !in 40..60 -> false
        s.startsWith(HIVE_ADDRESS_PREFIX) -> runCatching { PublicKey.fromString(s) }.isSuccess
        s.startsWith("P") -> isWif(s.substring(1))
        else -> isWif(s)
    }

    private fun isWif(s: String) = runCatching { PrivateKey.fromWif(s) }.isSuccess
}

package com.ecency.core.app

import com.ecency.core.l10n.L

/**
 * RN `utils/usernameValidation.ts` (getUsernameError) and `constants/exchangeAccounts.ts` (isExchangeLikeUsername),
 * ported rule for rule and pinned to RN's own output (`SignupGoldens`). Input is expected lowercased, as RN.
 */
enum class UsernameError(private val messageText: () -> String) {
    LENGTH({ L.register.validation.usernameLengthError }),
    START_LETTER({ L.register.validation.usernameNoAsciiFirstLetterError }),
    SYMBOLS({ L.register.validation.usernameContainsSymbolsError }),
    DOUBLE_HYPHENS({ L.register.validation.usernameContainsDoubleHyphens }),

    /** RN reuses the symbols message for a trailing hyphen. */
    TRAILING_HYPHEN({ L.register.validation.usernameContainsSymbolsError }),
    UNDERSCORE({ L.register.validation.usernameContainsUnderscore }),
    EXCHANGE({ L.register.validation.usernameResemblesExchange }),
    RESTRICTED({ L.register.validation.usernameRestrictedPrefix }),
    ;

    val message: String get() = messageText()
}

object UsernameRules {
    private val SEGMENT = Regex("^[a-z][a-z0-9-]*[a-z0-9]$")
    private val NOT_ALNUM = Regex("[^a-z0-9]")
    private val UID = Regex("^uid[0-9]")
    private val TRAILING_DIGITS = Regex("[0-9]+$")

    /** RN's list of exchange deposit accounts (kept in sync with the web app's). */
    val EXCHANGE_ACCOUNTS = listOf(
        "deepcrypto8", "gateiodeposit", "probithive", "mxchive", "huobi-pro", "coinexdeposit", "bittrex", "blocktrades",
        "changelly", "gopax-deposit", "hitbtc-exchange", "poloniex", "upbit-exchange", "onepagex", "bdhivesteem", "bitgethive",
    )
    private val GENERIC_SUFFIXES = listOf("deposit", "exchange", "steem", "hive", "pro")

    /** The chain's account name rules, then the two policy checks; null when [value] can be created. */
    fun error(value: String): UsernameError? {
        // JS `length` counts UTF-16 units, as Kotlin does
        if (value.isEmpty() || value.length < 3 || value.length > 16) return UsernameError.LENGTH
        value.split('.').firstNotNullOfOrNull(::segmentError)?.let { return it }
        if (isExchangeLike(value)) return UsernameError.EXCHANGE
        if (hasRestrictedPrefix(value)) return UsernameError.RESTRICTED
        return null
    }

    private fun segmentError(segment: String): UsernameError? = when {
        segment.length < 3 -> UsernameError.LENGTH
        '_' in segment -> UsernameError.UNDERSCORE
        segment[0] !in 'a'..'z' -> UsernameError.START_LETTER
        "--" in segment -> UsernameError.DOUBLE_HYPHENS
        segment.endsWith("-") -> UsernameError.TRAILING_HYPHEN
        !SEGMENT.matches(segment) -> UsernameError.SYMBOLS
        else -> null
    }

    /** "uid" + digits once separators and case are stripped (RN hasRestrictedUsernamePrefix). */
    fun hasRestrictedPrefix(value: String): Boolean = UID.containsMatchIn(normalize(value))

    private fun normalize(value: String): String = NOT_ALNUM.replace(asciiLower(value), "")

    /** JS toLowerCase for the characters that can end up in [a-z0-9] afterwards; anything else is stripped anyway. */
    private fun asciiLower(value: String): String = buildString(value.length) {
        for (c in value) {
            append(
                when (c) {
                    in 'A'..'Z' -> c + 32
                    // the two non-ASCII characters whose JS lowercase is ASCII
                    'K' -> 'k'
                    'İ' -> 'i'
                    else -> c
                },
            )
        }
    }

    private fun brandCore(normalized: String): String {
        val core = TRAILING_DIGITS.replace(normalized, "")
        val suffix = GENERIC_SUFFIXES.firstOrNull { core.length > it.length && core.endsWith(it) }
        return if (suffix != null) core.dropLast(suffix.length) else core
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            val curr = IntArray(b.length + 1)
            curr[0] = i + 1
            for (j in b.indices) {
                val cost = if (a[i] == b[j]) 0 else 1
                curr[j + 1] = minOf(prev[j + 1] + 1, curr[j] + 1, prev[j] + cost)
            }
            prev = curr
        }
        return prev[b.length]
    }

    /** RN isExchangeLikeUsername: the same name or brand, a name that embeds one, or a one character typo. */
    fun isExchangeLike(username: String): Boolean {
        if (username.isEmpty()) return false
        val candidate = normalize(username)
        if (candidate.length < 3) return false
        return EXCHANGE_ACCOUNTS.any { account ->
            val exchange = normalize(account)
            if (exchange.isEmpty()) return@any false
            val core = brandCore(exchange)
            candidate == exchange ||
                (core.length >= 4 && candidate == core) ||
                (candidate.length > exchange.length && exchange in candidate) ||
                (core != exchange && core.length >= 4 && candidate.length > core.length && core in candidate) ||
                (kotlin.math.abs(candidate.length - exchange.length) <= 1 && levenshtein(candidate, exchange) <= 1)
        }
    }

    // RN registerScreen's email pattern, part by part
    private const val ATOM = """[^<>()\[\]\\.,;:\s@"]+"""
    private val EMAIL = Regex(
        "^(($ATOM(\\.$ATOM)*)|(\".+\"))@((\\[[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}])|(([a-zA-Z\\-0-9]+\\.)+[a-zA-Z]{2,}))$",
    )

    /** RN `_handleEmailChange`. JS `.` and `$` do not match line terminators: an address holding one is refused first. */
    fun isValidEmail(value: String): Boolean {
        if (value.any { it == '\n' || it == '\r' || it == ' ' || it == ' ' }) return false
        if (value.any { isJsSpaceNotKotlin(it) }) return false
        return EMAIL.matches(value)
    }

    /** Characters JS `\s` matches and the platform regex engines may not (they are never valid in an address). */
    private fun isJsSpaceNotKotlin(c: Char): Boolean =
        c == ' ' || c == ' ' || c in ' '..' ' || c == ' ' || c == ' ' || c == '　' || c == '﻿' || c == '\u000B' || c == '\u000C'
}

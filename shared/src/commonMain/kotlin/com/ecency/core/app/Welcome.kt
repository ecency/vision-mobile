package com.ecency.core.app

/** RN `utils/parseVersionNumber.ts`: major * 1_000_000 + minor * 1_000 + patch, each part read like JS `parseInt(x, 10) || 0`. */
object AppVersions {
    fun parse(version: String?): Long {
        if (version.isNullOrEmpty()) return 0
        val parts = version.split('.')
        return parseInt(parts.getOrNull(0)) * 1_000_000 + parseInt(parts.getOrNull(1)) * 1_000 + parseInt(parts.getOrNull(2))
    }

    /** JS parseInt in base 10: leading white space, an optional sign, then digits; NaN (and 0) read as 0. */
    private fun parseInt(part: String?): Long {
        val s = part?.trimStart { it.isWhitespace() || it == '﻿' } ?: return 0
        var i = 0
        var negative = false
        if (i < s.length && (s[i] == '+' || s[i] == '-')) {
            negative = s[i] == '-'
            i++
        }
        val start = i
        var value = 0L
        while (i < s.length && s[i] in '0'..'9' && value < 1_000_000_000_000L) {
            value = value * 10 + (s[i] - '0')
            i++
        }
        if (i == start) return 0
        return if (negative) -value else value
    }
}

/**
 * RN's welcome screen decision (`appNavigator`: `!lastAppVersion || parse(lastAppVersion) < parse(appVersion)`)
 * and what "Get started!" stores (`setLastAppVersion`, `setIsTermsAccepted`): the screen shows on the first
 * launch and again after each update, with the terms box already ticked for someone who accepted before.
 * Announcements use the same "new version launch" test ([isNewVersionLaunch]).
 */
class WelcomeGate(private val prefs: () -> Prefs, private val appVersion: String) {
    fun isNewVersionLaunch(): Boolean {
        val last = prefs().getString(LAST_VERSION)
        return last.isNullOrEmpty() || AppVersions.parse(last) < AppVersions.parse(appVersion)
    }

    /** Whether the welcome screen shows now. */
    fun shouldShow(): Boolean = isNewVersionLaunch()

    /** The terms were accepted on an earlier version: the box starts ticked. */
    fun termsAccepted(): Boolean = prefs().getString(TERMS) == "true"

    /** "Get started!" with the box ticked (the button is disabled otherwise, and this refuses too). */
    fun accept(consent: Boolean): Boolean {
        if (!consent) return false
        prefs().putString(LAST_VERSION, appVersion)
        prefs().putString(TERMS, "true")
        return true
    }

    companion object {
        const val LAST_VERSION = "welcome.lastAppVersion"
        const val TERMS = "welcome.termsAccepted"
    }
}

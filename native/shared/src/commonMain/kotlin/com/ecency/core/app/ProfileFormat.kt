package com.ecency.core.app

import com.ecency.core.api.model.JsValue
import com.ecency.core.l10n.L
import com.ecency.core.l10n.LocaleFormat
import com.ecency.core.render.blocks.SafeTarget

/** A calendar date as written on chain (the UI formats it for the device locale, as RN's toLocaleDateString). */
data class CalendarDate(val year: Int, val month: Int, val day: Int)

/** The RN profile header's small formatting rules. */
object ProfileFormat {
    /**
     * RN makeCountFriendly: 1234 is "1.2K", 1500000 is "1.5M", below 1000 the number itself; in the app language
     * ([LocaleFormat.compact]: RN's rounding at RN's magnitudes; a language without that suffix shows the whole count).
     */
    fun count(value: Int): String = when {
        value >= 1_000_000 -> LocaleFormat.compact(value.toDouble(), 1, 6, 0, ::round1)
        value >= 1_000 -> LocaleFormat.compact(value.toDouble(), 1, 3, 0, ::round1)
        else -> LocaleFormat.posix(JsValue.numberToString(round1(value.toDouble())), grouping = false)
    }

    /** `Math.round(num * 10) / 10` */
    private fun round1(v: Double): Double = kotlin.math.floor(v * 10 + 0.5) / 10

    /**
     * RN profileView: `Math.ceil((100 - percent) * 0.833333) || null`, the hours until a mana bar is full
     * ([percent] is the one-decimal value shown). Null when full.
     */
    fun hoursToFull(percent: String): Int? {
        val p = percent.toDoubleOrNull() ?: return null
        val hours = kotlin.math.ceil((100 - p) * 0.833333)
        return if (hours.isNaN() || hours == 0.0) null else hours.toInt()
    }

    /** RN "Voting power: 87.3% • Full in 11 hours" (and the same for Resource Credits). */
    fun barText(label: String, percent: String, hours: Int?): String {
        // the decimals RN shows ("50.0"), in the app language
        val decimals = percent.substringAfter('.', "").length
        val shown = percent.toDoubleOrNull()?.let { LocaleFormat.percent(it, decimals, decimals) } ?: "$percent%"
        return if (hours == null) L.native.profile.bar(label = label, percent = shown) else L.native.profile.barFull(label = label, percent = shown, hours = hours)
    }

    /** The account's creation date ("2016-03-24T17:00:21"); null when unreadable or empty. */
    fun joined(created: String): CalendarDate? {
        val m = Regex("^(\\d{4})-(\\d{2})-(\\d{2})").find(created) ?: return null
        val (y, mo, d) = m.destructured
        if (y.toInt() < 1971) return null // "1970-01-01T00:00:00" is the chain's empty date
        return CalendarDate(y.toInt(), mo.toInt(), d.toInt())
    }

    /**
     * The profile's website as a link to open: RN hands the raw text to Linking.openURL; here only http(s)
     * opens, and a bare host ("ecency.com") gets https.
     */
    fun websiteUrl(website: String?): String? {
        val w = website?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val withScheme = if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(w)) w else "https://$w"
        return SafeTarget.httpUrl(withScheme)
    }
}

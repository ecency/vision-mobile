package com.ecency.core.api.rpc

/** HTTP dates (RFC 9110 IMF-fixdate, "Wed, 21 Oct 2026 07:28:00 GMT"), for `Retry-After`. */
internal object HttpDate {
    private val IMF = Regex("""^(?:[A-Za-z]{3},\s*)?(\d{1,2})\s+([A-Za-z]{3})\s+(\d{4})\s+(\d{2}):(\d{2}):(\d{2})\s*(?:GMT|UTC|Z)?$""")
    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    /** Epoch ms of [value], or null when it is not such a date. */
    fun parseMillis(value: String): Long? {
        val m = IMF.matchEntire(value.trim()) ?: return null
        val (d, mon, y, hh, mm, ss) = m.destructured
        val month = MONTHS.indexOf(mon.lowercase()) + 1
        val day = d.toInt()
        if (month == 0 || day !in 1..31 || hh.toInt() > 23 || mm.toInt() > 59 || ss.toInt() > 60) return null
        val days = daysFromCivil(y.toLong(), month, day)
        return ((days * 24 + hh.toLong()) * 60 + mm.toLong()) * 60_000 + ss.toLong() * 1000
    }

    /** Days since 1970-01-01 of a proleptic Gregorian date (H. Hinnant's days_from_civil). */
    private fun daysFromCivil(year: Long, month: Int, day: Int): Long {
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val mp = (month + 9) % 12
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097 + doe - 719468
    }
}

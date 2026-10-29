package com.ecency.core.l10n

import com.ibm.icu.impl.ICUData
import com.ibm.icu.impl.ICUResourceBundle
import com.ibm.icu.text.DateFormatSymbols
import com.ibm.icu.text.DateTimePatternGenerator
import com.ibm.icu.text.DecimalFormat
import com.ibm.icu.text.DecimalFormatSymbols
import com.ibm.icu.text.NumberFormat
import com.ibm.icu.text.NumberingSystem
import com.ibm.icu.text.PluralRules
import com.ibm.icu.util.ULocale
import com.ibm.icu.util.UResourceBundle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Prints `shared/l10n/cldr-formats.json` from ICU4J 75.1 (CLDR 45), test classpath only: per app language the
 * number symbols and digits, grouping, percent affixes, short compact patterns, the date patterns of the skeletons
 * the app uses, month, weekday and day period names (Gregorian), and the relative time and duration unit patterns.
 * [CldrFormatsOracleTest] checks that the vendored file is exactly this output.
 */
internal object CldrFormatsExtract {
    const val SOURCE = "ICU4J 75.1 (CLDR 45), CldrFormatsExtract; Gregorian calendar in every language"

    /** The skeletons [LocaleFormatter] formats, in the order of [LocaleData.Pattern]. */
    val SKELETONS = listOf("yMd", "yMMMd", "yMMMMd", "jmm", "yMMMdjmm", "MMMdd", "MMMEdjmm")

    val UNITS = listOf("second", "minute", "hour", "day", "week", "month", "year")

    /** The CLDR locale of an app language: sr-CS is Latin script Serbian (the RN file is Latin), the rest as is. */
    fun cldrTag(id: String): String = if (id == "sr-CS") "sr-Latn-RS" else id

    fun locale(id: String): ULocale = ULocale.forLanguageTag(cldrTag(id))

    fun gregorian(id: String): ULocale = locale(id).setKeywordValue("calendar", "gregorian")

    /** One locale per block and one field per line (minified values), so a CLDR refresh diffs readably. */
    fun json(ids: List<String>): String {
        val compact = Json
        val b = StringBuilder("{\n")
        b.append("  \"source\": ").append(compact.encodeToString(JsonElement.serializer(), JsonPrimitive(SOURCE))).append(",\n")
        b.append("  \"locales\": {\n")
        ids.sorted().forEachIndexed { i, id ->
            b.append("    \"").append(id).append("\": {\n")
            val fields = localeJson(id).entries.toList()
            fields.forEachIndexed { j, (k, v) ->
                b.append("      \"").append(k).append("\": ").append(compact.encodeToString(JsonElement.serializer(), v))
                b.append(if (j < fields.size - 1) ",\n" else "\n")
            }
            b.append(if (i < ids.size - 1) "    },\n" else "    }\n")
        }
        b.append("  }\n}\n")
        return b.toString()
    }

    private fun bundle(l: ULocale, base: String = ICUData.ICU_BASE_NAME): ICUResourceBundle =
        UResourceBundle.getBundleInstance(base, l) as ICUResourceBundle

    private fun ICUResourceBundle.str(path: String): String? = findWithFallback(path)?.string

    private fun arr(values: List<String>) = JsonArray(values.map(::JsonPrimitive))

    /** ICU pattern quoting removed ("0 Mio'.'" is "0 Mio."; "''" is one apostrophe). */
    fun unquote(p: String): String {
        val b = StringBuilder()
        var i = 0
        while (i < p.length) {
            val c = p[i]
            if (c == '\'') {
                if (p.getOrNull(i + 1) == '\'') { b.append('\''); i += 2; continue }
                i++
                while (i < p.length) {
                    if (p[i] == '\'') {
                        if (p.getOrNull(i + 1) == '\'') { b.append('\''); i += 2; continue }
                        break
                    }
                    b.append(p[i]); i++
                }
                i++
                continue
            }
            b.append(c); i++
        }
        return b.toString()
    }

    private fun localeJson(id: String): JsonObject {
        val l = locale(id)
        val g = gregorian(id)
        val rb = bundle(l)
        val ns = NumberingSystem.getInstance(l)
        val sym = DecimalFormatSymbols.getInstance(l)
        val df = NumberFormat.getInstance(l) as DecimalFormat
        val pf = NumberFormat.getPercentInstance(l) as DecimalFormat
        val cats = PluralRules.forLocale(l).keywords.sortedBy { CATEGORY_ORDER.indexOf(it) }
        val o = LinkedHashMap<String, JsonElement>()
        o["cldr"] = JsonPrimitive(cldrTag(id))
        o["digits"] = JsonPrimitive(ns.description)
        o["decimal"] = JsonPrimitive(sym.decimalSeparatorString)
        o["group"] = JsonPrimitive(sym.groupingSeparatorString)
        o["minus"] = JsonPrimitive(sym.minusSignString)
        val primary = df.groupingSize
        val secondary = df.secondaryGroupingSize.takeIf { it > 0 } ?: primary
        val minGrouping = rb.str("NumberElements/minimumGroupingDigits")?.toInt() ?: 1
        o["grouping"] = JsonArray(listOf(primary, secondary, minGrouping).map(::JsonPrimitive))
        o["percent"] = arr(listOf(pf.positivePrefix, pf.positiveSuffix))
        // short compact patterns per power of ten (3 = thousands), per plural category; "0" means not abbreviated
        val compact = LinkedHashMap<String, JsonElement>()
        for (power in 3..14) {
            val key = "1" + "0".repeat(power)
            fun pattern(cat: String): String? =
                rb.str("NumberElements/${ns.name}/patternsShort/decimalFormat/$key/$cat")
                    ?: rb.str("NumberElements/latn/patternsShort/decimalFormat/$key/$cat")
            val other = pattern("other") ?: "0"
            val byCat = LinkedHashMap<String, JsonElement>()
            // only the categories that differ from "other" (the runtime falls back to it)
            for (c in cats) unquote(pattern(c) ?: other).let { if (c == "other" || it != unquote(other)) byCat[c] = JsonPrimitive(it) }
            compact[power.toString()] = JsonObject(byCat)
        }
        o["compact"] = JsonObject(compact)
        val gen = DateTimePatternGenerator.getInstance(g)
        o["dates"] = JsonObject(SKELETONS.associateWith { JsonPrimitive(gen.getBestPattern(it)) })
        val dfs = DateFormatSymbols(g)
        o["MMM"] = arr(dfs.shortMonths.toList())
        o["MMMM"] = arr(dfs.months.toList())
        o["LLL"] = arr(dfs.getMonths(DateFormatSymbols.STANDALONE, DateFormatSymbols.ABBREVIATED).toList())
        // weekdays Sunday first (ICU's arrays start at index 1)
        o["EEE"] = arr(dfs.shortWeekdays.drop(1))
        o["ccc"] = arr(dfs.getWeekdays(DateFormatSymbols.STANDALONE, DateFormatSymbols.ABBREVIATED).drop(1))
        o["ampm"] = arr(dfs.amPmStrings.toList())
        o["now"] = JsonPrimitive(rb.str("fields/second/relative/0") ?: "now")
        val units = bundle(l, ICUData.ICU_UNIT_BASE_NAME)
        fun unitTable(b: ICUResourceBundle, path: (String, String) -> String): JsonObject = JsonObject(UNITS.associateWith { unit ->
            val other = requireNotNull(b.str(path(unit, "other"))) { "$id: no ${path(unit, "other")}" }
            JsonObject(cats.mapNotNull { c -> (b.str(path(unit, c)) ?: other).takeIf { c == "other" || it != other }?.let { c to JsonPrimitive(it) } }.toMap())
        })
        o["past"] = unitTable(rb) { u, c -> "fields/$u/relativeTime/past/$c" }
        o["future"] = unitTable(rb) { u, c -> "fields/$u/relativeTime/future/$c" }
        o["wide"] = unitTable(units) { u, c -> "units/duration/$u/$c" }
        o["narrow"] = unitTable(units) { u, c -> "unitsNarrow/duration/$u/$c" }
        return JsonObject(o)
    }

    private val CATEGORY_ORDER = listOf("zero", "one", "two", "few", "many", "other")
}

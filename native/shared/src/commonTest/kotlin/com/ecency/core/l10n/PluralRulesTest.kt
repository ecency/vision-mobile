package com.ecency.core.l10n

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The generated CLDR 45 plural rules, one golden list per language family (values from the CLDR rule samples). The
 * Android host test PluralRulesOracleTest compares every locale against ICU4J over a wide number range as well.
 */
class PluralRulesTest {
    private fun cats(locale: String, vararg values: Number) = values.map { L10nPluralRules.select(locale, PluralOperands.of(it.toDouble())) }

    @Test
    fun everyLocaleHasRulesAndOtherIsAlwaysReachable() {
        assertEquals(39, L10nData.LOCALES.size)
        val values = (0..1000).map { it.toDouble() } + listOf(0.5, 1.5, 2.25, 10.1)
        for (id in L10nData.LOCALES) {
            val seen = values.map { L10nPluralRules.select(id, PluralOperands.of(it)) }.toSet()
            assertEquals(true, "other" in seen && seen.all { it in setOf("zero", "one", "two", "few", "many", "other") }, "$id: $seen")
        }
    }

    @Test
    fun noPluralForms() {
        for (id in listOf("ja-JP", "ko-KR", "zh-CN", "zh-TW", "id-ID", "ms-MY", "th-TH", "vi-VN")) {
            assertEquals(listOf("other", "other", "other"), cats(id, 0, 1, 2.5), id)
        }
    }

    @Test
    fun germanicAndOneOther() {
        // i = 1 and v = 0: 1 is one, 1.0 too (a whole number), 1.5 is other
        for (id in listOf("en-US", "de-DE", "nl-NL", "sv-SE", "fi-FI", "et-EE")) assertEquals(listOf("other", "one", "other", "other"), cats(id, 0, 1, 2, 1.5), id)
        // n = 1
        for (id in listOf("tr-TR", "az-AZ", "bg-BG", "el-GR", "hu-HU", "ku-TR", "uz-UZ")) assertEquals(listOf("other", "one", "other"), cats(id, 0, 1, 1.5), id)
        // i = 0 or n = 1
        for (id in listOf("hi-IN", "bn-BD", "fa-IR")) assertEquals(listOf("one", "one", "one", "other"), cats(id, 0, 1, 0.5, 2), id)
        // da: n = 1 or t != 0 and i = 0,1
        assertEquals(listOf("other", "one", "one", "one", "other"), cats("da-DK", 0, 1, 0.5, 1.5, 2.5))
    }

    @Test
    fun romanceWithMany() {
        assertEquals(listOf("one", "one", "one", "other", "many"), cats("fr-FR", 0, 1, 1.5, 2, 1_000_000))
        assertEquals(listOf("other", "one", "other", "many"), cats("it-IT", 0, 1, 2, 2_000_000))
        // pt-PT follows its own rule (0 is other), unlike Brazilian pt
        assertEquals(listOf("other", "one", "other", "many"), cats("pt-PT", 0, 1, 1.5, 1_000_000))
        assertEquals(listOf("other", "one", "other", "many"), cats("es-ES", 0, 1, 2, 1_000_000))
        // ro: v != 0 or n = 0 or n % 100 = 2..19
        assertEquals(listOf("few", "one", "few", "few", "other", "few", "other"), cats("ro-RO", 0, 1, 2, 19, 20, 101, 120))
    }

    @Test
    fun slavic() {
        for (id in listOf("ru-RU", "uk-UA")) {
            assertEquals(listOf("many", "one", "few", "many", "many", "one", "few", "many", "other"), cats(id, 0, 1, 2, 5, 11, 21, 22, 111, 1.5), id)
        }
        assertEquals(listOf("many", "one", "few", "many", "many", "many", "few", "other"), cats("pl-PL", 0, 1, 2, 5, 12, 21, 22, 1.5))
        assertEquals(listOf("other", "one", "few", "other", "many"), cats("cs-CZ", 0, 1, 3, 5, 1.5))
        // sr: fraction digits count too (1.1 is one, 2.3 is few)
        assertEquals(listOf("other", "one", "few", "other", "one", "few", "other"), cats("sr-CS", 0, 1, 2, 5, 1.1, 2.3, 1.5))
    }

    @Test
    fun baltic() {
        assertEquals(listOf("other", "one", "few", "other", "one", "few", "many"), cats("lt-LT", 0, 1, 2, 11, 21, 29, 1.5))
    }

    @Test
    fun semitic() {
        assertEquals(listOf("zero", "one", "two", "few", "few", "many", "many", "other", "other", "few"), cats("ar-SA", 0, 1, 2, 3, 10, 11, 99, 100, 102, 103))
        assertEquals(listOf("other", "one", "two", "other", "one", "other"), cats("he-IL", 0, 1, 2, 3, 0.5, 1.5))
    }

    @Test
    fun filipino() {
        assertEquals(listOf("one", "one", "one", "other", "one", "other", "one", "other"), cats("fil-PH", 0, 1, 2, 4, 5, 6, 0.5, 0.4))
    }

    @Test
    fun operands() {
        val o = PluralOperands.of(1.50)
        assertEquals(listOf(1.5, 1.0, 1.0, 1.0, 5.0, 5.0), listOf(o.n, o.i, o.v, o.w, o.f, o.t))
        val w = PluralOperands.of(-3.0)
        assertEquals(listOf(3.0, 3.0, 0.0, 0.0), listOf(w.n, w.i, w.v, w.f))
        val small = PluralOperands.of(0.125)
        assertEquals(listOf(3.0, 125.0), listOf(small.v, small.f))
    }
}

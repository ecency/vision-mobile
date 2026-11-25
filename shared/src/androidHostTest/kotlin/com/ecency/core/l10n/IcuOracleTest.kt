package com.ecency.core.l10n

import com.ibm.icu.impl.number.DecimalQuantity_DualStorageBCD
import com.ibm.icu.text.MessageFormat
import com.ibm.icu.text.PluralRules
import com.ibm.icu.util.LocaleData
import com.ibm.icu.util.ULocale
import com.ibm.icu.util.VersionInfo
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * ICU4J 79.1 (CLDR 49) as an offline oracle, test classpath only: the generated plural rules agree with ICU4J's for
 * every locale over integers and decimals, the vendored rules file is exactly what ICU4J prints, and the formatter
 * agrees with ICU4J's MessageFormat on the syntax the app uses (numbers below 1000, so ICU's grouping never shows).
 */
class IcuOracleTest {
    private val values: List<Double> = (0..1200).map { it.toDouble() } + listOf(1_000_000.0, 2_000_000.0, 1_000_001.0) +
        (0..300).map { it / 10.0 } + listOf(0.01, 0.05, 1.25, 2.31, 10.11, 21.02, 101.5)

    @Test
    fun pluralRulesMatchIcu4jForEveryLocale() {
        for (id in L10nData.LOCALES) {
            val icu = PluralRules.forLocale(ULocale.forLanguageTag(id))
            for (v in values) {
                // ICU4J's select(double) truncates binary fractions (16.4 reads as f = 3); a decimal string is exact
                @Suppress("DEPRECATION")
                val expected = icu.select(DecimalQuantity_DualStorageBCD(java.math.BigDecimal.valueOf(v).stripTrailingZeros()))
                assertEquals(expected, L10nPluralRules.select(id, PluralOperands.of(v)), "$id $v")
            }
        }
    }

    @Test
    fun vendoredRulesAreIcu4jsRules() {
        val actual = File("l10n/cldr-plural-rules.json").readText()
        val lines = L10nData.LOCALES.sorted().map { id ->
            val icu = PluralRules.forLocale(ULocale.forLanguageTag(id))
            val rules = listOf("zero", "one", "two", "few", "many").filter { it in icu.keywords }.joinToString(", ") { "\"$it\": \"${icu.getRules(it).trim()}\"" }
            "    \"$id\": {$rules}"
        }
        val expected = "{\n  \"source\": \"ICU4J ${VersionInfo.ICU_VERSION.major}.${VersionInfo.ICU_VERSION.minor} (CLDR ${LocaleData.getCLDRVersion().major}): " +
            "PluralRules.forLocale(ULocale.forLanguageTag(id)).getRules(keyword), samples dropped\",\n  \"rules\": {\n" +
            lines.joinToString(",\n") + "\n  }\n}\n"
        if (actual != expected) File("build/cldr-plural-rules.json").writeText(expected)
        assertEquals(expected, actual, "shared/l10n/cldr-plural-rules.json differs from ICU4J; the fresh file is in shared/build/cldr-plural-rules.json")
    }

    @Test
    fun formatterMatchesIcuMessageFormat() {
        val cases = listOf(
            "Plain text" to emptyMap(),
            "by {username} in {community}" to mapOf("username" to "alice", "community" to "hive-1"),
            "{count, plural, =0 {none} one {# vote} other {# votes}}" to mapOf("count" to 0),
            "{count, plural, =0 {none} one {# vote} other {# votes}}" to mapOf("count" to 1),
            "{count, plural, =0 {none} one {# vote} other {# votes}}" to mapOf("count" to 7),
            "{n, plural, offset:1 =0 {nobody} =1 {you} one {you and # other} other {you and # others}}" to mapOf("n" to 2),
            "{n, plural, offset:1 =0 {nobody} =1 {you} one {you and # other} other {you and # others}}" to mapOf("n" to 6),
            "{g, select, female {She {n, plural, one {# post} other {# posts}}} other {They}}" to mapOf("g" to "female", "n" to 3),
            "{g, select, female {She} other {They}}" to mapOf("g" to "male"),
            "You're l'ami de {u}" to mapOf("u" to "bob"),
            "don''t '{literal}' and '}'" to emptyMap(),
            "{n, plural, other {'#' # '''#'''}}" to mapOf("n" to 2),
            "{n, number} items" to mapOf("n" to 42),
            "{n, plural, other {{g, select, other {# x}} #}}" to mapOf("n" to 2, "g" to "a"),
            "{n, plural, offset: 1 other {# more}}" to mapOf("n" to 3),
            "a '|{u}' b" to mapOf("u" to "bob"),
        )
        for ((pattern, args) in cases) {
            val icu = MessageFormat(pattern, ULocale.ENGLISH).format(args)
            val ours = IcuMessage.parse(pattern).format(args, { L10nPluralRules.select("en-US", it) }, { it })
            assertEquals(icu, ours, pattern)
        }
        val ru = "{n, plural, one {# голос} few {# голоса} many {# голосов} other {# голоса}}"
        for (n in listOf(1, 2, 5, 11, 21, 22, 25, 101, 111)) {
            assertEquals(
                MessageFormat(ru, ULocale("ru")).format(mapOf("n" to n)),
                IcuMessage.parse(ru).format(mapOf("n" to n), { L10nPluralRules.select("ru-RU", it) }, { it }),
            )
        }
    }
}

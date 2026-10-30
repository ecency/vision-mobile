package com.ecency.core.l10n

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

class L10nCatalogTest {
    @AfterTest
    fun english() {
        L10n.usePseudo(null)
        L10n.setLanguage(L10n.SOURCE)
    }

    private fun index(key: String) = requireNotNull(L10n.indexOf(key)) { key }

    /** Arguments for any message: numbers for plural and number args, a marker text for the rest. */
    private fun sampleArgs(index: Int): Map<String, Any> {
        val names = HashMap<String, Any>()
        fun walk(parts: List<IcuMessage.Part>) {
            for (p in parts) when (p) {
                is IcuMessage.Arg -> names.getOrPut(p.name) { "‹${p.name}›" }
                is IcuMessage.NumberArg -> names[p.name] = 3
                is IcuMessage.Plural -> { names[p.name] = 3; p.options.values.forEach(::walk) }
                is IcuMessage.Select -> { names.getOrPut(p.name) { "other" }; p.options.values.forEach(::walk) }
                else -> Unit
            }
        }
        walk(IcuMessage.parse(L10n.englishSource(index)).parts)
        return names
    }

    private fun format(index: Int): String {
        val args = sampleArgs(index)
        val names = args.keys.toTypedArray()
        return L10n.format(index, names, Array(names.size) { args.getValue(names[it]) })
    }

    @Test
    fun englishByDefaultThroughTheGeneratedApi() {
        assertEquals(L10n.SOURCE, L10n.language.value)
        assertEquals("Language", L.settings.language)
        assertEquals("1 vote", L.post.a11yVotes(count = 1))
        assertEquals("12 votes", L.post.a11yVotes(count = 12))
        assertEquals(39, L10n.supported.size)
        assertTrue(L10n.SOURCE in L10n.supported)
    }

    @Test
    fun switchingLanguageChangesTheStateAndTheMessages() {
        L10n.setLanguage("ru-RU")
        assertEquals("ru-RU", L10n.language.value)
        assertEquals("Язык", L.settings.language)
        assertFalse(L10n.isRtl)
        L10n.setLanguage("de-DE")
        assertEquals("Sprache", L.settings.language)
        L10n.setLanguage(L10n.SOURCE)
        assertEquals("Language", L.settings.language)
        L10n.setLanguage("xx-YY")
        assertEquals(L10n.SOURCE, L10n.language.value, "an unknown id is English")
    }

    @Test
    fun missingKeysFallBackToEnglish() {
        val language = index("settings.language")
        val votes = index("post.a11y_votes")
        val table = arrayOfNulls<String>(L10nData.SIZE)
        table[language] = "Язык"
        L10n.install("xx-TEST", table, rtl = false, pluralLocale = "ru-RU")
        assertEquals("Язык", L.settings.language)
        assertEquals("3 votes", L.post.a11yVotes(3), "a key the language lacks shows English, with English plurals")
        assertFalse(L10n.isTranslated(votes))
        // and in the real files: whatever ru-RU lacks today shows English
        L10n.setLanguage("ru-RU")
        val ru = requireNotNull(L10nData.table("ru-RU"))
        for (i in 0 until L10nData.SIZE) if (ru[i] == null) assertEquals(L10n.withEnglish { format(i) }, format(i))
    }

    @Test
    fun languageIdsAreMatchedLikeRn() {
        val cases = mapOf(
            "ru-RU" to "ru-RU", "ru-ru" to "ru-RU", "ru_RU" to "ru-RU", "ru" to "ru-RU", "en" to "en-US", "pt-BR" to "pt-PT",
            "zh-Hant-TW" to "zh-TW", "zh-HK" to "zh-TW", "zh" to "zh-CN", "zh-Hans" to "zh-CN", "fil" to "fil-PH",
            "iw" to "he-IL", "xx-YY" to "en-US", "" to "en-US",
        )
        for ((id, expected) in cases) {
            L10n.setLanguage(id)
            assertEquals(expected, L10n.language.value, id)
        }
    }

    @Test
    fun everyMessageOfEveryLocaleFormatsWithoutThrowing() {
        for (id in L10n.supported) {
            L10n.setLanguage(id)
            for (i in 0 until L10nData.SIZE) {
                val out = format(i)
                assertTrue(out.isNotEmpty() || L10n.englishSource(i).isEmpty(), "$id ${L10nData.keys()[i]}")
            }
        }
    }

    /**
     * Placeholder rules: a translation may drop English's placeholders (react-intl formats it, as RN shows bg-BG's
     * joined date without `{time}`), but one using a name English lacks, or malformed ICU, shows English.
     */
    @Test
    fun translationsMayDropPlaceholdersButNotAddThem() {
        val by = (0 until L10nData.SIZE).first { IcuMessage.parse(L10n.englishSource(it)).argNames.size >= 2 }
        val names = IcuMessage.parse(L10n.englishSource(by)).argNames.toList()
        val table = arrayOfNulls<String>(L10nData.SIZE)
        table[by] = "only {${names[0]}}"
        L10n.install("xx-TEST", table, rtl = false, pluralLocale = "en-US")
        assertTrue(L10n.isTranslated(by))
        assertEquals("only A", L10n.format(by, names.toTypedArray(), Array(names.size) { if (it == 0) "A" else "B" }))
        table[by] = "{${names[0]}} {stranger}"
        L10n.install("xx-TEST2", table, rtl = false, pluralLocale = "en-US")
        assertFalse(L10n.isTranslated(by), "an unknown placeholder would show raw or fail")
        table[by] = "{${names[0]}"
        L10n.install("xx-TEST3", table, rtl = false, pluralLocale = "en-US")
        assertFalse(L10n.isTranslated(by), "malformed")
    }

    /**
     * Report of translations in the real files that the catalog refuses (a placeholder English lacks, or malformed
     * ICU); each shows English. A Crowdin sync can change the list, so it is printed for the translators, not pinned.
     */
    @Test
    fun rejectedTranslationsAreReportedAndShowEnglish() {
        val keys = L10nData.keys()
        for (id in L10n.supported) {
            if (id == L10n.SOURCE) continue
            val table = requireNotNull(L10nData.table(id))
            L10n.setLanguage(id)
            for (i in 0 until L10nData.SIZE) {
                val t = table[i] ?: continue
                val en = IcuMessage.parse(L10n.englishSource(i)).argNames
                val reason = try {
                    val names = IcuMessage.parse(t).argNames
                    if (en.containsAll(names)) null else "placeholders ${names - en} not in English $en"
                } catch (e: IcuSyntaxException) {
                    "malformed (${e.message})"
                }
                if (reason == null) {
                    assertTrue(L10n.isTranslated(i), "$id ${keys[i]}")
                    continue
                }
                println("l10n rejected: $id ${keys[i]}: $reason")
                assertFalse(L10n.isTranslated(i), "$id ${keys[i]}")
                assertEquals(L10n.withEnglish { format(i) }, Bidi.strip(format(i)), "$id ${keys[i]} shows English")
            }
        }
    }

    @Test
    fun malformedOrMistypedTranslationsFallBackAndNeverThrow() {
        val language = index("settings.language")
        val votes = index("post.a11y_votes")
        val textArg = (0 until L10nData.SIZE).first { i ->
            IcuMessage.parse(L10n.englishSource(i)).parts.let { ps -> ps.any { it is IcuMessage.Arg } && ps.none { it is IcuMessage.Plural || it is IcuMessage.Select || it is IcuMessage.NumberArg } }
        }
        val name = (IcuMessage.parse(L10n.englishSource(textArg)).parts.first { it is IcuMessage.Arg } as IcuMessage.Arg).name
        val table = arrayOfNulls<String>(L10nData.SIZE)
        table[language] = "{broken"
        table[votes] = "{count, plural, one {# голос}}" // no other
        table[textArg] = "{$name, plural, other {#}}" // same name, but the app passes text
        L10n.install("xx-TEST", table, rtl = false, pluralLocale = "ru-RU")
        assertEquals("Language", L.settings.language)
        assertEquals("2 votes", L.post.a11yVotes(2))
        val out = L10n.format(textArg, arrayOf(name), arrayOf("abc"))
        assertTrue("abc" in out && out == L10n.withEnglish { L10n.format(textArg, arrayOf(name), arrayOf("abc")) }, out)
        // a caller passing nothing still gets text (English, raw), never an exception
        assertEquals(L10n.englishSource(textArg), L10n.format(textArg, emptyArray(), emptyArray()))
    }

    @Test
    fun rightToLeftLanguagesIsolatePlaceholders() {
        assertTrue(L10n.isRtl("ar-SA") && L10n.isRtl("fa-IR") && L10n.isRtl("he-IL"))
        assertFalse(L10n.isRtl("ku-TR"), "ku-TR is Latin script Kurmanji: left to right")
        L10n.setLanguage("ar-SA")
        assertTrue(L10n.isRtl)
        val i = (0 until L10nData.SIZE).first { L10n.isTranslated(it) && sampleArgs(it).values.any { v -> v is String && v.startsWith("‹") } }
        val out = format(i)
        assertTrue("${Bidi.FSI}‹" in out && "›${Bidi.PDI}" in out, out)
        L10n.setLanguage("ku-TR")
        assertFalse(L10n.isRtl)
        assertFalse(Bidi.FSI in format(i))
    }

    @Test
    fun rightToLeftMessagesStartWithOneRlm() {
        L10n.setLanguage("ar-SA")
        val own = (0 until L10nData.SIZE).filter { L10n.isTranslated(it) }
        assertTrue(own.size > 100)
        for (i in own) {
            val out = format(i)
            if (out.isNotEmpty()) assertTrue(out[0] == L10n.RLM && (out.length == 1 || out[1] != L10n.RLM), "${L10nData.keys()[i]}: $out")
        }
        // an English fallback keeps its own direction
        val fallback = (0 until L10nData.SIZE).firstOrNull { !L10n.isTranslated(it) && L10n.englishSource(it).isNotEmpty() }
        if (fallback != null) assertNotEquals(L10n.RLM, format(fallback).firstOrNull())
        // the pseudo RTL locale already starts its literal runs with RLM: still one
        PseudoLocales.install(PseudoLocales.RTL)
        assertEquals("\u200F\u202ELanguage\u202C\u200F", L.settings.language)
        // left to right languages are untouched
        L10n.setLanguage("de-DE")
        assertEquals("Sprache", L.settings.language)
    }

    @Test
    fun dynamicKeys() {
        assertEquals("Language", L10n.byKey("settings.language"))
        assertNull(L10n.byKey("settings.no_such_key"))
        assertEquals("5 votes", L10n.byKey("post.a11y_votes", mapOf("count" to 5)))
        L10n.setLanguage("ru-RU")
        assertEquals("Язык", L10n.byKey("settings.language"))
    }

    @Test
    fun accentedPseudoLocaleKeepsArgumentsAndExpands() {
        PseudoLocales.install(PseudoLocales.ACCENTED)
        assertEquals(PseudoLocales.ACCENTED, L10n.language.value)
        val title = L.settings.language
        assertTrue(title.startsWith("[Ļåñĝûåĝé") && title.length >= "Language".length * 1.4, title)
        assertTrue(L.post.a11yVotes(1).startsWith("[1 ṽöţé"), L.post.a11yVotes(1))
        assertTrue("7" in L.post.a11yVotes(7))
        for (i in 0 until L10nData.SIZE) assertTrue(L10n.isTranslated(i), "${L10nData.keys()[i]} kept its arguments")
    }

    @Test
    fun rtlPseudoLocaleMirrorsTextAndIsolatesArguments() {
        PseudoLocales.install(PseudoLocales.RTL)
        assertTrue(L10n.isRtl)
        assertEquals("\u200F\u202ELanguage\u202C\u200F", L.settings.language)
        val i = index("post.a11y_votes")
        assertTrue(L10n.isTranslated(i))
        for (k in 0 until L10nData.SIZE) assertTrue(L10n.isTranslated(k), "${L10nData.keys()[k]} kept its arguments")
    }

    @Test
    fun aDebugPseudoLocaleHoldsAgainstTheSettingsLanguageUntilEnded() {
        L10n.usePseudo(PseudoLocales.RTL)
        assertEquals(PseudoLocales.RTL, L10n.language.value)
        assertTrue(L10n.isRtl)
        L10n.setLanguage("de-DE")
        assertEquals(PseudoLocales.RTL, L10n.language.value, "the settings language does not replace the pseudo locale")
        assertEquals("\u200F\u202ELanguage\u202C\u200F", L.settings.language)
        L10n.usePseudo(null)
        L10n.setLanguage("de-DE")
        assertEquals("de-DE", L10n.language.value)
        L10n.usePseudo("de-DE")
        assertEquals(L10n.SOURCE, L10n.language.value, "only the two pseudo ids are accepted")
    }

    @Test
    fun pseudoTransformQuotesSyntaxInText() {
        val m = PseudoLocales.transform("It's '{literal}' {n, plural, one {'#' #} other {#}}", rtl = false)
        assertEquals(setOf("n"), IcuMessage.parse(m).argNames)
        val out = IcuMessage.parse(m).format(mapOf("n" to 1), { "one" }, { it })
        assertTrue(out.startsWith("[Îţ'š {ļîţéŕåļ} # 1"), out)
    }

    /** Decode cost of each table, printed for the README (the first switch to a language pays it once). */
    @Test
    fun tableLoadCost() {
        val lines = ArrayList<String>()
        var total = 0L
        for (id in L10n.supported) {
            val mark = TimeSource.Monotonic.markNow()
            val t = requireNotNull(L10nData.table(id))
            val us = mark.elapsedNow().inWholeMicroseconds
            total += us
            lines += "$id ${t.count { it != null }} msgs ${us}us"
        }
        val mark = TimeSource.Monotonic.markNow()
        L10n.setLanguage("ru-RU")
        val first = L.settings.language
        val switchUs = mark.elapsedNow().inWholeMicroseconds
        println("l10n table decode: total ${total}us for ${lines.size} tables; switch to ru-RU + first message ${switchUs}us\n" + lines.joinToString("\n"))
        assertNotEquals("Language", first)
    }
}

/** Runs [block] with English active, then restores the language that was active before. */
internal fun <T> L10n.withEnglish(block: () -> T): T {
    val before = language.value
    val table = if (before == SOURCE) null else L10nData.table(before)
    setLanguage(SOURCE)
    try {
        return block()
    } finally {
        if (table != null) install(before, table, isRtl(before), before)
    }
}

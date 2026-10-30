package com.ecency.l10n.gen

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SystemStringsTest {
    private val repo = generateSequence(File("").absoluteFile) { it.parentFile }.first { it.resolve("src/config/locales").isDirectory }
    private val localesDir = repo.resolve("src/config/locales")
    private val l10nDir = repo.resolve("native/shared/l10n")
    private fun realLocales() = readLocales(localesDir)
    private fun realConfig() = l10nDir.resolve("system-strings.json").readText()

    private val config = """{"android": {"label": "a.label"}, "infoPlist": {"NSCameraUsageDescription": "a.camera"},
        "widget": {"label": "a.label", "count": "a.count", "named": "a.named"}}"""
    private val rules = """{"source": "test", "rules": {"en-US": {"one": "i = 1 and v = 0"}, "ru-RU": {"one": "v = 0 and i % 10 = 1 and i % 100 != 11", "few": "v = 0 and i % 10 = 2..4 and i % 100 != 12..14"}, "he-IL": {}}}"""
    private fun formats(vararg ids: String) = """{"source": "test", "locales": {${ids.joinToString(",") { "\"$it\": {\"digits\": \"0123456789\", \"group\": \",\", \"minus\": \"-\", \"grouping\": [3, 3, 1]}" }}}}"""
    private val en = """{"a": {"label": "Label", "camera": "Camera's use", "count": "{n, plural, one {# item} other {# items}}", "named": "Hi {name}"}}"""

    @Test
    fun `resource folders follow the system tags with Android's legacy codes`() {
        assertEquals("values", SystemStrings.androidQualifier("en-US"))
        assertEquals("values-de", SystemStrings.androidQualifier("de-DE"))
        assertEquals("values-iw", SystemStrings.androidQualifier("he-IL"))
        assertEquals("values-in", SystemStrings.androidQualifier("id-ID"))
        assertEquals("values-pt-rPT", SystemStrings.androidQualifier("pt-PT"))
        assertEquals("values-b+zh+Hans", SystemStrings.androidQualifier("zh-CN"))
        assertEquals("values-b+zh+Hant", SystemStrings.androidQualifier("zh-TW"))
        assertEquals("values-b+sr+Latn", SystemStrings.androidQualifier("sr-CS"))
        assertEquals("values-b+fil", SystemStrings.androidQualifier("fil-PH"))
        assertEquals("values-ku", SystemStrings.androidQualifier("ku-TR"))
        // bare language folders for devices no region or script folder matches, as LocaleMatch maps them
        assertEquals(
            mapOf("values-pt" to "pt-PT", "values-sr" to "sr-CS", "values-zh" to "zh-CN"),
            SystemStrings.androidFallbacks(listOf("zh-TW", "de-DE", "en-US", "pt-PT", "sr-CS", "zh-CN", "fil-PH", "he-IL")),
        )
    }

    @Test
    fun `android values escape what aapt would read as syntax`() {
        assertEquals("l\\'ami \\\"x\\\"", SystemStrings.androidValue("l'ami \"x\""))
        assertEquals("\\@home", SystemStrings.androidValue("@home"))
        assertEquals("\\?attr", SystemStrings.androidValue("?attr"))
        assertEquals("a@b?", SystemStrings.androidValue("a@b?"), "only a leading @ or ? is a reference")
        assertEquals("a &amp; b &lt;i&gt;", SystemStrings.androidValue("a & b <i>"))
        assertEquals("back\\\\slash\\nline\\ttab", SystemStrings.androidValue("back\\slash\nline\ttab"))
        assertEquals("\\u0007", SystemStrings.androidValue("\u0007"))
        assertEquals("\" padded \"", SystemStrings.androidValue(" padded "))
        assertEquals("\"two  spaces\"", SystemStrings.androidValue("two  spaces"))
        val xml = SystemStrings.androidStringsXml(listOf("p" to "50% off", "q" to "plain"))
        assertTrue("<string name=\"p\" formatted=\"false\">50% off</string>" in xml, xml)
        assertTrue("<string name=\"q\">plain</string>" in xml, xml)
        // what aapt reads back: the XML parses and holds the escaped text
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(SystemStrings.androidStringsXml(listOf("x" to "a & <b> l'ami")).byteInputStream())
        assertEquals("a & <b> l\\'ami", doc.getElementsByTagName("string").item(0).textContent)
    }

    @Test
    fun `apple values escape quotes, backslashes and control characters`() {
        assertEquals("say \\\"hi\\\"", SystemStrings.appleValue("say \"hi\""))
        assertEquals("a\\\\b\\nc\\td", SystemStrings.appleValue("a\\b\nc\td"))
        assertEquals("\\U0007", SystemStrings.appleValue("\u0007"))
        assertEquals("50% l'ami @x ?y", SystemStrings.appleValue("50% l'ami @x ?y"), "nothing else is special in a .strings value")
        assertEquals("/* ${SystemStrings.HEADER_NOTE} */\n\"K\" = \"V \\\"q\\\"\";\n", SystemStrings.appleStrings(listOf("K" to "V \"q\"")))
    }

    @Test
    fun `a locale shows its translation only where the runtime would`() {
        assertEquals("Hallo" to true, SystemStrings.resolve("Hello", "Hallo"))
        assertEquals("Hello" to false, SystemStrings.resolve("Hello", null))
        assertEquals("Hello" to false, SystemStrings.resolve("Hello", ""))
        assertEquals("Hello" to false, SystemStrings.resolve("Hello", "Hello"))
        assertEquals("Hi {name}" to false, SystemStrings.resolve("Hi {name}", "Salut {nom}"), "a placeholder English lacks")
        assertEquals("Hi" to true, SystemStrings.resolve("Hi {name}", "Hi"), "a dropped placeholder is allowed")
        assertEquals("Hello" to false, SystemStrings.resolve("Hello", "Hallo {"), "malformed")
        // ICU quoting resolves as the runtime does: a lone apostrophe stays, '' is one, '{' quotes
        assertEquals("l'ami", SystemStrings.plainText("k", "l'ami"))
        assertEquals("it's {x}", SystemStrings.plainText("k", "it''s '{x}'"))
        assertFailsWith<IllegalArgumentException> { SystemStrings.plainText("k", "Hi {name}") }
    }

    @Test
    fun `android writes every string in every language, English where untranslated`() {
        val files = SystemStrings.android(
            mapOf("en-US" to en, "de-DE" to """{"a": {"label": "Etikett"}}""", "he-IL" to """{"a": {"label": "{oops}"}}"""),
            config,
        )
        assertEquals(setOf("xml/locales_config.xml", "values/strings.xml", "values-de/strings.xml", "values-iw/strings.xml"), files.keys)
        assertTrue("<string name=\"label\">Etikett</string>" in files.getValue("values-de/strings.xml"))
        assertTrue("<string name=\"label\">Label</string>" in files.getValue("values-iw/strings.xml"), "a refused translation shows English")
        assertTrue("android:name=\"he\"" in files.getValue("xml/locales_config.xml"))
        val missing = """{"android": {"x": "a.nope"}, "infoPlist": {}, "widget": {}}"""
        assertFailsWith<IllegalArgumentException> { SystemStrings.android(mapOf("en-US" to en), missing) }
        val withArgs = """{"android": {"x": "a.named"}, "infoPlist": {}, "widget": {}}"""
        assertFailsWith<IllegalArgumentException> { SystemStrings.android(mapOf("en-US" to en), withArgs) }
    }

    @Test
    fun `apple writes InfoPlist strings per tag and the widget table`() {
        val files = SystemStrings.apple(
            mapOf("en-US" to en, "ru-RU" to """{"a": {"camera": "Камера", "count": "{n, plural, one {# штука} few {# штуки} other {# штук}}"}}""", "he-IL" to "{}"),
            config, rules, formats("en-US", "ru-RU", "he-IL"),
        )
        assertEquals(setOf("InfoPlist/en.lproj/InfoPlist.strings", "InfoPlist/ru.lproj/InfoPlist.strings", "InfoPlist/he.lproj/InfoPlist.strings", "WidgetL10nData.swift"), files.keys)
        assertTrue("\"NSCameraUsageDescription\" = \"Camera's use\";" in files.getValue("InfoPlist/en.lproj/InfoPlist.strings"))
        assertTrue("\"NSCameraUsageDescription\" = \"Камера\";" in files.getValue("InfoPlist/ru.lproj/InfoPlist.strings"))
        val swift = files.getValue("WidgetL10nData.swift")
        assertTrue("static let languages: [String] = [\"en-US\", \"he-IL\", \"ru-RU\"]" in swift, swift)
        assertTrue("static let systemTags: [String] = [\"en\", \"he\", \"ru\"]" in swift, swift)
        assertTrue("\"ru-RU\": [nil, \"{n, plural, one {# штука} few {# штуки} other {# штук}}\", nil]," in swift, swift)
        assertTrue("\"he-IL\": [" !in swift, "a language with nothing of its own has no row")
        assertTrue("var label: String { text(0) }" in swift, swift)
        assertTrue("func count(n: Int) -> String { format(1, [\"n\": .number(n)]) }" in swift, swift)
        assertTrue("func named(name: String) -> String { format(2, [\"name\": .text(name)]) }" in swift, swift)
        assertTrue("case \"ru-RU\": return r1(o)" in swift, swift)
        assertTrue("if (inR(o.v, 0, 0) && inR((o.i % 10), 2, 4) && !inR((o.i % 100), 12, 14)) { return \"few\" }" in swift, swift)
        assertEquals(swift, SystemStrings.apple(mapOf("en-US" to en, "ru-RU" to """{"a": {"camera": "Камера", "count": "{n, plural, one {# штука} few {# штуки} other {# штук}}"}}""", "he-IL" to "{}"), config, rules, formats("en-US", "ru-RU", "he-IL")).getValue("WidgetL10nData.swift"), "deterministic")
    }

    @Test
    fun `integer plural rules use whole number literals`() {
        assertEquals("(inR((o.i % 10), 2, 4) && !inR((o.i % 100), 12, 14))", PluralRuleCompiler.compile("i % 10 = 2..4 and i % 100 != 12..14", integer = true))
        assertEquals("(inR((o.i % 10.0), 2.0, 4.0) && !inR((o.i % 100.0), 12.0, 14.0))", PluralRuleCompiler.compile("i % 10 = 2..4 and i % 100 != 12..14"))
    }

    @Test
    fun `the real files give every OS string in all 39 languages`() {
        val locales = realLocales()
        assertEquals(39, locales.size)
        val conf = SystemStrings.config(realConfig())
        val android = SystemStrings.android(locales, realConfig())
        assertEquals(39 + 1 + 3, android.size)
        assertEquals(android.getValue("values-pt-rPT/strings.xml"), android.getValue("values-pt/strings.xml"))
        assertEquals(android.getValue("values-b+sr+Latn/strings.xml"), android.getValue("values-sr/strings.xml"))
        assertEquals(android.getValue("values-b+zh+Hans/strings.xml"), android.getValue("values-zh/strings.xml"))
        for (id in locales.keys) {
            val xml = android.getValue("${SystemStrings.androidQualifier(id)}/strings.xml")
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())
            assertEquals(conf.android.keys, (0 until doc.getElementsByTagName("string").length).map { doc.getElementsByTagName("string").item(it).attributes.getNamedItem("name").nodeValue }.toSet(), id)
        }
        // a reused RN key carries its translations
        assertTrue("<string name=\"widget_notifications_label\">Benachrichtigungen</string>" in android.getValue("values-de/strings.xml"))
        val apple = SystemStrings.apple(locales, realConfig(), l10nDir.resolve("cldr-plural-rules.json").readText(), l10nDir.resolve("cldr-formats.json").readText())
        assertEquals(39 + 1, apple.size)
        for (id in locales.keys) {
            val strings = apple.getValue("InfoPlist/${SystemLocales.tag(id)}.lproj/InfoPlist.strings")
            for (k in conf.infoPlist.keys) assertTrue("\"$k\" = \"" in strings, "$id $k")
        }
        val swift = apple.getValue("WidgetL10nData.swift")
        for (name in conf.widget.keys) assertTrue(Regex("(var|func) $name\\b").containsMatchIn(swift), name)
        assertTrue("\"de-DE\": [\"Benachrichtigungen\"" in swift)
    }

    @Test
    fun `the project's base Info plist texts are the English catalog's`() {
        val yml = repo.resolve("native/iosApp/project.yml").readText()
        val en = L10nGenerator.flatten(MiniJson.parse(localesDir.resolve("en-US.json").readText()), "")
        for ((plistKey, key) in SystemStrings.config(realConfig()).infoPlist) {
            val line = Regex("^\\s+$plistKey: (.+)$", RegexOption.MULTILINE).find(yml)?.groupValues?.get(1)
            assertEquals(SystemStrings.plainText(key, en.getValue(key)), line, plistKey)
        }
    }

    @Test
    fun `the manifest and widget infos name only generated strings`() {
        val main = repo.resolve("native/androidApp/src/main")
        val used = (listOf(main.resolve("AndroidManifest.xml")) + main.resolve("res/xml").listFiles()!!.toList())
            .flatMap { f -> Regex("@string/([a-z0-9_]+)").findAll(f.readText()).map { it.groupValues[1] }.toList() }.toSet()
        assertEquals(setOf("app_name") + SystemStrings.config(realConfig()).android.keys, used)
    }
}

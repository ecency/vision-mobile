package com.ecency.l10n.gen

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SystemLocalesTest {
    private val localesDir = generateSequence(File("").absoluteFile) { it.parentFile }.map { it.resolve("src/config/locales") }.first { it.isDirectory }
    private val rules = """{"source": "test", "rules": {"en-US": {}, "sr-CS": {}, "zh-CN": {}}}"""

    @Test
    fun `tags are the bare language unless the variant matters`() {
        assertEquals("en", SystemLocales.tag("en-US"))
        assertEquals("ku", SystemLocales.tag("ku-TR"))
        assertEquals("fil", SystemLocales.tag("fil-PH"))
        assertEquals("zh-Hans", SystemLocales.tag("zh-CN"))
        assertEquals("zh-Hant", SystemLocales.tag("zh-TW"))
        assertEquals("sr-Latn", SystemLocales.tag("sr-CS"))
        assertEquals("pt-PT", SystemLocales.tag("pt-PT"))
    }

    @Test
    fun `locales config lists every locale once, sorted by id`() {
        val xml = SystemLocales.localeConfigXml(listOf("ru-RU", "en-US", "zh-TW"))
        assertEquals(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <!-- Generated from src/config/locales by native/build-logic (SystemLocales). Do not edit. -->
            <locale-config xmlns:android="http://schemas.android.com/apk/res/android">
                <locale android:name="en" />
                <locale android:name="ru" />
                <locale android:name="zh-Hant" />
            </locale-config>
            """.trimIndent() + "\n",
            xml,
        )
        assertFailsWith<IllegalArgumentException> { SystemLocales.localeConfigXml(listOf("de-DE", "de-AT")) }
    }

    @Test
    fun `the real locale files give 39 distinct tags`() {
        val ids = localesDir.listFiles { f -> f.name.endsWith(".json") }!!.map { it.name.removeSuffix(".json") }
        assertEquals(39, ids.size)
        val xml = SystemLocales.localeConfigXml(ids)
        assertEquals(39, Regex("<locale android:name=").findAll(xml).count())
        for (t in listOf("en", "ar", "he", "fa", "ku", "zh-Hans", "zh-Hant", "sr-Latn", "pt-PT")) assertTrue("\"$t\"" in xml, t)
    }

    @Test
    fun `the generated data carries the tag of every locale in order`() {
        val data = L10nGenerator.generate(mapOf("en-US" to """{"a": "A"}""", "zh-CN" to "{}", "sr-CS" to "{}"), rules).getValue("L10nData.kt")
        assertTrue("val LOCALES: List<String> = listOf(\"en-US\", \"sr-CS\", \"zh-CN\")" in data, data)
        assertTrue("val SYSTEM_TAGS: List<String> = listOf(\"en\", \"sr-Latn\", \"zh-Hans\")" in data, data)
    }
}

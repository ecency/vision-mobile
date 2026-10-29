package com.ecency.l10n.gen

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FormatDataGeneratorTest {
    private val nativeDir = generateSequence(File("").absoluteFile) { it.parentFile }.first { it.resolve("shared/l10n").isDirectory }
    private val realFile = nativeDir.resolve("shared/l10n/cldr-formats.json").readText()
    private val localeIds = nativeDir.resolve("../src/config/locales").listFiles { f -> f.name.endsWith(".json") }!!.map { it.name.removeSuffix(".json") }

    @Suppress("UNCHECKED_CAST")
    private fun withEnUs(edit: (MutableMap<String, Any?>) -> Unit): String {
        val root = MiniJson.parse(realFile) as Map<String, Any?>
        val en = ((root["locales"] as Map<String, Any?>)["en-US"] as Map<String, Any?>).toMutableMap()
        edit(en)
        return FormatDataGenerator.write(mapOf("source" to "test", "locales" to mapOf("en-US" to en)))
    }

    @Test
    fun `the vendored file covers every locale file and generates one minified text each`() {
        val out = FormatDataGenerator.generate(realFile, localeIds)
        assertEquals(39, localeIds.size)
        for (id in localeIds) assertTrue("        \"$id\" -> \"{" in out, id)
        assertTrue("internal object L10nFormatData" in out)
        assertTrue(out.lines().none { it.startsWith("        \"") && "\n" in it })
        // deterministic
        assertEquals(out, FormatDataGenerator.generate(realFile, localeIds.reversed()))
    }

    @Test
    fun `a locale without data fails the build`() {
        val e = assertFailsWith<IllegalArgumentException> { FormatDataGenerator.generate(withEnUs { }, listOf("en-US", "xx-YY")) }
        assertTrue("xx-YY" in e.message!!, e.message)
    }

    @Test
    fun `a date field the runtime does not format fails the build`() {
        val bad = withEnUs { it["dates"] = (it["dates"] as Map<*, *>).toMutableMap().apply { put("jmm", "Bh:mm") } }
        val e = assertFailsWith<IllegalArgumentException> { FormatDataGenerator.generate(bad, listOf("en-US")) }
        assertTrue("'B'" in e.message!!, e.message)
        // quoted letters are text, not fields
        FormatDataGenerator.checkPattern("en-US", "yMMMd", "d 'de' MMMM 'de' y")
        assertFailsWith<IllegalArgumentException> { FormatDataGenerator.checkPattern("en-US", "yMMMd", "yyyy") }
    }

    @Test
    fun `broken numbers data fails the build`() {
        assertFailsWith<IllegalArgumentException> { FormatDataGenerator.generate(withEnUs { it["digits"] = "0123" }, listOf("en-US")) }
        assertFailsWith<IllegalArgumentException> { FormatDataGenerator.generate(withEnUs { it["grouping"] = listOf(3.0, 0.0, 1.0) }, listOf("en-US")) }
        assertFailsWith<IllegalArgumentException> { FormatDataGenerator.generate(withEnUs { it["MMM"] = listOf("Jan") }, listOf("en-US")) }
        assertFailsWith<IllegalArgumentException> { FormatDataGenerator.generate(withEnUs { it.remove("past") }, listOf("en-US")) }
        FormatDataGenerator.generate(withEnUs { }, listOf("en-US"))
    }

    @Test
    fun `the writer escapes quotes, backslashes and control characters`() {
        assertEquals("""{"a":"x\"y\\z\u000a","n":[3,1.5]}""", FormatDataGenerator.write(mapOf("a" to "x\"y\\z\n", "n" to listOf(3.0, 1.5))))
    }
}

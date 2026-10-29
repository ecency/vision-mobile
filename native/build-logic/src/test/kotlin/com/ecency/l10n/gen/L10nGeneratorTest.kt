package com.ecency.l10n.gen

import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class L10nGeneratorTest {
    private val rules = """{"source": "test", "rules": {"en-US": {"one": "i = 1 and v = 0"}, "ru-RU": {"one": "v = 0 and i % 10 = 1 and i % 100 != 11", "few": "v = 0 and i % 10 = 2..4 and i % 100 != 12..14", "many": "v = 0 and i % 10 = 0 or v = 0 and i % 10 = 5..9 or v = 0 and i % 100 = 11..14"}, "ja-JP": {}}}"""

    private val en = """
        {
          "settings": {"language": "Language", "title": "Settings"},
          "post": {
            "a11y_votes": "{count, plural, one {# vote} other {# votes}}",
            "by": "by {username} in {community}",
            "edit": {"save": "Save"}
          },
          "chain-error": {"min-root-comment": "Wait {n} minutes"},
          "POINTS": "Points",
          "points": "points",
          "500_error": "Server error",
          "class": "Class"
        }
    """.trimIndent()

    private val ru = """{"settings": {"language": "Язык", "title": "Settings"}, "post": {"by": "от {username}"}, "stale": "x"}"""

    private fun gen() = L10nGenerator.generate(mapOf("en-US" to en, "ru-RU" to ru, "ja-JP" to "{}"), rules)

    @Test
    fun `api has one accessor per key with typed parameters`() {
        val api = gen().getValue("L.kt")
        assertTrue("val settings: Settings get() = Settings" in api)
        assertTrue("val language: String get() = L10n.text(0)" in api, api)
        assertTrue("fun a11yVotes(count: Int): String = L10n.format(2, arrayOf(\"count\"), arrayOf<Any>(count))" in api, api)
        assertTrue("fun by(username: String, community: String): String = L10n.format(3, arrayOf(\"username\", \"community\"), arrayOf<Any>(username, community))" in api, api)
        assertTrue("object Edit {" in api && "val save: String get() = L10n.text(4)" in api, api)
        assertTrue("val chainError: ChainError get() = ChainError" in api)
        assertTrue("fun minRootComment(n: String)" in api)
        // collisions and identifiers: POINTS keeps camel "points", the later "points" falls back to its raw key
        assertTrue("val points: String get() = L10n.text(6)" in api, api)
        assertTrue("val points_2: String get() = L10n.text(7)" in api, api)
        assertTrue("val _500Error: String get() = L10n.text(8)" in api, api)
        assertTrue("val class_: String get() = L10n.text(9)" in api, api)
        assertTrue("/** `post.a11y_votes`: {count, plural, one {# vote} other {# votes}} */" in api)
        // the Kotlin tree stays out of the Objective-C header (Swift has its own L)
        assertTrue("@HiddenFromObjC\nobject L {" in api && "@HiddenFromObjC\n    object Settings {" in api, api)
    }

    @Test
    fun `swift api mirrors the keys as nested enums with clean names`() {
        val swift = gen().getValue("L.swift")
        assertTrue("import EcencyCore\n" in swift && "\nenum L {\n" in swift, swift)
        assertTrue("    enum Settings {\n        /// `settings.language`: Language\n        static var language: String { L10n.shared.text(index: 0) }" in swift, swift)
        assertTrue(
            "static func a11yVotes(count: Int) -> String { L10n.shared.formatArgs(index: 2, names: [\"count\"], values: [KotlinInt(value: Int32(clamping: count))]) }" in swift,
            swift,
        )
        assertTrue("static func by(username: String, community: String) -> String { L10n.shared.formatArgs(index: 3, names: [\"username\", \"community\"], values: [username, community]) }" in swift, swift)
        assertTrue("enum Edit {" in swift && "enum ChainError {" in swift, swift)
        assertTrue("static var `class`: String { L10n.shared.text(index: 9) }" in swift, swift)
        assertTrue("static var _500Error: String" in swift && "static var points: String { L10n.shared.text(index: 6) }" in swift, swift)
        assertFalse("doCopy" in swift || "description_" in swift || "new_" in swift)
    }

    @Test
    fun `swift names escape keywords and never shadow the types the code uses`() {
        val names = HashSet<String>()
        assertEquals("`default`", L10nGenerator.swiftName("default", names))
        assertEquals("self_", L10nGenerator.swiftName("self", HashSet()))
        assertEquals("_13", L10nGenerator.swiftName("13", HashSet()))
        assertEquals("points", names.let { L10nGenerator.swiftName("POINTS", it) })
        assertEquals("points_2", L10nGenerator.swiftName("points", names))
        val out = L10nGenerator.generate(mapOf("en-US" to """{"string": {"a": "A"}, "l": {"l": {"x": "X"}}}"""), rules).getValue("L.swift")
        assertTrue("enum String_ {" in out, out)
        assertTrue("enum L_ {" in out, out)
    }

    @Test
    fun `locale tables hold only messages that differ from English and decode by index`() {
        val out = gen()
        val ruTable = out.getValue("L10nLocale_ru_RU.kt")
        assertTrue("Язык" in ruTable)
        assertFalse("Settings" in ruTable.substringAfter("private const val"), "same as English must not be stored")
        assertFalse("\"x" in ruTable, "stale keys are dropped")
        val c0 = Regex("private const val C0 = \"(.*)\"").find(ruTable)!!.groupValues[1]
        assertEquals(listOf("Язык", "", "", "от {username}", "", "", "", "", "", ""), c0.split("\\u0000").dropLast(1))
        assertTrue("\"en-US\" -> L10nLocale_en_US.table()" in out.getValue("L10nData.kt"))
        assertTrue("const val SIZE = 10" in out.getValue("L10nData.kt"))
    }

    @Test
    fun `plural rules compile per locale with shared functions`() {
        val p = gen().getValue("L10nPluralRules.kt")
        assertTrue("\"en-US\" -> r0(o)" in p, p)
        assertTrue("\"ru-RU\" -> r1(o)" in p, p)
        assertFalse("\"ja-JP\" ->" in p, "only-other locales use the else branch")
        assertTrue("(inR(o.v, 0.0, 0.0) && inR((o.i % 10.0), 1.0, 1.0) && !inR((o.i % 100.0), 11.0, 11.0)) -> \"one\"" in p, p)
    }

    @Test
    fun `output is deterministic regardless of input map order`() {
        val a = gen()
        val b = L10nGenerator.generate(linkedMapOf("ja-JP" to "{}", "ru-RU" to ru, "en-US" to en), rules)
        assertEquals(a, b)
        assertEquals(a.keys.toList(), b.keys.toList())
    }

    @Test
    fun `broken English messages and missing rules fail the build`() {
        assertFailsWith<IllegalArgumentException> { L10nGenerator.generate(mapOf("en-US" to """{"a": "{n, plural, one {x}}"}"""), rules) }
        assertFailsWith<IllegalArgumentException> { L10nGenerator.generate(mapOf("en-US" to """{"a": "{n"}"""), rules) }
        assertFailsWith<IllegalArgumentException> { L10nGenerator.generate(mapOf("en-US" to en, "xx-XX" to "{}"), rules) }
        // a broken translation file is a build error too (Crowdin always writes valid JSON)
        assertFailsWith<IllegalArgumentException> { L10nGenerator.generate(mapOf("en-US" to en, "ru-RU" to "{"), rules) }
    }

    @Test
    fun `chunks never split an entry and stay within the constant limit`() {
        val entries = List(50) { "x".repeat(it * 97) }
        val chunks = L10nGenerator.chunks(entries)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= L10nGenerator.CHUNK_CHARS })
        assertEquals(entries, chunks.joinToString("").split('\u0000').dropLast(1))
    }

    @Test
    fun `literals escape Kotlin specials`() {
        assertEquals("\"a\\\$b \\\"q\\\" \\\\ \\n\\u0000\"", L10nGenerator.literal("a\$b \"q\" \\ \n\u0000"))
    }

    @Test
    fun `names follow camel case and stay valid identifiers`() {
        assertEquals("a11yVotes", L10nGenerator.camel("a11y_votes"))
        assertEquals("chainError", L10nGenerator.camel("chain-error"))
        assertEquals("points", L10nGenerator.camel("POINTS"))
        assertEquals("_107", L10nGenerator.camel("107"))
        assertEquals("in_", L10nGenerator.camel("in"))
        assertEquals("hiveHBD", L10nGenerator.camel("hive_HBD"))
    }

    private val tricky = """
        {"type": {"a": "A"}, "protocol": {"b": "B"}, "any": {"c": "C"}, "self": {"d": "D"}, "Self": {"e": "E"},
         "string": {"f": "F"}, "class": {"default": "G {a${'$'}b} {n, plural, one {# x} other {# xs}} {v, number}"},
         "init": "H", "Type": "I", "self_": "J"}
    """.trimIndent()

    @Test
    fun `swift type names never take reserved names`() {
        val swift = L10nGenerator.generate(mapOf("en-US" to tricky), rules).getValue("L.swift")
        for (t in listOf("Type_", "Protocol_", "Any_", "Self_", "String_", "Class")) assertTrue("enum $t {" in swift, "$t in\n$swift")
        for (bad in listOf("enum Type {", "enum Protocol {", "enum Any {", "enum Self {", "enum String {")) assertFalse(bad in swift, bad)
        // a non identifier argument name is a valid Swift literal (no Kotlin `\$` escape)
        assertTrue("names: [\"a${'$'}b\", \"n\", \"v\"]" in swift, swift)
    }

    @Test
    fun `swift literals escape what Swift needs only`() {
        assertEquals("\"a${'$'}b \\\"q\\\" \\\\ \\n\\u{0}\\u{1b}\"", L10nGenerator.swiftLiteral("a${'$'}b \"q\" \\ \n\u0000\u001b"))
    }

    /** swiftc typechecks the Swift output (tricky names and the real files) against stubs of the framework API. */
    @Test
    fun `swift output typechecks`() {
        if (!File("/usr/bin/xcrun").exists()) return // no Swift toolchain (Linux CI): the iOS build checks it
        val stubs = """
            final class L10n { static let shared = L10n(); func text(index: Int32) -> String { "" }
                func formatArgs(index: Int32, names: [String], values: [Any]) -> String { "" } }
            final class KotlinInt { init(value: Int32) {} }
            final class KotlinDouble { init(value: Double) {} }
        """.trimIndent()
        val dir = kotlin.io.path.createTempDirectory("l10n-swift").toFile()
        try {
            val sources = listOf(
                L10nGenerator.generate(mapOf("en-US" to tricky), rules).getValue("L.swift"),
                L10nGenerator.generate(realLocales(), realRules()).getValue("L.swift"),
            )
            sources.forEachIndexed { i, src ->
                val f = dir.resolve("L$i.swift")
                f.writeText(src.replace("import EcencyCore\n", "") + "\n" + stubs + "\n")
                val p = ProcessBuilder("/usr/bin/xcrun", "swiftc", "-typecheck", f.path).redirectErrorStream(true).start()
                val out = p.inputStream.bufferedReader().readText()
                assertEquals(0, p.waitFor(), out)
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun localesDir() = generateSequence(File("").absoluteFile) { it.parentFile }.map { it.resolve("src/config/locales") }.first { it.isDirectory }

    private fun realLocales() = localesDir().listFiles { f -> f.name.endsWith(".json") }!!.associate { it.name.removeSuffix(".json") to it.readText() }

    private fun realRules() = localesDir().resolve("../../../native/shared/l10n/cldr-plural-rules.json").readText()

    /** The real files: generation succeeds, is order independent, and prints a digest to compare runs by hand. */
    @Test
    fun `real locale files generate`() {
        val dir = generateSequence(File("").absoluteFile) { it.parentFile }.map { it.resolve("src/config/locales") }.first { it.isDirectory }
        val locales = dir.listFiles { f -> f.name.endsWith(".json") }!!.associate { it.name.removeSuffix(".json") to it.readText() }
        val rulesFile = dir.resolve("../../../native/shared/l10n/cldr-plural-rules.json")
        val a = L10nGenerator.generate(locales, rulesFile.readText())
        val b = L10nGenerator.generate(locales.toSortedMap(compareByDescending { it }), rulesFile.readText())
        assertEquals(a, b)
        assertEquals(locales.size + 4, a.size)
        val md = MessageDigest.getInstance("SHA-256")
        a.forEach { (k, v) -> md.update(k.toByteArray()); md.update(v.toByteArray()) }
        println("l10n generator output digest: " + md.digest().joinToString("") { "%02x".format(it) })
    }
}

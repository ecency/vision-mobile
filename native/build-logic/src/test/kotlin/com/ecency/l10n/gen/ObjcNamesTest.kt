package com.ecency.l10n.gen

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The copy objects are exported to Swift through the framework's Objective-C header. A member named like an
 * Objective-C macro or keyword (`YES` and `NO` are macros) makes the header invalid and every Swift file fails to
 * compile; a name in an Objective-C method family (`new`, `copy`, `alloc`, `init`, `mutableCopy` followed by an
 * upper case letter or nothing) changes its memory rules or gets renamed. This scan fails on either in any object of
 * the shared core whose name ends in `Copy` or `Text`.
 */
class ObjcNamesTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { it.resolve("src/config/locales/en-US.json").isFile }
    private val sources = root.resolve("native/shared/src/commonMain").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun noCopyMemberClashesWithObjectiveC() {
        val found = sources.flatMap { f -> members(f.readText()).map { "${f.name}: ${it.first}.${it.second}" to it.second } }
        assertTrue(found.size > 500, "the scan found only ${found.size} copy members")
        val bad = found.filter { (_, name) -> clashes(name) }.map { it.first }
        assertTrue(bad.isEmpty(), "Copy members whose names break the Objective-C header (rename them):\n" + bad.joinToString("\n"))
    }

    @Test
    fun theRuleKnowsTheClashes() {
        for (n in listOf("YES", "NO", "nil", "id", "BOOL", "self", "NULL", "TRUE", "newPost", "copyLink", "init", "alloc", "mutableCopyOf"))
            assertTrue(clashes(n), n)
        for (n in listOf("NO_BUTTON", "YES_BUTTON", "COPY_LINK", "NEW", "copied", "newsletter", "keyCopyLabel", "initials", "CANCEL"))
            assertTrue(!clashes(n), n)
    }

    companion object {
        private val reserved = setOf("YES", "NO", "nil", "Nil", "id", "BOOL", "SEL", "Class", "self", "super", "NULL", "TRUE", "FALSE", "IMP", "Protocol")
        private val family = Regex("""^(new|copy|alloc|init|mutableCopy)($|[A-Z_0-9])""")

        fun clashes(name: String): Boolean = name in reserved || family.containsMatchIn(name)

        /** (object, member) for each `val`/`fun` declared directly in an object named `*Copy` or `*Text`. */
        fun members(src: String): List<Pair<String, String>> {
            val out = mutableListOf<Pair<String, String>>()
            for (m in Regex("""(?m)^(?:internal )?object (\w+(?:Copy|Text))\b[^{\n]*\{""").findAll(src)) {
                var depth = 1
                var i = m.range.last + 1
                val body = StringBuilder()
                while (i < src.length && depth > 0) {
                    val c = src[i]
                    if (c == '{') depth++ else if (c == '}') depth--
                    // keep only the object's own level
                    body.append(if (depth == 1 || (depth == 2 && c == '{')) c else ' ')
                    i++
                }
                Regex("""(?m)^\s*(?:(?:const|override|internal|inline)\s+)*(?:val|var|fun)\s+(?:<[^>]*>\s*)?(\w+)""").findAll(body).forEach { out += m.groupValues[1] to it.groupValues[1] }
            }
            return out
        }
    }
}

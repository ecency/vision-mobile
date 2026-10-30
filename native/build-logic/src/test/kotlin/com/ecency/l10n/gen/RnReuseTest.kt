package com.ecency.l10n.gen

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Native copy reuses an RN key only when native's English is the RN English word for word (owner decision 10-22:
 * native wording wins; a reworded text gets a `native.*` key). `native/shared/l10n/rn-reuse.json` records every reused
 * key with the English native showed when it moved to the catalog. These checks keep a reuse from turning into a
 * silent rewording: RN changing such an English text fails here, and so does shared, Android or iOS code reading an RN
 * key nobody recorded.
 */
class RnReuseTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { it.resolve("src/config/locales/en-US.json").isFile }
    private val enText = root.resolve("src/config/locales/en-US.json").readText()
    private val en = L10nGenerator.flatten(MiniJson.parse(enText), "")

    @Suppress("UNCHECKED_CAST")
    private val reuse: Map<String, String> =
        (MiniJson.parse(root.resolve("native/shared/l10n/rn-reuse.json").readText()) as Map<String, Any?>).mapValues { it.value as String }

    @Test
    fun everyReusedKeyStillHasTheNativeEnglish() {
        assertTrue(reuse.isNotEmpty())
        val changed = reuse.filter { (k, v) -> en[k] != v }.map { (k, v) -> "$k: native \"$v\", en-US \"${en[k]}\"" }
        assertTrue(changed.isEmpty(), "RN English changed under a reused key (give native its own key):\n" + changed.joinToString("\n"))
        assertTrue(reuse.keys.none { it.startsWith("native.") }, "native.* keys are native copy, not reuses")
    }

    @Test
    fun everyRnKeyTheKotlinCodeReadsIsRecorded() {
        val keyOf = L10nGenerator.kotlinAccessors(enText).entries.associate { (k, path) -> path to k }
        val accessor = Regex("""\bL(\.[A-Za-z_][A-Za-z0-9_]*)+""")
        val used = sortedSetOf<String>()
        val sources = listOf("native/shared/src/commonMain", "native/androidApp/src/main").map(root::resolve)
        for (f in sources.flatMap { d -> d.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }) {
            if (f.path.contains("/core/l10n/")) continue
            for (m in accessor.findAll(stripComments(f.readText()))) {
                val parts = m.value.split('.')
                (parts.size downTo 2).firstNotNullOfOrNull { n -> keyOf[parts.take(n).joinToString(".")] }?.let(used::add)
            }
        }
        assertTrue(used.size > 500, "the scan found only ${used.size} keys; did the accessor naming change?")
        val unrecorded = used.filter { !it.startsWith("native.") && it !in reuse }
        assertTrue(unrecorded.isEmpty(), "RN keys read by Kotlin code but not in rn-reuse.json (check the English matches, then record them):\n" + unrecorded.joinToString("\n"))
    }

    @Test
    fun everyRnKeyTheSwiftCodeReadsIsRecorded() {
        // the iOS app reads the catalog through the generated Swift `L` (`L.Post.a11yVotes(count:)`); its doc comments
        // name each accessor's key, so the accessor paths map back to keys without a second copy of the naming rules
        val api = L10nGenerator.generate(mapOf("en-US" to enText), root.resolve("native/shared/l10n/cldr-plural-rules.json").readText()).getValue("L.swift")
        val keyOf = swiftAccessors(api)
        assertEquals(en.size, keyOf.size, "every key has one Swift accessor")
        assertEquals("settings.language", keyOf["L.Settings.language"])
        val accessor = Regex("""\bL(\.`?[A-Za-z_][A-Za-z0-9_]*`?)+""")
        val used = sortedSetOf<String>()
        val app = root.resolve("native/iosApp")
        for (f in app.walkTopDown().filter { it.isFile && it.extension == "swift" && "/Generated/" !in it.path }) {
            for (m in accessor.findAll(stripComments(f.readText()))) {
                val parts = m.value.split('.')
                (parts.size downTo 2).firstNotNullOfOrNull { n -> keyOf[parts.take(n).joinToString(".")] }?.let(used::add)
            }
        }
        assertTrue(used.size > 20, "the Swift scan found only ${used.size} keys; did the accessor naming change?")
        val unrecorded = used.filter { !it.startsWith("native.") && it !in reuse }
        assertTrue(unrecorded.isEmpty(), "RN keys read by Swift code but not in rn-reuse.json (check the English matches, then record them):\n" + unrecorded.joinToString("\n"))
    }

    /** Accessor path (`L.Post.a11yVotes`) to key, read from the generated L.swift: each member follows its doc line. */
    private fun swiftAccessors(api: String): Map<String, String> {
        val out = HashMap<String, String>()
        val types = ArrayList<String>()
        var pending: String? = null
        for (line in api.lines().map { it.trim() }) {
            val type = Regex("""^enum (\S+) \{$""").find(line)
            if (type != null) { types += type.groupValues[1]; continue }
            if (line == "}") { if (types.isNotEmpty()) types.removeAt(types.size - 1); continue }
            Regex("""^/// `([^`]+)`:""").find(line)?.let { pending = it.groupValues[1] }
            val member = Regex("""^static (var|func) (`?[A-Za-z0-9_]+`?)""").find(line) ?: continue
            pending?.let { out[(types + member.groupValues[2]).joinToString(".")] = it }
            pending = null
        }
        return out
    }

    @Test
    fun everyKeyReadAtRunTimeIsRecorded() {
        val app = root.resolve("native/shared/src/commonMain/kotlin/com/ecency/core/app")
        // NotificationFormat.COPY_KEYS, read as `notification.<key>`
        val notify = app.resolve("NotificationFormat.kt").readText()
        val copyKeys = Regex("""COPY_KEYS = setOf\(([^)]*)\)""").find(notify)?.groupValues?.get(1)
            ?.let { Regex("\"([^\"]+)\"").findAll(it).map { m -> "notification." + m.groupValues[1] }.toList() }.orEmpty()
        // PostMenuItem ids, read as `post_dropdown.<id>`
        val menu = app.resolve("PostActionsModel.kt").readText().substringAfter("enum class PostMenuItem(").substringBefore(";")
        val menuKeys = Regex("""\n\s+[A-Z_]+\("([^"]+)"\)""").findAll(menu).map { "post_dropdown." + it.groupValues[1] }.toList()
        assertTrue(copyKeys.size > 40 && menuKeys.size > 20, "the run time key lists moved (${copyKeys.size}, ${menuKeys.size})")
        val all = copyKeys + menuKeys
        assertTrue(all.none { it !in en }, "run time keys missing from en-US: " + all.filter { it !in en })
        val unrecorded = all.filter { it !in reuse }
        assertTrue(unrecorded.isEmpty(), "run time keys not in rn-reuse.json:\n" + unrecorded.joinToString("\n"))
    }

    @Test
    fun accessorNamesFollowTheGeneratedApi() {
        val acc = L10nGenerator.kotlinAccessors(enText)
        assertEquals("L.post.a11yVotes", acc["post.a11y_votes"])
        assertEquals("L.settings.language", acc["settings.language"])
        assertEquals(en.size, acc.size)
        val api = L10nGenerator.generate(mapOf("en-US" to enText), root.resolve("native/shared/l10n/cldr-plural-rules.json").readText()).getValue("L.kt")
        // every accessor's last name is declared in the generated API
        for (path in acc.values.shuffled(kotlin.random.Random(1)).take(200)) {
            val name = path.substringAfterLast('.')
            assertTrue(Regex("""(val|fun) ${Regex.escape(name)}\b""").containsMatchIn(api), "$path not in L.kt")
        }
    }

    private fun stripComments(s: String): String =
        s.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "").replace(Regex("""(^|\s)//[^\n]*""", RegexOption.MULTILINE), "$1")
}

package com.ecency.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The icon rules on both platforms' sources (a grep, run in the unit suite): icons come only from the maps
 * (Android ui/AppIconMap.kt, iOS Screens/AppIconMap.swift, the widgets' WidgetSymbols.swift), and no text or emoji
 * character stands in for an icon.
 */
class AppIconSourceTest {
    private val native = File("..").canonicalFile
    private val androidSources = File(native, "androidApp/src/main/kotlin")
    private val iosSources = listOf("EcencyApp", "Widgets", "ShareExtension", "NotificationService").map { File(native, "iosApp/$it") }

    private val list = File(native, "scripts/icons/icons.tsv").readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split('\t') }

    private fun files(root: File, ext: String) = root.walkTopDown().filter { it.isFile && it.extension == ext }.toList()

    /** Code lines with their file and number, comments dropped. */
    private fun codeLines(files: List<File>) = files.flatMap { f ->
        f.readLines().mapIndexedNotNull { i, line ->
            val code = line.trim()
            if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) null else "${f.name}:${i + 1}: $code"
        }
    }

    /** Arrows, geometric shapes, dingbats, the multiplication sign, card suits and pictographs inside a string literal. */
    private val iconCharacter = Regex(
        "\"[^\"]*[\u00D7\u2190-\u21FF\u25A0-\u25FF\u2600-\u27BF\u2B00-\u2BFF\\x{1F300}-\\x{1FAFF}][^\"]*\"",
    )

    /** The same characters written as escapes (list bullets of the post renderer are text, not icons). */
    private val iconEscape = Regex(
        """\\u\{?(00D7|21[0-9A-F]{2}|25[A-F][0-9A-F]|2[67][0-9A-F]{2}|2B[0-9A-F]{2}|D83[C-E]|1F[3-9A][0-9A-F]{2})""",
        RegexOption.IGNORE_CASE,
    )

    @Test
    fun androidIconsAreDrawnOnlyFromTheMap() {
        val offenders = codeLines(files(androidSources, "kt").filter { it.name != "AppIconMap.kt" })
            .filter { line ->
                listOf("ImageVector.Builder", "materialIcon(", "Icons.Filled", "Icons.Outlined", "Icons.Default", "Icons.Rounded", "Icons.Sharp", "Icons.AutoMirrored")
                    .any { it in line } ||
                    // the generated icon drawables are reached only through the map (ic_notification is the push small icon)
                    Regex("""R\.drawable\.ic_(?!notification\b)""").containsMatchIn(line)
            }
        assertEquals("icons are AppIcon entries drawn by AppIconMap.kt", emptyList<String>(), offenders)
    }

    @Test
    fun iosSymbolNamesLiveOnlyInTheMaps() {
        val maps = setOf("AppIconMap.swift", "WidgetSymbols.swift")
        val swift = iosSources.flatMap { files(it, "swift") }.filter { it.name !in maps }
        val offenders = codeLines(swift).filter { Regex("""(systemName|systemImage): *"""").containsMatchIn(it) }
        assertEquals("SF Symbol names come from AppIconMap.swift", emptyList<String>(), offenders)
    }

    @Test
    fun noTextOrEmojiStandsInForAnIcon() {
        val ui = files(androidSources, "kt") + iosSources.flatMap { files(it, "swift") }
        val offenders = codeLines(ui).filter { (iconCharacter.containsMatchIn(it) || iconEscape.containsMatchIn(it)) && !it.contains("bullets", ignoreCase = true) }
        assertEquals("draw an AppIcon instead of a text glyph", emptyList<String>(), offenders)
    }

    @Test
    fun widgetSymbolsAreTheListedOnes() {
        val listed = list.flatMap { listOf(it[3], it[4]) }.toSet()
        val widget = Regex("""case \w+ = "([a-z0-9.]+)"""").findAll(File(native, "iosApp/Widgets/WidgetSymbols.swift").readText()).map { it.groupValues[1] }.toList()
        assertTrue(widget.isNotEmpty())
        assertEquals(emptyList<String>(), widget.filter { it !in listed })
    }

    /** Every SF Symbol in the list exists on iOS 17, the deployment target (read from this Mac's symbol catalog). */
    @Test
    fun sfSymbolsExistOnIos17() {
        val catalog = File("/System/Library/CoreServices/CoreGlyphs.bundle/Contents/Resources/name_availability.plist")
        if (!catalog.exists()) println("AppIconSourceTest: skipped the iOS 17 SF Symbols check (no macOS symbol catalog here); run the unit suite on a Mac")
        assumeTrue("needs macOS's SF Symbols catalog", catalog.exists())
        val json = ProcessBuilder("plutil", "-convert", "json", "-o", "-", catalog.path).start().inputStream.bufferedReader().readText()
        val symbols = Json.parseToJsonElement(json).jsonObject.getValue("symbols").jsonObject
        val names = list.flatMap { listOf(it[3], it[4]) }.filter { it != "-" } +
            Regex("""= "([a-z0-9.]+)"""").findAll(File(native, "iosApp/Widgets/WidgetSymbols.swift").readText()).map { it.groupValues[1] }
        val tooNew = names.filter { n -> symbols[n]?.jsonPrimitive?.content.let { it == null || it > "2023" } }
        assertEquals("SF Symbols newer than iOS 17 (2023) or unknown", emptyList<String>(), tooNew)
    }
}

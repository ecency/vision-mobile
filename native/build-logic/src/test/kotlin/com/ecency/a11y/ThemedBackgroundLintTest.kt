package com.ecency.a11y

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every page, sheet, dialog and loader of the Android app draws the app theme (README "Themed backgrounds"): this
 * scan fails when new UI code paints a page another way. Android `UniformBackgroundTest` checks the rendered pixels.
 * - a spinner is the shared `Spinner` (ui/Skeleton.kt), never an ad hoc indeterminate `CircularProgressIndicator`;
 * - pull to refresh is the app's `PullToRefreshBox` (ui/Skeleton.kt, a themed indicator), not Material's;
 * - no `MaterialTheme.colorScheme` reads outside ui/Theme.kt (screens use `EcencyColors`);
 * - a `Scaffold` names `containerColor = colors.background`;
 * - a `ModalBottomSheet` keeps the theme background (its default) unless allowed below;
 * - a full screen container (`fillMaxSize()`) paints no other colour than the theme background unless allowed below;
 * - the platform date and time pickers take an app theme (a theme resource), not the window's.
 * The scan reads source text with balanced brackets, no compiler.
 */
class ThemedBackgroundLintTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { it.resolve("src/config/locales/en-US.json").isFile }
    private val app = root.resolve("native/androidApp/src/main/kotlin/com/ecency/app")
    private val sources = app.walkTopDown()
        .filter { it.isFile && it.extension == "kt" && !it.path.contains("/widgets/") }.sortedBy { it.path }.toList()

    /**
     * Deliberate non-theme full screen backgrounds and sheets: media and camera views are black in both themes, as in
     * every photo and video app, and overlays (a scrim, a swipe action) sit over a page. File name and a snippet of the line.
     */
    private val allowed = listOf(
        "TransferScreens.kt" to "testTag(\"transferQr\")", // the QR code camera
        "PlatformScreens.kt" to "testTag(\"qrScan\")", // the QR scanner camera
        "PlatformScreens.kt" to "testTag(\"bridgeWorking\")", // a scrim over the dApp page while it signs
        "WavesScreen.kt" to "testTag(\"shorts\")", // the full screen video reel
        "WavesScreen.kt" to "testTag(\"reel:", // one video of the reel
        "ShellDrawer.kt" to "Color.Black.copy(alpha = 0.5f)", // the side menu's scrim
        "NotificationScreens.kt" to "background(colors.blue).padding(horizontal = 24.dp)", // the swipe to read action under a row
        "Sheets.kt" to "testTag(\"videoSheet\")", // the video player sheet
        "Sheets.kt" to "testTag(\"gallery\")", // the image viewer
    )

    private fun isAllowed(file: File, line: String) = allowed.any { (name, snippet) -> file.name == name && snippet in line }

    private val themeBackground = Regex("""^(colors|LocalEcencyColors\.current)\.background$""")

    private fun lineAt(src: String, at: Int) = src.substring(0, at).count { it == '\n' } + 1

    private fun lineText(src: String, at: Int): String {
        val start = src.lastIndexOf('\n', at) + 1
        val end = src.indexOf('\n', at).let { if (it < 0) src.length else it }
        return src.substring(start, end)
    }

    private fun isImportOrComment(src: String, at: Int): Boolean {
        val before = src.substring(src.lastIndexOf('\n', at) + 1, at).trimStart()
        return before.startsWith("import ") || before.startsWith("//") || before.startsWith("*") || before.startsWith("/*")
    }

    private fun balanced(src: String, open: Int): String {
        var depth = 0
        var i = open
        var inString = false
        while (i < src.length) {
            val c = src[i]
            if (inString) {
                if (c == '\\') i++ else if (c == '"') inString = false
            } else when (c) {
                '"' -> inString = true
                '(' -> depth++
                ')' -> { depth--; if (depth == 0) return src.substring(open + 1, i) }
            }
            i++
        }
        return src.substring(open + 1)
    }

    /** Each call of [name] outside imports and comments: its line and its argument text. */
    private fun calls(src: String, name: String): List<Pair<Int, String>> =
        Regex("""(?<![\w])${Regex.escape(name)}\(""").findAll(src).filter { !isImportOrComment(src, it.range.first) }
            .map { lineAt(src, it.range.first) to balanced(src, it.range.last) }.toList()

    /** The value of `name = value` among [args] (top level text, up to the next comma), or null. */
    private fun argument(args: String, name: String): String? =
        Regex("""\b$name\s*=\s*([^,\n]+)""").find(args)?.groupValues?.get(1)?.trim()?.removeSuffix(")")

    private fun findings(check: (File, String) -> List<String>): List<String> = sources.flatMap { f -> check(f, f.readText()) }

    @Test
    fun spinnersAreTheSharedSpinner() {
        val bad = findings { f, src ->
            if (f.name == "Skeleton.kt") return@findings emptyList()
            // a determinate ring (`progress =`) is a gauge, not a loader
            (calls(src, "CircularProgressIndicator") + calls(src, "material3.CircularProgressIndicator"))
                .filter { (_, args) -> "progress =" !in args }.map { (l, _) -> "${f.name}:$l" }.distinct()
        }
        assertEquals(emptyList(), bad, "ad hoc spinners: use Spinner (ui/Skeleton.kt), which has the one size, stroke and colour set")
    }

    @Test
    fun pullToRefreshIsTheThemedOne() {
        val bad = findings { f, src ->
            src.lines().mapIndexedNotNull { i, l ->
                if (f.name != "Skeleton.kt" && "material3.pulltorefresh.PullToRefreshBox" in l) "${f.name}:${i + 1}" else null
            }
        }
        assertEquals(emptyList(), bad, "Material's PullToRefreshBox draws its indicator in Material colours: use the app's PullToRefreshBox (ui/Skeleton.kt)")
    }

    @Test
    fun screensReadEcencyColorsNotTheMaterialScheme() {
        val bad = findings { f, src ->
            if (f.name == "Theme.kt") return@findings emptyList()
            src.lines().mapIndexedNotNull { i, l -> if ("colorScheme." in l && !l.trimStart().startsWith("//")) "${f.name}:${i + 1}" else null }
        }
        assertEquals(emptyList(), bad, "MaterialTheme.colorScheme outside ui/Theme.kt: use LocalEcencyColors")
    }

    @Test
    fun scaffoldsTakeTheThemeBackground() {
        val bad = findings { f, src ->
            calls(src, "Scaffold").filter { (_, args) -> argument(args, "containerColor")?.let { themeBackground.matches(it) } != true }
                .map { (l, _) -> "${f.name}:$l" }
        }
        assertEquals(emptyList(), bad, "a Scaffold names containerColor = colors.background")
    }

    @Test
    fun sheetsTakeTheThemeBackground() {
        val bad = findings { f, src ->
            // ui/Windows.kt is the wrapper every sheet goes through: its default is the theme background
            if (f.name == "Windows.kt") return@findings emptyList()
            val lines = src.lines()
            calls(src, "ModalBottomSheet").filter { (l, args) ->
                val color = argument(args, "containerColor") ?: return@filter false
                // the sheet's call and its first lines of content (where its tag is)
                !themeBackground.matches(color) && !isAllowed(f, lines.subList(l - 1, minOf(lines.size, l + 6)).joinToString("\n"))
            }.map { (l, _) -> "${f.name}:$l" }
        }
        assertEquals(emptyList(), bad, "a sheet keeps the theme background (or is added to the allowlist with its reason)")
    }

    @Test
    fun fullScreenContainersPaintTheThemeBackground() {
        val paint = Regex("""fillMaxSize\(\)((?:\.\w+\([^()]*(?:\([^()]*\))*[^()]*\))*?)\.background\(([^()]*(?:\([^()]*\))*[^()]*)\)""")
        val bad = findings { f, src ->
            paint.findAll(src).filter { m ->
                !isImportOrComment(src, m.range.first) && !themeBackground.matches(m.groupValues[2].trim()) &&
                    !isAllowed(f, lineText(src, m.range.first))
            }.map { "${f.name}:${lineAt(src, it.range.first)} ${it.groupValues[2].trim()}" }.toList()
        }
        assertEquals(emptyList(), bad, "a full screen container paints the theme background (or is added to the allowlist with its reason)")
    }

    @Test
    fun platformPickersTakeTheAppTheme() {
        val bad = findings { f, src ->
            (calls(src, "DatePickerDialog") + calls(src, "TimePickerDialog")).filter { (_, args) -> "R.style." !in args && "pickerTheme(" !in args }
                .map { (l, _) -> "${f.name}:$l" }
        }
        assertEquals(emptyList(), bad, "a platform picker without a theme takes the window's (always dark): pass the app's picker theme")
    }
}

package com.ecency.l10n.gen

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Apple's privacy manifest (README "Privacy manifest"): App Review refuses a build whose code calls a "required
 * reason" API that its bundle's `PrivacyInfo.xcprivacy` does not declare. This lint reads each iOS bundle's sources
 * (the app's include the Kotlin core, linked in statically as `EcencyCore`) and fails when one uses such an API and its
 * manifest does not list the category with the reasons below, or when a manifest lists a category no code uses (a
 * stale declaration claims a use Apple's privacy report would then show). Sentry and Firebase ship their own manifests.
 */
class PrivacyManifestTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { it.resolve("src/config/locales/en-US.json").isFile }
    private val ios = root.resolve("iosApp")

    /** A required reason API category: what in the code uses it, and the reasons this app gives for it. */
    private class Category(val key: String, val uses: Regex, val reasons: (String) -> Set<String>)

    private val categories = listOf(
        // CA92.1: the app's own settings; 1C8F.1: the App Group suite the app shares with the widgets
        Category("NSPrivacyAccessedAPICategoryUserDefaults", Regex("""\bUserDefaults\b|\bNSUserDefaults\b|@AppStorage\b""")) { code ->
            setOf("CA92.1") + if (Regex("""UserDefaults\(suiteName:\s*(\w+\.group\b|"group\.)""").containsMatchIn(code)) setOf("1C8F.1") else emptySet()
        },
        // C617.1: dates of files in the app's own container and App Group (shared items, dictation, uploads)
        Category(
            "NSPrivacyAccessedAPICategoryFileTimestamp",
            Regex("""\.creationDate(Key)?\b|[cC]ontentModificationDate(Key)?\b|\b(file)?[mM]odificationDate\b|\bNSFile(Creation|Modification)Date\b|\battributesOfItem\b|\b(fstat|lstat|fstatat)\(|\bposix\.(f|l)?stat\b|\b(f)?getattrlist(bulk|at)?\b"""),
        ) { setOf("C617.1") },
        // 35F9.1: time between two events in the app (a tap's length)
        Category("NSPrivacyAccessedAPICategorySystemBootTime", Regex("""\bsystemUptime\b|\bmach_absolute_time\b""")) { setOf("35F9.1") },
        Category("NSPrivacyAccessedAPICategoryDiskSpace", Regex("""volume(Available|Total)Capacity|\bNSFileSystem(Free)?Size\b|\bsystem(Free)?Size\b|\bf?statv?fs\b""")) { emptySet() },
        Category("NSPrivacyAccessedAPICategoryActiveKeyboards", Regex("""\bactiveInputModes\b""")) { emptySet() },
    )

    /**
     * Categories a bundle uses through libraries whose source is not here: the app links the Kotlin/Native runtime and
     * kotlinx-io (through ktor), which call `stat`, `fstat`, `lstat` and `getattrlist` (seen with `nm -u` on the app
     * binary), so the app needs FileTimestamp even with no Swift or Kotlin file date read of its own.
     */
    private val linked = mapOf("EcencyApp" to setOf("NSPrivacyAccessedAPICategoryFileTimestamp"))

    /** Each bundle: where its manifest lives and the code linked into it. */
    private val bundles = mapOf(
        "EcencyApp" to listOf("iosApp/EcencyApp", "shared/src/commonMain", "shared/src/appleMain", "shared/src/iosMain"),
        "ShareExtension" to listOf("iosApp/ShareExtension"),
        "Widgets" to listOf("iosApp/Widgets", "iosApp/EcencyApp/App/ContrastPalette.swift"),
        "NotificationService" to listOf("iosApp/NotificationService", "iosApp/EcencyApp/App/RichPush.swift"),
    )

    private fun code(paths: List<String>): String = paths.map(root::resolve).flatMap { f ->
        if (f.isFile) listOf(f) else f.walkTopDown().filter { it.isFile && it.extension in setOf("swift", "kt") }.toList()
    }.joinToString("\n") { it.readText() }

    /** category key to its reasons, read from the manifest's plist XML (no plist library on the Linux CI). */
    private fun declared(manifest: File): Map<String, Set<String>> {
        val xml = manifest.readText().replace(Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL), "")
        // the entries are flat dicts (type and reasons) after the key; the list ends where the outer dict does
        val types = xml.substringAfter("<key>NSPrivacyAccessedAPITypes</key>", "")
        return Regex("""<dict>(.*?)</dict>""", RegexOption.DOT_MATCHES_ALL).findAll(types).associate { m ->
            val entry = m.groupValues[1]
            val key = Regex("""<key>NSPrivacyAccessedAPIType</key>\s*<string>([^<]+)</string>""").find(entry)?.groupValues?.get(1) ?: fail("an entry without a type in $manifest")
            key to Regex("""<string>([0-9A-F]{4}\.\d)</string>""").findAll(entry).map { it.groupValues[1] }.toSet()
        }
    }

    @Test
    fun everyBundleDeclaresTheRequiredReasonApisItsCodeUses() {
        val problems = mutableListOf<String>()
        for ((bundle, paths) in bundles) {
            val code = code(paths)
            val used = categories.filter { it.uses.containsMatchIn(code) || it.key in linked[bundle].orEmpty() }.associate { it.key to it.reasons(code) }
            val manifest = ios.resolve("$bundle/PrivacyInfo.xcprivacy")
            if (!manifest.isFile) {
                if (used.isNotEmpty()) problems += "$bundle uses ${used.keys} but has no PrivacyInfo.xcprivacy"
                continue
            }
            val declared = declared(manifest)
            for ((key, reasons) in used) {
                val have = declared[key]
                when {
                    have == null -> problems += "$bundle uses $key but its manifest does not declare it"
                    reasons.isEmpty() -> problems += "$bundle uses $key: give it a reason here and in the manifest"
                    have != reasons -> problems += "$bundle $key: reasons $have, want $reasons"
                }
            }
            for (key in declared.keys - used.keys) problems += "$bundle declares $key but no code uses it"
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    @Test
    fun theAppDoesNotTrack() {
        val xml = ios.resolve("EcencyApp/PrivacyInfo.xcprivacy").also { assertTrue(it.isFile, "iosApp/EcencyApp/PrivacyInfo.xcprivacy") }.readText()
        assertTrue(Regex("""<key>NSPrivacyTracking</key>\s*<false/>""").containsMatchIn(xml), "NSPrivacyTracking false")
        assertEquals(listOf("<array/>"), Regex("""<key>NSPrivacyTrackingDomains</key>\s*(<array/>|<array>)""").findAll(xml).map { it.groupValues[1] }.toList(), "no tracking domains")
    }
}

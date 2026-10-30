package com.ecency.l10n.gen

import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Writes [L10nGenerator]'s output for the locale files in [localesDir]: Kotlin sources into [outputDir] and the iOS
 * app's `L.swift` into [swiftDir] (both cleared first).
 */
@CacheableTask
abstract class GenerateL10nTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val localesDir: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val pluralRules: RegularFileProperty

    /** CLDR number, date and relative time data (shared/l10n/cldr-formats.json, FormatDataGenerator). */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val formats: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:OutputDirectory
    abstract val swiftDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val locales = localesDir.get().asFile.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty()
            .associate { it.name.removeSuffix(".json") to it.readText(Charsets.UTF_8) }
        val files = L10nGenerator.generate(
            locales,
            pluralRules.get().asFile.readText(Charsets.UTF_8),
            formats.get().asFile.readText(Charsets.UTF_8),
        )
        val dir = outputDir.get().asFile.resolve(L10nGenerator.PACKAGE.replace('.', '/'))
        val swift = swiftDir.get().asFile
        outputDir.get().asFile.deleteRecursively()
        swift.deleteRecursively()
        dir.mkdirs()
        swift.mkdirs()
        files.forEach { (name, text) -> (if (name.endsWith(".swift")) swift else dir).resolve(name).writeText(text, Charsets.UTF_8) }
    }
}

/**
 * Registers `generateL10n` in the applying project: RN's `src/config/locales` (two levels above `native/shared`) and
 * `l10n/cldr-plural-rules.json` and `l10n/cldr-formats.json` in, `build/generated/l10n/kotlin` and
 * `build/generated/l10n/swift` out. The project adds the Kotlin output to its sources; the iOS app's build phase
 * copies `L.swift` into the app (iosApp/project.yml). Also `generateAppleL10n` ([GenerateAppleL10nTask]) into
 * `build/generated/l10n/apple`, run by the iOS build phases.
 */
class L10nPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.tasks.register("generateL10n", GenerateL10nTask::class.java) {
            group = "build"
            description = "Generates the typed localization API and message tables from src/config/locales."
            localesDir.set(project.rootProject.layout.projectDirectory.dir("../src/config/locales"))
            pluralRules.set(project.layout.projectDirectory.file("l10n/cldr-plural-rules.json"))
            formats.set(project.layout.projectDirectory.file("l10n/cldr-formats.json"))
            outputDir.set(project.layout.buildDirectory.dir("generated/l10n/kotlin"))
            swiftDir.set(project.layout.buildDirectory.dir("generated/l10n/swift"))
        }
        project.tasks.register("generateAppleL10n", GenerateAppleL10nTask::class.java) {
            group = "build"
            description = "Generates the iOS InfoPlist.strings files and the widget extension's messages from src/config/locales."
            localesDir.set(project.rootProject.layout.projectDirectory.dir("../src/config/locales"))
            systemStrings.set(project.layout.projectDirectory.file("l10n/system-strings.json"))
            pluralRules.set(project.layout.projectDirectory.file("l10n/cldr-plural-rules.json"))
            formats.set(project.layout.projectDirectory.file("l10n/cldr-formats.json"))
            outputDir.set(project.layout.buildDirectory.dir("generated/l10n/apple"))
        }
    }
}

/**
 * Writes Android's generated resources ([SystemStrings.android]) for the locale files in [localesDir]: the per-app
 * language list `xml/locales_config.xml` and each language's `strings.xml` with the OS-read strings of [systemStrings].
 */
@CacheableTask
abstract class GenerateAndroidL10nTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val localesDir: DirectoryProperty

    /** shared/l10n/system-strings.json: which key each OS-read string shows. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val systemStrings: RegularFileProperty

    /** A resource directory (cleared first). */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val files = SystemStrings.android(readLocales(localesDir.get().asFile), systemStrings.get().asFile.readText(Charsets.UTF_8))
        writeAll(outputDir.get().asFile, files)
    }
}

/**
 * Writes iOS's generated files ([SystemStrings.apple]): `InfoPlist/<tag>.lproj/InfoPlist.strings` (the app's permission
 * prompts; the app's build phase copies them into the bundle) and `WidgetL10nData.swift` (the widget extension's
 * messages; its build phase copies it to iosApp/Generated).
 */
@CacheableTask
abstract class GenerateAppleL10nTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val localesDir: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val systemStrings: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val pluralRules: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val formats: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val files = SystemStrings.apple(
            readLocales(localesDir.get().asFile),
            systemStrings.get().asFile.readText(Charsets.UTF_8),
            pluralRules.get().asFile.readText(Charsets.UTF_8),
            formats.get().asFile.readText(Charsets.UTF_8),
        )
        writeAll(outputDir.get().asFile, files)
    }
}

internal fun readLocales(dir: java.io.File): Map<String, String> =
    dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty().associate { it.name.removeSuffix(".json") to it.readText(Charsets.UTF_8) }

/** Clears [root] and writes [files] (relative path to text) under it. */
internal fun writeAll(root: java.io.File, files: Map<String, String>) {
    root.deleteRecursively()
    for ((path, text) in files) root.resolve(path).apply { parentFile.mkdirs() }.writeText(text, Charsets.UTF_8)
}

/**
 * Registers `generateL10nResources` in the Android app: RN's `src/config/locales` and
 * `shared/l10n/system-strings.json` in, a resource directory out ([GenerateAndroidL10nTask]: the manifest's
 * `android:localeConfig`, the widget labels and descriptions in every language). The app adds the output to its
 * variants' resources.
 */
class LocaleConfigPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.tasks.register("generateL10nResources", GenerateAndroidL10nTask::class.java) {
            group = "build"
            description = "Generates res/xml/locales_config.xml and the OS-read strings.xml files from src/config/locales."
            localesDir.set(project.rootProject.layout.projectDirectory.dir("../src/config/locales"))
            systemStrings.set(project.rootProject.layout.projectDirectory.file("shared/l10n/system-strings.json"))
            outputDir.set(project.layout.buildDirectory.dir("generated/l10n/res"))
        }
    }
}

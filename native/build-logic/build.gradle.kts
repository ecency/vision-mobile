// Build time tools for the native app. Today: the localization generator (README "Localization").
plugins {
    `kotlin-dsl`
}

gradlePlugin {
    plugins {
        create("l10n") {
            id = "ecency.l10n"
            implementationClass = "com.ecency.l10n.gen.L10nPlugin"
        }
        create("localeConfig") {
            id = "ecency.l10n.android"
            implementationClass = "com.ecency.l10n.gen.LocaleConfigPlugin"
        }
    }
}

dependencies {
    testImplementation(kotlin("test-junit"))
}

// The tests scan the app sources and the locale files (RnReuseTest, CachedCopyTest, UiLiteralLintTest, SwiftCopyLintTest, ObjcNamesTest,
// SystemStringsTest...): declared as inputs, so an edit there reruns them instead of leaving the task up to date.
tasks.test {
    val native = layout.projectDirectory.dir("..")
    inputs.dir(native.dir("androidApp/src/main")).withPropertyName("androidSources").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(native.file("androidApp/l10n-literals-allowed.txt")).withPropertyName("literalAllowlist").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(native.dir("shared/src")).withPropertyName("sharedSources").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(native.dir("shared/l10n")).withPropertyName("sharedL10n").withPathSensitivity(PathSensitivity.RELATIVE)
    // the Swift sources SwiftCopyLintTest and RnReuseTest read (Generated/ is build output, left out)
    for (d in listOf("EcencyApp", "Widgets", "ShareExtension", "NotificationService")) {
        inputs.dir(native.dir("iosApp/$d")).withPropertyName("ios$d").withPathSensitivity(PathSensitivity.RELATIVE)
    }
    inputs.file(native.file("iosApp/l10n-literals-allowed.txt")).withPropertyName("iosLiteralAllowlist").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(native.file("iosApp/project.yml")).withPropertyName("iosProject").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(native.dir("../src/config/locales")).withPropertyName("locales").withPathSensitivity(PathSensitivity.RELATIVE)
}

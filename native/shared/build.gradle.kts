plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    id("ecency.l10n")
}

kotlin {
    androidLibrary {
        namespace = "com.ecency.core"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = libs.versions.minSdk.get().toInt()
        withHostTestBuilder {}
    }

    listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "EcencyCore"
            isStatic = true
        }
        // os_log / os_signpost for PerfMarks (the os_log macros are not callable from Kotlin directly)
        target.compilations.getByName("main").cinterops.create("perf") {
            definitionFile.set(project.file("src/nativeInterop/cinterop/perf.def"))
        }
    }

    sourceSets {
        // The typed copy API and message tables generated from src/config/locales (README "Localization")
        commonMain {
            kotlin.srcDir(tasks.named<com.ecency.l10n.gen.GenerateL10nTask>("generateL10n").flatMap { it.outputDir })
        }
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.secp256k1.kmp)
            implementation(libs.kotlincrypto.sha2)
            implementation(libs.kotlincrypto.md)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
        }
        androidMain.dependencies {
            implementation(libs.secp256k1.jni.android)
            implementation(libs.ktor.client.okhttp)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.secp256k1.jni.jvm)
        }
        getByName("androidHostTest").dependencies {
            // test only oracle: CLDR plural rules and ICU MessageFormat to check the generated rules and the formatter
            implementation(libs.icu4j)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
    }
}

// The generator's own unit tests run with the shared module's checks
tasks.named("check") { dependsOn(gradle.includedBuild("build-logic").task(":test")) }

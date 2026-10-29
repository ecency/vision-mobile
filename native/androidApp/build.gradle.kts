import java.security.MessageDigest
import com.android.build.api.variant.BuildConfigField

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    // res/xml/locales_config.xml from src/config/locales (README "Localization", app language)
    id("ecency.l10n.android")
}

composeCompiler {
    stabilityConfigurationFiles.add(layout.projectDirectory.file("compose-stability.conf"))
}

// RN build secrets, needed once to import 3.x accounts: PIN_KEY decrypts the stored app PIN, DEFAULT_PIN is
// the PIN the RN app encrypts fresh logins with. Never commit them. Each comes from the environment
// (CI: ECENCY_PIN_KEY, ECENCY_DEFAULT_PIN) or from the untracked native/local.properties (ecency.pinKey,
// ecency.defaultPin), read as raw text after the first '=' (no escapes), exactly like iosApp/scripts/build-secrets.sh.
// Non-debuggable builds also need the SHA-256 of each value (ECENCY_PIN_KEY_SHA256 / ecency.pinKeySha256,
// and the same for DEFAULT_PIN) and fail packaging when a value is missing or does not match, so a
// truncated or wrong secret can never ship. Debuggable builds fall back to dummies.
val localProperties: Provider<Map<String, String>> =
    providers.fileContents(rootProject.layout.projectDirectory.file("local.properties")).asText.map { text ->
        text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") && "=" in it }
            .associate { it.substringBefore("=").trim() to it.substringAfter("=").trim() }
    }.orElse(emptyMap())

fun secret(env: String, property: String): Provider<String> = providers.environmentVariable(env)
    .orElse(localProperties.map { it[property].orEmpty() })
    .map { it.trim() }
    .filter { it.isNotEmpty() }

// Play upload key for release builds: ECENCY_UPLOAD_KEYSTORE (path to the .jks), ECENCY_UPLOAD_KEYSTORE_PASSWORD,
// ECENCY_UPLOAD_KEY_ALIAS, ECENCY_UPLOAD_KEY_PASSWORD from the environment, or ecency.uploadKeystore,
// ecency.uploadKeystorePassword, ecency.uploadKeyAlias, ecency.uploadKeyPassword in native/local.properties.
// Never commit the keystore or its passwords. With none of them set the release build stays unsigned.
data class UploadSigning(val storeFile: String, val storePassword: String, val keyAlias: String, val keyPassword: String)

val uploadSigningParts = listOf(
    secret("ECENCY_UPLOAD_KEYSTORE", "ecency.uploadKeystore"),
    secret("ECENCY_UPLOAD_KEYSTORE_PASSWORD", "ecency.uploadKeystorePassword"),
    secret("ECENCY_UPLOAD_KEY_ALIAS", "ecency.uploadKeyAlias"),
    secret("ECENCY_UPLOAD_KEY_PASSWORD", "ecency.uploadKeyPassword"),
).map { it.orNull }

val uploadSigning: UploadSigning? = uploadSigningParts.let { (store, storePass, alias, keyPass) ->
    if (store != null && storePass != null && alias != null && keyPass != null) {
        UploadSigning(store, storePass, alias, keyPass)
    } else {
        null
    }
}

// A partial upload key is always an error. CI also sets ECENCY_REQUIRE_SIGNING=true, so a missing key fails
// the build instead of producing an unsigned bundle that Play rejects later.
if (uploadSigning == null &&
    (uploadSigningParts.any { it != null } || providers.environmentVariable("ECENCY_REQUIRE_SIGNING").orNull == "true")
) {
    throw GradleException(
        "The release upload key is incomplete: set all of ECENCY_UPLOAD_KEYSTORE, ECENCY_UPLOAD_KEYSTORE_PASSWORD, " +
            "ECENCY_UPLOAD_KEY_ALIAS and ECENCY_UPLOAD_KEY_PASSWORD (or the ecency.upload* keys in " +
            "native/local.properties). See native/docs/release-builds.md.",
    )
}
if (uploadSigning != null && !file(uploadSigning.storeFile).isFile) {
    throw GradleException("ECENCY_UPLOAD_KEYSTORE (ecency.uploadKeystore) does not point to a file.")
}

// Native 4.x ships as an update of the RN app (README "In-place upgrade from RN 3.x"): the same application id, so
// the RN app's data, App Links, notification permission and Play listing carry over.
val APPLICATION_ID = "app.esteem.mobile.android"

// Store build number: ECENCY_BUILD_NUMBER or ecency.buildNumber, 0 when unset. CI passes the Unix time in
// seconds, exactly like the RN store workflows did (native/docs/release-builds.md, "Versions and build numbers").
val buildNumber: Int = secret("ECENCY_BUILD_NUMBER", "ecency.buildNumber").orNull.let { raw ->
    val n = raw?.toIntOrNull() ?: if (raw == null) 0 else throw GradleException("ECENCY_BUILD_NUMBER must be an integer.")
    // Play caps versionCode at 2_100_000_000.
    if (n !in 0..2_100_000_000) throw GradleException("ECENCY_BUILD_NUMBER must be between 0 and 2100000000.")
    n
}

// Firebase (push). The RN app's google-services.json (Firebase app app.esteem.mobile.android; CI decodes the existing
// GOOGLE_JSON secret) goes next to this file, untracked, never committed. The same Firebase app as RN keeps the
// installation id and the push token of updated users. Without it the app builds and runs with push inactive.
// With it, the values the google-services plugin would generate are written as string resources here
// (FirebaseInitProvider reads them at start), so no extra Gradle plugin is needed.
val firebaseResources: Map<String, String> = providers.fileContents(layout.projectDirectory.file("google-services.json")).asText.orNull
    ?.let { text ->
        @Suppress("UNCHECKED_CAST")
        val json = groovy.json.JsonSlurper().parseText(text) as Map<String, Any?>
        val project = json["project_info"] as? Map<String, Any?> ?: emptyMap()
        val client = (json["client"] as? List<Map<String, Any?>>).orEmpty().firstOrNull { c ->
            ((c["client_info"] as? Map<String, Any?>)?.get("android_client_info") as? Map<String, Any?>)?.get("package_name") == APPLICATION_ID
        } ?: throw GradleException("androidApp/google-services.json has no client for $APPLICATION_ID: use the RN app's file.")
        val apiKey = ((client["api_key"] as? List<Map<String, Any?>>).orEmpty().firstOrNull()?.get("current_key") as? String).orEmpty()
        listOf(
            "google_app_id" to (client["client_info"] as? Map<String, Any?>)?.get("mobilesdk_app_id") as? String,
            "gcm_defaultSenderId" to project["project_number"] as? String,
            "project_id" to project["project_id"] as? String,
            "google_storage_bucket" to project["storage_bucket"] as? String,
            "google_api_key" to apiKey,
        ).mapNotNull { (name, value) -> value?.takeIf { it.isNotEmpty() }?.let { name to it } }.toMap().also {
            if ("google_app_id" !in it || "google_api_key" !in it || "project_id" !in it) {
                throw GradleException("androidApp/google-services.json is incomplete (app id, API key or project id missing).")
            }
        }
    } ?: emptyMap()

/**
 * RN's scheme (RN android/app/build.gradle): major * 10000 + minor * 100 + patch, 40000 for 4.0.0 (RN 3.5.11 is
 * 30511). Store builds use the build number instead (CI: the Unix time, as the RN workflows did, so it is above
 * every RN store build); it may never be below this value.
 */
fun rnVersionCode(versionName: String): Int {
    val parts = versionName.split(".").map { it.toIntOrNull() }
    if (parts.size != 3 || parts.any { it == null || it !in 0..99 }) throw GradleException("appVersion must be X.Y.Z with parts 0..99, got $versionName.")
    val (major, minor, patch) = parts.map { it!! }
    return major * 10000 + minor * 100 + patch
}

val appVersionCode: Int = rnVersionCode(libs.versions.appVersion.get()).let { base ->
    if (buildNumber != 0 && buildNumber < base) throw GradleException("ECENCY_BUILD_NUMBER ($buildNumber) is below the version's own code $base.")
    if (buildNumber != 0) buildNumber else base
}

android {
    namespace = "com.ecency.app"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        // The RN store app's id; Kotlin packages and the namespace stay com.ecency.*.
        applicationId = APPLICATION_ID
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.compileSdk.get().toInt()
        // Above every RN build (native/docs/release-builds.md): the build number in CI, RN's formula locally.
        versionCode = appVersionCode
        versionName = libs.versions.appVersion.get()
        buildConfigField("int", "BUILD_NUMBER", buildNumber.toString())
        buildConfigField("int", "RN_VERSION_CODE", rnVersionCode(libs.versions.appVersion.get()).toString())
        // How this build names itself to Ecency's APIs: ECENCY_USER_AGENT or ecency.userAgent (native/local.properties).
        // Empty when unset: the shared core then uses its generic default.
        buildConfigField(
            "String", "USER_AGENT",
            "\"" + secret("ECENCY_USER_AGENT", "ecency.userAgent").getOrElse("").replace("\\", "\\\\").replace("\"", "\\\"") + "\"",
        )
        // RN ACTIVITY_WEBSOCKET_URL (the notification websocket used where push is unavailable): from the environment
        // or ecency.activityWebsocketUrl in native/local.properties. Empty when unset: no websocket.
        buildConfigField(
            "String", "ACTIVITY_WEBSOCKET_URL",
            "\"" + secret("ECENCY_ACTIVITY_WEBSOCKET_URL", "ecency.activityWebsocketUrl").getOrElse("").replace("\\", "\\\\").replace("\"", "\\\"") + "\"",
        )
        // RN ECENCY_BACKEND_API for chats (the chat API and its websocket, RN chatApi.ts / mattermostWebSocket.ts): from
        // ECENCY_CHAT_BACKEND_URL or ecency.chatBackendUrl. Empty: the Ecency host. Only a first-party https host is used.
        buildConfigField(
            "String", "CHAT_BACKEND_URL",
            "\"" + secret("ECENCY_CHAT_BACKEND_URL", "ecency.chatBackendUrl").getOrElse("").replace("\\", "\\\\").replace("\"", "\\\"") + "\"",
        )
        // Where the update check looks (README "App shell: update prompt"): the GitHub "latest release" endpoint and
        // the prefix of this app's release tags. From the environment or native/local.properties; empty: RN's
        // repository and plain X.Y.Z tags.
        buildConfigField(
            "String", "UPDATE_RELEASES_URL",
            "\"" + secret("ECENCY_UPDATE_RELEASES_URL", "ecency.updateReleasesUrl").getOrElse("").replace("\\", "\\\\").replace("\"", "\\\"") + "\"",
        )
        buildConfigField(
            "String", "UPDATE_TAG_PREFIX",
            "\"" + secret("ECENCY_UPDATE_TAG_PREFIX", "ecency.updateTagPrefix").getOrElse("").replace("\\", "\\\\").replace("\"", "\\\"") + "\"",
        )
        // Sentry (README "Crash reporting"): ECENCY_SENTRY_DSN or ecency.sentryDsn (CI secret NATIVE_SENTRY_DSN, optional).
        // Empty: RN's project in release builds, nothing in debug builds; "off" turns it off (shared CrashDsn).
        buildConfigField(
            "String", "SENTRY_DSN",
            "\"" + secret("ECENCY_SENTRY_DSN", "ecency.sentryDsn").getOrElse("").replace("\\", "\\\\").replace("\"", "\\\"") + "\"",
        )
        // The R8 mapping's id (docs/release-builds.md, "Crash symbols"): ECENCY_PROGUARD_UUID or ecency.proguardUuid. CI
        // makes a fresh one per store build and uploads mapping.txt to Sentry under it; empty in local builds.
        buildConfigField(
            "String", "PROGUARD_UUID",
            "\"" + secret("ECENCY_PROGUARD_UUID", "ecency.proguardUuid").getOrElse("").also { id ->
                if (id.isNotEmpty() && !Regex("^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$").matches(id)) {
                    throw GradleException("ECENCY_PROGUARD_UUID must be a UUID.")
                }
            } + "\"",
        )
        // Plausible (README "Analytics"): ECENCY_PLAUSIBLE_HOST or ecency.plausibleHost; empty is RN's pl.ecency.com in release
        // builds and off in debug builds, "off" turns it off everywhere.
        buildConfigField(
            "String", "PLAUSIBLE_HOST",
            "\"" + secret("ECENCY_PLAUSIBLE_HOST", "ecency.plausibleHost").getOrElse("").replace("\\", "\\\\").replace("\"", "\\\"") + "\"",
        )
        firebaseResources.forEach { (name, value) -> resValue("string", name, value) }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (uploadSigning != null) {
            create("upload") {
                storeFile = file(uploadSigning.storeFile)
                storePassword = uploadSigning.storePassword
                keyAlias = uploadSigning.keyAlias
                keyPassword = uploadSigning.keyPassword
            }
        }
    }

    buildTypes {
        release {
            // R8 is release-only, so release-only crashes come from here: keep proguard-rules.pro in step
            // with new reflection or JNI users; CI launches the minified build before any upload (docs/release-builds.md,
            // "Release launch check").
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Unsigned when no upload key is configured (local builds); CI sets ECENCY_REQUIRE_SIGNING.
            signingConfig = signingConfigs.findByName("upload")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}


data class BuildSecret(val field: String, val value: Provider<String>, val sha256: Provider<String>, val dummy: String)

val buildSecrets = listOf(
    BuildSecret("PIN_KEY", secret("ECENCY_PIN_KEY", "ecency.pinKey"), secret("ECENCY_PIN_KEY_SHA256", "ecency.pinKeySha256"), "debug-dummy-pin-key"),
    BuildSecret("DEFAULT_PIN", secret("ECENCY_DEFAULT_PIN", "ecency.defaultPin"), secret("ECENCY_DEFAULT_PIN_SHA256", "ecency.defaultPinSha256"), "0000"),
)

androidComponents {
    onVariants { variant ->
        variant.sources.res?.addGeneratedSourceDirectory(
            tasks.named<com.ecency.l10n.gen.GenerateAndroidL10nTask>("generateL10nResources"),
            com.ecency.l10n.gen.GenerateAndroidL10nTask::outputDir,
        )
        val debuggable = android.buildTypes.getByName(variant.buildType!!).isDebuggable
        for (s in buildSecrets) {
            val value = if (debuggable) s.value.orElse(s.dummy) else s.value.orElse("")
            variant.buildConfigFields?.put(
                s.field,
                value.map { v ->
                    val literal = v.replace("\\", "\\\\").replace("\"", "\\\"")
                    BuildConfigField("String", "\"$literal\"", "RN app ${s.field}, only for the one-time 3.x account import")
                },
            )
        }
        if (!debuggable) {
            val variantName = variant.name
            val name = variantName.replaceFirstChar { it.uppercase() }
            val check = tasks.register("check${name}BuildSecrets") {
                description = "Fails $variantName packaging unless every RN build secret is set and matches its SHA-256."
                val expected = buildSecrets.map { s -> Triple(s.field, s.value.orElse(""), s.sha256.orElse("")) }
                doLast {
                    for ((field, valueProvider, shaProvider) in expected) {
                        val v = valueProvider.get()
                        val sha = shaProvider.get().lowercase()
                        if (v.isEmpty() || sha.isEmpty()) {
                            throw GradleException(
                                "$field or its SHA-256 is not set for this $variantName build. Set ECENCY_$field and " +
                                    "ECENCY_${field}_SHA256 or the matching ecency.* keys in native/local.properties " +
                                    "(untracked). See native/README.md, Secure storage.",
                            )
                        }
                        val actual = MessageDigest.getInstance("SHA-256").digest(v.toByteArray())
                            .joinToString("") { "%02x".format(it) }
                        if (actual != sha) {
                            throw GradleException("$field does not match its SHA-256 (truncated or wrong value?).")
                        }
                    }
                }
            }
            tasks.matching { it.name == "package$name" || it.name == "bundle$name" }.configureEach { dependsOn(check) }
            // The real values end up in this variant's BuildConfig, class files, dex and bundle. Keep every task
            // of the variant out of the Gradle build cache so they never land in a cache directory that CI
            // might save (native/docs/release-builds.md, Caches and artifacts).
            tasks.matching { it.name.contains(name) }.configureEach {
                outputs.doNotCacheIf("$variantName outputs contain the RN build secrets") { true }
            }
        }
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.coil.gif)
    // Shorts reels: HLS in the platform player (Java libraries, no Kotlin metadata to match)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ui)
    // Listen: background playback with media notification and lock-screen controls (ListenPlaybackService)
    implementation(libs.media3.session)
    implementation(libs.glance.appwidget)
    // pins Glance's WorkManager to a version that survives R8 (libs.versions.toml "work")
    implementation(libs.work.runtime)
    // Listen: the app's foreground and background for the status polling (ProcessLifecycleOwner)
    implementation(libs.lifecycle.process)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.biometric)
    implementation(libs.zxing.core)
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.firebase.messaging)
    implementation(libs.play.billing)
    implementation(libs.sentry.android.core)
    implementation(libs.sentry.android.ndk)
    // the store rating prompt (Java library, no Kotlin metadata to match; see libs.versions.toml)
    implementation(libs.play.review)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.serialization.json)
    // signing in host tests (the app itself uses the Android JNI build)
    testImplementation(libs.secp256k1.jni.jvm)
    testImplementation(libs.androidx.test.core)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}

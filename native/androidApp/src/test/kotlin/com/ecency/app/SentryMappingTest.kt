package com.ecency.app

import com.ecency.app.crash.SentryReporting
import io.sentry.android.core.SentryAndroidOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Release crash reports are R8-obfuscated. CI names each build's mapping with a fresh id (ECENCY_PROGUARD_UUID),
 * uploads mapping.txt to Sentry under it and compiles the same id into the app, so Sentry can map the stack
 * traces back (docs/release-builds.md, "Crash symbols").
 */
class SentryMappingTest {
    private val id = "3f2c9d1e-8a4b-4c6d-9e0f-1a2b3c4d5e6f"

    private fun configured(mapping: String) = SentryAndroidOptions().also {
        SentryReporting.configure(it, dsn = "https://abc@example.ingest.sentry.io/1", applicationId = "app.esteem.mobile.android", version = "4.0.0", build = 57, debug = false, mappingId = mapping)
    }

    @Test
    fun eventsCarryTheMappingIdCiUploadedTheMappingUnder() {
        assertEquals(id, configured(id).proguardUuid)
        // uuidgen prints upper case; Sentry stores the id in lower case
        assertEquals(id, configured(id.uppercase()).proguardUuid)
    }

    @Test
    fun localBuildsAndMalformedIdsSendNoMappingId() {
        assertNull(configured("").proguardUuid)
        assertNull(configured("not-a-uuid").proguardUuid)
        assertNull(configured("$id\nx").proguardUuid)
    }

    @Test
    fun configureKeepsRnsReleaseNamingAndPrivacy() {
        val o = configured(id)
        assertEquals("app.esteem.mobile.android@4.0.0+57", o.release)
        assertEquals("57", o.dist)
        assertEquals("production", o.environment)
        assertEquals(false, o.isSendDefaultPii)
        assertNull(o.tracesSampleRate)
    }

    @Test
    fun theBuildExposesItsMappingId() {
        // empty in local and debug builds; CI sets it for store builds (build.gradle.kts rejects a malformed one)
        assertEquals("", BuildConfig.PROGUARD_UUID)
    }
}

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
        // issue #3571: performance data at a low sample rate
        assertEquals(0.05, o.tracesSampleRate!!, 0.0)
    }

    @Test
    fun aTransactionKeepsNoUrlPathQueryOrSecret() {
        val wif = "5JdeC9P7Pbd1uGdFVEsJ41EkEnADbbHGq6p1BwFxm6txNBsQnsw" // Bitcoin wiki example key, controls nothing
        fun span(op: String, description: String) = io.sentry.protocol.SentrySpan(
            1.0, 2.0, io.sentry.protocol.SentryId(), io.sentry.SpanId(), null, op, description,
            io.sentry.SpanStatus.OK, "auto", mutableMapOf(), mutableMapOf(), mutableMapOf(),
        )
        val tx = io.sentry.protocol.SentryTransaction(
            "MainActivity", 1.0, 2.0,
            mutableListOf(
                span("ui.load", "MainActivity"),
                span("http.client", "GET https://images.example/upload/tester/signature"),
                span("file.read", "/data/user/0/app/files/tester.json"),
                span("db.sql.query", "select * from accounts"),
                span("ui.load.initial_display", "key $wif"),
            ),
            mutableMapOf(), io.sentry.protocol.TransactionInfo("component"),
        ).apply { user = io.sentry.protocol.User().apply { username = "tester" } }
        val out = configured(id).beforeSendTransaction!!.execute(tx, io.sentry.Hint())!!
        assertEquals(listOf("ui.load", "ui.load.initial_display"), out.spans.map { it.op })
        assertEquals(false, out.spans.any { wif in it.description.orEmpty() })
        assertNull(out.user)
    }

    @Test
    fun theBuildExposesItsMappingId() {
        // empty in local and debug builds; CI sets it for store builds (build.gradle.kts rejects a malformed one)
        assertEquals("", BuildConfig.PROGUARD_UUID)
    }
}

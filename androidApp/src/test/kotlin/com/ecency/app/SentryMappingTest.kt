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
    fun aHandledReportIsAnEventWithItsFingerprintTagsAndAScrubbedContext() {
        // Bitcoin wiki example key, controls nothing
        val wif = "5JdeC9P7Pbd1uGdFVEsJ41EkEnADbbHGq6p1BwFxm6txNBsQnsw"
        val report = com.ecency.core.app.CrashReport(
            type = "Rejected",
            message = "refused for key $wif",
            tags = mapOf("handled" to "true", "where" to "editor.publish", "error.kind" to "rejected", "exception" to "Rejected"),
            fingerprint = listOf("handled", "editor.publish", "rejected", "Rejected", "refused for key $wif"),
            contextName = "handled",
            context = mapOf("where" to "editor.publish", "stack" to "Rejected: refused for key $wif\n\tat Editor.submit", "memo" to "secret"),
            level = "warning",
        )
        val event = SentryReporting.Reporter.event(report)
        val out = configured(id).beforeSend!!.execute(event, io.sentry.Hint())!!
        assertEquals(io.sentry.SentryLevel.WARNING, out.level)
        assertEquals("editor.publish", out.getTag("where"))
        assertEquals("true", out.getTag("handled"))
        assertEquals(listOf("handled", "editor.publish", "rejected", "Rejected"), out.fingerprints!!.take(4))
        assertEquals(false, out.fingerprints!!.any { wif in it })
        assertEquals(false, out.exceptions!!.single().value!!.contains(wif))
        @Suppress("UNCHECKED_CAST")
        val context = out.contexts["handled"] as Map<String, Any?>
        assertEquals(false, (context["stack"] as String).contains(wif))
        assertEquals(true, (context["stack"] as String).contains("Editor.submit"))
        assertEquals("<redacted>", context["memo"])
    }

    @Test
    fun aMasterPasswordAVaultFieldAndATokenAfterItsNameNeverLeave() {
        val password = "P5" + "K".repeat(50)
        val report = com.ecency.core.app.CrashReport(
            type = "Corrupted",
            message = "pass $password then {\"activeKey\":\"abc\"} and token dGhpc0lzQUhpdmVTaWduZXJUb2tlbl9mb3JUZXN0cw",
            contextName = "handled",
            context = mapOf("stack" to "JSON input: {\"accessToken\":\"abcdefghijklmnop"),
        )
        val out = configured(id).beforeSend!!.execute(SentryReporting.Reporter.event(report), io.sentry.Hint())!!
        val value = out.exceptions!!.single().value!!
        assertEquals(false, password in value || "\"abc\"" in value || "dGhpc0lz" in value)
        @Suppress("UNCHECKED_CAST")
        val stack = (out.contexts["handled"] as Map<String, Any?>)["stack"] as String
        assertEquals(false, "abcdefghijklmnop" in stack)
    }

    @Test
    fun theBuildExposesItsMappingId() {
        // empty in local and debug builds; CI sets it for store builds (build.gradle.kts rejects a malformed one)
        assertEquals("", BuildConfig.PROGUARD_UUID)
    }
}

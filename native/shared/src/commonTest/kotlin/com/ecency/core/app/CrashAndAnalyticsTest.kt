package com.ecency.core.app

import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.auth.AuthLink
import com.ecency.core.auth.AuthLinks
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Crash report scrubbing (RN App.tsx beforeSend), Plausible pageviews (RN usePlausibleTracker) and purchase links. */
class CrashAndAnalyticsTest {
    @Test
    fun theScrubberRedactsRnsPatternsAndThisAppsSecrets() {
        val wif = "5JdeC9P7Pbd1uGdFVEsJ41EkEnADbbHGq6p1BwFxm6txNBsQnsw"
        assertEquals("key <redacted-key> leaked", CrashScrubber.scrub("key $wif leaked"))
        assertEquals("Authorization: Bearer <redacted>", CrashScrubber.scrub("Authorization: Bearer abcdefghijklmnop123"))
        assertEquals("token <redacted-token>", CrashScrubber.scrub("token eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4In0.c2lnbmF0dXJl"))
        assertEquals("sig <redacted-hex>", CrashScrubber.scrub("sig " + "ab".repeat(65)))
        assertEquals("https://ecency.com/x?code=<redacted>&a=1", CrashScrubber.scrub("https://ecency.com/x?code=s3cr3t&a=1"))
        assertEquals("""{"memo":"<redacted>","amount":"1.000 HIVE","pin":"<redacted>"}""", CrashScrubber.scrub("""{"memo":"#secret note","amount":"1.000 HIVE","pin":"1234"}"""))
        assertEquals("""{"receipt":"<redacted>"}""", CrashScrubber.scrub("""{"receipt":"GPA.token"}"""))
        assertEquals("mail <redacted-email>", CrashScrubber.scrub("mail someone@example.com"))
        assertEquals("plain text stays", CrashScrubber.scrub("plain text stays"))
        assertNull(CrashScrubber.scrub(null))
        assertEquals(CrashScrubber.MAX_TEXT, CrashScrubber.scrub("x".repeat(100_000))!!.length)
    }

    @Test
    fun breadcrumbDataDropsSecretKeysWhole() {
        val scrubbed = CrashScrubber.scrubMap(mapOf("code" to "abc", "X-MM-Token" to "t", "memo" to "hi", "url" to "https://a.b/?token=xyz", "productId" to "999points"))
        assertEquals(mapOf("code" to "<redacted>", "X-MM-Token" to "<redacted>", "memo" to "<redacted>", "url" to "https://a.b/?token=<redacted>", "productId" to "999points"), scrubbed)
    }

    @Test
    fun rnsBenignErrorsAreDropped() {
        assertTrue(CrashScrubber.shouldDrop("kotlinx.coroutines.JobCancellationException", "Job was cancelled"))
        assertTrue(CrashScrubber.shouldDrop("Error", "auth.invalid_credentials"))
        assertTrue(CrashScrubber.shouldDrop("Error", "Please check your PIN"))
        assertTrue(CrashScrubber.shouldDrop("SigningCancelledError", ""))
        assertTrue(CrashScrubber.shouldDrop("Error", "HiveSigner signing cancelled"))
        assertFalse(CrashScrubber.shouldDrop("IllegalStateException", "boom"))
        assertFalse(CrashScrubber.shouldDrop(null, null))
        assertTrue(CrashScrubber.ignores(kotlinx.coroutines.CancellationException("x")))
    }

    @Test
    fun aPostRecordsOnePageviewWithRnsEvent() = runTest {
        val app = TestApp(this) { req ->
            when (req.method) {
                "bridge.get_post" -> com.ecency.core.api.FakeTransport.result(req, com.ecency.core.api.Fixtures.entry("alice", "root"))
                "bridge.get_discussion" -> com.ecency.core.api.FakeTransport.result(req, """{"alice/root": ${com.ecency.core.api.Fixtures.entry("alice", "root")}}""")
                else -> TestApp.chain(req) ?: HttpResult(500, "")
            }
        }
        val events = mutableListOf<com.ecency.core.auth.AuthHttpRequest>()
        app.authBackend.rest = { r -> if (r.url.endsWith("/api/event")) HttpResult(202, "ok").also { events += r } else null }
        app.env.analytics.config = AnalyticsConfig(AnalyticsConfig.hostOf(""), "android", "Ecency/4.0.0 (Android 15; Pixel) Version/4.0 Chrome/4.0.0 Mobile")
        app.env.appLock.start()
        testScheduler.advanceUntilIdle()
        val post = app.env.postModel("alice", "root")
        post.start()
        testScheduler.advanceUntilIdle()
        post.refresh()
        testScheduler.advanceUntilIdle()
        val e = events.single()
        assertEquals("https://pl.ecency.com/api/event", e.url)
        assertEquals("Ecency/4.0.0 (Android 15; Pixel) Version/4.0 Chrome/4.0.0 Mobile", e.headers["User-Agent"])
        assertEquals(Json.parseToJsonElement("""{"name":"pageview","url":"app://android.ecency.com/hive-100001/@alice/root","domain":"ecency.com","force":true}"""), Json.parseToJsonElement(e.body!!))
        assertFalse(e.body!!.contains("code"))
        post.close()

        // turned off by the build: nothing
        app.env.analytics.config = AnalyticsConfig(AnalyticsConfig.hostOf("off"), "android", "x")
        val again = app.env.postModel("alice", "root")
        again.start()
        testScheduler.advanceUntilIdle()
        assertEquals(1, events.size)
        again.close()
    }

    @Test
    fun nothingIsRecordedWhileTheLockHidesTheApp() = runTest {
        val app = TestApp(this) { req -> TestApp.chain(req) ?: HttpResult(500, "") }
        val events = mutableListOf<com.ecency.core.auth.AuthHttpRequest>()
        app.authBackend.rest = { r -> if (r.url.endsWith("/api/event")) HttpResult(202, "ok").also { events += r } else null }
        app.env.analytics.config = AnalyticsConfig(AnalyticsConfig.DEFAULT_HOST, "ios", "ua")
        // the lock has not decided yet: it hides the app
        app.env.analytics.pageview("/hive-100001/@alice/root")
        testScheduler.advanceUntilIdle()
        assertTrue(events.isEmpty())
        app.env.appLock.start()
        testScheduler.advanceUntilIdle()
        app.env.analytics.pageview("/hive-100001/@alice/root")
        app.env.analytics.pageview("")
        testScheduler.advanceUntilIdle()
        assertEquals(1, events.size)
    }

    @Test
    fun releaseBuildsReportToRnsSentryProjectUnlessTheBuildSaysOtherwise() {
        assertEquals(CrashDsn.DEFAULT, CrashDsn.of(null, debug = false))
        assertEquals(CrashDsn.DEFAULT, CrashDsn.of("  ", debug = false))
        assertTrue(CrashDsn.DEFAULT.endsWith("ingest.de.sentry.io/4509786252116048"))
        assertEquals("", CrashDsn.of("", debug = true))
        assertEquals("", CrashDsn.of("off", debug = false))
        assertEquals("", CrashDsn.of("NONE", debug = false))
        val other = "https://0123456789abcdef0123456789abcdef@o1.ingest.sentry.io/42"
        assertEquals(other, CrashDsn.of(other, debug = true))
        assertEquals("", CrashDsn.of("http://0123456789abcdef0123456789abcdef@o1.ingest.sentry.io/42", debug = false))
        assertEquals("", CrashDsn.of("https://o1.ingest.sentry.io/42", debug = false))
        assertEquals("", CrashDsn.of("garbage", debug = false))
    }

    @Test
    fun theAnalyticsHostSettingOnlyTakesABareHttpsHost() {
        assertEquals("https://pl.ecency.com", AnalyticsConfig.hostOf(null))
        assertEquals("", AnalyticsConfig.hostOf("none"))
        assertEquals("https://stats.example.org", AnalyticsConfig.hostOf("https://stats.example.org/"))
        assertEquals("", AnalyticsConfig.hostOf("http://stats.example.org"))
        assertEquals("", AnalyticsConfig.hostOf("https://user:pw@stats.example.org"))
        assertEquals("", AnalyticsConfig.hostOf("https://stats.example.org/path"))
        assertEquals("Ecency/4.0.0 (iOS 18.2; iPhone) Version/4.0 Chrome/4.0.0 Mobile", PlausibleAnalytics.userAgent("Ecency", "4.0.0", "iOS", "18.2", "iPhone"))
    }

    @Test
    fun purchaseLinksParseAndRouteAsRn() = runTest {
        assertEquals(AuthLink.Purchase("points", "spike-bob", "999points", null, null), AuthLinks.parse("https://ecency.com/purchase?type=points&username=spike-bob&product_id=999points"))
        assertNull(AuthLinks.parse("https://evil.example/purchase?type=points"))
        val app = appWithAccounts(this)
        app.env.session.start()
        app.env.appLock.start()
        testScheduler.advanceUntilIdle()
        val links = app.env.authLinks
        fun open(url: String): LinkNavigation? {
            assertTrue(links.handle(url))
            testScheduler.advanceUntilIdle()
            return links.current.navigation.also { links.consumeNavigation() }
        }
        assertEquals(LinkNavigation.Screen(AppScreen.AccountBoost("spike-bob")), open("https://ecency.com/purchase?type=boost&username=spike-bob"))
        assertEquals(LinkNavigation.Screen(AppScreen.GetPoints("spike-bob", "999points")), open("https://ecency.com/purchase?type=points&username=Spike-Bob&product_id=999points"))
        assertEquals(LinkNavigation.Screen(AppScreen.GetPoints("", "")), open("https://ecency.com/purchase?type=points&username=a%20b&product_id=999spins"), "bad names and products are dropped")
        assertEquals(LinkNavigation.Signup(AuthLinkModel.SIGNUP_URL, "spike-bob"), open("https://ecency.com/purchase?type=account&username=newbie&email=n@x.co&referral=spike-bob"))
        assertEquals(SignupPrefill("newbie", "n@x.co", "spike-bob", purchaseOnly = true), app.env.signupPrefill)
        assertNull(open("https://ecency.com/purchase?type=other"), "an unknown type is ignored, as RN")
    }

    @Test
    fun thePurchaseScreensAreBuiltAndRestoredWithoutTheProductToConfirm() {
        assertTrue(RouteName.BOOST.built && RouteName.BOOST.requiresLogin)
        assertTrue(RouteName.ACCOUNT_BOOST.built && RouteName.ACCOUNT_BOOST.requiresLogin)
        assertEquals(AppScreen.GetPoints("spike-bob"), AppScreen.of(RouteName.BOOST, "spike-bob"))
        assertEquals(AppScreen.GetPoints(""), AppScreen.of(RouteName.BOOST, "not a name"))
        assertEquals(AppScreen.AccountBoost(""), AppScreen.of(RouteName.ACCOUNT_BOOST))
        val line = NavState.encodeScreen(AppScreen.GetPoints("spike-bob", "999points"))
        assertEquals(AppScreen.GetPoints("spike-bob", ""), NavState.decodeScreen(line))
        assertEquals(AppScreen.AccountBoost("spike-bob"), NavState.decodeScreen(NavState.encodeScreen(AppScreen.AccountBoost("spike-bob"))))
    }
}

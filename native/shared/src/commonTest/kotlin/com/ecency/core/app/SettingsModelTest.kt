package com.ecency.core.app

import com.ecency.core.api.FakeTransport
import com.ecency.core.api.ecency.SupportSettings
import com.ecency.core.api.rpc.HiveNodes
import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.auth.AuthHttpRequest
import com.ecency.core.hive.ChainTime
import com.ecency.core.hive.GoldenVectors
import com.ecency.core.l10n.L10n
import com.ecency.core.l10n.LanguageSync
import com.ecency.core.l10n.SystemLanguage
import com.ecency.core.legacy.MapLegacyStorage
import com.ecency.core.render.ImageProxy
import com.ecency.core.secure.AuthType
import com.ecency.core.secure.KeyVault
import com.ecency.core.secure.VaultAccount
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** RN settingsContainer over a scripted node and Ecency API: nothing here reaches a real server. */
class SettingsModelTest {
    private val user = "spike-alice"
    private val other = "spike-other"

    private inner class World(val scope: TestScope, legacy: MapLegacyStorage? = null, signedIn: Boolean = true) {
        var chainLag = 3L
        var nodeDown = false
        val supportCalls = mutableListOf<AuthHttpRequest>()
        val support = mutableMapOf(user to """{"beneficiary_percent":0,"curation_percent":10}""", other to """{"beneficiary_percent":25,"curation_percent":0}""")
        var updateAnswer: (AuthHttpRequest) -> HttpResult = { r ->
            val body = Json.parseToJsonElement(r.body!!).jsonObject
            HttpResult(200, """{"beneficiary_percent":${body["beneficiary_percent"]},"curation_percent":${body["curation_percent"]}}""")
        }
        val gates = ArrayDeque<CompletableDeferred<Unit>>()
        val fetchGates = ArrayDeque<CompletableDeferred<Unit>>()
        val app = TestApp(scope, legacy = legacy?.let { LegacyMigrationSource(it, "pin-key", null) }) { req ->
            when (req.method) {
                "condenser_api.get_dynamic_global_properties" ->
                    if (nodeDown) awaitCancellation() else FakeTransport.result(req, """{"time":"${ChainTime.fromEpochSeconds(TestApp.NOW / 1000 - chainLag)}","head_block_number":5}""")
                else -> FakeTransport.result(req, "1")
            }
        }

        init {
            app.authBackend.rest = { r -> if (r.url == HiveNodes.REMOTE_LIST_URL) HttpResult(200, """{"hived":["https://pub-a.example","https://pub-b.example"]}""") else null }
            app.authBackend.gate = { r ->
                if (r.url.endsWith("support-settings-update")) gates.removeFirstOrNull()?.await()
                if (r.url.endsWith("/support-settings")) fetchGates.removeFirstOrNull()?.await()
            }
            app.authBackend.privateApi = { r ->
                supportCalls += r
                val code = Json.parseToJsonElement(r.body!!).jsonObject["code"]!!.jsonPrimitive.content
                val owner = code.removePrefix("token-")
                when {
                    r.url.endsWith("/support-settings") -> HttpResult(200, support.getValue(owner))
                    r.url.endsWith("/support-settings-update") -> updateAnswer(r)
                    else -> HttpResult(404, "{}")
                }
            }
            listOf(user, other).forEach { name ->
                KeyVault(app.store).save(VaultAccount(name, AuthType.POSTING_KEY, postingKey = GoldenVectors.WIF, accessToken = "token-$name"))
            }
            if (signedIn) KeyVault(app.store).setCurrentUsername(user)
            app.env.session.start()
            idle()
        }

        fun idle() = scope.testScheduler.advanceUntilIdle()

        /** Runs what is ready without moving the clock: a held-back request stays inside its 30 s bound. */
        fun step() = scope.testScheduler.runCurrent()

        val settings get() = app.env.settings

        fun toasts() = generateSequence { app.env.ui.current.toast?.also { app.env.ui.dismiss(it.id) }?.text }.toList()

        fun updates() = supportCalls.filter { it.url.endsWith("support-settings-update") }.map { Json.parseToJsonElement(it.body!!).jsonObject }
    }

    // ---- options (RN constants) ----

    @Test
    fun optionListsAreRns() {
        assertEquals(24, SettingsOptions.CURRENCIES.size)
        assertEquals(SettingsOption("BTC", "btc"), SettingsOptions.CURRENCIES.first())
        assertEquals(SettingsOption("NGN", "ngn"), SettingsOptions.CURRENCIES.last())
        assertEquals(39, SettingsOptions.LANGUAGES.size)
        assertEquals("en-US", SettingsOptions.LANGUAGES.first().value)
        assertEquals("cs-CZ", SettingsOptions.LANGUAGES.last().value)
        assertEquals(listOf("https://i.ecency.com", "https://images.hive.blog", "https://img.ecency.com"), SettingsOptions.IMAGE_SERVERS.map { it.value })
        assertEquals("api.hive.blog", SettingsOptions.serverLabel("https://api.hive.blog"))
        assertEquals("http://x.example", SettingsOptions.serverLabel("http://x.example"))
    }

    @Test
    fun deviceLocalesMapLikeRnAutoLocale() {
        val cases = mapOf(
            "de_DE" to "de-DE", "pt-BR" to "pt-PT", "en" to "en-US", "EN-gb" to "en-US", "zh-Hant-TW" to "zh-TW", "zh_HK" to "zh-TW",
            "zh-Hans-CN" to "zh-CN", "zh" to "zh-CN", "iw-IL" to "he-IL", "in" to "id-ID", "fil-PH" to "fil-PH", "sr-Latn-RS" to "sr-CS",
        )
        for ((device, expected) in cases) assertEquals(expected, SettingsOptions.registeredLocale(device), device)
        assertNull(SettingsOptions.registeredLocale("xx-YY"))
        assertNull(SettingsOptions.registeredLocale(""))
        assertNull(SettingsOptions.registeredLocale(null))
    }

    @Test
    fun feedbackMailIsRnsSendEmailUrl() {
        assertEquals(
            "mailto:bug@ecency.com?subject=Feedback%2FBug%20report&body=Write%20your%20message%20here%21%0A%0AHive%20username%3A%20spike-alice" +
                "%0AApp%20version%3A%204.0.0%20%2812%29%0APlatform%3A%20Pixel%208%20-%20Android%2034%0A",
            SettingsOptions.feedbackMailto("spike-alice", "4.0.0 (12)", "Pixel 8 - Android 34"),
        )
        assertTrue(SettingsOptions.feedbackMailto(null, "1", "p").contains("Unknown%20User"))
        assertEquals("%C3%BC~", SettingsOptions.qsEncode("ü~"))
    }

    @Test
    fun storeReviewLinks() {
        assertEquals("market://details?id=app.esteem.mobile.android", StoreListing.ANDROID)
        assertEquals("https://apps.apple.com/app/id1451896376?action=write-review", StoreListing.IOS_WEB, "RN's web listing when the lookup fails")
        assertEquals("https://apps.apple.com/app/ecency/id1?action=write-review", StoreListing.iosReview("https://apps.apple.com/app/ecency/id1"))
        assertEquals("https://apps.apple.com/us/app/x/id1?uo=4&action=write-review", StoreListing.iosReview("https://apps.apple.com/us/app/x/id1?uo=4"))
        assertNull(StoreListing.iosReview("https://evil.example/app"))
    }

    // ---- device settings ----

    @Test
    fun deviceSettingsAreSavedUnderRnsNames() = runTest {
        val w = World(this)
        w.settings.start("fr-CA")
        w.idle()
        assertEquals("fr-FR", w.settings.current.language, "the device language when nothing is saved")
        assertEquals("usd", w.settings.current.currency)
        assertEquals(NsfwMode.WARN, w.settings.current.nsfw)

        w.settings.setCurrency("EUR")
        assertEquals("eur", w.app.env.prefs.getString("settings.currency"), "lower case, the key the wallet reads")
        assertEquals("eur", WalletCurrencyProbe.code(w.app.env.prefs))
        w.settings.setCurrency("xyz")
        assertEquals("eur", w.settings.current.currency)

        w.settings.setLanguage("de-DE")
        assertEquals("de-DE", w.settings.contentLanguage)
        w.settings.setLanguage("klingon")
        assertEquals("de-DE", w.app.env.prefs.getString(SettingsModel.KEY_LANGUAGE))

        w.settings.setNsfw(NsfwMode.HIDE)
        assertEquals(NsfwMode.HIDE, NsfwMode.of(w.app.env.prefs), "the key feeds and waves read")

        w.settings.setTheme(ThemeMode.DARK)
        w.settings.adoptPlatformTheme("LIGHT")
        assertEquals(ThemeMode.DARK, w.settings.current.theme, "a theme saved here is never replaced by the old platform one")
    }

    /** The OS per-app language setting, scripted. */
    private class FakeSystemLanguage(var tag: String = "", var device: String? = "en-US") : SystemLanguage {
        val writes = mutableListOf<String>()
        override fun read() = tag
        override fun write(tag: String) {
            writes += tag
            this.tag = tag
        }
        override fun deviceLanguage() = device
    }

    /** Runs [block] with the catalog driven by [w]'s settings, and puts English back for the other tests. */
    private inline fun withCatalog(w: World, block: () -> Unit) {
        w.app.env.drivesLanguageCatalog = true
        try {
            block()
        } finally {
            L10n.setLanguage(L10n.SOURCE)
        }
    }

    @Test
    fun theLanguageDrivesTheCatalogAndTheOsPerAppSetting() = runTest {
        val w = World(this)
        val system = FakeSystemLanguage(device = "ru-RU")
        w.app.env.systemLanguage = system
        withCatalog(w) {
            w.settings.start("ru-RU")
            assertEquals("ru-RU", L10n.language.value, "the detected language, before the first frame")
            assertEquals(listOf("ru-RU"), system.writes, "the first sync writes it to the OS")

            w.settings.setLanguage("ar-SA")
            assertEquals("ar-SA", L10n.language.value)
            assertTrue(L10n.isRtl)
            assertEquals(listOf("ru-RU", "ar"), system.writes)
            assertEquals("ar-SA", w.app.env.prefs.getString(SettingsModel.KEY_LANGUAGE_SYNCED))

            // the user picks German for the app in the system settings, then comes back
            system.tag = "de"
            w.settings.syncSystemLanguage()
            assertEquals("de-DE", w.settings.current.language)
            assertEquals("de-DE", w.app.env.prefs.getString(SettingsModel.KEY_LANGUAGE))
            assertEquals("de-DE", L10n.language.value)
            assertFalse(L10n.isRtl)
            assertEquals(listOf("ru-RU", "ar"), system.writes, "an adopted language is not written back")

            // the device's language changes: the choice stays
            system.device = "ja-JP"
            w.settings.syncSystemLanguage()
            assertEquals("de-DE", w.settings.current.language)

            // the next start keeps it and writes nothing
            val next = SettingsModel(w.app.env)
            next.start("ja-JP")
            assertEquals("de-DE", next.current.language)
            assertEquals(2, system.writes.size)

            // "System default" in the system settings: the device language, and the OS keeps "System default"
            system.tag = ""
            next.syncSystemLanguage()
            assertEquals("ja-JP", next.current.language)
            assertEquals("ja-JP", L10n.language.value)
            assertEquals(LanguageSync.FOLLOWING_SYSTEM, w.app.env.prefs.getString(SettingsModel.KEY_LANGUAGE_SYNCED))
            next.syncSystemLanguage()
            assertEquals(2, system.writes.size, "never written back, also on the next resume")
            // following the system: a device language change is followed
            system.device = "de-AT"
            next.syncSystemLanguage()
            assertEquals("de-DE", next.current.language)
            assertEquals(2, system.writes.size)
            // the picker ends it and writes again, with the device's region when it is the same language
            next.setLanguage("de-DE")
            assertEquals("de-AT", system.writes.last())
            assertEquals("de-DE", w.app.env.prefs.getString(SettingsModel.KEY_LANGUAGE_SYNCED))
            next.syncSystemLanguage()
            assertEquals(3, system.writes.size, "no ping-pong: de-AT reads back as de-DE")
        }
    }

    @Test
    fun theWrittenTagKeepsTheDeviceRegion() = runTest {
        val w = World(this)
        val system = FakeSystemLanguage(device = "en-GB")
        w.app.env.systemLanguage = system
        withCatalog(w) {
            w.settings.start("en-GB")
            assertEquals(listOf("en-GB"), system.writes, "British dates stay British")
            w.settings.setLanguage("fr-FR")
            assertEquals("fr", system.writes.last())
            w.settings.syncSystemLanguage()
            assertEquals(2, system.writes.size)
        }
    }

    @Test
    fun aPerAppLanguageSetBeforeTheFirstStartBeatsTheDetectedOne() = runTest {
        val w = World(this)
        val system = FakeSystemLanguage(tag = "fa", device = "de-DE")
        w.app.env.systemLanguage = system
        withCatalog(w) {
            w.settings.start("de-DE")
            assertEquals("fa-IR", w.settings.current.language)
            assertEquals("fa-IR", L10n.language.value)
            assertTrue(L10n.isRtl)
            assertTrue(system.writes.isEmpty())
        }
    }

    @Test
    fun theRnLanguageReachesTheOsAfterTheImport() = runTest {
        val w = World(this, legacy = rnStorage(rnApplication))
        val system = FakeSystemLanguage()
        w.app.env.systemLanguage = system
        withCatalog(w) {
            w.settings.start("en")
            assertEquals(listOf("en-US"), system.writes)
            assertFalse(w.settings.current.languageSettled, "the RN import may still change it")
            w.idle()
            assertTrue(w.settings.current.languageSettled)
            assertEquals("de-DE", w.settings.current.language)
            assertEquals("de-DE", L10n.language.value)
            assertEquals(listOf("en-US", "de"), system.writes, "the imported choice beats the first sync's English")
        }
    }

    @Test
    fun withoutAnOsSettingOrCatalogTheLanguageIsOnlySaved() = runTest {
        val w = World(this)
        w.settings.start("fr-CA")
        assertTrue(w.settings.current.languageSettled, "nothing to import: final at once")
        w.settings.setLanguage("ar-SA")
        assertEquals("ar-SA", w.settings.current.language)
        assertEquals(L10n.SOURCE, L10n.language.value, "tests that do not drive the catalog leave it English")
        assertNull(w.app.env.prefs.getString(SettingsModel.KEY_LANGUAGE_SYNCED))
    }

    @Test
    fun anOldPlatformThemeMovesOnce() = runTest {
        val w = World(this)
        w.settings.start("en")
        w.settings.adoptPlatformTheme("DARK")
        assertEquals(ThemeMode.DARK, w.settings.current.theme)
        assertEquals("DARK", w.app.env.prefs.getString(SettingsModel.KEY_THEME))
    }

    @Test
    fun imageServerSwapsTheProxyAtOnce() = runTest {
        val w = World(this)
        w.settings.start("en")
        val before = w.app.env.renderer
        w.settings.setImageServer("https://images.hive.blog")
        assertEquals("https://images.hive.blog", w.app.env.proxyBase)
        val post = com.ecency.core.api.model.Post(author = "a", permlink = "p", body = "![x](https://example.com/a.png)")
        for (r in listOf(InterimPostBodyRenderer(), RenderHelperPostBodyRenderer())) {
            assertTrue("https://images.hive.blog/" in r.withProxyBase("https://images.hive.blog").blocks(post).toString(), "$r follows the proxy")
        }
        assertEquals(before, w.app.env.renderer, "a renderer without a proxy stays")
        assertEquals("https://images.hive.blog", w.app.env.prefs.getString(SettingsModel.KEY_IMAGE_SERVER))
        assertEquals(listOf("Successful"), w.toasts())
        assertEquals("https://images.hive.blog/u/alice/avatar/128", PostCard.avatarUrl("alice", w.app.env.proxyBase))

        w.settings.setImageServer("https://evil.example")
        assertEquals("https://images.hive.blog", w.app.env.proxyBase, "only RN's three servers")
        w.settings.setImageServer(ImageProxy.DEFAULT_BASE)
        assertNull(w.app.env.prefs.getString(SettingsModel.KEY_IMAGE_SERVER), "the default is no entry")
    }

    @Test
    fun aSavedImageServerAppliesAtStart() = runTest {
        val w = World(this)
        w.app.env.prefs.putString(SettingsModel.KEY_IMAGE_SERVER, "https://img.ecency.com")
        w.settings.start("en")
        assertEquals("https://img.ecency.com", w.app.env.proxyBase)
        assertEquals(2, w.settings.current.imageServerIndex)
    }

    // ---- server ----

    @Test
    fun serverPickShowsCheckingThenRnsToasts() = runTest {
        val w = World(this)
        w.app.env.managesHiveNodes = true
        w.settings.start("en")
        w.settings.onShow()
        w.idle()
        assertEquals(listOf("https://pub-a.example", "https://pub-b.example"), w.settings.current.servers)

        w.settings.selectServer("https://pub-b.example")
        assertTrue(w.settings.current.checkingServer)
        assertEquals("Checking...", w.settings.current.serverLabel)
        w.idle()
        assertFalse(w.settings.current.checkingServer)
        assertEquals("pub-b.example", w.settings.current.serverLabel)
        assertEquals("https://pub-b.example", w.app.env.hiveNodes.selected())
        assertEquals(listOf("Successfully connected!"), w.toasts())
        // RN #2672: the picker highlights the saved server
        assertEquals(1, w.settings.current.serverIndex)

        w.chainLag = 60
        w.settings.selectServer("https://pub-a.example")
        w.idle()
        assertEquals(listOf("Server not available"), w.toasts())
        assertEquals("https://pub-b.example", w.app.env.hiveNodes.selected(), "a stale server is not saved")
        assertFalse(w.settings.current.checkingServer, "RN leaves its spinner on here; this one stops")

        w.nodeDown = true
        w.settings.selectServer("https://pub-a.example")
        w.idle()
        assertEquals(listOf("Connection Failed!"), w.toasts())
        assertEquals("pub-b.example", w.settings.current.serverLabel)
        assertEquals(1, w.settings.current.serverIndex, "a refused pick leaves the saved one highlighted")
    }

    @Test
    fun thePickerHighlightsTheShownServerWhateverItsSpelling() {
        val list = listOf("https://pub-a.example", "https://pub-b.example")
        assertEquals(1, SettingsState(servers = list, server = "https://PUB-B.example/").serverIndex)
        assertEquals(0, SettingsState(servers = list, server = "https://pub-a.example").serverIndex)
        assertEquals(-1, SettingsState(servers = list, server = "https://elsewhere.example").serverIndex)
        assertEquals(-1, SettingsState(servers = list, server = null).serverIndex)
        assertEquals(-1, SettingsState(servers = emptyList(), server = "https://pub-a.example").serverIndex)
    }

    // ---- support settings ----

    @Test
    fun supportCardShowsOnlyAfterAValidLoad() = runTest {
        val w = World(this)
        w.settings.start("en")
        w.idle()
        assertNull(w.settings.current.support, "nothing is loaded before the screen shows")
        assertTrue(w.supportCalls.isEmpty())

        w.support[user] = """{"beneficiary_percent":"x"}"""
        w.settings.onShow()
        w.idle()
        assertNull(w.settings.current.support, "a malformed answer keeps the card hidden")
        w.settings.setSupportBeneficiary(true)
        w.idle()
        assertTrue(w.updates().isEmpty(), "nothing is written over unknown values")

        w.support[user] = """{"beneficiary_percent":0,"curation_percent":10}"""
        w.settings.onShow()
        w.idle()
        assertEquals(SupportSettings(0.0, 10.0), w.settings.current.support)
        assertEquals(user, w.settings.current.supportAccount)
    }

    @Test
    fun aToggleSendsBothPercentsAndShowsAtOnce() = runTest {
        val w = World(this)
        w.settings.start("en")
        w.settings.onShow()
        w.step()
        val gate = CompletableDeferred<Unit>().also { w.gates += it }
        w.settings.setSupportBeneficiary(true)
        w.step()
        assertEquals(5, w.settings.current.beneficiaryPercent, "optimistic")
        gate.complete(Unit)
        w.step()
        val body = w.updates().single()
        assertEquals(JsonPrimitive(5.0), body["beneficiary_percent"])
        assertEquals(JsonPrimitive(10.0), body["curation_percent"], "the other field travels unchanged")
        assertEquals(JsonPrimitive("token-$user"), body["code"])
        assertEquals(listOf("Successful"), w.toasts())

        w.settings.setSupportCurationPercent(50)
        w.settings.setSupportCurationPercent(7)
        w.step()
        assertEquals(50, w.settings.current.curationPercent, "only RN's presets")
        w.settings.setSupportCuration(false)
        w.step()
        assertEquals(0, w.settings.current.curationPercent)
    }

    @Test
    fun aSupersededSaveIsNotSentAndSavesGoInOrder() = runTest {
        val w = World(this)
        w.settings.start("en")
        w.settings.onShow()
        w.step()
        val first = CompletableDeferred<Unit>().also { w.gates += it }
        val last = CompletableDeferred<Unit>().also { w.gates += it }
        w.settings.setSupportBeneficiaryPercent(1)
        w.step()
        w.settings.setSupportBeneficiaryPercent(10)
        w.settings.setSupportBeneficiaryPercent(25)
        w.step()
        first.complete(Unit)
        w.step()
        assertEquals(25, w.settings.current.beneficiaryPercent, "the older save's answer does not replace the newer optimistic value")
        last.complete(Unit)
        w.step()
        assertEquals(listOf(1.0, 25.0), w.updates().map { it["beneficiary_percent"]!!.jsonPrimitive.content.toDouble() }, "10 was superseded before it went out")
        assertEquals(25, w.settings.current.beneficiaryPercent)
    }

    @Test
    fun aFailedSaveGoesBackToWhatTheServerConfirmed() = runTest {
        val w = World(this)
        w.settings.start("en")
        w.settings.onShow()
        w.step()
        w.toasts()
        w.updateAnswer = { HttpResult(500, "{}") }
        val first = CompletableDeferred<Unit>().also { w.gates += it }
        val second = CompletableDeferred<Unit>().also { w.gates += it }
        w.settings.setSupportBeneficiaryPercent(10)
        w.step()
        w.settings.setSupportCurationPercent(25)
        w.step()
        first.complete(Unit)
        w.step()
        assertEquals(SupportSettings(10.0, 25.0), w.settings.current.support, "an older failure does not roll back a newer save")
        assertEquals(emptyList(), w.toasts())
        second.complete(Unit)
        w.step()
        assertEquals(SupportSettings(0.0, 10.0), w.settings.current.support, "the last confirmed values, not the first save's optimistic ones")
        assertEquals(listOf("Fail!"), w.toasts())
    }

    @Test
    fun anAccountChangeHidesTheCardAndLoadsTheNewAccounts() = runTest {
        val w = World(this)
        w.settings.start("en")
        w.settings.onShow()
        w.step()
        val gate = CompletableDeferred<Unit>().also { w.gates += it }
        w.settings.setSupportBeneficiaryPercent(10)
        w.step()
        w.app.env.session.switchTo(other)
        w.step()
        gate.complete(Unit)
        w.step()
        assertEquals(other, w.settings.current.supportAccount)
        assertEquals(SupportSettings(25.0, 0.0), w.settings.current.support, "the previous account's late answer does not land")

        w.settings.onHide()
        w.app.env.session.switchTo(user)
        w.step()
        assertNull(w.settings.current.support, "hidden, and not loaded while the screen is away")
    }

    // ---- RN settings import ----

    private fun rnStorage(application: JsonObject, settings: String? = """{"server":"https://rpc.mine.example","language":"","nsfw":""}""", walkthrough: String? = null): MapLegacyStorage {
        val root = buildJsonObject {
            put("application", application.toString())
            walkthrough?.let { put("walkthrough", it) }
            put("_persist", """{"version":22,"rehydrated":true}""")
        }
        return MapLegacyStorage(listOfNotNull("persist:root" to root.toString(), settings?.let { "settings" to it }).toMap())
    }

    private val rnApplication = Json.parseToJsonElement(
        """{"api":"https://api.hive.blog","currency":{"currency":"eur","currencyRate":0.9,"currencySymbol":"€"},"language":"de-DE",
           |"nsfw":2,"colorTheme":2,"isDarkTheme":true,"hidePostsThumbnails":true,"imageServer":"https://images.hive.blog",
           |"isNotificationOpen":true,"notificationDetails":{"voteNotification":false,"followNotification":true,"tagsNotification":false,"oddKey":false},
           |"pin":"secret-not-read"}""".trimMargin(),
    ).jsonObject

    @Test
    fun rnEditorDraftsComeWithTheSettingsImport() = runTest {
        val cache = """{"draftsCollection":{"DEFAULT_USER_DRAFT_ID_alice":{"title":"T","body":"B","author":"alice","expiresAt":${TestApp.NOW + 100_000}}}}"""
        val w = World(this, legacy = MapLegacyStorage(mapOf("persist:root" to buildJsonObject { put("cache", cache) }.toString())))
        w.settings.start("en")
        w.idle()
        assertEquals("T", w.app.env.localDrafts.load("alice", LocalDrafts.NEW_POST)?.title)
        assertEquals("1", w.app.env.prefs.getString(SettingsModel.KEY_IMPORTED))
    }

    @Test
    fun rnSettingsAreImportedOnce() = runTest {
        val storage = rnStorage(rnApplication)
        val w = World(this, legacy = storage)
        w.app.env.managesHiveNodes = true
        w.app.env.hiveNodes.start()
        w.app.env.push.start()
        w.app.env.notifications.start()
        w.settings.start("en")
        w.idle()
        val prefs = w.app.env.prefs
        assertEquals("https://rpc.mine.example", prefs.getString(HiveNodeSettings.PREFS_KEY))
        assertEquals("https://rpc.mine.example", w.app.env.api.client.nodes.first(), "the pool follows at once")
        assertEquals("eur", w.settings.current.currency)
        assertEquals("de-DE", w.settings.current.language)
        assertEquals(NsfwMode.HIDE, w.settings.current.nsfw, "RN's dropdown stored the index as a number")
        assertEquals(ThemeMode.DARK, w.settings.current.theme)
        assertEquals("https://images.hive.blog", w.app.env.proxyBase)
        assertFalse(w.app.env.notifications.current.showImages)
        val push = w.app.env.push.current.settings
        assertTrue(push.enabled)
        assertEquals(setOf(PushType.VOTE, PushType.TAGS), push.off)
        assertEquals("1", prefs.getString(SettingsModel.KEY_IMPORTED))
        assertTrue(prefs.keys().none { "pin" in it.lowercase() && it != "push.typesOff" }, "no secret is copied")

        // a later start never imports again, even with other RN values
        storage.values["persist:root"] = buildJsonObject { put("application", """{"language":"ru-RU"}""") }.toString()
        val again = SettingsModel(w.app.env)
        prefs.putString(SettingsModel.KEY_LANGUAGE, null)
        again.start("en")
        w.idle()
        assertNull(prefs.getString(SettingsModel.KEY_LANGUAGE))
    }

    @Test
    fun valuesChosenInThisAppWin() = runTest {
        val w = World(this, legacy = rnStorage(rnApplication))
        w.app.env.prefs.putString(SettingsModel.KEY_CURRENCY, "jpy")
        w.app.env.prefs.putString(PushSettings.KEY_ENABLED, "1")
        w.settings.start("en")
        w.idle()
        assertEquals("jpy", w.settings.current.currency)
        assertNull(w.app.env.prefs.getString(PushSettings.KEY_OFF), "the push switches move together or not at all")
        assertEquals("de-DE", w.settings.current.language)
    }

    @Test
    fun anRnServerOverPlainHttpOrWithAUserIsNotImported() = runTest {
        for (server in listOf("http://rpc.mine.example", "https://me@rpc.mine.example")) {
            val w = World(this, legacy = rnStorage(JsonObject(emptyMap()), settings = """{"server":"$server"}"""))
            w.settings.start("en-US")
            w.idle()
            assertNull(w.app.env.prefs.getString(HiveNodeSettings.PREFS_KEY), server)
        }
    }

    @Test
    fun rnValuesOutsideTheOptionsAreDropped() = runTest {
        val app = Json.parseToJsonElement(
            """{"currency":{"currency":"doge"},"language":"xx-XX","nsfw":"9","colorTheme":"2","imageServer":"https://evil.example",
               |"isNotificationOpen":false,"notificationDetails":{"voteNotification":"no"}}""".trimMargin(),
        ).jsonObject
        val w = World(this, legacy = rnStorage(app, settings = """{"server":"ftp://x"}"""))
        w.settings.start("ja-JP")
        w.idle()
        val prefs = w.app.env.prefs
        for (key in listOf(SettingsModel.KEY_CURRENCY, NsfwMode.PREFS_KEY, SettingsModel.KEY_THEME, SettingsModel.KEY_IMAGE_SERVER, HiveNodeSettings.PREFS_KEY)) {
            assertNull(prefs.getString(key), key)
        }
        assertEquals("ja-JP", w.settings.current.language, "RN's unknown language leaves the detected one")
        assertEquals("0", prefs.getString(PushSettings.KEY_ENABLED), "the master switch alone still moves")
        assertEquals("", prefs.getString(PushSettings.KEY_OFF))
    }

    @Test
    fun unreadableRnStorageIsTriedAgainAndAbsentStorageIsDone() = runTest {
        val storage = rnStorage(rnApplication).also { it.unreadable = true }
        var w = World(this, legacy = storage)
        w.settings.start("en")
        w.idle()
        assertNull(w.app.env.prefs.getString(SettingsModel.KEY_IMPORTED))
        assertEquals("usd", w.settings.current.currency)

        storage.unreadable = false
        val next = SettingsModel(w.app.env)
        next.start("en")
        w.idle()
        assertEquals("eur", next.current.currency, "imported at the next start")

        w = World(this, legacy = MapLegacyStorage())
        w.settings.start("en")
        w.idle()
        assertEquals("1", w.app.env.prefs.getString(SettingsModel.KEY_IMPORTED))

        w = World(this, legacy = MapLegacyStorage(mapOf("persist:root" to "{not json")))
        w.settings.start("en")
        w.idle()
        assertEquals("1", w.app.env.prefs.getString(SettingsModel.KEY_IMPORTED), "bad data does not retry forever")
    }

    // ---- guards the review asked to pin ----

    @Test
    fun theBodyCacheIsKeptPerImageProxy() = runTest {
        val w = World(this)
        w.settings.start("en")
        val post = com.ecency.core.api.model.Post(author = "a", permlink = "cached", body = "text")
        w.app.env.renderBody(post)
        w.idle()
        assertNotNull(w.app.env.cachedBody(post), "cached for the proxy it was rendered with")
        w.settings.setImageServer("https://images.hive.blog")
        assertNull(w.app.env.cachedBody(post), "a body rendered for the old proxy is not served")
        w.app.env.renderBody(post)
        assertEquals(2, w.app.renderer.blockCalls.count { it == "cached" }, "rendered again for the new proxy")
    }

    @Test
    fun aSecondServerPickWhileOneIsCheckedIsIgnored() = runTest {
        val w = World(this)
        w.settings.start("en")
        w.settings.selectServer("https://pub-a.example")
        w.settings.selectServer("https://pub-b.example")
        w.idle()
        assertEquals("https://pub-a.example", w.app.env.hiveNodes.selected())
        assertEquals(1, w.app.transport.sent.count { it.method == "condenser_api.get_dynamic_global_properties" })
        assertEquals(listOf("Successfully connected!"), w.toasts())
    }

    @Test
    fun noSaveForTheOldAccountOnceTheSessionMoved() = runTest {
        val w = World(this)
        w.settings.start("en")
        w.settings.onShow()
        w.idle()
        w.app.env.session.switchTo(other)
        // before the model heard of the change: the card still names the previous account
        if (w.settings.current.supportAccount == user) {
            w.settings.setSupportBeneficiaryPercent(10)
            w.idle()
            assertTrue(w.updates().isEmpty(), "nothing is saved for an account that is no longer signed in")
        } else {
            error("the account change arrived before the check; the guard is not exercised")
        }
    }

    @Test
    fun aLoadStartedBeforeASaveDoesNotReplaceIt() = runTest {
        val w = World(this)
        w.settings.start("en")
        w.settings.onShow()
        w.idle()
        val load = CompletableDeferred<Unit>().also { w.fetchGates += it }
        val save = CompletableDeferred<Unit>().also { w.gates += it }
        w.settings.onShow()
        w.step()
        w.settings.setSupportBeneficiaryPercent(10)
        w.step()
        load.complete(Unit)
        w.step()
        assertEquals(10, w.settings.current.beneficiaryPercent, "the older load's answer does not land over the newer save")
        save.complete(Unit)
        w.step()
        assertEquals(10, w.settings.current.beneficiaryPercent)
    }

    @Test
    fun aSavedImageServerOutsideTheListIsIgnoredAtStart() = runTest {
        val w = World(this)
        w.app.env.prefs.putString(SettingsModel.KEY_IMAGE_SERVER, "https://evil.example")
        w.settings.start("en")
        assertEquals(ImageProxy.DEFAULT_BASE, w.app.env.proxyBase)
        assertEquals(0, w.settings.current.imageServerIndex)
    }

    @Test
    fun imagesShownInRnStayShownAndOnlySuffixedPushKeysCount() = runTest {
        val app = Json.parseToJsonElement("""{"hidePostsThumbnails":false,"notificationDetails":{"vote":false,"followNotification":false}}""").jsonObject
        val w = World(this, legacy = rnStorage(app, settings = null))
        w.settings.start("en")
        w.idle()
        assertNull(w.app.env.prefs.getString(NotificationsModel.KEY_SHOW_IMAGES))
        assertEquals("follow", w.app.env.prefs.getString(PushSettings.KEY_OFF), "a key without RN's suffix is not a switch")
    }

    @Test
    fun theRnWalkthroughStepsAlreadySeenAreImported() = runTest {
        // RN transformWalkthroughMap: a Map persisted as [[index, item]]; an unknown or unshown step changes nothing
        val walkthrough = """{"walkthroughMap":[[2,{"walkthroughIndex":2,"isShown":true}],[1,{"walkthroughIndex":1,"isShown":false}],[9,{"isShown":true}],"x"]}"""
        val w = World(this, legacy = rnStorage(rnApplication, walkthrough = walkthrough))
        assertTrue(w.app.env.walkthrough.showsMarker(WalkthroughStep.CUSTOM_TABS_BTN))
        w.settings.start("en")
        w.idle()
        assertFalse(w.app.env.walkthrough.showsMarker(WalkthroughStep.CUSTOM_TABS_BTN))
        assertTrue(w.app.env.walkthrough.showsMarker(WalkthroughStep.EDITOR_DRAFT_BTN))
        assertEquals("2", w.app.env.prefs.getString(WalkthroughModel.KEY))
    }

    @Test
    fun showImagesIsReadInOnePlaceAndTheViewerKeepsOnlyRevealedImages() {
        val prefs = InMemoryPrefs()
        assertTrue(ContentImages.shown(prefs), "on by default, as RN")
        prefs.putString(NotificationsModel.KEY_SHOW_IMAGES, "0")
        assertFalse(ContentImages.shown(prefs))
        val all = listOf("a", "b", "c")
        assertEquals(all, ContentImages.viewable(all, true) { false })
        assertEquals(listOf("b"), ContentImages.viewable(all, false) { it == "b" })
    }

    @Test
    fun aMarkerGoesOnFirstUseOnlyWhenItWasShown() = runTest {
        val prefs = InMemoryPrefs()
        val dispatchers = TestApp(this) { com.ecency.core.api.rpc.HttpResult(500, "") }.env.dispatchers
        val model = WalkthroughModel({ prefs }, dispatchers)
        // RN `hidden` (signed out): no dot, and a tap then does not count as seeing it
        assertFalse(model.showsMarker(WalkthroughStep.CUSTOM_TABS_BTN, hidden = true))
        model.intercept(WalkthroughStep.CUSTOM_TABS_BTN, hidden = true)
        assertTrue(model.showsMarker(WalkthroughStep.CUSTOM_TABS_BTN))
        model.intercept(WalkthroughStep.CUSTOM_TABS_BTN)
        assertFalse(model.showsMarker(WalkthroughStep.CUSTOM_TABS_BTN))
        // kept on the device: a new model (the next start) reads it back
        assertFalse(WalkthroughModel({ prefs }, dispatchers).showsMarker(WalkthroughStep.CUSTOM_TABS_BTN))
        assertTrue(WalkthroughModel({ prefs }, dispatchers).showsMarker(WalkthroughStep.EDITOR_DRAFT_BTN))
    }

    @Test
    fun anImportedServerDoesNotTouchAPoolNobodyStarted() = runTest {
        val w = World(this, legacy = rnStorage(rnApplication))
        val before = w.app.env.api.client.nodes
        w.settings.start("en")
        w.idle()
        assertEquals("https://rpc.mine.example", w.app.env.hiveNodes.selected())
        assertEquals(before, w.app.env.api.client.nodes, "a demo run keeps its offline node")
    }

    @Test
    fun theDeviceLanguageIsDetectedOnceAndRnsChoiceStillWins() = runTest {
        var w = World(this)
        w.settings.start("de-AT")
        assertEquals("de-DE", w.app.env.prefs.getString(SettingsModel.KEY_LANGUAGE))
        // an English device saves nothing, but the detection is done: a later device language is not taken
        val en = World(this)
        en.settings.start("en-GB")
        SettingsModel(en.app.env).also { it.start("fr-FR") }.let { assertEquals("en-US", it.current.language) }
        // a later start with another device language keeps it (RN's guard)
        SettingsModel(w.app.env).also { it.start("fr-FR") }.let { assertEquals("de-DE", it.current.language) }

        w = World(this, legacy = rnStorage(Json.parseToJsonElement("""{"language":"ru-RU"}""").jsonObject))
        w.settings.start("de-AT")
        w.idle()
        assertEquals("ru-RU", w.settings.current.language, "the RN app's choice replaces the detected one")
    }

    @Test
    fun rnsUndetectedDefaultEnglishDoesNotReplaceTheDetectedLanguage() = runTest {
        var w = World(this, legacy = rnStorage(Json.parseToJsonElement("""{"language":"en-US"}""").jsonObject))
        w.settings.start("de-AT")
        w.idle()
        assertEquals("de-DE", w.settings.current.language, "RN never detected: its en-US is the initial state")

        val storage = rnStorage(Json.parseToJsonElement("""{"language":"en-US"}""").jsonObject).also { it.values["locale_auto_detected"] = "1" }
        w = World(this, legacy = storage)
        w.settings.start("de-AT")
        w.idle()
        assertEquals("en-US", w.settings.current.language, "after RN's own detection, en-US was kept on purpose")
    }

    @Test
    fun rateFallsBackToTheWebListingWhenTheLookupHangs() = runTest {
        val w = World(this)
        w.app.authBackend.gate = { r -> if (r.url == UpdateLinks.APP_STORE_LOOKUP) kotlinx.coroutines.awaitCancellation() }
        val started = testScheduler.currentTime
        assertEquals(StoreListing.IOS_WEB, w.settings.iosRatePage())
        assertEquals(SettingsModel.RATE_LOOKUP_MILLIS, testScheduler.currentTime - started, "capped, not the 30 s request bound")
        w.app.authBackend.gate = null
        w.app.authBackend.rest = { r -> if (r.url == UpdateLinks.APP_STORE_LOOKUP) HttpResult(200, """{"results":[{"trackViewUrl":"https://apps.apple.com/app/ecency/id9?uo=4"}]}""") else null }
        assertEquals("https://apps.apple.com/app/ecency/id9?action=write-review", w.settings.iosRatePage())
    }
}

/** What the wallet branch's `WalletCurrency.of` reads (`settings.currency`, lower case), without depending on it. */
private object WalletCurrencyProbe {
    fun code(prefs: Prefs): String? = prefs.getString("settings.currency")?.lowercase()
}

package com.ecency.app

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import com.ecency.app.ui.AppHost
import com.ecency.app.ui.EcencyApp
import com.ecency.app.ui.EcencyTheme
import com.ecency.app.ui.Navigator
import com.ecency.core.api.rpc.HiveNodes
import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.app.AppScreen
import com.ecency.core.app.NavState
import com.ecency.core.app.SettingsModel
import com.ecency.core.l10n.L10n
import com.ecency.core.secure.AuthType
import com.ecency.core.secure.KeyVault
import com.ecency.core.secure.VaultAccount
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** RN settings and email digests on Android, against fakes only (no Ecency or Hive server is reached). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsFlowsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val node = TestNode()
    private val env by lazy { node.env() }
    private val mails = mutableListOf<String>()
    private var storeOpened = 0
    private var support = """{"beneficiary_percent":0,"curation_percent":0}"""

    /** What the second account's support settings answer. */
    @Volatile private var otherSupport = """{"beneficiary_percent":"x"}"""
    private var digests = """[{"id":"1","email":"a@example.com","type":"own","target":"tester","cadence":"weekly","status":"active"}]"""

    private val host = object : AppHost {
        override fun openExternal(url: String) {}
        override fun toast(text: String) {}
        override fun copy(text: String) {}
        override fun openMail(url: String): Boolean { mails += url; return true }
        override fun openStoreListing() { storeOpened++ }
        override fun feedbackPlatform() = "Robo - Android 35"
        override fun deviceLocale() = "de-AT"
        override fun buildNumber() = "7"
    }

    @Before
    fun setUp() {
        SingletonImageLoader.setUnsafe { ctx ->
            ImageLoader.Builder(ctx).components {
                add(object : Interceptor {
                    override suspend fun intercept(chain: Interceptor.Chain): ImageResult =
                        ErrorResult(null, chain.request, IllegalStateException("no network in tests"))
                })
            }.build()
        }
        node.auth.rest = { r ->
            when {
                r.url == HiveNodes.REMOTE_LIST_URL -> HttpResult(200, """{"hived":["https://node-a.example","https://node-b.example"]}""")
                r.url.endsWith("/private-api/support-settings") -> HttpResult(200, if ("access-other" in r.body.orEmpty()) otherSupport else support)
                r.url.endsWith("/private-api/support-settings-update") -> {
                    val b = Json.parseToJsonElement(r.body!!).jsonObject
                    HttpResult(200, """{"beneficiary_percent":${b["beneficiary_percent"]},"curation_percent":${b["curation_percent"]}}""")
                }
                r.url.endsWith("/api/newsletter/subscriptions") -> HttpResult(200, """{"subscriptions":$digests}""")
                r.url.endsWith("/api/newsletter/unsubscribe-all") -> { digests = "[]"; HttpResult(200, "{}") }
                else -> null
            }
        }
    }

    @After
    fun tearDown() = SingletonImageLoader.reset()

    private fun signIn() {
        val vault = KeyVault(node.store)
        vault.save(VaultAccount("tester", AuthType.POSTING_KEY, postingKey = node.key.toWif(), accessToken = "access-tester"))
        vault.setCurrentUsername("tester")
    }

    private fun launch(navigator: Navigator = Navigator(NavState().push(AppScreen.Settings))) =
        compose.setContent { EcencyTheme(dark = false) { EcencyApp(env, host, navigator) } }

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun until(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (true) {
            compose.waitForIdle()
            if (condition()) return
            if (System.currentTimeMillis() > end) throw AssertionError("condition not met in 10 s")
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            Thread.sleep(20)
        }
    }

    private fun waitFor(tag: String) = until { exists(tag) }

    private fun text(tag: String): String =
        compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()

    private fun pick(row: String, item: String) {
        compose.onNodeWithTag(row).performScrollTo().performClick()
        waitFor(item)
        compose.onNodeWithTag(item).performClick()
        compose.waitForIdle()
    }

    private fun toast(): String? = env.ui.current.toast?.text

    @Test
    fun signedOutShowsTheDeviceSettingsOnly() {
        launch()
        waitFor("settings:currency")
        assertEquals("USD", text("settings:currency:value"))
        assertEquals("the device language when none is saved (RN autoLocale)", "Deutsch", text("settings:language:value"))
        assertEquals("Always warn", text("settings:nsfw:value"))
        assertEquals("Ecency Images (Default)", text("settings:imageServer:value"))
        for (tag in listOf("menuPinLock", "pushSettings", "supportCard", "settings:emailDigests", "menuBackupKeys", "menuDeleteAccount")) {
            assertFalse("$tag needs an account", exists(tag))
        }
        assertTrue(exists("settings:rate") && exists("settings:feedback"))
    }

    /** The l10n core's end to end proof: the language row title is read through the generated catalog. */
    @Test
    fun languageRowTitleComesFromTheCatalog() {
        L10n.setLanguage("ru-RU")
        try {
            launch()
            waitFor("settings:language")
            compose.onNodeWithText("Язык", useUnmergedTree = true).assertExists()
            assertFalse(compose.onAllNodes(hasText("Language"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        } finally {
            L10n.setLanguage(L10n.SOURCE)
        }
    }

    /** The picker switches the copy at once, on the screen that is open, with no activity restart. */
    @Test
    fun pickingALanguageSwitchesTheCopyLive() {
        env.drivesLanguageCatalog = true
        try {
            launch()
            waitFor("settings:language")
            // the device's German (RN autoLocale) is what the catalog shows from the first frame
            compose.onNodeWithText("Sprache", useUnmergedTree = true).assertExists()
            pick("settings:language", "settings:language:2")
            assertEquals("ru-RU", L10n.language.value)
            compose.onNodeWithText("Язык", useUnmergedTree = true).assertExists()
            assertEquals("Русский", text("settings:language:value"))
            pick("settings:language", "settings:language:0")
            compose.onNodeWithText("Language", useUnmergedTree = true).assertExists()
        } finally {
            L10n.setLanguage(L10n.SOURCE)
        }
    }

    @Test
    fun dropdownsSaveUnderRnsNames() {
        launch()
        waitFor("settings:currency")
        pick("settings:currency", "settings:currency:2")
        assertEquals("eur", env.prefs.getString(SettingsModel.KEY_CURRENCY))
        assertEquals("EUR", text("settings:currency:value"))
        pick("settings:language", "settings:language:2")
        assertEquals("ru-RU", env.settings.contentLanguage)
        pick("settings:nsfw", "nsfw:HIDE")
        assertEquals("2", env.prefs.getString("nsfw"))
        pick("settings:imageServer", "settings:imageServer:1")
        assertEquals("https://images.hive.blog", env.proxyBase)
        assertEquals("Successful", toast())
    }

    @Test
    fun aStaleServerIsRefusedWithRnsToast() {
        launch()
        // the scripted node's head block is a minute behind the fixed clock
        until { env.settings.current.servers.isNotEmpty() }
        pick("settings:server", "settings:server:1")
        until { !env.settings.current.checkingServer }
        assertEquals("Server not available", toast())
        assertEquals(null, env.hiveNodes.selected())
    }

    @Test
    fun rateAndFeedbackOpenTheStoreAndTheMailApp() {
        signIn()
        launch()
        waitFor("settings:feedback")
        compose.onNodeWithTag("settings:rate").performScrollTo().performClick()
        assertEquals(1, storeOpened)
        compose.onNodeWithTag("settings:feedback").performScrollTo().performClick()
        compose.waitForIdle()
        val mail = mails.single()
        assertTrue(mail, mail.startsWith("mailto:bug@ecency.com?subject=Feedback%2FBug%20report&body="))
        assertTrue(mail, "tester" in mail && "Robo%20-%20Android%2035" in mail && "%20%287%29" in mail)
        assertEquals("Email successfully open", toast())
    }

    @Test
    fun supportCardShowsAfterTheLoadAndSavesBothPercents() {
        signIn()
        launch()
        waitFor("supportCard")
        compose.onNodeWithTag("support:beneficiary").performScrollTo().assertIsOff()
        compose.onNodeWithTag("support:beneficiary").performClick()
        until { node.auth.requests.any { it.url.endsWith("support-settings-update") } }
        val body = Json.parseToJsonElement(node.auth.requests.last { it.url.endsWith("support-settings-update") }.body!!).jsonObject
        assertEquals(5.0, body["beneficiary_percent"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertEquals(0.0, body["curation_percent"]!!.jsonPrimitive.content.toDouble(), 0.0)
        waitFor("support:beneficiaryPercent")
        compose.onNodeWithTag("support:beneficiary").assertIsOn()
        assertEquals("5%", text("support:beneficiaryPercent:value"))
    }

    @Test
    fun aMalformedSupportAnswerKeepsTheCardHidden() {
        support = """{"beneficiary_percent":null}"""
        signIn()
        launch()
        waitFor("menuDeleteAccount")
        until { node.auth.requests.any { it.url.endsWith("/support-settings") } }
        assertFalse(exists("supportCard"))
    }

    @Test
    fun emailDigestsListsStopsAllAndOffersTheNewsletter() {
        signIn()
        val navigator = Navigator(NavState().push(AppScreen.Settings))
        launch(navigator)
        waitFor("settings:emailDigests")
        compose.onNodeWithTag("settings:emailDigests").performScrollTo().performClick()
        waitFor("digest:1")
        assertEquals(AppScreen.EmailDigests, navigator.top.screen)
        assertTrue(exists("digestOffer:site"))
        assertFalse("own is subscribed", exists("digestOffer:own"))
        compose.onNodeWithTag("stopAll:a@example.com").performClick()
        waitFor("stopAllConfirm")
        compose.onNodeWithTag("stopAllConfirm").performClick()
        waitFor("digestsEmpty")
        val stop = node.auth.requests.single { it.url.endsWith("/unsubscribe-all") }
        assertEquals("a@example.com", Json.parseToJsonElement(stop.body!!).jsonObject["email"]!!.jsonPrimitive.content)
        assertTrue(exists("digestOffer:own"))
        compose.onNodeWithTag("digestOffer:site").performClick()
        until { env.digest.current.open }
        assertEquals("site", env.digest.current.type)
    }

    @Test
    fun anAccountSwitchWithTheScreenOpenHidesTheCardUntilTheNewAccountLoaded() {
        val vault = KeyVault(node.store)
        vault.save(VaultAccount("other", AuthType.POSTING_KEY, postingKey = node.key.toWif(), accessToken = "access-other"))
        signIn()
        launch()
        waitFor("supportCard")
        // the second account's settings do not load (malformed): the first account's card must not stay
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch { env.session.switchTo("other") }
        until { env.session.current.username == "other" && node.auth.requests.any { it.url.endsWith("/support-settings") && "access-other" in it.body.orEmpty() } }
        until { !exists("supportCard") }
        assertEquals("other", env.settings.current.supportAccount)
        assertEquals(null, env.settings.current.support)
    }
}

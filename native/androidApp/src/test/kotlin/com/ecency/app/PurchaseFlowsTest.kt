package com.ecency.app

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.Purchase
import com.ecency.app.crash.SentryReporting
import com.ecency.app.iap.PlayStore
import com.ecency.app.ui.AppHost
import com.ecency.app.ui.EcencyApp
import com.ecency.app.ui.EcencyTheme
import com.ecency.app.ui.Navigator
import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.app.AppScreen
import com.ecency.core.app.CrashReport
import com.ecency.core.app.DemoStore
import com.ecency.core.app.NavState
import com.ecency.core.app.PurchaseCopy
import com.ecency.core.app.PurchaseService
import com.ecency.core.app.SignupPrefill
import com.ecency.core.app.StoreError
import com.ecency.core.app.StorePurchaseState
import com.ecency.core.secure.AuthType
import com.ecency.core.secure.KeyVault
import com.ecency.core.secure.VaultAccount
import io.sentry.Breadcrumb
import io.sentry.SentryEvent
import io.sentry.protocol.Message
import io.sentry.protocol.SentryException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** In-app purchases on Android against the offline demo store and a fake Ecency: nothing is bought or charged. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PurchaseFlowsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val node = TestNode()
    private val store = DemoStore(PurchaseService.PLAY)
    private val env by lazy {
        node.env().also {
            it.store = store
            it.captchaStandIn = "test-captcha"
        }
    }

    @Volatile private var orderStatus = 201

    @Volatile private var game = """{"status":18,"remaining":0,"next_date":"2026-10-07T12:00:00Z"}"""

    private val host = object : AppHost {
        override fun openExternal(url: String) {}
        override fun toast(text: String) {}
        override fun copy(text: String) {}
        override fun share(text: String) {}
        override fun openStoreForUpdate() {}
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
                r.url.endsWith("/private-api/purchase-order") -> HttpResult(orderStatus, "{}")
                r.url.endsWith("/private-api/get-game") -> HttpResult(200, game)
                r.url.endsWith("/private-api/quests") -> HttpResult(200, """{"daily":[],"weekly":[],"monthly":[]}""")
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

    private fun launch(navigator: Navigator) = compose.setContent { EcencyTheme(dark = false) { EcencyApp(env, host, navigator) } }

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(tag: String) = compose.await("a node tagged $tag") { exists(tag) }

    private fun text(tag: String): String =
        compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()

    private fun click(tag: String) {
        val node = compose.onNodeWithTag(tag, useUnmergedTree = true)
        // a list scrolls to it; a screen without scrolling has it on screen already
        runCatching { node.performScrollTo() }
        node.performClick()
    }

    private fun hasText(t: String) = compose.onAllNodes(androidx.compose.ui.test.hasText(t), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun orders() = node.auth.requests.filter { it.url.endsWith("/private-api/purchase-order") }

    private fun order(i: Int = 0) = Json.parseToJsonElement(orders()[i].body!!).jsonObject

    @Test
    fun getPointsListsThePacksBuysOneAndToldTheUser() {
        signIn()
        launch(Navigator(NavState().push(AppScreen.GetPoints())))
        waitFor("product-9999points")
        assertTrue(hasText("14000 POINTS") && hasText("BEST DEAL!") && hasText("POPULAR!") && hasText("FREE POINTS"))
        assertEquals("$99.99", text("price-9999points"))
        assertTrue("the free points row is last", exists("product-freePoints"))
        assertFalse("no ribbon for one's own purchase", exists("userRibbon"))
        click("buy-999points")
        compose.await("the order") { orders().isNotEmpty() }
        val o = order()
        assertEquals("play_store", o.getValue("platform").jsonPrimitive.content)
        assertEquals("999points", o.getValue("product").jsonPrimitive.content)
        assertEquals("tester", o.getValue("user").jsonPrimitive.content)
        assertEquals("access-tester", o.getValue("code").jsonPrimitive.content)
        compose.await("the success toast") { env.ui.current.toast?.text == PurchaseCopy.POINTS_SUCCESS }
    }

    @Test
    fun aPurchaseLinkAsksToConfirmForTheLinkedAccount() {
        signIn()
        launch(Navigator(NavState().push(AppScreen.GetPoints("spike-bob", "999points"))))
        waitFor("storeConfirmBody")
        assertEquals("Buying 1200 POINTS for spike-bob at USD 9.99", text("storeConfirmBody"))
        compose.onNodeWithTag("storeConfirm").performClick()
        compose.await("the order") { orders().isNotEmpty() }
        assertEquals("spike-bob", order().getValue("user").jsonPrimitive.content)
    }

    @Test
    fun theFreePointsRowOpensTheSpinGameAndThePackBuysSpins() {
        signIn()
        val navigator = Navigator(NavState().push(AppScreen.GetPoints()))
        launch(navigator)
        waitFor("product-freePoints")
        click("buy-freePoints")
        compose.await("the spin game") { navigator.top.screen == AppScreen.SpinGame }
        waitFor("product-499spins")
        val statusesBefore = node.auth.requests.count { it.url.endsWith("/get-game") }
        click("buy-499spins")
        compose.await("the order") { orders().isNotEmpty() }
        assertEquals("499spins", order().getValue("product").jsonPrimitive.content)
        compose.await("the status asked again") { node.auth.requests.count { it.url.endsWith("/get-game") } > statusesBefore }
    }

    @Test
    fun accountBoostSellsTheBoostRow() {
        signIn()
        launch(Navigator(NavState().push(AppScreen.AccountBoost())))
        waitFor("product-999boosts")
        // the drawn Esie, not RN's old mascot picture
        assertEquals(
            com.ecency.core.esie.EsieMood.THUMBS_UP,
            compose.onNodeWithTag("esie", useUnmergedTree = true).fetchSemanticsNode().config[com.ecency.app.ui.EsieMoodKey],
        )
        click("buy-999boosts")
        compose.await("the order") { orders().isNotEmpty() }
        assertEquals("999boosts", order().getValue("product").jsonPrimitive.content)
    }

    @Test
    fun aPendingPaymentIsNotPostedAndSaysSo() {
        signIn()
        store.mode = DemoStore.Mode.PENDING
        launch(Navigator(NavState().push(AppScreen.GetPoints())))
        waitFor("product-999points")
        click("buy-999points")
        waitFor("storeAlert")
        assertEquals(PurchaseCopy.PENDING, text("storeAlert"))
        assertTrue(orders().isEmpty())
        assertNull(env.ui.current.toast)
    }

    @Test
    fun aPurchaseLinkForAPaidAccountSellsOnlyThePaidAccount() {
        env.signupPrefill = SignupPrefill("spike-new", "new@example.com", "spike-bob", purchaseOnly = true)
        val navigator = Navigator(NavState().push(AppScreen.Register()))
        launch(navigator)
        waitFor("registerBuy")
        assertFalse("RN disableFree", exists("registerFree"))
        assertTrue(hasText("Buy Account - $9.99"))
        click("registerBuy")
        waitFor("registerDone")
        val o = order()
        assertEquals("999accounts", o.getValue("product").jsonPrimitive.content)
        assertEquals("ecency", o.getValue("user").jsonPrimitive.content)
        assertEquals("""{"username":"spike-new","email":"new@example.com","referral":"spike-bob"}""", o.getValue("meta").toString())
    }

    @Test
    fun playPurchasesMapToTheSharedModel() {
        fun purchase(state: Int, ack: Boolean) = Purchase(
            """{"orderId":"GPA.1","packageName":"app.esteem.mobile.android","productId":"999points","purchaseTime":1,"purchaseState":${if (state == 1) 0 else 4},""" +
                """"purchaseToken":"tok","quantity":1,"acknowledged":$ack}""",
            "sig",
        )
        val paid = PlayStore.purchasesOf(purchase(1, ack = false)).single()
        assertEquals(StorePurchaseState.PURCHASED, paid.state)
        assertEquals("999points", paid.productId)
        assertEquals("tok", paid.token)
        assertFalse(paid.acknowledged)
        val pending = PlayStore.purchasesOf(purchase(2, ack = false)).single()
        assertEquals("a pending Play purchase is never paid", StorePurchaseState.PENDING, pending.state)
        assertTrue(PlayStore.purchasesOf(purchase(1, ack = true)).single().acknowledged)
        assertEquals("no account identifiers: no token", "", paid.accountToken)
        val token = com.ecency.core.app.PurchaseService.accountToken("tester")
        val bound = Purchase(
            """{"orderId":"GPA.2","packageName":"app.esteem.mobile.android","productId":"1999pro","purchaseTime":1,"purchaseState":0,""" +
                """"purchaseToken":"tok2","quantity":1,"acknowledged":false,"obfuscatedAccountId":"${token.uppercase()}"}""",
            "sig",
        )
        assertEquals("the buyer from obfuscatedAccountId, lowercased", token, PlayStore.purchasesOf(bound).single().accountToken)

        assertNull(PlayStore.errorOf(BillingResult.newBuilder().setResponseCode(0).build()))
        val cancel = PlayStore.errorOf(BillingResult.newBuilder().setResponseCode(1).setDebugMessage("x").build())!!
        assertTrue(cancel.isUserCancelled)
        assertEquals(StoreError.USER_CANCELLED, cancel.code)
        assertTrue(PlayStore.errorOf(BillingResult.newBuilder().setResponseCode(3).build())!!.isBillingUnavailable)
        assertEquals(StoreError.ALREADY_OWNED, PlayStore.errorOf(BillingResult.newBuilder().setResponseCode(7).build())!!.code)
    }

    @Test
    fun sentryEventsAreScrubbedAndRnsBenignErrorsDropped() {
        val wif = "5JdeC9P7Pbd1uGdFVEsJ41EkEnADbbHGq6p1BwFxm6txNBsQnsw"
        val event = SentryEvent().apply {
            message = Message().apply { message = "key $wif and Bearer abcdefghijklmnop123" }
            exceptions = listOf(SentryException().apply { type = "IllegalStateException"; value = "memo={\"memo\":\"secret\"}" })
            breadcrumbs = listOf(Breadcrumb().apply { message = "GET https://ecency.com/x?code=abc"; setData("code", "abc"); setData("nested", mapOf("pin" to "1234", "ok" to "fine")) })
            setExtra("email", "someone@example.com")
            setTag("productId", "999points")
            contexts["iap"] = mapOf("message" to "failed for someone@example.com with Bearer abcdefghijklmnop123", "code" to "secret-code", "productId" to "999points")
        }
        val out = SentryReporting.beforeSend(event)!!
        assertEquals("key <redacted-key> and Bearer <redacted>", out.message!!.message)
        assertEquals("memo={\"memo\":\"<redacted>\"}", out.exceptions!!.single().value)
        val crumb = out.breadcrumbs!!.single()
        assertEquals("GET https://ecency.com/x?code=<redacted>", crumb.message)
        assertEquals("<redacted>", crumb.getData("code"))
        assertEquals(mapOf("pin" to "<redacted>", "ok" to "fine"), crumb.getData("nested"))
        assertEquals("<redacted>", out.extras!!["email"])
        assertEquals("999points", out.getTag("productId"))
        assertNull(out.user)
        assertEquals(
            mapOf("message" to "failed for <redacted-email> with Bearer <redacted>", "code" to "<redacted>", "productId" to "999points"),
            out.contexts["iap"],
        )

        val cancelled = SentryEvent().apply { exceptions = listOf(SentryException().apply { type = "JobCancellationException"; value = "Job was cancelled" }) }
        assertNull(SentryReporting.beforeSend(cancelled))
        val pin = SentryEvent().apply { exceptions = listOf(SentryException().apply { type = "Error"; value = "Please check your PIN" }) }
        assertNull(SentryReporting.beforeSend(pin))

        assertEquals("app.esteem.mobile.android@4.0.0+57", SentryReporting.release("app.esteem.mobile.android", "4.0.0", 57))
        val e = SentryReporting.Reporter.event(CrashReport("IapError", "IAP x: y", tags = mapOf("iap.stage" to "purchase"), fingerprint = listOf("iap", "x"), contextName = "iap", context = mapOf("code" to "x")))
        assertEquals(listOf("iap", "x"), e.fingerprints)
        assertEquals("purchase", e.getTag("iap.stage"))
        assertNotNull(e.contexts["iap"])
        assertEquals("IapError", e.exceptions!!.single().type)
    }

    @Test
    fun debugBuildsSendNoAnalyticsUnlessTheyNameAHost() {
        assertEquals("", analyticsHost("", debug = true))
        assertEquals("https://pl.ecency.com", analyticsHost("", debug = false))
        assertEquals("", analyticsHost("off", debug = false))
        assertEquals("https://stats.example.org", analyticsHost("https://stats.example.org", debug = true))
    }
}

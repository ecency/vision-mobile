package com.ecency.app

import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import com.ecency.app.ui.AppHost
import com.ecency.app.ui.DappWeb
import com.ecency.app.ui.EcencyApp
import com.ecency.app.ui.Navigator
import com.ecency.app.ui.QrDecoder
import com.ecency.core.app.AppScreen
import com.ecency.core.app.AppTab
import com.ecency.core.app.DappBrowserModel
import com.ecency.core.app.EditorKind
import com.ecency.core.app.QrScanModel
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull
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
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.Collections

/** The dApp browser and its bridge, the QR scanner, share intents and the proposal card, against a scripted node. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlatformFlowsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val node = TestNode()
    private val env by lazy { node.env() }
    private val navigator = Navigator()
    private val opened: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val host = object : AppHost {
        override fun openExternal(url: String) { opened += url }
        override fun toast(text: String) {}
        override fun copy(text: String) {}
    }

    @Before
    fun setUp() {
        acceptTerms()
        SingletonImageLoader.setUnsafe { ctx ->
            ImageLoader.Builder(ctx).components {
                add(object : Interceptor {
                    override suspend fun intercept(chain: Interceptor.Chain): ImageResult =
                        ErrorResult(null, chain.request, IllegalStateException("no network in tests"))
                })
            }.build()
        }
    }

    @After
    fun tearDown() = SingletonImageLoader.reset()

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun idleMain() = Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

    private fun waitFor(tag: String) {
        compose.await("exists($tag)") { idleMain(); exists(tag) }
        compose.waitForIdle()
    }

    private fun settle() {
        compose.mainClock.advanceTimeBy(2_000)
        idleMain()
        compose.waitForIdle()
    }

    /** A tap inside a modal sheet: its window takes no injected touches under Robolectric. */
    private fun tap(tag: String) {
        settle()
        compose.onNodeWithTag(tag).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        settle()
    }

    private fun launchSignedIn() {
        node.signIn()
        compose.setContent { EcencyApp(env, host, navigator) }
        compose.await("signed in") { idleMain(); env.session.current.username == "tester" }
        waitFor("card:alice/trending-one")
    }

    private fun push(screen: AppScreen) = compose.runOnUiThread { navigator.push(screen) }

    private fun browser(): DappBrowserModel = navigator.top.peek<DappBrowserModel>("browser")!!

    private class Page {
        val answers: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val reply = DappBrowserModel.BridgeReply { answers += it }
        fun lastError(): String? = answers.lastOrNull()?.let { Json.parseToJsonElement(it).jsonObject["response"]!!.jsonObject["error"]?.jsonPrimitive?.contentOrNull }
    }

    // ---- the browser ----

    @Test
    fun exploreOpensTheBrowserHomeAndADappOpensInTheTab() {
        launchSignedIn()
        push(AppScreen.Explore)
        waitFor("dapp:PeakD")
        compose.onNodeWithTag("dapp:PeakD").performClick()
        compose.await("tab on peakd") { idleMain(); browser().current.activeTab.url == "https://peakd.com" }
        waitFor("dappPage")
        waitFor("browserUrl")
        assertTrue(exists("browserLock"))
        compose.onNodeWithTag("browserAddTab").performClick()
        compose.await("two tabs") { idleMain(); browser().current.tabs.size == 2 }
        waitFor("dapp:Ecency")
        val first = browser().current.tabs.first().id
        compose.onNodeWithTag("browserTab:$first").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.await("first tab") { idleMain(); browser().current.activeTabId == first }
        compose.onNodeWithTag("browserCloseTab:$first", useUnmergedTree = true).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.await("one tab") { idleMain(); browser().current.tabs.size == 1 }
    }

    @Test
    fun theWebViewIsLockedDown() {
        launchSignedIn()
        push(AppScreen.DappBrowser("https://dapp.example/"))
        waitFor("dappPage")
        val model = browser()
        val view = WebView(compose.activity)
        DappWeb.create(compose.activity, model.current.activeTabId, model).let { web ->
            val s = web.settings
            assertTrue(s.javaScriptEnabled)
            assertFalse(s.allowFileAccess)
            assertFalse(s.allowContentAccess)
            assertEquals(WebSettings.MIXED_CONTENT_NEVER_ALLOW, s.mixedContentMode)
            assertFalse(s.javaScriptCanOpenWindowsAutomatically)
            val client = Shadows.shadowOf(web).webViewClient
            fun request(url: String, gesture: Boolean = true) = object : WebResourceRequest {
                override fun getUrl(): Uri = Uri.parse(url)
                override fun isForMainFrame() = true
                override fun isRedirect() = false
                override fun hasGesture() = gesture
                override fun getMethod() = "GET"
                override fun getRequestHeaders(): Map<String, String> = emptyMap()
            }
            assertTrue("javascript: is blocked", client.shouldOverrideUrlLoading(web, request("javascript:alert(document.cookie)")))
            assertTrue("file: is blocked", client.shouldOverrideUrlLoading(web, request("file:///data/data/app.esteem.mobile.android/shared_prefs/x.xml")))
            assertTrue("intent: is blocked", client.shouldOverrideUrlLoading(web, request("intent://x#Intent;end")))
            assertFalse("https loads", client.shouldOverrideUrlLoading(web, request("https://dapp.example/next")))
            web.destroy()
        }
        view.destroy()
    }

    @Test
    fun aBridgeRequestIsConfirmedInTheSheetBeforeAnythingIsSigned() {
        launchSignedIn()
        push(AppScreen.DappBrowser("https://dapp.example/"))
        waitFor("dappPage")
        val model = browser()
        val page = Page()
        compose.runOnUiThread {
            model.bridgeMessage(
                model.current.activeTabId, "https://dapp.example", true,
                """{"name":"swRequest_hive","request_id":3,"data":{"type":"vote","username":"tester","author":"alice","permlink":"trending-one","weight":10000,"domain":"https://ecency.com"}}""",
                page.reply,
            )
        }
        waitFor("bridgeConfirm")
        compose.onNodeWithTag("bridgeHost", useUnmergedTree = true).assertTextContains("dapp.example")
        compose.onNodeWithTag("bridgeAuthority", useUnmergedTree = true).assertTextContains("POSTING")
        assertTrue(node.broadcasts().isEmpty())
        tap("bridgeConfirmButton")
        compose.await("broadcast") { idleMain(); node.broadcasts().size == 1 }
        compose.await("answered") { idleMain(); page.answers.isNotEmpty() }
        assertNull(page.lastError())
        assertFalse(exists("bridgeConfirm"))
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE) // real font metrics
    fun aLongFieldIsShownWholeOnTheSheet() {
        launchSignedIn()
        push(AppScreen.DappBrowser("https://dapp.example/"))
        waitFor("dappPage")
        val model = browser()
        val json = " ".repeat(2_001) + """{"note":"${"x".repeat(1_500)}","to":"spike-mallory","quantity":"1000"}"""
        compose.runOnUiThread {
            model.bridgeMessage(
                model.current.activeTabId, "https://dapp.example", true,
                """{"name":"swRequest_hive","request_id":5,"data":{"type":"custom","id":"ssc-mainnet-hive","json":${kotlinx.serialization.json.JsonPrimitive(json)}}}""",
                Page().reply,
            )
        }
        waitFor("bridgeConfirm")
        val field = compose.onNodeWithTag("bridgeField-json", useUnmergedTree = true)
        field.performScrollTo()
        field.assertTextContains("spike-mallory", substring = true)
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        field.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertFalse("the value is not clipped (${layout.lineCount} lines)", layout.hasVisualOverflow || layout.isLineEllipsized(layout.lineCount - 1))
        assertTrue("the value runs past four lines", layout.lineCount > 4)
        assertTrue("its end is laid out", layout.layoutInput.text.text.endsWith("\"quantity\":\"1000\"}"))
    }

    @Test
    fun cancellingTheSheetRejectsTheRequest() {
        launchSignedIn()
        push(AppScreen.DappBrowser("https://dapp.example/"))
        waitFor("dappPage")
        val model = browser()
        val page = Page()
        compose.runOnUiThread {
            model.bridgeMessage(
                model.current.activeTabId, "https://dapp.example", true,
                """{"name":"swRequest_hive","request_id":4,"data":{"type":"vote","author":"alice","permlink":"trending-one","weight":100}}""",
                page.reply,
            )
        }
        waitFor("bridgeConfirm")
        tap("bridgeCancel")
        compose.await("rejected") { idleMain(); page.lastError() == DappBrowserModel.REJECTED }
        assertTrue(node.broadcasts().isEmpty())
    }

    @Test
    fun aPhishingDomainIsFlaggedOnTheSheet() {
        launchSignedIn()
        push(AppScreen.DappBrowser("https://leodefi.blogspot.com/"))
        waitFor("dappPage")
        val model = browser()
        compose.runOnUiThread {
            model.bridgeMessage(
                model.current.activeTabId, "https://leodefi.blogspot.com", true,
                """{"name":"swRequest_hive","request_id":1,"data":{"type":"vote","author":"alice","permlink":"trending-one","weight":100}}""",
                Page().reply,
            )
        }
        waitFor("bridgePhishing")
    }

    @Test
    fun onlyACommittedPageNamesTheTab() {
        launchSignedIn()
        push(AppScreen.DappBrowser("https://evil.example/"))
        waitFor("dappPage")
        val model = browser()
        val tab = model.current.activeTabId
        compose.runOnUiThread {
            val web = DappWeb.create(compose.activity, tab, model)
            val client = Shadows.shadowOf(web).webViewClient
            client.doUpdateVisitedHistory(web, "https://evil.example/", false)
            // a navigation that only started (the old document still runs) is no committed page
            client.onPageStarted(web, "https://ecency.com:8443/", null)
            web.destroy()
        }
        idleMain()
        assertEquals("https://evil.example/", model.current.activeTab.committedUrl)
        assertEquals("https://ecency.com:8443/", model.current.activeTab.url)
    }

    @Test
    fun aFramesMessageNeverReachesTheModel() {
        assertNull(DappWeb.mainFrameMessage(isMainFrame = false, data = "{}"))
        assertEquals("{}", DappWeb.mainFrameMessage(isMainFrame = true, data = "{}"))
    }

    // ---- QR ----

    @Test
    fun theDecoderReadsARenderedCode() {
        val text = "hive://sign/op/WyJ2b3RlIix7fV0."
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 240, 240)
        val luminance = ByteArray(matrix.width * matrix.height) { i -> if (matrix.get(i % matrix.width, i / matrix.width)) 0 else 255.toByte() }
        assertEquals(text, QrDecoder.decode(luminance, matrix.width, matrix.height))
        // rows with padding (a camera's row stride)
        val stride = matrix.width + 16
        val padded = ByteArray(stride * matrix.height).also { out -> for (y in 0 until matrix.height) System.arraycopy(luminance, y * matrix.width, out, y * stride, matrix.width) }
        assertEquals(text, QrDecoder.decode(padded, matrix.width, matrix.height, stride))
        assertNull(QrDecoder.decode(ByteArray(100 * 100) { 127 }, 100, 100))
    }

    @Test
    fun theScannerAsksForTheCameraAndShowsRnsAlertWhenRefused() {
        launchSignedIn()
        compose.onNodeWithTag("headerQr").performClick()
        waitFor("qrScan")
        val activity = Shadows.shadowOf(compose.activity)
        compose.await("asked") { idleMain(); activity.lastRequestedPermission != null }
        val request = activity.lastRequestedPermission!!
        assertEquals(listOf(android.Manifest.permission.CAMERA), request.requestedPermissions.toList())
        compose.runOnUiThread {
            compose.activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions, intArrayOf(android.content.pm.PackageManager.PERMISSION_DENIED))
        }
        waitFor("qrPermission")
        compose.onNodeWithTag("qrPermission", useUnmergedTree = true).assertTextContains(QrScanModel.PERMISSION_MESSAGE)
    }

    @Test
    fun aScannedContentLinkClosesTheScannerAndOpensIt() {
        launchSignedIn()
        push(AppScreen.QrScan)
        waitFor("qrScan")
        val model = navigator.top.peek<QrScanModel>("qr")!!
        compose.runOnUiThread { model.scanned("https://ecency.com/@spike-bob") }
        compose.await("profile") { idleMain(); navigator.top.screen == AppScreen.Profile("spike-bob") }
    }

    @Test
    fun aScannedWebPageOpensInTheBrowserAndJunkIsUnsupported() {
        launchSignedIn()
        push(AppScreen.QrScan)
        waitFor("qrScan")
        compose.runOnUiThread { navigator.top.peek<QrScanModel>("qr")!!.scanned("https://example.com/x") }
        compose.await("opened") { idleMain(); opened == listOf("https://example.com/x") }
        push(AppScreen.QrScan)
        waitFor("qrScan")
        compose.runOnUiThread { navigator.top.peek<QrScanModel>("qr")!!.scanned("not a link") }
        waitFor("qrUnsupported")
    }

    @Test
    fun theTransferRecipientHasAScanButton() {
        launchSignedIn()
        push(AppScreen.Transfer("transfer", "HIVE", "hive"))
        waitFor("transferScan")
        compose.onNodeWithTag("transferScan").performClick()
        waitFor("transferQr")
    }

    // ---- share ----

    @Test
    fun shareIntentsTakeTextAndOtherAppsImagesOnly() {
        val context = compose.activity
        fun send(type: String, vararg extras: Pair<String, Any>) = Intent(Intent.ACTION_SEND).setType(type).apply {
            extras.forEach { (k, v) -> if (v is Uri) putExtra(k, v) else putExtra(k, v.toString()) }
        }
        val text = ShareIntents.parse(context, send("text/plain", Intent.EXTRA_TEXT to "hello https://ecency.com"))!!
        assertEquals(listOf("hello https://ecency.com"), text.texts)
        val photo = Uri.parse("content://media.example/photos/1")
        assertEquals(listOf(photo), ShareIntents.parse(context, send("image/jpeg", Intent.EXTRA_STREAM to photo))!!.images)
        // never this app's own files: not by path, not through its own provider
        assertNull(ShareIntents.parse(context, send("image/jpeg", Intent.EXTRA_STREAM to Uri.parse("file:///data/data/app.esteem.mobile.android/shared_prefs/ecency.secure.xml"))))
        assertNull(ShareIntents.parse(context, send("image/png", Intent.EXTRA_STREAM to Uri.parse("content://${context.packageName}.editorfiles/camera/x.jpg"))))
        val multiple = Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/*")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(photo, Uri.parse("file:///sdcard/a.jpg"), Uri.parse("content://other.example/2")))
        assertEquals(listOf(photo, Uri.parse("content://other.example/2")), ShareIntents.parse(context, multiple)!!.images)
        assertNull(ShareIntents.parse(context, Intent(Intent.ACTION_VIEW, Uri.parse("https://ecency.com"))))
    }

    @Test
    fun aSharedTextOpensTheChooserThenTheEditorWithIt() {
        launchSignedIn()
        compose.runOnUiThread { env.share.receive(listOf("Shared from another app"), emptyList()) }
        waitFor("shareChooser")
        tap("shareBlog")
        compose.await("editor") { idleMain(); (navigator.top.screen as? AppScreen.Editor)?.args?.kind == EditorKind.NEW_POST }
        waitFor("editorBody")
        compose.await("text in the body") {
            idleMain()
            compose.onAllNodes(hasText("Shared from another app", substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun aSharedTextAsAWaveGoesToTheWavesTab() {
        launchSignedIn()
        compose.runOnUiThread { env.share.receive(listOf("A quick update"), emptyList()) }
        waitFor("shareChooser")
        tap("shareWave")
        compose.await("waves tab") { idleMain(); navigator.state.tab == AppTab.WAVES }
    }

    // ---- proposal card ----

    @Test
    fun theProposalCardVotesThroughTheActiveKeyPath() {
        node.extra = { method, _ ->
            when (method) {
                "database_api.list_proposals" -> """{"proposals":[{"id":1,"proposal_id":342,"creator":"ecency","status":"active"}]}"""
                "database_api.list_proposal_votes" -> """{"proposal_votes":[]}"""
                else -> null
            }
        }
        launchSignedIn()
        waitFor("proposalCard")
        compose.onNodeWithTag("proposalTitle", useUnmergedTree = true).assertTextContains("Enjoying Ecency!")
        compose.onNodeWithTag("proposalVote").performClick()
        // a posting-key account: the active key is asked for (auth upgrade); nothing is broadcast meanwhile
        waitFor("authUpgrade")
        assertTrue(node.broadcasts().isEmpty())
    }

    @Test
    fun dismissingTheProposalCardForever() {
        node.extra = { method, _ ->
            when (method) {
                "database_api.list_proposals" -> """{"proposals":[{"id":1,"proposal_id":342,"creator":"ecency","status":"active"}]}"""
                "database_api.list_proposal_votes" -> """{"proposal_votes":[]}"""
                else -> null
            }
        }
        launchSignedIn()
        waitFor("proposalCard")
        compose.onNodeWithTag("proposalDismiss").performClick()
        waitFor("proposalDismissSheet")
        tap("proposalForever")
        compose.await("hidden") { idleMain(); !exists("proposalCard") }
        assertNotNull(env.prefs.keys().firstOrNull { it.startsWith(com.ecency.core.app.ProposalVoteModel.KEY_PREFIX) })
    }
}

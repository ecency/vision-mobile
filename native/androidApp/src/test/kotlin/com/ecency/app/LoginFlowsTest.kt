package com.ecency.app

import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import com.ecency.app.ui.AppHost
import com.ecency.app.ui.EcencyApp
import com.ecency.app.ui.EcencyTheme
import com.ecency.core.app.LoginCopy
import com.ecency.app.ui.Navigator
import com.ecency.core.app.AppScreen
import com.ecency.core.app.NavState
import com.ecency.app.ui.qrBitmap
import com.ecency.core.api.broadcast.OperationJson
import com.ecency.core.app.AppError
import com.ecency.core.app.AuthLinkModel
import com.ecency.core.app.LinkPrompt
import com.ecency.core.app.SigningModel
import com.ecency.core.auth.HiveUri
import com.ecency.core.hive.Asset
import com.ecency.core.hive.Transfer
import com.ecency.core.hive.Vote
import com.ecency.core.secure.AuthType
import com.ecency.core.secure.KeyVault
import com.ecency.core.secure.VaultAccount
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
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

/** Login and accounts on Android: form, errors, switcher, logout confirm, signing and link sheets, link intents. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LoginFlowsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val node = TestNode()
    private val env by lazy { node.env() }
    private val opened = mutableListOf<String>()
    private val callbacks = mutableListOf<String>()
    private var terms = true

    private val host = object : AppHost {
        override fun openExternal(url: String) { opened += url }
        override fun toast(text: String) {}
        override fun copy(text: String) {}
        override fun openCallback(url: String) { callbacks += url }
        override fun termsAccepted() = terms
        override fun setTermsAccepted() { terms = true }
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
    }

    @After
    fun tearDown() {
        SingletonImageLoader.reset()
        EcencyGraph.install(null)
    }

    /** The account switcher, from the side menu header (RN accounts sheet). */
    private fun openAccounts() {
        compose.onNodeWithTag("account").performClick()
        waitFor("drawerAccounts")
        compose.waitForIdle()
        compose.onNodeWithTag("drawerAccounts", useUnmergedTree = true).performClick()
    }

    private fun launch(navigator: Navigator = Navigator()) = compose.setContent { EcencyTheme(dark = false) { EcencyApp(env, host, navigator) } }

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(tag: String) = until { exists(tag) }

    /** Sheets animate in: let the clock run, then click through the semantics action (as SliceFlowsTest does). */
    private fun tapInSheet(tag: String) {
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        compose.onNodeWithTag(tag, useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
    }

    /** waitUntil does not always drain Robolectric's main looper; idling between checks does. */
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

    private fun store(vararg accounts: VaultAccount) = KeyVault(node.store).run {
        accounts.forEach { save(it) }
        setCurrentUsername(accounts.last().username)
    }

    private fun tester(name: String = "tester") = VaultAccount(name, AuthType.POSTING_KEY, postingKey = node.key.toWif(), accessToken = "access-$name")

    @Test
    fun loginNeedsBothFieldsAndShowsRnErrors() {
        launch(Navigator(NavState().push(AppScreen.Login)))
        waitFor("login")
        compose.onNodeWithTag("loginSubmit").assertIsNotEnabled()
        compose.onNodeWithTag("loginUser").performTextInput("tester")
        compose.onNodeWithTag("loginSubmit").assertIsNotEnabled()
        compose.onNodeWithTag("loginKey").performTextInput("wrong password")
        compose.onNodeWithTag("loginSubmit").assertIsEnabled().performClick()
        waitFor("loginError")
        assertTrue(compose.onAllNodes(hasText(AppError.INVALID_CREDENTIALS, substring = true)).fetchSemanticsNodes().isNotEmpty())
        assertTrue(compose.onAllNodes(hasText(LoginCopy.FAILED_TITLE)).fetchSemanticsNodes().isNotEmpty())
        assertTrue(node.store.names().none { it.startsWith("account.") })
    }

    @Test
    fun aPostingKeyLoginStoresTheAccountAndItsSession() {
        launch(Navigator(NavState().push(AppScreen.Login)))
        waitFor("login")
        compose.onNodeWithTag("loginUser").performTextInput("@Tester")
        compose.onNodeWithTag("loginKey").performTextInput(node.key.toWif())
        compose.onNodeWithTag("loginSubmit").performClick()
        until { !exists("login") }
        val stored = KeyVault(node.store).account("tester")!!
        assertEquals(AuthType.POSTING_KEY, stored.authType)
        assertEquals("access-tester", stored.accessToken)
        assertEquals("tester", env.session.current.username)
    }

    /** RN #3600: the keys screen shows and copies public keys; pasting one at sign-in names it. */
    @Test
    fun aPastedPublicKeyAtLoginIsNamedNotInvalidCredentials() {
        launch(Navigator(NavState().push(AppScreen.Login)))
        waitFor("login")
        compose.onNodeWithTag("loginUser").performTextInput("tester")
        compose.onNodeWithTag("loginKey").performTextInput("${node.key.publicKey()}\u200B")
        compose.onNodeWithTag("loginSubmit").performClick()
        waitFor("loginError")
        assertTrue(compose.onAllNodes(hasText(AppError.PUBLIC_KEY_GIVEN, substring = true)).fetchSemanticsNodes().isNotEmpty())
        assertTrue(compose.onAllNodes(hasText(AppError.INVALID_CREDENTIALS, substring = true)).fetchSemanticsNodes().isEmpty())
        assertTrue(node.store.names().none { it.startsWith("account.") })
    }

    /** Invisible characters alone do not fill the form: the button stays off (review of RN #3600). */
    @Test
    fun invisibleOnlyInputKeepsLoginDisabled() {
        launch(Navigator(NavState().push(AppScreen.Login)))
        waitFor("login")
        compose.onNodeWithTag("loginUser").performTextInput("\u200B\u200B")
        compose.onNodeWithTag("loginKey").performTextInput("\u200B\u00A0\uFEFF")
        compose.onNodeWithTag("loginSubmit").assertIsNotEnabled()
        compose.onNodeWithTag("loginUser").performTextInput("tester")
        compose.onNodeWithTag("loginSubmit").assertIsNotEnabled()
        compose.onNodeWithTag("loginKey").performTextInput("pw")
        compose.onNodeWithTag("loginSubmit").assertIsEnabled()
    }

    /** RN #3600: chat apps add NBSP and zero-width characters around a copied key. */
    @Test
    fun aKeyCopiedFromAChatAppSignsIn() {
        launch(Navigator(NavState().push(AppScreen.Login)))
        waitFor("login")
        compose.onNodeWithTag("loginUser").performTextInput("\u200Btester")
        compose.onNodeWithTag("loginKey").performTextInput("\u00A0${node.key.toWif()}\u200B")
        compose.onNodeWithTag("loginSubmit").performClick()
        until { !exists("login") }
        assertEquals(node.key.toWif(), KeyVault(node.store).account("tester")!!.postingKey)
        assertEquals("tester", env.session.current.username)
    }

    @Test
    fun hiveAuthShowsTheWalletRequestAsAQrCode() {
        launch(Navigator(NavState().push(AppScreen.Login)))
        waitFor("login")
        compose.onNodeWithTag("loginHiveAuth").performScrollTo().performClick()
        waitFor("hiveAuthSheet")
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithTag("hiveAuthUser", useUnmergedTree = true).performTextInput("tester")
        tapInSheet("hiveAuthStart")
        waitFor("hiveAuthQr")
        assertTrue(exists("hiveAuthOpenWallet"))

        tapInSheet("hiveAuthCancel")
        until { env.session.current.hiveAuth == null }
        assertTrue(node.store.names().none { it.startsWith("account.") })
    }

    @Test
    fun qrBitmapEncodesTheLink() {
        val bitmap = qrBitmap("has://auth_req/eyJhY2NvdW50IjoidGVzdGVyIn0=", 256)
        assertEquals(256, bitmap.width)
    }

    @Test
    fun aToastPostedWhileTheAccountSwitcherIsOpenShowsAboveIt() {
        store(tester("tester"), tester("tester-two"))
        launch()
        waitFor("account")
        openAccounts()
        waitFor("accountSwitcher")
        // the switcher's error toasts (a failed switch or logout) go through the shared channel
        compose.runOnUiThread { env.ui.toast("Could not switch the account") }
        waitFor("toast")
        assertEquals("the toast's window is the topmost, above the sheet's", "EcencyToast", topWindowTitles().last())
        assertTrue("the sheet is still open under it", exists("accountSwitcher"))
    }

    /** The titles of the app's windows, in the order they stack (WindowManagerGlobal keeps them in add order). */
    private fun topWindowTitles(): List<String> {
        val global = Class.forName("android.view.WindowManagerGlobal")
        val instance = global.getMethod("getInstance").invoke(null)
        val params = global.getDeclaredField("mParams").apply { isAccessible = true }.get(instance) as List<*>
        return params.map { (it as android.view.WindowManager.LayoutParams).title.toString() }
    }

    @Test
    fun accountSwitcherSwitchesAndLogoutAsksFirst() {
        store(tester("tester"), tester("tester-two"))
        launch()
        waitFor("account")
        openAccounts()
        waitFor("accountSwitcher")
        assertTrue(exists("account:tester"))
        assertTrue(exists("account:tester-two"))
        assertEquals(1, compose.onAllNodes(hasTestTag("currentMark"), useUnmergedTree = true).fetchSemanticsNodes().size)

        tapInSheet("account:tester")
        until { env.session.current.username == "tester" }

        openAccounts()
        waitFor("logout:tester-two")
        tapInSheet("logout:tester-two")
        waitFor("logoutConfirm")
        compose.onNodeWithTag("logoutCancel").performClick()
        assertNotNull(KeyVault(node.store).account("tester-two"))
        tapInSheet("logout:tester-two")
        compose.onNodeWithTag("logoutConfirm").performClick()
        until { KeyVault(node.store).account("tester-two") == null }
        assertEquals("tester", env.session.current.username)
    }

    @Test
    fun authUpgradeSheetChecksTheKeyAgainstTheChain() {
        store(tester())
        launch()
        waitFor("account")
        val ops = JsonArray(listOf(OperationJson.encode(Transfer("tester", "bob", Asset.parse("1.000 HIVE"), ""))))
        var outcome: Result<*>? = null
        CoroutineScope(Dispatchers.Main).launch { outcome = runCatching { env.signing.broadcast("tester", ops) } }
        waitFor("authUpgrade")
        compose.onNodeWithTag("upgradeKey", useUnmergedTree = true).performTextInput(node.key.toWif()) // the posting key
        tapInSheet("upgradeSign")
        waitFor("upgradeError")
        // RN #3600: a private key of another role is named
        assertTrue(compose.onAllNodes(hasText(SigningModel.wrongRole(com.ecency.core.hive.KeyRole.POSTING, com.ecency.core.hive.KeyRole.ACTIVE)), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        tapInSheet("upgradeCancel")
        until { outcome != null }
        assertTrue(outcome!!.isFailure)
        assertTrue(node.broadcasts().isEmpty())
    }

    /** RN #3600 on the "Authorization Required" sheet: a public key with a zero-width space is still named. */
    @Test
    fun authUpgradeSheetNamesAPastedPublicKey() {
        store(tester())
        launch()
        waitFor("account")
        val ops = JsonArray(listOf(OperationJson.encode(Transfer("tester", "bob", Asset.parse("1.000 HIVE"), ""))))
        var outcome: Result<*>? = null
        CoroutineScope(Dispatchers.Main).launch { outcome = runCatching { env.signing.broadcast("tester", ops) } }
        waitFor("authUpgrade")
        compose.onNodeWithTag("upgradeKey", useUnmergedTree = true).performTextInput("${node.key.publicKey()}\u200B")
        tapInSheet("upgradeSign")
        waitFor("upgradeError")
        assertTrue(compose.onAllNodes(hasText(AppError.PUBLIC_KEY_GIVEN), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        tapInSheet("upgradeCancel")
        until { outcome != null }
        assertTrue(node.broadcasts().isEmpty())
    }

    @Test
    fun aLongMemoCannotPushTheRecipientAndAmountOutOfView() {
        // a transfer needs the active key
        store(tester().copy(authType = AuthType.MASTER_PASSWORD, activeKey = node.key.toWif()))
        launch()
        waitFor("account")
        val link = HiveUri.encodeOps(
            HiveUri.ops(
                "transfer" to buildJsonObject {
                    put("memo", JsonPrimitive("\n".repeat(500) + "x".repeat(1_500))); put("from", JsonPrimitive("__signer"))
                    put("to", JsonPrimitive("spike-mallory")); put("amount", JsonPrimitive("1000.000 HIVE"))
                },
            ),
        )
        compose.runOnIdle { env.authLinks.handle(link) }
        waitFor("signConfirm")
        compose.onNodeWithTag("signField-to", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("signField-amount", useUnmergedTree = true).assertIsDisplayed()
        tapInSheet("signCancel")
    }

    @Test
    fun signConfirmShowsTheOperationAndOwnerOperationsAreRefused() {
        store(tester())
        launch()
        waitFor("account")
        val vote = HiveUri.encodeOps(JsonArray(listOf(OperationJson.encode(Vote("__signer", "alice", "trending-one", 10000)))), HiveUri.Params(callback = "otherapp://done"))
        compose.runOnIdle { env.authLinks.handle(vote) }
        waitFor("signConfirm")
        assertTrue(compose.onAllNodes(hasText("POSTING"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        tapInSheet("signCancel")
        until { callbacks.isNotEmpty() }
        assertTrue("user_cancelled" in callbacks.single())
        assertTrue(node.broadcasts().isEmpty())

        val owner = HiveUri.encodeOps(HiveUri.ops("change_recovery_account" to buildJsonObject {
            put("account_to_recover", JsonPrimitive("tester")); put("new_recovery_account", JsonPrimitive("bob")); put("extensions", JsonArray(emptyList()))
        }))
        compose.runOnIdle { env.authLinks.handle(owner) }
        waitFor("linkRefused")
        assertTrue(compose.onAllNodes(hasText(AuthLinkModel.OWNER_REFUSED), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        assertFalse(exists("signConfirm"))
    }

    @Test
    fun aPostingKeyRequestIsAnsweredOnlyAfterApprove() {
        store(tester())
        launch()
        waitFor("account")
        compose.runOnIdle { env.authLinks.handle("ecency://login?username=tester&callback=otherapp://cb") }
        waitFor("postingKeyRequest")
        assertTrue(callbacks.isEmpty())
        compose.onNodeWithTag("linkApprove").performClick()
        until { callbacks.isNotEmpty() }
        assertTrue("posting_key=${node.key.toWif()}" in callbacks.single())
    }

    @Test
    fun welcomeNeedsTheTermsFirst() {
        terms = false
        launch()
        waitFor("welcome")
        assertTrue("Esie in place of RN's mascot art", exists("esie"))
        assertFalse(exists("account"))
        compose.onNodeWithTag("welcomeStart").assertIsNotEnabled()
        compose.onNodeWithTag("welcomeTerms").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag("welcomeStart").assertIsEnabled().performClick()
        waitFor("account")
        assertTrue(terms)
    }

    @Test
    fun backupKeysAsksForAPinFirstWhenTheLockIsOff() {
        store(tester())
        launch(Navigator(NavState().push(AppScreen.BackupKeys)))
        waitFor("keysWarning")
        assertFalse(exists("key:POSTING"))
    }

    @Test
    fun viewIntentsReachTheLinkModel() {
        EcencyGraph.install(env)
        acceptTerms()
        store(tester())
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("ecency://auth-transfer?username=tester&accessToken=a&refreshToken=refresh-tester"))
            .setClass(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
        ActivityScenario.launch<MainActivity>(intent).use {
            until { env.authLinks.current.prompt != null }
            val prompt = env.authLinks.current.prompt as LinkPrompt.AuthTransfer
            assertEquals("tester", prompt.username)
            assertTrue(prompt.accountStored)
            assertNull(env.authLinks.current.callback)
        }
    }

    @Test
    fun aPinConfirmationShowsInItsOwnWindowAboveAnOpenSheet() {
        store(tester("tester"), tester("tester-two"))
        com.ecency.core.secure.PinLock(KeyVault(node.store), 16).setPin("1234")
        launch()
        waitFor("pinScreen") // cold start locks
        "1234".forEach { d -> compose.onNodeWithTag("pinKey$d", useUnmergedTree = true).performClick() }
        until { env.appLock.current.prompt == null }
        openAccounts()
        waitFor("accountSwitcher")
        var verified: Boolean? = null
        CoroutineScope(Dispatchers.Main).launch { verified = env.appLock.verifyPin(false) }
        waitFor("pinScreen")
        val inDialog = compose.onAllNodes(hasTestTag("pinScreen") and androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.isDialog()), useUnmergedTree = true)
            .fetchSemanticsNodes().isNotEmpty()
        assertTrue("the pad is a dialog window of its own, above the sheet", inDialog)
        "1234".forEach { d -> compose.onNodeWithTag("pinKey$d", useUnmergedTree = true).performClick() }
        until { verified != null }
        assertEquals(true, verified)
    }

    @Test
    fun theWalletOpensOncePerHiveAuthRequest() {
        var opened = 0
        val counting = object : AppHost by host {
            override fun openWallet(link: String): Boolean { opened++; return false }
        }
        val navigator = Navigator(NavState().push(AppScreen.Login))
        val show = androidx.compose.runtime.mutableStateOf(true)
        compose.setContent { EcencyTheme(dark = false) { if (show.value) EcencyApp(env, counting, navigator) } }
        waitFor("login")
        compose.runOnIdle { env.session.startHiveAuth("tester") }
        compose.onNodeWithTag("loginHiveAuth").performScrollTo().performClick()
        waitFor("hiveAuthQr")
        until { opened == 1 }
        // the whole UI goes away and comes back (relock, recreation): the wallet is not opened again
        compose.runOnIdle { show.value = false }
        compose.waitForIdle()
        compose.runOnIdle { show.value = true }
        waitFor("login")
        compose.onNodeWithTag("loginHiveAuth").performScrollTo().performClick()
        waitFor("hiveAuthQr")
        compose.waitForIdle()
        assertEquals(1, opened)
        compose.runOnIdle { env.session.cancelHiveAuth() }
    }

    @Test
    fun manageAuthoritiesIsReachedFromSettings() {
        store(tester())
        launch()
        waitFor("account")
        compose.onNodeWithTag("account").performClick()
        waitFor("menu:settings")
        compose.waitForIdle()
        compose.onNodeWithTag("menu:settings").apply { runCatching { performScrollTo() } }.performClick() // pinned, or in the one scroll on a short screen
        waitFor("menuBackupKeys")
        compose.onNodeWithTag("menuBackupKeys").performScrollTo().performClick()
        waitFor("backupKeys")
    }
}

package com.ecency.app

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import com.ecency.app.ui.AppHost
import com.ecency.app.ui.EcencyApp
import com.ecency.app.ui.EcencyTheme
import com.ecency.app.ui.Navigator
import com.ecency.core.app.AppScreen
import com.ecency.core.app.NavState
import com.ecency.core.secure.AuthType
import com.ecency.core.secure.KeyVault
import com.ecency.core.secure.PinLock
import com.ecency.core.secure.VaultAccount
import kotlinx.coroutines.launch
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

/** The keys screen ("Manage Authorities") on Android: the gate, reveal, both clipboards, import errors, closing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KeysFlowsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val node = TestNode()
    private val env by lazy { node.env() }
    private val copied = mutableListOf<String>()
    private val copiedSensitive = mutableListOf<String>()
    private val sensitiveScreen = mutableListOf<Boolean>()
    private val navigator by lazy { Navigator(NavState().push(AppScreen.Settings).push(AppScreen.BackupKeys)) }

    private val host = object : AppHost {
        override fun openExternal(url: String) {}
        override fun toast(text: String) {}
        override fun copy(text: String) { copied += text }
        override fun copySensitive(text: String) { copiedSensitive += text }
        override fun setSensitiveScreen(sensitive: Boolean) { sensitiveScreen += sensitive }
        override fun isChangingConfigurations() = changingConfigurations
    }

    @Volatile private var changingConfigurations = false

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
    fun tearDown() = SingletonImageLoader.reset()

    private fun signIn(pin: String? = "1234") {
        val vault = KeyVault(node.store)
        vault.save(VaultAccount("tester", AuthType.POSTING_KEY, postingKey = node.key.toWif(), accessToken = "access-tester"))
        vault.setCurrentUsername("tester")
        if (pin != null) PinLock(vault, 16).setPin(pin)
    }

    private fun launch() = compose.setContent { EcencyTheme(dark = false) { EcencyApp(env, host, navigator) } }

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

    private fun type(pin: String) {
        pin.forEach { compose.onNodeWithTag("pinKey$it").performClick() }
        compose.waitForIdle()
    }

    private fun text(tag: String): String =
        compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()

    /** Unlocks the cold start lock, then confirms the PIN the keys screen asks for. */
    private fun openKeys() {
        signIn()
        launch()
        waitFor("pinScreen")
        type("1234")
        until { env.appLock.current.isVerifying }
        assertFalse("no key shows before the PIN was confirmed", exists("key:POSTING"))
        type("1234")
        waitFor("key:POSTING")
    }

    @Test
    fun withTheLockOffTheWarningShowsAndCancelLeavesWithoutAnyKey() {
        signIn(pin = null)
        launch()
        waitFor("keysWarning")
        assertFalse(exists("key:POSTING"))
        compose.onNodeWithTag("keysCancel").performClick()
        until { navigator.top.screen == AppScreen.Settings }
        assertFalse(exists("key:POSTING"))
    }

    @Test
    fun setPinFromTheWarningShowsTheKeysAfterTheNewPin() {
        signIn(pin = null)
        launch()
        waitFor("keysWarning")
        compose.onNodeWithTag("keysSetPin").performClick()
        waitFor("pinScreen")
        type("4321")
        assertFalse(exists("key:POSTING"))
        type("4321")
        waitFor("key:POSTING")
        assertTrue(env.appLock.current.enabled)
    }

    @Test
    fun aCancelledPinLeavesTheScreen() {
        signIn()
        launch()
        waitFor("pinScreen")
        type("1234")
        until { env.appLock.current.isVerifying }
        compose.onNodeWithTag("pinClose").performClick()
        until { navigator.top.screen == AppScreen.Settings }
        assertFalse(exists("key:POSTING"))
    }

    @Test
    fun publicKeysShowFirstAndRevealShowsTheStoredPrivateKey() {
        openKeys()
        assertEquals("Posting Key (Public)", text("keyLabel:POSTING"))
        assertEquals(node.key.publicKey().toString(), text("keyValue:POSTING"))
        assertEquals("Reveal Private", text("keyButton:POSTING"))
        assertEquals("Import Private", text("keyButton:ACTIVE"))
        // the owner row is public only: a note in place of the button
        assertFalse(exists("keyButton:OWNER"))
        assertEquals("Public only. Ecency never stores the owner key.", text("keyNote:OWNER"))
        compose.onNodeWithTag("keyButton:POSTING").performClick()
        until { text("keyLabel:POSTING") == "Posting Key (Private)" }
        assertEquals("Reveal Public", text("keyButton:POSTING"))
        // the private key is drawn but never handed to accessibility services: no node carries it as text
        val value = compose.onNodeWithTag("keyValue:POSTING", useUnmergedTree = true).fetchSemanticsNode().config
        assertEquals(listOf("Posting Key, private key shown"), value.getOrNull(SemanticsProperties.ContentDescription))
        assertTrue(value.contains(SemanticsProperties.Password))
        assertEquals("", text("keyValue:POSTING"))
        assertTrue(
            "the private key is in no semantics node",
            compose.onAllNodes(androidx.compose.ui.test.hasText(node.key.toWif(), substring = true), useUnmergedTree = true).fetchSemanticsNodes().isEmpty(),
        )
        // and it is what the reveal shows: the model's row holds it
        assertEquals(node.key.toWif(), navigator.top.get("keys") { env.backupKeysModel() }.current.rows.first { it.revealed }.value)
        assertTrue("the window is secure while the keys show", sensitiveScreen.last())
    }

    @Test
    fun aPublicKeyIsCopiedPlainlyAndAPrivateKeyOnlyAfterTheWarningThroughTheKeyClipboard() {
        openKeys()
        compose.onNodeWithTag("keyCopy:POSTING").performClick()
        until { copied.isNotEmpty() }
        assertEquals(listOf(node.key.publicKey().toString()), copied)
        assertTrue(copiedSensitive.isEmpty())

        compose.onNodeWithTag("keyButton:POSTING").performClick()
        until { text("keyLabel:POSTING") == "Posting Key (Private)" }
        compose.onNodeWithTag("keyCopy:POSTING").performClick()
        waitFor("keyCopyWarning")
        assertTrue("nothing is copied before the warning was accepted", copiedSensitive.isEmpty())
        compose.onNodeWithTag("keyCopyConfirm").performClick()
        until { copiedSensitive.isNotEmpty() }
        assertEquals(listOf(node.key.toWif()), copiedSensitive)
        assertEquals("the private key never takes the plain clipboard path", 1, copied.size)
    }

    @Test
    fun theScreenLeavesWhenTheAppGoesToTheBackground() {
        openKeys()
        compose.onNodeWithTag("keyButton:POSTING").performClick()
        compose.runOnIdle { env.appLock.onBackground() }
        until { navigator.top.screen == AppScreen.Settings }
        assertFalse(exists("key:POSTING"))
    }

    @Test
    fun aRotationKeepsTheScreenAndARealStopClosesIt() {
        openKeys()
        compose.onNodeWithTag("keyButton:POSTING").performClick()
        until { text("keyLabel:POSTING") == "Posting Key (Private)" }
        // the activity stops only to be created again: not a background
        changingConfigurations = true
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        compose.waitForIdle()
        assertEquals(AppScreen.BackupKeys, navigator.top.screen)
        assertTrue(exists("key:POSTING"))
        assertEquals("Posting Key (Private)", text("keyLabel:POSTING"))
        // the app really leaves the foreground
        changingConfigurations = false
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        until { navigator.top.screen == AppScreen.Settings }
        assertFalse(exists("key:POSTING"))
    }

    @Test
    fun theScreenLeavesWhenTheAccountChanges() {
        openKeys()
        KeyVault(node.store).save(VaultAccount("other", AuthType.POSTING_KEY, postingKey = node.key.toWif(), accessToken = "access-other"))
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch { env.session.switchTo("other") }
        until { navigator.state.stack.none { it.screen == AppScreen.BackupKeys } }
        assertFalse(exists("key:POSTING"))
    }

    @Test
    fun aWrongImportShowsRnsFailureAndStoresNothing() {
        openKeys()
        compose.onNodeWithTag("keyButton:ACTIVE").performClick()
        waitFor("keyImportField")
        compose.onNodeWithTag("keyImportField").performTextInput("not the master password")
        compose.onNodeWithTag("keyImportSign").performClick()
        waitFor("keyImportError")
        assertEquals("Invalid credentials, please check and try again\ntry a different key or try again later.", text("keyImportError"))
        assertEquals(null, KeyVault(node.store).account("tester")!!.activeKey)
        assertFalse(node.store.map.values.any { "not the master password" in String(it) })
    }

    /** RN #3600: the copy button copies the public key; pasting it into Import names it. */
    @Test
    fun aPastedPublicKeyAtImportIsNamed() {
        openKeys()
        compose.onNodeWithTag("keyButton:ACTIVE").performClick()
        waitFor("keyImportField")
        compose.onNodeWithTag("keyImportField").performTextInput("${node.key.publicKey()}\u200B")
        compose.onNodeWithTag("keyImportSign").performClick()
        waitFor("keyImportError")
        assertEquals("${com.ecency.core.app.AppError.PUBLIC_KEY_GIVEN}\ntry a different key or try again later.", text("keyImportError"))
        assertEquals(null, KeyVault(node.store).account("tester")!!.activeKey)
    }
}

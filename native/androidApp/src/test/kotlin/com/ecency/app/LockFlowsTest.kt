package com.ecency.app

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
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
import com.ecency.core.api.EcencyApi
import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.api.rpc.RpcTransport
import com.ecency.core.app.AppDispatchers
import com.ecency.core.app.AppEnvironment
import com.ecency.core.app.AppLockModel
import com.ecency.core.app.InterimPostBodyRenderer
import com.ecency.core.app.SecurityConfig
import com.ecency.core.hive.PrivateKey
import com.ecency.core.secure.AuthType
import com.ecency.core.secure.KeyVault
import com.ecency.core.secure.KeyVaultAsync
import com.ecency.core.secure.PinCheck
import com.ecency.core.secure.PinLock
import com.ecency.core.secure.SecureStore
import com.ecency.core.secure.VaultAccount
import kotlinx.coroutines.Dispatchers
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
import java.util.Collections

/**
 * The PIN lock in the real app root, over an in-memory store (no Keystore) and a scripted node: locked at
 * start, wrong PINs counted with the RN texts, wipe on the third, settings flows from the account menu,
 * relock after the background timeout, biometric unlock.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LockFlowsTest {
    @get:Rule
    val compose = createComposeRule()

    private class Store : SecureStore {
        val map = Collections.synchronizedMap(linkedMapOf<String, ByteArray>())
        /** The device key is gone while the stored values remain (as the Keystore store reports a restore or a reset). */
        @Volatile var keyLost = false
        private fun key() { if (keyLost) throw com.ecency.core.secure.SecureStoreException.KeyInvalidated() }
        override fun put(name: String, value: ByteArray) { key(); map[name] = value.copyOf() }
        override fun get(name: String): ByteArray? { key(); return map[name]?.copyOf() }
        override fun delete(name: String) { map.remove(name) }
        override fun names(): Set<String> = map.keys.toSet()
        override fun wipe() { map.clear(); keyLost = false }
        override fun <T> withLock(block: () -> T): T = synchronized(this) { block() }
    }

    private val store = Store()
    private val key = PrivateKey.fromSeed("ecency-native-spike-lock-test-throwaway")
    @Volatile private var monotonic = 0L
    private var biometricAvailable = false
    private var biometricSucceeds = false
    private var biometricPrompts = 0
    private var enrollmentChanged = false
    private val hideInRecents = mutableListOf<Boolean>()

    private val transport = RpcTransport { _, body ->
        val req = Json.parseToJsonElement(body).jsonObject
        val id = req["id"]!!.jsonPrimitive.content
        when (req["method"]!!.jsonPrimitive.content) {
            "bridge.get_ranked_posts" -> HttpResult(200, """{"jsonrpc":"2.0","result":[],"id":$id}""")
            else -> HttpResult(500, "no fixture")
        }
    }

    private val env by lazy {
        AppEnvironment(
            api = EcencyApi(listOf("https://node-a.example"), transport),
            vault = KeyVaultAsync(KeyVault(store), Dispatchers.IO),
            renderer = InterimPostBodyRenderer("https://i.ecency.com"),
            dispatchers = AppDispatchers(Dispatchers.Main, Dispatchers.Default, Dispatchers.IO),
            proxyBase = "https://i.ecency.com",
            debugKeyImport = true,
            nowMillis = { 1_791_288_000_000L },
            security = SecurityConfig(pinIterations = ITERATIONS, monotonicMillis = { monotonic }),
        )
    }

    private val host = object : AppHost {
        override fun openExternal(url: String) {}
        override fun toast(text: String) {}
        override fun copy(text: String) {}
        override fun biometricAvailable() = biometricAvailable
        override fun authenticateBiometric(onSuccess: (String) -> Unit, onInvalidated: () -> Unit) {
            biometricPrompts++
            if (enrollmentChanged) onInvalidated() else if (biometricSucceeds) onSuccess(BINDING)
        }
        override fun setHideInRecents(hide: Boolean) { hideInRecents += hide }
        override fun confirmBiometric(onSuccess: () -> Unit) {
            biometricPrompts++
            if (biometricSucceeds) onSuccess()
        }
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
    fun tearDown() = SingletonImageLoader.reset()

    private fun signIn(pin: String? = "1234", biometric: Boolean = false) {
        val vault = KeyVault(store)
        vault.save(VaultAccount("tester", AuthType.POSTING_KEY, postingKey = key.toWif()))
        vault.setCurrentUsername("tester")
        val lock = PinLock(vault, ITERATIONS)
        if (pin != null) lock.setPin(pin)
        if (biometric) lock.setBiometricEnabled(pin!!, true) { BINDING }
    }

    private fun launch() = compose.setContent { EcencyTheme(dark = false) { EcencyApp(env, host) } }

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(tag: String) = compose.await("exists(tag)") { exists(tag) }

    private fun waitForText(text: String) =
        compose.await("compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()") { compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    private fun type(pin: String) {
        pin.forEach { compose.onNodeWithTag("pinKey$it").performClick() }
        // fetching semantics idles the main looper, where the model's results land
        compose.await("compose.onAllNodes(hasTestTag(\"pinDots\")).fetchSemanticsNodes() env.appLock.current.pro...") {
            compose.onAllNodes(hasTestTag("pinDots")).fetchSemanticsNodes()
            env.appLock.current.prompt?.isChecking != true
        }
        compose.waitForIdle()
    }

    private fun message() = env.appLock.current.prompt?.message

    /** Side menu, then Settings (where the RN app keeps the PIN settings); stays there between flows. */
    private fun openSettings() {
        if (exists("settings")) return
        compose.onNodeWithTag("account").performClick()
        waitFor("menu:settings")
        compose.waitForIdle() // the drawer slid in
        compose.onNodeWithTag("menu:settings").apply { runCatching { performScrollTo() } }.performClick() // pinned, or in the one scroll on a short screen
        waitFor("settings")
    }

    @Test
    fun lockedAtStartAndTheRightPinShowsTheApp() {
        signIn()
        launch()
        waitFor("pinScreen")
        assertFalse("nothing private behind the lock", exists("account"))
        compose.onNodeWithTag("pinUsername").assertExistsWithText("@tester")
        compose.onNodeWithText("Enter PIN to unlock").assertExists()
        assertFalse("the lock screen cannot be closed", exists("pinClose"))
        type("1234")
        waitFor("account")
        assertFalse(exists("pinScreen"))
        compose.onNodeWithTag("headerUser").assertTextContains("@tester", substring = true)
        assertEquals(listOf(true), hideInRecents)
    }

    @Test
    fun twoWrongPinsWarnAndTheThirdWipesAndSignsOut() {
        signIn()
        launch()
        waitFor("pinScreen")
        type("0000")
        waitForText("1 failed attempt")
        compose.onNodeWithTag("pinError").assertExistsWithText(AppLockModel.INVALID_PIN)
        assertFalse(compose.onAllNodes(hasText(AppLockModel.RESET_WARNING, substring = true)).fetchSemanticsNodes().isNotEmpty())
        type("1111")
        waitForText("2 failed attempts")
        waitForText(AppLockModel.RESET_WARNING)
        assertEquals(1, KeyVault(store).accounts().size)

        type("2222")
        waitFor("lockNotice")
        compose.onNodeWithText(AppLockModel.WIPED_NOTICE).assertExists()
        assertTrue(KeyVault(store).accounts().isEmpty())
        assertFalse(PinLock(KeyVault(store), ITERATIONS).hasPin())
        compose.onNodeWithText("OK").performClick()
        // back to the signed-out app
        waitFor("headerLogin")
        assertFalse(exists("pinScreen"))
        assertFalse(env.session.current.isLoggedIn)
    }

    @Test
    fun aLostStorageKeyAsksToSignInAgainAndOnlyTheConfirmationWipes() {
        signIn()
        store.keyLost = true
        launch()
        waitFor("storageResetConfirm")
        compose.onNodeWithTag("storageResetMessage").assertExistsWithText("Secure storage was reset on this device. Sign in again.")
        // the prompt alone removed nothing
        assertTrue(store.map.keys.any { it.startsWith("account.") })
        assertTrue(store.keyLost)
        assertFalse(exists("login"))

        compose.onNodeWithTag("storageResetConfirm").performClick()
        waitFor("login")
        assertFalse(exists("storageResetConfirm"))
        assertTrue(store.map.keys.none { it.startsWith("account.") || it.startsWith("pin.") })
        assertFalse(env.session.current.isLoggedIn)
        assertFalse(exists("pinScreen"))
    }

    @Test
    fun theAttemptCountSurvivesARestart() {
        signIn()
        PinLock(KeyVault(store), ITERATIONS).verify("0000")
        PinLock(KeyVault(store), ITERATIONS).verify("0000")
        launch()
        waitFor("pinScreen")
        waitForText("2 failed attempts")
        waitForText(AppLockModel.RESET_WARNING)
        type("9999")
        waitFor("lockNotice")
        assertTrue(KeyVault(store).accounts().isEmpty())
    }

    @Test
    fun forgotPinClearsEverythingAfterConfirming() {
        signIn()
        launch()
        waitFor("pinForgot")
        compose.onNodeWithTag("pinForgot").performClick()
        compose.onNodeWithText("Are you sure you want to clear all user data?").assertExists()
        var cleared = 0
        val before = com.ecency.app.ui.TurnstileBridge.clearProfile
        com.ecency.app.ui.TurnstileBridge.clearProfile = { cleared++ }
        try {
            compose.onNodeWithTag("pinForgotConfirm").performClick()
            waitFor("lockNotice")
            assertTrue(KeyVault(store).accounts().isEmpty())
            // the signup challenge's WebView profile goes with the wipe
            compose.await("compose.onAllNodes(hasTestTag(\"lockNotice\")).fetchSemanticsNodes(); cleared == 1") { compose.onAllNodes(hasTestTag("lockNotice")).fetchSemanticsNodes(); cleared == 1 }
        } finally {
            com.ecency.app.ui.TurnstileBridge.clearProfile = before
        }
    }

    @Test
    fun locksAgainAfterAMinuteInTheBackground() {
        signIn()
        launch()
        waitFor("pinScreen")
        type("1234")
        waitFor("account")
        compose.runOnUiThread {
            env.appLock.onBackground()
            monotonic += 30_000
            env.appLock.onForeground()
        }
        compose.waitForIdle()
        assertFalse(exists("pinScreen"))
        compose.runOnUiThread {
            env.appLock.onBackground()
            monotonic += 60_000
            env.appLock.onForeground()
        }
        waitFor("pinScreen")
        assertFalse(exists("account"))
    }

    @Test
    fun enableChangeAndDisableFromSettings() {
        signIn(pin = null)
        launch()
        waitFor("account")
        openSettings()
        compose.onNodeWithTag("menuPinLock").performScrollTo().performClick()
        waitFor("pinScreen")
        compose.onNodeWithText("Set new PIN").assertExists()
        type("4321")
        waitForText("Write again")
        type("4320")
        waitForText("PIN do not match, Please try again.")
        type("4321")
        type("4321")
        waitFor("lockNotice")
        compose.onNodeWithText("PIN lock is on.").assertExists()
        compose.onNodeWithText("OK").performClick()
        assertTrue(PinLock(KeyVault(store), ITERATIONS).isEnabled())

        openSettings()
        compose.onNodeWithTag("menuChangePin").performScrollTo().performClick()
        waitFor("pinClose")
        type("4321")
        waitForText("Set new PIN")
        type("8765")
        type("8765")
        waitForText("PIN changed.")
        compose.onNodeWithText("OK").performClick()
        assertEquals(PinCheck.Success, PinLock(KeyVault(store), ITERATIONS).verify("8765"))

        openSettings()
        compose.onNodeWithTag("menuPinLock").performScrollTo().performClick()
        waitFor("pinScreen")
        type("8765")
        waitForText("PIN lock is off.")
        assertFalse(PinLock(KeyVault(store), ITERATIONS).isEnabled())
    }

    @Test
    fun turningTheLockOnOffersBiometricsFirstWithThePinAsBackup() {
        signIn(pin = null)
        biometricAvailable = true
        env.appLock.biometricBinder = com.ecency.core.secure.BiometricBinder { BINDING }
        launch()
        waitFor("account")
        openSettings()
        compose.onNodeWithTag("menuPinLock").performScrollTo().performClick()
        waitFor("biometricOffer")
        assertFalse("the offer comes before the PIN pad", exists("pinScreen"))
        // a failed or cancelled prompt stays on the offer
        compose.onNodeWithTag("biometricOfferUse").performClick()
        assertEquals(1, biometricPrompts)
        assertTrue(exists("biometricOffer"))
        biometricSucceeds = true
        compose.onNodeWithTag("biometricOfferUse").performClick()
        waitFor("pinScreen")
        compose.onNodeWithText(com.ecency.core.app.AppLockModel.SET_BACKUP).assertExists()
        type("4321")
        waitForText("Write again")
        type("4321")
        waitFor("lockNotice")
        compose.onNodeWithText(com.ecency.core.app.AppLockModel.BIOMETRIC_ON_NOTICE).assertExists()
        compose.onNodeWithText("OK").performClick()
        val lock = PinLock(KeyVault(store), ITERATIONS)
        assertTrue(lock.isEnabled())
        assertTrue("biometrics are the default unlock", lock.isBiometricEnabled())

        // off again, then on with "Use PIN instead": a PIN only
        openSettings()
        compose.onNodeWithTag("menuPinLock").performScrollTo().performClick()
        waitFor("pinScreen")
        type("4321")
        waitForText("PIN lock is off.")
        compose.onNodeWithText("OK").performClick()
        openSettings()
        compose.onNodeWithTag("menuPinLock").performScrollTo().performClick()
        waitFor("biometricOffer")
        compose.onNodeWithTag("biometricOfferPin").performClick()
        waitFor("pinScreen")
        compose.onNodeWithText("Set new PIN").assertExists()
        type("1111")
        type("1111")
        waitForText("PIN lock is on.")
        assertFalse(PinLock(KeyVault(store), ITERATIONS).isBiometricEnabled())
    }

    @Test
    fun withoutBiometricsTurningTheLockOnGoesStraightToThePin() {
        signIn(pin = null)
        biometricAvailable = false
        launch()
        waitFor("account")
        openSettings()
        compose.onNodeWithTag("menuPinLock").performScrollTo().performClick()
        waitFor("pinScreen")
        assertFalse(exists("biometricOffer"))
        compose.onNodeWithText("Set new PIN").assertExists()
    }

    @Test
    fun aSettingsFlowCanBeClosedButItsWrongPinsStillCount() {
        signIn()
        launch()
        waitFor("pinScreen")
        type("1234")
        waitFor("account")
        openSettings()
        compose.onNodeWithTag("menuChangePin").performScrollTo().performClick()
        waitFor("pinClose")
        type("0000")
        waitForText("1 failed attempt")
        compose.onNodeWithTag("pinClose").performClick()
        compose.await("!exists(\"pinScreen\")") { !exists("pinScreen") }
        assertEquals(1, PinLock(KeyVault(store), ITERATIONS).failedAttempts())
    }

    @Test
    fun biometricUnlocksWhenOnAndAvailable() {
        biometricAvailable = true
        biometricSucceeds = true
        signIn(biometric = true)
        launch()
        waitFor("account")
        assertEquals(1, biometricPrompts)
        assertFalse(exists("pinScreen"))
    }

    @Test
    fun aFailedBiometricLeavesThePinPad() {
        biometricAvailable = true
        signIn(biometric = true)
        launch()
        waitFor("pinBiometric")
        compose.await("biometricPrompts == 1") { biometricPrompts == 1 }
        assertTrue(exists("pinScreen"))
        compose.onNodeWithTag("pinBiometric").performClick()
        assertEquals(2, biometricPrompts)
        // a wrong PIN shows the pad again but never starts the prompt by itself again
        type("0000")
        waitForText("1 failed attempt")
        compose.waitForIdle()
        assertEquals(2, biometricPrompts)
        type("1234")
        waitFor("account")
    }

    @Test
    fun aChangedEnrollmentTurnsBiometricUnlockOffAndAsksForThePin() {
        biometricAvailable = true
        enrollmentChanged = true
        signIn(biometric = true)
        launch()
        waitFor("pinScreen")
        compose.await("biometricPrompts == 1") { biometricPrompts == 1 }
        waitFor("lockNotice")
        compose.onNodeWithText(AppLockModel.BIOMETRIC_CHANGED_NOTICE).assertExists()
        assertFalse(exists("pinBiometric"))
        assertFalse(PinLock(KeyVault(store), ITERATIONS).isBiometricEnabled())
        compose.onNodeWithText("OK").performClick()
        type("1234")
        waitFor("account")
    }

    @Test
    fun noBiometricKeyWithoutHardware() {
        signIn(biometric = true)
        launch()
        waitFor("pinScreen")
        assertFalse(exists("pinBiometric"))
        assertEquals(0, biometricPrompts)
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertExistsWithText(text: String) {
        assertExists()
        val node = fetchSemanticsNode()
        val texts = node.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() }.map { it.text }
        assertTrue("$texts", text in texts)
    }

    companion object {
        const val ITERATIONS = 16
        const val BINDING = "test-binding"
    }
}

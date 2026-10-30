package com.ecency.app

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
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
import com.ecency.core.app.InterimPostBodyRenderer
import com.ecency.core.app.SecurityConfig
import com.ecency.core.hive.PrivateKey
import com.ecency.core.secure.AuthType
import com.ecency.core.secure.KeyVault
import com.ecency.core.secure.KeyVaultAsync
import com.ecency.core.secure.PinLock
import com.ecency.core.secure.SecureStore
import com.ecency.core.secure.SecureStoreException
import com.ecency.core.secure.VaultAccount
import kotlinx.coroutines.Dispatchers
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
import java.security.InvalidKeyException
import java.security.KeyStoreException
import java.security.UnrecoverableKeyException
import java.util.Collections

/** Review A lock fixes on Android: storage errors on the PIN pad, biometrics re-read on resume, Keystore failures. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LockDeltaFlowsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private class Store : SecureStore {
        val map = Collections.synchronizedMap(linkedMapOf<String, ByteArray>())
        @Volatile var down = false
        private fun check() { if (down) throw SecureStoreException.Unavailable(-1) }
        override fun put(name: String, value: ByteArray) { check(); map[name] = value.copyOf() }
        override fun get(name: String): ByteArray? { check(); return map[name]?.copyOf() }
        override fun delete(name: String) { check(); map.remove(name) }
        override fun names(): Set<String> { check(); return map.keys.toSet() }
        override fun wipe() = map.clear()
        override fun <T> withLock(block: () -> T): T = synchronized(this) { block() }
    }

    private val store = Store()
    private val key = PrivateKey.fromSeed("ecency-native-spike-lock-delta-throwaway")
    @Volatile private var biometricAvailable = false

    private val env by lazy {
        AppEnvironment(
            api = EcencyApi(listOf("https://node-a.example"), RpcTransport { _, _ -> HttpResult(500, "no fixture") }),
            vault = KeyVaultAsync(KeyVault(store), Dispatchers.IO),
            renderer = InterimPostBodyRenderer("https://i.ecency.com"),
            dispatchers = AppDispatchers(Dispatchers.Main, Dispatchers.Default, Dispatchers.IO),
            proxyBase = "https://i.ecency.com",
            debugKeyImport = true,
            nowMillis = { 1_791_288_000_000L },
            security = SecurityConfig(pinIterations = 16, monotonicMillis = { 0L }),
        )
    }

    private val host = object : AppHost {
        override fun openExternal(url: String) {}
        override fun toast(text: String) {}
        override fun copy(text: String) {}
        override fun biometricAvailable() = biometricAvailable
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
        val vault = KeyVault(store)
        vault.save(VaultAccount("tester", AuthType.POSTING_KEY, postingKey = key.toWif()))
        vault.setCurrentUsername("tester")
        PinLock(vault, 16).apply {
            setPin("1234")
            setBiometricEnabled("1234", true) { "binding" }
        }
    }

    @After
    fun tearDown() = SingletonImageLoader.reset()

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun launch() {
        compose.setContent { EcencyTheme(dark = false) { EcencyApp(env, host) } }
        compose.await("exists(\"pinScreen\")") { exists("pinScreen") }
    }

    @Test
    fun aStorageErrorDuringAnUnlockCheckShowsOnThePinPad() {
        launch()
        store.down = true
        "1234".forEach { compose.onNodeWithTag("pinKey$it").performClick() }
        // fetching semantics idles the main looper, where the model's results land
        compose.await("exists(\"pinError\")") { exists("pinError") }
        assertTrue("still locked", env.appLock.current.isLocked)
        compose.onNodeWithTag("pinError").assertTextEquals(env.appLock.current.error!!.message)
    }

    @Test
    fun biometricAvailabilityIsReadAgainOnResume() {
        launch()
        assertFalse(exists("pinBiometric"))
        // the user sets up a fingerprint in the system settings, then comes back
        biometricAvailable = true
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.await("exists(\"pinBiometric\")") { exists("pinBiometric") }
    }

    @Test
    fun onlyAPermanentlyInvalidatedKeyTurnsBiometricsOff() {
        assertEquals(BiometricKey.Use.Invalidated, BiometricKey.failure(android.security.keystore.KeyPermanentlyInvalidatedException()))
        // possibly transient: the PIN this time, biometric unlock stays on
        assertEquals(BiometricKey.Use.Unavailable, BiometricKey.failure(UnrecoverableKeyException("hiccup")))
        assertEquals(BiometricKey.Use.Unavailable, BiometricKey.failure(InvalidKeyException("refused")))
        assertEquals(BiometricKey.Use.Unavailable, BiometricKey.failure(KeyStoreException("busy")))
    }
}

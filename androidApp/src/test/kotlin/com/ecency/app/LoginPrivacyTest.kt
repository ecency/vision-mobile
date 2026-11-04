package com.ecency.app

import android.content.ClipData
import android.content.ClipboardManager
import android.view.WindowManager
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import com.ecency.app.ui.NavigationViewModel
import com.ecency.app.ui.Navigator
import com.ecency.core.app.AppScreen
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.time.Duration

/** FLAG_SECURE on screens with keys and passwords (every Android version), and the key clipboard expiry. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LoginPrivacyTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val node = TestNode()

    @Before
    fun setUp() {
        EcencyGraph.install(node.env())
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
    fun tearDown() {
        EcencyGraph.install(null)
        SingletonImageLoader.reset()
    }

    private fun secure(scenario: ActivityScenario<MainActivity>): Boolean {
        var flags = 0
        scenario.onActivity { flags = it.window.attributes.flags }
        return flags and WindowManager.LayoutParams.FLAG_SECURE != 0
    }

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun loginAndKeysScreensAreSecureOnAndroid13AndLaterEvenWithoutTheLock() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.await("exists(\"account\")") { exists("account") }
            assertFalse("the feed is not secure", secure(scenario))
            var nav: Navigator? = null
            scenario.onActivity { nav = ViewModelProvider(it)[NavigationViewModel::class.java].navigator }
            compose.runOnIdle { nav!!.push(AppScreen.Login) }
            compose.await("exists(\"login\")") { exists("login") }
            compose.waitForIdle()
            assertTrue("login is secure", secure(scenario))
            compose.runOnIdle { nav!!.pop() }
            compose.await("!exists(\"login\")") { !exists("login") }
            compose.waitForIdle()
            assertFalse("cleared when login goes", secure(scenario))
        }
    }

    @Test
    fun privacyCombinesTheLockAndSensitiveScreens() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.applyWindowPrivacy(hideInRecents = true, sensitive = false, sdk = 35)
                assertEquals(0, it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
                it.applyWindowPrivacy(hideInRecents = false, sensitive = true, sdk = 35)
                assertTrue(it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                it.applyWindowPrivacy(hideInRecents = true, sensitive = false, sdk = 30)
                assertTrue(it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                it.applyWindowPrivacy(hideInRecents = false, sensitive = false, sdk = 30)
                assertEquals(0, it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }

    @Test
    fun aCopiedKeyIsClearedAfterTwoMinutesOnlyIfStillThere() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        KeyClipboard.copy(context, "5KthrowawayKeyText")
        ShadowLooper.idleMainLooper(KeyClipboard.CLEAR_AFTER_MILLIS - 1000, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals("5KthrowawayKeyText", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        ShadowLooper.idleMainLooper(2000, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertNull(clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text)

        KeyClipboard.copy(context, "5KthrowawayKeyText")
        clipboard.setPrimaryClip(ClipData.newPlainText("note", "something else"))
        ShadowLooper.idleMainLooper(KeyClipboard.CLEAR_AFTER_MILLIS + 1000, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals("what the user copied since stays", "something else", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
    }
}

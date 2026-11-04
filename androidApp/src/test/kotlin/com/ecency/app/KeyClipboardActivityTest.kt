package com.ecency.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A key clip left by a dead process is checked when the window has focus (Android 10+ lets only a focused app read
 * the clipboard), on a fresh start and on a restore alike, and the mark stays until the check really ran.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KeyClipboardActivityTest {
    private val node = TestNode()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val clipboard: ClipboardManager get() = context.getSystemService(ClipboardManager::class.java)
    private val pending get() = context.getSharedPreferences("ecency.keyclip", Context.MODE_PRIVATE).getBoolean("pending", false)

    @Before
    fun setUp() {
        acceptTerms()
        EcencyGraph.install(node.env())
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

    /** What a process that died before its clear timer leaves behind: our clip and the mark. */
    private fun leftover(clip: ClipData = ClipData.newPlainText(KeyClipboard.LABEL, "5KthrowawayKeyText")) {
        clipboard.setPrimaryClip(clip)
        context.getSharedPreferences("ecency.keyclip", Context.MODE_PRIVATE).edit().putBoolean("pending", true).commit()
    }

    private fun clipText() = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()

    @Test
    fun aRestoreAfterProcessDeathStillClearsOurClipOnceFocused() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        leftover()
        scenario.recreate() // savedInstanceState is not null, like a restore after process death
        scenario.onActivity { it.onWindowFocusChanged(true) }
        assertNull(clipText())
        assertFalse(pending)
        scenario.close()
    }

    @Test
    fun withoutFocusTheMarkStaysForTheNextFocus() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        leftover()
        // the window lost focus (another app in front): Android 10+ hides the clipboard, so nothing is checked
        scenario.onActivity { it.onWindowFocusChanged(false) }
        assertTrue(pending)
        assertEquals("5KthrowawayKeyText", clipText())
        scenario.onActivity { it.onWindowFocusChanged(true) }
        assertNull(clipText())
        assertFalse(pending)
        scenario.close()
    }

    @Test
    fun focusClearsOursAndKeepsAnotherAppsClip() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        leftover(ClipData.newPlainText("note", "something else"))
        scenario.onActivity { it.onWindowFocusChanged(true) }
        assertEquals("something else", clipText())
        assertFalse(pending) // checked: the clip there was not ours
        scenario.close()
    }

    @Test
    fun aKeyJustCopiedInThisProcessWaitsForItsTimerNotForTheNextFocus() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { KeyClipboard.copy(it, "5KthrowawayKeyText") }
        // the copy confirmation closes and the window gets focus back: the key stays for its 120 s
        scenario.onActivity { it.onWindowFocusChanged(true) }
        assertEquals("5KthrowawayKeyText", clipText())
        scenario.close()
    }
}

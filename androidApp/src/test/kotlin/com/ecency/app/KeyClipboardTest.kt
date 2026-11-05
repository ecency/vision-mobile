package com.ecency.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** A private key reaches the clipboard only where the app can take it off again, and a dead timer is made up at start. */
@RunWith(RobolectricTestRunner::class)
class KeyClipboardTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val clipboard: ClipboardManager get() = context.getSystemService(ClipboardManager::class.java)

    @Test @Config(sdk = [27])
    fun belowApi28AKeyIsNotOfferedAndNothingIsCopied() {
        assertFalse(KeyClipboard.canCopyPrivateKey())
        KeyClipboard.copy(context, "5KthrowawayKeyText")
        assertNull(clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text)
    }

    @Test @Config(sdk = [33])
    fun fromApi28AKeyIsCopiedMarkedSensitiveAndTheMarkIsKept() {
        assertTrue(KeyClipboard.canCopyPrivateKey())
        KeyClipboard.copy(context, "5KthrowawayKeyText")
        assertEquals("5KthrowawayKeyText", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        assertEquals(true, clipboard.primaryClipDescription?.extras?.getBoolean("android.content.extra.IS_SENSITIVE"))
        assertTrue(context.getSharedPreferences("ecency.keyclip", Context.MODE_PRIVATE).getBoolean("pending", false))
    }

    /** What a process that died before its clear timer leaves behind: the clip and the mark. */
    private fun leftover(clip: ClipData = ClipData.newPlainText(KeyClipboard.LABEL, "5KthrowawayKeyText")) {
        clipboard.setPrimaryClip(clip)
        context.getSharedPreferences("ecency.keyclip", Context.MODE_PRIVATE).edit().putBoolean("pending", true).commit()
    }

    private val pending get() = context.getSharedPreferences("ecency.keyclip", Context.MODE_PRIVATE).getBoolean("pending", false)

    @Test @Config(sdk = [33])
    fun aKeyLeftByADeadProcessIsClearedAtTheNextFocusOnlyIfStillOurs() {
        leftover()
        KeyClipboard.clearLeftover(context, hasFocus = true)
        assertNull(clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text)
        assertFalse(pending)

        // something else was copied in between: it stays
        leftover(ClipData.newPlainText("note", "something else"))
        KeyClipboard.clearLeftover(context, hasFocus = true)
        assertEquals("something else", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        assertFalse(pending)
    }

    /** Android 10+ hides the clipboard from an app without focus: nothing is checked, so the mark stays. */
    @Test @Config(sdk = [33])
    fun withoutFocusTheClipAndTheMarkStay() {
        leftover()
        KeyClipboard.clearLeftover(context, hasFocus = false)
        assertEquals("5KthrowawayKeyText", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        assertTrue(pending)
    }

    /** A key copied in this process keeps its 120 s: a focus change (a dialog closing) does not clear it early. */
    @Test @Config(sdk = [33])
    fun aKeyCopiedInThisProcessWaitsForItsTimer() {
        KeyClipboard.copy(context, "5KthrowawayKeyText")
        KeyClipboard.clearLeftover(context, hasFocus = true)
        assertEquals("5KthrowawayKeyText", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        // its timer clears it and the mark goes
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(KeyClipboard.CLEAR_AFTER_MILLIS))
        assertNull(clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text)
        assertFalse(pending)
    }

    @Test @Config(sdk = [33])
    fun theKeyClipHasItsOwnLabelSoAnotherAppsKeyClipStays() {
        KeyClipboard.copy(context, "5KthrowawayKeyText")
        assertEquals("ecency.private-key", clipboard.primaryClipDescription?.label?.toString())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(KeyClipboard.CLEAR_AFTER_MILLIS))
        // another app's clip labelled "key" is not ours
        leftover(ClipData.newPlainText("key", "a door code"))
        KeyClipboard.clearLeftover(context, hasFocus = true)
        assertEquals("a door code", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
    }
}

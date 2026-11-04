package com.ecency.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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

    @Test @Config(sdk = [33])
    fun aKeyLeftByADeadProcessIsClearedAtTheNextStartOnlyIfStillOurs() {
        KeyClipboard.copy(context, "5KthrowawayKeyText")
        // the process died: the timer never ran. The next start clears the clip
        KeyClipboard.clearLeftover(context)
        assertNull(clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text)
        assertFalse(context.getSharedPreferences("ecency.keyclip", Context.MODE_PRIVATE).getBoolean("pending", false))

        // something else was copied in between: it stays
        KeyClipboard.copy(context, "5KthrowawayKeyText")
        clipboard.setPrimaryClip(ClipData.newPlainText("note", "something else"))
        KeyClipboard.clearLeftover(context)
        assertEquals("something else", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
    }

    @Test @Config(sdk = [33])
    fun theKeyClipHasItsOwnLabelSoAnotherAppsKeyClipStays() {
        KeyClipboard.copy(context, "5KthrowawayKeyText")
        assertEquals("ecency.private-key", clipboard.primaryClipDescription?.label?.toString())
        // another app's clip labelled "key" is not ours
        clipboard.setPrimaryClip(ClipData.newPlainText("key", "a door code"))
        KeyClipboard.clearLeftover(context)
        assertEquals("a door code", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
    }
}

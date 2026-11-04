package com.ecency.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle

/**
 * Copies a private key: marked sensitive (Android 13+ keeps it out of the clipboard preview) and cleared again
 * after [CLEAR_AFTER_MILLIS] if the clipboard still holds a key clip of ours (API 28+, like iOS's 120 s expiry). Android 10+ only
 * lets a foreground app read the clipboard, so a key left there while the app is in the background stays until
 * the user copies something else.
 */
object KeyClipboard {
    const val CLEAR_AFTER_MILLIS = 120_000L
    const val LABEL = "key"

    fun copy(context: Context, text: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText(LABEL, text)
        clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        clipboard.setPrimaryClip(clip)
        if (Build.VERSION.SDK_INT >= 28) {
            // the delayed check holds no key: it only looks at the clip's label
            Handler(Looper.getMainLooper()).postDelayed({ clearIfOurs(clipboard) }, CLEAR_AFTER_MILLIS)
        }
    }

    /**
     * Clears the clipboard when the current clip is still one of ours. Only the description is read, never the
     * text, so Android 12+ shows no "pasted from your clipboard" notice when another app's clip is there.
     */
    internal fun clearIfOurs(clipboard: ClipboardManager) {
        if (Build.VERSION.SDK_INT < 28) return
        if (!clipboard.hasPrimaryClip()) return
        if (clipboard.primaryClipDescription?.label != LABEL) return
        clipboard.clearPrimaryClip()
    }
}

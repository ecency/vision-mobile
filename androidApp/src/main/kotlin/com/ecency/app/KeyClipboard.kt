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
 * after [CLEAR_AFTER_MILLIS] if the clipboard still holds a key clip of ours (like iOS's 120 s expiry). Android 10+
 * only lets a foreground app read the clipboard, so a key left there while the app is in the background stays until
 * the user copies something else.
 *
 * Clearing a clip needs API 28, so below that a private key is not offered for copying at all ([canCopyPrivateKey]):
 * it stays readable on the screen. The clear is also kept as a mark in the app's preferences: when the process dies
 * before the timer fires, the next start clears the clip if it is still ours ([clearLeftover]).
 */
object KeyClipboard {
    const val CLEAR_AFTER_MILLIS = 120_000L
    const val LABEL = "key"
    private const val PREFS = "ecency.keyclip"
    private const val PENDING = "pending"

    /** Whether a private key can be put on the clipboard here: only where the app can take it off again. */
    fun canCopyPrivateKey(sdk: Int = Build.VERSION.SDK_INT): Boolean = sdk >= 28

    fun copy(context: Context, text: String) {
        if (!canCopyPrivateKey()) return
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText(LABEL, text)
        clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        clipboard.setPrimaryClip(clip)
        prefs(context).edit().putBoolean(PENDING, true).apply()
        // the delayed check holds no key: it only looks at the clip's label
        Handler(Looper.getMainLooper()).postDelayed({ clearIfOurs(clipboard); prefs(context).edit().remove(PENDING).apply() }, CLEAR_AFTER_MILLIS)
    }

    /** At start: a key copy whose timer died with the process is cleared now when the clip is still ours. */
    fun clearLeftover(context: Context) {
        val prefs = prefs(context)
        if (!prefs.getBoolean(PENDING, false)) return
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        clearIfOurs(clipboard)
        prefs.edit().remove(PENDING).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

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

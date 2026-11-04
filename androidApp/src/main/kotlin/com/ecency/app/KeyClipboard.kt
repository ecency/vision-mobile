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
 * before the timer fires, or the timer fires while the app cannot read the clipboard, the next time the window has
 * focus clears the clip if it is still ours ([clearLeftover]). The mark goes only once the clipboard was read.
 */
object KeyClipboard {
    const val CLEAR_AFTER_MILLIS = 120_000L
    /** Only this app names a clip so: a clip labelled plain "key" may be another app's and is never cleared. */
    const val LABEL = "ecency.private-key"
    private const val PREFS = "ecency.keyclip"
    private const val PENDING = "pending"

    /** Whether a private key can be put on the clipboard here: only where the app can take it off again. */
    fun canCopyPrivateKey(sdk: Int = Build.VERSION.SDK_INT): Boolean = sdk >= 28

    /** The clear timer of this process and the handler it waits on; while it waits the focus check leaves its clip to it. */
    private var timer: Pair<Handler, Runnable>? = null

    private val timerArmed: Boolean
        get() = timer?.let { (handler, check) -> androidx.core.os.HandlerCompat.hasCallbacks(handler, check) } == true

    fun copy(context: Context, text: String) {
        if (!canCopyPrivateKey()) return
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText(LABEL, text)
        clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        clipboard.setPrimaryClip(clip)
        prefs(context).edit().putBoolean(PENDING, true).apply()
        // the delayed check holds no key: it only looks at the clip's label. Android 10+ hides the clipboard from an
        // app without focus: then the mark stays and the next focus checks again
        timer?.let { (handler, check) -> handler.removeCallbacks(check) }
        val main = Handler(Looper.getMainLooper())
        val check = object : Runnable {
            override fun run() {
                if (timer?.second === this) timer = null // holds no activity past its time
                // a hidden clipboard reads as empty: the mark goes only when the clip was cleared or could be read
                if (clearIfOurs(clipboard) || canRead(context)) prefs(context).edit().remove(PENDING).apply()
            }
        }
        timer = main to check
        main.postDelayed(check, CLEAR_AFTER_MILLIS)
    }

    /**
     * When the window gets focus ([hasFocus], MainActivity.onWindowFocusChanged; on a fresh start and a restore
     * alike): a key copy whose timer died with the process, or ran while the clipboard was hidden, is cleared now
     * when the clip is still ours. Without focus Android 10+ reads the clipboard as empty, so nothing is checked and
     * the mark stays. A timer of this process that still waits keeps its clip for the rest of its time.
     */
    fun clearLeftover(context: Context, hasFocus: Boolean) {
        if (!hasFocus || timerArmed) return
        val prefs = prefs(context)
        if (!prefs.getBoolean(PENDING, false)) return
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        // with focus the clipboard is readable: no clip at all counts as checked
        clearIfOurs(clipboard)
        prefs.edit().remove(PENDING).apply()
    }

    /** Android 10+ lets only the app with focus read the clipboard. */
    private fun canRead(context: Context): Boolean =
        Build.VERSION.SDK_INT < 29 || (context as? android.app.Activity)?.hasWindowFocus() == true

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Clears the clipboard when the current clip is still one of ours. Only the description is read, never the
     * text, so Android 12+ shows no "pasted from your clipboard" notice when another app's clip is there.
     */
    internal fun clearIfOurs(clipboard: ClipboardManager): Boolean {
        if (Build.VERSION.SDK_INT < 28) return false
        if (!clipboard.hasPrimaryClip()) return false
        if (clipboard.primaryClipDescription?.label != LABEL) return false
        clipboard.clearPrimaryClip()
        return true
    }
}

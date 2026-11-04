package com.ecency.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build

/**
 * RN react-native-receive-sharing-intent on Android: ACTION_SEND with text (EXTRA_TEXT) or an image (EXTRA_STREAM),
 * ACTION_SEND_MULTIPLE with images. What another app hands over is untrusted:
 * - only `content://` images from another app's provider are taken. `file://` URIs (which could name this app's own
 *   files, its key store among them) and this app's own providers are refused, so a share can never upload what
 *   the app keeps privately;
 * - the images are read later through the editor's or the composer's prepare step (decoded and written again:
 *   metadata stripped), which also refuses anything that is not an image.
 */
object ShareIntents {
    data class Shared(val texts: List<String>, val images: List<Uri>)

    fun parse(context: Context, intent: Intent?): Shared? {
        val action = intent?.action ?: return null
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return null
        val type = intent.type.orEmpty().lowercase()
        val texts = listOfNotNull(intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() })
        val streams: List<Uri> = when (action) {
            Intent.ACTION_SEND -> listOfNotNull(stream(intent))
            else -> streams(intent)
        }
        val images = if (type.startsWith("image/") || type == "*/*" || type.isEmpty()) streams.filter { acceptable(context, it) } else emptyList()
        if (texts.isEmpty() && images.isEmpty()) return null
        return Shared(texts, images)
    }

    /** A shared image may be read: another app's content provider only. */
    fun acceptable(context: Context, uri: Uri): Boolean {
        if (uri.scheme?.lowercase() != "content") return false
        val authority = uri.authority?.lowercase() ?: return false
        return authority !in ownAuthorities(context)
    }

    /** This app's own content providers (the editor's file provider and anything else it declares). */
    private fun ownAuthorities(context: Context): Set<String> {
        val declared = runCatching {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_PROVIDERS)
            info.providers.orEmpty().flatMap { p -> p.authority.orEmpty().split(';') }
        }.getOrDefault(emptyList())
        return (declared + "${context.packageName}.editorfiles").filter { it.isNotEmpty() }.map { it.lowercase() }.toSet()
    }

    @Suppress("DEPRECATION")
    private fun stream(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri

    @Suppress("DEPRECATION")
    private fun streams(intent: Intent): List<Uri> =
        (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)).orEmpty()
}

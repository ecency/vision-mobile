package com.ecency.app

import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.ext.SdkExtensions
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.ecency.core.app.MediaCheck
import com.ecency.core.app.MediaKind
import com.ecency.core.app.MediaSurface
import com.ecency.core.app.MediaTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The one media picking layer on Android, for every upload surface ([MediaSurface]). "Choose from Library" opens the
 * system photo picker when the platform has it (Android 13 and later, or 11 and 12 with the SDK extension), and the
 * system document picker otherwise; "Choose from Files" always opens the document picker. Both are limited to the
 * surface's kind and MIME types and need no storage permission.
 *
 * Deliberately not [ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable]: it also counts the Play
 * services backport (ACTION_SYSTEM_FALLBACK_PICK_IMAGES), which fails on Android 10 and older and on Go devices
 * (RN #3564), and [ActivityResultContracts.PickVisualMedia] would launch it. Without the system picker the
 * document picker is used directly.
 */
object MediaPickers {
    /** The platform's own photo picker: SDK 33+, or SDK 30 to 32 with R extension 2 (Google's rule). */
    fun systemPhotoPickerAvailable(sdkInt: Int = Build.VERSION.SDK_INT, rExtension: () -> Int = ::rExtensionVersion): Boolean =
        sdkInt >= 33 || (sdkInt >= 30 && rExtension() >= 2)

    private fun rExtensionVersion(): Int = if (Build.VERSION.SDK_INT >= 30) SdkExtensions.getExtensionVersion(Build.VERSION_CODES.R) else 0

    /** The photo picker's filter for [kind]. */
    fun visualMediaType(kind: MediaKind): ActivityResultContracts.PickVisualMedia.VisualMediaType =
        if (kind == MediaKind.IMAGE) ActivityResultContracts.PickVisualMedia.ImageOnly else ActivityResultContracts.PickVisualMedia.VideoOnly

    /**
     * Keeps what [MediaTypes] accepts for [surface] and at most its limit; [onRejected] gets one message for what was
     * left out (the first refusal, or the limit). Reads only the provider's type, name and size.
     */
    fun accept(resolver: ContentResolver, surface: MediaSurface, uris: List<Uri>, onRejected: (String) -> Unit): List<Uri> {
        var message: String? = null
        val kept = uris.filter { uri ->
            val (name, size) = nameAndSize(resolver, uri)
            when (val c = MediaTypes.check(surface.kind, runCatching { resolver.getType(uri) }.getOrNull(), name, size)) {
                is MediaCheck.Accepted -> true
                is MediaCheck.Rejected -> {
                    if (message == null) message = c.message
                    false
                }
            }
        }
        if (kept.size > surface.maxItems && message == null && surface.kind == MediaKind.IMAGE) message = MediaTypes.tooMany(surface)
        message?.let(onRejected)
        return kept.take(surface.maxItems)
    }

    private fun nameAndSize(resolver: ContentResolver, uri: Uri): Pair<String?, Long?> = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null to null
            (if (c.isNull(0)) null else c.getString(0)) to (if (c.isNull(1)) null else c.getLong(1))
        }
    }.getOrNull() ?: (uri.lastPathSegment to null)

    /**
     * A picked video copied into the app's cache (the camera folder, cleaned like a recording), so the upload reads a
     * file of its own: a grant the picker or provider withdraws later cannot break a running upload. Off the main
     * thread; null when the copy fails (the partial file is removed).
     */
    suspend fun copyVideoToCache(context: Context, uri: Uri): Uri? = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "camera").apply { mkdirs() }
        val ext = MediaTypes.resolve(runCatching { context.contentResolver.getType(uri) }.getOrNull(), uri.lastPathSegment)
            ?.let { if (it == "video/quicktime") "mov" else "mp4" } ?: "mp4"
        val file = File(dir, "pick_${System.currentTimeMillis()}.$ext")
        // piece by piece, so leaving the sheet (its scope) stops a large copy and removes the partial file
        val ok = try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { out ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                    }
                }
            } != null
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            file.delete()
            throw e
        } catch (_: Exception) {
            false
        }
        if (!ok) {
            file.delete()
            return@withContext null
        }
        androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.editorfiles", file)
    }
}

/**
 * ACTION_OPEN_DOCUMENT limited to a surface's MIME types (EXTRA_MIME_TYPES), several items where the surface takes
 * several. The result is every returned uri (clip data first), not yet checked: see [MediaPickers.accept].
 */
class OpenMediaDocuments(private val surface: MediaSurface) : ActivityResultContract<Unit, List<Uri>>() {
    override fun createIntent(context: Context, input: Unit): Intent {
        val mimes = MediaTypes.mimes(surface.kind)
        return Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (surface.kind == MediaKind.IMAGE) "image/*" else "video/*"
            putExtra(Intent.EXTRA_MIME_TYPES, mimes.toTypedArray())
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, surface.allowsMultiple)
        }
    }

    override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> {
        if (resultCode != Activity.RESULT_OK || intent == null) return emptyList()
        val clip = intent.clipData
        val fromClip = if (clip != null) (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri } else emptyList()
        return fromClip.ifEmpty { listOfNotNull(intent.data) }
    }
}

/** The two routes of one surface; launching is safe when no picker app exists (nothing happens). */
class MediaPicker internal constructor(private val gallery: () -> Unit, private val files: () -> Unit) {
    /** "Choose from Library": the system photo picker, or the document picker where there is none. */
    fun pickFromLibrary() = gallery()

    /** "Choose from Files": the document picker, for media stored outside the gallery. */
    fun pickFromFiles() = files()
}

/**
 * The media picker of [surface]. [onPicked] gets the accepted uris (empty when cancelled or nothing was accepted);
 * [onRejected] a message for what was left out. Register it where the result must still arrive after a sheet
 * closed (the screen, not the sheet).
 */
@Composable
fun rememberMediaPicker(surface: MediaSurface, onRejected: (String) -> Unit, onPicked: (List<Uri>) -> Unit): MediaPicker {
    val context = LocalContext.current
    val picked by rememberUpdatedState(onPicked)
    val rejected by rememberUpdatedState(onRejected)
    fun deliver(uris: List<Uri>) = picked(MediaPickers.accept(context.contentResolver, surface, uris) { rejected(it) })
    val single = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { deliver(listOfNotNull(it)) }
    // the contract wants at least two; the surface's own limit is applied to the result
    val multiple = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(maxOf(2, surface.maxItems))) { deliver(it) }
    val documents = rememberLauncherForActivityResult(OpenMediaDocuments(surface)) { deliver(it) }
    val system = remember { MediaPickers.systemPhotoPickerAvailable() }
    return remember(surface, system) {
        val openFiles = { runCatching { documents.launch(Unit) }.onFailure { picked(emptyList()) }; Unit }
        MediaPicker(
            gallery = {
                if (!system) {
                    openFiles()
                } else {
                    val request = PickVisualMediaRequest(MediaPickers.visualMediaType(surface.kind))
                    runCatching { if (surface.allowsMultiple) multiple.launch(request) else single.launch(request) }.onFailure { openFiles() }
                }
            },
            files = openFiles,
        )
    }
}

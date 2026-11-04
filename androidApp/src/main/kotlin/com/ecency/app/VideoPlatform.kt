package com.ecency.app

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.graphics.scale
import androidx.core.net.toUri
import com.ecency.core.api.speak.TusSource
import com.ecency.core.app.PickedImage
import com.ecency.core.app.PickedVideo
import com.ecency.core.app.VideoUploadModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.nio.ByteBuffer

/**
 * A picked video as the tus upload reads it: one piece at a time straight from the content provider, so a
 * large file is never copied or held in memory. The system photo picker's grant lasts as long as the activity.
 */
class UriVideoSource(context: Context, private val uri: Uri, override val name: String, override val size: Long) : TusSource {
    private val resolver = context.applicationContext.contentResolver

    override fun read(offset: Long, length: Int): ByteArray? = runCatching {
        resolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { stream ->
                val buffer = ByteBuffer.allocate(length)
                var position = offset
                while (buffer.hasRemaining()) {
                    val n = stream.channel.read(buffer, position)
                    if (n <= 0) break
                    position += n
                }
                if (buffer.hasRemaining()) null else buffer.array()
            }
        }
    }.getOrNull()
}

/** What the editor's video upload needs from the platform: the picked file's facts and a few frames of it. */
object EditorVideos {
    /** Name, size and duration of a picked video; null when the provider gives no usable size. Off the main thread. */
    suspend fun describe(context: Context, uri: Uri): PickedVideo? = withContext(Dispatchers.IO) {
        var name: String? = null
        var size = -1L
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0)
                    size = if (c.isNull(1)) -1 else c.getLong(1)
                }
            }
        }
        if (size <= 0) size = runCatching { context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } }.getOrNull() ?: -1
        if (size <= 0) return@withContext null
        val duration = runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            } finally {
                retriever.release()
            }
        }.getOrNull()
        val appContext = context.applicationContext
        // the upload model lets go of the video when it is done with it: a recording of ours is deleted then
        PickedVideo(UriVideoSource(context, uri, com.ecency.core.app.UploadNames.safe(name ?: "video.mp4"), size), duration, uri.toString()) {
            EditorImages.deleteIfCapture(appContext, uri)
        }
    }

    /**
     * A picked or recorded video into [model]: its facts, then its frames. A video that cannot be read or is not
     * taken (a short over 60 seconds) leaves no recording behind. False when the model did not take it.
     */
    suspend fun adopt(context: Context, model: VideoUploadModel, uri: Uri): Boolean {
        val video = describe(context, uri)
        if (video == null) {
            EditorImages.deleteIfCapture(context.applicationContext, uri)
            model.pickFailed()
            return false
        }
        val pick = model.pick(video) ?: return false
        model.setThumbs(pick, frames(context, uri, video.durationMillis))
        return true
    }

    /** A fresh content uri the camera app writes one recording to (the editor's camera folder, as for photos). */
    fun newCaptureUri(context: Context): Uri {
        val dir = java.io.File(context.cacheDir, "camera").apply { mkdirs() }
        val file = java.io.File(dir, "capture_${System.currentTimeMillis()}.mp4")
        return androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.editorfiles", file)
    }

    /**
     * RN _generateThumbs: [VideoUploadModel.THUMBS] frames from the middle of equal slices, scaled down, as
     * JPEGs without any metadata. A frame that fails is left out. Off the main thread.
     */
    suspend fun frames(context: Context, uri: Uri, durationMillis: Long?): List<PickedImage> = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            VideoUploadModel.thumbTimesMillis(durationMillis).mapIndexedNotNull { index, millis ->
                runCatching {
                    val frame = frameAt(retriever, millis * 1000) ?: return@runCatching null
                    jpeg(frame, "thumbnail-${index + 1}.jpg").also { frame.recycle() }
                }.getOrNull()
            }
        } catch (e: Exception) {
            emptyList()
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** One frame, [FRAME_PIXELS] on its long side at most: scaled by the decoder from Android 8.1 on, by hand before. */
    private fun frameAt(retriever: MediaMetadataRetriever, micros: Long): Bitmap? {
        if (android.os.Build.VERSION.SDK_INT >= 27) return retriever.getScaledFrameAtTime(micros, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, FRAME_PIXELS, FRAME_PIXELS)
        val full = retriever.getFrameAtTime(micros, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
        val long = maxOf(full.width, full.height)
        if (long <= FRAME_PIXELS) return full
        val scale = FRAME_PIXELS.toFloat() / long
        return full.scale((full.width * scale).toInt().coerceAtLeast(1), (full.height * scale).toInt().coerceAtLeast(1)).also { if (it !== full) full.recycle() }
    }

    internal fun jpeg(bitmap: Bitmap, name: String): PickedImage {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return PickedImage(out.toByteArray(), name, "image/jpeg", bitmap.width, bitmap.height)
    }

    const val FRAME_PIXELS = 1280
}


/**
 * "Record Video" with the system camera app (RN `ImagePicker.openCamera({mediaType: 'video'})`): the camera
 * writes into a file of the app's own cache folder through the editor's FileProvider, so no camera or storage
 * permission is asked for. One recording at a time.
 *
 * - The recording belongs to the account that started it ([Capture.owner]) and to the screen that asked
 *   ([Capture.tag]); [take] hands it to that screen once and only while that account is signed in.
 * - The camera app often pushes this process out of memory: what is open is written to the activity's saved
 *   state ([save], [restore]), so the result still finds its recording in the new process.
 * - No temporary file stays behind: a cancelled or empty recording is deleted in [onResult], one nobody takes
 *   by the next [record], an account change ([discard], which also drops one still open in the camera) or the
 *   next cold start ([sweep]), one that reached the
 *   upload model by the model ([PickedVideo.letGo]: uploaded, replaced, sheet closed, editor left).
 */
class VideoCaptures(context: Context, private val launch: (Uri) -> Boolean) {
    data class Capture(val uri: Uri, val owner: String, val tag: String)

    private val context = context.applicationContext
    private var open: Capture? = null

    /** Recordings dropped while the camera app was still open ([discard]); deleted again when it closes. */
    private val abandoned = mutableListOf<Uri>()
    private val waiting = kotlinx.coroutines.flow.MutableStateFlow<Capture?>(null)

    /** A finished recording nobody took yet. */
    val captured: kotlinx.coroutines.flow.StateFlow<Capture?> = waiting

    /** True while the camera app is open for a recording. */
    val isRecording: Boolean get() = open != null

    /** How a "Record Video" tap ended. */
    enum class Start { OPENED, BUSY, NO_CAMERA }

    /**
     * Opens the camera for [owner]'s screen [tag] (one tag per editor or composer instance, so a recording never
     * lands in another editor of the same account). [Start.BUSY] while a recording is still open: nothing is
     * launched and the open one stays as it is. [Start.NO_CAMERA]: there is no camera app (nothing stays behind).
     */
    fun record(owner: String, tag: String): Start {
        if (open != null) return Start.BUSY
        discard()
        val uri = EditorVideos.newCaptureUri(context)
        open = Capture(uri, owner, tag)
        val opened = runCatching { launch(uri) }.getOrDefault(false)
        if (!opened) {
            open = null
            EditorImages.deleteIfCapture(context, uri)
        }
        return if (opened) Start.OPENED else Start.NO_CAMERA
    }

    /** The camera app closed. Some camera apps answer "cancelled" after writing the file, so the file decides. */
    fun onResult(@Suppress("UNUSED_PARAMETER") recorded: Boolean) {
        // a recording dropped while the camera was open (the account changed): whatever the camera wrote since goes
        abandoned.forEach { EditorImages.deleteIfCapture(context, it) }
        abandoned.clear()
        val capture = open ?: return
        open = null
        if ((EditorImages.captureFile(context, capture.uri)?.length() ?: 0) > 0) waiting.value = capture else EditorImages.deleteIfCapture(context, capture.uri)
    }

    /**
     * The waiting recording for the screen [tag], once. It is only handed to the account that recorded it:
     * with anyone else signed in ([owner]) it is deleted.
     */
    fun take(owner: String?, tag: String): Uri? {
        val capture = waiting.value?.takeIf { it.tag == tag } ?: return null
        waiting.value = null
        if (capture.owner != owner) {
            EditorImages.deleteIfCapture(context, capture.uri)
            return null
        }
        return capture.uri
    }

    /**
     * The account changed or another recording starts: a finished recording nobody took is deleted, and one
     * still open in the camera app is dropped and deleted too, so its result reaches nobody. The camera may
     * write the file again before it closes: it is deleted once more when its result arrives ([onResult]).
     */
    fun discard() {
        waiting.value?.let { EditorImages.deleteIfCapture(context, it.uri) }
        waiting.value = null
        open?.let {
            EditorImages.deleteIfCapture(context, it.uri)
            abandoned += it.uri
        }
        open = null
    }

    fun save(state: android.os.Bundle) {
        fun put(key: String, capture: Capture?) {
            if (capture != null) state.putStringArray(key, arrayOf(capture.uri.toString(), capture.owner, capture.tag))
        }
        put(KEY_OPEN, open)
        put(KEY_WAITING, waiting.value)
        if (abandoned.isNotEmpty()) state.putStringArray(KEY_ABANDONED, abandoned.map { it.toString() }.toTypedArray())
    }

    fun restore(state: android.os.Bundle?) {
        fun get(key: String): Capture? = state?.getStringArray(key)?.takeIf { it.size == 3 }?.let { Capture(it[0].toUri(), it[1], it[2]) }
            // only a file of our own camera folder is ever read or deleted through a restored uri
            ?.takeIf { EditorImages.captureFile(context, it.uri) != null }
        open = get(KEY_OPEN)
        waiting.value = get(KEY_WAITING)
        abandoned.clear()
        state?.getStringArray(KEY_ABANDONED)?.map { it.toUri() }?.filterTo(abandoned) { EditorImages.captureFile(context, it) != null }
    }

    companion object {
        private const val KEY_OPEN = "videoCapture.open"
        private const val KEY_WAITING = "videoCapture.waiting"
        private const val KEY_ABANDONED = "videoCapture.abandoned"

        /**
         * A cold start: no recording or picked copy can be waiting for anyone, so every leftover video goes: recordings
         * (`capture_*.mp4`) and picked copies (`pick_*.mp4` or `.mov`, review L6).
         */
        fun sweep(context: Context) {
            java.io.File(context.cacheDir, "camera").listFiles()
                ?.filter { f -> f.extension.lowercase() in VIDEO_EXTENSIONS && (f.name.startsWith("capture_") || f.name.startsWith("pick_")) }
                ?.forEach { it.delete() }
        }

        private val VIDEO_EXTENSIONS = setOf("mp4", "mov")
    }
}

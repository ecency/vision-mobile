package com.ecency.app

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.ecency.core.api.ecency.ImageUploader
import com.ecency.core.app.DraftStorage
import com.ecency.core.app.PickedImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * Local drafts as private files: one folder per account, one file per slot, both named by the SHA-256 of their
 * key, so account and post names never appear in a path. Writes go to a temp file first and are renamed, so a
 * crash mid-write keeps the old draft. [tempDirs] (the shared camera capture folder and the dictation clips) are
 * emptied only when every account's drafts are cleared ([clearAll]: the wipe, logging every account out). One
 * account's logout leaves them alone ([clear]): another account may be mid-capture, a capture is deleted by
 * whoever holds it, and the shared teardown removes the dictation clips itself.
 * Both folders are kept out of backups and device transfer (res/xml/backup_rules.xml, data_extraction_rules.xml;
 * the cache folder is never backed up by Android).
 */
class FileDraftStorage(private val dir: File, private val tempDirs: List<File> = emptyList()) : DraftStorage {
    private fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun folder(account: String) = File(dir, hash(account))

    private fun file(account: String, slot: String) = File(folder(account), hash(slot) + ".json")

    @Synchronized
    override fun read(account: String, slot: String): String? = file(account, slot).takeIf { it.isFile }?.readText()

    @Synchronized
    override fun write(account: String, slot: String, value: String?) {
        val target = file(account, slot)
        if (value == null) {
            target.delete()
            return
        }
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + ".tmp")
        temp.writeText(value)
        if (!temp.renameTo(target)) {
            target.delete()
            check(temp.renameTo(target)) { "could not store the draft" }
        }
    }

    @Synchronized
    override fun clear(account: String) {
        // only this account's folder: the camera's temp folder is shared, and another account may be mid-capture
        folder(account).deleteRecursively()
    }

    @Synchronized
    override fun clearAll() {
        dir.deleteRecursively()
        tempDirs.forEach { it.deleteRecursively() }
    }
}

/** Picked images made ready for upload, the way RN's gallery does it. Everything here runs on [Dispatchers.IO]. */
object EditorImages {
    /** A fresh content uri the camera app writes one capture to. */
    fun newCameraUri(context: Context): Uri {
        val dir = File(context.cacheDir, "camera").apply { mkdirs() }
        // old photo captures are not kept around. Only photos: a recording (capture_*.mp4) in this folder may belong
        // to a failed upload that can still be continued, and its upload model deletes it when it lets go
        sweepStalePhotos(dir, System.currentTimeMillis())
        val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
        return FileProvider.getUriForFile(context, "${context.packageName}.editorfiles", file)
    }

    /** Deletes the photo captures in [dir] that are older than an hour. Never a video. */
    internal fun sweepStalePhotos(dir: File, nowMillis: Long) {
        dir.listFiles()?.filter { it.name.endsWith(".jpg") && nowMillis - it.lastModified() > 60 * 60 * 1000 }?.forEach { it.delete() }
    }

    /** True when the clipboard holds an image (only its description is read, so Android shows no paste notice). */
    fun clipboardHasImage(context: Context): Boolean = runCatching {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.primaryClipDescription?.let { d -> (0 until d.mimeTypeCount).any { d.getMimeType(it).startsWith("image/") } } == true
    }.getOrDefault(false)

    fun clipboardImage(context: Context): Uri? = runCatching {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
    }.getOrNull()

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    /** The EXIF orientation tag (1 to 8), 1 when there is none. */
    private fun orientation(context: Context, uri: Uri): Int = runCatching {
        context.contentResolver.openInputStream(uri)?.use { android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1) }
    }.getOrNull() ?: 1

    /** The transform that shows an image with EXIF [orientation] upright, after scaling by [scale]. All eight values. */
    internal fun uprightMatrix(orientation: Int, scale: Float): Matrix = Matrix().apply {
        postScale(scale, scale)
        when (orientation) {
            2 -> postScale(-1f, 1f)
            3 -> postRotate(180f)
            4 -> postScale(1f, -1f)
            5 -> { postRotate(90f); postScale(-1f, 1f) }
            6 -> postRotate(90f)
            7 -> { postRotate(270f); postScale(-1f, 1f) }
            8 -> postRotate(270f)
        }
    }

    /** A capture of ours (the editor's camera folder) is deleted once it was read, or when it is not needed. */
    fun deleteIfCapture(context: Context, uri: Uri) {
        runCatching { captureFile(context, uri)?.delete() }
    }

    /** The file behind a capture uri of ours (a photo or a video in the editor's camera folder), else null. */
    fun captureFile(context: Context, uri: Uri): File? {
        if (uri.authority != "${context.packageName}.editorfiles") return null
        val name = uri.lastPathSegment.orEmpty().substringAfterLast('/')
        if (name.isEmpty() || name.startsWith(".")) return null
        return File(File(context.cacheDir, "camera"), name)
    }

    /**
     * Reads [uri] for upload. Every still image is decoded and written again, so nothing of the file's metadata
     * (EXIF with GPS position, device, time; XMP; comments) is uploaded: the EXIF orientation is applied to the
     * pixels, a PNG within 1920 px stays a PNG, everything else becomes a JPEG at quality 85, scaled to 1920 px
     * on its long side when larger (RN MAX_IMAGE_DIMENSION, COMPRESS_QUALITY). Large images are decoded
     * subsampled, never at full size. A GIF goes as it is (re-encoding would drop its animation); GIFs carry no
     * EXIF, and only the 30 MB size limit is checked. Null when the image cannot be read.
     */
    private fun head(stream: java.io.InputStream): ByteArray {
        val out = ByteArray(6)
        var n = 0
        while (n < out.size) {
            val r = stream.read(out, n, out.size - n)
            if (r < 0) break
            n += r
        }
        return out.copyOf(n)
    }

    /** The GIF signature ("GIF87a" or "GIF89a"): only a real GIF skips the re-encode. */
    internal fun isGif(head: ByteArray?): Boolean =
        head != null && head.size >= 6 && String(head, 0, 6, Charsets.ISO_8859_1).let { it == "GIF87a" || it == "GIF89a" }

    suspend fun prepare(context: Context, uri: Uri, index: Int): PickedImage? = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
            val mime = (resolver.getType(uri) ?: bounds.outMimeType ?: "image/jpeg").lowercase()
            val rawName = displayName(context, uri)?.takeIf { it.contains('.') } ?: "img_${System.currentTimeMillis()}_$index.jpg"
            val max = ImageUploader.MAX_DIMENSION
            // the bytes decide, never the provider's type or the name: a "GIF" that is a JPEG would keep its EXIF
            if (isGif(resolver.openInputStream(uri)?.use(::head))) {
                val bytes = resolver.openInputStream(uri)?.use { stream ->
                    // stop reading once the file is known to be over the limit
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = stream.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        if (out.size() > ImageUploader.MAX_BYTES) break
                    }
                    out.toByteArray()
                } ?: return@runCatching null
                return@runCatching PickedImage(bytes, rawName, "image/gif", bounds.outWidth, bounds.outHeight)
            }
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= max || bounds.outHeight / (sample * 2) >= max) sample *= 2
            val decoded = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return@runCatching null
            val scale = minOf(1f, max.toFloat() / maxOf(decoded.width, decoded.height))
            val turn = orientation(context, uri)
            val bitmap = if (scale < 1f || turn != 1) Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, uprightMatrix(turn, scale), true) else decoded
            val keepPng = mime == "image/png" && scale == 1f
            val out = ByteArrayOutputStream()
            bitmap.compress(if (keepPng) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, ImageUploader.JPEG_QUALITY, out)
            val name = rawName.substringBeforeLast('.') + if (keepPng) ".png" else ".jpg"
            val picked = PickedImage(out.toByteArray(), name, if (keepPng) "image/png" else "image/jpeg", bitmap.width, bitmap.height)
            if (bitmap !== decoded) bitmap.recycle()
            decoded.recycle()
            picked
        }.getOrNull().also { deleteIfCapture(context, uri) }
    }

    /**
     * The avatar (RN IMAGE_PICKER_AVATAR_OPTIONS, `cropping: true`, 512 x 512): [prepare]d first (upright, no
     * metadata), then the centred square ([AvatarCrop]) scaled to at most 512 px, as a JPEG. Null when unreadable.
     */
    suspend fun prepareSquare(context: Context, uri: Uri): PickedImage? = withContext(Dispatchers.IO) {
        val upright = prepare(context, uri, 0) ?: return@withContext null
        runCatching {
            val decoded = BitmapFactory.decodeByteArray(upright.bytes, 0, upright.bytes.size) ?: return@runCatching null
            val (x, y, side) = com.ecency.core.app.AvatarCrop.square(decoded.width, decoded.height)
            val target = com.ecency.core.app.AvatarCrop.target(side)
            val square = Bitmap.createBitmap(decoded, x, y, side, side, Matrix().apply { setScale(target / side.toFloat(), target / side.toFloat()) }, true)
            val out = ByteArrayOutputStream()
            square.compress(Bitmap.CompressFormat.JPEG, ImageUploader.JPEG_QUALITY, out)
            val picked = PickedImage(out.toByteArray(), upright.name.substringBeforeLast('.') + ".jpg", "image/jpeg", square.width, square.height)
            if (square !== decoded) square.recycle()
            decoded.recycle()
            picked
        }.getOrNull()
    }
}

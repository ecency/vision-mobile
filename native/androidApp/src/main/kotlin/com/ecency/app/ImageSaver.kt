package com.ecency.app

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * RN imageViewer _onSavePress: downloads the image (https only, 30 s, at most 50 MB) and stores it in the "Ecency"
 * album of the gallery (Pictures/Ecency). Android 10 and later write through MediaStore without a permission; older
 * versions need WRITE_EXTERNAL_STORAGE (requested by the activity first, as RN does).
 */
object ImageSaver {
    const val ALBUM = "Ecency"
    internal const val MAX_BYTES = 50L * 1024 * 1024

    /** RN: the extension from the Content-Type (jpeg is jpg, svg+xml is svg), else from the URL path, else jpg. */
    fun extension(contentType: String?, url: String): String {
        val sub = contentType?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it.startsWith("image/") }?.removePrefix("image/")
        when (sub) {
            null, "" -> Unit
            "jpeg" -> return "jpg"
            "svg+xml" -> return "svg"
            else -> if (sub.all { it.isLetterOrDigit() }) return sub
        }
        val path = runCatching { URL(url).path }.getOrNull().orEmpty()
        val ext = path.substringAfterLast('/').substringAfterLast('.', "").lowercase()
        return ext.takeIf { it.length in 2..5 && it.all { c -> c.isLetterOrDigit() } } ?: "jpg"
    }

    fun mimeOf(ext: String): String = when (ext) {
        "jpg" -> "image/jpeg"
        "svg" -> "image/svg+xml"
        else -> "image/$ext"
    }

    /** An opened download: status, Content-Type, declared length (-1 unknown) and the body. */
    class Download(val status: Int, val contentType: String?, val contentLength: Long, val body: java.io.InputStream, val close: () -> Unit = {})

    private fun open(url: String): Download {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout = 30_000
        val status = conn.responseCode
        return Download(status, conn.contentType, conn.contentLengthLong, if (status == 200) conn.inputStream else java.io.ByteArrayInputStream(ByteArray(0))) { conn.disconnect() }
    }

    /** Throws with a message to show (RN `Alert.alert('Fail!', err.message)`). */
    suspend fun save(context: Context, url: String) = save(context, url, ::open)

    /**
     * https only. Refused before anything is written when the server declares more than [MAX_BYTES]; otherwise the
     * body streams straight into the gallery file with a byte budget, and a file that goes past it is deleted.
     */
    internal suspend fun save(context: Context, url: String, opener: (String) -> Download) = withContext(Dispatchers.IO) {
        require(url.startsWith("https://")) { AndroidCopy.IMAGE_UNSUPPORTED_ADDRESS }
        val download = opener(url)
        try {
            if (download.status != 200) throw IllegalStateException(AndroidCopy.imageDownloadFailed(download.status))
            if (download.contentLength > MAX_BYTES) throw IllegalStateException(TOO_LARGE)
            val ext = extension(download.contentType, url)
            val name = "ecency_${System.currentTimeMillis()}.$ext"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, mimeOf(ext))
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: throw IllegalStateException(AndroidCopy.IMAGE_CREATE_FAILED)
                try {
                    (resolver.openOutputStream(uri) ?: throw IllegalStateException(AndroidCopy.IMAGE_WRITE_FAILED)).use { copy(download.body, it) }
                    resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                } catch (e: Exception) {
                    // the pending row of an aborted download never shows in the gallery
                    resolver.delete(uri, null, null)
                    throw e
                }
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), ALBUM).apply { mkdirs() }
                val file = File(dir, name)
                try {
                    file.outputStream().use { copy(download.body, it) }
                } catch (e: Exception) {
                    file.delete()
                    throw e
                }
                MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(mimeOf(ext)), null)
            }
        } finally {
            runCatching { download.body.close() }
            download.close()
        }
    }

    val TOO_LARGE: String get() = AndroidCopy.IMAGE_TOO_LARGE

    /** Copies at most [MAX_BYTES]; past that it throws and the caller drops what was written. */
    internal fun copy(input: java.io.InputStream, output: java.io.OutputStream, max: Long = MAX_BYTES) {
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > max) throw IllegalStateException(TOO_LARGE)
            output.write(buf, 0, n)
        }
    }
}

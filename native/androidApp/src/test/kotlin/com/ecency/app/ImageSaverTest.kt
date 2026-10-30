package com.ecency.app

import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.InputStream

/** The image viewer's "save": https only, the size cap before and while streaming, no half file left behind. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ImageSaverTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private var opened = 0

    /** A body of [size] bytes produced on the fly (never held in memory). */
    private fun body(size: Long) = object : InputStream() {
        var left = size
        override fun read(): Int = if (left-- > 0) 1 else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = minOf(len.toLong(), left).toInt()
            left -= n
            return n
        }
    }

    private fun download(size: Long, declared: Long = -1) = { _: String ->
        opened++
        ImageSaver.Download(200, "image/png", declared, body(size))
    }

    private fun error(block: suspend () -> Unit): String? = try {
        runBlocking { block() }
        null
    } catch (e: Exception) {
        e.message
    }

    @Test
    fun onlyHttpsImagesAreFetched() {
        assertEquals("Unsupported image address", error { ImageSaver.save(context, "http://example.com/a.png", download(10)) })
        assertEquals("Unsupported image address", error { ImageSaver.save(context, "file:///sdcard/a.png", download(10)) })
        assertEquals(0, opened)
        assertEquals(null, error { ImageSaver.save(context, "https://example.com/a.png", download(10)) })
    }

    @Test
    fun aDeclaredSizeOverTheCapIsRefusedBeforeAnythingIsWritten() {
        val resolver = shadowOf(context.contentResolver)
        assertEquals(ImageSaver.TOO_LARGE, error { ImageSaver.save(context, "https://example.com/a.png", download(10, declared = ImageSaver.MAX_BYTES + 1)) })
        assertTrue(resolver.insertStatements.isEmpty())
    }

    @Test
    fun aBodyThatRunsPastTheCapIsAbortedAndItsPendingFileDeleted() {
        val resolver = shadowOf(context.contentResolver)
        assertEquals(ImageSaver.TOO_LARGE, error { ImageSaver.save(context, "https://example.com/a.png", download(ImageSaver.MAX_BYTES + 1)) })
        val inserted = resolver.insertStatements.single()
        assertEquals(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, inserted.uri)
        assertTrue("the pending row is removed", resolver.deleteStatements.isNotEmpty())
        assertFalse(resolver.updateStatements.any { it.contentValues?.getAsInteger(MediaStore.Images.Media.IS_PENDING) == 0 })
    }

    @Test
    fun theCopyKeepsWithinItsBudget() {
        val out = java.io.ByteArrayOutputStream()
        ImageSaver.copy(body(100), out, max = 100)
        assertEquals(100, out.size())
        assertEquals(ImageSaver.TOO_LARGE, runCatching { ImageSaver.copy(body(101), java.io.ByteArrayOutputStream(), max = 100) }.exceptionOrNull()?.message)
    }
}

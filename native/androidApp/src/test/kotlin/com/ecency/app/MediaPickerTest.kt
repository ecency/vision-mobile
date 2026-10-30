package com.ecency.app

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.ecency.core.app.MediaSurface
import com.ecency.core.app.MediaTypes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.coroutines.launch
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Android media picking layer (RN #3564 / #3561): which picker opens, what the document picker asks for, what is kept. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MediaPickerTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun theSystemPhotoPickerOnlyWhereThePlatformHasIt() {
        // Android 13 and later: built in
        assertTrue(MediaPickers.systemPhotoPickerAvailable(33) { 0 })
        assertTrue(MediaPickers.systemPhotoPickerAvailable(35) { 0 })
        // Android 11 and 12: only with the SDK extension (R extension 2)
        assertTrue(MediaPickers.systemPhotoPickerAvailable(30) { 2 })
        assertTrue(MediaPickers.systemPhotoPickerAvailable(31) { 7 })
        assertFalse(MediaPickers.systemPhotoPickerAvailable(31) { 1 })
        // Android 10 and older (the Play services backport fails there): the document picker
        assertFalse(MediaPickers.systemPhotoPickerAvailable(29) { 99 })
        assertFalse(MediaPickers.systemPhotoPickerAvailable(24) { 99 })
        // the extension is not even asked about below 30
        assertFalse(MediaPickers.systemPhotoPickerAvailable(29) { throw IllegalStateException("not asked") })
    }

    @Test
    fun theDocumentPickerAsksForTheSurfacesTypesOnly() {
        val editor = OpenMediaDocuments(MediaSurface.EDITOR).createIntent(context, Unit)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, editor.action)
        assertTrue(editor.hasCategory(Intent.CATEGORY_OPENABLE))
        assertEquals("image/*", editor.type)
        assertArrayEquals(MediaTypes.IMAGE_MIMES.toTypedArray(), editor.getStringArrayExtra(Intent.EXTRA_MIME_TYPES))
        assertTrue(editor.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))

        val video = OpenMediaDocuments(MediaSurface.VIDEO).createIntent(context, Unit)
        assertEquals("video/*", video.type)
        assertArrayEquals(arrayOf("video/mp4", "video/quicktime"), video.getStringArrayExtra(Intent.EXTRA_MIME_TYPES))
        assertFalse(video.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, true))

        for (single in listOf(MediaSurface.WAVE, MediaSurface.CHAT, MediaSurface.AVATAR, MediaSurface.COVER, MediaSurface.VIDEO_THUMBNAIL)) {
            val intent = OpenMediaDocuments(single).createIntent(context, Unit)
            assertFalse(single.name, intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, true))
            assertEquals(single.name, "image/*", intent.type)
        }
    }

    @Test
    fun theDocumentPickersAnswerIsRead() {
        val contract = OpenMediaDocuments(MediaSurface.EDITOR)
        val a = Uri.parse("content://docs/a.jpg")
        val b = Uri.parse("content://docs/b.png")
        val several = Intent().apply { clipData = ClipData.newRawUri("", a).also { it.addItem(ClipData.Item(b)) } }
        assertEquals(listOf(a, b), contract.parseResult(Activity.RESULT_OK, several))
        assertEquals(listOf(a), contract.parseResult(Activity.RESULT_OK, Intent().setData(a)))
        assertEquals(emptyList<Uri>(), contract.parseResult(Activity.RESULT_CANCELED, Intent().setData(a)))
        assertEquals(emptyList<Uri>(), contract.parseResult(Activity.RESULT_OK, null))
    }

    @Test
    fun onlyAllowedTypesAreKeptUpToTheSurfacesLimit() {
        val resolver = context.contentResolver
        var said: String? = null
        val images = listOf("a.jpg", "b.heic", "c.webp", "d.gif", "e.png").map { Uri.parse("file:///picked/$it") }
        assertEquals(images, MediaPickers.accept(resolver, MediaSurface.EDITOR, images) { said = it })
        assertNull(said)

        // a type outside the list is left out, with the message that names what is accepted
        val mixed = listOf(Uri.parse("file:///picked/a.jpg"), Uri.parse("file:///picked/scan.tiff"), Uri.parse("file:///picked/page.pdf"))
        assertEquals(mixed.take(1), MediaPickers.accept(resolver, MediaSurface.EDITOR, mixed) { said = it })
        assertEquals(MediaTypes.IMAGE_TYPES_MESSAGE, said)

        // more than the surface takes: the first ones, and RN's limit message
        said = null
        val many = (1..7).map { Uri.parse("file:///picked/$it.jpg") }
        assertEquals(many.take(5), MediaPickers.accept(resolver, MediaSurface.EDITOR, many) { said = it })
        assertEquals("You can select up to 5 images at a time", said)
        assertEquals(many.take(1), MediaPickers.accept(resolver, MediaSurface.WAVE, many) { })

        // a video surface takes MP4 and MOV only
        said = null
        assertEquals(emptyList<Uri>(), MediaPickers.accept(resolver, MediaSurface.VIDEO, listOf(Uri.parse("file:///picked/clip.webm"))) { said = it })
        assertEquals(MediaTypes.VIDEO_TYPES_MESSAGE, said)
        val mov = Uri.parse("file:///picked/clip.MOV")
        assertEquals(listOf(mov), MediaPickers.accept(resolver, MediaSurface.VIDEO, listOf(mov)) { })
        // and an image surface never takes a video
        assertEquals(emptyList<Uri>(), MediaPickers.accept(resolver, MediaSurface.CHAT, listOf(mov)) { })
    }

    @Test
    fun aPickedVideoIsCopiedIntoTheAppsOwnCache() = kotlinx.coroutines.runBlocking {
        // FileProvider keeps its roots per authority for the process: another test's sandbox directory may be cached
        runCatching { androidx.core.content.FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }.let { (it.get(null) as MutableMap<*, *>).clear() } }
        val source = java.io.File(context.cacheDir, "source.mp4").apply { writeBytes(ByteArray(300_000) { (it % 251).toByte() }) }
        val copy = MediaPickers.copyVideoToCache(context, Uri.fromFile(source))!!
        assertEquals("${context.packageName}.editorfiles", copy.authority)
        val file = EditorImages.captureFile(context, copy)!!
        assertArrayEquals(source.readBytes(), file.readBytes())
        // the copy is the app's: deleting it goes the way of a recording
        EditorImages.deleteIfCapture(context, copy)
        assertFalse(file.exists())
        assertNull(MediaPickers.copyVideoToCache(context, Uri.fromFile(java.io.File(context.cacheDir, "missing.mp4"))))
    }

    @Test
    fun aCancelledCopyLeavesNoPartialFile() = kotlinx.coroutines.runBlocking {
        // review L6: leaving the sheet stops a large copy
        val endless = object : java.io.InputStream() {
            override fun read(): Int = 1
            override fun read(b: ByteArray, off: Int, len: Int): Int { java.util.Arrays.fill(b, off, off + len, 1); return len }
        }
        val dir = java.io.File(context.cacheDir, "camera").apply { deleteRecursively() }
        org.robolectric.Shadows.shadowOf(context.contentResolver).registerInputStream(Uri.parse("content://docs/huge.mp4"), endless)
        val job = launch(kotlinx.coroutines.Dispatchers.Default) { MediaPickers.copyVideoToCache(context, Uri.parse("content://docs/huge.mp4")) }
        kotlinx.coroutines.delay(200)
        job.cancel()
        job.join()
        assertTrue("no partial copy stays: ${dir.list()?.toList()}", dir.listFiles().orEmpty().none { it.name.startsWith("pick_") })
    }
}

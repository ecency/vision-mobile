package com.ecency.app

import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** The Android side of the video upload: the picked file is read in pieces, frames become plain JPEGs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VideoPlatformTest {
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    /**
     * FileProvider remembers its folders per authority for the life of the JVM, while Robolectric gives every
     * test a new data folder: without this the second test's capture uri would point into the first test's.
     */
    @Before
    fun forgetFileProviderRoots() {
        val cache = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }.get(null)
        @Suppress("UNCHECKED_CAST")
        synchronized(cache as Any) { (cache as MutableMap<String, Any>).clear() }
    }

    @Test
    fun aPickedFileIsReadPieceByPiece() {
        val bytes = ByteArray(10_000) { (it % 127).toByte() }
        val file = File(context.cacheDir, "video-test.mp4").apply { writeBytes(bytes) }
        val source = UriVideoSource(context, Uri.fromFile(file), "video-test.mp4", bytes.size.toLong())
        assertArrayEquals(bytes.copyOfRange(0, 4000), source.read(0, 4000))
        assertArrayEquals(bytes.copyOfRange(4000, 8000), source.read(4000, 4000))
        assertArrayEquals(bytes.copyOfRange(8000, 10_000), source.read(8000, 2000))
        assertNull("a piece that is not all there is no piece", source.read(9000, 4000))
        file.delete()
        assertNull(source.read(0, 10))
    }

    @Test
    fun describingAFileGivesItsSizeAndASafeName() = runBlocking {
        val file = File(context.cacheDir, "my video (1).mp4").apply { writeBytes(ByteArray(1234)) }
        val video = EditorVideos.describe(context, Uri.fromFile(file))!!
        assertEquals(1234L, video.source.size)
        assertTrue(video.source.name.none { it == ' ' || it == '(' })
        assertNull(EditorVideos.describe(context, Uri.fromFile(File(context.cacheDir, "missing.mp4"))))
    }

    @Test
    fun framesAreJpegsWithTheirSize() {
        val image = EditorVideos.jpeg(Bitmap.createBitmap(64, 36, Bitmap.Config.ARGB_8888), "thumbnail-1.jpg")
        assertEquals("image/jpeg", image.mime)
        assertEquals(64, image.width)
        assertEquals(36, image.height)
        assertTrue(image.bytes.isNotEmpty())
    }

    // ---- "Record Video": the recording's temporary file ----

    private fun cameraFiles() = File(context.cacheDir, "camera").listFiles()?.map { it.name }.orEmpty()

    /** A camera app that writes [bytes] into the uri it is handed (null: it writes nothing). */
    private fun captures(bytes: () -> ByteArray?, opens: Boolean = true) = VideoCaptures(context) { uri ->
        if (opens) bytes()?.let { EditorImages.captureFile(context, uri)!!.writeBytes(it) }
        opens
    }

    @Test
    fun aRecordingIsHandedOnceToTheScreenAndAccountThatMadeIt() {
        File(context.cacheDir, "camera").deleteRecursively()
        val c = captures({ ByteArray(100) })
        assertEquals(VideoCaptures.Start.OPENED, c.record("alice", "editor"))
        assertTrue(c.isRecording)
        assertNull("nothing is handed out while the camera is open", c.captured.value)
        c.onResult(true)
        val capture = c.captured.value!!
        assertEquals("alice", capture.owner)
        assertEquals(context.packageName + ".editorfiles", capture.uri.authority)
        assertNull("another screen does not get it", c.take("alice", "wave"))
        assertEquals(capture.uri, c.take("alice", "editor"))
        assertNull("once", c.take("alice", "editor"))
        assertEquals(1, cameraFiles().size)
        // the upload model lets go of it: the file is deleted
        EditorImages.deleteIfCapture(context, capture.uri)
        assertTrue(cameraFiles().isEmpty())
    }

    @Test
    fun aRecordingNeverReachesAnotherAccountAndLeavesNoFile() {
        File(context.cacheDir, "camera").deleteRecursively()
        val c = captures({ ByteArray(100) })
        c.record("alice", "editor")
        c.onResult(true)
        assertEquals(1, cameraFiles().size)
        assertNull(c.take("bob", "editor"))
        assertTrue("deleted, not kept for later", cameraFiles().isEmpty())
        assertNull(c.take("alice", "editor"))
        // signed out
        c.record("alice", "editor")
        c.onResult(true)
        assertNull(c.take(null, "editor"))
        assertTrue(cameraFiles().isEmpty())
    }

    @Test
    fun cancelledEmptyUntakenAndUnopenedRecordingsAreDeleted() {
        File(context.cacheDir, "camera").deleteRecursively()
        var bytes: ByteArray? = null
        val c = captures({ bytes })
        // cancelled: no file was written
        c.record("alice", "editor")
        c.onResult(false)
        assertNull(c.captured.value)
        // an empty file
        bytes = ByteArray(0)
        c.record("alice", "editor")
        c.onResult(true)
        assertNull(c.captured.value)
        assertTrue(cameraFiles().isEmpty())
        // a camera app that answers "cancelled" after writing: the file decides
        bytes = ByteArray(10)
        c.record("alice", "editor")
        c.onResult(false)
        assertEquals(1, cameraFiles().size)
        // nobody took it and another recording starts, or the account changes
        c.record("alice", "editor")
        assertEquals("the untaken one went, the new one is being written", 1, cameraFiles().size)
        c.onResult(true)
        c.discard()
        assertNull(c.captured.value)
        assertTrue(cameraFiles().isEmpty())
        // no camera app at all
        val none = captures({ ByteArray(10) }, opens = false)
        assertEquals(VideoCaptures.Start.NO_CAMERA, none.record("alice", "editor"))
        assertEquals(false, none.isRecording)
        assertTrue(cameraFiles().isEmpty())
        // a cold start sweeps what an earlier run left
        File(context.cacheDir, "camera").apply { mkdirs() }.let {
            File(it, "capture_1.mp4").writeBytes(ByteArray(5)); File(it, "capture_2.jpg").writeBytes(ByteArray(5))
            // picked copies of both kinds (review L6: a MOV copy was never swept)
            File(it, "pick_3.mp4").writeBytes(ByteArray(5)); File(it, "pick_4.mov").writeBytes(ByteArray(5))
        }
        VideoCaptures.sweep(context)
        assertEquals("photos have their own cleanup", listOf("capture_2.jpg"), cameraFiles())
    }

    @Test
    fun anAccountChangeDropsAndDeletesTheRecordingStillOpenInTheCamera() {
        File(context.cacheDir, "camera").deleteRecursively()
        var launches = 0
        val c = VideoCaptures(context) { uri ->
            launches++
            EditorImages.captureFile(context, uri)!!.writeBytes(ByteArray(100))
            true
        }
        c.record("alice", "editor:1")
        val uri = EditorImages.captureFile(context, c.let { android.os.Bundle().also(it::save) }.getStringArray("videoCapture.open")!![0].let(android.net.Uri::parse))!!
        assertEquals(1, cameraFiles().size)
        // the account changes while the camera app is open
        c.discard()
        assertFalse("the open recording is dropped", c.isRecording)
        assertTrue("and its file deleted", cameraFiles().isEmpty())
        // the camera app writes on and then closes: its result reaches nobody and its file goes again
        uri.writeBytes(ByteArray(200))
        val saved = android.os.Bundle().also(c::save)
        c.onResult(true)
        assertNull(c.captured.value)
        assertNull(c.take("alice", "editor:1"))
        assertTrue(cameraFiles().isEmpty())
        // the same after a new process: the dropped recording is remembered through the saved state
        uri.writeBytes(ByteArray(200))
        val restored = captures({ null })
        restored.restore(saved)
        assertFalse(restored.isRecording)
        restored.onResult(true)
        assertNull(restored.captured.value)
        assertTrue(cameraFiles().isEmpty())
        assertEquals(1, launches)
    }

    @Test
    fun aSecondRecordWhileTheCameraIsOpenIsBusyAndLaunchesNothing() {
        File(context.cacheDir, "camera").deleteRecursively()
        var launches = 0
        val c = VideoCaptures(context) { uri ->
            launches++
            EditorImages.captureFile(context, uri)!!.writeBytes(ByteArray(100))
            true
        }
        assertEquals(VideoCaptures.Start.OPENED, c.record("alice", "editor:1"))
        assertEquals(VideoCaptures.Start.BUSY, c.record("alice", "editor:2"))
        assertEquals("nothing was launched for the second", 1, launches)
        assertEquals("the first recording stays as it is", 1, cameraFiles().size)
        c.onResult(true)
        assertNull("the busy caller gets nothing", c.take("alice", "editor:2"))
        assertTrue(c.take("alice", "editor:1") != null)
        File(context.cacheDir, "camera").deleteRecursively()
    }

    @Test
    fun aRecordingOnlyReachesTheEditorInstanceThatAskedForIt() {
        File(context.cacheDir, "camera").deleteRecursively()
        val c = captures({ ByteArray(100) })
        c.record("alice", "editor:first")
        c.onResult(true)
        assertNull("another editor of the same account does not get it", c.take("alice", "editor:second"))
        assertEquals("and the recording is still there for its own", 1, cameraFiles().size)
        assertTrue(c.take("alice", "editor:first") != null)
        File(context.cacheDir, "camera").deleteRecursively()
    }

    @Test
    fun theOpenRecordingSurvivesANewProcessThroughSavedState() {
        File(context.cacheDir, "camera").deleteRecursively()
        val first = captures({ ByteArray(100) })
        first.record("alice", "wave")
        val saved = android.os.Bundle().also(first::save)
        // the process is gone; the activity comes back with its saved state and then the camera's result
        val second = captures({ null })
        second.restore(saved)
        assertTrue(second.isRecording)
        second.onResult(true)
        assertEquals("alice", second.captured.value?.owner)
        val again = android.os.Bundle().also(second::save)
        val third = captures({ null })
        third.restore(again)
        assertTrue(third.take("alice", "wave") != null)
        // a saved uri that is not a capture of ours is never trusted
        val forged = android.os.Bundle().apply { putStringArray("videoCapture.waiting", arrayOf("content://other.app/secret.mp4", "alice", "wave")) }
        val fourth = captures({ null })
        fourth.restore(forged)
        assertNull(fourth.captured.value)
        File(context.cacheDir, "camera").deleteRecursively()
    }
}

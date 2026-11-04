package com.ecency.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.ExifInterface
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.File

/** The Android side of the editor: image preparation (real codecs through Robolectric's native graphics) and draft files. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorPlatformTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** A JPEG made here (never a real photo) with a GPS position, camera fields and an orientation. */
    private fun jpegWithMetadata(width: Int, height: Int, orientation: Int): File {
        val file = File(context.cacheDir, "fixture_${width}_$orientation.jpg")
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(20, 120, 200)) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        ExifInterface(file.path).apply {
            setAttribute(ExifInterface.TAG_GPS_LATITUDE, "54/1,41/1,14/1")
            setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "25/1,16/1,47/1")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "E")
            setAttribute(ExifInterface.TAG_MAKE, "SpikeCam")
            setAttribute(ExifInterface.TAG_DATETIME, "2026:10:06 12:00:00")
            setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            saveAttributes()
        }
        return file
    }

    @Test
    fun gpsAndCameraMetadataNeverLeaveTheDeviceSmallOrLarge() = runBlocking {
        for ((w, h) in listOf(800 to 600, 4000 to 3000)) {
            val file = jpegWithMetadata(w, h, orientation = 6)
            assertNotNull("the fixture really carries a position", ExifInterface(file.path).getAttribute(ExifInterface.TAG_GPS_LATITUDE))
            val picked = EditorImages.prepare(context, Uri.fromFile(file), 0)!!
            val exif = ExifInterface(ByteArrayInputStream(picked.bytes))
            assertNull("no position in what is uploaded ($w px)", exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
            assertNull(exif.getAttribute(ExifInterface.TAG_MAKE))
            assertNull(exif.getAttribute(ExifInterface.TAG_DATETIME))
            assertFalse(String(picked.bytes, Charsets.ISO_8859_1).contains("SpikeCam"))
            assertEquals("image/jpeg", picked.mime)
            // orientation 6 is applied to the pixels: the upright image is portrait, 1920 px at most
            assertTrue(picked.width < picked.height)
            assertTrue(maxOf(picked.width, picked.height) <= 1920)
            if (w > 1920) assertEquals(1920, picked.height)
        }
    }

    @Test
    fun theAvatarIsTheUprightCentredSquareAt512WithoutMetadata() = runBlocking {
        // 4000 x 3000 turned by orientation 6: upright 1440 x 1920 after prepare, its centred square scaled to 512
        val file = jpegWithMetadata(4000, 3000, orientation = 6)
        val avatar = EditorImages.prepareSquare(context, Uri.fromFile(file))!!
        assertEquals(512, avatar.width)
        assertEquals(512, avatar.height)
        assertEquals("image/jpeg", avatar.mime)
        assertNull(ExifInterface(ByteArrayInputStream(avatar.bytes)).getAttribute(ExifInterface.TAG_GPS_LATITUDE))
        // a small picture is cropped but never upscaled
        val small = EditorImages.prepareSquare(context, Uri.fromFile(jpegWithMetadata(300, 200, orientation = 1)))!!
        assertEquals(200, small.width)
        assertEquals(200, small.height)
        val junk = File(context.cacheDir, "junk.jpg").apply { writeText("not an image") }
        assertNull(EditorImages.prepareSquare(context, Uri.fromFile(junk)))
    }

    @Test
    fun everyExifOrientationIsMadeUpright() {
        // where the corner (0,0) of a 4x2 image lands, per EXIF orientation
        fun map(orientation: Int): Pair<Int, Int> {
            val m = EditorImages.uprightMatrix(orientation, 1f)
            val rect = android.graphics.RectF(0f, 0f, 4f, 2f)
            m.mapRect(rect)
            val p = floatArrayOf(0.5f, 0.5f)
            m.mapPoints(p)
            return Math.round(p[0] - rect.left - 0.5f) to Math.round(p[1] - rect.top - 0.5f)
        }
        assertEquals(0 to 0, map(1))
        assertEquals(3 to 0, map(2)) // mirrored horizontally
        assertEquals(3 to 1, map(3)) // rotated 180
        assertEquals(0 to 1, map(4)) // mirrored vertically
        assertEquals(0 to 0, map(5)) // transposed
        assertEquals(1 to 0, map(6)) // rotated 90 clockwise
        assertEquals(1 to 3, map(7)) // transverse
        assertEquals(0 to 3, map(8)) // rotated 270
    }

    /** A provider that serves one file under whatever type it claims (a hostile or confused document provider). */
    class LyingProvider : android.content.ContentProvider() {
        override fun onCreate() = true
        override fun getType(uri: Uri) = claimed
        override fun openFile(uri: Uri, mode: String): android.os.ParcelFileDescriptor =
            android.os.ParcelFileDescriptor.open(served!!, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
        override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): android.database.Cursor? = null
        override fun insert(uri: Uri, values: android.content.ContentValues?): Uri? = null
        override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
        override fun update(uri: Uri, v: android.content.ContentValues?, s: String?, a: Array<out String>?) = 0
        companion object {
            var claimed: String = "image/gif"
            var served: File? = null
        }
    }

    @Test
    fun aJpegClaimedAsGifIsReEncodedWithoutItsLocation() = runBlocking {
        // review M2: the provider says image/gif over JPEG bytes with a GPS position
        LyingProvider.served = jpegWithMetadata(640, 480, orientation = 1)
        LyingProvider.claimed = "image/gif"
        org.robolectric.Robolectric.setupContentProvider(LyingProvider::class.java, "spike.lying")
        val picked = EditorImages.prepare(context, Uri.parse("content://spike.lying/photo.gif"), 0)!!
        assertEquals("image/jpeg", picked.mime)
        assertNull(ExifInterface(ByteArrayInputStream(picked.bytes)).getAttribute(ExifInterface.TAG_GPS_LATITUDE))
        assertFalse(String(picked.bytes, Charsets.ISO_8859_1).contains("SpikeCam"))
        assertTrue(EditorImages.isGif("GIF89a..".toByteArray()))
        assertFalse(EditorImages.isGif(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0, 0, 0)))
        assertFalse(EditorImages.isGif(null))
    }

    @Test
    fun takingAPhotoSweepsOldPhotosAndNeverARecording() {
        val dir = File(context.cacheDir, "camera").apply { mkdirs() }
        val old = System.currentTimeMillis() - 2 * 60 * 60 * 1000
        val oldPhoto = File(dir, "capture_1.jpg").apply { writeText("x"); setLastModified(old) }
        val freshPhoto = File(dir, "capture_2.jpg").apply { writeText("x") }
        // a recording a failed upload can still continue, older than the photo sweep's hour
        val oldVideo = File(dir, "capture_3.mp4").apply { writeText("x"); setLastModified(old) }
        // the sweep newCameraUri runs before every photo (called here without the FileProvider, whose roots Robolectric caches across tests)
        EditorImages.sweepStalePhotos(dir, System.currentTimeMillis())
        assertFalse(oldPhoto.exists())
        assertTrue(freshPhoto.exists())
        assertTrue("a recording is deleted by the upload model that holds it, never by the photo sweep", oldVideo.exists())
        listOf(freshPhoto, oldVideo).forEach { it.delete() }
    }

    @Test
    fun aCaptureIsDeletedOnceItWasReadAndGarbageIsRefused() = runBlocking {
        val uri = EditorImages.newCameraUri(context)
        val file = File(File(context.cacheDir, "camera"), uri.lastPathSegment!!.substringAfterLast('/'))
        jpegWithMetadata(64, 64, 1).copyTo(file, overwrite = true)
        assertTrue(file.exists())
        EditorImages.deleteIfCapture(context, uri)
        assertFalse("a capture never stays in the cache", file.exists())
        val junk = File(context.cacheDir, "junk.jpg").apply { writeText("not an image") }
        assertNull(EditorImages.prepare(context, Uri.fromFile(junk), 0))
    }

    @Test
    fun draftFilesAreKeptPerAccountHideTheirKeysAndClear() {
        val dir = File(context.filesDir, "drafts-test")
        val camera = File(context.cacheDir, "camera-test").apply { mkdirs() }
        File(camera, "capture.jpg").writeText("x")
        val storage = FileDraftStorage(dir, listOf(camera))
        storage.write("spike-alice", "new", "{\"title\":\"Hello\"}")
        storage.write("spike-alice", "reply:a/b", "r")
        storage.write("spike-bob", "new", "bob")
        assertEquals("{\"title\":\"Hello\"}", storage.read("spike-alice", "new"))
        assertTrue(dir.walkTopDown().none { it.name.contains("spike") || it.name.contains("reply") })
        storage.clear("spike-alice")
        assertNull(storage.read("spike-alice", "new"))
        assertNull(storage.read("spike-alice", "reply:a/b"))
        assertEquals("bob", storage.read("spike-bob", "new"))
        assertTrue("one account's logout leaves the shared camera folder alone (another account may be mid-capture)", File(camera, "capture.jpg").exists())
        storage.clearAll()
        assertNull(storage.read("spike-bob", "new"))
        assertFalse("capture temp files go when everything is cleared", File(camera, "capture.jpg").exists())
        // backups: the drafts folder is excluded in both rule files
        val res = context.resources
        listOf(R.xml.backup_rules, R.xml.data_extraction_rules).forEach { id ->
            val parser = res.getXml(id)
            var found = false
            while (parser.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "exclude" &&
                    parser.getAttributeValue(null, "domain") == "file" && parser.getAttributeValue(null, "path") == "drafts/"
                ) found = true
            }
            assertTrue(found)
        }
    }
}

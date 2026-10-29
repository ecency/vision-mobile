package com.ecency.core.app

import com.ecency.core.api.ecency.ImageUploader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The one media allow-list and the surfaces' limits (RN #3564 / #3561: one picking layer for images and videos). */
class MediaPickingTest {
    @Test
    fun onlyTheListedImageAndVideoTypesPass() {
        for (mime in listOf("image/jpeg", "image/png", "image/gif", "image/webp", "image/heic", "image/heif", "IMAGE/JPEG", "image/jpg", "image/jpeg; charset=x")) {
            assertIs<MediaCheck.Accepted>(MediaTypes.check(MediaKind.IMAGE, mime), mime)
        }
        for (mime in listOf("video/mp4", "video/quicktime", "video/mov")) assertIs<MediaCheck.Accepted>(MediaTypes.check(MediaKind.VIDEO, mime), mime)
        for (mime in listOf("image/tiff", "image/bmp", "image/svg+xml", "application/pdf", "text/html", "video/mp4", "image/x-adobe-dng")) {
            assertEquals(MediaCheck.Rejected(MediaTypes.IMAGE_TYPES_MESSAGE), MediaTypes.check(MediaKind.IMAGE, mime), mime)
        }
        for (mime in listOf("video/webm", "video/x-matroska", "video/3gpp", "image/jpeg", "audio/mpeg")) {
            assertEquals(MediaCheck.Rejected(MediaTypes.VIDEO_TYPES_MESSAGE), MediaTypes.check(MediaKind.VIDEO, mime), mime)
        }
        // nothing known about the item: refused
        assertIs<MediaCheck.Rejected>(MediaTypes.check(MediaKind.IMAGE, null))
        assertIs<MediaCheck.Rejected>(MediaTypes.check(MediaKind.IMAGE, "application/octet-stream", "x.bin"))
    }

    @Test
    fun theTypeFallsBackToTheUtTypeThenTheExtension() {
        assertEquals("image/heic", MediaTypes.resolve(null, null, "public.heic"))
        assertEquals("video/quicktime", MediaTypes.resolve(null, null, "com.apple.quicktime-movie"))
        assertEquals("image/jpeg", MediaTypes.resolve("application/octet-stream", "IMG_1.JPG"))
        assertEquals("video/mp4", MediaTypes.resolve("", "clip.m4v"))
        // the provider's own type wins over a misleading name
        assertEquals("image/png", MediaTypes.resolve("image/png", "photo.jpg"))
        assertIs<MediaCheck.Rejected>(MediaTypes.check(MediaKind.IMAGE, null, "evil.svg"))
        assertEquals(MediaTypes.IMAGE_UTTYPES.size, MediaTypes.IMAGE_MIMES.size)
        MediaTypes.IMAGE_UTTYPES.forEach { assertIs<MediaCheck.Accepted>(MediaTypes.check(MediaKind.IMAGE, null, utType = it), it) }
        MediaTypes.VIDEO_UTTYPES.forEach { assertIs<MediaCheck.Accepted>(MediaTypes.check(MediaKind.VIDEO, null, utType = it), it) }
    }

    @Test
    fun heicIsConvertedAndOnlyAGifHasASizeLimit() {
        assertEquals(MediaCheck.Accepted("image/heic", convertToJpeg = true), MediaTypes.check(MediaKind.IMAGE, "image/heic"))
        assertEquals(MediaCheck.Accepted("image/heif", convertToJpeg = true), MediaTypes.check(MediaKind.IMAGE, "image/heif"))
        assertEquals(MediaCheck.Accepted("image/jpeg", convertToJpeg = false), MediaTypes.check(MediaKind.IMAGE, "image/jpeg", sizeBytes = 80_000_000))
        val limit = ImageUploader.MAX_BYTES.toLong()
        assertIs<MediaCheck.Accepted>(MediaTypes.check(MediaKind.IMAGE, "image/gif", sizeBytes = limit))
        assertEquals(MediaCheck.Rejected(MediaTypes.GIF_TOO_LARGE), MediaTypes.check(MediaKind.IMAGE, "image/gif", sizeBytes = limit + 1))
        assertIs<MediaCheck.Accepted>(MediaTypes.check(MediaKind.IMAGE, "image/gif", sizeBytes = -1))
        assertIs<MediaCheck.Accepted>(MediaTypes.check(MediaKind.VIDEO, "video/mp4", sizeBytes = 5_000_000_000))
    }

    @Test
    fun everySurfaceTakesOneKindAndItsLimit() {
        assertEquals(ImageUploader.MAX_IMAGES_PER_PICK, MediaSurface.EDITOR.maxItems)
        assertTrue(MediaSurface.EDITOR.allowsMultiple && MediaSurface.EDITOR_GALLERY.allowsMultiple)
        for (s in listOf(MediaSurface.WAVE, MediaSurface.CHAT, MediaSurface.VIDEO, MediaSurface.VIDEO_THUMBNAIL, MediaSurface.AVATAR, MediaSurface.COVER)) {
            assertFalse(s.allowsMultiple, s.name)
        }
        assertEquals(MediaKind.VIDEO, MediaSurface.VIDEO.kind)
        assertTrue(MediaSurface.entries.filter { it != MediaSurface.VIDEO }.all { it.kind == MediaKind.IMAGE })
        assertEquals(listOf("video/mp4", "video/quicktime"), MediaTypes.mimes(MediaKind.VIDEO))
        assertEquals("You can select up to 5 images at a time", MediaTypes.tooMany(MediaSurface.EDITOR))
    }

    @Test
    fun theChoosersKeepEachSurfacesRoutesAndTitles() {
        fun sources(options: List<MediaSourceOption>) = options.map { it.source }
        assertEquals(listOf(MediaSource.LIBRARY, MediaSource.CAMERA, MediaSource.FILES, MediaSource.LINK), sources(MediaSourceMenus.profileImage(camera = true)))
        assertEquals(listOf(MediaSource.LIBRARY, MediaSource.FILES, MediaSource.LINK), sources(MediaSourceMenus.profileImage(camera = false)))
        assertEquals(listOf(MediaSource.LIBRARY, MediaSource.FILES), sources(MediaSourceMenus.attachment()))
        assertEquals(listOf(MediaSource.LIBRARY, MediaSource.FILES, MediaSource.CAMERA, MediaSource.PASTE), sources(MediaSourceMenus.editorUploads(camera = true, paste = true)))
        assertEquals(listOf(MediaSource.LIBRARY, MediaSource.FILES), sources(MediaSourceMenus.editorUploads(camera = false, paste = false)))
        // the titles each surface showed before the sheets
        assertEquals(listOf(ProfileEditCopy.OPEN_GALLERY, ProfileEditCopy.CAPTURE_PHOTO, ProfileEditCopy.OPEN_FILES, ProfileEditCopy.ENTER_LINK), MediaSourceMenus.profileImage(true).map { it.title })
        assertEquals(listOf(MediaPickCopy.LIBRARY, MediaPickCopy.FILES), MediaSourceMenus.attachment().map { it.title })
        // every source says what it does, and none says the same as another
        val hints = MediaSource.entries.map { it.subtitle }
        assertTrue(hints.all { it.isNotBlank() })
        assertEquals(hints.size, hints.toSet().size)
    }
}

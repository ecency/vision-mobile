package com.ecency.core.app

import com.ecency.core.api.ecency.ImageUploader
import com.ecency.core.l10n.L

/** The two kinds of media the app uploads. Nothing else is ever picked for upload. */
enum class MediaKind { IMAGE, VIDEO }

/**
 * Every place that picks media for an upload, with what it accepts and how many at once. One entry per surface, so
 * the platform pickers (Android photo picker or document picker, iOS PHPicker or Files) are configured from here.
 */
enum class MediaSurface(val kind: MediaKind, val maxItems: Int) {
    /** The editor's gallery button and the uploads sheet: images go into the body. */
    EDITOR(MediaKind.IMAGE, ImageUploader.MAX_IMAGES_PER_PICK),

    /** The uploads sheet's "+": images join the gallery only. */
    EDITOR_GALLERY(MediaKind.IMAGE, ImageUploader.MAX_IMAGES_PER_PICK),
    WAVE(MediaKind.IMAGE, 1),
    CHAT(MediaKind.IMAGE, 1),
    VIDEO(MediaKind.VIDEO, 1),
    VIDEO_THUMBNAIL(MediaKind.IMAGE, 1),
    AVATAR(MediaKind.IMAGE, 1),
    COVER(MediaKind.IMAGE, 1),
    ;

    val allowsMultiple: Boolean get() = maxItems > 1
}

/** The two routes every surface offers, plus the chooser's cancel (RN gallery and "Choose from Files" copy). */
object MediaPickCopy {
    val LIBRARY: String get() = L.videoUpload.chooseLibrary
    val FILES: String get() = L.editor.openFiles
    val CANCEL: String get() = L.alert.cancel

    /** A picked video is being copied into the app before it can be used. */
    val PREPARING_VIDEO: String get() = L.native.mediaPick.preparingVideo
}

/**
 * Where an image for an upload comes from, as the chooser sheets show it: an icon and a short line under the
 * surface's own title. Presentation only; each surface keeps the routes and titles it always had ([MediaSourceMenus]).
 */
enum class MediaSource(val icon: AppIcon, private val hintText: () -> String) {
    LIBRARY(AppIcon.IMAGE, { L.native.mediaPick.libraryHint }),
    CAMERA(AppIcon.CAMERA, { L.native.mediaPick.cameraHint }),
    FILES(AppIcon.FOLDER, { L.native.mediaPick.filesHint }),
    PASTE(AppIcon.CLIPBOARD, { L.native.mediaPick.pasteHint }),
    LINK(AppIcon.LINK, { L.native.mediaPick.linkHint }),
    ;

    val subtitle: String get() = hintText()
}

/** One row of a chooser: the [source] and the title its surface shows for it (read when shown). */
class MediaSourceOption(val source: MediaSource, private val titleText: () -> String) {
    val title: String get() = titleText()
}

/**
 * The chooser of every surface that asks where an image comes from, the same on both apps. The option sets are the
 * ones the surfaces offered before the sheets (RN OptionsModal plus the Files route of RN #3561, and the link entry).
 */
object MediaSourceMenus {
    /** Profile avatar and cover: gallery, camera where there is one, files, an image link. */
    fun profileImage(camera: Boolean): List<MediaSourceOption> = listOfNotNull(
        MediaSourceOption(MediaSource.LIBRARY) { ProfileEditCopy.OPEN_GALLERY },
        if (camera) MediaSourceOption(MediaSource.CAMERA) { ProfileEditCopy.CAPTURE_PHOTO } else null,
        MediaSourceOption(MediaSource.FILES) { ProfileEditCopy.OPEN_FILES },
        MediaSourceOption(MediaSource.LINK) { ProfileEditCopy.ENTER_LINK },
    )

    /** A chat attachment and a video's thumbnail: the library or files. */
    fun attachment(): List<MediaSourceOption> = listOf(
        MediaSourceOption(MediaSource.LIBRARY) { MediaPickCopy.LIBRARY },
        MediaSourceOption(MediaSource.FILES) { MediaPickCopy.FILES },
    )

    /** The editor's uploads sheet: gallery, files, camera where there is one, the copied image when there is one. */
    fun editorUploads(camera: Boolean, paste: Boolean): List<MediaSourceOption> = listOfNotNull(
        MediaSourceOption(MediaSource.LIBRARY) { EditorUiCopy.OPEN_GALLERY },
        MediaSourceOption(MediaSource.FILES) { EditorUiCopy.CHOOSE_FROM_FILES },
        if (camera) MediaSourceOption(MediaSource.CAMERA) { EditorUiCopy.CAPTURE_A_PHOTO } else null,
        if (paste) MediaSourceOption(MediaSource.PASTE) { EditorUiCopy.PASTE_IMAGE } else null,
    )
}

/** The outcome of [MediaTypes.check]. */
sealed interface MediaCheck {
    /** Accepted. [convertToJpeg]: HEIC/HEIF, which the image service does not take, is converted before upload. */
    data class Accepted(val mime: String, val convertToJpeg: Boolean) : MediaCheck

    data class Rejected(val message: String) : MediaCheck
}

/**
 * The one allow-list of uploadable media, for both apps: JPEG, PNG, GIF, WebP and HEIC/HEIF images, MP4 and
 * QuickTime (MOV) videos. Anything else is refused with a message that names what is accepted.
 *
 * Images are prepared on the device before upload (upright, at most [ImageUploader.MAX_DIMENSION] px, every
 * metadata block dropped, GPS position included; HEIC and WebP become JPEG); an animated GIF is uploaded as it is
 * and must fit [ImageUploader.MAX_BYTES]. Videos go to 3Speak by tus, which sets no size limit.
 */
object MediaTypes {
    val IMAGE_MIMES = listOf("image/jpeg", "image/png", "image/gif", "image/webp", "image/heic", "image/heif")
    val VIDEO_MIMES = listOf("video/mp4", "video/quicktime")

    /** iOS UTType identifiers of the same lists (`public.heif` covers the HEIF family, HEIC included). */
    val IMAGE_UTTYPES = listOf("public.jpeg", "public.png", "com.compuserve.gif", "org.webmproject.webp", "public.heic", "public.heif")
    val VIDEO_UTTYPES = listOf("public.mpeg-4", "com.apple.quicktime-movie")

    private val ALIASES = mapOf("image/jpg" to "image/jpeg", "image/pjpeg" to "image/jpeg", "image/x-png" to "image/png", "video/mov" to "video/quicktime")
    private val BY_EXTENSION = mapOf(
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "gif" to "image/gif", "webp" to "image/webp",
        "heic" to "image/heic", "heif" to "image/heif", "mp4" to "video/mp4", "m4v" to "video/mp4", "mov" to "video/quicktime",
    )
    private val BY_UTTYPE = IMAGE_UTTYPES.zip(listOf("image/jpeg", "image/png", "image/gif", "image/webp", "image/heic", "image/heif")).toMap() +
        VIDEO_UTTYPES.zip(VIDEO_MIMES).toMap()

    val IMAGE_TYPES_MESSAGE: String get() = L.native.mediaPicking.imageTypesMessage
    val VIDEO_TYPES_MESSAGE: String get() = L.native.mediaPicking.videoTypesMessage
    val GIF_TOO_LARGE: String get() = L.native.mediaPicking.gifTooLarge(mb = (ImageUploader.MAX_BYTES / 1_000_000).toString())

    fun mimes(kind: MediaKind): List<String> = if (kind == MediaKind.IMAGE) IMAGE_MIMES else VIDEO_MIMES

    fun utTypes(kind: MediaKind): List<String> = if (kind == MediaKind.IMAGE) IMAGE_UTTYPES else VIDEO_UTTYPES

    /** The MIME type of a picked item: the provider's, else the UTType's, else the file name's extension. */
    fun resolve(mime: String?, name: String?, utType: String? = null): String? {
        val m = mime?.trim()?.lowercase()?.substringBefore(';')?.takeIf { it.isNotEmpty() && it != "application/octet-stream" }
        return m?.let { ALIASES[it] ?: it }
            ?: utType?.let { BY_UTTYPE[it] }
            ?: name?.substringAfterLast('.', "")?.lowercase()?.let { BY_EXTENSION[it] }
    }

    /**
     * Whether an item picked for [kind] may be uploaded. [sizeBytes] is checked for a GIF only (every other image is
     * re-encoded smaller on the device); null or negative means unknown, decided when the bytes are read.
     */
    fun check(kind: MediaKind, mime: String?, name: String? = null, sizeBytes: Long? = null, utType: String? = null): MediaCheck {
        val resolved = resolve(mime, name, utType)
        val allowed = mimes(kind)
        if (resolved == null || resolved !in allowed) {
            return MediaCheck.Rejected(if (kind == MediaKind.IMAGE) IMAGE_TYPES_MESSAGE else VIDEO_TYPES_MESSAGE)
        }
        if (resolved == "image/gif" && sizeBytes != null && sizeBytes > ImageUploader.MAX_BYTES) return MediaCheck.Rejected(GIF_TOO_LARGE)
        return MediaCheck.Accepted(resolved, convertToJpeg = resolved == "image/heic" || resolved == "image/heif")
    }

    /** [check] by an iOS type identifier alone (PHPicker item providers, the Files importer). */
    fun checkUtType(kind: MediaKind, utType: String, sizeBytes: Long): MediaCheck = check(kind, null, null, sizeBytes, utType)

    /** RN's copy for more than [MediaSurface.maxItems] picked images (editor.max_images). */
    fun tooMany(surface: MediaSurface): String = L.native.plurals.alert.tooManyImages(count = surface.maxItems)
}

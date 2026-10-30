package com.ecency.core.app

import com.ecency.core.api.broadcast.ProfileMetadataGuard
import com.ecency.core.api.model.Account
import com.ecency.core.api.model.AccountProfile
import com.ecency.core.api.model.JsValue
import com.ecency.core.api.model.ProfileMetadata
import com.ecency.core.hive.AccountUpdate2
import com.ecency.core.hive.KeyRole
import com.ecency.core.l10n.L
import com.ecency.core.render.ImageProxy
import com.ecency.core.render.blocks.SafeTarget
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlin.coroutines.cancellation.CancellationException

object ProfileEditCopy {
    /** RN editor.open_gallery */
    val OPEN_GALLERY: String get() = L.editor.openGallery

    /** RN editor.capture_photo */
    val CAPTURE_PHOTO: String get() = L.editor.capturePhoto

    /** RN editor.open_files (Android only, as RN SHOW_FILES_SOURCE) */
    val OPEN_FILES: String get() = L.editor.openFiles

    /** The link entry the native app keeps beside the uploads. */
    val ENTER_LINK: String get() = L.native.profileEdit.enterLink

    val CANCEL: String get() = L.alert.cancel

    /** RN alert.invalidImage: the picked file could not be read. */
    val INVALID_IMAGE: String get() = L.alert.invalidImage
}

/**
 * RN IMAGE_PICKER_AVATAR_OPTIONS (`cropping: true, width: 512, height: 512`): the avatar is the centred square of
 * the picked image, at most [SIZE] px. The platforms crop and scale with this.
 */
object AvatarCrop {
    const val SIZE = 512

    /** The centred square of a [width] x [height] image: x, y and side, in pixels. */
    fun square(width: Int, height: Int): Triple<Int, Int, Int> {
        require(width > 0 && height > 0) { "empty image" }
        val side = minOf(width, height)
        return Triple((width - side) / 2, (height - side) / 2, side)
    }

    /** The side the cropped square is scaled to: [SIZE], or less when the square is smaller (never upscaled). */
    fun target(side: Int): Int = minOf(side, SIZE)
}

/** Where an avatar or cover image comes from (RN profileEdit OptionsModal). */
enum class ProfileImageSource { GALLERY, CAMERA, FILES }

/** The fields of RN's profile edit (FORM_DATA plus the two images). */
enum class ProfileField(private val labelText: () -> String, val metadataKey: String) {
    NAME({ L.profile.edit.displayName }, "name"),
    ABOUT({ L.profile.edit.about }, "about"),
    LOCATION({ L.profile.edit.location }, "location"),
    WEBSITE({ L.profile.edit.website }, "website"),
    AVATAR({ L.profile.edit.profileImg }, "profile_image"),
    COVER({ L.profile.edit.coverImg }, "cover_image"),
    ;

    val label: String get() = labelText()

    companion object {
        /** The text fields in RN's form order. */
        val form = listOf(NAME, ABOUT, LOCATION, WEBSITE)
    }
}

data class ProfileEditState(
    val username: String?,
    val values: Map<ProfileField, String> = emptyMap(),
    val isLoading: Boolean = false,
    val loaded: Boolean = false,
    val loadError: AppError? = null,
    /** RN shows SAVE once something changed. */
    val saveEnabled: Boolean = false,
    val isSaving: Boolean = false,
    /** The edit went through; the screen closes (RN goBack). */
    val saved: Boolean = false,
    /** A failed save or a rejected image URL, as RN's "Fail!" alert. */
    val alert: AppError? = null,
    /** The avatar or cover uploading now (RN isUploading with selectedUploadAction). */
    val uploading: ProfileField? = null,
    val proxyBase: String = ImageProxy.DEFAULT_BASE,
) {
    fun value(field: ProfileField): String = values[field].orEmpty()

    /** The avatar to show: the entered URL, proxied, else the account's avatar. */
    val avatarPreview: String
        get() = value(ProfileField.AVATAR).takeIf { it.isNotEmpty() }?.let { ImageProxy(proxyBase).proxify(it, 256, 256) }
            ?: username?.let { PostCard.avatarUrl(it, proxyBase, "large") }.orEmpty()

    /** RN getResizedImage(coverUrl, 600); empty for the default cover. */
    val coverPreview: String
        get() = value(ProfileField.COVER).takeIf { it.isNotEmpty() }?.let { ImageProxy(proxyBase).proxify(it, 600, 0) }.orEmpty()
}

/**
 * The signed-in account's profile edit (RN profileEditContainer). Saving broadcasts `account_update2` with the
 * posting key, built exactly as @ecency/sdk useAccountUpdate builds it: the account is read again first, the
 * edited fields are merged over its on-chain profile and every other metadata key is kept. The avatar and cover
 * are uploaded through the editor's image upload ([uploadImage], RN _uploadImage), which saves the profile right
 * away without closing the screen, as RN; an image link can be entered too.
 */
class ProfileEditModel internal constructor(private val env: AppEnvironment) :
    StateModel<ProfileEditState>(ProfileEditState(env.session.current.username, proxyBase = env.proxyBase), env.dispatchers) {

    private var original: Map<ProfileField, String> = emptyMap()
    private var job: Job? = null

    fun start() {
        if (current.loaded || job?.isActive == true) return
        load()
    }

    fun retry() = load()

    private fun load() {
        setState { it.copy(isLoading = true, loadError = null) }
        job = scope.launch {
            try {
                env.session.awaitLoaded()
                val user = env.session.current.username ?: throw AppException(AppError(ErrorKind.AUTH, L.native.profileEdit.pleaseSignInFirst, false))
                setState { it.copy(username = user) }
                val account = env.api.accounts.getAccount(user, refresh = true) ?: throw AppException(AppError(ErrorKind.NOT_FOUND, L.native.profileEdit.accountNotFound, false))
                // the same profile a save would start from (hivemind may be reading the legacy metadata)
                val profile = ProfileWrites.formProfile(env, account)
                val values = ProfileField.entries.associateWith { f -> (profile[f.metadataKey] as? JsValue.Str)?.value.orEmpty() }
                original = values
                setState { it.copy(values = values, isLoading = false, loaded = true, saveEnabled = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setState { it.copy(isLoading = false, loadError = AppError.from(e)) }
            }
        }
    }

    fun setValue(field: ProfileField, value: String) = setState {
        val values = it.values + (field to value)
        it.copy(values = values, saveEnabled = values != original)
    }

    /**
     * An image URL entered for [field] (avatar or cover): http(s) only, or empty to clear it. Returns false
     * (and shows why) when it is not a usable link.
     */
    fun setImageUrl(field: ProfileField, url: String): Boolean {
        require(field == ProfileField.AVATAR || field == ProfileField.COVER) { "not an image field: $field" }
        val trimmed = url.trim()
        if (trimmed.isEmpty()) {
            setValue(field, "")
            return true
        }
        val safe = SafeTarget.httpUrl(trimmed)
        if (safe == null) {
            setState { it.copy(alert = AppError(ErrorKind.INVALID_INPUT, L.native.profileEdit.pleaseEnterAnImage, false)) }
            return false
        }
        setValue(field, safe)
        return true
    }

    fun dismissAlert() = setState { it.copy(alert = null) }

    private var uploadJob: Job? = null

    /**
     * RN _uploadImage: [image] (prepared by the platform: the avatar cropped square at 512 px, metadata stripped)
     * goes through the editor's upload for the signed-in account, its URL becomes [field] and the profile is saved
     * at once without closing the screen (RN `_handleOnSubmit({ goBack: false })`). Null: the picker was cancelled.
     * An image over the upload limit is refused before anything is sent (RN _rejectTooLarge).
     */
    fun uploadImage(field: ProfileField, image: PickedImage?) {
        require(field == ProfileField.AVATAR || field == ProfileField.COVER) { "not an image field: $field" }
        val s = current
        val user = s.username ?: return
        if (image == null || !s.loaded || s.uploading != null || s.isSaving) return
        if (image.bytes.size > com.ecency.core.api.ecency.ImageUploader.MAX_BYTES) {
            setState { it.copy(alert = AppError(ErrorKind.INVALID_INPUT, UploadsModel.TOO_LARGE, false)) }
            return
        }
        setState { it.copy(uploading = field, alert = null) }
        uploadJob = scope.launch {
            val url = try {
                ImageUploads.upload(env, user, image)
            } catch (e: CancellationException) {
                setState { it.copy(uploading = null) }
                throw e
            } catch (e: Exception) {
                setState { it.copy(uploading = null, alert = AppError(ErrorKind.UNKNOWN, com.ecency.core.api.ecency.ImageUploader.message(e), false)) }
                return@launch
            }
            // uploaded for [user]: another account signed in meanwhile gets nothing of it
            if (env.session.current.username != user) return@launch setState { it.copy(uploading = null) }
            setState { it.copy(uploading = null) }
            setValue(field, url)
            save(close = false)
        }
    }

    /** Saves the edit; [close]: the screen goes back afterwards (RN SAVE), false after an image upload. */
    fun save(close: Boolean = true) {
        val s = current
        val user = s.username ?: return
        if (!s.loaded || s.isSaving || s.uploading != null || job?.isActive == true) return
        setState { it.copy(isSaving = true, alert = null) }
        job = scope.launch {
            try {
                // SDK onMutate: read the account fresh so a stale copy never overwrites the profile, and never
                // merge onto a stripped read (ProfileWrites, the path pin to blog takes too)
                val checked = ProfileWrites.checkedBase(env, user)
                ProfileWrites.broadcast(env, user, checked, params(checked.base.profile, current.values, original))
                env.avatars.bump(user, env.nowMillis())
                original = current.values
                setState { it.copy(isSaving = false, saved = close, saveEnabled = false) }
                env.emit(AppEvent.ProfileUpdated(user))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val error = AppError.from(e)
                setState { it.copy(isSaving = false, alert = if (error.kind == ErrorKind.CANCELLED) null else error) }
            }
        }
    }

    companion object {
        /**
         * The profile an edit starts from and merges over: the posting metadata's, or, while that has none, the
         * legacy `json_metadata` one the app shows (Account.profile falls back the same way), so saving such an
         * account carries its about, website, cover and location into the posting metadata instead of dropping them.
         */
        internal fun editBase(account: Account): JsValue.Obj =
            ProfileMetadataGuard.effectiveProfile(account.postingJsonMetadata, account.jsonMetadata)

        /**
         * RN _handleOnSubmit's profile: `{ ...profile, profile_image: avatarUrl, cover_image, name, website,
         * about, location, pinned, version: 2 }`. A field left as loaded keeps its on-chain value (whatever its
         * type); an absent one stays absent; the avatar is `null` when there is none (RN getAvatar).
         */
        internal fun params(base: JsValue.Obj, values: Map<ProfileField, String>, original: Map<ProfileField, String>): JsValue.Obj {
            val p = base.copy()
            fun display(v: JsValue?) = (v as? JsValue.Str)?.value.orEmpty()
            // a field the user did not change keeps its current on-chain value (absent stays absent, a number
            // stays a number, and a change made elsewhere since the screen opened is not overwritten)
            fun value(f: ProfileField): JsValue? {
                val raw = base[f.metadataKey]
                val v = values[f]
                return if (v == null || v == original[f] || v == display(raw)) raw else JsValue.Str(v)
            }
            val avatar = value(ProfileField.AVATAR)
            // RN state: getAvatar(profile) is the image or null; an edited value is the text as typed
            p["profile_image"] = if (avatar != null && (avatar.truthy || values[ProfileField.AVATAR] != original[ProfileField.AVATAR])) avatar else JsValue.Null
            for (f in listOf(ProfileField.COVER, ProfileField.NAME, ProfileField.WEBSITE, ProfileField.ABOUT, ProfileField.LOCATION)) {
                value(f)?.let { p[f.metadataKey] = it }
            }
            base["pinned"]?.let { p["pinned"] = it }
            p["version"] = JsValue.Num(2.0)
            return p
        }
    }
}

package com.ecency.core.app

import com.ecency.core.api.speak.SpeakApi
import com.ecency.core.api.speak.SpeakUploadToken
import com.ecency.core.api.speak.TusClient
import com.ecency.core.api.speak.TusException
import com.ecency.core.api.speak.TusSource
import com.ecency.core.api.speak.TusUpload
import com.ecency.core.editor.EditorMeta
import com.ecency.core.l10n.L
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * A video the user picked or recorded: its file (read piece by piece) and what the platform knows about it.
 * [ref] is where the platform's preview player finds it (a content uri, a file path). [release] is called once,
 * when the upload model lets go of the video for good (uploaded, replaced by another pick, the model reset or
 * closed): a camera capture's temporary file and the app's own copy of a picked file are deleted there. A failed
 * upload that can still be continued keeps its file for as long as the app runs: nothing sweeps a video while
 * its model lives (Android's photo sweep leaves recordings alone), and at the next cold start both apps remove
 * every leftover video, so "Continue upload" is an offer of this run only. A file that is gone all the same ends
 * the upload with [VideoUploadModel.SOURCE_GONE] instead of an offer that can never work.
 */
class PickedVideo(val source: TusSource, val durationMillis: Long? = null, val ref: String? = null, private val release: (() -> Unit)? = null) {
    private var released = false

    /** Runs [release] the first time only. */
    fun letGo() {
        if (released) return
        released = true
        try {
            release?.invoke()
        } catch (e: Exception) {
            // a file that is already gone
        }
    }
}

data class VideoUploadState(
    val fileName: String = "",
    val sizeBytes: Long = 0,
    /** RN isGeneratingThumbs: the platform is still cutting frames; the upload waits for them. */
    val generatingThumbs: Boolean = false,
    val thumbCount: Int = 0,
    /** The chosen frame; null is RN's default, the middle one. */
    val selectedThumb: Int? = null,
    /** RN's pencil: an image from the library is the thumbnail instead of a frame. */
    val customThumb: Boolean = false,
    /** Where the platform's preview player finds the video; null when it cannot be previewed. */
    val previewRef: String? = null,
    /** The app lock hides the app's content: nothing plays behind it. */
    val locked: Boolean = false,
    val uploading: Boolean = false,
    /** 0 to 100. */
    val progress: Double = 0.0,
    /** A failed upload can be continued where it stopped. */
    val canResume: Boolean = false,
    val error: String? = null,
    val done: Boolean = false,
) {
    val hasVideo: Boolean get() = fileName.isNotEmpty()

    /** RN _effectiveThumb. */
    val effectiveThumb: Int? get() = if (customThumb) null else selectedThumb ?: (thumbCount / 2).takeIf { thumbCount > 0 }

    /** RN `paused={isUploading}`: the looping preview plays only while nothing is being uploaded. */
    val previewPlays: Boolean get() = previewRef != null && !uploading && !done && !locked

    val canStart: Boolean get() = hasVideo && !uploading && !generatingThumbs && !done

    val startLabel: String get() = if (uploading) L.uploadsModal.uploading else if (canResume) L.native.videoUpload.continueUpload else L.uploadsModal.startUpload
}

/** Where an uploaded video goes: the editor's post, or the wave composer. It names the account the upload is for. */
internal interface VideoUploadTarget {
    /** The account the video is uploaded for; null while the target does not know it yet. */
    val owner: String?

    /** Why no video may be added now (one a post), or null. */
    fun refusal(): String?

    /** An upload started (+1) or ended (-1): the target shows it and holds its publish back. */
    fun uploadsChanged(delta: Int)

    /** The uploaded video and its thumbnail; false when the target no longer takes it (another account). */
    fun insertVideo(embedUrl: String, thumbUrl: String?): Boolean
}

/**
 * RN `speakUploaderModal` over `providers/speak`: one picked video goes to 3Speak with the tus protocol and a
 * token from Ecency's proxy, a frame of it becomes the thumbnail (uploaded like any image, then named to
 * 3Speak), and the embed address goes into the post.
 *
 * - The upload belongs to the editor's owner: the token is asked for with the owner's session, every piece is
 *   only sent while the owner is signed in, and nothing is inserted otherwise.
 * - A failed upload keeps its place: "Continue upload" asks the server how far it got and goes on from there
 *   with the same token; when 3Speak no longer knows the upload or the token, it starts over with a new one.
 *   Another account never continues it: the place is held by this model, which goes with the editor.
 * - One video a post (RN), shorts 60 seconds at most ([isShort]); 3Speak refuses more than 1 GB itself.
 */
class VideoUploadModel internal constructor(private val env: AppEnvironment, private val target: VideoUploadTarget, private val isShort: Boolean = false) :
    StateModel<VideoUploadState>(VideoUploadState(), env.dispatchers) {
    internal constructor(env: AppEnvironment, editor: EditorModel) : this(
        env,
        object : VideoUploadTarget {
            override val owner: String? get() = editor.current.owner
            override fun refusal(): String? = ONE_PER_POST.takeIf { EditorMeta.hasThreeSpeakEmbed(editor.current.body) }
            override fun uploadsChanged(delta: Int) = editor.uploadsChanged(delta)
            override fun insertVideo(embedUrl: String, thumbUrl: String?): Boolean = editor.insertVideo(embedUrl, thumbUrl)
        },
    )

    private var video: PickedVideo? = null
    private var thumbs: List<PickedImage> = emptyList()
    private var customThumb: PickedImage? = null
    private var token: SpeakUploadToken? = null
    private var upload: TusUpload? = null
    private var job: Job? = null
    private var generation = 0

    init {
        // signed out or wiped: the owner's video (a recording's temporary file, a copy of a picked one) and its
        // upload place go at once, whatever screen still holds this model
        scope.launch {
            env.session.state.collect {
                val owner = target.owner
                if (owner != null && env.localDrafts.hasLeft(owner) && (video != null || upload != null)) {
                    job?.cancel()
                    video?.letGo()
                    video = null
                    thumbs = emptyList()
                    customThumb = null
                    token = null
                    upload = null
                    generation++
                    setState { s -> VideoUploadState(locked = s.locked) }
                }
            }
        }
        // the preview stops under the app lock (the platforms add "not in front" themselves)
        scope.launch { env.appLock.state.collect { lock -> setState { if (it.locked == lock.hidesContent) it else it.copy(locked = lock.hidesContent) } } }
    }

    /** RN _showVideoUploads: null when a video may be added, else why not. */
    fun refusal(): String? = target.refusal()

    /** RN's uploader is 60 seconds at most for shorts (the wave composer). */
    val short: Boolean get() = isShort

    /**
     * The sheet opens for another video after one was inserted (the user took the first one out again): the
     * finished upload is forgotten. Nothing happens while an upload runs or one waits to be continued.
     */
    fun reset() {
        if (current.uploading || !current.done) return
        video?.letGo()
        video = null
        thumbs = emptyList()
        customThumb = null
        token = null
        upload = null
        generation++
        setState { VideoUploadState(locked = it.locked) }
    }

    /**
     * The picker answered. False (with [VideoUploadState.error]) when the video cannot be used. The platform
     * then cuts [THUMBS] frames off the main thread and reports them with [setThumbs] and the number this returns.
     */
    fun pick(picked: PickedVideo): Int? {
        // a video that is not taken is let go at once: a recording's temporary file does not stay behind
        if (current.uploading) {
            picked.letGo()
            return null
        }
        if (picked.source.size <= 0) {
            picked.letGo()
            setState { it.copy(error = GENERIC) }
            return null
        }
        if (isShort && (picked.durationMillis ?: 0) > SHORT_MAX_MILLIS) {
            picked.letGo()
            setState { it.copy(error = TOO_LONG_SHORT) }
            return null
        }
        if (video !== picked) video?.letGo()
        video = picked
        thumbs = emptyList()
        customThumb = null
        token = null
        upload = null
        setState {
            it.copy(
                fileName = picked.source.name, sizeBytes = picked.source.size, generatingThumbs = true, thumbCount = 0, selectedThumb = null, customThumb = false,
                previewRef = picked.ref, progress = 0.0, canResume = false, error = null, done = false,
            )
        }
        return ++generation
    }

    /**
     * Runs the platform's reading of a picked or recorded video in this model's scope: it outlives the sheet
     * (the lock may cover it) and ends with the editor or composer.
     */
    fun launch(block: suspend () -> Unit) {
        scope.launch { block() }
    }

    /** The picked or recorded video could not be read at all. */
    fun pickFailed() {
        if (!current.uploading) setState { it.copy(error = GENERIC) }
    }

    /** RN _generateThumbs' result for the pick numbered [forPick]; a slower batch for an earlier video is dropped. */
    fun setThumbs(forPick: Int, frames: List<PickedImage>) {
        if (forPick != generation) return
        thumbs = frames.take(THUMBS)
        setState { it.copy(generatingThumbs = false, thumbCount = thumbs.size) }
    }

    fun selectThumb(index: Int) {
        if (current.uploading || index !in thumbs.indices) return
        customThumb = null
        setState { it.copy(selectedThumb = index, customThumb = false) }
    }

    /**
     * RN's pencil (`_handleOpenImagePicker`): an image from the library as the thumbnail. The platform hands it
     * over prepared like any upload (decoded and written again without its metadata, resized); it goes up through
     * the same signed image upload as a frame would.
     */
    fun setCustomThumb(image: PickedImage) {
        if (current.uploading || !current.hasVideo || current.done) return
        customThumb = image
        setState { it.copy(customThumb = true) }
    }

    /** The frame at [index], for the strip. */
    fun thumb(index: Int): PickedImage? = thumbs.getOrNull(index)

    /** RN `_effectiveThumb`: the image that will be the thumbnail (the picked one, else the chosen or middle frame). */
    fun thumbImage(): PickedImage? = customThumb ?: current.effectiveThumb?.let(thumbs::getOrNull)

    fun clearError() = setState { it.copy(error = null) }

    private fun ownerSignedIn(owner: String) = env.session.current.username == owner

    /** RN _startUpload, or the continuation of an upload that failed. */
    fun start() {
        val s = current
        // read when it is needed: this model may exist before the editor knows its owner (which then never changes)
        val owner = target.owner ?: return
        val picked = video ?: return
        if (!s.canStart) return
        if (!ownerSignedIn(owner)) {
            setState { it.copy(error = PaidCopy.otherAccount(owner)) }
            return
        }
        refusal()?.let { why ->
            setState { it.copy(error = why) }
            return
        }
        setState { it.copy(uploading = true, error = null) }
        target.uploadsChanged(1)
        job = scope.launch {
            try {
                val (embedUrl, permlink) = send(owner, picked)
                // the thumbnail is not critical (RN): the video is up either way
                val thumbUrl = thumbImage()?.let { frame ->
                    try {
                        ImageUploads.upload(env, owner, frame)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                }
                if (thumbUrl != null && permlink != null) env.background.launch { env.speakApi.setThumbnail(owner, permlink, thumbUrl) }
                val inserted = target.insertVideo(embedUrl, thumbUrl)
                // uploaded and placed: the file is no longer needed (a recording's temporary file goes here)
                if (inserted) picked.letGo()
                setState { it.copy(uploading = false, progress = 100.0, canResume = false, done = inserted, error = if (inserted) null else PaidCopy.otherAccount(owner)) }
            } catch (e: CancellationException) {
                setState { it.copy(uploading = false, canResume = upload != null) }
                throw e
            } catch (e: com.ecency.core.api.speak.TusSourceGone) {
                // the file is gone (a recording's temporary file was removed, a picked file's copy deleted): there
                // is nothing to continue with, so the upload place is dropped and the user is told to pick again
                picked.letGo()
                video = null
                thumbs = emptyList()
                customThumb = null
                token = null
                upload = null
                generation++
                setState { VideoUploadState(locked = it.locked, error = SOURCE_GONE) }
            } catch (e: Exception) {
                val status = (e as? TusException)?.status ?: 0
                setState { it.copy(uploading = false, canResume = upload != null, error = if (!ownerSignedIn(owner)) PaidCopy.otherAccount(owner) else message(status)) }
            } finally {
                target.uploadsChanged(-1)
            }
        }
    }

    /** Token, creation (or the place a failed try left off) and the pieces; the embed address and the video's permlink. */
    private suspend fun send(owner: String, picked: PickedVideo): Pair<String, String?> {
        val tus = env.tus
        val progress = { sent: Long, total: Long -> setState { it.copy(progress = if (total > 0) kotlin.math.round(sent * 10000.0 / total) / 100 else 0.0) } }
        val proceed = { ownerSignedIn(owner) }
        suspend fun bytes(t: SpeakUploadToken, u: TusUpload, from: Long) = withContext(dispatchers.io) { tus.send(u, t.token, picked.source, from, proceed) { s, total -> scope.launch { progress(s, total) } } }
        var t = token
        var u = upload
        if (t != null && u != null) {
            // a failed try left its place: ask 3Speak how far it got; gone or refused means starting over
            val from = try {
                tus.offset(u, t.token)
            } catch (e: TusException) {
                if (e.status == 401 || e.status == 403) null else throw e
            }
            if (from != null && from <= picked.source.size) {
                bytes(t, u, from)
                return result(t, u)
            }
        }
        t = env.speakApi.uploadToken(owner, isShort)
        if (!proceed()) throw TusException(0, "stopped")
        u = tus.create(t.endpoint, t.token, picked.source)
        token = t
        upload = u
        bytes(t, u, 0)
        return result(t, u)
    }

    /** RN: the permlink and embed address the token came with; on an older backend the creation's X-Embed-URL header. */
    private fun result(t: SpeakUploadToken, u: TusUpload): Pair<String, String?> {
        val embed = t.embedUrl ?: u.createHeaders["x-embed-url"]?.takeIf(SpeakApi::isEmbedUrl) ?: throw TusException(0, "no embed address")
        return embed to (t.permlink ?: SpeakApi.permlinkOf(embed))
    }

    /**
     * The sheet was closed. A video that was picked or recorded and never sent is forgotten with it (its temporary
     * file goes); a running upload goes on, and a failed one keeps its place for "Continue upload".
     */
    fun dismissed() {
        val s = current
        if (s.uploading || s.canResume || s.done || !s.hasVideo) return
        video?.letGo()
        video = null
        thumbs = emptyList()
        customThumb = null
        generation++
        setState { VideoUploadState(locked = it.locked) }
    }

    /** Stops a running upload; its place is kept for "Continue upload". */
    fun cancel() {
        job?.cancel()
    }

    override fun close() {
        job?.cancel()
        video?.letGo()
        video = null
        super.close()
    }

    companion object {
        /** RN THUMBS_COUNT. */
        const val THUMBS = 5
        const val SHORT_MAX_MILLIS = 60_000L

        val ONE_PER_POST: String get() = L.videoUpload.errorOnePerPost
        val TOO_LONG_SHORT: String get() = L.videoUpload.errorTooLongShort
        val GENERIC: String get() = L.videoUpload.errorGeneric

        /** Not RN, which would fail the same way on every "Continue upload". */
        val SOURCE_GONE: String get() = L.native.videoUpload.sourceGone
        val SELECT_THUMB: String get() = L.uploadsModal.selectThumb

        /** RN video-upload.select_source, record, choose_library. */
        val SELECT_SOURCE: String get() = L.videoUpload.selectSource
        val RECORD: String get() = L.videoUpload.record
        val CHOOSE: String get() = L.videoUpload.chooseLibrary
        val NO_CAMERA: String get() = L.native.videoUpload.noCamera

        /** RN's toasts by status. */
        fun message(status: Int): String = when (status) {
            413 -> L.videoUpload.errorTooLarge
            429 -> L.videoUpload.errorTooMany
            503 -> L.videoUpload.errorUnavailable
            401, 403 -> L.videoUpload.errorAuth
            else -> GENERIC
        }

        /** RN _generateThumbs: the middle of each of [THUMBS] equal slices; 10 s stands in for an unknown duration. */
        fun thumbTimesMillis(durationMillis: Long?): List<Long> {
            val duration = durationMillis?.takeIf { it > 0 } ?: 10_000L
            val step = duration.toDouble() / THUMBS
            // JS Math.round: halves go up (Kotlin's round goes to even)
            return (0 until THUMBS).map { kotlin.math.floor((it + 0.5) * step + 0.5).toLong() }
        }
    }
}

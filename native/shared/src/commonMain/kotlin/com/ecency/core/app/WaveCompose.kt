package com.ecency.core.app

import com.ecency.core.api.HiveApi
import com.ecency.core.api.AccountPostsSort
import com.ecency.core.api.broadcast.CommentDraft
import com.ecency.core.api.broadcast.OperationJson
import com.ecency.core.api.model.Post
import com.ecency.core.api.model.PostKey
import com.ecency.core.api.model.PostMetadata
import com.ecency.core.api.waves.ShortVideo
import com.ecency.core.api.waves.WaveEntry
import com.ecency.core.api.waves.WavePublish
import com.ecency.core.api.waves.Waves
import com.ecency.core.api.waves.WavesApi
import com.ecency.core.api.waves.WavesFeedParams
import com.ecency.core.api.waves.WavesPager
import com.ecency.core.hive.ChainTime
import com.ecency.core.l10n.L
import com.ecency.core.render.js.Js
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlin.coroutines.cancellation.CancellationException

/** Where the composer's image comes from. */
/** Where a wave's image comes from; CLIPBOARD: RN's "Image in clipboard · Paste image" chip. */
/** Where a wave's image comes from. [FILES]: the system's file picker, for images stored outside the gallery (RN #3561). */
enum class WaveImageSource { GALLERY, CAMERA, CLIPBOARD, FILES }

object WaveComposeCopy {
    /** RN editor.clipboard_image_detected and editor.paste_image, as RN joins them on the chip. */
    val CLIPBOARD_CHIP: String get() = L.native.waveCompose.clipboardChip

    /** RN alert.cancel: the chip's close button. */
    val CLIPBOARD_DISMISS: String get() = L.alert.cancel

    /** RN editor.clipboard_no_image */
    val CLIPBOARD_EMPTY: String get() = L.editor.clipboardNoImage

    /** The dictation button's label (RN's microphone with the "AI" badge). */
    val DICTATE: String get() = L.dictation.title

    /** The AI assist button's label (RN's AI assist icon). */
    val AI_ASSIST: String get() = L.aiAssist.title
}

/**
 * The composer's image attach, as one small seam: the platform picks an image, uploads it for [username] and
 * returns its URL (null when the user cancelled). The base has no uploader yet, so the apps leave
 * [WavesService.attacher] unset until the editor's upload is wired in; tests use a fake.
 */
interface WaveImageAttacher {
    @Throws(Exception::class)
    suspend fun attach(username: String, source: WaveImageSource): String?
}

/** Width / height of an image, for RN's `image_ratios` metadata; null when it cannot be read. */
fun interface WaveImageRatios {
    suspend fun ratio(url: String): Double?
}

/**
 * The composer's image attach over the editor's upload: the platform shows its picker ([onPick]) and hands back one
 * prepared image ([picked]: metadata stripped, resized, off the main thread, as for the editor; null when the user
 * cancelled), which is uploaded for the composer's owner through [ImageUploads] (signed for that account, size
 * guard, retries, stall timeout, checked answer). One image a wave, as RN. [install] wires it into an environment.
 */
class WaveUploadAttacher internal constructor(private val env: AppEnvironment) : WaveImageAttacher {
    /** Set by the screen that shows the composer: opens the gallery or the camera. Main thread. */
    var onPick: ((WaveImageSource) -> Unit)? = null

    private var waiting: kotlinx.coroutines.CompletableDeferred<PickedImage?>? = null

    /** True while a picker is open for the composer. */
    val isPicking: Boolean get() = waiting != null

    /** The picker closed: with the prepared image, or null when nothing was picked. Main thread. */
    fun picked(image: PickedImage?) {
        waiting?.complete(image)
    }

    override suspend fun attach(username: String, source: WaveImageSource): String? {
        val show = onPick ?: throw AppException(AppError(ErrorKind.INVALID_INPUT, WaveComposeModel.NO_UPLOAD, false))
        // a picker left over from an earlier request counts as cancelled
        waiting?.complete(null)
        val request = kotlinx.coroutines.CompletableDeferred<PickedImage?>()
        waiting = request
        val image = try {
            show(source)
            request.await()
        } finally {
            if (waiting === request) waiting = null
        }
        return ImageUploads.upload(env, username, image ?: return null)
    }

    companion object {
        /**
         * Gives [env]'s wave composer the editor's upload and its image ratios (known from the upload itself, as
         * the editor uses them). Each app calls it once, then sets [onPick] from its composer screen.
         */
        fun install(env: AppEnvironment): WaveUploadAttacher {
            val attacher = WaveUploadAttacher(env)
            env.waves.attacher = attacher
            env.waves.imageRatios = WaveImageRatios { env.imageRatios(it) }
            return attacher
        }
    }
}

/**
 * What the waves screens share: the API, the waves just published from this device (shown on top of For you
 * until the feed has them), a share link's text waiting for the composer, and the composer itself. One per app.
 */
class WavesService internal constructor(private val env: AppEnvironment) {
    /** Replaceable in tests (a delayed or failing fake). */
    var api: WavesApi = WavesApi(env.auth.http, env.auth.tokenHost)

    /** Set by the platform once image upload exists; null hides the attach buttons' effect (a notice instead). */
    var attacher: WaveImageAttacher? = null

    /** Set by the platform (its image pipeline); null writes RN's "could not measure" (`null`) ratios. */
    var imageRatios: WaveImageRatios? = null

    private data class Local(val owner: String, val item: WaveItem, val at: Long)

    private var local: List<Local> = emptyList()

    /** [owner]'s waves published here and not yet in the feed, newest first. Main thread. */
    internal fun localItems(owner: String?): List<WaveItem> {
        val now = env.nowMillis()
        local = local.filter { now - it.at < LOCAL_MILLIS }
        return local.filter { it.owner == owner }.map { it.item }
    }

    internal fun addLocal(owner: String, item: WaveItem) {
        markWaves(listOf(item.key))
        local = listOf(Local(owner, item, env.nowMillis())) + local.filter { it.item.key != item.key }
    }

    /** The feed returned these waves: their local copies are no longer needed. */
    internal fun forgetIndexed(keys: List<PostKey>) {
        if (local.isEmpty()) return
        val set = keys.toHashSet()
        local = local.filter { it.item.key !in set }
    }

    internal fun updateLocal(changed: Set<PostKey>, update: (WaveItem) -> WaveItem?) {
        if (local.none { it.item.key in changed }) return
        local = local.map { l -> if (l.item.key in changed) l.copy(item = update(l.item) ?: l.item) else l }
    }

    /** The account changed: local waves and the open composer belonged to the previous one. Main thread. */
    internal fun reset() {
        local = emptyList()
        // a share link's text is not account data: it waits through the login it may have asked for
        composer?.dispose()
        composer = null
    }

    // ---- RN waveUpvotePercent: the vote sheet remembers its last weight for waves apart from posts ----

    private val waveKeys = LinkedHashSet<PostKey>()

    /** [keys] are waves (list rows, an opened thread). Main thread; the newest [KNOWN_WAVES] are remembered. */
    internal fun markWaves(keys: Collection<PostKey>) {
        for (key in keys) {
            waveKeys.remove(key)
            waveKeys.add(key)
        }
        while (waveKeys.size > KNOWN_WAVES) waveKeys.remove(waveKeys.first())
    }

    /** Whether [key] is a wave the app has shown (the vote sheet then uses the wave weight memory). */
    fun isWave(key: PostKey): Boolean = key in waveKeys

    /** The last weight a wave was voted with, in percent (1..100); RN starts at 100. */
    var voteWeightPercent: Double
        get() = env.prefs.getString(VOTE_WEIGHT_KEY)?.toDoubleOrNull()?.takeIf { it > 0 && it <= 100 } ?: 100.0
        set(value) {
            if (value > 0 && value <= 100) env.prefs.putString(VOTE_WEIGHT_KEY, value.toString())
        }

    /**
     * RN `ecency.com/waves?text=...`: text the Waves tab opens its composer with, once ([takeComposeText]). It
     * belongs to whoever was signed in when the link arrived (nobody: to the account that signs in next), and it
     * does not wait forever: after [COMPOSE_TEXT_MILLIS], a logout, an account switch, a wipe or a login the user
     * backed out of ([dropComposeText]) it is gone, so it never surfaces later in someone else's composer.
     */
    var pendingComposeText: String? = null
        private set
    private var composeTextFor: String? = null
    private var composeTextAt = 0L

    fun requestCompose(text: String) {
        pendingComposeText = text
        composeTextFor = env.session.current.username
        composeTextAt = env.nowMillis()
        env.emit(AppEvent.WaveComposeRequested)
    }

    /** A link the app was opened with: `ecency.com/waves?text=...` asks for the composer with that text. */
    fun openLink(url: String) {
        Waves.composeText(url)?.takeIf { it.isNotEmpty() }?.let(::requestCompose)
    }

    /**
     * The waiting share text, once, and only for a signed-in reader: signed out it stays, so it is still there
     * after the login the Waves tab sends them to.
     */
    fun takeComposeText(): String? {
        expireComposeText()
        if (env.session.current.username == null) return null
        // whose it is was settled when the account changed ([accountChanging]): what is still here is this account's
        return pendingComposeText.also { dropComposeText() }
    }

    private fun expireComposeText() {
        if (pendingComposeText != null && env.nowMillis() - composeTextAt !in 0..COMPOSE_TEXT_MILLIS) dropComposeText()
    }

    /** Forgets the waiting share text: the account changed or left, or the user closed the login it waited for. */
    fun dropComposeText() {
        pendingComposeText = null
        composeTextFor = null
    }

    /**
     * The session's account is about to change from [previous] ([AppEnvironment.forgetSessionState]). A text that
     * arrived while signed out waits for exactly this first sign-in; in every other case it belonged to
     * [previous] and goes.
     */
    internal fun accountChanging(previous: String?) {
        if (previous != null || composeTextFor != null) dropComposeText()
    }

    private val linkedVideos = LinkedHashSet<PostKey>()

    /**
     * RN `linkVideoToHive` after a published wave that embeds a 3Speak video: tells 3Speak (through Ecency's
     * proxy, with [owner]'s session) which wave carries the video, so it shows in 3Speak's own feeds. The wave is
     * already published and stays so: this runs apart from the publish, shows nothing, and never fails it. One
     * call per wave from this run of the app; a lost answer, a 5xx or a 429 is tried again ([LINK_DELAYS]), which
     * is safe because the request names the same video and the same wave. RN tries once.
     */
    internal fun linkVideo(owner: String, wavePermlink: String, body: String, tags: List<String>) {
        val video = WavePublish.videoLinkPermlink(body)?.takeIf(com.ecency.core.api.speak.SpeakApi::isPermlink) ?: return
        val key = PostKey(owner, wavePermlink)
        if (!linkedVideos.add(key)) return
        while (linkedVideos.size > KNOWN_WAVES) linkedVideos.remove(linkedVideos.first())
        env.background.launch {
            for (wait in LINK_DELAYS) {
                delay(wait)
                val status = env.speakApi.linkHive(owner, video, wavePermlink, tags)
                if (status in 200..299 || (status in 400..499 && status != 408 && status != 429)) return@launch
            }
        }
    }

    /** A deleted wave's local copy goes with it. */
    internal fun forget(key: PostKey) = forgetIndexed(listOf(key))

    private var composer: WaveComposeModel? = null

    /**
     * The wave composer of the signed-in account: one for the app, so the draft is still there when the sheet
     * is opened again and a send outlives the sheet. A composer never changes hands: after an account change
     * this returns a new, empty one.
     */
    fun composer(): WaveComposeModel {
        val user = env.session.current.username
        composer?.let { if (it.owner == user) return it else it.dispose() }
        return WaveComposeModel(env, user).also { composer = it }
    }

    internal fun sent(model: WaveComposeModel) {
        if (composer === model) composer = null
    }

    /**
     * RN fetchLatestWavesContainer([hive.flow, ecency.waves]): the newest post of the first host that has one
     * (`bridge.get_account_posts`, sort posts, limit 1). A host that fails is skipped; with no container at all
     * the last failure is thrown.
     */
    @Throws(Exception::class)
    suspend fun latestContainer(hive: HiveApi = env.api.hive): Post {
        var last: Exception? = null
        for (host in Waves.HOSTS) {
            val post = try {
                hive.accountPosts(host, AccountPostsSort.POSTS, limit = 1).firstOrNull()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
                null
            }
            // only the host's own root post is a container: a reblog row, a comment or a wrong node answer
            // must never become the parent a wave is signed under
            if (post != null && isContainer(post, host)) return post
        }
        throw last ?: AppException(AppError(ErrorKind.NETWORK, NO_CONTAINER, true))
    }

    companion object {
        val NO_CONTAINER: String get() = L.native.waveCompose.noContainer

        /** [post] is a root post written by [host] itself, with a well-formed permlink. */
        internal fun isContainer(post: Post, host: String): Boolean =
            post.author == host && post.depth == 0 && post.parentAuthor.isNullOrEmpty() &&
                com.ecency.core.render.blocks.SafeTarget.permlink(post.permlink) != null

        /** Waits before each try of the video link call: at once, then twice more. */
        val LINK_DELAYS = listOf(0L, 3_000L, 10_000L)

        const val VOTE_WEIGHT_KEY = "waves.voteWeightPercent"
        const val KNOWN_WAVES = 2000

        /** How long a published wave stays on top without the feed confirming it. */
        const val LOCAL_MILLIS = 10 * 60 * 1000L

        /** How long a share link's text waits for the composer (a login in between included). */
        const val COMPOSE_TEXT_MILLIS = 5 * 60 * 1000L
    }
}

data class WaveComposeState(
    val text: String = "",
    /** The attached image's URL (RN keeps one). */
    val imageUrl: String? = null,
    val isAttaching: Boolean = false,
    val isSending: Boolean = false,
    /** RN shows failures in an alert: [errorTitle] ("Something went wrong" or "Fail") over the message. */
    val error: AppError? = null,
    val errorTitle: String = "",
    /** Set after a successful publish; the UI closes the sheet. */
    val sent: PostKey? = null,
    val username: String? = null,
    /** Bumped when [WaveComposeModel.prefill] replaced the text: a UI that keeps its own editor text takes it then. */
    val prefillSeq: Int = 0,
    /**
     * RN hasClipboardImage: the clipboard holds an image, so the "Paste image" chip shows. Once the chip was used
     * or closed it stays closed for this composer (RN dismissedClipboardRef).
     */
    val clipboardChip: Boolean = false,
    /** RN: the poll attached through the poll wizard (the editor's poll model), published with the wave. */
    val poll: com.ecency.core.editor.PollDraft? = null,
    /** RN videoEmbedUrl: the uploaded 3Speak video's embed address; it goes after the text and replaces the image. */
    val videoEmbedUrl: String? = null,
    /** RN videoThumbUrl: the video's uploaded thumbnail, for `json_metadata.image`. */
    val videoThumbUrl: String? = null,
    /** RN isVideoUploading: PUBLISH and the video button wait for it. */
    val isVideoUploading: Boolean = false,
    /**
     * RN POSTING_AUTHORITY_PROMPT after an `unauthorized_client` publish error: the UI shows "Enable Faster
     * Posting" and answers with [WaveComposeModel.answerAuthorityPrompt].
     */
    val authorityPrompt: Boolean = false,
) {
    /** Without the prefill counter (Swift sees no default arguments; keeps earlier callers compiling). */
    constructor(text: String, imageUrl: String?, isAttaching: Boolean, isSending: Boolean, error: AppError?, errorTitle: String, sent: PostKey?, username: String?) :
        this(text, imageUrl, isAttaching, isSending, error, errorTitle, sent, username, 0)

    /** RN: the video button is off while a video is attached or on its way. */
    val canAddVideo: Boolean get() = videoEmbedUrl == null && !isVideoUploading && !isSending

    /** RN counter: UTF-16 units of the typed text. */
    val length: Int get() = text.length

    val lengthExceeded: Boolean get() = length > Waves.MAX_BODY_LENGTH

    val counter: String get() = "$length/${Waves.MAX_BODY_LENGTH}"

    /** RN PUBLISH isDisable. */
    val canPublish: Boolean get() = !isAttaching && !isVideoUploading && !lengthExceeded && !isSending

    /** Something to publish: text that is not only spaces, an image or a video. A poll alone is not: [WaveComposeModel.send] refuses an empty body, as RN. */
    val hasContent: Boolean get() = Js.trim(text).isNotEmpty() || imageUrl != null || videoEmbedUrl != null

    /**
     * What the PUBLISH button shows and takes (not RN, whose button looks ready over an empty composer and then
     * refuses): off while [canPublish] is false and while there is nothing to publish, so it looks disabled
     * whenever a tap would do nothing.
     */
    val publishEnabled: Boolean get() = canPublish && hasContent

    /** RN "Too short to earn points (25 characters or fewer, links excluded)". */
    val showsShortHint: Boolean get() = WavePublish.showsShortContentHint(username, text)
}

/**
 * The wave composer (RN quick post modal in wave mode over usePostSubmitter). It belongs to [owner], the
 * account that opened it, and never sends as anyone else: a sheet left open across an account switch or a link
 * login refuses, and the environment disposes it on the session change.
 *
 * The first [send] of a text fixes the wave (container, permlink, metadata); a failed send keeps it, so a retry
 * broadcasts the same operations again and a key account resends the same transaction.
 */
class WaveComposeModel internal constructor(
    private val env: AppEnvironment,
    val owner: String?,
) : StateModel<WaveComposeState>(WaveComposeState(username = owner), env.dispatchers), AiTextTarget {

    // ---- RN quick post dictation and AI assist (the editor's models, over this composer) ----

    override val aiOwner: String? get() = owner

    /** One composer per account: its paid requests are found again when it opens again. */
    override val paidScope: String get() = PAID_SCOPE

    override val aiText: String get() = current.text

    /** RN quick post: `supportedActions: ['improve', 'check_grammar', 'summarize']`. */
    override val aiActions: List<String> get() = AI_ACTIONS

    /** A paid result only goes into the composer of the account that paid, while signed in and not sending. */
    private fun acceptsAiWrite(): Boolean = owner != null && env.session.current.username == owner && !current.isSending

    /**
     * RN onApply: the output replaces the whole text (the screen's own text field takes it, [WaveComposeState.prefillSeq]).
     * A Recap is copy only (RN replaced the text with it, #3599).
     */
    override fun applyAiResult(action: String, text: String, tags: List<String>): Boolean {
        if (action !in AI_ACTIONS || action == AiAssistModel.RECAP_ACTION || !acceptsAiWrite()) return false
        setState { it.copy(text = text, sent = null, prefillSeq = it.prefillSeq + 1) }
        return true
    }

    /**
     * RN _handleDictationResult: the transcript after one space unless the text is empty or already ends in
     * whitespace. At the end of the text: the composer does not report its caret (deliberate difference).
     */
    override fun insertDictation(text: String): Boolean {
        if (text.isEmpty() || !acceptsAiWrite()) return false
        setState { s ->
            val sep = if (s.text.isEmpty() || Js.isSpace(s.text.last())) "" else " "
            s.copy(text = s.text + sep + text, sent = null, prefillSeq = s.prefillSeq + 1)
        }
        return true
    }

    private var clipboardDismissed = false

    /** The platform's clipboard check (on open and whenever the app comes back to the front). */
    fun setClipboardHasImage(has: Boolean) {
        if (clipboardDismissed) return
        setState { if (it.clipboardChip == has) it else it.copy(clipboardChip = has) }
    }

    /** The chip's close button. */
    fun dismissClipboardChip() {
        clipboardDismissed = true
        setState { it.copy(clipboardChip = false) }
    }

    /** RN _handlePasteImageBtn: the chip goes for good and the clipboard's image is attached like a picked one. */
    fun pasteClipboardImage() {
        dismissClipboardChip()
        attach(WaveImageSource.CLIPBOARD)
    }

    private var draft: CommentDraft? = null
    private var draftBody: String? = null
    private var draftContainer: Post? = null
    private var draftPoll: com.ecency.core.editor.PollDraft? = null

    /** The current draft was broadcast at least once: the wave may exist although the answer was lost. */
    private var attempted = false

    /** Screens do not own this model. */
    override fun close() {}

    internal fun dispose() {
        videoModel?.close()
        videoModel = null
        super.close()
    }

    // ---- RN quick post video: SpeakUploaderModal with isShort, onVideoUploaded, _handleRemoveVideo ----

    private var videoModel: VideoUploadModel? = null

    /**
     * The composer's video upload (RN `SpeakUploaderModal isShort`): the editor's upload model, for this
     * composer's [owner]. It lives as long as the composer, so a failed upload can be continued after its sheet
     * was closed, and it goes with the composer on an account change.
     */
    fun video(): VideoUploadModel = videoModel ?: VideoUploadModel(
        env,
        object : VideoUploadTarget {
            override val owner: String? get() = this@WaveComposeModel.owner
            override fun refusal(): String? =
                VideoUploadModel.ONE_PER_POST.takeIf { current.videoEmbedUrl != null || WavePublish.hasThreeSpeakEmbed(current.text) }
            override fun uploadsChanged(delta: Int) {
                videoUploads = (videoUploads + delta).coerceAtLeast(0)
                setState { it.copy(isVideoUploading = videoUploads > 0) }
            }
            override fun insertVideo(embedUrl: String, thumbUrl: String?): Boolean = attachVideo(embedUrl, thumbUrl)
        },
        isShort = true,
    ).also { videoModel = it }

    private var videoUploads = 0

    /**
     * RN _handleVideoUploaded: only for the owner while signed in, only 3Speak's own embed address, one video a
     * wave; the thumbnail only as a clean address on the image host.
     */
    private fun attachVideo(embedUrl: String, thumbUrl: String?): Boolean {
        if (owner == null || env.session.current.username != owner || current.isSending) return false
        if (!com.ecency.core.api.speak.SpeakApi.isEmbedUrl(embedUrl) || current.videoEmbedUrl != null) return false
        val thumb = thumbUrl?.takeIf { UploadNames.isSafeUrl(it, env.imageHost) }
        setState { it.copy(videoEmbedUrl = embedUrl, videoThumbUrl = thumb, sent = null) }
        return true
    }

    /**
     * The uploaded video's embed address, or null to take the video off (RN _handleRemoveVideo). An address that
     * is not a 3Speak embed is ignored; the thumbnail goes with the video it belonged to.
     */
    fun setVideoEmbed(url: String?) {
        if (current.isSending) return
        // attaching is the owner's alone, while signed in (as the image and the poll's upload paths); removing is always allowed
        if (url != null && (!com.ecency.core.api.speak.SpeakApi.isEmbedUrl(url) || refuseOtherAccount())) return
        setState { if (it.videoEmbedUrl == url) it else it.copy(videoEmbedUrl = url, videoThumbUrl = null, sent = null) }
        if (url == null) videoModel?.reset()
    }

    fun removeVideo() = setVideoEmbed(null)

    fun setText(text: String) = setState { it.copy(text = text, sent = null) }

    /**
     * RN share link and share sheet: the composer opens with this text. A draft the user already has is never
     * overwritten: with any text or image in the composer the prefill is ignored (false).
     */
    fun prefill(text: String): Boolean {
        val s = current
        if (s.isSending || Js.trim(s.text).isNotEmpty() || s.imageUrl != null || s.videoEmbedUrl != null) return false
        setState { it.copy(text = text, sent = null, prefillSeq = it.prefillSeq + 1) }
        return true
    }

    fun removeImage() = setState { it.copy(imageUrl = null) }

    /**
     * RN's poll button: attach, update (the wizard's result) or remove (null) the wave's poll. Only a valid poll
     * is attached (a question and no empty choice, the wizard's own rule).
     */
    fun setPoll(poll: com.ecency.core.editor.PollDraft?) {
        if (current.isSending) return
        if (poll != null && !poll.isValid) return
        setState { it.copy(poll = poll, sent = null) }
    }

    fun clearError() = setState { it.copy(error = null) }

    private fun refuseOtherAccount(): Boolean {
        if (owner != null && env.session.current.username == owner) return false
        setState { it.copy(error = AppError(ErrorKind.AUTH, if (owner == null) L.native.waveCompose.pleaseSignInFirst else ACCOUNT_CHANGED, false), errorTitle = SOMETHING_WRONG) }
        return true
    }

    /** RN image button: pick, upload, attach one image. */
    fun attach(source: WaveImageSource) {
        val s = current
        if (s.isAttaching || s.isSending) return
        if (refuseOtherAccount()) return
        val attacher = env.waves.attacher
        if (attacher == null) {
            setState { it.copy(error = AppError(ErrorKind.INVALID_INPUT, NO_UPLOAD, false), errorTitle = SOMETHING_WRONG) }
            return
        }
        val user = owner ?: return
        setState { it.copy(isAttaching = true, error = null) }
        scope.launch {
            try {
                val url = attacher.attach(user, source)
                // uploaded for [owner]: an account change meanwhile disposed this model, so nothing lands
                setState { it.copy(isAttaching = false, imageUrl = url ?: it.imageUrl) }
            } catch (e: CancellationException) {
                setState { it.copy(isAttaching = false) }
                throw e
            } catch (e: Exception) {
                setState { it.copy(isAttaching = false, error = AppError.from(e), errorTitle = SOMETHING_WRONG) }
            }
        }
    }

    /**
     * A shared image ([ShareModel]), prepared by the platform like a picked one (metadata stripped, resized):
     * uploaded for [owner] and attached, unless an image or a video is already there or a send runs.
     */
    fun attachPrepared(image: PickedImage) {
        val s = current
        if (s.isAttaching || s.isSending || s.imageUrl != null || s.videoEmbedUrl != null) return
        if (refuseOtherAccount()) return
        val user = owner ?: return
        setState { it.copy(isAttaching = true, error = null) }
        scope.launch {
            try {
                val url = ImageUploads.upload(env, user, image)
                setState { it.copy(isAttaching = false, imageUrl = it.imageUrl ?: url) }
            } catch (e: CancellationException) {
                setState { it.copy(isAttaching = false) }
                throw e
            } catch (e: Exception) {
                setState { it.copy(isAttaching = false, error = AppError.from(e), errorTitle = SOMETHING_WRONG) }
            }
        }
    }

    fun send() {
        val state = current
        if (!state.canPublish) return
        val body = WavePublish.body(state.text, state.imageUrl, state.videoEmbedUrl)
        val videoThumb = state.videoThumbUrl?.takeIf { state.videoEmbedUrl != null }
        // RN posts nothing for an empty body; a body of only spaces is refused here too
        if (Js.trim(body).isEmpty()) {
            setState { it.copy(error = AppError(ErrorKind.INVALID_INPUT, L.native.waveCompose.writeSomethingFirst, false), errorTitle = SOMETHING_WRONG) }
            return
        }
        if (refuseOtherAccount()) return
        val author = owner ?: return
        setState { it.copy(isSending = true, error = null, sent = null, authorityPrompt = false) }
        val authorityRetry = authority.takeRetry()
        scope.launch {
            try {
                val container = try {
                    containerFor(body)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    setState { it.copy(isSending = false, error = AppError.from(e), errorTitle = FAIL) }
                    return@launch
                }
                val wave = draftFor(author, container, body, state.poll, videoThumb)
                // A retry: the earlier broadcast may have landed with its answer lost. The chain itself says
                // (get_content, not hivemind): the same body is the wave, published; another body is an edit,
                // which must go without comment_options (write-once: resending them fails forever).
                val onChain = if (attempted) env.api.hive.content(author, wave.permlink)?.body else null
                attempted = true
                if (onChain != body) {
                    val operations = if (onChain == null) wave.operations else wave.operations.filterNot { it is com.ecency.core.hive.CommentOptions }
                    // the same draft gives the same operations, so a key account's retry resends the same transaction
                    val signed = env.signing.broadcast(author, JsonArray(operations.map(OperationJson::encode)))
                    // a wave is a reply (SDK useComment: 110); an edit of one that is out earns nothing
                    env.pointActivities.recordBroadcast(author, PointActivity.content(container.author, isUpdate = onChain != null), signed)
                }
                val item = localItem(wave, container, body)
                env.waves.addLocal(author, item)
                // RN linkVideoToHive, fire and forget: the wave is published whatever becomes of this call
                env.waves.linkVideo(author, wave.permlink, body, WavePublish.tags(WavePublish.containerTags(container.metadata.rawJson), body))
                videoModel?.close()
                videoModel = null
                draft = null
                draftBody = null
                draftContainer = null
                draftPoll = null
                attempted = false
                setState { WaveComposeState(username = owner, sent = item.key) }
                env.waves.sent(this@WaveComposeModel)
                env.ui.toast(SUCCESS)
                env.emit(AppEvent.WavePosted(item.key))
            } catch (e: CancellationException) {
                setState { it.copy(isSending = false) }
                throw e
            } catch (e: Exception) {
                // RN: a missing ecency.app posting authority opens the grant prompt instead of the raw error
                if (authority.asks(e, authorityRetry)) {
                    setState { it.copy(isSending = false, authorityPrompt = true) }
                    return@launch
                }
                val error = AppError.fromBroadcast(e)
                // out of RC: the app-wide offer (top up or boost) instead of the error; the draft stays
                val offered = error.kind != ErrorKind.CANCELLED && env.offerRcIfShort(e)
                setState { it.copy(isSending = false, error = error.takeIf { err -> err.kind != ErrorKind.CANCELLED && !offered }, errorTitle = SOMETHING_WRONG) }
            }
        }
    }

    private val authority = AuthorityRecovery(env)

    /**
     * The answer to [WaveComposeState.authorityPrompt]. [grant]: the guarded grant (active authority), then the
     * wave is sent once more. Otherwise ("Skip for Now", or the prompt closed) nothing is sent and the draft stays.
     */
    fun answerAuthorityPrompt(grant: Boolean) {
        if (!current.authorityPrompt) return
        setState { it.copy(authorityPrompt = false) }
        if (!grant || refuseOtherAccount()) return
        val author = owner ?: return
        setState { it.copy(isSending = true) }
        scope.launch {
            try {
                authority.grant(author)
            } catch (e: CancellationException) {
                setState { it.copy(isSending = false) }
                throw e
            } catch (e: Exception) {
                val error = AppError.fromBroadcast(e)
                setState { it.copy(isSending = false, error = error.takeIf { err -> err.kind != ErrorKind.CANCELLED }, errorTitle = SOMETHING_WRONG) }
                return@launch
            }
            setState { it.copy(isSending = false) }
            send()
        }
    }

    /** The container of the kept draft while the body is unchanged, else the newest one. */
    private suspend fun containerFor(body: String): Post {
        draftContainer?.let { if (draftBody == body && draft != null && draftPoll == current.poll) return it }
        return env.waves.latestContainer()
    }

    private suspend fun draftFor(author: String, container: Post, body: String, poll: com.ecency.core.editor.PollDraft?, videoThumb: String?): CommentDraft {
        // RN leaves the video's thumbnail out of what identifies a wave (the poll is part of it): the kept draft is resent as it was built
        draft?.let { if (draftBody == body && draftPoll == poll && it.author == author) return it }
        val linked = mutableMapOf<String, String?>()
        for (link in WavePublish.postLinks(body)) {
            linked[link.url] = try {
                env.api.hive.post(link.author, link.permlink)?.title
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
        val ratios = mutableMapOf<String, Double>()
        env.waves.imageRatios?.let { probe ->
            for (url in (WavePublish.imageUrls(WavePublish.extractUrls(body)) + listOfNotNull(videoThumb)).take(5)) {
                try {
                    probe.ratio(url)?.let { ratios[url] = it }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // RN resolves NaN: the ratio is written as null
                }
            }
        }
        return WavePublish.draft(
            author, container.author, container.permlink, WavePublish.containerTags(container.metadata.rawJson), body, linked, ratios,
            poll = poll, videoThumbs = listOfNotNull(videoThumb),
        ).also {
            attempted = false
            draft = it
            draftBody = body
            draftPoll = poll
            draftContainer = container
        }
    }

    private suspend fun localItem(wave: CommentDraft, container: Post, body: String): WaveItem {
        val now = ChainTime.fromEpochSeconds(env.nowMillis() / 1000)
        val post = Post(
            author = wave.author,
            permlink = wave.permlink,
            category = container.category,
            body = body,
            metadata = PostMetadata(tags = WavePublish.tags(WavePublish.containerTags(container.metadata.rawJson), body), app = "ecency/${com.ecency.core.EcencyCore.VERSION}-mobile"),
            created = now,
            updated = now,
            depth = 1,
            parentAuthor = wave.parentAuthor,
            parentPermlink = wave.parentPermlink,
        )
        val entry = WaveEntry(post, container.author, null, now, 0, 0, hasActiveVotes = true, hasReputation = false)
        val warn = NsfwMode.of(env.prefs) == NsfwMode.WARN
        return withContext(dispatchers.render) { WaveItems.build(env, entry, wave.author, promoted = false, local = true, nsfwWarn = warn) }
    }

    companion object {
        /** [paidScope] of the wave composer. */
        const val PAID_SCOPE = "wave"

        /** RN quick post supportedActions. */
        val AI_ACTIONS = listOf("improve", "check_grammar", "summarize")
        val ACCOUNT_CHANGED: String get() = L.native.waveCompose.accountChanged
        val NO_UPLOAD: String get() = L.native.waveCompose.noUpload

        /** RN alert.success, alert.something_wrong, and the literal 'Fail' of `_submitWave`. */
        val SUCCESS: String get() = L.alert.success
        val SOMETHING_WRONG: String get() = L.alert.somethingWrong
        val FAIL: String get() = L.native.waveCompose.fail
    }
}

/** How a reel plays. */
sealed interface ShortPlayback {
    /** Not resolved yet: the poster shows. */
    data object Loading : ShortPlayback

    /** The 3Speak HLS manifest, for the platform player. */
    data class Stream(val url: String) : ShortPlayback

    /** RN's fallback: 3Speak's web player (an allowlisted embed) when the stream cannot be resolved or played. */
    data class Web(val url: String) : ShortPlayback
}

/** One reel (RN wavesReelItem). */
data class ShortItem(
    val post: Post,
    val card: PostCard,
    val video: ShortVideo,
    /** RN buildReelCaption: the human text of the title, else the body, at most 140 UTF-16 units. */
    val caption: String,
    /** The poster, or null for a plain dark frame. */
    val thumbnailUrl: String?,
) {
    val key: PostKey get() = post.key
}

data class ShortsState(
    val items: List<ShortItem> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val endReached: Boolean = false,
    val error: AppError? = null,
    val loaded: Boolean = false,
    val pages: Int = 0,
    /** The reel in view; the only one that may play. */
    val activeKey: PostKey? = null,
    /** The Shorts tab is the one on screen and the app is in front (RN `focused`). */
    val focused: Boolean = false,
    val playback: Map<PostKey, ShortPlayback> = emptyMap(),
    /** RN's mute button, kept for the session. */
    val muted: Boolean = false,
) {
    /** The one reel that plays right now, or null: nothing plays off screen. */
    val playingKey: PostKey? get() = activeKey.takeIf { focused }

    fun playbackOf(key: PostKey): ShortPlayback = playback[key] ?: ShortPlayback.Loading
}

/** RN wavesReelItem sanitizeCaptionText / buildReelCaption (golden against the RN source). */
object ShortCaption {
    private val IMAGE = Regex("""!\[[^\]]*\]\([^)]*\)""")
    private val SPEAK_LINK = Regex("""\[[^\]]*\]\((?:[hH][tT][tT][pP][sS]?://)?(?:[pP][lL][aA][yY]\.)?3[sS][pP][eE][aA][kK][^)]*\)""")
    private val LINK = Regex("""\[([^\]]*)\]\([^)]*\)""")
    private val TAG = Regex("""<[^>]+>""")

    fun sanitize(value: String): String {
        var s = IMAGE.replace(value, " ")
        s = SPEAK_LINK.replace(s, " ")
        s = LINK.replace(s) { it.groupValues[1] }
        s = TAG.replace(s, " ")
        s = stripUrls(s)
        s = s.replace("▶️", " ").replace("▶", " ")
        return Js.trim(collapse(s))
    }

    /** `/https?:\/\/\S+/gi` to a space. */
    private fun stripUrls(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val skip = when {
                Js.regionMatchesLower(s, i, "https://") -> 8
                Js.regionMatchesLower(s, i, "http://") -> 7
                else -> 0
            }
            if (skip > 0 && i + skip < s.length && !Js.isSpace(s[i + skip])) {
                i += skip
                while (i < s.length && !Js.isSpace(s[i])) i++
                sb.append(' ')
            } else {
                sb.append(s[i])
                i++
            }
        }
        return sb.toString()
    }

    /** `/\s+/g` to one space (JS whitespace). */
    private fun collapse(s: String): String {
        val sb = StringBuilder()
        var inSpace = false
        for (c in s) {
            if (Js.isSpace(c)) {
                if (!inSpace) sb.append(' ')
                inSpace = true
            } else {
                sb.append(c)
                inSpace = false
            }
        }
        return sb.toString()
    }

    fun of(title: String?, body: String?): String =
        sanitize(title.orEmpty()).ifEmpty { sanitize(body.orEmpty()) }.take(140)
}

/**
 * The Shorts reels (RN wavesReelsView over useShortsQuery): waves that embed a 3Speak video, from
 * `waves/shorts`, with RN's visibility filter. Exactly one reel may play, [ShortsState.playingKey]: the one in
 * view while the tab is focused. The stream of a reel is resolved when it becomes active.
 *
 * Like the waves lists it belongs to the account that loaded it and starts empty after an account change.
 */
class ShortsModel internal constructor(private val env: AppEnvironment) : StateModel<ShortsState>(ShortsState(), env.dispatchers) {
    private var pager: WavesPager? = null
    private var job: Job? = null
    private var pollJob: Job? = null
    private var generation = 0
    private var started = false
    private val resolving = mutableSetOf<PostKey>()

    fun post(key: PostKey): Post? = current.items.firstOrNull { it.key == key }?.post

    init {
        scope.launch {
            var previous = env.localVotes.state.value
            env.localVotes.state.drop(1).collect { local ->
                val changed = Cards.changedKeys(previous, local)
                previous = local
                if (changed.isEmpty()) return@collect
                val user = env.session.current.username
                setState { s ->
                    val items = Cards.replaceChanged(s.items, changed, { it.key }) { item ->
                        item.copy(card = Cards.withVote(item.card, item.post, user, local[item.key]))
                    }
                    if (items === s.items) s else s.copy(items = items)
                }
            }
        }
        scope.launch {
            env.events.collect { event ->
                when (event) {
                    is AppEvent.SessionChanged, AppEvent.DataWiped -> {
                        generation++
                        job?.cancel()
                        job = null
                        pager = null
                        resolving.clear()
                        setState { ShortsState(focused = it.focused, muted = it.muted) }
                        if (started) load(reset = true, refreshing = false)
                    }
                    is AppEvent.AuthorMuted, AppEvent.MutesLoaded ->
                        setState { s -> s.copy(items = s.items.filter { it.post.author !in env.mutedAuthors }).let(::withActive) }
                    is AppEvent.PostDeleted -> setState { s -> s.copy(items = s.items.filter { it.key != event.key }).let(::withActive) }
                    else -> {}
                }
            }
        }
    }

    /** The active reel left the list: none is active until the UI reports the one in view. */
    private fun withActive(s: ShortsState): ShortsState =
        if (s.activeKey != null && s.items.none { it.key == s.activeKey }) s.copy(activeKey = null) else s

    fun start() {
        started = true
        if (current.loaded || job?.isActive == true) return
        load(reset = true, refreshing = false)
    }

    fun refresh() {
        started = true
        load(reset = true, refreshing = current.items.isNotEmpty())
    }

    @Throws(CancellationException::class)
    suspend fun refreshAndWait() {
        refresh()
        scope.async { state.first { !it.isRefreshing && !it.isLoading } }.await()
    }

    fun loadMore() {
        val s = current
        if (job?.isActive == true || s.endReached || s.error != null || !s.loaded) return
        load(reset = false, refreshing = false)
    }

    fun retry() {
        if (job?.isActive == true) return
        val reset = !current.loaded || pager == null
        load(reset = reset, refreshing = reset && current.items.isNotEmpty())
    }

    /**
     * The tab came on screen with the app in front ([focused]) or left it (another tab, another screen, the app
     * in the background or locked). Nothing plays while not focused. RN refetches every minute while mounted;
     * here only while focused.
     */
    fun setFocused(focused: Boolean) {
        setState { if (it.focused == focused) it else it.copy(focused = focused) }
        if (!focused) {
            pollJob?.cancel()
            pollJob = null
        } else {
            current.activeKey?.let(::resolve)
            if (pollJob?.isActive != true) {
                pollJob = scope.launch {
                    while (true) {
                        delay(WavesFeedModel.REFETCH_MILLIS)
                        refetchFirstPage()
                    }
                }
            }
        }
    }

    /** The reel that covers the viewport (RN: 80% visible). Its stream is resolved now. */
    fun setActive(key: PostKey?) {
        if (key != null && current.items.none { it.key == key }) return
        setState { if (it.activeKey == key) it else it.copy(activeKey = key) }
        if (key != null && current.focused) resolve(key)
    }

    fun toggleMute() = setState { it.copy(muted = !it.muted) }

    /** The platform player could not play [key]'s stream: RN falls back to 3Speak's web player. */
    fun playbackFailed(key: PostKey) {
        val item = current.items.firstOrNull { it.key == key } ?: return
        setState { it.copy(playback = it.playback + (key to web(item))) }
    }

    private fun web(item: ShortItem): ShortPlayback = ShortPlayback.Web(WavesApi.threeSpeakWatchUrl(item.video.author, item.video.permlink))

    private fun resolve(key: PostKey) {
        val item = current.items.firstOrNull { it.key == key } ?: return
        if (current.playback[key].let { it != null && it != ShortPlayback.Loading } || !resolving.add(key)) return
        val startedIn = generation
        scope.launch {
            try {
                val url = env.waves.api.threeSpeakStream(item.video.author, item.video.permlink)
                if (startedIn != generation) return@launch
                setState { it.copy(playback = it.playback + (key to (url?.let(ShortPlayback::Stream) ?: web(item)))) }
            } finally {
                resolving -= key
            }
        }
    }

    private suspend fun visible(entries: List<WaveEntry>): List<WaveEntry> {
        val bots = env.botAuthors()
        val nsfw = NsfwMode.of(env.prefs)
        return entries.filter { e ->
            e.video != null && e.post.author !in env.mutedAuthors && !e.post.isMuted && e.post.author !in bots &&
                (nsfw != NsfwMode.HIDE || !NsfwMode.isNsfw(e.post))
        }
    }

    private suspend fun build(entries: List<WaveEntry>, user: String?): List<ShortItem> = withContext(dispatchers.render) {
        entries.distinctBy { it.post.key }.mapNotNull { e ->
            val video = e.video ?: return@mapNotNull null
            val post = WaveItems.post(e)
            ShortItem(
                post = post,
                card = Cards.of(post, env.renderer, user, env.localVotes.get(post.key), env.proxyBase),
                video = video,
                caption = ShortCaption.of(post.title, post.body),
                thumbnailUrl = video.thumbnailUrl?.let { com.ecency.core.render.blocks.SafeTarget.httpUrl(it) }?.takeIf { it.startsWith("https://") },
            )
        }
    }

    private suspend fun refetchFirstPage() {
        if (!current.loaded || job?.isActive == true) return
        val user = env.session.current.username
        val startedIn = generation
        val fresh = try {
            build(visible(env.waves.api.shorts(WavesFeedParams(observer = user))), user)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        }
        if (startedIn != generation || job?.isActive == true) return
        val byKey = fresh.associateBy { it.key }
        setState { s ->
            val known = s.items.map { it.key }.toHashSet()
            // new reels go below the one being watched, never above it: the pager must not jump
            s.copy(items = s.items.map { byKey[it.key] ?: it } + fresh.filter { it.key !in known })
        }
    }

    private fun load(reset: Boolean, refreshing: Boolean) {
        job?.cancel()
        val user = env.session.current.username
        val params = WavesFeedParams(observer = user)
        if (reset) pager = null
        val activePager = pager ?: WavesPager(params.limit) { cursor -> env.waves.api.shorts(params, cursor) }.also { pager = it }
        setState {
            it.copy(
                isLoading = reset && !refreshing, isRefreshing = refreshing, isLoadingMore = !reset, error = null,
                items = if (reset && !refreshing) emptyList() else it.items,
            )
        }
        val startedIn = generation
        job = scope.launch {
            try {
                val items = build(visible(activePager.loadMore()), user)
                if (startedIn != generation) return@launch
                env.waves.markWaves(items.map { it.key })
                setState { s ->
                    val known = if (reset) emptySet() else s.items.map { it.key }.toHashSet()
                    val next = if (reset) items else s.items + items.filter { it.key !in known }
                    withActive(
                        s.copy(
                            items = next, isLoading = false, isRefreshing = false, isLoadingMore = false,
                            endReached = activePager.isEndReached, loaded = true, pages = if (reset) 1 else s.pages + 1,
                            playback = if (reset) emptyMap() else s.playback,
                        ),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (startedIn != generation) return@launch
                if (reset) pager = null
                setState { it.copy(isLoading = false, isRefreshing = false, isLoadingMore = false, error = AppError.from(e)) }
            }
        }
    }

    companion object {
        val EMPTY: String get() = L.waves.shortsEmpty
    }
}

package com.ecency.core.app

import com.ecency.core.api.broadcast.CommentDraft
import com.ecency.core.api.broadcast.Comments
import com.ecency.core.api.broadcast.OperationJson
import com.ecency.core.l10n.L
import kotlinx.serialization.json.JsonArray
import com.ecency.core.api.broadcast.PayoutType
import com.ecency.core.api.model.Post
import com.ecency.core.api.model.PostKey
import com.ecency.core.api.model.PostMetadata
import com.ecency.core.hive.ChainTime
import com.ecency.core.hive.CommentOptions
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

data class ReplyState(
    val text: String = "",
    val isSending: Boolean = false,
    val error: AppError? = null,
    /** Permlink of the draft being sent; kept across failed attempts. */
    val draftPermlink: String? = null,
    /** Set after a successful send (the text is cleared then). */
    val sent: PostKey? = null,
    /**
     * An earlier attempt already posted this reply and the text changed since: the UI asks "Update it, or post
     * this as a new reply?" and answers with [ComposeReplyModel.resolveConflict].
     */
    val conflict: Boolean = false,
    /**
     * RN POSTING_AUTHORITY_PROMPT after an `unauthorized_client` publish error: the UI shows "Enable Faster
     * Posting" and answers with [ComposeReplyModel.answerAuthorityPrompt].
     */
    val authorityPrompt: Boolean = false,
    /** RN quick reply's one image ([ComposeReplyModel.attach]), sent after the text as ` ![](url)`. */
    val imageUrl: String? = null,
    /** The image is being picked or uploaded: Reply waits for it. */
    val isAttaching: Boolean = false,
    /** RN "Image in clipboard · Paste image" chip. */
    val clipboardChip: Boolean = false,
    /** Bumped when the model replaced the text (dictation, AI assist, expand): the text field takes it. */
    val prefillSeq: Int = 0,
) {
    /** RN: an image alone is a reply too (its body is the image line). */
    val canSend: Boolean get() = (text.isNotBlank() || imageUrl != null) && !isSending && !isAttaching
}

/**
 * Reply composer for [parent]. The first [send] fixes the reply's permlink and remembers a hash of what was
 * sent ([PendingPublish], also in the local draft). A retry asks the chain first ([PendingCheck]): the reply is
 * there and the text is the same, it counts as sent; it is there and the text changed, the user chooses between
 * updating it and posting a new reply (never a silent edit); it is not there, the same permlink goes out again
 * (the same signed transaction while the text is unchanged, so Hive dedupes it).
 *
 * The draft lives in the same local slot, in the same format, as the editor's reply mode ([LocalDrafts.reply]):
 * either surface continues what the other wrote, pending publish included.
 *
 * Owned by [AppEnvironment] (one per parent, see [AppEnvironment.replyModel]), not by a screen: a send
 * keeps running when the post screen goes away, the draft is there again when the user comes back,
 * and the result still reaches every open discussion through [AppEvent.CommentPosted]. [close] is
 * therefore a no-op; the environment drops drafts on a session change.
 */
class ComposeReplyModel internal constructor(
    private val env: AppEnvironment,
    val root: PostKey,
    val parent: Post,
    private val payout: PayoutType = PayoutType.DEFAULT,
) : StateModel<ReplyState>(ReplyState(), env.dispatchers), AiTextTarget {

    private var pending: PendingPublish? = null
    private var conflictChoice: Boolean? = null

    // ---- RN quick reply's toolbar: dictation and AI assist (the editor's models), image and clipboard (the wave composer's upload) ----

    override val aiOwner: String? get() = owner

    /** The editor's reply mode pays in the same scope: both write the same reply. */
    override val paidScope: String get() = slot

    override val aiText: String get() = current.text

    /** RN quick reply: `supportedActions: ['improve', 'check_grammar', 'summarize']`, as the wave composer. */
    override val aiActions: List<String> get() = WaveComposeModel.AI_ACTIONS

    private fun acceptsAiWrite(): Boolean = owner != null && env.session.current.username == owner && !current.isSending

    /** RN onApply: the output replaces the text. A Recap is copy only (#3599). */
    override fun applyAiResult(action: String, text: String, tags: List<String>): Boolean {
        if (action !in aiActions || action == AiAssistModel.RECAP_ACTION || !acceptsAiWrite()) return false
        setText(text)
        setState { it.copy(prefillSeq = it.prefillSeq + 1) }
        return true
    }

    /** RN _handleDictationResult: the transcript after one space unless the text is empty or ends in whitespace. */
    override fun insertDictation(text: String): Boolean {
        if (text.isEmpty() || !acceptsAiWrite()) return false
        val now = current.text
        val sep = if (now.isEmpty() || com.ecency.core.render.js.Js.isSpace(now.last())) "" else " "
        setText(now + sep + text)
        setState { it.copy(prefillSeq = it.prefillSeq + 1) }
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

    /**
     * A tile of the composer's image sheet, the editor's uploads sheet ([MediaSourceMenus.editorUploads]): paste is
     * the chip's paste, the others the reply's one image attach.
     */
    fun attachFrom(source: MediaSource) {
        val mapped = WaveImageSource.of(source) ?: return
        if (mapped == WaveImageSource.CLIPBOARD) pasteClipboardImage() else attach(mapped)
    }

    /**
     * RN's image button (UploadsGalleryModal, `allowMultiple={false}`): pick, upload for [owner] through the app's
     * image upload (the wave composer's [WaveImageAttacher]), attach one image; a second one replaces it, as RN.
     * Saved with the draft, apart from the text, as RN's reply cache kept it.
     */
    fun attach(source: WaveImageSource) {
        val s = current
        if (s.isAttaching || s.isSending) return
        val user = owner
        if (user == null || env.session.current.username != user) {
            setState { it.copy(error = AppError(ErrorKind.AUTH, if (user == null) L.native.composeReply.pleaseSignInFirst else ACCOUNT_CHANGED, false)) }
            return
        }
        val attacher = env.waves.attacher
        if (attacher == null) {
            setState { it.copy(error = AppError(ErrorKind.INVALID_INPUT, WaveComposeModel.NO_UPLOAD, false)) }
            return
        }
        setState { it.copy(isAttaching = true, error = null) }
        scope.launch {
            try {
                val url = attacher.attach(user, source)
                setState { it.copy(isAttaching = false, imageUrl = url ?: it.imageUrl, sent = null) }
                if (url != null) saveNow()
            } catch (e: CancellationException) {
                setState { it.copy(isAttaching = false) }
                throw e
            } catch (e: Exception) {
                setState { it.copy(isAttaching = false, error = AppError.from(e, "composeReply.attach")) }
            }
        }
    }

    /**
     * RN quick reply's UploadsGalleryModal (`allowMultiple={false}`, `_handleMediaInsert`): an image the account
     * uploaded before ([UploadsModel] over `env.uploadsModel(null)`) becomes the reply's one image, nothing is
     * uploaded. The same guards as [attach]: not while attaching or sending, only for [owner] while signed in, and
     * only a clean https address (any host: older uploads may sit on another one). Saved with the draft at once, as RN's `_addQuickCommentIntoCache`.
     */
    fun pickUploaded(url: String) {
        val s = current
        if (s.isAttaching || s.isSending) return
        val user = owner
        if (user == null || env.session.current.username != user) {
            setState { it.copy(error = AppError(ErrorKind.AUTH, if (user == null) L.native.composeReply.pleaseSignInFirst else ACCOUNT_CHANGED, false)) }
            return
        }
        if (!UploadNames.isClean(url)) return
        setState { it.copy(imageUrl = url, sent = null, error = null) }
        saveNow()
    }

    /** RN: a tap on the thumbnail takes the image off. */
    fun removeImage() {
        if (current.isSending) return
        setState { it.copy(imageUrl = null) }
        saveNow()
    }

    /** What goes on chain: RN _submitPost's `text\n\n ![](url)` with an image. */
    private fun bodyOf(s: ReplyState): String = com.ecency.core.api.waves.WavePublish.body(s.text, s.imageUrl)

    companion object {
        val ACCOUNT_CHANGED: String get() = L.native.composeReply.accountChanged
    }

    /** Screens do not own this model; it lives as long as the environment keeps the draft. */
    override fun close() {}

    /** Cancels a running send and stops the model (sign-out, wipe). */
    internal fun dispose() = super.close()

    /**
     * The account the composer was opened by (bound once the session is read, or at the first send when it was
     * opened signed out). A draft is never sent, loaded or stored as anyone else: a sheet left open across a switch
     * or a link login refuses.
     */
    private var owner: String? = env.session.current.username
    private var saveJob: kotlinx.coroutines.Job? = null
    private val slot = LocalDrafts.reply(parent.author, parent.permlink)

    init {
        // RN replyCache: a reply draft survives the app being closed, per account and parent (shared with the editor)
        scope.launch {
            env.session.awaitLoaded()
            if (owner == null) owner = env.session.current.username
            val user = owner ?: return@launch
            val local = env.localDrafts.load(user, slot) ?: return@launch
            if (pending == null) pending = local.pending
            if (current.isSending) return@launch
            if (local.body.isNotEmpty() && current.text.isEmpty()) setState { it.copy(text = local.body, draftPermlink = local.pending?.permlink) }
            if (local.replyImage != null && current.imageUrl == null && !current.isAttaching) setState { it.copy(imageUrl = local.replyImage) }
        }
    }

    /** The slot's draft with [text] and [image] (the attached one); the pending publish is always carried along. */
    private suspend fun store(user: String, text: String, image: String?) {
        if (text.isEmpty() && image == null && pending == null) {
            env.localDrafts.delete(user, slot)
        } else {
            val base = env.localDrafts.load(user, slot) ?: LocalDraft()
            env.localDrafts.save(user, slot, base.copy(body = text, updated = env.nowMillis(), pending = pending, replyImage = image))
        }
    }

    /** The image changed: the draft is written at once, with the latest text. */
    private fun saveNow() {
        val user = owner ?: return
        saveJob?.cancel()
        saveJob = scope.launch { store(user, current.text, current.imageUrl) }
    }

    fun setText(text: String) {
        setState { it.copy(text = text, sent = null) }
        val user = owner ?: return
        saveJob?.cancel()
        saveJob = scope.launch {
            kotlinx.coroutines.delay(EditorModel.LOCAL_SAVE_DELAY_MILLIS)
            store(user, text, current.imageUrl)
        }
    }

    /**
     * RN's quick reply "expand" button: the full editor continues this reply. The text is written to the shared
     * slot first, then [onReady] gets the editor's arguments (main thread). Nothing happens while an image is
     * being picked or uploaded: the editor would open without it.
     */
    fun expand(onReady: (EditorArgs) -> Unit) {
        if (current.isAttaching) return
        val user = owner
        // RN editorContainer: the quick reply's image (replyMediaUrls) continues in the editor's body
        val text = bodyOf(current)
        if (current.imageUrl != null) setState { it.copy(text = text, imageUrl = null, prefillSeq = it.prefillSeq + 1) }
        scope.launch {
            saveJob?.cancel()
            if (user != null) store(user, text, null)
            onReady(EditorArgs(EditorKind.REPLY, parent.author, parent.permlink, rootAuthor = root.author, rootPermlink = root.permlink))
        }
    }

    /** The answer to [ReplyState.conflict]: [update] edits the reply that is already out, otherwise a new reply is posted. */
    fun resolveConflict(update: Boolean) {
        if (!current.conflict) return
        conflictChoice = update
        setState { it.copy(conflict = false) }
        send()
    }

    fun dismissConflict() = setState { it.copy(conflict = false) }

    fun send() {
        val state = current
        if (state.isSending || state.isAttaching) return
        if (state.text.isBlank() && state.imageUrl == null) {
            setState { it.copy(error = AppError(ErrorKind.INVALID_INPUT, L.native.composeReply.writeSomethingFirst, false)) }
            return
        }
        // signed by the account signed in at the tap (a session change disposes this model and its sends)
        val signedInAs = env.session.current.username
        if (owner == null) owner = signedInAs
        if (signedInAs != owner) {
            setState { it.copy(error = AppError(ErrorKind.AUTH, ACCOUNT_CHANGED, false)) }
            return
        }
        setState { it.copy(isSending = true, error = null, sent = null, conflict = false, authorityPrompt = false) }
        val authorityRetry = authority.takeRetry()
        val text = bodyOf(state)
        val choice = conflictChoice
        conflictChoice = null
        scope.launch {
            try {
                val author = signedInAs ?: throw AppException(AppError(ErrorKind.AUTH, L.native.composeReply.pleaseSignInFirst, false))
                saveJob?.cancel()
                val hash = PendingPublish.hashOf(parent.author, parent.permlink, "", text)
                val was = pending
                var permlink: String? = null
                var create = true
                var send = true
                when (PendingCheck.verdict(env, author, was, hash) { PendingPublish.hashOf(it.parentAuthor, it.parentPermlink, "", it.body) }) {
                    PendingVerdict.PUBLISHED -> {
                        permlink = was!!.permlink
                        send = false
                    }
                    PendingVerdict.CONFLICT, PendingVerdict.CONFLICT_CHANGED_ELSEWHERE -> when (choice) {
                        null -> {
                            setState { it.copy(isSending = false, conflict = true) }
                            return@launch
                        }
                        true -> {
                            // an edit of the reply that is out: comment_options is write-once on chain
                            permlink = was!!.permlink
                            create = false
                        }
                        false -> pending = null
                    }
                    PendingVerdict.RESEND -> permlink = was!!.permlink
                    PendingVerdict.FRESH -> pending = null
                }
                val now = env.nowMillis()
                // a new reply never reuses the permlink of the one that is out (same millisecond on a coarse clock)
                val link = permlink ?: Comments.replyPermlink(parent.author, now).let { if (it == was?.permlink) Comments.replyPermlink(parent.author, now + 1) else it }
                setState { it.copy(draftPermlink = link) }
                if (send) {
                    // the metadata the RN editor writes for a reply (links, images, type "comment"), shared with the editor
                    val json = com.ecency.core.editor.EditorMeta.stringify(EditorModels.replyMetadata(env, text, parent.metadata.tags))
                    val ops = buildList<com.ecency.core.hive.Operation> {
                        add(com.ecency.core.hive.Comment(parent.author, parent.permlink, author, link, "", text, json))
                        if (create && payout != PayoutType.DEFAULT) {
                            val reward = if (payout == PayoutType.DECLINE) com.ecency.core.editor.RewardType.DECLINE else com.ecency.core.editor.RewardType.POWER_UP
                            add(com.ecency.core.editor.EditorMeta.commentOptions(author, link, reward, emptyList()))
                        }
                    }
                    pending = pending?.takeIf { it.permlink == link }?.alsoSent(hash, now) ?: PendingPublish(link, listOf(hash), now)
                    // the body as sent (the image in it), so the editor's reply mode sees what the pending hash covers
                    store(author, text, null)
                    // the same text gives the same operations, so a key account's retry resends the same transaction
                    val signed = env.signing.broadcast(author, JsonArray(ops.map(OperationJson::encode)))
                    // SDK useComment: a new reply earns points, an edit of the one that is out does not
                    env.pointActivities.recordBroadcast(author, PointActivity.content(parent.author, isUpdate = !create), signed)
                }
                val local = localPost(author, link, text)
                env.api.posts.addLocalComment(root, local)
                pending = null
                env.localDrafts.delete(author, slot)
                setState { ReplyState(sent = local.key) }
                env.replySent(this@ComposeReplyModel)
                env.emit(AppEvent.CommentPosted(root, local.key))
            } catch (e: CancellationException) {
                setState { it.copy(isSending = false) }
                throw e
            } catch (e: Exception) {
                // RN: a missing ecency.app posting authority opens the grant prompt instead of the raw error
                if (authority.asks(e, authorityRetry)) {
                    setState { it.copy(isSending = false, authorityPrompt = true) }
                    return@launch
                }
                val error = AppError.fromBroadcast(e, "composeReply.send")
                // out of RC: the app-wide offer (top up or boost) instead of the error; the draft stays
                val offered = error.kind != ErrorKind.CANCELLED && env.offerRcIfShort(e)
                setState { it.copy(isSending = false, error = error.takeIf { err -> err.kind != ErrorKind.CANCELLED && !offered }) }
            }
        }
    }

    private val authority = AuthorityRecovery(env)

    /**
     * The answer to [ReplyState.authorityPrompt]. [grant]: the guarded grant (active authority), then the reply
     * is sent once more. Otherwise ("Skip for Now", or the prompt closed) nothing is sent and the draft stays.
     */
    fun answerAuthorityPrompt(grant: Boolean) {
        if (!current.authorityPrompt) return
        setState { it.copy(authorityPrompt = false) }
        if (!grant) return
        val author = owner
        if (author == null || env.session.current.username != author) {
            setState { it.copy(error = AppError(ErrorKind.AUTH, ACCOUNT_CHANGED, false)) }
            return
        }
        setState { it.copy(isSending = true) }
        scope.launch {
            try {
                authority.grant(author)
            } catch (e: CancellationException) {
                setState { it.copy(isSending = false) }
                throw e
            } catch (e: Exception) {
                val error = AppError.fromBroadcast(e, "composeReply.answerAuthorityPrompt")
                setState { it.copy(isSending = false, error = error.takeIf { err -> err.kind != ErrorKind.CANCELLED }) }
                return@launch
            }
            setState { it.copy(isSending = false) }
            send()
        }
    }

    fun clearError() = setState { it.copy(error = null) }

    private fun localPost(author: String, permlink: String, body: String): Post {
        val now = ChainTime.fromEpochSeconds(env.nowMillis() / 1000)
        return Post(
            author = author,
            permlink = permlink,
            category = parent.category,
            body = body,
            metadata = PostMetadata(tags = parent.metadata.tags),
            created = now,
            updated = now,
            depth = parent.depth + 1,
            parentAuthor = parent.author,
            parentPermlink = parent.permlink,
        )
    }
}

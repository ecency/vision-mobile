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
) {
    val canSend: Boolean get() = text.isNotBlank() && !isSending
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
) : StateModel<ReplyState>(ReplyState(), env.dispatchers) {

    private var pending: PendingPublish? = null
    private var conflictChoice: Boolean? = null

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
            if (local.body.isNotEmpty() && current.text.isEmpty() && !current.isSending) setState { it.copy(text = local.body, draftPermlink = local.pending?.permlink) }
        }
    }

    /** The slot's draft with [text]; the pending publish is always carried along. */
    private suspend fun store(user: String, text: String) {
        if (text.isEmpty() && pending == null) {
            env.localDrafts.delete(user, slot)
        } else {
            val base = env.localDrafts.load(user, slot) ?: LocalDraft()
            env.localDrafts.save(user, slot, base.copy(body = text, updated = env.nowMillis(), pending = pending))
        }
    }

    fun setText(text: String) {
        setState { it.copy(text = text, sent = null) }
        val user = owner ?: return
        saveJob?.cancel()
        saveJob = scope.launch {
            kotlinx.coroutines.delay(EditorModel.LOCAL_SAVE_DELAY_MILLIS)
            store(user, text)
        }
    }

    /**
     * RN's quick reply "expand" button: the full editor continues this reply. The text is written to the shared
     * slot first, then [onReady] gets the editor's arguments (main thread).
     */
    fun expand(onReady: (EditorArgs) -> Unit) {
        val user = owner
        val text = current.text
        scope.launch {
            saveJob?.cancel()
            if (user != null) store(user, text)
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
        if (state.isSending) return
        if (state.text.isBlank()) {
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
        val text = state.text
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
                    store(author, text)
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
                val error = AppError.fromBroadcast(e)
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
                val error = AppError.fromBroadcast(e)
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

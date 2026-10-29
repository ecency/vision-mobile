package com.ecency.core.app

import com.ecency.core.api.broadcast.Comments
import com.ecency.core.api.broadcast.OperationJson
import com.ecency.core.api.ecency.RemoteDraft
import com.ecency.core.api.model.JsValue
import com.ecency.core.api.model.Post
import com.ecency.core.api.model.PostKey
import com.ecency.core.api.model.PostMetadata
import com.ecency.core.api.model.arr
import com.ecency.core.api.model.obj
import com.ecency.core.api.model.str
import com.ecency.core.api.model.stringList
import com.ecency.core.editor.BeneficiaryRoute
import com.ecency.core.editor.EditorFormats
import com.ecency.core.editor.EditorMeta
import com.ecency.core.editor.EditorTags
import com.ecency.core.editor.LinkedPost
import com.ecency.core.editor.MediaInsert
import com.ecency.core.editor.PollDraft
import com.ecency.core.editor.RewardType
import com.ecency.core.editor.Slug
import com.ecency.core.editor.TextEdit
import com.ecency.core.editor.TextSelection
import com.ecency.core.hive.ChainTime
import com.ecency.core.hive.Operation
import com.ecency.core.l10n.L
import com.ecency.core.render.RenderHelper
import com.ecency.core.render.blocks.Block
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.cancellation.CancellationException

/** What the editor is writing (RN route params `isReply`, `isEdit`, `draftId`). */
enum class EditorKind { NEW_POST, EDIT_POST, REPLY, EDIT_REPLY }

/** How an editor session ended; the UI navigates on it. */
sealed interface EditorDone {
    /** A new post went out. [draftId] is the saved draft it came from (RN then offers to delete it). */
    data class Published(val author: String, val permlink: String, val draftId: String?) : EditorDone

    /** RN opens the drafts screen on its Schedules tab. */
    data object Scheduled : EditorDone

    data class Edited(val key: PostKey) : EditorDone

    data class Replied(val key: PostKey) : EditorDone
}

/** The community a post goes to (first tag), with what RN's posting restriction needs. */
data class EditorCommunity(val name: String, val title: String, val typeId: Int = 1, val role: String = "guest") {
    /** RN _checkCanPostToCommunity: journals (2) and councils (3) take posts from members only; guests may reply in journals. */
    fun canPost(isReply: Boolean): Boolean = when (typeId) {
        2 -> isReply || role != "guest"
        3 -> role != "guest"
        else -> true
    }
}

/** The publish button while a draft save runs. */
val SAVING_DRAFT: String get() = L.native.editor.savingDraft

data class EditorState(
    val kind: EditorKind,
    /** The account this editor belongs to; null until the session was read. It never changes afterwards. */
    val owner: String? = null,
    val loading: Boolean = true,
    val title: String = "",
    val body: String = "",
    val selection: TextSelection = TextSelection(0),
    /** Goes up whenever the model (not the keyboard) rewrote [body] or [selection]: the text field takes them over. */
    val bodyRevision: Int = 0,
    val tags: List<String> = emptyList(),
    val tagInput: String = "",
    val tagWarning: String? = null,
    val community: EditorCommunity? = null,
    val isPreview: Boolean = false,
    val preview: List<Block>? = null,
    val reward: RewardType = RewardType.DEFAULT,
    val isDefaultReward: Boolean = false,
    /** RN `moment(date).format()`, null for "Immediate". */
    val scheduledFor: String? = null,
    val reblog: Boolean = false,
    val thumbUrl: String = "",
    val description: String = "",
    /** Without the author's own row. */
    val beneficiaries: List<BeneficiaryRoute> = emptyList(),
    /** The author set the list (RN: a key in beneficiariesMap); only then is it published as is. */
    val beneficiariesExplicit: Boolean = false,
    val supportPercent: Double = 0.0,
    val poll: PollDraft? = null,
    val draftId: String? = null,
    val isDraftSaved: Boolean = false,
    val isDraftSaving: Boolean = false,
    val isSending: Boolean = false,
    /** Uploads still running; publishing waits for them (RN isUploading). */
    val uploading: Int = 0,
    val wordsCount: Int = 0,
    /** RN's floating "DRAFT" button: a recent remote draft can be loaded into the empty editor. */
    val recentDraftAvailable: Boolean = false,
    /** RN videoThumbs: thumbnails of the videos uploaded here, by embed address. */
    val videoThumbs: List<com.ecency.core.editor.VideoThumb> = emptyList(),
    /** A reply's parent summary, shown above the field. */
    val parentSummary: String? = null,
    /** The post being edited or replied to could not be loaded. */
    val error: AppError? = null,
    /**
     * The post was already published by an earlier attempt and the text changed since: the UI asks "Update it, or
     * publish this as a new post?" and answers with [EditorModel.resolvePublishConflict].
     */
    val publishConflict: Boolean = false,
    /** With [publishConflict]: what is on chain under that link is not what was sent from here (edited elsewhere). */
    val conflictChangedElsewhere: Boolean = false,
    /**
     * Scheduling needs ecency.app in the account's posting authority and it is not there: the UI asks before the
     * app changes the account ([EditorModel.confirmPostingGrant]); the grant then needs the active key.
     */
    val grantPrompt: Boolean = false,
    /**
     * RN RcPrecheckBanner: the account's resource credits look too low for what is written ("This post may be too
     * large to publish"). Advice only: it never blocks the submit, and a tap opens the same offer a failed
     * broadcast raises ([EditorModel.showRcOffer], [RcOfferModel]).
     */
    val rcWarning: Boolean = false,
    /** RN's quest chip above the toolbar of a new post ("Daily post 0/1 ..."); null when there is none to show. */
    val questChip: String? = null,
    /**
     * RN's "Enable Faster Posting" prompt before a wallet or token account publishes without ecency.app in its
     * posting authority; answered with [EditorModel.answerAuthorityPrompt].
     */
    val authorityPrompt: Boolean = false,
    /** An edit has an unsaved copy made from an older version of the post: the UI asks "Restore unsaved changes?". */
    val recoveryAvailable: Boolean = false,
    /** The device refused to store the local draft: the text only lives in memory right now. */
    val localSaveFailed: Boolean = false,
    val done: EditorDone? = null,
) {
    val isReply: Boolean get() = kind == EditorKind.REPLY || kind == EditorKind.EDIT_REPLY
    val isEdit: Boolean get() = kind == EditorKind.EDIT_POST || kind == EditorKind.EDIT_REPLY

    val canPostToCommunity: Boolean get() = community?.canPost(isReply) != false

    /** RN _handleIsFormValid. */
    val isFormValid: Boolean
        get() = if (isReply) {
            canPostToCommunity && body.isNotEmpty()
        } else {
            canPostToCommunity && title.isNotEmpty() && title.length < 255 && body.isNotEmpty() && tags.size <= EditorTags.MAX_TAGS
        }

    /**
     * Not while a draft save is in flight (RN #3527): a server draft created after the publish would outlive it,
     * so publishing waits for the save to land (the save itself is refused while sending).
     */
    val canSubmit: Boolean get() = isFormValid && !isSending && !isDraftSaving && uploading == 0 && !loading && owner != null

    /** RN basic_header.update / reply / schedule / publish. */
    val submitLabel: String
        get() = when {
            // the button waits for a draft save in flight (canSubmit): it says why (review L9)
            isDraftSaving && !isSending -> SAVING_DRAFT
            isEdit -> L.newsletter.update
            isReply -> L.chats.reply
            scheduledFor != null -> L.transfer.schedule
            else -> L.editor.publish
        }

    /** 10000 minus the others (RN's "weight correction"). */
    val authorWeight: Int get() = EditorMeta.MAX_TOTAL_WEIGHT - beneficiaries.sumOf { it.weight }

    /** Image urls of the body, for the thumbnail row. */
    val images: List<String> get() = (EditorMeta.extractImageUrls(body) + videoThumbUrls).distinct()

    /** RN collectVideoThumbUrls: thumbnails of the videos that are still in the body. */
    val videoThumbUrls: List<String> get() = EditorMeta.collectVideoThumbUrls(videoThumbs, body)

    val supportsEcency: Boolean get() = beneficiaries.any { EditorMeta.isEcencySupport(it.account) }

    /** RN: a reply (not an edit) too short to earn points shows the hint under the field. */
    val showsShortReplyHint: Boolean get() = kind == EditorKind.REPLY && Quests.showsShortHint(owner, false, body)
}

object EditorLoadCopy {
    /** The button under a post or reply the editor could not load (RN alert.retry). */
    val RETRY: String get() = L.dappBrowser.retry
}

/** What opens an editor. Only plain ids: the saved navigation never carries a title or a body. */
data class EditorArgs(
    val kind: EditorKind,
    /** The post to edit, or the parent of a reply. */
    val author: String = "",
    val permlink: String = "",
    val draftId: String = "",
    /** A template draft to start a new post from. */
    val templateId: String = "",
    val community: String = "",
    val tags: List<String> = emptyList(),
    /** The discussion a reply shows in (the parent itself when empty). */
    val rootAuthor: String = "",
    val rootPermlink: String = "",
)

/**
 * The RN editor (screens/editor) as one state holder: new post, edit, reply, edit reply.
 *
 * - It belongs to the account that opened it ([EditorState.owner]). Every save and broadcast names that
 *   account, and [submit] refuses when another account is signed in by then.
 * - Text is never lost: every change lands in [LocalDrafts] 300 ms later and at once in [flush] (background,
 *   leaving); a failed publish keeps everything.
 * - A publish is sent once: the permlink of the first attempt is kept (also on disk) with a hash of what was
 *   sent ([PendingPublish]), and a retry first asks the chain for that post ([PendingCheck]). On chain and the
 *   same as the editor: published, nothing is sent. On chain and the editor holds something else: the user is
 *   asked whether to update that post or publish a new one; it is never edited silently. A post under that
 *   permlink whose content was not sent from here is left alone. Clearing the editor, opening another draft or
 *   a day passing forgets the pending publish.
 */
class EditorModel internal constructor(private val env: AppEnvironment, val args: EditorArgs) :
    StateModel<EditorState>(EditorState(args.kind), env.dispatchers), AiTextTarget {

    override val aiOwner: String? get() = current.owner

    override val aiText: String get() = current.body

    override val aiActions: List<String> get() = AiAssistModel.ACTIONS


    /** The post being edited, or a reply's parent. */
    private var post: Post? = null
    private var recentDraft: RemoteDraft? = null
    private var saveJob: Job? = null
    private var previewJob: Job? = null
    private var dirty = false
    private var published = false
    private var pending: PendingPublish? = null

    /** The answer to [EditorState.publishConflict] for the submit that follows: true update, false publish as new. */
    private var conflictChoice: Boolean? = null
    private var recoveryCopy: LocalDraft? = null
    private var draftMeta: JsonObject? = null

    /** RN fields.aiTools: disclosures earned in this editor; only ever added to. */
    private var aiTools = setOf<String>()

    /** RN cleanAiTools({...base, ...fields.aiTools}): what [base] (a draft's or the post's own) discloses plus this session's. */
    private fun aiToolsValue(base: JsValue?): JsValue.Obj? {
        val merged = (EditorMeta.cleanAiTools(base) ?: JsValue.Obj())
        aiTools.forEach { merged[it] = JsValue.Bool(true) }
        return EditorMeta.cleanAiTools(merged)
    }

    /**
     * Which editor a paid request belongs to: the draft, reply or edit this editor was opened for. It does not
     * change while the editor lives, and the same draft opened again finds its own unanswered request.
     */
    override val paidScope: String
        get() = when (args.kind) {
            EditorKind.NEW_POST -> if (args.draftId.isNotEmpty()) LocalDrafts.draft(args.draftId) else LocalDrafts.NEW_POST
            EditorKind.REPLY -> LocalDrafts.reply(args.author, args.permlink)
            EditorKind.EDIT_POST, EditorKind.EDIT_REPLY -> LocalDrafts.edit(args.author, args.permlink)
        }

    /** RN _handleAiToolUsed. */
    private fun aiToolUsed(key: String) {
        if (key in LocalDraft.AI_TOOLS) aiTools = aiTools + key
    }

    /** The disclosures that would be published right now (tests, the UI's note). */
    val disclosedAiTools: Set<String> get() = aiTools
    private var started = false
    private var originalTitle = ""
    private var originalBody = ""

    private val slot: String
        get() = when (args.kind) {
            EditorKind.NEW_POST -> current.draftId?.let(LocalDrafts::draft) ?: LocalDrafts.NEW_POST
            EditorKind.REPLY -> LocalDrafts.reply(args.author, args.permlink)
            EditorKind.EDIT_POST, EditorKind.EDIT_REPLY -> LocalDrafts.edit(args.author, args.permlink)
        }

    /** The running load of [start] (a [retryLoad] never overlaps it). */
    private var loadJob: Job? = null

    fun start() {
        if (started) return
        started = true
        loadJob = scope.launch {
            env.session.awaitLoaded()
            val owner = env.session.current.username
            if (owner == null) {
                setState { it.copy(loading = false, error = AppError(ErrorKind.AUTH, L.native.editor.pleaseSignInFirst, false)) }
                return@launch
            }
            setState { it.copy(owner = owner, reward = defaultReward() ?: RewardType.DEFAULT, isDefaultReward = defaultReward() != null) }
            try {
                when (args.kind) {
                    EditorKind.NEW_POST -> loadNewPost(owner)
                    EditorKind.EDIT_POST, EditorKind.EDIT_REPLY -> loadEdit(owner)
                    EditorKind.REPLY -> loadReply(owner)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setState { it.copy(error = AppError.from(e)) }
            }
            setState { s -> s.copy(loading = false, wordsCount = EditorMeta.wordsCount(s.body), tagWarning = EditorTags.warning(s.tags)) }
            loadSupportPercent(owner)
            loadQuestChip(owner)
            loadRc(owner)
        }
    }

    /** "Retry" after the post or reply to load could not be read: the editor loads again (nothing was shown yet). */
    fun retryLoad() {
        val s = current
        if (s.loading || s.error == null || s.body.isNotEmpty() || s.kind == EditorKind.NEW_POST) return
        // the editor's account only (another one signed in meanwhile: the screen goes with the account change)
        if (s.owner == null || env.session.current.username != s.owner) return
        // what the failed load still runs (its side reads) ends first: two loads never overlap
        loadJob?.cancel()
        started = false
        setState { it.copy(error = null, loading = true) }
        start()
    }

    // ---- loading ----

    private suspend fun loadNewPost(owner: String) {
        if (args.draftId.isNotEmpty()) {
            setState { it.copy(draftId = args.draftId) }
            val local = env.localDrafts.load(owner, LocalDrafts.draft(args.draftId))
            val remote = try {
                env.editorApi.allDrafts(owner).firstOrNull { it.id == args.draftId }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (local == null) env.ui.toast(L.alert.fail)
                null
            }
            // RN _getStorageDraft: the local copy wins when it is newer than the remote draft and has a body
            val remoteMillis = remote?.let { EditorMeta.isoMillis(it.modified) ?: EditorMeta.isoMillis(it.modified + "Z") } ?: 0
            if (local != null && local.body.isNotEmpty() && remoteMillis < local.updated) applyLocal(local) else if (remote != null) applyRemote(remote, owner)
            return
        }
        if (args.templateId.isNotEmpty()) {
            val template = try {
                env.editorApi.allDrafts(owner).firstOrNull { it.id == args.templateId }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (template != null) {
                // a template starts a NEW post: no draft id, and its template marks stay behind
                applyRemote(template, owner, asTemplate = true)
                env.ui.toast(L.templates.applied)
                markChanged()
            } else {
                env.ui.toast(L.alert.fail)
            }
            return
        }
        val local = env.localDrafts.load(owner, LocalDrafts.NEW_POST)
        if (local != null && local.updated > 0 && local.hasContent) {
            applyLocal(local)
            return
        }
        val tags = (listOfNotNull(args.community.takeIf { it.isNotEmpty() }) + args.tags).distinct()
        setState { it.copy(tags = tags) }
        tags.firstOrNull()?.takeIf(EditorTags::isCommunity)?.let { loadCommunity(it, owner) }
        // RN _fetchDraftsForComparison: offer the most recent remote draft (never a template)
        scope.launch {
            val drafts = try {
                env.editorApi.allDrafts(owner).filterNot { it.isTemplate }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            recentDraft = drafts.maxByOrNull { EditorMeta.isoMillis(it.modified) ?: EditorMeta.isoMillis(it.modified + "Z") ?: 0 }
            if (recentDraft != null) setState { s -> s.copy(recentDraftAvailable = s.title.isEmpty() && s.body.isEmpty() && s.tags.isEmpty()) }
        }
    }

    /** RN's "DRAFT" button: the most recent remote draft goes into the still empty editor. */
    fun loadRecentDraft() {
        val draft = recentDraft ?: return
        val owner = current.owner ?: return
        if (!current.recentDraftAvailable) return
        pending = null
        setState { it.copy(recentDraftAvailable = false, draftId = draft.id) }
        scope.launch {
            val local = env.localDrafts.load(owner, LocalDrafts.draft(draft.id))
            val remoteMillis = EditorMeta.isoMillis(draft.modified) ?: EditorMeta.isoMillis(draft.modified + "Z") ?: 0
            if (local != null && local.body.isNotEmpty() && remoteMillis < local.updated) applyLocal(local) else applyRemote(draft, owner)
        }
    }

    private suspend fun applyLocal(local: LocalDraft) {
        val (body, caret) = EditorFormats.sweepPlaceholders(local.body, local.caret)
        pending = local.pending
        aiTools = local.aiTools.toSet()
        setState { s ->
            s.copy(
                title = local.title, body = body, tags = local.tags, selection = TextSelection((caret ?: body.length).coerceIn(0, body.length)),
                bodyRevision = s.bodyRevision + 1, reward = local.reward ?: s.reward, description = local.description, thumbUrl = local.thumbUrl,
                beneficiaries = local.beneficiaries.orEmpty(), beneficiariesExplicit = local.beneficiaries != null, poll = local.poll,
                scheduledFor = local.scheduledFor, reblog = local.reblog, draftId = local.draftId ?: s.draftId, videoThumbs = local.videoThumbs,
            )
        }
        local.tags.firstOrNull()?.takeIf(EditorTags::isCommunity)?.let { loadCommunity(it, current.owner ?: return) }
    }

    /** RN _getStorageDraft + _loadMeta (and _applyTemplateDraft): a server draft into the editor. */
    private suspend fun applyRemote(draft: RemoteDraft, owner: String, asTemplate: Boolean = false) {
        val meta = runCatching { Json.parseToJsonElement(draft.metaJson) }.getOrNull().obj()
        draftMeta = meta
        // a server draft is other content than whatever was sent from this editor before
        pending = null
        val (body, _) = EditorFormats.sweepPlaceholders(draft.body, null)
        // RN drops the author from the stored list; an empty list is the saved default, not a choice
        val routes = meta?.get("beneficiaries").arr()?.let(LocalDraft::routes).orEmpty().filter { it.account != owner && it.weight in 1..EditorMeta.MAX_TOTAL_WEIGHT }.distinctBy { it.account }
        setState { s ->
            s.copy(
                title = draft.title, body = body, tags = draft.tags, selection = TextSelection(body.length), bodyRevision = s.bodyRevision + 1,
                draftId = if (asTemplate) null else draft.id,
                thumbUrl = meta?.get("image").stringList().firstOrNull().orEmpty(),
                reward = meta?.str("rewardType")?.let(RewardType::ofKey) ?: s.reward,
                description = meta?.str("description").orEmpty(),
                beneficiaries = routes, beneficiariesExplicit = routes.isNotEmpty(),
                poll = meta?.get("poll").obj()?.let(LocalDraft::pollOf),
                isDraftSaved = !asTemplate,
                videoThumbs = EditorMeta.restoreVideoThumbs(body, meta?.get("image").stringList()),
            )
        }
        draft.tags.firstOrNull()?.takeIf(EditorTags::isCommunity)?.let { loadCommunity(it, owner) }
    }

    private suspend fun loadEdit(owner: String) {
        val p = env.api.posts.getPost(args.author, args.permlink, owner, refresh = true)
            ?: throw AppException(AppError(ErrorKind.NOT_FOUND, L.native.editor.fetchFailed, true))
        if (p.author != owner) throw AppException(AppError(ErrorKind.AUTH, L.native.editor.youCanOnlyEdit, false))
        post = p
        if (p.depth > 0) setState { it.copy(kind = EditorKind.EDIT_REPLY) }
        originalTitle = p.title
        originalBody = p.body
        setState { s ->
            s.copy(
                title = p.title, body = p.body, tags = p.metadata.tags, selection = TextSelection(p.body.length), bodyRevision = s.bodyRevision + 1,
                thumbUrl = p.metadata.images.firstOrNull().orEmpty(),
            )
        }
        // a recovery copy (the app died mid-edit) that actually differs from the post
        val recovery = env.localDrafts.load(owner, slot)?.takeIf { it.body.isNotEmpty() && (it.body != p.body || (p.depth == 0 && it.title != p.title)) } ?: return
        recoveryCopy = recovery
        // made from this very version of the post: it is the user's own unfinished edit. Made from an older
        // version (the post was edited elsewhere since): never applied silently, it would revert those edits.
        if (recovery.baseUpdated == p.updated) restoreRecovery() else setState { it.copy(recoveryAvailable = true) }
    }

    /** "Restore unsaved changes?" answered yes (or the copy was made from this version of the post). */
    fun restoreRecovery() {
        val copy = recoveryCopy ?: return
        recoveryCopy = null
        change { s ->
            s.copy(
                title = if (s.kind == EditorKind.EDIT_POST) copy.title else s.title, body = copy.body, tags = copy.tags.ifEmpty { s.tags },
                selection = TextSelection(copy.body.length), bodyRevision = s.bodyRevision + 1, wordsCount = EditorMeta.wordsCount(copy.body), recoveryAvailable = false,
            )
        }
    }

    /** "Restore unsaved changes?" answered no: the copy is dropped. */
    fun discardRecovery() {
        recoveryCopy = null
        setState { it.copy(recoveryAvailable = false) }
        val owner = current.owner ?: return
        val key = slot
        if (!dirty) env.background.launch { env.localDrafts.delete(owner, key) }
    }

    /**
     * The text the reply editor's account tile carried here ([ReplyAccountHandoff]): taken once, before anything
     * can fail, and kept by this editor only, so a load that failed and is retried ([retryLoad]) still has it while
     * no other editor can ever take it.
     */
    private var carried: String? = null
    private var carriedTaken = false

    private suspend fun loadReply(owner: String) {
        if (!carriedTaken) {
            carriedTaken = true
            carried = env.replyHandoff.takeBody(args)
        }
        val p = env.api.posts.getPost(args.author, args.permlink, owner)
            ?: throw AppException(AppError(ErrorKind.NOT_FOUND, L.native.editor.fetchFailed, true))
        post = p
        val summary = withContext(dispatchers.render) { RenderHelper(env.proxyBase).postBodySummary(p.body, 150) }
        val local = env.localDrafts.load(owner, slot)
        pending = local?.pending
        // the reply editor's account tile switched accounts: the text carries over, unless this account has its own draft
        val handed = carried?.takeIf { local?.body.isNullOrEmpty() }
        carried = null
        val body = handed ?: local?.body.orEmpty()
        setState { s -> s.copy(parentSummary = summary, body = body, selection = TextSelection(body.length), bodyRevision = s.bodyRevision + 1) }
        if (handed != null) markChanged()
    }

    private suspend fun loadCommunity(name: String, owner: String) {
        val c = try {
            env.communityApi.community(name, owner)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return
        setState { s -> if (s.tags.firstOrNull() == name) s.copy(community = EditorCommunity(c.name, c.title, c.typeId, c.observerRole ?: "guest")) else s }
    }

    private suspend fun loadSupportPercent(owner: String) {
        if (current.isReply || current.isEdit || owner == EditorMeta.ECENCY_SUPPORT_ACCOUNT) return
        val percent = try {
            env.editorApi.supportSettings(owner)?.beneficiaryPercent
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return
        setState { it.copy(supportPercent = percent) }
    }

    /** RN: the quests query runs for new posts only; the chip stays away once closed in this run of the app. */
    private suspend fun loadQuestChip(owner: String) {
        if (args.kind != EditorKind.NEW_POST || owner in env.dismissedQuestChips) return
        val chip = QuestChip.load(env, owner)?.takeIf { it.visible } ?: return
        setState { if (owner in env.dismissedQuestChips) it else it.copy(questChip = chip.text) }
    }

    fun dismissQuestChip() {
        current.owner?.let { env.dismissedQuestChips += it }
        setState { it.copy(questChip = null) }
    }

    private var authorityAnswered = false
    private var authorityGrant = false

    /** "Grant Permission" ([grant]) or "Skip for Now": either way the submit runs again; a skip remembers nothing (RN). */
    fun answerAuthorityPrompt(grant: Boolean) {
        if (!current.authorityPrompt) return
        authorityAnswered = true
        authorityGrant = grant
        setState { it.copy(authorityPrompt = false) }
        submit()
    }

    /** The prompt was closed without an answer: RN continues the submit as with a skip. */
    fun dismissAuthorityPrompt() = answerAuthorityPrompt(false)

    private class AuthorityPromptNeeded : Exception()

    /** RN shouldPromptPostingAuthority, asked once per submit; a failed lookup never blocks publishing. */
    private suspend fun postingAuthorityStep(owner: String) {
        val answered = authorityAnswered
        val grant = authorityGrant
        authorityAnswered = false
        authorityGrant = false
        if (answered && !grant) return
        val account = env.accounts.vaultAccount(owner) ?: return
        if (!PostingAuthorityPrompt.usesWalletOrToken(account)) return
        val authorities = try {
            env.chain.authorities(owner)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return
        if (authorities.grantsPostingTo()) return
        if (!answered) throw AuthorityPromptNeeded()
        // RN onError: a failed grant aborts this submit
        grantPostingAuthority(owner, authorities)
        env.ui.toast(PostingAuthorityPrompt.GRANTED)
    }

    private fun defaultReward(): RewardType? = env.prefs.getString(PREF_DEFAULT_REWARD)?.let(RewardType::ofKey)

    // ---- fields ----

    private fun change(transform: (EditorState) -> EditorState) {
        if (current.isSending) return
        setState { transform(it).copy(isDraftSaved = false, recentDraftAvailable = false) }
        markChanged()
    }

    // ---- resource credit precheck (RN RcPrecheckBanner, SDK estimateRcPrecheck) ----

    private var rcAccount: JsonObject? = null
    private var rcStats: JsonObject? = null
    private var rcJob: Job? = null
    private var rcReplyPermlink: String? = null

    /** `rc_api.find_rc_accounts` and `get_rc_stats` once per editor, the curve parameters once a day per app. */
    private suspend fun loadRc(owner: String) {
        suspend fun read(method: String, params: JsonObject): JsonObject? = try {
            env.api.client.call(method, params) as? JsonObject
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        rcAccount = (read("rc_api.find_rc_accounts", kotlinx.serialization.json.buildJsonObject { put("accounts", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(owner)) }) })
            ?.get("rc_accounts") as? JsonArray)?.firstOrNull() as? JsonObject
        rcStats = read("rc_api.get_rc_stats", JsonObject(emptyMap()))?.get("rc_stats") as? JsonObject
        val cached = env.rcParams?.takeIf { env.nowMillis() - env.rcParamsAtMillis in 0..RC_PARAMS_MAX_AGE_MILLIS }
        if (cached == null) {
            read("rc_api.get_resource_params", JsonObject(emptyMap()))?.let {
                env.rcParams = it
                env.rcParamsAtMillis = env.nowMillis()
            }
        }
        scheduleRcCheck()
    }

    /** RN BUILD_DEBOUNCE_MS: the draft is costed on a pause in typing, not on every keystroke. */
    private fun scheduleRcCheck() {
        rcJob?.cancel()
        rcJob = scope.launch {
            delay(RC_DEBOUNCE_MILLIS)
            val s = current
            val warn = try {
                rcPayload(s)?.let { (op, options) -> com.ecency.core.hive.RcCost.precheck(rcAccount, rcStats, env.rcParams, op, options, env.nowMillis()) }?.let { it.ready && it.willLikelyFail } == true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // an estimate is a courtesy: a draft that cannot be costed says nothing
                false
            }
            if (current.rcWarning != warn) setState { it.copy(rcWarning = warn) }
        }
    }

    /**
     * RN buildEditorRcPayload: the operation the submit would broadcast right now, in its three shapes (a new post
     * with its comment_options, a reply, an edit with its patch). Image ratios are left out, as in RN: they
     * would mean requests on every pause and can only make the estimate lower.
     */
    private suspend fun rcPayload(s: EditorState): Pair<com.ecency.core.hive.RcComment, com.ecency.core.hive.RcCommentOptions?>? {
        val owner = s.owner ?: return null
        if (s.body.isEmpty() || s.loading) return null
        val tags = EditorMeta.publishTags(s.tags)
        if (s.isEdit) {
            val p = post ?: return null
            val old = JsValue.parse(p.metadata.rawJson) as? JsValue.Obj
            val meta = EditorMeta.extractMetadata(
                s.body, s.thumbUrl, videoThumbUrls = s.videoThumbUrls, fetchRatios = false,
                postType = (old?.get("type") as? JsValue.Str)?.value, contentType = (old?.get("content_type") as? JsValue.Str)?.value, linkedPost = ::linkedPost,
            )
            aiToolsValue(old?.get("ai_tools"))?.let { meta["ai_tools"] = it }
            val json = EditorMeta.makeJsonMetadataForUpdate(old, meta, tags) ?: EditorMeta.makeJsonMetadata(meta, tags)
            val isReply = !p.parentAuthor.isNullOrEmpty()
            return com.ecency.core.hive.RcComment(owner, p.permlink, p.parentAuthor.orEmpty(), p.parentPermlink.orEmpty(), if (isReply) "" else s.title, EditorMeta.editBody(p.body, s.body), EditorMeta.stringify(json)) to null
        }
        if (s.kind == EditorKind.REPLY) {
            val parent = post ?: return null
            val meta = EditorMeta.extractMetadata(s.body, s.thumbUrl, videoThumbUrls = s.videoThumbUrls, fetchRatios = false, postType = "comment", linkedPost = ::linkedPost)
            aiToolsValue(null)?.let { meta["ai_tools"] = it }
            // time-derived: only its length reaches the estimate, so one is made per editor
            val permlink = rcReplyPermlink ?: Comments.replyPermlink(parent.author, env.nowMillis()).also { rcReplyPermlink = it }
            val json = EditorMeta.makeJsonMetadata(meta, parent.metadata.tags.ifEmpty { listOf("ecency") })
            return com.ecency.core.hive.RcComment(owner, permlink, parent.author, parent.permlink, "", s.body, EditorMeta.stringify(json)) to null
        }
        val meta = EditorMeta.extractMetadata(s.body, s.thumbUrl, videoThumbUrls = s.videoThumbUrls, fetchRatios = false, poll = s.poll, linkedPost = ::linkedPost)
        aiToolsValue(draftMeta?.get("ai_tools")?.let(JsValue::from))?.let { meta["ai_tools"] = it }
        val permlink = Slug.generatePermlink(s.title, false, env.randomString)
        val json = EditorMeta.makeJsonMetadata(meta, tags)
        // RN resolveSubmitBeneficiaries: the rows the submit adds (3Speak, the saved Ecency support) are priced too
        var routes = EditorMeta.enforceThreeSpeakBeneficiary(s.beneficiaries, s.body)
        if (!s.beneficiariesExplicit && owner != EditorMeta.ECENCY_SUPPORT_ACCOUNT && s.supportPercent > 0) routes = EditorMeta.injectEcencySupport(routes, s.supportPercent)
        // a post always sends comment_options beside the comment; RN's makeOptions gives none without a permlink
        val options = if (permlink.isEmpty()) null else com.ecency.core.hive.RcCommentOptions(routes.takeIf { it.isNotEmpty() }?.map { it.account to it.weight })
        return com.ecency.core.hive.RcComment(owner, permlink, "", tags.firstOrNull() ?: EditorMeta.DEFAULT_PARENT_PERMLINK, s.title, s.body, EditorMeta.stringify(json)) to options
    }

    /** A tap on the banner: RN opens the offer a failed broadcast raises (top up with Points, or boost). */
    fun showRcOffer() = env.rcOffer.raise()

    private fun markChanged() {
        scheduleRcCheck()
        dirty = true
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(LOCAL_SAVE_DELAY_MILLIS)
            saveLocal()
        }
    }

    /** RN _handleChangeTitle: the first newline becomes a space; 250 characters at most. */
    fun setTitle(text: String) = change { it.copy(title = text.replaceFirst("\n", " ").take(MAX_TITLE_LENGTH)) }

    /** The text field changed (typing, cut, paste). */
    fun setBody(text: String, selection: TextSelection) {
        if (current.isSending) return
        val s = current
        if (s.body == text) {
            if (s.selection != selection) setState { it.copy(selection = selection.clamped(text.length)) }
            return
        }
        change { it.copy(body = text, selection = selection.clamped(text.length), wordsCount = EditorMeta.wordsCount(text)) }
    }

    fun setSelection(selection: TextSelection) = setState { it.copy(selection = selection.clamped(it.body.length)) }

    private fun applyEdit(edit: TextEdit?) {
        val e = edit?.clamped ?: return
        change { it.copy(body = e.text, selection = e.selection, bodyRevision = it.bodyRevision + 1, wordsCount = EditorMeta.wordsCount(e.text)) }
    }

    fun bold() = applyEdit(EditorFormats.wrap(current.body, current.selection, EditorFormats.BOLD))

    fun italic() = applyEdit(EditorFormats.wrap(current.body, current.selection, EditorFormats.ITALIC))

    fun table() = applyEdit(EditorFormats.list(current.body, current.selection, EditorFormats.TABLE))

    /** The format row's link button: placeholders around the selection. */
    fun wrapLink() = applyEdit(EditorFormats.webLink(current.body, current.selection))

    /** RN's insert link sheet. False when [url] is not a web link ("Please insert valid url"). */
    fun insertLink(label: String, url: String, type: EditorFormats.LinkType): Boolean {
        val u = url.trim()
        if (!EditorFormats.isWebLink(u)) return false
        applyEdit(EditorFormats.snippet(current.body, current.selection, EditorFormats.linkSnippet(label, u, type)))
        return true
    }

    /**
     * RN _showTranslateModal's onApply: [appendix] goes to the end of the body with the caret after it, and
     * [titleMarker] to the end of the title. False when the editor cannot change right now.
     */
    fun applyTranslation(appendix: String, titleMarker: String?): Boolean {
        if (current.isSending || current.owner == null) return false
        change { s ->
            val body = s.body + appendix
            s.copy(
                body = body, selection = TextSelection(body.length), bodyRevision = s.bodyRevision + 1, wordsCount = EditorMeta.wordsCount(body),
                title = if (titleMarker != null) (s.title + titleMarker).take(MAX_TITLE_LENGTH) else s.title,
            )
        }
        return true
    }

    /**
     * RN _handleAiAssistResult: a body action replaces the whole body and sets the `writing_edit` disclosure,
     * a title is set, tags replace the tags (through the tag field's own rules, the answer is not trusted).
     * A Recap is never applied (RN replaced the body with it, #3599; it is copy only here).
     * False when the editor cannot change right now or belongs to another account than the one signed in.
     */
    override fun applyAiResult(action: String, text: String, tags: List<String>): Boolean {
        if (!acceptsAiWrite()) return false
        when (action) {
            "improve", "check_grammar" -> {
                aiToolUsed("writing_edit")
                change { it.copy(body = text, selection = TextSelection(text.length), bodyRevision = it.bodyRevision + 1, wordsCount = EditorMeta.wordsCount(text)) }
            }
            "generate_title" -> setTitle(text)
            "suggest_tags" -> if (tags.isNotEmpty()) {
                // one tag at a time through the field's rules (lowercase, no separators inside, no duplicates), ten at most
                var result = com.ecency.core.editor.TagInputResult(emptyList(), current.tagInput)
                for (tag in tags.take(50)) {
                    if (result.tags.size >= EditorTags.MAX_TAGS) break
                    val r = EditorTags.input(result.tags, tag.take(EditorTags.MAX_INPUT_LENGTH - 1) + " ")
                    result = com.ecency.core.editor.TagInputResult(r.tags.take(EditorTags.MAX_TAGS), result.rest, communitySelected = r.communitySelected ?: result.communitySelected)
                }
                applyTags(result)
            }
            else -> return false
        }
        return true
    }

    /** RN's generator onInsert: the image at the caret and the `media_generation` disclosure. */
    fun insertAiImage(url: String): Boolean {
        if (!acceptsAiWrite() || !UploadNames.isSafeUrl(url, env.imageHost)) return false
        aiToolUsed("media_generation")
        insertMedia(listOf(MediaInsert(url = url, status = com.ecency.core.editor.MediaInsertStatus.READY)))
        return true
    }

    /**
     * RN _handleDictationResult: the transcript replaces the selection, after one space unless the text before
     * it is empty or already ends in whitespace; the caret ends up behind it.
     */
    override fun insertDictation(text: String): Boolean {
        if (!acceptsAiWrite() || text.isEmpty()) return false
        change { s ->
            val sel = s.selection.clamped(s.body.length)
            val before = s.body.substring(0, sel.start)
            val insertion = (if (before.isEmpty() || com.ecency.core.render.js.Js.isSpace(before.last())) "" else " ") + text
            val body = before + insertion + s.body.substring(sel.end)
            val caret = sel.start + insertion.length
            s.copy(body = body, selection = TextSelection(caret), bodyRevision = s.bodyRevision + 1, wordsCount = EditorMeta.wordsCount(body))
        }
        return true
    }

    /**
     * RN onVideoUploaded: the embed address on a line of its own at the caret (the renderer finds the 3Speak
     * embed by its bare url), and its thumbnail remembered for `meta.image` while the address stays in the body.
     * Only 3Speak's own embed addresses are taken, one video a post, and only for the editor's owner.
     */
    fun insertVideo(embedUrl: String, thumbUrl: String?): Boolean {
        if (!acceptsAiWrite() || !com.ecency.core.api.speak.SpeakApi.isEmbedUrl(embedUrl) || EditorMeta.hasThreeSpeakEmbed(current.body)) return false
        insertMedia(listOf(MediaInsert(url = embedUrl, status = com.ecency.core.editor.MediaInsertStatus.READY, isVideo = true)))
        val thumb = thumbUrl?.takeIf { UploadNames.isSafeUrl(it, env.imageHost) }
        if (thumb != null) change { s -> if (s.videoThumbs.any { it.embedUrl == embedUrl }) s else s.copy(videoThumbs = s.videoThumbs + com.ecency.core.editor.VideoThumb(embedUrl, thumb)) }
        return true
    }

    /** A paid result only goes into the editor of the account that paid, while that account is signed in. */
    private fun acceptsAiWrite(): Boolean {
        val owner = current.owner ?: return false
        return !current.isSending && env.session.current.username == owner
    }

    /** A snippet (or shared text) over the selection, on lines of its own. */
    fun insertSnippet(text: String) = applyEdit(EditorFormats.snippet(current.body, current.selection, "\n$text\n"))

    fun insertUsername(username: String) = applyEdit(EditorFormats.username(current.body, current.selection, username))

    /** The word at the caret when it is a mention being typed ("@al"), for the username suggestions. */
    val mentionQuery: String?
        get() = EditorFormats.wordAt(current.body, current.selection.start).takeIf { it.startsWith("@") && it.length >= 3 }?.drop(1)

    /** Uploads report here: placeholders in, urls in, failed ones out (RN applyMediaLink). */
    fun insertMedia(items: List<MediaInsert>, otherPending: List<String> = emptyList()) =
        applyEdit(EditorFormats.media(current.body, current.selection, items, otherPending))

    internal fun uploadsChanged(delta: Int) = setState { it.copy(uploading = (it.uploading + delta).coerceAtLeast(0)) }

    /** RN's trash button, after its confirm: an empty editor and no local draft. */
    fun clear() {
        if (current.isSending) return
        val owner = current.owner ?: return
        consumedTagText = ""
        setState { it.copy(title = "", body = "", tags = emptyList(), tagInput = "", selection = TextSelection(0), bodyRevision = it.bodyRevision + 1, wordsCount = 0, tagWarning = null, community = null) }
        dirty = false
        saveJob?.cancel()
        // what is written next is a new post, whatever was sent from here before
        pending = null
        conflictChoice = null
        aiTools = emptySet()
        scope.launch { env.localDrafts.delete(owner, slot) }
    }

    // ---- tags and community ----

    private fun applyTags(result: com.ecency.core.editor.TagInputResult) {
        result.toasts.forEach { env.ui.toast(it) }
        change { s ->
            // RN _handleOnTagAdded: without a community first, the post goes to the author's blog
            val community = s.community?.takeIf { result.tags.firstOrNull() == it.name }
            s.copy(tags = result.tags, tagInput = result.rest, tagWarning = EditorTags.warning(result.tags), community = community)
        }
        result.communitySelected?.let { name -> current.owner?.let { owner -> scope.launch { loadCommunity(name, owner) } } }
    }

    /** The text that last became chips, which the field may still show for a frame (EditorTags.input). */
    private var consumedTagText = ""

    fun setTagInput(text: String) {
        val result = EditorTags.input(current.tags, text, consumedTagText)
        consumedTagText = result.consumed
        if (result.tags == current.tags && result.toasts.isEmpty()) setState { it.copy(tagInput = result.rest) } else applyTags(result)
    }

    /**
     * The tag field now shows [text]. Once it shows the model's text, nothing it sends can be the stale text any
     * more: the consumed text is forgotten, so typing or pasting a tag that is already a chip is a real duplicate
     * (RN's toast) and never dropped quietly. Android calls it after each composition, iOS after the field is set.
     */
    fun tagFieldShown(text: String) {
        if (text == current.tagInput) consumedTagText = ""
    }

    fun endTagEditing() {
        consumedTagText = ""
        val result = EditorTags.endEditing(current.tags, current.tagInput)
        if (result.tags != current.tags || result.toasts.isNotEmpty()) applyTags(result)
    }

    fun removeTag(tag: String) {
        consumedTagText = "" // a removed tag typed (or pasted) again is a new tag
        applyTags(com.ecency.core.editor.TagInputResult(current.tags - tag, current.tagInput))
    }

    /** RN _handlePressCommunity: the community is the first tag; null is "My Blog". */
    fun selectCommunity(community: EditorCommunity?) = change { s ->
        val rest = if (s.community != null) s.tags.drop(1) else s.tags
        val tags = (listOfNotNull(community?.name) + rest.filter { it != community?.name })
        s.copy(tags = tags, community = community, tagWarning = EditorTags.warning(tags), reblog = s.reblog && community != null)
    }

    // ---- options ----

    fun setReward(reward: RewardType) = change { it.copy(reward = reward, isDefaultReward = defaultReward() == reward) }

    /** RN "Set Default": the current reward is the default for new posts, or none. */
    fun setDefaultReward(on: Boolean) {
        env.prefs.putString(PREF_DEFAULT_REWARD, if (on) current.reward.key else null)
        setState { it.copy(isDefaultReward = on) }
    }

    fun setSchedule(dateTime: String?) = change { it.copy(scheduledFor = dateTime?.takeIf { d -> d.isNotEmpty() }) }

    fun setReblog(on: Boolean) = change { it.copy(reblog = on && it.community != null) }

    fun setThumb(url: String) = change { it.copy(thumbUrl = url) }

    fun setDescription(text: String) = change { it.copy(description = text.take(MAX_DESCRIPTION_LENGTH)) }

    fun setPoll(poll: PollDraft?) = change { it.copy(poll = poll) }

    /**
     * RN beneficiarySelectionContent: [percent] of the reward to [account]. Null when added, else why not. The
     * account must exist; whole percents from 1 (RN also takes 0, which the chain refuses).
     */
    suspend fun addBeneficiary(account: String, percent: Int): String? {
        val name = account.trim().removePrefix("@").lowercase()
        val s = current
        val weight = percent * 100
        return when {
            name.isEmpty() -> L.auth.invalidUsername
            percent < 1 || weight > s.authorWeight -> L.native.editor.invalidPercent
            name == s.owner || s.beneficiaries.any { it.account == name } -> L.native.editor.beneficiaryExistsAlready
            !accountExists(name) -> L.auth.invalidUsername
            else -> {
                change { it.copy(beneficiaries = it.beneficiaries + BeneficiaryRoute(name, weight), beneficiariesExplicit = true) }
                null
            }
        }
    }

    private suspend fun accountExists(name: String): Boolean = try {
        name in env.communityApi.lookupAccounts(name, 50)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    /** Removing a row makes the list the author's own, even when it ends up empty (RN: `[]` is an explicit "none"). */
    fun removeBeneficiary(account: String) {
        if (account == EditorMeta.THREESPEAK_ACCOUNT && EditorMeta.hasThreeSpeakEmbed(current.body)) return
        change { it.copy(beneficiaries = it.beneficiaries.filter { b -> b.account != account }, beneficiariesExplicit = true) }
    }

    /** RN's "Support Ecency {percent}%" chip. */
    val supportChipPercent: Int
        get() = current.beneficiaries.firstOrNull { EditorMeta.isEcencySupport(it.account) }?.let { it.weight / 100 }
            ?: current.supportPercent.toInt().takeIf { it > 0 } ?: EditorMeta.DEFAULT_SUPPORT_PERCENT

    fun toggleSupport() {
        val s = current
        val owner = s.owner ?: return
        val on = !s.supportsEcency
        val percent = supportChipPercent
        if (on) {
            val weight = percent * 100
            if (s.authorWeight < weight || s.beneficiaries.size >= EditorMeta.MAX_BENEFICIARY_ROUTES) {
                env.ui.toast(L.alert.fail)
                return
            }
            change { it.copy(beneficiaries = it.beneficiaries + BeneficiaryRoute(EditorMeta.ECENCY_SUPPORT_ACCOUNT, weight), beneficiariesExplicit = true) }
        } else {
            change { it.copy(beneficiaries = it.beneficiaries.filterNot { b -> EditorMeta.isEcencySupport(b.account) }, beneficiariesExplicit = true) }
        }
        // the preference follows the chip (RN _persistSupportPreference), best effort
        env.background.launch {
            try {
                val settings = env.editorApi.supportSettings(owner) ?: return@launch
                env.editorApi.updateSupportSettings(owner, if (on) percent.toDouble() else 0.0, settings.curationPercent)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(dispatchers.main) { env.ui.toast(L.alert.fail) }
            }
        }
        setState { it.copy(supportPercent = if (on) percent.toDouble() else 0.0) }
    }

    // ---- preview ----

    fun setPreview(on: Boolean) {
        previewJob?.cancel()
        setState { it.copy(isPreview = on, preview = if (on) it.preview else null) }
        if (!on) return
        val body = current.body.ifEmpty { "..." }
        val owner = current.owner.orEmpty()
        previewJob = scope.launch {
            val blocks = withContext(dispatchers.render) { env.renderer.blocks(Post(author = owner, permlink = "preview", body = body)) }
            setState { if (it.isPreview) it.copy(preview = blocks) else it }
        }
    }

    // ---- local draft ----

    private fun localDraft(s: EditorState) = LocalDraft(
        title = s.title, body = s.body, tags = s.tags, updated = env.nowMillis(), caret = s.selection.start, reward = s.reward,
        description = s.description, thumbUrl = s.thumbUrl, beneficiaries = s.beneficiaries.takeIf { s.beneficiariesExplicit }, poll = s.poll,
        scheduledFor = s.scheduledFor, reblog = s.reblog, draftId = s.draftId, pending = pending,
        baseUpdated = post?.updated?.takeIf { s.isEdit }, aiTools = aiTools.toList(), videoThumbs = s.videoThumbs,
    )

    private suspend fun saveLocal() {
        val s = current
        val owner = s.owner ?: return
        // RN _saveCurrentDraft: nothing after a publish, so a late save cannot bring a published post back as a draft
        if (published || s.loading) return
        dirty = false
        val ok = env.localDrafts.save(owner, slot, localDraft(s))
        // a save refused because the account left the device (logout, wipe) is the teardown at work, not a failure to report
        val failed = !ok && !env.localDrafts.hasLeft(owner)
        if (current.localSaveFailed != failed) setState { it.copy(localSaveFailed = failed) }
    }

    /**
     * Writes the local draft now (app to background, screen leaving). Runs in the environment's scope, so it
     * finishes even when the screen and this model are gone.
     */
    fun flush() {
        saveJob?.cancel()
        if (!dirty) return
        val s = current
        val owner = s.owner ?: return
        if (published || s.loading) return
        dirty = false
        val draft = localDraft(s)
        val key = slot
        env.background.launch { env.localDrafts.save(owner, key, draft) }
    }

    /** Whatever removes the screen (back, an account change, the activity finishing), the text is written first. */
    override fun close() {
        flush()
        super.close()
    }

    /** True when leaving would throw changes away: only an edit, which is never saved as a draft (RN drops it silently). */
    val leavingLosesChanges: Boolean
        get() = current.isEdit && !published && !current.loading && (current.body != originalBody || (current.kind == EditorKind.EDIT_POST && current.title != originalTitle))

    /**
     * The user left the editor (RN componentWillUnmount). A new post with a title or body is saved as a server
     * draft, as RN does; a reply stays a local draft; an edit's recovery copy is dropped.
     */
    fun leave() {
        val s = current
        val owner = s.owner ?: return
        if (published) return
        when {
            s.isEdit -> {
                saveJob?.cancel()
                dirty = false
                val key = slot
                env.background.launch { env.localDrafts.delete(owner, key) }
            }
            s.isReply -> flush()
            else -> {
                flush()
                val inFlight = remoteSave?.takeIf { it.isActive }
                if (inFlight != null) {
                    // a save is running: wait for it, then update the draft it made only when needed
                    env.background.launch {
                        inFlight.join()
                        val sent = remoteSaveSent
                        val (id, saved) = withContext(dispatchers.main) { current.draftId to current.isDraftSaved }
                        // the save failed (nothing on the server yet), or the text changed after it was sent
                        if (!published && (!saved || sent == null || draftPayload(sent) != draftPayload(s))) {
                            saveRemote(owner, s.copy(draftId = id ?: s.draftId), saveAsNew = false, fromModel = false)
                        }
                    }
                } else if ((s.body.isNotEmpty() || s.title.isNotEmpty()) && !s.isDraftSaved && !s.loading && !s.isSending) {
                    env.background.launch { saveRemote(owner, s, saveAsNew = false, fromModel = false) }
                }
            }
        }
    }

    // ---- server draft ----

    /** RN's save button: update the open draft, or (with [saveAsNew], or without one) create a draft. */
    fun saveDraft(saveAsNew: Boolean = false) {
        val s = current
        val owner = s.owner ?: return
        if (s.isReply || s.isEdit || s.isDraftSaving || s.isSending || (s.body.isEmpty() && s.title.isEmpty())) return
        setState { it.copy(isDraftSaving = true) }
        // in the environment's scope, and known to [leave]: leaving while it runs never starts a second save that
        // would create a second server draft (review M4)
        remoteSave = env.background.launch {
            val sent = withContext(dispatchers.main) {
                saveLocal()
                current.also { remoteSaveSent = it }
            }
            saveRemote(owner, sent, saveAsNew, fromModel = true)
        }
    }

    /** Everything a server draft stores (body, title, tags and the meta), for "did it change since it was sent". */
    private fun draftPayload(s: EditorState): List<Any?> = listOf(
        s.title, s.body, s.tags, s.beneficiaries, s.beneficiariesExplicit, s.community, s.poll, s.reward, s.description,
        s.thumbUrl, s.videoThumbs, s.videoThumbUrls,
    )

    /** The explicit save running now, and what it sent. */
    private var remoteSave: Job? = null
    private var remoteSaveSent: EditorState? = null

    /** RN "Save as template": a new draft marked as a template; the draft being written is left alone. */
    fun saveAsTemplate(name: String) {
        val s = current
        val owner = s.owner ?: return
        if (s.isReply || s.isEdit || (s.title.isEmpty() && s.body.isEmpty()) || name.isBlank()) return
        env.background.launch {
            val ok = try {
                val meta = draftMetaJson(s, owner, template = name.trim())
                env.editorApi.addDraft(owner, s.title, s.body, s.tags.joinToString(" "), meta)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            withContext(dispatchers.main) { env.ui.toast(if (ok) L.templates.saved else L.editor.draftSaveFail) }
        }
    }

    /** RN _saveDraftToDB's meta: the extracted metadata plus tags (as one string), beneficiaries, poll, reward and description. */
    private suspend fun draftMetaJson(s: EditorState, owner: String, template: String? = null): JsonElement {
        val tagString = s.tags.joinToString(" ")
        val summary = withContext(dispatchers.render) { RenderHelper(env.proxyBase).postBodySummary(s.body, 200) }
        val meta = EditorMeta.extractMetadata(s.body, s.thumbUrl, videoThumbUrls = s.videoThumbUrls, fetchRatios = false, linkedPost = ::linkedPost)
        meta["tags"] = JsValue.Str(tagString)
        meta["beneficiaries"] = JsValue.Arr(
            s.beneficiaries.filter { it.account != owner }.map { b ->
                JsValue.Obj().apply { set("account", JsValue.Str(b.account)); set("weight", JsValue.Num(b.weight.toDouble())) }
            },
        )
        meta["poll"] = s.poll?.let { JsValue.from(LocalDraft.pollJson(it)) } ?: JsValue.Undefined
        meta["rewardType"] = JsValue.Str(s.reward.key)
        meta["description"] = JsValue.Str(s.description.ifEmpty { summary })
        if (template != null) {
            meta["postTemplate"] = JsValue.Bool(true)
            meta["templateName"] = JsValue.Str(template)
        }
        aiToolsValue(draftMeta?.get("ai_tools")?.let(JsValue::from))?.let { meta["ai_tools"] = it }
        meta["tags"] = JsValue.Str(tagString)
        meta["app"] = JsValue.Str(EditorMeta.appString())
        meta["format"] = JsValue.Str("markdown+html")
        return Json.parseToJsonElement(EditorMeta.stringify(meta))
    }

    private suspend fun saveRemote(owner: String, s: EditorState, saveAsNew: Boolean, fromModel: Boolean) {
        suspend fun onMain(block: () -> Unit) = withContext(dispatchers.main) { block() }
        try {
            if (published) return
            val meta = draftMetaJson(s, owner)
            val tags = s.tags.joinToString(" ")
            // RN re-checks right before the request: a save that started before a publish must not recreate the draft
            if (published) return
            val id = s.draftId
            if (id != null && !saveAsNew) {
                env.editorApi.updateDraft(owner, id, s.title, s.body, tags, meta)
                env.localDrafts.delete(owner, LocalDrafts.draft(id))
                onMain { if (fromModel) setState { it.copy(isDraftSaved = true, isDraftSaving = false) } }
            } else {
                val created = env.editorApi.addDraft(owner, s.title, s.body, tags, meta) ?: throw IllegalStateException("newly saved draft not returned in response")
                // the id-less local copy became this server draft
                env.localDrafts.delete(owner, if (id != null) LocalDrafts.draft(id) else LocalDrafts.NEW_POST)
                onMain { if (fromModel) setState { it.copy(isDraftSaved = true, isDraftSaving = false, draftId = created.id) } }
            }
            onMain {
                env.ui.toast(L.editor.draftSaveSuccess)
                env.emit(AppEvent.DraftsChanged(owner))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onMain {
                if (fromModel) setState { it.copy(isDraftSaved = false, isDraftSaving = false) }
                // the account left the device (logout) while it ran: the teardown at work, not a failure to report
                if (!env.localDrafts.hasLeft(owner)) env.ui.toast(L.editor.draftSaveFail)
            }
        }
    }

    /** After a publish from a saved draft: RN's "Post published" prompt answered with Delete. */
    fun deletePublishedDraft() {
        val done = current.done as? EditorDone.Published ?: return
        val id = done.draftId ?: return
        env.background.launch {
            runCatching { env.editorApi.deleteDraft(done.author, id) }
            withContext(dispatchers.main) { env.emit(AppEvent.DraftsChanged(done.author)) }
        }
    }

    // ---- publishing ----

    private suspend fun linkedPost(author: String, permlink: String): LinkedPost? =
        env.api.posts.getPost(author, permlink, "")?.let { LinkedPost(it.title) }

    private suspend fun imageRatio(url: String): Double? = env.imageRatios(url)

    /**
     * Publish, schedule, update or reply (after the UI's "Are you sure?"). Everything stays as it is on a
     * failure; calling again retries without posting twice.
     */
    fun submit() {
        val s = current
        val owner = s.owner ?: return
        if (!s.canSubmit) return
        // the editor belongs to the account that opened it: never publish as whoever is signed in now
        if (env.session.current.username != owner) {
            env.ui.toast(L.native.editor.thisDraftBelongsTo(owner = owner))
            return
        }
        if (s.scheduledFor != null && !s.isEdit && !s.isReply && (s.title.isEmpty() || s.body.isEmpty())) {
            env.ui.toast(L.alert.canNotBeEmpty)
            return
        }
        saveJob?.cancel()
        setState { it.copy(isSending = true, publishConflict = false, conflictChangedElsewhere = false, grantPrompt = false) }
        scope.launch {
            try {
                saveLocal()
                // scheduling has its own authority step (Ecency itself posts later)
                if (s.scheduledFor == null || s.isEdit || s.isReply) postingAuthorityStep(owner)
                val done = when {
                    s.kind == EditorKind.REPLY -> submitReply(owner, s)
                    s.isEdit -> submitEdit(owner, s)
                    s.scheduledFor != null -> submitSchedule(owner, s)
                    else -> submitPost(owner, s)
                }
                published = true
                env.localDrafts.delete(owner, slot)
                if (s.kind == EditorKind.NEW_POST) env.localDrafts.delete(owner, LocalDrafts.NEW_POST)
                setState { it.copy(isSending = false, done = done) }
            } catch (e: CancellationException) {
                setState { it.copy(isSending = false) }
                throw e
            } catch (e: AuthorityPromptNeeded) {
                setState { it.copy(isSending = false, authorityPrompt = true) }
            } catch (e: GrantNeeded) {
                setState { it.copy(isSending = false, grantPrompt = true) }
            } catch (e: PublishConflict) {
                // already published, and the text changed since: nothing is sent until the user chose
                env.localDrafts.save(owner, slot, localDraft(current))
                setState { it.copy(isSending = false, publishConflict = true, conflictChangedElsewhere = e.changedElsewhere) }
            } catch (e: Exception) {
                // the permlink that may have gone out is remembered on disk too
                env.localDrafts.save(owner, slot, localDraft(current))
                val error = AppError.fromBroadcast(e)
                // RN: out of resource credits is an offer (top up or boost), not a dead-end toast
                val outOfRc = RcErrors.isInsufficientRc(e) || RcErrors.matches(error.message)
                setState { it.copy(isSending = false) }
                // the app-wide offer (RcOfferModel), as every other broadcast that ran out
                if (outOfRc) env.rcOffer.raise()
                // RN alert.something_wrong_msg; a closed signing prompt says nothing
                if (!outOfRc && error.kind != ErrorKind.CANCELLED) env.ui.toast(L.alert.somethingWrongMsg(message = error.message))
            }
        }
    }

    private class PublishConflict(val changedElsewhere: Boolean = false) : Exception()

    private class GrantNeeded : Exception()

    private var grantApproved = false

    /** "Grant posting authority to ecency.app?" answered yes: the schedule runs again and makes the grant first. */
    fun confirmPostingGrant() {
        if (!current.grantPrompt) return
        grantApproved = true
        setState { it.copy(grantPrompt = false) }
        submit()
    }

    fun dismissPostingGrant() = setState { it.copy(grantPrompt = false) }

    /** The guarded grant ([PostingAuthorityFlow.grant]), shared with the quick post surfaces. */
    private suspend fun grantPostingAuthority(owner: String, known: com.ecency.core.auth.ChainAuthorities) = PostingAuthorityFlow.grant(env, owner, known)

    /**
     * The answer to [EditorState.publishConflict]: [update] edits the post that is already on chain, otherwise this
     * text goes out as a new post (or reply) under a new permlink. Then the submit runs again.
     */
    fun resolvePublishConflict(update: Boolean) {
        if (!current.publishConflict) return
        conflictChoice = update
        setState { it.copy(publishConflict = false) }
        submit()
    }

    /** The question was closed without an answer: nothing is sent, everything stays. */
    fun dismissPublishConflict() = setState { it.copy(publishConflict = false) }

    /** [activity]: the points earning report a new post or reply makes (SDK useComment); none for an edit. */
    private suspend fun broadcast(owner: String, ops: List<Operation>, activity: Int? = null) {
        val signed = env.signing.broadcast(owner, JsonArray(ops.map(OperationJson::encode)))
        env.pointActivities.recordBroadcast(owner, activity, signed)
    }

    /** RN _submitPost's beneficiaries: the 3Speak route, then the saved Ecency support unless the author set the list. */
    private suspend fun publishBeneficiaries(owner: String, s: EditorState): List<BeneficiaryRoute> {
        var list = EditorMeta.enforceThreeSpeakBeneficiary(s.beneficiaries, s.body)
        if (!s.beneficiariesExplicit && owner != EditorMeta.ECENCY_SUPPORT_ACCOUNT) {
            // fails open: a slow or failed lookup publishes without the route
            val percent = withTimeoutOrNull(SUPPORT_SETTINGS_TIMEOUT_MILLIS) {
                try {
                    env.editorApi.supportSettings(owner)?.beneficiaryPercent
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            } ?: 0.0
            list = EditorMeta.injectEcencySupport(list, percent)
        }
        EditorMeta.beneficiaryProblem(list, owner)?.let { throw AppException(AppError(ErrorKind.INVALID_INPUT, it, false)) }
        return list
    }

    private suspend fun postMetadata(s: EditorState): JsValue.Obj {
        val meta = EditorMeta.extractMetadata(s.body, s.thumbUrl, videoThumbUrls = s.videoThumbUrls, fetchRatios = true, poll = s.poll, linkedPost = ::linkedPost, imageRatio = ::imageRatio)
        aiToolsValue(draftMeta?.get("ai_tools")?.let(JsValue::from))?.let { meta["ai_tools"] = it }
        return EditorMeta.makeJsonMetadata(meta, EditorMeta.publishTags(s.tags))
    }

    /** The pending record after sending [hash] under [permlink] now. */
    private suspend fun remember(owner: String, permlink: String, hash: String) {
        val now = env.nowMillis()
        pending = pending?.takeIf { it.permlink == permlink }?.alsoSent(hash, now) ?: PendingPublish(permlink, listOf(hash), now)
        env.localDrafts.save(owner, slot, localDraft(current))
    }

    /**
     * A permlink for a new post from [title] (RN: the slug, with a random suffix when the author already has that
     * permlink). Taken is decided by the chain itself, never by hivemind, which lags behind a post that just
     * landed; and it is never [avoid], the permlink of a pending publish the user chose not to touch.
     */
    private suspend fun newPermlink(owner: String, title: String, avoid: String?): String {
        var permlink = Slug.generatePermlink(title, false, env.randomString)
        var tries = 0
        // a permlink that is taken is never returned: publishing under it would edit the post that has it
        while (permlink == avoid || env.api.hive.content(owner, permlink) != null) {
            if (tries++ >= 5) throw AppException(AppError(ErrorKind.INVALID_INPUT, L.native.editor.couldNotMakeA, false))
            permlink = Slug.generatePermlink(title, true, env.randomString)
        }
        return permlink
    }

    /** The owner's post count before this editor's first publish attempt (RN's cached `post_count`); read once. */
    private var postCountBefore: Int? = null
    private var postCountRead = false

    private suspend fun submitPost(owner: String, s: EditorState): EditorDone {
        // RN first publish digest offer: whether this is the account's first post is asked before anything is sent
        // (a retry keeps the first answer: the post of a lost answer would already count)
        if (!postCountRead) {
            postCountRead = true
            postCountBefore = env.digest.postCountBeforePublish(owner)
        }
        val beneficiaries = publishBeneficiaries(owner, s)
        val json = EditorMeta.stringify(postMetadata(s))
        val hash = PendingPublish.hashOf("", "", s.title, s.body, EditorMeta.publishTags(s.tags))
        val chainHash = { c: com.ecency.core.api.ChainContent -> PendingPublish.hashOf("", "", c.title, c.body, c.tags) }
        val choice = conflictChoice
        conflictChoice = null
        val was = pending
        var permlink: String? = null
        val verdict = PendingCheck.verdict(env, owner, was, hash, chainHash)
        when (verdict) {
            PendingVerdict.PUBLISHED -> return finishPost(owner, was!!.permlink, s)
            PendingVerdict.CONFLICT, PendingVerdict.CONFLICT_CHANGED_ELSEWHERE -> when (choice) {
                null -> throw PublishConflict(verdict == PendingVerdict.CONFLICT_CHANGED_ELSEWHERE)
                true -> {
                    // the user chose to update the post that is already out (comment_options is create-only)
                    val parent = env.api.hive.content(owner, was!!.permlink)?.parentPermlink.orEmpty()
                        .ifEmpty { EditorMeta.publishTags(s.tags).firstOrNull() ?: EditorMeta.DEFAULT_PARENT_PERMLINK }
                    remember(owner, was.permlink, hash)
                    broadcast(owner, EditorMeta.editOperations(owner, was.permlink, "", parent, s.title, s.body, json))
                    return finishPost(owner, was.permlink, s)
                }
                false -> pending = null // a new post: a new permlink below
            }
            PendingVerdict.RESEND -> permlink = was!!.permlink
            PendingVerdict.FRESH -> pending = null
        }
        // a resend with other content than was sent before, while the first transaction can still land
        val racing = permlink != null && was != null && hash !in was.hashes
        if (permlink == null) permlink = newPermlink(owner, s.title, avoid = was?.permlink)
        remember(owner, permlink, hash)
        broadcast(owner, EditorMeta.newPostOperations(owner, permlink, s.title, s.body, s.tags, json, s.reward, beneficiaries), PointActivity.POST)
        if (racing) {
            // both transactions may have landed, in either order: what the chain holds now decides
            val now = env.api.hive.content(owner, permlink)
            if (now != null && chainHash(now) != hash) {
                env.ui.toast(L.native.editor.anEarlierAttemptWas)
                throw PublishConflict()
            }
        }
        return finishPost(owner, permlink, s)
    }

    private fun finishPost(owner: String, permlink: String, s: EditorState): EditorDone {
        env.ui.toast(L.alert.successShared)
        // RN maybeRequestReview: publishing is the moment the store rating prompt may be asked for, once
        env.maybeRequestReview()
        // RN maybeOfferFirstPublishDigest: once, after the account's first post, apart from the publish
        env.digest.maybeOfferFirstPublish(owner, postCountBefore)
        if (s.reblog && s.community != null) {
            // RN: best effort after the post is out
            env.background.launch {
                try {
                    val signed = env.signing.broadcast(owner, com.ecency.core.api.broadcast.PostActionOps.reblog(owner, owner, permlink, false))
                    withContext(dispatchers.main) { env.pointActivities.recordBroadcast(owner, PointActivity.REBLOG, signed) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    withContext(dispatchers.main) { env.ui.toast(L.alert.fail) }
                }
            }
        }
        env.emit(AppEvent.PostChanged(PostKey(owner, permlink)))
        return EditorDone.Published(owner, permlink, s.draftId)
    }

    private suspend fun submitSchedule(owner: String, s: EditorState): EditorDone {
        // Ecency posts for the account at the scheduled time: it needs the posting authority (RN grants it here)
        val authorities = try {
            env.chain.authorities(owner)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: throw AppException(AppError(ErrorKind.AUTH, L.native.editor.couldNotCheckYour, true))
        if (!authorities.grantsPostingTo()) {
            // the account is changed only after the user said yes to exactly that
            if (!grantApproved) throw GrantNeeded()
            grantApproved = false
            grantPostingAuthority(owner, authorities)
        }
        grantApproved = false
        val beneficiaries = publishBeneficiaries(owner, s)
        val meta = postMetadata(s)
        val permlink = newPermlink(owner, s.title, avoid = null)
        val options = OperationJson.encode(EditorMeta.commentOptions(owner, permlink, s.reward, beneficiaries))[1]
        env.editorApi.addSchedule(owner, permlink, s.title, s.body, Json.parseToJsonElement(EditorMeta.stringify(meta)), options, s.scheduledFor.orEmpty())
        env.emit(AppEvent.DraftsChanged(owner))
        return EditorDone.Scheduled
    }

    private suspend fun submitEdit(owner: String, s: EditorState): EditorDone {
        val p = post ?: throw AppException(AppError(ErrorKind.NOT_FOUND, L.native.editor.fetchFailed, true))
        // the patch is made against the version loaded here: if the post changed on chain since, it would revert that
        val latest = env.api.posts.getPost(owner, p.permlink, owner, refresh = true)
        if (latest != null && latest.updated != p.updated) {
            throw AppException(AppError(ErrorKind.INVALID_INPUT, L.native.editor.thisPostWasChanged, false))
        }
        val old = JsValue.parse(p.metadata.rawJson) as? JsValue.Obj
        val meta = EditorMeta.extractMetadata(
            s.body, s.thumbUrl, videoThumbUrls = s.videoThumbUrls, fetchRatios = true,
            postType = (old?.get("type") as? JsValue.Str)?.value, contentType = (old?.get("content_type") as? JsValue.Str)?.value,
            linkedPost = ::linkedPost, imageRatio = ::imageRatio,
        )
        aiToolsValue(old?.get("ai_tools"))?.let { meta["ai_tools"] = it }
        val json = EditorMeta.makeJsonMetadataForUpdate(old, meta, s.tags) ?: EditorMeta.makeJsonMetadata(meta, s.tags)
        val title = if (s.kind == EditorKind.EDIT_REPLY) "" else s.title
        val parentPermlink = p.parentPermlink.orEmpty().ifEmpty { p.category }
        broadcast(owner, EditorMeta.editOperations(owner, p.permlink, p.parentAuthor.orEmpty(), parentPermlink, title, EditorMeta.editBody(p.body, s.body), EditorMeta.stringify(json)))
        env.emit(AppEvent.PostChanged(p.key))
        return EditorDone.Edited(p.key)
    }

    private suspend fun submitReply(owner: String, s: EditorState): EditorDone {
        val parent = post ?: throw AppException(AppError(ErrorKind.NOT_FOUND, L.native.editor.fetchFailed, true))
        val json = EditorMeta.stringify(EditorModels.replyMetadata(env, s.body, parent.metadata.tags))
        val hash = PendingPublish.hashOf(parent.author, parent.permlink, "", s.body)
        val choice = conflictChoice
        conflictChoice = null
        val was = pending
        var permlink: String? = null
        var send = true
        val verdict = PendingCheck.verdict(env, owner, was, hash) { PendingPublish.hashOf(it.parentAuthor, it.parentPermlink, "", it.body) }
        when (verdict) {
            PendingVerdict.PUBLISHED -> {
                permlink = was!!.permlink
                send = false
            }
            PendingVerdict.CONFLICT, PendingVerdict.CONFLICT_CHANGED_ELSEWHERE -> when (choice) {
                null -> throw PublishConflict(verdict == PendingVerdict.CONFLICT_CHANGED_ELSEWHERE)
                true -> permlink = was!!.permlink
                false -> pending = null
            }
            PendingVerdict.RESEND -> permlink = was!!.permlink
            PendingVerdict.FRESH -> pending = null
        }
        // a new reply never reuses the permlink of the one that is out (same millisecond on a coarse clock)
        if (permlink == null) permlink = Comments.replyPermlink(parent.author, env.nowMillis()).let { if (it == was?.permlink) Comments.replyPermlink(parent.author, env.nowMillis() + 1) else it }
        if (send) {
            remember(owner, permlink, hash)
            broadcast(owner, EditorMeta.editOperations(owner, permlink, parent.author, parent.permlink, "", s.body, json), PointActivity.COMMENT)
        }
        val now = ChainTime.fromEpochSeconds(env.nowMillis() / 1000)
        val local = Post(
            author = owner, permlink = permlink, category = parent.category, body = s.body, metadata = PostMetadata(tags = parent.metadata.tags),
            created = now, updated = now, depth = parent.depth + 1, parentAuthor = parent.author, parentPermlink = parent.permlink,
        )
        val root = if (args.rootAuthor.isNotEmpty() && args.rootPermlink.isNotEmpty()) PostKey(args.rootAuthor, args.rootPermlink) else parent.key
        env.api.posts.addLocalComment(root, local)
        env.replyPostedElsewhere(parent.key)
        env.emit(AppEvent.CommentPosted(root, local.key))
        return EditorDone.Replied(local.key)
    }

    companion object {
        /** RN's form timer: the local draft is written this long after the last change. */
        const val LOCAL_SAVE_DELAY_MILLIS = 300L
        const val MAX_TITLE_LENGTH = 250
        const val MAX_DESCRIPTION_LENGTH = 255

        /** RN SUPPORT_SETTINGS_FETCH_TIMEOUT_MS. */
        const val SUPPORT_SETTINGS_TIMEOUT_MILLIS = 4000L
        const val PREF_DEFAULT_REWARD = "editor.defaultRewardType"

        /** RN BUILD_DEBOUNCE_MS. */
        const val RC_DEBOUNCE_MILLIS = 800L

        /** The SDK's staleTime for the curve parameters: they change at a hardfork only. */
        const val RC_PARAMS_MAX_AGE_MILLIS = 24L * 60 * 60 * 1000
        val RC_PRECHECK_TITLE: String get() = L.alert.rcPrecheckTitle
        val RC_PRECHECK_BODY: String get() = L.alert.rcPrecheckBody
    }
}

/** Helpers the editor shares with the quick reply sheet. */
object EditorModels {
    /** RN _submitReply's json_metadata: extracted links and images, type "comment", the parent's tags (or "ecency"). */
    suspend fun replyMetadata(env: AppEnvironment, body: String, parentTags: List<String>): JsValue.Obj {
        val meta = EditorMeta.extractMetadata(
            body, fetchRatios = true, postType = "comment",
            linkedPost = { a, p -> env.api.posts.getPost(a, p, "")?.let { LinkedPost(it.title) } },
            imageRatio = { env.imageRatios(it) },
        )
        return EditorMeta.makeJsonMetadata(meta, parentTags.ifEmpty { listOf("ecency") })
    }
}

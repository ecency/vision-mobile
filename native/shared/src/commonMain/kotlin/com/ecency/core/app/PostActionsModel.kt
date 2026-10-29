package com.ecency.core.app

import com.ecency.core.api.broadcast.PostActionOps
import com.ecency.core.api.model.Post
import com.ecency.core.api.model.PostKey
import com.ecency.core.api.postingJsonMetadata
import com.ecency.core.api.rebloggedBy
import com.ecency.core.l10n.L
import com.ecency.core.l10n.L10n
import com.ecency.core.render.RenderHelper
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlin.coroutines.cancellation.CancellationException

/** The RN post options menu (src/constants/options/post.ts), in its order. */
enum class PostMenuItem(val id: String) {
    CROSS_POST("cross-post"),
    PROMOTE("promote"),
    COPY("copy"),
    COPY_TEXT("copy-text"),
    OPEN_THREAD("open-thread"),
    REPLY("reply"),
    TRANSLATE("translate"),
    REBLOG("reblog"),
    UNDO_REBLOG("undo-reblog"),
    PIN_BLOG("pin-blog"),
    UNPIN_BLOG("unpin-blog"),
    PIN_COMMUNITY("pin-community"),
    UNPIN_COMMUNITY("unpin-community"),
    PIN_REPLY("pin-reply"),
    UNPIN_REPLY("unpin-reply"),
    MUTE_POST("mute-post"),
    UNMUTE_POST("unmute-post"),
    EDIT_HISTORY("edit-history"),
    SHARE("share"),
    BOOKMARKS("bookmarks"),
    MUTE("mute"),
    REPORT("report"),
    DELETE("delete-post"),
    ;

    /** RN `post_dropdown.<id>` in the current language (every id has its key, recorded in `rn-reuse.json`). */
    val label: String get() = L10n.byKey("post_dropdown.$id") ?: id

    /** RN draws every label upper-cased. */
    val title: String get() = label.uppercase()

    /** RN draws Delete in red. */
    val destructive: Boolean get() = this == DELETE
}

/** Where the menu is opened: RN's PostOptionsModal props. */
data class PostMenuContext(
    /** RN `isVisibleTranslateModal`: comments and sub-posts. */
    val translate: Boolean = false,
    /** RN `onOpenThread` given: comment surfaces. */
    val openThread: Boolean = false,
    /** RN `pageType === 'ownProfile'`: a post not by me on my blog is a reblog of mine. */
    val ownProfile: Boolean = false,
)

/** What the UI does after a menu tap ([PostActionsModel.select]). */
sealed interface PostMenuEffect {
    /** Copy [text] to the clipboard, then toast [toast] (RN "Copied!"). */
    data class Copy(val text: String, val toast: String) : PostMenuEffect

    /** The system share sheet with [message] (RN `${title} ${url}`). */
    data class Share(val message: String) : PostMenuEffect

    /** Push [screen] (gated). */
    data class Open(val screen: AppScreen) : PostMenuEffect

    /** Open the reply composer for [post]. */
    data class Reply(val post: Post) : PostMenuEffect

    data class CrossPost(val post: Post) : PostMenuEffect

    data class Translate(val post: Post) : PostMenuEffect

    /** RN ACTION_MODAL; on confirm call [PostActionsModel.confirm] with [user], the account that asked. */
    data class Confirm(val item: PostMenuItem, val post: Post, val title: String, val body: String?, val confirmLabel: String, val user: String) : PostMenuEffect

    /** RN MOD_NOTES sheet; on confirm call [PostActionsModel.confirm] with the notes. */
    data class AskNotes(
        val item: PostMenuItem,
        val post: Post,
        val title: String,
        val description: String,
        val placeholder: String,
        val confirmLabel: String,
        /** The account that opened the sheet; [PostActionsModel.confirm] refuses for any other. */
        val user: String,
    ) : PostMenuEffect

    /** Signed out: the login screen. */
    data object Login : PostMenuEffect

    /** Started; the result arrives as a toast or [PostActionsState.alert]. */
    data object Started : PostMenuEffect

    data object None : PostMenuEffect
}

/** RN Alert.alert("Fail!", message). */
data class ActionAlert(val title: String, val message: String)

data class PostActionsState(
    /** Actions running, by post. */
    val running: Map<PostKey, PostMenuItem> = emptyMap(),
    val alert: ActionAlert? = null,
    /** Posts deleted from this device; screens showing one go back (RN popScreenOnDelete). */
    val deleted: Set<PostKey> = emptySet(),
)

/**
 * The post options menu: which items show for a post and the signed-in account ([menu], RN _initOptions)
 * and what each does ([select], then [confirm] for the ones RN confirms first). Broadcasts go through
 * [SigningModel]; results are RN's toasts. One per app.
 */
class PostActionsModel internal constructor(private val env: AppEnvironment) :
    StateModel<PostActionsState>(PostActionsState(), env.dispatchers) {

    private val me: String? get() = env.session.current.username

    /**
     * The items for [post], as RN computes them when the sheet opens: the reblog state (reblogged_by, else
     * `get_reblogged_by`), the moderator role (`bridge.get_community` team), the blog pin (stats, else the
     * profile's `pinned`) and the parent's pinned reply. A lookup that fails counts as "no" (the item that
     * needs it does not show), so the menu always opens.
     */
    @Throws(CancellationException::class)
    suspend fun menu(shown: Post, context: PostMenuContext): List<PostMenuItem> = coroutineScope {
        val user = me
        val post = target(shown)
        val crossPost = shown.crossPostOriginal != null
        val isComment = post.depth > 0 || !post.parentAuthor.isNullOrEmpty()
        val rebloggedDeferred = async {
            when {
                user == null -> false
                context.ownProfile -> post.author != user
                user in post.rebloggedBy -> true
                else -> quiet { env.api.hive.rebloggedBy(post.author, post.permlink) }?.contains(user) == true
            }
        }
        // RN keeps the wrapper's community on a cross-post
        val community = shown.community
        val moderatorDeferred = async {
            if (user == null || community == null || crossPost) false
            // RN isCommunityModerator: owner, admin or mod on the team
            else quiet { env.communityApi.community(community, user) }?.roleOf(user)?.let { it.rank >= com.ecency.core.api.community.CommunityRole.MOD.rank } == true
        }
        val canPinBlog = user != null && user == post.author && !isComment
        val pinnedBlogDeferred = async {
            if (!canPinBlog) false
            else post.stats.isPinnedBlog ?: (quiet { env.api.accounts.getAccount(user!!) }?.let { pinnedOf(it.name) } == post.permlink)
        }
        val canPinReply = post.depth == 1 && user != null && user == post.parentAuthor
        val pinnedReplyDeferred = async {
            if (!canPinReply) false
            else quiet { env.api.posts.getPost(post.parentAuthor!!, post.parentPermlink.orEmpty(), user) }
                ?.metadata?.pinnedReply == post.key
        }
        val reblogged = rebloggedDeferred.await()
        val moderator = moderatorDeferred.await()
        val pinnedBlog = pinnedBlogDeferred.await()
        val pinnedReply = pinnedReplyDeferred.await()
        val canDelete = user != null && user == post.author && !crossPost && !post.isPaidout && post.children == 0 && post.netRshares <= 0
        PostMenuItem.entries.filter { item ->
            when (item) {
                PostMenuItem.REBLOG -> !reblogged
                PostMenuItem.UNDO_REBLOG -> reblogged
                PostMenuItem.PIN_BLOG -> canPinBlog && !pinnedBlog
                PostMenuItem.UNPIN_BLOG -> canPinBlog && pinnedBlog
                PostMenuItem.PIN_COMMUNITY -> moderator && !post.stats.isPinned
                PostMenuItem.UNPIN_COMMUNITY -> moderator && post.stats.isPinned
                PostMenuItem.PIN_REPLY -> canPinReply && !pinnedReply
                PostMenuItem.UNPIN_REPLY -> canPinReply && pinnedReply
                PostMenuItem.MUTE_POST -> moderator && !post.stats.gray
                PostMenuItem.UNMUTE_POST -> moderator && post.stats.gray
                PostMenuItem.COPY_TEXT -> isComment && post.body.isNotBlank()
                PostMenuItem.OPEN_THREAD -> isComment && context.openThread
                PostMenuItem.TRANSLATE -> context.translate
                PostMenuItem.DELETE -> canDelete
                PostMenuItem.CROSS_POST -> community != null
                else -> true
            }
        }
    }

    /** The profile's pinned permlink, read fresh from posting_json_metadata. */
    private suspend fun pinnedOf(account: String): String? {
        val raw = env.api.hive.postingJsonMetadata(account) ?: return null
        val profile = PostActionOps.parseObject(raw)["profile"] as? kotlinx.serialization.json.JsonObject ?: return null
        return (profile["pinned"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
    }

    /** The entry the menu acts on: a cross-post's original (RN parsePost swaps it in). */
    private fun target(post: Post): Post = post.crossPostOriginal ?: post

    /** A menu tap. Effects the UI performs come back; broadcasts start here. Call on the main thread. */
    @Throws(CancellationException::class)
    suspend fun select(item: PostMenuItem, shown: Post): PostMenuEffect {
        val user = me
        val post = target(shown)
        return when (item) {
            PostMenuItem.COPY -> PostMenuEffect.Copy(postUrl(post), COPIED)
            PostMenuItem.COPY_TEXT -> {
                val platform = (env.renderer as? RenderHelperPostBodyRenderer)?.platform ?: RenderHelper.Platform.ANDROID
                val text = kotlinx.coroutines.withContext(env.dispatchers.render) { RenderHelper(env.proxyBase).postBodySummary(post.body, 0, platform) }
                if (text.isBlank()) PostMenuEffect.None else PostMenuEffect.Copy(text, COPIED)
            }
            PostMenuItem.SHARE -> PostMenuEffect.Share("${post.title} ${postUrl(post)}")
            PostMenuItem.OPEN_THREAD -> PostMenuEffect.Open(AppScreen.Post(post.author, post.permlink))
            PostMenuItem.REPLY -> if (user == null) PostMenuEffect.None else PostMenuEffect.Reply(post)
            PostMenuItem.PROMOTE -> if (user == null) PostMenuEffect.None
            else PostMenuEffect.Open(AppScreen.of(RouteName.REDEEM, "${post.author}/${post.permlink}"))
            PostMenuItem.EDIT_HISTORY -> PostMenuEffect.Open(AppScreen.EditHistory(post.author, post.permlink))
            PostMenuItem.CROSS_POST -> if (user == null) PostMenuEffect.Login else PostMenuEffect.CrossPost(post)
            PostMenuItem.TRANSLATE -> PostMenuEffect.Translate(post)
            PostMenuItem.DELETE -> if (user == null) PostMenuEffect.Login else PostMenuEffect.Confirm(item, post, REMOVE_ALERT, null, L.alert.delete, user)
            PostMenuItem.REPORT -> if (user == null) PostMenuEffect.Login
            else PostMenuEffect.Confirm(item, post, L.report.confirmReportTitle, L.report.confirmReportBody, L.alert.confirm, user)
            PostMenuItem.MUTE_POST, PostMenuItem.UNMUTE_POST -> if (user == null) PostMenuEffect.Login else {
                val mute = item == PostMenuItem.MUTE_POST
                PostMenuEffect.AskNotes(
                    item, post,
                    title = ModNotes.title(unmute = !mute),
                    description = "@${post.author}/${post.permlink}",
                    placeholder = ModNotes.placeholder(unmute = !mute),
                    confirmLabel = ModNotes.confirmLabel(unmute = !mute),
                    user = user,
                )
            }
            PostMenuItem.MUTE -> when {
                user == null -> PostMenuEffect.Login
                user == post.author -> PostMenuEffect.None
                else -> run(item, post) { ignore(user, post.author) }
            }
            PostMenuItem.BOOKMARKS -> if (user == null) PostMenuEffect.Login else run(item, post) {
                env.accountApi.addBookmark(user, post.author, post.permlink)
                env.ui.toast(SUCCESS)
            }
            PostMenuItem.REBLOG, PostMenuItem.UNDO_REBLOG -> if (user == null) PostMenuEffect.Login else run(item, post) {
                reblog(user, post, undo = item == PostMenuItem.UNDO_REBLOG)
            }
            PostMenuItem.PIN_BLOG, PostMenuItem.UNPIN_BLOG -> if (user == null) PostMenuEffect.Login else run(item, post) {
                // never merge onto a stripped read: the profile edit's guarded path (ProfileWrites)
                val checked = try {
                    ProfileWrites.checkedBase(env, user)
                } catch (e: AppException) {
                    if (!ProfileWrites.isRefusal(e)) throw e
                    env.ui.toast(com.ecency.core.api.broadcast.ProfileMetadataGuard.REFUSED)
                    return@run
                }
                val pinned = if (item == PostMenuItem.PIN_BLOG) post.permlink else null
                ProfileWrites.broadcast(env, user, checked, com.ecency.core.api.broadcast.ProfileMetadataGuard.pinnedProfile(checked.base, pinned))
                env.emit(AppEvent.ProfileUpdated(user))
                env.ui.toast(SUCCESSFUL)
                changed(post.key)
            }
            PostMenuItem.PIN_COMMUNITY, PostMenuItem.UNPIN_COMMUNITY -> if (user == null) PostMenuEffect.Login else run(item, post) {
                val community = post.community ?: throw AppException(AppError(ErrorKind.INVALID_INPUT, L.native.postActions.notACommunityPost, false))
                env.communities.pinPost(community, post.author, post.permlink, item == PostMenuItem.PIN_COMMUNITY, asUser = user)
                env.ui.toast(SUCCESSFUL)
                changed(post.key)
            }
            PostMenuItem.PIN_REPLY, PostMenuItem.UNPIN_REPLY -> if (user == null) PostMenuEffect.Login else run(item, post) {
                val parent = env.api.posts.getPost(post.parentAuthor.orEmpty(), post.parentPermlink.orEmpty(), user, true)
                    ?: throw AppException(AppError(ErrorKind.NOT_FOUND, L.native.postActions.parentPostNotFound, false))
                broadcast(user, PostActionOps.pinReply(parent, if (item == PostMenuItem.PIN_REPLY) post else null))
                env.ui.toast(SUCCESSFUL)
                changed(parent.key)
            }
        }
    }

    /**
     * The confirmed half of [PostMenuEffect.Confirm] and [PostMenuEffect.AskNotes]; [asUser] is the effect's
     * `user`. Blank notes do nothing (RN). A confirmation that outlived its account (a switch or a link login
     * while the alert or sheet was open) does nothing: it was asked of someone else.
     */
    fun confirm(item: PostMenuItem, shown: Post, asUser: String, notes: String? = null) {
        val user = me ?: return
        if (user != asUser) return
        val post = target(shown)
        when (item) {
            PostMenuItem.DELETE -> if (user == post.author) run(item, post) {
                try {
                    broadcast(user, PostActionOps.delete(post.author, post.permlink))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (AppError.fromBroadcast(e).kind == ErrorKind.CANCELLED) throw e
                    env.ui.toast(FAIL)
                    return@run
                }
                setState { it.copy(deleted = it.deleted + post.key) }
                env.emit(AppEvent.PostDeleted(post.key))
                env.ui.toast(L.alert.removed)
            } else Unit
            PostMenuItem.REPORT -> run(item, post) {
                try {
                    requireCurrent(user)
                    env.accountApi.reportPost(user, post.author, post.permlink)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    env.ui.toast(L.report.failed)
                    return@run
                }
                env.ui.toast(L.report.added)
                // RN mutes the reported author right after
                if (user != post.author) ignore(user, post.author)
            }
            PostMenuItem.MUTE_POST, PostMenuItem.UNMUTE_POST -> {
                // hivemind's notes rules, in one place (ModNotes, CommunityOps): blank does nothing, as RN
                val text = ModNotes.notes(notes.orEmpty()) ?: return
                run(item, post) {
                    val community = post.community ?: throw AppException(AppError(ErrorKind.INVALID_INPUT, L.native.postActions.notACommunityPost, false))
                    env.communities.mutePost(community, post.author, post.permlink, text, item == PostMenuItem.MUTE_POST, asUser = user)
                    env.ui.toast(SUCCESSFUL)
                    changed(post.key)
                }
            }
            else -> Unit
        }
    }

    fun dismissAlert() = setState { it.copy(alert = null) }

    private suspend fun reblog(user: String, post: Post, undo: Boolean) {
        try {
            // SDK useReblog reports 130 for an undo too (RN parity; the server decides what earns)
            broadcast(user, PostActionOps.reblog(user, post.author, post.permlink, undo), PointActivity.REBLOG)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val error = AppError.fromBroadcast(e)
            if (error.kind == ErrorKind.CANCELLED) return
            when {
                (e.message ?: "").contains("has already reblogged") -> env.ui.toast(L.alert.alreadyRebloged)
                // RN repostQueries / postOptionsModal: out of RC is the app-wide offer, not a toast
                env.offerRcIfShort(e) -> {}
                else -> env.ui.toast(FAIL)
            }
            return
        }
        env.ui.toast(if (undo) L.alert.successReblogDeleted else L.alert.successRebloged)
        changed(post.key)
    }

    private suspend fun ignore(user: String, author: String) {
        try {
            broadcast(user, PostActionOps.ignore(user, author))
            // the account changed while signing: this result belongs to the previous one (no list, no toast)
            if (me != user) return
            env.setAuthorMuted(author, true)
            env.ui.toast(L.alert.successMute)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val error = AppError.fromBroadcast(e)
            if (error.kind != ErrorKind.CANCELLED && !env.offerRcIfShort(e)) setState { it.copy(alert = ActionAlert(FAIL, error.message)) }
        }
    }

    /** [activity]: the points report the SDK's mutation makes for this action (a reblog), if any. */
    private suspend fun broadcast(user: String, ops: JsonArray, activity: Int? = null) {
        requireCurrent(user)
        val signed = env.signing.broadcast(user, ops)
        env.pointActivities.recordBroadcast(user, activity, signed)
    }

    /** An action belongs to the account that started it: once the session moved on it is dropped, silently. */
    private fun requireCurrent(user: String) {
        if (me != user) throw AppException(AppError(ErrorKind.CANCELLED, L.native.postActions.theAccountChanged, false))
    }

    private suspend fun changed(key: PostKey) {
        env.api.posts.invalidate(key)
        env.emit(AppEvent.PostChanged(key))
    }

    /** Runs [block] once per post at a time; an error that escapes becomes RN's "Fail!" alert. */
    private fun run(item: PostMenuItem, post: Post, block: suspend () -> Unit): PostMenuEffect {
        if (post.key in current.running) {
            env.ui.toast(BUSY)
            return PostMenuEffect.None
        }
        setState { it.copy(running = it.running + (post.key to item)) }
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val error = AppError.fromBroadcast(e)
                // RN postOptionsModal _profileActionDone: out of RC is the app-wide offer, not the alert
                if (error.kind != ErrorKind.CANCELLED && !env.offerRcIfShort(e)) setState { it.copy(alert = ActionAlert(FAIL, error.message)) }
            } finally {
                setState { it.copy(running = it.running - post.key) }
            }
        }
        return PostMenuEffect.Started
    }

    private suspend fun <T> quiet(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    companion object {
        val COPIED: String get() = L.alert.copied
        val BUSY: String get() = L.native.postActions.busy
        val SUCCESS: String get() = L.alert.success
        val SUCCESSFUL: String get() = L.alert.successful
        val FAIL: String get() = L.alert.fail
        val REMOVE_ALERT: String get() = L.alert.removeAlert

        /** RN getPostUrl(stripCategoryFromPostPath(url)): `https://ecency.com/@author/permlink`. */
        fun postUrl(post: Post): String {
            val path = post.url.ifEmpty { "/@${post.author}/${post.permlink}" }
            val canonical = Regex("^/[^/@][^/]*/@").replace(path, "/@")
            return "https://ecency.com" + (if (canonical.startsWith("/")) canonical else "/$canonical")
        }
    }
}

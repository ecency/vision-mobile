package com.ecency.app.ui

import androidx.compose.ui.semantics.LiveRegionMode

import androidx.compose.ui.semantics.liveRegion

import androidx.compose.ui.semantics.selected

import androidx.compose.ui.semantics.customActions

import androidx.compose.ui.semantics.contentDescription

import androidx.compose.ui.text.style.TextOverflow

import androidx.compose.foundation.layout.heightIn

import androidx.compose.ui.semantics.heading

import androidx.compose.ui.semantics.semantics

import androidx.compose.ui.semantics.Role

import com.ecency.core.app.AppIcon
import com.ecency.app.render.ProvideSpeakPlayers
import com.ecency.app.render.SpeakPlayers
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ecency.app.render.PostActions
import com.ecency.app.render.PostBody
import com.ecency.app.render.postBlockKey
import com.ecency.app.render.postBody
import com.ecency.app.render.withAnchors
import com.ecency.core.api.model.Post
import com.ecency.core.api.repository.CommentSort
import com.ecency.core.app.AppScreen
import com.ecency.core.app.CommentItem
import com.ecency.core.app.PerfEvent
import com.ecency.core.app.PerfMarks
import com.ecency.core.app.PostCard
import com.ecency.core.app.PostModel
import com.ecency.core.app.TimeAgo
import com.ecency.core.app.VoteState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.ecency.core.app.ReadingUiCopy
import com.ecency.core.app.UiCopy

/**
 * The list index of body block [index]: found from a body row or the body's start that is on screen (the rows are
 * keyed by [postBlockKey]); else [bodyBase], the body's first row as the list was last built (a link in a comment).
 */
internal fun anchorItem(list: LazyListState, index: Int, bodyBase: Int = -1): Int? {
    val visible = list.layoutInfo.visibleItemsInfo
    visible.firstOrNull { (it.key as? String)?.startsWith("block:") == true }?.let { row ->
        val shown = (row.key as String).removePrefix("block:").toIntOrNull() ?: return@let
        return row.index - shown + index
    }
    visible.firstOrNull { it.key == "bodyStart" }?.let { return it.index + 1 + index }
    // a link in a comment far below the body: the body's first row as the list was last built
    return bodyBase.takeIf { it >= 0 }?.let { it + index }
}

/** Counts the lazy items declared through it, so a screen knows where its body rows start. */
internal class CountingListScope(private val scope: androidx.compose.foundation.lazy.LazyListScope) : androidx.compose.foundation.lazy.LazyListScope by scope {
    var count = 0
        private set

    override fun item(key: Any?, contentType: Any?, content: @Composable androidx.compose.foundation.lazy.LazyItemScope.() -> Unit) {
        count++
        scope.item(key, contentType, content)
    }

    override fun items(
        count: Int,
        key: ((index: Int) -> Any)?,
        contentType: (index: Int) -> Any?,
        itemContent: @Composable androidx.compose.foundation.lazy.LazyItemScope.(index: Int) -> Unit,
    ) {
        this.count += count
        scope.items(count, key, contentType, itemContent)
    }
}

/** What the post screen can lead to. */
class PostScreenActions(
    val onBack: () -> Unit,
    val onAuthor: (String) -> Unit,
    val onCommunity: (PostCard) -> Unit,
    /** Vote on the post or a comment: opens the weight sheet. */
    val onVote: (PostCard) -> Unit,
    /** Reply to the post or a comment: opens the composer. */
    val onReply: (Post) -> Unit,
    /** "More replies" below level 3: the comment as its own post screen. */
    val onOpenThread: (Post) -> Unit,
    val body: PostActions,
    /** Voters, reblogs, edit history, bot comments, the parent and "Read next" posts, the editor placeholder. */
    val onScreen: (AppScreen) -> Unit = {},
    /** The discussion replies from the menu belong to. */
    val root: com.ecency.core.api.model.PostKey? = null,
)

/**
 * Header, body and comments in ONE LazyColumn: the body is one lazy item per block, so only what
 * is on screen is composed, and comments follow it. The action bar sits below the list as in RN.
 */
@Composable
fun PostScreen(
    model: PostModel,
    listState: LazyListState,
    votes: StateFlow<VoteState>,
    nowMillis: Long,
    actions: PostScreenActions,
    /** Holds the screen's extra models (translation, "Read next") so they outlive a trip to another screen. */
    entry: StackEntry? = null,
) {
    val state by model.state.collectAsState()
    val colors = LocalEcencyColors.current
    val scope = rememberCoroutineScope()
    val drawn = rememberOnce(model)
    // #2403: an `#id` link in the body or a comment scrolls the body to that place (no screen is pushed, so back
    // still leaves the post); a link to this same post with a fragment does too
    val motionLevel = LocalMotion.current.level
    // where the body's first row sits in the list, from its last build (-1 before there is a body)
    val bodyBase = remember(model) { intArrayOf(-1) }
    // where the comments header (with its Reply) sits in the list, from its last build (-1 before the post is in)
    val commentsBase = remember(model) { intArrayOf(-1) }
    val screenActions = actions
    val actions = remember(screenActions, state.body, model.key, motionLevel) {
        val blocks = state.body
        val body = screenActions.body.withAnchors { target ->
            val index = blocks?.let { com.ecency.core.render.blocks.BlockAnchors.jump(it, target, com.ecency.core.render.blocks.LinkTarget.Post(model.key.author, model.key.permlink)) }
            if (index == null) target.post == null // an unknown id does nothing; another post's link opens it
            else {
                anchorItem(listState, index, bodyBase[0])?.let { item ->
                    scope.launch { if (motionLevel == com.ecency.core.app.MotionLevel.FULL) listState.animateScrollToItem(item) else listState.scrollToItem(item) }
                }
                true
            }
        }
        PostScreenActions(
            screenActions.onBack, screenActions.onAuthor, screenActions.onCommunity, screenActions.onVote, screenActions.onReply,
            screenActions.onOpenThread, body, screenActions.onScreen, screenActions.root,
        )
    }
    LaunchedEffect(model) { model.start() }
    val feedx = LocalFeedx.current
    val env = feedx?.env
    // RN postDisplayView: reading a post, comment or reply is the daily check-in (at most once per 15 minutes and
    // account, the recorder's rule); asked again when the account lands or the lock lifts
    if (env != null) {
        val reader = env.session.state.collectAsState().value.username
        val hidden = env.appLock.state.collectAsState().value.hidesContent
        LaunchedEffect(reader, hidden) { if (reader != null && !hidden) env.checkIn.record() }
    }
    val translate = remember(entry, env) { env?.let { e -> entry?.get("translate") { e.translateModel(model.key.author, model.key.permlink, feedx.host.readerLanguage()) } } }
    val digestPrompt = remember(entry, env) { env?.let { e -> entry?.get("digestPrompt") { e.digestPromptModel() } } }
    val recap = remember(entry, env) { env?.let { e -> entry?.get("recap") { e.recapModel(model.key.author, model.key.permlink, feedx.host.readerLanguage()) } } }
    val similar = remember(entry, env) { env?.let { e -> entry?.get("similar") { e.similarEntriesModel() } } }
    val translation = translate?.state?.collectAsState()?.value
    val poll = remember(state.post?.metadata?.rawJson, env) { state.post?.let { p -> env?.pollModel(p) } }
    androidx.compose.runtime.DisposableEffect(poll) { onDispose { poll?.close() } }
    // RN listenBar: the status and the player belong to the screen (its stack entry), not to the lazy list item, so the
    // audio goes on while the post scrolls, behind pushed screens and in the background, and stops when the screen closes
    val listenEngine = com.ecency.app.LocalListenEngine.current
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val listen = remember(entry, env) {
        env?.let { e ->
            val m = entry?.get("listen") { e.listenModel(model.key.author, model.key.permlink) } ?: e.listenModel(model.key.author, model.key.permlink)
            val c = entry?.get("listenPlayer") { listenController(e, m, listenEngine, appContext, feedx.host.readerLanguage()) }
                ?: listenController(e, m, listenEngine, appContext, feedx.host.readerLanguage())
            m to c
        }
    }
    if (entry == null && listen != null) {
        androidx.compose.runtime.DisposableEffect(listen) { onDispose { listen.second.close(); listen.first.close() } }
    }
    val listenPost = state.post
    LaunchedEffect(listen, listenPost?.updated, listenPost?.body) { listenPost?.let { listen?.first?.start(it) } }
    val me = env?.session?.state?.collectAsState()?.value?.username
    // RN commentsSection: replies under a top-level comment start collapsed (kept with the screen's entry)
    val expanded = remember(entry) { entry?.get("expandedThreads") { androidx.compose.runtime.mutableStateOf(emptySet<com.ecency.core.api.model.PostKey>()) } ?: androidx.compose.runtime.mutableStateOf(emptySet()) }
    val shownComments = remember(state.comments, expanded.value) { com.ecency.core.app.CommentThreads.visible(state.comments, expanded.value) }

    Column(Modifier.fillMaxSize().background(colors.background).testTag("postScreen")) {
        TopBar(ReadingUiCopy.POST, onBack = actions.onBack) {
            val p = state.post
            if (p != null && feedx != null) {
                // RN header edit icon: own content only
                if (me != null && me == p.author) {
                    androidx.compose.foundation.layout.Box(
                        Modifier.clip(RoundedCornerShape(50)).clickable(role = Role.Button) { actions.onScreen(AppScreen.of(com.ecency.core.app.RouteName.EDITOR, "${p.author}/${p.permlink}")) }.padding(12.dp).testTag("editPost"),
                    ) { Glyph(AppIcon.EDIT, colors.darkGray, 22.dp, description = UiCopy.EDIT) }
                }
                OptionsButton(Modifier.padding(end = 6.dp)) {
                    feedx.openMenu(p, com.ecency.core.app.PostMenuContext(translate = p.depth > 0, openThread = false), model.key)
                }
            }
        }
        BoxWithConstraints(Modifier.weight(1f)) { ProvideSpeakPlayers(remember(entry) { entry?.get("speakPlayers") { SpeakPlayers() } ?: SpeakPlayers() }) {
            val contentWidth = maxWidth - 32.dp
            // 16dp gutters for every item, as the RN post screen (content width = window - 32)
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(horizontal = 16.dp),
                modifier = Modifier.fillMaxSize().testTag("postList"),
            ) {
                val counted = CountingListScope(this)
                with(counted) {
                when {
                    state.notFound -> item { EsieMessageBlock(com.ecency.core.esie.EsiePlacement.EMPTY, ReadingUiCopy.THIS_POST_DOES_NOT, Modifier.padding(top = 48.dp)) }
                    state.post == null && state.error != null -> item {
                        ErrorBlock(state.error!!, Modifier.padding(top = 48.dp), com.ecency.core.esie.EsiePlacement.ERROR, model::retry)
                    }
                    state.post == null -> item { PostBodySkeleton(Modifier.padding(top = 16.dp)) }
                }
                val card = state.card
                if (card != null) {
                    item(key = "title") {
                        Text(
                            card.title, fontSize = 24.sp, lineHeight = 31.sp, fontWeight = FontWeight.Bold, color = colors.text,
                            modifier = Modifier.padding(top = 8.dp, bottom = 8.dp).testTag("postTitle"),
                        )
                    }
                    item(key = "header") {
                        Row(verticalAlignment = Alignment.Top) {
                            PostHeader(
                                card, nowMillis, avatarSize = 40,
                                onAuthor = { actions.onAuthor(card.author) },
                                onCommunity = { actions.onCommunity(card) },
                                modifier = Modifier.weight(1f).padding(bottom = 12.dp),
                                inline = true,
                            )
                            // RN postDisplayView headerWithStats: the view count, tap for the stats modal
                            state.post?.let { p -> if (env != null) PostViewCount(env, p, entry, actions.onScreen) }
                        }
                        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.lightBackground))
                    }
                }
                state.parent?.let { parent ->
                    item(key = "parent") { ParentPostBox(parent, state.parentSummary) { actions.onScreen(AppScreen.Post(parent.author, parent.permlink)) } }
                }
                val reading = state.reading
                if (reading != null && env != null) {
                    item(key = "reading") {
                        val p = state.post
                        if (recap != null && p != null) LaunchedEffect(recap, p.body, me) { recap.start(p, me) }
                        ReadingBar(reading, recap)
                    }
                }
                if (listen != null && state.post != null && feedx != null) {
                    item(key = "listen") {
                        ListenSection(listen.first, listen.second, onLogin = feedx::requestLogin) {
                            actions.onScreen(AppScreen.of(com.ecency.core.app.RouteName.BOOST))
                        }
                    }
                }
                val post = state.post
                if (translate != null && post != null) {
                    item(key = "translate") { TranslateBanner(translate, post) { feedx?.translate = post } }
                }
                val body = state.body
                if (card != null && body == null) item(key = "bodyLoading") { PostBodySkeleton() }
                val translated = translation?.translated
                if (translated != null) {
                    item(key = "translatedBody") { TranslatedBody(translated, translation.rtl) }
                } else if (body != null) {
                    bodyBase[0] = counted.count + 1
                    item(key = "bodyStart") {
                        Spacer(Modifier.height(8.dp).onFirstDraw { drawn.run { PerfMarks.mark(PerfEvent.POST_BODY_DRAWN) } }.testTag("bodyStart"))
                    }
                    postBody(body, contentWidth, actions.body)
                }
                // RN postDisplayView footer: "Posted by ... via ..." with the source and AI badges, once the body is in
                if (post != null && state.body != null && translation?.translated == null) item(key = "postedBy") { PostedByRow(post, nowMillis) }
                // RN NewsletterPostPrompt, once the body is in
                if (digestPrompt != null && post != null && state.body != null) item(key = "digestPrompt") { DigestPostPrompt(digestPrompt, post, me) }
                if (poll != null) item(key = "poll") { PollCard(poll) { voters -> feedx?.env?.ui?.toast(voters.joinToString(", ").ifEmpty { ReadingUiCopy.NO_VOTES_YET }) } }
                if (similar != null && post != null && state.body != null) {
                    item(key = "similar") { SimilarEntries(similar, post, nowMillis, actions.body.showImages) { a, p -> actions.onScreen(AppScreen.Post(a, p)) } }
                }
                if (state.post != null) {
                    commentsBase[0] = counted.count
                    item(key = "commentsHeader") {
                        CommentsHeader(state.card?.comments ?: state.comments.size, state.commentSort, model::setCommentSort) { actions.onReply(state.post!!) }
                        if (env != null) {
                            BotPreviewRow(state.botComments.map { it.post.author }, env.proxyBase) {
                                actions.onScreen(AppScreen.BotComments(model.key.author, model.key.permlink))
                            }
                        }
                    }
                    if (state.comments.isEmpty() && state.commentsLoading) item(key = "commentsLoading") { LoadingBlock() }
                    state.commentsError?.let { error -> item(key = "commentsError") { ErrorBlock(error, onRetry = model::retry) } }
                    if (state.comments.isEmpty() && !state.commentsLoading && state.commentsError == null) {
                        item(key = "noComments") { MessageBlock(ReadingUiCopy.NO_COMMENTS_YET) }
                    }
                    items(shownComments, key = { "c:" + it.key }, contentType = { "comment" }) { item ->
                        CommentView(
                            item, nowMillis, contentWidth,
                            voteInFlight = voteInFlight(votes, item.key),
                            actions = actions,
                            thread = if (com.ecency.core.app.CommentThreads.hasToggle(item)) {
                                ThreadToggle(com.ecency.core.app.CommentThreads.isOpen(state.comments, item.key, expanded.value)) {
                                    // RN: nothing loaded below it opens the comment's own thread
                                    if (com.ecency.core.app.CommentThreads.loadedReplies(state.comments, item.key) == 0) actions.onOpenThread(item.post)
                                    else expanded.value = com.ecency.core.app.CommentThreads.toggle(expanded.value, item.key)
                                }
                            } else null,
                        )
                    }
                    item(key = "end") { Spacer(Modifier.height(24.dp)) }
                }
                }
            }
        }
}
        val card = state.card
        val tip = LocalTip.current
        // RN tipsQuery: the tip count beside the gift, read again after a tip from here
        // kept in the stack entry (closed with it) like the screen's other models; without an entry, remembered here
        // and closed when it leaves
        val tipsModel = remember(entry, env) {
            env?.let { e -> entry?.get("tips") { e.postTipsModel(model.key.author, model.key.permlink) } ?: e.postTipsModel(model.key.author, model.key.permlink) }
        }
        androidx.compose.runtime.DisposableEffect(tipsModel) { onDispose { if (entry == null) tipsModel?.close() } }
        LaunchedEffect(tipsModel) { tipsModel?.start() }
        val tips = tipsModel?.state?.collectAsState()?.value
        if (card != null) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.lightBackground))
            ActionsRow(
                card = card,
                voteInFlight = voteInFlight(votes, card.key),
                onVote = { actions.onVote(card) },
                // RN _scrollToComments: the comments icon takes the reader to the comments, whose header holds the
                // Reply to the post (no separate Reply in this bar)
                onComments = {
                    val index = commentsBase[0]
                    if (index >= 0) scope.launch { if (motionLevel == com.ecency.core.app.MotionLevel.FULL) listState.animateScrollToItem(index) else listState.scrollToItem(index) }
                },
                modifier = Modifier.navigationBarsPadding().padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 6.dp).testTag("postActions"),
                extras = RowExtras(
                    onVoters = { actions.onScreen(AppScreen.Voters(card.key.author, card.key.permlink)) },
                    onReblogs = { actions.onScreen(AppScreen.Reblogs(card.key.author, card.key.permlink)) },
                    onTip = { tip?.invoke(card.key) },
                    payoutPost = { state.post },
                    tips = tips,
                ),
            )
        }
    }
}

/** The Reply to the post, in its comments header (the bar's comments icon scrolls here). */
@Composable
private fun ReplyButton(onClick: () -> Unit) {
    val colors = LocalEcencyColors.current
    Text(
        ReadingUiCopy.REPLY, color = colors.blueText, fontWeight = FontWeight.Bold, fontSize = 14.sp,
        modifier = Modifier.padding(start = 8.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick, role = Role.Button)
            .inMergedRow(LocalMergedRow.current).padding(horizontal = 10.dp, vertical = 6.dp).testTag("commentsReply"),
    )
}

@Composable
private fun CommentsHeader(count: Int, sort: CommentSort, onSort: (CommentSort) -> Unit, onReply: () -> Unit) {
    val colors = LocalEcencyColors.current
    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Box(Modifier.fillMaxWidth().height(8.dp).background(colors.lightBackground))
        Row(Modifier.padding(top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(ReadingUiCopy.commentsCount(count), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = colors.text, modifier = Modifier.weight(1f))
            ReplyButton(onReply)
        }
        // the sort tabs wrap at a large font on a narrow phone instead of cutting the last one
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        androidx.compose.foundation.layout.FlowRow {
            listOf(CommentSort.TRENDING to ReadingUiCopy.TOP, CommentSort.REPUTATION to UiCopy.REPUTATION, CommentSort.VOTES to ReadingUiCopy.VOTES, CommentSort.AGE to ReadingUiCopy.AGE)
                .forEach { (s, label) ->
                    val selected = s == sort
                    Text(
                        label,
                        fontSize = 13.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        color = if (selected) colors.blueText else colors.darkGray,
                        modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(role = Role.Tab) { onSort(s) }
                            .semantics { this.selected = selected }
                            .padding(horizontal = 8.dp, vertical = 6.dp).testTag("sort:${s.name}"),
                    )
                }
        }
    }
}

/** One comment: avatar left, name and age, body, actions; nested levels are indented (RN: 44 past level 1). */
@Composable
internal fun CommentView(item: CommentItem, nowMillis: Long, contentWidth: Dp, voteInFlight: Boolean, actions: PostScreenActions, thread: ThreadToggle? = null) {
    val feedx = LocalFeedx.current
    val colors = LocalEcencyColors.current
    val indent = (44 * (item.level - 1)).dp
    val bodyWidth = contentWidth - indent - 44.dp
    val tip = LocalTip.current
    // TalkBack: the comment's header is one stop (author, age, votes) carrying the comment's taps as custom actions;
    // the body after it keeps its own links and images; the small taps are hidden from TalkBack (LocalMergedRow)
    val payoutDetails = rememberPayoutDetails()
    // keyed on the toggle's state, not the toggle (a new one each pass): its latest handler is read when used
    val currentThread by androidx.compose.runtime.rememberUpdatedState(thread)
    val a11yActions = remember(item, actions, thread?.open, tip, feedx, LocalAppLanguage.current) { buildList {
        add(a11yAction(UiCopy.UPVOTE) { actions.onVote(item.card) })
        add(a11yAction(com.ecency.core.l10n.L.post.a11yReplyHint) { actions.onReply(item.post) })
        add(a11yAction(UiCopy.profileOf(item.card.author)) { actions.onAuthor(item.card.author) })
        add(a11yAction(com.ecency.core.l10n.L.post.a11yVotersHint) { actions.onScreen(AppScreen.Voters(item.key.author, item.key.permlink)) })
        if (tip != null) add(a11yAction(UiCopy.SEND_TIP) { tip(item.key) })
        thread?.let { t -> add(a11yAction(if (t.open) com.ecency.core.app.CommentThreadCopy.HIDE_REPLIES else com.ecency.core.app.CommentThreadCopy.SHOW_REPLIES) { currentThread?.onToggle?.invoke() }) }
        if (feedx != null) {
            add(a11yAction(ReadingUiCopy.POST_OPTIONS) { feedx.openMenu(item.post, com.ecency.core.app.PostMenuContext(translate = true, openThread = true), actions.root ?: item.post.key) })
        }
        add(a11yAction(com.ecency.core.app.PayoutCopy.DETAILS) { payoutDetails.value = item.post })
    } }
    fun header() = buildList {
        add(item.card.author)
        add(TimeAgo.short(item.card.createdEpochMillis, nowMillis))
        add(com.ecency.core.l10n.L.post.a11yVotes(count = item.card.votes))
        // the reply count lives on the comments icon, which the merged row hides from TalkBack
        add(com.ecency.core.l10n.L.post.a11yComments(count = item.card.comments))
        add(item.card.payoutText)
    }.joinToString(", ")
    Row(Modifier.fillMaxWidth().padding(start = indent, top = 12.dp).testTag("comment:${item.key}")) {
      androidx.compose.runtime.CompositionLocalProvider(LocalMergedRow provides true) {
        Avatar(item.card.avatarUrl, 36.dp, Modifier.clip(RoundedCornerShape(50)).avatarButton(item.card.author) { actions.onAuthor(item.card.author) }.then(HiddenFromA11y))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Row(
                Modifier.semantics(mergeDescendants = true) { contentDescription = header(); customActions = a11yActions }.testTag("commentHeader"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    item.card.author, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = colors.text,
                    modifier = Modifier.clickable(role = Role.Button) { actions.onAuthor(item.card.author) }.then(HiddenFromA11y),
                )
                Text(
                    " " + TimeAgo.short(item.card.createdEpochMillis, nowMillis),
                    fontSize = 14.sp, fontWeight = FontWeight.Light, color = colors.darkGray,
                )
                if (item.isLocal) Text("  " + ReadingUiCopy.SENDING_TO_CHAIN, fontSize = 12.sp, color = colors.darkGray, modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }.testTag("localComment"))
                Spacer(Modifier.weight(1f))
                if (feedx != null) {
                    // RN comment menu: translate and "open thread" on comment surfaces
                    OptionsButton { feedx.openMenu(item.post, com.ecency.core.app.PostMenuContext(translate = true, openThread = true), actions.root ?: item.post.key) }
                }
            }
            androidx.compose.runtime.CompositionLocalProvider(LocalMergedRow provides false) {
                PostBody(item.blocks, bodyWidth, actions.body, Modifier.padding(top = 2.dp))
            }
            ActionsRow(
                card = item.card,
                voteInFlight = voteInFlight,
                onVote = { actions.onVote(item.card) },
                // the comments icon with the reply count opens the reply input (no "Reply" text: the row keeps the
                // thread toggle on its line)
                onComments = { actions.onReply(item.post) },
                commentsReply = true,
                modifier = Modifier.padding(top = 2.dp),
                trailing = { thread?.let { ThreadTogglePill(it) } },
                extras = RowExtras(
                    onVoters = { actions.onScreen(AppScreen.Voters(item.key.author, item.key.permlink)) },
                    onTip = { tip?.invoke(item.key) },
                    payoutPost = { item.post },
                ),
                payoutDetails = payoutDetails,
            )
            if (item.hasHiddenReplies) {
                Text(
                    ReadingUiCopy.MORE_REPLIES, color = colors.blueText, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable(role = Role.Button) { actions.onOpenThread(item.post) }.padding(vertical = 6.dp).testTag("moreReplies"),
                )
            }
        }
      }
    }
}

/** Back arrow and title, as the RN post screen's header. */
@Composable
fun TopBar(title: String, onBack: (() -> Unit)?, trailing: @Composable () -> Unit = {}) {
    val colors = LocalEcencyColors.current
    Row(
        // at least 56 dp, taller when a large font wraps the title (README "Accessibility")
        Modifier.fillMaxWidth().background(colors.background).padding(horizontal = 8.dp).heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            Box(
                Modifier.iconClickable(radius = 24.dp, onClick = onBack).padding(12.dp).testTag("back"),
            ) { Glyph(AppIcon.BACK, colors.darkGray, 24.dp, description = UiCopy.BACK) }
        }
        Text(
            title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = colors.darkGray, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 6.dp).weight(1f).semantics { heading() }.testTag("screenTitle"),
        )
        trailing()
    }
}

/**
 * The post's Listen player; the lock screen and the media notification show the shared now playing card (title,
 * author, artwork unless the PIN lock is on, README "Background media"), read again for every source it loads.
 */
private fun listenController(
    env: com.ecency.core.app.AppEnvironment,
    model: com.ecency.core.app.ListenModel,
    engine: com.ecency.app.ListenEngine,
    context: android.content.Context,
    language: String,
) = ListenController(
    model, engine, context, language, env.backgroundSessions,
    cardChanges = env.appLock.state.map { it.enabled }.distinctUntilChanged().drop(1),
) { track ->
    val card = env.nowPlaying(model)
    com.ecency.app.ListenItem(track.url, card.title, card.artist, card.artworkUrl)
}

/** Opens the tip sheet for a post or comment (RN TIPPING_DIALOG); null where tipping is not wired (tests, demos). */
val LocalTip = androidx.compose.runtime.staticCompositionLocalOf<((com.ecency.core.api.model.PostKey) -> Unit)?> { null }

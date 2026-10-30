package com.ecency.app.ui

import androidx.compose.ui.semantics.role

import androidx.compose.ui.semantics.customActions

import androidx.compose.ui.semantics.onClick

import androidx.compose.ui.semantics.contentDescription

import androidx.compose.ui.semantics.semantics

import androidx.compose.ui.semantics.Role

import com.ecency.core.app.AppIcon
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import com.ecency.core.app.MotionTokens
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.ecency.core.app.FeedModel
import com.ecency.core.app.PerfEvent
import com.ecency.core.app.PerfMarks
import com.ecency.core.app.PostCard
import com.ecency.core.app.TimeAgo
import com.ecency.core.app.VoteState
import com.ecency.core.api.model.PostKey
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.interaction.DragInteraction
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.ecency.core.app.ReadingUiCopy

/**
 * Where a feed stays when its first page replaces the skeleton. A list the user did not scroll during the load is
 * put at the top: it anchors on the skeleton's row, which goes, and would land below a header that was still empty
 * then. A list the user scrolled keeps its place: a header they scrolled part of the way keeps that offset (a tall
 * community or proposal header), and one scrolled past the header into the skeleton lets the cards take its place.
 */
@Composable
internal fun FirstPageAnchor(listState: LazyListState, hasItems: Boolean, key: Any?) {
    val loaded by rememberUpdatedState(hasItems)
    val hadItems = remember(key) { booleanArrayOf(hasItems) }
    val dragged = remember(key) { booleanArrayOf(false) }
    // the header's offset while it was the first visible row during the load; -1 once the skeleton was
    val headerOffset = remember(key) { intArrayOf(0) }
    LaunchedEffect(listState, key) {
        listState.interactionSource.interactions.collect { if (it is DragInteraction.Start && !loaded) dragged[0] = true }
    }
    LaunchedEffect(listState, key) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }.collect { (index, offset) ->
            if (!loaded) headerOffset[0] = if (index == 0) offset else -1
        }
    }
    LaunchedEffect(hasItems) {
        if (hasItems && !hadItems[0]) {
            when {
                !dragged[0] -> if (listState.firstVisibleItemIndex <= 1) listState.scrollToItem(0)
                headerOffset[0] >= 0 -> listState.scrollToItem(0, headerOffset[0])
            }
        }
        if (!hasItems && hadItems[0]) {
            // back to the skeleton (a retry after an error): a new first load, judged on its own
            dragged[0] = false
            headerOffset[0] = if (listState.firstVisibleItemIndex == 0) listState.firstVisibleItemScrollOffset else -1
        }
        hadItems[0] = hasItems
    }
}

/**
 * Whether a vote on [key] is in flight, as its own state: only the card (or comment) whose status
 * changed recomposes, not every row that reads the shared [VoteState].
 */
@Composable
fun voteInFlight(votes: StateFlow<VoteState>, key: PostKey): Boolean {
    val flow = remember(votes, key) { votes.map { it.statuses[key]?.inFlight == true }.distinctUntilChanged() }
    // the current status as the first value (read once per row and key, not on every recomposition)
    val initial = remember(votes, key) { votes.value.statuses[key]?.inFlight == true }
    return flow.collectAsState(initial).value
}

/** Test hook: called on every composition of a feed card (recomposition counts). */
object CompositionProbe {
    @Volatile var onCard: ((PostKey) -> Unit)? = null
}

/** What a feed card can lead to. */
class FeedActions(
    val onOpen: (PostCard) -> Unit,
    val onAuthor: (String) -> Unit,
    val onCommunity: (PostCard) -> Unit,
    val onVote: (PostCard) -> Unit,
    /** RN dots button: the options menu (the list resolves the card's post). */
    val onMenu: ((PostCard) -> Unit)? = null,
    val onVoters: ((PostCard) -> Unit)? = null,
    val onReblogs: ((PostCard) -> Unit)? = null,
    val onTip: ((PostCard) -> Unit)? = null,
    /** The card's post for the payout popover (the list resolves it). */
    val payoutPost: ((PostCard) -> com.ecency.core.api.model.Post?)? = null,
)

/**
 * A feed: pull to refresh, infinite scroll (the next page starts 3 cards before the end), a
 * full-screen state for the first page and a footer for later pages.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedList(
    model: FeedModel,
    listState: LazyListState,
    votes: StateFlow<VoteState>,
    nowMillis: Long,
    actions: FeedActions,
    modifier: Modifier = Modifier,
    header: (@Composable () -> Unit)? = null,
    /** A header above the list that closes as it scrolls (community Details). */
    collapse: SummaryCollapseState? = null,
    /** Pushed feeds (no tab bar below) pass `Modifier.navigationBarsPadding()` for the scroll to top. */
    scrollTopModifier: Modifier = Modifier,
) {
    val state by model.state.collectAsState()
    val colors = LocalEcencyColors.current
    LaunchedEffect(model) { model.start() }
    // the options menu needs the card's post, which this feed holds
    val feedx = LocalFeedx.current
    val cardActions = remember(actions, model, feedx) {
        if (feedx == null) actions else FeedActions(
            actions.onOpen, actions.onAuthor, actions.onCommunity, actions.onVote,
            onMenu = { card -> model.post(card.key)?.let { feedx.openMenu(it) } },
            onVoters = actions.onVoters, onReblogs = actions.onReblogs, onTip = actions.onTip,
            payoutPost = { card -> model.post(card.key) },
        )
    }

    val nearEnd by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 3
        }
    }
    // keyed on the page counter, not the item count: a page of duplicates adds no items but must
    // still let the next page start while the end of the list stays in view
    LaunchedEffect(nearEnd, state.pages) { if (nearEnd) model.loadMore() }

    val haptics = rememberHaptics()
    val motion = LocalMotion.current
    FirstPageAnchor(listState, hasItems = state.items.isNotEmpty(), key = model)
    PullToRefreshBox(
        isRefreshing = state.isRefreshing,
        onRefresh = { haptics(com.ecency.core.app.HapticEvent.REFRESH); model.refresh() },
        modifier = modifier.fillMaxSize().background(colors.background).testTag("feed"),
    ) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().then(if (collapse != null) Modifier.collapsesSummary(collapse) else Modifier)) {
            if (header != null) item(key = "header") { header() }
            when {
                state.items.isEmpty() && state.isLoading -> item(key = "loading") { FeedSkeleton() }
                state.items.isEmpty() && state.error != null -> item(key = "error") {
                    ErrorBlock(state.error!!, Modifier.padding(top = 48.dp), com.ecency.core.esie.EsiePlacement.ERROR, model::retry)
                }
                state.items.isEmpty() && state.loaded -> item(key = "empty") { EsieMessageBlock(com.ecency.core.esie.EsiePlacement.EMPTY, ReadingUiCopy.NOTHING_HERE_YET, Modifier.padding(top = 48.dp)) }
            }
            items(state.items, key = { it.key.toString() }, contentType = { "card" }) { card ->
                // cards fade in as they replace the skeleton or a page lands; opacity only, no placement animation
                val fadeIn = Modifier.animateItem(fadeInSpec = motion.fade(MotionTokens.MEDIUM_MS), placementSpec = null, fadeOutSpec = null)
                PostCardView(
                    card = card,
                    nowMillis = nowMillis,
                    voteInFlight = voteInFlight(votes, card.key),
                    actions = cardActions,
                    modifier = if (card === state.items.first()) {
                        fadeIn.onFirstDraw { PerfMarks.markOnce(PerfEvent.FEED_FIRST_CONTENT) }
                    } else {
                        fadeIn
                    },
                )
            }
            if (state.items.isNotEmpty()) {
                item(key = "footer") {
                    when {
                        state.isLoadingMore -> LoadingBlock()
                        state.error != null -> ErrorBlock(state.error!!, onRetry = model::retry)
                        state.endReached -> MessageBlock(ReadingUiCopy.NO_MORE_POSTS)
                        else -> Spacer(Modifier.height(72.dp))
                    }
                }
            }
        }
        // RN postsTabContent's ScrollTopPopup (feed, tag, community lists)
        ScrollTopPopup(listState, modifier = scrollTopModifier, onTap = { collapse?.expand() })
    }
}

private val Color_White = androidx.compose.ui.graphics.Color.White

/** The RN post card: header, image, title, summary, actions. */
@Composable
fun PostCardView(card: PostCard, nowMillis: Long, voteInFlight: Boolean, actions: FeedActions, modifier: Modifier = Modifier) {
    val colors = LocalEcencyColors.current
    SideEffect { CompositionProbe.onCard?.invoke(card.key) }
    // RN useMutedReveal: dimmed with a "Tap to reveal" hint until the reader reveals it (this session)
    val reveals = LocalFeedx.current?.env?.mutedReveals
    var revealed by androidx.compose.runtime.remember(card.key) { androidx.compose.runtime.mutableStateOf(reveals?.isRevealed(card.key) == true) }
    val dimmed = card.isMuted && !revealed
    // one TalkBack stop per card: the summary, a double tap opens the post, the card's taps are custom actions.
    // Built once per card, state and language (not on every recomposition); the summary is read when asked for.
    val payoutDetails = rememberPayoutDetails()
    val language = LocalAppLanguage.current
    val a11yActions = androidx.compose.runtime.remember(card, dimmed, actions, language) {
        buildList {
            if (dimmed) add(a11yAction(com.ecency.core.app.MutedReveals.REVEAL) { reveals?.reveal(card.key); revealed = true })
            add(a11yAction(com.ecency.core.app.UiCopy.UPVOTE) { actions.onVote(card) })
            add(a11yAction(com.ecency.core.l10n.L.post.a11yCommentsHint) { actions.onOpen(card) })
            add(a11yAction(com.ecency.core.app.UiCopy.profileOf(card.author)) { actions.onAuthor(card.author) })
            if ((card.communityTitle ?: card.category).isNotEmpty()) {
                add(a11yAction(ReadingUiCopy.inTopic(card.communityTitle ?: card.category)) { actions.onCommunity(card) })
            }
            actions.onVoters?.let { add(a11yAction(com.ecency.core.l10n.L.post.a11yVotersHint) { it(card) }) }
            actions.onReblogs?.let { add(a11yAction(com.ecency.core.l10n.L.post.a11yReblogsHint) { it(card) }) }
            actions.onTip?.let { add(a11yAction(com.ecency.core.app.UiCopy.SEND_TIP) { it(card) }) }
            actions.payoutPost?.let { add(a11yAction(com.ecency.core.app.PayoutCopy.DETAILS) { payoutDetails.value = it(card) }) }
            actions.onMenu?.let { add(a11yAction(ReadingUiCopy.POST_OPTIONS) { it(card) }) }
        }
    }
    Column(
        modifier.fillMaxWidth().background(colors.background)
            .semantics(mergeDescendants = true) {
                contentDescription = postCardSummary(card, nowMillis)
                role = Role.Button
                onClick { actions.onOpen(card); true }
                customActions = a11yActions
            }
            .testTag("card:${card.key}"),
    ) {
      androidx.compose.runtime.CompositionLocalProvider(LocalMergedRow provides true) {
        if (card.rebloggedBy.isNotEmpty()) {
            Text(
                ReadingUiCopy.rebloggedBy(card.rebloggedBy.first()),
                fontSize = 11.sp, fontWeight = FontWeight.Bold, color = colors.darkGray,
                modifier = Modifier.padding(start = 12.dp, top = 8.dp),
            )
        }
        card.crossPostLabel?.let {
            Text(it, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = colors.darkGray, modifier = Modifier.padding(start = 12.dp, top = 8.dp).testTag("crossPost"))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            PostHeader(
                card = card,
                nowMillis = nowMillis,
                avatarSize = 50,
                onAuthor = { actions.onAuthor(card.author) },
                onCommunity = { actions.onCommunity(card) },
                modifier = Modifier.weight(1f).padding(start = 12.dp, end = 4.dp, top = 14.dp, bottom = 12.dp),
            )
            if (card.hasPoll) Glyph(AppIcon.CHART_BAR, colors.darkGray, 16.dp, Modifier.padding(end = 4.dp))
            actions.onMenu?.let { onMenu -> OptionsButton(Modifier.padding(end = 6.dp)) { onMenu(card) } }
        }
        if (dimmed) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(8.dp)).background(colors.lightBackground)
                    .clickable(role = Role.Button) { reveals?.reveal(card.key); revealed = true }.then(HiddenFromA11y).padding(10.dp).testTag("mutedHint"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("!", fontWeight = FontWeight.Bold, color = Color_White, modifier = Modifier.clip(RoundedCornerShape(50)).background(colors.darkGray).padding(horizontal = 7.dp))
                Text(
                    "${card.mutedText ?: ""} ${com.ecency.core.app.MutedReveals.REVEAL}", fontSize = 13.sp, color = colors.darkGray,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        Column(Modifier.alpha(if (dimmed) 0.3f else 1f).clickable(role = Role.Button) { actions.onOpen(card) }.then(HiddenFromA11y).testTag("open:${card.key}")) {
            card.featuredText?.let {
                Text(it, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = colors.darkGray, modifier = Modifier.padding(start = 17.dp, bottom = 6.dp).testTag("featured"))
            }
            val imageReveals = LocalFeedx.current?.env?.imageReveals
            var imageRevealed by androidx.compose.runtime.remember(card.image) { androidx.compose.runtime.mutableStateOf(false) }
            if (card.image != null && !imageRevealed && imageReveals?.hides(card, LocalShowImages.current) == true) {
                // RN HiddenImagePlaceholder: same frame, nothing fetched until tapped (that one image, this session)
                HiddenImagePlaceholder(Modifier.padding(horizontal = 9.dp).fillMaxWidth().aspectRatio(16f / 9f).testTag("hiddenImage:${card.key}")) {
                    card.image?.let { imageReveals.reveal(it) }
                    imageRevealed = true
                }
            } else if (card.image != null) {
                AsyncImage(
                    model = if (card.isNsfw) com.ecency.core.app.NsfwMode.NSFW_IMAGE else card.image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.padding(horizontal = 9.dp).fillMaxWidth().aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(8.dp)).background(colors.lightBackground),
                )
            }
            Text(
                card.title,
                fontSize = 18.sp, fontWeight = FontWeight.Bold, color = colors.text,
                modifier = Modifier.padding(start = 17.dp, end = 17.dp, top = 12.dp, bottom = 10.dp).testTag("title"),
            )
            if (card.summary.isNotEmpty()) {
                Text(
                    card.summary,
                    fontSize = 16.sp, lineHeight = 22.sp, color = colors.darkGray,
                    maxLines = 5, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 17.dp),
                )
            }
        }
        ActionsRow(
            card = card,
            voteInFlight = voteInFlight,
            onVote = { actions.onVote(card) },
            onComments = { actions.onOpen(card) },
            modifier = Modifier.padding(start = 8.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
            extras = RowExtras(
                onVoters = actions.onVoters?.let { { it(card) } },
                onReblogs = actions.onReblogs?.let { { it(card) } },
                onTip = actions.onTip?.let { { it(card) } },
                payoutPost = actions.payoutPost?.let { { it(card) } },
            ),
            payoutDetails = payoutDetails,
        )
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.lightBackground))
      }
    }
}

/** Avatar, author, "in community", age; shared by cards and the post screen. */
@Composable
fun PostHeader(
    card: PostCard,
    nowMillis: Long,
    avatarSize: Int,
    onAuthor: () -> Unit,
    onCommunity: (() -> Unit)?,
    modifier: Modifier = Modifier,
    /** Post screen: name and age on one line, no community line (as RN). */
    inline: Boolean = false,
) {
    val colors = LocalEcencyColors.current
    val merged = LocalMergedRow.current
    if (inline) {
        Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Avatar(card.avatarUrl, avatarSize.dp, Modifier.clip(RoundedCornerShape(50)).avatarButton(card.author, onAuthor).inMergedRow(merged).testTag("avatar"))
            Text(
                card.author, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = colors.text,
                modifier = Modifier.padding(start = 10.dp).clickable(onClick = onAuthor, role = Role.Button).inMergedRow(merged).testTag("author"),
            )
            Text(
                " " + TimeAgo.short(card.createdEpochMillis, nowMillis),
                fontSize = 14.sp, fontWeight = FontWeight.Light, color = colors.darkGray, modifier = Modifier.testTag("age"),
            )
            // RN postHeaderDescription: the source badge, then the AI disclosure, after the date
            if (card.isFromEcency) EcencySourceBadge(Modifier.padding(start = 6.dp))
            if (card.aiDisclosed) AiDisclosedBadge(Modifier.padding(start = 6.dp))
        }
        return
    }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Avatar(
            card.avatarUrl, avatarSize.dp,
            modifier = Modifier.clip(RoundedCornerShape(50)).avatarButton(card.author, onAuthor).inMergedRow(merged).testTag("avatar"),
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    card.author, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = colors.text,
                    modifier = Modifier.clickable(onClick = onAuthor, role = Role.Button).inMergedRow(merged).testTag("author"),
                )
                if (card.isPinned || card.isPinnedBlog) Glyph(AppIcon.PIN, colors.redText, 16.dp, Modifier.padding(start = 8.dp).testTag("pinned"), filled = true)
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 3.dp)) {
                val topic = card.communityTitle ?: card.category
                if (topic.isNotEmpty()) {
                    Text(
                        ReadingUiCopy.inTopic(topic) + " ",
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.darkGray, maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                            .then(if (onCommunity != null) Modifier.clickable(onClick = onCommunity, role = Role.Button).inMergedRow(merged) else Modifier),
                    )
                }
                Text(
                    TimeAgo.short(card.createdEpochMillis, nowMillis),
                    fontSize = 14.sp, fontWeight = FontWeight.Light, color = colors.darkGray,
                    modifier = Modifier.testTag("age"),
                )
                if (card.isFromEcency) EcencySourceBadge(Modifier.padding(start = 6.dp))
                if (card.aiDisclosed) AiDisclosedBadge(Modifier.padding(start = 6.dp))
            }
        }
        Spacer(Modifier.size(4.dp))
    }
}

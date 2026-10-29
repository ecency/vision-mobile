package com.ecency.app.ui

import androidx.compose.ui.semantics.LiveRegionMode

import androidx.compose.ui.semantics.liveRegion

import androidx.compose.ui.semantics.customActions

import androidx.compose.ui.semantics.heading

import androidx.compose.ui.semantics.selected


import androidx.compose.ui.semantics.Role

import com.ecency.core.app.AppIcon
import com.ecency.app.render.ProvideSpeakPlayers
import com.ecency.app.render.SpeakPlayers
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.ecency.app.render.PostActions
import com.ecency.app.render.PostBody
import com.ecency.core.api.model.Post
import com.ecency.core.api.model.PostKey
import com.ecency.core.api.repository.FeedSource
import com.ecency.core.api.waves.Waves
import com.ecency.core.app.AppEnvironment
import com.ecency.core.app.AppEvent
import com.ecency.core.app.AppScreen
import com.ecency.core.app.LinkRouter
import com.ecency.core.app.MutedReveals
import com.ecency.core.app.PostMenuContext
import com.ecency.core.app.PostMenuEffect
import com.ecency.core.app.Route
import com.ecency.core.app.ShortItem
import com.ecency.core.app.ShortPlayback
import com.ecency.core.app.ShortsModel
import com.ecency.core.app.ShortsState
import com.ecency.core.app.TimeAgo
import com.ecency.core.app.VoteState
import com.ecency.core.app.WaveComposeModel
import com.ecency.core.app.WaveImageSource
import com.ecency.core.app.WaveItem
import com.ecency.core.app.WavesFeedModel
import com.ecency.core.app.WavesTab
import com.ecency.core.app.WavesTabTap
import com.ecency.core.app.WavesTabsModel
import com.ecency.core.app.WavesTabsState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import com.ecency.core.app.ReadingUiCopy
import com.ecency.core.app.UiCopy

/** What the Waves tab needs from the root besides the feed actions. */
class WavesUi(
    val body: PostActions,
    val login: () -> Unit,
    /** Runs the block signed in, else opens the login screen. */
    val requireLogin: (() -> Unit) -> Unit,
    val push: (AppScreen) -> Unit,
)

/** Plays one reel's HLS stream. The app uses Media3; tests swap in a recorder. */
fun interface ReelPlayer {
    @Composable
    fun Play(url: String, muted: Boolean, onError: () -> Unit, modifier: Modifier)
}

val LocalReelPlayer = staticCompositionLocalOf<ReelPlayer> { ExoReelPlayer }

/**
 * RN waves screen: the pill tab bar with its "+" picker, the selected tab's list (or the Shorts reels), the
 * hashtag filter chip, the scroll-to-top button and the compose button. The models live in the tab's root entry,
 * so lists keep their place while a wave's thread is open.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WavesTabScreen(env: AppEnvironment, entry: StackEntry, username: String?, now: Long, actions: FeedActions, ui: WavesUi) {
    val colors = LocalEcencyColors.current
    val model = entry.get("waves:tabs") { env.wavesTabsModel() }
    val tabs by model.state.collectAsState()
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var picker by remember { mutableStateOf(false) }
    var composer by remember { mutableStateOf<WaveComposeModel?>(null) }
    val user by rememberUpdatedState(username)
    val feedx = LocalFeedx.current

    fun listState(key: String) = entry.get("waves:list:$key") { LazyListState() }
    fun compose(text: String?) = ui.requireLogin {
        composer = env.waves.composer().also { c ->
            text?.takeIf { it.isNotEmpty() }?.let(c::prefill)
            // a share into the app: its first image goes with the text (the composer holds one image)
            env.share.takeImagesForWave(c.owner).firstOrNull()?.let { android.net.Uri.parse(it) }
                ?.takeIf { com.ecency.app.ShareIntents.acceptable(context, it) }
                ?.let { uri ->
                    val appContext = context.applicationContext
                    scope.launch {
                        val image = com.ecency.app.EditorImages.prepare(appContext, uri, 0)
                        if (image == null) env.ui.toast(com.ecency.core.app.ShareCopy.INVALID_IMAGE) else c.attachPrepared(image)
                    }
                }
        }
    }

    // RN: ecency.com/waves?text=... opens the composer with the text, once. Signed out, the text waits through the
    // login this sends the reader to; a draft already in the composer is kept (the prefill is ignored).
    fun openShared() {
        if (env.waves.pendingComposeText == null) return
        if (user == null) ui.login() else env.waves.takeComposeText()?.let { compose(it) }
    }
    // a composer belongs to the account that opened it: on any account change the sheet goes
    LaunchedEffect(username) {
        composer = null
        if (username != null) openShared()
    }
    LaunchedEffect(env) {
        openShared()
        env.events.collect { if (it is AppEvent.WaveComposeRequested) openShared() }
    }

    // a hashtag inside a wave filters For you (RN onTagPress); every other link goes where links go
    val body = remember(ui.body, model) {
        PostActions(
            onLink = { target ->
                val route = LinkRouter.route(target)
                val source = (route as? Route.Feed)?.source as? FeedSource.Ranked
                if (source != null && source.tag.isNotEmpty() && !source.tag.startsWith("hive-")) model.filterByTag(source.tag) else ui.body.onLink(target)
            },
            onImage = ui.body.onImage,
            onParagraphLongPress = ui.body.onParagraphLongPress,
            avatarUrl = ui.body.avatarUrl,
            showImages = ui.body.showImages,
        )
    }

    // RN: browsing waves is reading too, so it records the daily check-in (at most once per 15 minutes and account)
    // Keyed on the lock too: a check-in refused while the lock hid the app is recorded once it is unlocked.
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val lockState by env.appLock.state.collectAsState()
    LaunchedEffect(username, lifecycleState.isAtLeast(Lifecycle.State.RESUMED), lockState.hidesContent) {
        if (username != null && !lockState.hidesContent && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)) {
            env.checkIn.record()
            env.wavesOnboarding.start()
            env.wavesOnboarding.refresh()
        }
    }

    Column(Modifier.fillMaxSize().background(colors.background).testTag("wavesScreen")) {
        // RN WavesOnboardingChecklist, above the tabs; it shows nothing for established accounts
        if (username != null) {
            WavesOnboardingCard(env, username, onWave = { compose(null) }, onCheckIn = { ui.push(AppScreen.of(com.ecency.core.app.RouteName.PERKS)) })
        }
        WavesTabBar(
            tabs,
            onTab = { key ->
                when (model.select(key)) {
                    WavesTabTap.NEEDS_LOGIN -> ui.login()
                    WavesTabTap.SCROLL_TO_TOP -> scope.launch { listState(tabs.feedKey).scrollToItem(0) }
                    WavesTabTap.SELECTED -> Unit
                }
            },
            onAdd = {
                picker = true
                model.loadTrending()
            },
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (tabs.showsShorts) {
                ShortsReels(env, model.shorts(), actions, onReply = { post -> ui.requireLogin { feedx?.handle(PostMenuEffect.Reply(post), post.key) } })
            } else {
                Column(Modifier.fillMaxSize()) {
                    tabs.activeTag?.takeIf { tabs.selected == WavesTab.FOR_YOU }?.let { tag ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                            Row(
                                Modifier.clip(RoundedCornerShape(16.dp)).background(colors.blue).clickable(role = Role.Button) { model.clearTag() }
                                    .padding(horizontal = 12.dp, vertical = 6.dp).testTag("waves:tagChip"),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("#$tag", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Spacer(Modifier.width(6.dp))
                                Glyph(AppIcon.CLOSE, Color.White, 16.dp, description = ReadingUiCopy.CLEAR_TAG_FILTER)
                            }
                        }
                    }
                    val key = tabs.feedKey
                    val feed = remember(key, tabs.activeTag, username) { model.feed(key) }
                    if (feed != null) {
                        androidx.compose.runtime.key(key, tabs.activeTag) {
                            WavesList(env, feed, listState("$key:${tabs.activeTag.orEmpty()}"), env.votes.state, now, actions, body, ui, onLogin = ui.login, speakPlayers = entry.get("speakPlayers") { SpeakPlayers() })
                        }
                    }
                }
            }
            if (tabs.showsCompose) {
                CreateFab(onClick = { compose(null) }, modifier = Modifier.align(Alignment.BottomEnd))
            }
        }
    }

    if (picker) WavesPickerSheet(model, tabs, onDismiss = { picker = false })
    composer?.let { c ->
        // never draw a composer of another account, even for the frame before the effect above clears it
        if (c.owner == user) WaveComposerSheet(env, c, onDismiss = { composer = null })
    }
}

/** RN wavesTabBar: scrollable uppercase pills, the active one blue, and a trailing "+". */
@Composable
private fun WavesTabBar(tabs: WavesTabsState, onTab: (String) -> Unit, onAdd: () -> Unit) {
    val colors = LocalEcencyColors.current
    Row(Modifier.fillMaxWidth().background(colors.grayBackground).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            horizontalArrangement = if (tabs.tabs.size <= 2) Arrangement.SpaceEvenly else Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.tabs.forEach { tab ->
                val selected = tab.key == tabs.selected
                Text(
                    tab.title.uppercase(),
                    color = if (selected) Color.White else colors.darkGray,
                    fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1,
                    modifier = Modifier.clip(RoundedCornerShape(20.dp))
                        .background(if (selected) colors.blue else Color.Transparent)
                        .clickable(role = Role.Tab) { onTab(tab.key) }
                        .padding(horizontal = 20.dp, vertical = 4.dp)
                        .semantics { contentDescription = tab.title; this.selected = selected }
                        .testTag("waves:tab:${tab.key}"),
                )
            }
        }
        Box(
            Modifier.width(44.dp).height(32.dp).clip(CircleShape).clickable(onClick = onAdd, role = Role.Button).testTag("waves:add"),
            contentAlignment = Alignment.Center,
        ) { Glyph(AppIcon.ADD, colors.icon, 26.dp, description = ReadingUiCopy.ADD_TAG_FEED) }
    }
}

/** One waves list: pull to refresh, infinite scroll, RN's empty state, the scroll-to-top button. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WavesList(
    env: AppEnvironment,
    model: WavesFeedModel,
    listState: LazyListState,
    votes: StateFlow<VoteState>,
    nowMillis: Long,
    actions: FeedActions,
    body: PostActions,
    ui: WavesUi,
    onLogin: () -> Unit,
    modifier: Modifier = Modifier,
    /** The screen's voice players, from its stack entry so a paused clip keeps its place. */
    speakPlayers: SpeakPlayers = remember { SpeakPlayers() },
) {
    val state by model.state.collectAsState()
    val colors = LocalEcencyColors.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(model) { model.start() }
    // RN refetches every minute while the list is mounted; here while it is on screen
    DisposableEffect(model) {
        model.setActive(true)
        onDispose { model.setActive(false) }
    }
    val nearEnd by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 3
        }
    }
    LaunchedEffect(nearEnd, state.pages) { if (nearEnd) model.loadMore() }
    // a wave just published from here goes on top: show it (the list would otherwise keep its place below it)
    val justPosted = state.items.firstOrNull()?.takeIf { it.isLocal }?.key
    LaunchedEffect(justPosted) { if (justPosted != null) listState.scrollToItem(0) }
    // RN ScrollTopPopup: far down the list and scrolling back up
    val showTop by remember(listState) { derivedStateOf { DebugLaunch.demoScrollTop || (listState.firstVisibleItemIndex >= SCROLL_TOP_AFTER && listState.lastScrolledBackward) } }

    Box(modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = model::refresh,
            modifier = Modifier.fillMaxSize().background(colors.background).testTag("wavesList"),
        ) {
            BoxWithConstraints(Modifier.fillMaxSize()) { ProvideSpeakPlayers(speakPlayers) {
                val contentWidth = maxWidth - 32.dp
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    when {
                        state.needsLogin -> item(key = "login") { MessageBlock(ReadingUiCopy.LOGIN_TO_SEE, Modifier.padding(top = 48.dp), UiCopy.LOGIN, onLogin) }
                        state.items.isEmpty() && state.isLoading -> item(key = "loading") { LoadingBlock(Modifier.padding(top = 48.dp)) }
                        state.items.isEmpty() && state.error != null -> item(key = "error") { ErrorBlock(state.error!!, Modifier.padding(top = 48.dp), com.ecency.core.esie.EsiePlacement.ERROR, model::retry) }
                        state.items.isEmpty() && state.loaded -> item(key = "empty") { EsieMessageBlock(com.ecency.core.esie.EsiePlacement.EMPTY, UiCopy.NOTHING_HERE, Modifier.padding(top = 48.dp)) }
                    }
                    items(state.items, key = { it.key.toString() }, contentType = { "wave" }) { item ->
                        WaveView(env, item, nowMillis, contentWidth, voteInFlight(votes, item.key), actions, body, ui)
                    }
                    if (state.items.isNotEmpty()) {
                        item(key = "footer") {
                            Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                                when {
                                    state.isLoadingMore -> CircularProgressIndicator(Modifier.size(24.dp), color = colors.blueText, strokeWidth = 2.dp)
                                    state.error != null -> TextButton(onClick = model::retry) { Text(UiCopy.TRY_AGAIN, color = colors.blueText) }
                                }
                            }
                        }
                    }
                }
            }
}
        }
        // the FAB's twin at the bottom start, opposite the compose FAB
        ScrollTopFab(showTop, UiCopy.SCROLL_TO_TOP, "waves:scrollTop") { scope.launch { listState.animateScrollToItem(0) } }
    }
}

private const val SCROLL_TOP_AFTER = 15

/** One wave: RN's comment view at level 1 with the wave extras (promoted, Ecency badge, reply count). */
@Composable
internal fun WaveView(
    env: AppEnvironment,
    item: WaveItem,
    nowMillis: Long,
    contentWidth: Dp,
    voteInFlight: Boolean,
    actions: FeedActions,
    body: PostActions,
    ui: WavesUi,
) {
    val feedx = LocalFeedx.current
    val colors = LocalEcencyColors.current
    val card = item.card
    var revealed by remember(item.key) { mutableStateOf(env.mutedReveals.isRevealed(item.key)) }
    // TalkBack: the header is one stop (author, age, votes) carrying the wave's taps as custom actions; the text after
    // it opens the thread and keeps its links; the small taps are hidden from TalkBack (LocalMergedRow)
    val payoutDetails = rememberPayoutDetails()
    val a11yActions = remember(item, actions, feedx, LocalAppLanguage.current) { buildList {
        add(a11yAction(UiCopy.UPVOTE) { actions.onVote(card) })
        add(a11yAction(com.ecency.core.l10n.L.post.a11yReplyHint) { ui.requireLogin { feedx?.handle(PostMenuEffect.Reply(item.post), item.key) } })
        add(a11yAction(com.ecency.core.l10n.L.post.a11yCommentsHint) { actions.onOpen(card) })
        add(a11yAction(UiCopy.profileOf(card.author)) { actions.onAuthor(card.author) })
        add(a11yAction(com.ecency.core.l10n.L.post.a11yVotersHint) { ui.push(AppScreen.Voters(item.key.author, item.key.permlink)) })
        actions.onTip?.let { add(a11yAction(UiCopy.SEND_TIP) { it(card) }) }
        if (feedx != null) add(a11yAction(ReadingUiCopy.POST_OPTIONS) { feedx.openMenu(item.post, PostMenuContext(translate = true), item.key) })
        add(a11yAction(com.ecency.core.app.PayoutCopy.DETAILS) { payoutDetails.value = item.post })
    } }
    fun header() = buildList {
        add(card.author)
        add(TimeAgo.short(card.createdEpochMillis, nowMillis))
        if (item.isFromEcency) add(com.ecency.core.app.PostBadges.SOURCE_ECENCY)
        if (item.isPromoted) add(ReadingUiCopy.PROMOTED)
        add(com.ecency.core.l10n.L.post.a11yVotes(count = card.votes))
        add(com.ecency.core.l10n.L.post.a11yComments(count = card.comments))
        add(card.payoutText)
    }.joinToString(", ")
    Column(Modifier.fillMaxWidth().testTag("wave:${item.key}")) {
      androidx.compose.runtime.CompositionLocalProvider(LocalMergedRow provides true) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
            Avatar(card.avatarUrl, 36.dp, Modifier.clip(CircleShape).avatarButton(card.author) { actions.onAuthor(card.author) }.then(HiddenFromA11y).testTag("wave:avatar"))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Row(
                    Modifier.semantics(mergeDescendants = true) { contentDescription = header(); customActions = a11yActions }.testTag("wave:header"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        card.author, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false).clickable(role = Role.Button) { actions.onAuthor(card.author) }.then(HiddenFromA11y),
                    )
                    Text(" " + TimeAgo.short(card.createdEpochMillis, nowMillis), fontSize = 14.sp, fontWeight = FontWeight.Light, color = colors.darkGray)
                    if (item.isFromEcency) EcencySourceBadge(Modifier.padding(start = 6.dp), tag = "wave:ecency")
                    if (item.isLocal) Text("  " + ReadingUiCopy.SENDING_TO_CHAIN, fontSize = 12.sp, color = colors.darkGray, modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }.testTag("wave:local"))
                    Spacer(Modifier.weight(1f))
                    if (item.isPromoted) Text(ReadingUiCopy.PROMOTED, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = colors.darkGray, modifier = Modifier.testTag("wave:promoted"))
                    if (feedx != null) {
                        // RN PostOptionsModal isWave: translate offered; a wave is a comment, so no pin to blog and the canonical link
                        OptionsButton { feedx.openMenu(item.post, PostMenuContext(translate = true), item.key) }
                    }
                }
                val bodyWidth = contentWidth - 44.dp
                // read again on every composition: a reveal on the thread screen uncovers the row too
                val covered = card.isNsfw && !revealed && !env.mutedReveals.isRevealed(item.key)
                if (covered) {
                    // RN #3586: the text stays readable, the media is covered until this wave is revealed
                    Text(
                        UiCopy.NSFW_TAP_TO_REVEAL, fontSize = 13.sp, color = colors.darkGray,
                        modifier = Modifier.clickable(role = Role.Button) {
                            env.mutedReveals.reveal(item.key)
                            revealed = true
                        }.padding(vertical = 10.dp).testTag("wave:nsfw"),
                    )
                }
                // the text opens the thread; links, mentions, hashtags and images keep their own taps
                val open = { actions.onOpen(card) }
                val waveBody = remember(body, item.key, covered) {
                    PostActions(body.onLink, body.onImage, body.onParagraphLongPress, open, body.avatarUrl, body.showImages && !covered)
                }
                androidx.compose.runtime.CompositionLocalProvider(LocalMergedRow provides false) {
                    Box(Modifier.clickable(onClick = open, role = Role.Button).testTag("wave:body")) {
                        PostBody(item.blocks, bodyWidth, waveBody, Modifier.padding(top = 2.dp))
                    }
                }
                ActionsRow(
                    card = card,
                    voteInFlight = voteInFlight,
                    onVote = { actions.onVote(card) },
                    onComments = { actions.onOpen(card) },
                    modifier = Modifier.padding(top = 2.dp),
                    trailing = {
                        Text(
                            ReadingUiCopy.REPLY, color = colors.darkGray, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) {
                                ui.requireLogin { feedx?.handle(PostMenuEffect.Reply(item.post), item.key) }
                            }.then(HiddenFromA11y).padding(horizontal = 8.dp, vertical = 6.dp).testTag("wave:reply"),
                        )
                    },
                    extras = RowExtras(
                        onVoters = { ui.push(AppScreen.Voters(item.key.author, item.key.permlink)) },
                        onTip = { actions.onTip?.invoke(card) },
                        payoutPost = { item.post },
                    ),
                    payoutDetails = payoutDetails,
                )
            }
        }
      }
        Box(Modifier.fillMaxWidth().padding(top = 8.dp).height(1.dp).background(colors.border))
    }
}

/** RN wavesTagPickerModal: "Custom feeds". */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun WavesPickerSheet(model: WavesTabsModel, tabs: WavesTabsState, onDismiss: () -> Unit) {
    val colors = LocalEcencyColors.current
    var input by remember { mutableStateOf("") }
    val submit = {
        model.addTag(input)
        input = ""
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.background) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).imePadding().navigationBarsPadding().padding(bottom = 16.dp).testTag("waves:picker")) {
            Text(ReadingUiCopy.CUSTOM_FEEDS, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = colors.text, modifier = Modifier.semantics { heading() })
            Text(ReadingUiCopy.CUSTOM_FEEDS_SUBTITLE, fontSize = 14.sp, color = colors.darkGray, modifier = Modifier.padding(top = 4.dp))
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("#", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = colors.darkGray, modifier = Modifier.padding(end = 8.dp))
                OutlinedTextField(
                    value = input, onValueChange = { input = it }, singleLine = true,
                    placeholder = { Text(ReadingUiCopy.ADD_A_TAG, color = colors.darkGray) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = colors.text, unfocusedTextColor = colors.text, focusedBorderColor = colors.blueText, unfocusedBorderColor = colors.icon),
                    modifier = Modifier.weight(1f).testTag("waves:tagInput"),
                )
                val canAdd = model.canAdd(input)
                Box(
                    Modifier.padding(start = 8.dp).size(40.dp).clip(CircleShape).background(if (canAdd) colors.blue else colors.icon)
                        .clickable(enabled = canAdd, role = Role.Button) { submit() }.testTag("waves:tagAdd"),
                    contentAlignment = Alignment.Center,
                ) { Glyph(AppIcon.ADD, Color.White, 22.dp, description = ReadingUiCopy.ADD_TAG) }
            }
            PickerSection(ReadingUiCopy.SOURCES)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Waves.SOURCE_OPTIONS.forEach { source ->
                    PickerChip(source.label, active = source.host in tabs.pinnedContainers, tag = "waves:source:${source.host}") { model.toggleContainer(source.host) }
                }
            }
            if (tabs.pinnedTags.isNotEmpty()) {
                PickerSection(ReadingUiCopy.YOUR_FEEDS)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    tabs.pinnedTags.forEach { tag -> PickerChip("#$tag", active = true, tag = "waves:pinned:$tag") { model.removeTag(tag) } }
                }
            }
            if (tabs.trendingTags.isNotEmpty()) {
                PickerSection(ReadingUiCopy.TRENDING_TAGS)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    tabs.trendingTags.forEach { tag -> PickerChip("#$tag", active = false, tag = "waves:trending:$tag") { model.addTag(tag) } }
                }
            }
        }
    }
}

@Composable
private fun PickerSection(title: String) =
    Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = LocalEcencyColors.current.darkGray, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))

@Composable
private fun PickerChip(label: String, active: Boolean, tag: String, onClick: () -> Unit) {
    val colors = LocalEcencyColors.current
    Row(
        Modifier.clip(RoundedCornerShape(16.dp)).background(if (active) colors.blue else colors.grayBackground).clickable(onClick = onClick, role = Role.Button)
            .padding(horizontal = 12.dp, vertical = 6.dp).semantics { contentDescription = if (active) ReadingUiCopy.feedPinned(label) else label }.testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = if (active) Color.White else colors.text, fontSize = 14.sp)
        Spacer(Modifier.width(4.dp))
        Glyph(if (active) AppIcon.CLOSE else AppIcon.ADD, if (active) Color.White else colors.darkGray, 15.dp)
    }
}

/**
 * The composer's image pickers (the shared media picker: library or files, and the camera through the FileProvider),
 * one image a wave as RN. The picked file is prepared like an editor upload (decoded subsampled and written again
 * without its metadata, off the main thread) and handed to the shared attacher, which uploads it for the
 * composer's owner. A cancelled picker, an unreadable file or leaving the sheet all end the attach.
 */
@Composable
private fun WaveImagePickers(env: AppEnvironment, attacher: com.ecency.core.app.WaveUploadAttacher) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun done(uri: android.net.Uri?) {
        if (uri == null) {
            attacher.picked(null)
            return
        }
        val appContext = context.applicationContext
        scope.launch {
            val image = com.ecency.app.EditorImages.prepare(appContext, uri, 0)
            if (image == null) env.ui.toast(UiCopy.INVALID_IMAGE)
            attacher.picked(image)
        }
    }
    // the one media picking layer (MediaPicker.kt): an unsupported file ends the attach with its message
    val gallery = com.ecency.app.rememberMediaPicker(com.ecency.core.app.MediaSurface.WAVE, onRejected = { env.ui.toast(it) }) { done(it.firstOrNull()) }
    // saved: the camera app often pushes this process out of memory
    var cameraUri by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<android.net.Uri?>(null) }
    val camera = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.TakePicture()) { ok ->
        val uri = cameraUri
        cameraUri = null
        if (ok && uri != null) {
            done(uri)
        } else {
            uri?.let { com.ecency.app.EditorImages.deleteIfCapture(context, it) } // cancelled: no temp file stays behind
            attacher.picked(null)
        }
    }
    // the camera app needs the camera permission the QR scanner declared; refused, the attach ends
    val cameraGate = rememberCameraGate { attacher.picked(null) }
    androidx.compose.runtime.DisposableEffect(attacher) {
        attacher.onPick = { source ->
            val opened = runCatching {
                when (source) {
                    WaveImageSource.GALLERY -> gallery.pickFromLibrary()
                    WaveImageSource.FILES -> gallery.pickFromFiles()
                    WaveImageSource.CAMERA -> cameraGate {
                        runCatching {
                            val uri = com.ecency.app.EditorImages.newCameraUri(context)
                            cameraUri = uri
                            camera.launch(uri)
                        }.onFailure { attacher.picked(null) }
                    }
                    // RN _handlePasteImageBtn: the clipboard's image, prepared like a picked one
                    WaveImageSource.CLIPBOARD -> {
                        val uri = com.ecency.app.EditorImages.clipboardImage(context)
                        if (uri == null) env.ui.toast(com.ecency.core.app.WaveComposeCopy.CLIPBOARD_EMPTY)
                        done(uri)
                    }
                }
            }
            // no picker or camera on this device: the attach ends instead of waiting forever
            if (opened.isFailure) attacher.picked(null)
        }
        onDispose {
            attacher.onPick = null
            attacher.picked(null)
        }
    }
}

/**
 * RN quick post modal in wave mode: "Publishing in Waves", the counter, the close button, avatar and name, the
 * text, the short content hint, the attached image and PUBLISH. While a publish runs the sheet stays.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WaveComposerSheet(env: AppEnvironment, model: WaveComposeModel, onDismiss: () -> Unit) {
    val colors = LocalEcencyColors.current
    val state by model.state.collectAsState()
    val busy by rememberUpdatedState(state.isSending)
    var pollSheet by remember { mutableStateOf(false) }
    LaunchedEffect(state.sent) { if (state.sent != null) onDismiss() }
    // RN autoFocus: the keyboard is up when the composer opens
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(focus) { runCatching { focus.requestFocus() } }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { it != SheetValue.Hidden || !busy })
    (env.waves.attacher as? com.ecency.core.app.WaveUploadAttacher)?.let { WaveImagePickers(env, it) }
    // RN SpeakUploaderModal isShort: the editor's upload sheet over the composer's own upload model
    var showVideo by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    // RN quick post: dictation and AI assist, the editor's sheets over this composer
    var aiSheet by remember { mutableStateOf<String?>(null) }
    // RN hasClipboardImage: checked when the composer opens and whenever the app comes back to the front
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle, model) {
        val check = { model.setClipboardHasImage(com.ecency.app.EditorImages.clipboardHasImage(context)) }
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event -> if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) check() }
        check()
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }, sheetState = sheetState, containerColor = colors.background) {
        // the whole sheet height: the text fills down to the toolbar, and the toolbar sits on the keyboard (or on
        // the navigation bar without one), with no empty stripe between them
        Column(Modifier.fillMaxWidth().fillMaxHeight().padding(horizontal = 16.dp).imePadding().navigationBarsPadding().padding(bottom = 4.dp).testTag("waveComposer")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(ReadingUiCopy.PUBLISHING_IN_WAVES, color = colors.darkGray, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text(
                    state.counter, fontSize = 13.sp, color = if (state.lengthExceeded) colors.redText else colors.icon,
                    modifier = Modifier.padding(end = 12.dp).testTag("waveCounter"),
                )
                Box(Modifier.clip(CircleShape).clickable(enabled = !state.isSending, onClick = onDismiss, role = Role.Button).padding(6.dp).testTag("waveClose")) {
                    Glyph(AppIcon.CLOSE, colors.icon, 22.dp, description = UiCopy.CLOSE)
                }
            }
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(com.ecency.core.app.PostCard.avatarUrl(model.owner.orEmpty(), env.proxyBase), 32.dp)
                Text("@${model.owner.orEmpty()}", fontWeight = FontWeight.Bold, color = colors.text, fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp))
            }
            OutlinedTextField(
                value = state.text,
                onValueChange = model::setText,
                enabled = !state.isSending,
                placeholder = { Text(ReadingUiCopy.WHATS_HAPPENING, color = Color(0xFFC1C5C7)) },
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = colors.text, unfocusedTextColor = colors.text, focusedBorderColor = colors.blueText, unfocusedBorderColor = colors.icon),
                modifier = Modifier.fillMaxWidth().weight(1f).heightIn(min = 120.dp).padding(top = 8.dp).focusRequester(focus).testTag("waveText"),
            )
            if (state.showsShortHint) {
                Text(
                    ReadingUiCopy.shortContentHint(com.ecency.core.api.waves.WavePublish.QUEST_MIN_CONTENT_LENGTH),
                    color = colors.darkGray, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp).testTag("waveShortHint"),
                )
            }
            if (state.isVideoUploading) {
                Box(Modifier.padding(top = 8.dp).size(72.dp).clip(RoundedCornerShape(8.dp)).background(colors.grayBackground).testTag("waveVideoUploading"), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(22.dp), color = colors.blueText, strokeWidth = 2.dp)
                }
            }
            if (state.videoEmbedUrl != null) {
                // RN _videoPreview: "Video attached" with the minus badge; a tap takes the video off. The image hides.
                Box(
                    Modifier.padding(top = 8.dp).size(72.dp).clip(RoundedCornerShape(8.dp)).background(colors.grayBackground)
                        .clickable(enabled = !state.isSending && !state.isVideoUploading, role = Role.Button) { model.removeVideo() }.testTag("waveVideo"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(ReadingUiCopy.VIDEO_ATTACHED, color = colors.blueText, fontSize = 10.sp, fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Box(Modifier.align(Alignment.TopEnd).padding(2.dp).size(18.dp).clip(CircleShape).background(colors.red), contentAlignment = Alignment.Center) {
                        Text("-", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            } else if (state.isAttaching) {
                Box(Modifier.padding(top = 8.dp).size(72.dp).clip(RoundedCornerShape(8.dp)).background(colors.grayBackground), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(22.dp), color = colors.blueText, strokeWidth = 2.dp)
                }
            } else {
                state.imageUrl?.let { url ->
                    // RN: the thumbnail with a minus badge; a tap removes the image
                    Box(Modifier.padding(top = 8.dp).size(72.dp).clip(RoundedCornerShape(8.dp)).clickable(enabled = !state.isSending, role = Role.Button) { model.removeImage() }.testTag("waveImage")) {
                        AsyncImage(model = url, contentDescription = ReadingUiCopy.ATTACHED_IMAGE_REMOVE, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        Box(Modifier.align(Alignment.TopEnd).padding(2.dp).size(18.dp).clip(CircleShape).background(colors.red), contentAlignment = Alignment.Center) {
                            Text("-", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            if (state.clipboardChip) {
                // RN clipboard chip: "Image in clipboard · Paste image" and its close button
                Row(
                    Modifier.padding(top = 8.dp).clip(RoundedCornerShape(16.dp)).background(colors.lightBackground).testTag("waveClipboardChip"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        com.ecency.core.app.WaveComposeCopy.CLIPBOARD_CHIP, color = colors.text, fontSize = 13.sp,
                        modifier = Modifier.clickable(enabled = !state.isSending && !state.isAttaching, onClick = model::pasteClipboardImage, role = Role.Button)
                            .padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 4.dp).testTag("wavePasteImage"),
                    )
                    Glyph(
                        AppIcon.CLOSE, colors.text, 16.dp,
                        Modifier.clickable(onClick = model::dismissClipboardChip, role = Role.Button).padding(horizontal = 10.dp, vertical = 6.dp).testTag("waveClipboardClose"),
                        description = com.ecency.core.app.WaveComposeCopy.CLIPBOARD_DISMISS,
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
              Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    UiCopy.IMAGE, color = colors.text, fontSize = 14.sp,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = !state.isSending && !state.isAttaching, role = Role.Button) { model.attach(WaveImageSource.GALLERY) }
                        .padding(horizontal = 8.dp, vertical = 8.dp).testTag("waveAttach"),
                )
                Text(
                    ReadingUiCopy.CAMERA, color = colors.text, fontSize = 14.sp,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = !state.isSending && !state.isAttaching, role = Role.Button) { model.attach(WaveImageSource.CAMERA) }
                        .padding(horizontal = 8.dp, vertical = 8.dp).testTag("waveCamera"),
                )
                Text(
                    ReadingUiCopy.FILES, color = colors.text, fontSize = 14.sp,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = !state.isSending && !state.isAttaching, role = Role.Button) { model.attach(WaveImageSource.FILES) }
                        .padding(horizontal = 8.dp, vertical = 8.dp).testTag("waveFiles"),
                )
                // RN's poll button (the editor's poll wizard); blue once a poll is attached
                Text(
                    UiCopy.POLL, color = if (state.poll != null) colors.blueText else colors.text, fontSize = 14.sp, fontWeight = if (state.poll != null) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = !state.isSending, role = Role.Button) { pollSheet = true }
                        .padding(horizontal = 8.dp, vertical = 8.dp).testTag("wavePoll"),
                )
                // RN video-outline: blue and off while a video is attached or on its way
                Text(
                    UiCopy.VIDEO, color = if (state.videoEmbedUrl != null) colors.blueText else if (state.canAddVideo) colors.text else colors.icon, fontSize = 14.sp,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = state.canAddVideo, role = Role.Button) { showVideo = true }
                        .padding(horizontal = 8.dp, vertical = 8.dp).testTag("waveVideoButton"),
                )
                // RN: dictation with the content buttons, then the AI pair
                Text(
                    com.ecency.core.app.WaveComposeCopy.DICTATE, color = colors.text, fontSize = 14.sp,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = !state.isSending, role = Role.Button) { aiSheet = "dictation" }
                        .padding(horizontal = 8.dp, vertical = 8.dp).testTag("waveDictate"),
                )
                Text(
                    UiCopy.AI_BADGE, color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = !state.isSending, role = Role.Button) { aiSheet = "aiAssist" }
                        .semantics { contentDescription = com.ecency.core.app.WaveComposeCopy.AI_ASSIST }
                        .padding(horizontal = 8.dp, vertical = 8.dp).testTag("waveAiAssist"),
                )
              }
                Spacer(Modifier.width(8.dp))
                // PUBLISH turns into its own progress while the wave goes out (Motion.kt)
                val phase = rememberSendPhase(state.isSending, sent = state.sent, holdsDone = false)
                Button(
                    onClick = model::send,
                    // off also over an empty composer, and it shows: the grey of iOS's disabled PUBLISH, not a faded blue
                    enabled = state.publishEnabled,
                    colors = ButtonDefaults.buttonColors(contentColor = androidx.compose.ui.graphics.Color.White, containerColor = colors.blue, disabledContainerColor = colors.icon),
                    modifier = Modifier.testTag("wavePublish"),
                ) { SendContent(phase, Color.White, 18.dp) { Text(ReadingUiCopy.PUBLISH, color = Color.White, fontWeight = FontWeight.Bold) } }
            }
        }
    }
    if (pollSheet) {
        PollSheet(state.poll, onAttach = { model.setPoll(it); pollSheet = false }, onReset = { model.setPoll(null); pollSheet = false }, onDismiss = { pollSheet = false })
    }
    when (aiSheet) {
        "aiAssist" -> {
            val assist = remember(model) { env.aiAssistModel(model) }
            DisposableEffect(assist) { onDispose { assist.close() } }
            AiAssistSheet(assist) { aiSheet = null }
        }
        "dictation" -> {
            // leaving composition (the sheet closed, the composer gone, the app lock) closes the model, which stops the microphone
            val dictation = remember(model) { env.dictationModel(model) }
            DisposableEffect(dictation) { onDispose { dictation.close() } }
            DictationSheet(dictation) { aiSheet = null }
        }
    }
    val recordVideo = rememberVideoRecord(env, model.owner, "wave") { uri ->
        val appContext = context.applicationContext
        val video = model.video()
        video.launch {
            if (video.refusal() != null) com.ecency.app.EditorImages.deleteIfCapture(appContext, uri) else com.ecency.app.EditorVideos.adopt(appContext, video, uri)
        }
        if (video.refusal() == null) showVideo = true
    }
    if (showVideo) VideoUploadSheet(remember(model) { model.video() }, onRecord = recordVideo) { showVideo = false }
    // RN: a publish HiveSigner refused for the missing ecency.app authority offers the grant; closing it is a skip
    if (state.authorityPrompt) PostingAuthorityDialog(onAnswer = model::answerAuthorityPrompt, onDismiss = { model.answerAuthorityPrompt(false) })
    state.error?.let { error ->
        AlertDialog(
            onDismissRequest = model::clearError,
            title = { Text(state.errorTitle) },
            text = { Text(error.message, modifier = Modifier.testTag("waveError")) },
            confirmButton = { TextButton(onClick = model::clearError) { Text(UiCopy.OK) } },
        )
    }
}

/**
 * RN wavesReelsView: full-height reels, one page each. Only [ShortsState.playingKey] has a player: the reel in
 * view, while this tab is on screen and the app is resumed. Leaving the tab, another screen on top, the
 * background or the app lock all take the composition (or the focus) away, which releases the player.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ShortsReels(env: AppEnvironment, model: ShortsModel, actions: FeedActions, onReply: (Post) -> Unit) {
    val state by model.state.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    val resumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    LaunchedEffect(model) { model.start() }
    DisposableEffect(model, resumed) {
        model.setFocused(resumed)
        onDispose { model.setFocused(false) }
    }
    val pager = rememberPagerState { state.items.size }
    LaunchedEffect(pager.settledPage, state.items) { model.setActive(state.items.getOrNull(pager.settledPage)?.key) }
    LaunchedEffect(pager.settledPage, state.pages, state.items.size) { if (state.items.isNotEmpty() && pager.settledPage >= state.items.size - 2) model.loadMore() }

    PullToRefreshBox(isRefreshing = state.isRefreshing, onRefresh = model::refresh, modifier = Modifier.fillMaxSize().background(Color.Black).testTag("shorts")) {
        when {
            state.items.isEmpty() && (state.isLoading || !state.loaded && state.error == null) ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color.White) }
            state.items.isEmpty() && state.error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                TextButton(onClick = model::retry) { Text(state.error!!.message + "\n" + UiCopy.TRY_AGAIN, color = Color.White) }
            }
            state.items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(ShortsModel.EMPTY, color = Color.White, modifier = Modifier.testTag("shorts:empty"))
            }
            else -> VerticalPager(state = pager, modifier = Modifier.fillMaxSize(), key = { state.items.getOrNull(it)?.key?.toString() ?: it }) { page ->
                state.items.getOrNull(page)?.let { item ->
                    ReelView(env, item, state, model, actions, onReply)
                }
            }
        }
    }
}

@Composable
private fun ReelView(env: AppEnvironment, item: ShortItem, state: ShortsState, model: ShortsModel, actions: FeedActions, onReply: (Post) -> Unit) {
    val playing = state.playingKey == item.key
    val playback = state.playbackOf(item.key)
    val inFlight = voteInFlight(env.votes.state, item.key)
    Box(Modifier.fillMaxSize().background(Color.Black).testTag("reel:${item.key}")) {
        when {
            playing && playback is ShortPlayback.Stream -> {
                LocalReelPlayer.current.Play(playback.url, state.muted, { model.playbackFailed(item.key) }, Modifier.fillMaxSize())
                Box(
                    Modifier.align(Alignment.TopEnd).padding(12.dp).size(40.dp).clip(CircleShape).background(Color(0x66000000))
                        .clickable(role = Role.Button) { model.toggleMute() }.testTag("reel:mute"),
                    contentAlignment = Alignment.Center,
                ) { Text(if (state.muted) ReadingUiCopy.UNMUTE else ReadingUiCopy.MUTE, color = Color.White, fontSize = 10.sp) }
            }
            playing && playback is ShortPlayback.Web -> PlayerWebView(playback.url, Modifier.fillMaxSize().testTag("reel:web"))
            else -> {
                item.thumbnailUrl?.let { AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                if (!playing) Glyph(AppIcon.PLAY, Color.White, 56.dp, Modifier.align(Alignment.Center), description = ReadingUiCopy.PAUSED, filled = true)
            }
        }
        Column(Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 24.dp, end = 88.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(role = Role.Button) { actions.onAuthor(item.card.author) }.testTag("reel:author")) {
                Avatar(item.card.avatarUrl, 36.dp)
                Text("@${item.card.author}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.padding(start = 8.dp))
            }
            if (item.caption.isNotEmpty()) {
                Text(item.caption, color = Color.White, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp).testTag("reel:caption"))
            }
        }
        Column(Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(
                Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = !inFlight, role = Role.Button) { actions.onVote(item.card) }.padding(8.dp)
                    .semantics { contentDescription = if (item.card.votedByMe) UiCopy.UPVOTED else UiCopy.UPVOTE }.testTag("reel:vote"),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                UpvoteGlyph(Color.White, filled = item.card.votedByMe, size = 30.dp)
                Text(item.card.payoutText, color = Color.White, fontSize = 12.sp)
            }
            Column(
                Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { onReply(item.post) }.padding(8.dp).semantics { contentDescription = ReadingUiCopy.REPLY }.testTag("reel:reply"),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Glyph(AppIcon.COMMENT, Color.White, 28.dp)
                Text(com.ecency.core.l10n.LocaleFormat.integer(item.card.comments.toLong()), color = Color.White, fontSize = 12.sp)
            }
            Box(Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { actions.onTip?.invoke(item.card) }.padding(8.dp).semantics { contentDescription = UiCopy.SEND_TIP }.testTag("reel:tip")) {
                Glyph(AppIcon.GIFT, Color.White, 28.dp)
            }
        }
    }
}

/**
 * The app's reel player: Media3 ExoPlayer over HLS, looping, filling the frame for portrait clips and fitting
 * landscape ones (RN resizeMode cover / contain). It exists only while composed and is released on dispose.
 */
private object ExoReelPlayer : ReelPlayer {
    // the PlayerView resize modes are Media3 "unstable" API (stable in behaviour, not yet frozen in name)
    @androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
    @Composable
    override fun Play(url: String, muted: Boolean, onError: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val failed by rememberUpdatedState(onError)
    var portrait by remember(url) { mutableStateOf(false) }
    val player = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.Builder().setUri(url).setMimeType(MimeTypes.APPLICATION_M3U8).build())
            repeatMode = Player.REPEAT_MODE_ONE
            playWhenReady = true
            prepare()
        }
    }
    LaunchedEffect(player, muted) { player.volume = if (muted) 0f else 1f }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) = failed()
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                portrait = videoSize.height > videoSize.width
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    AndroidView(
        factory = { PlayerView(it).apply { useController = false; this.player = player } },
        update = { it.resizeMode = if (portrait) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT },
        modifier = modifier.testTag("reel:player"),
    )
    }
}

/**
 * RN WavesOnboardingChecklist: "Getting started" for new accounts. Drawn only while the card's account is the
 * signed-in one; the shared model decides whether it shows at all.
 */
@Composable
fun WavesOnboardingCard(env: AppEnvironment, username: String, onWave: () -> Unit, onCheckIn: () -> Unit) {
    val colors = LocalEcencyColors.current
    val state by env.wavesOnboarding.state.collectAsState()
    if (!state.visible || state.account != username) return
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).clip(RoundedCornerShape(12.dp)).background(colors.grayBackground).padding(14.dp)
            .testTag("wavesOnboarding"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (state.celebrating) com.ecency.core.app.WavesOnboardingCopy.ALL_DONE_TITLE else com.ecency.core.app.WavesOnboardingCopy.TITLE,
                color = colors.text, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f).testTag("wavesOnboardingTitle"),
            )
            if (!state.celebrating) Text(state.progressText, color = colors.darkGray, fontSize = 13.sp, modifier = Modifier.padding(end = 8.dp).testTag("wavesOnboardingProgress"))
            Box(Modifier.clip(CircleShape).clickable(role = Role.Button) { env.wavesOnboarding.dismiss() }.padding(4.dp).testTag("wavesOnboardingDismiss")) {
                Glyph(AppIcon.CLOSE, colors.darkGray, 18.dp, description = com.ecency.core.app.WavesOnboardingCopy.DISMISS)
            }
        }
        Text(
            if (state.celebrating) com.ecency.core.app.WavesOnboardingCopy.ALL_DONE_SUBTITLE else com.ecency.core.app.WavesOnboardingCopy.SUBTITLE,
            color = colors.darkGray, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp),
        )
        if (state.celebrating) return@Column
        Box(Modifier.padding(top = 10.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(colors.border)) {
            Box(Modifier.fillMaxWidth(state.percent / 100f).height(6.dp).background(colors.blue))
        }
        state.items.forEach { item ->
            val tap: (() -> Unit)? = when {
                !item.actionable -> null
                item.id == com.ecency.core.app.WavesOnboardingItemId.WAVE -> onWave
                else -> onCheckIn
            }
            Row(
                Modifier.fillMaxWidth().then(if (tap != null) Modifier.clickable(onClick = tap, role = Role.Button) else Modifier).padding(vertical = 6.dp)
                    .testTag("wavesOnboardingItem:${item.id.id}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(24.dp)) { Glyph(AppIcon.TASK, if (item.completed) Color(0xFF4FD688) else colors.icon, 18.dp, filled = item.completed) }
                Text(
                    item.label, fontSize = 14.sp,
                    color = when {
                        item.completed -> colors.darkGray
                        tap != null -> colors.blueText
                        else -> colors.text
                    },
                    textDecoration = if (item.completed) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
                )
            }
        }
    }
}

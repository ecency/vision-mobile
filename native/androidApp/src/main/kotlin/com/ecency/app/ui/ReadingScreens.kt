package com.ecency.app.ui

import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.heightIn

import androidx.compose.ui.semantics.heading

import androidx.compose.ui.semantics.selected


import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription

import androidx.compose.ui.semantics.Role

import com.ecency.core.app.AppIcon
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.ecency.core.api.DiffSpan
import com.ecency.core.api.EditHistory
import com.ecency.core.api.model.Post
import com.ecency.core.api.model.PostKey
import com.ecency.core.api.repository.FeedSource
import com.ecency.core.app.AppEnvironment
import com.ecency.core.app.AppScreen
import com.ecency.core.app.EditHistoryModel
import com.ecency.core.app.FeedTabs
import com.ecency.core.app.FromNow
import com.ecency.core.app.PollModel
import com.ecency.core.app.PostCard
import com.ecency.core.app.ReadingInfo
import com.ecency.core.app.SimilarEntriesModel
import com.ecency.core.app.TranslateModel
import com.ecency.core.app.VoterSort
import com.ecency.core.app.VoteState
import kotlinx.coroutines.flow.StateFlow
import com.ecency.core.app.ReadingUiCopy
import com.ecency.core.app.UiCopy

// ---------------------------------------------------------------- home feed

/**
 * RN feed tabs: signed in, the saved filters (Following, Communities, Trending, Hot by default) with the "+"
 * customise button; signed out, Trending, Hot, New. Each filter keeps its own feed and list position in [entry].
 */
@Composable
fun FilterHomeFeeds(
    env: AppEnvironment,
    entry: StackEntry,
    username: String?,
    now: Long,
    actions: FeedActions,
    onLogin: () -> Unit,
    onTarget: (com.ecency.core.app.AnnouncementTarget) -> Unit = {},
) {
    val colors = LocalEcencyColors.current
    val feedx = LocalFeedx.current
    // re-read after the customise sheet saved (it closes) and on sign-in or out
    val tabs = remember(username, feedx?.customise) { FeedTabs.tabs(env.prefs, username != null) }
    val selected = entry.get("filter:$username") { mutableIntStateOf(0) }
    if (selected.intValue >= tabs.size) selected.intValue = 0
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().background(colors.grayBackground).padding(start = 8.dp, top = 8.dp, bottom = 8.dp).testTag("filterBar"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                tabs.forEachIndexed { index, filter -> Pill(filter.title, index == selected.intValue, tag = filter.name) { selected.intValue = index } }
            }
            // RN WalkthroughMarker: a red dot on "+" until it was first used (signed in only)
            val walkthrough by env.walkthrough.state.collectAsState()
            val step = com.ecency.core.app.WalkthroughStep.CUSTOM_TABS_BTN
            val marker = username != null && step !in walkthrough.shown
            Box(
                Modifier.padding(horizontal = 8.dp).clip(CircleShape).clickable(role = Role.Button) {
                    env.walkthrough.intercept(step, hidden = username == null)
                    if (username == null) onLogin() else feedx?.customise = true
                }
                    .padding(6.dp).testTag("customise"),
            ) {
                Glyph(AppIcon.ADD, colors.darkGray, 22.dp, Modifier.testTag("customiseGlyph"), description = ReadingUiCopy.CUSTOMISE_FILTERS)
                // on the glyph's top end corner, inside the button and the bar (it used to sit on the button's
                // edge and spill over the bar, onto a "Getting started" card above it)
                if (marker) {
                    Box(Modifier.align(Alignment.TopEnd).size(8.dp).clip(CircleShape).background(colors.red).testTag("walkthroughMarker:customise"))
                }
            }
        }
        val filter = tabs[selected.intValue]
        val source = filter.source(username)
        androidx.compose.runtime.key(filter, username) {
            if (source == null) {
                MessageBlock(ReadingUiCopy.LOGIN_TO_SEE, Modifier.padding(top = 48.dp), UiCopy.LOGIN, onLogin)
            } else {
                Guarded({ entry.get("feed:${filter.key}:$username") { env.feedModel(source, withPromoted = true) } }) { feed ->
                    // RN postsTabContent: on the main feed's first tab, the proposal card (signed in) then the spotlight
                    val header: (@Composable () -> Unit)? = if (selected.intValue == 0) ({
                        Column {
                            if (username != null) ProposalCard(env)
                            SpotlightCardView(env, onTarget)
                        }
                    }) else null
                    FeedList(feed, entry.get("list:${filter.key}:$username") { LazyListState() }, env.votes.state, now, actions, header = header)
                }
            }
        }
    }
}

/** RN FeatureSpotlightCard: "Did you know?", the spotlight, its button and "Dismiss" (SpotlightModel). */
@Composable
fun SpotlightCardView(env: AppEnvironment, onTarget: (com.ecency.core.app.AnnouncementTarget) -> Unit) {
    val colors = LocalEcencyColors.current
    LaunchedEffect(env) { env.spotlight.start() }
    val state by env.spotlight.state.collectAsState()
    val card = state.card ?: return
    Column(
        Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp).background(colors.lightBackground).padding(16.dp).testTag("spotlight:${card.id}"),
    ) {
        Text(com.ecency.core.app.SpotlightCopy.LABEL.uppercase(), color = colors.blueText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 4.dp))
        Text(card.title, color = colors.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() }.testTag("spotlightTitle"))
        Text(card.description, color = colors.text, modifier = Modifier.padding(vertical = 8.dp))
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { env.spotlight.act()?.let(onTarget) },
                colors = ButtonDefaults.buttonColors(contentColor = androidx.compose.ui.graphics.Color.White, containerColor = colors.blue),
                modifier = Modifier.heightIn(min = 40.dp).testTag("spotlightAction"),
            ) { Text(card.buttonText, color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.Bold) }
            androidx.compose.material3.TextButton(onClick = env.spotlight::dismiss, modifier = Modifier.padding(start = 8.dp).testTag("spotlightDismiss")) {
                Text(com.ecency.core.app.SpotlightCopy.DISMISS, color = colors.darkGray)
            }
        }
    }
}

@Composable
fun Pill(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    val colors = LocalEcencyColors.current
    // the selection moves with a short colour fade (Motion.kt)
    val motion = LocalMotion.current
    val fill by androidx.compose.animation.animateColorAsState(if (selected) colors.blue else Color.Transparent, motion.fade(), label = "pill") // fill
    val ink by androidx.compose.animation.animateColorAsState(if (selected) Color.White else colors.darkGray, motion.fade(), label = "pillText")
    Text(
        label,
        color = ink,
        fontWeight = FontWeight.Bold, fontSize = 14.sp,
        modifier = Modifier.padding(horizontal = 2.dp).clip(RoundedCornerShape(20.dp))
            .background(fill)
            .clickable(onClick = onClick, role = Role.Tab)
            .semantics { this.selected = selected }
            .padding(horizontal = 16.dp, vertical = 4.dp).testTag("tab:$tag"),
    )
}

/** RN tag screen: TOP / HOT / NEW over the tag, the follow row for signed-in readers, promoted entries. */
@Composable
fun TagFeedScreen(env: AppEnvironment, entry: StackEntry, tag: String, title: String, now: Long, actions: FeedActions, onBack: () -> Unit) {
    val colors = LocalEcencyColors.current
    // the tags stay these ids in every language
    val sorts = listOf(com.ecency.core.api.RankedSort.TRENDING to "TOP", com.ecency.core.api.RankedSort.HOT to "HOT", com.ecency.core.api.RankedSort.CREATED to "NEW")
    val sortLabels = listOf(ReadingUiCopy.TOP_CAPS, UiCopy.HOT, UiCopy.NEW)
    val selected = entry.get("sort") { mutableIntStateOf(0) }
    val follow = entry.get("follow") { env.tagFollowModel(tag) }
    LaunchedEffect(follow) { follow.start() }
    val fs by follow.state.collectAsState()
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        TopBar(title, onBack = onBack)
        if (fs.visible) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(fs.hint, fontSize = 12.sp, color = colors.darkGray, modifier = Modifier.weight(1f))
                Text(
                    fs.buttonLabel, color = if (fs.following) colors.darkGray else Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                    modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(if (fs.following) colors.lightBackground else colors.blue)
                        .clickable(enabled = !fs.busy, onClick = follow::toggle, role = Role.Button).padding(horizontal = 14.dp, vertical = 6.dp).testTag("followTag"),
                )
            }
        }
        Row(Modifier.fillMaxWidth().background(colors.grayBackground).padding(8.dp)) {
            sorts.forEachIndexed { i, (_, id) -> Pill(sortLabels[i], i == selected.intValue, tag = id) { selected.intValue = i } }
        }
        val sort = sorts[selected.intValue].first
        androidx.compose.runtime.key(sort) {
            Guarded({ entry.get("feed:${sort.name}") { env.feedModel(FeedSource.Ranked(sort, tag), withPromoted = true) } }) { feed ->
                FeedList(feed, entry.get("list:${sort.name}") { LazyListState() }, env.votes.state, now, actions, scrollTopModifier = Modifier.navigationBarsPadding())
            }
        }
    }
}

// ---------------------------------------------------------------- voters, reblogs, history, bots

@Composable
fun VotersScreen(env: AppEnvironment, entry: StackEntry, key: PostKey, onBack: () -> Unit, onProfile: (String) -> Unit) {
    val colors = LocalEcencyColors.current
    val model = entry.get("voters") { env.votersModel(key.author, key.permlink) }
    LaunchedEffect(model) { model.start() }
    val s by model.state.collectAsState()
    Column(Modifier.fillMaxSize().background(colors.background).statusBarsPadding().testTag("votersScreen")) {
        TopBar(s.title, onBack = onBack)
        OutlinedTextField(s.query, model::search, placeholder = { Text(UiCopy.SEARCH) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("votersSearch"))
        Row(Modifier.fillMaxWidth().padding(8.dp)) {
            VoterSort.entries.forEach { sort -> Pill(sort.label, s.sorted && s.sort == sort, tag = sort.name) { model.selectSort(sort) } }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            when {
                s.isLoading && s.rows.isEmpty() -> item { LoadingBlock(Modifier.padding(top = 48.dp)) }
                s.error != null && s.rows.isEmpty() -> item { ErrorBlock(s.error!!, Modifier.padding(top = 48.dp), com.ecency.core.esie.EsiePlacement.ERROR, model::refresh) }
                s.rows.isEmpty() -> item { EsieMessageBlock(com.ecency.core.esie.EsiePlacement.EMPTY, ReadingUiCopy.USER_IS_NOT_FOUND, Modifier.padding(top = 48.dp)) }
            }
            items(s.rows, key = { it.voter }) { row ->
                Row(
                    Modifier.fillMaxWidth().clickable(role = Role.Button) { onProfile(row.voter) }.padding(horizontal = 16.dp, vertical = 10.dp).testTag("voter:${row.voter}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(row.avatarUrl, 40.dp)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(row.voter, fontWeight = FontWeight.Bold, color = colors.text)
                        row.timeAgo?.let { Text(it, fontSize = 12.sp, color = colors.darkGray) }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("$ ${row.rewardText}", fontWeight = FontWeight.Bold, color = if (row.isDownVote) colors.redText else colors.text)
                        Text(row.percentText, fontSize = 12.sp, color = if (row.isDownVote) colors.redText else colors.darkGray)
                    }
                }
            }
        }
    }
}

@Composable
fun ReblogsScreen(env: AppEnvironment, entry: StackEntry, key: PostKey, loggedIn: Boolean, onBack: () -> Unit, onProfile: (String) -> Unit, onLogin: () -> Unit) {
    val colors = LocalEcencyColors.current
    val model = entry.get("reblogs") { env.reblogsModel(key.author, key.permlink) }
    LaunchedEffect(model) { model.start() }
    val s by model.state.collectAsState()
    Box(Modifier.fillMaxSize().background(colors.background).statusBarsPadding().testTag("reblogsScreen")) {
        Column(Modifier.fillMaxSize()) {
            TopBar(s.title, onBack = onBack)
            OutlinedTextField(s.query, model::search, placeholder = { Text(UiCopy.SEARCH) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp))
            LazyColumn(Modifier.fillMaxSize()) {
                when {
                    s.isLoading && s.accounts.isEmpty() -> item { LoadingBlock(Modifier.padding(top = 48.dp)) }
                    s.error != null && s.accounts.isEmpty() -> item { ErrorBlock(s.error!!, Modifier.padding(top = 48.dp), com.ecency.core.esie.EsiePlacement.ERROR, model::refresh) }
                    s.accounts.isEmpty() -> item { EsieMessageBlock(com.ecency.core.esie.EsiePlacement.EMPTY, ReadingUiCopy.NOTHING_HERE_YET, Modifier.padding(top = 48.dp)) }
                }
                items(s.accounts, key = { it }) { name ->
                    Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { onProfile(name) }.padding(horizontal = 16.dp, vertical = 10.dp).testTag("reblogger:$name"), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(PostCard.avatarUrl(name, env.proxyBase), 40.dp)
                        Text(name, fontWeight = FontWeight.Bold, color = colors.text, modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
        }
        Button(
            onClick = {
                if (!loggedIn) onLogin() else model.toggle(Post(author = key.author, permlink = key.permlink))
            },
            colors = ButtonDefaults.buttonColors(contentColor = androidx.compose.ui.graphics.Color.White, containerColor = colors.blue),
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp).testTag("reblogButton"),
        ) { Text(s.buttonLabel, color = Color.White) }
    }
}

@Composable
fun EditHistoryScreen(env: AppEnvironment, entry: StackEntry, key: PostKey, onBack: () -> Unit) {
    val colors = LocalEcencyColors.current
    val model = entry.get("history") { env.editHistoryModel(key.author, key.permlink) }
    LaunchedEffect(model) { model.start() }
    val s by model.state.collectAsState()
    Column(Modifier.fillMaxSize().background(colors.background).statusBarsPadding().testTag("editHistory")) {
        TopBar(ReadingUiCopy.EDIT_HISTORY, onBack = onBack) {
            Box(Modifier.clip(CircleShape).clickable(onClick = model::toggleDiff, role = Role.Button).padding(12.dp).testTag("toggleDiff")) {
                Glyph(AppIcon.SWAP, if (s.showDiff) colors.blueText else colors.darkGray, 24.dp, description = ReadingUiCopy.COMPARE)
            }
        }
        if (s.isLoading) LoadingBlock(Modifier.padding(top = 48.dp))
        s.message?.let { MessageBlock(it, Modifier.padding(top = 48.dp)) }
        if (s.versions.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(8.dp)) {
                s.versions.forEachIndexed { i, v ->
                    // the date in the app language (moment 'LL', as iOS); the raw text when it does not parse
                    val millis = com.ecency.core.api.wallet.WalletHistory.epochMillis(v.timestamp)
                    val label = EditHistoryModel.versionLabel(v) + "\n" + (millis?.let(com.ecency.core.l10n.LocaleFormat::dateLong) ?: v.timestamp.take(10))
                    Text(
                        label, textAlign = TextAlign.Center, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        color = if (i == s.selected) Color.White else colors.darkGray,
                        modifier = Modifier.padding(4.dp).clip(RoundedCornerShape(8.dp))
                            .background(if (i == s.selected) colors.blue else colors.lightBackground)
                            .clickable(role = Role.Button) { model.select(i) }.padding(horizontal = 12.dp, vertical = 6.dp).testTag("version:${v.version}"),
                    )
                }
            }
            val v = s.versions[s.selected]
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                val diff = s.diff
                if (s.showDiff && diff != null) {
                    DiffText(diff.title, 18.sp, FontWeight.Bold)
                    DiffText(diff.tags, 13.sp, FontWeight.Normal, Modifier.padding(vertical = 8.dp))
                    DiffText(diff.body, 15.sp, FontWeight.Normal)
                } else {
                    Text(v.title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = colors.text)
                    Text(EditHistory.tagsText(v), fontSize = 13.sp, color = colors.darkGray, modifier = Modifier.padding(vertical = 8.dp))
                    Text(v.body, fontSize = 15.sp, color = colors.text, modifier = Modifier.testTag("historyBody"))
                }
            }
        }
    }
}

/** diff_prettyHtml as styled text: insertions on #e6ffe6, deletions struck on #ffe6e6 (RN's colours). */
@Composable
private fun DiffText(spans: List<DiffSpan>, size: androidx.compose.ui.unit.TextUnit, weight: FontWeight, modifier: Modifier = Modifier) {
    val colors = LocalEcencyColors.current
    val text = buildAnnotatedString {
        spans.forEach { span ->
            when (span.kind) {
                1 -> withStyle(SpanStyle(background = Color(0xFFE6FFE6), color = Color(0xFF1B5E20))) { append(span.text) }
                -1 -> withStyle(SpanStyle(background = Color(0xFFFFE6E6), color = Color(0xFFB71C1C), textDecoration = TextDecoration.LineThrough)) { append(span.text) }
                else -> append(span.text)
            }
        }
    }
    Text(text, fontSize = size, fontWeight = weight, color = colors.text, modifier = modifier.testTag("diff"))
}

@Composable
fun BotCommentsScreen(env: AppEnvironment, entry: StackEntry, key: PostKey, onBack: () -> Unit, actions: PostScreenActions, votes: StateFlow<VoteState>, now: Long) {
    val colors = LocalEcencyColors.current
    val model = entry.get("post") { env.postModel(key.author, key.permlink) }
    LaunchedEffect(model) { model.start() }
    val s by model.state.collectAsState()
    Column(Modifier.fillMaxSize().background(colors.background).statusBarsPadding().testTag("botComments")) {
        TopBar(ReadingUiCopy.COMMENTS, onBack = onBack)
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val width = maxWidth - 32.dp
            LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp)) {
                if (s.commentsLoading && s.botComments.isEmpty()) item { LoadingBlock() }
                items(s.botComments, key = { "b:" + it.key }) { item -> CommentView(item, now, width, voteInFlight(votes, item.key), actions) }
            }
        }
    }
}

// ---------------------------------------------------------------- post screen parts

/** RN parentPost: a bordered box with the parent's title and summary; tap opens it. */
@Composable
fun ParentPostBox(parent: Post, summary: String, onOpen: () -> Unit) {
    val colors = LocalEcencyColors.current
    Column(
        Modifier.fillMaxWidth().padding(vertical = 8.dp).border(1.dp, colors.border, RoundedCornerShape(8.dp)).clickable(onClick = onOpen, role = Role.Button)
            .padding(10.dp).testTag("parentPost"),
    ) {
        if (parent.title.isNotEmpty()) Text(parent.title, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = colors.text)
        Text(summary, fontSize = 10.sp, color = colors.darkGray, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

/** RN postTranslateInline: the banner, then the translated plain text in place of the body. */
@Composable
fun TranslateBanner(model: TranslateModel, post: Post, onChangeLanguage: () -> Unit) {
    val colors = LocalEcencyColors.current
    val s by model.state.collectAsState()
    LaunchedEffect(post.body) { model.start(post) }
    if (!s.show && s.translated == null) return
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(8.dp)).background(colors.lightBackground).padding(10.dp).testTag("translateBanner")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Glyph(AppIcon.TRANSLATE, colors.blueText, 18.dp)
            Text(
                if (s.translated != null) s.translatedFromText else s.bannerText,
                fontSize = 13.sp, color = colors.text, modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
            Box(Modifier.clickable(onClick = model::dismiss, role = Role.Button).padding(4.dp).testTag("translateDismiss")) { Glyph(AppIcon.CLOSE, colors.darkGray, 16.dp, description = UiCopy.DISMISS) }
        }
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (s.translated == null) {
                Text(
                    if (s.translating) "..." else s.translateLabel, color = colors.blueText, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                    modifier = Modifier.clickable(role = Role.Button) { model.translate(post) }.testTag("translateGo"),
                )
            } else {
                Text(ReadingUiCopy.SHOW_ORIGINAL, color = colors.blueText, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.clickable(onClick = model::showOriginal, role = Role.Button).testTag("showOriginal"))
            }
            Text(ReadingUiCopy.CHANGE_LANGUAGE, color = colors.blueText, fontSize = 13.sp, modifier = Modifier.clickable(onClick = onChangeLanguage, role = Role.Button))
        }
    }
}

/** The translated body: plain text, 16/24, right to left for RTL targets (RN translatedBody). */
@Composable
fun TranslatedBody(text: String, rtl: Boolean) {
    val colors = LocalEcencyColors.current
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
    ) {
        Text(text, fontSize = 16.sp, lineHeight = 24.sp, color = colors.text, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("translatedBody"))
    }
}

/** RN postPoll. */
@Composable
fun PollCard(model: PollModel, onVoters: (List<String>) -> Unit) {
    val colors = LocalEcencyColors.current
    LaunchedEffect(model) { model.start() }
    val s by model.state.collectAsState()
    val poll = s.poll ?: return
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp).border(1.dp, colors.border, RoundedCornerShape(10.dp)).padding(12.dp).testTag("poll")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(poll.question, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = colors.text, modifier = Modifier.weight(1f))
            Text(s.endText, fontSize = 12.sp, color = colors.darkGray, modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(colors.lightBackground).padding(horizontal = 8.dp, vertical = 2.dp))
        }
        s.notes.forEach { Text(it, fontSize = 12.sp, color = colors.darkGray) }
        s.rows.forEach { row ->
            Box(
                Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(8.dp)).background(colors.lightBackground)
                    .border(if (row.selected) 2.dp else 0.dp, if (row.selected) colors.blueText else Color.Transparent, RoundedCornerShape(8.dp))
                    .clickable(enabled = s.canVote && !s.showResults, role = Role.Button) { model.toggle(row.num) }.testTag("choice:${row.num}"),
            ) {
                Box(Modifier.fillMaxWidth(row.progress.toFloat()).height(40.dp).background(if (row.votedByMe) colors.blue.copy(alpha = 0.35f) else colors.darkGray.copy(alpha = 0.2f)))
                Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (row.votedByMe) Glyph(AppIcon.CHECK, colors.blueText, 18.dp, Modifier.padding(end = 4.dp), description = ReadingUiCopy.YOUR_VOTE)
                    Text(row.text, color = colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (s.showResults) {
                        Text(
                            row.label, fontSize = 12.sp, color = colors.darkGray,
                            modifier = Modifier.clickable(enabled = !s.hideVoters, role = Role.Button) { onVoters(model.votersOf(row.num)) },
                        )
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (s.canToggleStats) Text(if (s.showResults) ReadingUiCopy.HIDE_STATS else ReadingUiCopy.VIEW_STATS, color = colors.blueText, fontSize = 13.sp, modifier = Modifier.clickable(onClick = model::toggleStats, role = Role.Button).testTag("pollStats"))
            if (s.canSwitchInterpretation) {
                Text(
                    if (s.interpretation == PollModel.TOKENS) ReadingUiCopy.INTERPRET_BY_VOTES else ReadingUiCopy.INTERPRET_BY_TOKENS, color = colors.blueText, fontSize = 13.sp,
                    modifier = Modifier.padding(start = 16.dp).clickable(onClick = model::switchInterpretation, role = Role.Button),
                )
            }
            Spacer(Modifier.weight(1f))
            if (s.canVote && !s.showResults) {
                Button(
                    onClick = model::vote, enabled = s.selection.isNotEmpty() && !s.isVoting,
                    colors = ButtonDefaults.buttonColors(contentColor = androidx.compose.ui.graphics.Color.White, containerColor = colors.blue), modifier = Modifier.testTag("pollVote"),
                ) { Text(ReadingUiCopy.VOTE, color = Color.White) }
            }
        }
    }
}

/** RN similarEntries: "Read next", two cards side by side. */
@Composable
fun SimilarEntries(model: SimilarEntriesModel, post: Post, now: Long, showImages: Boolean = true, onOpen: (String, String) -> Unit) {
    val colors = LocalEcencyColors.current
    LaunchedEffect(post.key) { model.start(post) }
    val s by model.state.collectAsState()
    if (!s.visible) return
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp).testTag("similar")) {
        Text(ReadingUiCopy.READ_NEXT, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = colors.text)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            s.entries.forEach { e ->
                Column(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { onOpen(e.author, e.permlink) }.testTag("similar:${e.permlink}")) {
                    val image = e.imageUrl?.let { com.ecency.core.render.ImageProxy(com.ecency.core.render.ImageProxy.DEFAULT_BASE).proxify(it, 400, 200) }
                    if (image != null && !showImages && !com.ecency.app.render.RevealedImages.isRevealed(image)) {
                        // RN similarEntryItem useImageReveal: the placeholder until tapped, nothing requested before
                        BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(2f)) {
                            com.ecency.app.render.HiddenImage(maxWidth, maxHeight) { com.ecency.app.render.RevealedImages.reveal(image) }
                        }
                    } else {
                        Box(Modifier.fillMaxWidth().aspectRatio(2f).background(colors.lightBackground)) {
                            if (image != null) AsyncImage(image, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        }
                    }
                    Text(e.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = colors.text, modifier = Modifier.padding(top = 4.dp))
                    Text(
                        "@${e.author} " + FromNow.ofTime(e.created, now),
                        fontSize = 11.sp, color = colors.darkGray, maxLines = 1,
                    )
                }
            }
        }
    }
}

/** RN BotCommentsPreview: the bots' avatars in the comments header, opening their comments. */
@Composable
fun BotPreviewRow(authors: List<String>, proxyBase: String, onOpen: () -> Unit) {
    if (authors.isEmpty()) return
    val colors = LocalEcencyColors.current
    Row(Modifier.padding(vertical = 6.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onOpen, role = Role.Button).padding(6.dp)
        .semantics(mergeDescendants = true) { contentDescription = com.ecency.core.app.RouteName.BOT_COMMENTS.title }.testTag("botPreview"), verticalAlignment = Alignment.CenterVertically) {
        Glyph(AppIcon.CHEVRON_DOUBLE_RIGHT, colors.darkGray, 18.dp)
        authors.distinct().take(5).forEach { Avatar(PostCard.avatarUrl(it, proxyBase), 22.dp, Modifier.padding(start = 4.dp)) }
    }
}

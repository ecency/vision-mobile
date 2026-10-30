package com.ecency.app.ui

import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.heightIn

import androidx.compose.ui.semantics.heading

import androidx.compose.ui.semantics.selected


import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.ecency.core.app.ShellUiCopy
import androidx.compose.ui.semantics.contentDescription

import androidx.compose.ui.semantics.Role

import com.ecency.core.app.AppIcon
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.ecency.core.api.ecency.Bookmark
import com.ecency.core.api.ecency.Favorite
import com.ecency.core.api.ecency.FavoriteTag
import com.ecency.core.app.AccountListEntry
import com.ecency.core.app.AccountListFilter
import com.ecency.core.app.AppEnvironment
import com.ecency.core.app.BookmarksModel
import com.ecency.core.app.BookmarksTab
import com.ecency.core.app.CalendarDate
import com.ecency.core.app.FeedModel
import com.ecency.core.app.FollowsModel
import com.ecency.core.app.PostCard
import com.ecency.core.app.ProfileEditModel
import com.ecency.core.app.ProfileField
import com.ecency.core.app.ProfileHeader
import com.ecency.core.app.ProfileMenuItem
import com.ecency.core.app.ProfileModel
import com.ecency.core.app.ProfileState
import com.ecency.core.app.ProfileTab
import com.ecency.core.app.TimeAgo
import com.ecency.core.app.VoteState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import com.ecency.core.app.OfficialAccounts
import com.ecency.core.app.ProfileUiCopy
import com.ecency.core.app.UiCopy
import com.ecency.core.app.ReadingUiCopy

// ---- shared pieces: avatar ribbon, user rows, Pro badge ----

/** RN ProBadge: the blue check-decagram next to an Ecency Pro member's name; nothing for others. */
@Composable
fun ProBadge(env: AppEnvironment, username: String, size: Dp = 15.dp) {
    val members by env.proMembers.state.collectAsState()
    LaunchedEffect(Unit) { env.proMembers.ensureLoaded() }
    if (username.lowercase() !in members) return
    Glyph(AppIcon.VERIFIED, LocalEcencyColors.current.blueText, size, Modifier.padding(start = 4.dp).testTag("proBadge"), ProfileUiCopy.ECENCY_PRO_MEMBER, filled = true)
}

/** RN userRibbon: avatar and @username in one row. */
@Composable
fun UserRibbon(env: AppEnvironment, username: String, modifier: Modifier = Modifier) {
    val colors = LocalEcencyColors.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Avatar(env.avatars.url(username, "small", env.proxyBase), 24.dp)
        Text("@$username", fontSize = 14.sp, color = colors.text, modifier = Modifier.padding(start = 8.dp))
    }
}

/** RN UserListItem: zebra row with avatar, name (or [text]), Pro badge and an optional [description]. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun UserListItem(
    env: AppEnvironment,
    index: Int,
    username: String,
    onClick: () -> Unit,
    text: String? = null,
    description: String? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val colors = LocalEcencyColors.current
    Row(
        Modifier.fillMaxWidth().background(if (index % 2 == 1) colors.lightBackground else colors.background)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, role = Role.Button)
            .padding(horizontal = 16.dp, vertical = 12.dp).testTag("user:$username"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(env.avatars.url(username, "small", env.proxyBase), 32.dp)
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text ?: username, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                ProBadge(env, username)
            }
            if (!description.isNullOrEmpty()) Text(description, fontSize = 12.sp, color = colors.darkGray)
        }
    }
}

// ---- profile ----

/** What the profile screen asks the shell to do. */
class ProfileActions(
    val onBack: () -> Unit,
    val onFollows: (username: String, following: Boolean, count: Int) -> Unit,
    val onEdit: () -> Unit,
    val onMessage: (String) -> Unit,
    val onEmailDigest: (String) -> Unit,
    val onDelegate: (String) -> Unit,
    val onWebsite: (String) -> Unit,
    val onWallet: (own: Boolean) -> Unit,
    val onWaves: () -> Unit,
    /** RN NewsletterSenderInfo's "Manage": the email digests screen. */
    val onManageDigests: () -> Unit = {},
)

/**
 * RN profile: the summary (cover, avatar with Follow / Message / email / menu or Edit, name, bio, date, website,
 * location, follower counts, voting power and RC bars), then the tabs over the selected tab's list. The summary
 * sits above the list and collapses as it scrolls (RN profileView, [SummaryCollapseUi]); the tab row stays.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    env: AppEnvironment,
    model: ProfileModel,
    listState: LazyListState,
    votes: StateFlow<VoteState>,
    nowMillis: Long,
    feedActions: FeedActions,
    actions: ProfileActions,
    /** What the Waves tab's rows need (body links, reply, voters); null shows the tab without them wired. */
    wavesUi: WavesUi? = null,
    /** The summary's collapse, kept in the stack entry with [listState]. */
    collapseState: SummaryCollapseState = remember { SummaryCollapseState() },
) {
    val state by model.state.collectAsState()
    val colors = LocalEcencyColors.current
    LaunchedEffect(model) { model.start() }
    val tab = state.tab
    // RN wavesTabContent: the author's waves, loaded when the tab is first shown
    val waves = remember(tab, model) { if (tab == ProfileTab.WAVES) model.waves() else null }
    val wavesState = waves?.state?.collectAsState()?.value
    LaunchedEffect(waves) { waves?.start() }
    val wavesWidth = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp - 32.dp
    val feed: FeedModel? = remember(tab) { model.feed(tab) }
    val feedState = feed?.state?.collectAsState()?.value
    LaunchedEffect(feed) { feed?.start() }
    val nearEnd by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 3
        }
    }
    LaunchedEffect(nearEnd, feedState?.pages, feed) { if (nearEnd) feed?.loadMore() }
    LaunchedEffect(nearEnd, wavesState?.pages, waves) { if (nearEnd) waves?.loadMore() }
    var confirmReport by remember { mutableStateOf(false) }

    val collapse = rememberSummaryCollapse(collapseState, listState)
    Box(Modifier.fillMaxSize().background(colors.background).statusBarsPadding().testTag("profile")) {
        val header = state.header
        Column(Modifier.fillMaxSize()) {
            if (header == null) {
                if (state.error != null) {
                    TopBar("@${state.username}", onBack = actions.onBack)
                    ErrorBlock(state.error!!, Modifier.padding(top = 48.dp), com.ecency.core.esie.EsiePlacement.ERROR, model::retry)
                } else {
                    ProfileHeaderSkeleton()
                }
                return@Column
            }
            // RN profileView: the summary closes as the list scrolls down and RN's Header (back, avatar, name) takes its
            // place; it opens again near the top, after a drag up, or on a tap on that header
            if (!collapse.open) ProfileCompactHeader(env, header, actions.onBack, collapse::expand)
            CollapsingSummary(collapse) {
                ProfileSummary(env, state, header, model, actions, onReport = { confirmReport = true })
                // RN NewsletterSenderInfo: the own creator list's subscribers, link and "Manage"
                if (state.isOwn) DigestSenderRow(env, state.username, actions.onManageDigests)
            }
            // another tab's list: the summary opens over it (the three ways in agree)
            if (!state.isMuted) ProfileTabs(state) { i -> if (i != state.selectedTab) collapse.reset(); model.selectTab(i) }
            PullToRefreshBox(
                isRefreshing = feedState?.isRefreshing == true || wavesState?.isRefreshing == true, onRefresh = model::refresh,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize().collapsesSummary(collapse).testTag("profileList")) {
                    if (state.isMuted) {
                        // RN _renderMutedView
                        item(key = "muted") {
                            Box(Modifier.fillMaxWidth().padding(top = 48.dp).testTag("mutedView"), contentAlignment = Alignment.Center) {
                                Glyph(AppIcon.MUTED, colors.icon, 120.dp, description = UiCopy.MUTED)
                            }
                        }
                        return@LazyColumn
                    }
                    when (tab) {
                        ProfileTab.WAVES -> if (wavesState != null && waves != null) {
                            when {
                                wavesState.items.isEmpty() && wavesState.error != null -> item(key = "waves:error") {
                                    ErrorBlock(wavesState.error!!, Modifier.padding(top = 32.dp), com.ecency.core.esie.EsiePlacement.ERROR, waves::retry)
                                }
                                wavesState.items.isEmpty() && wavesState.loaded -> item(key = "waves:empty") {
                                    EsieMessageBlock(com.ecency.core.esie.EsiePlacement.EMPTY, UiCopy.userHasNoPosts(state.username), Modifier.padding(top = 32.dp))
                                }
                                wavesState.items.isEmpty() -> item(key = "waves:loading") { LoadingBlock(Modifier.padding(top = 32.dp)) }
                            }
                            val ui = wavesUi ?: WavesUi(com.ecency.app.render.PostActions(), login = {}, requireLogin = { it() }, push = {})
                            items(wavesState.items, key = { "waves:${it.key}" }, contentType = { "wave" }) { item ->
                                WaveView(env, item, nowMillis, wavesWidth, voteInFlight(votes, item.key), feedActions, ui.body, ui)
                            }
                            if (wavesState.items.isNotEmpty()) item(key = "waves:footer") {
                                when {
                                    wavesState.isLoadingMore -> LoadingBlock()
                                    wavesState.error != null -> ErrorBlock(wavesState.error!!, onRetry = waves::retry)
                                    else -> Spacer(Modifier.height(72.dp))
                                }
                            }
                        }
                        ProfileTab.WALLET -> item(key = "wallet") {
                            if (state.isOwn) {
                                PlaceholderTab(ProfileUiCopy.WALLET, ProfileUiCopy.YOUR_WALLET_IS_ON, ProfileUiCopy.OPEN_WALLET) { actions.onWallet(true) }
                            } else {
                                // RN profile wallet tab of another account: unclaimed rewards and Wallet Details, read only
                                ProfileWalletTab(env, state.username)
                            }
                        }
                        else -> if (feedState != null) {
                            when {
                                feedState.items.isEmpty() && feedState.isLoading -> item(key = "loading") { FeedSkeleton(2) }
                                feedState.items.isEmpty() && feedState.error != null -> item(key = "error") {
                                    ErrorBlock(feedState.error!!, Modifier.padding(top = 32.dp), com.ecency.core.esie.EsiePlacement.ERROR, feed!!::retry)
                                }
                                feedState.items.isEmpty() && feedState.loaded -> item(key = "empty") {
                                    // RN profile.havent_posted / havent_commented
                                    EsieMessageBlock(com.ecency.core.esie.EsiePlacement.EMPTY,
                                        if (tab.isComments) ProfileUiCopy.userHasNoComments(state.username) else UiCopy.userHasNoPosts(state.username),
                                        Modifier.padding(top = 32.dp),
                                    )
                                }
                            }
                            items(feedState.items, key = { "${tab.key}:${it.key}" }, contentType = { if (tab.isComments) "comment" else "card" }) { card ->
                                if (tab.isComments) {
                                    CommentRow(card, nowMillis, feedActions)
                                } else {
                                    PostCardView(card, nowMillis, voteInFlight(votes, card.key), feedActions)
                                }
                            }
                            if (feedState.items.isNotEmpty()) item(key = "footer") {
                                when {
                                    feedState.isLoadingMore -> LoadingBlock()
                                    feedState.error != null -> ErrorBlock(feedState.error!!, onRetry = feed!!::retry)
                                    feedState.endReached -> MessageBlock(ReadingUiCopy.NO_MORE_POSTS)
                                    else -> Spacer(Modifier.height(72.dp))
                                }
                            }
                        }
                    }
                }
                // RN postsTabContent's ScrollTopPopup (the profile's post tabs)
                ScrollTopPopup(listState, "profileScrollTop", modifier = Modifier.navigationBarsPadding(), onTap = collapse::expand)
            }
        }
        // RN's floating back button over the cover, while the summary is open
        if (header != null && collapse.open) {
            Box(
                Modifier.padding(8.dp).size(40.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.35f))
                    .clickable(onClick = actions.onBack, role = Role.Button).testTag("back"),
                contentAlignment = Alignment.Center,
            ) { Glyph(AppIcon.BACK, Color.White, 22.dp, description = UiCopy.BACK) }
        }
    }

    if (confirmReport) {
        AlertDialog(
            onDismissRequest = { confirmReport = false },
            title = { Text(ProfileUiCopy.CONFIRM_REPORT) },
            text = { Text(ProfileUiCopy.REPORT_CONFIRM) },
            dismissButton = { TextButton(onClick = { confirmReport = false }) { Text(UiCopy.CANCEL) } },
            confirmButton = {
                TextButton(onClick = { confirmReport = false; model.report() }, modifier = Modifier.testTag("confirmReport")) { Text(UiCopy.CONFIRM) }
            },
        )
    }
    state.alert?.let { error ->
        AlertDialog(
            onDismissRequest = model::dismissAlert,
            title = { Text(UiCopy.FAIL) },
            text = { Text(error.message.ifEmpty { ProfileUiCopy.AN_ERROR_OCCURRED }) },
            confirmButton = { TextButton(onClick = model::dismissAlert) { Text(UiCopy.OK) } },
        )
    }
}

/** RN Header with the selected user, shown while the summary is collapsed; a tap on the user opens the summary. */
@Composable
private fun ProfileCompactHeader(env: AppEnvironment, header: ProfileHeader, onBack: () -> Unit, onExpand: () -> Unit) {
    val colors = LocalEcencyColors.current
    Row(
        Modifier.fillMaxWidth().background(colors.background).padding(horizontal = 8.dp).heightIn(min = 56.dp).testTag("profileCompactHeader"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.iconClickable(radius = 24.dp, onClick = onBack).padding(12.dp).testTag("back")) {
            Glyph(AppIcon.BACK, colors.darkGray, 24.dp, description = UiCopy.BACK)
        }
        Row(
            Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClickLabel = ShellUiCopy.EXPAND, onClick = onExpand)
                .semantics { stateDescription = ShellUiCopy.COLLAPSED }
                .padding(horizontal = 8.dp, vertical = 6.dp).testTag("profileExpand"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(header.avatarUrl, 32.dp)
            Column(Modifier.padding(start = 10.dp)) {
                Text(header.displayName, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = colors.darkText, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
                Text("@${header.username}", fontSize = 12.sp, color = colors.darkGray, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ProfileSummary(
    env: AppEnvironment,
    state: ProfileState,
    header: ProfileHeader,
    model: ProfileModel,
    actions: ProfileActions,
    onReport: () -> Unit,
) {
    val colors = LocalEcencyColors.current
    Column(Modifier.fillMaxWidth().testTag("profileSummary")) {
        // cover edge to edge, the avatar overlapping its bottom by 40dp (RN avatarSection marginTop -40)
        Box(Modifier.fillMaxWidth().height(200.dp)) {
            Box(Modifier.fillMaxWidth().height(160.dp).background(if (colors.isDark) Color(0xFF2E3D51) else Color(0xFFE6EEF9)).testTag("profileCover")) {
                if (header.coverUrl.isNotEmpty()) {
                    AsyncImage(header.coverUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().testTag("cover"))
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 120.dp).height(80.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Box(Modifier.size(80.dp).clip(CircleShape).background(colors.background).border(3.dp, colors.background, CircleShape), contentAlignment = Alignment.Center) {
                    Avatar(header.avatarUrl, 74.dp)
                }
                Row(Modifier.padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (state.showsActions) {
                        val haptics = rememberHaptics()
                        PillButton(state.followLabel, enabled = !state.busy, tag = "follow") { haptics(com.ecency.core.app.HapticEvent.TOGGLE); model.toggleFollow() }
                        Spacer(Modifier.width(8.dp))
                        PillButton(UiCopy.MESSAGE, enabled = !state.busy, tag = "message") { actions.onMessage(state.username) }
                        Box(
                            Modifier.padding(start = 4.dp).clip(CircleShape).clickable(enabled = !state.busy, role = Role.Button) { actions.onEmailDigest(state.username) }
                                .padding(6.dp).testTag("emailDigest"),
                        ) { Glyph(AppIcon.EMAIL, colors.blueText, 26.dp, description = ProfileUiCopy.EMAIL_DIGEST) }
                        if (state.busy) {
                            CircularProgressIndicator(Modifier.padding(start = 8.dp).size(24.dp).testTag("profileBusy"), color = colors.blueText, strokeWidth = 2.dp)
                        } else {
                            ProfileMenu(state, onSelect = { item ->
                                when (item) {
                                    ProfileMenuItem.FAVORITE -> { haptics(com.ecency.core.app.HapticEvent.TOGGLE); model.toggleFavorite() }
                                    ProfileMenuItem.DELEGATE -> actions.onDelegate(state.username)
                                    ProfileMenuItem.MUTE -> model.toggleMute()
                                    ProfileMenuItem.REPORT -> onReport()
                                }
                            })
                        }
                    }
                    if (state.isOwn) PillButton(UiCopy.EDIT, enabled = true, tag = "editProfile", onClick = actions.onEdit)
                }
            }
        }
        Column(Modifier.padding(bottom = 4.dp)) {
            // identity
            Column(Modifier.padding(horizontal = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(header.displayName, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = colors.darkText, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).testTag("profileName"))
                    if (state.isPro) Glyph(AppIcon.VERIFIED, colors.blueText, 18.dp, Modifier.padding(start = 4.dp).testTag("proBadge"), ProfileUiCopy.ECENCY_PRO_MEMBER, filled = true)
                    if (OfficialAccounts.isOfficial(header.username)) OfficialBadge(Modifier.padding(start = 6.dp))
                }
                Text("@${header.username}", fontSize = 14.sp, color = colors.darkGray, modifier = Modifier.padding(top = 1.dp).testTag("profileUsername"))
                header.about?.let {
                    Text(it, fontSize = 14.sp, lineHeight = 20.sp, color = colors.darkText, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp).testTag("profileAbout"))
                }
            }
            // date, website, location
            val joined = header.joined?.let(::localDate)
            if (joined != null || header.website != null || header.location != null) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    joined?.let { MetaItem(AppIcon.CALENDAR, it, "joined") }
                    header.website?.let { w -> MetaItem(AppIcon.GLOBE, w, "website", header.websiteUrl?.let { url -> { actions.onWebsite(url) } }) }
                    header.location?.let { MetaItem(AppIcon.LOCATION, it, "location") }
                }
            }
            // followers / following
            Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Stat(header.followersText, UiCopy.FOLLOWER, "followers") { actions.onFollows(state.username, false, header.followers) }
                Stat(header.followingText, UiCopy.FOLLOWING, "following") { actions.onFollows(state.username, true, header.following) }
            }
            ManaBars(header)
        }
    }
}

@Composable
private fun PillButton(text: String, enabled: Boolean, tag: String, onClick: () -> Unit) {
    val colors = LocalEcencyColors.current
    // busy dims with a fade, a new label (Follow, Unfollow) pops in (Motion.kt); the text node keeps the tag
    val alpha by androidx.compose.animation.core.animateFloatAsState(if (enabled) 1f else 0.5f, LocalMotion.current.fade(), label = "pillAlpha")
    Text(
        text, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = colors.darkText,
        modifier = Modifier.popOnChange(text).graphicsLayer { this.alpha = alpha }.clip(RoundedCornerShape(20.dp)).border(1.dp, colors.icon, RoundedCornerShape(20.dp))
            .clickable(enabled = enabled, onClick = onClick, role = Role.Button).padding(horizontal = 16.dp, vertical = 6.dp).testTag(tag),
    )
}

@Composable
private fun ProfileMenu(state: ProfileState, onSelect: (ProfileMenuItem) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.padding(start = 8.dp)) {
        Box(Modifier.clip(CircleShape).clickable(role = Role.Button) { open = true }.padding(4.dp).testTag("profileMenu")) {
            Glyph(AppIcon.MORE, LocalEcencyColors.current.darkGray, 25.dp, description = ProfileUiCopy.MENU)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            state.menu.forEach { item ->
                DropdownMenuItem(
                    text = { Text(state.menuLabel(item), fontSize = 14.sp) },
                    onClick = { open = false; onSelect(item) },
                    modifier = Modifier.testTag("menu:${item.name}"),
                )
            }
        }
    }
}

@Composable
private fun MetaItem(icon: AppIcon, text: String, tag: String, onClick: (() -> Unit)? = null) {
    val colors = LocalEcencyColors.current
    Row(
        Modifier.then(if (onClick != null) Modifier.clickable(onClick = onClick, role = Role.Button) else Modifier).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(icon, colors.icon, 14.dp)
        Text(text, fontSize = 13.sp, color = if (onClick != null) colors.blueText else colors.darkGray, modifier = Modifier.padding(start = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Stat(count: String, label: String, tag: String, onClick: () -> Unit) {
    val colors = LocalEcencyColors.current
    Row(Modifier.clickable(onClick = onClick, role = Role.Button).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        Text(count, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = colors.darkText)
        Text(label, fontSize = 14.sp, color = colors.darkGray, modifier = Modifier.padding(start = 4.dp))
    }
}

/** RN PercentBar pair: voting power (blue) and RC (green); a tap shows the percent texts. */
@Composable
private fun ManaBars(header: ProfileHeader) {
    val colors = LocalEcencyColors.current
    var showText by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp).clickable(role = Role.Button) { showText = !showText }.testTag("manaBars")) {
        if (showText) Text(header.votingPowerText, fontSize = 11.sp, color = colors.blueText, modifier = Modifier.align(Alignment.CenterHorizontally).testTag("vpText"))
        Bar(header.votingPower, colors.blueText.copy(alpha = 0.2f), colors.blueText)
        Bar(header.resourceCredits, if (colors.isDark) Color(0xFF333E47) else Color(0xFFEAFCEF), Color(0xFF11C28B))
        if (showText) Text(header.resourceCreditsText, fontSize = 11.sp, color = Color(0xFF11C28B), modifier = Modifier.align(Alignment.CenterHorizontally).testTag("rcText"))
    }
}

@Composable
private fun Bar(percent: String, track: Color, fill: Color) {
    val fraction = ((percent.toFloatOrNull() ?: 0f) / 100f).coerceIn(0f, 1f)
    Box(Modifier.padding(vertical = 2.dp).fillMaxWidth().height(2.dp).clip(RoundedCornerShape(50)).background(track)) {
        Box(Modifier.fillMaxWidth(fraction).height(2.dp).clip(RoundedCornerShape(50)).background(fill))
    }
}

@Composable
private fun ProfileTabs(state: ProfileState, onSelect: (Int) -> Unit) {
    val colors = LocalEcencyColors.current
    Row(Modifier.fillMaxWidth().background(colors.background).testTag("profileTabs")) {
        state.tabs.forEachIndexed { i, t ->
            val selected = i == state.selectedTab
            Column(Modifier.weight(1f).clickable(role = Role.Tab) { onSelect(i) }.semantics { this.selected = selected }.testTag("tab:${t.key}"), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    t.label, fontSize = 14.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    color = if (selected) colors.blueText else colors.darkGray, modifier = Modifier.padding(vertical = 12.dp),
                )
                Box(Modifier.fillMaxWidth().height(2.dp).background(if (selected) colors.blue else Color.Transparent))
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.lightBackground))
}

@Composable
private fun PlaceholderTab(title: String, text: String, action: String?, onAction: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 32.dp).testTag("placeholderTab"), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = LocalEcencyColors.current.text, modifier = Modifier.semantics { heading() })
        MessageBlock(text, actionLabel = action, onAction = if (action != null) onAction else null)
    }
}

/** A comment of the comments / replies tab: author, age, text and votes; a tap opens it. */
@Composable
private fun CommentRow(card: PostCard, nowMillis: Long, actions: FeedActions) {
    val colors = LocalEcencyColors.current
    Column(Modifier.fillMaxWidth().clickable(role = Role.Button) { actions.onOpen(card) }.padding(horizontal = 16.dp, vertical = 12.dp).testTag("comment:${card.key}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(card.avatarUrl, 24.dp)
            Text(card.author, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = colors.text, modifier = Modifier.padding(start = 8.dp))
            Text(" " + TimeAgo.short(card.createdEpochMillis, nowMillis), fontSize = 12.sp, color = colors.darkGray)
        }
        if (card.title.isNotEmpty()) Text(card.title, fontSize = 13.sp, color = colors.darkGray, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        Text(card.summary, fontSize = 15.sp, lineHeight = 21.sp, color = colors.text, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        Text(ProfileUiCopy.cardStats(card.payoutText, card.votes, card.comments), fontSize = 12.sp, color = colors.darkGray, modifier = Modifier.padding(top = 6.dp))
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.lightBackground))
}

/** The join date in the app language ("May 1, 2018", "1. Mai 2018"; RN toLocaleDateString). */
private fun localDate(d: CalendarDate): String =
    com.ecency.core.l10n.LocaleFormat.calendarDate(d.year, d.month, d.day, com.ecency.core.l10n.LocaleFormatter.Pattern.DATE)

// ---- followers / following ----

/** RN followsScreen: "Follower (12)" with a search, zebra rows, paging at the end; a row opens the profile. */
@Composable
fun FollowsScreen(env: AppEnvironment, model: FollowsModel, listState: LazyListState, onBack: () -> Unit, onUser: (String) -> Unit) {
    val state by model.state.collectAsState()
    val colors = LocalEcencyColors.current
    LaunchedEffect(model) { model.start() }
    var searching by rememberSaveable { mutableStateOf(state.query.isNotEmpty()) }
    val nearEnd by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 5
        }
    }
    LaunchedEffect(nearEnd, state.pages) { if (nearEnd && state.query.isEmpty()) model.loadMore() }
    Column(Modifier.fillMaxSize().background(colors.background).statusBarsPadding().testTag("follows")) {
        TopBar(state.title, onBack = onBack) {
            Box(Modifier.clip(CircleShape).clickable(role = Role.Button) { searching = !searching; if (!searching) model.search("") }.padding(12.dp).testTag("searchToggle")) {
                Glyph(AppIcon.SEARCH, colors.darkGray, 22.dp, description = UiCopy.SEARCH)
            }
        }
        if (searching) {
            OutlinedTextField(
                value = state.query, onValueChange = model::search, singleLine = true, placeholder = { Text(UiCopy.SEARCH) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("followsSearch"),
            )
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            if (state.isLoading) item(key = "loading") { LoadingBlock(Modifier.padding(top = 32.dp)) }
            state.emptyText?.let { text ->
                item(key = "empty") {
                    if (state.error != null && state.rows.isEmpty()) EsieMessageBlock(com.ecency.core.esie.EsiePlacement.ERROR, text, actionLabel = UiCopy.RETRY, onAction = model::retry)
                    else EsieMessageBlock(com.ecency.core.esie.EsiePlacement.EMPTY, text)
                }
            }
            itemsIndexed(state.visible, key = { _, r -> r.name }) { i, row -> UserListItem(env, i, row.name, onClick = { onUser(row.name) }) }
            if (state.rows.isNotEmpty()) item(key = "footer") {
                when {
                    state.isLoadingMore -> LoadingBlock()
                    state.error != null -> ErrorBlock(state.error!!, onRetry = model::retry)
                    else -> Spacer(Modifier.heightIn(min = 48.dp))
                }
            }
        }
    }
}

// ---- profile edit ----

/**
 * RN profileEdit: the avatar (tap: image source), the cover, Display Name, About, Location and Website, and
 * SAVE once something changed. An image from the gallery, the camera or files is prepared here (the avatar cropped
 * square at 512 px) and uploaded by the model, which saves the profile right away (RN _uploadImage); an image
 * link can be entered too.
 */
@Composable
fun ProfileEditScreen(env: AppEnvironment, model: ProfileEditModel, onBack: () -> Unit, onSaved: () -> Unit) {
    val state by model.state.collectAsState()
    val colors = LocalEcencyColors.current
    LaunchedEffect(model) { model.start() }
    LaunchedEffect(state.saved) { if (state.saved) onSaved() }
    var source by remember { mutableStateOf<ProfileField?>(null) }
    var urlFor by remember { mutableStateOf<ProfileField?>(null) }
    // the field a picker is open for; saved, since the camera app often pushes this process out of memory
    var picking by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    var cameraUri by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<android.net.Uri?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun picked(uri: android.net.Uri?) {
        val field = picking?.let { name -> ProfileField.entries.firstOrNull { it.name == name } }
        picking = null
        if (uri == null || field == null) return
        val appContext = context.applicationContext
        scope.launch {
            val image = if (field == ProfileField.AVATAR) com.ecency.app.EditorImages.prepareSquare(appContext, uri)
            else com.ecency.app.EditorImages.prepare(appContext, uri, 0)
            if (image == null) env.ui.toast(com.ecency.core.app.ProfileEditCopy.INVALID_IMAGE) else model.uploadImage(field, image)
        }
    }
    // the one media picking layer (MediaPicker.kt), one surface per image field
    val avatarPicker = com.ecency.app.rememberMediaPicker(com.ecency.core.app.MediaSurface.AVATAR, onRejected = { env.ui.toast(it) }) { picked(it.firstOrNull()) }
    val coverPicker = com.ecency.app.rememberMediaPicker(com.ecency.core.app.MediaSurface.COVER, onRejected = { env.ui.toast(it) }) { picked(it.firstOrNull()) }
    val camera = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.TakePicture()) { ok ->
        val uri = cameraUri
        cameraUri = null
        if (ok && uri != null) picked(uri) else {
            uri?.let { com.ecency.app.EditorImages.deleteIfCapture(context, it) } // cancelled: no temp file stays behind
            picking = null
        }
    }
    fun open(field: ProfileField, from: com.ecency.core.app.ProfileImageSource) {
        picking = field.name
        val opened = runCatching {
            when (from) {
                com.ecency.core.app.ProfileImageSource.GALLERY -> (if (field == ProfileField.AVATAR) avatarPicker else coverPicker).pickFromLibrary()
                com.ecency.core.app.ProfileImageSource.FILES -> (if (field == ProfileField.AVATAR) avatarPicker else coverPicker).pickFromFiles()
                com.ecency.core.app.ProfileImageSource.CAMERA -> {
                    val uri = com.ecency.app.EditorImages.newCameraUri(context)
                    cameraUri = uri
                    camera.launch(uri)
                }
            }
        }
        if (opened.isFailure) picking = null
    }
    Box(Modifier.fillMaxSize().background(colors.background).statusBarsPadding().testTag("profileEdit")) {
        Column(Modifier.fillMaxSize()) {
            TopBar(ProfileUiCopy.EDIT_PROFILE, onBack = onBack)
            when {
                state.isLoading -> LoadingBlock(Modifier.padding(top = 48.dp))
                state.loadError != null -> ErrorBlock(state.loadError!!, Modifier.padding(top = 48.dp), com.ecency.core.esie.EsiePlacement.ERROR, model::retry)
                state.loaded -> LazyColumn(Modifier.fillMaxSize()) {
                    item(key = "avatar") {
                        Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                            // one control; the pen sits over the circle's edge, outside its clip (inside it, it was
                            // cut off), and the ripple is the avatar's circle, drawn from its centre
                            Box(
                                Modifier.clickable(
                                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                    indication = androidx.compose.material3.ripple(bounded = false, radius = 64.dp),
                                    enabled = !state.isSaving && state.uploading == null, onClickLabel = UiCopy.EDIT, role = Role.Button,
                                ) { source = ProfileField.AVATAR }
                                    .semantics { contentDescription = UiCopy.IMAGE }.testTag("editAvatar"),
                            ) {
                                Box(Modifier.clip(CircleShape).testTag("editAvatarCircle")) {
                                    Avatar(state.avatarPreview, 128.dp)
                                    if (state.uploading == ProfileField.AVATAR) UploadingCover(Modifier.matchParentSize())
                                }
                                EditBadge(Modifier.align(Alignment.BottomEnd).testTag("editAvatarBadge"))
                            }
                        }
                    }
                    item(key = "cover") {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text(ProfileUiCopy.COVER_IMAGE, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = colors.darkGray)
                            Box(
                                Modifier.padding(top = 8.dp).fillMaxWidth().height(140.dp).clip(RoundedCornerShape(8.dp))
                                    .background(colors.lightBackground).clickable(enabled = !state.isSaving && state.uploading == null, role = Role.Button) { source = ProfileField.COVER }
                                    .semantics { contentDescription = ProfileUiCopy.COVER_IMAGE }.testTag("editCover"),
                            ) {
                                if (state.coverPreview.isNotEmpty()) AsyncImage(state.coverPreview, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                if (state.uploading == ProfileField.COVER) UploadingCover(Modifier.matchParentSize())
                                EditBadge(Modifier.align(Alignment.BottomEnd).padding(8.dp))
                            }
                        }
                    }
                    items(ProfileField.form, key = { it.name }) { field ->
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text(field.label, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = colors.darkGray)
                            OutlinedTextField(
                                value = state.value(field), onValueChange = { model.setValue(field, it) }, enabled = !state.isSaving,
                                singleLine = field != ProfileField.ABOUT, modifier = Modifier.fillMaxWidth().padding(top = 4.dp).testTag("field:${field.name}"),
                            )
                        }
                    }
                    item(key = "space") { Spacer(Modifier.height(96.dp)) }
                }
            }
        }
        if (state.saveEnabled || state.isSaving) {
            Row(
                Modifier.align(Alignment.BottomEnd).padding(24.dp).clip(RoundedCornerShape(24.dp)).background(colors.blue)
                    .clickable(enabled = !state.isSaving, onClick = model::save, role = Role.Button).padding(horizontal = 24.dp, vertical = 12.dp).testTag("saveProfile"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.isSaving) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                else Text(UiCopy.SAVE_CAPS, color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
    source?.let { field ->
        // RN OptionsModal (gallery, camera; Android: files) plus the link entry, as the shared chooser sheet
        MediaSourceSheet(
            field.label, com.ecency.core.app.MediaSourceMenus.profileImage(camera = true),
            tagFor = { if (it == com.ecency.core.app.MediaSource.LINK) "source:link" else "source:${it.profileSource().name}" },
            onDismiss = { source = null },
        ) { from ->
            if (from == com.ecency.core.app.MediaSource.LINK) urlFor = field else open(field, from.profileSource())
        }
    }
    urlFor?.let { field ->
        var text by remember(field) { mutableStateOf(state.value(field)) }
        AlertDialog(
            onDismissRequest = { urlFor = null },
            title = { Text(field.label) },
            text = { OutlinedTextField(text, { text = it }, singleLine = true, placeholder = { Text("https://") }, modifier = Modifier.testTag("imageUrl")) },
            dismissButton = { TextButton(onClick = { urlFor = null }) { Text(UiCopy.CANCEL) } },
            confirmButton = { TextButton(onClick = { if (model.setImageUrl(field, text)) urlFor = null }, modifier = Modifier.testTag("imageUrlOk")) { Text(UiCopy.OK) } },
        )
    }
    state.alert?.let { error ->
        AlertDialog(
            onDismissRequest = model::dismissAlert,
            title = { Text(UiCopy.FAIL) },
            text = { Text(error.message) },
            confirmButton = { TextButton(onClick = model::dismissAlert) { Text(UiCopy.OK) } },
        )
    }
}

/** RN editAvatar / profileEditForm while uploading: the image dimmed under a spinner. */
@Composable
private fun UploadingCover(modifier: Modifier = Modifier) {
    Box(modifier.background(Color.Black.copy(alpha = 0.4f)).testTag("imageUploading"), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(28.dp), color = Color.White, strokeWidth = 2.dp)
    }
}

@Composable
private fun EditBadge(modifier: Modifier = Modifier) {
    val colors = LocalEcencyColors.current
    // a ring of the page colour keeps the pen apart from the picture under it
    Box(modifier.size(32.dp).clip(CircleShape).background(colors.blue).border(2.dp, colors.background, CircleShape), contentAlignment = Alignment.Center) {
        Glyph(AppIcon.EDIT, Color.White, 18.dp, description = UiCopy.EDIT)
    }
}

// ---- bookmarks ----

/** RN bookmarks: Bookmarks, Favorites and Tags tabs; a long press asks to remove. */
@Composable
fun BookmarksScreen(
    env: AppEnvironment,
    model: BookmarksModel,
    onBack: () -> Unit,
    onPost: (author: String, permlink: String) -> Unit,
    onUser: (String) -> Unit,
    onTag: (String) -> Unit,
) {
    val state by model.state.collectAsState()
    val colors = LocalEcencyColors.current
    LaunchedEffect(model) { model.start() }
    var remove by remember { mutableStateOf<Any?>(null) }
    Column(Modifier.fillMaxSize().background(colors.background).statusBarsPadding().testTag("bookmarks")) {
        TopBar(ProfileUiCopy.BOOKMARKS, onBack = onBack)
        Row(Modifier.fillMaxWidth()) {
            BookmarksTab.entries.forEachIndexed { i, t ->
                val selected = i == state.selected
                Column(Modifier.weight(1f).clickable(role = Role.Tab) { model.select(i) }.semantics { this.selected = selected }.testTag("btab:${t.name}"), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(t.label, fontSize = 14.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, color = if (selected) colors.blueText else colors.darkGray, modifier = Modifier.padding(vertical = 12.dp))
                    Box(Modifier.fillMaxWidth().height(2.dp).background(if (selected) colors.blue else Color.Transparent))
                }
            }
        }
        val tab = state.tab
        val listState = remember(tab) { LazyListState() }
        val count = when (tab) {
            BookmarksTab.BOOKMARKS -> state.bookmarks.items.size
            BookmarksTab.FAVORITES -> state.favorites.items.size
            BookmarksTab.TAGS -> state.tags.items.size
        }
        val nearEnd by remember(listState) {
            derivedStateOf { val info = listState.layoutInfo; info.totalItemsCount > 0 && (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 3 }
        }
        LaunchedEffect(nearEnd, count, tab) { if (nearEnd) model.loadMore(tab) }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag("blist:${tab.name}")) {
            val error = state.error(tab)
            when {
                count == 0 && state.isLoading(tab) -> item(key = "loading") { LoadingBlock(Modifier.padding(top = 32.dp)) }
                count == 0 && error != null -> item(key = "error") { ErrorBlock(error, Modifier.padding(top = 32.dp), com.ecency.core.esie.EsiePlacement.ERROR) { model.retry(tab) } }
                state.isEmpty(tab) -> item(key = "empty") { EsieMessageBlock(com.ecency.core.esie.EsiePlacement.EMPTY, UiCopy.NOTHING_HERE, Modifier.padding(top = 32.dp)) }
            }
            when (tab) {
                BookmarksTab.BOOKMARKS -> itemsIndexed(state.bookmarks.items, key = { _, b -> "b:${b.id}:${b.author}/${b.permlink}" }) { i, b ->
                    UserListItem(env, i, b.author, onClick = { onPost(b.author, b.permlink) }, text = "${b.author}/${b.permlink}", onLongClick = { remove = b })
                }
                BookmarksTab.FAVORITES -> itemsIndexed(state.favorites.items, key = { _, f -> "f:${f.account}" }) { i, f ->
                    UserListItem(env, i, f.account, onClick = { onUser(f.account) }, onLongClick = { remove = f })
                }
                BookmarksTab.TAGS -> items(state.tags.items, key = { "t:${it.tag}" }) { t -> TagRow(t, onClick = { onTag(t.tag) }, onLongClick = { remove = t }) }
            }
            val list = when (tab) {
                BookmarksTab.BOOKMARKS -> state.bookmarks
                BookmarksTab.FAVORITES -> state.favorites
                BookmarksTab.TAGS -> state.tags
            }
            if (list.isLoadingMore) item(key = "more") { LoadingBlock() }
        }
    }
    remove?.let { item ->
        AlertDialog(
            onDismissRequest = { remove = null },
            title = { Text(UiCopy.REMOVE_CONFIRM) },
            dismissButton = { TextButton(onClick = { remove = null }) { Text(UiCopy.CANCEL) } },
            confirmButton = {
                TextButton(onClick = {
                    remove = null
                    when (item) {
                        is Bookmark -> model.removeBookmark(item)
                        is Favorite -> model.removeFavorite(item)
                        is FavoriteTag -> model.removeTag(item)
                    }
                }, modifier = Modifier.testTag("confirmRemove")) { Text(UiCopy.DELETE) }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TagRow(tag: FavoriteTag, onClick: () -> Unit, onLongClick: () -> Unit) {
    val colors = LocalEcencyColors.current
    Text(
        "#${tag.tag}", fontSize = 16.sp, color = colors.text,
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick, role = Role.Button)
            .padding(horizontal = 16.dp, vertical = 14.dp).testTag("tag:${tag.tag}"),
    )
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.lightBackground))
}

// ---- account list ----

/** RN accountList: a search over the given users ("Users (3)"), rows with how long ago. */
@Composable
fun AccountListScreen(env: AppEnvironment, title: String, users: List<AccountListEntry>, nowMillis: Long, onBack: () -> Unit, onUser: (String) -> Unit) {
    val colors = LocalEcencyColors.current
    var query by rememberSaveable { mutableStateOf("") }
    val header = title.ifEmpty { ProfileUiCopy.USERS }
    val visible = remember(users, query) { AccountListFilter.filter(users, query) }
    Column(Modifier.fillMaxSize().background(colors.background).statusBarsPadding().testTag("accountList")) {
        TopBar(header, onBack = onBack)
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true, placeholder = { Text("$header (${users.size})") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("accountSearch"),
        )
        LazyColumn(Modifier.fillMaxSize().padding(top = 16.dp)) {
            itemsIndexed(visible, key = { _, u -> u.account }) { i, u ->
                UserListItem(env, i, u.account, onClick = { onUser(u.account) },
                    description = u.timestampMillis.takeIf { it > 0 }?.let { TimeAgo.short(it, nowMillis) })
            }
        }
    }
}

/** The profile picker route of a chooser row (the link row opens the URL field instead). */
private fun com.ecency.core.app.MediaSource.profileSource(): com.ecency.core.app.ProfileImageSource = when (this) {
    com.ecency.core.app.MediaSource.CAMERA -> com.ecency.core.app.ProfileImageSource.CAMERA
    com.ecency.core.app.MediaSource.FILES -> com.ecency.core.app.ProfileImageSource.FILES
    else -> com.ecency.core.app.ProfileImageSource.GALLERY
}

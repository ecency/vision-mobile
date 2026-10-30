package com.ecency.app.ui

import androidx.compose.ui.semantics.LiveRegionMode

import androidx.compose.ui.semantics.liveRegion

import androidx.compose.ui.semantics.heading

import androidx.compose.ui.semantics.selected

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.ecency.core.app.ShellUiCopy

import androidx.compose.ui.semantics.Role

import com.ecency.core.app.AppIcon
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ecency.core.api.community.CommunityRole
import com.ecency.core.app.AccountLookupModel
import com.ecency.core.app.ActivityRow
import com.ecency.core.app.AppEnvironment
import com.ecency.core.app.AppScreen
import com.ecency.core.app.CommunitiesModel
import com.ecency.core.app.CommunityActivitiesModel
import com.ecency.core.app.CommunityCopy
import com.ecency.core.app.CommunityManageAction
import com.ecency.core.app.CommunityMembersModel
import com.ecency.core.app.CommunityModel
import com.ecency.core.app.CommunityRow
import com.ecency.core.app.CommunitySettingsForm
import com.ecency.core.app.CommunitySettingsModel
import com.ecency.core.app.CommunitySettingsState
import com.ecency.core.app.CommunityTab
import com.ecency.core.app.JoinedRow
import com.ecency.core.app.MemberRow
import com.ecency.core.app.ModNotes
import com.ecency.core.app.PostCard
import com.ecency.core.app.TimeAgo
import com.ecency.core.app.UiCopy

/** What the community and search screens need from the app root. */
class ScreenNav(
    val push: (AppScreen) -> Unit,
    val pop: () -> Unit,
    /** Signed out: the login screen. */
    val login: () -> Unit,
)

/**
 * The communities and search screens (this file and SearchScreens.kt), one entry point for the root's screen
 * switch. Each screen keeps its model and list positions in [entry], so going back restores them.
 */
@Composable
fun CommunityAndSearchScreen(screen: AppScreen, env: AppEnvironment, entry: StackEntry, now: Long, feedActions: FeedActions, nav: ScreenNav) {
    val colors = LocalEcencyColors.current
    Column(Modifier.fillMaxSize().background(colors.background).statusBarsPadding()) {
        when (screen) {
            AppScreen.Communities -> Guarded({ entry.get("model") { CommunitiesModel(env) } }) { CommunitiesScreen(it, env.proxyBase, entry, nav) }
            is AppScreen.Community -> Guarded({ entry.get("model") { CommunityModel(env, screen.name) } }) {
                CommunityScreen(it, env, entry, CommunityTab.of(screen.filter), now, feedActions, nav)
            }
            is AppScreen.CommunityMembers -> Guarded({ entry.get("model") { CommunityMembersModel(env, screen.name, screen.title) } }) {
                CommunityMembersScreen(it, env, entry, nav)
            }
            is AppScreen.CommunitySettings -> Guarded({ entry.get("model") { CommunitySettingsModel(env, screen.name) } }) { CommunitySettingsScreen(it, nav) }
            is AppScreen.CommunityActivities -> Guarded({ entry.get("model") { CommunityActivitiesModel(env, screen.name, screen.title) } }) {
                CommunityActivitiesScreen(it, env, entry, now, nav)
            }
            AppScreen.Search -> Guarded({ entry.get("model") { com.ecency.core.app.SearchModel(env) } }) { SearchScreen(it, env, entry, now, nav) }
            is AppScreen.TagResult -> Guarded({ entry.get("model") { com.ecency.core.app.TagResultModel(env, screen.tag, screen.filter) } }) {
                TagResultScreen(it, env, entry, now, feedActions, nav)
            }
            else -> ComingSoon(screen.route.title)
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// shared pieces

/** RN Tag with isFilter: Join filled blue (isPin), Leave outlined; a spinner while the change runs. */
@Composable
fun JoinButton(subscribed: Boolean, pending: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalEcencyColors.current
    if (pending) {
        Box(modifier.width(65.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(20.dp).testTag("joinPending"), color = colors.blueText, strokeWidth = 2.dp)
        }
        return
    }
    PillButton(if (subscribed) CommunityCopy.LEAVE else CommunityCopy.JOIN, filled = !subscribed, onClick = onClick, modifier = modifier.testTag("join"))
}

/** RN's filter Tag: a rounded pill, blue filled ([filled]) or blue outlined. */
@Composable
fun PillButton(label: String, filled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalEcencyColors.current
    Text(
        label,
        color = if (filled) Color.White else colors.blueText,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (filled) colors.blue else Color.Transparent)
            .border(1.dp, colors.blueText, RoundedCornerShape(50))
            .clickable(onClick = onClick, role = Role.Button)
            .padding(horizontal = 14.dp, vertical = 5.dp),
    )
}

/** RN TabBar: equal tabs with an underline under the selected one; [ids] name the tabs' test tags (not the copy). */
@Composable
fun UnderlineTabs(labels: List<String>, ids: List<String>, selected: Int, onSelect: (Int) -> Unit, tagPrefix: String = "tab") {
    val colors = LocalEcencyColors.current
    Row(Modifier.fillMaxWidth().background(colors.background)) {
        labels.forEachIndexed { i, label ->
            val on = i == selected
            Column(Modifier.weight(1f).clickable(role = Role.Tab) { onSelect(i) }.semantics { this.selected = on }.testTag("$tagPrefix:${ids[i]}"), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(label, color = if (on) colors.blueText else colors.darkGray, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.padding(vertical = 12.dp))
                Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp).heightIn(min = 2.dp, max = 2.dp).background(if (on) colors.blue else Color.Transparent))
            }
        }
    }
}

/** The home feed's pill tabs (TRENDING, HOT, NEW), shared with the community and tag screens; [ids] name the test tags. */
@Composable
fun PillTabs(labels: List<String>, ids: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val colors = LocalEcencyColors.current
    Row(Modifier.fillMaxWidth().background(colors.grayBackground).padding(horizontal = 12.dp, vertical = 8.dp)) {
        labels.forEachIndexed { index, label ->
            val on = index == selected
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    label.uppercase(), color = if (on) Color.White else colors.darkGray, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                    modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(if (on) colors.blue else Color.Transparent)
                        .clickable(role = Role.Tab) { onSelect(index) }.semantics { this.selected = on }.padding(horizontal = 20.dp, vertical = 4.dp).testTag("tab:${ids[index]}"),
                )
            }
        }
    }
}

/** Calls [onEnd] when the list's last 3 rows are on screen. */
@Composable
fun LoadMoreEffect(listState: LazyListState, key: Any?, onEnd: () -> Unit) {
    val nearEnd by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 3
        }
    }
    LaunchedEffect(nearEnd, key) { if (nearEnd) onEnd() }
}

/** RN's discover row (communitiesListItem): avatar, title and join button, about, separator, stats. */
@Composable
fun CommunityListRow(row: CommunityRow, index: Int, avatarUrl: String, onOpen: () -> Unit, onJoin: () -> Unit, showJoin: Boolean = true) {
    val colors = LocalEcencyColors.current
    Row(
        Modifier.fillMaxWidth().background(if (index % 2 != 0) colors.lightBackground else colors.background)
            .clickable(onClick = onOpen, role = Role.Button).padding(16.dp).testTag("community:${row.name}"),
    ) {
        Avatar(avatarUrl, 40.dp)
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(row.title, color = colors.blueText, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (showJoin) JoinButton(row.subscribed, row.pending, onJoin)
            }
            if (row.about.isNotEmpty()) Text(row.about, color = colors.text, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
            Box(Modifier.padding(vertical = 10.dp).width(100.dp).heightIn(min = 1.dp, max = 1.dp).background(colors.darkGray).align(Alignment.CenterHorizontally))
            Text(row.stats, color = colors.darkGray, fontSize = 14.sp, modifier = Modifier.padding(bottom = 10.dp))
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Communities (Membership, Discover)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunitiesScreen(model: CommunitiesModel, proxy: String, entry: StackEntry, nav: ScreenNav) {
    val state by model.state.collectAsState()
    LaunchedEffect(model) { model.start() }
    val tab = entry.get("tab") { mutableIntStateOf(0) }
    TopBar(CommunityCopy.TITLE, onBack = nav.pop)
    UnderlineTabs(listOf(CommunityCopy.JOINED_TAB, CommunityCopy.DISCOVER_TAB), listOf("joined", "discover"), tab.intValue, { tab.intValue = it })
    PullToRefreshBox(isRefreshing = false, onRefresh = model::refresh, modifier = Modifier.fillMaxSize()) {
        if (tab.intValue == 0) {
            LazyColumn(Modifier.fillMaxSize().testTag("joinedList"), state = entry.get("joined") { LazyListState() }) {
                when {
                    state.joined.isEmpty() && state.joinedLoading -> item { LoadingBlock(Modifier.padding(top = 48.dp)) }
                    state.joined.isEmpty() && state.joinedError != null -> item { ErrorBlock(state.joinedError!!, Modifier.padding(top = 48.dp), com.ecency.core.esie.EsiePlacement.ERROR, model::refresh) }
                    state.joined.isEmpty() -> item {
                        EsieMessageBlock(com.ecency.core.esie.EsiePlacement.EMPTY, CommunityCopy.NO_COMMUNITIES, Modifier.padding(top = 48.dp), CommunityCopy.DISCOVER_COMMUNITIES) { tab.intValue = 1 }
                    }
                }
                itemsIndexed(state.joined, key = { _, r -> r.name }) { index, row ->
                    JoinedListRow(row, index, PostCard.avatarUrl(row.name, proxy), { nav.push(AppScreen.Community(row.name)) }) {
                        if (!model.toggleJoined(row)) nav.login()
                    }
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().testTag("discoverList"), state = entry.get("discover") { LazyListState() }) {
                when {
                    state.discover.isEmpty() && state.discoverLoading -> item { LoadingBlock(Modifier.padding(top = 48.dp)) }
                    state.discover.isEmpty() && state.discoverError != null -> item { ErrorBlock(state.discoverError!!, Modifier.padding(top = 48.dp), com.ecency.core.esie.EsiePlacement.ERROR, model::refresh) }
                }
                itemsIndexed(state.discover, key = { _, r -> r.name }) { index, row ->
                    CommunityListRow(row, index, PostCard.avatarUrl(row.name, proxy), { nav.push(AppScreen.Community(row.name)) }, {
                        if (!model.toggle(row)) nav.login()
                    })
                }
            }
        }
    }
}

@Composable
private fun JoinedListRow(row: JoinedRow, index: Int, avatarUrl: String, onOpen: () -> Unit, onJoin: () -> Unit) {
    val colors = LocalEcencyColors.current
    Row(
        Modifier.fillMaxWidth().background(if (index % 2 != 0) colors.lightBackground else colors.background)
            .padding(horizontal = 16.dp, vertical = 12.dp).testTag("joined:${row.name}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f).clickable(onClick = onOpen, role = Role.Button), verticalAlignment = Alignment.CenterVertically) {
            Avatar(avatarUrl, 36.dp)
            Text(row.title, color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 12.dp))
        }
        JoinButton(row.subscribed, row.pending, onJoin)
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Community page

@Composable
fun CommunityScreen(
    model: CommunityModel,
    env: AppEnvironment,
    entry: StackEntry,
    initialTab: CommunityTab,
    now: Long,
    feedActions: FeedActions,
    nav: ScreenNav,
) {
    val state by model.state.collectAsState()
    val colors = LocalEcencyColors.current
    LaunchedEffect(model) { model.start() }
    val tab = entry.get("tab") { mutableIntStateOf(initialTab.ordinal) }
    var manage by remember { mutableStateOf(false) }
    TopBar(state.headerTitle, onBack = nav.pop) {
        if (state.isModerator) {
            Box(Modifier.clip(RoundedCornerShape(50)).clickable(role = Role.Button) { manage = true }.padding(12.dp).testTag("manage")) {
                Glyph(AppIcon.MODERATOR, colors.darkGray, 24.dp, description = CommunityCopy.MANAGE)
            }
        }
    }
    val selected = CommunityTab.entries[tab.intValue]
    val listState = entry.get("list:${selected.name}") { LazyListState() }
    // RN CollapsibleCard "Details" above the tabs: it closes as the feed scrolls down and opens near the top, after
    // a drag up or on a tap on its title, the same rule as the profile summary (RN closed it on any drag)
    val collapse = rememberSummaryCollapse(entry.get("summary") { SummaryCollapseState() }, listState)
    CommunityDetails(
        state, collapse, onJoin = { if (!model.toggleSubscription()) nav.login() },
        onNewPost = { nav.push(model.newPostScreen) },
        // RN community header: the community's digest sheet
        onNewsletter = { env.digest.show("community", state.name) },
        onRetry = model::retry,
    )
    // another tab's list: the card opens over it (the three ways in agree)
    PillTabs(CommunityTab.entries.map { it.label }, CommunityTab.entries.map { it.name }, tab.intValue, { if (it != tab.intValue) collapse.reset(); tab.intValue = it })
    key(selected) {
        Guarded({ entry.get("feed:${selected.name}") { env.feedModel(model.feedSource(selected)) } }) { feed ->
            FeedList(feed, listState, env.votes.state, now, feedActions, collapse = collapse, scrollTopModifier = Modifier.navigationBarsPadding())
        }
    }
    if (manage) {
        CommunityManageSheet(onPick = { manage = false; nav.push(model.screenFor(it)) }, onDismiss = { manage = false })
    }
}

/** RN CollapsibleCard "Details": description, stats, then Join / New Post / Newsletter; the title opens and closes it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CommunityDetails(
    state: com.ecency.core.app.CommunityState,
    collapse: SummaryCollapseState,
    onJoin: () -> Unit,
    onNewPost: () -> Unit,
    onNewsletter: () -> Unit,
    onRetry: () -> Unit,
) {
    val colors = LocalEcencyColors.current
    val community = state.community
    Column(Modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(8.dp)).background(colors.lightBackground).testTag("communityDetails")) {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = collapse::toggle)
                .semantics { stateDescription = if (collapse.open) ShellUiCopy.EXPANDED else ShellUiCopy.COLLAPSED }
                .padding(12.dp).testTag("communityDetailsToggle"),
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(CommunityCopy.DETAILS, color = colors.text, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.semantics { heading() })
        }
        when {
            community == null && state.error != null -> ErrorBlock(state.error!!, esie = com.ecency.core.esie.EsiePlacement.ERROR, onRetry = onRetry)
            community == null -> LoadingBlock()
            else -> CollapsingSummary(collapse) {
                Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp).testTag("communityDetailsBody")) {
                    Box(Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState())) {
                        Text(community.description, color = colors.text, fontSize = 14.sp, modifier = Modifier.testTag("communityDescription"))
                    }
                    HorizontalDivider(Modifier.padding(vertical = 10.dp), color = colors.border)
                    Text(state.stats.orEmpty(), color = colors.darkGray, fontSize = 14.sp)
                    HorizontalDivider(Modifier.padding(vertical = 10.dp), color = colors.border)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.loggedIn) JoinButton(state.subscribed, state.pending, onJoin)
                        PillButton(CommunityCopy.NEW_POST, filled = true, onClick = onNewPost, modifier = Modifier.testTag("newPost"))
                        if (state.loggedIn) PillButton(CommunityCopy.NEWSLETTER, filled = false, onClick = onNewsletter, modifier = Modifier.testTag("newsletter"))
                    }
                }
            }
        }
    }
}

/**
 * The one layout of the community moderation sheets (manage, set role, mod notes), after RN communityRoleEditSheet:
 * a centred title, an optional subtitle (the account or post it acts on), the sheet's own rows, then Cancel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModerationSheet(
    title: String,
    subtitle: String?,
    tag: String,
    cancelTag: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalEcencyColors.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.background) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding().imePadding().padding(bottom = 24.dp).testTag(tag)) {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = colors.text, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() }.fillMaxWidth())
            if (subtitle != null) {
                Text(subtitle, fontSize = 14.sp, color = colors.darkGray, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 16.dp))
            } else {
                Spacer(Modifier.height(12.dp))
            }
            content()
            Text(
                CommunityCopy.CANCEL, fontSize = 16.sp, color = colors.darkGray, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clickable(onClick = onDismiss, role = Role.Button).padding(vertical = 14.dp).testTag(cancelTag),
            )
        }
    }
}

/** A row of a moderation sheet: an optional icon, the label, and a check on the current choice. */
@Composable
private fun ModerationRow(
    label: String,
    tag: String,
    enabled: Boolean = true,
    icon: AppIcon? = null,
    checked: Boolean = false,
    emphasis: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = LocalEcencyColors.current
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(vertical = 14.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { Glyph(it, if (enabled) colors.darkGray else colors.border, 22.dp, Modifier.padding(end = 14.dp)) }
        Text(
            label, fontSize = 16.sp, fontWeight = if (emphasis && enabled) FontWeight.Bold else FontWeight.Normal,
            color = when {
                !enabled -> colors.darkGray
                emphasis -> colors.blueText
                else -> colors.text
            },
            modifier = Modifier.weight(1f),
        )
        if (checked) Glyph(AppIcon.CHECK, colors.blueText, 20.dp)
    }
}

/** RN communityManageSheet: members and roles, settings, activity log. */
@Composable
fun CommunityManageSheet(onPick: (CommunityManageAction) -> Unit, onDismiss: () -> Unit) {
    ModerationSheet(CommunityCopy.MANAGE, subtitle = null, tag = "manageSheet", cancelTag = "manageCancel", onDismiss = onDismiss) {
        CommunityManageAction.entries.forEach { action ->
            ModerationRow(action.label, "manage:${action.name}", icon = action.icon) { onPick(action) }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Members and roles

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunityMembersScreen(model: CommunityMembersModel, env: AppEnvironment, entry: StackEntry, nav: ScreenNav) {
    val state by model.state.collectAsState()
    val colors = LocalEcencyColors.current
    LaunchedEffect(model) { model.start() }
    var editing by remember { mutableStateOf<MemberRow?>(null) }
    var assigning by remember { mutableStateOf(false) }
    TopBar(state.headerTitle, onBack = nav.pop) {
        if (state.assignableRoles.isNotEmpty()) {
            Box(Modifier.clip(RoundedCornerShape(50)).clickable(role = Role.Button) { assigning = true }.padding(12.dp).testTag("assignRole")) {
                Glyph(AppIcon.PERSON_ADD, colors.darkGray, 24.dp, description = CommunityCopy.ASSIGN_ROLE)
            }
        }
    }
    val listState = entry.get("list") { LazyListState() }
    LoadMoreEffect(listState, state.members.size) { model.loadMore() }
    PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = model::refresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().testTag("members"), state = listState) {
            if (state.loading) item { LoadingBlock(Modifier.padding(top = 48.dp)) }
            state.emptyText?.let { text -> item { EsieMessageBlock(com.ecency.core.esie.EsiePlacement.EMPTY, text, Modifier.padding(top = 48.dp)) } }
            itemsIndexed(state.members, key = { _, m -> m.account }) { index, member ->
                Row(
                    Modifier.fillMaxWidth().background(if (index % 2 != 0) colors.lightBackground else colors.background)
                        .clickable(role = Role.Button) { nav.push(AppScreen.Profile(member.account)) }.padding(horizontal = 16.dp, vertical = 10.dp)
                        .testTag("member:${member.account}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(PostCard.avatarUrl(member.account, env.proxyBase), 36.dp)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text("@${member.account}", color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        if (member.title.isNotEmpty()) Text(member.title, color = colors.darkGray, fontSize = 13.sp)
                    }
                    if (member.account in state.saving) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = colors.blueText, strokeWidth = 2.dp)
                    } else {
                        RoleChip(member) { editing = member }
                    }
                }
            }
            if (state.loadingMore) item { LoadingBlock() }
            state.footerNote?.let { note -> item { MessageBlock(note) } }
        }
    }
    editing?.let { member ->
        RoleSheet(env, member.account, member.role, state.assignableRoles, onPick = { account, role -> editing = null; if (role != member.role) model.setRole(account, role) }, onDismiss = { editing = null })
    }
    if (assigning) {
        RoleSheet(env, null, null, state.assignableRoles, onPick = { account, role -> assigning = false; model.setRole(account, role) }, onDismiss = { assigning = false })
    }
    state.alert?.let { message ->
        AlertDialog(
            onDismissRequest = model::dismissAlert,
            title = { Text(CommunityCopy.FAIL) },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = model::dismissAlert) { Text(UiCopy.OK) } },
        )
    }
}

@Composable
private fun RoleChip(member: MemberRow, onEdit: () -> Unit) {
    val colors = LocalEcencyColors.current
    val base = Modifier.clip(RoundedCornerShape(12.dp))
        .background(colors.lightBackground)
        .then(if (member.editable) Modifier.border(1.dp, colors.blueText, RoundedCornerShape(12.dp)).clickable(onClick = onEdit, role = Role.Button) else Modifier)
        .padding(horizontal = 10.dp, vertical = 4.dp)
        .testTag("role:${member.account}")
    Text(member.roleLabel, fontSize = 12.sp, color = if (member.editable) colors.blueText else colors.darkGray, fontWeight = FontWeight.Bold, modifier = base)
}

/**
 * RN communityRoleEditSheet. With [account] it sets that member's role (the current one checked and disabled);
 * without, the moderator types any account (suggestions from `lookup_accounts`, only an exact match counts).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoleSheet(
    env: AppEnvironment,
    account: String?,
    currentRole: CommunityRole?,
    roles: List<CommunityRole>,
    onPick: (String, CommunityRole) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalEcencyColors.current
    val lookup = remember { AccountLookupModel(env) }
    androidx.compose.runtime.DisposableEffect(lookup) { onDispose { lookup.close() } }
    val lookupState by lookup.state.collectAsState()
    var error by remember { mutableStateOf<String?>(null) }
    ModerationSheet(
        if (account == null) CommunityCopy.ASSIGN_ROLE else CommunityCopy.SET_ROLE, subtitle = account?.let { "@$it" },
        tag = "roleSheet", cancelTag = "roleCancel", onDismiss = onDismiss,
    ) {
        if (account == null) {
            OutlinedTextField(
                value = lookupState.input,
                onValueChange = { error = null; lookup.setInput(it) },
                placeholder = { Text(CommunityCopy.USERNAME_PLACEHOLDER) },
                singleLine = true,
                textStyle = ltrFieldStyle(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                trailingIcon = {
                    when {
                        lookupState.searching -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        lookupState.known -> Glyph(AppIcon.CHECK, colors.blueText, 20.dp)
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag("roleAccount"),
            )
            error?.let { Text(it, color = colors.redText, fontSize = 12.sp, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.padding(top = 6.dp)) }
            if (lookupState.suggestions.isNotEmpty() && !lookupState.known) {
                Column(Modifier.padding(top = 8.dp).clip(RoundedCornerShape(8.dp)).background(colors.lightBackground)) {
                    lookupState.suggestions.forEach { name ->
                        Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { lookup.pick(name) }.padding(horizontal = 12.dp, vertical = 10.dp).testTag("suggestion:$name"), verticalAlignment = Alignment.CenterVertically) {
                            Avatar(PostCard.avatarUrl(name, env.proxyBase), 24.dp)
                            Text(name, fontSize = 15.sp, color = colors.text, modifier = Modifier.padding(start = 10.dp))
                        }
                    }
                }
            }
            Text(CommunityCopy.ASSIGN_ROLE_HINT, fontSize = 12.sp, color = colors.darkGray, modifier = Modifier.padding(vertical = 8.dp))
        }
        roles.forEach { role ->
            val current = account != null && role == currentRole
            val blocked = current || (account == null && !lookupState.known)
            ModerationRow(role.label, "roleOption:${role.value}", enabled = !blocked, checked = current) {
                val target = account ?: lookupState.input
                if (account == null && (target.isEmpty() || !lookupState.known)) error = CommunityCopy.INVALID_USERNAME
                else onPick(target, role)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Settings

@Composable
fun CommunitySettingsScreen(model: CommunitySettingsModel, nav: ScreenNav) {
    val state by model.state.collectAsState()
    val colors = LocalEcencyColors.current
    LaunchedEffect(model) { model.start() }
    LaunchedEffect(state.saved) { if (state.saved) nav.pop() }
    TopBar(CommunitySettingsState.HEADER, onBack = nav.pop)
    state.notice?.let { Text(it, color = colors.darkGray, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("settingsNotice")) }
    when {
        state.loading -> LoadingBlock(Modifier.padding(top = 48.dp))
        state.error != null -> ErrorBlock(state.error!!, Modifier.padding(top = 48.dp), com.ecency.core.esie.EsiePlacement.ERROR, model::retry)
        else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp).testTag("settingsForm")) {
            val f = state.form
            SettingsField(CommunitySettingsState.TITLE_LABEL, f.title, state.canEdit, f.titleError, "title") { model.update(f.copy(title = it)) }
            SettingsField(CommunitySettingsState.ABOUT_LABEL, f.about, state.canEdit, f.aboutError, "about") { model.update(f.copy(about = it)) }
            SettingsField(CommunitySettingsState.LANG_LABEL, f.lang, state.canEdit, f.langError, "lang") { model.update(f.copy(lang = it)) }
            SettingsField(CommunitySettingsState.DESCRIPTION_LABEL, f.description, state.canEdit, f.descriptionError, "description", multiline = true) { model.update(f.copy(description = it)) }
            SettingsField(CommunitySettingsState.RULES_LABEL, f.rules, state.canEdit, f.rulesError, "rules", multiline = true) { model.update(f.copy(rules = it)) }
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(CommunitySettingsState.NSFW_LABEL, color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Switch(
                    checked = f.isNsfw, onCheckedChange = { if (state.canEdit) model.update(f.copy(isNsfw = it)) }, enabled = state.canEdit,
                    colors = SwitchDefaults.colors(checkedTrackColor = colors.blue), modifier = Modifier.testTag("nsfw"),
                )
            }
            if (state.canEdit) {
                Button(
                    onClick = model::save, enabled = f.isValid && !state.saving,
                    colors = ButtonDefaults.buttonColors(contentColor = androidx.compose.ui.graphics.Color.White, containerColor = colors.blue),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("saveSettings"),
                ) {
                    if (state.saving) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                    else Text(CommunitySettingsState.SAVE, color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
    state.alert?.let { message ->
        AlertDialog(
            onDismissRequest = model::dismissAlert,
            title = { Text(CommunityCopy.FAIL) },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = model::dismissAlert) { Text(UiCopy.OK) } },
        )
    }
}

@Composable
private fun SettingsField(label: String, value: String, editable: Boolean, error: String?, tag: String, multiline: Boolean = false, onChange: (String) -> Unit) {
    val colors = LocalEcencyColors.current
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Text(label, color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 4.dp))
        OutlinedTextField(
            value = value, onValueChange = onChange, enabled = editable, isError = error != null, singleLine = !multiline,
            minLines = if (multiline) 4 else 1,
            modifier = Modifier.fillMaxWidth().testTag("field:$tag"),
        )
        error?.let { Text(it, color = colors.redText, fontSize = 12.sp, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.padding(top = 4.dp).testTag("error:$tag")) }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Activity log

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunityActivitiesScreen(model: CommunityActivitiesModel, env: AppEnvironment, entry: StackEntry, now: Long, nav: ScreenNav) {
    val state by model.state.collectAsState()
    LaunchedEffect(model) { model.start() }
    TopBar(state.headerTitle, onBack = nav.pop)
    val listState = entry.get("list") { LazyListState() }
    LoadMoreEffect(listState, state.items.size) { model.loadMore() }
    PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = model::refresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().testTag("activities"), state = listState) {
            if (state.loading) item { LoadingBlock(Modifier.padding(top = 48.dp)) }
            state.emptyText?.let { text -> item { EsieMessageBlock(com.ecency.core.esie.EsiePlacement.EMPTY, text, Modifier.padding(top = 48.dp)) } }
            itemsIndexed(state.items, key = { _, r -> r.id }) { _, row -> ActivityRowView(row, env.proxyBase, now, nav) }
            if (state.loadingMore) item { LoadingBlock() }
        }
    }
}

@Composable
private fun ActivityRowView(row: ActivityRow, proxy: String, now: Long, nav: ScreenNav) {
    val colors = LocalEcencyColors.current
    val text = remember(row, colors) {
        buildAnnotatedString {
            row.parts.forEach { part ->
                if (part.account == null) {
                    append(part.text)
                } else {
                    val target = if (part.permlink != null) AppScreen.Post(part.account!!, part.permlink!!) else AppScreen.Profile(part.account!!)
                    withLink(LinkAnnotation.Clickable(part.text, TextLinkStyles(SpanStyle(color = colors.blueText))) { nav.push(target) }) { append(part.text) }
                }
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).testTag("activity:${row.id}")) {
        Box(Modifier.width(36.dp)) { row.actor?.let { Avatar(PostCard.avatarUrl(it, proxy), 28.dp) } }
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(text, color = colors.text, fontSize = 14.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
            Text(TimeAgo.short(row.epochMillis, now), color = colors.darkGray, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Mod notes (for the post menu's mute and unmute)

/**
 * RN modNotesSheet: the reason a mute or unmute carries (hivemind requires one; 120 characters, as web). [onConfirm]
 * gets the trimmed note; Cancel, back and swipe call [onDismiss] only, so a dismissal never broadcasts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModNotesSheet(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    title: String = ModNotes.DEFAULT_TITLE,
    description: String? = null,
    placeholder: String = ModNotes.DEFAULT_PLACEHOLDER,
    confirmLabel: String = UiCopy.CONFIRM,
) {
    val colors = LocalEcencyColors.current
    var value by remember { mutableStateOf("") }
    val notes = ModNotes.notes(value)
    ModerationSheet(title, subtitle = description, tag = "modNotes", cancelTag = "modNotesCancel", onDismiss = onDismiss) {
        OutlinedTextField(
            value = value, onValueChange = { value = ModNotes.limit(it) }, placeholder = { Text(placeholder) }, singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("modNotesInput"),
        )
        Text("${ModNotes.count(value)} / ${ModNotes.MAX_LENGTH}", fontSize = 12.sp, color = colors.darkGray, textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
        ModerationRow(confirmLabel, "modNotesConfirm", enabled = notes != null, emphasis = true) { notes?.let(onConfirm) }
    }
}

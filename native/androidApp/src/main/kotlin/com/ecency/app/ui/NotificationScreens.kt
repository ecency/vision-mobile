package com.ecency.app.ui

import androidx.compose.ui.semantics.customActions


import androidx.compose.ui.semantics.Role

import com.ecency.core.app.AppIcon
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.ecency.core.api.ecency.LeaderboardEntry
import com.ecency.core.api.ecency.NotificationTime
import com.ecency.core.app.AppEnvironment
import com.ecency.core.app.LeaderboardDuration
import com.ecency.core.app.LeaderboardModel
import com.ecency.core.app.NavEntry
import com.ecency.core.app.NotificationFilter
import com.ecency.core.app.NotificationRow
import com.ecency.core.app.PostCard
import com.ecency.core.app.PushType
import kotlinx.coroutines.launch
import com.ecency.core.app.ReadingUiCopy
import com.ecency.core.app.UiCopy

/** RN `$primaryLightBlue` and `$notificationBorder`: an unread row. */
private val UnreadLight = Color(0xFFEAF2FC)
private val UnreadDark = Color(0xFF2E3D51)

/** RN `$primaryGreen`: the quests check on the daily leaderboard. */
private val QuestGreen = Color(0xFF4FD688)

/**
 * RN notification screen: the Notifications and Leaderboard tabs. Notifications needs an account ("Login to
 * see"), the leaderboard is public. Rows are drawn only while the model's account is the signed-in one.
 */
@Composable
fun NotificationsTab(
    env: AppEnvironment,
    entry: StackEntry,
    username: String?,
    now: Long,
    onOpen: (NavEntry) -> Unit,
    onProfile: (String) -> Unit,
    onLogin: () -> Unit,
) {
    val colors = LocalEcencyColors.current
    val page = entry.get("notifications:page") { mutableIntStateOf(0) }
    val listState = entry.get("notifications:list") { LazyListState() }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().background(colors.background).testTag("notificationsTab")) {
        Row(Modifier.fillMaxWidth()) {
            listOf(ReadingUiCopy.NOTIFICATIONS, ReadingUiCopy.LEADERBOARD).forEachIndexed { index, title ->
                val active = page.intValue == index
                Column(
                    Modifier.weight(1f).clickable(role = Role.Tab) {
                        // RN: tapping the selected tab scrolls its list to the top
                        if (active && index == 0) scope.launch { listState.animateScrollToItem(0) } else page.intValue = index
                    }.semantics { selected = active }.testTag("notificationsPage:" + listOf("Notifications", "Leaderboard")[index]),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        title, color = if (active) colors.blueText else colors.darkGray, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                    Box(Modifier.fillMaxWidth().height(2.dp).background(if (active) colors.blue else Color.Transparent))
                }
            }
        }
        if (page.intValue == 0) {
            if (username == null) LoginPrompt(ReadingUiCopy.LOGIN_TO_SEE, onLogin)
            else NotificationList(env, username, listState, now, onOpen, onProfile)
        } else {
            Guarded({ entry.get("leaderboard") { env.leaderboardModel() } }) { model -> Leaderboard(model, env, onProfile) }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun FilterBar(
    selected: String,
    options: List<String>,
    onSelect: (Int) -> Unit,
    tag: String,
    chips: @Composable androidx.compose.foundation.layout.FlowRowScope.() -> Unit = {},
    trailing: @Composable () -> Unit = {},
) {
    val colors = LocalEcencyColors.current
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().background(colors.grayBackground).padding(horizontal = 16.dp).heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // the menu and its quick chips wrap to a second line on a narrow screen or with large text, never clip
        androidx.compose.foundation.layout.FlowRow(
            Modifier.weight(1f).padding(vertical = 2.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp),
        ) {
        Box(Modifier.align(Alignment.CenterVertically)) {
            Row(
                Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { open = true }.padding(horizontal = 8.dp, vertical = 8.dp).testTag(tag),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(selected, color = colors.darkGray, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Glyph(AppIcon.DROPDOWN, colors.darkGray, 22.dp)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 420.dp)) {
                options.forEachIndexed { index, label ->
                    DropdownMenuItem(
                        text = { Text(label, fontWeight = if (label == selected) FontWeight.Bold else FontWeight.Normal) },
                        onClick = { open = false; onSelect(index) },
                        modifier = Modifier.testTag("$tag:$index"),
                    )
                }
            }
        }
        chips()
        }
        trailing()
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun NotificationList(
    env: AppEnvironment,
    username: String,
    listState: LazyListState,
    now: Long,
    onOpen: (NavEntry) -> Unit,
    onProfile: (String) -> Unit,
) {
    val colors = LocalEcencyColors.current
    val model = env.notifications
    val state by model.state.collectAsState()
    LaunchedEffect(username) { model.show() }
    // another account's rows never draw, not even for the frame in which the account changes
    val mine = state.account == username
    val items = if (mine) state.items else emptyList()

    // near the end means one of the last rows is on screen: an empty view's placeholder and footer never ask for more
    val tail by androidx.compose.runtime.rememberUpdatedState(items.takeLast(4).map { it.id }.toSet())
    val nearEnd by remember(listState) {
        derivedStateOf { tail.isNotEmpty() && listState.layoutInfo.visibleItemsInfo.any { it.key in tail } }
    }
    LaunchedEffect(nearEnd, state.pages) { if (nearEnd) model.loadMore() }
    LaunchedEffect(state.filter) { listState.scrollToItem(0) }

    Column(Modifier.fillMaxSize()) {
        FilterBar(
            state.filter.title, NotificationFilter.entries.map { it.title }, { model.selectFilter(NotificationFilter.entries[it]) }, "notificationFilter",
            chips = {
                NotificationFilter.quick.forEach { filter ->
                    QuickFilterChip(filter.label, filter == state.filter, "notificationChip:${filter.name}") { model.toggleQuickFilter(filter) }
                }
            },
        ) {
            Box(
                Modifier.clip(RoundedCornerShape(50)).clickable(role = Role.Button) { model.markAllRead() }.padding(8.dp)
                    .semantics { contentDescription = ReadingUiCopy.MARK_ALL_AS_READ }.testTag("markAllRead"),
            ) { Glyph(AppIcon.MARK_ALL_READ, colors.icon, 24.dp) }
        }
        PullToRefreshBox(
            isRefreshing = mine && state.isRefreshing,
            onRefresh = model::refresh,
            modifier = Modifier.fillMaxSize().testTag("notificationList"),
        ) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag("notificationRows")) {
                when {
                    !mine || (items.isEmpty() && (state.isLoading || !state.loaded)) -> item(key = "loading") { RowsSkeleton(8, tag = "skeleton:notifications") }
                    items.isEmpty() && state.error != null -> item(key = "error") {
                        // RN QueryErrorRetry
                        EsieMessageBlock(com.ecency.core.esie.EsiePlacement.ERROR, ReadingUiCopy.LOAD_FAILED_RETRY, Modifier.padding(top = 48.dp), UiCopy.TRY_AGAIN_QUESTION, model::retry)
                    }
                    items.isEmpty() -> item(key = "empty") { EsieMessageBlock(com.ecency.core.esie.EsiePlacement.EMPTY, ReadingUiCopy.NO_RECENT_ACTIVITY, Modifier.padding(top = 48.dp)) }
                }
                items(items, key = { it.id }) { row ->
                    SwipeToRead(row, onRead = { model.markRead(row.id) }) {
                        NotificationLine(env, row, now, state.showImages, onTap = { model.open(row.id)?.let(onOpen) }, onAvatar = { onProfile(row.source) }, onRead = { model.markRead(row.id) })
                    }
                }
                if (state.isLoadingMore) item(key = "more") { LoadingBlock() }
                item(key = "bottom") { Spacer(Modifier.height(60.dp)) }
            }
        }
    }
}

/** A quick filter next to the menu (Replies, Mentions): blue filled while selected; tapped again it goes back to All. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun androidx.compose.foundation.layout.FlowRowScope.QuickFilterChip(label: String, on: Boolean, tag: String, onToggle: () -> Unit) {
    val colors = LocalEcencyColors.current
    Box(
        // a 48 dp touch area around the 32 dp pill
        Modifier.align(Alignment.CenterVertically)
            .testTag(tag)
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(50))
            .background(if (on) colors.blue else Color.Transparent)
            .border(1.dp, colors.blueText, RoundedCornerShape(50))
            .toggleable(value = on, role = Role.Checkbox, onValueChange = { onToggle() })
            .heightIn(min = 32.dp)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (on) Color.White else colors.blueText, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * An unread row swipes from the end towards the start (right to left, mirrored in RTL) to be marked read; the row
 * springs back and stays. TalkBack has the same as a custom action.
 */
@Composable
private fun SwipeToRead(row: NotificationRow, onRead: () -> Unit, content: @Composable () -> Unit) {
    if (row.read) {
        content()
        return
    }
    val colors = LocalEcencyColors.current
    val swipe = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) onRead()
            false
        },
    )
    SwipeToDismissBox(
        state = swipe,
        enableDismissFromStartToEnd = false,
        modifier = Modifier.testTag("swipeToRead:${row.id}"),
        backgroundContent = {
            Row(
                Modifier.fillMaxSize().background(colors.blue).padding(horizontal = 24.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Glyph(AppIcon.MARK_ALL_READ, Color.White, 22.dp)
                Spacer(Modifier.width(8.dp))
                Text(ReadingUiCopy.MARK_AS_READ, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        },
    ) { content() }
}

/** RN notificationLineView: avatar, "name text", the post line, a description, the time and a post thumbnail. */
@Composable
private fun NotificationLine(env: AppEnvironment, row: NotificationRow, now: Long, showImages: Boolean, onTap: () -> Unit, onAvatar: () -> Unit, onRead: () -> Unit) {
    val colors = LocalEcencyColors.current
    val unread = if (colors.isDark) UnreadDark else UnreadLight
    Row(
        Modifier.fillMaxWidth().background(if (row.read) colors.background else unread).clickable(onClick = onTap, role = Role.Button)
            .heightIn(min = 64.dp).padding(vertical = 12.dp)
            .semantics {
                stateDescription = if (row.read) ReadingUiCopy.READ else ReadingUiCopy.UNREAD
                // the avatar's tap and the swipe as actions: the row stays one TalkBack stop
                customActions = listOfNotNull(
                    a11yAction(UiCopy.profileOf(row.source), onAvatar),
                    if (row.read) null else a11yAction(ReadingUiCopy.MARK_AS_READ, onRead),
                )
            }.testTag("notification:${row.id}"),
        verticalAlignment = Alignment.Top,
    ) {
        Avatar(row.avatarUrl, 32.dp, Modifier.padding(start = 24.dp).clip(RoundedCornerShape(50)).avatarButton(row.source, onAvatar).then(HiddenFromA11y).testTag("notificationAvatar:${row.id}"))
        Column(Modifier.weight(1f).padding(start = 16.dp, end = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = colors.text)) { append(row.source) }
                        append(" ")
                        append(row.text)
                    },
                    color = colors.darkGray, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).testTag("notificationText"),
                )
                ProBadge(env, row.source, 13.dp)
            }
            if (row.moreInfo.isNotEmpty()) {
                Text(row.moreInfo, color = colors.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
            if (row.description.isNotEmpty()) {
                Text(row.description, color = colors.text, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
            notificationTime(row.timestampMillis, now)?.let {
                Text(it, color = colors.darkGray, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp).testTag("notificationTime"))
            }
        }
        // RN "Show Images" off: the thumbnail is simply left out
        if (showImages && row.imageUrl.isNotEmpty()) {
            AsyncImage(
                model = row.imageUrl, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.padding(end = 24.dp).size(32.dp).clip(RoundedCornerShape(8.dp)).background(colors.lightBackground).testTag("notificationImage:${row.id}"),
            )
        }
    }
}

/** RN formatNotificationTimestamp: relative for a week, then the date and time, both in the app language. */
internal fun notificationTime(epochMillis: Long, now: Long): String? {
    if (epochMillis <= 0) return null
    return NotificationTime.relative(epochMillis, now)
        ?: com.ecency.core.l10n.LocaleFormat.dateTime(epochMillis)
}

/** RN leaderboard: the top users by points for a day, week or month. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun Leaderboard(model: LeaderboardModel, env: AppEnvironment, onProfile: (String) -> Unit) {
    val colors = LocalEcencyColors.current
    val state by model.state.collectAsState()
    LaunchedEffect(model) { model.start() }
    Column(Modifier.fillMaxSize()) {
        FilterBar(state.duration.label, LeaderboardDuration.entries.map { it.label }, { model.select(LeaderboardDuration.entries[it]) }, "leaderboardFilter")
        Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(ReadingUiCopy.TOP_USERS, color = colors.darkGray, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f).padding(start = 20.dp))
            Text(ReadingUiCopy.ACTIVITIES, color = colors.darkGray, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.width(80.dp))
            Text(ReadingUiCopy.LEADERBOARD_REWARD, color = colors.darkGray, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.width(88.dp))
        }
        PullToRefreshBox(isRefreshing = state.isLoading && state.users.isNotEmpty(), onRefresh = model::refresh, modifier = Modifier.fillMaxSize().testTag("leaderboard")) {
            LazyColumn(Modifier.fillMaxSize()) {
                when {
                    state.users.isEmpty() && (state.isLoading || !state.loaded) -> item(key = "loading") { LoadingBlock(Modifier.padding(top = 48.dp)) }
                    state.users.isEmpty() -> item(key = "empty") { EsieMessageBlock(com.ecency.core.esie.EsiePlacement.EMPTY, UiCopy.NOTHING_HERE, Modifier.padding(top = 48.dp)) }
                }
                itemsIndexed(state.users, key = { _, user -> user.username }) { index, user ->
                    LeaderboardRow(env, user, index, state.showsQuestCheck, onProfile, model::showEarnHint)
                }
                item(key = "bottom") { Spacer(Modifier.height(60.dp)) }
            }
        }
    }
}

@Composable
private fun LeaderboardRow(env: AppEnvironment, user: LeaderboardEntry, index: Int, questCheck: Boolean, onProfile: (String) -> Unit, onReward: () -> Unit) {
    val colors = LocalEcencyColors.current
    Row(
        Modifier.fillMaxWidth().background(if (index % 2 == 1) colors.lightBackground else colors.background)
            .clickable(role = Role.Button) { onProfile(user.username) }.padding(horizontal = 16.dp, vertical = 10.dp).testTag("leader:${user.username}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
            if (questCheck && user.questsDone) Glyph(AppIcon.CHECK_CIRCLE, QuestGreen, 16.dp, description = ReadingUiCopy.QUESTS_DONE, filled = true)
            else Text(com.ecency.core.l10n.LocaleFormat.integer((index + 1).toLong()), color = colors.darkGray, fontSize = 11.sp)
        }
        Spacer(Modifier.width(13.dp))
        Avatar(PostCard.avatarUrl(user.username, env.proxyBase), 32.dp)
        Text(user.username, color = colors.text, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 12.dp))
        Text(user.count, color = colors.darkGray, fontSize = 14.sp, modifier = Modifier.width(80.dp))
        // RN: a tap on the reward shows the "Earn points" tooltip
        Text(
            user.points, color = colors.blueText, fontSize = 14.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.width(72.dp).clickable(onClick = onReward, role = Role.Button).testTag("leaderReward:${user.username}"),
        )
    }
}

/** RN settings "Push Notification": the master switch, the 14 per-type switches under it, and the unavailable notice. */
@Composable
fun PushSettingsSection(env: AppEnvironment) {
    val colors = LocalEcencyColors.current
    val state by env.push.state.collectAsState()
    Column(Modifier.fillMaxWidth().testTag("pushSettings")) {
        PushSwitch(ReadingUiCopy.PUSH_NOTIFICATION, state.settings.enabled, "push:all", bold = true) { env.push.setEnabled(it) }
        if (state.settings.enabled) {
            PushType.entries.forEach { type ->
                PushSwitch(type.label, state.settings.isOn(type), "push:${type.key}") { env.push.setType(type, it) }
            }
        }
        state.unavailableNotice?.let {
            Text(
                it, color = Color(0xFF856404), fontSize = 13.sp, lineHeight = 18.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFFFF3CD)).padding(16.dp).testTag("pushUnavailable"),
            )
        }
    }
}

@Composable
private fun PushSwitch(title: String, on: Boolean, tag: String, bold: Boolean = false, onChange: (Boolean) -> Unit) {
    val colors = LocalEcencyColors.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = colors.text, fontSize = 15.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f))
        Switch(
            checked = on, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = colors.blue),
            modifier = Modifier.semantics { contentDescription = title }.testTag(tag),
        )
    }
}

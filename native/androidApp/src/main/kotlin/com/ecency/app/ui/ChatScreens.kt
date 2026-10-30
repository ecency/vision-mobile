package com.ecency.app.ui

import androidx.compose.ui.semantics.onClick

import androidx.compose.ui.semantics.LiveRegionMode

import androidx.compose.ui.semantics.liveRegion

import androidx.compose.ui.semantics.customActions

import androidx.compose.ui.semantics.heading


import androidx.compose.ui.semantics.Role

import androidx.compose.ui.text.style.TextDirection
import com.ecency.core.app.AppIcon
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import coil3.compose.AsyncImage
import com.ecency.core.api.chat.DmPrivacy
import com.ecency.core.app.AppEnvironment
import com.ecency.core.app.AppScreen
import com.ecency.core.app.ChatAvatar
import com.ecency.core.app.ChatChannelAction
import com.ecency.core.app.ChatEmoji
import com.ecency.core.app.ChatItem
import com.ecency.core.app.ChatJoinGroupItem
import com.ecency.core.app.ChatJoinItem
import com.ecency.core.app.ChatLinkCard
import com.ecency.core.app.ChatMessageAction
import com.ecency.core.app.ChatMessageItem
import com.ecency.core.app.ChatNewGroupModel
import com.ecency.core.app.ChatNewGroupState
import com.ecency.core.app.ChatNoticeItem
import com.ecency.core.app.ChatPinnedModel
import com.ecency.core.app.ChatQuote
import com.ecency.core.app.ChatReactorsModel
import com.ecency.core.app.ChatRenameModel
import com.ecency.core.app.ChatRenameState
import com.ecency.core.app.ChatRow
import com.ecency.core.app.ChatSpan
import com.ecency.core.app.ChatThreadModel
import com.ecency.core.app.ChatsModel
import com.ecency.core.app.PostCard
import kotlinx.coroutines.launch
import com.ecency.core.app.ChatUiCopy
import com.ecency.core.app.OfficialAccounts
import com.ecency.core.app.UiCopy

/** RN `#28a745` / `#dc3545`: the status pill's dot. */
private val ConnectedGreen = Color(0xFF28A745)
private val ConnectingRed = Color(0xFFDC3545)

/** RN `#FFB300`: a favourite's star. */
private val FavoriteStar = Color(0xFFFFB300)

/** RN dmWarningContainer: `#FFF6DF` with a `#F4C26B` border, text `#8A5B00`. */
private val WarningBackground = Color(0xFFFFF6DF)
private val WarningBorder = Color(0xFFF4C26B)
private val WarningText = Color(0xFF8A5B00)

/** RN `#4CAF50`: the online dot in the members list. */
private val OnlineGreen = Color(0xFF4CAF50)

// ---------------------------------------------------------------- the Chats tab

/**
 * RN chatsContainer: search bar (sort and New group), search results, the channel list with pull to refresh, the
 * status pill, the channel options, the new group and rename sheets. Rows are drawn only for the model's account.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatsTab(env: AppEnvironment, entry: StackEntry, username: String, push: (AppScreen) -> Unit) {
    val colors = LocalEcencyColors.current
    val model = entry.get("chats") { env.chat.chatsModel() }
    LaunchedEffect(model) { model.show() }
    val state by model.state.collectAsState()
    val listState = entry.get("chats:list") { LazyListState() }
    val scope = rememberCoroutineScope()
    var options by remember { mutableStateOf<ChatRow?>(null) }
    var rename by remember { mutableStateOf<Pair<String, String>?>(null) }
    var newGroup by remember { mutableStateOf(false) }
    LaunchedEffect(state.open) {
        state.open?.let {
            model.consumeOpen()
            push(it.screen)
        }
    }
    val mine = state.account == username
    Box(Modifier.fillMaxSize().background(colors.background).testTag("chatsTab")) {
        Column(Modifier.fillMaxSize()) {
            if (mine && state.error != null && !state.isLoading && state.rows.isNotEmpty()) {
                Text(state.error!!, color = colors.redText, fontSize = 13.sp, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.padding(horizontal = 16.dp, vertical = 4.dp).testTag("chatsError"))
            }
            ChatSearchBar(state.query, state.sortByName, onQuery = model::setQuery, onSort = model::toggleSort, onNewGroup = if (state.canStartGroup) ({ newGroup = true }) else null)
            // the first load draws the rows' shape; a search or a reload over rows keeps the spinner
            if (mine && state.isLoading && state.rows.isEmpty() && !state.searching) {
                RowsSkeleton(6, tag = "chatsLoading")
            } else if (mine && (state.isLoading || state.searching)) {
                CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).padding(top = 16.dp).size(28.dp).testTag("chatsLoading"), color = colors.blueText)
            }
            PullToRefreshBox(
                isRefreshing = mine && state.isRefreshing,
                onRefresh = { scope.launch { model.refreshAndWait() } },
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(Modifier.fillMaxSize().testTag("chatsList"), state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, bottom = 56.dp)) {
                    if (mine) {
                        state.searchError?.let { item { Text(it, color = colors.redText, fontSize = 13.sp, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.padding(vertical = 4.dp)) } }
                        if (state.channelResults.isNotEmpty()) {
                            item { SectionHeading(ChatUiCopy.CHANNELS) }
                            items(state.channelResults, key = { "rc:" + it.channel.id }) { r ->
                                SearchRow(env, r.avatar, r.title, null, ChatUiCopy.JOIN, "join:${r.channel.id}") { model.join(r) }
                            }
                        }
                        if (state.userResults.isNotEmpty()) {
                            item { SectionHeading(ChatUiCopy.USERS) }
                            items(state.userResults, key = { "ru:" + it.user.id }) { r ->
                                SearchRow(env, r.avatar, r.title, r.handle, null, "dm:${r.user.id}") { model.startDirect(r) }
                            }
                        }
                        items(state.rows, key = { it.id }) { row ->
                            ChannelRow(env, row, onOpen = { push(model.open(row)) }, onOptions = { options = row })
                        }
                        if (state.rows.isEmpty() && !state.isLoading) {
                            item {
                                // README "Esie": resting on an empty list, sad when the list failed to load
                                EsieView(
                                    if (state.error != null) com.ecency.core.esie.EsiePlacement.ERROR else com.ecency.core.esie.EsiePlacement.EMPTY,
                                    Modifier.fillMaxWidth().padding(top = 48.dp).wrapContentWidth(),
                                )
                                Text(
                                    state.error ?: ChatsModel.EMPTY, color = if (state.error != null) colors.redText else colors.darkGray,
                                    textAlign = TextAlign.Center, fontSize = 14.sp,
                                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp, start = 24.dp, end = 24.dp).testTag("chatsEmpty"),
                                )
                            }
                        }
                    }
                }
            }
        }
        StatusPill(state.connected && mine, Modifier.align(Alignment.BottomStart).padding(16.dp))
    }
    options?.let { row ->
        OptionsSheet(model.actionsTitle(row), model.actions(row).map { it.label to it.destructive }, model.actions(row).map { it.name }, onDismiss = { options = null }) { index ->
            val action = model.actions(row)[index]
            options = null
            if (action == ChatChannelAction.RENAME) rename = row.id to (row.channel.groupName ?: "") else model.perform(row, action)
        }
    }
    rename?.let { (id, name) ->
        val m = remember(id) { env.chat.renameModel(id, name) }
        DisposableEffect(m) { onDispose { m.close() } }
        RenameGroupSheet(m) { rename = null }
    }
    if (newGroup) {
        val m = remember { env.chat.newGroupModel() }
        DisposableEffect(m) { onDispose { m.close() } }
        NewGroupSheet(env, m, onCreated = { id -> newGroup = false; model.groupCreated(id) }, onDismiss = { newGroup = false })
    }
}

@Composable
private fun ChatSearchBar(query: String, sortByName: Boolean, onQuery: (String) -> Unit, onSort: () -> Unit, onNewGroup: (() -> Unit)?) {
    val colors = LocalEcencyColors.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            // grows with the text: a long placeholder (de, ru) or a large font wraps instead of being cut
            // a stable 48 dp host: the clear button appearing never moves the layout; a large font grows it
            Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp)).background(colors.lightBackground).testTag("chatsSearchBar").padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Glyph(AppIcon.SEARCH, colors.icon, 20.dp)
            BasicTextField(
                query, onQuery, singleLine = true, textStyle = TextStyle(color = colors.text, fontSize = 14.sp, textDirection = TextDirection.Content),
                cursorBrush = SolidColor(colors.blueText), modifier = Modifier.weight(1f).padding(horizontal = 8.dp).testTag("chatsSearch"),
                // the placeholder inside the field, so TalkBack reads it as the field's hint
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) Text(ChatsModel.SEARCH_PLACEHOLDER, color = colors.darkGray, fontSize = 14.sp)
                        inner()
                    }
                },
            )
            if (query.isNotEmpty()) Box(Modifier.clickable(role = Role.Button) { onQuery("") }.padding(6.dp).testTag("chatsSearchClear")) { Glyph(AppIcon.CLEAR, colors.icon, 18.dp, description = UiCopy.CLEAR, filled = true) }
        }
        Box(
            Modifier.padding(start = 8.dp).size(40.dp).clip(CircleShape).background(if (sortByName) colors.lightBackground else Color.Transparent)
                .clickable(onClick = onSort, role = Role.Button).testTag("chatsSort"),
            contentAlignment = Alignment.Center,
        ) { Glyph(AppIcon.SORT_AZ, if (sortByName) colors.blueText else colors.icon, 20.dp, description = ChatUiCopy.SORT_BY_NAME) }
        if (onNewGroup != null) {
            Box(Modifier.padding(start = 4.dp).size(40.dp).clip(CircleShape).clickable(onClick = onNewGroup, role = Role.Button).testTag("chatsNewGroup"), contentAlignment = Alignment.Center) {
                Glyph(AppIcon.GROUP_ADD, colors.icon, 20.dp, description = ChatNewGroupState.TITLE)
            }
        }
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(text, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = LocalEcencyColors.current.darkGray, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
}

@Composable
private fun ChatAvatarView(env: AppEnvironment, avatar: ChatAvatar, size: androidx.compose.ui.unit.Dp) {
    val colors = LocalEcencyColors.current
    when (avatar) {
        is ChatAvatar.Account -> Avatar(PostCard.avatarUrl(avatar.name, env.proxyBase), size)
        is ChatAvatar.Pair -> Box(Modifier.size(size)) {
            // two of the other members, overlapped (RN groupAvatar)
            Avatar(PostCard.avatarUrl(avatar.first, env.proxyBase), size * 0.7f, Modifier.align(Alignment.TopStart))
            Box(Modifier.align(Alignment.BottomEnd).clip(CircleShape).background(colors.background).padding(1.5.dp)) {
                Avatar(PostCard.avatarUrl(avatar.second, env.proxyBase), size * 0.7f)
            }
        }
        is ChatAvatar.Initial -> Box(Modifier.size(size).clip(CircleShape).background(colors.lightBackground), contentAlignment = Alignment.Center) {
            Text(avatar.letter, fontWeight = FontWeight.Bold, color = colors.darkGray, fontSize = 16.sp)
        }
    }
}

/**
 * Web OfficialBadge: marks an account Ecency runs (Esie, @ecency) so lookalikes cannot pass as it. A text label, not
 * a seal, so it never reads as the Pro badge: white on the blue fill (AA in both themes), in sp so it follows the font
 * size, and read as "Official Ecency account".
 */
@Composable
internal fun OfficialBadge(modifier: Modifier = Modifier) {
    val colors = LocalEcencyColors.current
    Text(
        OfficialAccounts.BADGE, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        modifier = modifier.clip(RoundedCornerShape(4.dp)).background(colors.blue).padding(horizontal = 4.dp, vertical = 1.dp)
            .semantics { contentDescription = OfficialAccounts.BADGE_LABEL }.testTag("officialBadge"),
    )
}

@Composable
private fun ChannelRow(env: AppEnvironment, row: ChatRow, onOpen: () -> Unit, onOptions: () -> Unit) {
    val colors = LocalEcencyColors.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen, role = Role.Button)
            .semantics { customActions = listOf(a11yAction(ChatUiCopy.CHANNEL_OPTIONS, onOptions)) }.padding(vertical = 10.dp).testTag("chatRow:${row.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChatAvatarView(env, row.avatar, 44.dp)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(row.title, fontWeight = FontWeight.SemiBold, color = colors.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (row.official) OfficialBadge(Modifier.padding(start = 6.dp))
                if (row.favorite) Glyph(AppIcon.STAR, FavoriteStar, 14.dp, Modifier.padding(start = 4.dp), description = ChatUiCopy.FAVORITE, filled = true)
                if (row.muted) Glyph(AppIcon.NOTIFICATIONS_OFF, colors.icon, 14.dp, Modifier.padding(start = 4.dp), description = UiCopy.MUTED)
            }
            if (row.time.isNotEmpty()) Text(row.time, color = colors.darkGray, fontSize = 12.sp, maxLines = 1)
        }
        if (row.unread > 0) {
            Text(
                row.unread.toString(), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(colors.blue).padding(horizontal = 8.dp, vertical = 4.dp).testTag("chatUnread:${row.id}"),
            )
        }
        Box(Modifier.clip(CircleShape).clickable(onClick = onOptions, role = Role.Button).then(HiddenFromA11y).padding(8.dp).testTag("chatOptions:${row.id}")) {
            Glyph(AppIcon.MORE, colors.icon, 20.dp, description = ChatUiCopy.CHANNEL_OPTIONS)
        }
    }
}

@Composable
private fun SearchRow(env: AppEnvironment, avatar: ChatAvatar, title: String, meta: String?, action: String?, tag: String, onAction: () -> Unit) {
    val colors = LocalEcencyColors.current
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("result:$tag"), verticalAlignment = Alignment.CenterVertically) {
        ChatAvatarView(env, avatar, 36.dp)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            meta?.let { Text(it, fontSize = 12.sp, color = colors.darkGray, maxLines = 1) }
        }
        if (action != null) {
            Text(
                action, color = colors.blueText, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                modifier = Modifier.clip(RoundedCornerShape(16.dp)).border(1.dp, colors.blueText, RoundedCornerShape(16.dp)).clickable(onClick = onAction, role = Role.Button)
                    .padding(horizontal = 14.dp, vertical = 6.dp).testTag(tag),
            )
        } else {
            Box(Modifier.clip(CircleShape).clickable(onClick = onAction, role = Role.Button).padding(8.dp).testTag(tag)) { Glyph(AppIcon.CHAT, colors.blueText, 20.dp, description = UiCopy.MESSAGE) }
        }
    }
}

@Composable
private fun StatusPill(connected: Boolean, modifier: Modifier) {
    val colors = LocalEcencyColors.current
    Row(
        modifier.clip(RoundedCornerShape(20.dp)).background(colors.lightBackground).padding(horizontal = 12.dp, vertical = 8.dp).testTag("chatsStatus"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (connected) ConnectedGreen else ConnectingRed))
        Text(if (connected) ChatsModel.CONNECTED else ChatsModel.CONNECTING, fontSize = 12.sp, color = colors.text, modifier = Modifier.padding(start = 6.dp))
    }
}

/** RN chatChannelOptionsSheet / chatOptionsSheet's list: a title and one row per option (destructive in red). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OptionsSheet(
    title: String?, options: List<Pair<String, Boolean>>, ids: List<String>, onDismiss: () -> Unit, header: @Composable () -> Unit = {}, onPick: (Int) -> Unit,
) {
    val colors = LocalEcencyColors.current
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.background) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp).testTag("chatOptionsSheet")) {
            title?.let { Text(it, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = colors.text, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) }
            header()
            options.forEachIndexed { i, (label, destructive) ->
                Text(
                    label, color = if (destructive) colors.redText else colors.text, fontSize = 15.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { onPick(i) }.padding(vertical = 14.dp).testTag("option:${ids[i]}"),
                )
            }
        }
    }
}

/** RN chatRenameGroupSheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RenameGroupSheet(model: ChatRenameModel, onDismiss: () -> Unit) {
    val colors = LocalEcencyColors.current
    val s by model.state.collectAsState()
    LaunchedEffect(s.saved) { if (s.saved != null) onDismiss() }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.background) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding().imePadding().padding(bottom = 16.dp).testTag("renameSheet")) {
            Text(ChatRenameState.TITLE, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = colors.text, modifier = Modifier.semantics { heading() })
            Text(ChatRenameState.HINT, fontSize = 13.sp, color = colors.darkGray, modifier = Modifier.padding(vertical = 8.dp))
            SheetField(s.value, ChatRenameState.PLACEHOLDER, "renameField", onChange = model::setValue)
            s.message?.let { Text(it, color = colors.redText, fontSize = 13.sp, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.padding(top = 6.dp).testTag("renameError")) }
            SheetButton(UiCopy.SAVE, enabled = s.canSave, busy = s.saving, tag = "renameSave", onClick = model::save)
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(UiCopy.CANCEL, color = colors.darkGray) }
        }
    }
}

/** RN chatNewGroupSheet. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun NewGroupSheet(env: AppEnvironment, model: ChatNewGroupModel, onCreated: (String) -> Unit, onDismiss: () -> Unit) {
    val colors = LocalEcencyColors.current
    val s by model.state.collectAsState()
    LaunchedEffect(s.createdChannelId) { s.createdChannelId?.let(onCreated) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.background) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding().imePadding().padding(bottom = 16.dp).testTag("newGroupSheet")) {
            Text(ChatNewGroupState.TITLE, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = colors.text, modifier = Modifier.semantics { heading() })
            Text(ChatNewGroupState.HINT, fontSize = 13.sp, color = colors.darkGray, modifier = Modifier.padding(vertical = 8.dp))
            if (s.selected.isNotEmpty()) {
                FlowRow(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    s.selected.forEach { name ->
                        Row(
                            Modifier.clip(RoundedCornerShape(16.dp)).background(colors.lightBackground).clickable(role = Role.Button) { model.remove(name) }
                                .padding(horizontal = 8.dp, vertical = 4.dp).semantics { contentDescription = ChatUiCopy.removeUser(name) }.testTag("chip:$name"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Avatar(PostCard.avatarUrl(name, env.proxyBase), 20.dp)
                            Text("@$name", fontSize = 13.sp, color = colors.text, modifier = Modifier.padding(horizontal = 4.dp))
                            Glyph(AppIcon.CLOSE, colors.darkGray, 14.dp)
                        }
                    }
                }
            }
            SheetField(s.term, s.placeholder, "groupSearch", enabled = !s.atLimit, onChange = model::setTerm)
            if (s.showsResults) {
                val visible = model.visible
                Column(Modifier.fillMaxWidth().heightIn(max = 240.dp).padding(top = 6.dp)) {
                    when {
                        s.searching && visible.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).padding(12.dp).size(24.dp), color = colors.blueText)
                        visible.isEmpty() -> Text(s.emptyText, fontSize = 13.sp, color = colors.darkGray, modifier = Modifier.padding(vertical = 8.dp).testTag("groupEmpty"))
                        else -> LazyColumn {
                            items(visible, key = { it.username }) { p ->
                                Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { model.add(p.username) }.padding(vertical = 8.dp).testTag("person:${p.username}"), verticalAlignment = Alignment.CenterVertically) {
                                    Avatar(p.account?.let { PostCard.avatarUrl(it, env.proxyBase) }.orEmpty(), 28.dp)
                                    Text("@${p.username}", color = colors.text, modifier = Modifier.padding(start = 10.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
            s.error?.let { Text(it, color = colors.redText, fontSize = 13.sp, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.padding(top = 6.dp).testTag("groupError")) }
            SheetButton(ChatNewGroupState.CREATE, enabled = s.canCreate, busy = s.creating, tag = "groupCreate", onClick = model::create)
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(UiCopy.CANCEL, color = colors.darkGray) }
        }
    }
}

@Composable
private fun SheetField(value: String, placeholder: String, tag: String, enabled: Boolean = true, onChange: (String) -> Unit) {
    val colors = LocalEcencyColors.current
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).border(1.dp, colors.border, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        if (value.isEmpty()) Text(placeholder, color = colors.darkGray, fontSize = 14.sp)
        BasicTextField(
            value, onChange, enabled = enabled, singleLine = true, textStyle = TextStyle(color = colors.text, fontSize = 14.sp, textDirection = TextDirection.Content),
            cursorBrush = SolidColor(colors.blueText), modifier = Modifier.fillMaxWidth().testTag(tag),
        )
    }
}

@Composable
private fun SheetButton(text: String, enabled: Boolean, busy: Boolean, tag: String, onClick: () -> Unit) {
    val colors = LocalEcencyColors.current
    Box(
        Modifier.fillMaxWidth().padding(top = 14.dp).heightIn(min = 44.dp).clip(RoundedCornerShape(22.dp))
            .background(if (enabled) colors.blue else colors.blue.copy(alpha = 0.4f)).clickable(enabled = enabled, onClick = onClick, role = Role.Button).testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
        else Text(text, color = Color.White, fontWeight = FontWeight.Bold)
    }
}

// ---------------------------------------------------------------- a conversation

/** What a conversation asks of the shell. */
class ChatThreadActions(
    val onBack: () -> Unit,
    /** The quick profile of an account. */
    val onProfile: (String) -> Unit,
    /** A tapped link (http(s), hive://). */
    val onLink: (String) -> Unit,
    val onSettings: () -> Unit,
)

/**
 * RN chatThreadContainer: header (members, pinned, group options), the inverted message list with paging and the
 * unread marker, typing, the DM safety and ban notices, the composer (mentions, reply, edit, link card, image) and the
 * message, pinned, members, reactors, emoji and translation sheets. The websocket runs while the screen is resumed.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ChatThreadScreen(env: AppEnvironment, host: AppHost, entry: StackEntry, screen: AppScreen.ChatThread, actions: ChatThreadActions) {
    val colors = LocalEcencyColors.current
    val model = entry.get("thread") { env.chat.threadModel(screen.channelId, screen.type) }
    LaunchedEffect(model) { model.start() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(model, lifecycle) {
        try {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                model.setActive(true)
                try {
                    kotlinx.coroutines.awaitCancellation()
                } finally {
                    model.setActive(false)
                }
            }
        } finally {
            model.setActive(false)
        }
    }
    val s by model.state.collectAsState()
    val listState = entry.get("thread:list") { LazyListState() }
    val scope = rememberCoroutineScope()
    var messageOptions by remember { mutableStateOf<ChatMessageItem?>(null) }
    var removeTarget by remember { mutableStateOf<ChatMessageItem?>(null) }
    var emojiFor by remember { mutableStateOf<ChatMessageItem?>(null) }
    var reactors by remember { mutableStateOf<ChatReactorsModel?>(null) }
    var pinned by remember { mutableStateOf<ChatPinnedModel?>(null) }
    var members by remember { mutableStateOf(false) }
    var groupOptions by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var translate by remember { mutableStateOf<ChatMessageItem?>(null) }
    var gallery by remember { mutableStateOf<Pair<List<String>, Int>?>(null) }

    // the first unread message (once), a sent message, a pinned message tapped in the sheet
    LaunchedEffect(s.scrollTo) {
        val id = s.scrollTo ?: return@LaunchedEffect
        val index = s.items.indexOfFirst { it.id == id }
        if (index >= 0) listState.animateScrollToItem(index)
        model.consumeScroll()
    }
    // the oldest message is near: the page before (RN onEndReached at 0.3)
    LaunchedEffect(listState, model) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            val st = model.current
            if (st.items.isNotEmpty() && last >= st.items.size - 3) model.loadMore()
        }
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    // the one media picking layer (MediaPicker.kt): library or files, one image
    val picker = com.ecency.app.rememberMediaPicker(com.ecency.core.app.MediaSurface.CHAT, onRejected = { env.ui.toast(it) }) { uris ->
        val uri = uris.firstOrNull()
        if (uri != null) {
            scope.launch {
                val app = context.applicationContext
                val image = com.ecency.app.EditorImages.prepare(app, uri, 0)
                if (image == null) env.ui.toast(UiCopy.INVALID_IMAGE) else model.attach(image)
            }
        }
    }
    val mine = s.account != null
    // "Choose from Library" or "Choose from Files" (RN #3561: chat had no files route)
    var attachChoice by remember { mutableStateOf(false) }
    if (attachChoice) MediaSourceChooser(picker, "chatAttach") { attachChoice = false }

    Column(Modifier.fillMaxSize().background(colors.background).statusBarsPadding().imePadding().testTag("chatThread")) {
        ChatHeader(s.title, s.official, s.memberCount, s.pinnedCount, onBack = actions.onBack, onMembers = { members = true }, onPinned = { pinned = model.pinnedModel() }, onOptions = if (s.isGroup) ({ groupOptions = true }) else null)
        if (s.showDmWarning) DmWarning(onDismiss = model::dismissDmWarning, onSettings = actions.onSettings)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            PullToRefreshBox(isRefreshing = s.isRefreshing, onRefresh = { scope.launch { model.refreshAndWait() } }, modifier = Modifier.fillMaxSize()) {
                LazyColumn(Modifier.fillMaxSize().testTag("messages"), state = listState, reverseLayout = true, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                    if (mine) {
                        items(s.items, key = { it.id }) { item ->
                            Column {
                                if (item.id == s.unreadItemId) UnreadMarker()
                                when (item) {
                                    is ChatMessageItem -> MessageBubble(
                                        env, item,
                                        onLongPress = { messageOptions = item },
                                        onProfile = actions.onProfile,
                                        onLink = actions.onLink,
                                        onImage = { url -> gallery = item.images to item.images.indexOf(url).coerceAtLeast(0) },
                                        onReaction = { model.react(item, it) },
                                        onReactors = { reactors = model.reactorsModel(item, it).also { r -> r.start() } },
                                    )
                                    is ChatJoinItem -> JoinPill(item, actions.onProfile)
                                    is ChatJoinGroupItem -> JoinGroupPill(item, actions.onProfile)
                                    is ChatNoticeItem -> SystemPill(item.text, "notice:${item.id}")
                                }
                            }
                        }
                        item(key = "footer") {
                            Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                                when {
                                    s.isLoading -> CircularProgressIndicator(Modifier.size(24.dp), color = colors.blueText)
                                    !s.hasMore && s.items.isNotEmpty() -> Text(ChatThreadModel.NO_MORE, color = colors.darkGray, fontSize = 12.sp, modifier = Modifier.testTag("noMore"))
                                }
                            }
                        }
                    }
                }
            }
            if (mine && s.items.isEmpty() && s.loaded && !s.isLoading) {
                Text(
                    s.error ?: ChatThreadModel.EMPTY, color = if (s.error != null) colors.redText else colors.darkGray, textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp).testTag("threadEmpty"),
                )
            }
        }
        s.typing?.let { Text("•••  $it", color = colors.darkGray, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp).testTag("typing")) }
        s.banText?.let { BanNotice(it) }
        Composer(env, model, s, onAttach = { attachChoice = true })
    }

    messageOptions?.let { item ->
        val options = model.actions(item)
        OptionsSheet(
            null, options.map { it.label.uppercase() to it.destructive }, options.map { it.name }, onDismiss = { messageOptions = null },
            header = {
                LazyRow(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    items(ChatEmoji.QUICK, key = { it.first }) { (name, emoji) ->
                        Text(emoji, fontSize = 26.sp, modifier = Modifier.clip(CircleShape).clickable(role = Role.Button) { messageOptions = null; model.react(item, name) }.padding(10.dp).testTag("quick:$name"))
                    }
                    item(key = "more") {
                        Text("\u22EF " + ChatUiCopy.MORE_MENU, fontSize = 16.sp, color = colors.text, modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(colors.lightBackground).clickable(role = Role.Button) { messageOptions = null; emojiFor = item }.padding(horizontal = 12.dp, vertical = 10.dp).testTag("quick:more"))
                    }
                }
            },
        ) { index ->
            messageOptions = null
            when (options[index]) {
                ChatMessageAction.REPLY -> model.replyTo(item)
                ChatMessageAction.COPY -> {
                    host.copy(model.messageText(item))
                    env.ui.toast(ChatUiCopy.COPIED_TO_CLIPBOARD)
                }
                ChatMessageAction.SHARE -> host.share(model.messageText(item))
                ChatMessageAction.TRANSLATE -> translate = item
                ChatMessageAction.EDIT -> model.startEdit(item)
                ChatMessageAction.PIN -> model.setPinned(item.post.id, true)
                ChatMessageAction.UNPIN -> model.setPinned(item.post.id, false)
                ChatMessageAction.REMOVE -> removeTarget = item
            }
        }
    }
    removeTarget?.let { item ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text(ChatThreadModel.REMOVE_TITLE) },
            text = { Text(ChatThreadModel.REMOVE_BODY) },
            confirmButton = { TextButton(onClick = { removeTarget = null; model.remove(item) }, modifier = Modifier.testTag("confirmRemove")) { Text(ChatUiCopy.DELETE_MESSAGE, color = colors.redText) } },
            dismissButton = { TextButton(onClick = { removeTarget = null }) { Text(UiCopy.CANCEL) } },
        )
    }
    emojiFor?.let { item -> EmojiPickerSheet(onDismiss = { emojiFor = null }) { name -> emojiFor = null; model.react(item, name) } }
    reactors?.let { r -> ReactorsSheet(env, r, onDismiss = { r.close(); reactors = null }, onProfile = { reactors = null; r.close(); actions.onProfile(it) }) }
    pinned?.let { p -> PinnedSheet(p, s.canPin, onDismiss = { p.close(); pinned = null }, onOpen = { id -> p.close(); pinned = null; model.scrollToMessage(id) }) }
    if (members) MembersSheet(env, model, onDismiss = { members = false }, onProfile = { members = false; actions.onProfile(it) })
    if (groupOptions) {
        val actions = listOfNotNull(ChatChannelAction.MEMBERS, ChatChannelAction.RENAME.takeIf { s.groupOwner })
        OptionsSheet(s.title, actions.map { it.label to false }, actions.map { it.name }, onDismiss = { groupOptions = false }) { index ->
            groupOptions = false
            if (index == 0) members = true else rename = true
        }
    }
    if (rename) {
        val m = remember { env.chat.renameModel(screen.channelId, s.groupName) }
        DisposableEffect(m) { onDispose { m.close() } }
        RenameGroupSheet(m) { rename = false }
    }
    translate?.let { item -> ChatTranslationSheet(model, item, host.readerLanguage()) { translate = null } }
    gallery?.let { (urls, index) -> GallerySheet(urls, index) { gallery = null } }
    s.alert?.let { text ->
        AlertDialog(onDismissRequest = model::clearAlert, title = { Text(UiCopy.FAIL) }, text = { Text(text) }, confirmButton = { TextButton(onClick = model::clearAlert) { Text(UiCopy.OK) } })
    }
}

@Composable
private fun ChatHeader(title: String, official: Boolean, members: Int, pinned: Int, onBack: () -> Unit, onMembers: () -> Unit, onPinned: () -> Unit, onOptions: (() -> Unit)?) {
    val colors = LocalEcencyColors.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.clip(CircleShape).clickable(onClick = onBack, role = Role.Button).padding(6.dp).testTag("back")) { Glyph(AppIcon.BACK, colors.icon, 24.dp, description = UiCopy.BACK) }
        Row(Modifier.weight(1f).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).testTag("threadTitle"))
            if (official) OfficialBadge(Modifier.padding(start = 6.dp))
        }
        HeaderChip(AppIcon.MEMBERS, if (members > 0) com.ecency.core.l10n.LocaleFormat.integer(members.toLong()) else null, ChatUiCopy.MEMBERS, "members", onMembers)
        if (pinned > 0) HeaderChip(AppIcon.PIN, com.ecency.core.l10n.LocaleFormat.integer(pinned.toLong()), ChatUiCopy.PINNED_MESSAGES, "pinned", onPinned)
        if (onOptions != null) HeaderChip(AppIcon.MORE, null, ChatUiCopy.GROUP_OPTIONS, "groupOptions", onOptions)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border.copy(alpha = 0.4f)))
}

@Composable
private fun HeaderChip(icon: AppIcon, count: String?, description: String, tag: String, onClick: () -> Unit) {
    val colors = LocalEcencyColors.current
    Row(
        Modifier.padding(start = 8.dp).clip(RoundedCornerShape(12.dp)).background(colors.lightBackground).clickable(onClick = onClick, role = Role.Button).padding(horizontal = 6.dp, vertical = 4.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(icon, colors.icon, 20.dp, description = description)
        count?.let { Text(it, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colors.text, modifier = Modifier.padding(start = 4.dp)) }
    }
}

@Composable
private fun DmWarning(onDismiss: () -> Unit, onSettings: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp).clip(RoundedCornerShape(10.dp)).background(WarningBackground)
            .border(1.dp, WarningBorder, RoundedCornerShape(10.dp)).padding(12.dp).testTag("dmWarning"),
    ) {
        Glyph(AppIcon.WARNING, WarningText, 20.dp, Modifier.padding(end = 8.dp))
        Column(Modifier.weight(1f)) {
            Text(ChatThreadModel.DM_WARNING_TITLE, fontWeight = FontWeight.Bold, color = WarningText, fontSize = 14.sp)
            Text(
                buildAnnotatedString {
                    append(ChatThreadModel.DM_WARNING_BODY + " ")
                    withLink(LinkAnnotation.Clickable("settings", TextLinkStyles(SpanStyle(textDecoration = TextDecoration.Underline, fontWeight = FontWeight.Bold))) { onSettings() }) {
                        append(ChatThreadModel.DM_WARNING_SETTINGS)
                    }
                },
                color = WarningText, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp),
            )
        }
        Glyph(AppIcon.CLOSE, WarningText, 18.dp, Modifier.clickable(onClick = onDismiss, role = Role.Button).padding(start = 8.dp).testTag("dmWarningClose"), description = UiCopy.DISMISS)
    }
}

@Composable
private fun BanNotice(text: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(10.dp)).background(WarningBackground)
            .border(1.dp, WarningBorder, RoundedCornerShape(10.dp)).padding(12.dp).testTag("banNotice"),
    ) {
        Text("⏳", fontSize = 16.sp, modifier = Modifier.padding(end = 8.dp))
        Text(text, color = WarningText, fontSize = 13.sp)
    }
}

@Composable
private fun UnreadMarker() {
    val colors = LocalEcencyColors.current
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("unreadMarker"), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(1.dp).background(colors.red))
        Text(ChatThreadModel.NEW_MESSAGES, color = colors.redText, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp))
        Box(Modifier.weight(1f).height(1.dp).background(colors.red))
    }
}

/** Behind a code span in a message. */
internal val ChatCodeShade = Color.Black.copy(alpha = 0.12f)

/** The message text: mentions bold, links tappable (shown as RN shows them), inline markdown as web reads it. */
@Composable
private fun messageText(spans: List<ChatSpan>, own: Boolean, onLink: (String) -> Unit): androidx.compose.ui.text.AnnotatedString {
    val colors = LocalEcencyColors.current
    val link = if (own) Color.White else colors.blueText
    return buildAnnotatedString {
        for (span in spans) {
            val marks = SpanStyle(
                fontWeight = if (span.bold) FontWeight.Bold else null,
                fontStyle = if (span.italic) FontStyle.Italic else null,
                textDecoration = if (span.strike) TextDecoration.LineThrough else null,
                fontFamily = if (span.code) FontFamily.Monospace else null,
                // a shade darker than the bubble keeps the text's contrast on both bubbles and themes
                background = if (span.code) ChatCodeShade else Color.Unspecified,
            )
            val url = span.url
            if (url != null) {
                val decoration = if (span.strike) TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough)) else TextDecoration.Underline
                val style = marks.merge(SpanStyle(color = link, textDecoration = decoration))
                withLink(LinkAnnotation.Clickable(url, TextLinkStyles(style)) { onLink(url) }) { append(span.text) }
                continue
            }
            withStyle(marks) { append(span.text) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun MessageBubble(
    env: AppEnvironment,
    item: ChatMessageItem,
    onLongPress: () -> Unit,
    onProfile: (String) -> Unit,
    onLink: (String) -> Unit,
    onImage: (String) -> Unit,
    onReaction: (String) -> Unit,
    onReactors: (String) -> Unit,
) {
    val colors = LocalEcencyColors.current
    val own = item.own
    val text = if (own) Color.White else colors.text
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("message:${item.id}"), horizontalAlignment = if (own) Alignment.End else Alignment.Start) {
        Row(verticalAlignment = Alignment.Top) {
            if (!own) {
                val author = item.authorAccount
                Box(Modifier.clip(CircleShape).then(if (author != null) Modifier.avatarButton(author) { onProfile(author) } else Modifier)) {
                    Avatar(item.authorAccount?.let { PostCard.avatarUrl(it, env.proxyBase) }.orEmpty(), 32.dp)
                }
                Spacer(Modifier.width(8.dp))
            }
            Column(
                Modifier.widthIn(max = 300.dp)
                    .clip(RoundedCornerShape(topStart = if (own) 16.dp else 4.dp, topEnd = if (own) 4.dp else 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp))
                    .background(if (own) colors.blue else colors.lightBackground)
                    // a tap does nothing (RN), a long press opens the options; TalkBack's double tap opens them too, so
                    // the bubble never announces an action that does nothing
                    .semantics { onClick(ChatUiCopy.MESSAGE_OPTIONS) { onLongPress(); true } }
                    .combinedClickable(onClick = {}, onLongClickLabel = ChatUiCopy.MESSAGE_OPTIONS, onLongClick = onLongPress, role = Role.Button)
                    .padding(horizontal = 12.dp, vertical = 8.dp).testTag("bubble:${item.id}"),
            ) {
                if (!own) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(item.author, color = colors.blueText, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        if (item.authorOfficial) OfficialBadge(Modifier.padding(start = 4.dp))
                    }
                }
                item.quote?.let { QuoteBox(it, own, onClose = null) }
                if (item.spans.isNotEmpty()) Text(messageText(item.spans, own, onLink), color = text, fontSize = 15.sp, modifier = Modifier.testTag("messageText:${item.id}"))
                item.images.forEach { url ->
                    AsyncImage(
                        url, contentDescription = UiCopy.IMAGE, contentScale = ContentScale.Crop,
                        modifier = Modifier.padding(top = 6.dp).size(220.dp, 160.dp).clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button) { onImage(url) },
                    )
                }
                item.card?.let { LinkCard(it, own) { onLink(it.url) } }
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (item.edited) ChatUiCopy.editedAt(item.time) else item.time, fontSize = 11.sp, color = if (own) Color.White.copy(alpha = 0.8f) else colors.darkGray)
                    if (own) Box(Modifier.padding(start = 10.dp).clickable(onClick = onLongPress, role = Role.Button).padding(horizontal = 6.dp).testTag("messageActions:${item.id}")) { Glyph(AppIcon.MORE, Color.White, 16.dp, description = ChatUiCopy.MESSAGE_OPTIONS) }
                }
            }
        }
        if (item.reactions.isNotEmpty()) {
            FlowRow(Modifier.padding(top = 4.dp, start = if (own) 0.dp else 40.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                item.reactions.forEach { pill ->
                    Row(
                        Modifier.clip(RoundedCornerShape(12.dp)).background(colors.lightBackground)
                            .border(1.dp, if (pill.reacted) colors.blueText else colors.background, RoundedCornerShape(12.dp))
                            .combinedClickable(onClick = { onReaction(pill.emojiName) }, onLongClick = { onReactors(pill.emojiName) }, role = Role.Button)
                            .semantics { contentDescription = ChatUiCopy.reactionLabel(pill.emoji, pill.count) }
                            .padding(horizontal = 8.dp, vertical = 4.dp).testTag("reaction:${item.id}:${pill.emojiName}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(pill.emoji, fontSize = 14.sp)
                        if (pill.showsCount) Text(com.ecency.core.l10n.LocaleFormat.integer(pill.count.toLong()), fontSize = 12.sp, color = if (pill.reacted) colors.blueText else colors.darkGray, modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun QuoteBox(quote: ChatQuote, own: Boolean, onClose: (() -> Unit)?) {
    val colors = LocalEcencyColors.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(8.dp))
            .background(if (own) Color.White.copy(alpha = 0.15f) else colors.background.copy(alpha = 0.6f)).padding(8.dp).testTag("quote"),
    ) {
        Box(Modifier.width(3.dp).height(32.dp).background(if (own) Color.White else colors.blue))
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(quote.author, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = if (own) Color.White else colors.blueText)
            Text(quote.text, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = if (own) Color.White.copy(alpha = 0.9f) else colors.darkGray)
        }
        if (onClose != null) Box(Modifier.clickable(onClick = onClose, role = Role.Button).padding(4.dp).testTag("cancelReply")) { Glyph(AppIcon.CLOSE, colors.icon, 18.dp, description = ChatUiCopy.CANCEL_REPLY) }
    }
}

@Composable
private fun LinkCard(card: ChatLinkCard, own: Boolean, onOpen: () -> Unit) {
    val colors = LocalEcencyColors.current
    Column(Modifier.padding(top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (own) Color.White.copy(alpha = 0.15f) else colors.background).clickable(onClick = onOpen, role = Role.Button).testTag("linkCard")) {
        card.image?.takeIf { it.isNotEmpty() }?.let { AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(120.dp)) }
        Column(Modifier.padding(8.dp)) {
            Text(card.title, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = if (own) Color.White else colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (card.summary.isNotEmpty()) Text(card.summary, fontSize = 12.sp, color = if (own) Color.White.copy(alpha = 0.85f) else colors.darkGray, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SystemPill(text: String, tag: String, content: (@Composable () -> Unit)? = null) {
    val colors = LocalEcencyColors.current
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.clip(RoundedCornerShape(12.dp)).background(colors.lightBackground).padding(horizontal = 12.dp, vertical = 4.dp).testTag(tag)) {
            if (content != null) content() else Text(text, fontSize = 12.sp, color = colors.darkGray)
        }
    }
}

@Composable
private fun JoinPill(item: ChatJoinItem, onProfile: (String) -> Unit) {
    val colors = LocalEcencyColors.current
    SystemPill(item.text, "join:${item.id}") {
        Row {
            item.name?.let { n ->
                Text(n, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = colors.blueText, modifier = Modifier.clickable(enabled = item.account != null, role = Role.Button) { item.account?.let(onProfile) })
                Text(" ", fontSize = 12.sp)
            }
            Text(item.text, fontSize = 12.sp, color = colors.darkGray)
        }
    }
}

@Composable
private fun JoinGroupPill(item: ChatJoinGroupItem, onProfile: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().clickable(role = Role.Button) { open = !open }) { SystemPill(item.text, "joins:${item.id}") }
        if (open) item.entries.forEach { JoinPill(it, onProfile) }
    }
}

@Composable
private fun Composer(env: AppEnvironment, model: ChatThreadModel, s: com.ecency.core.app.ChatThreadState, onAttach: () -> Unit) {
    val colors = LocalEcencyColors.current
    // the field keeps its own text (caret) and follows the model only when the model replaced it
    var field by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(s.text)) }
    LaunchedEffect(s.textVersion) {
        if (field.text != model.current.text) field = androidx.compose.ui.text.input.TextFieldValue(model.current.text, androidx.compose.ui.text.TextRange(model.current.text.length))
    }
    Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
        if (s.mentions.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().heightIn(max = 220.dp).padding(horizontal = 12.dp).clip(RoundedCornerShape(10.dp)).background(colors.lightBackground).testTag("mentions")) {
                s.mentions.forEach { m ->
                    Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { model.selectMention(m) }.padding(8.dp).testTag("mention:${m.name}"), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(PostCard.avatarUrl(m.name, env.proxyBase), 24.dp)
                        Text("@${m.name}", fontWeight = FontWeight.Bold, color = colors.text, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp))
                        m.displayName?.let { Text(it, color = colors.darkGray, fontSize = 12.sp, maxLines = 1, modifier = Modifier.padding(start = 6.dp)) }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.Bottom) {
            Box(Modifier.size(44.dp).clip(CircleShape).clickable(enabled = !s.uploading && !s.sending, onClick = onAttach, role = Role.Button).testTag("attach"), contentAlignment = Alignment.Center) {
                if (s.uploading) CircularProgressIndicator(Modifier.size(20.dp), color = colors.blueText, strokeWidth = 2.dp) else Glyph(AppIcon.ADD, colors.icon, 24.dp, description = UiCopy.ATTACH_IMAGE)
            }
            Column(Modifier.weight(1f).padding(horizontal = 8.dp).clip(RoundedCornerShape(16.dp)).background(colors.lightBackground).padding(horizontal = 12.dp, vertical = 10.dp)) {
                if (s.editingId != null) {
                    Row(Modifier.fillMaxWidth().testTag("editingBanner"), verticalAlignment = Alignment.CenterVertically) {
                        Text(ChatThreadModel.EDITING, color = colors.blueText, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Box(Modifier.clickable(role = Role.Button) { model.cancelEdit() }.padding(horizontal = 6.dp).testTag("cancelEdit")) { Glyph(AppIcon.CLOSE, colors.icon, 18.dp, description = ChatUiCopy.CANCEL_EDIT) }
                    }
                }
                s.replyTo?.let { QuoteBox(it, own = false, onClose = model::cancelReply) }
                if (s.fetchingPreview && s.preview == null) Text("…", color = colors.darkGray)
                s.preview?.let { LinkCard(it, own = false) {} }
                Box {
                    if (field.text.isEmpty()) Text(ChatThreadModel.PLACEHOLDER, color = colors.darkGray, fontSize = 15.sp)
                    BasicTextField(
                        field,
                        onValueChange = {
                            field = it
                            if (it.text != model.current.text) model.setText(it.text)
                        },
                        textStyle = TextStyle(color = colors.text, fontSize = 15.sp, textDirection = TextDirection.Content),
                        cursorBrush = SolidColor(colors.blueText),
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth().testTag("composer"),
                    )
                }
            }
            val enabled = s.canSend
            // enabled fades in, the arrow turns into progress, then a tick while the next message can already be typed
            val sendBlue = if (enabled || s.sending) 1f else 0.4f
            val fill by androidx.compose.animation.animateColorAsState(colors.blue.copy(alpha = sendBlue), LocalMotion.current.fade(), label = "sendFill") // fill
            val phase = rememberSendPhase(s.sending, sent = s.sentVersion)
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(fill)
                    .clickable(enabled = enabled, role = Role.Button) { model.send() }.testTag("send"),
                contentAlignment = Alignment.Center,
            ) {
                SendContent(phase, Color.White) { Glyph(AppIcon.SEND, Color.White, 20.dp, description = UiCopy.SEND, filled = true) }
            }
        }
    }
}

/** RN emojiPickerSheet: search and a grid of every emoji. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmojiPickerSheet(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val colors = LocalEcencyColors.current
    var query by remember { mutableStateOf("") }
    val list by remember { derivedStateOf { ChatEmoji.search(query) } }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.background) {
        Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).padding(horizontal = 16.dp).navigationBarsPadding().testTag("emojiPicker")) {
            Text(ChatUiCopy.SELECT_EMOJI, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = colors.text, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(8.dp))
            SheetField(query, ChatUiCopy.SEARCH_EMOJIS, "emojiSearch") { query = it }
            if (list.isEmpty()) Text(ChatUiCopy.NO_EMOJI_FOUND, color = colors.darkGray, modifier = Modifier.padding(16.dp))
            LazyVerticalGrid(GridCells.Adaptive(44.dp), Modifier.fillMaxWidth().padding(top = 8.dp)) {
                items(list, key = { it.name }) { e ->
                    Text(e.emoji, fontSize = 26.sp, textAlign = TextAlign.Center, modifier = Modifier.size(44.dp).clickable(role = Role.Button) { onPick(e.name) }.padding(4.dp).testTag("emoji:${e.name}"))
                }
            }
        }
    }
}

/** RN chatReactorsSheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReactorsSheet(env: AppEnvironment, model: ChatReactorsModel, onDismiss: () -> Unit, onProfile: (String) -> Unit) {
    val colors = LocalEcencyColors.current
    val s by model.state.collectAsState()
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.background) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 16.dp).testTag("reactorsSheet")) {
            Text(ChatReactorsModel.TITLE, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = colors.text, modifier = Modifier.semantics { heading() })
            LazyRow(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(s.tabs, key = { it.emojiName }) { tab ->
                    val active = tab.emojiName == s.selected
                    Row(
                        Modifier.clip(RoundedCornerShape(16.dp)).background(if (active) colors.blue.copy(alpha = 0.15f) else colors.lightBackground)
                            .clickable(role = Role.Button) { model.select(tab.emojiName) }.padding(horizontal = 10.dp, vertical = 6.dp).testTag("reactorsTab:${tab.emojiName}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(ChatEmoji.display(tab.emojiName), fontSize = 16.sp)
                        Text(com.ecency.core.l10n.LocaleFormat.integer(tab.userIds.size.toLong()), fontSize = 12.sp, color = if (active) colors.blueText else colors.darkGray, modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(s.rows, key = { it.userId }) { r ->
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = r.account != null, role = Role.Button) { r.account?.let(onProfile) }.padding(vertical = 8.dp).testTag("reactor:${r.userId}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(r.account?.let { PostCard.avatarUrl(it, env.proxyBase) }.orEmpty(), 32.dp)
                        Text(r.label, color = colors.text, modifier = Modifier.padding(start = 10.dp))
                    }
                }
            }
        }
    }
}

/** RN PinnedMessagesModal. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PinnedSheet(model: ChatPinnedModel, canUnpin: Boolean, onDismiss: () -> Unit, onOpen: (String) -> Unit) {
    val colors = LocalEcencyColors.current
    LaunchedEffect(model) { model.start() }
    val s by model.state.collectAsState()
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.background) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 16.dp).testTag("pinnedSheet")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Glyph(AppIcon.PIN, colors.blueText, 20.dp)
                Text(ChatPinnedModel.TITLE, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = colors.text, modifier = Modifier.semantics { heading() }.padding(start = 8.dp))
            }
            when {
                s.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).padding(32.dp), color = colors.blueText)
                s.error != null -> Text(s.error!!, color = colors.redText, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.padding(32.dp))
                s.items.isEmpty() -> Text(ChatPinnedModel.EMPTY, color = colors.darkGray, modifier = Modifier.padding(32.dp))
                else -> LazyColumn(Modifier.heightIn(max = 420.dp).padding(top = 8.dp)) {
                    items(s.items, key = { it.post.id }) { row ->
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(8.dp)).background(colors.lightBackground)
                                .clickable(role = Role.Button) { onOpen(row.post.id) }.padding(12.dp).testTag("pinnedRow:${row.post.id}"),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(row.author, color = colors.blueText, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                                Text(row.time, color = colors.darkGray, fontSize = 12.sp)
                                if (canUnpin) Box(Modifier.padding(start = 12.dp).clickable(role = Role.Button) { model.unpin(row) }.padding(6.dp).testTag("unpin:${row.post.id}")) { Glyph(AppIcon.CLOSE, colors.icon, 18.dp, description = ChatUiCopy.UNPIN) }
                            }
                            Text(row.text, color = colors.text, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                }
            }
        }
    }
}

/** RN OnlineUsersModal. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MembersSheet(env: AppEnvironment, model: ChatThreadModel, onDismiss: () -> Unit, onProfile: (String) -> Unit) {
    val colors = LocalEcencyColors.current
    val rows = remember(model) { model.members() }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.background) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 16.dp).testTag("membersSheet")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Glyph(AppIcon.MEMBERS, colors.blueText, 20.dp)
                Column(Modifier.padding(start = 8.dp)) {
                    Text(ChatUiCopy.CHANNEL_MEMBERS, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = colors.text, modifier = Modifier.semantics { heading() })
                    Text(model.membersSubtitle(rows), fontSize = 13.sp, color = colors.darkGray, modifier = Modifier.testTag("membersCount"))
                }
            }
            if (rows.isEmpty()) Text(ChatUiCopy.NO_MEMBERS_FOUND, color = colors.darkGray, modifier = Modifier.padding(32.dp))
            LazyColumn(Modifier.heightIn(max = 420.dp).padding(top = 8.dp)) {
                items(rows, key = { it.userId }) { m ->
                    Row(Modifier.fillMaxWidth().clickable(enabled = m.account != null, role = Role.Button) { m.account?.let(onProfile) }.padding(vertical = 10.dp).testTag("member:${m.userId}"), verticalAlignment = Alignment.CenterVertically) {
                        Box {
                            Avatar(m.account?.let { PostCard.avatarUrl(it, env.proxyBase) }.orEmpty(), 40.dp)
                            if (m.online) Box(Modifier.align(Alignment.BottomEnd).size(12.dp).clip(CircleShape).background(colors.background).padding(2.dp).clip(CircleShape).background(OnlineGreen))
                        }
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(m.displayName, color = colors.text, fontWeight = FontWeight.Medium)
                            if (m.username != m.displayName) Text("@${m.username}", color = colors.darkGray, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

/** RN Translate on a message: the post translation sheet over the message's text. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatTranslationSheet(thread: ChatThreadModel, item: ChatMessageItem, reader: String, onDismiss: () -> Unit) {
    val colors = LocalEcencyColors.current
    val model = remember(item.id) { thread.translationModel(item, reader) }
    DisposableEffect(model) { onDispose { model.close() } }
    LaunchedEffect(model) { model.start() }
    val state by model.state.collectAsState()
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.background) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp).testTag("chatTranslation")) {
            Text(UiCopy.TRANSLATE, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = colors.text, modifier = Modifier.semantics { heading() })
            Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (state.source == "auto") UiCopy.AUTO else com.ecency.core.app.Languages.name(state.source), color = colors.blueText)
                Glyph(AppIcon.ARROW_FORWARD, colors.blueText, 16.dp, Modifier.padding(horizontal = 8.dp))
                Text(com.ecency.core.app.Languages.name(state.target), color = colors.blueText)
            }
            Text(state.original, maxLines = 3, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, color = colors.darkGray)
            Box(Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 80.dp)) {
                when {
                    state.isLoading -> CircularProgressIndicator(Modifier.size(24.dp), color = colors.blueText)
                    state.error != null -> Text(state.error!!, color = colors.redText, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    else -> Text(state.translated ?: "", color = colors.text)
                }
            }
        }
    }
}

/** RN settings "Chat Privacy" choices, in RN's order. */
internal val DmPrivacyLabels: List<String> = DmPrivacy.entries.map { it.label }

/** Opens the direct message with [username] (profile, quick profile); RN's toast when the account is not on chat. */
internal fun openDirectMessage(env: AppEnvironment, scope: kotlinx.coroutines.CoroutineScope, username: String, push: (AppScreen) -> Unit) {
    scope.launch {
        try {
            val id = env.chat.openDirect(username)
            push(AppScreen.ChatThread(id, "D"))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            env.ui.toast(com.ecency.core.app.ChatService.DM_ERROR)
        }
    }
}

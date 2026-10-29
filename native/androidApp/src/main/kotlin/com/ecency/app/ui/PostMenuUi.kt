package com.ecency.app.ui

import androidx.compose.ui.semantics.LiveRegionMode

import androidx.compose.ui.semantics.liveRegion

import androidx.compose.ui.semantics.heading

import androidx.compose.ui.semantics.semantics


import androidx.compose.ui.semantics.Role

import com.ecency.core.app.AppIcon
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ecency.core.api.model.Post
import com.ecency.core.api.model.PostKey
import com.ecency.core.app.AppEnvironment
import com.ecency.core.app.AppScreen
import com.ecency.core.app.FeedFilter
import com.ecency.core.app.FeedTabs
import com.ecency.core.app.PostMenuContext
import com.ecency.core.app.PostMenuEffect
import com.ecency.core.app.PostMenuItem
import kotlinx.coroutines.launch
import com.ecency.core.app.ReadingUiCopy
import com.ecency.core.app.UiCopy

/** A post options request: the post, where the menu opened, and the discussion a reply belongs to. */
data class MenuRequest(val post: Post, val context: PostMenuContext, val root: PostKey)

/**
 * The overlays of the reading features (post menu and its confirmations, cross post, translation, quick profile,
 * feed customising): one holder per app, read by screens through [LocalFeedx].
 */
@Stable
class FeedxUi(
    val env: AppEnvironment,
    val host: AppHost,
    private val push: (AppScreen) -> Unit,
    private val reply: (root: PostKey, post: Post) -> Unit,
    private val login: () -> Unit,
) {
    var menu by mutableStateOf<MenuRequest?>(null)
    var confirm by mutableStateOf<PostMenuEffect.Confirm?>(null)
    var notes by mutableStateOf<PostMenuEffect.AskNotes?>(null)
    var crossPost by mutableStateOf<Post?>(null)
    var translate by mutableStateOf<Post?>(null)
    var quickProfile by mutableStateOf<String?>(null)
    var customise by mutableStateOf(false)

    /** The app's haptics (set by the root; Motion.kt): copy, bookmark and reblog confirm with a light tap. */
    var haptics: (com.ecency.core.app.HapticEvent) -> Unit = {}

    fun openMenu(post: Post, context: PostMenuContext = PostMenuContext(), root: PostKey = post.key) {
        menu = MenuRequest(post, context, root)
    }

    /**
     * The account changed (sign-out, switch, a link login as someone else): the menu, its confirmation, the notes
     * sheet and the cross post picker were opened for the previous account and go.
     */
    fun onAccountChange() {
        menu = null
        confirm = null
        notes = null
        crossPost = null
    }

    fun openProfile(username: String) {
        quickProfile = username
    }

    fun open(screen: AppScreen) = push(screen)

    /** RN showLoginAlert: the login screen. */
    fun requestLogin() = login()

    /** Acts on what a menu tap asked for (RN _handleOnDropdownSelect after the sheet closed). */
    fun handle(effect: PostMenuEffect, root: PostKey) {
        when (effect) {
            is PostMenuEffect.Copy -> {
                host.copy(effect.text)
                haptics(com.ecency.core.app.HapticEvent.SUCCESS)
                env.ui.toast(effect.toast)
            }
            is PostMenuEffect.Share -> host.share(effect.message)
            is PostMenuEffect.Open -> push(effect.screen)
            // RN's menu "reply" opens the editor (the action bar and comment reply buttons open the quick reply)
            is PostMenuEffect.Reply -> push(
                AppScreen.Editor(
                    com.ecency.core.app.EditorArgs(
                        com.ecency.core.app.EditorKind.REPLY, effect.post.author, effect.post.permlink,
                        rootAuthor = root.author, rootPermlink = root.permlink,
                    ),
                ),
            )
            is PostMenuEffect.CrossPost -> crossPost = effect.post
            is PostMenuEffect.Translate -> translate = effect.post
            is PostMenuEffect.Confirm -> confirm = effect
            is PostMenuEffect.AskNotes -> notes = effect
            PostMenuEffect.Login -> login()
            PostMenuEffect.Started, PostMenuEffect.None -> Unit
        }
    }
}

val LocalFeedx = staticCompositionLocalOf<FeedxUi?> { null }

/** Menu items that flip a two-state choice on the post: a light haptic as they are picked. */
internal val MenuToggles = setOf(PostMenuItem.BOOKMARKS, PostMenuItem.REBLOG, PostMenuItem.UNDO_REBLOG)

/** Every overlay of [ui]; mounted once at the root. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedxOverlays(ui: FeedxUi) {
    val colors = LocalEcencyColors.current
    val scope = rememberCoroutineScope()
    val env = ui.env
    val actions by env.postActions.state.collectAsState()

    ui.menu?.let { request ->
        val items by produceState<List<PostMenuItem>?>(null, request) { value = env.postActions.menu(request.post, request.context) }
        ModalBottomSheet(onDismissRequest = { ui.menu = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.background) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp).testTag("postMenu")) {
                val list = items
                if (list == null) {
                    LoadingBlock()
                } else {
                    list.forEach { item ->
                        Text(
                            item.title,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (item.destructive) colors.redText else colors.text,
                            modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) {
                                ui.menu = null
                                if (item in MenuToggles) ui.haptics(com.ecency.core.app.HapticEvent.TOGGLE)
                                scope.launch { ui.handle(env.postActions.select(item, request.post), request.root) }
                            }.padding(horizontal = 24.dp, vertical = 14.dp).testTag("menu:${item.id}"),
                        )
                    }
                }
            }
        }
    }

    ui.confirm?.let { c ->
        AlertDialog(
            onDismissRequest = { ui.confirm = null },
            title = { Text(c.title) },
            text = c.body?.let { { Text(it) } },
            confirmButton = {
                TextButton(onClick = {
                    ui.confirm = null
                    env.postActions.confirm(c.item, c.post, c.user)
                }, modifier = Modifier.testTag("confirm")) { Text(c.confirmLabel) }
            },
            dismissButton = { TextButton(onClick = { ui.confirm = null }) { Text(UiCopy.CANCEL) } },
        )
    }

    ui.notes?.let { n ->
        // the one notes sheet (CommunityScreens.kt): hivemind's rules on the reason live in ModNotes
        ModNotesSheet(
            onConfirm = { text ->
                ui.notes = null
                env.postActions.confirm(n.item, n.post, n.user, text)
            },
            onDismiss = { ui.notes = null },
            title = n.title, description = n.description, placeholder = n.placeholder, confirmLabel = n.confirmLabel,
        )
    }

    actions.alert?.let { a ->
        AlertDialog(
            onDismissRequest = { env.postActions.dismissAlert() },
            title = { Text(a.title) },
            text = { Text(a.message) },
            confirmButton = { TextButton(onClick = { env.postActions.dismissAlert() }) { Text(UiCopy.OK) } },
        )
    }

    ui.crossPost?.let { post -> CrossPostSheet(ui, post) }
    ui.translate?.let { post -> TranslationSheet(ui, post) }
    ui.quickProfile?.let { name -> QuickProfileSheet(ui, name) }
    if (ui.customise) CustomiseFiltersSheet(ui)
}

/** RN crossPostModal. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CrossPostSheet(ui: FeedxUi, post: Post) {
    val colors = LocalEcencyColors.current
    val model = remember(post) { ui.env.crossPostModel(post, ui.host.appVersion()) }
    DisposableEffect(model) { onDispose { model.close() } }
    LaunchedEffect(model) { model.start() }
    val state by model.state.collectAsState()
    LaunchedEffect(state.published) {
        state.published?.let { community ->
            ui.crossPost = null
            ui.open(AppScreen.Feed(com.ecency.core.api.repository.FeedSource.Ranked(com.ecency.core.api.RankedSort.CREATED, community), community))
        }
    }
    var picking by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = { if (!state.isPublishing) ui.crossPost = null }, containerColor = colors.background) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).imePadding().navigationBarsPadding().padding(bottom = 16.dp).testTag("crossPostSheet")) {
            Text(ReadingUiCopy.CROSS_POST, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = colors.text, modifier = Modifier.semantics { heading() })
            Text(
                state.selected?.title ?: ReadingUiCopy.SELECT_COMMUNITY,
                color = if (state.selected == null) colors.darkGray else colors.text,
                modifier = Modifier.padding(top = 12.dp).fillMaxWidth().border(1.dp, colors.border, RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button) { picking = !picking }.padding(12.dp).testTag("pickCommunity"),
            )
            if (picking) {
                OutlinedTextField(state.query, model::search, placeholder = { Text(UiCopy.SEARCH) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                LazyColumn(Modifier.heightIn(max = 220.dp)) {
                    if (state.isLoading) item { LoadingBlock() }
                    items(state.filtered, key = { it.name }) { c ->
                        Text(
                            c.title, color = colors.text,
                            modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { model.select(c); picking = false }.padding(vertical = 10.dp).testTag("community:${c.name}"),
                        )
                    }
                }
            }
            OutlinedTextField(
                state.message, model::setMessage, placeholder = { Text(ReadingUiCopy.SHARE_YOUR_THOUGHTS_HERE) },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 100.dp).testTag("crossPostMessage"),
            )
            Text(
                ReadingUiCopy.CROSS_POST_DESCRIPTION,
                fontSize = 12.sp, color = colors.darkGray, modifier = Modifier.padding(top = 8.dp),
            )
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { ui.crossPost = null }, enabled = !state.isPublishing) { Text(UiCopy.CLOSE_CAPS) }
                Button(
                    onClick = model::publish, enabled = state.canPublish,
                    colors = ButtonDefaults.buttonColors(contentColor = androidx.compose.ui.graphics.Color.White, containerColor = colors.blue),
                    modifier = Modifier.testTag("publish"),
                ) {
                    if (state.isPublishing) CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                    else Text(ReadingUiCopy.PUBLISH, color = Color.White)
                }
            }
        }
    }
}

/** RN post-translation-modal: source and target over the post summary. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TranslationSheet(ui: FeedxUi, post: Post) {
    val colors = LocalEcencyColors.current
    val model = remember(post) { ui.env.translationSheetModel(post, ui.host.readerLanguage()) }
    DisposableEffect(model) { onDispose { model.close() } }
    LaunchedEffect(model) { model.start() }
    val state by model.state.collectAsState()
    var pick by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = { ui.translate = null }, containerColor = colors.background) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp).testTag("translationSheet")) {
            Text(UiCopy.TRANSLATE, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = colors.text, modifier = Modifier.semantics { heading() })
            Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                LanguageChip(if (state.source == "auto") UiCopy.AUTO else com.ecency.core.app.Languages.name(state.source)) { pick = "source" }
                Glyph(AppIcon.ARROW_FORWARD, colors.darkGray, 16.dp, Modifier.padding(horizontal = 8.dp))
                LanguageChip(com.ecency.core.app.Languages.name(state.target)) { pick = "target" }
            }
            if (pick != null) {
                LazyColumn(Modifier.heightIn(max = 260.dp)) {
                    if (pick == "source") item { Text(UiCopy.AUTO, color = colors.text, modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { model.setSource("auto"); pick = null }.padding(10.dp)) }
                    items(state.languages, key = { it.first }) { (code, name) ->
                        Text(name, color = colors.text, modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) {
                            if (pick == "source") model.setSource(code) else model.setTarget(code)
                            pick = null
                        }.padding(10.dp))
                    }
                }
            }
            Text(state.original, maxLines = 3, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, color = colors.darkGray)
            Box(Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 80.dp)) {
                when {
                    state.isLoading -> LoadingBlock()
                    state.error != null -> Text(state.error!!, color = colors.redText, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    else -> Text(state.translated ?: "", color = colors.text, modifier = Modifier.testTag("translated"))
                }
            }
        }
    }
}

@Composable
private fun LanguageChip(label: String, onClick: () -> Unit) {
    val colors = LocalEcencyColors.current
    Text(
        label, color = colors.blueText, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(colors.lightBackground).clickable(onClick = onClick, role = Role.Button).padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** RN quickProfileModal. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickProfileSheet(ui: FeedxUi, username: String) {
    val colors = LocalEcencyColors.current
    val model = remember(username) { ui.env.quickProfileModel(username) }
    DisposableEffect(model) { onDispose { model.close() } }
    LaunchedEffect(model) { model.start() }
    val s by model.state.collectAsState()
    ModalBottomSheet(onDismissRequest = { ui.quickProfile = null }, containerColor = colors.background) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp).testTag("quickProfile"),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(contentAlignment = Alignment.Center) {
                androidx.compose.material3.CircularProgressIndicator(
                    progress = { ((s.votingPower ?: 0.0) / 100).toFloat() },
                    modifier = Modifier.size(112.dp), color = colors.blueText, strokeWidth = 6.dp, trackColor = colors.lightBackground,
                )
                Avatar(s.avatarUrl, 96.dp)
            }
            Text("@${s.username}", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = colors.text, modifier = Modifier.semantics { heading() }.padding(top = 10.dp).testTag("qpName"))
            s.about?.let { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, color = colors.darkGray, modifier = Modifier.padding(top = 4.dp)) }
            Text(s.joined, fontSize = 12.sp, color = colors.darkGray, modifier = Modifier.padding(top = 4.dp))
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                Stat(UiCopy.FOLLOWER, s.followers?.let { com.ecency.core.l10n.LocaleFormat.integer(it.toLong()) })
                Stat(UiCopy.FOLLOWING, s.following?.let { com.ecency.core.l10n.LocaleFormat.integer(it.toLong()) })
                Stat(UiCopy.POSTS, s.posts?.let { com.ecency.core.l10n.LocaleFormat.integer(it.toLong()) })
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                Stat(UiCopy.RESOURCE_CREDITS, s.resourceCredits)
                Stat(UiCopy.REPUTATION, s.reputation?.let { com.ecency.core.l10n.LocaleFormat.integer(it.toLong()) })
            }
            if (s.showActions && !s.isOwn) {
                val haptics = rememberHaptics()
                Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { haptics(com.ecency.core.app.HapticEvent.TOGGLE); model.follow() }, enabled = !s.isFollowing && !s.actionRunning && !s.isMuted,
                        colors = ButtonDefaults.buttonColors(contentColor = androidx.compose.ui.graphics.Color.White, containerColor = colors.blue), modifier = Modifier.testTag("qpFollow"),
                    ) {
                        // the label swaps with a crossfade as the follow lands (Motion.kt)
                        AnimatedSwap(if (s.isMuted) UiCopy.MUTED else if (s.isFollowing) UiCopy.FOLLOWING else UiCopy.FOLLOW, label = "qpFollow") { Text(it, color = Color.White) }
                    }
                    TextButton(onClick = { haptics(com.ecency.core.app.HapticEvent.TOGGLE); model.toggleFavorite() }, enabled = !s.actionRunning, modifier = Modifier.testTag("qpFavorite")) {
                        val ink by androidx.compose.animation.animateColorAsState(if (s.isFavorite) colors.redText else colors.blueText, LocalMotion.current.fade(), label = "qpFavorite")
                        AnimatedSwap(s.isFavorite, Modifier.popOnChange(s.isFavorite, enabled = s.isFavorite), label = "qpFavorite") { on ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(ReadingUiCopy.FAVOURITE, color = ink)
                                if (on) Glyph(AppIcon.HEART, ink, 16.dp, Modifier.padding(start = 4.dp), filled = true)
                            }
                        }
                    }
                    // RN quickProfile ActionPanel: the direct message with this account
                    val chatScope = androidx.compose.runtime.rememberCoroutineScope()
                    TextButton(onClick = {
                        openDirectMessage(ui.env, chatScope, username) { screen -> ui.quickProfile = null; ui.open(screen) }
                    }, enabled = !s.actionRunning, modifier = Modifier.testTag("qpMessage")) { Text(UiCopy.MESSAGE, color = colors.blueText) }
                }
            }
            TextButton(onClick = {
                ui.quickProfile = null
                ui.open(AppScreen.Profile(username))
            }, modifier = Modifier.padding(top = 8.dp).testTag("qpFull")) { Text(UiCopy.VIEW_FULL_PROFILE, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun Stat(label: String, value: String?) {
    val colors = LocalEcencyColors.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value ?: "--", fontWeight = FontWeight.Bold, color = colors.text)
        Text(label, fontSize = 12.sp, color = colors.darkGray)
    }
}

/** RN customiseFiltersModal: selected first (in tab order), then available; APPLY needs three. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomiseFiltersSheet(ui: FeedxUi) {
    val colors = LocalEcencyColors.current
    val selected = remember { mutableStateListOf<FeedFilter>().apply { addAll(FeedTabs.saved(ui.env.prefs)) } }
    var alert by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = { ui.customise = false }, containerColor = colors.background) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp).testTag("customiseSheet")) {
            Text(ReadingUiCopy.CUSTOMISE_FILTERS, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = colors.text, modifier = Modifier.semantics { heading() })
            Text(ReadingUiCopy.SELECTED_FILTERS, fontWeight = FontWeight.Bold, color = colors.darkGray, modifier = Modifier.padding(top = 12.dp))
            if (selected.isEmpty()) Text(ReadingUiCopy.PLEASE_ADD_FROM_AVAILABLE, fontSize = 12.sp, color = colors.darkGray)
            selected.toList().forEachIndexed { index, f ->
                FilterRow(f, true, onToggle = { selected.remove(f) },
                    onUp = if (index > 0) ({ selected.removeAt(index); selected.add(index - 1, f) }) else null,
                    onDown = if (index < selected.size - 1) ({ selected.removeAt(index); selected.add(index + 1, f) }) else null)
            }
            Text(ReadingUiCopy.AVAILABLE_FILTERS, fontWeight = FontWeight.Bold, color = colors.darkGray, modifier = Modifier.padding(top = 12.dp))
            FeedFilter.entries.filter { it !in selected }.forEach { f -> FilterRow(f, false, onToggle = { selected.add(f) }, null, null) }
            Button(
                onClick = {
                    val error = FeedTabs.save(ui.env.prefs, selected.toList())
                    if (error == null) ui.customise = false else alert = error
                },
                colors = ButtonDefaults.buttonColors(contentColor = androidx.compose.ui.graphics.Color.White, containerColor = colors.blue),
                modifier = Modifier.align(Alignment.End).padding(top = 12.dp).testTag("applyFilters"),
            ) { Text(ReadingUiCopy.APPLY_CAPS, color = Color.White) }
        }
    }
    alert?.let {
        AlertDialog(onDismissRequest = { alert = null }, text = { Text(it) }, confirmButton = { TextButton(onClick = { alert = null }) { Text(UiCopy.OK) } })
    }
}

@Composable
private fun FilterRow(filter: FeedFilter, checked: Boolean, onToggle: () -> Unit, onUp: (() -> Unit)?, onDown: (() -> Unit)?) {
    val colors = LocalEcencyColors.current
    Row(Modifier.fillMaxWidth().testTag("filter:${filter.key}"), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, { onToggle() })
        Text(filter.label, color = colors.text, modifier = Modifier.weight(1f))
        if (onUp != null) Glyph(AppIcon.ARROW_UP, colors.darkGray, 20.dp, Modifier.clickable(onClick = onUp, role = Role.Button).padding(8.dp), description = UiCopy.MOVE_UP)
        if (onDown != null) Glyph(AppIcon.ARROW_DOWN, colors.darkGray, 20.dp, Modifier.clickable(onClick = onDown, role = Role.Button).padding(8.dp), description = UiCopy.MOVE_DOWN)
    }
}

/** The options (dots) button RN puts on cards, comments and the post header. */
@Composable
fun OptionsButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = LocalEcencyColors.current
    Box(modifier.iconClickable(radius = 20.dp, onClick = onClick).inMergedRow(LocalMergedRow.current).padding(6.dp).testTag("options")) {
        Glyph(AppIcon.MORE, colors.icon, 24.dp, description = ReadingUiCopy.POST_OPTIONS)
    }
}

package com.ecency.app.ui

import androidx.compose.foundation.layout.heightIn


import androidx.compose.ui.semantics.Role

import com.ecency.core.app.AppIcon
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import com.ecency.core.app.Languages
import com.ecency.core.app.ReadingInfo
import com.ecency.core.app.RecapModel
import com.ecency.core.app.UiCopy

/**
 * RN postReadingMetadata: Words | Reading N min | AI Recap (signed in, 50 words or more), then the summary box with
 * "Translate" / "Hide translation" and a language picker. Listen is its own bar ([ListenSection]).
 */
@Composable
fun ReadingBar(info: ReadingInfo, recap: RecapModel? = null) {
    if (!info.visible) return
    val colors = LocalEcencyColors.current
    val r = recap?.state?.collectAsState()?.value
    Column(
        Modifier.fillMaxWidth().padding(vertical = 8.dp).border(1.dp, colors.lightBackground).padding(vertical = 12.dp).testTag("readingBar"),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Metric(UiCopy.WORDS, info.words.toString(), Modifier.weight(1f))
            Divider()
            Metric(UiCopy.READING, "${info.minutes} ${UiCopy.MIN}", Modifier.weight(1f))
            if (recap != null && r != null && r.eligible) {
                Divider()
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.padding(bottom = 4.dp).clip(RoundedCornerShape(3.dp)).background(colors.blue).padding(horizontal = 3.dp, vertical = 1.dp)) {
                        Text(UiCopy.AI_BADGE, color = Color.White, fontSize = 8.sp, lineHeight = 10.sp, fontWeight = FontWeight.ExtraBold)
                    }
                    when {
                        r.running -> CircularProgressIndicator(Modifier.size(18.dp).testTag("recapRunning"), strokeWidth = 2.dp)
                        r.summary != null -> Text(
                            UiCopy.TRY_AGAIN, color = colors.blueText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable(onClick = recap::tryAgain, role = Role.Button).testTag("recapTryAgain"),
                        )
                        else -> Text(
                            r.label, color = colors.blueText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable(onClick = recap::summarize, role = Role.Button).testTag("recap"),
                        )
                    }
                    r.costText?.takeIf { r.summary == null && !r.unresolved }?.let {
                        Text(it, color = colors.darkGray, fontSize = 10.sp, modifier = Modifier.testTag("recapCost"))
                    }
                }
            }
        }
        if (recap != null && r != null) {
            if (r.unresolved || r.notice != null) {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    UnresolvedNotice(r.notice, r.unresolved, r.running, "recap", onGiveUp = recap::giveUp)
                }
            }
            r.shownText?.let { text -> RecapBox(recap, text, r.showTranslation, r.translating, r.target) }
            FailAlert(r.alert, "recapAlert", recap::clearAlert)
        }
    }
}

@Composable
private fun RecapBox(recap: RecapModel, text: String, translating: Boolean, busy: Boolean, target: String) {
    val colors = LocalEcencyColors.current
    var picking by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp, start = 16.dp, end = 16.dp).clip(RoundedCornerShape(8.dp)).background(colors.lightBackground)
            .padding(horizontal = 16.dp, vertical = 12.dp).testTag("recapBox"),
    ) {
        Text(text, color = colors.text, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.testTag("recapText"))
        if (translating && busy) CircularProgressIndicator(Modifier.padding(top = 8.dp).size(18.dp), strokeWidth = 2.dp)
        Box(Modifier.fillMaxWidth().padding(top = 10.dp).height(1.dp).background(colors.icon))
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                if (translating) UiCopy.HIDE_TRANSLATION else UiCopy.TRANSLATE, color = colors.blueText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable(onClick = recap::toggleTranslation, role = Role.Button).testTag("recapTranslate"),
            )
            if (translating) {
                Box {
                    Row(
                        Modifier.border(1.dp, colors.icon, RoundedCornerShape(6.dp)).clickable(role = Role.Button) { picking = true }
                            .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp).testTag("recapLanguage"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(Languages.name(target), color = colors.text, fontSize = 12.sp)
                        Glyph(AppIcon.DROPDOWN, colors.text, 16.dp)
                    }
                    DropdownMenu(expanded = picking, onDismissRequest = { picking = false }) {
                        recap.current.languages.forEach { (code, name) ->
                            DropdownMenuItem(text = { Text(name) }, onClick = { picking = false; recap.setTarget(code) }, modifier = Modifier.testTag("recapLang:$code"))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier) {
    val colors = LocalEcencyColors.current
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 12.sp, color = colors.darkGray, modifier = Modifier.padding(bottom = 4.dp))
        Text(value, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.text)
    }
}

@Composable
private fun Divider() {
    Box(Modifier.width(1.dp).height(40.dp).background(LocalEcencyColors.current.lightBackground))
}

/** RN "Show Images" (`!hidePostsThumbnails`), provided once at the root from the settings preference. */
val LocalShowImages = androidx.compose.runtime.compositionLocalOf { true }

/** RN HiddenImagePlaceholder: dashed frame, image-outline icon and "Tap to load image"; the tap loads that image only. */
@Composable
fun HiddenImagePlaceholder(modifier: Modifier, compact: Boolean = false, onReveal: () -> Unit) {
    val colors = LocalEcencyColors.current
    val border = colors.borderTop
    Box(
        modifier.clip(RoundedCornerShape(8.dp)).background(colors.lightBackground)
            .drawBehind {
                drawRoundRect(
                    border, cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6f, 6f))),
                )
            }
            .clickable(onClick = onReveal, role = Role.Button),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Glyph(AppIcon.IMAGE, colors.icon, if (compact) 20.dp else 28.dp)
            if (!compact) Text(UiCopy.TAP_TO_LOAD_IMAGE, color = colors.darkGray, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/**
 * RN ScrollTopPopup over a post list, shown by [com.ecency.core.app.ScrollTopRule] (scrolling up more than 5000 dp
 * from the top; hidden by a tap). Drawn as the FAB's twin ([ScrollTopFab]) at the bottom start, so it never sits
 * on the compose FAB at the bottom end. The offset of a lazy list is estimated from the laid out rows (their average
 * height times the first row's index, plus its offset).
 */
@Composable
fun androidx.compose.foundation.layout.BoxScope.ScrollTopPopup(
    listState: androidx.compose.foundation.lazy.LazyListState,
    tag: String = "scrollTop",
    /** A pushed screen (no tab bar below) passes `Modifier.navigationBarsPadding()`: the app is edge to edge. */
    modifier: Modifier = Modifier,
    onTap: () -> Unit = {},
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val rule = remember(listState) { com.ecency.core.app.ScrollTopRule { System.currentTimeMillis() } }
    val demo = DebugLaunch.demoScrollTop
    var visible by remember(listState) { mutableStateOf(demo) }
    androidx.compose.runtime.LaunchedEffect(listState) {
        if (demo) return@LaunchedEffect
        androidx.compose.runtime.snapshotFlow {
            val info = listState.layoutInfo.visibleItemsInfo
            val avg = if (info.isEmpty()) 0f else info.sumOf { it.size }.toFloat() / info.size
            listState.firstVisibleItemIndex * avg + listState.firstVisibleItemScrollOffset
        }.collect { px -> visible = rule.onScroll(with(density) { px.toDp().value.toDouble() }) }
    }
    ScrollTopFab(visible, UiCopy.SCROLL_TOP, tag, modifier) {
        rule.onTap()
        visible = demo
        onTap()
        scope.launch { listState.scrollToItem(0) }
    }
}

/**
 * The scroll to top button of the feeds and Waves: the FAB's twin ([FabButton], an up arrow) at the bottom start (it
 * mirrors in RTL), opposite the compose FAB at the bottom end; it pops in and out.
 */
@Composable
fun androidx.compose.foundation.layout.BoxScope.ScrollTopFab(visible: Boolean, description: String, tag: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    // reduced motion: a fade only (Motion.kt levels)
    val pops = LocalMotion.current.level == com.ecency.core.app.MotionLevel.FULL
    androidx.compose.animation.AnimatedVisibility(
        visible,
        Modifier.align(Alignment.BottomStart).then(modifier),
        enter = if (pops) androidx.compose.animation.scaleIn() + androidx.compose.animation.fadeIn() else androidx.compose.animation.fadeIn(),
        exit = if (pops) androidx.compose.animation.scaleOut() + androidx.compose.animation.fadeOut() else androidx.compose.animation.fadeOut(),
    ) { FabButton(AppIcon.ARROW_UP, description, tag, onClick) }
}

/**
 * RN feedScreen's `{isLoggedIn && <WavesOnboardingChecklist />}`: the waves "Getting started" card on the Feed tab,
 * the same shared model as on the Waves tab; "Post your first Wave" opens the wave composer here.
 */
@Composable
fun FeedWavesCard(env: com.ecency.core.app.AppEnvironment, username: String, onCheckIn: () -> Unit) {
    var composer by remember(username) { mutableStateOf<com.ecency.core.app.WaveComposeModel?>(null) }
    val lock by env.appLock.state.collectAsState()
    androidx.compose.runtime.LaunchedEffect(username, lock.hidesContent) {
        if (!lock.hidesContent) {
            env.wavesOnboarding.start()
            env.wavesOnboarding.refresh()
        }
    }
    WavesOnboardingCard(env, username, onWave = { composer = env.waves.composer() }, onCheckIn = onCheckIn)
    composer?.let { c -> if (c.owner == username) WaveComposerSheet(env, c, onDismiss = { composer = null }) }
}

/** RN NewsletterSenderInfo: "{weekly} weekly • {monthly} monthly email subscribers", the copy link button and "Manage". */
@Composable
fun DigestSenderRow(env: com.ecency.core.app.AppEnvironment, username: String, onManage: () -> Unit) {
    val colors = LocalEcencyColors.current
    val model = remember(username) { env.digestSenderModel(username) }
    androidx.compose.runtime.DisposableEffect(model) { onDispose { model.close() } }
    androidx.compose.runtime.LaunchedEffect(model) { model.start() }
    val s by model.state.collectAsState()
    if (!s.loaded) return
    val host = LocalFeedx.current?.host
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 4.dp).testTag("digestSender"), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Text(s.text, color = colors.darkGray, fontSize = 13.sp, maxLines = 1, modifier = Modifier.weight(1f, fill = false).testTag("digestSenderCount"))
        Box(
            Modifier.padding(horizontal = 4.dp).clip(RoundedCornerShape(50)).clickable(role = Role.Button) {
                host?.copy(model.link)
                env.ui.toast(com.ecency.core.app.DigestEntryCopy.COPIED)
            }.padding(6.dp).testTag("digestSenderCopy"),
        ) { Glyph(AppIcon.LINK, colors.darkGray, 18.dp, description = com.ecency.core.app.DigestEntryCopy.COPIED) }
        Text(
            com.ecency.core.app.DigestEntryCopy.MANAGE, color = colors.blueText, fontSize = 13.sp, maxLines = 1,
            modifier = Modifier.padding(start = 4.dp).clickable(onClick = onManage, role = Role.Button).testTag("digestSenderManage"),
        )
    }
}

/** RN NewsletterPostPrompt: the end-of-post card offering the digest that would carry this post. */
@Composable
fun DigestPostPrompt(model: com.ecency.core.app.DigestPromptModel, post: com.ecency.core.api.model.Post, viewer: String?) {
    val colors = LocalEcencyColors.current
    androidx.compose.runtime.LaunchedEffect(model, post.author, post.permlink, viewer) { model.start(post, viewer) }
    val s by model.state.collectAsState()
    val target = s.target
    if (!s.visible || target == null) return
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(12.dp)).background(colors.lightBackground)
            .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp).testTag("digestPrompt"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(target.body, color = colors.darkGray, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.weight(1f).padding(end = 8.dp))
        Text(
            com.ecency.core.app.DigestCopy.SUBSCRIBE, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(colors.blue).clickable(onClick = model::subscribe, role = Role.Button)
                .padding(horizontal = 14.dp, vertical = 7.dp).testTag("digestPromptSubscribe"),
        )
        Box(Modifier.clip(RoundedCornerShape(50)).clickable(onClick = model::dismiss, role = Role.Button).padding(6.dp).testTag("digestPromptDismiss")) {
            Glyph(AppIcon.CLOSE, colors.darkGray, 18.dp, description = com.ecency.core.app.DigestEntryCopy.CLOSE)
        }
    }
}

/**
 * RN upvotePopover with payoutDetailsContent: a rounded popover above the payout amount with the rows of
 * [com.ecency.core.app.PayoutDetails] (label 120 wide, bold, 12; value right aligned).
 */
@Composable
fun PayoutPopover(post: com.ecency.core.api.model.Post, onDismiss: () -> Unit) {
    val colors = LocalEcencyColors.current
    val env = LocalFeedx.current?.env
    val model = remember(post) { env?.payoutDetailsModel(post) }
    androidx.compose.runtime.DisposableEffect(model) { onDispose { model?.close() } }
    androidx.compose.runtime.LaunchedEffect(model) { model?.start() }
    val rows = model?.state?.collectAsState()?.value?.rows ?: com.ecency.core.app.PayoutDetails.rows(post, null, System.currentTimeMillis())
    val density = androidx.compose.ui.platform.LocalDensity.current
    Popup(
        alignment = Alignment.BottomStart,
        offset = with(density) { androidx.compose.ui.unit.IntOffset(0, -24.dp.roundToPx()) },
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.PopupProperties(focusable = true),
    ) {
        Column(
            Modifier.widthIn(max = 320.dp).shadow(6.dp, RoundedCornerShape(20.dp)).clip(RoundedCornerShape(20.dp)).background(colors.background)
                .padding(horizontal = 26.dp, vertical = 12.dp).testTag("payoutPopover"),
        ) {
            rows.forEach { row ->
                Row(Modifier.padding(top = 4.dp).testTag("payoutRow:${row.label}")) {
                    Text(row.label, color = colors.darkGray, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(120.dp))
                    Text(row.value, color = colors.darkGray, fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.weight(1f, fill = false))
                }
            }
        }
    }
}

/** RN postDisplayView's view count (eye-outline, abbreviated visits); a tap opens the stats modal. */
@Composable
fun PostViewCount(env: com.ecency.core.app.AppEnvironment, post: com.ecency.core.api.model.Post, entry: StackEntry?, onScreen: (com.ecency.core.app.AppScreen) -> Unit) {
    val colors = LocalEcencyColors.current
    val model = remember(post.author, post.permlink, entry) {
        entry?.get("stats:${post.author}/${post.permlink}") { env.postStatsModel(post) } ?: env.postStatsModel(post)
    }
    if (entry == null) androidx.compose.runtime.DisposableEffect(model) { onDispose { model.close() } }
    androidx.compose.runtime.LaunchedEffect(model) { model.start() }
    val s by model.state.collectAsState()
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.padding(start = 8.dp, top = 8.dp).clip(RoundedCornerShape(6.dp))
            .clickable(onClickLabel = com.ecency.core.app.StatsCopy.STATS_HINT, role = Role.Button) { open = true; model.loadDevices() }
            .semantics(mergeDescendants = true) { contentDescription = com.ecency.core.app.StatsCopy.views(s.stats.visits) }
            .padding(4.dp).testTag("postViews"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(AppIcon.PREVIEW, colors.darkGray.copy(alpha = 0.6f), 14.dp)
        if (s.loading) CircularProgressIndicator(Modifier.padding(start = 4.dp).size(10.dp), strokeWidth = 1.dp)
        else Text(s.viewsText, color = colors.darkGray, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp).testTag("postViewsCount"))
    }
    if (open) PostStatsSheet(model, onPromote = { open = false; onScreen(model.promoteScreen) }) { open = false }
}

/** RN postStatsModal: Visitors, Views, Minutes; the device breakdown; the promote pitch and PROMOTE POST. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PostStatsSheet(model: com.ecency.core.app.PostStatsModel, onPromote: () -> Unit, onDismiss: () -> Unit) {
    val colors = LocalEcencyColors.current
    val s by model.state.collectAsState()
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.background) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp).testTag("postStats")) {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                listOf(
                    Triple("visitors", com.ecency.core.app.StatsCopy.VISITORS, s.stats.visitors),
                    Triple("views", com.ecency.core.app.StatsCopy.VIEWS, s.stats.visits),
                    Triple("minutes", com.ecency.core.app.StatsCopy.MINUTES, s.minutes),
                ).forEach { (id, label, value) ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (s.loading) "..." else com.ecency.core.l10n.LocaleFormat.integer(value.toLong()), color = colors.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("stat:$id"))
                        Text(label, color = colors.darkGray, fontSize = 12.sp)
                    }
                }
            }
            s.devices.forEach { d ->
                Column(Modifier.padding(top = 10.dp).testTag("device:${d.device}")) {
                    Text(d.device, color = colors.text, fontSize = 13.sp)
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { d.share.toFloat() }, color = colors.icon, trackColor = colors.lightBackground,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(10.dp).clip(RoundedCornerShape(5.dp)),
                    )
                }
            }
            Text(
                androidx.compose.ui.text.buildAnnotatedString {
                    withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold, color = colors.text)) { append(com.ecency.core.app.StatsCopy.PROMOTE_TITLE) }
                    withStyle(androidx.compose.ui.text.SpanStyle(color = colors.darkGray)) { append(com.ecency.core.app.StatsCopy.PROMOTE_MESSAGE) }
                },
                fontSize = 14.sp, modifier = Modifier.padding(top = 20.dp),
            )
            androidx.compose.material3.Button(
                onClick = onPromote, colors = androidx.compose.material3.ButtonDefaults.buttonColors(contentColor = androidx.compose.ui.graphics.Color.White, containerColor = colors.blue),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 48.dp).testTag("statsPromote"),
            ) { Text(com.ecency.core.app.StatsCopy.PROMOTE, color = Color.White, fontWeight = FontWeight.Bold) }
        }
    }
}

/**
 * RN EcencySourceBadge: the Ecency logo mark, 12 dp, in a soft gray so it stays a faint hint beside the Pro badge.
 * Shared by cards, headers and the post footer; an image that says "Published with Ecency" and shows no text.
 */
@Composable
fun EcencySourceBadge(modifier: Modifier = Modifier, tag: String = "ecencySource") {
    val dark = LocalEcencyColors.current.isDark
    Icon(
        // inside a merged row the row's summary already says it (no double announcement)
        EcencyLogoMarkVector, contentDescription = if (LocalMergedRow.current) null else com.ecency.core.app.PostBadges.SOURCE_ECENCY,
        tint = Color(if (dark) com.ecency.core.app.EcencyLogoMark.COLOR_DARK else com.ecency.core.app.EcencyLogoMark.COLOR_LIGHT),
        modifier = modifier.size(12.dp).testTag(tag),
    )
}

/** RN's robot-outline icon: "Author disclosed AI usage" (json_metadata.ai_tools). */
@Composable
fun AiDisclosedBadge(modifier: Modifier = Modifier) {
    Glyph(AppIcon.AI, LocalEcencyColors.current.icon, 14.dp, modifier.testTag("aiDisclosed"), description = if (LocalMergedRow.current) null else com.ecency.core.app.PostBadges.AI_DISCLOSED)
}

/** RN postDisplayView's footer source row: "Posted by {author} via {App} {age}", then the badges. */
@Composable
fun PostedByRow(post: com.ecency.core.api.model.Post, nowMillis: Long) {
    val colors = LocalEcencyColors.current
    Row(Modifier.fillMaxWidth().padding(top = 12.dp).testTag("postedBy"), verticalAlignment = Alignment.CenterVertically) {
        Text(com.ecency.core.app.PostBadges.postedBy(post, nowMillis), color = colors.darkGray, fontSize = 12.sp, modifier = Modifier.weight(1f, fill = false))
        if (com.ecency.core.app.PostBadges.footerFromEcency(post.metadata.app)) EcencySourceBadge(Modifier.padding(start = 3.dp), tag = "postedByEcency")
        if (post.metadata.aiDisclosed) AiDisclosedBadge(Modifier.padding(start = 3.dp))
    }
}

/**
 * RN postInteractionHandler's OptionsModal for an external link: "Link", then Copy, Open in Explore (only when the
 * dApp browser can open a URL, after its warning), Open in System browser, Cancel.
 */
@Composable
fun LinkActionSheet(url: String, onCopy: () -> Unit, onExplore: (com.ecency.core.app.AppScreen) -> Unit, onSystem: () -> Unit, onDismiss: () -> Unit) {
    val colors = LocalEcencyColors.current
    val explore = remember(url) { com.ecency.core.app.LinkSheet.exploreScreen(url) }
    var warn by remember { mutableStateOf(false) }
    if (warn && explore != null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(com.ecency.core.app.LinkSheet.EXPLORE_WARNING_TITLE) },
            text = { Text(com.ecency.core.app.LinkSheet.exploreWarning(url)) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { onDismiss(); onExplore(explore) }, modifier = Modifier.testTag("linkExploreContinue")) { Text(com.ecency.core.app.LinkSheet.CONTINUE) } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text(com.ecency.core.app.LinkSheet.CANCEL) } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(com.ecency.core.app.LinkSheet.TITLE) },
        text = {
            Column(Modifier.testTag("linkSheet")) {
                Text(url, color = colors.darkGray, fontSize = 12.sp, maxLines = 2, modifier = Modifier.padding(bottom = 8.dp))
                LinkOption(com.ecency.core.app.LinkSheet.COPY, "linkCopy") { onDismiss(); onCopy() }
                if (explore != null) LinkOption(com.ecency.core.app.LinkSheet.OPEN_EXPLORE, "linkExplore") { warn = true }
                LinkOption(com.ecency.core.app.LinkSheet.OPEN_SYSTEM, "linkSystem") { onDismiss(); onSystem() }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onDismiss, modifier = Modifier.testTag("linkCancel")) { Text(com.ecency.core.app.LinkSheet.CANCEL) } },
    )
}

@Composable
private fun LinkOption(label: String, tag: String, onClick: () -> Unit) {
    Text(
        label, color = LocalEcencyColors.current.blueText, fontSize = 16.sp,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick, role = Role.Button).padding(vertical = 12.dp).testTag(tag),
    )
}

/** The collapse toggle of a top-level comment's replies: whether they show, and the tap. */
class ThreadToggle(val open: Boolean, val onToggle: () -> Unit)

/** RN commentView's rightButton: a 22 high pill in `$iconColor` with a white chevron, down while collapsed. */
@Composable
fun ThreadTogglePill(toggle: ThreadToggle) {
    val colors = LocalEcencyColors.current
    Box(
        Modifier.padding(start = 8.dp).height(22.dp).widthIn(min = 40.dp).clip(RoundedCornerShape(20.dp)).background(colors.icon)
            .clickable(onClickLabel = if (toggle.open) com.ecency.core.app.CommentThreadCopy.HIDE_REPLIES else com.ecency.core.app.CommentThreadCopy.SHOW_REPLIES, onClick = toggle.onToggle, role = Role.Button)
            .semantics { contentDescription = if (toggle.open) com.ecency.core.app.CommentThreadCopy.HIDE_REPLIES else com.ecency.core.app.CommentThreadCopy.SHOW_REPLIES }
            .inMergedRow(LocalMergedRow.current).testTag("threadToggle"),
        contentAlignment = Alignment.Center,
    ) { Glyph(if (toggle.open) AppIcon.COLLAPSE else AppIcon.EXPAND, Color.White, 16.dp) }
}

/**
 * RN profile's wallet tab for another account (walletView, no dropdowns): "Unclaimed Rewards" as text and
 * "Wallet Details" (HIVE, staked HIVE with delegated / received / net, HBD, savings, next power down). Read only.
 */
@Composable
fun ProfileWalletTab(env: com.ecency.core.app.AppEnvironment, username: String) {
    val colors = LocalEcencyColors.current
    val model = remember(username) { env.profileWalletModel(username) }
    androidx.compose.runtime.DisposableEffect(model) { onDispose { model.close() } }
    androidx.compose.runtime.LaunchedEffect(model) { model.start() }
    val s by model.state.collectAsState()
    Column(Modifier.fillMaxWidth().padding(16.dp).testTag("profileWallet")) {
        val error = s.error
        when {
            s.loading -> LoadingBlock()
            error != null -> ErrorBlock(error, esie = com.ecency.core.esie.EsiePlacement.ERROR, onRetry = model::refresh)
            else -> {
                s.unclaimed?.let { text ->
                    WalletCard(com.ecency.core.app.ProfileWalletCopy.UNCLAIMED_REWARDS) {
                        Text(text, color = colors.darkGray, fontSize = 14.sp, modifier = Modifier.testTag("profileWalletUnclaimed"))
                    }
                }
                WalletCard(com.ecency.core.app.ProfileWalletCopy.WALLET_DETAILS) {
                    s.lines.forEach { line ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = if (line.detail) 2.dp else 8.dp).testTag("walletLine:${line.label.ifEmpty { line.value }}"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(line.label, color = colors.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Text(
                                line.value, color = if (line.net) colors.blueText else if (line.detail) colors.darkGray else colors.text,
                                fontSize = if (line.detail) 13.sp else 14.sp, fontWeight = if (line.detail) FontWeight.Normal else FontWeight.Bold,
                            )
                        }
                    }
                    s.powerDown?.let { Text(it, color = colors.darkGray, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp).testTag("profileWalletPowerDown")) }
                }
            }
        }
    }
}

@Composable
private fun WalletCard(title: String, content: @Composable () -> Unit) {
    val colors = LocalEcencyColors.current
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(8.dp)).background(colors.lightBackground).padding(12.dp)) {
        Text(title, color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
        content()
    }
}

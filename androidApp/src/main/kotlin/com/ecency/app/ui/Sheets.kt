package com.ecency.app.ui

import androidx.compose.ui.semantics.LiveRegionMode

import androidx.compose.ui.semantics.liveRegion

import androidx.compose.ui.semantics.semantics


import androidx.compose.ui.semantics.Role

import com.ecency.core.app.AppIcon
import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.SheetValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.ecency.core.app.ComposeReplyModel
import com.ecency.core.app.PostCard
import com.ecency.core.render.blocks.SafeTarget
import com.ecency.core.app.ReadingUiCopy
import com.ecency.core.app.UiCopy
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.semantics.contentDescription
import com.ecency.core.app.VoteSheetState

/**
 * The compact vote sheet: an Up / Down toggle with the `$` estimate, the weight, a 1..100% slider with
 * 25/50/75 ticks, four presets and one vote button whose label follows the direction ([VoteSheetState]).
 * "Remove vote" when the user already voted. [onVote] gets basis points (10000 = 100%, negative = downvote).
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun VoteSheet(
    card: PostCard,
    onVote: (Int) -> Unit,
    onDismiss: () -> Unit,
    initialPercent: Float = 100f,
    /** RN's `$` estimate for the signed-in account; null shows none. */
    preview: com.ecency.core.app.VotePreview? = null,
) {
    val colors = LocalEcencyColors.current
    // starts at the last weight used, in downvote mode for a post the reader downvoted
    var sheet by remember { mutableStateOf(VoteSheetState.open(initialPercent.toDouble(), card.votedByMe, card.downvotedByMe)) }
    // text, ticks, slider and outlines in the AA text colours; the fills only under white text
    val tint = if (sheet.down) colors.redText else colors.blueText
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.background) {
        // wraps its content; scrolls at large font scales instead of clipping
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding()
                .padding(bottom = 12.dp).testTag("voteSheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            androidx.compose.foundation.layout.FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Row(Modifier.clip(RoundedCornerShape(12.dp)).background(colors.lightBackground).padding(3.dp)) {
                    VoteDirection(ReadingUiCopy.VOTE_UP, UiCopy.UPVOTE, AppIcon.COLLAPSE, !sheet.down, false, "voteDirUp") { sheet = sheet.withDirection(false) }
                    VoteDirection(ReadingUiCopy.VOTE_DOWN, ReadingUiCopy.DOWNVOTE, AppIcon.EXPAND, sheet.down, true, "voteDirDown") { sheet = sheet.withDirection(true) }
                }
                preview?.let {
                    Column(horizontalAlignment = Alignment.End, modifier = Modifier.semantics(mergeDescendants = true) {}) {
                        Text(sheet.valueText(it), fontWeight = FontWeight.Bold, fontSize = 24.sp, color = colors.text, modifier = Modifier.testTag("voteValue"))
                        Text(ReadingUiCopy.ESTIMATED_VALUE, fontSize = 12.sp, color = colors.darkGray)
                    }
                }
            }
            Column {
                Text(sheet.percentText, fontWeight = FontWeight.Bold, fontSize = 40.sp, lineHeight = 44.sp, color = tint, modifier = Modifier.testTag("votePercent"))
                Text(ReadingUiCopy.VOTE_WEIGHT, fontSize = 12.sp, color = colors.darkGray)
            }
            Box {
                Slider(
                    value = sheet.percent.toFloat(), onValueChange = { sheet = sheet.withPercent(it.toDouble()) }, valueRange = 1f..100f,
                    colors = SliderDefaults.colors(thumbColor = tint, activeTrackColor = tint),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = ReadingUiCopy.VOTE_WEIGHT }.testTag("voteSlider"),
                )
                VoteTicks(colors.darkGray, Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(6.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (p in VoteSheetState.PRESETS) {
                    val selected = sheet.selectedPreset == p
                    Box(
                        Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp))
                            .background(colors.lightBackground)
                            .then(if (selected) Modifier.border(2.dp, tint, RoundedCornerShape(10.dp)) else Modifier)
                            .selectable(selected = selected, role = Role.Button) { sheet = sheet.withPreset(p) }
                            .padding(horizontal = 4.dp, vertical = 8.dp)
                            .testTag("votePreset:$p"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(com.ecency.core.l10n.LocaleFormat.percent(p.toDouble(), 0, 0), fontSize = 14.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, color = if (selected) tint else colors.text)
                    }
                }
            }
            Button(
                onClick = { onVote(sheet.weight) },
                colors = ButtonDefaults.buttonColors(contentColor = Color.White, containerColor = if (sheet.down) colors.red else colors.blue),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp).testTag("voteSubmit"),
            ) { Text(sheet.buttonLabel, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
            if (card.votedByMe || card.downvotedByMe) {
                androidx.compose.material3.TextButton(onClick = { onVote(0) }, modifier = Modifier.align(Alignment.CenterHorizontally).heightIn(min = 44.dp).testTag("unvote")) {
                    Text(ReadingUiCopy.REMOVE_VOTE, color = colors.darkGray)
                }
            }
            preview?.votingPowerText?.let {
                Text(it, fontSize = 12.sp, color = colors.darkGray, modifier = Modifier.align(Alignment.CenterHorizontally).testTag("voteVotingPower"))
            }
        }
    }
}

/** One half of the vote sheet's direction toggle: a short label with its chevron, filled when selected. */
@Composable
private fun VoteDirection(label: String, a11y: String, icon: AppIcon, selected: Boolean, down: Boolean, tag: String, onClick: () -> Unit) {
    val colors = LocalEcencyColors.current
    val content = if (selected) Color.White else colors.text
    Row(
        Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp)).background(if (!selected) Color.Transparent else if (down) colors.red else colors.blue)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = a11y }
            .padding(horizontal = 14.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        androidx.compose.material3.Icon(icon.vector(), contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
        Text(label, color = content, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}

/** Tick marks under the slider at 25, 50 and 75 percent (mirrored right to left). */
@Composable
private fun VoteTicks(color: Color, modifier: Modifier) {
    val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    androidx.compose.foundation.Canvas(modifier) {
        // the thumb's half width insets the track at both ends
        val inset = 10.dp.toPx()
        val track = size.width - 2 * inset
        for (p in listOf(25, 50, 75)) {
            val f = (p - 1) / 99f
            val x = inset + track * (if (rtl) 1 - f else f)
            drawLine(color, androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x, size.height), strokeWidth = 1.5.dp.toPx())
        }
    }
}

/**
 * Reply composer (RN quick reply, quickPostModalContent in comment mode): the text, the attached image, the
 * clipboard chip and RN's toolbar on its own line under the text (image, camera, files, dictation, AI assist: the wave
 * composer's [QuickComposeTools] without poll and video), then expand to the full editor and Reply. The draft lives in [model] (app-wide, one per parent) until a send succeeds, so
 * retries reuse its permlink and closing the sheet or leaving the post keeps it. While a send runs the
 * sheet cannot be swiped, tapped or backed away: back and scrim taps go through the same
 * confirmValueChange as a swipe (the send itself would survive, but its result and a retry belong on screen).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReplySheet(env: com.ecency.core.app.AppEnvironment, model: ComposeReplyModel, onSent: () -> Unit, onDismiss: () -> Unit, onExpand: ((com.ecency.core.app.EditorArgs) -> Unit)? = null) {
    val colors = LocalEcencyColors.current
    val state by model.state.collectAsState()
    val sending by rememberUpdatedState(state.isSending)
    LaunchedEffect(state.sent) { if (state.sent != null) onSent() }
    // RN quick reply's image button: the app's pickers behind the shared attach (as the wave composer)
    (env.waves.attacher as? com.ecency.core.app.WaveUploadAttacher)?.let { WaveImagePickers(env, it) }
    ClipboardImageWatch(model) { model.setClipboardHasImage(it) }
    var aiSheet by remember { mutableStateOf<String?>(null) }
    // RN: a reply HiveSigner refused for the missing ecency.app authority offers the grant; closing it is a skip
    if (state.authorityPrompt) PostingAuthorityDialog(onAnswer = model::answerAuthorityPrompt, onDismiss = { model.answerAuthorityPrompt(false) })
    if (state.conflict) {
        // an earlier attempt already posted this reply and the text changed since: never edited silently
        AlertDialog(
            onDismissRequest = model::dismissConflict,
            title = { Text(UiCopy.YOUR_REPLY_WAS_ALREADY) },
            text = { Text(UiCopy.UPDATE_IT_OR_POST) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { model.resolveConflict(update = true) }, modifier = Modifier.testTag("replyConflictUpdate")) { Text(UiCopy.UPDATE_IT) } },
            dismissButton = {
                Row {
                    // Cancel: nothing is sent, the text stays in the sheet
                    androidx.compose.material3.TextButton(onClick = model::dismissConflict, modifier = Modifier.testTag("replyConflictCancel")) { Text(UiCopy.CANCEL) }
                    androidx.compose.material3.TextButton(onClick = { model.resolveConflict(update = false) }, modifier = Modifier.testTag("replyConflictNew")) { Text(UiCopy.NEW_REPLY) }
                }
            },
        )
    }
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden || !sending },
    )
    ModalBottomSheet(
        onDismissRequest = { if (!sending) onDismiss() },
        sheetState = sheetState,
        containerColor = colors.background,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).imePadding().navigationBarsPadding().padding(bottom = 12.dp).testTag("replySheet")) {
            Text(ReadingUiCopy.replyTo(model.parent.author), fontWeight = FontWeight.Bold, color = colors.text, fontSize = 16.sp)
            OutlinedTextField(
                value = state.text,
                onValueChange = model::setText,
                enabled = !state.isSending,
                placeholder = { Text(ReadingUiCopy.WHAT_DO_YOU_THINK, color = colors.darkGray) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = colors.text, unfocusedTextColor = colors.text,
                    focusedBorderColor = colors.blueText, unfocusedBorderColor = colors.icon,
                ),
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp).padding(top = 8.dp).testTag("replyText"),
            )
            state.error?.let {
                Text(it.message, color = colors.redText, fontSize = 13.sp, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.padding(top = 6.dp).testTag("replyError"))
            }
            // RN quick post: too short to earn points
            if (com.ecency.core.app.Quests.showsShortHint("me", false, state.text)) {
                Text(com.ecency.core.app.Quests.SHORT_CONTENT_HINT, color = colors.darkGray, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp).testTag("shortContentHint"))
            }
            AttachedImageThumb(state.imageUrl, state.isAttaching, "replyImage", enabled = !state.isSending, onRemove = model::removeImage)
            if (state.clipboardChip) {
                ClipboardImageChip("reply", enabled = !state.isSending && !state.isAttaching, onPaste = model::pasteClipboardImage, onDismiss = model::dismissClipboardChip)
            }
            // RN quick reply's toolbar, on its own line so every button shows next to the keyboard
            QuickComposeTools(
                "reply", state.isSending, state.isAttaching, onAttach = model::attach,
                onDictate = { aiSheet = "dictation" }, onAiAssist = { aiSheet = "aiAssist" }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                // RN quick reply "expand": the same reply continues in the full editor
                if (onExpand != null && !state.isSending) {
                    androidx.compose.material3.TextButton(onClick = { model.expand(onExpand) }, enabled = !state.isAttaching, modifier = Modifier.testTag("replyExpand")) { Text(ReadingUiCopy.OPEN_EDITOR, color = colors.blueText) }
                    androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                }
                // the button turns into its own progress while the reply goes out (Motion.kt); the sheet closes on success
                val phase = rememberSendPhase(state.isSending, sent = state.sent, holdsDone = false)
                Button(
                    onClick = model::send,
                    enabled = state.canSend,
                    colors = ButtonDefaults.buttonColors(contentColor = androidx.compose.ui.graphics.Color.White, containerColor = colors.blue),
                    modifier = Modifier.testTag("replySend"),
                ) {
                    SendContent(phase, Color.White, 18.dp) {
                        Text(if (state.error?.retryable == true) UiCopy.RETRY else ReadingUiCopy.REPLY, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
    QuickComposeAiSheet(env, model, aiSheet) { aiSheet = null }
}

/**
 * Third-party video player for an allowlisted embed. The WebView loads only [embedUrl], sent with
 * `Referer: https://ecency.com/` (players such as YouTube refuse an embed without an embedding origin:
 * error 153), as the iOS sheet does. Every navigation, main frame or subframe, must stay on the embed's
 * own host over https, and a video file on its own file ([playerNavigationAllowed], shared with iOS); popups are off; no JavaScript bridges, no file or
 * content access. Cookies and storage are not kept: third-party cookies are refused and everything the
 * player stored is cleared when the sheet closes. Cookies and storage are process-wide: the HiveSigner web view
 * (AuthWeb.kt) clears them too, so a sheet that closes signs nothing else out. JavaScript itself stays
 * on because the players need it, except for a video file ([SafeTarget.playerAllowsScript]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoPlayerSheet(embedUrl: String, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color.Black) {
        Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp).testTag("videoSheet")) {
            PlayerWebView(embedUrl, Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black))
        }
    }
}

/** The Referer every player request starts with. */
internal const val EMBED_REFERER = "https://ecency.com/"

/** Whether the player WebView may start on [url] (only allowlisted https embeds). */
internal fun playerMayLoad(url: String): Boolean =
    url.startsWith("https://", ignoreCase = true) && SafeTarget.embed(url) != null

/**
 * Whether the player may navigate a frame to [url]: the shared rule both apps use ([SafeTarget.playerNavigationAllowed],
 * iOS `VideoEmbedPolicy`), read with the WHATWG parser as Chromium reads it. Applies to subframes too.
 */
internal fun playerNavigationAllowed(url: String, embedUrl: String): Boolean = SafeTarget.playerNavigationAllowed(url, embedUrl)

/**
 * Loads [embedUrl] with the embedding Referer, only when it is an allowlisted https player. A video file the post
 * links directly ([SafeTarget.playerAllowsScript] false) is never the page: the WebView does not ask
 * shouldOverrideUrlLoading for a POST, so whatever that URL serves could post a form elsewhere. It plays in an
 * app-made page with one video element ([mediaFilePage]) on the file's own origin.
 */
internal fun WebView.loadEmbed(embedUrl: String) {
    if (!playerMayLoad(embedUrl)) return
    if (SafeTarget.playerAllowsScript(embedUrl)) {
        loadUrl(embedUrl, mapOf("Referer" to EMBED_REFERER))
    } else {
        val origin = android.net.Uri.parse(embedUrl).let { "${it.scheme}://${it.host}/" }
        loadDataWithBaseURL(origin, mediaFilePage(embedUrl), "text/html", "utf-8", null)
    }
}

/** The page a video file plays in: no script, one video element, [url] (validated by [playerMayLoad]) escaped. */
internal fun mediaFilePage(url: String): String {
    val src = url.replace("&", "&amp;").replace("\"", "&quot;").replace("'", "&#39;").replace("<", "&lt;").replace(">", "&gt;")
    return "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
        "<style>html,body{margin:0;height:100%;background:#000}video{width:100%;height:100%}</style></head>" +
        "<body><video src=\"$src\" controls playsinline autoplay></video></body></html>"
}

/** The player web view's settings and navigation rule ([playerNavigationAllowed]) for [embedUrl]. */
@SuppressLint("SetJavaScriptEnabled")
internal fun WebView.setUpPlayer(embedUrl: String) {
    // players need script; a video file the post links directly plays without it
    settings.javaScriptEnabled = SafeTarget.playerAllowsScript(embedUrl)
    settings.domStorageEnabled = true
    settings.mediaPlaybackRequiresUserGesture = false
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.setSupportMultipleWindows(false)
    settings.javaScriptCanOpenWindowsAutomatically = false
    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
    settings.setGeolocationEnabled(false)
    CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
    webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            // true = blocked: no frame leaves the embed's host
            !playerNavigationAllowed(request.url.toString(), embedUrl)
    }
}

@Composable
internal fun PlayerWebView(embedUrl: String, modifier: Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                setUpPlayer(embedUrl)
                loadEmbed(embedUrl)
            }
        },
        onRelease = { view ->
            view.stopLoading()
            view.clearHistory()
            view.clearCache(true)
            view.destroy()
            // incognito-like: nothing the player stored outlives it, but never under an open HiveSigner page
            WebData.wipeAfterPlayer()
        },
    )
}

/**
 * RN imageViewer (react-native-image-viewing): the body images in a pager starting at the tapped one, pinch and
 * double tap (1.5x) zoom, and the header: close, "Preview (i/n)", share, copy link and save to the gallery.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GallerySheet(urls: List<String>, index: Int, onDismiss: () -> Unit) {
    val pager = rememberPagerState(initialPage = index.coerceIn(0, (urls.size - 1).coerceAtLeast(0))) { urls.size }
    val feedx = LocalFeedx.current
    val host = feedx?.host
    var zoomed by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var alert by remember { mutableStateOf<Pair<String, String>?>(null) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color.Black,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp).testTag("gallery")) {
            val url = urls.getOrNull(pager.currentPage).orEmpty()
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                GalleryButton(AppIcon.CLOSE, com.ecency.core.app.UiCopy.CLOSE, "galleryClose", onDismiss)
                Text(
                    com.ecency.core.app.UiCopy.preview(pager.currentPage + 1, urls.size), color = Color.White, fontSize = 16.sp,
                    modifier = Modifier.weight(1f).padding(start = 4.dp).testTag("galleryPosition"),
                )
                if (host != null && url.isNotEmpty()) {
                    GalleryButton(AppIcon.SHARE, com.ecency.core.app.UiCopy.SHARE, "galleryShare") { host.share(url) }
                    GalleryButton(AppIcon.CLIPBOARD, com.ecency.core.app.UiCopy.COPY_LINK, "galleryCopy") {
                        host.copy(url)
                        feedx.env.ui.toast(com.ecency.core.app.UiCopy.COPIED)
                    }
                    if (saving) {
                        Spinner(Modifier.padding(10.dp), size = 24.dp, color = Color.White)
                    } else {
                        GalleryButton(AppIcon.DOWNLOAD, com.ecency.core.app.UiCopy.SAVE_IMAGE, "gallerySave") {
                            saving = true
                            host.saveImage(url) { error ->
                                saving = false
                                alert = if (error == null) com.ecency.core.app.UiCopy.IMAGE_SAVED to "" else com.ecency.core.app.UiCopy.FAIL to error
                            }
                        }
                    }
                }
            }
            HorizontalPager(state = pager, userScrollEnabled = !zoomed, modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f)) { page ->
                ZoomableImage(urls[page], UiCopy.imageOf(page + 1, urls.size), Modifier.testTag("galleryImage:$page")) { z -> if (page == pager.currentPage) zoomed = z }
            }
        }
    }
    alert?.let { (title, body) ->
        AlertDialog(
            onDismissRequest = { alert = null },
            title = { Text(title, Modifier.testTag("galleryAlert")) },
            text = if (body.isNotEmpty()) ({ Text(body) }) else null,
            confirmButton = { androidx.compose.material3.TextButton(onClick = { alert = null }) { Text(com.ecency.core.app.UiCopy.OK) } },
        )
    }
}

@Composable
private fun GalleryButton(icon: AppIcon, label: String, tag: String, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onClick, role = Role.Button).padding(10.dp).testTag(tag)) {
        Glyph(icon, Color.White, 24.dp, description = label)
    }
}

/** The image viewer zooms from 1x up to this. */
private const val MAX_ZOOM = 5f

/**
 * One image of the viewer: two fingers zoom (1x to 5x) and pan; a double tap toggles 1.5x (RN doubleTapScale). At 1x
 * a one-finger drag is left to the pager, so swiping between images still works; [onZoom] says when it is zoomed.
 */
@Composable
fun ZoomableImage(url: String, description: String, modifier: Modifier = Modifier, onZoom: (Boolean) -> Unit = {}) {
    var scale by remember(url) { mutableStateOf(1f) }
    var offset by remember(url) { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    androidx.compose.runtime.LaunchedEffect(scale > 1f) { onZoom(scale > 1f) }
    Box(
        modifier.fillMaxSize().clipToBounds()
            .pointerInput(url) {
                detectTapGestures(onDoubleTap = {
                    scale = if (scale > 1f) 1f else 1.5f
                    offset = androidx.compose.ui.geometry.Offset.Zero
                })
            }
            .pointerInput(url) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed >= 2 || scale > 1f) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            scale = (scale * zoom).coerceIn(1f, MAX_ZOOM)
                            val maxX = size.width * (scale - 1) / 2
                            val maxY = size.height * (scale - 1) / 2
                            offset = if (scale <= 1f) androidx.compose.ui.geometry.Offset.Zero
                            else androidx.compose.ui.geometry.Offset((offset.x + pan.x).coerceIn(-maxX, maxX), (offset.y + pan.y).coerceIn(-maxY, maxY))
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        // the full file through the proxy, not a sized body variant, decoded for the widest zoom: a screen sized
        // decode scaled up 5x would blur (Coil's maxBitmapSize, 4096 px a side by default, bounds the memory)
        val context = androidx.compose.ui.platform.LocalContext.current
        val screen = androidx.compose.ui.platform.LocalConfiguration.current
        val density = androidx.compose.ui.platform.LocalDensity.current.density
        val side = (maxOf(screen.screenWidthDp, screen.screenHeightDp) * density * MAX_ZOOM).toInt().coerceAtLeast(1)
        val request = remember(url, side) {
            coil3.request.ImageRequest.Builder(context).data(url).size(side, side)
                .precision(coil3.size.Precision.INEXACT).build()
        }
        AsyncImage(
            model = request,
            contentDescription = description,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            }.testTag("zoom:$url"),
        )
    }
}

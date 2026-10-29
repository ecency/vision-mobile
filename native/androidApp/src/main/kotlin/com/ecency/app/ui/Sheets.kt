package com.ecency.app.ui

import androidx.compose.ui.semantics.LiveRegionMode

import androidx.compose.ui.semantics.liveRegion

import androidx.compose.ui.semantics.semantics


import androidx.compose.ui.semantics.Role

import com.ecency.core.app.AppIcon
import android.annotation.SuppressLint
import android.net.Uri
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
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
import kotlin.math.roundToInt
import com.ecency.core.app.ReadingUiCopy
import com.ecency.core.app.UiCopy

/**
 * Vote weight, as the RN upvote popover: a 1..100% slider, upvote and downvote, and "remove"
 * when the user already voted. [onVote] gets basis points (10000 = 100%, negative = downvote).
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    // starts at the last weight used, as the RN upvote slider and the iOS sheet do
    var percent by remember { mutableFloatStateOf(initialPercent.coerceIn(1f, 100f)) }
    // RN popover: the first tap on the down arrow only switches to downvote mode (red), the second one votes
    var downMode by remember { mutableStateOf(card.downvotedByMe) }
    val accent = if (downMode) Color_Downvote else colors.blueText
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.background) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp).testTag("voteSheet")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(ReadingUiCopy.VOTE_WEIGHT, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = colors.text, modifier = Modifier.weight(1f))
                preview?.let {
                    val weight = percent.roundToInt() * 100
                    Text((if (downMode) "-" else "") + it.text(weight), fontSize = 14.sp, color = colors.darkGray, modifier = Modifier.padding(end = 10.dp).testTag("voteValue"))
                }
                Text((if (downMode) "-" else "") + "${percent.roundToInt()}%", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = accent, modifier = Modifier.testTag("votePercent"))
            }
            Slider(
                value = percent, onValueChange = { percent = it }, valueRange = 1f..100f,
                colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent),
                modifier = Modifier.testTag("voteSlider"),
            )
            Text(card.payoutText, fontSize = 12.sp, color = colors.darkGray)
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (card.votedByMe || card.downvotedByMe) {
                    OutlinedButton(onClick = { onVote(0) }, modifier = Modifier.testTag("unvote")) { Text(ReadingUiCopy.REMOVE_VOTE, color = colors.darkGray) }
                }
                OutlinedButton(
                    onClick = { if (downMode) onVote(-(percent.roundToInt() * 100)) else downMode = true },
                    modifier = Modifier.testTag("downvote"),
                ) { Text(if (downMode) ReadingUiCopy.downvotePercent(percent.roundToInt()) else ReadingUiCopy.DOWNVOTE, color = Color_Downvote) }
                Button(
                    onClick = { if (downMode) downMode = false else onVote(percent.roundToInt() * 100) },
                    colors = ButtonDefaults.buttonColors(contentColor = androidx.compose.ui.graphics.Color.White, containerColor = colors.blue),
                    modifier = Modifier.weight(1f).testTag("upvote"),
                ) { Text(UiCopy.UPVOTE, color = Color.White, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

/**
 * Reply composer. The draft lives in [model] (app-wide, one per parent) until a send succeeds, so
 * retries reuse its permlink and closing the sheet or leaving the post keeps it. While a send runs the
 * sheet cannot be swiped, tapped or backed away: back and scrim taps go through the same
 * confirmValueChange as a swipe (the send itself would survive, but its result and a retry belong on screen).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReplySheet(model: ComposeReplyModel, onSent: () -> Unit, onDismiss: () -> Unit, onExpand: ((com.ecency.core.app.EditorArgs) -> Unit)? = null) {
    val colors = LocalEcencyColors.current
    val state by model.state.collectAsState()
    val sending by rememberUpdatedState(state.isSending)
    LaunchedEffect(state.sent) { if (state.sent != null) onSent() }
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
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                // RN quick reply "expand": the same reply continues in the full editor
                if (onExpand != null && !state.isSending) {
                    androidx.compose.material3.TextButton(onClick = { model.expand(onExpand) }, modifier = Modifier.testTag("replyExpand")) { Text(ReadingUiCopy.OPEN_EDITOR, color = colors.blueText) }
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
}

/**
 * Third-party video player for an allowlisted embed. The WebView loads only [embedUrl], sent with
 * `Referer: https://ecency.com/` (players such as YouTube refuse an embed without an embedding origin:
 * error 153), as the iOS sheet does. Every navigation, main frame or subframe, must stay on the embed's
 * own host over https ([playerNavigationAllowed]); popups are off; no JavaScript bridges, no file or
 * content access. Cookies and storage are not kept: third-party cookies are refused and everything the
 * player stored is cleared when the sheet closes. Cookies and storage are process-wide: the HiveSigner web view
 * (AuthWeb.kt) clears them too, so a sheet that closes signs nothing else out. JavaScript itself stays
 * on because the players need it.
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
 * Whether the player may navigate a frame to [url] (iOS `VideoEmbedPolicy`): https on the embed's own
 * host (with or without `www.`, [SafeTarget.samePlayerHost]), or the `about:blank` / `about:srcdoc` frames players create. Applies to subframes too.
 */
internal fun playerNavigationAllowed(url: String, embedUrl: String): Boolean {
    if (url == "about:blank" || url == "about:srcdoc") return true
    val target = runCatching { Uri.parse(url) }.getOrNull() ?: return false
    val embed = runCatching { Uri.parse(embedUrl) }.getOrNull() ?: return false
    if (!target.scheme.equals("https", ignoreCase = true)) return false
    return SafeTarget.samePlayerHost(target.host ?: return false, embed.host ?: return false)
}

/** Loads [embedUrl] with the embedding Referer, only when it is an allowlisted https player. */
internal fun WebView.loadEmbed(embedUrl: String) {
    if (playerMayLoad(embedUrl)) loadUrl(embedUrl, mapOf("Referer" to EMBED_REFERER))
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun PlayerWebView(embedUrl: String, modifier: Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
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
                        androidx.compose.material3.CircularProgressIndicator(Modifier.padding(10.dp).size(24.dp), color = Color.White, strokeWidth = 2.dp)
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
                            scale = (scale * zoom).coerceIn(1f, 5f)
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
        AsyncImage(
            model = url,
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

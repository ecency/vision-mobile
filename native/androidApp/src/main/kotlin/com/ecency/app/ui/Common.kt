package com.ecency.app.ui

import androidx.compose.ui.semantics.LiveRegionMode

import androidx.compose.ui.semantics.liveRegion


import androidx.compose.ui.semantics.Role

import com.ecency.core.app.AppIcon
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.ecency.core.app.AppError
import com.ecency.core.app.PostCard
import com.ecency.core.app.UiCopy

/**
 * Round avatar with the RN `$borderColor` hairline and a placeholder disc until it loads. No reputation badge
 * (a deliberate change from RN): reputation shows only in the quick profile sheet.
 */
@Composable
fun Avatar(url: String, size: Dp, modifier: Modifier = Modifier) {
    val colors = LocalEcencyColors.current
    Box(modifier.size(size)) {
        Box(
            Modifier.size(size).clip(CircleShape).background(colors.lightBackground)
                .border(1.dp, colors.border, CircleShape),
        ) {
            if (url.isNotEmpty()) {
                AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

/**
 * The RN action row: upvote circle, payout, heart with vote count, then comments. Used by feed
 * cards, the post screen's bottom bar and comments.
 */
@Composable
fun ActionsRow(
    card: PostCard,
    voteInFlight: Boolean,
    onVote: () -> Unit,
    onComments: (() -> Unit)?,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
    /** RN: the vote count opens the voters screen, the reblog count the reblogs screen, the gift the tipping. */
    extras: RowExtras? = null,
    /** The open payout breakdown; a merged row hoists it to offer it as a TalkBack action. */
    payoutDetails: androidx.compose.runtime.MutableState<com.ecency.core.api.model.Post?> = rememberPayoutDetails(),
    /** A comment's row: the comments icon with its count is the reply button (no separate "Reply" text). */
    commentsReply: Boolean = false,
) {
    val colors = LocalEcencyColors.current
    val merged = LocalMergedRow.current
    // a flow row: at a large font size on a narrow phone the trailing actions (a comment's thread toggle) wrap to a
    // second line instead of being squeezed and cut
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    androidx.compose.foundation.layout.FlowRow(modifier, itemVerticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).clickable(enabled = !voteInFlight, onClick = onVote, role = Role.Button)
                .semantics { contentDescription = if (card.votedByMe) UiCopy.UPVOTED else if (card.downvotedByMe) UiCopy.DOWNVOTED else UiCopy.UPVOTE }
                .inMergedRow(merged).testTag("vote"),
            contentAlignment = Alignment.Center,
        ) {
            // spinner while the vote is in flight, then the glyph pops back in filled (Motion.kt); the fill itself
            // pops when the vote lands from elsewhere (another card, a rollback)
            AnimatedSwap(voteInFlight, label = "vote") { inFlight ->
                if (inFlight) {
                    CircularProgressIndicator(Modifier.size(22.dp), color = colors.blueText, strokeWidth = 2.dp)
                } else {
                    val voted = card.votedByMe || card.downvotedByMe
                    UpvoteGlyph(
                        if (card.downvotedByMe) Color_Downvote else colors.blueText,
                        filled = voted,
                        down = card.downvotedByMe,
                        size = 26.dp,
                        modifier = Modifier.popOnChange(voted, enabled = voted),
                    )
                }
            }
        }
        Box {
            val details = payoutDetails
            val payoutPost = extras?.payoutPost
            Text(
                card.payoutText.replace("$", "$ "),
                fontSize = 12.sp,
                color = colors.darkGray,
                textDecoration = if (card.isPayoutDeclined) TextDecoration.LineThrough else null,
                modifier = Modifier.padding(start = 4.dp)
                    .then(if (payoutPost != null) Modifier.clickable(onClickLabel = com.ecency.core.app.PayoutCopy.DETAILS, role = Role.Button) { details.value = payoutPost() } .inMergedRow(merged) else Modifier)
                    .testTag("payout"),
            )
            details.value?.let { post -> PayoutPopover(post) { details.value = null } }
        }
        // tighter on a comment, whose row also carries the thread toggle
        Spacer(Modifier.width(if (commentsReply) 8.dp else 20.dp))
        Row(
            Modifier.clip(RoundedCornerShape(4.dp)).then(if (extras?.onVoters != null) Modifier.clickable(onClickLabel = com.ecency.core.l10n.L.post.a11yVotersHint, onClick = extras.onVoters, role = Role.Button).inMergedRow(merged).testTag("voters") else Modifier)
                .semantics { contentDescription = com.ecency.core.l10n.L.post.a11yVotes(count = card.votes) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Glyph(AppIcon.HEART, colors.icon, 22.dp)
            AnimatedCounter(card.votes.toLong(), Modifier.padding(start = 6.dp)) {
                Text(com.ecency.core.l10n.LocaleFormat.integer(it), fontSize = 12.sp, color = colors.darkGray, modifier = Modifier.testTag("votes"))
            }
        }
        Spacer(Modifier.weight(1f))
        if (extras?.onReblogs != null) {
            Row(
                Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = extras.onReblogs, role = Role.Button).inMergedRow(merged).padding(horizontal = 6.dp, vertical = 6.dp).testTag("reblogs"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Glyph(AppIcon.REBLOG, colors.icon, 22.dp, description = com.ecency.core.l10n.L.post.a11yReblogs(count = card.reblogs))
                if (card.reblogs > 0) {
                    AnimatedCounter(card.reblogs.toLong(), Modifier.padding(start = 6.dp)) { Text(com.ecency.core.l10n.LocaleFormat.integer(it), fontSize = 12.sp, color = colors.darkGray) }
                }
            }
        }
        Row(
            Modifier.clip(RoundedCornerShape(4.dp))
                .then(
                    if (onComments != null) {
                        val hint = if (commentsReply) com.ecency.core.l10n.L.post.a11yReplyHint else com.ecency.core.l10n.L.post.a11yCommentsHint
                        Modifier.clickable(onClickLabel = hint, onClick = onComments, role = Role.Button).inMergedRow(merged)
                    } else Modifier,
                )
                .semantics {
                    if (commentsReply) {
                        // a button labelled for replying, the count as its state
                        contentDescription = com.ecency.core.l10n.L.post.a11yReplyHint
                        stateDescription = com.ecency.core.l10n.L.post.a11yComments(count = card.comments)
                    } else {
                        contentDescription = com.ecency.core.l10n.L.post.a11yComments(count = card.comments)
                    }
                }
                .testTag(if (commentsReply) "reply" else "postComments")
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Glyph(AppIcon.COMMENT, colors.icon, 22.dp)
            AnimatedCounter(card.comments.toLong(), Modifier.padding(start = 6.dp)) {
                Text(com.ecency.core.l10n.LocaleFormat.integer(it), fontSize = 12.sp, color = colors.darkGray, modifier = Modifier.testTag("comments"))
            }
        }
        if (extras?.onTip != null) {
            val tips = extras.tips
            // 8 dp between the comment's reply, tip and thread toggle targets (README "Accessibility")
            Row(
                Modifier.padding(start = 8.dp).clip(RoundedCornerShape(4.dp)).clickable(onClick = extras.onTip, role = Role.Button).inMergedRow(merged)
                    .semantics {
                        contentDescription = UiCopy.SEND_TIP
                        // RN post.a11y_tips: the post bar says how many tips the post has
                        // (nothing while the count is unknown: not read yet, or the read failed)
                        tips?.a11yCount?.let { stateDescription = it }
                    }
                    .testTag("tip")
                    // the post bar's gift is a full 48 dp target; comment rows keep their height (Compose widens a
                    // small pointer target's touch area to 48 dp without growing the layout)
                    .then(if (tips != null) Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp) else Modifier)
                    .padding(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Glyph(AppIcon.GIFT, colors.icon, 22.dp)
                // RN tipsQuery count beside the gift (compact; none at zero)
                tips?.countText?.let { Text(it, fontSize = 12.sp, color = colors.darkGray, modifier = Modifier.padding(start = 6.dp).testTag("tipCount")) }
            }
        }
        trailing()
    }
}

@Composable
fun rememberPayoutDetails() = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<com.ecency.core.api.model.Post?>(null) }

/** Optional taps of the action row (RN postCardActionsPanel). */
class RowExtras(
    val onVoters: (() -> Unit)? = null,
    val onReblogs: (() -> Unit)? = null,
    val onTip: (() -> Unit)? = null,
    /** RN upvoteButton: the payout amount opens the payout breakdown of this post (no login needed). */
    val payoutPost: (() -> com.ecency.core.api.model.Post?)? = null,
    /** The post screen's tip count beside the gift (RN tipsQuery); null elsewhere. */
    val tips: com.ecency.core.app.PostTipsState? = null,
)

/** RN uses '#ec8b88' for the downvoted icon. */
val Color_Downvote = androidx.compose.ui.graphics.Color(0xFFEC8B88)

/** Full-width message with an optional retry, for empty, error and end states. */
@Composable
fun MessageBlock(text: String, modifier: Modifier = Modifier, actionLabel: String? = null, onAction: (() -> Unit)? = null) =
    EsieMessageBlock(null, text, modifier, actionLabel, onAction)

/**
 * [MessageBlock] with Esie above the message (README "Esie"), for whole-screen states: resting for empty lists
 * ([com.ecency.core.esie.EsiePlacement.EMPTY]), sad for failures ([com.ecency.core.esie.EsiePlacement.ERROR]).
 */
@Composable
fun EsieMessageBlock(
    esie: com.ecency.core.esie.EsiePlacement?,
    text: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = LocalEcencyColors.current
    Column(modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (esie != null) EsieView(esie, Modifier.padding(bottom = 12.dp))
        // announced when it appears (an empty list, a failure), as the RN toast would be
        Text(text, color = colors.darkGray, fontSize = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("message"))
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction, modifier = Modifier.testTag("retry")) { Text(actionLabel, color = colors.blueText, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
fun ErrorBlock(error: AppError, modifier: Modifier = Modifier, esie: com.ecency.core.esie.EsiePlacement? = null, onRetry: () -> Unit) =
    EsieMessageBlock(esie, error.message, modifier, if (error.retryable) UiCopy.RETRY else null, onRetry)

@Composable
fun LoadingBlock(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(72.dp).testTag("loading"), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(28.dp), color = LocalEcencyColors.current.blueText, strokeWidth = 2.5.dp)
    }
}

/** Calls [onFirstDraw] once, after this element's first frame was drawn. */
fun Modifier.onFirstDraw(onFirstDraw: () -> Unit): Modifier = this.then(
    Modifier.drawWithContent {
        drawContent()
        onFirstDraw()
    },
)

/** A one-shot flag holder for [onFirstDraw] callers. */
class Once {
    private var done = false
    fun run(block: () -> Unit) {
        if (!done) {
            done = true
            block()
        }
    }
}

@Composable
fun rememberOnce(vararg keys: Any?): Once = remember(*keys) { Once() }

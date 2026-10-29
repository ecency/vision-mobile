package com.ecency.app.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import com.ecency.core.app.PostCard
import com.ecency.core.app.ReadingUiCopy
import com.ecency.core.app.TimeAgo
import com.ecency.core.l10n.L

/*
 * TalkBack helpers (README "Accessibility", Android). A list row (a post card, a comment, a wave) is one focus stop
 * that reads a summary and offers its taps (vote, comments, reblogs, the author...) as custom actions, instead of a
 * dozen small stops per row. The row sets the summary and the actions; the taps inside it stay for touch but hide
 * from TalkBack through [LocalMergedRow].
 */

/** True inside a row that speaks for its children: their own taps are then hidden from TalkBack. */
val LocalMergedRow = staticCompositionLocalOf { false }

/** Hides this node from TalkBack (it stays tappable and visible, and in the semantics tree for tests). */
val HiddenFromA11y: Modifier = Modifier.semantics { hideFromAccessibility() }

/** [HiddenFromA11y] inside a merged row ([LocalMergedRow]), nothing elsewhere. */
fun Modifier.inMergedRow(merged: Boolean): Modifier = if (merged) this.then(HiddenFromA11y) else this

/** A custom TalkBack action that runs [run]; [label] is what TalkBack's actions menu shows. */
fun a11yAction(label: String, run: () -> Unit) = CustomAccessibilityAction(label) { run(); true }

/**
 * What TalkBack reads for a post card: who reblogged it, the title, the author, the community, the age, the source
 * and AI badges, the summary, the votes, the comments and the payout, each part localized on its own.
 */
fun postCardSummary(card: PostCard, nowMillis: Long): String = buildList {
    card.rebloggedBy.firstOrNull()?.let { add(ReadingUiCopy.rebloggedBy(it)) }
    if (card.title.isNotEmpty()) add(card.title)
    add(card.author)
    (card.communityTitle ?: card.category).takeIf { it.isNotEmpty() }?.let { add(ReadingUiCopy.inTopic(it)) }
    add(TimeAgo.short(card.createdEpochMillis, nowMillis))
    if (card.isFromEcency) add(com.ecency.core.app.PostBadges.SOURCE_ECENCY)
    if (card.aiDisclosed) add(com.ecency.core.app.PostBadges.AI_DISCLOSED)
    if (card.summary.isNotEmpty()) add(card.summary)
    add(L.post.a11yVotes(count = card.votes))
    add(L.post.a11yComments(count = card.comments))
    add(card.payoutText)
}.joinToString(", ")

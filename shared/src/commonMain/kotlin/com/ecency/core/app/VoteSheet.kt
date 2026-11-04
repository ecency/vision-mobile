package com.ecency.core.app

import com.ecency.core.l10n.LocaleFormat
import kotlin.math.roundToInt

/**
 * What the vote sheet has chosen: the direction (Up / Down toggle) and the weight in whole percent, 1 to 100
 * (the slider and the presets). Both apps draw the sheet from it; [weight] goes to [VoteModel.vote].
 * Switching direction keeps the weight (one remembered weight for both directions, as RN).
 */
data class VoteSheetState(val percent: Int, val down: Boolean) {
    /** Basis points, negative for a downvote. */
    val weight: Int get() = percent * 100 * (if (down) -1 else 1)

    /** The preset equal to the weight, else null. */
    val selectedPreset: Int? get() = percent.takeIf { it in PRESETS }

    /** "12%", "-12%" in downvote mode. */
    val percentText: String get() = LocaleFormat.percent((if (down) -percent else percent).toDouble(), 0, 0)

    /** The one vote button: "Upvote 12%" or "Downvote 12%". */
    val buttonLabel: String get() = if (down) ReadingUiCopy.downvotePercent(percent) else ReadingUiCopy.upvotePercent(percent)

    fun withDirection(down: Boolean): VoteSheetState = copy(down = down)

    fun withPreset(preset: Int): VoteSheetState = copy(percent = clamp(preset.toDouble()))

    /** The slider's value, rounded to whole percent. */
    fun withPercent(value: Double): VoteSheetState = copy(percent = clamp(value))

    /** The `$` estimate, with RN's "-" in downvote mode. */
    fun valueText(preview: VotePreview): String = (if (down) "-" else "") + preview.text(percent * 100)

    companion object {
        val PRESETS: List<Int> = listOf(25, 50, 75, 100)

        /** Opens at the remembered weight; a post the reader downvoted (and did not upvote) opens in downvote mode. */
        fun open(rememberedPercent: Double, votedByMe: Boolean, downvotedByMe: Boolean): VoteSheetState =
            VoteSheetState(clamp(rememberedPercent), downvotedByMe && !votedByMe)

        private fun clamp(value: Double): Int = if (value.isNaN()) 100 else value.roundToInt().coerceIn(1, 100)
    }
}

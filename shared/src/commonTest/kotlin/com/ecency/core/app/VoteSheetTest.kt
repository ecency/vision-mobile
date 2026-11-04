package com.ecency.core.app

import com.ecency.core.api.model.Account
import com.ecency.core.api.model.Manabar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The compact vote sheet's choice (direction toggle, presets, one vote button), the same on both apps. */
class VoteSheetTest {
    @Test
    fun opensAtTheRememberedWeightInTheDirectionOfTheReadersVote() {
        val s = VoteSheetState.open(rememberedPercent = 40.0, votedByMe = false, downvotedByMe = false)
        assertEquals(40, s.percent)
        assertFalse(s.down)
        // a post the reader downvoted opens in downvote mode (RN popover)
        assertTrue(VoteSheetState.open(40.0, votedByMe = false, downvotedByMe = true).down)
        assertFalse(VoteSheetState.open(40.0, votedByMe = true, downvotedByMe = true).down)
        // the slider's range is 1..100
        assertEquals(1, VoteSheetState.open(0.2, votedByMe = false, downvotedByMe = false).percent)
        assertEquals(100, VoteSheetState.open(250.0, votedByMe = false, downvotedByMe = false).percent)
    }

    @Test
    fun switchingDirectionKeepsTheWeight() {
        val down = VoteSheetState.open(12.0, votedByMe = false, downvotedByMe = false).withDirection(down = true)
        assertEquals(12, down.percent)
        assertTrue(down.down)
        assertEquals(-1200, down.weight)
        val up = down.withDirection(down = false)
        assertEquals(12, up.percent)
        assertEquals(1200, up.weight)
    }

    @Test
    fun presetsSetExactWeightsAndShowAsSelected() {
        assertEquals(listOf(25, 50, 75, 100), VoteSheetState.PRESETS)
        val s = VoteSheetState.open(12.0, votedByMe = false, downvotedByMe = false)
        assertNull(s.selectedPreset)
        for (p in VoteSheetState.PRESETS) {
            val picked = s.withPreset(p)
            assertEquals(p, picked.percent)
            assertEquals(p, picked.selectedPreset)
            assertEquals(p * 100, picked.weight)
        }
        // a preset in downvote mode stays a downvote
        assertEquals(-7500, s.withDirection(down = true).withPreset(75).weight)
        // the slider rounds to whole percent and keeps 1..100
        assertEquals(50, s.withPercent(49.6).selectedPreset)
        assertEquals(1, s.withPercent(0.3).percent)
        assertEquals(100, s.withPercent(100.4).percent)
    }

    @Test
    fun theOneVoteButtonFollowsDirectionAndWeight() {
        val s = VoteSheetState.open(12.0, votedByMe = false, downvotedByMe = false)
        assertEquals("Upvote 12%", s.buttonLabel)
        assertEquals("12%", s.percentText)
        val down = s.withDirection(down = true)
        assertEquals("Downvote 12%", down.buttonLabel)
        // the weight reads negative in downvote mode, as before
        assertEquals("-12%", down.percentText)
        assertEquals("Downvote 50%", down.withPreset(50).buttonLabel)
    }

    @Test
    fun theValueReadsNegativeForADownvoteAndVotingPowerShowsOnlyWhenKnown() {
        val props = RewardProps(6e17, 800000.0, 0.25, 1.0, 10.0, 28)
        val account = Account(name = "tester", vestingShares = "1000.000000 VESTS", votingManabar = Manabar(930_000_000.0, TestApp.NOW / 1000))
        val preview = VotePreview(account, props, TestApp.NOW)
        val s = VoteSheetState.open(100.0, votedByMe = false, downvotedByMe = false)
        val up = s.valueText(preview)
        assertTrue(up.startsWith("$"), up)
        assertEquals("-$up", s.withDirection(down = true).valueText(preview))
        assertEquals("Voting power 93%", preview.votingPowerText)
        assertNull(VotePreview(account.copy(votingManabar = null), props, TestApp.NOW).votingPowerText)
    }
}

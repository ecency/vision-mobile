package com.ecency.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ecency.app.render.PostActions
import com.ecency.app.render.PostBody
import com.ecency.core.render.blocks.Block
import com.ecency.core.render.blocks.Inline
import com.ecency.core.render.blocks.LinkTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Layout-dependent checks with real font metrics (native graphics): chip boxes and link hit areas. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChipMetricsTest {
    @get:Rule
    val compose = createComposeRule()

    private fun measure(scale: Float): Pair<Dp, Dp> {
        val blocks = listOf(Block.Paragraph(listOf(Inline.Text("Hi "), Inline.Mention("alice"))))
        compose.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, scale)) {
                PostBody(blocks, 360.dp, PostActions(avatarUrl = { null }))
            }
        }
        val chip = compose.onNodeWithTag("chip:@alice", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val label = compose.onNodeWithText("@alice", useUnmergedTree = true).getUnclippedBoundsInRoot()
        return (chip.bottom - chip.top) to (label.bottom - label.top)
    }

    @Test
    fun chipIsEighteenHighAtNormalScale() {
        val (chip, label) = measure(1f)
        assertEquals(18f, chip.value, 0.5f)
        assertTrue("label $label fits chip $chip", label <= chip)
    }

    @Test
    fun chipGrowsSoTheLabelIsNotClippedAtLargeFontScale() {
        val (chip, label) = measure(2f)
        assertTrue("label $label fits chip $chip", label.value <= chip.value + 0.5f)
        assertTrue("chip grew past 18: $chip", chip.value > 18f)
    }

    @Test
    fun tappingLinkTextFiresOnLinkDespiteTheLongPressDetector() {
        val taps = mutableListOf<LinkTarget>()
        val link = LinkTarget.External("https://example.com/page")
        val blocks = listOf(Block.Paragraph(listOf(Inline.Text("Read "), Inline.Link(link, listOf(Inline.Text("this page"))), Inline.Text(" now"))))
        compose.setContent { PostBody(blocks, 360.dp, PostActions(onLink = { taps += it })) }
        // A real touch at the link's laid-out position (native text metrics give it real bounds).
        compose.onAllNodes(hasClickAction(), useUnmergedTree = true)[0].performClick()
        assertEquals(listOf(link), taps)
    }
}

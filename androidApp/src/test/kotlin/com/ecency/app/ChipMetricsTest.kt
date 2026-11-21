package com.ecency.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
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

    /**
     * User report: `@annephilbrick` showed as `@annephilbr` and `@idea-make-rich` as `@idea-make`. Android 14 scales
     * large text sizes less than small ones, so a chip width passed as width / fontScale in sp came out narrower than
     * the label measured in dp. With the system's own font scale (no linear density swapped in), every label fits its
     * chip on one line.
     */
    @Test
    @Config(sdk = [35], fontScale = 1.3f, qualifiers = "xxhdpi")
    fun aLongNameFitsItsChipAtTheSystemsLargerFontScale() = assertLongNamesFit()

    @Test
    @Config(sdk = [35], fontScale = 2f, qualifiers = "xxhdpi")
    fun aLongNameFitsItsChipAtTheLargestFontScale() = assertLongNamesFit()

    private fun assertLongNamesFit() {
        val names = listOf("annephilbrick", "idea-make-rich")
        val blocks = listOf(Block.Paragraph(listOf(Inline.Text("Cover photo by "), Inline.Mention(names[0]), Inline.Text(" by "), Inline.Mention(names[1]))))
        compose.setContent { PostBody(blocks, 360.dp, PostActions(avatarUrl = { "https://images.example.com/u/$it/avatar" })) }
        for (name in names) {
            val label = compose.onNodeWithText("@$name", useUnmergedTree = true)
            val layout = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            label.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layout) }
            val result = layout.single()
            assertEquals("@$name wraps inside its chip", 1, result.lineCount)
            val needed = result.multiParagraph.intrinsics.maxIntrinsicWidth
            assertTrue("@$name is cut: ${result.size.width}px of the ${needed}px it needs", result.size.width >= needed - 1f)
            val chip = compose.onNodeWithTag("chip:@$name", useUnmergedTree = true).getUnclippedBoundsInRoot()
            val text = label.getUnclippedBoundsInRoot()
            assertTrue("@$name label ${text.right} ends past its chip ${chip.right}", text.right <= chip.right + 0.5.dp)
        }
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

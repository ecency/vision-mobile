package com.ecency.app

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.text.TextLayoutResult
import org.junit.Assert.assertTrue

/*
 * TalkBack and font scale scans over every window's semantics tree (README "Accessibility", Android), shared by
 * A11yFlowsTest and the screen flow tests that render real rows.
 */

/** Every node; in the merged tree a node hidden from TalkBack is left out, its children are still walked. */
fun ComposeTestRule.a11yNodes(unmerged: Boolean): List<SemanticsNode> {
    val out = mutableListOf<SemanticsNode>()
    fun walk(n: SemanticsNode) {
        if (unmerged || !n.config.contains(SemanticsProperties.HideFromAccessibility)) out += n
        n.children.forEach(::walk)
    }
    onAllNodes(isRoot(), useUnmergedTree = unmerged).fetchSemanticsNodes().forEach(::walk)
    return out
}

fun SemanticsNode.a11yLabel(): String =
    (config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() + config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text })
        .joinToString(" ").trim()

private fun SemanticsNode.where(): String = config.getOrNull(SemanticsProperties.TestTag) ?: a11yLabel().ifEmpty {
    "node $id (${config.getOrNull(SemanticsProperties.Role)}) in ${generateSequence(parent) { it.parent }.firstNotNullOfOrNull { it.config.getOrNull(SemanticsProperties.TestTag) }}"
}

/**
 * A link span inside a text (`LinkAnnotation`): Compose gives it its own node with only a click; TalkBack reads it
 * from the text's links, so it carries no label of its own.
 */
private fun SemanticsNode.isTextLink(): Boolean = config.contains(SemanticsProperties.LinkTestMarker)

/** Actions TalkBack reaches without a label or a role. */
fun ComposeTestRule.unlabelledActions(): List<String> = a11yNodes(unmerged = false)
    .filter { it.config.contains(SemanticsActions.OnClick) && !it.config.contains(SemanticsProperties.EditableText) && !it.isTextLink() }
    .mapNotNull { n ->
        when {
            n.a11yLabel().isEmpty() -> "no label: ${n.where()}"
            n.config.getOrNull(SemanticsProperties.Role) == null -> "no role: ${n.where()}"
            else -> null
        }
    }

/** Texts whose layout is cut (overflow that is not a deliberate ellipsis). */
fun ComposeTestRule.clippedTexts(): List<String> = a11yNodes(unmerged = true).mapNotNull { n ->
    val action = n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action ?: return@mapNotNull null
    val results = mutableListOf<TextLayoutResult>()
    if (action(results) != true) return@mapNotNull null
    val r = results.firstOrNull() ?: return@mapNotNull null
    val ellipsized = r.lineCount > 0 && r.isLineEllipsized(r.lineCount - 1)
    // hasVisualOverflow also counts a wrap-content text narrower than its paragraph, so measure the cut itself:
    // a line wider than the text's box, or lines taller than it, or lines dropped by maxLines without an ellipsis
    val widest = (0 until r.lineCount).maxOfOrNull { r.getLineRight(it) - r.getLineLeft(it) } ?: 0f
    val cutWide = widest > r.size.width + 1f
    val cutTall = r.multiParagraph.height > r.size.height + 1f || r.multiParagraph.didExceedMaxLines
    if ((cutWide || cutTall) && !ellipsized) "\"${r.layoutInput.text.text.take(40)}\" (${n.where()})" else null
}

fun ComposeTestRule.assertAccessible(what: String) {
    waitForIdle()
    val unlabelled = unlabelledActions()
    assertTrue("$what: TalkBack reaches actions without a label or role:\n" + unlabelled.joinToString("\n"), unlabelled.isEmpty())
}

fun ComposeTestRule.assertNothingClipped(what: String) {
    waitForIdle()
    val clipped = clippedTexts()
    assertTrue("$what at font scale 2: cut text:\n" + clipped.joinToString("\n"), clipped.isEmpty())
}

package com.ecency.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import com.ecency.app.ui.EcencyTheme
import com.ecency.app.ui.MediaSourceSheetContent
import com.ecency.app.ui.MediaSourceTiles
import com.ecency.core.app.MediaSource
import com.ecency.core.app.MediaSourceMenus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The image source chooser (README "Media picking"): the shared options as rows or tiles, picks and Cancel. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h740dp")
class MediaSourceChooserTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val events = mutableListOf<String>()

    private fun sheet(fontScale: Float = 1f, dark: Boolean = false) = compose.setContent {
        val d = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) {
            EcencyTheme(dark = dark) {
                MediaSourceSheetContent("Profile picture", MediaSourceMenus.profileImage(camera = true), tagFor = { "src:${it.name}" },
                    onDismiss = { events += "dismiss" }, onPick = { events += "pick:${it.name}" })
            }
        }
    }

    private fun exists(text: String) = compose.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun theRowsShowEachRouteWithWhatItDoesAndAPickClosesFirst() {
        sheet()
        for (option in MediaSourceMenus.profileImage(camera = true)) {
            compose.onNodeWithTag("src:${option.source.name}").assertExists()
            assertTrue(option.title, exists(option.title))
            assertTrue(option.source.subtitle, exists(option.source.subtitle))
        }
        assertTrue(exists("Profile picture"))
        compose.onNodeWithTag("src:FILES").performClick()
        assertEquals(listOf("dismiss", "pick:FILES"), events)
        compose.onNodeWithTag("mediaSourceCancel").performClick()
        assertEquals(listOf("dismiss", "pick:FILES", "dismiss"), events)
    }

    @Test
    fun atTheLargestFontEveryRowIsATouchTargetAndReadsAsOne() {
        sheet(fontScale = 2f, dark = true)
        val density = compose.activity.resources.displayMetrics.density
        for (source in listOf(MediaSource.LIBRARY, MediaSource.CAMERA, MediaSource.FILES, MediaSource.LINK)) {
            val node = compose.onNodeWithTag("src:${source.name}").fetchSemanticsNode()
            assertTrue("$source tall enough", node.boundsInRoot.height >= 48 * density)
            assertTrue("$source fits the width", node.boundsInRoot.right <= 360 * density + 1)
            assertTrue("$source clickable", node.config.getOrNull(SemanticsActions.OnClick) != null)
        }
    }

    @Test
    fun theEditorTilesReadTheTitleAndWhatEachDoes() {
        compose.setContent {
            EcencyTheme(dark = false) {
                MediaSourceTiles(MediaSourceMenus.editorUploads(camera = true, paste = true), tagFor = { "tile:${it.name}" }) { events += it.name }
            }
        }
        for (option in MediaSourceMenus.editorUploads(camera = true, paste = true)) {
            val node = compose.onNodeWithTag("tile:${option.source.name}").fetchSemanticsNode()
            assertEquals(option.title, node.config.getOrNull(SemanticsActions.OnClick)?.label)
            assertEquals(listOf("${option.title}, ${option.source.subtitle}"), node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription))
        }
        compose.onNodeWithTag("tile:PASTE").performClick()
        assertEquals(listOf("PASTE"), events)
        assertTrue(compose.onAllNodes(hasTestTag("tile:CAMERA")).fetchSemanticsNodes().isNotEmpty())
    }
}

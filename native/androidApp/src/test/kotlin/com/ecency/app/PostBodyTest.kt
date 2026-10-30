package com.ecency.app

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.unit.dp
import com.ecency.app.render.PostActions
import com.ecency.app.render.PostBody
import com.ecency.core.render.blocks.Block
import com.ecency.core.render.blocks.Inline
import com.ecency.core.render.blocks.LinkTarget
import com.ecency.core.render.blocks.BlockBuilder
import com.ecency.core.render.blocks.SampleHtml
import android.graphics.drawable.Animatable
import android.graphics.drawable.ColorDrawable
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.style.TextAlign
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.intercept.Interceptor
import coil3.request.ImageResult
import coil3.request.SuccessResult
import com.ecency.app.render.RevealedImages
import com.ecency.app.render.paragraphAlign
import com.ecency.core.render.blocks.BlockStyle
import java.util.Collections
import androidx.compose.ui.test.performSemanticsAction
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PostBodyTest {
    @get:Rule
    val compose = createComposeRule()

    private val taps = mutableListOf<LinkTarget>()
    private val actions = PostActions(onLink = { taps += it })

    @Test
    fun mentionAndTagChipsRouteToProfileAndTag() {
        val blocks = listOf(Block.Paragraph(listOf(Inline.Text("Hi "), Inline.Mention("ecency"), Inline.Text(" "), Inline.Tag("hive"))))
        compose.setContent { PostBody(blocks, 360.dp, actions) }
        compose.onNodeWithTag("chip:@ecency").performClick()
        compose.onNodeWithTag("chip:#hive").performClick()
        assertEquals(listOf(LinkTarget.Author("ecency"), LinkTarget.Tag("hive", null)), taps)
    }

    @Test
    fun videoThumbnailOpensTheYoutubePlayer() {
        val blocks = listOf(Block.Video("https://www.youtube.com/embed/abc", null, youtubeId = "abc", startSeconds = 42))
        compose.setContent { PostBody(blocks, 360.dp, actions) }
        compose.onNodeWithTag("video").performClick()
        assertEquals(listOf(LinkTarget.Youtube("abc", 42)), taps)
    }

    @Test
    fun imageInsideALinkFollowsTheLinkInsteadOfOpeningTheViewer() {
        val viewed = mutableListOf<String>()
        val link = LinkTarget.External("https://example.com")
        val blocks = listOf(Block.Image("https://example.com/a.png", null, link))
        compose.setContent { PostBody(blocks, 360.dp, PostActions(onLink = { taps += it }, onImage = { url, _ -> viewed += url })) }
        compose.onNodeWithTag("image").performClick()
        assertEquals(listOf(link), taps)
        assertEquals(emptyList<String>(), viewed)
    }

    @Test
    fun samplePostRendersEveryBlockKindFromHtml() {
        compose.setContent { HomeScreen(actions = actions) }
        compose.scrollListTo("post", hasTestTag("video"))
        compose.onNodeWithTag("video").performClick()
        compose.scrollListTo("post", androidx.compose.ui.test.hasText("some-post"))
        compose.onNodeWithText("some-post").performClick()
        assertEquals(listOf(LinkTarget.Youtube("dQw4w9WgXcQ", 0), LinkTarget.Post("ecency", "some-post")), taps)
        assertEquals(true, BlockBuilder.build(SampleHtml.POST).size > 10)
    }

    /** Every URL Coil is asked for; answers each with an animated drawable so no network is used. */
    private val requested = Collections.synchronizedList(mutableListOf<String>())

    private class FakeAnimated : ColorDrawable(0xFF336699.toInt()), Animatable {
        override fun start() {}
        override fun stop() {}
        override fun isRunning() = true
    }

    @Before
    fun fakeImageLoader() {
        RevealedImages.clear()
        val recorder = object : Interceptor {
            override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
                requested += chain.request.data.toString()
                return SuccessResult(image = FakeAnimated().asImage(), request = chain.request)
            }
        }
        SingletonImageLoader.setUnsafe { ctx -> ImageLoader.Builder(ctx).components { add(recorder) }.build() }
        ApplicationProvider.getApplicationContext<android.content.Context>()
    }

    @After
    fun resetImageLoader() {
        SingletonImageLoader.reset()
        RevealedImages.clear()
    }

    private fun waitForRequest(url: String) = runCatching { compose.await("url in requested", boundMillis = 5_000) { url in requested } }
        .getOrElse { throw AssertionError("$url not requested, saw $requested", it) }

    @Test
    fun mentionChipLoadsTheSmallAvatarAndTagChipHasNone() {
        val blocks = listOf(Block.Paragraph(listOf(Inline.Mention("alice"), Inline.Text(" "), Inline.Tag("hive"))))
        compose.setContent { PostBody(blocks, 360.dp, actions) }
        compose.onNodeWithTag("chip-avatar:https://images.ecency.com/u/alice/avatar/128", useUnmergedTree = true).assertExists()
        waitForRequest("https://images.ecency.com/u/alice/avatar/128")
        val avatars = compose.onAllNodes(hasTestTagStartingWith("chip-avatar:"), useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals(1, avatars.size)
    }

    @Test
    fun justifyHoldsUnlessChipsWouldOverlap() {
        val justify = BlockStyle(justify = true)
        assertEquals(TextAlign.Justify, paragraphAlign(justify, hasChips = false))
        assertEquals(TextAlign.Start, paragraphAlign(justify, hasChips = true))
        assertEquals(TextAlign.Center, paragraphAlign(BlockStyle(align = BlockStyle.Align.Center), hasChips = true))
    }

    @Test
    fun paragraphLongPressHandsOverPlainText() {
        val pressed = mutableListOf<String>()
        val blocks = listOf(
            Block.Paragraph(listOf(Inline.Text("Hello "), Inline.Mention("alice"), Inline.Text(" and "), Inline.Tag("hive"))),
        )
        compose.setContent { PostBody(blocks, 360.dp, PostActions(onParagraphLongPress = { pressed += it })) }
        compose.onNodeWithText("Hello", substring = true).performTouchInput { longClick() }
        assertEquals(listOf("Hello @alice and #hive"), pressed)
    }

    @Test
    fun imageTapOpensTheGalleryWithEveryImageInDocumentOrder() {
        val opened = mutableListOf<Pair<String, List<String>>>()
        fun img(n: Int) = Block.Image("https://example.com/$n.png", null)
        val blocks = listOf(
            img(1),
            Block.Quote(listOf(img(2))),
            Block.ListBlock(false, 1, listOf(listOf(img(3)))),
        )
        compose.setContent { PostBody(blocks, 360.dp, PostActions(onImage = { url, all -> opened += url to all })) }
        compose.onAllNodesWithTag("image")[1].performClick()
        val all = (1..3).map { "https://example.com/$it.png" }
        assertEquals(listOf(all[1] to all), opened)
    }

    @Test
    fun animatedImageShowsTheGifBadge() {
        val blocks = listOf(Block.Image("https://example.com/a.gif", null))
        compose.setContent { PostBody(blocks, 360.dp, actions) }
        runCatching { compose.await("compose.onAllNodesWithTag(\"gif\", useUnmergedTree = true).fetchSemanticsNodes().isNotEmp...", boundMillis = 5_000) { compose.onAllNodesWithTag("gif", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() } }
            .onFailure { throw AssertionError("requested=$requested", it) }
    }

    @Test
    fun showImagesOffNeverRequestsBytesUntilOneImageIsTapped() {
        val opened = mutableListOf<String>()
        val blocks = listOf(Block.Image("https://example.com/1.png", null), Block.Image("https://example.com/2.png", null))
        compose.setContent { PostBody(blocks, 360.dp, PostActions(onImage = { url, _ -> opened += url }, showImages = false)) }
        compose.onAllNodesWithTag("image-hidden").assertCountEquals(2)
        compose.onAllNodesWithTag("image").assertCountEquals(0)
        compose.onAllNodesWithText("Tap to load image").assertCountEquals(2)
        compose.waitForIdle()
        Thread.sleep(200)
        compose.waitForIdle()
        assertEquals(emptyList<String>(), requested.toList())

        // The tap reveals that image only, and does not open the gallery.
        compose.onAllNodesWithTag("image-hidden")[1].performClick()
        compose.onAllNodesWithTag("image-hidden").assertCountEquals(1)
        compose.onAllNodesWithTag("image").assertCountEquals(1)
        waitForRequest("https://example.com/2.png")
        assertEquals(listOf("https://example.com/2.png"), requested.toList())
        assertEquals(emptyList<String>(), opened)
    }

    @Test
    fun tablesScrollSidewaysOnlyAboveThreeHeaderColumns() {
        fun table(columns: Int) = Block.Table(
            listOf(Block.Table.Row(List(columns) { Block.Table.Cell(true, listOf(Block.Paragraph(listOf(Inline.Text("h$it"))))) })),
        )
        compose.setContent { PostBody(listOf(table(3), table(5)), 360.dp, actions) }
        compose.onAllNodesWithTag("table-scroll").assertCountEquals(1)
        compose.onNodeWithTag("table-scroll").assertExists()
        assertTrue(compose.onAllNodesWithText("h4").fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun showImagesOffDoesNotFetchVideoThumbnailsButKeepsThePlayButton() {
        val blocks = listOf(Block.Video("https://www.youtube.com/embed/abc", "https://img.youtube.com/vi/abc/0.jpg", youtubeId = "abc"))
        compose.setContent { PostBody(blocks, 360.dp, PostActions(onLink = { taps += it }, showImages = false)) }
        compose.waitForIdle()
        Thread.sleep(200)
        compose.waitForIdle()
        assertEquals(emptyList<String>(), requested.toList())
        compose.onNodeWithTag("video").performClick()
        assertEquals(listOf(LinkTarget.Youtube("abc", 0)), taps)
    }

    @Test
    fun showImagesOnFetchesVideoThumbnails() {
        val blocks = listOf(Block.Video("https://www.youtube.com/embed/abc", "https://img.youtube.com/vi/abc/0.jpg", youtubeId = "abc"))
        compose.setContent { PostBody(blocks, 360.dp, actions) }
        waitForRequest("https://img.youtube.com/vi/abc/0.jpg")
    }

    @Test
    fun hiddenImageGridTilesAreNotFetchedUntilTapped() {
        val grid = Block.ImageGrid(listOf(Block.Image("https://example.com/g1.png", null), Block.Image("https://example.com/g2.png", null)))
        compose.setContent { PostBody(listOf(grid), 360.dp, PostActions(showImages = false)) }
        compose.onAllNodesWithTag("image-hidden").assertCountEquals(2)
        compose.waitForIdle()
        Thread.sleep(200)
        compose.waitForIdle()
        assertEquals(emptyList<String>(), requested.toList())
        compose.onAllNodesWithTag("image-hidden")[0].performClick()
        compose.onAllNodesWithTag("image-hidden").assertCountEquals(1)
        waitForRequest("https://example.com/g1.png")
        assertEquals(listOf("https://example.com/g1.png"), requested.toList())
    }

    @Test
    fun galleryLeavesOutHiddenAndLinkedImages() {
        val opened = mutableListOf<List<String>>()
        val blocks = listOf(
            Block.Image("https://example.com/1.png", null),
            Block.Image("https://example.com/2.png", null),
            Block.Image("https://example.com/linked.png", null, LinkTarget.External("https://example.com")),
        )
        compose.setContent { PostBody(blocks, 360.dp, PostActions(onImage = { _, all -> opened += all }, showImages = false)) }
        compose.onAllNodesWithTag("image-hidden")[1].performClick()
        compose.onAllNodesWithTag("image")[0].performClick()
        assertEquals(listOf(listOf("https://example.com/2.png")), opened)
    }

    @Test
    fun linkIsAnAccessibleClickableNode() {
        val link = LinkTarget.External("https://example.com/page")
        val blocks = listOf(Block.Paragraph(listOf(Inline.Text("Read "), Inline.Link(link, listOf(Inline.Text("this page"))), Inline.Text(" now"))))
        compose.setContent { PostBody(blocks, 360.dp, actions) }
        // Each LinkAnnotation is its own clickable semantics node: what TalkBack lists and activates.
        val links = compose.onAllNodes(androidx.compose.ui.test.hasClickAction(), useUnmergedTree = true)
        links.assertCountEquals(1)
        links[0].performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        assertEquals(listOf(link), taps)
    }

    @Test
    fun longPressOnAChipCopiesTheParagraphAndDoesNotNavigate() {
        val pressed = mutableListOf<String>()
        val blocks = listOf(Block.Paragraph(listOf(Inline.Text("Hello "), Inline.Mention("alice"), Inline.Text(" there"))))
        compose.setContent { PostBody(blocks, 360.dp, PostActions(onLink = { taps += it }, onParagraphLongPress = { pressed += it })) }
        compose.onNodeWithTag("chip:@alice", useUnmergedTree = true).performTouchInput { longClick() }
        compose.waitForIdle()
        assertEquals(emptyList<LinkTarget>(), taps)
        assertEquals(listOf("Hello @alice there"), pressed)
    }

    @Test
    fun invalidAccountNamesGetNoAvatarRequest() {
        val blocks = listOf(Block.Paragraph(listOf(Inline.Mention("../evil"), Inline.Mention("alice"))))
        compose.setContent { PostBody(blocks, 360.dp, actions) }
        waitForRequest("https://images.ecency.com/u/alice/avatar/128")
        compose.onNodeWithTag("chip:@../evil", useUnmergedTree = true).assertExists()
        assertEquals(listOf("https://images.ecency.com/u/alice/avatar/128"), requested.toList())
    }

    private fun hasTestTagStartingWith(prefix: String) = androidx.compose.ui.test.SemanticsMatcher("tag starts with $prefix") {
        it.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { null }?.startsWith(prefix) == true
    }
}

package com.ecency.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.ecency.app.render.LocalPostLinkCards
import com.ecency.app.render.PostActions
import com.ecency.app.render.PostBody
import com.ecency.core.render.blocks.Block
import com.ecency.core.render.blocks.LinkCard
import com.ecency.core.render.blocks.LinkTarget
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #3705: a post link in a body shows the linked post's title, summary and thumbnail. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PostLinkCardTest {
    @get:Rule
    val compose = createComposeRule()

    private val taps = mutableListOf<LinkTarget>()

    @Test
    fun aCardFromLinksMetaShowsTitleSummaryAndThumbnailAndOpensThePost() {
        val card = LinkCard("A linked post", "What it says first.", "https://i.ecency.com/p/x?format=match&mode=fit&width=600&height=500")
        compose.setContent { PostBody(listOf(Block.PostPreview("spike-author", "spike-permlink", null, card)), 360.dp, PostActions(onLink = { taps += it })) }
        compose.onNodeWithText("A linked post").assertExists()
        compose.onNodeWithText("What it says first.").assertExists()
        compose.onNodeWithText("@spike-author").assertExists()
        compose.onAllNodesWithTag("post-link-thumb", useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithTag("post-link-card").performClick()
        assertEquals(listOf(LinkTarget.Post("spike-author", "spike-permlink")), taps)
    }

    @Test
    fun showImagesOffDrawsNoThumbnail() {
        val card = LinkCard("A linked post", "What it says first.", "https://i.ecency.com/p/x?format=match&mode=fit&width=600&height=500")
        compose.setContent { PostBody(listOf(Block.PostPreview("spike-author", "spike-permlink", null, card)), 360.dp, PostActions(showImages = false)) }
        compose.onNodeWithText("A linked post").assertExists()
        compose.onAllNodesWithTag("post-link-thumb", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun aLinksMetaCardShowsItsTextUntilTheReadReplacesIt() {
        val node = TestNode().apply { metadataImage = "https://example.com/{permlink}.jpg" }
        val env = node.env()
        node.holdAll = kotlinx.coroutines.CompletableDeferred()
        compose.setContent {
            CompositionLocalProvider(LocalPostLinkCards provides env.postLinkCards) {
                PostBody(listOf(Block.PostPreview("alice", "trending-one", null, LinkCard("Title from links_meta", "Its own words", null))), 360.dp, PostActions())
            }
        }
        // the links_meta text at once (the read is held); whatever the linking post claims, the read post replaces it
        compose.onNodeWithText("Title from links_meta").assertExists()
        node.holdAll!!.complete(Unit)
        compose.await("the read post's title") { compose.onAllNodes(hasText("Title trending-one")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Summary of trending-one").assertExists()
        compose.onAllNodes(hasText("Title from links_meta")).assertCountEquals(0)
        compose.onAllNodesWithTag("post-link-thumb", useUnmergedTree = true).assertCountEquals(1)
    }

    /** Review of #3707: a card the read cannot show (here an author the reader muted) is the plain card, not the links_meta text. */
    @Test
    fun aCardWithoutAUsableReadIsThePlainCardNotTheLinksMetaText() {
        val node = TestNode()
        val env = node.env()
        env.setAuthorMuted("alice", true)
        compose.setContent {
            CompositionLocalProvider(LocalPostLinkCards provides env.postLinkCards) {
                PostBody(listOf(Block.PostPreview("alice", "trending-one", null, LinkCard("Claimed by the linking post", "Its own words", null))), 360.dp, PostActions())
            }
        }
        compose.onNodeWithText("trending-one").assertExists()
        compose.onAllNodes(hasText("Claimed by the linking post")).assertCountEquals(0)
    }

    @Test
    fun showImagesOffDrawsNoThumbnailForARead() {
        val node = TestNode().apply { metadataImage = "https://example.com/{permlink}.jpg" }
        val env = node.env()
        compose.setContent {
            CompositionLocalProvider(LocalPostLinkCards provides env.postLinkCards) {
                PostBody(listOf(Block.PostPreview("alice", "trending-one", null)), 360.dp, PostActions(showImages = false))
            }
        }
        compose.await("the linked post's title") { compose.onAllNodes(hasText("Title trending-one")).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithTag("post-link-thumb", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun aCardWithoutLinksMetaShowsTheLinkedPostOnceItIsRead() {
        val node = TestNode().apply { metadataImage = "https://example.com/{permlink}.jpg" }
        val env = node.env()
        compose.setContent {
            CompositionLocalProvider(LocalPostLinkCards provides env.postLinkCards) {
                PostBody(listOf(Block.PostPreview("alice", "trending-one", null)), 360.dp, PostActions())
            }
        }
        compose.await("the linked post's title") { compose.onAllNodes(hasText("Title trending-one")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Summary of trending-one").assertExists()
        compose.onAllNodesWithTag("post-link-thumb", useUnmergedTree = true).assertCountEquals(1)
        // one read (the post cache asks bridge.get_post_v2 first; this node falls back to bridge.get_post)
        assertEquals(1, node.sent.count { "\"bridge.get_post\"" in it })
    }
}

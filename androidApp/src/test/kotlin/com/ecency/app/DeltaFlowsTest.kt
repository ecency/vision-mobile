package com.ecency.app

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import com.ecency.app.ui.AppHost
import com.ecency.app.ui.EcencyApp
import com.ecency.app.ui.EcencyTheme
import com.ecency.app.ui.playerNavigationAllowed
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Delta review fixes on Android: the composer opens again after a reply, players follow www redirects. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeltaFlowsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val node = TestNode()
    private val env by lazy { node.env() }

    private val host = object : AppHost {
        override fun openExternal(url: String) {}
        override fun toast(text: String) {}
        override fun copy(text: String) {}
        override fun voteWeightPercent() = 100f
        override fun setVoteWeightPercent(percent: Float) {}
    }

    @Before
    fun setUp() {
        SingletonImageLoader.setUnsafe { ctx ->
            ImageLoader.Builder(ctx).components {
                add(object : Interceptor {
                    override suspend fun intercept(chain: Interceptor.Chain): ImageResult =
                        ErrorResult(null, chain.request, IllegalStateException("no network in tests"))
                })
            }.build()
        }
    }

    @After
    fun tearDown() = SingletonImageLoader.reset()

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(tag: String) = compose.await("exists(tag)") { exists(tag) }

    private fun settle() {
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
    }

    private fun openReply() {
        // the bar's comments icon scrolls to the comments, whose header holds the Reply to the post
        compose.onAllNodes(hasTestTag("postComments") and hasAnyAncestor(hasTestTag("postActions")))[0].performClick()
        waitFor("commentsReply")
        compose.onNodeWithTag("commentsReply").performClick()
        waitFor("replySheet")
        settle()
    }

    @Test
    fun theComposerOpensAgainEmptyAfterAReplyWasSent() {
        node.signIn()
        compose.setContent { EcencyTheme(dark = false) { EcencyApp(env, host) } }
        waitFor("open:alice/trending-one")
        compose.onNodeWithTag("open:alice/trending-one").performClick()
        waitFor("postActions")
        openReply()
        compose.onNodeWithTag("replyText").performTextInput("Nice post")
        compose.onNodeWithTag("replySend").performSemanticsAction(SemanticsActions.OnClick)
        compose.await("!exists(\"replySheet\")") { !exists("replySheet") }
        assertEquals(1, node.broadcasts().size)

        // the same parent again: the sheet stays open with an empty draft
        openReply()
        settle()
        assertTrue("the composer must stay open", exists("replySheet"))
        compose.onNodeWithTag("replyText").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
    }

    @Test
    fun playerFramesFollowWwwRedirectsOnly() {
        assertTrue(playerNavigationAllowed("https://www.youtube.com/embed/x", "https://youtube.com/embed/x"))
        assertTrue(playerNavigationAllowed("https://rumble.com/embed/x", "https://www.rumble.com/embed/x"))
        assertFalse(playerNavigationAllowed("https://m.youtube.com/watch?v=x", "https://www.youtube.com/embed/x"))
        assertFalse(playerNavigationAllowed("https://evilyoutube.com/x", "https://youtube.com/embed/x"))
        assertFalse(playerNavigationAllowed("http://youtube.com/embed/x", "https://www.youtube.com/embed/x"))
    }
}

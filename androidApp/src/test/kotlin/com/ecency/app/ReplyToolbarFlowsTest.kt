package com.ecency.app

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertTextEquals
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import com.ecency.app.ui.AppHost
import com.ecency.app.ui.EcencyApp
import com.ecency.app.ui.EcencyTheme
import com.ecency.core.app.WaveImageAttacher
import com.ecency.core.app.WaveImageSource
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

/**
 * RN quick reply's toolbar in the reply sheet: image (library, camera, files), dictation, AI assist and expand, the
 * wave composer's buttons without its poll and video. The image goes through the app's upload seam (a fake here).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReplyToolbarFlowsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val node = TestNode()
    private val env by lazy { node.env() }

    private val host = object : AppHost {
        override fun openExternal(url: String) {}
        override fun toast(text: String) {}
        override fun copy(text: String) {}
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
    private fun waitFor(tag: String) = compose.await("a node tagged $tag") { exists(tag) }

    private fun openReplySheet() {
        node.signIn()
        compose.setContent { EcencyTheme(dark = false) { EcencyApp(env, host) } }
        waitFor("open:alice/trending-one")
        compose.onNodeWithTag("open:alice/trending-one").performClick()
        waitFor("postActions")
        compose.onAllNodes(hasTestTag("postComments") and hasAnyAncestor(hasTestTag("postActions")))[0].performClick()
        waitFor("commentsReply")
        compose.onNodeWithTag("commentsReply").performClick()
        waitFor("replySheet")
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
    }

    @Test
    fun theReplySheetShowsRnsQuickReplyToolbar() {
        openReplySheet()
        for (tag in listOf("replyAttach", "replyDictate", "replyAiAssist", "replyExpand")) {
            assertTrue("the reply sheet has $tag", exists(tag))
            assertTrue("$tag sits in the reply sheet", compose.onAllNodes(hasTestTag(tag) and hasAnyAncestor(hasTestTag("replySheet"))).fetchSemanticsNodes().isNotEmpty())
        }
        // one image button: camera, files and the gallery are tiles of its sheet, as in the editor
        for (tag in listOf("replyCamera", "replyFiles", "replyUploads")) assertFalse("no $tag button", exists(tag))
        // RN: poll and video are the wave mode's only
        assertFalse(exists("replyPoll"))
        assertFalse(exists("replyVideoButton"))
    }

    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    /** Tester report: Enter on the keyboard starts a new line in a reply (the key, not pasted text). */
    @Test
    fun enterStartsANewLineInAReply() {
        openReplySheet()
        val text = compose.onNodeWithTag("replyText")
        text.performTextInput("first line")
        text.performKeyInput { pressKey(Key.Enter) }
        compose.waitForIdle()
        text.performTextInput("second line")
        compose.waitForIdle()
        text.assertTextEquals("first line\nsecond line", includeEditableText = true)
    }

    @Test
    fun anImagePickedInTheReplySheetGoesOutWithTheReply() {
        val asked = mutableListOf<WaveImageSource>()
        env.waves.attacher = object : WaveImageAttacher {
            override suspend fun attach(username: String, source: WaveImageSource): String? {
                asked += source
                return "https://images.ecency.com/DQmReply/photo.jpg"
            }
        }
        openReplySheet()
        compose.onNodeWithTag("replyText").performTextInput("Nice post")
        compose.onNodeWithTag("replyAttach").performSemanticsAction(SemanticsActions.OnClick)
        waitFor("uploadsSheet")
        // the editor's sheet: where an image comes from, then the account's uploads
        for (tag in listOf("openGallery", "openFiles", "openCamera")) {
            assertTrue("the sheet has $tag", compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        }
        assertFalse("no add-to-gallery here: a pick is the reply's image", exists("addToGallery"))
        compose.onAllNodes(hasTestTag("openGallery"), useUnmergedTree = true)[0].performSemanticsAction(SemanticsActions.OnClick)
        compose.await("the sheet closes") { !exists("uploadsSheet") }
        waitFor("replyImage")
        assertEquals(listOf(WaveImageSource.GALLERY), asked)
        compose.onNodeWithTag("replySend").performSemanticsAction(SemanticsActions.OnClick)
        compose.await("the reply reaches the node") { node.broadcasts().isNotEmpty() }
        val sent = node.broadcasts().single()
        assertTrue(sent, "Nice post\\n\\n ![](https://images.ecency.com/DQmReply/photo.jpg)" in sent)
    }

    @Test
    fun anImageFromTheUploadsGalleryIsTheRepliesImage() {
        // RN quick reply's UploadsGalleryModal (allowMultiple={false}): the account's uploaded images, one tap is the reply's image
        val gallery = "https://images.ecency.com/DQmGallery/funny.gif"
        node.auth.rest = { r ->
            if ("/private-api/images?" in r.url) com.ecency.core.api.rpc.HttpResult(200, """{"data":[{"_id":"i1","url":"$gallery","created":"2026-10-01"}],"pagination":{"total":1,"limit":20,"offset":0,"has_next":false}}""") else null
        }
        openReplySheet()
        compose.onNodeWithTag("replyText").performTextInput("Nice post")
        compose.onNodeWithTag("replyAttach").performSemanticsAction(SemanticsActions.OnClick)
        waitFor("uploadsSheet")
        waitFor("galleryImage:$gallery")
        compose.onNodeWithTag("galleryImage:$gallery").performSemanticsAction(SemanticsActions.OnClick)
        waitFor("replyImage")
        compose.await("the gallery closes") { !exists("uploadsSheet") }
        compose.onNodeWithTag("replySend").performSemanticsAction(SemanticsActions.OnClick)
        compose.await("the reply reaches the node") { node.broadcasts().isNotEmpty() }
        val sent = node.broadcasts().single()
        assertTrue(sent, "Nice post\\n\\n ![]($gallery)" in sent)
    }
}

package com.ecency.app

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import com.ecency.app.ui.NavigationViewModel
import com.ecency.app.ui.Navigator
import com.ecency.core.app.AppScreen
import com.ecency.core.app.NavState
import com.ecency.core.app.PostModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Rotation keeps the back stack and its models; finishing the activity closes them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecreationTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val node = TestNode()

    @Before
    fun setUp() {
        EcencyGraph.install(node.env())
        acceptTerms()
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
    fun tearDown() {
        EcencyGraph.install(null)
        SingletonImageLoader.reset()
    }

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun navigator(scenario: ActivityScenario<MainActivity>): Navigator {
        var nav: Navigator? = null
        scenario.onActivity { nav = ViewModelProvider(it)[NavigationViewModel::class.java].navigator }
        return nav!!
    }

    @Test
    fun recreationKeepsTheStackAndItsModelsAndFinishingClosesThem() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.await("exists(\"open:alice/trending-one\")") { exists("open:alice/trending-one") }
        compose.onAllNodes(hasTestTag("open:alice/trending-one"))[0].performClick()
        compose.await("exists(\"postTitle\")") { exists("postTitle") }
        val nav = navigator(scenario)
        val model = nav.top.peek<PostModel>("post")!!
        val requests = node.sent.size

        scenario.recreate() // rotation, dark mode, a foldable unfolding...
        compose.await("exists(\"postTitle\")") { exists("postTitle") }
        assertSame("the same navigator after recreation", nav, navigator(scenario))
        assertEquals(listOf(AppScreen.Post("alice", "trending-one")), nav.screens)
        assertSame("the same post model, not a new one", model, nav.top.peek<PostModel>("post"))
        assertFalse(model.watch {}.isClosed)
        assertEquals("nothing loaded again", requests, node.sent.size)

        scenario.close() // finished for good: onCleared
        assertTrue("every model closed with the activity", model.watch {}.isClosed)
    }

    @Test
    fun recreationKeepsAnUnlockedAppUnlocked() {
        node.signIn()
        com.ecency.core.secure.PinLock(com.ecency.core.secure.KeyVault(node.store)).setPin("1234")
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.await("exists(\"pinScreen\")") { exists("pinScreen") }
        "1234".forEach { compose.onAllNodes(hasTestTag("pinKey$it"))[0].performClick() }
        compose.await("!exists(\"pinScreen\") && exists(\"open:alice/trending-one\")") { !exists("pinScreen") && exists("open:alice/trending-one") }

        scenario.recreate() // rotation: the environment and its lock model are process-wide
        compose.await("exists(\"open:alice/trending-one\")") { exists("open:alice/trending-one") }
        compose.waitForIdle()
        assertFalse("no PIN pad after a recreation", exists("pinScreen"))
        scenario.close()
    }
}

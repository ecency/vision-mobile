package com.ecency.app

import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import com.ecency.app.ui.AppHost
import com.ecency.app.ui.EcencyApp
import com.ecency.app.ui.EcencyColors
import com.ecency.app.ui.EcencyTheme
import com.ecency.app.ui.Navigator
import com.ecency.core.app.AppScreen
import com.ecency.core.app.AppTab
import com.ecency.core.app.NavState
import com.ecency.core.app.ThemeMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The chats list and a conversation sit on the theme background like every other screen, dark and light. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatsThemedBackgroundTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val node = ChatNode()
    private val env by lazy { node.env() }
    private val host = object : AppHost {
        override fun openExternal(url: String) {}
        override fun toast(text: String) {}
        override fun copy(text: String) {}
    }

    @Before
    fun setUp() {
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
    fun tearDown() = SingletonImageLoader.reset()

    private fun idleMain() = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))

    private fun waitFor(tag: String) {
        compose.await("a node tagged $tag") { idleMain(); compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
    }

    private fun launch(dark: Boolean, navigator: Navigator) {
        node.signIn()
        // the app picks its theme from the settings, over any EcencyTheme around it
        env.settings.setTheme(if (dark) ThemeMode.DARK else ThemeMode.LIGHT)
        compose.setContent { EcencyTheme(dark = dark) { EcencyApp(env, host, navigator) } }
    }

    /** The content view drawn into a bitmap (captureToImage waits for a frame the paused looper never draws), saved
     *  as android-[name].png when `BG_SHOT_DIR` is set; returns the colour at a point in dp. */
    private fun pixels(name: String): (Float, Float) -> Int {
        val view = compose.activity.findViewById<android.view.View>(android.R.id.content)
        val image = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(android.graphics.Canvas(image)) }
        System.getenv("BG_SHOT_DIR")?.takeIf { it.isNotEmpty() }?.let { dir ->
            java.io.File(dir, "android-$name.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
        val density = compose.activity.resources.displayMetrics.density
        return { x, y -> image.getPixel((x * density).toInt().coerceIn(0, image.width - 1), (y * density).toInt().coerceIn(0, image.height - 1)) }
    }

    private fun want(dark: Boolean) = Integer.toHexString((if (dark) EcencyColors.Dark else EcencyColors.Light).background.toArgb())

    private fun bounds(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot()

    private fun chatsList(dark: Boolean) {
        launch(dark, Navigator(NavState(tab = AppTab.CHATS)))
        waitFor("chatRow:ch-ecency")
        val at = pixels("chats-list-${if (dark) "dark" else "light"}")
        val side = "dark=$dark"
        val row = bounds("chatRow:ch-ecency")
        val search = bounds("chatsSearchBar")
        val width = compose.activity.resources.displayMetrics.let { it.widthPixels / it.density }
        assertEquals("beside a channel row ($side)", want(dark), Integer.toHexString(at(4f, (row.top.value + row.bottom.value) / 2)))
        assertEquals("beside the chats search bar ($side)", want(dark), Integer.toHexString(at(4f, (search.top.value + search.bottom.value) / 2)))
        val list = bounds("chatsList")
        assertEquals("below the channel rows ($side)", want(dark), Integer.toHexString(at(width / 2, list.bottom.value - 100)))
    }

    private fun chatThread(dark: Boolean) {
        launch(dark, Navigator(NavState(tab = AppTab.CHATS)).also { it.push(AppScreen.ChatThread("ch-ecency", "O")) })
        waitFor("composer")
        val at = pixels("chat-thread-${if (dark) "dark" else "light"}")
        val side = "dark=$dark"
        val title = bounds("threadTitle")
        val composer = bounds("composer")
        val width = compose.activity.resources.displayMetrics.let { it.widthPixels / it.density }
        assertEquals("beside the chat header ($side)", want(dark), Integer.toHexString(at(width - 4f, (title.top.value + title.bottom.value) / 2)))
        assertEquals("beside the composer ($side)", want(dark), Integer.toHexString(at(2f, (composer.top.value + composer.bottom.value) / 2)))
    }

    @Test
    fun chatsListOnTheDarkTheme() = chatsList(dark = true)

    @Test
    fun chatsListOnTheLightTheme() = chatsList(dark = false)

    @Test
    fun chatThreadOnTheDarkTheme() = chatThread(dark = true)

    @Test
    fun chatThreadOnTheLightTheme() = chatThread(dark = false)
}

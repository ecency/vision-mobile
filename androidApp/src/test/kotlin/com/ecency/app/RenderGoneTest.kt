package com.ecency.app

import android.view.View
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.ecency.app.ui.HiveSignerWebView
import com.ecency.core.auth.HiveSigner
import com.ecency.core.auth.HiveSignerConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/** Issue #3701: a HiveSigner page whose renderer ended never ends the app; a signing page closes, a login page offers Retry. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RenderGoneTest {
    @get:Rule
    val compose = createComposeRule()

    private object Gone : RenderProcessGoneDetail() {
        override fun didCrash() = true
        override fun rendererPriorityAtExit() = 0
    }

    private fun pages(v: View): List<WebView> = when (v) {
        is WebView -> listOf(v)
        is ViewGroup -> (0 until v.childCount).flatMap { pages(v.getChildAt(it)) }
        else -> emptyList()
    }

    private fun page(): WebView {
        var found: List<WebView> = emptyList()
        compose.runOnIdle { found = pages(rootView().rootView) }
        return found.single()
    }

    private var root: View? = null
    private fun rootView(): View = root!!

    private fun crash(web: WebView): Boolean {
        var handled = false
        compose.runOnIdle { handled = Shadows.shadowOf(web).webViewClient.onRenderProcessGone(web, Gone) }
        return handled
    }

    @Test
    fun aSigningPageWhoseRendererEndedClosesItsPrompt() {
        var closed = 0
        compose.setContent {
            root = androidx.compose.ui.platform.LocalView.current
            HiveSignerWebView(HiveSigner.authorizeUrl(HiveSignerConfig.RN), onRedirect = {}, onGone = { closed++ })
        }
        assertTrue("the app goes on", crash(page()))
        compose.waitForIdle()
        assertEquals(1, closed)
        assertTrue("no Retry for a signing page", compose.onAllNodes(hasTestTag("webRetry")).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun aLoginPageWhoseRendererEndedOffersRetry() {
        compose.setContent {
            root = androidx.compose.ui.platform.LocalView.current
            HiveSignerWebView(HiveSigner.authorizeUrl(HiveSignerConfig.RN), onRedirect = {})
        }
        val first = page()
        assertTrue(crash(first))
        compose.onNodeWithTag("webFailed").assertExists()
        compose.onNodeWithTag("webRetry").performClick()
        compose.onNodeWithTag("hiveSignerWeb").assertExists()
        assertTrue("a new view", page() !== first)
    }
}

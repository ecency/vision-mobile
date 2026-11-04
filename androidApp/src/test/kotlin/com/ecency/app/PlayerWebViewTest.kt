package com.ecency.app

import android.content.Context
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import com.ecency.app.ui.loadEmbed
import com.ecency.app.ui.mediaFilePage
import com.ecency.app.ui.setUpPlayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A video file the post links directly (Skatehive IPFS) never loads as the page: a POST from whatever that URL
 * serves would not pass through the navigation rule. The player loads an app-made page with one video element.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlayerWebViewTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun player(url: String) = WebView(context).apply {
        setUpPlayer(url)
        loadEmbed(url)
    }

    @Test
    fun aMediaFileHostLoadsTheAppsOwnVideoPageNotTheUrl() {
        val url = "https://ipfs.skatehive.app/ipfs/QmVideoHash/clip.mp4"
        val view = player(url)
        val shadow = shadowOf(view)
        assertNull("the file is never the page", shadow.lastLoadedUrl)
        val page = shadow.lastLoadDataWithBaseURL
        assertTrue(page.data, "<video src=\"$url\" controls playsinline" in page.data)
        assertFalse("<script" in page.data)
        assertEquals("https://ipfs.skatehive.app/", page.baseUrl)
        assertFalse(view.settings.javaScriptEnabled)
    }

    @Test
    fun aPlayerHostStillLoadsItsEmbed() {
        val url = "https://www.youtube.com/embed/dQw4w9WgXcQ"
        val shadow = shadowOf(player(url))
        assertEquals(url, shadow.lastLoadedUrl)
        assertNull(shadow.lastLoadDataWithBaseURL)
    }

    @Test
    fun theVideoPageEscapesItsSource() {
        val page = mediaFilePage("https://ipfs.skatehive.app/ipfs/Qm\"><script>x</script>")
        assertFalse(page, "<script>" in page)
        assertTrue(page, "&quot;&gt;&lt;script&gt;" in page)
    }
}

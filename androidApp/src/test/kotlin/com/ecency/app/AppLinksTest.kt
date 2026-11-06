package com.ecency.app

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The manifest's ecency.com app links, against RN's (android/app/src/main/AndroidManifest.xml). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppLinksTest {
    private fun opensHere(url: String): Boolean {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(context.packageName)
        return context.packageManager.queryIntentActivities(intent, 0).any { it.activityInfo.name == MainActivity::class.java.name }
    }

    @Test
    fun rnsBareFeedAndWavesPathsOpenTheApp() {
        for (url in listOf("https://ecency.com/trending", "https://ecency.com/hot", "https://ecency.com/created", "https://ecency.com/waves", "https://ecency.com/waves/alice/wave-1")) {
            assertTrue(url, opensHere(url))
        }
        // and the ones the app had before
        assertTrue(opensHere("https://ecency.com/@alice/post"))
        assertTrue(opensHere("https://ecency.com/trending/photography"))
    }

    /**
     * #3655: `/auth` is the website's HiveSigner login callback (`/auth?code=...`). The app taking it left a website
     * login unfinished; the app signs in through its own loopback redirect and `ecency://auth-transfer`.
     */
    @Test
    fun theWebsitesLoginCallbackStaysInTheBrowser() {
        for (url in listOf("https://ecency.com/auth", "https://ecency.com/auth?code=abc", "https://ecency.com/auth?username=alice&code=abc")) {
            assertEquals(url, false, opensHere(url))
        }
        assertTrue(opensHere("https://ecency.com/signup?referral=alice"))
        assertTrue(opensHere("https://ecency.com/purchase"))
    }

    @Test
    fun otherPathsAndHostsStayInTheBrowser() {
        for (url in listOf("https://ecency.com/payout", "https://ecency.com/faq", "https://evil.example/trending")) {
            assertEquals(url, false, opensHere(url))
        }
    }
}

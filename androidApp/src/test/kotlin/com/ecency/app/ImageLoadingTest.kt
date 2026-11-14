package com.ecency.app

import androidx.test.core.app.ApplicationProvider
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections

/**
 * Issue #3686: images from Ecency's proxies are requested with the formats this Android version decodes, so the
 * proxy answers AVIF or WebP instead of the source PNG or JPEG. Each case loads through the app's own image loader
 * (Coil over its OkHttp client); the last interceptor answers instead of the network and keeps the request's Accept.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ImageLoadingTest {
    private val accepts = Collections.synchronizedList(mutableListOf<String?>())

    private fun acceptFor(url: String, sdk: Int): String? {
        accepts.clear()
        val network = OkHttpClient.Builder().addInterceptor { chain ->
            accepts += chain.request().header("Accept")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(404).message("test")
                .body(ByteArray(0).toResponseBody()).build()
        }
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val loader = ImageLoading.loader(context, sdk, network)
        // the disk cache is shared by every loader in the process: a response kept there would answer instead
        runBlocking { loader.execute(ImageRequest.Builder(context).data(url).diskCachePolicy(CachePolicy.DISABLED).build()) }
        assertEquals("one request for $url", 1, accepts.size)
        return accepts.single()
    }

    @Test
    fun android12AndLaterAskForAvif() {
        for (url in listOf("https://i.ecency.com/p/abc?format=match&mode=fit", "https://images.ecency.com/u/someone/avatar/small")) {
            assertEquals(url, "image/avif,image/webp,*/*", acceptFor(url, sdk = 31))
            assertEquals(url, "image/avif,image/webp,*/*", acceptFor(url, sdk = 35))
        }
    }

    /** Android 9 to 11 decode WebP, still and animated (Coil's animated decoder), but not AVIF. */
    @Test
    fun android9To11AskForWebP() {
        assertEquals("image/webp,*/*", acceptFor("https://i.ecency.com/p/abc?format=match&mode=fit", sdk = 28))
        assertEquals("image/webp,*/*", acceptFor("https://i.ecency.com/p/abc?format=match&mode=fit", sdk = 30))
    }

    /** Before Android 9 the app decodes only GIF animations: an animated WebP would stand still, so nothing changes. */
    @Test
    fun beforeAndroid9TheRequestIsAsBefore() {
        assertEquals(null, acceptFor("https://i.ecency.com/p/abc?format=match&mode=fit", sdk = 27))
    }

    @Test
    fun otherHostsAreAsBefore() {
        assertEquals(null, acceptFor("https://images.hive.blog/p/abc", sdk = 35))
        assertEquals(null, acceptFor("https://files.peakd.com/file/a.png", sdk = 35))
    }
}

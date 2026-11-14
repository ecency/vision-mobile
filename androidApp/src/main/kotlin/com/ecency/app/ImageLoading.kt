package com.ecency.app

import android.content.Context
import android.os.Build
import coil3.ImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.ecency.core.render.ImageAccept
import okhttp3.Interceptor
import okhttp3.OkHttpClient

/** The app's one image loader (Coil), set as Coil's singleton in MainActivity. */
internal object ImageLoading {
    /**
     * Coil decodes GIF and animated WebP only with an animated decoder registered: Android 9+ decodes both
     * (ImageDecoder), older versions GIF only. Requests to Ecency's image proxies name the formats this version
     * decodes ([ImageAccept], issue #3686): AVIF on Android 12+, WebP on 9 to 11 (an animated source comes back as
     * animated WebP), nothing before 9, where an animated WebP would stand still. [sdk] and [network] are for tests.
     */
    fun loader(context: Context, sdk: Int = Build.VERSION.SDK_INT, network: OkHttpClient.Builder = OkHttpClient.Builder()): ImageLoader {
        val avif = sdk >= 31
        val webp = sdk >= 28
        // first, so it runs before any interceptor a test puts after it
        network.interceptors().add(0, Interceptor { chain ->
            val request = chain.request()
            val accept = ImageAccept.header(request.url.toString(), avif, webp)
            chain.proceed(if (accept == null) request else request.newBuilder().header(ImageAccept.NAME, accept).build())
        })
        val client = network.build()
        return ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { client }))
                add(if (Build.VERSION.SDK_INT >= 28) AnimatedImageDecoder.Factory() else GifDecoder.Factory())
            }
            .build()
    }
}

package com.ecency.app.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.ecency.core.app.HiveSignerNavigation
import com.ecency.core.app.HiveSignerPage
import com.ecency.core.auth.HiveSignerConfig
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.ecency.core.app.AuthUiCopy

/**
 * HiveSigner's pages (login and hot signing) in an in-app WebView. Only https on HiveSigner's own host loads;
 * the loopback redirect URI is never loaded, its URL goes to [onRedirect]; everything else is cancelled
 * ([HiveSignerPage]). No JavaScript bridge, no file or content access, no popups; cookies and storage are
 * cleared before the first load and when the view goes away, so no HiveSigner session outlives the sheet.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HiveSignerWebView(url: String, onRedirect: (String) -> Unit, modifier: Modifier = Modifier, config: HiveSignerConfig = HiveSignerConfig.RN) {
    val redirect by rememberUpdatedState(onRedirect)
    AndroidView(
        modifier = modifier.testTag("hiveSignerWeb"),
        factory = { context ->
            clearWebData()
            val web = WebView(context)
            try {
                web.apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.setSupportMultipleWindows(false)
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    settings.setGeolocationEnabled(false)
                    settings.saveFormData = false
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                            handle(view, request.url.toString(), config) { redirect(it) }

                        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                            // a redirect a server answered with (HTTP 302) reaches only this callback
                            if (HiveSignerPage.decide(url, config) != HiveSignerNavigation.LOAD) {
                                view.stopLoading()
                                if (HiveSignerPage.decide(url, config) == HiveSignerNavigation.REDIRECT) redirect(url)
                            }
                        }
                    }
                    if (HiveSignerPage.decide(url, config) == HiveSignerNavigation.LOAD) loadUrl(url)
                }
            } catch (t: Throwable) {
                // a view that could not be set up is not shown and is never released: it must not be counted
                runCatching { web.destroy() }
                throw t
            }
            // counted only once the view exists and is set up (onRelease takes it off again): a WebView that could
            // not be created or configured never blocks the player's wipe
            WebData.authViews.incrementAndGet()
            web
        },
        onRelease = { view ->
            try {
                view.stopLoading()
                view.clearHistory()
                view.clearCache(true)
                view.destroy()
            } finally {
                WebData.authViews.decrementAndGet()
                clearWebData()
            }
        },
    )
}

/** True when the navigation is cancelled. */
private fun handle(view: WebView, url: String, config: HiveSignerConfig, onRedirect: (String) -> Unit): Boolean =
    when (HiveSignerPage.decide(url, config)) {
        HiveSignerNavigation.LOAD -> false
        HiveSignerNavigation.REDIRECT -> {
            view.stopLoading()
            onRedirect(url)
            true
        }
        HiveSignerNavigation.BLOCK -> true
    }

/**
 * Cookies and web storage are process-wide (one WebView profile; a separate profile needs the multi-profile
 * API, which this minSdk's WebView does not guarantee). A video player closing must therefore never wipe them
 * while a HiveSigner page is open: it would sign the user out of the page mid-flow. The auth view wipes before
 * and after itself, so skipping the player's wipe meanwhile loses nothing.
 */
internal object WebData {
    /** HiveSigner web views alive right now. */
    val authViews = java.util.concurrent.atomic.AtomicInteger(0)

    /** Whether a player may wipe cookies and storage now. */
    fun playerMayWipe(): Boolean = authViews.get() <= 0

    /** The player's cleanup: everything it stored goes, unless an auth page is open. Returns whether it wiped. */
    fun wipeAfterPlayer(wipe: () -> Unit = ::clearWebData): Boolean {
        if (!playerMayWipe()) return false
        wipe()
        return true
    }
}

private fun clearWebData() {
    CookieManager.getInstance().removeAllCookies(null)
    CookieManager.getInstance().flush()
    WebStorage.getInstance().deleteAllData()
}

/** A QR code of [text] (the HiveAuth wallet link), drawn from zxing's bit matrix. */
@Composable
fun QrCode(text: String, size: Dp, modifier: Modifier = Modifier) {
    val bitmap = remember(text) { qrBitmap(text, 512) }
    Image(bitmap.asImageBitmap(), contentDescription = AuthUiCopy.QR_CODE, modifier = modifier.size(size).testTag("hiveAuthQr"))
}

internal fun qrBitmap(text: String, px: Int): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, px, px, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 1))
    val pixels = IntArray(matrix.width * matrix.height) { i ->
        if (matrix.get(i % matrix.width, i / matrix.width)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
}

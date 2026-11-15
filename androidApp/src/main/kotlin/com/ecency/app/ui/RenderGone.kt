package com.ecency.app.ui

import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ecency.core.app.AppIcon
import com.ecency.core.app.BrowserCopy
import java.util.Collections
import java.util.WeakHashMap

/**
 * A web view whose renderer crashed, or was ended by the system under memory pressure. Android ends the whole app
 * unless `onRenderProcessGone` answers true, and the dead view must never be used again (issue #3701). Every
 * `WebViewClient` of the app answers through [handle]: the view leaves its parent and is destroyed, then the screen
 * shows its failed state.
 */
internal object RenderGone {
    private val gone: MutableSet<WebView> = Collections.newSetFromMap(WeakHashMap())

    /** Whether [view] was ended here: it is destroyed already, its release must not touch it. */
    fun isGone(view: WebView): Boolean = view in gone

    /** Takes [view] out and destroys it once, then [after] (the failed state). Always true: the app goes on. */
    fun handle(view: WebView, after: () -> Unit): Boolean {
        if (gone.add(view)) {
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
            after()
        }
        return true
    }
}

/** The page could not be shown: the failed state of the dApp browser, with Retry when there is one. */
@Composable
internal fun WebPageFailed(onRetry: (() -> Unit)?, modifier: Modifier = Modifier) {
    val colors = LocalEcencyColors.current
    Column(modifier.fillMaxSize().testTag("webFailed"), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Glyph(AppIcon.ERROR, colors.icon, 48.dp)
        Text(BrowserCopy.PAGE_LOAD_FAILED, color = colors.darkGray, fontSize = 16.sp, modifier = Modifier.padding(top = 12.dp))
        if (onRetry != null) {
            Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(contentColor = Color.White, containerColor = colors.blue), modifier = Modifier.padding(top = 16.dp).testTag("webRetry")) {
                Text(BrowserCopy.RETRY, color = Color.White)
            }
        }
    }
}

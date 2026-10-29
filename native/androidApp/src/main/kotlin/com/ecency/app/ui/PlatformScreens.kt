package com.ecency.app.ui

import androidx.compose.ui.semantics.heading

import androidx.compose.ui.semantics.selected

import androidx.compose.ui.semantics.semantics


import androidx.compose.ui.semantics.Role

import androidx.compose.ui.text.style.TextDirection
import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import coil3.compose.AsyncImage
import com.ecency.core.app.AppEnvironment
import com.ecency.core.app.AppIcon
import com.ecency.core.app.BridgePrompt
import com.ecency.core.app.BrowserCopy
import com.ecency.core.app.BrowserPolicy
import com.ecency.core.app.BrowserTab
import com.ecency.core.app.DappBridgeScript
import com.ecency.core.app.DappBrowserModel
import com.ecency.core.app.Dapps
import com.ecency.core.app.ProposalCopy
import com.ecency.core.app.ProposalDismiss
import com.ecency.core.app.QrOutcome
import com.ecency.core.app.QrScanModel
import com.ecency.core.app.ShareCopy
import com.ecency.core.app.ShareTarget
import com.ecency.core.app.ShellUiCopy
import com.ecency.core.app.UiCopy

// ---------------------------------------------------------------------------------------------------------------
// RN screens/dappBrowser: the browser with the Hive signing bridge

/**
 * The web views of one browser screen, one per tab, owned by its stack entry: they outlive recomposition, the app
 * lock and activity recreation (each runs on a [MutableContextWrapper] that is pointed at the current activity), and
 * are destroyed with the entry. Only the active tab's view is attached to the screen.
 */
class DappWebViews : AutoCloseable {
    private val views = mutableMapOf<Int, WebView>()
    private val loaded = mutableMapOf<Int, Int>()

    fun view(tabId: Int, context: Context, create: (Context) -> WebView): WebView {
        val existing = views[tabId]
        if (existing != null) {
            (existing.context as? MutableContextWrapper)?.baseContext = context
            (existing.parent as? ViewGroup)?.removeView(existing)
            return existing
        }
        return create(MutableContextWrapper(context)).also { views[tabId] = it }
    }

    /** Loads [tab]'s URL when the app asked for it ([BrowserTab.load] changed since the last load). */
    fun loadIfAsked(tab: BrowserTab) {
        val url = tab.url ?: return
        val view = views[tab.id] ?: return
        if (loaded[tab.id] == tab.load) return
        loaded[tab.id] = tab.load
        // debug runs: the synthetic dApp is served as if from its https origin
        if (DappWeb.demo && url == com.ecency.core.app.DemoDapp.URL) {
            view.loadDataWithBaseURL(url, com.ecency.core.app.DemoDapp.HTML, "text/html", "utf-8", null)
        } else {
            view.loadUrl(url)
        }
    }

    fun get(tabId: Int): WebView? = views[tabId]

    /** Tabs that closed: their views go. */
    fun retain(ids: Set<Int>) {
        (views.keys - ids).forEach { id -> views.remove(id)?.let(::destroy); loaded.remove(id) }
    }

    override fun close() {
        views.values.forEach(::destroy)
        views.clear()
        loaded.clear()
    }

    private fun destroy(view: WebView) {
        (view.parent as? ViewGroup)?.removeView(view)
        DappWeb.detach(view)
        view.stopLoading()
        view.destroy()
    }
}

/** How the browser's web views are set up and talk to the bridge. */
internal object DappWeb {
    /** Debug runs only (`--es openScreen dapp`): [com.ecency.core.app.DemoDapp] loads in place of its URL. */
    @Volatile var demo = false

    /** Explore's own web profile: its cookies and storage stay apart from HiveSigner's pages, the players and the signup challenge. */
    const val PROFILE = "explore"

    /** Both features the bridge needs (WebView 83+); without them the bridge is off (never a JavaScript interface). */
    var bridgeSupported: () -> Boolean = {
        runCatching {
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        }.getOrDefault(false)
    }

    @SuppressLint("SetJavaScriptEnabled", "RequiresFeature")
    fun create(context: Context, tabId: Int, model: DappBrowserModel): WebView = WebView(context).apply {
        // its own profile, set before anything else touches the view
        val cookies = ownProfileCookies(this) ?: CookieManager.getInstance()
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mediaPlaybackRequiresUserGesture = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.setSupportMultipleWindows(false)
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setGeolocationEnabled(false)
        settings.saveFormData = false
        cookies.setAcceptThirdPartyCookies(this, false)
        // WebView takes "*" as the only all-origins rule ("https://*" is refused by some versions); the origin is
        // checked again for every message (BrowserPolicy.bridgeOrigin: https, main frame, the tab's own origin) and the
        // script installs itself on https top frames only. A WebView that refuses the setup gets no bridge.
        if (bridgeSupported()) runCatching {
            val view = this
            WebViewCompat.addWebMessageListener(this, DappBridgeScript.CHANNEL, setOf("*")) { _, message, sourceOrigin, isMainFrame, reply ->
                val data = mainFrameMessage(isMainFrame, message.data) ?: return@addWebMessageListener
                val origin = sourceOrigin.toString().trimEnd('/')
                model.bridgeMessage(tabId, origin, isMainFrame, data) { answer ->
                    // the reply proxy is bound to the document that asked; one that navigated away hears nothing
                    if (BrowserPolicy.httpsOrigin(view.url.orEmpty()) == BrowserPolicy.httpsOrigin(origin)) reply.postMessage(answer)
                }
            }
            WebViewCompat.addDocumentStartJavaScript(this, DappBridgeScript.source(DappBridgeScript.Platform.ANDROID), setOf("*"))
        }.onFailure { detach(this) }
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                !model.shouldLoad(tabId, request.url.toString(), request.isForMainFrame, request.hasGesture())

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) =
                model.pageChanged(tabId, url, null, view.canGoBack(), view.canGoForward())

            // the committed page: a navigation that only started (onPageStarted) never names "the page"
            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                model.pageCommitted(tabId, url)
                model.pageChanged(tabId, url, view.title, view.canGoBack(), view.canGoForward())
            }

            override fun onPageFinished(view: WebView, url: String?) {
                model.pageCommitted(tabId, url)
                model.pageChanged(tabId, url, view.title, view.canGoBack(), view.canGoForward())
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) model.pageFailed(tabId)
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) = model.progress(tabId, newProgress / 100f)

            override fun onReceivedTitle(view: WebView, title: String?) =
                model.pageChanged(tabId, view.url, title, view.canGoBack(), view.canGoForward())

            // no camera, microphone, location or files for pages (RN's WebView asks for none of them here either)
            override fun onPermissionRequest(request: PermissionRequest) = request.deny()

            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback) = callback.invoke(origin, false, false)

            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<android.net.Uri>>, params: FileChooserParams): Boolean = false
        }
    }

    /**
     * A frame's message never reaches the model: this app-side check stands on its own, next to the shared model's
     * (BrowserPolicy.bridgeOrigin), so neither depends on how the other was compiled.
     */
    fun mainFrameMessage(isMainFrame: Boolean, data: String?): String? = if (isMainFrame) data else null

    @SuppressLint("RequiresFeature")
    fun detach(view: WebView) {
        runCatching { if (bridgeSupported()) WebViewCompat.removeWebMessageListener(view, DappBridgeScript.CHANNEL) }
    }

    @SuppressLint("RequiresFeature")
    private fun ownProfileCookies(view: WebView): CookieManager? = runCatching {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) return null
        val profile = ProfileStore.getInstance().getOrCreateProfile(PROFILE)
        WebViewCompat.setProfile(view, PROFILE)
        profile.cookieManager
    }.getOrNull()

    /** What the dApps stored (cookies, web storage) goes when every account left or the data was wiped; tests replace it. */
    var clearProfile: () -> Unit = ::clearOwnProfile

    @SuppressLint("RequiresFeature")
    private fun clearOwnProfile() {
        runCatching {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) return
            val profile = ProfileStore.getInstance().getProfile(PROFILE) ?: return
            profile.cookieManager.removeAllCookies(null)
            profile.cookieManager.flush()
            profile.webStorage.deleteAllData()
        }
    }
}

/** RN DappBrowser: header, progress, the page or the home screen, the tab bar; the bridge's confirmation sheet. */
@Composable
fun DappBrowserScreen(env: AppEnvironment, entry: StackEntry, initialUrl: String?, onClose: () -> Unit) {
    val colors = LocalEcencyColors.current
    val model = entry.get("browser") { env.dappBrowserModel(initialUrl) }
    val views = entry.get("webviews") { DappWebViews() }
    val state by model.state.collectAsState()
    val tab = state.activeTab
    val context = LocalContext.current
    LaunchedEffect(state.tabs.map { it.id }) { views.retain(state.tabs.map { it.id }.toSet()) }
    LaunchedEffect(state.notice) { state.notice?.let { env.ui.toast(it); model.consumeNotice() } }
    // RN has no back handling (back closes the screen); here a page goes back in its own history first
    ScreenBackHandler {
        val view = views.get(tab.id)
        if (tab.url != null && view?.canGoBack() == true) view.goBack() else onClose()
    }
    Column(Modifier.fillMaxSize().background(colors.background).statusBarsPadding().navigationBarsPadding().imePadding().testTag("dappBrowser")) {
        BrowserHeader(state.showsHome, tab, state.displayUrl, state.urlFocused, state.urlText, state.isSecure, model, onClose, views)
        if (!state.showsHome) {
            Box(Modifier.fillMaxWidth().height(2.dp)) {
                if (tab.progress > 0f) Box(Modifier.fillMaxWidth(tab.progress).fillMaxHeight().background(colors.blue).testTag("browserProgress"))
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.showsHome -> DappHome(state.urlText, model)
                tab.hasError -> Column(Modifier.fillMaxSize().testTag("browserError"), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Glyph(AppIcon.ERROR, colors.icon, 64.dp)
                    Text(BrowserCopy.PAGE_LOAD_FAILED, color = colors.darkGray, fontSize = 16.sp, modifier = Modifier.padding(top = 12.dp))
                    Button(onClick = model::retry, colors = ButtonDefaults.buttonColors(contentColor = androidx.compose.ui.graphics.Color.White, containerColor = colors.blue), modifier = Modifier.padding(top = 16.dp).testTag("browserRetry")) {
                        Text(BrowserCopy.RETRY, color = Color.White)
                    }
                }
                else -> androidx.compose.runtime.key(tab.id) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize().testTag("dappPage"),
                        factory = { ctx -> views.view(tab.id, ctx) { c -> DappWeb.create(c, tab.id, model) }.also { views.loadIfAsked(tab) } },
                        update = { views.loadIfAsked(tab) },
                        onRelease = { (it.parent as? ViewGroup)?.removeView(it) },
                    )
                }
            }
        }
        BrowserTabBar(state.tabs, state.activeTabId, state.canCloseTabs, model)
    }
    state.prompt?.let { prompt -> BridgeConfirmSheet(prompt, onConfirm = model::confirm, onReject = model::reject) }
    if (state.working) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.2f)).testTag("bridgeWorking"), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = colors.blueText) }
    }
}

@Composable
private fun BrowserHeader(
    home: Boolean,
    tab: BrowserTab,
    displayUrl: String,
    focused: Boolean,
    urlText: String,
    secure: Boolean,
    model: DappBrowserModel,
    onClose: () -> Unit,
    views: DappWebViews,
) {
    val colors = LocalEcencyColors.current
    val focus = LocalFocusManager.current
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        HeaderButton(if (home) AppIcon.CLOSE else AppIcon.HOME, if (home) UiCopy.CLOSE else ShellUiCopy.HOME, "browserHome") { if (!model.home()) onClose() }
        if (!home) {
            HeaderButton(AppIcon.CHEVRON_LEFT, UiCopy.BACK, "browserBack", enabled = tab.canGoBack) { views.get(tab.id)?.goBack() }
            HeaderButton(AppIcon.CHEVRON_RIGHT, ShellUiCopy.FORWARD, "browserForward", enabled = tab.canGoForward) { views.get(tab.id)?.goForward() }
            var field by remember { mutableStateOf(TextFieldValue(displayUrl)) }
            LaunchedEffect(displayUrl, focused) {
                if (!focused) field = TextFieldValue(displayUrl) else if (field.text != urlText) field = TextFieldValue(urlText, TextRange(0, urlText.length))
            }
            Row(
                Modifier.weight(1f).heightIn(min = 36.dp).clip(RoundedCornerShape(18.dp)).background(colors.lightBackground).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!focused && secure) Glyph(AppIcon.LOCK, Color(0xFF4FD688), 14.dp, Modifier.padding(end = 4.dp).testTag("browserLock"), filled = true)
                BasicTextField(
                    field,
                    { field = it; model.setUrlText(it.text) },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(color = colors.text, fontSize = 14.sp, textDirection = androidx.compose.ui.text.style.TextDirection.Ltr),
                    cursorBrush = SolidColor(colors.blueText),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onGo = { model.submitUrl(); focus.clearFocus() }),
                    modifier = Modifier.weight(1f).onFocusChanged { model.setUrlFocused(it.isFocused) }.testTag("browserUrl"),
                )
                if (focused && urlText.isNotEmpty()) {
                    Box(Modifier.clip(CircleShape).clickable(role = Role.Button) { field = TextFieldValue(""); model.setUrlText("") }.padding(4.dp).testTag("browserClear")) {
                        Glyph(AppIcon.CLOSE, colors.icon, 16.dp, description = UiCopy.CLEAR)
                    }
                }
            }
            HeaderButton(AppIcon.REFRESH, ShellUiCopy.RELOAD, "browserReload") { views.get(tab.id)?.reload() }
        } else {
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun HeaderButton(icon: AppIcon, label: String, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = LocalEcencyColors.current
    Box(Modifier.size(40.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onClick, role = Role.Button).testTag(tag), contentAlignment = Alignment.Center) {
        Glyph(icon, if (enabled) colors.text else colors.icon, 22.dp, description = label)
    }
}

/** RN _renderHome: title, subtitle, the URL field, the signing banner and the dApp grid; picks open in this tab. */
@Composable
private fun DappHome(urlText: String, model: DappBrowserModel) {
    val colors = LocalEcencyColors.current
    LazyVerticalGrid(GridCells.Fixed(4), Modifier.fillMaxSize().padding(horizontal = 16.dp).testTag("explore")) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Text(Dapps.TITLE, color = colors.text, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() }.padding(top = 8.dp))
                Text(Dapps.SUBTITLE, color = colors.darkGray, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.lightBackground).padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Glyph(AppIcon.SEARCH, colors.icon, 20.dp)
                    Box(Modifier.weight(1f).padding(start = 8.dp)) {
                        if (urlText.isEmpty()) Text(Dapps.SEARCH_PLACEHOLDER, color = colors.darkGray, fontSize = 15.sp)
                        BasicTextField(
                            urlText, model::setUrlText, singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(color = colors.text, fontSize = 15.sp, textDirection = TextDirection.Content),
                            cursorBrush = SolidColor(colors.blueText),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                            keyboardActions = KeyboardActions(onGo = { model.submitUrl() }),
                            modifier = Modifier.fillMaxWidth().testTag("dappUrl"),
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, colors.blueText.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                        .background(colors.blue.copy(alpha = 0.08f)).padding(12.dp),
                ) {
                    Glyph(AppIcon.INFO, colors.blueText, 20.dp)
                    Text(Dapps.SIGNING_INFO, color = colors.text, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp))
                }
                Text(Dapps.POPULAR, color = colors.text, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 20.dp, bottom = 12.dp))
            }
        }
        items(Dapps.DIRECTORY, key = { it.url }) { dapp ->
            Column(
                Modifier.padding(bottom = 20.dp).clickable(role = Role.Button) { model.navigate(dapp.url) }.testTag("dapp:${dapp.name}"),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(colors.lightBackground), contentAlignment = Alignment.Center) {
                    val tint = remember(dapp.color) { Color(android.graphics.Color.parseColor(dapp.color)) }
                    Text(dapp.initial, color = tint, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    AsyncImage(dapp.icon, contentDescription = dapp.name, contentScale = ContentScale.Fit, modifier = Modifier.size(36.dp))
                }
                Text(dapp.name, color = colors.text, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                Text(dapp.category, color = colors.darkGray, fontSize = 9.sp)
            }
        }
    }
}

/** RN _renderTabBar: the tabs by title (close when more than one), then +. */
@Composable
private fun BrowserTabBar(tabs: List<BrowserTab>, active: Int, canClose: Boolean, model: DappBrowserModel) {
    val colors = LocalEcencyColors.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).background(colors.grayBackground).padding(horizontal = 8.dp).testTag("browserTabs"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            tabs.forEach { t ->
                Row(
                    Modifier.padding(end = 6.dp).widthIn(max = 160.dp).clip(RoundedCornerShape(8.dp))
                        .background(if (t.id == active) colors.background else Color.Transparent)
                        .clickable(role = Role.Tab) { model.switchTab(t.id) }.semantics { selected = t.id == active }.padding(horizontal = 10.dp, vertical = 6.dp).testTag("browserTab:${t.id}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(t.title, color = colors.text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (canClose) {
                        Box(Modifier.padding(start = 6.dp).clip(CircleShape).clickable(role = Role.Button) { model.closeTab(t.id) }.padding(horizontal = 8.dp).testTag("browserCloseTab:${t.id}")) {
                            Glyph(AppIcon.CLOSE, colors.icon, 14.dp, description = ShellUiCopy.CLOSE_TAB)
                        }
                    }
                }
            }
        }
        Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = model::addTab, role = Role.Button).testTag("browserAddTab"), contentAlignment = Alignment.Center) {
            Glyph(AppIcon.ADD, colors.text, 22.dp, description = ShellUiCopy.NEW_TAB)
        }
    }
}

/** RN SignConfirmSheet: what the page asks, from where (the web view's origin), with what key. Dismissing rejects. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BridgeConfirmSheet(prompt: BridgePrompt, onConfirm: () -> Unit, onReject: () -> Unit) {
    val colors = LocalEcencyColors.current
    val reject by rememberUpdatedState(onReject)
    ModalBottomSheet(onDismissRequest = { reject() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.background) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).navigationBarsPadding().padding(bottom = 16.dp).testTag("bridgeConfirm")) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Glyph(AppIcon.VERIFIED_USER, colors.blueText, 40.dp) }
            Text(prompt.title, color = colors.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() }.fillMaxWidth().padding(top = 12.dp).testTag("bridgeTitle"), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp), contentAlignment = Alignment.Center) {
                Row(
                    Modifier.clip(RoundedCornerShape(20.dp)).background(colors.lightBackground).padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Glyph(AppIcon.GLOBE, colors.icon, 16.dp)
                    Text(prompt.host, color = colors.darkGray, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 6.dp).widthIn(max = 250.dp).testTag("bridgeHost"))
                }
            }
            prompt.blacklisted?.let {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFFFDEDED)).padding(12.dp).testTag("bridgePhishing"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Glyph(AppIcon.WARNING, Color(0xFFE74C3C), 18.dp)
                    Text(BrowserCopy.PHISHING, color = Color(0xFFE74C3C), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp))
                }
            }
            Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()).padding(bottom = 20.dp)) {
                DetailRow(BrowserCopy.ACCOUNT, "@${prompt.username}")
                DetailRow(BrowserCopy.AUTHORITY, prompt.authority, tag = "bridgeAuthority")
                prompt.details.forEach { DetailRow(it.name, it.value) }
                prompt.operations.forEach { op ->
                    Text(op.title, color = colors.text, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
                    op.fields.forEach { DetailRow(it.name, it.value, mono = true) }
                }
            }
            Button(onClick = onConfirm, colors = ButtonDefaults.buttonColors(contentColor = androidx.compose.ui.graphics.Color.White, containerColor = colors.blue), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("bridgeConfirmButton")) {
                Text(BrowserCopy.CONFIRM, color = Color.White, fontWeight = FontWeight.Bold)
            }
            TextButton(onClick = onReject, modifier = Modifier.fillMaxWidth().padding(top = 6.dp).testTag("bridgeCancel")) { Text(BrowserCopy.CANCEL, color = colors.text) }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String, mono: Boolean = false, tag: String? = null) {
    if (value.isEmpty()) return
    val colors = LocalEcencyColors.current
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = colors.darkGray, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Text(
            value, color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 4, overflow = TextOverflow.Ellipsis,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier.weight(2f).let { m -> tag?.let { m.testTag(it) } ?: m },
        )
    }
}

// ---------------------------------------------------------------------------------------------------------------
// RN QR_SCAN: the scanner screen (header and side menu)

/**
 * RN qrModalView: the back camera, QR codes only; one code per open; the code goes through the app's link
 * handling ([QrScanModel]); the screen closes on the outcome. The permission is asked on open; refused, RN's alert
 * offers the system settings.
 */
@Composable
fun QrScanScreen(env: AppEnvironment, entry: StackEntry, host: AppHost, onClose: () -> Unit, onOpen: (com.ecency.core.app.NavEntry) -> Unit) {
    val colors = LocalEcencyColors.current
    val model = entry.get("qr") { env.qrScanModel(com.ecency.core.app.QrPurpose.LINKS) }
    val state by model.state.collectAsState()
    LaunchedEffect(state.outcome) {
        when (val o = state.outcome) {
            QrOutcome.LinkHandled -> { model.consumeOutcome(); onClose() }
            is QrOutcome.Open -> { model.consumeOutcome(); onClose(); onOpen(o.entry) }
            is QrOutcome.Browser -> { model.consumeOutcome(); onClose(); host.openExternal(o.url) }
            is QrOutcome.Unsupported, is QrOutcome.Value, null -> Unit
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black).testTag("qrScan")) {
        QrScanner(model, Modifier.fillMaxSize())
        Box(Modifier.statusBarsPadding().padding(8.dp).size(44.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.4f)).clickable(onClick = onClose, role = Role.Button).testTag("qrClose"), contentAlignment = Alignment.Center) {
            Glyph(AppIcon.CLOSE, Color.White, 24.dp, description = UiCopy.CLOSE)
        }
    }
    (state.outcome as? QrOutcome.Unsupported)?.let { u ->
        AlertDialog(
            onDismissRequest = { model.consumeOutcome(); onClose() },
            title = { Text(u.title) },
            text = { Text(u.message, modifier = Modifier.testTag("qrUnsupported")) },
            confirmButton = { TextButton(onClick = { model.consumeOutcome(); onClose() }) { Text(UiCopy.CLOSE, color = colors.blueText) } },
        )
    }
}

/**
 * The camera preview and the decoder over a [QrScanModel]: asks for the camera permission first, shows RN's alert
 * when refused, and hands every decoded value to the model (which keeps only the first).
 */
@Composable
fun QrScanner(model: QrScanModel, modifier: Modifier = Modifier, onDenied: () -> Unit = {}) {
    val context = LocalContext.current
    val colors = LocalEcencyColors.current
    val state by model.state.collectAsState()
    var granted by remember { mutableStateOf(QrCamera.hasPermission(context)) }
    val ask = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        model.setPermissionDenied(!ok)
    }
    LaunchedEffect(Unit) {
        model.reset()
        if (!granted) ask.launch(android.Manifest.permission.CAMERA)
    }
    if (granted && !state.handled) QrCamera.Preview(modifier) { model.scanned(it) } else Box(modifier)
    if (state.permissionDenied) {
        AlertDialog(
            onDismissRequest = { model.setPermissionDenied(false); onDenied() },
            title = { Text(QrScanModel.PERMISSION_TITLE) },
            text = { Text(QrScanModel.PERMISSION_MESSAGE, modifier = Modifier.testTag("qrPermission")) },
            dismissButton = { TextButton(onClick = { model.setPermissionDenied(false); onDenied() }) { Text(QrScanModel.PERMISSION_CLOSE, color = colors.text) } },
            confirmButton = {
                TextButton(onClick = {
                    model.setPermissionDenied(false)
                    QrCamera.openSettings(context)
                }) { Text(QrScanModel.PERMISSION_ALLOW, color = colors.blueText) }
            },
        )
    }
}

// ---------------------------------------------------------------------------------------------------------------
// RN ShareIntentSheet

/** RN "Share to...": Blog Post or Wave; closing the sheet picks Blog Post. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareChooserSheet(onChoose: (ShareTarget?) -> Unit) {
    val colors = LocalEcencyColors.current
    ModalBottomSheet(onDismissRequest = { onChoose(null) }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.background) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 16.dp, bottom = 32.dp).navigationBarsPadding().testTag("shareChooser")) {
            Text(ShareCopy.TITLE, color = colors.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() }.padding(bottom = 20.dp))
            ShareOption(AppIcon.EDIT, ShareCopy.BLOG, ShareCopy.BLOG_DESC, "shareBlog") { onChoose(ShareTarget.BLOG) }
            ShareOption(AppIcon.WAVES, ShareCopy.WAVE, ShareCopy.WAVE_DESC, "shareWave") { onChoose(ShareTarget.WAVE) }
        }
    }
}

@Composable
private fun ShareOption(icon: AppIcon, title: String, description: String, tag: String, onClick: () -> Unit) {
    val colors = LocalEcencyColors.current
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick, role = Role.Button).padding(vertical = 14.dp, horizontal = 8.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(icon, colors.blueText, 28.dp)
        Column(Modifier.padding(start = 16.dp).weight(1f)) {
            Text(title, color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(description, color = colors.darkGray, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// RN ProposalVoteRequest

/** The proposal card above the main feed's first tab (signed in only). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProposalCard(env: AppEnvironment) {
    val colors = LocalEcencyColors.current
    val model = env.proposalVote
    LaunchedEffect(model) { model.start() }
    val state by model.state.collectAsState()
    if (!state.visible) return
    Column(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp).background(colors.lightBackground).padding(16.dp).testTag("proposalCard")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(state.title, color = colors.darkText, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() }.testTag("proposalTitle"))
                Text(state.body, color = colors.darkText, modifier = Modifier.padding(vertical = 8.dp))
            }
            androidx.compose.foundation.Image(
                androidx.compose.ui.res.painterResource(com.ecency.app.R.drawable.ecency_logo),
                contentDescription = null, modifier = Modifier.padding(horizontal = 12.dp).width(56.dp),
            )
        }
        if (!state.voted) {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = model::vote, enabled = !state.voting, colors = ButtonDefaults.buttonColors(contentColor = androidx.compose.ui.graphics.Color.White, containerColor = colors.blue),
                    modifier = Modifier.heightIn(min = 40.dp).testTag("proposalVote"),
                ) {
                    if (state.voting) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    else Text(ProposalCopy.VOTE, color = Color.White, fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = model::askDismiss, modifier = Modifier.padding(start = 8.dp).testTag("proposalDismiss")) { Text(ProposalCopy.DISMISS, color = colors.blueText) }
            }
        }
    }
    if (state.askingDismiss) {
        ModalBottomSheet(onDismissRequest = { model.dismiss(null) }, containerColor = colors.background) {
            Column(Modifier.fillMaxWidth().padding(24.dp).navigationBarsPadding().testTag("proposalDismissSheet")) {
                Text(ProposalCopy.DISMISS_TITLE, color = colors.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() }.padding(bottom = 16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.OutlinedButton(onClick = { model.dismiss(ProposalDismiss.FOREVER) }, modifier = Modifier.weight(1f).testTag("proposalForever")) {
                        Text(ProposalCopy.FOREVER, color = colors.text)
                    }
                    Button(onClick = { model.dismiss(ProposalDismiss.LATER) }, colors = ButtonDefaults.buttonColors(contentColor = androidx.compose.ui.graphics.Color.White, containerColor = colors.blue), modifier = Modifier.weight(1f).testTag("proposalLater")) {
                        Text(ProposalCopy.LATER, color = Color.White)
                    }
                }
            }
        }
    }
}

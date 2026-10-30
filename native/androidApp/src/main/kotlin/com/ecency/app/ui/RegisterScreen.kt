package com.ecency.app.ui

import androidx.compose.foundation.layout.heightIn

import androidx.compose.ui.semantics.LiveRegionMode

import androidx.compose.ui.semantics.liveRegion

import androidx.compose.ui.semantics.heading

import androidx.compose.ui.semantics.semantics

import androidx.compose.ui.semantics.Role

import com.ecency.core.app.AppIcon
import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogProperties
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.ecency.core.app.AppEnvironment
import com.ecency.core.app.RegisterCopy
import com.ecency.core.app.RegisterModel
import com.ecency.core.app.RegisterState
import com.ecency.core.app.TurnstilePage
import com.ecency.core.app.LoginCopy
import com.ecency.core.app.UiCopy

/**
 * RN registerScreen over [RegisterModel]: username (rules, then the availability lookup), email, optional
 * referral, Continue, and "Get Hive Account" with the free signup behind the Turnstile challenge and the paid account
 * (an in-app purchase, "Buy Account - {price}" or "Claim Paid Account").
 */
@Composable
fun RegisterScreen(env: AppEnvironment, host: AppHost, model: RegisterModel, onBack: () -> Unit, onLogin: () -> Unit, onDone: () -> Unit) {
    val colors = LocalEcencyColors.current
    val state by model.state.collectAsState()
    // the register flow ended (created, left, the account changed): nothing of the challenge stays on the device
    DisposableEffect(Unit) { onDispose { TurnstileBridge.clearProfileSoon() } }

    Column(Modifier.fillMaxSize().background(colors.background).imePadding().testTag("register")) {
        TopBar(RegisterCopy.TITLE, onBack = onBack)
        Column(Modifier.weight(1f).padding(horizontal = 24.dp).verticalScroll(rememberScrollState())) {
            Text(RegisterCopy.TITLE_DESCRIPTION, fontSize = 15.sp, color = colors.darkGray, modifier = Modifier.padding(bottom = 20.dp))
            // the username after its "@" reads left to right in every language
            LeftToRight {
                OutlinedTextField(
                    value = state.username, onValueChange = model::setUsername, singleLine = true, isError = !state.usernameValid,
                    placeholder = { Text(RegisterCopy.USERNAME, color = colors.darkGray) },
                    leadingIcon = { Text("@", color = colors.darkGray, fontWeight = FontWeight.Bold) },
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Email),
                    colors = fieldColors(), modifier = Modifier.fillMaxWidth().testTag("registerUser"),
                )
            }
            if (state.usernameError.isNotEmpty()) {
                Text(state.usernameError, color = colors.redText, fontSize = 12.sp, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.padding(top = 4.dp).testTag("registerUserError"))
            }
            OutlinedTextField(
                value = state.email, onValueChange = model::setEmail, singleLine = true, isError = !state.emailValid,
                placeholder = { Text(RegisterCopy.MAIL, color = colors.darkGray) },
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Email),
                textStyle = ltrFieldStyle(),
                colors = fieldColors(), modifier = Modifier.fillMaxWidth().padding(top = 10.dp).testTag("registerEmail"),
            )
            OutlinedTextField(
                value = state.referral, onValueChange = model::setReferral, singleLine = true, isError = !state.referralValid,
                placeholder = { Text(RegisterCopy.REF_USER, color = colors.darkGray) },
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Email),
                textStyle = ltrFieldStyle(),
                colors = fieldColors(), modifier = Modifier.fillMaxWidth().padding(top = 10.dp).testTag("registerReferral"),
            )
            Text(
                RegisterCopy.FORM_DESCRIPTION, fontSize = 12.sp, color = colors.darkGray,
                modifier = Modifier.padding(top = 10.dp).clickable(role = Role.Button) { host.openExternal(LoginCopy.TERMS_URL) }.testTag("registerTerms"),
            )
            Button(
                onClick = model::continueToOptions, enabled = state.canContinue,
                colors = ButtonDefaults.buttonColors(contentColor = androidx.compose.ui.graphics.Color.White, containerColor = colors.blue),
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp).heightIn(min = 48.dp).testTag("registerContinue"),
            ) { Text(RegisterCopy.CONTINUE, color = Color.White, fontWeight = FontWeight.Bold) }
            Row(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalArrangement = Arrangement.Center) {
                Text(RegisterCopy.HAVE_ACCOUNT, color = colors.darkGray, fontSize = 14.sp)
                Text(
                    " ${RegisterCopy.LOGIN_HERE}", color = colors.blueText, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable(onClick = onLogin, role = Role.Button).testTag("registerLogin"),
                )
            }
        }
    }

    if (state.optionsOpen) {
        Dialog(onDismissRequest = model::closeOptions, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            RegisterOptions(env, state, model, onClose = model::closeOptions, onContinue = { host.openInbox(); onDone() })
        }
    }

    state.alert?.let { alert ->
        AlertDialog(
            onDismissRequest = model::dismissAlert,
            title = { Text(alert.title) },
            text = { Text(alert.body, modifier = Modifier.testTag("registerAlert")) },
            confirmButton = { TextButton(onClick = model::dismissAlert) { Text(UiCopy.OK) } },
        )
    }
}

/** RN registerAccountModal. */
@Composable
private fun RegisterOptions(env: AppEnvironment, state: RegisterState, model: RegisterModel, onClose: () -> Unit, onContinue: () -> Unit) {
    val colors = LocalEcencyColors.current
    Column(Modifier.fillMaxSize().background(colors.background).statusBarsPadding().navigationBarsPadding().testTag("registerOptions")) {
        TopBar(RegisterCopy.MODAL_TITLE, onBack = if (state.isRegistering) null else onClose)
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text(state.username, color = colors.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(state.email, color = colors.darkGray, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (state.isRegistering || state.isRegistered) {
            Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                if (state.isRegistered) Glyph(AppIcon.CHECK_CIRCLE, Color(0xFF4FD688), 64.dp, filled = true) else CircularProgressIndicator(color = colors.text)
                Text(
                    if (state.isRegistered) RegisterCopy.REGISTERED else RegisterCopy.REGISTERING, color = colors.text, fontSize = 16.sp,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(top = 16.dp).testTag("registerProgress"),
                )
                if (state.isRegistered) {
                    Button(
                        onClick = onContinue, colors = ButtonDefaults.buttonColors(contentColor = androidx.compose.ui.graphics.Color.White, containerColor = colors.blue),
                        modifier = Modifier.padding(top = 24.dp).testTag("registerDone"),
                    ) { Text(RegisterCopy.CONTINUE, color = Color.White) }
                }
            }
            return
        }
        if (state.productsLoading) {
            Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = colors.blueText) }
            return
        }
        Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
            // RN `disableFree`: a purchase link sells only the paid account
            if (!state.purchaseOnly) OptionCard(RegisterCopy.FREE_TITLE, RegisterCopy.FREE_DESC) {
                val standIn = env.captchaStandIn
                if (standIn != null) {
                    // debug fake-data runs: the hosted challenge is never loaded
                    LaunchedEffect(state.captchaKey) { model.captchaVerified(standIn) }
                    Text("Demo run: the verification step is skipped.", color = colors.darkGray, fontSize = 12.sp, modifier = Modifier.testTag("captchaStandIn"))
                } else {
                    key(state.captchaKey) {
                        TurnstileView { event ->
                            when (event) {
                                is TurnstilePage.Event.Verified -> model.captchaVerified(event.token)
                                TurnstilePage.Event.Expired -> model.captchaExpired()
                                TurnstilePage.Event.Failed -> model.captchaFailed()
                            }
                        }
                    }
                }
                TextButton(onClick = model::registerFree, enabled = state.canRegisterFree, modifier = Modifier.testTag("registerFree")) {
                    Text(RegisterCopy.FREE_BUTTON, fontWeight = FontWeight.Bold)
                }
            }
            // RN maps the store's products: no card when the store does not sell the paid account here
            state.paidButton?.let { label ->
                Spacer(Modifier.height(16.dp))
                OptionCard(RegisterCopy.BUY_TITLE, RegisterCopy.BUY_DESC) {
                    TextButton(onClick = model::buyAccount, enabled = !state.isRegistering, modifier = Modifier.testTag("registerBuy")) {
                        Text(label, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun OptionCard(title: String, description: String, content: @Composable () -> Unit) {
    val colors = LocalEcencyColors.current
    Column(Modifier.fillMaxWidth().border(1.dp, colors.border, RoundedCornerShape(12.dp)).padding(16.dp)) {
        Text(title, color = colors.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
        Text(description, color = colors.darkGray, fontSize = 14.sp, modifier = Modifier.padding(vertical = 8.dp))
        content()
    }
}

/**
 * RN turnstileWebView: Ecency's hosted challenge page in a web view that may only show that page and
 * Cloudflare's challenge ([TurnstilePage.allowsLoad]). A failed page load shows a manual retry and never asks for
 * a new challenge by itself (RN: that would reload the failing URL in a loop).
 *
 * The page reports through `window.ReactNativeWebView.postMessage`. That object is provided by an origin-bound
 * message listener ([WebViewCompat.addWebMessageListener] for `https://ecency.com` only), so Cloudflare's frames
 * and anything else the page embeds never see it, and a message counts only from the main frame of that origin
 * while the top document is the embed page. On a WebView too old for the listener (before version 83) the
 * object is a JavaScript interface, which every frame can reach: there the top document check and the token's
 * shape are the only filters.
 *
 * Cookies: where the WebView supports profiles, the challenge runs in its own ("signup-challenge"), apart from
 * the cookies of HiveSigner's pages and the video players; otherwise it shares the app's one WebView cookie jar.
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface", "RequiresFeature")
@Composable
fun TurnstileView(onEvent: (TurnstilePage.Event) -> Unit) {
    val colors = LocalEcencyColors.current
    var failed by remember { mutableStateOf(false) }
    val deliver by rememberUpdatedState(onEvent)
    if (failed) {
        Column(Modifier.fillMaxWidth().height(76.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(RegisterCopy.CAPTCHA_LOAD_FAILED, color = colors.darkGray, fontSize = 13.sp)
            Text(RegisterCopy.RETRY, color = colors.blueText, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable(role = Role.Button) { failed = false }.padding(6.dp).testTag("captchaRetry"))
        }
        return
    }
    DisposableEffect(Unit) {
        // a closing video player must not wipe cookies under a running challenge
        WebData.authViews.incrementAndGet()
        onDispose { WebData.authViews.decrementAndGet() }
    }
    AndroidView(
        modifier = Modifier.fillMaxWidth().height(76.dp).testTag("captcha"),
        factory = { context ->
            WebView(context).apply {
                val main = Handler(Looper.getMainLooper())
                val view = this
                // its own cookie scope, set before anything else touches the view
                val cookies = TurnstileBridge.ownProfileCookies(view) ?: CookieManager.getInstance()
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.setSupportMultipleWindows(false)
                settings.javaScriptCanOpenWindowsAutomatically = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                settings.setGeolocationEnabled(false)
                settings.saveFormData = false
                cookies.setAcceptThirdPartyCookies(this, true) // the challenge frame is third party (RN)
                setLayerType(View.LAYER_TYPE_SOFTWARE, null) // RN androidLayerType="software"
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                isVerticalScrollBarEnabled = false
                TurnstileBridge.attach(view) { event -> main.post { if (TurnstilePage.acceptsMessagesFrom(view.url)) deliver(event) } }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean =
                        !TurnstilePage.allowsLoad(request.url.toString(), request.isForMainFrame)

                    override fun onReceivedError(v: WebView, request: WebResourceRequest, error: WebResourceError) {
                        if (request.isForMainFrame) failed = true
                    }

                    override fun onReceivedHttpError(v: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                        if (request.isForMainFrame) failed = true
                    }
                }
                loadUrl(TurnstilePage.URL)
            }
        },
        onRelease = { TurnstileBridge.detach(it); it.stopLoading(); it.destroy() },
    )
}

/** How the challenge page's messages reach the app, and which cookie jar the challenge uses. */
internal object TurnstileBridge {
    /** The only origin that gets the bridge object. */
    const val ORIGIN = "https://ecency.com"
    const val PROFILE = "signup-challenge"

    /** Whether the origin-bound listener is available (WebView 83+); tests set it. */
    var listenerSupported: () -> Boolean = { runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) }.getOrDefault(false) }

    /**
     * A message from the listener counts only from the main frame of [ORIGIN]; then it must have one of RN's
     * three shapes ([TurnstilePage.parse], which also checks the token).
     */
    fun accept(data: String?, sourceOrigin: String, isMainFrame: Boolean): TurnstilePage.Event? =
        if (isMainFrame && sourceOrigin == ORIGIN) TurnstilePage.parse(data) else null

    @SuppressLint("JavascriptInterface", "RequiresFeature")
    fun attach(view: WebView, onEvent: (TurnstilePage.Event) -> Unit) {
        if (listenerSupported()) {
            WebViewCompat.addWebMessageListener(view, TurnstilePage.BRIDGE, setOf(ORIGIN)) { _, message, sourceOrigin, isMainFrame, _ ->
                accept(message.data, sourceOrigin.toString().trimEnd('/'), isMainFrame)?.let(onEvent)
            }
        } else {
            // old WebView: reachable from every frame; the caller's top document check and the shape check remain
            view.addJavascriptInterface(
                object {
                    @JavascriptInterface
                    fun postMessage(data: String?) {
                        TurnstilePage.parse(data)?.let(onEvent)
                    }
                },
                TurnstilePage.BRIDGE,
            )
        }
    }

    @SuppressLint("RequiresFeature")
    fun detach(view: WebView) {
        runCatching {
            if (listenerSupported()) WebViewCompat.removeWebMessageListener(view, TurnstilePage.BRIDGE) else view.removeJavascriptInterface(TurnstilePage.BRIDGE)
        }
    }

    /** Puts [view] in the challenge's own profile and returns that profile's cookie manager; null when profiles are not supported. */
    @SuppressLint("RequiresFeature")
    fun ownProfileCookies(view: WebView): CookieManager? = runCatching {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) return null
        val profile = ProfileStore.getInstance().getOrCreateProfile(PROFILE)
        WebViewCompat.setProfile(view, PROFILE)
        profile.cookieManager
    }.getOrNull()

    /**
     * What the challenge left in its profile (cookies, web storage) goes when the register flow ends and on a
     * wipe; tests replace it. The profile itself is deleted too when no view uses it any more (best effort).
     */
    var clearProfile: () -> Unit = ::clearOwnProfile

    @SuppressLint("RequiresFeature")
    private fun clearOwnProfile() {
        runCatching {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) return
            val store = ProfileStore.getInstance()
            // never creates it: nothing to clear when no challenge ever ran
            val profile = store.getProfile(PROFILE) ?: return
            profile.cookieManager.removeAllCookies(null)
            profile.cookieManager.flush()
            profile.webStorage.deleteAllData()
            // refused while a view still uses the profile: its data is gone all the same
            runCatching { store.deleteProfile(PROFILE) }
        }
    }

    /** After the challenge's view was released (the screen's own disposal runs first): on the next main loop turn. */
    fun clearProfileSoon() {
        Handler(Looper.getMainLooper()).post { runCatching { clearProfile() } }
    }
}

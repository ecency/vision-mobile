package com.ecency.app.ui

import androidx.compose.foundation.layout.heightIn

import androidx.compose.ui.semantics.LiveRegionMode

import androidx.compose.ui.semantics.liveRegion

import androidx.compose.ui.semantics.heading

import androidx.compose.ui.semantics.semantics

import androidx.compose.ui.semantics.Role

import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.ecency.core.app.AppEnvironment
import com.ecency.core.app.AppIcon
import com.ecency.core.app.ErrorKind
import com.ecency.core.app.HiveAuthCopy
import com.ecency.core.app.HiveAuthStep
import com.ecency.core.app.LoginCopy
import com.ecency.core.auth.HiveSigner
import com.ecency.core.auth.HiveSignerCallback
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.ecency.core.app.AuthUiCopy
import com.ecency.core.app.UiCopy

/**
 * The login screen (RN screens/login): username plus master password or private key, HiveSigner in an
 * in-app web view, HiveAuth / Keychain with a QR code and a wallet link. Errors show RN's "Login Failed"
 * alert. Closing returns to the app signed out (browsing stays possible).
 */
@Composable
fun LoginScreen(env: AppEnvironment, host: AppHost, onBack: () -> Unit, onSignedIn: () -> Unit, onSignUp: () -> Unit = { host.openExternal(LoginCopy.SIGNUP_URL) }) {
    val colors = LocalEcencyColors.current
    val state by env.session.state.collectAsState()
    var username by rememberSaveable { mutableStateOf(state.loginPrefill.orEmpty()) }
    var secret by remember { mutableStateOf("") }
    var failure by remember { mutableStateOf<String?>(null) }
    var hiveSigner by remember { mutableStateOf(false) }
    // a HiveAuth sign-in already running (debug launch option) shows its sheet
    var hiveAuth by remember { mutableStateOf(state.hiveAuth != null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { env.session.consumeLoginPrefill() }
    val canSubmit = !state.isSigningIn && com.ecency.core.auth.KeyLogin.formFilled(username, secret)

    Column(Modifier.fillMaxSize().background(colors.background).imePadding().testTag("login")) {
        TopBar(LoginCopy.TITLE, onBack = onBack) {
            // secondary: plain weight text, so the filled LOGIN button stays the one primary action
            TextButton(onClick = onSignUp, modifier = Modifier.testTag("loginJoin")) {
                Text(LoginCopy.JOIN, color = colors.blueText, fontWeight = FontWeight.Normal)
            }
        }
        Column(Modifier.padding(horizontal = 24.dp).verticalScroll(rememberScrollState())) {
            // README "Esie": a wave hello, small so the form stays in view
            EsieView(com.ecency.core.esie.EsiePlacement.LOGIN, Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp))
            Text(LoginCopy.SUBTITLE, fontSize = 15.sp, color = colors.darkGray, modifier = Modifier.padding(bottom = 20.dp))
            // the username after its "@" reads left to right in every language
            LeftToRight {
                OutlinedTextField(
                    value = username, onValueChange = { username = it }, singleLine = true,
                    placeholder = { Text(LoginCopy.USERNAME, color = colors.darkGray) },
                    leadingIcon = { Text("@", color = colors.darkGray, fontWeight = FontWeight.Bold) },
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Email),
                    colors = fieldColors(), modifier = Modifier.fillMaxWidth().testTag("loginUser"),
                )
            }
            OutlinedTextField(
                value = secret, onValueChange = { secret = it }, singleLine = true,
                placeholder = { Text(LoginCopy.PASSWORD, color = colors.darkGray) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                textStyle = ltrFieldStyle(),
                colors = fieldColors(), modifier = Modifier.fillMaxWidth().padding(top = 10.dp).testTag("loginKey"),
            )
            Text(
                LoginCopy.TERMS, fontSize = 12.sp, color = colors.darkGray,
                modifier = Modifier.padding(top = 10.dp).clickable(role = Role.Button) { host.openExternal(LoginCopy.TERMS_URL) }.testTag("loginTerms"),
            )
            Button(
                onClick = {
                    scope.launch {
                        runCatching { env.session.loginWithKey(username, secret) }
                            .onSuccess { secret = ""; onSignedIn() }
                            .onFailure { e -> failure = (e as? com.ecency.core.app.AppException)?.error?.message ?: e.message }
                    }
                },
                enabled = canSubmit,
                colors = ButtonDefaults.buttonColors(contentColor = androidx.compose.ui.graphics.Color.White, containerColor = colors.blue),
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp).heightIn(min = 48.dp).testTag("loginSubmit"),
            ) {
                if (state.isSigningIn) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                else Text(LoginCopy.LOGIN, color = Color.White, fontWeight = FontWeight.Bold)
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                HorizontalDivider(Modifier.weight(1f), color = colors.border)
                Text(LoginCopy.OR, color = colors.darkGray, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp))
                HorizontalDivider(Modifier.weight(1f), color = colors.border)
            }
            OutlinedButton(onClick = { hiveSigner = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("loginHiveSigner")) {
                Text(LoginCopy.HIVESIGNER, color = colors.text)
            }
            OutlinedButton(onClick = { hiveAuth = true }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp).heightIn(min = 48.dp).testTag("loginHiveAuth")) {
                Text(LoginCopy.HIVEAUTH, color = colors.text)
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalArrangement = Arrangement.Center) {
                Text(LoginCopy.NO_ACCOUNT, color = colors.darkGray, fontSize = 14.sp)
                Text(
                    " ${LoginCopy.SIGN_UP}", color = colors.blueText, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable(onClick = onSignUp, role = Role.Button).testTag("loginSignUp"),
                )
            }
        }
    }

    failure?.let { message ->
        AlertDialog(
            onDismissRequest = { failure = null; env.session.clearError() },
            title = { Text(LoginCopy.FAILED_TITLE) },
            text = { Text(" $message\n${LoginCopy.FAILED_BODY}", modifier = Modifier.testTag("loginError")) },
            confirmButton = { TextButton(onClick = { failure = null; env.session.clearError() }) { Text(UiCopy.OK) } },
        )
    }
    if (hiveSigner) {
        HiveSignerLoginDialog(
            onCode = { code ->
                hiveSigner = false
                scope.launch {
                    runCatching { env.session.loginWithHiveSignerCode(code) }
                        .onSuccess { onSignedIn() }
                        .onFailure { e -> failure = (e as? com.ecency.core.app.AppException)?.error?.message ?: e.message }
                }
            },
            onError = { hiveSigner = false; failure = it },
            onDismiss = { hiveSigner = false },
        )
    }
    if (hiveAuth) {
        HiveAuthLoginSheet(env, host, username, onSignedIn = { hiveAuth = false; onSignedIn() }, onDismiss = { hiveAuth = false })
    }
}

@Composable
internal fun fieldColors(): TextFieldColors {
    val colors = LocalEcencyColors.current
    return OutlinedTextFieldDefaults.colors(
        focusedTextColor = colors.text, unfocusedTextColor = colors.text,
        focusedBorderColor = colors.blueText, unfocusedBorderColor = colors.icon,
        focusedLabelColor = colors.blueText, unfocusedLabelColor = colors.darkGray,
    )
}

/** RN hiveSigner.tsx: HiveSigner's authorize page full screen; the redirect's code (or error) ends it. */
@Composable
fun HiveSignerLoginDialog(onCode: (String) -> Unit, onError: (String) -> Unit, onDismiss: () -> Unit) {
    val colors = LocalEcencyColors.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, securePolicy = androidx.compose.ui.window.SecureFlagPolicy.SecureOn)) {
        Column(Modifier.fillMaxSize().background(colors.background).statusBarsPadding().navigationBarsPadding().testTag("hiveSignerLogin")) {
            TopBar(LoginCopy.TITLE, onBack = onDismiss)
            HiveSignerWebView(
                url = HiveSigner.authorizeUrl(),
                onRedirect = { url ->
                    when (val cb = HiveSigner.parseCallback(url)) {
                        is HiveSignerCallback.Code -> onCode(cb.code)
                        is HiveSignerCallback.Error -> onError(cb.description ?: cb.error)
                        else -> onDismiss()
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * RN hiveAuthModal: username, then the wallet request as a QR code plus "Open wallet app" (has:// link), a
 * countdown to the relay's expiry, the result. Closing cancels a waiting request.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HiveAuthLoginSheet(env: AppEnvironment, host: AppHost, initialUsername: String, onSignedIn: () -> Unit, onDismiss: () -> Unit) {
    val colors = LocalEcencyColors.current
    val state by env.session.state.collectAsState()
    var username by rememberSaveable { mutableStateOf(initialUsername) }
    var walletMissing by remember { mutableStateOf(false) }
    val progress = state.hiveAuth
    val close = { env.session.cancelHiveAuth(); onDismiss() }
    LaunchedEffect(progress?.step) {
        if (progress?.step == HiveAuthStep.SUCCESS) {
            delay(1500)
            env.session.cancelHiveAuth()
            onSignedIn()
        }
    }
    // RN opens the wallet by itself once the request waits: once per relay request (model state), so a remount,
    // rotation or relock never brings it up again
    LaunchedEffect(progress?.requestId, progress?.openWallet) {
        val link = progress?.link
        if (progress?.openWallet == true && link != null) {
            env.session.walletOpened()
            walletMissing = !host.openWallet(link)
        }
    }
    ModalBottomSheet(
        onDismissRequest = close,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { true }),
        containerColor = colors.background,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).navigationBarsPadding().padding(bottom = 20.dp).testTag("hiveAuthSheet"),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(HiveAuthCopy.TITLE, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = colors.text, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(16.dp))
            when (progress?.step) {
                null -> {
                    // the username after its "@" reads left to right in every language
                    LeftToRight {
                        OutlinedTextField(
                            value = username, onValueChange = { username = it }, singleLine = true,
                            placeholder = { Text(LoginCopy.USERNAME, color = colors.darkGray) },
                            leadingIcon = { Text("@", color = colors.darkGray, fontWeight = FontWeight.Bold) },
                            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                            colors = fieldColors(), modifier = Modifier.fillMaxWidth().testTag("hiveAuthUser"),
                        )
                    }
                    Button(
                        onClick = { env.session.startHiveAuth(username) },
                        enabled = com.ecency.core.auth.KeyInput.edges(username).length >= 3,
                        colors = ButtonDefaults.buttonColors(contentColor = androidx.compose.ui.graphics.Color.White, containerColor = colors.blue),
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp).heightIn(min = 48.dp).testTag("hiveAuthStart"),
                    ) { Text(LoginCopy.HIVEAUTH, color = Color.White, fontWeight = FontWeight.Bold) }
                }
                HiveAuthStep.WAITING -> {
                    val link = progress.link.orEmpty()
                    Text(HiveAuthCopy.AUTHENTICATING, color = colors.text, fontWeight = FontWeight.Bold)
                    Box(Modifier.padding(vertical = 12.dp).background(Color.White).padding(8.dp)) { QrCode(link, 220.dp) }
                    Text(HiveAuthCopy.SCAN, color = colors.darkGray, fontSize = 13.sp, textAlign = TextAlign.Center)
                    Countdown(progress.expiresAtMillis, env.nowMillis)
                    if (walletMissing) Text(HiveAuthCopy.NOT_INSTALLED, color = colors.redText, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp).testTag("hiveAuthNoWallet"))
                    Button(
                        onClick = { walletMissing = !host.openWallet(link) },
                        colors = ButtonDefaults.buttonColors(contentColor = androidx.compose.ui.graphics.Color.White, containerColor = colors.blue),
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 48.dp).testTag("hiveAuthOpenWallet"),
                    ) { Text(HiveAuthCopy.OPEN_WALLET, color = Color.White, fontWeight = FontWeight.Bold) }
                }
                HiveAuthStep.SUCCESS -> {
                    Glyph(AppIcon.CHECK_CIRCLE, Color(0xFF4CAF50), 56.dp, filled = true)
                    Text(HiveAuthCopy.SUCCESS, color = colors.text, modifier = Modifier.padding(top = 8.dp).testTag("hiveAuthSuccess"))
                }
                HiveAuthStep.FAILED -> {
                    Text(progress.error?.message ?: AuthUiCopy.AUTHENTICATION_FAILED, color = colors.redText, textAlign = TextAlign.Center, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("hiveAuthError"))
                    Button(
                        onClick = { env.session.startHiveAuth(progress.username) },
                        colors = ButtonDefaults.buttonColors(contentColor = androidx.compose.ui.graphics.Color.White, containerColor = colors.blue),
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp).heightIn(min = 48.dp),
                    ) { Text(UiCopy.TRY_AGAIN, color = Color.White) }
                }
                else -> {
                    CircularProgressIndicator(color = colors.blueText)
                    Text(
                        if (progress.step == HiveAuthStep.LOGGING_IN) HiveAuthCopy.LOGGING_IN else HiveAuthCopy.INITIATING,
                        color = colors.darkGray, modifier = Modifier.padding(top = 12.dp).testTag("hiveAuthProgress"),
                    )
                }
            }
            TextButton(onClick = close, modifier = Modifier.padding(top = 8.dp).testTag("hiveAuthCancel")) { Text(UiCopy.CANCEL, color = colors.darkGray) }
        }
    }
}

/** "Expires in m:ss" until [expiresAtMillis]. */
@Composable
internal fun Countdown(expiresAtMillis: Long, nowMillis: () -> Long) {
    val colors = LocalEcencyColors.current
    val left by produceState((expiresAtMillis - nowMillis()).coerceAtLeast(0), expiresAtMillis) {
        while (value > 0) {
            delay(1000)
            value = (expiresAtMillis - nowMillis()).coerceAtLeast(0)
        }
    }
    if (expiresAtMillis <= 0) return
    val seconds = left / 1000
    Text(
        com.ecency.core.app.UiCopy.expiresIn(if (left > 0) maxOf(1L, seconds) else 0L),
        color = colors.darkGray, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp).testTag("hiveAuthExpiry"),
    )
}

/** The user-facing text of an error from a sign-in, unless the user just cancelled. */
internal fun loginMessage(e: Throwable): String? {
    val error = (e as? com.ecency.core.app.AppException)?.error ?: return e.message
    return if (error.kind == ErrorKind.CANCELLED) null else error.message
}

package com.ecency.app.ui

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.ecency.core.app.AccountCopy
import com.ecency.core.app.AppEnvironment
import com.ecency.core.app.AppIcon
import com.ecency.core.app.AuthLinkModel
import com.ecency.core.app.HiveAuthCopy
import com.ecency.core.app.HiveAuthStep
import com.ecency.core.app.LinkNavigation
import com.ecency.core.app.LinkPrompt
import com.ecency.core.app.LoginCopy
import com.ecency.core.app.PostCard
import com.ecency.core.app.SigningPrompt
import com.ecency.core.app.WelcomeCopy
import com.ecency.core.auth.AccountSummary
import com.ecency.core.auth.AuthUpgradeChoice
import com.ecency.core.auth.LoginMethod
import com.ecency.core.hive.KeyRole
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.ecency.core.app.AuthUiCopy
import com.ecency.core.app.UiCopy

/** Debug launch options the root reads once (screenshots). */
object DebugLaunch {
    @Volatile var openAccounts = false

    /** `--ez demoScrollTop true`: the lists' scroll to top button shows at once (recordings; it otherwise needs 5000 dp). */
    @Volatile var demoScrollTop = false

    /** `--es editorSheet dictation|translate|video|aiAssist|aiImage`: the sheet the first editor screen opens with. */
    @Volatile var editorSheet: String? = null

    /** `--es editorText <text>`: the body the first editor screen starts with (the translate sheet needs a few sentences). */
    @Volatile var editorText: String? = null

    /** `--ez openDigest true`: the email digest offer of a first post, once the demo account is signed in. */
    @Volatile var openDigest = false
}

/**
 * The account switcher (RN accountsBottomSheet): every account on the device with its avatar, sign-in method and
 * a check on the current one; tap to switch, the logout icon signs that account out after a confirm. Footer:
 * add an existing account, create one, log out of all accounts. The shell mounts it from its side menu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSwitcherSheet(
    env: AppEnvironment, host: AppHost, onAddAccount: () -> Unit, onDismiss: () -> Unit,
    onCreateAccount: () -> Unit = { host.openExternal(LoginCopy.SIGNUP_URL) },
) {
    val colors = LocalEcencyColors.current
    val state by env.session.state.collectAsState()
    val scope = rememberCoroutineScope()
    var confirmLogout by remember { mutableStateOf<String?>(null) }
    var confirmAll by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.background) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp).testTag("accountSwitcher")) {
            state.accounts.forEach { account ->
                AccountRow(env, account, switching = state.switchingTo == account.username,
                    onSwitch = {
                        if (!account.isCurrent) scope.launch {
                            runCatching { env.session.switchTo(account.username) }
                                .onSuccess { onDismiss() }
                                .onFailure { e -> loginMessage(e)?.let(env.ui::toast) }
                        }
                    },
                    onLogout = { confirmLogout = account.username },
                )
            }
            HorizontalDivider(color = colors.border, modifier = Modifier.padding(vertical = 4.dp))
            FooterRow(AccountCopy.ADD_EXISTING, "accountAdd") { onDismiss(); onAddAccount() }
            FooterRow(AccountCopy.CREATE_NEW, "accountCreate") { onCreateAccount() }
            if (state.accounts.isNotEmpty()) FooterRow(AccountCopy.LOGOUT_ALL, "accountLogoutAll", colors.redText) { confirmAll = true }
        }
    }
    confirmLogout?.let { name ->
        LogoutConfirmDialog(
            title = AccountCopy.LOGOUT_TITLE, message = "@$name",
            onConfirm = {
                confirmLogout = null
                scope.launch { runCatching { env.session.logout(name) }.onFailure { e -> loginMessage(e)?.let(env.ui::toast) } }
            },
            onDismiss = { confirmLogout = null },
        )
    }
    if (confirmAll) {
        LogoutConfirmDialog(
            title = AccountCopy.LOGOUT_ALL_TITLE, message = null,
            onConfirm = {
                confirmAll = false
                scope.launch { runCatching { env.session.logoutAll() }.onSuccess { onDismiss() } }
            },
            onDismiss = { confirmAll = false },
        )
    }
}

@Composable
private fun AccountRow(env: AppEnvironment, account: AccountSummary, switching: Boolean, onSwitch: () -> Unit, onLogout: () -> Unit) {
    val colors = LocalEcencyColors.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onSwitch, role = Role.Button).padding(horizontal = 20.dp, vertical = 10.dp).testTag("account:${account.username}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(PostCard.avatarUrl(account.username, env.proxyBase), 40.dp)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text("@${account.username}", color = colors.text, fontWeight = FontWeight.Bold)
            Text(if (switching) AccountCopy.SWITCHING else AccountCopy.method(account.method), color = colors.darkGray, fontSize = 12.sp)
        }
        if (account.isCurrent) Glyph(AppIcon.CHECK_CIRCLE, colors.blueText, 22.dp, Modifier.testTag("currentMark"), description = AuthUiCopy.CURRENT_ACCOUNT, filled = true)
        Box(Modifier.clickable(onClick = onLogout, role = Role.Button).padding(10.dp).testTag("logout:${account.username}")) {
            Glyph(AppIcon.LOGOUT, colors.darkGray, 20.dp, description = "${AccountCopy.LOGOUT} ${com.ecency.core.l10n.Bidi.inUi("@" + account.username)}")
        }
    }
}

@Composable
private fun FooterRow(text: String, tag: String, color: Color = LocalEcencyColors.current.blueText, onClick: () -> Unit) {
    Text(
        text, color = color, fontWeight = FontWeight.Bold, fontSize = 14.sp,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick, role = Role.Button).padding(horizontal = 20.dp, vertical = 14.dp).testTag(tag),
    )
}

/** RN side_menu.logout_text with Cancel / Logout. */
@Composable
fun LogoutConfirmDialog(title: String, message: String?, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = message?.let { { Text(it) } },
        confirmButton = { TextButton(onClick = onConfirm, modifier = Modifier.testTag("logoutConfirm")) { Text(AccountCopy.LOGOUT) } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("logoutCancel")) { Text(AccountCopy.CANCEL) } },
    )
}

/**
 * Everything the auth layer may put on screen, whatever screen is showing: signing prompts (auth upgrade,
 * HiveSigner page, HiveAuth wallet), link prompts and their callbacks, the expired-session alert. Mounted once
 * at the root, above the back stack.
 */
@Composable
fun AuthOverlays(env: AppEnvironment, host: AppHost, onOpenLogin: () -> Unit, onOpenSignup: (String) -> Unit = {}, onOpenScreen: (com.ecency.core.app.AppScreen) -> Unit = {}) {
    SigningPrompts(env, host)
    LinkPrompts(env, host, onOpenLogin, onOpenSignup, onOpenScreen)
    val session by env.session.state.collectAsState()
    session.expiredSession?.let { name ->
        AlertDialog(
            onDismissRequest = env.session::dismissExpiredSession,
            title = { Text(AccountCopy.WARNING) },
            text = { Text(AccountCopy.AUTH_EXPIRED, modifier = Modifier.testTag("sessionExpired")) },
            confirmButton = { TextButton(onClick = { env.session.requestLogin(name); onOpenLogin() }) { Text(AccountCopy.VERIFY) } },
            dismissButton = { TextButton(onClick = env.session::dismissExpiredSession) { Text(AccountCopy.CANCEL, color = LocalEcencyColors.current.redText) } },
        )
    }
}

@Composable
private fun SigningPrompts(env: AppEnvironment, host: AppHost) {
    val state by env.signing.state.collectAsState()
    when (val prompt = state.prompt) {
        is SigningPrompt.AuthUpgrade -> AuthUpgradeSheet(env, prompt)
        is SigningPrompt.HiveSignerHot -> HiveSignerSignDialog(env, prompt)
        is SigningPrompt.HiveAuthBroadcast -> HiveAuthBroadcastSheet(env, host, prompt)
        null -> Unit
    }
}

/** RN authUpgradeSheet: the active key (or master password) for one signature, or HiveSigner / HiveAuth. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthUpgradeSheet(env: AppEnvironment, prompt: SigningPrompt.AuthUpgrade) {
    val colors = LocalEcencyColors.current
    var key by remember(prompt.username, prompt.authority) { mutableStateOf("") }
    ModalBottomSheet(
        onDismissRequest = env.signing::cancel,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.background,
        // the key typed here never ends up in a screenshot or a recording
        properties = androidx.compose.material3.ModalBottomSheetProperties(securePolicy = androidx.compose.ui.window.SecureFlagPolicy.SecureOn),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).imePadding().navigationBarsPadding().padding(bottom = 16.dp).testTag("authUpgrade")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Glyph(AppIcon.KEY, colors.blueText, 28.dp)
                Text(AccountCopy.UPGRADE_TITLE, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = colors.text, modifier = Modifier.semantics { heading() }.padding(start = 10.dp))
            }
            Text(AccountCopy.upgradeDescription(prompt.authority), color = colors.darkGray, modifier = Modifier.padding(vertical = 10.dp))
            if (prompt.keyEntry) {
                OutlinedTextField(
                    value = key, onValueChange = { key = it }, singleLine = true,
                    placeholder = { Text(AccountCopy.UPGRADE_KEY_PLACEHOLDER, color = colors.darkGray, fontSize = 13.sp) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    textStyle = ltrFieldStyle(),
                    isError = prompt.error != null, colors = fieldColors(),
                    modifier = Modifier.fillMaxWidth().testTag("upgradeKey"),
                )
                prompt.error?.let { Text(it, color = colors.redText, fontSize = 13.sp, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.padding(top = 6.dp).testTag("upgradeError")) }
                Button(
                    onClick = { env.signing.submitUpgradeKey(key) },
                    enabled = key.isNotBlank() && !prompt.validating,
                    colors = ButtonDefaults.buttonColors(contentColor = androidx.compose.ui.graphics.Color.White, containerColor = colors.blue),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 48.dp).testTag("upgradeSign"),
                ) {
                    if (prompt.validating) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                    else Text(AccountCopy.UPGRADE_SIGN, color = Color.White, fontWeight = FontWeight.Bold)
                }
                Text(AccountCopy.UPGRADE_OR, color = colors.darkGray, modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 8.dp))
            }
            OutlinedButton(onClick = { env.signing.chooseUpgrade(AuthUpgradeChoice.HIVESIGNER) }, modifier = Modifier.fillMaxWidth().testTag("upgradeHiveSigner")) {
                Text(AccountCopy.UPGRADE_HIVESIGNER, color = colors.text)
            }
            OutlinedButton(onClick = { env.signing.chooseUpgrade(AuthUpgradeChoice.HIVEAUTH) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("upgradeHiveAuth")) {
                Text(AccountCopy.UPGRADE_HIVEAUTH, color = colors.text)
            }
            TextButton(onClick = env.signing::cancel, modifier = Modifier.align(Alignment.CenterHorizontally).testTag("upgradeCancel")) {
                Text(AccountCopy.CANCEL, color = colors.darkGray)
            }
        }
    }
}

/** RN hiveSignerModal: HiveSigner's signing page; the redirect with the transaction id ends it. */
@Composable
private fun HiveSignerSignDialog(env: AppEnvironment, prompt: SigningPrompt.HiveSignerHot) {
    val colors = LocalEcencyColors.current
    Dialog(onDismissRequest = env.signing::cancel, properties = DialogProperties(usePlatformDefaultWidth = false, securePolicy = androidx.compose.ui.window.SecureFlagPolicy.SecureOn)) {
        Column(Modifier.fillMaxSize().background(colors.background).statusBarsPadding().navigationBarsPadding().testTag("hiveSignerSign")) {
            TopBar(AccountCopy.CONFIRM_TX, onBack = env.signing::cancel)
            HiveSignerWebView(prompt.url, onRedirect = { if (!env.signing.hotSignNavigation(it)) env.signing.cancel() }, modifier = Modifier.fillMaxSize())
        }
    }
}

/** RN hiveAuthBroadcastSheet: waits for the wallet to approve; brings the wallet up by itself once. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HiveAuthBroadcastSheet(env: AppEnvironment, host: AppHost, prompt: SigningPrompt.HiveAuthBroadcast) {
    val colors = LocalEcencyColors.current
    var walletMissing by remember { mutableStateOf(false) }
    // once per relay request (model state): a remount, rotation or relock never brings the wallet up again
    LaunchedEffect(prompt.requestId, prompt.openWallet) {
        val link = prompt.link
        if (prompt.openWallet && link != null) {
            env.signing.walletOpened()
            walletMissing = !host.openWallet(link)
        }
    }
    ModalBottomSheet(onDismissRequest = env.signing::cancel, containerColor = colors.background) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp).navigationBarsPadding().testTag("hiveAuthBroadcast"),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(HiveAuthCopy.TITLE, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = colors.text, modifier = Modifier.semantics { heading() })
            CircularProgressIndicator(color = colors.blueText, modifier = Modifier.padding(16.dp))
            Text(if (prompt.step == HiveAuthStep.WAITING) AuthUiCopy.APPROVE_THE_REQUEST_IN else HiveAuthCopy.INITIATING, color = colors.darkGray)
            Countdown(prompt.expiresAtMillis, env.nowMillis)
            if (walletMissing) Text(HiveAuthCopy.NOT_INSTALLED, color = colors.redText, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            prompt.link?.let { link ->
                Button(
                    onClick = { walletMissing = !host.openWallet(link) },
                    colors = ButtonDefaults.buttonColors(contentColor = androidx.compose.ui.graphics.Color.White, containerColor = colors.blue),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                ) { Text(HiveAuthCopy.OPEN_WALLET, color = Color.White) }
            }
            TextButton(onClick = env.signing::cancel) { Text(AccountCopy.CANCEL, color = colors.darkGray) }
        }
    }
}

@Composable
private fun LinkPrompts(env: AppEnvironment, host: AppHost, onOpenLogin: () -> Unit, onOpenSignup: (String) -> Unit, onOpenScreen: (com.ecency.core.app.AppScreen) -> Unit) {
    val state by env.authLinks.state.collectAsState()
    val links = env.authLinks
    LaunchedEffect(state.callback) { state.callback?.let { host.openCallback(it); links.consumeCallback() } }
    LaunchedEffect(state.notice) { state.notice?.let { env.ui.toast(it); links.consumeNotice() } } // the one toast channel
    LaunchedEffect(state.navigation) {
        when (val nav = state.navigation) {
            is LinkNavigation.Login -> { env.session.requestLogin(nav.username); onOpenLogin() }
            is LinkNavigation.Signup -> onOpenSignup(nav.referral)
            is LinkNavigation.Screen -> onOpenScreen(nav.screen)
            null -> return@LaunchedEffect
        }
        links.consumeNavigation()
    }
    when (val prompt = state.prompt) {
        is LinkPrompt.SignConfirm -> SignConfirmSheet(prompt, onApprove = links::confirm, onCancel = links::decline)
        is LinkPrompt.Refused -> AlertDialog(
            onDismissRequest = links::dismiss,
            title = { Text(prompt.title) },
            text = { Text(prompt.message, modifier = Modifier.testTag("linkRefused")) },
            confirmButton = { TextButton(onClick = links::dismiss) { Text(UiCopy.CLOSE) } },
        )
        is LinkPrompt.PostingKeyRequest -> ConfirmAlert(AccountCopy.postingKeyRequest(prompt.requester, prompt.username), "postingKeyRequest", links)
        is LinkPrompt.AuthRequest -> ConfirmAlert(AccountCopy.authRequest(prompt.requester, prompt.username), "authRequest", links)
        is LinkPrompt.AuthTransfer -> ConfirmAlert(
            AccountCopy.authTransfer(prompt.username) + AccountCopy.authTransferStored(prompt.username, prompt.storedMethod.takeIf { prompt.accountStored }),
            "authTransfer", links,
        )
        is LinkPrompt.CodeLogin -> ConfirmAlert(AuthUiCopy.VERIFY_DIRECT_LOGIN, "codeLogin", links, title = AuthUiCopy.easyLogin(prompt.username))
        null -> Unit
    }
}

/** RN's confirm alerts: not cancelable by tapping outside, Cancel / Approve. */
@Composable
private fun ConfirmAlert(message: String, tag: String, links: AuthLinkModel, title: String = AccountCopy.CONFIRM) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = true),
        title = { Text(title) },
        text = { Text(message, modifier = Modifier.testTag(tag)) },
        confirmButton = { TextButton(onClick = links::confirm, modifier = Modifier.testTag("linkApprove")) { Text(AccountCopy.APPROVE) } },
        dismissButton = { TextButton(onClick = links::decline, modifier = Modifier.testTag("linkCancel")) { Text(AccountCopy.CANCEL) } },
    )
}

/** RN qr.confirmTransaction: the operation, its fields and the key it uses. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignConfirmSheet(prompt: LinkPrompt.SignConfirm, onApprove: () -> Unit, onCancel: () -> Unit) {
    val colors = LocalEcencyColors.current
    ModalBottomSheet(onDismissRequest = onCancel, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.background) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).navigationBarsPadding().padding(bottom = 16.dp).testTag("signConfirm")) {
            Text(AccountCopy.CONFIRM_TX, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = colors.text, modifier = Modifier.semantics { heading() })
            Text(AuthLinkModel.signWarning(prompt.authority), color = colors.darkGray, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
            Text(prompt.title, fontWeight = FontWeight.Bold, color = colors.text)
            Row(Modifier.padding(top = 4.dp)) {
                Text(AuthUiCopy.accountLabel(prompt.username), color = colors.darkGray, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text(prompt.authority.uppercase(), color = colors.blueText, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.testTag("signAuthority"))
            }
            prompt.requester?.let { Text(AuthUiCopy.requestedBy(it), color = colors.darkGray, fontSize = 12.sp) }
            Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
                prompt.fields.forEach { f ->
                    Row(Modifier.padding(vertical = 2.dp)) {
                        Text(f.name, color = colors.darkGray, fontSize = 13.sp, modifier = Modifier.weight(0.4f))
                        Text(f.value, color = colors.text, fontSize = 13.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(0.6f))
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f).testTag("signCancel")) { Text(AccountCopy.CANCEL, color = colors.text) }
                Button(onClick = onApprove, colors = ButtonDefaults.buttonColors(contentColor = androidx.compose.ui.graphics.Color.White, containerColor = colors.blue), modifier = Modifier.weight(1f).testTag("signApprove")) {
                    Text(AccountCopy.APPROVE, color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/** Esie on the welcome screen only below large text sizes (README "Esie"). */
fun welcomeShowsEsie(fontScale: Float): Boolean = fontScale < 1.5f

/** RN welcome screen: three lines, the terms checkbox, "Get started!" only once accepted. */
@Composable
fun WelcomeScreen(host: AppHost, onAccepted: () -> Unit) {
    val colors = LocalEcencyColors.current
    var accepted by remember { mutableStateOf(host.termsPreviouslyAccepted()) }
    Column(Modifier.fillMaxSize().background(colors.background).statusBarsPadding().navigationBarsPadding().padding(24.dp).testTag("welcome")) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
                // where RN's welcome mascot (assets/love_mascot.png) stood: Esie waves a few times, then rests (README "Esie");
                // at most 40 % of the width, so she never covers "Ecency" on narrow phones
                val welcome = com.ecency.core.esie.EsiePlacement.WELCOME
                val fit = welcome.height.dp * (maxWidth * 0.4f / esieWidth(welcome.mood, welcome.height.dp))
                // at large text sizes (font scale 1.5 and up) the title needs the whole width: Esie stays out
                if (welcomeShowsEsie(androidx.compose.ui.platform.LocalDensity.current.fontScale)) {
                    EsieView(
                        welcome,
                        Modifier.align(Alignment.TopEnd).padding(end = 8.dp, top = 16.dp),
                        height = minOf(welcome.height.dp, fit),
                    )
                }
                Column(Modifier.padding(top = 40.dp)) {
                    Text(WelcomeCopy.LABEL, color = colors.darkGray, fontSize = 18.sp)
                    Text(WelcomeCopy.TITLE, color = colors.blueText, fontSize = 34.sp, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                }
            }
            Spacer(Modifier.height(24.dp))
            WelcomeCopy.LINES.forEach { (heading, body) ->
                Text(heading, color = colors.text, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(top = 16.dp))
                Text(body, color = colors.darkGray, fontSize = 14.sp)
            }
        }
        Row(
            Modifier.toggleable(value = accepted, role = androidx.compose.ui.semantics.Role.Checkbox, onValueChange = { accepted = it }).testTag("welcomeTerms"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.Checkbox(checked = accepted, onCheckedChange = null)
            Column {
                Text(WelcomeCopy.TERMS, color = colors.text, fontSize = 13.sp)
                Text(WelcomeCopy.READ, color = colors.blueText, fontSize = 13.sp, modifier = Modifier.clickable(role = Role.Button) { host.openExternal(LoginCopy.TERMS_URL) })
            }
        }
        Button(
            onClick = { host.setTermsAccepted(); onAccepted() },
            enabled = accepted,
            colors = ButtonDefaults.buttonColors(contentColor = androidx.compose.ui.graphics.Color.White, containerColor = colors.blue),
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp).heightIn(min = 48.dp).testTag("welcomeStart"),
        ) { Text(WelcomeCopy.GET_STARTED, color = Color.White, fontWeight = FontWeight.Bold) }
    }
}

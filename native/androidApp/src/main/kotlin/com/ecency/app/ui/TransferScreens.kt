package com.ecency.app.ui

import androidx.compose.foundation.layout.heightIn

import androidx.compose.ui.semantics.heading

import androidx.compose.ui.semantics.semantics


import androidx.compose.ui.semantics.Role

import com.ecency.core.app.AppIcon
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.IconButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ecency.core.app.AppEnvironment
import com.ecency.core.app.AppScreen
import com.ecency.core.app.DelegateModel
import com.ecency.core.app.ManageAssetsModel
import com.ecency.core.app.PowerDownModel
import com.ecency.core.app.Recurrence
import com.ecency.core.app.RedeemCopy
import com.ecency.core.app.RedeemKind
import com.ecency.core.app.RedeemModel
import com.ecency.core.app.StakeCopy
import com.ecency.core.app.StakePrompt
import com.ecency.core.app.TipCopy
import com.ecency.core.app.TipModel
import com.ecency.core.app.TradeCopy
import com.ecency.core.app.TradeModel
import com.ecency.core.app.TradePrompt
import com.ecency.core.app.TransferCopy
import com.ecency.core.app.TransferModel
import com.ecency.core.app.TransferPrompt
import com.ecency.core.app.TransferView
import com.ecency.core.app.WalletCopy
import com.ecency.core.app.UiCopy
import com.ecency.core.app.WalletUiCopy

/** RN TransferScreen: the view its type asks for (transfer form, delegation, power down and routes, receive address). */
@Composable
fun TransferRoute(env: AppEnvironment, host: AppHost, entry: StackEntry, screen: AppScreen.Transfer, onBack: () -> Unit) {
    when (TransferView.of(screen)) {
        TransferView.TRANSFER -> Guarded({ entry.get("transfer") { env.transferModel(screen)!! } }) { TransferForm(env, it, onBack) }
        TransferView.DELEGATE -> Guarded({ entry.get("delegate") { env.delegateModel(screen.recipient) } }) { DelegateForm(it, onBack) }
        TransferView.POWER_DOWN -> Guarded({ entry.get("powerDown") { env.powerDownModel(routesOnly = false) } }) { PowerDownForm(it, onBack) }
        TransferView.ROUTES -> Guarded({ entry.get("routes") { env.powerDownModel(routesOnly = true) } }) { PowerDownForm(it, onBack) }
        TransferView.RECEIVE -> ReceiveAddress(env, host, screen, onBack)
        TransferView.UNSUPPORTED -> Column(Modifier.fillMaxSize().statusBarsPadding()) {
            TopBar(WalletCopy.label(screen.transferType), onBack = onBack)
            ComingSoon(WalletCopy.label(screen.transferType))
        }
    }
}

@Composable
private fun Field(
    label: String, value: String, onChange: (String) -> Unit, tag: String, enabled: Boolean = true, number: Boolean = false, placeholder: String = "",
    ltr: Boolean = number,
) {
    // amounts, usernames and permlinks are typed left to right in a right to left UI too; a memo follows its text
    OutlinedTextField(
        value = value, onValueChange = onChange, enabled = enabled, singleLine = true, label = { Text(label) },
        placeholder = { Text(placeholder) },
        textStyle = if (ltr) ltrFieldStyle() else androidx.compose.material3.LocalTextStyle.current,
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag(tag),
    )
}

@Composable
private fun Suggestions(names: List<String>, onPick: (String) -> Unit) {
    val colors = LocalEcencyColors.current
    names.forEach { n ->
        Text("@$n", color = colors.blueText, modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { onPick(n) }.padding(8.dp).testTag("suggestion:$n"))
    }
}

@Composable
private fun Warning(text: String, tag: String, red: Boolean = true) {
    val colors = LocalEcencyColors.current
    Text(text, color = if (red) colors.redText else colors.darkGray, fontSize = 13.sp, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag(tag))
}

@Composable
private fun PrimaryButton(label: String, enabled: Boolean, tag: String, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled, colors = ButtonDefaults.buttonColors(contentColor = androidx.compose.ui.graphics.Color.White, containerColor = LocalEcencyColors.current.blue),
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 48.dp).testTag(tag),
    ) { Text(label, color = Color.White, fontWeight = FontWeight.Bold) }
}

/** RN TransferView: recipient, amount with balance and MAX, memo, schedule, the checks and the confirmation. */
@Composable
fun TransferForm(env: AppEnvironment, model: TransferModel, onBack: () -> Unit) {
    val colors = LocalEcencyColors.current
    val s by model.state.collectAsState()
    LaunchedEffect(model) { model.start() }
    LaunchedEffect(s.closed) { if (s.closed) onBack() }
    val back = leaveGuard(s.busy, onBack)
    var favorites by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    if (scanning) {
        val qr = remember { env.qrScanModel(com.ecency.core.app.QrPurpose.VALUE) }
        androidx.compose.runtime.DisposableEffect(qr) { onDispose { qr.close() } }
        val qrState by qr.state.collectAsState()
        LaunchedEffect(qrState.outcome) {
            (qrState.outcome as? com.ecency.core.app.QrOutcome.Value)?.let { model.scanned(it.text); qr.consumeOutcome(); scanning = false }
        }
        Dialog(
            onDismissRequest = { scanning = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            Box(Modifier.fillMaxSize().background(Color.Black).testTag("transferQr")) {
                QrScanner(qr, Modifier.fillMaxSize(), onDenied = { scanning = false })
                Box(
                    Modifier.statusBarsPadding().padding(8.dp).size(44.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Color.Black.copy(alpha = 0.4f))
                        .clickable(role = Role.Button) { scanning = false },
                    contentAlignment = Alignment.Center,
                ) { Glyph(AppIcon.CLOSE, Color.White, 24.dp, description = UiCopy.CLOSE) }
            }
        }
    }
    if (favorites) {
        val fm = remember { env.transferFavoritesModel() }
        androidx.compose.runtime.DisposableEffect(fm) { onDispose { fm.close() } }
        FavoritesSheet(fm, onPick = { model.setRecipient(it); favorites = false }, onDismiss = { favorites = false })
    }
    Column(Modifier.fillMaxSize().background(colors.background).statusBarsPadding().imePadding().testTag("transferForm")) {
        TopBar(s.kind.title, onBack = back)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text("${TransferCopy.FROM}: ${com.ecency.core.l10n.Bidi.inUi("@" + s.from.orEmpty())}", color = colors.darkGray, fontSize = 13.sp)
            if (!s.recipientLocked) {
                Field(TransferCopy.TO, s.recipient, model::setRecipient, "transferTo", enabled = !s.busy, placeholder = TransferCopy.TO_PLACEHOLDER, ltr = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { favorites = true }, enabled = !s.busy, modifier = Modifier.testTag("transferFavorites")) { Text(WalletCopy.PICK_FAVORITE, color = colors.blueText, fontSize = 13.sp) }
                    // RN _openQrScanner: a code's account becomes the recipient (TransferRecipientParser)
                    Box(
                        Modifier.clip(androidx.compose.foundation.shape.CircleShape).clickable(enabled = !s.busy, role = Role.Button) { scanning = true }.padding(8.dp).testTag("transferScan"),
                    ) { Glyph(AppIcon.QR_SCAN, colors.icon, 20.dp, description = WalletUiCopy.SCAN_QR_RECIPIENT) }
                }
                Suggestions(s.suggestions, model::pickSuggestion)
            }
            if (s.badActor) Warning(TransferCopy.BAD_ACTOR, "badActor")
            if (s.tooManyRecipients) Warning(TransferCopy.ONE_RECIPIENT, "oneRecipient")
            if (s.tokenChoices.isNotEmpty()) {
                Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    s.tokenChoices.forEach { t ->
                        Text(
                            t, color = if (t == s.fund) Color.White else colors.text, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(if (t == s.fund) colors.blue else colors.grayBackground)
                                .clickable(role = Role.Button) { model.setFund(t) }.padding(horizontal = 14.dp, vertical = 6.dp).testTag("fund:$t"),
                        )
                    }
                }
            }
            Field("${TransferCopy.AMOUNT} (${com.ecency.core.l10n.Bidi.inUi(s.displayFund)})", s.amount, model::setAmount, "transferAmount", enabled = !s.busy, number = true)
            Row(verticalAlignment = Alignment.CenterVertically) {
                val balance = s.balance
                Text(
                    if (s.balanceFailed) TransferCopy.BALANCE_FAILED else "${TransferCopy.BALANCE} ${com.ecency.core.l10n.Bidi.inUi("${balance ?: "..."} ${s.displayFund}")}",
                    color = if (s.balanceFailed) colors.redText else colors.darkGray, fontSize = 13.sp,
                    modifier = Modifier.weight(1f).clickable(enabled = s.balanceFailed, role = Role.Button) { model.retryBalance() }.testTag("transferBalance"),
                )
                TextButton(onClick = model::max, enabled = balance != null && !s.busy, modifier = Modifier.testTag("transferMax")) { Text(TransferCopy.MAX, color = colors.blueText) }
            }
            if (s.precisionFailed) Warning(TransferCopy.PRECISION_FAILED, "precisionFailed")
            if (s.canSchedule) {
                Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (listOf<Recurrence?>(null) + Recurrence.entries).forEach { r ->
                        val selected = s.recurrenceHours == r?.hours
                        Text(
                            r?.label ?: TransferCopy.ONE_TIME, fontSize = 12.sp, color = if (selected) Color.White else colors.text,
                            modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(if (selected) colors.blue else colors.grayBackground)
                                .clickable(role = Role.Button) { model.setRecurrence(r?.hours) }.padding(horizontal = 10.dp, vertical = 6.dp).testTag("schedule:${r?.name ?: "ONE_TIME"}"),
                        )
                    }
                }
                s.recurrenceHours?.takeIf { h -> Recurrence.entries.none { it.hours == h } }?.let { Text(TransferCopy.customSchedule(it), color = colors.darkGray, fontSize = 12.sp) }
                if (s.isRecurrent) Field(TransferCopy.EXECUTIONS, s.executions, model::setExecutions, "transferExecutions", enabled = !s.busy, number = true, placeholder = TransferCopy.EXECUTIONS_PLACEHOLDER)
                s.existingScheduleDate?.let { d ->
                    Text(TransferCopy.DELETE_RECURRENT + d.take(10), color = colors.redText, fontSize = 13.sp, modifier = Modifier.clickable(role = Role.Button) { model.deleteSchedule() }.padding(vertical = 6.dp).testTag("deleteSchedule"))
                }
            }
            if (s.showMemo) {
                Field(TransferCopy.MEMO, s.memo, model::setMemo, "transferMemo", enabled = !s.busy, placeholder = TransferCopy.MEMO_PLACEHOLDER)
                Text(TransferCopy.MEMO_DESC, color = colors.darkGray, fontSize = 12.sp)
            }
            if (s.kind == com.ecency.core.app.TransferKind.CONVERT) Warning(TransferCopy.CONVERT_DESC, "convertDesc", red = false)
            s.exchange?.let { ex ->
                if (s.exchangeMemoRequired) Warning(TransferCopy.exchangeMemoRequired(ex), "exchangeMemo")
                if (s.exchangeRecurrentWarning) Warning(TransferCopy.exchangeRecurrentWarning(ex), "exchangeRecurrent")
            }
            SendPhasePanel(s.phase, onClose = onBack)
            if (!s.busy && s.phase !is com.ecency.core.app.SendPhase.Unconfirmed) PrimaryButton(TransferCopy.NEXT, s.nextEnabled, "transferNext", model::next)
        }
    }
    when (val p = s.prompt) {
        TransferPrompt.Confirm -> AlertDialog(
            onDismissRequest = model::dismissPrompt,
            text = { Text(TransferCopy.INFORMATION) },
            confirmButton = { TextButton(onClick = model::confirm, modifier = Modifier.testTag("transferConfirm")) { Text(WalletCopy.CONFIRM) } },
            dismissButton = { TextButton(onClick = model::dismissPrompt) { Text(WalletCopy.CANCEL) } },
        )
        TransferPrompt.ConfirmDeleteSchedule -> AlertDialog(
            onDismissRequest = model::dismissPrompt,
            text = { Text(TransferCopy.DELETE_RECURRENT.trim() + "?") },
            confirmButton = { TextButton(onClick = model::confirmDeleteSchedule, modifier = Modifier.testTag("deleteScheduleConfirm")) { Text(WalletCopy.CONFIRM) } },
            dismissButton = { TextButton(onClick = model::dismissPrompt) { Text(WalletCopy.CANCEL) } },
        )
        is TransferPrompt.Alert -> AlertDialog(
            onDismissRequest = model::dismissPrompt,
            title = { Text(p.title) },
            text = { if (p.message.isNotEmpty()) Text(p.message) },
            confirmButton = { TextButton(onClick = model::dismissPrompt, modifier = Modifier.testTag("transferAlertOk")) { Text(UiCopy.OK) } },
        )
        null -> Unit
    }
}

@Composable
private fun StakeAlert(prompt: StakePrompt?, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    when (prompt) {
        is StakePrompt.Alert -> AlertDialog(onDismissRequest = onDismiss, title = { Text(prompt.title) }, text = { Text(prompt.message) }, confirmButton = { TextButton(onClick = onDismiss) { Text(UiCopy.OK) } })
        is StakePrompt.Review -> AlertDialog(
            onDismissRequest = onDismiss, title = { Text(StakeCopy.CONFIRM_TITLE) },
            text = { Column { Text(prompt.summary); prompt.overwrite?.let { Text(it, modifier = Modifier.padding(top = 8.dp)) } } },
            confirmButton = { TextButton(onClick = onConfirm, modifier = Modifier.testTag("stakeConfirm")) { Text(WalletCopy.CONFIRM) } },
            dismissButton = { TextButton(onClick = onDismiss) { Text(WalletCopy.CANCEL) } },
        )
        null -> Unit
        else -> AlertDialog(
            onDismissRequest = onDismiss,
            text = {
                Text(
                    when (prompt) {
                        StakePrompt.ConfirmStop -> StakeCopy.STOP_INFORMATION
                        is StakePrompt.ConfirmRemoveRoute -> "${StakeCopy.WITHDRAW_ACCOUNTS}: ${com.ecency.core.l10n.Bidi.inUi("@" + prompt.account)}?"
                        else -> TransferCopy.INFORMATION
                    },
                )
            },
            confirmButton = { TextButton(onClick = onConfirm, modifier = Modifier.testTag("stakeConfirm")) { Text(WalletCopy.CONFIRM) } },
            dismissButton = { TextButton(onClick = onDismiss) { Text(WalletCopy.CANCEL) } },
        )
    }
}

/** RN delegateScreen. */
@Composable
fun DelegateForm(model: DelegateModel, onBack: () -> Unit) {
    val colors = LocalEcencyColors.current
    val s by model.state.collectAsState()
    LaunchedEffect(model) { model.start() }
    LaunchedEffect(s.closed) { if (s.closed) onBack() }
    val back = leaveGuard(s.busy, onBack)
    Column(Modifier.fillMaxSize().background(colors.background).statusBarsPadding().imePadding().testTag("delegateForm")) {
        TopBar(StakeCopy.DELEGATE, onBack = back)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Field(TransferCopy.TO, s.recipient, model::setRecipient, "delegateTo", enabled = !s.busy, placeholder = TransferCopy.TO_PLACEHOLDER, ltr = true)
            Suggestions(s.suggestions, model::pickSuggestion)
            if (s.badActor) Warning(TransferCopy.BAD_ACTOR, "badActor")
            s.existingHp?.let { Text(StakeCopy.alreadyDelegatedTo(s.recipient, it), color = colors.darkGray, fontSize = 13.sp, modifier = Modifier.testTag("alreadyDelegated")) }
            Field(StakeCopy.AMOUNT_HP, s.hp, model::setHp, "delegateHp", enabled = !s.busy, number = true)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${StakeCopy.REMAIN_HP}: ${com.ecency.core.l10n.Bidi.inUi(s.remainingHp)}", color = colors.darkGray, fontSize = 13.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = model::max, enabled = !s.busy, modifier = Modifier.testTag("delegateMax")) { Text(TransferCopy.MAX, color = colors.blueText) }
            }
            Slider(value = ((s.hp.toDoubleOrNull() ?: 0.0) / s.maxHp.coerceAtLeast(0.001)).toFloat().coerceIn(0f, 1f), onValueChange = { model.setFraction(it.toDouble()) }, enabled = !s.busy)
            SendPhasePanel(s.phase, onClose = onBack)
            if (!s.busy) PrimaryButton(StakeCopy.REVIEW, s.reviewEnabled, "delegateReview", model::review)
        }
    }
    StakeAlert(s.prompt, onConfirm = model::confirm, onDismiss = model::dismissPrompt)
}

/** RN powerDownScreen and its withdraw routes. */
@Composable
fun PowerDownForm(model: PowerDownModel, onBack: () -> Unit) {
    val colors = LocalEcencyColors.current
    val s by model.state.collectAsState()
    LaunchedEffect(model) { model.start() }
    LaunchedEffect(s.closed) { if (s.closed) onBack() }
    val back = leaveGuard(s.busy, onBack)
    var routeName by remember { mutableStateOf("") }
    var routePercent by remember { mutableStateOf("25") }
    var autoVest by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(colors.background).statusBarsPadding().imePadding().testTag("powerDownForm")) {
        TopBar(if (s.routesOnly) StakeCopy.WITHDRAW_ACCOUNTS else StakeCopy.POWER_DOWN, onBack = back)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            if (!s.routesOnly) {
                if (s.poweringDown) {
                    Text(StakeCopy.POWERING_DOWN, color = colors.text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(StakeCopy.POWERING_DOWN_SUB, color = colors.darkGray, fontSize = 13.sp)
                    s.nextInfo?.let { Text(it, color = colors.text, fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp).testTag("nextPowerDown")) }
                    if (!s.busy) Button(onClick = model::stop, colors = ButtonDefaults.buttonColors(contentColor = androidx.compose.ui.graphics.Color.White, containerColor = colors.red), modifier = Modifier.testTag("powerDownStop")) {
                        Text(StakeCopy.STOP, color = Color.White)
                    }
                } else if (s.loaded) {
                    Field(StakeCopy.AMOUNT_HP, s.hp, model::setHp, "powerDownHp", enabled = !s.busy, number = true)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${StakeCopy.ESTIMATED_WEEKLY}: ${StakeCopy.weekly(s.weekly)}", color = colors.darkGray, fontSize = 13.sp, modifier = Modifier.weight(1f).testTag("weekly"))
                        TextButton(onClick = model::max, enabled = !s.busy) { Text(TransferCopy.MAX, color = colors.blueText) }
                    }
                    Slider(value = ((s.hp.toDoubleOrNull() ?: 0.0) / s.maxHp.coerceAtLeast(0.001)).toFloat().coerceIn(0f, 1f), onValueChange = { model.setFraction(it.toDouble()) }, enabled = !s.busy)
                    if (!s.busy) PrimaryButton(TransferCopy.NEXT, s.amountValid, "powerDownNext", model::next)
                } else {
                    LoadingBlock()
                }
                SendPhasePanel(s.phase, onClose = onBack)
            }
            if (s.showRoutes) {
                Text(StakeCopy.WITHDRAW_ACCOUNTS, color = colors.text, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))
                s.routes.forEach { r ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag("route:${r.toAccount}"), verticalAlignment = Alignment.CenterVertically) {
                        Text("@${r.toAccount}", color = colors.text, modifier = Modifier.weight(1f))
                        Text(com.ecency.core.l10n.LocaleFormat.percent(r.percent / 100.0, 1, 1) + if (r.autoVest) " HP" else "", color = colors.darkGray)
                        Box(Modifier.padding(start = 12.dp).clickable(enabled = !s.routeBusy, role = Role.Button) { model.removeRoute(r.toAccount) }.padding(horizontal = 6.dp).testTag("routeRemove:${r.toAccount}")) { Glyph(AppIcon.CLOSE, colors.redText, 18.dp, description = UiCopy.REMOVE) }
                    }
                }
                Field(TransferCopy.TO, routeName, { routeName = it }, "routeAccount", enabled = !s.routeBusy, placeholder = TransferCopy.TO_PLACEHOLDER, ltr = true)
                Field(StakeCopy.PERCENT, routePercent, { v -> routePercent = v.filter(Char::isDigit).take(3) }, "routePercent", enabled = !s.routeBusy, number = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = autoVest, onCheckedChange = { autoVest = it }, modifier = Modifier.testTag("routeAutoVest"))
                    Text(StakeCopy.AUTO_VESTS, color = colors.text)
                }
                if (s.routeBusy) CircularProgressIndicator(Modifier.size(20.dp), color = colors.blueText)
                else PrimaryButton(UiCopy.SAVE_CAPS, routeName.isNotBlank() && (routePercent.toIntOrNull() ?: 0) in 1..100, "routeSave") {
                    model.addRoute(routeName, routePercent.toInt(), autoVest); routeName = ""
                }
            }
        }
    }
    StakeAlert(s.prompt, onConfirm = model::confirm, onDismiss = model::dismissPrompt)
}

/** RN AddressView: the chain token's address as a QR code and text. */
@Composable
private fun ReceiveAddress(env: AppEnvironment, host: AppHost, screen: AppScreen.Transfer, onBack: () -> Unit) {
    val colors = LocalEcencyColors.current
    Column(Modifier.fillMaxSize().background(colors.background).statusBarsPadding().testTag("receiveAddress"), horizontalAlignment = Alignment.CenterHorizontally) {
        TopBar("${WalletCopy.RECEIVE} · ${com.ecency.core.l10n.Bidi.inUi(screen.fund)}", onBack = onBack)
        val address = screen.recipient
        if (address.isEmpty()) {
            Text(WalletCopy.ADDRESS_UNAVAILABLE, color = colors.darkGray, modifier = Modifier.padding(24.dp))
        } else {
            Box(Modifier.padding(24.dp).background(Color.White).padding(8.dp)) { QrCode(address, 200.dp) }
            Text(WalletCopy.addressLabel(screen.fund), color = colors.darkGray, fontSize = 13.sp)
            Text(
                address, color = colors.text, textAlign = TextAlign.Center,
                modifier = Modifier.padding(16.dp).clip(RoundedCornerShape(8.dp)).background(colors.grayBackground).clickable(role = Role.Button) { host.copy(address); env.ui.toast(WalletCopy.COPIED) }.padding(12.dp).testTag("addressCopy"),
            )
        }
    }
}

/** RN TradeScreen (swap HIVE and HBD). */
@Composable
fun TradeScreen(model: TradeModel, onBack: () -> Unit) {
    val colors = LocalEcencyColors.current
    val s by model.state.collectAsState()
    LaunchedEffect(model) { model.start() }
    LaunchedEffect(s.closed) { if (s.closed) onBack() }
    val back = leaveGuard(s.busy, onBack)
    Column(Modifier.fillMaxSize().background(colors.background).statusBarsPadding().imePadding().testTag("tradeScreen")) {
        TopBar(TradeCopy.TITLE, onBack = back)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Field("${TransferCopy.FROM} (${com.ecency.core.l10n.Bidi.inUi(s.from)})", s.fromAmount, model::setAmount, "tradeFrom", enabled = !s.busy, number = true)
            Text(com.ecency.core.app.UiCopy.balance("${com.ecency.core.l10n.LocaleFormat.toFixed(s.balance, 3)} ${s.from}"), color = colors.blueText, fontSize = 13.sp, modifier = Modifier.clickable(role = Role.Button) { model.useBalance() }.testTag("tradeBalance"))
            TextButton(onClick = model::flip, enabled = !s.busy, modifier = Modifier.align(Alignment.CenterHorizontally).testTag("tradeFlip")) { Glyph(AppIcon.SORT, colors.blueText, 24.dp, description = WalletUiCopy.SWAP) }
            Text("${TransferCopy.TO} (${com.ecency.core.l10n.Bidi.inUi(s.to)}): ${com.ecency.core.l10n.Bidi.inUi(com.ecency.core.l10n.LocaleFormat.toFixed(s.toAmount, 3))}", color = colors.text, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("tradeTo"))
            s.rateText?.let { Text(it, color = colors.darkGray, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp).testTag("tradeRate")) }
            Text("${TradeCopy.FEE}: ${TradeCopy.FREE}", color = colors.darkGray, fontSize = 13.sp)
            if (s.errors.isNotEmpty()) Warning(s.errors.joinToString("\n"), "tradeErrors")
            if (s.bookLoading) LoadingBlock()
            SendPhasePanel(s.phase, onClose = onBack)
            s.result?.let { r ->
                Column(Modifier.fillMaxWidth().padding(top = 12.dp).testTag("tradeResult"), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(r, color = colors.text, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    if (s.pending) Text(TradeCopy.PENDING_BODY, color = colors.darkGray, fontSize = 13.sp, textAlign = TextAlign.Center)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(onClick = model::reset, modifier = Modifier.testTag("tradeNew")) { Text(TradeCopy.NEW_SWAP, color = colors.blueText) }
                        TextButton(onClick = onBack) { Text(TradeCopy.DONE, color = colors.blueText) }
                    }
                }
            }
            if (s.result == null && !s.busy) PrimaryButton(TransferCopy.NEXT, s.continueEnabled, "tradeNext", model::next)
        }
    }
    when (val p = s.prompt) {
        is TradePrompt.Confirm -> AlertDialog(
            onDismissRequest = model::dismissPrompt, title = { Text(TradeCopy.CONFIRM_SWAP) }, text = { Text(p.text) },
            confirmButton = { TextButton(onClick = model::confirm, modifier = Modifier.testTag("tradeConfirm")) { Text(WalletCopy.CONFIRM) } },
            dismissButton = { TextButton(onClick = model::dismissPrompt) { Text(WalletCopy.CANCEL) } },
        )
        is TradePrompt.Alert -> AlertDialog(onDismissRequest = model::dismissPrompt, title = { Text(p.title) }, text = { Text(p.message) }, confirmButton = { TextButton(onClick = model::dismissPrompt) { Text(UiCopy.OK) } })
        null -> Unit
    }
}

/** RN redeemScreen: promote a post, Boost+ or RC top-up with points. */
@Composable
fun RedeemScreen(model: RedeemModel, onBack: () -> Unit, onBuyPoints: () -> Unit) {
    val colors = LocalEcencyColors.current
    val s by model.state.collectAsState()
    LaunchedEffect(model) { model.start() }
    LaunchedEffect(s.closed) { if (s.closed) onBack() }
    val back = leaveGuard(s.busy, onBack)
    Column(Modifier.fillMaxSize().background(colors.background).statusBarsPadding().imePadding().testTag("redeemScreen")) {
        TopBar(s.kind.title, onBack = back)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text("${RedeemCopy.USER}: ${com.ecency.core.l10n.Bidi.inUi("@" + s.user.orEmpty())}", color = colors.text)
            Text(s.balance?.let { RedeemCopy.points(com.ecency.core.l10n.LocaleFormat.toFixed(it, 3)) } ?: "--", color = colors.blueText, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("redeemBalance"))
            if (s.kind == RedeemKind.RC_TOPUP) Text(RedeemCopy.RC_DESC, color = colors.darkGray, fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp))
            if (s.kind == RedeemKind.PROMOTE) Field(RedeemCopy.POST, s.permlink, model::setPermlink, "redeemPermlink", enabled = !s.busy, placeholder = RedeemCopy.PERMLINK_PLACEHOLDER, ltr = true)
            s.boostedUntil?.let { Warning(RedeemCopy.alreadyBoosted(it), "alreadyBoosted") }
            if (s.pricesLoading) LoadingBlock()
            Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                s.prices.forEach { p ->
                    val selected = p.duration == s.days
                    Text(
                        "${p.duration}", color = if (selected) Color.White else colors.text, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(if (selected) colors.blue else colors.grayBackground)
                            .clickable(role = Role.Button) { model.setDays(p.duration) }.padding(horizontal = 12.dp, vertical = 6.dp).testTag("days:${p.duration}"),
                    )
                }
            }
            Row { Text("${s.days} ${RedeemCopy.DAYS}", color = colors.text, modifier = Modifier.weight(1f)); Text(s.priceText, color = colors.text, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("redeemPrice")) }
            if (s.insufficient && s.kind == RedeemKind.RC_TOPUP) {
                Warning(RedeemCopy.INSUFFICIENT, "insufficient")
                TextButton(onClick = onBuyPoints) { Text(RedeemCopy.BUY_POINTS, color = colors.blueText) }
            }
            SendPhasePanel(s.phase, onClose = onBack)
            if (!s.busy) PrimaryButton(TransferCopy.NEXT, s.enabled, "redeemNext", model::next)
        }
    }
    when (val p = s.prompt) {
        is TradePrompt.Confirm -> AlertDialog(
            onDismissRequest = model::dismissPrompt, text = { Text(p.text) },
            confirmButton = { TextButton(onClick = model::confirm, modifier = Modifier.testTag("redeemConfirm")) { Text(WalletCopy.CONFIRM) } },
            dismissButton = { TextButton(onClick = model::dismissPrompt) { Text(WalletCopy.CANCEL) } },
        )
        is TradePrompt.Alert -> AlertDialog(onDismissRequest = model::dismissPrompt, title = { Text(p.title) }, text = { Text(p.message) }, confirmButton = { TextButton(onClick = model::dismissPrompt) { Text(UiCopy.OK) } })
        null -> Unit
    }
}

/** RN AssetsSelect: pick and order the tokens the Wallet tab shows. */
@Composable
fun ManageAssetsScreen(model: ManageAssetsModel, onBack: () -> Unit) {
    val colors = LocalEcencyColors.current
    val s by model.state.collectAsState()
    LaunchedEffect(model) { model.start() }
    LaunchedEffect(s.closed) { if (s.closed) onBack() }
    Column(Modifier.fillMaxSize().background(colors.background).statusBarsPadding().testTag("manageAssets")) {
        TopBar(WalletCopy.MANAGE_ASSETS, onBack = onBack)
        OutlinedTextField(value = s.query, onValueChange = model::setQuery, placeholder = { Text(WalletCopy.SEARCH) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("assetsSearch"))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            if (s.isLoading) LoadingBlock()
            Text(WalletCopy.SELECTED_ASSETS, color = colors.darkGray, fontWeight = FontWeight.Bold)
            if (s.selected.isEmpty()) Text(WalletCopy.NO_SELECTED_ASSETS, color = colors.darkGray, fontSize = 13.sp)
            s.selected.forEachIndexed { i, a ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("selected:${a.symbol}"), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = true, onCheckedChange = { model.toggle(a.symbol) })
                    Avatar(a.iconUrl.orEmpty(), 24.dp)
                    Text(a.symbol, color = colors.text, modifier = Modifier.weight(1f).padding(start = 8.dp))
                    IconButton(onClick = { model.move(i, i - 1) }, enabled = i > 0) {
                        Glyph(AppIcon.ARROW_UP, if (i > 0) colors.blueText else colors.icon, 20.dp, description = UiCopy.MOVE_UP)
                    }
                    IconButton(onClick = { model.move(i, i + 1) }, enabled = i < s.selected.size - 1) {
                        Glyph(AppIcon.ARROW_DOWN, if (i < s.selected.size - 1) colors.blueText else colors.icon, 20.dp, description = UiCopy.MOVE_DOWN)
                    }
                }
            }
            Text(WalletCopy.AVAILABLE_ASSETS, color = colors.darkGray, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
            s.visibleAvailable.forEach { a ->
                Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { model.toggle(a.symbol) }.padding(vertical = 4.dp).testTag("available:${a.symbol}"), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = false, onCheckedChange = { model.toggle(a.symbol) })
                    Avatar(a.iconUrl.orEmpty(), 24.dp)
                    Text(a.symbol, color = colors.text, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
        Box(Modifier.padding(16.dp).navigationBarsPadding()) {
            if (s.saving) CircularProgressIndicator(color = colors.blueText) else PrimaryButton(WalletCopy.CONFIRM, s.user != null && !s.isLoading, "assetsConfirm", model::confirm)
        }
    }
    s.error?.let { e -> AlertDialog(onDismissRequest = model::dismissError, text = { Text(e) }, confirmButton = { TextButton(onClick = model::dismissError) { Text(UiCopy.OK) } }) }
}

/** RN tippingDialog. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TipSheet(model: TipModel, onDismiss: () -> Unit) {
    val colors = LocalEcencyColors.current
    val s by model.state.collectAsState()
    LaunchedEffect(model) { model.start() }
    LaunchedEffect(s.closed) { if (s.closed) onDismiss() }
    ModalBottomSheet(
        onDismissRequest = { if (!s.busy) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { !s.busy }),
        containerColor = colors.background,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(16.dp).testTag("tipSheet")) {
            Text(TipCopy.TITLE, color = colors.text, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.semantics { heading() })
            Text(TipCopy.supportAuthor(s.author), color = colors.darkGray)
            Text("${TipCopy.EXISTING} (${s.tipsCount})", color = colors.darkGray, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            s.tipsTotals.forEach { (cur, total) -> Text("$total $cur", color = colors.text, fontSize = 12.sp) }
            Text(TipCopy.TOKEN, color = colors.text, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                s.currencies.forEach { c ->
                    val selected = c.symbol == s.currency
                    Column(
                        Modifier.clip(RoundedCornerShape(10.dp)).border(1.dp, if (selected) colors.blueText else colors.border, RoundedCornerShape(10.dp))
                            .clickable(role = Role.Button) { model.setCurrency(c.symbol) }.padding(8.dp).testTag("tipCurrency:${c.symbol}"),
                    ) {
                        Text(c.symbol, color = if (selected) colors.blueText else colors.text, fontWeight = FontWeight.Bold)
                        if (c.balance.isNotEmpty()) Text(c.balance, color = colors.darkGray, fontSize = 11.sp)
                    }
                }
            }
            Field(TipCopy.AMOUNT, s.amount, model::setAmount, "tipAmount", enabled = !s.busy, number = true)
            Text(TipCopy.maxDecimals(s.selected.precision), color = colors.darkGray, fontSize = 12.sp)
            if (s.user == null) Warning(TipCopy.LOGIN_REQUIRED, "tipLogin")
            SendPhasePanel(s.phase, onClose = onDismiss)
            if (!s.busy) PrimaryButton(TipCopy.SEND, s.sendEnabled, "tipSend", model::send)
        }
    }
    if (s.confirming) AlertDialog(
        onDismissRequest = model::dismissConfirm, title = { Text(TipCopy.CONFIRM_TITLE) },
        text = { Text(TipCopy.confirm(s.amount, s.currency, s.author)) },
        confirmButton = { TextButton(onClick = model::confirm, modifier = Modifier.testTag("tipConfirm")) { Text(WalletCopy.CONFIRM) } },
        dismissButton = { TextButton(onClick = model::dismissConfirm) { Text(WalletCopy.CANCEL) } },
    )
}

/** RN TRANSFER_FAVORITES: pick the recipient from the favourite accounts. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FavoritesSheet(model: com.ecency.core.app.TransferFavoritesModel, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val colors = LocalEcencyColors.current
    val s by model.state.collectAsState()
    LaunchedEffect(model) { model.start() }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.background) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp).testTag("favoritesSheet")) {
            Text(WalletCopy.PICK_FAVORITE, color = colors.text, fontWeight = FontWeight.Bold)
            OutlinedTextField(value = s.query, onValueChange = model::setQuery, placeholder = { Text(TransferCopy.TO_PLACEHOLDER) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
            if (s.isLoading) LoadingBlock()
            s.error?.let { ErrorBlock(it, onRetry = model::start) }
            s.visible.forEach { name ->
                Text("@$name", color = colors.blueText, modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { onPick(name) }.padding(10.dp).testTag("favorite:$name"))
            }
        }
    }
}

/**
 * Back while a send runs: asks first (the send goes on without the screen and reports its outcome as a toast).
 * Returns the back action every back button of the screen uses.
 */
@Composable
internal fun leaveGuard(busy: Boolean, onBack: () -> Unit): () -> Unit {
    var asking by remember { mutableStateOf(false) }
    ScreenBackHandler(enabled = busy) { asking = true }
    if (asking) AlertDialog(
        onDismissRequest = { asking = false },
        title = { Text(WalletCopy.LEAVE_TITLE) },
        text = { Text(WalletCopy.LEAVE_MESSAGE) },
        confirmButton = { TextButton(onClick = { asking = false; onBack() }, modifier = Modifier.testTag("leaveConfirm")) { Text(WalletCopy.LEAVE) } },
        dismissButton = { TextButton(onClick = { asking = false }) { Text(WalletCopy.STAY) } },
    )
    return { if (busy) asking = true else onBack() }
}

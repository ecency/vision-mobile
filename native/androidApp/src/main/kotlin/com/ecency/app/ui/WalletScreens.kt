package com.ecency.app.ui

import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.heightIn

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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ecency.core.api.wallet.CoinActivity
import com.ecency.core.api.wallet.RecurrentSchedule
import com.ecency.core.api.wallet.TransactionExplorer
import com.ecency.core.api.wallet.WalletHistory
import com.ecency.core.app.AppEnvironment
import com.ecency.core.app.AppScreen
import com.ecency.core.app.AssetDetailsModel
import com.ecency.core.app.AssetRow
import com.ecency.core.app.DelegationsModel
import com.ecency.core.app.DetailsPrompt
import com.ecency.core.app.DetailsSheet
import com.ecency.core.app.JournalEntry
import com.ecency.core.app.PostCard
import com.ecency.core.app.ReceiveQr
import com.ecency.core.app.RecurrentCopy
import com.ecency.core.app.SendPhase
import com.ecency.core.app.WalletCopy
import com.ecency.core.app.UiCopy
import com.ecency.core.app.WalletUiCopy

/** RN walletScreen (the Wallet tab): header with the estimated balance, quick actions, journal notices, asset cards. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletTab(env: AppEnvironment, username: String, list: LazyListState, push: (AppScreen) -> Unit) {
    val colors = LocalEcencyColors.current
    val model = env.wallet
    val state by model.state.collectAsState()
    val journal by env.sendJournal.state.collectAsState()
    var receive by remember { mutableStateOf(false) }
    LaunchedEffect(model) { model.start(); env.sendJournal.start() }
    DisposableEffect(model) {
        model.visible(true)
        onDispose { model.visible(false) }
    }
    val mine = state.account == username
    val rows = if (mine) state.rows else emptyList()
    PullToRefreshBox(isRefreshing = state.isRefreshing, onRefresh = { model.refresh(); env.sendJournal.recheck() }, modifier = Modifier.fillMaxSize().testTag("walletTab")) {
        LazyColumn(Modifier.fillMaxSize().testTag("walletList"), state = list) {
            item(key = "header") {
                WalletHeader(
                    total = if (mine) state.totalText else "--.--",
                    updated = when {
                        state.isUpdating -> WalletCopy.UPDATING
                        state.updatedAtMillis != null -> WalletCopy.updated(com.ecency.core.l10n.LocaleFormat.time(state.updatedAtMillis!!))
                        else -> "--:--"
                    },
                    updating = state.isUpdating,
                    onRefresh = model::refresh,
                    onManage = { push(AppScreen.AssetsSelect) },
                    onSend = { push(AppScreen.Transfer("transfer", "HIVE", "hive")) },
                    onReceive = { receive = true },
                    onSwap = { push(AppScreen.Trade("HIVE")) },
                    onGetPoints = { push(AppScreen.of(com.ecency.core.app.RouteName.BOOST, username)) },
                )
            }
            items(journal.forAccount(username), key = { "journal:${it.actionId}" }) { JournalNotice(it, onDismiss = { env.sendJournal.dismiss(it) }) }
            if (state.showError) item(key = "error") {
                Column(Modifier.fillMaxWidth().padding(16.dp).testTag("walletError"), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(WalletCopy.REFRESH_FAILED, color = colors.text, textAlign = TextAlign.Center)
                    TextButton(onClick = model::retry) { Text(WalletCopy.TRY_AGAIN, color = colors.blueText) }
                }
            }
            if (state.showSkeleton) item(key = "skeleton") { RowsSkeleton(5, avatar = false, tag = "skeleton:wallet") }
            items(rows, key = { it.symbol }) { row ->
                AssetCard(row, onOpen = { push(AppScreen.AssetDetails(row.symbol)) }, onClaim = {
                    if (row.pendingText == null && row.symbol == "POINTS") push(AppScreen.of(com.ecency.core.app.RouteName.BOOST, username)) else model.claim(row.symbol)
                })
            }
        }
    }
    if (receive) ReceiveSheet(env, username, onDismiss = { receive = false })
}

@Composable
private fun WalletHeader(
    total: String, updated: String, updating: Boolean, onRefresh: () -> Unit, onManage: () -> Unit,
    onSend: () -> Unit, onReceive: () -> Unit, onSwap: () -> Unit, onGetPoints: () -> Unit,
) {
    val colors = LocalEcencyColors.current
    Column(Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(12.dp)).background(colors.lightBackground).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(WalletCopy.ESTIMATED_BALANCE, color = colors.darkGray, fontSize = 13.sp)
                Text(if (updating && total == "--.--") "--.--" else total, color = colors.text, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("walletTotal"))
                Text(updated, color = colors.darkGray, fontSize = 12.sp, modifier = Modifier.testTag("walletUpdated"))
            }
            Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onRefresh, role = Role.Button).testTag("walletRefresh"), contentAlignment = Alignment.Center) {
                if (updating) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.blueText)
                else Glyph(AppIcon.REFRESH, colors.icon, 20.dp, description = WalletUiCopy.REFRESH)
            }
            Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onManage, role = Role.Button).testTag("walletManage"), contentAlignment = Alignment.Center) {
                Glyph(AppIcon.TUNE, colors.icon, 20.dp, description = WalletCopy.MANAGE_ASSETS)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            QuickAction(AppIcon.SEND, WalletCopy.SEND, "walletSend", onSend)
            QuickAction(AppIcon.QR_SCAN, WalletCopy.RECEIVE, "walletReceive", onReceive)
            QuickAction(AppIcon.SWAP, WalletCopy.SWAP, "walletSwap", onSwap)
            QuickAction(AppIcon.ADD, WalletCopy.GET_POINTS, "walletGetPoints", onGetPoints)
        }
    }
}

@Composable
private fun QuickAction(icon: AppIcon, label: String, tag: String, onClick: () -> Unit) {
    val colors = LocalEcencyColors.current
    Column(Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick, role = Role.Button).padding(6.dp).testTag(tag), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(42.dp).clip(CircleShape).background(colors.blue), contentAlignment = Alignment.Center) { Glyph(icon, Color.White, 20.dp, filled = true) }
        Text(label, color = colors.text, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

/** The restart journal's notice: still checking (spinner), or what the chain said. */
@Composable
fun JournalNotice(entry: JournalEntry, onDismiss: () -> Unit) {
    val colors = LocalEcencyColors.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(10.dp)).background(colors.grayBackground).padding(12.dp)
            .testTag("journal:${entry.actionId}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (entry.outcome == null) CircularProgressIndicator(Modifier.size(18.dp).testTag("journalSpinner"), strokeWidth = 2.dp, color = colors.blueText)
        Text(entry.message, color = colors.text, fontSize = 13.sp, modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
        if (entry.outcome != null) TextButton(onClick = onDismiss, modifier = Modifier.testTag("journalDismiss")) { Text(UiCopy.OK, color = colors.blueText) }
    }
}

/** RN AssetCard: icon, symbol and name, balance and fiat, the claim button when something is pending. */
@Composable
private fun AssetCard(row: AssetRow, onOpen: () -> Unit, onClaim: () -> Unit) {
    val colors = LocalEcencyColors.current
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(12.dp)).background(colors.lightBackground)
            .clickable(onClick = onOpen, role = Role.Button).padding(14.dp).testTag("asset:${row.symbol}"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(row.item.iconUrl.orEmpty(), 32.dp)
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(row.symbol, color = colors.text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(row.name, color = colors.darkGray, fontSize = 12.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(row.balanceText, color = colors.text, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.testTag("assetBalance:${row.symbol}"))
                row.fiatText?.let { Text(it, color = colors.darkGray, fontSize = 12.sp) }
            }
        }
        val pending = row.pendingText
        // RN ClaimButton: the pill at its own width, centred in the card (claimContainer justifyContent center)
        if (pending != null || row.symbol == "POINTS") Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
            Row(
                Modifier.clip(RoundedCornerShape(16.dp)).border(1.dp, colors.blueText, RoundedCornerShape(16.dp))
                    .clickable(enabled = !row.claiming, onClick = onClaim, role = Role.Button).padding(horizontal = 12.dp, vertical = 6.dp).testTag("claim:${row.symbol}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (row.claiming) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = colors.blueText)
                else Glyph(AppIcon.ADD, colors.blueText, 16.dp)
                Text(pending ?: WalletCopy.GET_POINTS, color = colors.blueText, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}

/** RN receiveQrSheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiveSheet(env: AppEnvironment, username: String, onDismiss: () -> Unit) {
    val colors = LocalEcencyColors.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.background) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(20.dp).testTag("receiveSheet"), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(WalletCopy.RECEIVE, color = colors.text, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.semantics { heading() })
            Text(WalletCopy.RECEIVE_DESC, color = colors.darkGray, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 8.dp))
            Box(Modifier.background(Color.White).padding(8.dp)) { QrCode(ReceiveQr.uri(username), 200.dp) }
            Text("@$username", color = colors.blueText, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp).clickable(role = Role.Button) { env.ui.toast(WalletCopy.COPIED) }.testTag("receiveCopy"))
        }
    }
}

/** A send that may already have gone through: the spinner while the chain is asked, or the final word. */
@Composable
fun SendPhasePanel(phase: SendPhase?, onClose: () -> Unit) {
    val colors = LocalEcencyColors.current
    when (phase) {
        is SendPhase.Sending -> Row(Modifier.fillMaxWidth().padding(16.dp).testTag("sending"), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = colors.blueText)
        }
        is SendPhase.Checking -> Column(Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(10.dp)).background(colors.grayBackground).padding(16.dp).testTag("checkWallet")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp).testTag("checkSpinner"), strokeWidth = 2.dp, color = colors.blueText)
                Text(WalletCopy.CHECKING, color = colors.text, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 10.dp))
            }
            Text(phase.message, color = colors.text, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        }
        is SendPhase.Unconfirmed -> Column(Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(10.dp)).background(colors.grayBackground).padding(16.dp).testTag("unconfirmed")) {
            Text(phase.message, color = colors.text, fontSize = 13.sp)
            TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End).testTag("unconfirmedClose")) { Text(UiCopy.CLOSE, color = colors.blueText) }
        }
        else -> Unit
    }
}

/** RN assetDetailsScreen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssetDetailsScreen(env: AppEnvironment, host: AppHost, model: AssetDetailsModel, list: LazyListState, onBack: () -> Unit, push: (AppScreen) -> Unit) {
    val colors = LocalEcencyColors.current
    val state by model.state.collectAsState()
    LaunchedEffect(model) { model.start() }
    var sheet by remember { mutableStateOf<DetailsSheet?>(null) }
    var filter by remember { mutableStateOf(false) }
    var analytics by remember { mutableStateOf(false) }
    val analyticsCoin = state.analyticsCoin
    val analyticsUser = state.account
    if (analytics && analyticsCoin != null && analyticsUser != null) {
        BalanceAnalyticsSheet(env, analyticsUser, analyticsCoin) { analytics = false }
    }
    LaunchedEffect(list, state.activities.size) {
        androidx.compose.runtime.snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { last -> if (last >= list.layoutInfo.totalItemsCount - 3) model.loadMore() }
    }
    Column(Modifier.fillMaxSize().background(colors.background).statusBarsPadding().testTag("assetDetails")) {
        TopBar(WalletCopy.DETAILS, onBack = onBack) {
            if (state.filterable) TextButton(onClick = { filter = true }, modifier = Modifier.testTag("historyFilter")) { Text(WalletCopy.FILTER_TITLE, color = colors.blueText, fontSize = 12.sp) }
        }
        PullToRefreshBox(isRefreshing = state.isRefreshing, onRefresh = model::refresh, modifier = Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize(), state = list) {
                item(key = "summary") {
                    Column(Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(12.dp)).background(colors.lightBackground).padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Avatar(state.asset?.iconUrl.orEmpty(), 36.dp)
                            Text(state.symbol, color = colors.text, fontWeight = FontWeight.Bold, fontSize = 20.sp, modifier = Modifier.padding(start = 10.dp).weight(1f))
                            state.aprText?.let { Text(it, color = colors.blueText, fontSize = 13.sp, modifier = Modifier.testTag("assetApr")) }
                            // RN coinBasics: the chart-line button opens the balance analytics (HIVE, HBD, HP)
                            if (state.analyticsCoin != null && state.account != null) {
                                Box(Modifier.padding(start = 8.dp).clip(CircleShape).clickable(role = Role.Button) { analytics = true }.padding(4.dp).testTag("openAnalytics")) {
                                    Glyph(AppIcon.CHART_LINE, colors.icon, 22.dp, description = com.ecency.core.app.UiCopy.OPEN_ANALYTICS)
                                }
                            }
                        }
                        state.summary.forEach { r ->
                            Row(
                                Modifier.fillMaxWidth().padding(top = 10.dp).let { m -> r.opens?.let { o -> m.clickable(role = Role.Button) { sheet = o } } ?: m }.testTag("summary:${r.label}"),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(r.label, color = if (r.opens != null) colors.blueText else colors.darkGray, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                Text(r.value, color = colors.text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                        }
                        if (state.actions.isNotEmpty()) {
                            androidx.compose.foundation.layout.FlowRow(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                state.actions.forEach { a ->
                                    Button(onClick = { push(a.screen) }, colors = ButtonDefaults.buttonColors(contentColor = androidx.compose.ui.graphics.Color.White, containerColor = colors.blue), modifier = Modifier.testTag("action:${a.actionName}")) {
                                        Text(a.label, color = Color.White, fontSize = 13.sp)
                                    }
                                }
                            }
                        }
                    }
                }
                if (state.symbol == "POINTS") item(key = "earn") {
                    // RN HorizontalIconList of POINTS_KEYS: the ways to earn points
                    androidx.compose.foundation.lazy.LazyRow(Modifier.fillMaxWidth().testTag("pointsEarn"), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(WalletCopy.POINTS_EARN, key = { it.name }) { way ->
                            Column(Modifier.clip(RoundedCornerShape(10.dp)).background(colors.lightBackground).clickable(role = Role.Button) { env.ui.toast(way.description) }.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(way.points, color = colors.blueText, fontWeight = FontWeight.Bold)
                                Text(way.name, color = colors.text, fontSize = 12.sp)
                            }
                        }
                    }
                }
                if (state.pending.isNotEmpty()) {
                    item(key = "pendingTitle") { SectionTitle(WalletCopy.PENDING_REQUESTS) }
                    items(state.pending, key = { "p:" + it.key + it.textKey }) { row -> ActivityRow(env, host, row, state.expanded, model, pending = true, busy = state.busyKey == "order:${row.trxIndex}", push = push) }
                }
                if (state.activitiesEnabled) {
                    item(key = "activitiesTitle") { SectionTitle(WalletCopy.ACTIVITIES) }
                    // #2747: web's categories (hive layer, the server filters by their bitmask) and points types
                    if (state.categories.isNotEmpty()) item(key = "categories") {
                        HistoryChips(state.categories.map { it.name to it.label }, state.category?.name) { name -> model.setCategory(com.ecency.core.api.wallet.HistoryCategory.valueOf(name)) }
                    }
                    if (state.pointTypes.isNotEmpty()) item(key = "pointTypes") {
                        HistoryChips(state.pointTypes.map { it.toString() to WalletCopy.pointTypeLabel(it) }, state.pointType.toString()) { model.setPointType(it.toInt()) }
                    }
                    items(state.activities, key = { "a:" + it.key + it.textKey }) { row -> ActivityRow(env, host, row, state.expanded, model, pending = false, busy = false, push = push) }
                    item(key = "footer") {
                        Box(Modifier.fillMaxWidth().height(72.dp), contentAlignment = Alignment.Center) {
                            when {
                                state.isLoading || state.isLoadingMore -> CircularProgressIndicator(color = colors.blueText)
                                state.historyFailed -> Text(WalletCopy.ACTIVITIES_FAILED, color = colors.darkGray, textAlign = TextAlign.Center, modifier = Modifier.testTag("historyFailed"))
                                state.isEmpty -> Text(WalletCopy.NO_ACTIVITIES, color = colors.darkGray, modifier = Modifier.testTag("historyEmpty"))
                            }
                        }
                    }
                }
            }
        }
    }
    state.prompt?.let { p ->
        AlertDialog(
            onDismissRequest = model::dismissPrompt,
            title = { Text(WalletCopy.CONFIRM) },
            text = { Text(if (p is DetailsPrompt.DeleteRecurrent) RecurrentCopy.unsubscribe(p.schedule.to) else WalletCopy.label("limit_order_cancel") + "?") },
            confirmButton = { TextButton(onClick = model::confirmPrompt, modifier = Modifier.testTag("detailsConfirm")) { Text(WalletCopy.CONFIRM) } },
            dismissButton = { TextButton(onClick = model::dismissPrompt) { Text(WalletCopy.CANCEL) } },
        )
    }
    when (val s = sheet) {
        DetailsSheet.RECURRENT_TRANSFERS -> RecurrentSheet(state.recurrent, onUser = { sheet = null; push(AppScreen.Profile(it)) }, onDelete = model::deleteRecurrent, onDismiss = { sheet = null })
        DetailsSheet.OUTGOING_DELEGATIONS, DetailsSheet.INCOMING_DELEGATIONS -> state.account?.let { acc ->
            val dm = remember(acc, s) { env.delegationsModel(acc, s) }
            DisposableEffect(dm) { onDispose { dm.close() } }
            DelegationsSheet(dm, onUser = { sheet = null; push(AppScreen.Profile(it)) }, onUpdate = { sheet = null; push(AppScreen.Transfer("delegate_vesting_shares", "HP", "hive", it)) }, onDismiss = { sheet = null })
        }
        null -> Unit
    }
    if (filter) HistoryFilterSheet(state.symbol, state.filter, onApply = { model.setFilter(it); filter = false }, onDismiss = { filter = false })
}

/** One choice of chips over the history, sideways scrolling (mirrored in RTL); [selected] null when none applies. */
@Composable
private fun HistoryChips(chips: List<Pair<String, String>>, selected: String?, onSelect: (String) -> Unit) {
    androidx.compose.foundation.lazy.LazyRow(
        Modifier.fillMaxWidth().semantics { contentDescription = WalletCopy.HISTORY_CATEGORIES }.testTag("historyChips"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 4.dp),
    ) {
        items(chips, key = { it.first }) { (id, label) ->
            Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) { Pill(label, id == selected, "history:$id") { onSelect(id) } }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = LocalEcencyColors.current.darkGray, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { heading() })
}

/** RN Transaction row: label, time, value; tap expands details, memo and the transaction id. */
@Composable
private fun ActivityRow(env: AppEnvironment, host: AppHost, row: CoinActivity, expanded: Set<String>, model: AssetDetailsModel, pending: Boolean, busy: Boolean, push: (AppScreen) -> Unit) {
    val colors = LocalEcencyColors.current
    val open = row.key in expanded
    Column(Modifier.fillMaxWidth().clickable(role = Role.Button) { model.toggle(row.key) }.padding(horizontal = 16.dp, vertical = 10.dp).testTag("activity:${row.key}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(WalletCopy.label(row.textKey), color = colors.text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                val sub = when {
                    row.textKey == "recurrent_transfer" && (row.executions?.toIntOrNull() ?: 0) > 0 -> RecurrentCopy.scheduleSummary(row.executions.orEmpty(), row.recurrence.orEmpty())
                    row.textKey == "fill_recurrent_transfer" -> RecurrentCopy.remaining(row.executions?.toIntOrNull() ?: 0)
                    else -> (if (pending) "${WalletCopy.EXPIRES} " else "") + relativeTime(row.expires?.takeIf { it.isNotEmpty() } ?: row.created)
                }
                Text(sub, color = colors.darkGray, fontSize = 12.sp)
            }
            Text(row.displayValue, color = colors.text, fontSize = 13.sp, textAlign = TextAlign.End, modifier = Modifier.testTag("activityAmount:${row.key}"))
            if (row.cancelable) {
                if (busy) CircularProgressIndicator(Modifier.padding(start = 8.dp).size(16.dp), strokeWidth = 2.dp, color = colors.blueText)
                else Box(Modifier.padding(start = 12.dp).clickable(role = Role.Button) { row.trxIndex?.let(model::cancelOrder) }.padding(6.dp).testTag("cancelOrder:${row.trxIndex}")) { Glyph(AppIcon.CLOSE, colors.redText, 18.dp, description = UiCopy.CANCEL) }
            }
            if (row.repeatable && !pending) {
                Box(Modifier.padding(start = 12.dp).clickable(role = Role.Button) { model.repeat(row)?.let(push) }.padding(6.dp).testTag("repeat:${row.key}")) { Glyph(AppIcon.REBLOG, colors.blueText, 18.dp, description = WalletUiCopy.REPEAT) }
            }
        }
        if (open) {
            row.details?.let { Text(it, color = colors.darkGray, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
            row.memo?.takeIf { it.isNotEmpty() }?.let { Text(it, color = colors.text, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
            row.trxId?.let { id ->
                Row(Modifier.padding(top = 6.dp).clickable(role = Role.Button) { host.copy(TransactionExplorer.link(id)); env.ui.toast(WalletCopy.COPIED) }.testTag("copyTx:${row.key}"), verticalAlignment = Alignment.CenterVertically) {
                    Text("${WalletCopy.TRANSACTION_ID}: ${com.ecency.core.l10n.Bidi.inUi(id.take(12) + "...")}", color = colors.blueText, fontSize = 12.sp)
                }
            }
        }
    }
}

/** "2 hours ago" from a chain or ISO time, in the app language. */
private fun relativeTime(time: String): String {
    val millis = WalletHistory.epochMillis(time) ?: return time
    return com.ecency.core.app.FromNow.format(millis, System.currentTimeMillis())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryFilterSheet(symbol: String, selected: List<String>?, onApply: (List<String>?) -> Unit, onDismiss: () -> Unit) {
    val colors = LocalEcencyColors.current
    val all = WalletHistory.opsFor(symbol)
    var picked by remember { mutableStateOf(selected ?: all) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.background) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp).testTag("filterSheet")) {
            Text(WalletCopy.FILTER_TITLE, color = colors.text, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Text(WalletCopy.FILTER_DESC, color = colors.darkGray, fontSize = 13.sp)
            TextButton(onClick = { picked = if (picked.size == all.size) emptyList() else all }) { Text(if (picked.size == all.size) WalletCopy.FILTER_NONE else WalletCopy.FILTER_ALL, color = colors.blueText) }
            all.forEach { op ->
                Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { picked = if (op in picked) picked - op else picked + op }.testTag("filter:$op"), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = op in picked, onCheckedChange = { picked = if (op in picked) picked - op else picked + op })
                    Text(WalletCopy.label(op).trim(), color = colors.text)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(WalletCopy.CANCEL, color = colors.darkGray) }
                Button(onClick = { onApply(picked) }, enabled = picked.isNotEmpty(), colors = ButtonDefaults.buttonColors(contentColor = androidx.compose.ui.graphics.Color.White, containerColor = colors.blue), modifier = Modifier.testTag("filterApply")) {
                    Text(WalletCopy.FILTER_APPLY, color = Color.White)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecurrentSheet(list: List<RecurrentSchedule>, onUser: (String) -> Unit, onDelete: (RecurrentSchedule) -> Unit, onDismiss: () -> Unit) {
    val colors = LocalEcencyColors.current
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.background) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp).testTag("recurrentSheet")) {
            Text(RecurrentCopy.title(list.size), color = colors.text, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            list.forEach { r ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable(role = Role.Button) { onUser(r.to) }) {
                        Text(r.to, color = colors.text, fontWeight = FontWeight.Bold)
                        Text(RecurrentCopy.transferIn(relativeTime(r.triggerDate)), color = colors.darkGray, fontSize = 12.sp)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(r.amount, color = colors.text)
                        Text(RecurrentCopy.remaining(r.remainingExecutions), color = colors.darkGray, fontSize = 12.sp)
                    }
                    Box(Modifier.padding(start = 12.dp).clickable(role = Role.Button) { onDelete(r) }.padding(6.dp).testTag("recurrentDelete:${r.to}")) { Glyph(AppIcon.CLOSE, colors.redText, 18.dp, description = UiCopy.DELETE) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DelegationsSheet(model: DelegationsModel, onUser: (String) -> Unit, onUpdate: (String) -> Unit, onDismiss: () -> Unit) {
    val colors = LocalEcencyColors.current
    val state by model.state.collectAsState()
    LaunchedEffect(model) { model.start() }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.background) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp).testTag("delegationsSheet")) {
            Text(state.title, color = colors.text, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            OutlinedTextField(value = state.query, onValueChange = model::setQuery, placeholder = { Text(WalletCopy.SEARCH) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
            if (state.isLoading) LoadingBlock()
            state.error?.let { ErrorBlock(it, onRetry = model::refresh) }
            LazyColumn(Modifier.fillMaxWidth().height(360.dp)) {
                items(state.visible, key = { it.account }) { d ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("delegation:${d.account}"), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).clickable(enabled = !d.expiring, role = Role.Button) { onUser(d.account) }) {
                            Text(if (d.expiring) WalletCopy.EXPIRING else d.account, color = if (d.expiring) colors.redText else colors.text, fontWeight = FontWeight.Bold)
                            (d.timeLeft ?: d.date?.take(10))?.let { Text(it, color = colors.darkGray, fontSize = 12.sp) }
                        }
                        Column(Modifier.clickable(enabled = d.updatable, role = Role.Button) { onUpdate(d.account) }, horizontalAlignment = Alignment.End) {
                            Text(d.hp, color = if (d.expiring) colors.redText else colors.text)
                            if (d.updatable) Text(WalletCopy.TAP_UPDATE, color = colors.blueText, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

/** The avatar of an account as a small icon. */
@Composable
internal fun AccountAvatar(env: AppEnvironment, name: String, size: androidx.compose.ui.unit.Dp = 28.dp) = Avatar(PostCard.avatarUrl(name, env.proxyBase), size)

@Composable
internal fun Spacer8() = Spacer(Modifier.width(8.dp))

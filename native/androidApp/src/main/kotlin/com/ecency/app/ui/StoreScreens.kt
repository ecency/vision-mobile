package com.ecency.app.ui

import androidx.compose.foundation.layout.heightIn

import androidx.compose.ui.semantics.Role

import com.ecency.core.app.AppIcon
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ecency.core.app.AppEnvironment
import com.ecency.core.app.PurchaseCopy
import com.ecency.core.app.StoreRow
import com.ecency.core.app.StoreScreenModel
import com.ecency.core.app.UiCopy

/** RN `$companyRed`: the deal ribbon. */
private val CompanyRed = Color(0xFFC10000)

/**
 * RN ProductItemLine: the deal ribbon ("BEST DEAL!", "POPULAR!") on the left, the blue button with the product's
 * title and a "+" in a white circle, the store's price at the right.
 */
@Composable
fun ProductItemLine(row: StoreRow, enabled: Boolean, onBuy: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalEcencyColors.current
    Row(modifier.fillMaxWidth().testTag("product-${row.productId}"), verticalAlignment = Alignment.CenterVertically) {
        if (row.deal != null) {
            Box(contentAlignment = Alignment.CenterEnd) {
                Box(
                    Modifier.width(90.dp).heightIn(min = 40.dp).clip(RoundedCornerShape(topEnd = 5.dp, bottomEnd = 5.dp)).background(CompanyRed).padding(horizontal = 16.dp),
                    contentAlignment = Alignment.CenterStart,
                ) { Text(row.deal!!, color = Color.White, fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold) }
                // RN's triangle: the background color notched into the ribbon's right edge
                Box(Modifier.width(14.dp).height(36.dp).clip(GenericShape { size, _ -> moveTo(size.width, 0f); lineTo(0f, size.height / 2); lineTo(size.width, size.height); close() }).background(colors.background))
            }
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
            Row(
                Modifier.padding(vertical = 12.dp).heightIn(min = 50.dp).clip(RoundedCornerShape(25.dp)).background(colors.blue)
                    .alpha(if (enabled) 1f else 0.5f).clickable(enabled = enabled, role = Role.Button) { onBuy(row.productId) }
                    .padding(horizontal = 18.dp).testTag("buy-${row.productId}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(row.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 16.dp).widthIn(min = 110.dp, max = 140.dp))
                Box(Modifier.size(24.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) { Glyph(AppIcon.ADD, colors.blueText, 23.dp) }
            }
        }
        Box(Modifier.width(96.dp), contentAlignment = Alignment.Center) {
            if (row.price.isNotEmpty()) Text(row.price, color = colors.blueText, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("price-${row.productId}"))
        }
    }
}

/** RN BoostPlaceHolder while the store answers. */
@Composable
private fun StoreLoading() {
    Box(Modifier.fillMaxWidth().padding(48.dp).testTag("storeLoading"), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = LocalEcencyColors.current.blueText)
    }
}

/** The alert, the link's confirmation and the free points row of a purchase screen. */
@Composable
fun StoreDialogs(env: AppEnvironment, model: StoreScreenModel, onSpinGame: () -> Unit) {
    val s by model.state.collectAsState()
    LaunchedEffect(s.openSpinGame) {
        if (s.openSpinGame) {
            model.consumeOpenSpinGame()
            onSpinGame()
        }
    }
    s.alert?.let { a ->
        AlertDialog(
            onDismissRequest = model::dismissAlert,
            title = { Text(a.title) },
            text = { Text(a.body, modifier = Modifier.testTag("storeAlert")) },
            confirmButton = { TextButton(onClick = model::dismissAlert, modifier = Modifier.testTag("storeAlertOk")) { Text(UiCopy.OK) } },
        )
    }
    s.confirm?.let { c ->
        AlertDialog(
            onDismissRequest = model::dismissConfirm,
            icon = { UserRibbon(env, c.username) },
            title = { Text(c.title) },
            text = { Text(c.body, modifier = Modifier.testTag("storeConfirmBody")) },
            dismissButton = { TextButton(onClick = model::dismissConfirm) { Text(PurchaseCopy.CANCEL) } },
            confirmButton = { TextButton(onClick = model::confirm, modifier = Modifier.testTag("storeConfirm")) { Text(PurchaseCopy.CONFIRM) } },
        )
    }
}

/** RN boostScreen ("Get Points"): the points packs, highest first, then the free points row. */
@Composable
fun GetPointsScreen(env: AppEnvironment, model: StoreScreenModel, onBack: () -> Unit, onSpinGame: () -> Unit) {
    val colors = LocalEcencyColors.current
    val s by model.state.collectAsState()
    LaunchedEffect(model) { model.start() }
    Column(Modifier.fillMaxSize().background(colors.background).testTag("getPoints")) {
        TopBar(PurchaseCopy.POINTS_TITLE, onBack = if (s.processing) null else onBack)
        // RN shows the ribbon only for a username parameter (a link or the QR flow)
        if (model.forSomeoneElse) UserRibbon(env, s.username, Modifier.padding(start = 32.dp, top = 8.dp))
        if (s.loading) {
            StoreLoading()
        } else {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
                s.rows.forEach { ProductItemLine(it, enabled = !s.processing, onBuy = model::buy) }
            }
        }
    }
    StoreDialogs(env, model, onSpinGame)
}

/** RN accountBoostScreen ("Get Account Boost"). */
@Composable
fun AccountBoostScreen(env: AppEnvironment, model: StoreScreenModel, onBack: () -> Unit) {
    val colors = LocalEcencyColors.current
    val s by model.state.collectAsState()
    LaunchedEffect(model) { model.start() }
    Column(Modifier.fillMaxSize().background(colors.background).testTag("accountBoost")) {
        TopBar(PurchaseCopy.BOOST_TITLE, onBack = if (s.processing) null else onBack)
        if (s.loading) {
            StoreLoading()
            return@Column
        }
        // RN spreads these over the screen; it scrolls here so a small screen still reaches the button
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.SpaceAround) {
            if (s.username.isNotEmpty()) UserRibbon(env, s.username, Modifier.padding(start = 32.dp, top = 8.dp))
            Column(Modifier.fillMaxWidth().padding(bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                // README "Esie": the drawn mascot, not RN's old coin hugging picture
                EsieView(com.ecency.core.esie.EsiePlacement.PROMO)
                Text(
                    PurchaseCopy.BOOST_DESC, color = colors.darkGray, fontSize = 16.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(0.67f).padding(top = 8.dp),
                )
            }
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 52.dp)) {
                s.rows.forEach { ProductItemLine(it, enabled = !s.processing, onBuy = model::buy) }
            }
        }
    }
    StoreDialogs(env, model) {}
}

/** The 5 spins pack on the spin game (RN spinGameView's ProductItemLine). */
@Composable
fun SpinPackRow(env: AppEnvironment, model: StoreScreenModel) {
    val s by model.state.collectAsState()
    LaunchedEffect(model) { model.start() }
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
        if (s.loading) Spacer(Modifier.height(74.dp))
        s.rows.forEach { ProductItemLine(it, enabled = !s.processing, onBuy = model::buy) }
    }
    StoreDialogs(env, model) {}
}

package com.ecency.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ecency.core.app.AppIcon
import com.ecency.core.app.ProCopy
import com.ecency.core.app.ProModel
import com.ecency.core.app.UiCopy

/** The Pro card on Perks (issue #3555): membership or the store's price; a tap opens [ProSheet]. */
@Composable
fun ProCard(model: ProModel) {
    val colors = LocalEcencyColors.current
    val s by model.state.collectAsState()
    LaunchedEffect(model) { model.start() }
    if (s.account == null) return
    Row(
        Modifier.fillMaxWidth().padding(bottom = 16.dp).clip(RoundedCornerShape(12.dp)).background(colors.lightBackground)
            .clickable(role = Role.Button, onClickLabel = ProCopy.OPEN, onClick = model::open).padding(16.dp).testTag("proCard"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(AppIcon.VERIFIED, colors.blueText, 28.dp, filled = s.member)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(ProCopy.TITLE, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = colors.blueText)
            Text(s.cardText, fontSize = 13.sp, color = colors.darkGray, modifier = Modifier.padding(top = 2.dp).testTag(if (s.member) "proCardMember" else "proCardText"))
        }
        Glyph(AppIcon.CHEVRON_RIGHT, colors.icon, 22.dp)
    }
    if (s.open) ProSheet(model)
}

/** The Pro sheet: web's benefits, the store purchase (or a member's extension) and its outcome. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProSheet(model: ProModel) {
    val colors = LocalEcencyColors.current
    val s by model.state.collectAsState()
    ModalBottomSheet(
        onDismissRequest = model::dismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.background,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp).testTag("proSheet")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Glyph(AppIcon.VERIFIED, colors.blueText, 24.dp, filled = true)
                Text(ProCopy.TITLE, color = colors.text, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(start = 8.dp).semantics { heading() })
            }
            when {
                s.succeeded -> Column(Modifier.padding(top = 12.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }.testTag("proSuccess")) {
                    Text(s.successTitle, color = colors.text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(s.successBody, color = colors.darkGray, modifier = Modifier.padding(top = 4.dp))
                }
                s.activating -> Row(
                    Modifier.padding(top = 12.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }.testTag("proActivating"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(color = colors.blueText, modifier = Modifier.size(20.dp))
                    Text(ProCopy.ACTIVATING, color = colors.text, modifier = Modifier.padding(start = 10.dp))
                }
                else -> {
                    if (s.member) Text(ProCopy.MEMBER, color = colors.text, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp).testTag("proMember"))
                    Text(ProCopy.SUBTITLE, color = colors.darkGray, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                    Column(Modifier.padding(top = 10.dp)) {
                        ProCopy.BENEFITS.forEachIndexed { i, b ->
                            Row(Modifier.padding(vertical = 4.dp).semantics(mergeDescendants = true) {}.testTag("proBenefit:$i"), verticalAlignment = Alignment.Top) {
                                Glyph(AppIcon.CHECK_CIRCLE, colors.blueText, 18.dp)
                                Text(b, color = colors.text, fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                    if (s.member) Text(ProCopy.EXTEND_NOTE, color = colors.darkGray, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                }
            }
            s.notice?.let {
                Text(it, color = colors.text, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp).semantics { liveRegion = LiveRegionMode.Polite }.testTag("proNotice"))
            }
            when {
                s.showBuy -> Button(
                    onClick = model::buy, enabled = s.canBuy,
                    colors = ButtonDefaults.buttonColors(contentColor = Color.White, containerColor = colors.blue),
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp).heightIn(min = 48.dp).testTag("proBuy"),
                ) {
                    if (s.buying) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(18.dp).testTag("proBuying"))
                    else Text(s.buyLabel.orEmpty(), color = Color.White, fontWeight = FontWeight.Bold)
                }
                s.loadingPrice -> Box(Modifier.fillMaxWidth().padding(top = 16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = colors.blueText, modifier = Modifier.size(24.dp))
                }
                s.unavailable && !s.purchased -> Text(ProCopy.UNAVAILABLE, color = colors.darkGray, modifier = Modifier.padding(top = 16.dp).testTag("proUnavailable"))
            }
            TextButton(onClick = model::dismiss, modifier = Modifier.align(Alignment.End).padding(top = 8.dp).heightIn(min = 48.dp).testTag("proClose")) {
                Text(ProCopy.CLOSE, color = colors.blueText)
            }
        }
    }
    s.alert?.let { a ->
        AlertDialog(
            onDismissRequest = model::dismissAlert,
            title = { Text(a.title) },
            text = { Text(a.body) },
            confirmButton = { TextButton(onClick = model::dismissAlert, modifier = Modifier.testTag("proAlertOk")) { Text(UiCopy.OK) } },
        )
    }
}

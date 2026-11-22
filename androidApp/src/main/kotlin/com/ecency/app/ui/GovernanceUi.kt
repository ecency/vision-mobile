package com.ecency.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ecency.core.app.AppEnvironment
import com.ecency.core.app.GovernanceCardModel
import com.ecency.core.app.GovernanceCopy
import com.ecency.core.app.GovernanceDismiss

/** #3724: the wallet's governance card (expired or expiring votes), its confirm sheet and its dismiss choice. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GovernanceCard(env: AppEnvironment, openExternal: (String) -> Unit) {
    val colors = LocalEcencyColors.current
    val model = env.governanceCard
    val state by model.state.collectAsState()
    if (!state.visible) return
    val primary = ButtonDefaults.buttonColors(contentColor = Color.White, containerColor = colors.blue)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).background(colors.lightBackground).padding(16.dp).testTag("governanceCard")) {
        Text(state.title, color = colors.darkText, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() }.testTag("governanceTitle"))
        Text(state.body, color = colors.darkText, modifier = Modifier.padding(top = 6.dp).testTag("governanceBody"))
        if (!state.done) {
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (state.action != null) {
                    Button(onClick = model::askConfirm, enabled = !state.sending, colors = primary, modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp).testTag("governanceSetProxy")) {
                        Text(state.actionLabel, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { openExternal(GovernanceCardModel.VOTE_URL) }, modifier = Modifier.testTag("governanceVoteYourself")) {
                        Text(GovernanceCopy.VOTE_YOURSELF, color = colors.blueText)
                    }
                    TextButton(onClick = model::askDismiss, modifier = Modifier.testTag("governanceDismiss")) {
                        Text(GovernanceCopy.DISMISS, color = colors.blueText)
                    }
                }
            }
        }
    }
    if (state.confirming) {
        // never closed while it signs and sends (a swipe or back would leave the card waiting on a sheet nobody sees)
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { it != SheetValue.Hidden || !model.current.sending })
        ModalBottomSheet(onDismissRequest = model::cancelConfirm, sheetState = sheetState, containerColor = colors.background) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp).navigationBarsPadding().testTag("governanceSheet")) {
                Text(state.sheetTitle, color = colors.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() }.padding(bottom = 12.dp))
                state.sheetPoints.forEach { point -> Text("•  $point", color = colors.text, modifier = Modifier.padding(bottom = 8.dp)) }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = model::cancelConfirm, enabled = !state.sending, modifier = Modifier.weight(1f).testTag("governanceCancel")) {
                        Text(GovernanceCopy.CANCEL, color = colors.text)
                    }
                    Button(onClick = model::confirm, enabled = !state.sending, colors = primary, modifier = Modifier.weight(1f).testTag("governanceConfirm")) {
                        if (state.sending) Spinner(size = 18.dp, color = Color.White) else Text(state.confirmLabel, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
    if (state.askingDismiss) {
        ModalBottomSheet(onDismissRequest = { model.dismiss(null) }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.background) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp).navigationBarsPadding().testTag("governanceDismissSheet")) {
                Text(GovernanceCopy.DISMISS_TITLE, color = colors.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() }.padding(bottom = 16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { model.dismiss(GovernanceDismiss.FOREVER) }, modifier = Modifier.weight(1f).testTag("governanceForever")) {
                        Text(GovernanceCopy.DONT_SHOW_AGAIN, color = colors.text)
                    }
                    Button(onClick = { model.dismiss(GovernanceDismiss.LATER) }, colors = primary, modifier = Modifier.weight(1f).testTag("governanceLater")) {
                        Text(GovernanceCopy.LATER, color = Color.White)
                    }
                }
            }
        }
    }
}

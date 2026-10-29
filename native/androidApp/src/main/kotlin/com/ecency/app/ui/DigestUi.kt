package com.ecency.app.ui

import androidx.compose.ui.semantics.heading

import androidx.compose.ui.semantics.semantics

import androidx.compose.ui.semantics.Role

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ecency.core.app.AppEnvironment
import com.ecency.core.app.DigestCopy
import com.ecency.core.app.DigestState

/**
 * RN newsletterDigestSheet, app-wide like RN's registered sheet: it shows while the shared model says so (the
 * offer after an account's first post) and only for the account it was opened for. It lives inside the app's
 * content, so nothing of it is composed while the app lock hides the app.
 */
@Composable
fun DigestSheetHost(env: AppEnvironment, username: String?) {
    val state by env.digest.state.collectAsState()
    // never drawn for another account, even for the frame before the model closes on the session change
    // and only when it is its turn among the root prompts (shared RootPromptRule)
    val root by env.rootPrompts.state.collectAsState()
    if (root.prompt == com.ecency.core.app.RootPrompt.DIGEST && state.open && state.owner != null && state.owner == username) DigestSheet(env, state)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DigestSheet(env: AppEnvironment, state: DigestState) {
    val colors = LocalEcencyColors.current
    val model = env.digest
    ModalBottomSheet(onDismissRequest = model::dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.background) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).imePadding().navigationBarsPadding().padding(bottom = 24.dp).testTag("digestSheet"),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when {
                state.checkInboxEmail != null -> {
                    Title(DigestCopy.CHECK_INBOX_TITLE)
                    Description(DigestCopy.checkInboxBody(state.checkInboxEmail.orEmpty()), "digestCheckInbox")
                    Primary(DigestCopy.OK, enabled = true, tag = "digestOk", onClick = model::dismiss)
                }
                state.loading -> LoadingBlock(Modifier.padding(vertical = 24.dp).testTag("digestLoading"))
                state.unavailable -> {
                    Description(DigestCopy.UNAVAILABLE, "digestUnavailable")
                    Quiet(DigestCopy.CANCEL, colors.darkGray, "digestCancel", model::dismiss)
                }
                else -> {
                    Title(state.title)
                    Description(state.body, "digestBody")
                    state.status?.let { Text(it, color = colors.blueText, fontSize = 13.sp, modifier = Modifier.padding(bottom = 12.dp).testTag("digestStatus")) }
                    Row(Modifier.padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("weekly" to DigestCopy.WEEKLY, "monthly" to DigestCopy.MONTHLY).forEach { (value, label) ->
                            val selected = state.effectiveCadence == value
                            Text(
                                label, color = if (selected) Color.White else colors.text, fontSize = 14.sp,
                                modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(if (selected) colors.blue else colors.lightBackground)
                                    .border(1.dp, if (selected) colors.blueText else colors.icon, RoundedCornerShape(16.dp))
                                    .clickable(enabled = !state.isPending, role = Role.Button) { model.setCadence(value) }
                                    .padding(horizontal = 16.dp, vertical = 8.dp).testTag("digestCadence:$value"),
                            )
                        }
                    }
                    if (state.needsEmailInput) {
                        OutlinedTextField(
                            value = state.email, onValueChange = model::setEmail, singleLine = true, enabled = !state.isPending,
                            placeholder = { Text(DigestCopy.EMAIL_PLACEHOLDER, color = colors.darkGray) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                            textStyle = ltrFieldStyle(),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp).testTag("digestEmail"),
                        )
                    }
                    Primary(state.primaryLabel, enabled = state.canSubmit, tag = "digestSubmit", onClick = model::subscribe)
                    if (state.showsLeave) Quiet(DigestCopy.LEAVE, colors.redText, "digestLeave", model::leave, enabled = !state.isPending)
                    Quiet(DigestCopy.CANCEL, colors.darkGray, "digestCancel", model::dismiss)
                }
            }
        }
    }
    state.alert?.let { text ->
        AlertDialog(
            onDismissRequest = model::clearAlert, text = { Text(text, Modifier.testTag("digestAlert")) },
            confirmButton = { TextButton(onClick = model::clearAlert) { Text(DigestCopy.OK) } },
        )
    }
}

@Composable
private fun Title(text: String) {
    Text(text, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = LocalEcencyColors.current.text, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() }.padding(bottom = 8.dp).testTag("digestTitle"))
}

@Composable
private fun Description(text: String, tag: String) {
    Text(text, fontSize = 15.sp, lineHeight = 22.sp, color = LocalEcencyColors.current.darkGray, textAlign = TextAlign.Center, modifier = Modifier.padding(bottom = 16.dp).testTag(tag))
}

@Composable
private fun Primary(text: String, enabled: Boolean, tag: String, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, colors = ButtonDefaults.buttonColors(contentColor = androidx.compose.ui.graphics.Color.White, containerColor = LocalEcencyColors.current.blue), modifier = Modifier.fillMaxWidth().testTag(tag)) {
        Text(text, color = Color.White, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Quiet(text: String, color: Color, tag: String, onClick: () -> Unit, enabled: Boolean = true) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.padding(top = 4.dp).testTag(tag)) { Text(text, color = color) }
}

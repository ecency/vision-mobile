package com.ecency.app.ui

import androidx.compose.foundation.layout.heightIn

import androidx.compose.ui.semantics.Role

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.ecency.core.app.AppEnvironment
import com.ecency.core.app.BackupKeysCopy
import com.ecency.core.app.BackupKeysModel
import com.ecency.core.app.KeyRow
import com.ecency.core.app.KeysPhase
import com.ecency.core.app.AuthUiCopy
import com.ecency.core.app.UiCopy

/**
 * RN backupKeysScreen ("Manage Authorities") over [BackupKeysModel], which holds the gate (PIN or biometrics,
 * or RN's "set a PIN first" warning), the rows and the import. The screen only draws: it leaves when the model
 * says [KeysPhase.CLOSED] (cancelled PIN, lock, background, another account). The window is FLAG_SECURE while
 * this screen is on top (see `EcencyApp`), and its dialogs are secure windows too. A private key is copied
 * through [AppHost.copySensitive] (the expiring key clipboard) only after the warning.
 */
@Composable
fun BackupKeysScreen(env: AppEnvironment, host: AppHost, model: BackupKeysModel, onBack: () -> Unit) {
    val colors = LocalEcencyColors.current
    val state by model.state.collectAsState()
    LaunchedEffect(model) { model.start() }
    LaunchedEffect(state.phase) { if (state.phase == KeysPhase.CLOSED) onBack() }
    val secure = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn)

    Column(Modifier.fillMaxSize().background(colors.background).testTag("backupKeys")) {
        TopBar(BackupKeysCopy.TITLE, onBack = model::dismiss)
        if (state.isShown) {
            Column(Modifier.padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
                // the account uses its owner key for another role too: said every time the keys show
                state.sharedOwnerWarning?.let { warning ->
                    Text(
                        warning, color = colors.redText, fontSize = 13.sp,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("keysSharedOwnerWarning"),
                    )
                }
                state.rows.forEach { row ->
                    KeyBox(row, onButton = { model.toggle(row.role) }, onCopy = { model.keyToCopy(row.role)?.let { host.copy(it.text) } })
                }
            }
        }
    }

    if (state.phase == KeysPhase.NEEDS_PIN) {
        AlertDialog(
            onDismissRequest = model::dismiss,
            properties = secure,
            title = { Text(BackupKeysCopy.WARNING) },
            text = { Text(BackupKeysCopy.KEYS_WARNING, modifier = Modifier.testTag("keysWarning")) },
            confirmButton = { TextButton(onClick = { model.setPin(host.biometricAvailable()) }, modifier = Modifier.testTag("keysSetPin")) { Text(BackupKeysCopy.SET_PIN) } },
            dismissButton = { TextButton(onClick = model::dismiss, modifier = Modifier.testTag("keysCancel")) { Text(BackupKeysCopy.CANCEL, color = colors.redText) } },
        )
    }

    if (state.copyAsks != null) {
        AlertDialog(
            onDismissRequest = model::cancelCopy,
            properties = secure,
            title = { Text(BackupKeysCopy.WARNING) },
            text = { Text(BackupKeysCopy.COPY_WARNING, modifier = Modifier.testTag("keyCopyWarning")) },
            confirmButton = {
                TextButton(onClick = { model.confirmCopy()?.let { host.copySensitive(it.text) } }, modifier = Modifier.testTag("keyCopyConfirm")) { Text(BackupKeysCopy.COPY) }
            },
            dismissButton = { TextButton(onClick = model::cancelCopy) { Text(BackupKeysCopy.CANCEL) } },
        )
    }

    if (state.importOpen) ImportKeyDialog(state.isImporting, secure, onSign = model::importKey, onClose = model::closeImport)

    state.importError?.let { error ->
        AlertDialog(
            onDismissRequest = model::clearImportError,
            properties = secure,
            title = { Text(BackupKeysCopy.IMPORT_FAILED) },
            text = { Text(error, modifier = Modifier.testTag("keyImportError")) },
            confirmButton = { TextButton(onClick = model::clearImportError) { Text(UiCopy.OK) } },
        )
    }
}

/** RN TextBoxWithCopy: label and the reveal or import button above, the value and the copy button below. */
@Composable
private fun KeyBox(row: KeyRow, onButton: () -> Unit, onCopy: () -> Unit) {
    val colors = LocalEcencyColors.current
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp).testTag("key:${row.role.name}")) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(row.label, color = colors.text, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.testTag("keyLabel:${row.role.name}"))
            val note = row.note
            if (note != null) {
                // the owner row: public only, nothing to reveal and nothing to import (RN offers "Import Private")
                Text(note, color = colors.darkGray, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp).testTag("keyNote:${row.role.name}"))
            } else {
                Text(
                    row.button, color = colors.blueText, fontSize = 13.sp,
                    modifier = Modifier.clickable(onClick = onButton, role = Role.Button).padding(vertical = 8.dp, horizontal = 4.dp).testTag("keyButton:${row.role.name}"),
                )
            }
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp)).background(colors.darkGrayBackground.copy(alpha = 0.12f))
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                // One line, as RN. A public key is ordinary text. A private key is drawn but not given to
                // accessibility services (TalkBack, and any app with accessibility access, would otherwise read
                // it): its semantics are replaced by a description that only says it is showing.
                val tag = "keyValue:${row.role.name}"
                Text(
                    row.value, color = colors.text, fontSize = 13.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = if (row.isSensitive) {
                        Modifier.clearAndSetSemantics {
                            testTag = tag
                            contentDescription = AuthUiCopy.privateKeyShown(row.title)
                            password()
                        }
                    } else Modifier.testTag(tag),
                )
            }
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp)).background(colors.blue).clickable(onClick = onCopy, role = Role.Button)
                    .semantics { contentDescription = AuthUiCopy.keyCopyLabel(row.title) }.testTag("keyCopy:${row.role.name}"),
                contentAlignment = Alignment.Center,
            ) { Text("⧉", color = Color.White, fontSize = 18.sp) }
        }
    }
}

/** RN importPrivateKeyModal. The typed secret lives only in this dialog and is dropped when it closes. */
@Composable
private fun ImportKeyDialog(busy: Boolean, properties: DialogProperties, onSign: (String) -> Unit, onClose: () -> Unit) {
    var secret by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onClose,
        properties = properties,
        title = { Text(BackupKeysCopy.ENTER_PASS) },
        text = {
            Column {
                Text(BackupKeysCopy.IMPORT_DESC)
                OutlinedTextField(
                    value = secret, onValueChange = { secret = it }, singleLine = true, enabled = !busy,
                    placeholder = { Text(BackupKeysCopy.SIGN_PLACEHOLDER) },
                    visualTransformation = PasswordVisualTransformation(),
                    textStyle = ltrFieldStyle(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Password, autoCorrectEnabled = false,
                    ),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("keyImportField"),
                )
            }
        },
        confirmButton = {
            if (busy) CircularProgressIndicator(Modifier.size(24.dp))
            else TextButton(onClick = { onSign(secret) }, enabled = secret.isNotBlank(), modifier = Modifier.testTag("keyImportSign")) { Text(BackupKeysCopy.SIGN) }
        },
        dismissButton = { TextButton(onClick = onClose, enabled = !busy) { Text(BackupKeysCopy.CANCEL) } },
    )
}

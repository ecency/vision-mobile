package com.ecency.app.ui

import androidx.compose.ui.semantics.heading

import androidx.compose.foundation.layout.widthIn

import androidx.compose.ui.semantics.hideFromAccessibility

import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.selected

import androidx.compose.ui.semantics.stateDescription

import androidx.compose.ui.semantics.contentDescription

import androidx.compose.ui.semantics.semantics

import com.ecency.core.app.AppIcon
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ecency.core.app.AppEnvironment
import com.ecency.core.app.AppLockState
import com.ecency.core.app.DeleteAccountCopy
import com.ecency.core.app.DigestCopy
import com.ecency.core.app.EmailDigestsCopy
import com.ecency.core.app.EmailDigestsModel
import com.ecency.core.app.EmailDigestsState
import com.ecency.core.app.NsfwMode
import com.ecency.core.app.SettingsCopy
import com.ecency.core.app.SettingsOptions
import com.ecency.core.app.ThemeMode

/** What the settings screen asks of the shell: the PIN flows, other screens, the platform's mail and store apps. */
class SettingsActions(
    val onPinLock: () -> Unit,
    val onBiometric: () -> Unit,
    val onChangePin: () -> Unit,
    val onBackupKeys: () -> Unit,
    val onDeleteAccount: () -> Unit,
    val onEmailDigests: () -> Unit,
    val onBack: () -> Unit,
)

/**
 * RN settingsScreen: the General card (currency, language, server, image server, NSFW, appearance, show images and
 * the app lock rows), Push Notification, Support Ecency, then rate, feedback, email digests, Manage Authorities and
 * Delete Account. Cards and rows follow RN's settingsStyles and SettingsItem.
 */
@Composable
fun SettingsScreen(env: AppEnvironment, host: AppHost, lock: AppLockState, loggedIn: Boolean, biometricAvailable: Boolean, actions: SettingsActions) {
    val colors = LocalEcencyColors.current
    val settings = env.settings
    val state by settings.state.collectAsState()
    val notifications by env.notifications.state.collectAsState()
    val session by env.session.state.collectAsState()
    DisposableEffect(settings) {
        settings.onShow()
        onDispose { settings.onHide() }
    }
    Column(Modifier.fillMaxSize().background(colors.background).testTag("settings")) {
        TopBar(SettingsCopy.TITLE, onBack = actions.onBack)
        // RN's RefreshControl spins while a server is checked
        if (state.checkingServer) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("serverChecking"), color = colors.blueText)
        Column(Modifier.fillMaxSize().background(colors.lightBackground).verticalScroll(rememberScrollState()).padding(bottom = 72.dp)) {
            SettingsCard {
                CardTitle(SettingsCopy.GENERAL)
                DropdownRow(
                    SettingsCopy.CURRENCY, SettingsOptions.CURRENCIES.map { it.label }, state.currencyIndex, null, "settings:currency",
                ) { settings.setCurrency(SettingsOptions.CURRENCIES[it].value) }
                DropdownRow(
                    SettingsCopy.LANGUAGE, SettingsOptions.LANGUAGES.map { it.label }, state.languageIndex, null, "settings:language",
                ) { settings.setLanguage(SettingsOptions.LANGUAGES[it].value) }
                // RN "Chat Privacy" (signed in): who may send direct messages, read and saved through the chat API
                if (loggedIn) ChatPrivacyRow(env, session.username)
                DropdownRow(
                    SettingsCopy.SERVER, state.servers.map(SettingsOptions::serverLabel), state.serverIndex,
                    state.serverLabel, "settings:server", enabled = !state.checkingServer,
                ) { settings.selectServer(state.servers[it]) }
                DropdownRow(
                    SettingsCopy.IMAGE_SERVER, SettingsOptions.IMAGE_SERVERS.map { it.label }, state.imageServerIndex, null, "settings:imageServer",
                ) { settings.setImageServer(SettingsOptions.IMAGE_SERVERS[it].value) }
                DropdownRow(
                    SettingsCopy.NSFW, NsfwMode.entries.map { it.label }, state.nsfw.ordinal, null, "settings:nsfw",
                    itemTag = { "nsfw:${NsfwMode.entries[it].name}" },
                ) { settings.setNsfw(NsfwMode.entries[it]) }
                DropdownRow(
                    SettingsCopy.APPEARANCE, ThemeMode.entries.map { it.title }, state.theme.ordinal, null, "settings:theme",
                    itemTag = { "theme:${ThemeMode.entries[it].name}" },
                ) { settings.setTheme(ThemeMode.entries[it]) }
                ToggleRow(SettingsCopy.SHOW_IMAGES, notifications.showImages, "settings:showImages") { env.notifications.setShowImages(it) }
                if (loggedIn) {
                    // RN toggles latch back: they show the lock's state and start its PIN flow
                    ToggleRow(SettingsCopy.PIN_CODE, lock.enabled, "menuPinLock") { actions.onPinLock() }
                    if (lock.enabled) {
                        if (biometricAvailable) ToggleRow(SettingsCopy.BIOMETRIC, lock.biometricEnabled, "menuBiometric") { actions.onBiometric() }
                        ButtonRow(SettingsCopy.RESET_PIN, SettingsCopy.RESET, "menuChangePin", actions.onChangePin)
                    }
                }
            }
            if (loggedIn) {
                SettingsCard { PushSettingsSection(env) }
                // only the signed-in account's own values (iOS does the same)
                state.support?.takeIf { state.supportAccount != null && state.supportAccount == session.username }?.let {
                    SettingsCard(Modifier.testTag("supportCard")) {
                        CardTitle(SettingsCopy.SUPPORT)
                        ToggleRow(SettingsCopy.SUPPORT_BENEFICIARY, state.beneficiaryPercent > 0, "support:beneficiary") { on -> settings.setSupportBeneficiary(on) }
                        if (state.beneficiaryPercent > 0) {
                            DropdownRow(
                                SettingsCopy.SUPPORT_BENEFICIARY_PERCENT, SettingsOptions.BENEFICIARY_PERCENTS.map { p -> "$p%" },
                                SettingsOptions.BENEFICIARY_PERCENTS.indexOf(state.beneficiaryPercent), "${state.beneficiaryPercent}%", "support:beneficiaryPercent",
                            ) { i -> settings.setSupportBeneficiaryPercent(SettingsOptions.BENEFICIARY_PERCENTS[i]) }
                        }
                        ToggleRow(SettingsCopy.SUPPORT_CURATION, state.curationPercent > 0, "support:curation") { on -> settings.setSupportCuration(on) }
                        if (state.curationPercent > 0) {
                            DropdownRow(
                                SettingsCopy.SUPPORT_CURATION_PERCENT, SettingsOptions.CURATION_PERCENTS.map { p -> "$p%" },
                                SettingsOptions.CURATION_PERCENTS.indexOf(state.curationPercent), "${state.curationPercent}%", "support:curationPercent",
                            ) { i -> settings.setSupportCurationPercent(SettingsOptions.CURATION_PERCENTS[i]) }
                        }
                    }
                }
            }
            SettingsCard(Modifier.padding(bottom = 20.dp)) {
                ButtonRow(SettingsCopy.RATE_APP, SettingsCopy.RATE, "settings:rate") { host.openStoreListing() }
                ButtonRow(SettingsCopy.SEND_FEEDBACK, SettingsCopy.SEND, "settings:feedback") {
                    val url = SettingsOptions.feedbackMailto(env.session.current.username, "${host.appVersion()} (${host.buildNumber()})", host.feedbackPlatform())
                    env.ui.toast(if (host.openMail(url)) SettingsCopy.FEEDBACK_SUCCESS else SettingsCopy.FEEDBACK_FAIL)
                }
                if (loggedIn) {
                    ButtonRow(SettingsCopy.EMAIL_DIGESTS, SettingsCopy.MANAGE, "settings:emailDigests", actions.onEmailDigests)
                    // RN settings: the account's keys and authorities, behind the PIN
                    ButtonRow(SettingsCopy.MANAGE_AUTHORITIES, SettingsCopy.OPEN, "menuBackupKeys", actions.onBackupKeys)
                    Row(
                        Modifier.fillMaxWidth().clickable(onClick = actions.onDeleteAccount, role = Role.Button).padding(vertical = 8.dp).testTag("menuDeleteAccount"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RowTitle(DeleteAccountCopy.SETTINGS_ROW)
                        Glyph(AppIcon.DISCARD, colors.darkGray, 24.dp, Modifier.padding(end = 13.dp), DeleteAccountCopy.SETTINGS_ROW)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.fillMaxWidth().padding(vertical = 5.dp).background(LocalEcencyColors.current.background)
            .padding(start = 42.dp, end = 32.dp, top = 4.dp, bottom = 4.dp),
    ) { content() }
}

@Composable
private fun CardTitle(text: String) =
    Text(text, color = LocalEcencyColors.current.blueText, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 12.dp).semantics { heading() })

@Composable
private fun androidx.compose.foundation.layout.RowScope.RowTitle(text: String) =
    Text(text, color = LocalEcencyColors.current.darkGray, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).padding(end = 8.dp))

/** RN SettingsItem type "toggle": the whole row is the switch. */
@Composable
private fun ToggleRow(title: String, on: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    val colors = LocalEcencyColors.current
    Row(
        Modifier.fillMaxWidth().toggleable(value = on, role = Role.Switch, onValueChange = onChange).padding(vertical = 2.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowTitle(title)
        Switch(checked = on, onCheckedChange = null, colors = SwitchDefaults.colors(checkedTrackColor = colors.blue), modifier = Modifier.padding(vertical = 12.dp))
    }
}

/** RN SettingsItem type "button": the action as blue text on the right. */
@Composable
private fun ButtonRow(title: String, button: String, tag: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick, role = Role.Button).padding(vertical = 14.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        RowTitle(title)
        Text(button, color = LocalEcencyColors.current.blueText, fontSize = 14.sp, modifier = Modifier.padding(end = 8.dp))
    }
}

@Composable
private fun ChatPrivacyRow(env: AppEnvironment, username: String?) {
    val model = remember(username) { env.chat.privacyModel() }
    DisposableEffect(model) {
        model.start()
        onDispose { model.close() }
    }
    val s by model.state.collectAsState()
    DropdownRow(
        com.ecency.core.app.ChatPrivacyModel.TITLE, DmPrivacyLabels, s.value.ordinal, null, "settings:dmPrivacy", enabled = !s.saving,
        itemTag = { "dmPrivacy:${com.ecency.core.api.chat.DmPrivacy.entries[it].name}" },
    ) { model.select(com.ecency.core.api.chat.DmPrivacy.entries[it]) }
}

/** RN SettingsItem type "dropdown": a bordered box with the choice; [display] replaces it when given. */
@Composable
private fun DropdownRow(
    title: String,
    options: List<String>,
    selected: Int,
    display: String?,
    tag: String,
    enabled: Boolean = true,
    itemTag: (Int) -> String = { "$tag:$it" },
    onSelect: (Int) -> Unit,
) {
    val colors = LocalEcencyColors.current
    var open by remember { mutableStateOf(false) }
    val value = display ?: options.getOrNull(selected).orEmpty()
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        // the title is read with the box (one TalkBack stop: "Language, English, drop down list")
        androidx.compose.material3.Text(
            title, color = colors.darkGray, fontSize = 14.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f).padding(end = 8.dp).semantics { hideFromAccessibility() },
        )
        // up to half the row, wrapping a long choice (de, ru, large text) instead of cutting it
        Box(Modifier.weight(1f, fill = false)) {
            Row(
                Modifier.widthIn(min = 120.dp).heightIn(min = 44.dp).clip(RoundedCornerShape(8.dp)).border(1.dp, colors.border, RoundedCornerShape(8.dp))
                    .clickable(enabled = enabled && options.isNotEmpty(), role = Role.DropdownList) { open = true }
                    .semantics {
                        contentDescription = title
                        stateDescription = value
                        if (open) collapse { open = false; true } else expand { open = true; true }
                    }
                    .padding(start = 16.dp, end = 10.dp, top = 8.dp, bottom = 8.dp).testTag(tag),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    value, color = colors.darkGray, fontSize = 14.sp,
                    modifier = Modifier.weight(1f, fill = false).padding(end = 6.dp).testTag("$tag:value"),
                )
                Glyph(AppIcon.EXPAND, colors.darkGray, 18.dp)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 420.dp)) {
                options.forEachIndexed { i, label ->
                    DropdownMenuItem(
                        text = { Text(label, fontSize = 13.sp, color = if (i == selected) colors.blueText else colors.darkGray) },
                        onClick = {
                            open = false
                            if (i != selected) onSelect(i)
                        },
                        modifier = Modifier.semantics { this.selected = i == selected }.testTag(itemTag(i)),
                    )
                }
            }
        }
    }
}

/** RN emailDigestsScreen. */
@Composable
fun EmailDigestsScreen(model: EmailDigestsModel, username: String?, onBack: () -> Unit) {
    val colors = LocalEcencyColors.current
    val state by model.state.collectAsState()
    var confirm by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(model) { model.start() }
    Column(Modifier.fillMaxSize().background(colors.background).testTag("emailDigests")) {
        TopBar(EmailDigestsCopy.TITLE, onBack = onBack)
        when {
            state.account == null || state.account != username -> Unit
            state.loading -> Box(Modifier.fillMaxWidth().padding(top = 32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.blueText, modifier = Modifier.testTag("digestsLoading"))
            }
            state.error != null -> DigestsMessage(state.error!!, "digestsError")
            else -> DigestsList(state, model) { confirm = it }
        }
    }
    confirm?.let { email ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(EmailDigestsCopy.STOP_ALL_TITLE) },
            text = { Text(EmailDigestsCopy.stopAllBody(email)) },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(DigestCopy.CANCEL) } },
            confirmButton = {
                TextButton(onClick = { confirm = null; model.stopAll(email) }, modifier = Modifier.testTag("stopAllConfirm")) {
                    Text(EmailDigestsCopy.STOP_ALL, color = colors.redText)
                }
            },
        )
    }
    state.alert?.let {
        AlertDialog(
            onDismissRequest = model::clearAlert, text = { Text(it) },
            confirmButton = { TextButton(onClick = model::clearAlert) { Text(DigestCopy.OK) } },
            modifier = Modifier.testTag("digestsAlert"),
        )
    }
}

@Composable
private fun DigestsMessage(text: String, tag: String) = Text(
    text, color = LocalEcencyColors.current.darkGray, fontSize = 15.sp, textAlign = TextAlign.Center,
    modifier = Modifier.fillMaxWidth().padding(top = 24.dp, start = 40.dp, end = 40.dp).testTag(tag),
)

@Composable
private fun DigestsList(state: EmailDigestsState, model: EmailDigestsModel, onStopAll: (String) -> Unit) {
    val colors = LocalEcencyColors.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        if (state.subscriptions.isEmpty()) {
            DigestsMessage(EmailDigestsCopy.EMPTY, "digestsEmpty")
            Spacer(Modifier.height(16.dp))
        }
        state.addresses.forEach { group ->
            DigestCard {
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(group.email, color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(end = 12.dp))
                    Text(
                        EmailDigestsCopy.STOP_ALL, color = colors.redText, fontSize = 13.sp,
                        modifier = Modifier.clickable(enabled = state.stopping == null, role = Role.Button) { onStopAll(group.email) }.testTag("stopAll:${group.email}"),
                    )
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
                group.rows.forEach { row ->
                    DigestRow(EmailDigestsCopy.rowLabel(row), EmailDigestsCopy.meta(row), EmailDigestsCopy.MANAGE, "digest:${row.id}") { model.open(row.type, row.target) }
                }
            }
        }
        if (state.showsDiscover) {
            DigestCard(Modifier.testTag("digestsDiscover")) {
                Text(EmailDigestsCopy.DISCOVER, color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 4.dp))
                if (state.showsOwnOffer) DigestRow(DigestCopy.LIST_OWN, null, DigestCopy.SUBSCRIBE, "digestOffer:own") { model.open("own", state.account!!) }
                if (state.showsSiteOffer) DigestRow(DigestCopy.LIST_SITE, null, DigestCopy.SUBSCRIBE, "digestOffer:site") { model.open("site", EmailDigestsState.SITE_TARGET) }
            }
        }
    }
}

@Composable
private fun DigestCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth().padding(bottom = 16.dp).clip(RoundedCornerShape(12.dp)).background(LocalEcencyColors.current.lightBackground).padding(12.dp)) { content() }
}

@Composable
private fun DigestRow(title: String, meta: String?, action: String, tag: String, onClick: () -> Unit) {
    val colors = LocalEcencyColors.current
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick, role = Role.Button).padding(vertical = 10.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = colors.text, fontSize = 15.sp)
            meta?.let { Text(it, color = colors.darkGray, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp)) }
        }
        Text(action, color = colors.blueText, fontSize = 14.sp)
    }
}

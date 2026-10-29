package com.ecency.app.ui

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import com.ecency.core.app.AnnouncementTarget
import com.ecency.core.app.AppEnvironment
import com.ecency.core.app.DeleteAccountCopy
import com.ecency.core.app.NavEntry
import com.ecency.core.app.RcErrors
import com.ecency.core.app.RcOfferChoice
import com.ecency.core.app.RootPrompt
import com.ecency.core.app.UiCopy

/**
 * The shell's prompts, drawn only with the unlocked app: RN's "Confirm Delete" for the account deletion request,
 * the app-wide RC offer, the update prompt and the announcement. Their rules live in the shared models; these dialogs only draw them.
 */
@Composable
fun ShellPrompts(env: AppEnvironment, host: AppHost, onEntry: (NavEntry) -> Unit) {
    val colors = LocalEcencyColors.current
    LaunchedEffect(env) { env.shellStarted() }

    // ---- delete account (RN settings, ACTION_MODAL "Confirm Delete") ----
    val delete by env.deleteAccount.state.collectAsState()
    if (delete.asking != null) {
        AlertDialog(
            onDismissRequest = env.deleteAccount::cancel,
            title = { Text(DeleteAccountCopy.CONFIRM_TITLE) },
            text = { Text(DeleteAccountCopy.CONFIRM_BODY, modifier = Modifier.testTag("deleteConfirm")) },
            confirmButton = {
                if (delete.isSending) CircularProgressIndicator(Modifier.size(24.dp))
                else TextButton(onClick = env.deleteAccount::confirm, modifier = Modifier.testTag("deleteConfirmYes")) { Text(DeleteAccountCopy.DELETE, color = colors.redText) }
            },
            dismissButton = {
                TextButton(onClick = env.deleteAccount::cancel, enabled = !delete.isSending, modifier = Modifier.testTag("deleteConfirmNo")) { Text(DeleteAccountCopy.CANCEL) }
            },
        )
    }
    delete.error?.let { error ->
        AlertDialog(
            onDismissRequest = env.deleteAccount::clearError,
            title = { Text(DeleteAccountCopy.FAIL) },
            text = { Text(error, modifier = Modifier.testTag("deleteError")) },
            confirmButton = { TextButton(onClick = env.deleteAccount::clearError) { Text(UiCopy.OK) } },
        )
    }

    // ---- RN ui.rcOffer: a broadcast refused for lack of resource credits, from anywhere in the app ----
    val rcOffer by env.rcOffer.state.collectAsState()
    if (rcOffer.open) {
        val choose = { choice: RcOfferChoice -> env.rcOffer.choose(choice)?.let { onEntry(NavEntry.screen(it)) }; Unit }
        AlertDialog(
            // closed without a choice: nothing opens (RN acts only on an explicit button)
            onDismissRequest = { choose(RcOfferChoice.CANCEL) },
            title = { Text(RcErrors.OFFER_TITLE) },
            text = {
                androidx.compose.foundation.layout.Column {
                    Text(RcErrors.OFFER_BODY, modifier = Modifier.testTag("rcOffer"))
                    TextButton(onClick = { choose(RcOfferChoice.TOP_UP) }, modifier = Modifier.testTag("rcTopUp")) { Text(RcErrors.TOP_UP) }
                    TextButton(onClick = { choose(RcOfferChoice.BOOST) }, modifier = Modifier.testTag("rcBoost")) { Text(RcErrors.BOOST) }
                }
            },
            confirmButton = { TextButton(onClick = { choose(RcOfferChoice.CANCEL) }, modifier = Modifier.testTag("rcCancel")) { Text(RcErrors.CANCEL) } },
        )
    }

    // which of the prompts below has its turn: the shared rule (RootPromptRule), one at a time
    val root by env.rootPrompts.state.collectAsState()

    // ---- notices about an account's keys on this device (AccountNoticesModel), one at a time ----
    val notices by env.accountNotices.state.collectAsState()
    notices.notice?.takeIf { root.prompt == RootPrompt.ACCOUNT_NOTICE }?.let { notice ->
        AlertDialog(
            onDismissRequest = { env.accountNotices.dismiss(notice.id) },
            title = { Text(notice.title, modifier = Modifier.testTag("accountNoticeTitle")) },
            text = { Text(notice.body, modifier = Modifier.testTag("accountNotice:${notice.kind.name}")) },
            confirmButton = { TextButton(onClick = { env.accountNotices.dismiss(notice.id) }, modifier = Modifier.testTag("accountNoticeOk")) { Text(UiCopy.OK) } },
        )
    }

    // ---- update prompt (RN _compareAndPromptForUpdate) ----
    val update by env.updates.state.collectAsState()
    update.prompt?.takeIf { root.prompt == RootPrompt.UPDATE }?.let { prompt ->
        AlertDialog(
            // closed without a choice: nothing is remembered, as RN
            onDismissRequest = env.updates::dismiss,
            title = { Text(prompt.title, modifier = Modifier.testTag("updateTitle")) },
            text = { Text(prompt.body) },
            confirmButton = { TextButton(onClick = { env.updates.dismiss(); host.openStoreForUpdate() }, modifier = Modifier.testTag("updateNow")) { Text(prompt.update) } },
            dismissButton = { TextButton(onClick = env.updates::later, modifier = Modifier.testTag("updateLater")) { Text(prompt.later) } },
        )
    }

    // ---- announcement (RN useAnnouncementsQuery) ----
    val announcements by env.announcements.state.collectAsState()
    announcements.prompt?.takeIf { root.prompt == RootPrompt.ANNOUNCEMENT }?.let { prompt ->
        AlertDialog(
            // RN onClosed: seen, asked again after two days
            onDismissRequest = env.announcements::later,
            title = { if (prompt.title.isNotEmpty()) Text(prompt.title, modifier = Modifier.testTag("announcementTitle")) },
            text = { Text(prompt.body, modifier = Modifier.testTag("announcementBody")) },
            confirmButton = {
                TextButton(
                    onClick = {
                        when (val target = env.announcements.act()) {
                            is AnnouncementTarget.InApp -> onEntry(target.entry)
                            is AnnouncementTarget.Web -> host.openExternal(target.url)
                            null -> {}
                        }
                    },
                    modifier = Modifier.testTag("announcementAction"),
                ) { Text(prompt.action) }
            },
            dismissButton = { TextButton(onClick = env.announcements::later, modifier = Modifier.testTag("announcementLater")) { Text(prompt.later) } },
        )
    }
}

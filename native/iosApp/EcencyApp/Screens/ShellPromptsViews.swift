import EcencyCore
import SwiftUI

/// The shell's prompts: RN's "Confirm Delete" for the account deletion request, the update prompt and the
/// announcement. Their rules live in the shared models; each alert hangs on its own empty view so none of them
/// competes with the root's other alerts. They sit in the app window, under the lock window.
struct ShellPromptsHost: View {
    @EnvironmentObject private var app: AppContainer
    @StateObject private var deletion: Observed<DeleteAccountState>
    @StateObject private var updates: Observed<UpdateState>
    @StateObject private var announcements: Observed<AnnouncementsState>
    @StateObject private var notices: Observed<AccountNoticesState>
    /// Which prompt has its turn: the shared rule (RootPromptRule), one at a time, none locked or before the terms.
    @StateObject private var root: Observed<RootPromptState>
    private let env: AppEnvironment

    init(env: AppEnvironment) {
        self.env = env
        _deletion = StateObject(wrappedValue: Observed(env.deleteAccount, ownsModel: false))
        _updates = StateObject(wrappedValue: Observed(env.updates, ownsModel: false))
        _announcements = StateObject(wrappedValue: Observed(env.announcements, ownsModel: false))
        _notices = StateObject(wrappedValue: Observed(env.accountNotices, ownsModel: false))
        _root = StateObject(wrappedValue: Observed(env.rootPrompts, ownsModel: false))
    }

    var body: some View {
        let copy = DeleteAccountCopy.shared
        // one prompt at a time, in the shared order: the notice about the user's keys, the update, the announcement
        let turn = root.state.prompt
        let notice = turn == .accountNotice ? notices.state.notice : nil
        let update = turn == .update ? updates.state.prompt : nil
        let announcement = turn == .announcement ? announcements.state.prompt : nil
        ZStack {
            // notices about an account's keys on this device (AccountNoticesModel), one at a time
            Color.clear.alert(notice?.title ?? "", isPresented: Binding(get: { notice != nil }, set: { if !$0, let id = notice?.id { env.accountNotices.dismiss(id: id) } })) {
                Button(UiCopy.shared.OK, role: .cancel) {}.accessibilityIdentifier("account-notice-ok")
            } message: { Text(notice?.body ?? "") }
            Color.clear.alert(copy.CONFIRM_TITLE, isPresented: Binding(get: { deletion.state.asking != nil }, set: { if !$0 { env.deleteAccount.cancel() } })) {
                Button(copy.CANCEL, role: .cancel) { env.deleteAccount.cancel() }.accessibilityIdentifier("delete-cancel")
                Button(copy.DELETE, role: .destructive) { env.deleteAccount.confirm() }.accessibilityIdentifier("delete-confirm")
            } message: { Text(copy.CONFIRM_BODY) }
            Color.clear.alert(copy.FAIL, isPresented: Binding(get: { deletion.state.error != nil }, set: { if !$0 { env.deleteAccount.clearError() } })) {
                Button(UiCopy.shared.OK, role: .cancel) {}
            } message: { Text(deletion.state.error ?? "") }
            // closed without a choice: nothing is remembered, as RN
            Color.clear.alert(update?.title ?? "", isPresented: Binding(get: { update != nil }, set: { if !$0 { env.updates.dismiss() } })) {
                Button(update?.later ?? "") { env.updates.later() }.accessibilityIdentifier("update-later")
                Button(update?.update ?? "") { env.updates.dismiss(); app.openStoreForUpdate() }.accessibilityIdentifier("update-now")
            } message: { Text(update?.body ?? "") }
            // RN onClosed: seen, asked again after two days
            Color.clear.alert(announcement?.title ?? "", isPresented: Binding(get: { announcement != nil }, set: { if !$0 { env.announcements.later() } })) {
                Button(announcement?.later ?? "", role: .cancel) { env.announcements.later() }.accessibilityIdentifier("announcement-later")
                Button(announcement?.action ?? "") { app.openAnnouncementTarget(env.announcements.act()) }.accessibilityIdentifier("announcement-action")
            } message: { Text(announcement?.body ?? "") }
        }
        .allowsHitTesting(false)
        .onAppear { env.shellStarted() }
    }
}

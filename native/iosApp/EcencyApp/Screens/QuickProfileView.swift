import EcencyCore
import SwiftUI

/// RN quickProfileModal: avatar with the voting power ring, stats, follow and favourite, full profile.
struct QuickProfileSheet: View {
    @EnvironmentObject private var app: AppContainer
    @Environment(\.appTheme) private var theme
    @Environment(\.motion) private var motion
    @Environment(\.dismiss) private var dismiss
    @StateObject private var model: Observed<QuickProfileState>

    init(username: String, env: AppEnvironment) {
        _model = StateObject(wrappedValue: Observed(env.quickProfileModel(username: username), ownsModel: true))
    }

    private var profile: QuickProfileModel { model.model as! QuickProfileModel }

    var body: some View {
        let s = model.state
        ScrollView {
            VStack(spacing: 12) {
                ZStack {
                    Circle().stroke(theme.lightBackground, lineWidth: 8)
                    Circle().trim(from: 0, to: CGFloat((s.votingPower?.doubleValue ?? 0) / 100))
                        .stroke(theme.accent, style: StrokeStyle(lineWidth: 8, lineCap: .round))
                        .rotationEffect(.degrees(-90))
                    AvatarView(url: s.avatarUrl, size: 120)
                }
                .frame(width: 144, height: 144)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(L.Native.Ios.Profile.votingPower(percent: QuickProfileText.percent(s.votingPower?.doubleValue)))
                Text("@\(s.username)").scaledFont(18, weight: .bold).foregroundStyle(theme.text).accessibilityAddTraits(.isHeader)
                if let about = s.about { Text(about).scaledFont(13).foregroundStyle(theme.secondary).lineLimit(2).multilineTextAlignment(.center) }
                Text(s.joined).scaledFont(12).foregroundStyle(theme.secondary)
                AdaptiveStack(horizontalAlignment: .center) {
                    stat(UiCopy.shared.FOLLOWER, s.followers.map { Fmt.int($0.intValue) })
                    stat(UiCopy.shared.FOLLOWING, s.following.map { Fmt.int($0.intValue) })
                    stat(UiCopy.shared.POSTS, s.posts.map { Fmt.int($0.intValue) })
                }
                AdaptiveStack(horizontalAlignment: .center) {
                    stat(UiCopy.shared.RESOURCE_CREDITS, s.resourceCredits)
                    stat(UiCopy.shared.REPUTATION, s.reputation.map { Fmt.int($0.intValue) })
                }
                if s.showActions && !s.isOwn {
                    HStack(spacing: 24) {
                        // the symbol swaps as the follow lands, with a light tap (Motion.swift)
                        Button { Haptics.play(.toggle); profile.follow() } label: {
                            Image(appIcon: s.isMuted ? .muted : .personAdd)
                                .scaledFont(22)
                                .contentTransition(motion.isOff ? .identity : .symbolEffect(.replace))
                                .tapTarget()
                        }
                        .disabled(s.isFollowing || s.isMuted || s.actionRunning)
                        .accessibilityLabel(s.isFollowing ? UiCopy.shared.FOLLOWING : UiCopy.shared.FOLLOW)
                        .accessibilityIdentifier("quick-follow")
                        Button { Haptics.play(.toggle); profile.toggleFavorite() } label: {
                            Image(appIcon: .heart, filled: s.isFavorite).scaledFont(22)
                                .foregroundStyle(s.isFavorite ? theme.danger : theme.accent)
                                .contentTransition(motion.isOff ? .identity : .symbolEffect(.replace))
                                .popOnChange(s.isFavorite, enabled: s.isFavorite)
                                .tapTarget()
                        }
                        .animation(motion.pop, value: s.isFavorite)
                        .disabled(s.actionRunning)
                        .accessibilityLabel(s.isFavorite ? L.Native.Ios.Profile.removeFavourite : L.Native.Ios.Profile.addFavourite)
                        .accessibilityIdentifier("quick-favorite")
                        // RN ActionPanel: the direct message with this account
                        Button {
                            dismiss()
                            app.openDirectMessage(s.username)
                        } label: {
                            Image(appIcon: .chat).scaledFont(22).tapTarget()
                        }
                        .disabled(s.actionRunning)
                        .accessibilityLabel(UiCopy.shared.MESSAGE)
                        .accessibilityIdentifier("quick-message")
                    }
                }
                Button(UiCopy.shared.VIEW_FULL_PROFILE) {
                    dismiss()
                    app.push(AppScreenProfile(author: s.username))
                }
                .buttonStyle(.prominentFill)
                .accessibilityIdentifier("quick-full-profile")
            }
            .padding(20)
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .onAppear { profile.start() }
        .accessibilityIdentifier("quick-profile")
    }

    private func stat(_ label: String, _ value: String?) -> some View {
        VStack(spacing: 2) {
            Text(value ?? "---").scaledFont(16, weight: .bold).foregroundStyle(theme.text)
            Text(label).scaledFont(11).foregroundStyle(theme.secondary)
        }
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .combine)
    }
}

enum QuickProfileText {
    static func percent(_ value: Double?) -> String {
        guard let value else { return "---" }
        return LocaleFormat.shared.percent(value: value, maxFraction: 1, minFraction: 1)
    }
}

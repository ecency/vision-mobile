import EcencyCore
import SwiftUI

/// The Pro card on Perks (issue #3555): membership or the store's price; a tap opens `ProSheet`.
struct ProCard: View {
    @StateObject private var observed: Observed<ProState>
    @Environment(\.appTheme) private var theme

    init(env: AppEnvironment) {
        _observed = StateObject(wrappedValue: Observed(env.proModel(), ownsModel: true))
    }

    private var model: ProModel { observed.model as! ProModel }

    var body: some View {
        let s = observed.state
        // never empty: an empty Group gets no onAppear, and the model starts there (it learns the account)
        VStack(spacing: 0) {
            if s.account == nil {
                Color.clear.frame(height: 0).accessibilityHidden(true)
            } else {
                Button { model.open() } label: {
                    HStack(spacing: 12) {
                        Image(appIcon: .verified, filled: s.member).scaledFont(24).foregroundStyle(theme.accentText).accessibilityHidden(true)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(ProCopy.shared.TITLE).scaledFont(16, weight: .bold).foregroundStyle(theme.accentText)
                            Text(s.cardText).scaledFont(13).foregroundStyle(theme.secondary)
                                .fixedSize(horizontal: false, vertical: true)
                                .accessibilityIdentifier(s.member ? "pro-card-member" : "pro-card-text")
                            if let ends = s.expiryText {
                                Text(ends).scaledFont(13).foregroundStyle(theme.secondary)
                                    .fixedSize(horizontal: false, vertical: true)
                                    .accessibilityIdentifier("pro-card-expiry")
                            }
                        }
                        Spacer(minLength: 0)
                        Image(appIcon: .chevronRight).foregroundStyle(theme.icon).accessibilityHidden(true)
                    }
                    .padding(16)
                    .frame(maxWidth: .infinity, minHeight: minTapTarget, alignment: .leading)
                    .background(theme.lightBackground)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityElement(children: .combine)
                .accessibilityHint(ProCopy.shared.OPEN)
                .accessibilityIdentifier("pro-card")
            }
        }
        .onAppear { model.start() }
        .themedSheet(isPresented: Binding(get: { observed.state.open }, set: { if !$0 { model.dismiss() } })) {
            ProSheet(observed: observed).environment(\.appTheme, theme)
        }
    }
}

/// The Pro sheet: web's benefits, the store purchase (or a member's extension) and its outcome.
struct ProSheet: View {
    @ObservedObject var observed: Observed<ProState>
    @Environment(\.appTheme) private var theme

    private var model: ProModel { observed.model as! ProModel }
    private var copy: ProCopy { ProCopy.shared }

    var body: some View {
        let s = observed.state
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    if s.succeeded {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(s.successTitle).scaledFont(16, weight: .bold).foregroundStyle(theme.text)
                            Text(s.successBody).scaledFont(14).foregroundStyle(theme.secondary)
                        }
                        .accessibilityElement(children: .combine)
                        .announces(s.successBody)
                        .accessibilityIdentifier("pro-success")
                    } else if s.activating {
                        HStack(spacing: 10) {
                            ProgressView()
                            Text(copy.ACTIVATING).scaledFont(14).foregroundStyle(theme.text)
                        }
                        .accessibilityElement(children: .combine)
                        .announces(copy.ACTIVATING)
                        .accessibilityIdentifier("pro-activating")
                    } else {
                        if s.member {
                            Text(copy.MEMBER).scaledFont(15, weight: .semibold).foregroundStyle(theme.text).accessibilityIdentifier("pro-member")
                        }
                        if let ends = s.expiryText {
                            Text(ends).scaledFont(14).foregroundStyle(theme.text).accessibilityIdentifier("pro-expiry")
                        }
                        Text(copy.SUBTITLE).scaledFont(14).foregroundStyle(theme.secondary)
                        VStack(alignment: .leading, spacing: 8) {
                            ForEach(Array(copy.BENEFITS.enumerated()), id: \.offset) { i, benefit in
                                HStack(alignment: .firstTextBaseline, spacing: 8) {
                                    Image(appIcon: .checkCircle).scaledFont(14).foregroundStyle(theme.accentText).accessibilityHidden(true)
                                    Text(benefit).scaledFont(14).foregroundStyle(theme.text).fixedSize(horizontal: false, vertical: true)
                                }
                                .accessibilityElement(children: .combine)
                                .accessibilityIdentifier("pro-benefit-\(i)")
                            }
                        }
                        if s.member { Text(copy.EXTEND_NOTE).scaledFont(13).foregroundStyle(theme.secondary) }
                    }
                    // #3636: the free blog, for a member
                    if s.member { ProBlogSection(observed: observed) }
                    if let notice = s.notice {
                        Text(notice).scaledFont(13).foregroundStyle(theme.text).announces(notice).accessibilityIdentifier("pro-notice")
                    }
                    if s.showBuy {
                        Button { model.buy() } label: {
                            Group {
                                if s.buying { ProgressView().tint(.white) } else { Text(s.buyLabel ?? "").bold() }
                            }
                            .frame(maxWidth: .infinity, minHeight: 36)
                        }
                        .buttonStyle(.prominentFill)
                        .disabled(!s.canBuy)
                        .padding(.top, 8)
                        .accessibilityIdentifier("pro-buy")
                    } else if s.loadingPrice {
                        ProgressView().frame(maxWidth: .infinity).padding(.top, 8)
                    } else if s.unavailable && !s.purchased {
                        Text(copy.UNAVAILABLE).scaledFont(14).foregroundStyle(theme.secondary).accessibilityIdentifier("pro-unavailable")
                    }
                }
                .padding(16)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .background(theme.background)
            .navigationTitle(copy.TITLE)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(copy.CLOSE) { model.dismiss() }.accessibilityIdentifier("pro-close")
                }
            }
        }
        .accessibilityIdentifier("pro-sheet")
        .alert(s.alert?.title ?? "", isPresented: Binding(get: { observed.state.alert != nil }, set: { if !$0 { model.dismissAlert() } })) {
            Button(UiCopy.shared.OK, role: .cancel) { model.dismissAlert() }.accessibilityIdentifier("pro-alert-ok")
        } message: {
            Text(s.alert?.body ?? "")
        }
    }
}

/// A member's free blog (#3636): its address with Open once it exists, else "Claim your free blog" and the small
/// form (title and description from the profile, the default look). Nothing here points to buying anything.
struct ProBlogSection: View {
    @ObservedObject var observed: Observed<ProState>
    @Environment(\.appTheme) private var theme
    @Environment(\.openURL) private var openURL

    private var model: ProModel { observed.model as! ProModel }
    private var copy: ProCopy { ProCopy.shared }

    var body: some View {
        let s = observed.state
        if let address = s.blogAddress {
            VStack(alignment: .leading, spacing: 4) {
                Text(s.blogHeading).scaledFont(15, weight: .bold).foregroundStyle(theme.text)
                    .announces(s.blogHeading)
                    .accessibilityIdentifier("pro-blog-heading")
                Text(verbatim: address).scaledFont(14).foregroundStyle(theme.text)
                    .environment(\.layoutDirection, .leftToRight)
                    .accessibilityIdentifier("pro-blog-address")
                Button(copy.BLOG_OPEN) {
                    if let url = s.blogUrl.flatMap({ URL(string: $0) }) { openURL(url) }
                }
                .scaledFont(15, weight: .semibold)
                .frame(minHeight: minTapTarget)
                .accessibilityIdentifier("pro-blog-open")
            }
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(theme.lightBackground)
            .clipShape(RoundedRectangle(cornerRadius: 12))
            .padding(.top, 8)
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("pro-blog")
        } else if s.blogFormOpen {
            VStack(alignment: .leading, spacing: 8) {
                Text(copy.BLOG_CLAIM).scaledFont(15, weight: .bold).foregroundStyle(theme.text).accessibilityAddTraits(.isHeader)
                Text(s.blogIntro).scaledFont(13).foregroundStyle(theme.secondary).fixedSize(horizontal: false, vertical: true)
                Text(copy.BLOG_TITLE_LABEL).scaledFont(13, weight: .semibold).foregroundStyle(theme.text)
                TextField(copy.BLOG_TITLE_LABEL, text: Binding(get: { observed.state.blogTitle }, set: { model.setBlogTitle(text: $0) }))
                    .textFieldStyle(.roundedBorder)
                    .disabled(s.blogClaiming || s.blogPrefilling)
                    .accessibilityIdentifier("pro-blog-title")
                Text(copy.BLOG_DESCRIPTION_LABEL).scaledFont(13, weight: .semibold).foregroundStyle(theme.text)
                TextField(copy.BLOG_DESCRIPTION_LABEL, text: Binding(get: { observed.state.blogDescription }, set: { model.setBlogDescription(text: $0) }), axis: .vertical)
                    .lineLimit(2...5)
                    .textFieldStyle(.roundedBorder)
                    .disabled(s.blogClaiming || s.blogPrefilling)
                    .accessibilityIdentifier("pro-blog-description")
                if let error = s.blogError {
                    Text(error.message).scaledFont(13).foregroundStyle(theme.dangerText)
                        .fixedSize(horizontal: false, vertical: true)
                        .announces(error.message)
                        .accessibilityIdentifier("pro-blog-error")
                }
                HStack {
                    Spacer(minLength: 0)
                    Button(copy.BLOG_CANCEL) { model.cancelBlogForm() }
                        .disabled(s.blogClaiming)
                        .frame(minHeight: minTapTarget)
                        .accessibilityIdentifier("pro-blog-cancel")
                    Button { model.claimBlog() } label: {
                        Group {
                            if s.blogClaiming || s.blogPrefilling { ProgressView().tint(.white) } else { Text(copy.BLOG_CLAIM_CONFIRM).bold() }
                        }
                        .frame(minHeight: 36)
                    }
                    .buttonStyle(.prominentFill)
                    .disabled(!s.canClaimBlog)
                    .accessibilityIdentifier("pro-blog-confirm")
                }
            }
            .padding(.top, 8)
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("pro-blog-form")
        } else if s.showBlogClaim {
            Button { model.openBlogForm() } label: {
                Text(copy.BLOG_CLAIM).bold().frame(maxWidth: .infinity, minHeight: 36)
            }
            .buttonStyle(.bordered)
            .tint(theme.accent)
            .padding(.top, 8)
            .accessibilityIdentifier("pro-blog-claim")
        }
    }
}

import EcencyCore
import SwiftUI
import UIKit

/// RN theme tokens the perks screens use that `AppTheme` does not carry.
private enum PerksColors {
    static let green = Color(hex: 0x4FD688)
    static let darkBlue = Color(hex: 0x1A509A)
    static func lightBlue(_ dark: Bool) -> Color { dark ? Color(hex: 0x2E3D51) : Color(hex: 0xEAF2FC) }
    static func grayBackground(_ dark: Bool) -> Color { dark ? Color(hex: 0x1E2835) : Color(hex: 0xF5F5F5) }
}

/// RN PerksScreen: the quests card (tiers, progress, the streak and its freeze) and "Use your points". Rows are drawn
/// only while the card's account is the signed-in one.
struct PerksScreen: View {
    @EnvironmentObject private var app: AppContainer
    @EnvironmentObject private var session: Observed<SessionState>
    @Environment(\.appTheme) private var theme
    @Environment(\.colorScheme) private var scheme
    @StateObject private var observed: Observed<PerksState>
    @State private var foreground: NSObjectProtocol?

    init(env: AppEnvironment) {
        _observed = StateObject(wrappedValue: Observed(env.perksModel(), ownsModel: true))
    }

    private var model: PerksModel { observed.model as! PerksModel }
    private var copy: PerksCopy { PerksCopy.shared }

    var body: some View {
        let state = observed.state
        let mine = state.account != nil && state.account == session.state.username
        ScrollView {
            VStack(spacing: 16) {
                questsCard(state, mine: mine)
                // issue #3555: Ecency Pro by in-app purchase (ProViews.swift)
                ProCard(env: app.env)
                spendCard
            }
            .padding(16)
        }
        .background(theme.background)
        .navigationTitle(copy.HEADER)
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier("perks")
        .onAppear {
            model.start()
            // RN opts the quests into refetch on focus
            if foreground == nil {
                foreground = NotificationCenter.default.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { [weak model] _ in
                    MainActor.assumeIsolated { model?.onForeground() }
                }
            }
        }
        .onDisappear {
            if let f = foreground { NotificationCenter.default.removeObserver(f) }
            foreground = nil
        }
        .onChange(of: state.getPointsFor) {
            // RN: not enough Points for the freeze opens the Points top-up for this account
            guard let user = state.getPointsFor else { return }
            model.getPointsOpened()
            app.push(AppScreenCompanion.shared.of(route: .boost, arg: user))
        }
        .alert(copy.FREEZE_CONFIRM_TITLE, isPresented: Binding(get: { state.confirmingFreeze }, set: { if !$0 { model.cancelFreeze() } })) {
            Button(copy.CANCEL, role: .cancel) { model.cancelFreeze() }.accessibilityIdentifier("freeze-cancel")
            Button(copy.CONFIRM) { model.confirmFreeze() }.accessibilityIdentifier("freeze-confirm")
        } message: {
            Text(copy.freezeConfirmBody(price: QuestCatalog.shared.STREAK_FREEZE_PRICE))
        }
        .alert(state.alert ?? "", isPresented: Binding(get: { state.alert != nil }, set: { if !$0 { model.dismissAlert() } })) {
            Button(UiCopy.shared.OK, role: .cancel) { model.dismissAlert() }
        }
    }

    private func card<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 0) { content() }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(theme.lightBackground)
            .clipShape(RoundedRectangle(cornerRadius: 12))
    }

    private func bubble(_ icon: AppIcon, filled: Bool = false, tint: Color) -> some View {
        // a decoration: it grows up to xxxLarge, so it stays inside its 36 pt circle
        Image(appIcon: icon, filled: filled).scaledFont(16).foregroundStyle(tint)
            .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
            .frame(width: 36, height: 36).background(PerksColors.grayBackground(scheme == .dark)).clipShape(Circle())
            .accessibilityHidden(true)
    }

    @ViewBuilder
    private func questsCard(_ state: PerksState, mine: Bool) -> some View {
        card {
            Text(copy.QUESTS_TITLE).scaledFont(16, weight: .bold).foregroundStyle(theme.text).accessibilityAddTraits(.isHeader)
            if mine && state.failed {
                // RN QueryErrorRetry, compact: wrong progress is worse than none
                HStack(spacing: 8) {
                    Image(appIcon: state.timedOut ? .cloudOff : .error).foregroundStyle(theme.secondary).accessibilityHidden(true)
                    Text(state.errorText).scaledFont(13).foregroundStyle(theme.secondary)
                }
                .padding(.top, 10).announces(state.errorText).accessibilityIdentifier("perks-error")
                Button(state.retrying ? copy.RETRYING : copy.TRY_AGAIN) { model.retry() }
                    .disabled(state.retrying).padding(.top, 8).accessibilityIdentifier("perks-retry")
            } else {
                Text(copy.QUESTS_SUBTITLE).scaledFont(13).foregroundStyle(theme.secondary).padding(.top, 2)
                if mine && state.showStreak { streak(state) }
                tiers(state.tier)
                DailyResetLine(line: { state.resetLine(nowMillis: $0) }, identifier: "perks-reset").padding(.top, 8)
                if !mine || (state.loading && state.quests == nil) {
                    ProgressView().frame(maxWidth: .infinity).padding(16).accessibilityIdentifier("perks-loading")
                } else if state.rows.isEmpty {
                    Text(copy.QUESTS_EMPTY).scaledFont(13).foregroundStyle(theme.secondary).frame(maxWidth: .infinity).padding(.vertical, 16)
                } else {
                    ForEach(state.rows, id: \.id) { row in questRow(row) }
                    // README "Esie": every quest of the tier done
                    if state.rows.allSatisfy({ $0.completed }) { EsieView(.success).frame(maxWidth: .infinity).padding(.top, 12) }
                }
            }
        }
    }

    @ViewBuilder
    private func streak(_ state: PerksState) -> some View {
        HStack(spacing: 6) {
            Image(appIcon: .boost, filled: true).scaledFont(14).accessibilityHidden(true)
            Text(copy.streak(n: state.streak)).bold()
        }
        .foregroundStyle(theme.text)
        .padding(.horizontal, 12).padding(.vertical, 6)
        .background(PerksColors.lightBlue(scheme == .dark)).clipShape(Capsule())
        .padding(.top, 10)
        .accessibilityElement(children: .combine).accessibilityIdentifier("perks-streak")
        AdaptiveStack(spacing: 10) {
            if state.showFreezeOwned {
                HStack(spacing: 4) {
                    Image(appIcon: .snowflake).scaledFont(12).foregroundStyle(theme.accentText).accessibilityHidden(true)
                    Text(copy.freezeOwned(n: state.freezesOwned)).scaledFont(12).foregroundStyle(theme.secondary)
                }
                .accessibilityElement(children: .combine).accessibilityIdentifier("perks-freeze-owned")
            }
            if state.showFreezeBuy {
                Button { model.askFreeze() } label: {
                    Text(state.freezeLabel).scaledFont(12, weight: .semibold).foregroundStyle(theme.accentText)
                        .padding(.horizontal, 12).padding(.vertical, 6)
                        .overlay(Capsule().stroke(theme.accent, lineWidth: 1))
                }
                .buttonStyle(.plain).disabled(state.buyingFreeze).opacity(state.buyingFreeze ? 0.6 : 1)
                .accessibilityIdentifier("perks-freeze-buy")
            }
        }
        .padding(.top, 8)
    }

    private func tiers(_ selected: QuestTier) -> some View {
        // the tier tabs scroll sideways when large text makes them wider than the card
        ScrollView(.horizontal, showsIndicators: false) {
        HStack(spacing: 4) {
            ForEach(QuestTier.entries, id: \.self) { tier in
                let active = tier == selected
                Button { model.selectTier(tier: tier) } label: {
                    Text(copy.tier(tier: tier)).scaledFont(13, weight: .semibold).foregroundStyle(active ? theme.accentText : theme.secondary)
                        .padding(.horizontal, 14).padding(.vertical, 8)
                        .overlay(alignment: .bottom) { Rectangle().fill(active ? theme.accent : .clear).frame(height: 2) }
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(active ? [.isHeader, .isSelected] : [])
                .accessibilityIdentifier("tier-\(tier.id)")
            }
            Spacer()
        }
        }
        .scrollBounceBehavior(.basedOnSize, axes: .horizontal)
        .overlay(alignment: .bottom) { Rectangle().fill(theme.border).frame(height: 1).accessibilityHidden(true) }
        .padding(.top, 14).padding(.bottom, 4)
    }

    private func questRow(_ row: QuestRow) -> some View {
        HStack(spacing: 12) {
            bubble(row.icon, filled: row.completed, tint: row.completed ? PerksColors.green : theme.accent)
            VStack(alignment: .leading, spacing: 6) {
                AdaptiveStack {
                    Text(row.title).scaledFont(14, weight: .semibold).foregroundStyle(theme.text)
                    FormRowGap(minLength: 0)
                    Text(row.count).scaledFont(12).foregroundStyle(theme.secondary).accessibilityIdentifier("quest-count-\(row.id)")
                }
                GeometryReader { geo in
                    ZStack(alignment: .leading) {
                        Capsule().fill(theme.border)
                        Capsule().fill(row.completed ? PerksColors.green : theme.accent).frame(width: geo.size.width * CGFloat(row.percent) / 100)
                    }
                }
                .frame(height: 6)
                // the count beside the title says the same
                .accessibilityHidden(true)
            }
        }
        .padding(.vertical, 10)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("quest-\(row.tier.id)-\(row.id)")
    }

    private var spendCard: some View {
        card {
            Text(copy.SPEND_TITLE).scaledFont(16, weight: .bold).foregroundStyle(theme.text).accessibilityAddTraits(.isHeader)
            let options = SpendOptions.shared.ALL
            ForEach(Array(options.enumerated()), id: \.element.id) { index, option in
                Button { app.push(option.screen) } label: {
                    HStack(spacing: 12) {
                        bubble(option.icon, tint: theme.accent)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(option.title).scaledFont(15, weight: .semibold).foregroundStyle(theme.text)
                            Text(option.description_).scaledFont(12).foregroundStyle(theme.secondary)
                        }
                        Spacer()
                        Image(appIcon: .chevronRight).foregroundStyle(theme.icon).accessibilityHidden(true)
                    }
                    .padding(.vertical, 14)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain).accessibilityElement(children: .combine).accessibilityIdentifier("spend-\(option.id)")
                if index < options.count - 1 { Rectangle().fill(theme.border).frame(height: 1).accessibilityHidden(true) }
            }
        }
    }
}

/// RN SpinGameView: spins left, the rings (turning for 8 s after a press), the ESTM tags while a spin is left, the
/// score bubble, SPIN & WIN, and with none left the 5 spins pack (an in-app purchase, SpinPackRow) and the time to
/// the next free spin.
struct SpinGameScreen: View {
    @EnvironmentObject private var app: AppContainer
    @Environment(\.appTheme) private var theme
    @StateObject private var observed: Observed<SpinState>
    @State private var now = Date()
    private let timer = Timer.publish(every: Double(SpinCopy.shared.TIMER_TICK_MILLIS) / 1000, on: .main, in: .common).autoconnect()

    init(env: AppEnvironment) {
        _observed = StateObject(wrappedValue: Observed(env.spinGameModel(), ownsModel: true))
    }

    private var model: SpinGameModel { observed.model as! SpinGameModel }
    private var copy: SpinCopy { SpinCopy.shared }

    var body: some View {
        let state = observed.state
        SpinStage {
            VStack(spacing: 5) {
                if state.showCounters {
                    Text(Fmt.int(state.gameRight)).scaledFont(40, weight: .bold).foregroundStyle(theme.secondary).accessibilityIdentifier("spin-count")
                    Text(copy.SPIN_LEFT).scaledFont(16, weight: .bold).foregroundStyle(theme.secondary)
                }
            }
        } wheel: { side in
            ZStack {
                if state.showTags {
                    Image("SpinTags").resizable().scaledToFit().frame(maxWidth: 400, maxHeight: 360).accessibilityHidden(true).accessibilityIdentifier("spin-tags")
                }
                // the rings keep their size where they fit and shrink with the room where they do not (small phones,
                // large text), so they never run over the counters or the button
                SpinRings(spinning: state.spinning).scaleEffect(min(1, side / SpinRings.size)).accessibilityHidden(true)
                if state.showScore {
                    Text(copy.score(score: state.score)).scaledFont(10, weight: .bold).foregroundStyle(.white)
                        .padding(.horizontal, 5).frame(minWidth: 75, minHeight: 30, alignment: .leading).background(PerksColors.darkBlue)
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing).padding(.top, 40)
                        .accessibilityIdentifier("spin-score")
                }
            }
        } bottom: {
            VStack {
                // README "Esie": a won spin is a success moment; one hop per new score. Small (64 pt) so the 5 spins
                // pack and the countdown stay on short phones
                if state.showScore { EsieView(.success, height: 64).id(state.score).padding(.bottom, 4) }
                if state.showButton {
                    Button { model.spin() } label: {
                        Text(copy.BUTTON).bold().foregroundStyle(.white).padding(.horizontal, 16).frame(minWidth: 150, minHeight: 50).background(theme.accent).clipShape(Capsule())
                    }
                    .buttonStyle(.plain).accessibilityIdentifier("spin-button")
                } else if state.showProducts {
                    // RN ProductItemLine of the `499spins` purchase
                    SpinPackRow(env: app.env).accessibilityIdentifier("spin-product")
                    Text("\(copy.TIMER_TEXT) \(copy.timeLeft(nextDate: state.nextDate, nowMillis: Int64(now.timeIntervalSince1970 * 1000)))")
                        .scaledFont(16, weight: .semibold).foregroundStyle(theme.secondary).padding(.top, 50)
                        .accessibilityIdentifier("spin-timer")
                    if let note = state.resetNote {
                        Text(note).scaledFont(13).foregroundStyle(theme.secondary).multilineTextAlignment(.center)
                            .fixedSize(horizontal: false, vertical: true).padding(.top, 6).padding(.horizontal, 16)
                            .accessibilityIdentifier("spin-reset")
                    }
                }
                Spacer(minLength: 0)
            }
        }
        .background(theme.background)
        .navigationTitle(copy.TITLE)
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("spin-game")
        .onAppear { model.start() }
        .onReceive(timer) { now = $0 }
        .alert(state.alert ?? "", isPresented: Binding(get: { state.alert != nil }, set: { if !$0 { model.dismissAlert() } })) {
            Button(UiCopy.shared.OK, role: .cancel) { model.dismissAlert() }
        }
    }
}

/// The daily reset line (00:00 UTC, issue #3548): `line` reads the clock, nil hides it. It ticks while shown, each
/// time the minutes it reads change, so it rolls over at midnight.
struct DailyResetLine: View {
    let line: (Int64) -> String?
    let identifier: String
    @Environment(\.appTheme) private var theme
    @State private var now = DailyResetLine.clock()

    private static func clock() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    var body: some View {
        if let text = line(now) {
            Text(text).scaledFont(13).foregroundStyle(theme.secondary)
                .fixedSize(horizontal: false, vertical: true).frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityIdentifier(identifier)
                .task {
                    now = DailyResetLine.clock()
                    while !Task.isCancelled {
                        let wait = DailyReset.shared.nextTickMillis(nowMillis: DailyResetLine.clock())
                        try? await Task.sleep(nanoseconds: UInt64(max(wait, 1)) * 1_000_000)
                        now = DailyResetLine.clock()
                    }
                }
        }
    }
}

/// The spin screen's layout: RN's 0.3 : 1 : 0.7 split of the height (counters, wheel, button or pack), each part
/// the full width so the wheel sits on the screen's centre line whatever its content's width (a GeometryReader puts
/// a narrower column at its leading edge: the turning rings, narrower than the tags, ended up on the left). The
/// counters and the bottom part never get less than their content (as Android): on a short phone or at large text
/// the wheel gives up the room (its rings shrink) instead of anything being clipped or drawn over. [wheel] gets the side of the largest square
/// that fits its part.
struct SpinStage<Top: View, Wheel: View, Bottom: View>: View {
    @ViewBuilder let top: Top
    @ViewBuilder let wheel: (CGFloat) -> Wheel
    @ViewBuilder let bottom: Bottom

    var body: some View {
        GeometryReader { geo in
            VStack(spacing: 0) {
                // at least its content: the counters at the largest text sizes are taller than RN's 0.15 share,
                // and the wheel gives up the room instead of being drawn over
                top
                    .frame(maxWidth: .infinity)
                    .frame(minHeight: geo.size.height * 0.3 / 2)
                    .fixedSize(horizontal: false, vertical: true)
                    .layoutPriority(1)
                GeometryReader { part in
                    wheel(min(part.size.width, part.size.height))
                        .frame(width: part.size.width, height: part.size.height)
                }
                .frame(maxWidth: .infinity, minHeight: 0, maxHeight: geo.size.height * 1 / 2)
                bottom
                    .frame(maxWidth: .infinity)
                    .frame(minHeight: geo.size.height * 0.7 / 2)
                    .fixedSize(horizontal: false, vertical: true)
                    .layoutPriority(1)
            }
            .frame(width: geo.size.width, height: geo.size.height, alignment: .top)
        }
    }
}

/// RN BoostIndicatorAnimation: three rings around the logo, grey at rest, turning blue arcs while spinning.
struct SpinRings: View {
    /// The outer ring's diameter.
    static let size: CGFloat = 230
    let spinning: Bool
    @Environment(\.motion) private var motion

    private static let rings: [(CGFloat, Color, Double, Double)] = [
        (230, Color(hex: 0x1A509A), 2.4, 0), (180, Color(hex: 0x357CE6), 2.0, 20), (130, Color(hex: 0x4DA1F1), 1.7, 40),
    ]

    var body: some View {
        ZStack {
            if spinning {
                // reduce motion: the arcs stand still (a quarter of each ring), no turning
                TimelineView(.animation(paused: !motion.pops)) { context in
                    let t = motion.pops ? context.date.timeIntervalSinceReferenceDate : 0
                    ZStack {
                        ForEach(0..<Self.rings.count, id: \.self) { i in
                            let (size, color, period, start) = Self.rings[i]
                            let sweep = 0.17 + 0.58 * abs(sin(t * .pi / 2.4))
                            Circle().trim(from: 0, to: sweep)
                                .stroke(color, style: StrokeStyle(lineWidth: 12, lineCap: .round))
                                .frame(width: size - 12, height: size - 12)
                                .rotationEffect(.degrees(start + (t.truncatingRemainder(dividingBy: period) / period) * 360))
                        }
                    }
                }
                .accessibilityIdentifier("spin-rings-turning")
            } else {
                ForEach(0..<Self.rings.count, id: \.self) { i in
                    Circle().stroke(Color(hex: 0xF2F2F2), lineWidth: 12).frame(width: Self.rings[i].0 - 12, height: Self.rings[i].0 - 12)
                }
            }
            Image("SpinLogo").resizable().frame(width: 80, height: 80)
        }
    }
}

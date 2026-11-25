import SwiftUI
import EcencyCore

/// The calendar's festive palette (owner-approved design 11-25): night sky, presents, gold, holly.
private enum Festive {
    static let night = Color(red: 0.051, green: 0.102, blue: 0.227)
    static let nightCard = Color(red: 0.106, green: 0.184, blue: 0.369)
    static let nightBorder = Color(red: 0.184, green: 0.290, blue: 0.522)
    static let wire = Color(red: 0.173, green: 0.239, blue: 0.388)
    static let gold = Color(red: 1.0, green: 0.827, blue: 0.306)
    static let goldDark = Color(red: 0.722, green: 0.525, blue: 0.043)
    static let red = Color(red: 0.839, green: 0.184, blue: 0.227)
    static let redText = Color(red: 0.702, green: 0.125, blue: 0.169)
    static let holly = Color(red: 0.122, green: 0.478, blue: 0.302)
    static let hollyDark = Color(red: 0.078, green: 0.333, blue: 0.216)
    static let snow = Color(red: 0.984, green: 0.969, blue: 0.941)
    static let cream = Color(red: 1.0, green: 0.957, blue: 0.839)
    static let ink = Color(red: 0.106, green: 0.133, blue: 0.188)
    static let inkSoft = Color(red: 0.875, green: 0.906, blue: 0.969)
    static let answered = Color(red: 0.890, green: 0.933, blue: 0.988)
    static let answeredBorder = Color(red: 0.663, green: 0.769, blue: 0.933)
    static let missed = Color(red: 0.925, green: 0.910, blue: 0.882)
    static let missedInk = Color(red: 0.373, green: 0.400, blue: 0.451)
    static let lights: [Color] = [Color(red: 1.0, green: 0.353, blue: 0.373), gold, Color(red: 0.373, green: 0.827, blue: 0.541), Color(red: 0.486, green: 0.780, blue: 1.0)]
    static let papers: [(Color, Color)] = [
        (Color(red: 0.122, green: 0.478, blue: 0.302), gold), (Color(red: 0.141, green: 0.341, blue: 0.722), .white),
        (Color(red: 0.482, green: 0.247, blue: 0.627), gold), (Color(red: 0.722, green: 0.196, blue: 0.353), .white),
        (Color(red: 0.059, green: 0.431, blue: 0.431), Color(red: 1.0, green: 0.561, blue: 0.639)), (Color(red: 0.757, green: 0.392, blue: 0.110), .white),
    ]

    /// A handwritten face for headings (the design's "Mountains of Christmas"; a system script here).
    static func script(_ size: CGFloat) -> Font { .custom("SnellRoundhand-Black", size: size, relativeTo: .title) }
}

/// The Ecency Advent Calendar (AdventModel): the night sky header, the 24 presents, the grand draw and the prizes.
struct AdventScreen: View {
    @EnvironmentObject private var app: AppContainer
    @Environment(\.motion) private var motion
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var observed: Observed<AdventState>

    init(env: AppEnvironment) {
        _observed = StateObject(wrappedValue: Observed(env.adventModel(), ownsModel: true))
    }

    private var model: AdventModel { observed.model as! AdventModel }

    var body: some View {
        let state = observed.state
        ScrollView {
            VStack(spacing: 0) {
                AdventHeader(state: state, falling: state.snow && motion.level == .full, onSnow: { model.toggleSnow() })
                if state.signIn == .failed {
                    notice(L.Native.Advent.signRetry, id: "advent-sign-retry") { model.retrySignIn() }
                } else if state.error != nil && !state.loaded {
                    notice(L.Native.Advent.calendarFailed, id: "advent-retry") { model.refresh() }
                } else if state.account == nil && state.phase == .season {
                    notice(L.Native.Advent.loginToOpen, id: "advent-login") { app.push(AppScreenLogin.shared) }
                }
                if state.phase == .hidden {
                    Text(L.Native.Advent.ended).foregroundStyle(Festive.ink).multilineTextAlignment(.center).padding(24)
                        .accessibilityIdentifier("advent-ended")
                } else {
                    doors(state.tiles).padding(.top, -20)
                    legend
                    if state.phase == .teaser { steps }
                    if let grand = state.grand { grandCard(grand) }
                    if !state.prizes.isEmpty { prizes(state.prizes, total: state.pointsSoFar) }
                }
                Spacer(minLength: 32)
            }
        }
        .background(Festive.snow.ignoresSafeArea())
        .refreshable { model.refresh() }
        .overlay { if state.loading && !state.loaded { ProgressView().accessibilityIdentifier("advent-loading") } }
        .navigationTitle(AdventCopy.shared.TITLE)
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(Festive.night, for: .navigationBar)
        .toolbarBackground(.visible, for: .navigationBar)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .onAppear { model.start() }
        // back from the background: a phone that slept through midnight UTC shows the new day's door
        .onChange(of: scenePhase) { _, phase in if phase == .active { model.resumed() } }
        .onChange(of: state.askLogin) { _, ask in
            if ask {
                model.consumeLogin()
                app.push(AppScreenLogin.shared)
            }
        }
        .themedSheet(isPresented: Binding(get: { state.sheet != nil }, set: { if !$0 { model.closeSheet() } })) {
            if let sheet = observed.state.sheet {
                AdventDoorSheet(sheet: sheet, account: observed.state.account, model: model) { url in
                    if let entry = ContentLinks.shared.entry(url: url) { app.open(entry: entry) }
                }
            }
        }
        .accessibilityIdentifier("advent")
    }

    private func notice(_ text: String, id: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(text).bold().foregroundStyle(Festive.redText).multilineTextAlignment(.center).frame(maxWidth: .infinity, minHeight: 44)
        }
        .buttonStyle(.plain).padding(.horizontal, 12).accessibilityIdentifier(id)
    }

    private func doors(_ tiles: [AdventTile]) -> some View {
        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 9), count: 4), spacing: 9) {
            ForEach(tiles, id: \.day) { tile in
                AdventDoorTile(tile: tile) { model.openDoor(day: tile.day) }
            }
        }
        .padding(.horizontal, 12)
    }

    private var legend: some View {
        HStack(spacing: 14) {
            ForEach(Array([(Festive.red, L.Native.Advent.legendToday), (Festive.holly, L.Native.Advent.legendWrapped),
                           (Festive.gold, L.Native.Advent.legendWon), (Festive.answered, L.Native.Advent.legendAnswered)].enumerated()), id: \.offset) { _, item in
                HStack(spacing: 5) {
                    RoundedRectangle(cornerRadius: 3).fill(item.0).frame(width: 10, height: 10).accessibilityHidden(true)
                    Text(item.1).scaledFont(11).foregroundStyle(Color(red: 0.29, green: 0.333, blue: 0.408))
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 14).padding(.top, 12)
    }

    private var steps: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(L.Native.Advent.lede).scaledFont(14).foregroundStyle(Festive.ink)
            ForEach(Array([(L.Native.Advent.stepOpen, L.Native.Advent.stepOpenText), (L.Native.Advent.stepAnswer, L.Native.Advent.stepAnswerText),
                           (L.Native.Advent.stepWin, L.Native.Advent.stepWinText)].enumerated()), id: \.offset) { _, step in
                VStack(alignment: .leading, spacing: 2) {
                    Text(step.0).font(Festive.script(22)).foregroundStyle(Festive.redText)
                    Text(step.1).scaledFont(13).foregroundStyle(Festive.ink)
                }
                .frame(maxWidth: .infinity, alignment: .leading).padding(12)
                .background(Color.white, in: RoundedRectangle(cornerRadius: 14))
            }
        }
        .padding(12)
    }

    private func grandCard(_ grand: AdventGrandCard) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(L.Native.Advent.grandTitle).font(Festive.script(26)).foregroundStyle(.white).accessibilityAddTraits(.isHeader)
                Spacer()
                Text(grand.total).scaledFont(12, weight: .heavy).foregroundStyle(Color(red: 0.561, green: 0.114, blue: 0.145))
                    .padding(.horizontal, 9).padding(.vertical, 4).background(Color.white, in: RoundedRectangle(cornerRadius: 10))
            }
            Text(grand.text).scaledFont(13).foregroundStyle(.white)
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    RoundedRectangle(cornerRadius: 5).fill(Color(red: 0.659, green: 0.133, blue: 0.169))
                    RoundedRectangle(cornerRadius: 5).fill(Festive.gold).frame(width: geo.size.width * CGFloat(min(max(grand.fraction, 0), 1)))
                }
            }
            .frame(height: 10).accessibilityHidden(true)
            Text(grand.progress).scaledFont(12, weight: .bold).foregroundStyle(.white)
        }
        .padding(.leading, 14).padding(.vertical, 14).padding(.trailing, 44)
        .background(alignment: .trailing) { Festive.gold.frame(width: 10).padding(.trailing, 18) }
        .background(Festive.red, in: RoundedRectangle(cornerRadius: 16))
        .padding(12)
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("advent-grand")
    }

    private func prizes(_ rows: [AdventPrizeRow], total: String?) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(L.Native.Advent.yourPrizes).font(Festive.script(24)).foregroundStyle(Festive.redText).accessibilityAddTraits(.isHeader)
                Spacer()
                if let total { Text(total).scaledFont(12, weight: .bold).foregroundStyle(Festive.ink) }
            }
            ForEach(Array(rows.enumerated()), id: \.offset) { _, row in
                HStack {
                    VStack(alignment: .leading) {
                        Text(row.text).scaledFont(13).foregroundStyle(Festive.ink)
                        Text(row.amount).scaledFont(13, weight: .bold).foregroundStyle(Festive.ink)
                    }
                    Spacer()
                    Text(row.status).scaledFont(12, weight: .bold).foregroundStyle(row.paid ? Festive.hollyDark : Color(red: 0.478, green: 0.325, blue: 0))
                }
                .accessibilityElement(children: .combine)
            }
        }
        .padding(14).background(Color.white, in: RoundedRectangle(cornerRadius: 16)).padding(12)
        .accessibilityIdentifier("advent-prizes")
    }
}

/// The night sky header: stars, moon, a string of lights, falling snow, a snowy hill with a tree (on the right, clear of the headline and the countdown).
private struct AdventHeader: View {
    let state: AdventState
    let falling: Bool
    let onSnow: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Spacer()
                Button(action: onSnow) {
                    HStack(spacing: 6) {
                        Image(appIcon: .snowflake).font(.footnote).accessibilityHidden(true)
                        Text(L.Native.Advent.snow).scaledFont(13)
                    }
                    .foregroundStyle(.white).opacity(state.snow ? 1 : 0.6)
                    .padding(.horizontal, 12).frame(minHeight: 36)
                    .background(Festive.nightCard, in: Capsule()).overlay(Capsule().stroke(Festive.nightBorder))
                }
                .buttonStyle(.plain)
                .accessibilityLabel(state.snow ? L.Native.Advent.snowOn : L.Native.Advent.snowOff)
                .accessibilityIdentifier("advent-snow")
            }
            .padding(.top, 8)
            Text(state.kicker.uppercased()).scaledFont(12, weight: .heavy).tracking(1.5).foregroundStyle(Festive.gold).padding(.top, 62)
            Text(state.headline).font(Festive.script(38)).foregroundStyle(.white).accessibilityAddTraits(.isHeader)
                .accessibilityIdentifier("advent-headline")
            if let countdown = state.countdown {
                Text(countdown).scaledFont(20, weight: .bold).foregroundStyle(Festive.gold).padding(.top, 4)
                    .accessibilityIdentifier("advent-countdown")
            }
            if state.account != nil && state.phase == .season {
                HStack(spacing: 10) {
                    stat(Fmt.int(Int(state.answered)) + " / " + Fmt.int(24), L.Native.Advent.doorsAnswered, tint: .white).accessibilityIdentifier("advent-answered")
                    stat(state.poolLeft ?? "", L.Native.Advent.poolLeft, tint: Festive.gold)
                }
                .padding(.top, 12)
            }
        }
        .padding(.horizontal, 16).padding(.bottom, 46)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background {
            TimelineView(.animation(minimumInterval: 1 / 20, paused: !falling)) { context in
                let fall = falling ? CGFloat(context.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 9) / 9) : 0
                Canvas { ctx, size in AdventSky.draw(ctx, size: size, fall: fall) }
            }
            .background(Festive.night)
            .accessibilityHidden(true)
        }
    }

    private func stat(_ value: String, _ label: String, tint: Color) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(value).scaledFont(20, weight: .heavy).foregroundStyle(tint)
            Text(label).scaledFont(12).foregroundStyle(Festive.inkSoft)
        }
        .frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 12).padding(.vertical, 10)
        .background(Festive.nightCard, in: RoundedRectangle(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(Festive.nightBorder))
        .accessibilityElement(children: .combine)
    }
}

/// Decoration only (hidden from VoiceOver).
enum AdventSky {
    static func draw(_ ctx: GraphicsContext, size: CGSize, fall: CGFloat) {
        let u = size.width / 390
        let h = size.height
        ctx.fill(Path(ellipseIn: CGRect(x: 310 * u, y: 50 * u, width: 40 * u, height: 40 * u)), with: .color(Color(red: 1, green: 0.965, blue: 0.847)))
        ctx.fill(Path(ellipseIn: CGRect(x: 322 * u, y: 45 * u, width: 36 * u, height: 36 * u)), with: .color(Festive.night))
        for (x, y) in [(30, 80), (90, 110), (150, 70), (250, 100), (290, 140), (60, 160), (190, 150), (370, 160)] {
            ctx.fill(Path(ellipseIn: CGRect(x: CGFloat(x) * u - 1.5 * u, y: CGFloat(y) * u - 1.5 * u, width: 3 * u, height: 3 * u)), with: .color(.white.opacity(0.85)))
        }
        for (i, (x, y)) in [(110, 92), (220, 64), (46, 130), (275, 175), (360, 115), (160, 40), (320, 20)].enumerated() {
            let dy = (CGFloat(y) * u + fall * h + CGFloat(i) * 37 * u).truncatingRemainder(dividingBy: max(h, 1))
            flake(ctx, at: CGPoint(x: CGFloat(x) * u + (i % 2 == 0 ? 6 : -6) * u * fall, y: dy), r: 5.5 * u * (0.7 + CGFloat(i % 3) * 0.15))
        }
        var wire = Path()
        wire.move(to: CGPoint(x: -5 * u, y: 52 * u))
        var x: CGFloat = -5
        while x < 395 {
            wire.addQuadCurve(to: CGPoint(x: (x + 98) * u, y: 54 * u), control: CGPoint(x: (x + 49) * u, y: 72 * u))
            x += 98
        }
        ctx.stroke(wire, with: .color(Festive.wire), lineWidth: 2 * u)
        for i in 0..<8 {
            let cx = CGFloat(24 + i * 49) * u
            let cy = CGFloat(i % 3 == 1 ? 69 : i % 3 == 2 ? 62 : 64) * u
            let c = Festive.lights[i % 4]
            ctx.fill(Path(ellipseIn: CGRect(x: cx - 11 * u, y: cy - 11 * u, width: 22 * u, height: 22 * u)), with: .color(c.opacity(0.25)))
            ctx.fill(Path(ellipseIn: CGRect(x: cx - 5 * u, y: cy - 7 * u, width: 10 * u, height: 14 * u)), with: .color(c))
        }
        tree(ctx, at: CGPoint(x: 330 * u, y: h - 86 * u), u: u, green: Festive.holly)
        var hill = Path()
        hill.move(to: CGPoint(x: 0, y: h - 32 * u))
        hill.addQuadCurve(to: CGPoint(x: 200 * u, y: h - 42 * u), control: CGPoint(x: 90 * u, y: h - 60 * u))
        hill.addQuadCurve(to: CGPoint(x: size.width, y: h - 50 * u), control: CGPoint(x: 300 * u, y: h - 26 * u))
        hill.addLine(to: CGPoint(x: size.width, y: h))
        hill.addLine(to: CGPoint(x: 0, y: h))
        hill.closeSubpath()
        ctx.fill(hill, with: .color(Festive.snow))
    }

    static func flake(_ ctx: GraphicsContext, at c: CGPoint, r: CGFloat, color: Color = .white.opacity(0.85)) {
        for k in 0..<3 {
            let a = (90 + Double(k) * 60) * .pi / 180
            let dx = CGFloat(cos(a)) * r
            let dy = CGFloat(sin(a)) * r
            var p = Path()
            p.move(to: CGPoint(x: c.x - dx, y: c.y - dy))
            p.addLine(to: CGPoint(x: c.x + dx, y: c.y + dy))
            ctx.stroke(p, with: .color(color), style: StrokeStyle(lineWidth: r / 4, lineCap: .round))
        }
    }

    static func star(_ c: CGPoint, r: CGFloat) -> Path {
        var p = Path()
        for i in 0..<10 {
            let radius = i % 2 == 0 ? r : r * 0.45
            let a = (-90 + Double(i) * 36) * .pi / 180
            let pt = CGPoint(x: c.x + CGFloat(cos(a)) * radius, y: c.y + CGFloat(sin(a)) * radius)
            if i == 0 { p.move(to: pt) } else { p.addLine(to: pt) }
        }
        p.closeSubpath()
        return p
    }

    private static func tree(_ ctx: GraphicsContext, at a: CGPoint, u: CGFloat, green: Color) {
        var p = Path()
        p.move(to: CGPoint(x: a.x, y: a.y - 30 * u))
        p.addLine(to: CGPoint(x: a.x + 16 * u, y: a.y))
        p.addLine(to: CGPoint(x: a.x + 8 * u, y: a.y))
        p.addLine(to: CGPoint(x: a.x + 20 * u, y: a.y + 22 * u))
        p.addLine(to: CGPoint(x: a.x - 20 * u, y: a.y + 22 * u))
        p.addLine(to: CGPoint(x: a.x - 8 * u, y: a.y))
        p.addLine(to: CGPoint(x: a.x - 16 * u, y: a.y))
        p.closeSubpath()
        ctx.fill(p, with: .color(green))
        ctx.fill(Path(CGRect(x: a.x - 3 * u, y: a.y + 22 * u, width: 6 * u, height: 8 * u)), with: .color(Color(red: 0.478, green: 0.290, blue: 0.141)))
        ctx.fill(star(CGPoint(x: a.x, y: a.y - 34 * u), r: 6 * u), with: .color(Festive.gold))
    }
}

/// One door: a wrapped present with ribbon and bow, today's glowing with a star, a won one gold, answered pale.
private struct AdventDoorTile: View {
    let tile: AdventTile
    let onTap: () -> Void

    var body: some View {
        let paper = Festive.papers[Int(tile.day) % Festive.papers.count]
        let look = tile.look
        let wrapped = look == .locked || look == .today
        let bg: Color = switch look {
        case .today: Festive.red
        case .won: Festive.gold
        case .answered, .waiting: Festive.answered
        case .missed, .closed, .results: Festive.missed
        default: paper.0
        }
        let ink: Color = switch look {
        case .locked, .today: .white
        case .won: Color(red: 0.29, green: 0.196, blue: 0)
        case .missed, .closed, .results: Festive.missedInk
        default: Festive.ink
        }
        let band = look == .today ? Festive.gold : paper.1
        Button { if tile.tappable { onTap() } } label: {
            ZStack {
                RoundedRectangle(cornerRadius: 10).fill(bg)
                if wrapped {
                    band.opacity(0.95).frame(width: 8)
                    band.opacity(0.95).frame(height: 8)
                }
                VStack(spacing: 1) {
                    Text(Fmt.int(Int(tile.day))).scaledFont(19, weight: .heavy).foregroundStyle(ink)
                        .shadow(color: wrapped ? .black.opacity(0.45) : .clear, radius: 1, y: 1)
                    if !tile.tag.isEmpty {
                        if look == .today {
                            Text(tile.tag).scaledFont(10, weight: .heavy).foregroundStyle(Festive.redText).lineLimit(1)
                                .padding(.horizontal, 6).padding(.vertical, 1).background(Color.white, in: RoundedRectangle(cornerRadius: 8))
                        } else {
                            Text(tile.tag).scaledFont(10, weight: .bold).foregroundStyle(ink).lineLimit(1).minimumScaleFactor(0.7)
                        }
                    }
                }
                if look == .today || look == .won {
                    Canvas { ctx, size in ctx.fill(AdventSky.star(CGPoint(x: size.width / 2, y: size.height / 2), r: size.width / 2), with: .color(look == .won ? .white : Festive.gold)) }
                        .frame(width: 18, height: 18).frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing).padding(4)
                }
            }
            .frame(height: 68)
            .overlay(alignment: .top) {
                if wrapped {
                    Canvas { ctx, size in
                        var p = Path()
                        let w = size.width, h = size.height
                        p.move(to: CGPoint(x: w / 2, y: h * 0.64))
                        p.addCurve(to: CGPoint(x: w * 0.15, y: h * 0.64), control1: CGPoint(x: w * 0.23, y: 0), control2: CGPoint(x: 0, y: h * 0.29))
                        p.addCurve(to: CGPoint(x: w / 2, y: h * 0.64), control1: CGPoint(x: w * 0.23, y: h * 0.86), control2: CGPoint(x: w * 0.42, y: h * 0.79))
                        p.addCurve(to: CGPoint(x: w * 0.85, y: h * 0.64), control1: CGPoint(x: w * 0.58, y: h * 0.79), control2: CGPoint(x: w * 0.77, y: h * 0.86))
                        p.addCurve(to: CGPoint(x: w / 2, y: h * 0.64), control1: CGPoint(x: w, y: h * 0.29), control2: CGPoint(x: w * 0.77, y: 0))
                        ctx.fill(p, with: .color(band))
                    }
                    .frame(width: 26, height: 14).offset(y: -8)
                }
            }
            .overlay(RoundedRectangle(cornerRadius: 10).stroke(border, lineWidth: borderWidth))
            .shadow(color: look == .today ? Festive.gold.opacity(0.8) : .clear, radius: 9)
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(tile.label)
        .accessibilityAddTraits(tile.tappable ? .isButton : [])
        .accessibilityIdentifier("advent-door-\(tile.day)")
    }

    private var border: Color {
        switch tile.look {
        case .today: Festive.gold
        case .won: Festive.goldDark
        case .answered, .waiting: Festive.answeredBorder
        default: .clear
        }
    }

    private var borderWidth: CGFloat { tile.look == .today ? 3 : tile.look == .won ? 2 : 1 }
}

/// One door's sheet: the answer form, the answer sent and its Wave, or the result of the draw.
private struct AdventDoorSheet: View {
    let sheet: AdventSheet
    let account: String?
    let model: AdventModel
    let onUrl: (String) -> Void
    @State private var text = ""
    @State private var choice = -1

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                header
                switch sheet.mode {
                case .loading: ProgressView().frame(maxWidth: .infinity, minHeight: 120)
                case .failed:
                    VStack {
                        Text(L.Native.Advent.loadFailed)
                        Button(L.Native.Advent.tryAgain) { model.retrySheet() }.accessibilityIdentifier("advent-sheet-retry")
                    }
                    .frame(maxWidth: .infinity)
                case .answer: answerForm
                case .done: done
                default: result
                }
            }
            .padding(16)
        }
        .accessibilityIdentifier("advent-sheet")
    }

    private var drawn: Bool { sheet.mode == .result }

    private var header: some View {
        HStack(spacing: 12) {
            ZStack {
                RoundedRectangle(cornerRadius: 12).fill(drawn ? Festive.gold : Festive.red)
                if drawn {
                    Canvas { ctx, size in ctx.fill(AdventSky.star(CGPoint(x: size.width / 2, y: size.height / 2), r: size.width / 2), with: .color(.white)) }.frame(width: 30, height: 30)
                } else {
                    Festive.gold.frame(width: 8)
                    Festive.gold.frame(height: 8)
                }
            }
            .frame(width: 56, height: 56).clipShape(RoundedRectangle(cornerRadius: 12)).accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 3) {
                let title = L.Native.Advent.doorLabel(day: Fmt.int(Int(sheet.day)))
                Text(drawn ? title + " · " + L.Native.Advent.labelDrawn : title).font(Festive.script(30)).foregroundStyle(Festive.redText)
                    .accessibilityAddTraits(.isHeader).accessibilityIdentifier("advent-sheet-title")
                if let door = sheet.door {
                    Text(AdventCopy.shared.kindLabel(kind: door.kind) + " · " + AdventCopy.shared.reward(reward: door.reward)).scaledFont(13).foregroundStyle(.secondary)
                }
            }
        }
    }

    @ViewBuilder private var answerForm: some View {
        if let door = sheet.door {
            if let prompt = door.prompt { Text(prompt).scaledFont(16, weight: .semibold).accessibilityIdentifier("advent-prompt") }
            if sheet.late { Text(L.Native.Advent.lateNote).scaledFont(13, weight: .bold).foregroundStyle(Festive.redText) }
            if door.kind == AdventDoorKind.open {
                Text(L.Native.Advent.yourAnswer).scaledFont(13, weight: .semibold).foregroundStyle(.secondary)
                TextField(L.Native.Advent.writeHint, text: $text, axis: .vertical)
                    .lineLimit(5...12).textFieldStyle(.roundedBorder)
                    .onChange(of: text) { _, t in
                        // UTF-16 units, as the core and Android count (an emoji is two)
                        let max = Int(AdventModel.companion.MAX_ANSWER)
                        if t.utf16.count > max { text = AdventCopy.shared.limit(text: t, max: Int32(max)) }
                    }
                    .accessibilityIdentifier("advent-answer")
                HStack {
                    Text(L.Native.Advent.words(n: text.split(whereSeparator: { $0.isWhitespace }).count))
                    Spacer()
                    Text(AdventCopy.shared.MAX_CHARS)
                }
                .scaledFont(12).foregroundStyle(.secondary)
            } else {
                Text(L.Native.Advent.labelPick).scaledFont(13, weight: .bold).foregroundStyle(.secondary)
                ForEach(Array(door.choices.enumerated()), id: \.offset) { i, c in
                    Button { choice = i } label: {
                        HStack {
                            Image(appIcon: choice == i ? .checkCircle : .task, filled: choice == i).accessibilityHidden(true)
                            Text(c)
                            Spacer()
                        }
                        .frame(minHeight: 44).contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(choice == i ? .isSelected : [])
                    .accessibilityIdentifier("advent-choice-\(i)")
                }
            }
            Text(door.kind == AdventDoorKind.open ? L.Native.Advent.waveNoteOpen : L.Native.Advent.waveNotePick(day: Fmt.int(Int(sheet.day))))
                .scaledFont(13).padding(12).frame(maxWidth: .infinity, alignment: .leading)
                .background(Color(red: 0.933, green: 0.953, blue: 0.992), in: RoundedRectangle(cornerRadius: 12))
            if let error = sheet.error { Text(error.text).scaledFont(13).foregroundStyle(Festive.redText) }
            let ready = door.kind == AdventDoorKind.open ? !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty : choice >= 0
            Button {
                model.submit(answer: door.kind == AdventDoorKind.open ? text : nil, choice: Int32(choice))
            } label: {
                Group {
                    if sheet.submitting { ProgressView().tint(.white) } else { Text(L.Native.Advent.submitWave).scaledFont(16, weight: .heavy) }
                }
                .foregroundStyle(.white).frame(maxWidth: .infinity, minHeight: 52)
                .background(ready ? Festive.holly : Festive.holly.opacity(0.5), in: Capsule())
            }
            .buttonStyle(.plain).disabled(!ready || sheet.submitting)
            .accessibilityIdentifier("advent-submit")
        }
    }

    @ViewBuilder private var done: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(L.Native.Advent.answeredTitle).font(Festive.script(26)).foregroundStyle(Color(red: 0.561, green: 0.114, blue: 0.145))
            Text(sheet.entry?.late == true ? L.Native.Advent.answeredLate : L.Native.Advent.answeredText).scaledFont(14).foregroundStyle(Color(red: 0.361, green: 0.247, blue: 0))
        }
        .padding(12).frame(maxWidth: .infinity, alignment: .leading).background(Festive.cream, in: RoundedRectangle(cornerRadius: 14))
        .accessibilityElement(children: .combine).accessibilityIdentifier("advent-done")
        if let answer = sheet.entry?.answer { Text(answer).scaledFont(15).accessibilityIdentifier("advent-your-answer") }
        switch sheet.wave {
        case .posting:
            HStack { ProgressView(); Text(L.Native.Advent.postingWave).foregroundStyle(.secondary) }
        case .failed:
            Button(sheet.wavePermlink != nil ? L.Native.Advent.linkWave : L.Native.Advent.postWave) { model.retryWave() }.bold().accessibilityIdentifier("advent-post-wave")
        default: EmptyView()
        }
        if sheet.wave == .posted, let permlink = sheet.wavePermlink, let account {
            Button(L.Native.Advent.viewWave) { onUrl(AdventModel.companion.waveUrl(author: account, permlink: permlink)) }.bold().accessibilityIdentifier("advent-view-wave")
        }
        Button(L.Native.Advent.back) { model.closeSheet() }.frame(maxWidth: .infinity, minHeight: 44)
    }

    @ViewBuilder private var result: some View {
        if sheet.notDrawn || sheet.result == nil {
            Text(L.Native.Advent.notDrawn).scaledFont(15).accessibilityIdentifier("advent-not-drawn")
        } else if let r = sheet.result {
            let door = sheet.door
            if let mine = r.winners.first(where: { $0.account == account && !$0.cancelled }), let door {
                VStack(alignment: .leading, spacing: 2) {
                    Text(L.Native.Advent.youWon).font(Festive.script(24)).foregroundStyle(Color(red: 0.561, green: 0.114, blue: 0.145))
                    Text(L.Native.Advent.place(n: Fmt.int(Int(mine.place))) + " · " + AdventCopy.shared.prize(kind: door.reward.kind, amount: amount(door, mine.place)))
                        .scaledFont(14, weight: .semibold).foregroundStyle(Color(red: 0.361, green: 0.247, blue: 0))
                }
                .padding(12).frame(maxWidth: .infinity, alignment: .leading).background(Festive.cream, in: RoundedRectangle(cornerRadius: 14))
                .accessibilityElement(children: .combine).accessibilityIdentifier("advent-you-won")
            }
            Text(L.Native.Advent.winners).scaledFont(13, weight: .bold).foregroundStyle(.secondary)
            if r.winners.isEmpty { Text(L.Native.Advent.noWinners) }
            ForEach(r.winners, id: \.place) { w in
                HStack(spacing: 10) {
                    Text(Fmt.int(Int(w.place))).scaledFont(12, weight: .heavy).frame(width: 26, height: 26)
                        .background(w.place == 1 ? Festive.gold : Color(red: 0.875, green: 0.898, blue: 0.941), in: Circle())
                    Text("@" + w.account + (w.account == account ? " " + L.Native.Advent.you : "")).bold(w.account == account)
                    Spacer()
                    if let door { Text(AdventCopy.shared.prize(kind: door.reward.kind, amount: amount(door, w.place))) }
                }
                .scaledFont(14).accessibilityElement(children: .combine).accessibilityIdentifier("advent-winner-\(w.place)")
            }
            Text(L.Native.Advent.entries(n: Int(r.entries))).scaledFont(13, weight: .bold).foregroundStyle(.secondary)
            if let door {
                let total = max(r.tallies.map { Int(truncating: $0) }.reduce(0, +), 1)
                ForEach(Array(door.choices.enumerated()), id: \.offset) { i, c in
                    let share = Double(i < r.tallies.count ? Int(truncating: r.tallies[i]) : 0) / Double(total)
                    let right = r.answerIndex?.intValue == i
                    VStack(alignment: .leading, spacing: 4) {
                        HStack {
                            Text(c + (right ? " · " + L.Native.Advent.rightAnswer : "")).bold(right)
                            Spacer()
                            Text(Fmt.percent(Int((share * 100).rounded())))
                        }
                        .scaledFont(13)
                        GeometryReader { geo in
                            ZStack(alignment: .leading) {
                                RoundedRectangle(cornerRadius: 4).fill(Color(red: 0.914, green: 0.929, blue: 0.961))
                                RoundedRectangle(cornerRadius: 4).fill(right ? Festive.holly : Color(red: 0.541, green: 0.651, blue: 0.839)).frame(width: geo.size.width * share)
                            }
                        }
                        .frame(height: 8).accessibilityHidden(true)
                    }
                    .accessibilityElement(children: .combine)
                }
            }
            if let block = r.seedBlock?.int64Value {
                Text(L.Native.Advent.seedBlock(block: String(block))).scaledFont(12).foregroundStyle(.secondary).accessibilityIdentifier("advent-seed")
            }
        }
        if account != nil && sheet.door != nil {
            Button(sheet.answered ? L.Native.Advent.yourAnswerOpen : L.Native.Advent.answerLate) { model.answerFromResult() }.bold()
                .accessibilityIdentifier("advent-answer-from-result")
        }
        Button(L.Native.Advent.back) { model.closeSheet() }.frame(maxWidth: .infinity, minHeight: 44)
    }

    private func amount(_ door: AdventDoor, _ place: Int32) -> Double {
        let i = Int(place) - 1
        return i >= 0 && i < door.reward.amounts.count ? door.reward.amounts[i].doubleValue : 0
    }
}

/// The feed's Advent banner (web advent-banner.tsx): by date, closable for the rest of the UTC day.
struct AdventFeedBanner: View {
    @EnvironmentObject private var app: AppContainer
    @State private var version = 0
    @State private var events: Closeable?
    @Environment(\.dynamicTypeSize) private var typeSize
    /// The banner follows the UTC day by itself (a new door, the end of the season), as Android's minute tick.
    private let minute = Timer.publish(every: 60, on: .main, in: .common).autoconnect()

    var body: some View {
        let _ = version
        VStack(spacing: 0) {
            if let banner = app.env.advent.banner(nowMillis: app.adventNowMillis()) { card(banner) }
        }
        .onReceive(minute) { _ in version += 1 }
        .onAppear {
            if events == nil {
                // answering today's door (on time) hides the banner for the UTC day
                events = app.env.watchEvents { event in
                    MainActor.assumeIsolated { if event is AppEventAdventBannerChanged { version += 1 } }
                }
            }
        }
        .onDisappear {
            events?.close()
            events = nil
        }
    }

    private func card(_ banner: AdventBanner) -> some View {
            VStack(alignment: .leading, spacing: 12) {
                HStack(alignment: .top, spacing: 12) {
                    // at the accessibility text sizes the present gives its width to the words
                    if !typeSize.isAccessibilitySize {
                        ZStack {
                            RoundedRectangle(cornerRadius: 10).fill(Festive.red)
                            Festive.gold.frame(width: 8)
                            Festive.gold.frame(height: 8)
                        }
                        .frame(width: 50, height: 50).clipShape(RoundedRectangle(cornerRadius: 10)).shadow(color: Festive.gold.opacity(0.6), radius: 8)
                        .padding(.top, 6).accessibilityHidden(true)
                    }
                    VStack(alignment: .leading, spacing: 4) {
                        Text(banner.title).font(Festive.script(26)).foregroundStyle(Festive.gold).accessibilityAddTraits(.isHeader)
                            .fixedSize(horizontal: false, vertical: true)
                        Text(banner.text).scaledFont(13).foregroundStyle(Festive.inkSoft).fixedSize(horizontal: false, vertical: true)
                    }
                    Spacer(minLength: 0)
                    Button {
                        app.env.advent.closeBanner()
                        version += 1
                    } label: {
                        Image(appIcon: .close).foregroundStyle(Festive.inkSoft).frame(width: 44, height: 44)
                    }
                    .accessibilityLabel(L.Native.Advent.bannerClose)
                    .accessibilityIdentifier("advent-banner-close")
                }
                Button { app.push(AppScreenAdvent.shared) } label: {
                    Text(banner.action).scaledFont(14, weight: .bold).foregroundStyle(Festive.ink)
                        .multilineTextAlignment(.leading).fixedSize(horizontal: false, vertical: true)
                        .padding(.horizontal, 18).padding(.vertical, 10).frame(minHeight: 44).background(Festive.gold, in: Capsule())
                }
                .buttonStyle(.plain).accessibilityIdentifier("advent-banner-open")
            }
            .padding(.leading, 16).padding(.top, 24).padding(.bottom, 16).padding(.trailing, 6)
            .background {
                Canvas { ctx, size in
                    let u = size.width / 366
                    var wire = Path()
                    wire.move(to: CGPoint(x: -5 * u, y: 4 * u))
                    var x: CGFloat = -5
                    while x < 372 {
                        wire.addQuadCurve(to: CGPoint(x: (x + 92) * u, y: 6 * u), control: CGPoint(x: (x + 46) * u, y: 22 * u))
                        x += 92
                    }
                    ctx.stroke(wire, with: .color(Festive.wire), lineWidth: 2 * u)
                    for i in 0..<8 {
                        let cx = CGFloat(22 + i * 46) * u
                        let cy = CGFloat(i % 3 == 1 ? 15 : i % 3 == 2 ? 9 : 12) * u
                        let c = Festive.lights[i % 4]
                        ctx.fill(Path(ellipseIn: CGRect(x: cx - 10 * u, y: cy - 10 * u, width: 20 * u, height: 20 * u)), with: .color(c.opacity(0.25)))
                        ctx.fill(Path(ellipseIn: CGRect(x: cx - 4.5 * u, y: cy - 6 * u, width: 9 * u, height: 12 * u)), with: .color(c))
                    }
                    AdventSky.flake(ctx, at: CGPoint(x: 300 * u, y: 70 * u), r: 6 * u)
                }
                .background(Festive.night)
                .accessibilityHidden(true)
            }
            .clipShape(RoundedRectangle(cornerRadius: 16))
            .padding(.horizontal, 12).padding(.top, 12).padding(.bottom, 8)
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("advent-banner")
    }
}

import EcencyCore
import SwiftUI
import UIKit

// The one place icons become pictures on iOS: every AppIcon (shared core, named by meaning) maps to an SF Symbol
// available on iOS 17, outlined at rest and the filled form for an active, selected or toggled state. The list
// with both platforms' names is native/scripts/icons/icons.tsv; AppIconTests checks every entry resolves.
// Directional icons use the backward / forward symbols, which mirror in right to left layouts on their own.
// The Waves mark is drawn by WavyDash (from the shared WavesMark path); its symbol here is the system stand-in.

extension AppIcon {
    /// The SF Symbol at rest, or nil when the entry has no mapping yet (AppIconTests fails then).
    var mappedSymbol: String? {
        switch self {
        case .home: return "house"
        case .waves: return "water.waves"
        case .chat: return "bubble.left"
        case .comment: return "bubble.left"
        case .wallet: return "creditcard"
        case .notifications: return "bell"
        case .notificationsOff: return "bell.slash"
        case .back: return "chevron.backward"
        case .arrowForward: return "arrow.forward"
        case .chevronLeft: return "chevron.backward"
        case .chevronRight: return "chevron.forward"
        case .chevronDoubleRight: return "chevron.forward.2"
        case .expand: return "chevron.down"
        case .collapse: return "chevron.up"
        case .arrowUp: return "arrow.up"
        case .arrowDown: return "arrow.down"
        case .dropdown: return "arrowtriangle.down.fill"
        case .more: return "ellipsis"
        case .close: return "xmark"
        case .clear: return "xmark.circle"
        case .add: return "plus"
        case .remove: return "minus.circle"
        case .check: return "checkmark"
        case .checkCircle: return "checkmark.circle"
        case .task: return "circle"
        case .checkbox: return "square"
        case .search: return "magnifyingglass"
        case .qrScan: return "qrcode.viewfinder"
        case .boost: return "flame"
        case .person: return "person"
        case .account: return "person.crop.circle"
        case .personAdd: return "person.badge.plus"
        case .group: return "person.2"
        case .members: return "person.3"
        case .groupAdd: return "person.crop.circle.badge.plus"
        case .bookmark: return "bookmark"
        case .drafts: return "doc.text"
        case .explore: return "safari"
        case .share: return "square.and.arrow.up"
        case .help: return "questionmark.circle"
        case .settings: return "gearshape"
        case .power: return "power"
        case .logout: return "rectangle.portrait.and.arrow.forward"
        case .gift: return "gift"
        case .translate: return "character.bubble"
        case .refresh: return "arrow.clockwise"
        case .lock: return "lock"
        case .verifiedUser: return "checkmark.shield"
        case .moderator: return "shield"
        case .globe: return "globe"
        case .warning: return "exclamationmark.triangle"
        case .error: return "exclamationmark.circle"
        case .info: return "info.circle"
        case .play: return "play"
        case .pause: return "pause"
        case .playCircle: return "play.circle"
        case .headphones: return "headphones"
        case .chartBar: return "chart.bar"
        case .chartLine: return "chart.xyaxis.line"
        case .edit: return "pencil"
        case .compose: return "square.and.pencil"
        case .swap: return "arrow.left.arrow.right"
        case .sort: return "arrow.up.arrow.down"
        case .discard: return "trash"
        case .heart: return "heart"
        case .reblog: return "arrow.2.squarepath"
        case .repeat: return "repeat"
        case .pin: return "pin"
        case .send: return "paperplane"
        case .backspace: return "delete.backward"
        case .key: return "key"
        case .star: return "star"
        case .snowflake: return "snowflake"
        case .bolt: return "bolt"
        case .megaphone: return "megaphone"
        case .rocket: return "paperplane"
        case .checkin: return "checkmark.circle"
        case .voteUp: return "chevron.up.circle"
        case .voteDown: return "chevron.down.circle"
        case .tune: return "slider.horizontal.3"
        case .filter: return "line.3.horizontal.decrease.circle"
        case .history: return "clock.arrow.circlepath"
        case .preview: return "eye"
        case .ai: return "cpu"
        case .aiAssist: return "sparkles"
        case .image: return "photo"
        case .link: return "link"
        case .download: return "square.and.arrow.down"
        case .clipboard: return "doc.on.doc"
        case .verified: return "checkmark.seal"
        case .calendar: return "calendar"
        case .location: return "location"
        case .email: return "envelope"
        case .muted: return "speaker.slash"
        case .soundOn: return "speaker.wave.2"
        case .sortAz: return "textformat.abc"
        case .markAllRead: return "checklist.checked"
        case .camera: return "camera"
        case .folder: return "folder"
        case .video: return "video"
        case .videoAttached: return "video.badge.checkmark"
        case .mic: return "mic"
        case .crossPost: return "arrow.triangle.branch"
        case .reply: return "arrowshape.turn.up.backward"
        case .fullscreen: return "arrow.up.left.and.arrow.down.right"
        case .offline: return "wifi.exclamationmark"
        case .cloudOff: return "icloud.slash"
        case .faceId: return "faceid"
        case .touchId: return "touchid"
        case .ticket: return "ticket"
        case .money: return "dollarsign.circle"
        case .autorenew: return "arrow.triangle.2.circlepath"
        case .reorder: return "line.3.horizontal"
        case .escrow: return "lock.shield"
        case .delegation: return "triangle"
        case .hourglassFull: return "hourglass"
        case .hourglassTop: return "hourglass.tophalf.filled"
        case .created: return "burst"
        case .refund: return "arrow.uturn.backward.circle"
        case .trophy: return "trophy"
        case .target: return "target"
        case .shrink: return "arrow.down.right.and.arrow.up.left"
        default: return nil
        }
    }

    /// The filled SF Symbol, or nil when the symbol has no filled form (the outline is drawn instead).
    var filledSymbol: String? {
        switch self {
        case .home: return "house.fill"
        case .chat: return "bubble.left.fill"
        case .comment: return "bubble.left.fill"
        case .wallet: return "creditcard.fill"
        case .notifications: return "bell.fill"
        case .notificationsOff: return "bell.slash.fill"
        case .clear: return "xmark.circle.fill"
        case .remove: return "minus.circle.fill"
        case .checkCircle: return "checkmark.circle.fill"
        case .task: return "checkmark.circle.fill"
        case .checkbox: return "checkmark.square.fill"
        case .boost: return "flame.fill"
        case .person: return "person.fill"
        case .account: return "person.crop.circle.fill"
        case .personAdd: return "person.fill.badge.plus"
        case .group: return "person.2.fill"
        case .members: return "person.3.fill"
        case .groupAdd: return "person.crop.circle.fill.badge.plus"
        case .bookmark: return "bookmark.fill"
        case .drafts: return "doc.text.fill"
        case .explore: return "safari.fill"
        case .share: return "square.and.arrow.up.fill"
        case .help: return "questionmark.circle.fill"
        case .settings: return "gearshape.fill"
        case .logout: return "rectangle.portrait.and.arrow.forward.fill"
        case .gift: return "gift.fill"
        case .translate: return "character.bubble.fill"
        case .lock: return "lock.fill"
        case .verifiedUser: return "checkmark.shield.fill"
        case .moderator: return "shield.fill"
        case .warning: return "exclamationmark.triangle.fill"
        case .error: return "exclamationmark.circle.fill"
        case .info: return "info.circle.fill"
        case .play: return "play.fill"
        case .pause: return "pause.fill"
        case .playCircle: return "play.circle.fill"
        case .chartBar: return "chart.bar.fill"
        case .discard: return "trash.fill"
        case .heart: return "heart.fill"
        case .pin: return "pin.fill"
        case .send: return "paperplane.fill"
        case .backspace: return "delete.backward.fill"
        case .key: return "key.fill"
        case .star: return "star.fill"
        case .bolt: return "bolt.fill"
        case .megaphone: return "megaphone.fill"
        case .rocket: return "paperplane.fill"
        case .checkin: return "checkmark.circle.fill"
        case .voteUp: return "chevron.up.circle.fill"
        case .voteDown: return "chevron.down.circle.fill"
        case .filter: return "line.3.horizontal.decrease.circle.fill"
        case .preview: return "eye.fill"
        case .ai: return "cpu.fill"
        case .image: return "photo.fill"
        case .download: return "square.and.arrow.down.fill"
        case .clipboard: return "doc.on.doc.fill"
        case .verified: return "checkmark.seal.fill"
        case .location: return "location.fill"
        case .email: return "envelope.fill"
        case .muted: return "speaker.slash.fill"
        case .soundOn: return "speaker.wave.2.fill"
        case .camera: return "camera.fill"
        case .folder: return "folder.fill"
        case .video: return "video.fill"
        case .videoAttached: return "video.fill.badge.checkmark"
        case .mic: return "mic.fill"
        case .reply: return "arrowshape.turn.up.backward.fill"
        case .cloudOff: return "icloud.slash.fill"
        case .ticket: return "ticket.fill"
        case .money: return "dollarsign.circle.fill"
        case .escrow: return "lock.shield.fill"
        case .delegation: return "triangle.fill"
        case .created: return "burst.fill"
        case .refund: return "arrow.uturn.backward.circle.fill"
        case .trophy: return "trophy.fill"
        default: return nil
        }
    }

    /// The SF Symbol name to draw: the filled form when [filled] and there is one.
    func symbol(filled: Bool = false) -> String {
        (filled ? filledSymbol : nil) ?? mappedSymbol ?? "questionmark.square.dashed"
    }
}

extension SwiftUI.Image {
    /// An app icon as an SF Symbol. Size it with a text style (`.font(.body)`, `.imageScale`) so it follows
    /// Dynamic Type; an icon-only control takes its accessibility label from its action.
    init(appIcon: AppIcon, filled: Bool = false) {
        self.init(systemName: appIcon.symbol(filled: filled))
    }
}

extension Label where Title == SwiftUI.Text, Icon == SwiftUI.Image {
    /// A label with an app icon; the title is shown as written.
    init<S: StringProtocol>(_ title: S, appIcon: AppIcon, filled: Bool = false) {
        self.init { SwiftUI.Text(title) } icon: { SwiftUI.Image(appIcon: appIcon, filled: filled) }
    }
}

extension UIImage {
    /// An app icon for UIKit views.
    convenience init?(appIcon: AppIcon, filled: Bool = false) {
        self.init(systemName: appIcon.symbol(filled: filled))
    }
}

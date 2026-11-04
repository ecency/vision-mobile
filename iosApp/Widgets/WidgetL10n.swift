import Foundation

/// A language's digits and grouping (the shared CLDR data), as the app writes numbers in messages.
struct WidgetNumberFormat {
    let digits: String
    let group: String
    let minus: String
    let primary: Int
    let secondary: Int
    let minGrouping: Int
}

/// CLDR plural operands of a whole number (the widgets only count).
struct WidgetPluralOperands {
    let n, i, v, w, f, t, e: Int

    init(_ value: Int) {
        n = abs(value)
        i = abs(value)
        v = 0
        w = 0
        f = 0
        t = 0
        e = 0
    }
}

/// The widget extension's copy in the app language (README "Localization" and "Widgets").
///
/// The extension runs in its own process and does not link the shared framework, so its messages come generated from
/// src/config/locales (`WidgetL10nData`, with the plural rules and digits of every language) and are formatted here
/// the way the shared catalog formats them: a language's own message when it has one, else English (plural forms
/// then English's), numbers in the app language's digits and grouping, substituted text in bidi isolates in a right
/// to left language. The app hands its language over through the App Group (`IosWidgetStore.handOver`); before the
/// app has run, the device's language is matched as the app's first start would.
struct WidgetL10n: Equatable {
    enum Value {
        case text(String)
        case number(Int)
    }

    static let group = "group.com.ecency.eshare"
    static let fallback = "en-US"

    /// One of `WidgetL10nData.languages`.
    let language: String

    init(language: String?) {
        self.language = WidgetL10n.match(language) ?? WidgetL10n.fallback
    }

    /// The app language the app handed over, else the device's.
    static var current: WidgetL10n {
        WidgetL10n(language: handedOver() ?? Locale.preferredLanguages.first)
    }

    /// The file the app writes its language to, beside the widget snapshot (`IosWidgetStore.languageFileURL`).
    static func languageFileURL() -> URL? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group)?
            .appendingPathComponent("Widgets", isDirectory: true).appendingPathComponent("language")
    }

    /// The handed over language when it is one of the app's (untrusted like any stored value).
    static func handedOver() -> String? {
        guard let file = languageFileURL(), let data = try? Data(contentsOf: file), data.count <= 32,
              let text = String(data: data, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines) else { return nil }
        return WidgetL10nData.languages.contains(text) ? text : nil
    }

    /// The shared `LocaleMatch.registered` (RN mapToRegisteredLocale): exact id in any case or with `_`, Chinese by
    /// script or region, then the base language (with the old ISO codes iw, in, ji).
    static func match(_ locale: String?) -> String? {
        guard let locale, !locale.isEmpty else { return nil }
        let ids = WidgetL10nData.languages
        let norm = locale.replacingOccurrences(of: "_", with: "-").lowercased()
        if let exact = ids.first(where: { $0.lowercased() == norm }) { return exact }
        if norm.hasPrefix("zh") {
            let traditional = norm.contains("hant") || ["tw", "hk", "mo"].contains { norm.contains("-\($0)") }
            if let zh = ids.first(where: { $0.lowercased() == (traditional ? "zh-tw" : "zh-cn") }) { return zh }
        }
        let raw = String(norm.split(separator: "-").first ?? "")
        let base = ["iw": "he", "in": "id", "ji": "yi"][raw] ?? raw
        return ids.first { String($0.split(separator: "-")[0]).lowercased() == base }
    }

    /// Right to left: ar, fa and he (ku-TR is Latin script and stays left to right).
    var isRtl: Bool { ["ar", "fa", "he"].contains(String(language.split(separator: "-")[0])) }

    /// The OS tag of the language ("de", "zh-Hans"), for the views' locale.
    var systemTag: String {
        WidgetL10nData.languages.firstIndex(of: language).map { WidgetL10nData.systemTags[$0] } ?? "en"
    }

    /// A whole number in the language's digits and grouping ("1,234", "١٬٢٣٤", "12 345").
    func number(_ value: Int) -> String {
        guard let f = WidgetL10nData.numbers[language] ?? WidgetL10nData.numbers[WidgetL10n.fallback] else { return String(value) }
        let digits = Array(f.digits)
        let whole = Array(String(value.magnitude))
        var out = value < 0 ? f.minus : ""
        let n = whole.count
        let grouped = n >= f.primary + f.minGrouping
        for (i, c) in whole.enumerated() {
            out.append(c.wholeNumberValue.map { digits[$0] } ?? c)
            let left = n - 1 - i
            if !grouped || left == 0 { continue }
            if left == f.primary || (left > f.primary && (left - f.primary) % f.secondary == 0) { out += f.group }
        }
        return out
    }

    func text(_ index: Int) -> String { format(index, [:]) }

    /// The message at generated index [index]: the language's own when it parses and formats, else English.
    func format(_ index: Int, _ args: [String: Value]) -> String {
        if let own = WidgetL10nData.translations[language]?[index], let parts = WidgetIcu.parse(own),
           let text = render(parts, args, pluralLanguage: language, pound: nil) {
            // as the shared catalog: a right to left language's own message starts with RLM, once
            return isRtl && !text.isEmpty && !text.hasPrefix("\u{200F}") ? "\u{200F}" + text : text
        }
        let english = WidgetL10nData.english[index]
        return WidgetIcu.parse(english).flatMap { render($0, args, pluralLanguage: WidgetL10n.fallback, pound: nil) } ?? english
    }

    private func isolate(_ s: String) -> String { isRtl && !s.isEmpty ? "\u{2068}\(s)\u{2069}" : s }

    private func render(_ parts: [WidgetIcu.Part], _ args: [String: Value], pluralLanguage: String, pound: Int?) -> String? {
        var out = ""
        for part in parts {
            switch part {
            case let .text(t):
                out += t
            case let .arg(name):
                switch args[name] {
                case let .text(t): out += isolate(t)
                case let .number(n): out += isolate(number(n))
                case nil: return nil
                }
            case let .number(name):
                guard case let .number(n) = args[name] else { return nil }
                out += number(n)
            case .pound:
                out += pound.map(number) ?? "#"
            case let .plural(name, offset, options):
                guard case let .number(value) = args[name] else { return nil }
                let n = value - offset
                let chosen = options["=\(value)"] ?? options[WidgetL10nData.pluralCategory(pluralLanguage, WidgetPluralOperands(n))] ?? options["other"]
                guard let chosen, let text = render(chosen, args, pluralLanguage: pluralLanguage, pound: n) else { return nil }
                out += text
            case let .select(name, options):
                let key: String
                switch args[name] {
                case let .text(t): key = t
                case let .number(n): key = String(n)
                case nil: return nil
                }
                guard let chosen = options[key] ?? options["other"], let text = render(chosen, args, pluralLanguage: pluralLanguage, pound: pound) else { return nil }
                out += text
            }
        }
        return out
    }
}

/// The ICU subset of the shared `IcuMessage` (`{x}`, `{x, number}`, plural with `=N`, offset and `#`, select, and
/// ICU's DOUBLE_OPTIONAL apostrophes); nil for anything malformed.
enum WidgetIcu {
    indirect enum Part: Equatable {
        case text(String)
        case arg(String)
        case number(String)
        case pound
        case plural(String, Int, [String: [Part]])
        case select(String, [String: [Part]])
    }

    static func parse(_ message: String) -> [Part]? {
        var p = Parser(Array(message))
        guard let parts = p.message(inPlural: false, nested: false), p.pos == p.s.count else { return nil }
        return parts
    }

    private struct Parser {
        let s: [Character]
        var pos = 0

        init(_ s: [Character]) { self.s = s }

        private func at(_ i: Int) -> Character? { i < s.count ? s[i] : nil }

        mutating func message(inPlural: Bool, nested: Bool) -> [Part]? {
            var parts: [Part] = []
            var text = ""
            func flush() { if !text.isEmpty { parts.append(.text(text)); text = "" } }
            while pos < s.count {
                let c = s[pos]
                if c == "'" {
                    quote(&text, inPlural: inPlural)
                } else if c == "{" {
                    flush()
                    pos += 1
                    guard let a = argument() else { return nil }
                    parts.append(a)
                } else if c == "}" {
                    guard nested else { return nil }
                    flush()
                    return parts
                } else if c == "#" && inPlural {
                    flush()
                    parts.append(.pound)
                    pos += 1
                } else {
                    text.append(c)
                    pos += 1
                }
            }
            if nested { return nil }
            flush()
            return parts
        }

        mutating func quote(_ text: inout String, inPlural: Bool) {
            let next = at(pos + 1)
            if next == "'" { text.append("'"); pos += 2; return }
            if next == "{" || next == "}" || (inPlural && next == "#") {
                pos += 1
                while pos < s.count {
                    let c = s[pos]
                    if c == "'" {
                        if at(pos + 1) == "'" { text.append("'"); pos += 2; continue }
                        pos += 1
                        return
                    }
                    text.append(c)
                    pos += 1
                }
                return
            }
            text.append("'")
            pos += 1
        }

        mutating func ws() { while pos < s.count && s[pos].isWhitespace { pos += 1 } }

        mutating func ident() -> String? {
            ws()
            let start = pos
            while pos < s.count && !s[pos].isWhitespace && s[pos] != "," && s[pos] != "{" && s[pos] != "}" { pos += 1 }
            return pos > start ? String(s[start..<pos]) : nil
        }

        mutating func expect(_ c: Character) -> Bool {
            ws()
            guard pos < s.count, s[pos] == c else { return false }
            pos += 1
            return true
        }

        mutating func argument() -> Part? {
            guard let name = ident() else { return nil }
            ws()
            guard pos < s.count else { return nil }
            if s[pos] == "}" { pos += 1; return .arg(name) }
            guard expect(","), let type = ident() else { return nil }
            switch type {
            case "number":
                return expect("}") ? .number(name) : nil
            case "plural", "select":
                let isSelect = type == "select"
                guard expect(",") else { return nil }
                var offset = 0
                var options: [String: [Part]] = [:]
                while true {
                    ws()
                    guard pos < s.count else { return nil }
                    if s[pos] == "}" { pos += 1; break }
                    guard let sel = ident() else { return nil }
                    if !isSelect && sel.hasPrefix("offset:") {
                        guard options.isEmpty else { return nil }
                        var value = String(sel.dropFirst("offset:".count))
                        if value.isEmpty { value = ident() ?? "" }
                        guard let o = Int(value) else { return nil }
                        offset = o
                        continue
                    }
                    if !isSelect && sel.hasPrefix("=") && Int(sel.dropFirst()) == nil { return nil }
                    guard options[sel] == nil, expect("{"), let sub = message(inPlural: !isSelect, nested: true), expect("}") else { return nil }
                    options[sel] = sub
                }
                guard options["other"] != nil else { return nil }
                return isSelect ? .select(name, options) : .plural(name, offset, options)
            default:
                return nil
            }
        }
    }
}

import EcencyCore

/// Numbers shown in the UI, in the app language (README "Localization", formatting): never through the device
/// locale (`Text("\(n)")` formats with it) and never for text that is sent or signed.
enum Fmt {
    /// A plain count: "1,234" in en-US, "1.234" in de-DE, Arabic-Indic digits in ar-SA.
    static func int<T: BinaryInteger>(_ value: T) -> String { LocaleFormat.shared.integer(value: Int64(value)) }

    /// A whole percent (12 is "12%", "12 %" in de-DE).
    static func percent<T: BinaryInteger>(_ value: T) -> String {
        LocaleFormat.shared.percent(value: Double(Int64(value)), maxFraction: 0, minFraction: 0)
    }
}

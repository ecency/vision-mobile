package com.ecency.l10n.gen

/**
 * The strings the OS reads outside the app's own catalog, generated for every locale file from
 * `shared/l10n/system-strings.json` (which key each one shows; README "Localization"):
 * - Android: `strings.xml` in `values` and `values-<qualifier>` (manifest and widget provider labels and descriptions)
 *   beside `xml/locales_config.xml`,
 *   in the resource folders of the [SystemLocales] tags ([androidQualifier]).
 * - iOS: `<tag>.lproj/InfoPlist.strings` (permission prompts) and the widget extension's table
 *   `WidgetL10nData.swift` (the extension does not link the shared framework, so it gets its messages, plural rules and
 *   digits as Swift data; `iosApp/Widgets/WidgetL10n.swift` formats them).
 *
 * A locale shows its translation of a key under the runtime's rule ([resolve]); anything else shows English, so every
 * file holds every string (Android lint wants no missing translation). Pure and deterministic, as [L10nGenerator].
 */
object SystemStrings {
    internal const val HEADER_NOTE = "Generated from src/config/locales by native/build-logic (SystemStrings). Do not edit."

    /** Android's legacy language codes: resources resolve Hebrew, Indonesian and Yiddish under these. */
    private val ANDROID_LEGACY = mapOf("he" to "iw", "id" to "in", "yi" to "ji")

    private val RESOURCE_NAME = Regex("[a-z][a-z0-9_]*")
    private val PLIST_KEY = Regex("[A-Za-z][A-Za-z0-9_]*")
    private val SWIFT_NAME = Regex("[a-z][A-Za-z0-9]*")

    data class Config(val android: Map<String, String>, val infoPlist: Map<String, String>, val widget: Map<String, String>)

    @Suppress("UNCHECKED_CAST")
    fun config(json: String): Config {
        val root = MiniJson.parse(json) as? Map<String, Any?> ?: throw IllegalArgumentException("system strings: not an object")
        fun section(name: String, valid: Regex): Map<String, String> {
            val m = root[name] as? Map<String, Any?> ?: throw IllegalArgumentException("system strings: no '$name' object")
            return m.mapValues { (k, v) ->
                require(valid.matches(k)) { "system strings $name: bad name '$k'" }
                v as? String ?: throw IllegalArgumentException("system strings $name.$k: not a key")
            }
        }
        return Config(section("android", RESOURCE_NAME), section("infoPlist", PLIST_KEY), section("widget", SWIFT_NAME))
    }

    /** The flattened messages of each locale, en-US first checked to hold every key [config] names. */
    private class Messages(locales: Map<String, String>, keys: Collection<String>) {
        val ids: List<String> = locales.keys.sorted()
        val en: Map<String, String>
        val byLocale: Map<String, Map<String, String>>

        init {
            require(L10nGenerator.SOURCE_LOCALE in locales) { "missing ${L10nGenerator.SOURCE_LOCALE}.json" }
            byLocale = locales.mapValues { (id, text) ->
                runCatching { L10nGenerator.flatten(MiniJson.parse(text), "") }.getOrElse { throw IllegalArgumentException("$id.json: ${it.message}", it) }
            }
            en = byLocale.getValue(L10nGenerator.SOURCE_LOCALE)
            for (k in keys) {
                val m = requireNotNull(en[k]) { "system strings: en-US has no key '$k'" }
                try { IcuPart.parse(m) } catch (e: IllegalArgumentException) { throw IllegalArgumentException("en-US '$k': ${e.message}", e) }
            }
        }

        fun resolve(id: String, key: String) = resolve(en.getValue(key), byLocale[id]?.get(key).takeIf { id != L10nGenerator.SOURCE_LOCALE })
    }

    /**
     * The message a locale shows for a key whose English is [english]: [translated] when it is set, differs from
     * English, parses and uses no argument English lacks (it may drop some, as react-intl allows), else English. The
     * shared runtime's rule (`L10n.resolve`), so the OS strings agree with the in-app copy. Second: true for the
     * translation (plural rules then follow that language; English's otherwise).
     */
    internal fun resolve(english: String, translated: String?): Pair<String, Boolean> {
        if (translated.isNullOrEmpty() || translated == english) return english to false
        val ok = runCatching { IcuPart.names(IcuPart.parse(english)).containsAll(IcuPart.names(IcuPart.parse(translated))) }.getOrDefault(false)
        return if (ok) translated to true else english to false
    }

    /** The plain text of a message that takes no arguments (ICU quoting resolved); fails for one that does. */
    internal fun plainText(key: String, message: String): String =
        IcuPart.plain(IcuPart.parse(message)) ?: throw IllegalArgumentException("system string '$key' takes arguments: $message")

    // ---- Android

    /**
     * The resource folder of locale [id]: `values` for en-US, else from its [SystemLocales] tag: `values-de`,
     * `values-pt-rPT`, `values-b+zh+Hans`, `values-b+sr+Latn`, `values-b+fil` (a three letter language), with Android's
     * legacy codes for Hebrew (`values-iw`) and Indonesian (`values-in`).
     */
    fun androidQualifier(id: String): String {
        if (id == L10nGenerator.SOURCE_LOCALE) return "values"
        val parts = SystemLocales.tag(id).split('-')
        val lang = ANDROID_LEGACY[parts[0]] ?: parts[0]
        val rest = parts.drop(1)
        return when {
            rest.isEmpty() && lang.length == 2 -> "values-$lang"
            rest.size == 1 && lang.length == 2 && rest[0].length == 2 && rest[0].all { it in 'A'..'Z' } -> "values-$lang-r${rest[0]}"
            else -> "values-b+" + (listOf(lang) + rest).joinToString("+")
        }
    }

    /**
     * The bare language folders that no locale fills itself (`values-pt`, `values-sr`, `values-zh`), each with the
     * locale LocaleMatch picks for that language alone: the first of [ids] (sorted) with that base language.
     */
    fun androidFallbacks(ids: List<String>): Map<String, String> {
        val own = ids.map(::androidQualifier).toSet()
        val out = LinkedHashMap<String, String>()
        for (id in ids.sorted()) {
            if (id == L10nGenerator.SOURCE_LOCALE) continue
            val base = SystemLocales.tag(id).substringBefore('-')
            val lang = ANDROID_LEGACY[base] ?: base
            val folder = if (lang.length == 2) "values-$lang" else "values-b+$lang"
            if (folder !in own && folder !in out) out[folder] = id
        }
        return out
    }

    /**
     * [text] as the content of an Android `<string>`: XML special characters as entities; `\`, `'` and `"` escaped
     * (aapt drops a bare apostrophe or quote); a leading `@` or `?` escaped (else it reads as a reference); line breaks
     * and tabs as `\n` and `\t`; other control characters as `\uXXXX` (XML 1.0 cannot hold them); the whole value in
     * quotes when it starts or ends with white space or holds a run of it (aapt trims and collapses it otherwise).
     */
    fun androidValue(text: String): String {
        val b = StringBuilder()
        text.forEachIndexed { i, c ->
            when {
                c == '&' -> b.append("&amp;")
                c == '<' -> b.append("&lt;")
                c == '>' -> b.append("&gt;")
                c == '\\' -> b.append("\\\\")
                c == '\'' -> b.append("\\'")
                c == '"' -> b.append("\\\"")
                c == '\n' -> b.append("\\n")
                c == '\t' -> b.append("\\t")
                (c == '@' || c == '?') && i == 0 -> b.append('\\').append(c)
                c < ' ' || c in '\u007f'..'\u009f' || c == '\u2028' || c == '\u2029' -> b.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> b.append(c)
            }
        }
        val spaced = text.isNotEmpty() && (text.first() == ' ' || text.last() == ' ' || "  " in text)
        return if (spaced) "\"$b\"" else b.toString()
    }

    /** A `strings.xml` with [entries] (name to text); `formatted="false"` where a text has `%` (aapt reads it as a format otherwise). */
    fun androidStringsXml(entries: List<Pair<String, String>>): String {
        val b = StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        b.append("<!-- $HEADER_NOTE -->\n<resources>\n")
        for ((name, text) in entries) {
            b.append("    <string name=\"").append(name).append('"')
            if ('%' in text) b.append(" formatted=\"false\"")
            b.append('>').append(androidValue(text)).append("</string>\n")
        }
        b.append("</resources>\n")
        return b.toString()
    }

    /** Android's generated resources, by path under the resource directory: `xml/locales_config.xml` and each folder's `strings.xml`. */
    fun android(locales: Map<String, String>, configJson: String): Map<String, String> {
        val config = config(configJson)
        val m = Messages(locales, config.android.values)
        val out = LinkedHashMap<String, String>()
        out["xml/locales_config.xml"] = SystemLocales.localeConfigXml(m.ids)
        val folders = HashSet<String>()
        val xml = m.ids.associateWith { id -> androidStringsXml(config.android.map { (name, key) -> name to plainText(key, m.resolve(id, key).first) }) }
        for (id in m.ids) {
            val folder = androidQualifier(id)
            require(folders.add(folder)) { "two locales share the resource folder $folder" }
            out["$folder/strings.xml"] = xml.getValue(id)
        }
        // A device in a region or script no folder names (pt-BR, sr-RS in Cyrillic, zh-SG) falls back to the bare
        // language folder; it holds what the app picks for that device (LocaleMatch's base language rule: the first
        // locale of that language, so pt-PT, sr-CS, zh-CN), so the system's labels agree with the app's language.
        for ((folder, id) in androidFallbacks(m.ids)) {
            if (folders.add(folder)) out["$folder/strings.xml"] = xml.getValue(id)
        }
        return out
    }

    // ---- Apple

    /** [text] as an Apple `.strings` value (without the quotes): `"` and `\` escaped, control characters as escapes. */
    fun appleValue(text: String): String {
        val b = StringBuilder()
        for (c in text) {
            when {
                c == '"' -> b.append("\\\"")
                c == '\\' -> b.append("\\\\")
                c == '\n' -> b.append("\\n")
                c == '\t' -> b.append("\\t")
                c == '\r' -> b.append("\\r")
                c < ' ' || c in '\u007f'..'\u009f' || c == '\u2028' || c == '\u2029' -> b.append("\\U").append(c.code.toString(16).uppercase().padStart(4, '0'))
                else -> b.append(c)
            }
        }
        return b.toString()
    }

    fun appleStrings(entries: List<Pair<String, String>>): String {
        val b = StringBuilder("/* $HEADER_NOTE */\n")
        for ((k, v) in entries) b.append('"').append(appleValue(k)).append("\" = \"").append(appleValue(v)).append("\";\n")
        return b.toString()
    }

    /**
     * iOS's generated files by path: `InfoPlist/<tag>.lproj/InfoPlist.strings` for every locale (its [SystemLocales]
     * tag, as `CFBundleLocalizations`) and `WidgetL10nData.swift`.
     */
    fun apple(locales: Map<String, String>, configJson: String, pluralRulesJson: String, formatsJson: String): Map<String, String> {
        val config = config(configJson)
        val m = Messages(locales, config.infoPlist.values + config.widget.values)
        val out = LinkedHashMap<String, String>()
        for (id in m.ids) {
            out["InfoPlist/${SystemLocales.tag(id)}.lproj/InfoPlist.strings"] =
                appleStrings(config.infoPlist.map { (plistKey, key) -> plistKey to plainText(key, m.resolve(id, key).first) })
        }
        out["WidgetL10nData.swift"] = widgetSwift(m, config.widget, L10nGenerator.pluralRules(pluralRulesJson), formatsJson)
        return out
    }

    @Suppress("UNCHECKED_CAST")
    private fun widgetSwift(m: Messages, widget: Map<String, String>, rules: Map<String, Map<String, String>>, formatsJson: String): String {
        val formats = (MiniJson.parse(formatsJson) as? Map<String, Any?>)?.get("locales") as? Map<String, Any?>
            ?: throw IllegalArgumentException("formats: no 'locales' object")
        val keys = widget.values.distinct()
        val lit = L10nGenerator::swiftLiteral
        val b = StringBuilder("// $HEADER_NOTE\n\n")
        b.append("/// The widget extension's messages in every app language (keys from native/shared/l10n/system-strings.json), its\n")
        b.append("/// plural rules and digits; `WidgetL10n` formats them. A translation the shared catalog would refuse is left out\n")
        b.append("/// (nil), so it shows English as in the app.\n")
        b.append("enum WidgetL10nData {\n")
        b.append("    static let languages: [String] = [${m.ids.joinToString(", ") { lit(it) }}]\n\n")
        b.append("    /// The OS tag of each of `languages`, in the same order.\n")
        b.append("    static let systemTags: [String] = [${m.ids.joinToString(", ") { lit(SystemLocales.tag(it)) }}]\n\n")
        b.append("    static let keys: [String] = [${keys.joinToString(", ") { lit(it) }}]\n\n")
        b.append("    static let english: [String] = [\n")
        for (k in keys) b.append("        ").append(lit(m.en.getValue(k))).append(",\n")
        b.append("    ]\n\n")
        b.append("    /// Each language's own messages by key index (nil: English).\n")
        b.append("    static let translations: [String: [String?]] = [\n")
        for (id in m.ids) {
            if (id == L10nGenerator.SOURCE_LOCALE) continue
            val values = keys.map { k -> m.resolve(id, k).let { (text, own) -> if (own) text else null } }
            if (values.all { it == null }) continue
            b.append("        ").append(lit(id)).append(": [").append(values.joinToString(", ") { v -> v?.let(lit) ?: "nil" }).append("],\n")
        }
        b.append("    ]\n\n")
        b.append("    static let numbers: [String: WidgetNumberFormat] = [\n")
        for (id in m.ids) {
            val d = formats[id] as? Map<String, Any?> ?: throw IllegalArgumentException("formats: no data for locale '$id'")
            val grouping = (d["grouping"] as? List<Any?>)?.map { (it as Number).toInt() } ?: throw IllegalArgumentException("formats $id: no grouping")
            b.append("        ").append(lit(id)).append(": WidgetNumberFormat(digits: ").append(lit(d["digits"] as String))
                .append(", group: ").append(lit(d["group"] as String)).append(", minus: ").append(lit(d["minus"] as String))
                .append(", primary: ${grouping[0]}, secondary: ${grouping[1]}, minGrouping: ${grouping[2]}),\n")
        }
        b.append("    ]\n\n")
        // plural rules, grouped as L10nGenerator does
        val withRules = m.ids.filter { rules[it]?.isNotEmpty() == true }
        m.ids.forEach { require(it in rules) { "no CLDR plural rules for locale '$it'" } }
        val groups = withRules.groupBy { id -> L10nGenerator.CATEGORIES.mapNotNull { c -> rules.getValue(id)[c]?.let { c to it } } }
        b.append("    /// The CLDR plural category of a whole number in a language.\n")
        b.append("    static func pluralCategory(_ language: String, _ o: WidgetPluralOperands) -> String {\n")
        b.append("        switch language {\n")
        groups.values.forEachIndexed { i, members -> b.append("        case ${members.joinToString(", ") { lit(it) }}: return r$i(o)\n") }
        b.append("        default: return \"other\"\n        }\n    }\n\n")
        b.append("    private static func inR(_ x: Int, _ lo: Int, _ hi: Int) -> Bool { x >= lo && x <= hi }\n")
        groups.keys.forEachIndexed { i, cats ->
            b.append("\n    private static func r$i(_ o: WidgetPluralOperands) -> String {\n")
            for ((cat, rule) in cats) {
                b.append("        // $cat: $rule\n")
                b.append("        if ${PluralRuleCompiler.compile(rule, integer = true)} { return ${lit(cat)} }\n")
            }
            b.append("        return \"other\"\n    }\n")
        }
        b.append("}\n\n")
        b.append("extension WidgetL10n {\n")
        for ((name, key) in widget) {
            val i = keys.indexOf(key)
            val args = MessageArgs.of(m.en.getValue(key))
            b.append("    /// `$key`: ").append(m.en.getValue(key).replace("\n", "\\n")).append('\n')
            if (args.isEmpty()) {
                b.append("    var $name: String { text($i) }\n")
            } else {
                val params = HashSet<String>()
                val p = args.map { a -> L10nGenerator.swiftName(a.name, params) to a }
                b.append("    func $name(").append(p.joinToString(", ") { (pn, a) -> "$pn: ${if (a.kind == ArgKind.TEXT || a.kind == ArgKind.SELECT) "String" else "Int"}" })
                b.append(") -> String { format($i, [").append(p.joinToString(", ") { (pn, a) ->
                    "${lit(a.name)}: .${if (a.kind == ArgKind.TEXT || a.kind == ArgKind.SELECT) "text" else "number"}($pn)"
                }).append("]) }\n")
            }
        }
        b.append("}\n")
        return b.toString()
    }
}

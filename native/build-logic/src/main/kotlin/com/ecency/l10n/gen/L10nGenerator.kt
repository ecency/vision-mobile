package com.ecency.l10n.gen

/**
 * Generates the shared core's localization sources from RN's locale files (`src/config/locales/<id>.json`, nested
 * keys flattened with dots as RN does) and the vendored CLDR plural rules. Pure and deterministic: the same inputs
 * always give byte identical output (key order is en-US's, locales sorted by id). Outputs, package
 * `com.ecency.core.l10n`:
 * - `L.kt`: the typed Kotlin API, one accessor per en-US key (a property, or a function with one parameter per ICU
 *   argument: String for text and select, Int for plural, Double for number). Hidden from Objective-C, so the iOS
 *   framework carries only the accessors Kotlin code uses.
 * - `L.swift`: the same API for the iOS app as nested Swift enums (`L.Settings.language`, `L.Post.a11yVotes(count:)`),
 *   calling the exported `L10n.text` / `L10n.formatArgs` by key index.
 * - `L10nData.kt`: key names, the locale list with each locale's OS tag ([SystemLocales]) and the lazy table registry.
 * - `L10nLocale_<id>.kt`: one object per locale holding its messages as string constants (only the ones that
 *   differ from en-US; en-US holds all), decoded on first use.
 * - `L10nPluralRules.kt`: the CLDR plural rules of every locale, compiled to Kotlin.
 * - `L10nFormatData.kt` (when the CLDR formats file is given): number, date and relative time data per locale
 *   ([FormatDataGenerator]).
 */
object L10nGenerator {
    const val PACKAGE = "com.ecency.core.l10n"
    const val SOURCE_LOCALE = "en-US"

    /** Entries are separated by this character inside the table constants; no message may contain it. */
    const val SEPARATOR = '\u0000'

    /** Max chars per string constant (JVM constants hold at most 65535 bytes of modified UTF-8, up to 3 per char). */
    const val CHUNK_CHARS = 8000

    internal const val HEADER = "// Generated from src/config/locales by native/build-logic (L10nGenerator). Do not edit.\n"

    private val KEYWORDS = setOf(
        "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface", "is", "null",
        "object", "package", "return", "super", "this", "throw", "true", "try", "typealias", "typeof", "val", "var",
        "when", "while",
    )

    /** [locales] maps a locale id (file name without `.json`) to the file text; it must contain [SOURCE_LOCALE]. */
    fun generate(locales: Map<String, String>, pluralRulesJson: String, formatsJson: String? = null): Map<String, String> {
        val enText = requireNotNull(locales[SOURCE_LOCALE]) { "missing $SOURCE_LOCALE.json" }
        val en = flatten(MiniJson.parse(enText), "")
        require(en.isNotEmpty()) { "$SOURCE_LOCALE.json has no keys" }
        val keys = en.keys.toList()
        val index = keys.withIndex().associate { (i, k) -> k to i }
        val args = en.mapValues { (k, v) ->
            try {
                MessageArgs.of(v)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("$SOURCE_LOCALE.json '$k': ${e.message}", e)
            }
        }
        val ids = locales.keys.sorted()
        val rules = pluralRules(pluralRulesJson)
        ids.forEach { require(it in rules) { "no CLDR plural rules for locale '$it' in the plural rules file" } }

        val out = LinkedHashMap<String, String>()
        out["L.kt"] = api(en, args, index)
        out["L.swift"] = swiftApi(en, args, index)
        out["L10nData.kt"] = data(keys, ids)
        for (id in ids) {
            val values: List<String?> = if (id == SOURCE_LOCALE) {
                keys.map { en.getValue(it) }
            } else {
                val loc = runCatching { flatten(MiniJson.parse(locales.getValue(id)), "") }
                    .getOrElse { throw IllegalArgumentException("$id.json: ${it.message}", it) }
                keys.map { k -> loc[k]?.takeIf { it.isNotEmpty() && it != en[k] } }
            }
            out["L10nLocale_${ident(id)}.kt"] = table(id, values)
        }
        out["L10nPluralRules.kt"] = plurals(ids, rules)
        if (formatsJson != null) out["L10nFormatData.kt"] = FormatDataGenerator.generate(formatsJson, ids)
        return out
    }

    /** RN's flattening: nested objects joined with dots; only string leaves count (others are ignored). */
    internal fun flatten(node: Any?, prefix: String): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        require(node is Map<*, *>) { "expected an object at '${prefix.ifEmpty { "<root>" }}'" }
        for ((k, v) in node) {
            val key = if (prefix.isEmpty()) k as String else "$prefix.$k"
            when (v) {
                is Map<*, *> -> out.putAll(flatten(v, key))
                is String -> out[key] = v
            }
        }
        return out
    }

    // ---- names

    /** `a11y_votes` -> `a11yVotes`, `chain-error` -> `chainError`, `POINTS` -> `points`, `500_error` -> `_500Error`. */
    internal fun camel(raw: String): String {
        val words = raw.split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return "_"
        val first = words[0].let { w -> if (w.all { !it.isLetter() || it.isUpperCase() }) w.lowercase() else w.replaceFirstChar { it.lowercase() } }
        val name = first + words.drop(1).joinToString("") { w -> w.replaceFirstChar { it.uppercase() } }
        return safe(name)
    }

    private fun safe(name: String): String {
        val n = if (name[0].isDigit()) "_$name" else name
        return if (n in KEYWORDS) "${n}_" else n
    }

    /** The first sibling keeps the camel name; a later one that collides gets its raw key (non letters as `_`). */
    private fun uniqueName(raw: String, taken: MutableSet<String>): String {
        val candidates = sequenceOf(camel(raw), safe(raw.replace(Regex("[^A-Za-z0-9]"), "_").ifEmpty { "_" })) +
            generateSequence(2) { it + 1 }.map { "${camel(raw)}_$it" }
        return candidates.first { taken.add(it) }
    }

    private fun ident(localeId: String) = localeId.replace('-', '_')

    // ---- L.kt

    private class Node {
        val children = LinkedHashMap<String, Node>()
        var key: String? = null
    }

    private fun tree(en: Map<String, String>): Node {
        val root = Node()
        for (k in en.keys) {
            var n = root
            for (seg in k.split('.')) n = n.children.getOrPut(seg) { Node() }
            n.key = k
        }
        return root
    }

    /**
     * The Kotlin accessor of every key of [enText] (the en-US file) as code writes it: `post.a11y_votes` is
     * `L.post.a11yVotes`. The names [members] gives; checks such as the RN reuse test map code back to keys with it.
     */
    fun kotlinAccessors(enText: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        fun walk(node: Node, path: String) {
            val taken = HashSet<String>()
            for ((seg, child) in node.children) {
                val name = uniqueName(seg, taken)
                val key = child.key
                if (key != null) out[key] = "$path.$name" else walk(child, "$path.$name")
            }
        }
        walk(tree(flatten(MiniJson.parse(enText), "")), "L")
        return out
    }

    private fun api(en: Map<String, String>, args: Map<String, List<MessageArg>>, index: Map<String, Int>): String {
        val root = tree(en)
        val b = StringBuilder(HEADER)
        b.append("@file:OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)\n\n")
        b.append("package $PACKAGE\n\n")
        b.append("import kotlin.native.HiddenFromObjC\n\n")
        b.append("/**\n * The app's copy, one accessor per key of `src/config/locales/en-US.json`, in the current language\n")
        b.append(" * ([L10n.language]) with English as the fallback. Generated at build time; see README \"Localization\".\n */\n")
        b.append("@Suppress(\"ClassName\", \"FunctionName\", \"PropertyName\", \"ObjectPropertyName\", \"unused\")\n")
        b.append("@HiddenFromObjC\n")
        b.append("object L {\n")
        members(b, root, en, args, index, "    ", listOf("L"))
        b.append("}\n")
        return b.toString()
    }

    private fun members(
        b: StringBuilder, node: Node, en: Map<String, String>, args: Map<String, List<MessageArg>>,
        index: Map<String, Int>, indent: String, classPath: List<String>,
    ) {
        val taken = HashSet<String>()
        val classes = HashSet<String>()
        val groups = ArrayList<Pair<String, Pair<String, Node>>>()
        for ((seg, child) in node.children) {
            val name = uniqueName(seg, taken)
            val key = child.key
            if (key != null) {
                val i = index.getValue(key)
                b.append(indent).append("/** `").append(key).append("`: ").append(doc(en.getValue(key))).append(" */\n")
                val a = args.getValue(key)
                if (a.isEmpty()) {
                    b.append(indent).append("val $name: String get() = L10n.text($i)\n")
                } else {
                    val params = HashSet<String>()
                    val p = a.map { arg -> uniqueName(arg.name, params) to arg }
                    b.append(indent).append("fun $name(")
                    b.append(p.joinToString(", ") { (pn, arg) -> "$pn: ${type(arg.kind)}" })
                    b.append("): String = L10n.format($i, arrayOf(")
                    b.append(p.joinToString(", ") { (_, arg) -> literal(arg.name) })
                    b.append("), arrayOf<Any>(")
                    b.append(p.joinToString(", ") { it.first })
                    b.append("))\n")
                }
            } else {
                var cls = name.replaceFirstChar { it.uppercase() }
                while (cls in classes || cls in classPath || cls in taken) cls += "_"
                classes += cls
                groups += name to (cls to child)
                b.append(indent).append("val $name: $cls get() = $cls\n")
            }
        }
        for ((_, g) in groups) {
            val (cls, child) = g
            b.append("\n").append(indent).append("@HiddenFromObjC\n").append(indent).append("object $cls {\n")
            members(b, child, en, args, index, "$indent    ", classPath + cls)
            b.append(indent).append("}\n")
        }
    }

    // ---- L.swift

    private val SWIFT_KEYWORDS = setOf(
        "associatedtype", "class", "deinit", "enum", "extension", "fileprivate", "func", "import", "inout", "internal",
        "let", "open", "operator", "private", "precedencegroup", "protocol", "public", "rethrows", "static", "struct",
        "subscript", "typealias", "var", "break", "case", "catch", "continue", "default", "defer", "do", "else",
        "fallthrough", "for", "guard", "if", "in", "repeat", "return", "throw", "switch", "where", "while", "Any", "as",
        "await", "false", "is", "nil", "super", "throws", "true", "try",
    )

    /** Capitalised names a Swift type may not take (`enum Type` and `enum Self` do not compile). */
    private val SWIFT_TYPE_RESERVED = setOf("Type", "Protocol", "Any", "Self")

    /** Type names the generated code itself uses; a nested enum with one of these names would shadow it. */
    private val SWIFT_SHADOWED = setOf("String", "Int", "Int32", "Double", "L10n", "KotlinInt", "KotlinDouble")

    /** Names Swift reads specially after a dot even in backticks; they get a trailing `_`. */
    private val SWIFT_RESERVED = setOf("Type", "Protocol", "self", "Self", "init")

    /** Swift identifier for a raw key: camel case like Kotlin, but Swift's keywords are escaped with backticks. */
    internal fun swiftName(raw: String, taken: MutableSet<String>): String {
        val words = raw.split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }
        val base = if (words.isEmpty()) "_" else {
            val first = words[0].let { w -> if (w.all { !it.isLetter() || it.isUpperCase() }) w.lowercase() else w.replaceFirstChar { it.lowercase() } }
            (first + words.drop(1).joinToString("") { w -> w.replaceFirstChar { it.uppercase() } }).let { if (it[0].isDigit()) "_$it" else it }
        }
        val raw2 = raw.replace(Regex("[^A-Za-z0-9]"), "_").let { if (it.isEmpty() || it[0].isDigit()) "_$it" else it }
        val name = (sequenceOf(base, raw2) + generateSequence(2) { it + 1 }.map { "${base}_$it" })
            .map { if (it in SWIFT_RESERVED) "${it}_" else it }
            .first { taken.add(it) }
        return if (name in SWIFT_KEYWORDS) "`$name`" else name
    }

    private fun swiftApi(en: Map<String, String>, args: Map<String, List<MessageArg>>, index: Map<String, Int>): String {
        val b = StringBuilder(HEADER)
        b.append("import EcencyCore\n\n")
        b.append("/// The app's copy, one accessor per key of `src/config/locales/en-US.json`, in the current language\n")
        b.append("/// (`L10n.shared.language`) with English as the fallback. Generated at build time; see README \"Localization\".\n")
        b.append("enum L {\n")
        swiftMembers(b, tree(en), en, args, index, "    ", listOf("L"))
        b.append("}\n")
        return b.toString()
    }

    private fun swiftMembers(
        b: StringBuilder, node: Node, en: Map<String, String>, args: Map<String, List<MessageArg>>,
        index: Map<String, Int>, indent: String, typePath: List<String>,
    ) {
        val taken = HashSet<String>()
        val groups = ArrayList<Pair<String, Node>>()
        val leaves = node.children.filterValues { it.key != null }
        // nested types first take their names, so a later member never shadows a type
        for ((seg, child) in node.children) {
            if (child.key != null) continue
            var type = swiftName(seg, HashSet()).removeSurrounding("`").removeSuffix("_").ifEmpty { "Group" }.replaceFirstChar { it.uppercase() }
            while (type in SWIFT_TYPE_RESERVED || type in SWIFT_KEYWORDS || type in typePath || type in SWIFT_SHADOWED || !taken.add(type)) type += "_"
            groups += type to child
        }
        for ((seg, child) in leaves) {
            val key = child.key!!
            val name = swiftName(seg, taken)
            val i = index.getValue(key)
            b.append(indent).append("/// `").append(key).append("`: ").append(doc(en.getValue(key))).append('\n')
            val a = args.getValue(key)
            if (a.isEmpty()) {
                b.append(indent).append("static var $name: String { L10n.shared.text(index: $i) }\n")
            } else {
                val params = HashSet<String>()
                val p = a.map { arg -> swiftName(arg.name, params) to arg }
                b.append(indent).append("static func $name(")
                b.append(p.joinToString(", ") { (pn, arg) -> "$pn: ${swiftType(arg.kind)}" })
                b.append(") -> String { L10n.shared.formatArgs(index: $i, names: [")
                b.append(p.joinToString(", ") { (_, arg) -> swiftLiteral(arg.name) })
                b.append("], values: [")
                b.append(p.joinToString(", ") { (pn, arg) -> swiftValue(pn, arg.kind) })
                b.append("]) }\n")
            }
        }
        for ((type, child) in groups) {
            b.append("\n").append(indent).append("enum $type {\n")
            swiftMembers(b, child, en, args, index, "$indent    ", typePath + type)
            b.append(indent).append("}\n")
        }
    }

    /** A Swift string literal: `\\`, `"` and line breaks escaped, other control characters as `\\u{X}`. */
    internal fun swiftLiteral(s: String): String {
        val b = StringBuilder("\"")
        for (c in s) {
            when {
                c == '\\' -> b.append("\\\\")
                c == '"' -> b.append("\\\"")
                c == '\n' -> b.append("\\n")
                c == '\r' -> b.append("\\r")
                c == '\t' -> b.append("\\t")
                c < ' ' || c in '\u007f'..'\u009f' || c == '\u2028' || c == '\u2029' -> b.append("\\u{").append(c.code.toString(16)).append('}')
                else -> b.append(c)
            }
        }
        return b.append('"').toString()
    }

    private fun swiftType(kind: ArgKind) = when (kind) {
        ArgKind.TEXT, ArgKind.SELECT -> "String"
        ArgKind.PLURAL -> "Int"
        ArgKind.NUMBER -> "Double"
    }

    private fun swiftValue(name: String, kind: ArgKind) = when (kind) {
        ArgKind.TEXT, ArgKind.SELECT -> name
        ArgKind.PLURAL -> "KotlinInt(value: Int32(clamping: $name))"
        ArgKind.NUMBER -> "KotlinDouble(value: $name)"
    }

    private fun type(kind: ArgKind) = when (kind) {
        ArgKind.TEXT, ArgKind.SELECT -> "String"
        ArgKind.PLURAL -> "Int"
        ArgKind.NUMBER -> "Double"
    }

    private fun doc(text: String): String {
        val one = text.replace("\n", "\\n").replace("*/", "* /").replace("/*", "/ *")
        return if (one.length > 120) one.take(117) + "..." else one
    }

    // ---- data and tables

    private fun data(keys: List<String>, ids: List<String>): String {
        val b = StringBuilder(HEADER)
        b.append("package $PACKAGE\n\n")
        b.append("internal object L10nData {\n")
        b.append("    const val SIZE = ${keys.size}\n\n")
        b.append("    /** Every locale with a message file, sorted by id. */\n")
        b.append("    val LOCALES: List<String> = listOf(${ids.joinToString(", ") { literal(it) }})\n\n")
        b.append("    /** The OS per-app language tag of each of [LOCALES], in the same order (SystemLocales). */\n")
        b.append("    val SYSTEM_TAGS: List<String> = listOf(${ids.joinToString(", ") { literal(SystemLocales.tag(it)) }})\n\n")
        b.append("    /** The messages of [id] by key index (null: use English), decoded now, or null for an unknown id. */\n")
        b.append("    fun table(id: String): Array<String?>? = when (id) {\n")
        for (id in ids) b.append("        ${literal(id)} -> L10nLocale_${ident(id)}.table()\n")
        b.append("        else -> null\n    }\n\n")
        b.append("    /** The dotted key of every index. */\n")
        b.append("    fun keys(): Array<String?> = L10nTables.decode(arrayOf(${chunks(keys).joinToString(", ") { literal(it) }}), SIZE)\n")
        b.append("}\n")
        return b.toString()
    }

    private fun table(id: String, values: List<String?>): String {
        values.forEachIndexed { i, v -> require(v == null || SEPARATOR !in v) { "$id.json: message $i contains U+0000" } }
        val chunks = chunks(values.map { it.orEmpty() })
        val b = StringBuilder(HEADER)
        b.append("package $PACKAGE\n\n")
        b.append("/** ${values.count { it != null }} messages of $id.json${if (id == SOURCE_LOCALE) "" else " that differ from $SOURCE_LOCALE"}. */\n")
        b.append("@Suppress(\"ClassName\")\n")
        b.append("internal object L10nLocale_${ident(id)} {\n")
        b.append("    fun table(): Array<String?> = L10nTables.decode(arrayOf(${chunks.indices.joinToString(", ") { "C$it" }}), L10nData.SIZE)\n")
        chunks.forEachIndexed { i, c -> b.append("\n    private const val C$i = ").append(literal(c)).append("\n") }
        b.append("}\n")
        return b.toString()
    }

    /** Entries each ending in [SEPARATOR], packed into constants of at most [CHUNK_CHARS] (whole entries only). */
    internal fun chunks(entries: List<String>): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        for (e in entries) {
            if (cur.isNotEmpty() && cur.length + e.length + 1 > CHUNK_CHARS) { out += cur.toString(); cur.setLength(0) }
            cur.append(e).append(SEPARATOR)
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }

    internal fun literal(s: String): String {
        val b = StringBuilder("\"")
        for (c in s) {
            when {
                c == '\\' -> b.append("\\\\")
                c == '"' -> b.append("\\\"")
                c == '$' -> b.append("\\$")
                c == '\n' -> b.append("\\n")
                c == '\r' -> b.append("\\r")
                c == '\t' -> b.append("\\t")
                c < ' ' || c in '\u007f'..'\u009f' || c == '\u2028' || c == '\u2029' ->
                    b.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> b.append(c)
            }
        }
        return b.append('"').toString()
    }

    // ---- plural rules

    internal val CATEGORIES = listOf("zero", "one", "two", "few", "many")

    @Suppress("UNCHECKED_CAST")
    internal fun pluralRules(json: String): Map<String, Map<String, String>> {
        val root = MiniJson.parse(json) as Map<String, Any?>
        val rules = root["rules"] as? Map<String, Any?> ?: throw IllegalArgumentException("plural rules: no 'rules' object")
        return rules.mapValues { (id, v) ->
            (v as Map<String, Any?>).mapValues { (cat, r) ->
                require(cat in CATEGORIES) { "plural rules $id: unknown category '$cat'" }
                r as String
            }
        }
    }

    private fun plurals(ids: List<String>, rules: Map<String, Map<String, String>>): String {
        // locales with the same rules share one function; locales with only "other" fall to the else branch
        val groups = ids.filter { rules.getValue(it).isNotEmpty() }.groupBy { id -> CATEGORIES.mapNotNull { c -> rules.getValue(id)[c]?.let { c to it } } }
        val b = StringBuilder(HEADER)
        b.append("package $PACKAGE\n\n")
        b.append("/** CLDR plural rules per locale (vendored in shared/l10n/cldr-plural-rules.json, see its source field). */\n")
        b.append("internal object L10nPluralRules {\n")
        b.append("    fun select(localeId: String, o: PluralOperands): String = when (localeId) {\n")
        groups.values.forEachIndexed { i, members ->
            b.append("        ${members.joinToString(", ") { literal(it) }} -> r$i(o)\n")
        }
        b.append("        else -> \"other\"\n    }\n")
        groups.keys.forEachIndexed { i, cats ->
            b.append("\n    private fun r$i(o: PluralOperands): String = when {\n")
            for ((cat, rule) in cats) {
                val expr = try { PluralRuleCompiler.compile(rule) } catch (e: IllegalArgumentException) {
                    throw IllegalArgumentException("plural rules ${groups.getValue(cats).first()} $cat: ${e.message}", e)
                }
                b.append("        // $cat: $rule\n")
                b.append("        $expr -> ${literal(cat)}\n")
            }
            b.append("        else -> \"other\"\n    }\n")
        }
        b.append("}\n")
        return b.toString()
    }
}

package com.ecency.core.l10n

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.concurrent.atomics.AtomicArray
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.atomicArrayOfNulls

/**
 * The app's message catalog: the current language and its messages, with English (`en-US`) under every key.
 *
 * Messages come from RN's `src/config/locales/<id>.json`, compiled into generated constants at build time (README
 * "Localization"); only the current language's table is decoded, on [setLanguage], and each message is parsed once on
 * first use. The typed API is generated: Kotlin `L` (`L.settings.language`, `L.post.a11yVotes(count = 3)`) and Swift
 * `L` in the iOS app (`L.Settings.language`), both calling [text] and [format] by key index.
 *
 * Never throws for a bad translation: a missing key, a message that does not parse, one that uses a placeholder English
 * does not have, or one that fails while formatting (an argument of the wrong type) shows the English message instead
 * (translator mistakes from Crowdin cannot crash or garble the app). A translation may leave out placeholders, as
 * react-intl allows (ja-JP drops English's `{suffix}`).
 */
@OptIn(ExperimentalAtomicApi::class)
object L10n {
    const val SOURCE = "en-US"

    /** The app languages written right to left. ku-TR is Latin script Kurmanji, so it stays left to right (owner decision). */
    private val RTL_LANGUAGES = setOf("ar", "fa", "he")

    private class Resolved(val message: IcuMessage, val pluralLocale: String)

    private class Active(val id: String, val table: Array<String?>?, val rtl: Boolean, val pluralLocale: String) {
        // atomic slots: a reader on another thread sees either null or a fully built value (Kotlin/Native too)
        val cache: AtomicArray<Resolved?> = atomicArrayOfNulls(L10nData.SIZE)
    }

    private val englishTable: Array<String?> by lazy { requireNotNull(L10nData.table(SOURCE)) }
    private val englishCache: AtomicArray<Resolved?> = atomicArrayOfNulls(L10nData.SIZE)
    private val keyIndex: Map<String, Int> by lazy {
        HashMap<String, Int>(L10nData.SIZE * 2).also { m -> L10nData.keys().forEachIndexed { i, k -> if (k != null) m[k] = i } }
    }

    private val active = MutableStateFlow(Active(SOURCE, null, rtl = false, pluralLocale = SOURCE))
    private val currentLanguage = MutableStateFlow(SOURCE)
    private val pseudo = MutableStateFlow<String?>(null)

    /** The current language id (one of [supported]); the UI observes it to redraw on a change. */
    val language: StateFlow<String> = currentLanguage.asStateFlow()

    /** Every language with a message file, sorted by id (the 39 of RN's language list). */
    val supported: List<String> get() = L10nData.LOCALES

    /** The current language is written right to left (the UI mirrors its layout; placeholders get bidi isolates). */
    val isRtl: Boolean get() = active.value.rtl

    fun isRtl(languageId: String): Boolean = languageId.substringBefore('-').lowercase() in RTL_LANGUAGES

    /**
     * The tag the OS per-app language setting uses for [languageId] (one of [supported]): the bare language ("de"),
     * or with script or region where the language alone names another variant ("zh-Hans", "sr-Latn", "pt-PT").
     * Generated with Android's locales_config.xml from the same rule (build-logic SystemLocales); [LocaleMatch] maps
     * each back. An unknown id gives English's.
     */
    fun systemTag(languageId: String): String =
        L10nData.SYSTEM_TAGS[L10nData.LOCALES.indexOf(languageId).takeIf { it >= 0 } ?: L10nData.LOCALES.indexOf(SOURCE)]

    /**
     * Switches the language and decodes its table now (see README for cost). [id] is matched like RN's
     * mapToRegisteredLocale ([LocaleMatch]): exact id in any case or with `_`, Chinese by script or region, then the
     * base language ("pt-BR" is pt-PT, "en" is en-US); anything else is English.
     */
    fun setLanguage(id: String) {
        pseudo.value?.let { p ->
            if (active.value.id != p) PseudoLocales.install(p)
            return
        }
        val target = LocaleMatch.registered(id) ?: SOURCE
        if (active.value.id == target) return
        install(target, if (target == SOURCE) null else L10nData.table(target), isRtl(target), target)
    }

    /**
     * Debug builds only (the apps gate the call on their debug flag; release builds never reach it): shows the pseudo
     * locale [id] ([PseudoLocales.ACCENTED] `en-XA` or [PseudoLocales.RTL] `ar-XB`) whatever language the settings
     * set, for the long text and mirrored screenshot runs. The saved language and the OS per-app language are left
     * alone. Null, or any other id, ends it and shows English until the next [setLanguage].
     */
    fun usePseudo(id: String?) {
        val p = id?.takeIf { it == PseudoLocales.ACCENTED || it == PseudoLocales.RTL }
        pseudo.value = p
        if (p != null) PseudoLocales.install(p) else install(SOURCE, null, rtl = false, pluralLocale = SOURCE)
    }

    /** Installs a message table directly (tests: pseudo locales). [pluralLocale] names the CLDR rules to use. */
    internal fun install(id: String, table: Array<String?>?, rtl: Boolean, pluralLocale: String) {
        active.value = Active(id, table, rtl, pluralLocale)
        currentLanguage.value = id
    }

    /** The message at generated index [index], without arguments. */
    fun text(index: Int): String = format(index, NO_NAMES, NO_VALUES)

    /** [format] with lists (the generated Swift API: plural values as KotlinInt, number values as KotlinDouble). */
    fun formatArgs(index: Int, names: List<String>, values: List<Any>): String = format(index, names.toTypedArray(), values.toTypedArray())

    /** The message at [index] with arguments [names] = [values] (generated accessors pass them in order). */
    fun format(index: Int, names: Array<String>, values: Array<Any>): String {
        val a = active.value
        val r = resolve(a, index)
        r.message.plain?.let { return marked(it, a, r, index) }
        val args = HashMap<String, Any>(names.size * 2)
        for (i in names.indices) args[names[i]] = values[i]
        return try {
            marked(formatResolved(r, args, a), a, r, index)
        } catch (e: Exception) {
            val en = english(index)
            if (en === r) englishTable[index].orEmpty() else runCatching { formatResolved(en, args, a) }.getOrElse { englishTable[index].orEmpty() }
        }
    }

    /**
     * A right to left language's own message starts with RLM (U+200F), once: text that takes its direction from its
     * first strong letter (Compose `TextDirection.Content`, SwiftUI, UIKit) would otherwise lay out "HIVE ..." or
     * "PIN ..." in Arabic left to right. English shown as a fallback keeps its own direction. Catalog text never
     * leaves the app (outbound text is fixed English, README "Localization"), so the mark stays on screen.
     */
    private fun marked(text: String, a: Active, r: Resolved, index: Int): String =
        if (!a.rtl || text.isEmpty() || text[0] == RLM || r === english(index)) text else RLM + text

    /** RIGHT-TO-LEFT MARK: a strong right to left character that shows nothing. */
    const val RLM = '\u200F'

    /**
     * The message for a dotted RN key built at run time (`"wallet.$type"`), or null when en-US has no such key.
     * Prefer the generated accessors; this exists for RN's dynamic ids.
     */
    fun byKey(key: String, args: Map<String, Any> = emptyMap()): String? {
        val i = keyIndex[key] ?: return null
        val names = args.keys.toTypedArray()
        return format(i, names, Array(names.size) { args.getValue(names[it]) })
    }

    internal fun indexOf(key: String): Int? = keyIndex[key]

    /** The raw en-US message of [index] (tests and reports). */
    internal fun englishSource(index: Int): String = englishTable[index].orEmpty()

    /** True when the current language shows its own message for [index] (not the English fallback). */
    internal fun isTranslated(index: Int): Boolean = resolve(active.value, index).let { it !== english(index) }

    /**
     * Plural categories follow the message's own language (English rules for an English fallback), numbers the app
     * language ([LocaleFormatter]: "1.234 votes" in de-DE while the message is still English).
     */
    private fun formatResolved(r: Resolved, args: Map<String, Any>, a: Active): String =
        r.message.format(
            args,
            plural = { ops -> L10nPluralRules.select(r.pluralLocale, ops) },
            isolate = if (a.rtl) Bidi::isolate else IDENTITY,
            number = LocaleFormatter.of(a.id)::messageNumber,
        )

    private fun resolve(a: Active, index: Int): Resolved {
        a.cache.loadAt(index)?.let { return it }
        val en = english(index)
        val translated = a.table?.get(index)
        val r = if (translated == null) en else try {
            val m = IcuMessage.parse(translated)
            // a translation may drop placeholders (react-intl formats it); a name English lacks would show raw
            if (en.message.argNames.containsAll(m.argNames)) Resolved(m, a.pluralLocale) else en
        } catch (e: Exception) {
            en
        }
        return publish(a.cache, index, r)
    }

    private fun english(index: Int): Resolved {
        englishCache.loadAt(index)?.let { return it }
        val source = englishTable[index].orEmpty()
        // en-US is checked by the generator at build time; a plain text message is the last resort anyway
        val m = try { IcuMessage.parse(source) } catch (e: Exception) { IcuMessage.literal(source) }
        return publish(englishCache, index, Resolved(m, SOURCE))
    }

    /** Stores [value] unless another thread was first; every caller then gets the same instance. */
    private fun publish(slots: AtomicArray<Resolved?>, index: Int, value: Resolved): Resolved =
        if (slots.compareAndSetAt(index, null, value)) value else slots.loadAt(index) ?: value

    private val NO_NAMES = emptyArray<String>()
    private val NO_VALUES = emptyArray<Any>()
    private val IDENTITY: (String) -> String = { it }
}

/** Decoding of the generated message constants (entries end in U+0000; an empty entry means "use English"). */
internal object L10nTables {
    fun decode(chunks: Array<String>, size: Int): Array<String?> {
        val out = arrayOfNulls<String>(size)
        var i = 0
        for (c in chunks) {
            var start = 0
            while (start < c.length) {
                val end = c.indexOf('\u0000', start)
                check(end >= 0) { "l10n table entry without terminator" }
                if (end > start && i < size) out[i] = c.substring(start, end)
                i++
                start = end + 1
            }
        }
        check(i == size) { "l10n table has $i entries, expected $size" }
        return out
    }
}

package com.ecency.core.app

import com.ecency.core.api.model.Post
import com.ecency.core.api.model.PostKey
import com.ecency.core.l10n.L
import com.ecency.core.render.RenderHelper
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.ceil
import kotlin.math.max

/** RN src/utils/iso639.ts: the LibreTranslate languages, their English names and the reader's language. */
object Languages {
    val SUPPORTED = listOf(
        "ar", "az", "bg", "bn", "ca", "cs", "da", "de", "el", "en", "eo", "es", "et", "fa", "fi", "fr", "ga", "he", "hi",
        "hu", "id", "it", "ja", "ko", "lt", "lv", "ms", "nb", "nl", "pl", "pt", "ro", "ru", "sk", "sl", "sq", "sv", "th",
        "tl", "tr", "uk", "zh", "zt",
    )

    private val NAMES: Map<String, () -> String> = mapOf(
        "ar" to { L.native.translateLanguage.ar }, "az" to { L.native.translateLanguage.az }, "bg" to { L.native.translateLanguage.bg }, "bn" to { L.native.translateLanguage.bn }, "ca" to { L.native.translateLanguage.ca }, "cs" to { L.native.translateLanguage.cs },
        "da" to { L.native.translateLanguage.da }, "de" to { L.native.translateLanguage.de }, "el" to { L.native.translateLanguage.el }, "en" to { L.native.translateLanguage.en }, "eo" to { L.native.translateLanguage.eo }, "es" to { L.native.translateLanguage.es },
        "et" to { L.native.translateLanguage.et }, "fa" to { L.native.translateLanguage.fa }, "fi" to { L.native.translateLanguage.fi }, "fr" to { L.native.translateLanguage.fr }, "ga" to { L.native.translateLanguage.ga }, "he" to { L.native.translateLanguage.he },
        "hi" to { L.native.translateLanguage.hi }, "hu" to { L.native.translateLanguage.hu }, "id" to { L.native.translateLanguage.id }, "it" to { L.native.translateLanguage.it }, "ja" to { L.native.translateLanguage.ja }, "ko" to { L.native.translateLanguage.ko },
        "lt" to { L.native.translateLanguage.lt }, "lv" to { L.native.translateLanguage.lv }, "ms" to { L.native.translateLanguage.ms }, "nb" to { L.native.translateLanguage.nb }, "nl" to { L.native.translateLanguage.nl }, "pl" to { L.native.translateLanguage.pl },
        "pt" to { L.native.translateLanguage.pt }, "ro" to { L.native.translateLanguage.ro }, "ru" to { L.native.translateLanguage.ru }, "sk" to { L.native.translateLanguage.sk }, "sl" to { L.native.translateLanguage.sl }, "sq" to { L.native.translateLanguage.sq },
        "sv" to { L.native.translateLanguage.sv }, "th" to { L.native.translateLanguage.th }, "tl" to { L.native.translateLanguage.tl }, "tr" to { L.native.translateLanguage.tr }, "uk" to { L.native.translateLanguage.uk }, "zh" to { L.native.translateLanguage.zh },
        "zt" to { L.native.translateLanguage.zt },
    )

    private val RTL = setOf("ar", "he", "fa", "ur", "ps", "dv", "yi")

    fun name(code: String): String = NAMES[code]?.invoke() ?: code

    fun isRtl(code: String?): Boolean = code != null && normalize(code) in RTL

    /** RN normLang. */
    fun normalize(code: String): String {
        val c = code.lowercase().replace('_', '-')
        return when {
            c == "zh-hant" || c.startsWith("zh-hant-") || c == "zh-tw" || c == "zh-hk" || c == "zh-mo" -> "zt"
            c == "no" || c.startsWith("no-") || c == "nn" || c.startsWith("nn-") -> "nb"
            else -> c.substringBefore('-')
        }
    }

    /** RN resolveReaderLang: the app language, then the device locale, the first one supported, else English. */
    fun readerLanguage(vararg candidates: String?): String =
        candidates.filterNotNull().map(::normalize).firstOrNull { it in SUPPORTED } ?: "en"

    private val STOPWORDS: Map<String, Set<String>> = mapOf(
        "en" to setOf("the", "and", "is", "to", "of", "in", "that", "it", "for", "with", "this", "was", "are", "you", "have", "not"),
        "es" to setOf("el", "la", "de", "que", "y", "en", "los", "las", "por", "con", "para", "una", "es", "del", "se", "pero"),
        "de" to setOf("der", "die", "und", "das", "ist", "nicht", "ich", "mit", "ein", "eine", "auf", "für", "den", "zu", "von", "auch"),
        "fr" to setOf("le", "la", "les", "des", "et", "est", "une", "dans", "pour", "que", "qui", "pas", "sur", "avec", "ce", "je"),
        "pt" to setOf("o", "de", "que", "e", "do", "da", "em", "um", "para", "com", "não", "uma", "os", "se", "na", "mais"),
        "it" to setOf("il", "di", "che", "e", "la", "per", "un", "non", "con", "una", "sono", "del", "le", "della", "anche", "più"),
        "nl" to setOf("de", "het", "een", "en", "van", "is", "dat", "niet", "op", "te", "met", "voor", "zijn", "ik", "ook", "maar"),
        "pl" to setOf("i", "w", "nie", "na", "się", "że", "do", "jest", "to", "z", "jak", "ale", "co", "tak", "po", "od"),
        "tr" to setOf("bir", "ve", "bu", "için", "ile", "de", "da", "çok", "ama", "gibi", "daha", "en", "ne", "var", "olarak", "ben"),
        "id" to setOf("yang", "dan", "di", "ini", "itu", "dengan", "untuk", "dari", "tidak", "saya", "ke", "pada", "akan", "ada", "juga", "atau"),
    )

    /**
     * A cheap on-device guess of [sample]'s language, standing in for RN's franc so that posts in the reader's
     * own language never leave the device for detection: the dominant script decides for non-Latin text, a few
     * function words for Latin text. Null when it cannot tell (the server is asked then, as RN does for "und").
     */
    fun guess(sample: String): String? {
        val counts = HashMap<String, Int>()
        var letters = 0
        for (c in sample) {
            val script = when (c) {
                in '\uAC00'..'\uD7AF', in '\u1100'..'\u11FF' -> "ko"
                in '\u3040'..'\u30FF' -> "ja"
                in '\u4E00'..'\u9FFF', in '\u3400'..'\u4DBF' -> "zh"
                in '\u0400'..'\u04FF' -> "cyrillic"
                in '\u0600'..'\u06FF' -> "arabic"
                in '\u0590'..'\u05FF' -> "he"
                in '\u0370'..'\u03FF' -> "el"
                in '\u0E00'..'\u0E7F' -> "th"
                in '\u0900'..'\u097F' -> "hi"
                in '\u0980'..'\u09FF' -> "bn"
                else -> if (c.isLetter()) "latin" else null
            } ?: continue
            letters++
            counts[script] = (counts[script] ?: 0) + 1
        }
        if (letters == 0) return null
        // kana anywhere means Japanese even when Han characters outnumber it
        if ((counts["ja"] ?: 0) * 10 >= letters) return "ja"
        val (script, n) = counts.maxByOrNull { it.value }!!
        if (n * 2 < letters) return null
        return when (script) {
            "latin" -> {
                val words = sample.lowercase().split(Regex("[^\\p{L}]+")).filter { it.isNotEmpty() }
                val scores = STOPWORDS.mapValues { (_, set) -> words.count { it in set } }
                val best = scores.maxByOrNull { it.value } ?: return null
                // too few hits, or a tie between languages: not sure
                if (best.value < 3 || scores.count { it.value == best.value } > 1) null else best.key
            }
            // several languages share these scripts: only the server can tell them apart
            "cyrillic", "arabic" -> null
            else -> script
        }
    }

    /** RN chunkText: pieces of at most [limit] chars split on whitespace, over-long tokens hard split. */
    fun chunk(text: String, limit: Int = 1800): List<String> {
        if (text.length <= limit) return listOf(text)
        val out = mutableListOf<String>()
        var current = StringBuilder()
        for (token in text.split(Regex("\\s+")).filter { it.isNotEmpty() }) {
            var t = token
            while (t.length > limit) {
                if (current.isNotEmpty()) { out += current.toString(); current = StringBuilder() }
                out += t.take(limit)
                t = t.drop(limit)
            }
            if (current.isNotEmpty() && current.length + 1 + t.length > limit) { out += current.toString(); current = StringBuilder() }
            if (current.isNotEmpty()) current.append(' ')
            current.append(t)
        }
        if (current.isNotEmpty()) out += current.toString()
        return out
    }
}

data class TranslateState(
    /** RN resolveTranslateCta: show the banner. */
    val show: Boolean = false,
    val source: String? = null,
    val target: String = "en",
    val translating: Boolean = false,
    /** The translated body (plain text, RN shows it in place of the rendered body). */
    val translated: String? = null,
    val translatedFrom: String? = null,
    val rtl: Boolean = false,
) {
    /** RN post_translate.banner_detected. */
    val bannerText: String get() = L.native.reading.bannerText(source = Languages.name(source ?: ""), target = Languages.name(target))
    val translateLabel: String get() = L.postTranslate.translateTo(lang = Languages.name(target))
    val translatedFromText: String get() = L.native.reading.translatedFromText(source = Languages.name(translatedFrom ?: source ?: ""))
}

/** RN useContentLanguageGate's session cache: the detected language per post (null: could not tell), FIFO at 500. */
class DetectedLanguages(private val max: Int = 500) {
    private val map = LinkedHashMap<PostKey, String?>()

    fun has(key: PostKey): Boolean = map.containsKey(key)

    fun get(key: PostKey): String? = map[key]

    fun put(key: PostKey, lang: String?) {
        if (!map.containsKey(key) && map.size >= max) map.remove(map.keys.first())
        map[key] = lang
    }

    fun clear() = map.clear()
}

/**
 * RN postTranslateInline with useContentLanguageGate({serverConfirm: true}): a banner when the body's language
 * differs from the reader's, the translation in place of the body. Privacy as RN: an on-device guess first
 * ([Languages.guess], a script and function word heuristic in place of franc); translate.ecency.com/detect is
 * asked only when the guess is not the reader's language, never for muted or NSFW posts, and once per post and
 * session ([DetectedLanguages]). Translation itself only starts when the reader asks for it.
 */
class TranslateModel internal constructor(private val env: AppEnvironment, val key: PostKey, readerLanguage: String) :
    StateModel<TranslateState>(TranslateState(target = Languages.readerLanguage(readerLanguage)), env.dispatchers) {
    private var job: Job? = null
    private var body: String = ""

    private val dismissKey get() = "$DISMISSED_PREFIX$key"

    /** Decides the banner for [post] (once per body). */
    fun start(post: Post) {
        if (post.body == body) return
        body = post.body
        setState { it.copy(show = false, translated = null) }
        if (post.body.trim().length < MIN_CHARS || env.prefs.getString(dismissKey) == "1") return
        // nothing of muted or NSFW content is sent anywhere unasked
        if (post.isMuted || NsfwMode.isNsfw(post)) return
        job?.cancel()
        job = scope.launch {
            val target = current.target
            val detected: String?
            if (env.detectedLanguages.has(key)) {
                detected = env.detectedLanguages.get(key)
            } else {
                val sample = withContext(env.dispatchers.render) { summary(post.body.take(2000)).take(600) }
                if (sample.trim().length < MIN_CHARS) {
                    env.detectedLanguages.put(key, null)
                    return@launch
                }
                val guess = withContext(env.dispatchers.render) { Languages.guess(sample) }
                // the server /detect is asked only for a signed-in reader (RN #3460, as the web); a logged-out
                // reader keeps the local guess, which is not remembered so a later sign-in can still ask
                val signedIn = env.session.current.username != null
                detected = if (guess == target || !signedIn) guess
                else env.rest.detect(sample).firstOrNull()?.let(Languages::normalize) ?: guess
                if (guess == target || signedIn) env.detectedLanguages.put(key, detected)
            }
            if (detected == null) return@launch
            val show = detected != "und" && detected in Languages.SUPPORTED && target in Languages.SUPPORTED && detected != target
            setState { it.copy(show = show, source = detected) }
        }
    }

    /** Banner "Translate to ...": the plain text summary of the whole body, translated in chunks. */
    fun translate(post: Post, target: String = current.target) {
        if (current.translating) return
        setState { it.copy(translating = true, target = target) }
        job = scope.launch {
            try {
                val text = withContext(env.dispatchers.render) { summary(post.body) }
                var detected: String? = null
                val parts = mutableListOf<String>()
                for (chunk in Languages.chunk(text)) {
                    val t = env.rest.translate(chunk, "auto", target)
                    if (detected == null) detected = t.detectedLanguage?.let(Languages::normalize)
                    parts += t.text
                }
                if (detected == target) {
                    // the guess was wrong: the post already is in the reader's language (RN hides the banner for good)
                    env.prefs.putString(dismissKey, "1")
                    setState { it.copy(translating = false, show = false) }
                    return@launch
                }
                setState {
                    it.copy(
                        translating = false, translated = parts.joinToString(" "), translatedFrom = detected ?: it.source,
                        rtl = Languages.isRtl(target),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // RN goes back to idle without a toast
                setState { it.copy(translating = false) }
            }
        }
    }

    /** "Show original". */
    fun showOriginal() = setState { it.copy(translated = null) }

    /** The banner's X: hidden for this post from now on. */
    fun dismiss() {
        env.prefs.putString(dismissKey, "1")
        setState { it.copy(show = false, translated = null) }
    }

    private fun summary(text: String): String {
        val platform = (env.renderer as? RenderHelperPostBodyRenderer)?.platform ?: RenderHelper.Platform.ANDROID
        return RenderHelper(env.proxyBase).postBodySummary(text, 0, platform)
    }

    companion object {
        const val MIN_CHARS = 40

        /** The prefs key of a dismissed banner: this prefix, then the post. */
        const val DISMISSED_PREFIX = "translate-dismissed:"
    }
}

data class TranslationSheetState(
    val languages: List<Pair<String, String>> = Languages.SUPPORTED.map { it to Languages.name(it) },
    val source: String = "auto",
    val target: String = "en",
    val original: String = "",
    val translated: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
)

/** RN post-translation-modal: source (Auto) and target pickers over a ~200 character summary of the post. */
class TranslationSheetModel internal constructor(private val env: AppEnvironment, private val post: Post, target: String) :
    StateModel<TranslationSheetState>(TranslationSheetState(target = Languages.readerLanguage(target)), env.dispatchers) {
    private var job: Job? = null

    fun start() {
        scope.launch {
            val platform = (env.renderer as? RenderHelperPostBodyRenderer)?.platform ?: RenderHelper.Platform.ANDROID
            // RN passes `null` as the length, which render-helper reads as 200
            val text = withContext(env.dispatchers.render) { RenderHelper(env.proxyBase).postBodySummary(post.body, 200, platform) }
            setState { it.copy(original = text) }
            run()
        }
    }

    fun setSource(code: String) {
        setState { it.copy(source = code) }
        run()
    }

    fun setTarget(code: String) {
        setState { it.copy(target = code) }
        run()
    }

    private fun run() {
        val s = current
        if (s.original.isBlank()) return
        job?.cancel()
        setState { it.copy(isLoading = true, error = null) }
        job = scope.launch {
            try {
                val t = env.rest.translate(s.original, s.source, s.target)
                setState { it.copy(isLoading = false, translated = t.text) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setState { it.copy(isLoading = false, error = e.message?.takeIf { m -> m.isNotBlank() } ?: L.alert.error) }
            }
        }
    }
}

// ---------------------------------------------------------------- reading metadata

/**
 * RN src/utils/textToSpeech.ts and postReadingMetadata.tsx: what is left of them since Listen replaced on-device text to
 * speech (760b9070be): the plain text of a post, its word count and reading time.
 */
object Speech {
    /** RN extractPlainTextForTTS: the regex chain in RN's order (it reads the raw body, not the title). */
    fun plainText(body: String): String {
        var t = body
        t = t.replace(Regex("<!--[\\s\\S]*?-->"), "")
        t = t.replace(Regex("!\\[.*?\\]\\(.*?\\)"), "")
        t = t.replace(Regex("\\[([^\\]]+)\\]\\([^)]+\\)"), "$1")
        t = t.replace(Regex("<[^>]*>"), " ")
        t = t.replace(Regex("^#{1,6}\\s+", RegexOption.MULTILINE), "")
        t = t.replace(Regex("```[\\s\\S]*?```"), "")
        t = t.replace(Regex("`([^`]+)`"), "$1")
        t = t.replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
        t = t.replace(Regex("\\*([^*]+)\\*"), "$1")
        t = t.replace(Regex("~~([^~]+)~~"), "$1")
        t = t.replace(Regex("^[-*_]{3,}\\s*$", RegexOption.MULTILINE), "")
        t = t.replace(Regex("^>\\s+", RegexOption.MULTILINE), "")
        t = t.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'")
        t = t.replace(Regex("https?://[^\\s]+"), "")
        t = t.replace(Regex("\\s+"), " ").trim()
        t = t.replace(Regex("\\.\\s+"), ". ... ").replace(Regex("\\?\\s+"), "? ... ").replace(Regex("!\\s+"), "! ... ")
        return t
    }

    /** RN hasReadableContent. */
    fun readable(text: String): Boolean = text.length >= 10

    private fun isCjk(c: Char) = c in '一'..'鿿' || c in '㐀'..'䶿' || c in '豈'..'﫿' ||
        c in '぀'..'ゟ' || c in '゠'..'ヿ' || c in '가'..'힯'

    private val PUNCT_ONLY = Regex("^\\p{P}+$")

    /** RN word count: every CJK character is a word, other text splits on whitespace (punctuation-only tokens excluded). */
    fun wordCount(text: String): Pair<Int, Int> {
        val cjk = text.count(::isCjk)
        val rest = text.map { if (isCjk(it)) ' ' else it }.joinToString("")
        val words = rest.split(Regex("\\s+")).count { it.isNotEmpty() && !PUNCT_ONLY.matches(it) }
        return cjk to words
    }

    /** RN reading minutes: 0 without words, else max(1, ceil(cjk / 500 + words / 200)). */
    fun readingMinutes(cjk: Int, words: Int): Int =
        if (cjk + words == 0) 0 else max(1, ceil(cjk / 500.0 + words / 200.0).toInt())
}

/** The post screen's reading bar: "Words", "Reading ... min" (hidden without readable text or under 10 words, RN). */
data class ReadingInfo(val words: Int, val minutes: Int, val readable: Boolean = true) {
    val visible: Boolean get() = readable && words >= 10

    companion object {
        /** RN postReadingMetadata: counts over the speech text. Off the main thread: regexes over the whole body. */
        fun of(post: Post): ReadingInfo {
            val text = Speech.plainText(post.body)
            if (text.isBlank()) return ReadingInfo(0, 0, false)
            val (cjk, words) = Speech.wordCount(text)
            return ReadingInfo(cjk + words, Speech.readingMinutes(cjk, words), Speech.readable(text))
        }
    }
}

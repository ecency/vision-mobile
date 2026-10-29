package com.ecency.core.app

import com.ecency.core.editor.TranslateMarkdown
import com.ecency.core.l10n.L
import com.ecency.core.render.RenderHelper
import com.ecency.core.render.js.Js
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

data class ComposeTranslateState(
    val loading: Boolean = true,
    /** RN: under 30 characters of plain text there is nothing worth translating. */
    val tooShort: Boolean = false,
    val source: String = "en",
    val target: String = "es",
    /** The language guessed on the device, when it is one the service knows ("Detected: ..."). */
    val detected: String? = null,
    val hasTitle: Boolean = false,
    val addTitleMarker: Boolean = true,
    val translating: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val failed: Boolean = false,
    val translated: String = "",
) {
    val sources: List<String> get() = Languages.SUPPORTED

    /** RN targetCodes: every language but the source. */
    val targets: List<String> get() = Languages.SUPPORTED.filter { it != source }

    val detectedText: String? get() = detected?.let { L.composeTranslate.detected(lang = Languages.name(it)) }

    val progressText: String? get() = if (translating && total > 0) L.composeTranslate.progress(done = done.toString(), total = total.toString()) else null

    val canTranslate: Boolean get() = !loading && !tooShort && !translating

    val canApply: Boolean get() = translated.isNotEmpty() && !translating

    val rtl: Boolean get() = Languages.isRtl(target)
}

/**
 * RN `composeTranslateModal`: a machine translation of the author's own draft, added to the end of the post.
 * The source language is guessed on the device ([Languages.guess]), the target is English, or for an English
 * draft the app language (Spanish when that is English or unknown to the service). The body goes to
 * translate.ecency.com block by block ([TranslateMarkdown]), all or nothing. Free: no points are charged.
 *
 * A run belongs to one language pair: changing the pair, closing the sheet or starting again retires it and
 * its late answer is dropped.
 */
class ComposeTranslateModel internal constructor(
    private val env: AppEnvironment,
    private val editor: EditorModel,
    private val appLanguage: String,
) : StateModel<ComposeTranslateState>(ComposeTranslateState(), env.dispatchers) {
    /** The body and title as they were when the sheet opened (RN's payload). */
    private val body = editor.current.body
    private val title = editor.current.title
    private var run: Job? = null
    private var runId = 0
    private var started = false

    fun start() {
        if (started) return
        started = true
        setState { it.copy(hasTitle = Js.trim(title).isNotEmpty()) }
        scope.launch {
            val sample = if (body.isEmpty()) "" else withContext(dispatchers.render) {
                RenderHelper(env.proxyBase).postBodySummary(body.take(RAW_SAMPLE_CHARS), 0).take(SAMPLE_CHARS)
            }
            if (Js.trim(sample).length < MIN_TRANSLATE_CHARS) {
                setState { it.copy(loading = false, tooShort = true) }
                return@launch
            }
            val guess = withContext(dispatchers.render) { Languages.guess(sample) }?.takeIf { it in Languages.SUPPORTED }
            val source = guess ?: "en"
            setState { it.copy(loading = false, detected = guess, source = source, target = defaultTarget(source)) }
        }
    }

    /** RN: a draft that is not English goes to English; an English one to the app language, else Spanish. */
    private fun defaultTarget(source: String): String {
        if (source != "en") return "en"
        val reader = Languages.normalize(appLanguage)
        return if (reader.isNotEmpty() && reader != "en" && reader in Languages.SUPPORTED) reader else "es"
    }

    private fun retire() {
        runId++
        run?.cancel()
        run = null
    }

    /** RN: choosing a source also resets the target to its default for that source. */
    fun setSource(code: String) {
        if (code !in Languages.SUPPORTED || code == current.source) return
        retire()
        setState { it.copy(source = code, target = defaultTarget(code), translating = false, translated = "", failed = false) }
    }

    fun setTarget(code: String) {
        if (code !in current.targets || code == current.target) return
        retire()
        setState { it.copy(target = code, translating = false, translated = "", failed = false) }
    }

    fun setAddTitleMarker(on: Boolean) = setState { it.copy(addTitleMarker = on) }

    fun translate() {
        val s = current
        if (!s.canTranslate) return
        retire()
        val id = runId
        setState { it.copy(translating = true, failed = false, translated = "", done = 0, total = 0) }
        run = scope.launch {
            try {
                val result = TranslateMarkdown.translate(
                    body,
                    translate = { text -> env.rest.translate(text, s.source, s.target).text },
                    onProgress = { done, total -> if (runId == id) setState { it.copy(done = done, total = total) } },
                    isCancelled = { runId != id },
                )
                if (runId == id) setState { it.copy(translating = false, translated = result) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // RN compose_translate.error; nothing of a failed run is kept
                if (runId == id) setState { it.copy(translating = false, failed = true) }
            }
        }
    }

    /**
     * RN _apply: the translation goes under a rule and a heading at the end of the body, and the title gets
     * ` [EN | ES]` when asked for and when it fits. False when there is nothing to apply.
     */
    fun apply(): Boolean {
        val s = current
        if (!s.canApply) return false
        val appendix = appendix(s.target, s.translated)
        val marker = titleMarker(title, s.source, s.target).takeIf { s.addTitleMarker }
        retire()
        return editor.applyTranslation(appendix, marker)
    }

    override fun close() {
        retire()
        super.close()
    }

    companion object {
        /** RN RAW_SAMPLE_CHARS, SAMPLE_CHARS and MIN_TRANSLATE_CHARS. */
        const val RAW_SAMPLE_CHARS = 2000
        const val SAMPLE_CHARS = 600
        const val MIN_TRANSLATE_CHARS = 30

        val TITLE: String get() = L.composeTranslate.title
        val REVIEW_NOTE: String get() = L.composeTranslate.reviewNote
        val TOO_SHORT: String get() = L.composeTranslate.tooShort
        val ERROR: String get() = L.composeTranslate.error
        val ADD_TITLE_MARKER: String get() = L.composeTranslate.addTitleMarker

        /**
         * RN asks `Intl.DisplayNames` for the language's name in that language first; Hermes has none, so RN's
         * heading is the English name, as here.
         */
        fun appendix(target: String, translated: String): String = "\n\n---\n\n## ${Languages.name(target)}\n\n$translated"

        /**
         * ` [EN | ES]`, or null without a title or when it would not fit. RN allows 255 characters in all, which
         * its own form then refuses to publish; here the limit is the title field's 250.
         */
        fun titleMarker(title: String, source: String, target: String): String? {
            if (Js.trim(title).isEmpty()) return null
            val marker = " [${source.uppercase()} | ${target.uppercase()}]"
            return marker.takeIf { title.length + it.length <= EditorModel.MAX_TITLE_LENGTH }
        }
    }
}

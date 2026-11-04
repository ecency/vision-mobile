package com.ecency.core.app

import com.ecency.core.api.model.Post
import com.ecency.core.api.model.PostKey
import com.ecency.core.api.model.obj
import com.ecency.core.api.model.str
import com.ecency.core.render.js.Js
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.cancellation.CancellationException

data class RecapState(
    /** The account the recap is asked for; null signed out (RN shows no recap then). */
    val owner: String? = null,
    /** RN: signed in and at least 50 words of readable text. */
    val eligible: Boolean = false,
    /** The server's price for `summarize`; null until read (nothing is sent without it). */
    val price: com.ecency.core.api.ecency.AiAssistPrice? = null,
    val balance: Double? = null,
    val running: Boolean = false,
    /** The request has no answer yet: the button is "Check again" with the same key, never a new charge. */
    val unresolved: Boolean = false,
    val notice: String? = null,
    /** "Fail!" alert text to show once. */
    val alert: String? = null,
    val summary: String? = null,
    val showTranslation: Boolean = false,
    val translating: Boolean = false,
    val translated: String? = null,
    val target: String = "en",
) {
    val isFree: Boolean get() = (price?.freeRemaining ?: 0) > 0

    /** What the tap costs, under the RN label: "Free" or "5 POINTS" (RN shows no price; the money rules do). */
    val costText: String? get() = price?.let { if (isFree) UiCopy.FREE else "${PaidCalls.format(it.cost)} ${PaidCopy.POINTS}" }

    val label: String get() = if (unresolved) PaidCopy.CHECK_AGAIN else UiCopy.RECAP

    /** The text shown in the summary box: the translation while it is on and done, else the summary. */
    val shownText: String? get() = if (showTranslation && translated != null) translated else summary

    val languages: List<Pair<String, String>> get() = Languages.SUPPORTED.map { it to Languages.name(it) }
}

/**
 * RN postReadingMetadata's "AI Recap": `useAiAssist({action: 'summarize', text: plainText.slice(0, 10000)})` for the
 * signed-in reader of a post with 50 words or more, then "Translate" / "Hide translation" with a language picker
 * over the summary. Paid in points as the editor's assist ([AiAssistModel]): one key per run stored before it leaves
 * (scoped to the post), no retry by itself, "Check again" for a run without an answer, nothing sent without a
 * price and a balance that covers it.
 */
class RecapModel internal constructor(private val env: AppEnvironment, val key: PostKey, readerLanguage: String) :
    StateModel<RecapState>(RecapState(target = Languages.readerLanguage(readerLanguage)), env.dispatchers) {
    private var flow: PaidFlow<String>? = null
    private var text: String = ""
    private var body: String? = null
    private var translateJob: Job? = null
    private var startJob: Job? = null
    private var runJob: Job? = null

    /**
     * Every paid flow this screen made, per account and body version. A switch away keeps the payer's flow (with an
     * intent the device may not have been able to store), so the payer can still fetch the answer on return.
     */
    private val flows = mutableMapOf<String, PaidFlow<String>>()

    /** The post as edited while the same reader's run was open: applied once that run is over. */
    private var deferred: Post? = null

    /** One stored intent per post AND body version, so a run for an older body never answers the new one. */
    private fun scopeName(body: String) = "recap:${key.author}/${key.permlink}:${body.hashCode().toUInt().toString(16)}"

    /**
     * Gating and prices for [post] and the signed-in [owner] (RN resets the summary for another post). A change of
     * account starts over; the same post and account again do nothing.
     */
    fun start(post: Post, owner: String?) {
        if (post.body == body && owner == current.owner) {
            deferred = null
            return
        }
        if (owner != null && owner == current.owner && (current.running || current.unresolved)) {
            // the same reader's run is open: it finishes on its own key first (its answer is a summary of this post)
            deferred = post
            return
        }
        deferred = null
        apply(post, owner, keepSummary = false)
    }

    /** Takes [post] for [owner]: a new account starts over, an edited body keeps the summary on screen. */
    private fun apply(post: Post, owner: String?, keepSummary: Boolean) {
        body = post.body
        startJob?.cancel()
        // a run in flight for another account keeps going (its request may be charged) but lands only with its own
        // flow and account: it is not settled while they are away, so the payer gets it with the same key later
        runJob = null
        flow = null
        if (!keepSummary) {
            translateJob?.cancel()
            setState { RecapState(owner = owner, target = it.target) }
        }
        if (owner == null) return
        startJob = scope.launch {
            text = withContext(env.dispatchers.render) { Speech.plainText(post.body) }
            val words = withContext(env.dispatchers.render) { Speech.wordCount(text).let { (c, w) -> c + w } }
            val eligible = words >= MIN_WORDS && text.length >= MIN_CHARS
            setState { it.copy(eligible = eligible) }
            if (!eligible) return@launch
            val scopeName = scopeName(post.body)
            val f = flows.getOrPut("$owner|$scopeName") {
                PaidFlow(
                    env, owner, AiAssistModel.KIND, scopeName,
                    PaidRunner(env, { i -> env.aiApi.assist(i.owner, i.payload.str("action").orEmpty(), i.payload.str("text").orEmpty(), i.key) }, ::parse),
                )
            }
            flow = f
            if (f.restore() != null) setState { it.copy(unresolved = true, notice = PaidCopy.RESTORED) }
            refresh(owner)
        }
    }

    private fun parse(json: JsonElement): String? =
        (json.obj()?.get("output") as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() && it.length <= AiAssistResult.MAX_OUTPUT }

    /** Price and balance of [owner]; written only while the model still shows that owner. */
    private suspend fun refresh(owner: String) {
        val price = try {
            env.aiApi.assistPrices(owner).firstOrNull { it.action == ACTION }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val points = try {
            env.aiApi.points(owner)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (current.owner != owner) return
        setState { it.copy(price = price ?: it.price, balance = PaidCalls.points(points) ?: it.balance) }
    }

    /** RN handleSummarize; for a run without an answer, "Check again" with its key. */
    fun summarize() {
        val s = current
        val f = flow ?: return
        if (s.running || !s.eligible || s.summary != null) return
        if (!s.unresolved) {
            val price = s.price ?: return
            if (!s.isFree) {
                val balance = s.balance ?: return
                if (balance < price.cost) {
                    // RN learns this from the server's 402; known here before anything is sent
                    setState { it.copy(alert = UiCopy.insufficientPoints(PaidCalls.format(price.cost), PaidCalls.format(balance))) }
                    return
                }
            }
        }
        val input = text.take(AiAssistModel.MAX_ASSIST_INPUT)
        setState { it.copy(running = true, alert = null) }
        runJob = scope.launch {
            val outcome = f.ask(
                payload = { buildJsonObject { put("action", ACTION); put("text", input) } },
                onPending = { if (flow === f) setState { it.copy(notice = PaidCopy.PENDING) } },
            )
            // another account on screen since the tap: nothing of this run is shown here (nor settled, see PaidFlow.ask)
            if (flow !== f || current.owner != f.owner) return@launch
            when (outcome) {
                is PaidOutcome.Delivered -> if (!f.ownerSignedIn()) {
                    setState { it.copy(running = false, unresolved = true, notice = PaidCopy.otherAccount(f.owner)) }
                } else {
                    setState { it.copy(running = false, unresolved = false, notice = null, summary = outcome.value, showTranslation = false, translated = null) }
                    refresh(f.owner)
                }
                is PaidOutcome.Pending -> setState { it.copy(running = false, unresolved = true, notice = PaidCopy.PENDING) }
                PaidOutcome.Unknown -> setState { it.copy(running = false, unresolved = true, notice = if (f.stored) PaidCopy.UNKNOWN else PaidCopy.UNKNOWN + " " + PaidCopy.NOT_STORED) }
                is PaidOutcome.Refused -> {
                    val ended = PaidFlow.endsIntent(outcome)
                    val alert = when (outcome.status) {
                        PaidCalls.ACCOUNT_CHANGED -> PaidCopy.otherAccount(f.owner)
                        401, 403 -> PaidCopy.SIGN_IN_AGAIN
                        402 -> UiCopy.insufficientPoints(PaidCalls.format(outcome.required ?: 0.0), PaidCalls.format(outcome.available ?: 0.0))
                        422 -> UiCopy.ERROR_CONTENT_POLICY
                        429 -> UiCopy.ERROR_RATE_LIMIT
                        else -> UiCopy.ERROR_GENERIC
                    }
                    setState { it.copy(running = false, unresolved = if (ended) false else it.unresolved || f.intent != null, notice = if (ended) null else it.notice, alert = alert) }
                    if (ended) refresh(f.owner)
                }
            }
            // a body edited during the run is taken now that the run is over (the summary stays on screen)
            if (!current.unresolved) deferred?.let { d ->
                deferred = null
                apply(d, f.owner, keepSummary = true)
            }
        }
    }

    /** "Give up" on a run without an answer (after the UI's confirmation): its key is dropped. */
    fun giveUp() {
        val f = flow ?: return
        if (current.running || !current.unresolved) return
        scope.launch {
            f.settle()
            setState { it.copy(unresolved = false, notice = null) }
            deferred?.let { d ->
                deferred = null
                apply(d, f.owner, keepSummary = true)
            }
        }
    }

    /** RN "Try again": clears the summary; the next tap is a new run with a new key. */
    fun tryAgain() {
        translateJob?.cancel()
        setState { it.copy(summary = null, showTranslation = false, translated = null, translating = false) }
    }

    fun clearAlert() = setState { it.copy(alert = null) }

    /** RN "Translate" / "Hide translation". */
    fun toggleTranslation() {
        if (current.summary == null) return
        val on = !current.showTranslation
        setState { it.copy(showTranslation = on) }
        if (on && current.translated == null) translate()
    }

    /** RN's language dropdown. */
    fun setTarget(code: String) {
        if (code !in Languages.SUPPORTED || code == current.target) return
        setState { it.copy(target = code, translated = null) }
        if (current.showTranslation) translate()
    }

    private fun translate() {
        val summary = current.summary ?: return
        val target = current.target
        translateJob?.cancel()
        setState { it.copy(translating = true, translated = null) }
        translateJob = scope.launch {
            val out = try {
                val parts = mutableListOf<String>()
                for (chunk in Languages.chunk(Js.trim(summary))) parts += env.rest.translate(chunk, "auto", target).text
                parts.joinToString(" ")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // RN only logs it and keeps the summary on screen
                null
            }
            setState { it.copy(translating = false, translated = out) }
        }
    }

    companion object {
        const val ACTION = "summarize"
        const val MIN_WORDS = 50
        const val MIN_CHARS = 50
    }
}

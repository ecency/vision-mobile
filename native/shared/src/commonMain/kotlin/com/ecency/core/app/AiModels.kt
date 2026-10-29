package com.ecency.core.app

import com.ecency.core.api.ecency.AiAssistPrice
import com.ecency.core.api.ecency.AiImagePrices
import com.ecency.core.api.model.int
import com.ecency.core.api.model.obj
import com.ecency.core.api.model.str
import com.ecency.core.l10n.L
import com.ecency.core.render.js.Js
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.cancellation.CancellationException

/** Copy shared by the paid AI features where this app says more than RN does about the charge. */
object PaidCopy {
    val POINTS: String get() = L.aiAssist.pointsUnit
    val CHECK_AGAIN: String get() = L.native.paid.checkAgain
    val GIVE_UP: String get() = L.native.paid.giveUp
    val UNKNOWN: String get() = L.native.paid.unknown
    val PENDING: String get() = L.native.paid.pending
    val RESTORED: String get() = L.native.paid.restored
    val GIVE_UP_CONFIRM: String get() = L.native.paid.giveUpConfirm
    val NOT_STORED: String get() = L.native.paid.notStored
    val SIGN_IN_AGAIN: String get() = L.native.paid.signInAgain

    fun otherAccount(owner: String) = L.native.paid.otherAccount(owner = owner)

    fun insufficient(required: String, available: String) = L.aiAssist.errorInsufficientPoints(required = required, available = available)
}

/**
 * The one intent a paid screen works on. A request for it always carries the key it got when it began; a new
 * key is only made after the previous intent ended ([settle]): delivered, refused, or given up by the user.
 */
internal class PaidFlow<T>(
    private val env: AppEnvironment,
    val owner: String,
    private val kind: String,
    /** The editor the request belongs to ([EditorModel.paidScope]). */
    private val scope: String,
    private val runner: PaidRunner<T>,
    /** Which answers end the intent; by default [endsIntent]. */
    private val ends: (PaidOutcome<*>) -> Boolean = ::endsIntent,
) {
    var intent: PaidIntent? = null
        private set

    /** False when the device refused to store the running intent. */
    var stored: Boolean = true
        private set

    /** The unfinished intent from an earlier run of the app, if any. */
    suspend fun restore(): PaidIntent? {
        if (intent == null) intent = env.paidIntents.load(owner, kind, scope)
        return intent
    }

    /**
     * Asks once for the current intent, or begins one from [payload] when there is none. The payload of an
     * unfinished intent is never replaced: its key is bound to what was asked the first time.
     */
    suspend fun ask(payload: () -> JsonObject, onPending: () -> Unit = {}): PaidOutcome<T> {
        val current = intent ?: env.paidIntents.begin(owner, kind, scope, payload()).let { (created, ok) ->
            stored = ok
            intent = created
            created
        }
        val outcome = runner.ask(current, onPending)
        // A result that came back after the payer switched away is not settled: the intent stays stored, so the
        // payer finds "Check again" on return and fetches the same answer with the same key.
        if (ends(outcome) && !(outcome is PaidOutcome.Delivered && !ownerSignedIn())) settle()
        return outcome
    }

    /** The account that pays for this intent is the one signed in now. */
    fun ownerSignedIn(): Boolean = env.session.current.username == owner

    /** The intent is over: its key is never used again. */
    suspend fun settle() {
        val done = intent ?: return
        intent = null
        stored = true
        env.paidIntents.finish(done, scope)
    }

    companion object {
        /**
         * A delivered result and a definite refusal end the intent. An expired session (401, 403) and a changed
         * account do not: the request may have been taken earlier, and its result is still to be fetched.
         */
        fun endsIntent(outcome: PaidOutcome<*>): Boolean = when (outcome) {
            is PaidOutcome.Delivered -> true
            is PaidOutcome.Refused -> outcome.status != 401 && outcome.status != 403 && outcome.status != PaidCalls.ACCOUNT_CHANGED
            is PaidOutcome.Pending, PaidOutcome.Unknown -> false
        }
    }
}

/** What an assist run gave back, read as RN's `parsedResult`. */
data class AiAssistResult(val action: String, val output: String, val titles: List<String> = emptyList(), val tags: List<String> = emptyList()) {
    val isTitles: Boolean get() = titles.isNotEmpty()
    val isTags: Boolean get() = tags.isNotEmpty()

    /**
     * Whether "Apply to post" is offered. A Recap (summarize) is copy only: RN applied it by replacing the
     * whole body with the summary (#3599), which loses the post.
     */
    val canApply: Boolean get() = action != AiAssistModel.RECAP_ACTION

    companion object {
        /** The longest output this app takes in (the server stores 10000 characters). */
        const val MAX_OUTPUT = 50_000

        /** RN: the output without a surrounding code fence, as a JSON list of strings for titles and tags. */
        fun of(action: String, output: String): AiAssistResult {
            val stripped = Js.trim(stripFence(output))
            val list = runCatching { Json.parseToJsonElement(stripped) }.getOrNull().let { it as? JsonArray }
                ?.takeIf { a -> a.isNotEmpty() && a.all { it is JsonPrimitive && it.isString } }?.map { (it as JsonPrimitive).content }
            return when {
                action == "generate_title" && list != null -> AiAssistResult(action, output, titles = list.map { Js.trim(it) }.filter { it.isNotEmpty() }.distinct())
                action == "suggest_tags" && list != null -> AiAssistResult(action, output, tags = list.map { Js.trim(it).lowercase() }.filter { it.isNotEmpty() }.distinct())
                else -> AiAssistResult(action, output)
            }
        }

        /** `.replace(/^```(?:json)?\s*\n?/i, '').replace(/\n?```\s*$/i, '')` */
        internal fun stripFence(output: String): String {
            var s = output
            if (s.startsWith("```")) {
                var i = 3
                if (s.regionMatches(i, "json", 0, 4, ignoreCase = true)) i += 4
                while (i < s.length && Js.isSpace(s[i])) i++
                s = s.substring(i)
            }
            var end = s.length
            while (end > 0 && Js.isSpace(s[end - 1])) end--
            if (end >= 3 && s.startsWith("```", end - 3)) {
                end -= 3
                if (end > 0 && s[end - 1] == '\n') end--
                s = s.substring(0, end)
            }
            return s
        }
    }
}

data class AiAssistState(
    val owner: String? = null,
    val loadingPrices: Boolean = true,
    val prices: List<AiAssistPrice> = emptyList(),
    /** The balance as the server prints it ("1,234.567"); null until it is known. */
    val balanceText: String? = null,
    val balance: Double? = null,
    val action: String? = null,
    val text: String = "",
    val running: Boolean = false,
    /** The current request has no answer: only "Check again" (same key) or "Give up" are offered. */
    val unresolved: Boolean = false,
    /** What is said about the request right now (no answer, still processing, restored). */
    val notice: String? = null,
    /** An alert to show once ("Fail!"). */
    val alert: String? = null,
    val result: AiAssistResult? = null,
    val selectedTitle: Int = 0,
    /** The actions offered, in RN's order: every one for a post, improve, grammar and summary for a wave (RN supportedActions). */
    val actions: List<String> = AiAssistModel.ACTIONS,
) {
    val selectedPrice: AiAssistPrice? get() = action?.let { a -> prices.firstOrNull { it.action == a } }

    val isFree: Boolean get() = (selectedPrice?.freeRemaining ?: 0) > 0

    /** RN getMinInput. */
    val minInput: Int get() = if (action == null || action == "improve" || action == "check_grammar") 50 else 100

    /** Known to be short: without a balance from the server nothing is claimed (and nothing paid can be run). */
    val insufficient: Boolean get() = !isFree && balance != null && selectedPrice?.let { balance < it.cost } == true

    /** A paid run needs a balance that was read; a free one does not. */
    private val balanceOk: Boolean get() = isFree || (balance != null && !insufficient)

    val trimmedLength: Int get() = Js.trim(text).length

    val canSubmit: Boolean
        get() = unresolved && !running ||
            action != null && selectedPrice != null && !loadingPrices && trimmedLength >= minInput && balanceOk && !running

    /** RN's cost row: "Free" or "5 POINTS". */
    val costText: String? get() = selectedPrice?.let { if (isFree) L.trade.free else "${PaidCalls.format(it.cost)} ${PaidCopy.POINTS}" }

    /**
     * The button that charges. RN's says "Run AI Assist" and has no confirmation step; here it also states what
     * the tap costs. For a request without an answer it is "Check again", which never charges anew.
     */
    val submitLabel: String
        get() = when {
            unresolved -> PaidCopy.CHECK_AGAIN
            costText != null -> L.native.ai.runAiAssist(costText = costText.orEmpty())
            else -> L.aiAssist.submitButton
        }

    val balanceLabel: String get() = if (balanceText == null) "..." else "${PaidCalls.balance(balanceText)} ${PaidCopy.POINTS}"

    val countText: String
        get() = if (text.length < minInput) L.native.plurals.aiAssist.minChars(count = minInput) else L.native.plurals.aiAssist.charsRemaining(count = AiAssistModel.MAX_ASSIST_INPUT - text.length)

    val insufficientText: String?
        get() = if (insufficient) PaidCopy.insufficient(PaidCalls.format(selectedPrice?.cost ?: 0.0), PaidCalls.format(balance ?: 0.0)) else null

    /** What Apply and Copy use for a list of titles. */
    val chosenTitle: String? get() = result?.titles?.let { it.getOrNull(selectedTitle) ?: it.firstOrNull() }
}

/**
 * What AI assist and dictation write into: the post editor, or the wave composer (RN quick post's dictation and
 * AI assist). Everything paid belongs to [aiOwner], the account that opened the target.
 */
interface AiTextTarget {
    /** The account that opened the editor or composer; null: nothing paid can start. */
    val aiOwner: String?

    /** Which editor or composer a paid request belongs to ([PaidFlow]); it does not change while the target lives. */
    val paidScope: String

    /** The text AI assist starts from. */
    val aiText: String

    /** RN supportedActions: the AI assist actions this target offers (a subset of [AiAssistModel.ACTIONS], in its order). */
    val aiActions: List<String>

    /** RN onApply. False when the target cannot change now or belongs to another account than the one signed in. */
    fun applyAiResult(action: String, text: String, tags: List<String> = emptyList()): Boolean

    /** RN _handleDictationResult. False as for [applyAiResult]. */
    fun insertDictation(text: String): Boolean
}

/**
 * RN `aiAssistModal` over the SDK's `useAiAssist`: improve, tags, title, recap and grammar for the editor's
 * text, paid in Ecency points (some runs a day are free). As in RN there is no confirmation step: the button
 * charges. What differs is the handling of the charge:
 * - one idempotency key per run, stored before the request leaves ([PaidIntents]) and reused by every later
 *   request for that run, also after the app was killed. RN makes a new key on every tap;
 * - a timeout or a lost answer is "result unknown" with "Check again" (same key), never a silent retry and
 *   never a new charge; a 409 (`in_progress`) means the request was taken and is asked for again with its key;
 * - the request and the result belong to the account that opened the editor.
 */
class AiAssistModel internal constructor(private val env: AppEnvironment, private val editor: AiTextTarget) :
    StateModel<AiAssistState>(AiAssistState(), env.dispatchers) {
    private var flow: PaidFlow<AiAssistResult>? = null
    private var started = false

    fun start() {
        if (started) return
        started = true
        val owner = editor.aiOwner ?: return
        val f = PaidFlow(env, owner, KIND, editor.paidScope, PaidRunner(env, { i -> env.aiApi.assist(i.owner, i.payload.str("action").orEmpty(), i.payload.str("text").orEmpty(), i.key) }, ::parse))
        flow = f
        // RN: the body as it is when the sheet opens, 10000 characters at most
        setState { it.copy(owner = owner, text = editor.aiText.take(MAX_ASSIST_INPUT), actions = ACTIONS.filter { a -> a in editor.aiActions }) }
        scope.launch {
            val earlier = f.restore()
            if (earlier != null) {
                setState { it.copy(action = earlier.payload.str("action"), text = earlier.payload.str("text").orEmpty(), unresolved = true, notice = PaidCopy.RESTORED) }
            }
            refresh(owner)
        }
    }

    private var pricesAtMillis = 0L

    private suspend fun refresh(owner: String) {
        val prices = try {
            env.aiApi.assistPrices(owner)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (prices != null) pricesAtMillis = env.nowMillis()
        setState { it.copy(loadingPrices = false, prices = prices?.filter { p -> p.action in ACTIONS } ?: it.prices) }
        val points = try {
            env.aiApi.points(owner)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (points != null) setState { it.copy(balanceText = points, balance = PaidCalls.points(points)) }
    }

    private fun parse(json: JsonElement): AiAssistResult? {
        val o = json.obj() ?: return null
        val output = (o["output"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() && it.length <= AiAssistResult.MAX_OUTPUT } ?: return null
        val asked = flow?.intent?.payload?.str("action") ?: return null
        // RN: the action the server names when it is a known one, else the one that was asked for
        return AiAssistResult.of(o.str("action")?.takeIf { it in ACTIONS } ?: asked, output)
    }

    fun selectAction(action: String) {
        if (action !in current.actions || current.running || current.unresolved) return
        setState { it.copy(action = action) }
    }

    fun setText(text: String) {
        if (current.running || current.unresolved) return
        setState { it.copy(text = text.take(MAX_ASSIST_INPUT)) }
    }

    fun selectTitle(index: Int) = setState { it.copy(selectedTitle = index) }

    fun clearAlert() = setState { it.copy(alert = null) }

    /** The button: a new run (charged as the label says), or "Check again" for the run without an answer. */
    fun submit() {
        val s = current
        val f = flow ?: return
        if (!s.canSubmit) return
        val action = s.action ?: return
        val text = Js.trim(s.text).take(MAX_ASSIST_INPUT)
        val cost = s.selectedPrice?.cost ?: 0.0
        setState { it.copy(running = true, alert = null, notice = if (it.unresolved) it.notice else null) }
        scope.launch {
            // A new run under a label that has been on screen for a while: the free allowance may be used up by now
            // (another device, another editor). The price is read again, and a run that stopped being free is not
            // sent under "(Free)": the button shows the cost and waits for another tap.
            if (f.intent == null && env.nowMillis() - pricesAtMillis > PRICE_MAX_AGE_MILLIS) {
                val wasFree = s.isFree
                refresh(f.owner)
                val now = current
                if (wasFree && !now.isFree) {
                    setState { it.copy(running = false, alert = now.selectedPrice?.let { p -> L.native.ai.noLongerFreeCost(cost = PaidCalls.format(p.cost), unit = PaidCopy.POINTS) } ?: L.native.ai.noLongerFree) }
                    return@launch
                }
                if (now.selectedPrice == null || !(now.isFree || (now.balance != null && !now.insufficient))) {
                    setState { it.copy(running = false) }
                    return@launch
                }
            }
            val outcome = f.ask(
                payload = { buildJsonObject { put("action", action); put("text", text) } },
                onPending = { setState { it.copy(notice = PaidCopy.PENDING) } },
            )
            when (outcome) {
                is PaidOutcome.Delivered -> if (!f.ownerSignedIn()) {
                    // the answer belongs to the payer, who switched away: kept for their "Check again", never shown here
                    setState { it.copy(running = false, unresolved = true, notice = PaidCopy.otherAccount(f.owner)) }
                } else {
                    setState { it.copy(running = false, unresolved = false, notice = null, result = outcome.value, selectedTitle = 0) }
                    refresh(f.owner)
                }
                is PaidOutcome.Pending -> setState { it.copy(running = false, unresolved = true, notice = PaidCopy.PENDING) }
                PaidOutcome.Unknown -> setState { it.copy(running = false, unresolved = true, notice = if (f.stored) PaidCopy.UNKNOWN else PaidCopy.UNKNOWN + " " + PaidCopy.NOT_STORED) }
                is PaidOutcome.Refused -> {
                    val ended = PaidFlow.endsIntent(outcome)
                    val alert = when (outcome.status) {
                        PaidCalls.ACCOUNT_CHANGED -> PaidCopy.otherAccount(f.owner)
                        401, 403 -> PaidCopy.SIGN_IN_AGAIN
                        402 -> PaidCopy.insufficient(PaidCalls.format(outcome.required ?: cost), PaidCalls.format(outcome.available ?: 0.0))
                        422 -> L.native.ai.contentViolatesOurContent
                        429 -> L.aiAssist.errorRateLimit
                        else -> L.aiAssist.errorGeneric
                    }
                    setState { it.copy(running = false, unresolved = if (ended) false else it.unresolved || f.intent != null, notice = if (ended) null else it.notice, alert = alert) }
                    if (ended) refresh(f.owner)
                }
            }
        }
    }

    /** "Give up" after its confirmation: the run without an answer is dropped, with its key. */
    fun giveUp() {
        val f = flow ?: return
        if (current.running || !current.unresolved) return
        scope.launch {
            f.settle()
            setState { it.copy(unresolved = false, notice = null) }
        }
    }

    /** RN _handleApply. False when the editor did not take it (another account is signed in, or it is sending). */
    fun apply(): Boolean {
        val r = current.result ?: return false
        if (!r.canApply) return false
        return when {
            r.isTitles -> editor.applyAiResult("generate_title", current.chosenTitle.orEmpty(), emptyList())
            r.isTags -> editor.applyAiResult("suggest_tags", "", r.tags)
            // RN's fallback for tags that did not come as a list: split on commas
            r.action == "suggest_tags" -> editor.applyAiResult("suggest_tags", "", r.output.split(',').map { Js.trim(it) }.filter { it.isNotEmpty() })
            else -> editor.applyAiResult(r.action, r.output, emptyList())
        }
    }

    /** RN _handleCopy's value. */
    val copyValue: String?
        get() = current.result?.let { r -> if (r.isTags) r.tags.joinToString(", ") else if (r.isTitles) current.chosenTitle else r.output }

    /** RN "Try again": back to the form with the same action. The next run is a new one, with a new key. */
    fun tryAgain() = setState { it.copy(result = null) }

    /** RN "Try another". */
    fun tryAnother() = setState { it.copy(result = null, action = null) }

    companion object {
        const val KIND = "assist"

        /** The summary action, never applied to a body (copy only). */
        const val RECAP_ACTION = "summarize"

        /** A price older than this is read again before a new run is sent. */
        const val PRICE_MAX_AGE_MILLIS = 60_000L
        const val MAX_ASSIST_INPUT = 10_000

        /** RN ACTIONS, in its order. */
        val ACTIONS = listOf("improve", "suggest_tags", "generate_title", "summarize", "check_grammar")

        val TITLE: String get() = L.aiAssist.title
        val HINT: String get() = L.aiAssist.hint

        fun actionName(action: String): String = when (action) {
            "improve" -> L.aiAssist.actionImprove
            "suggest_tags" -> L.aiAssist.actionSuggestTags
            "generate_title" -> L.aiAssist.actionGenerateTitle
            "summarize" -> L.aiAssist.actionSummarize
            "check_grammar" -> L.aiAssist.actionCheckGrammar
            else -> action
        }

        fun actionDescription(action: String): String = when (action) {
            "improve" -> L.aiAssist.actionImproveDesc
            "suggest_tags" -> L.aiAssist.actionSuggestTagsDesc
            "generate_title" -> L.aiAssist.actionGenerateTitleDesc
            "summarize" -> L.aiAssist.actionSummarizeDesc
            "check_grammar" -> L.aiAssist.actionCheckGrammarDesc
            else -> ""
        }

        /** "3 free left · 5 POINTS" as RN's card shows it. */
        fun priceLine(price: AiAssistPrice?): String =
            listOfNotNull(price?.takeIf { it.freeRemaining > 0 }?.let { L.aiAssist.freeRemaining(count = it.freeRemaining.toString()) }, "${price?.let { PaidCalls.format(it.cost) } ?: "?"} ${PaidCopy.POINTS}").joinToString(" · ")
    }
}

data class AiImageState(
    val owner: String? = null,
    val loading: Boolean = true,
    val prices: AiImagePrices? = null,
    val balance: Double? = null,
    val prompt: String = "",
    val aspectRatio: String = AiImageModel.DEFAULT_RATIO,
    val power: Int = 1,
    /** The editor's title, offered as a prompt while the field is empty. */
    val suggestedPrompt: String? = null,
    val running: Boolean = false,
    val unresolved: Boolean = false,
    val notice: String? = null,
    val alert: String? = null,
    /** The generated image; always an address that passed the upload url check. */
    val resultUrl: String? = null,
    /** RN shows "Insert into post" only when the screen was opened from an editor. */
    val canInsert: Boolean = false,
) {
    /** RN baseCost, without its made-up 150: no price from the server, no cost and no button. */
    val baseCost: Double? get() = prices?.prices?.let { list -> (list.firstOrNull { it.aspectRatio == aspectRatio } ?: list.firstOrNull())?.cost }

    val multiplier: Double get() = prices?.power?.firstOrNull { it.power == power }?.multiplier ?: 1.0

    val cost: Double? get() = baseCost?.let { it * multiplier }

    /** RN shows the tiers only when there is more than one. */
    val powerTiers: List<Int> get() = prices?.power?.takeIf { it.size > 1 }?.map { it.power }.orEmpty()

    val canGenerate: Boolean
        get() = unresolved && !running ||
            Js.trim(prompt).isNotEmpty() && cost != null && balance != null && balance >= (cost ?: 0.0) && !running

    val costLabel: String get() = cost?.let { "${PaidCalls.format(it)} ${PaidCopy.POINTS}" } ?: "..."

    val balanceLabel: String get() = balance?.let { "${PaidCalls.balance(it)} ${PaidCopy.POINTS}" } ?: "..."

    /** RN's button says "Generate Image" and charges without a confirmation; here it also states the cost. */
    val generateLabel: String
        get() = when {
            unresolved -> PaidCopy.CHECK_AGAIN
            cost != null -> L.native.ai.generateImage(costLabel = costLabel)
            else -> L.aiAssist.actionGenerateImage
        }

    val remainingText: String get() = L.native.plurals.aiAssist.charsRemaining(count = AiImageModel.MAX_PROMPT - prompt.length)

    val suggestionText: String?
        get() = suggestedPrompt?.takeIf { Js.trim(prompt).isEmpty() && !unresolved }?.let { L.native.ai.usePrompt(prompt = if (it.length > 80) it.take(80) + "…" else it) }

    /** Width over height of the chosen ratio (RN _getAspectRatioNumber, 4:3 when unreadable). */
    val ratioNumber: Double
        get() {
            val parts = aspectRatio.split(':').map { it.toDoubleOrNull() }
            val w = parts.getOrNull(0)
            val h = parts.getOrNull(1)
            return if (w == null || h == null || h == 0.0 || !w.isFinite() || !h.isFinite()) 4.0 / 3 else w / h
        }
}

/**
 * RN `aiImageGeneratorScreen` over the SDK's `useGenerateImage`: a prompt, an aspect ratio and a power tier,
 * paid in Ecency points when the button is tapped (no confirmation step, as RN). The charge is handled as in
 * [AiAssistModel]; in addition the SDK's HTTP 202 (`delivery_pending`: paid, the image is not on the image
 * host yet) and a 409 are asked for again with the same key, which RN's screen does not do (it shows its
 * generic failure and forgets the key). The address the server answers with must pass the same check as an
 * uploaded image's before it is shown or inserted.
 */
class AiImageModel internal constructor(private val env: AppEnvironment, private val editor: EditorModel?) :
    StateModel<AiImageState>(AiImageState(), env.dispatchers) {
    private var flow: PaidFlow<String>? = null
    private var started = false

    fun start() {
        if (started) return
        started = true
        if (editor != null) begin(editor.current.owner ?: return) else scope.launch {
            // opened on its own (RN's screen without `onInsert`): the account signed in when it opens pays, and only that one
            env.session.awaitLoaded()
            env.session.current.username?.let(::begin)
        }
    }

    private fun begin(owner: String) {
        val f = PaidFlow(
            env, owner, KIND, editor?.paidScope ?: STANDALONE_SCOPE,
            PaidRunner(
                env,
                { i -> env.aiApi.generateImage(i.owner, i.payload.str("prompt").orEmpty(), i.payload.str("aspect_ratio") ?: DEFAULT_RATIO, i.payload.int("power") ?: 1, i.key) },
                // a 200 without a usable address says nothing reliable: it stays "unknown" and can be asked again
                { json -> (json.obj()?.get("url") as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { UploadNames.isSafeUrl(it, env.imageHost) } },
            ),
        )
        flow = f
        setState { it.copy(owner = owner, canInsert = editor != null, suggestedPrompt = editor?.let { e -> Js.trim(e.current.title).takeIf { t -> t.isNotEmpty() } }) }
        scope.launch {
            val earlier = f.restore()
            if (earlier != null) {
                setState {
                    it.copy(
                        prompt = earlier.payload.str("prompt").orEmpty(), aspectRatio = earlier.payload.str("aspect_ratio")?.takeIf { r -> r in RATIOS } ?: it.aspectRatio,
                        power = earlier.payload.int("power") ?: 1, unresolved = true, notice = PaidCopy.RESTORED,
                    )
                }
            }
            refresh(owner)
        }
    }

    private suspend fun refresh(owner: String) {
        val prices = try {
            env.aiApi.imagePrices(owner)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        setState { it.copy(loading = false, prices = prices ?: it.prices) }
        val points = try {
            env.aiApi.points(owner)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        // RN: rounded to three decimals
        PaidCalls.points(points)?.let { p -> setState { it.copy(balance = kotlin.math.round(p * 1000) / 1000) } }
    }

    private val locked: Boolean get() = current.running || current.unresolved

    fun setPrompt(text: String) {
        if (!locked) setState { it.copy(prompt = text.take(MAX_PROMPT)) }
    }

    fun useSuggestion() {
        val s = current.suggestedPrompt ?: return
        setPrompt(s)
    }

    fun setAspectRatio(ratio: String) {
        if (!locked && ratio in RATIOS) setState { it.copy(aspectRatio = ratio) }
    }

    fun setPower(power: Int) {
        if (!locked && power in current.powerTiers) setState { it.copy(power = power) }
    }

    fun clearAlert() = setState { it.copy(alert = null) }

    /** The button: a new image (charged as the label says), or "Check again" for the one without an answer. */
    fun generate() {
        val s = current
        val f = flow ?: return
        if (!s.canGenerate) return
        val prompt = Js.trim(s.prompt)
        setState { it.copy(running = true, alert = null, notice = if (it.unresolved) it.notice else null) }
        scope.launch {
            val outcome = f.ask(
                payload = { buildJsonObject { put("prompt", prompt); put("aspect_ratio", s.aspectRatio); put("power", s.power) } },
                onPending = { setState { it.copy(notice = PaidCopy.PENDING) } },
            )
            when (outcome) {
                is PaidOutcome.Delivered -> if (!f.ownerSignedIn()) {
                    setState { it.copy(running = false, unresolved = true, notice = PaidCopy.otherAccount(f.owner)) }
                } else {
                    setState { it.copy(running = false, unresolved = false, notice = null, resultUrl = outcome.value) }
                    refresh(f.owner)
                }
                is PaidOutcome.Pending -> setState { it.copy(running = false, unresolved = true, notice = PaidCopy.PENDING) }
                PaidOutcome.Unknown -> setState { it.copy(running = false, unresolved = true, notice = if (f.stored) PaidCopy.UNKNOWN else PaidCopy.UNKNOWN + " " + PaidCopy.NOT_STORED) }
                is PaidOutcome.Refused -> {
                    val ended = PaidFlow.endsIntent(outcome)
                    val alert = when (outcome.status) {
                        PaidCalls.ACCOUNT_CHANGED -> PaidCopy.otherAccount(f.owner)
                        401, 403 -> PaidCopy.SIGN_IN_AGAIN
                        402 -> PaidCopy.insufficient(PaidCalls.format(outcome.required ?: s.cost ?: 0.0), PaidCalls.format(outcome.available ?: s.balance ?: 0.0))
                        422 -> L.aiImageGenerator.errorContentPolicy
                        429 -> L.aiImageGenerator.errorRateLimit
                        else -> L.aiImageGenerator.errorGeneric
                    }
                    setState { it.copy(running = false, unresolved = if (ended) false else it.unresolved || f.intent != null, notice = if (ended) null else it.notice, alert = alert) }
                    if (ended) refresh(f.owner)
                }
            }
        }
    }

    fun giveUp() {
        val f = flow ?: return
        if (current.running || !current.unresolved) return
        scope.launch {
            f.settle()
            setState { it.copy(unresolved = false, notice = null) }
        }
    }

    /** RN _handleInsert: the image goes into the editor that opened this screen, if its account is still signed in. */
    fun insert(): Boolean = current.resultUrl?.let { editor?.insertAiImage(it) } ?: false

    /** RN "Generate Again": back to the form. The next image is a new one, with a new key. */
    fun generateAgain() = setState { it.copy(resultUrl = null) }

    companion object {
        const val KIND = "image"

        /** Where the standalone screen's unanswered request waits (an editor's waits under its own draft). */
        const val STANDALONE_SCOPE = "standalone"
        const val MAX_PROMPT = 1000
        const val DEFAULT_RATIO = "4:3"

        /** RN ASPECT_RATIOS. */
        val RATIOS = listOf("1:1", "16:9", "9:16", "4:3", "3:4", "3:2", "2:3")

        val TITLE: String get() = L.aiImageGenerator.title
        val PROMPT_LABEL: String get() = L.aiImageGenerator.promptLabel
        val PROMPT_PLACEHOLDER: String get() = L.aiImageGenerator.promptPlaceholder
        val GENERATING: String get() = L.aiImageGenerator.generating
    }
}

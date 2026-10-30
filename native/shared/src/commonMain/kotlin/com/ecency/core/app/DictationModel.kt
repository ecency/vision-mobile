package com.ecency.core.app

import com.ecency.core.api.ecency.AiTranscribePrice
import com.ecency.core.api.model.long
import com.ecency.core.api.model.obj
import com.ecency.core.api.model.str
import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.l10n.L
import com.ecency.core.render.js.Js
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.cancellation.CancellationException

/**
 * The platform's microphone recorder (Android MediaRecorder, iOS AVAudioRecorder): AAC in an .m4a file, as RN's
 * expo-audio HIGH_QUALITY preset, written to a private folder of the app that the system does not purge and that
 * is never backed up. A clip is known by its file name only (the folder's path changes with an app update); the
 * recorder resolves it against its folder as it is now. Called on the main thread except [read].
 */
interface DictationRecorder {
    /** Whether the microphone permission is granted. The apps ask for it themselves when it is not. */
    fun hasPermission(): Boolean

    /**
     * Starts a recording into a new file whose name begins with [ownerTag] and an underscore ([DictationClips.tag]:
     * 16 hex digits standing for the account, so no account name is on a path). False when the recorder or the
     * audio session refused, or [ownerTag] is not such a tag.
     */
    fun start(ownerTag: String): Boolean

    /** Stops the recording and returns its file's name, or null when there is nothing usable. */
    fun stop(): String?

    /** Stops a recording in progress and deletes what it wrote. */
    fun cancel()

    /** The bytes of the clip named [path], null when it is gone or larger than the server takes. Called off the main thread. */
    fun read(path: String): ByteArray?

    fun delete(path: String)

    /** The names of the recordings on the device, without one in progress. Which of them go is decided by [DictationClips]. */
    fun clipNames(): List<String>
}

/**
 * Whose clip is whose. A clip belongs to the account that recorded it: its name starts with that account's tag
 * (the first 8 bytes of the SHA-256 of the name, in hex, as the draft files are named). One account leaving the
 * device takes its own clips and leaves another account's waiting clip, and the paid request it belongs to, alone.
 */
object DictationClips {
    private const val TAG_LENGTH = 16

    fun tag(owner: String): String = com.ecency.core.hive.sha256(owner.encodeToByteArray()).copyOf(TAG_LENGTH / 2).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    fun isTag(text: String): Boolean = text.length == TAG_LENGTH && text.all { it in '0'..'9' || it in 'a'..'f' }

    /** The tag a clip's name carries; null for a clip of a build that did not tag them. */
    fun tagOf(name: String): String? = name.take(TAG_LENGTH).takeIf { isTag(it) && name.getOrNull(TAG_LENGTH) == '_' }

    /** A sheet of [owner] tidies up: that account's clips go, except [keep] (the one its unanswered request waits for). */
    fun purgeOwn(recorder: DictationRecorder, owner: String, keep: String?, alsoKeep: String? = null) {
        val tag = tag(owner)
        recorder.clipNames().filter { it != keep && it != alsoKeep && tagOf(it) == tag }.forEach(recorder::delete)
    }

    /**
     * Accounts left the device ([owners]; null: all of them, a wipe): their clips go, and so does every clip
     * without a tag, which nobody can be shown to own.
     */
    fun purgeLeft(recorder: DictationRecorder, owners: List<String>?) {
        val tags = owners?.map(::tag)?.toSet()
        recorder.clipNames().filter { name -> tags == null || tagOf(name).let { it == null || it in tags } }.forEach(recorder::delete)
    }
}

enum class DictationPhase { IDLE, RECORDING, TRANSCRIBING }

data class DictationState(
    val owner: String? = null,
    val priceLoading: Boolean = true,
    val price: AiTranscribePrice? = null,
    val priceFailed: Boolean = false,
    /** The balance as the server prints it; RN's sheet does not show one. */
    val balanceText: String? = null,
    val phase: DictationPhase = DictationPhase.IDLE,
    /** Whole seconds recorded, rounded up (the server bills started units). */
    val seconds: Int = 0,
    /** The app has to show the system's microphone prompt and answer with [DictationModel.permissionAnswered]. */
    val askPermission: Boolean = false,
    val denied: Boolean = false,
    val recorderFailed: Boolean = false,
    val unavailable: Boolean = false,
    /** Transcripts inserted since the sheet opened. */
    val segments: Int = 0,
    /** A clip was sent and has no result: null, or what the button does about it. */
    val retry: Retry? = null,
    val notice: String? = null,
    val alert: String? = null,
) {
    /**
     * [CHECK]: the request may have been charged, only its key may be sent again. [TRY]: it was refused uncharged
     * (RN "Try again"). [CONFIRM]: the clip was stopped under "Stop (Free)" but is no longer free; nothing was
     * sent, and it only goes out when the user taps the button that now names the cost.
     */
    enum class Retry { CHECK, TRY, CONFIRM }

    val isPriceReady: Boolean get() = price != null && !priceLoading && !priceFailed

    val busy: Boolean get() = phase == DictationPhase.TRANSCRIBING || askPermission

    val clock: String get() = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"

    /** What stopping now would cost; null until the price is known. */
    val estimatedCost: Double? get() = price?.takeIf { isPriceReady }?.cost(seconds)

    /** RN's cost line: the rate before recording, the running total while recording. */
    val costLine: String
        get() {
            val p = price?.takeIf { isPriceReady } ?: return L.dictation.priceLoading
            val cost = p.cost(seconds)
            return when {
                phase != DictationPhase.RECORDING -> L.native.plurals.dictation.rate(n = PaidCalls.format(p.unitCost), s = p.unitSeconds)
                cost > 0 -> L.dictation.costRunning(n = PaidCalls.format(cost))
                else -> L.dictation.costRunningFree
            }
        }

    val freeLine: String?
        get() = price?.takeIf { isPriceReady && phase != DictationPhase.RECORDING && it.freeRemaining > 0 }?.let { L.dictation.freeRemaining(n = it.freeRemaining.toString(), s = it.unitSeconds.toString()) }

    val hintLine: String?
        get() = price?.takeIf { isPriceReady && phase != DictationPhase.TRANSCRIBING }?.let {
            if (segments > 0) L.dictation.inserted else L.native.plurals.dictation.stopHint(m = it.maxSeconds / 60)
        }

    val balanceLine: String? get() = balanceText?.let { L.native.dictation.balance(balance = PaidCalls.balance(it), unit = PaidCopy.POINTS) }

    /**
     * What "Give up" asks before it throws the clip away. Points are only mentioned where they may have been
     * spent: a clip that was held back and never sent, and one the server refused without charging (402, 429),
     * cost nothing.
     */
    val giveUpConfirm: String
        get() = when (retry) {
            Retry.CONFIRM -> DictationModel.GIVE_UP_NOT_SENT
            Retry.TRY -> DictationModel.GIVE_UP_NOT_CHARGED
            else -> PaidCopy.GIVE_UP_CONFIRM
        }

    /**
     * The main button. RN's "Stop" is the tap that spends points, with no confirmation; here it also says how
     * many. "Check again" sends the same clip with the same key and never charges anew.
     */
    val mainLabel: String
        get() = when {
            phase == DictationPhase.RECORDING -> estimatedCost?.let { if (it > 0) L.native.dictation.stopPoints(cost = PaidCalls.format(it)) else L.native.dictation.stopFree } ?: L.transfer.stop
            retry == Retry.CONFIRM -> estimatedCost?.let { L.native.dictation.transcribePoints(cost = PaidCalls.format(it)) } ?: L.native.dictation.transcribe
            retry == Retry.CHECK -> PaidCopy.CHECK_AGAIN
            retry == Retry.TRY -> L.aiAssist.tryAgain
            segments > 0 -> L.dictation.recordMore
            else -> L.dictation.start
        }

    val mainEnabled: Boolean
        get() = when {
            phase == DictationPhase.RECORDING -> true
            retry != null -> !busy
            else -> !busy && isPriceReady && !unavailable
        }
}

/**
 * RN `dictationModal` and `useDictationRecorder` over the SDK's `useAiTranscribe`: record, stop, and the
 * transcript goes into the editor at the caret; the sheet stays open for the next segment. Recognition runs
 * on Ecency's server: the clip is uploaded and paid for in points per started unit when Stop is tapped (no
 * confirmation step, as RN; the running cost is on screen and on the button).
 *
 * - One idempotency key per clip, stored with the clip's file name before the upload leaves and reused by
 *   every later request for it (RN keeps it in memory across "Try again" only). A lost answer or a timeout
 *   is "Check again", never a second upload by itself and never a new key.
 * - A recording starts only in the foreground, unlocked, for the editor's account. It goes on in the
 *   background (Android's `microphone` foreground service with its notification, iOS's audio background mode,
 *   [DictationSessions]); the notification's Stop holds the clip unsent (it is reachable on the lock screen). It stops and is thrown away on an account
 *   change, a logout or wipe and when the sheet (or its composer) closes: a clip that was thrown away is never
 *   uploaded, so it costs nothing. The app lock engaging (on the return after the lock timeout) or the system
 *   taking the microphone (a call) stops it and keeps the clip unsent until the user taps "Transcribe" after the
 *   unlock, or gives it up.
 * - The clip's file is deleted as soon as its request is settled, given up or interrupted, and stray files go
 *   when the sheet opens.
 */
class DictationModel internal constructor(private val env: AppEnvironment, private val editor: AiTextTarget) :
    StateModel<DictationState>(DictationState(), env.dispatchers) {
    private var flow: PaidFlow<String>? = null
    private var started = false
    private var startedAtMillis = 0L
    private var tick: Job? = null

    /** The current clip may have reached the server: it was read and handed to the transport, now or before a restart. */
    private var mayHaveBeenSent = false
    private var priceAtMillis = 0L

    /** The platform shows this recording in the background (notification and Stop); else it may not go on there. */
    private var backgroundShown = true

    /** A stopped clip that waits for the user's yes to its cost ([DictationState.Retry.CONFIRM]). */
    private var held: Pair<String, Long>? = null
    private val recorder: DictationRecorder? get() = env.dictationRecorder

    fun start() {
        if (started) return
        started = true
        val owner = editor.aiOwner ?: return
        val f = PaidFlow(env, owner, KIND, editor.paidScope, PaidRunner(env, ::send, ::parse), ::ends)
        flow = f
        setState { it.copy(owner = owner, unavailable = recorder == null) }
        // a recording never outlives the lock screen or its account
        scope.launch { env.appLock.state.collect { if (it.hidesContent) locked() } }
        // nothing visible would be left to stop it: a recording without its background notification stops on leaving
        scope.launch { env.events.collect { if (it is AppEvent.Backgrounded && !backgroundShown) holdRecording(HELD_NO_BACKGROUND) } }
        scope.launch {
            env.session.state.collect {
                if (it.username != owner) interrupt()
                // signed out or wiped: the account's clip does not stay behind for a "Check again" that can never run
                if (env.localDrafts.hasLeft(owner)) keptClip()
            }
        }
        scope.launch {
            val earlier = f.restore()
            val clip = earlier?.payload?.str("clip")
            if (earlier != null && (clip == null || withContext(dispatchers.io) { recorder?.read(clip) } == null)) {
                // the clip is gone: its request cannot be asked for again, and that is said, not hidden
                f.settle()
                setState { it.copy(alert = CLIP_LOST) }
            } else if (earlier != null) {
                mayHaveBeenSent = true
                setState { it.copy(retry = DictationState.Retry.CHECK, notice = PaidCopy.RESTORED) }
            } else {
                // a clip the lock stopped while this editor's sheet was away (README "Background media")
                env.dictationSessions.take(owner, editor.paidScope)?.let { h ->
                    if (withContext(dispatchers.io) { recorder?.read(h.clip) } != null) {
                        held = h.clip to h.millis
                        setState { it.copy(retry = DictationState.Retry.CONFIRM, seconds = ((h.millis + 999) / 1000).toInt(), alert = h.message) }
                    }
                }
            }
            recorder?.let { DictationClips.purgeOwn(it, owner, keptClip(), held?.first ?: env.dictationSessions.heldClip(owner)) }
            refresh(owner)
        }
    }

    private suspend fun refresh(owner: String) {
        val price = try {
            env.aiApi.transcribePrice(owner)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (price != null) priceAtMillis = env.nowMillis()
        setState { it.copy(priceLoading = false, price = price ?: it.price, priceFailed = price == null && it.price == null) }
        val points = try {
            env.aiApi.points(owner)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (points != null) setState { it.copy(balanceText = points) }
    }

    /** RN's "Try again" under "Could not load transcription pricing." */
    fun reloadPrice() {
        val owner = current.owner ?: return
        setState { it.copy(priceLoading = true, priceFailed = false) }
        scope.launch { refresh(owner) }
    }

    private suspend fun send(intent: PaidIntent): HttpResult {
        // a clip that cannot be read never leaves the device: that is not an unknown result
        val path = intent.payload.str("clip") ?: throw PaidNotSent("no clip")
        val audio = withContext(dispatchers.io) { recorder?.read(path) } ?: throw PaidNotSent("clip unreadable")
        mayHaveBeenSent = true
        return env.aiApi.transcribe(intent.owner, audio, intent.payload.long("duration_ms") ?: 0, intent.key, "ecency-${env.randomString()}${env.randomString()}")
    }

    private fun parse(json: kotlinx.serialization.json.JsonElement): String? =
        (json.obj()?.get("text") as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.length <= MAX_TEXT }

    /** RN keeps the clip and its key after 402 and 429 ("Try again"); other answers end as in [PaidFlow.endsIntent]. */
    private fun ends(outcome: PaidOutcome<*>): Boolean =
        if (outcome is PaidOutcome.Refused && (outcome.status == 402 || outcome.status == 429)) false else PaidFlow.endsIntent(outcome)

    /** A recording starts only in the foreground, unlocked, for the editor's account. */
    private fun mayRecord(): Boolean = env.isForeground && maySend()

    /** A stopped clip is sent (points spent) only unlocked, for the editor's account; in the background too. */
    private fun maySend(): Boolean {
        val owner = current.owner ?: return false
        return !env.appLock.current.hidesContent && env.session.current.username == owner
    }

    /** The main button: Record, Stop, or the retry of a clip without a result. */
    fun main() {
        val s = current
        when {
            s.phase == DictationPhase.RECORDING -> stop()
            s.retry == DictationState.Retry.CONFIRM -> if (!s.busy && maySend()) held?.let { (clip, millis) ->
                held = null
                env.dictationSessions.release(clip)
                setState { it.copy(retry = null) }
                submit(clip, millis)
            }
            s.retry != null -> if (!s.busy) submit()
            else -> record()
        }
    }

    private fun record() {
        val s = current
        val r = recorder ?: return
        if (s.phase != DictationPhase.IDLE || s.busy || !s.isPriceReady || !mayRecord()) return
        setState { it.copy(denied = false, recorderFailed = false, seconds = 0, alert = null) }
        if (!r.hasPermission()) {
            setState { it.copy(askPermission = true) }
            return
        }
        begin(r)
    }

    /** The answer to the system's microphone prompt. */
    fun permissionAnswered(granted: Boolean) {
        if (!current.askPermission) return
        setState { it.copy(askPermission = false, denied = !granted) }
        val r = recorder ?: return
        // the prompt took the app out of the foreground for a moment: the rules are checked again
        if (granted && mayRecord() && current.phase == DictationPhase.IDLE) begin(r)
    }

    private fun begin(r: DictationRecorder) {
        val owner = current.owner
        val ok = owner != null && runCatching { r.start(DictationClips.tag(owner)) }.getOrDefault(false)
        if (!ok) {
            setState { it.copy(recorderFailed = true) }
            return
        }
        startedAtMillis = env.nowMillis()
        setState { it.copy(phase = DictationPhase.RECORDING, seconds = 0, notice = null) }
        backgroundShown = env.dictationSessions.started(this)
        val max = current.price?.maxSeconds ?: DEFAULT_MAX_SECONDS
        tick?.cancel()
        tick = scope.launch {
            while (true) {
                delay(TICK_MILLIS)
                val elapsed = (env.nowMillis() - startedAtMillis).coerceAtLeast(0)
                setState { it.copy(seconds = ((elapsed + 999) / 1000).toInt()) }
                // RN: a second before the server's cap, so the clip is not refused after its upload
                if (elapsed >= (max - 1) * 1000L) {
                    stop()
                    break
                }
            }
        }
    }

    private fun stop() {
        if (current.phase != DictationPhase.RECORDING) return
        val r = recorder ?: return
        tick?.cancel()
        env.dictationSessions.ended(this)
        // wall clock, taken before the recorder flushes: the figure the user saw is the one that is billed
        val elapsed = (env.nowMillis() - startedAtMillis).coerceAtLeast(1)
        val path = runCatching { r.stop() }.getOrNull()
        if (path == null) {
            runCatching { r.cancel() }
            setState { it.copy(phase = DictationPhase.IDLE, recorderFailed = true) }
            return
        }
        // stopping is the moment points are spent: only for the account that owns the editor, signed in right now
        if (!maySend()) {
            r.delete(path)
            setState { it.copy(phase = DictationPhase.IDLE, seconds = 0) }
            return
        }
        val seconds = ((elapsed + 999) / 1000).toInt()
        setState { it.copy(seconds = seconds) }
        val owner = current.owner
        // "Stop (Free)" on a price that has been on screen for a while: the free units may be used up by now. The
        // price is read again, and a clip that stopped being free is not uploaded under that label: it waits for a
        // tap on a button that names the cost.
        if (owner != null && current.price?.cost(seconds) == 0.0 && env.nowMillis() - priceAtMillis > PRICE_MAX_AGE_MILLIS) {
            setState { it.copy(phase = DictationPhase.TRANSCRIBING) }
            scope.launch {
                refresh(owner)
                val cost = current.price?.cost(seconds)
                if (cost == 0.0) {
                    setState { it.copy(phase = DictationPhase.IDLE) }
                    submit(path, elapsed)
                } else {
                    held = path to elapsed
                    setState {
                        it.copy(phase = DictationPhase.IDLE, retry = DictationState.Retry.CONFIRM, alert = cost?.let { c -> L.native.dictation.noLongerFreeCost(cost = PaidCalls.format(c)) } ?: L.native.dictation.noLongerFree)
                    }
                }
            }
            return
        }
        submit(path, elapsed)
    }

    /** Sends the clip: a new one ([path]), or the one that is waiting for its result. */
    private fun submit(path: String? = null, durationMillis: Long = 0) {
        val f = flow ?: return
        val r = recorder ?: return
        if (current.phase == DictationPhase.TRANSCRIBING) return
        setState { it.copy(phase = DictationPhase.TRANSCRIBING, alert = null) }
        scope.launch {
            if (path != null) {
                // a new clip is checked before it becomes a paid request: unreadable or over the size the server takes
                mayHaveBeenSent = false
                if (withContext(dispatchers.io) { r.read(path) } == null) {
                    r.delete(path)
                    setState { it.copy(phase = DictationPhase.IDLE, seconds = 0, alert = NOT_READ) }
                    return@launch
                }
            }
            val outcome = f.ask(
                payload = { buildJsonObject { put("clip", path.orEmpty()); put("duration_ms", durationMillis) } },
                onPending = { setState { it.copy(notice = PaidCopy.PENDING) } },
            )
            val clip = f.intent?.payload?.str("clip")
            when (outcome) {
                is PaidOutcome.Delivered -> if (!f.ownerSignedIn()) {
                    // the payer switched away while it ran: the clip and its key stay for their "Check again"
                    setState { it.copy(phase = DictationPhase.IDLE, retry = DictationState.Retry.CHECK, alert = PaidCopy.otherAccount(f.owner)) }
                } else {
                    path?.let(r::delete)
                    DictationClips.purgeOwn(r, f.owner, null)
                    val text = outcome.value
                    when {
                        // RN: a silent clip still costs points, so it is said
                        Js.trim(text).isEmpty() -> setState { it.copy(phase = DictationPhase.IDLE, seconds = 0, retry = null, notice = null, alert = EMPTY) }
                        editor.insertDictation(text) -> setState { it.copy(phase = DictationPhase.IDLE, seconds = 0, retry = null, notice = null, segments = it.segments + 1) }
                        else -> setState { it.copy(phase = DictationPhase.IDLE, seconds = 0, retry = null, notice = null, alert = PaidCopy.otherAccount(f.owner)) }
                    }
                    refresh(f.owner)
                }
                is PaidOutcome.Pending -> setState { it.copy(phase = DictationPhase.IDLE, retry = DictationState.Retry.CHECK, notice = PaidCopy.PENDING) }
                PaidOutcome.Unknown -> setState {
                    it.copy(phase = DictationPhase.IDLE, retry = DictationState.Retry.CHECK, notice = if (f.stored) PaidCopy.UNKNOWN else PaidCopy.UNKNOWN + " " + PaidCopy.NOT_STORED)
                }
                is PaidOutcome.Refused -> {
                    val kept = f.intent != null
                    if (!kept) DictationClips.purgeOwn(r, f.owner, null)
                    val alert = when (outcome.status) {
                        PaidCalls.ACCOUNT_CHANGED -> PaidCopy.otherAccount(f.owner)
                        PaidCalls.NOT_SENT -> if (mayHaveBeenSent) CLIP_LOST else NOT_READ
                        401, 403 -> PaidCopy.SIGN_IN_AGAIN
                        402 -> L.dictation.errorInsufficientPoints
                        429 -> L.dictation.errorRateLimited
                        400 -> L.dictation.errorTooLong
                        else -> FAILED
                    }
                    val retry = when {
                        !kept -> null
                        outcome.status == 402 || outcome.status == 429 -> DictationState.Retry.TRY
                        else -> DictationState.Retry.CHECK
                    }
                    setState { it.copy(phase = DictationPhase.IDLE, seconds = if (kept) it.seconds else 0, retry = retry, notice = if (kept) it.notice else null, alert = alert) }
                    if (!kept) refresh(f.owner)
                }
            }
            if (clip != null && (f.intent == null || env.localDrafts.hasLeft(f.owner))) r.delete(clip)
        }
    }

    /** "Give up" after its confirmation: the clip and its key are dropped. */
    fun giveUp() {
        val f = flow ?: return
        if (current.busy || current.retry == null) return
        held?.let { (clip, _) ->
            // never sent: thrown away without any charge
            held = null
            env.dictationSessions.release(clip)
            recorder?.delete(clip)
            setState { it.copy(retry = null, notice = null, seconds = 0) }
            return
        }
        scope.launch {
            val clip = f.intent?.payload?.str("clip")
            f.settle()
            clip?.let { recorder?.delete(it) }
            setState { it.copy(retry = null, notice = null, seconds = 0) }
        }
    }

    /**
     * The app locked, left the foreground, another account signed in, or the sheet is closing: a recording in
     * progress stops and is thrown away. Nothing is uploaded, so nothing is charged.
     */
    fun interrupt() {
        tick?.cancel()
        env.dictationSessions.ended(this)
        if (current.phase == DictationPhase.RECORDING) runCatching { recorder?.cancel() }
        if (current.phase == DictationPhase.RECORDING || current.askPermission) {
            setState { it.copy(phase = if (it.phase == DictationPhase.RECORDING) DictationPhase.IDLE else it.phase, askPermission = false, seconds = 0) }
        }
    }

    fun clearAlert() = setState { it.copy(alert = null) }

    /** Stop from outside the sheet (the background notification): the same as the sheet's Stop while recording. */
    fun stopRecording() {
        if (current.phase == DictationPhase.RECORDING) stop()
    }

    internal fun backgroundUnavailable() {
        backgroundShown = false
        if (!env.isForeground) holdRecording(HELD_NO_BACKGROUND)
    }

    /** The lock engaged: a recording stops and waits, unsent, behind it; a permission prompt is dropped. */
    private fun locked() {
        if (current.phase == DictationPhase.RECORDING) holdRecording(HELD_LOCKED) else interrupt()
    }

    /**
     * Stops a recording and keeps its clip unsent ([DictationState.Retry.CONFIRM]): it goes out only when the user taps
     * "Transcribe" (unlocked, the owner signed in), or is deleted by "Give up". Nothing is charged until then.
     */
    internal fun holdRecording(message: String) {
        if (current.phase != DictationPhase.RECORDING) return
        val r = recorder ?: return
        tick?.cancel()
        env.dictationSessions.ended(this)
        val elapsed = (env.nowMillis() - startedAtMillis).coerceAtLeast(1)
        val path = runCatching { r.stop() }.getOrNull()
        if (path == null) {
            runCatching { r.cancel() }
            setState { it.copy(phase = DictationPhase.IDLE, seconds = 0, recorderFailed = true) }
            return
        }
        held?.let { (old, _) -> r.delete(old); env.dictationSessions.release(old) }
        held = path to elapsed
        current.owner?.let { env.dictationSessions.hold(DictationSessions.Held(it, editor.paidScope, path, elapsed, message)) }
        setState { it.copy(phase = DictationPhase.IDLE, seconds = ((elapsed + 999) / 1000).toInt(), retry = DictationState.Retry.CONFIRM, alert = message) }
    }

    override fun close() {
        // the lock cover may close the sheet before the lock's own collector ran (Android leaves the composer out
        // behind it): a recording is held for the next sheet, never thrown away by the lock
        if (current.phase == DictationPhase.RECORDING && env.appLock.current.hidesContent) holdRecording(HELD_LOCKED) else interrupt()
        // a clip whose request has no answer stays for "Check again"; everything else goes
        // and a clip the lock held stays for the next sheet of this editor
        if (started) runCatching { val f = flow; val r = recorder; if (f != null && r != null) DictationClips.purgeOwn(r, f.owner, keptClip(), env.dictationSessions.heldClip(f.owner)) }
        super.close()
    }

    /**
     * The clip of this sheet's unanswered request, the one file a purge keeps. For an account that left the device
     * (logout, wipe) there is none: its clip is deleted here, as the teardown deleted its stored request.
     */
    private fun keptClip(): String? {
        val f = flow ?: return null
        val clip = f.intent?.payload?.str("clip") ?: return null
        if (!env.localDrafts.hasLeft(f.owner)) return clip
        runCatching { recorder?.delete(clip) }
        return null
    }

    companion object {
        const val KIND = "transcribe"

        /** A price older than this is read again before a clip is sent as free. */
        const val PRICE_MAX_AGE_MILLIS = 60_000L

        /** RN DEFAULT_MAX_SECONDS. */
        const val DEFAULT_MAX_SECONDS = 300
        const val TICK_MILLIS = 250L

        /** The server stores 20000 characters of a transcript. */
        const val MAX_TEXT = 100_000

        val TITLE: String get() = L.dictation.title
        val HELD_LOCKED: String get() = L.native.dictation.heldLocked
        val HELD_NO_BACKGROUND: String get() = L.native.dictation.heldNoBackground
        val HELD_STOPPED: String get() = L.native.dictation.heldStopped
        val HELD_FOCUS_LOST: String get() = L.native.dictation.heldFocusLost
        val HELD_INTERRUPTED: String get() = L.native.dictation.heldInterrupted
        val DENIED: String get() = L.dictation.denied
        val RECORDER_ERROR: String get() = L.dictation.errorRecorder
        val PRICE_ERROR: String get() = L.dictation.priceError
        val EMPTY: String get() = L.dictation.errorEmpty
        val FAILED: String get() = L.dictation.errorFailed
        val UNAVAILABLE: String get() = L.native.dictation.unavailable
        val NOT_READ: String get() = L.native.dictation.notRead
        val GIVE_UP_NOT_SENT: String get() = L.native.dictation.giveUpNotSent
        val GIVE_UP_NOT_CHARGED: String get() = L.native.dictation.giveUpNotCharged
        val CLIP_LOST: String get() = L.native.dictation.clipLost
    }
}

/** What a platform shows while a dictation records, so it can go on in the background (README "Background media"). */
interface DictationHost {
    /**
     * A recording started (always in the foreground). Android starts its `microphone` foreground service with a
     * visible notification and a Stop action; iOS keeps the session active (the system shows its recording indicator).
     * False when the platform cannot show that (the service was refused, notifications are off): the recording then
     * stops and is held when the app leaves the foreground, as nothing visible would be left to stop it.
     */
    fun recordingStarted(): Boolean

    /** The recording ended, for any reason: the service and its notification go. */
    fun recordingEnded()
}

/**
 * The one dictation that records, app-wide, for the platform's background session and its notification. The
 * notification's Stop and the system taking the microphone (a call) stop it and keep the clip unsent until the user
 * confirms in the app (a notification can be tapped on the lock screen, so it never spends points). Main thread only.
 */
class DictationSessions {
    var host: DictationHost? = null
    private var active: DictationModel? = null

    val isRecording: Boolean get() = active != null

    /** False when the platform could not show its background recording ([DictationHost.recordingStarted]). */
    internal fun started(model: DictationModel): Boolean {
        active = model
        val h = host ?: return true
        return runCatching { h.recordingStarted() }.getOrDefault(false)
    }

    /** The platform's background session failed after it started (Android: the foreground service was refused). */
    fun backgroundUnavailable() {
        active?.backgroundUnavailable()
    }

    internal fun ended(model: DictationModel) {
        if (active !== model) return
        active = null
        runCatching { host?.recordingEnded() }
    }

    /**
     * The notification's Stop action. It can be tapped on the lock screen without unlocking, so it never spends
     * points: the recording stops and its clip waits unsent ([DictationModel.HELD_STOPPED]) until the user taps
     * "Transcribe (N Points)" in the app.
     */
    fun stopFromNotification() {
        active?.holdRecording(DictationModel.HELD_STOPPED)
    }

    /** A call or another app took the microphone: the clip so far waits for the user's yes. */
    fun systemInterrupted() {
        active?.holdRecording(DictationModel.HELD_INTERRUPTED)
    }

    /** The system took the audio focus from the recording (a call, an alarm): the clip so far waits for the user's yes. */
    fun audioFocusLost() {
        active?.holdRecording(DictationModel.HELD_FOCUS_LOST)
    }

    /** Teardown (accounts left the device): the recording stops and is thrown away, a held clip is forgotten. */
    fun stopAll() {
        active?.interrupt()
        held = null
    }

    /**
     * A clip the lock (or a call) stopped, unsent. It outlives its sheet (Android leaves the composer out of the
     * screen behind the lock cover, which closes the sheet): the next dictation sheet of the same account and editor
     * takes it back, with "Transcribe" and "Give up". Memory only: after a restart it is a stray file, which the next
     * sheet of its account deletes.
     */
    internal class Held(val owner: String, val scope: String, val clip: String, val millis: Long, val message: String)

    internal var held: Held? = null
        private set

    internal fun hold(clip: Held) {
        held = clip
    }

    internal fun take(owner: String, scope: String): Held? = held?.takeIf { it.owner == owner && it.scope == scope }?.also { held = null }

    /** The held clip of [owner], which a purge of that account's stray clips keeps. */
    internal fun heldClip(owner: String): String? = held?.takeIf { it.owner == owner }?.clip

    internal fun release(clip: String) {
        if (held?.clip == clip) held = null
    }
}

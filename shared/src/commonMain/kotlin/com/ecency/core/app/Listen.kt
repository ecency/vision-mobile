package com.ecency.core.app

import com.ecency.core.api.EcencyHttpException
import com.ecency.core.api.ecency.ListenStatus
import com.ecency.core.api.ecency.ListenTrack
import com.ecency.core.api.ecency.ListenUnlockException
import com.ecency.core.api.model.Post
import com.ecency.core.api.model.PostKey
import com.ecency.core.l10n.L
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * RN `src/utils/listen.ts`: the pure parts of Listen (natural-voice audio of a post, unlocked once per reader with
 * Points, made on the server). Kept in step with RN and the web player; `ListenGoldens` pins them against RN.
 */
object Listen {
    val SPEEDS = listOf(1.0, 1.25, 1.5, 1.75, 2.0, 2.5)

    /** Generation takes about a quarter of the audio's length; rounded up a little (RN `GENERATION_PER_AUDIO_SECOND`). */
    private const val GENERATION_PER_AUDIO_SECOND = 0.3

    /** The speed after [speed], back to 1x after the fastest (an unknown speed goes to the first, as RN's indexOf -1). */
    fun nextSpeed(speed: Double): Double = SPEEDS[(SPEEDS.indexOf(speed) + 1) % SPEEDS.size]

    /** "1x", "1.25x": a speed as RN prints a JS number. */
    fun speedLabel(speed: Double): String = "${PaidCalls.format(speed)}x"

    /** m:ss for a number of seconds; 0:00 for anything that is not a time. */
    fun formatTime(seconds: Double): String {
        if (!seconds.isFinite() || seconds < 0) return "0:00"
        val s = floor(seconds).toLong()
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }

    /** The reader's interface language if the post has a track in it, else the main one. */
    fun pickTrack(tracks: List<ListenTrack>, uiLanguage: String): ListenTrack? {
        val ui = uiLanguage.split('-')[0]
        return tracks.firstOrNull { it.lang == ui } ?: tracks.firstOrNull()
    }

    /** Minutes the audio of these languages takes to generate, at least one. */
    fun preparingMinutes(seconds: List<Double>): Int = max(1, jsRound(seconds.sum() * GENERATION_PER_AUDIO_SECOND / 60))

    /** Minutes of audio, from the analysis when there is one, else from the word count. */
    fun listenMinutes(seconds: List<Double>, words: Int): Int =
        if (seconds.isNotEmpty()) max(1, jsRound(seconds.max() / 60)) else max(1, jsRound(words / 150.0))

    /** JS Math.round: halves go up, also for negatives. */
    private fun jsRound(x: Double): Int = floor(x + 0.5).roundToInt()

    // Thai, Bengali, Hebrew, Tamil, Telugu: languages the voice cannot read.
    private val UNSUPPORTED = listOf(0x0e00..0x0e7f, 0x0980..0x09ff, 0x0590..0x05ff, 0x0b80..0x0bff, 0x0c00..0x0c7f)
    private val HAN = listOf(0x3400..0x4dbf, 0x4e00..0x9fff, 0xf900..0xfaff)

    // Japanese and Korean are supported and also use Han characters; any kana or hangul marks them.
    private val KANA_HANGUL = listOf(0x3040..0x30ff, 0x1100..0x11ff, 0x3130..0x318f, 0xac00..0xd7af)
    private val MARKUP = Regex("!?\\[[^\\]]*\\]\\([^)]*\\)")
    private val URL = Regex("https?://\\S+")
    /**
     * Letters outside the BMP: Kotlin/Native's regex has no Unicode categories there and `Char.isLetter` sees one half
     * of a pair, so the blocks that are letters are listed (historic scripts, kana supplements, mathematical letters,
     * Mende and Adlam, the CJK extensions). Emoji and other symbols are not letters, as in JS `\p{L}`.
     */
    private val SUPPLEMENTARY_LETTERS = listOf(0x10000..0x18AFF, 0x1B000..0x1B2FF, 0x1D400..0x1D7CB, 0x1E800..0x1E94F, 0x20000..0x323AF)

    private fun inRanges(cp: Int, ranges: List<IntRange>) = ranges.any { cp in it }

    /**
     * True when most of a post's letters are in a script the voice cannot read (Thai, Bengali, Hebrew, Tamil, Telugu,
     * or Chinese). Links and image markup are left out first. Only a first guess: the server's analysis overrides it.
     * Walks code points as RN's `Array.from` does (a surrogate pair is one character, a lone surrogate is not a letter).
     * Same answer on both platforms; outside the BMP the letter blocks are a list (see [SUPPLEMENTARY_LETTERS]).
     */
    fun isUnreadableScript(body: String): Boolean {
        val text = body.replace(MARKUP, " ").replace(URL, " ")
        var letters = 0
        var unsupported = 0
        var han = 0
        var kanaHangul = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val cp: Int
            val letter: Boolean
            if (c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) {
                cp = 0x10000 + ((c.code - 0xD800) shl 10) + (text[i + 1].code - 0xDC00)
                letter = SUPPLEMENTARY_LETTERS.any { cp in it }
                i += 2
            } else {
                cp = c.code
                letter = c.isLetter()
                i += 1
            }
            if (!letter) continue
            letters++
            when {
                inRanges(cp, UNSUPPORTED) -> unsupported++
                inRanges(cp, HAN) -> han++
                inRanges(cp, KANA_HANGUL) -> kanaHangul++
            }
        }
        if (letters == 0) return false
        val chinese = if (kanaHangul > 0) 0 else han
        return (unsupported + chinese).toDouble() / letters > 0.5
    }

    /** "English + German": the analysed languages by name, as RN `languageDisplayName` without a UI language. */
    fun languagesLabel(langs: List<String>): String = langs.joinToString(" + ") { Languages.name(Languages.normalize(it)) }

    fun languageName(lang: String): String = Languages.name(Languages.normalize(lang))
}

// ---------------------------------------------------------------- the player

/**
 * RN `listenAudio.tsx` as an immutable value: the platform player (Media3 behind a media session on Android,
 * AVPlayer with the lock screen on iOS) reports to it and acts on what it returns. The platform stream exists from
 * the first play on and stays while paused, so the lock-screen controls stay too; before that nothing is loaded.
 */
data class ListenPlayerState(
    val lang: String,
    val duration: Double,
    val started: Boolean = false,
    val paused: Boolean = true,
    val loading: Boolean = false,
    val time: Double = 0.0,
    val speed: Double = 1.0,
    /** The slider is being dragged: shown instead of [time]. */
    val seeking: Double? = null,
    /** Where to go once the next source loads (a renewed URL, a switched track). */
    val resume: Double? = null,
    /** URL renewals since playback last proved one worked. */
    val refreshes: Int = 0,
    val failedAt: Double = 0.0,
    /** The URL could not be renewed: "The audio could not be loaded." with "Try again". */
    val failed: Boolean = false,
) {
    val showSpinner: Boolean get() = loading && !paused
    val shownTime: Double get() = seeking ?: time
    val timeLabel: String get() = Listen.formatTime(shownTime)
    val durationLabel: String get() = Listen.formatTime(duration)
    val speedLabel: String get() = Listen.speedLabel(speed)
    /** Slider maximum (RN `Math.max(1, duration)`). */
    val sliderMax: Double get() = max(1.0, duration)

    /** The track of [lang] among [tracks], else the first (RN `tracks.find(...) ?? tracks[0]`). */
    fun track(tracks: List<ListenTrack>): ListenTrack? = tracks.firstOrNull { it.lang == lang } ?: tracks.firstOrNull()

    /** Play or pause; the first play mounts the player. */
    fun toggle(): ListenPlayerState = copy(started = true, paused = !paused)

    /** Paused from outside: another player started, the screen closed. */
    fun stopped(): ListenPlayerState = copy(paused = true)

    fun loadStarted(): ListenPlayerState = copy(loading = true)

    fun buffering(isBuffering: Boolean): ListenPlayerState = copy(loading = isBuffering)

    /** The source loaded: the failure is over, the real length is known, and a pending [resume] is applied. */
    fun loaded(streamDuration: Double): Step = Step(
        copy(loading = false, failed = false, duration = if (streamDuration.isFinite() && streamDuration > 0) streamDuration else duration, resume = null),
        seekTo = resume,
    )

    /**
     * Progress. Playing [RENEWAL_PROVEN_SECONDS] past the point that failed proves a renewal worked, so the count of
     * renewals starts over: a long session can outlive many URLs, but a file that loads and then fails at the same
     * spot still stops after [MAX_URL_REFRESHES].
     */
    fun progressed(currentTime: Double): ListenPlayerState {
        val reset = refreshes > 0 && currentTime - failedAt > RENEWAL_PROVEN_SECONDS
        return copy(time = currentTime, refreshes = if (reset) 0 else refreshes)
    }

    /**
     * The player itself started or stopped (the lock screen, a notification button, audio focus). Not while loading or
     * seeking: the player reports "not playing" then too, and following it would pause before the start.
     */
    fun playbackChanged(isPlaying: Boolean, isSeeking: Boolean = false): ListenPlayerState =
        if (loading || isSeeking) this else copy(paused = !isPlaying, started = started || isPlaying)

    fun ended(): Step = Step(copy(paused = true, time = 0.0), seekTo = 0.0)

    /** Most likely the signed URL expired: renew it and pick up here, a few times at most. */
    fun error(): Pair<ListenPlayerState, Boolean> =
        if (refreshes >= MAX_URL_REFRESHES) copy(paused = true, failed = true) to false
        else copy(refreshes = refreshes + 1, failedAt = time, resume = time) to true

    /** "Try again": the count starts over. The caller renews. */
    fun retry(): ListenPlayerState = copy(failed = false, refreshes = 0, resume = time)

    /** Fresh URLs are on their way (true), or could not be had (false). */
    fun renewed(ok: Boolean): ListenPlayerState = if (ok) copy(failed = false) else copy(paused = true, failed = true)

    /** Another language: from its start. Null when it is the one playing. */
    fun switchTrack(next: String, current: ListenTrack?): ListenPlayerState? =
        if (next == current?.lang) null else copy(lang = next, resume = 0.0, time = 0.0)

    fun nextSpeed(): ListenPlayerState = copy(speed = Listen.nextSpeed(speed))

    fun drag(value: Double): ListenPlayerState = copy(seeking = value)

    /** The slider was let go: the player seeks there. */
    fun dragEnded(value: Double): Step = Step(copy(seeking = null, time = value), seekTo = value)

    data class Step(val state: ListenPlayerState, val seekTo: Double?)

    companion object {
        /** A signed URL that keeps failing is really gone: stop asking for fresh ones. */
        const val MAX_URL_REFRESHES = 3
        const val RENEWAL_PROVEN_SECONDS = 30.0

        fun initial(tracks: List<ListenTrack>, uiLanguage: String): ListenPlayerState {
            val track = Listen.pickTrack(tracks, uiLanguage)
            return ListenPlayerState(lang = track?.lang.orEmpty(), duration = track?.seconds ?: 0.0)
        }
    }
}

// ---------------------------------------------------------------- the bar

/** What the bar asks the reader after an unlock attempt. */
sealed interface ListenAlert {
    /** 402: "Not enough Points", with "Get Points" (RN opens the Boost screen). */
    data class NotEnoughPoints(val required: Double, val available: Double) : ListenAlert {
        val message: String get() = L.native.listen.message(required = PaidCalls.format(required), available = PaidCalls.format(available))
    }

    /** "Could not unlock the audio, please try again". */
    data object UnlockFailed : ListenAlert
}

enum class ListenButton { NONE, LOGIN, LOADING, UNLOCK }

data class ListenBarState(
    /** Comments, posts without a body, and posts the voice cannot read show nothing (RN returns null). */
    val hidden: Boolean = true,
    /** Null until the server answered for this reader. */
    val status: ListenStatus? = null,
    val signedIn: Boolean = false,
    val words: Int = 0,
    /** An unlock is on its way. */
    val busy: Boolean = false,
    /** The confirmation is showing for this many Points (an unlock is never sent on a single tap). */
    val confirmCost: Double? = null,
    val alert: ListenAlert? = null,
) {
    val view: ListenStatus get() = status ?: ListenStatus.EMPTY

    /** The ready tracks of an unlocked post: the player shows instead of the bar. */
    val tracks: List<ListenTrack> get() = if (view.unlocked && view.status == ListenStatus.READY) view.tracks else emptyList()
    val showsPlayer: Boolean get() = !hidden && tracks.isNotEmpty()
    val preparing: Boolean get() = view.unlocked && view.preparing
    val minutes: Int get() = Listen.listenMinutes(view.languages.map { it.seconds }, words)

    /** "English · ~3 min", plus "Audio could not be made, your Points were refunded" for a failed job. */
    val subtitle: String
        get() = buildString {
            val langs = Listen.languagesLabel(view.languages.map { it.lang })
            if (langs.isNotEmpty()) append(langs).append(" · ")
            append(L.native.listen.aboutMinutes(minutes = minutes.toString()))
            if (view.status == ListenStatus.FAILED && !view.unlocked) append(" · ").append(L.native.listen.failedRefunded)
        }

    val button: ListenButton
        get() = when {
            preparing -> ListenButton.NONE
            !signedIn -> ListenButton.LOGIN
            status == null -> ListenButton.LOADING
            !view.unlocked -> ListenButton.UNLOCK
            else -> ListenButton.NONE
        }

    val unlockLabel: String get() = L.native.listen.unlockLabel(cost = PaidCalls.format(view.cost))

    /** RN `PreparingState`: the server's progress once the worker has the job, else the place in line, else an estimate. */
    val preparingText: String
        get() {
            val v = view
            val progress = v.progress ?: 0.0
            if (progress > 0) return L.native.listen.preparingAudio(preparingPercent = preparingPercent.toString())
            val ahead = v.queuedAhead
            return when {
                ahead != null && ahead > 0 -> L.native.listen.waitingInLineAhead(ahead = ahead.toString())
                ahead != null -> L.native.listen.nextInLine
                v.languages.isNotEmpty() -> L.native.listen.preparingAboutMinutes(minutes = (Listen.preparingMinutes(v.languages.map { it.seconds })).toString())
                else -> L.native.listen.preparingAudio2
            }
        }

    /** The progress bar's share, never 100 while it is not ready (RN `Math.min(99, Math.round(progress * 100))`); null shows a spinner. */
    val preparingPercent: Int? get() = view.progress?.takeIf { it > 0 }?.let { minOf(99, floor(it * 100 + 0.5).toInt()) }

    val confirmText: String? get() = confirmCost?.let { L.native.listen.spendConfirm(cost = PaidCalls.format(it)) }
}

/**
 * One access token renewal per account at a time, whoever asks (the Listen bar's status, its unlock, its player,
 * on any screen): a second caller waits for the first one's answer instead of spending the refresh token again.
 * The renewal itself is [com.ecency.core.auth.AccountManager.refreshSession], which never writes for an account that
 * left while it ran (removal marks); on top of that a token is handed out only while its account is still the
 * current one (RN `useRefreshAccessToken`'s `isCurrent`).
 */
class SessionRenewals internal constructor(
    private val env: AppEnvironment,
    private val renew: suspend (String) -> String? = { env.accounts.refreshSession(it) },
) {
    private val lock = Mutex()
    private val running = mutableMapOf<String, Deferred<String?>>()

    /** Exchanges made so far (tests). */
    var started = 0
        private set

    /**
     * [com.ecency.core.auth.AccountManager.refreshSession] for [username], joined by every caller while one runs.
     * Throws what it throws (an expired session that cannot be renewed) to every caller.
     */
    @Throws(Exception::class)
    suspend fun exchange(username: String): String? {
        var made = false
        val job = lock.withLock {
            running[username] ?: env.background.async(start = CoroutineStart.LAZY) {
                try {
                    renew.invoke(username)
                } finally {
                    lock.withLock { running.remove(username) }
                }
            }.also {
                running[username] = it
                started++
                made = true
            }
        }
        if (made) job.start()
        return job.await()
    }

    /**
     * The renewed token for a screen that was refused with it (RN `useRefreshAccessToken`), or null when it could not be
     * renewed (expired, offline) or the account is no longer the current one: nothing is retried with it then.
     */
    suspend fun renew(username: String): String? {
        val token = try {
            exchange(username)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return null
        val current = withContext(env.dispatchers.main) { env.session.current.username }
        return token.takeIf { current == username }
    }
}

/** How an unlock ended. */
sealed interface UnlockOutcome {
    data class Delivered(val status: ListenStatus) : UnlockOutcome

    /** A definite refusal (402 not enough Points, 401 after a renewal that did not help, ...). */
    data class Refused(val status: Int, val required: Double? = null, val available: Double? = null) : UnlockOutcome

    /** No answer, or one that says nothing about the charge: the key is kept. */
    data object Lost : UnlockOutcome

    /** Nothing left the device (another account is signed in). */
    data object NotSent : UnlockOutcome
}

/**
 * The unlocks on their way and their keys, app-wide, per account and post: a reopened screen, or a reader switching
 * back, joins the request already on its way instead of spending a second key, and a lost answer leaves the key for
 * the next tap from any screen. A key goes when the outcome is known (delivered, or a 4xx). Main thread only.
 */
class ListenUnlocks internal constructor(private val env: AppEnvironment) {
    private val keys = mutableMapOf<String, String>()
    private val running = mutableMapOf<String, kotlinx.coroutines.Deferred<UnlockOutcome>>()
    private val mutableDelivered = kotlinx.coroutines.flow.MutableSharedFlow<Pair<String, ListenStatus>>(extraBufferCapacity = 16)

    /** Every delivered unlock (id, new status): every open bar of that account and post shows it, not only the one that asked. */
    val delivered: kotlinx.coroutines.flow.SharedFlow<Pair<String, ListenStatus>> = mutableDelivered

    fun key(id: String): String? = keys[id]

    fun running(id: String): kotlinx.coroutines.Deferred<UnlockOutcome>? = running[id]

    internal fun start(id: String, request: suspend (String) -> UnlockOutcome): kotlinx.coroutines.Deferred<UnlockOutcome> {
        val key = keys.getOrPut(id) { env.idempotencyKey() }
        val job = env.background.async(start = CoroutineStart.LAZY) {
            val outcome = try {
                request(key)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UnlockOutcome.Lost
            }
            when (outcome) {
                is UnlockOutcome.Delivered -> {
                    keys.remove(id)
                    mutableDelivered.tryEmit(id to outcome.status)
                }
                is UnlockOutcome.Refused -> if (outcome.status in 400..499) keys.remove(id)
                else -> Unit
            }
            running.remove(id)
            outcome
        }
        running[id] = job
        job.start()
        return job
    }

    companion object {
        fun id(account: String, author: String, permlink: String) = "$account|$author/$permlink"
    }
}

/**
 * RN `listenBar.tsx` for one post screen: the status (polled while the audio is made), the confirmed unlock and its
 * errors, the reader's token renewed on a 401, and fresh URLs for the player. Held by the screen (its stack entry),
 * closed with it.
 */
class ListenModel internal constructor(private val env: AppEnvironment, val key: PostKey) :
    StateModel<ListenBarState>(ListenBarState(), env.dispatchers) {
    /** The post the bar is for (its latest version). */
    var post: Post? = null
        private set
    private var unreadable = false

    /** The script guess for the current body is in (the bar stays hidden until it is). */
    private var guessed = false
    private var comment = false
    private var statusJob: Job? = null

    /** The reader's unlock key for this post while its outcome is unknown ([ListenUnlocks]), else null (tests). */
    internal val idempotencyKey: String?
        get() = reader?.let { r -> post?.let { env.listenUnlocks.key(ListenUnlocks.id(r, it.author, it.permlink)) } }

    /** RN `statusRenewedRef`: one status renewal at a time, allowed again after a failed (or cancelled) one. */
    private var statusRenewed = false

    /** The status renewal on its way: never cancelled by a reload, which waits for it instead. */
    private var renewalJob: Job? = null

    /** A reload was asked for while [renewalJob] ran: it runs once the renewal is over. */
    private var reloadAfterRenewal = false

    /** The app is in the background: no polling (React Query's focus manager); resumed on the foreground. */
    private var inBackground = false

    /** The last status load failed (RN refetches on focus and reconnect; here [onForeground] does). */
    private var statusFailed = false
    private var reader: String? = null
    private var watching = false

    /** Shows the bar for [post] (once per edit). */
    fun start(post: Post) {
        val previous = this.post
        if (previous != null && previous.updated == post.updated && previous.body == post.body) return
        this.post = post
        comment = !post.parentAuthor.isNullOrEmpty() || post.depth > 0
        if (previous?.body != post.body) guessed = false
        setState { it.withHidden() }
        scope.launch {
            val body = post.body
            val (words, cannotRead) = withContext(env.dispatchers.render) {
                val (cjk, other) = Speech.wordCount(Speech.plainText(body))
                (cjk + other) to Listen.isUnreadableScript(body)
            }
            if (this@ListenModel.post?.body != body) return@launch
            unreadable = cannotRead
            guessed = true
            setState { it.copy(words = words).withHidden() }
        }
        if (!watching) {
            watching = true
            // the reader: known once the vault was read; every change asks for that reader's status
            scope.launch {
                env.session.state.filter { !it.isLoading }.map { it.username }.distinctUntilChanged().collect(::accountChanged)
            }
            // an unlock delivered to another open bar of this post for this reader is this bar's status too
            scope.launch {
                env.listenUnlocks.delivered.collect { (id, status) ->
                    val r = reader ?: return@collect
                    val p = this@ListenModel.post ?: return@collect
                    if (id != ListenUnlocks.id(r, p.author, p.permlink) || current.status?.unlocked == true && !status.unlocked) return@collect
                    setState { it.copy(busy = false, status = status).withHidden() }
                    if (status.preparing) loadStatus(POLL_MILLIS)
                }
            }
        } else if (started) {
            // an edit: the status follows the post's new version (`updated`)
            loadStatus()
        }
    }

    /** The session was read and the status asked for at least once. */
    private val started: Boolean get() = statusJob != null

    private fun ListenBarState.withHidden(): ListenBarState {
        val p = post
        val s = status
        val analysedSupported = s != null && s.status != ListenStatus.UNSUPPORTED && s.languages.isNotEmpty()
        val hide = p == null || !guessed || comment || p.body.isEmpty() || s?.status == ListenStatus.UNSUPPORTED || (unreadable && !analysedSupported)
        return copy(hidden = hide)
    }

    /** Another reader (or none): the status is theirs to ask for; whatever the last one started is dropped. */
    private fun accountChanged(user: String?) {
        reader = user
        // an unlock on its way finishes with its own key in [ListenUnlocks]; its answer is dropped for another reader
        reloadAfterRenewal = false
        renewalJob?.cancel()
        renewalJob = null
        statusRenewed = false
        setState { it.copy(status = null, signedIn = user != null, busy = false, confirmCost = null, alert = null).withHidden() }
        if (post != null) loadStatus()
        joinInFlight()
    }

    /**
     * The app came back to the foreground (called by the screen's owner, not by the bar's row): a status that failed
     * to load is asked for again, and polling paused in the background goes on.
     */
    fun onForeground() {
        val paused = inBackground
        inBackground = false
        if (statusFailed || (paused && current.status?.preparing == true)) loadStatus()
    }

    /** The app went to the background: the status is not polled there (RN's focus manager). A renewal goes on. */
    fun onBackground() {
        inBackground = true
        if (current.status?.preparing == true) statusJob?.cancel()
    }

    /** (Re)starts the status loop: ask, then ask again every [POLL_MILLIS] while the audio is made. */
    private fun loadStatus(afterMillis: Long = 0) {
        // RN asks nothing for a comment (the query is disabled)
        val p = post?.takeIf { !comment } ?: return
        statusJob?.cancel()
        if (renewalJob?.isActive == true) {
            // the renewal brings the status itself; anything else waits for its end
            reloadAfterRenewal = true
            statusJob = Job().also { it.complete() }
            return
        }
        statusJob = scope.launch {
            if (afterMillis > 0) delay(afterMillis)
            var failures = 0
            while (true) {
                val user = reader
                val code = user?.let { readerCode(it) }
                val answer = try {
                    env.listenApi.status(p.author, p.permlink, code, p.updated)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (e is EcencyHttpException && e.status == 401 && user != null && code != null) {
                        statusFailed = true
                        if (user == reader) renewStatus(user, code)
                        return@launch
                    }
                    // React Query's three retries, 1, 2 and 4 s apart; then the bar waits for the foreground
                    if (failures < STATUS_RETRIES) {
                        delay(1_000L shl failures)
                        failures++
                        continue
                    }
                    statusFailed = true
                    return@launch
                }
                if (user != reader) return@launch
                failures = 0
                statusFailed = false
                show(answer)
                // an answer that came in the background schedules nothing; a background during the wait cancels this job
                if (!answer.preparing || inBackground) return@launch
                delay(POLL_MILLIS)
            }
        }
    }

    private suspend fun readerCode(user: String): String? = try {
        env.accounts.code(user)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private fun show(answer: ListenStatus) = setState { it.copy(status = answer).withHidden() }

    /**
     * The reader's status was refused with 401 (the screen outlived the access token): renew it and ask again with
     * it. One renewal at a time; a failed or cancelled one is allowed again on the next refusal. The renewal runs in
     * its own job: a reload asked for meanwhile (the foreground, an edit, a failed unlock) waits for it.
     */
    private fun renewStatus(user: String, code: String) {
        if (statusRenewed || renewalJob?.isActive == true) return
        statusRenewed = true
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            var ok = false
            var poll = false
            try {
                val fresh = env.sessionRenewals.renew(user)
                if (fresh == null || fresh == code || reader != user) return@launch
                val p = post ?: return@launch
                val answer = try {
                    env.listenApi.status(p.author, p.permlink, fresh, p.updated)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return@launch
                }
                if (reader != user) return@launch
                ok = true
                statusFailed = false
                show(answer)
                poll = answer.preparing
            } finally {
                // not only on a failure: a cancelled renewal must not leave the bar unable to renew again
                if (!ok) statusRenewed = false
                if (renewalJob === coroutineContext[Job]) {
                    renewalJob = null
                    val again = reloadAfterRenewal
                    reloadAfterRenewal = false
                    if (again) loadStatus() else if (poll) loadStatus(POLL_MILLIS)
                }
            }
        }
        renewalJob = job
        job.start()
    }

    // ---- unlock

    /** The Unlock button: asks first. Points are spent, so never on a single tap. */
    fun requestUnlock() {
        val s = current
        if (s.hidden || !s.signedIn || s.busy || s.status == null || s.view.unlocked || s.confirmCost != null) return
        setState { it.copy(confirmCost = s.view.cost) }
    }

    fun cancelUnlock() = setState { it.copy(confirmCost = null) }

    /** "Confirm" in the question [requestUnlock] put up; nothing happens without one showing. */
    fun confirmUnlock() {
        if (current.confirmCost == null) return
        setState { it.copy(confirmCost = null) }
        val owner = reader ?: return
        if (current.busy) return
        send(owner)
    }

    fun dismissAlert() = setState { it.copy(alert = null) }

    /** The unlock for this reader and post: the one already on its way (another screen of it, or before a switch back), or a new one. */
    private fun send(owner: String) {
        val p = post ?: return
        val id = ListenUnlocks.id(owner, p.author, p.permlink)
        val running = env.listenUnlocks.running(id) ?: env.listenUnlocks.start(id) { key -> request(owner, p, key) }
        join(owner, running)
    }

    /** An unlock of this reader and post on its way (from an earlier screen): show it as busy and take its answer. */
    private fun joinInFlight() {
        val owner = reader ?: return
        val p = post ?: return
        env.listenUnlocks.running(ListenUnlocks.id(owner, p.author, p.permlink))?.let { join(owner, it) }
    }

    private fun join(owner: String, running: kotlinx.coroutines.Deferred<UnlockOutcome>) {
        setState { it.copy(busy = true) }
        // awaited in the screen's scope: closing the screen stops the waiting, never the request (app scope)
        scope.launch {
            val outcome = running.await()
            if (reader != owner) return@launch
            when (outcome) {
                is UnlockOutcome.Delivered -> {
                    setState { it.copy(busy = false, status = outcome.status).withHidden() }
                    // the answer is the post's new status (SDK setQueryData); polling goes on from there
                    if (outcome.status.preparing) loadStatus(POLL_MILLIS)
                }
                is UnlockOutcome.Refused -> fail(
                    if (outcome.status == 402) ListenAlert.NotEnoughPoints(outcome.required ?: current.view.cost, outcome.available ?: 0.0)
                    else ListenAlert.UnlockFailed,
                )
                UnlockOutcome.Lost -> fail(ListenAlert.UnlockFailed)
                UnlockOutcome.NotSent -> setState { it.copy(busy = false) }
            }
        }
    }

    /**
     * One unlock request in the app's scope, with [key]: never cut off by a screen or a reader change. A 401 renews the
     * token and sends the same request (same key) once: nothing was charged (it never got past the proxy).
     */
    private suspend fun request(owner: String, p: Post, key: String): UnlockOutcome {
        // the Points belong to the account that asked: nothing is sent once another one is signed in
        if (env.session.current.username != owner) return UnlockOutcome.NotSent
        val code = readerCode(owner) ?: return UnlockOutcome.Lost
        suspend fun once(token: String): UnlockOutcome = try {
            UnlockOutcome.Delivered(env.listenApi.unlock(token, p.author, p.permlink, p.updated, key))
        } catch (e: CancellationException) {
            throw e
        } catch (e: ListenUnlockException) {
            UnlockOutcome.Refused(e.status, e.required, e.available)
        } catch (e: Exception) {
            // no answer: the outcome is unknown, the key stays for the next tap (a replay if it was charged)
            UnlockOutcome.Lost
        }
        val first = once(code)
        if (first !is UnlockOutcome.Refused || first.status != 401) return first
        val fresh = env.sessionRenewals.renew(owner)
        return if (fresh != null && fresh != code) once(fresh) else first
    }

    private fun fail(alert: ListenAlert) {
        setState { it.copy(busy = false, alert = alert) }
        // the SDK invalidates the status after a failed unlock: the outcome may be unknown, ask for the real one
        loadStatus()
    }

    // ---- the player's URLs

    /**
     * The signed URL expired (RN `onExpired`): ask for the status again; when that fails or brings no audio, the
     * screen may have outlived the access token as well, so renew the token and ask once more with it.
     * @throws IllegalStateException when no fresh audio could be had.
     */
    @Throws(Exception::class)
    suspend fun renewUrls() {
        val p = post ?: throw IllegalStateException("listen: no post")
        val user = reader
        val code = user?.let { readerCode(it) }
        val first = try {
            env.listenApi.status(p.author, p.permlink, code, p.updated)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (user != reader) throw IllegalStateException("listen: the reader changed")
        if (first != null) show(first)
        if (first != null && first.tracks.isNotEmpty()) return
        val fresh = user?.let { env.sessionRenewals.renew(it) }
        if (fresh == null || fresh == code || user != reader) throw IllegalStateException("listen: no fresh audio URLs")
        val renewed = env.listenApi.status(p.author, p.permlink, fresh, p.updated)
        if (user != reader) throw IllegalStateException("listen: the reader changed")
        show(renewed)
        if (renewed.tracks.isEmpty()) throw IllegalStateException("listen: no fresh audio URLs")
    }

    companion object {
        /** SDK `LISTEN_STATUS_POLL_MS`. */
        const val POLL_MILLIS = 5_000L
        const val STATUS_RETRIES = 3
    }
}

package com.ecency.core.app

import com.ecency.core.api.model.PostKey
import com.ecency.core.l10n.L
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * RN postDisplayView's `tipsQuery` (SDK getPostTipsQueryOptions): the number of tips beside the gift in the post's
 * action bar. [count] is null until a read lands (and after a first read that failed): then there is no number and
 * nothing is said. [countText] is the compact number ([PostStatsModel.abbreviated], in the app language), hidden at
 * zero unless [SHOWS_ZERO]; [a11yCount] is RN's `post.a11y_tips` for the gift's spoken value.
 */
data class PostTipsState(val loading: Boolean = true, val count: Int? = null) {
    val countText: String? get() = count?.takeIf { it > 0 || SHOWS_ZERO }?.let { PostStatsModel.abbreviated(it.toLong()) }
    val a11yCount: String? get() = count?.let { L.post.a11yTips(count = it) }

    companion object {
        /** RN prints "0" beside the gift; native hides it for now (owner deciding): true shows it as RN. */
        const val SHOWS_ZERO = false
    }
}

/**
 * One post's tip count: read on [start]. A tip to this post that went through ([AppEnvironment.tipped]) counts at
 * once (+1) and the count is read again after [CONFIRM_DELAY_MS] and [FINAL_CONFIRM_MS]: the first read keeps the
 * higher of server and optimistic (the API lags the chain), the final one takes the server's count even when lower.
 * A tip while the count is unknown (first read in flight or failed) adds nothing and reads at once. The newest read
 * wins. A tip finished after the screen closed is simply read fresh on the next visit.
 */
class PostTipsModel internal constructor(private val env: AppEnvironment, val author: String, val permlink: String) :
    StateModel<PostTipsState>(PostTipsState(), env.dispatchers) {
    companion object {
        /** After a tip: the first confirm read (the higher of server and optimistic stays). */
        const val CONFIRM_DELAY_MS = 4_000L

        /** After a tip: the last read, whose count is taken even when lower than the optimistic one. */
        const val FINAL_CONFIRM_MS = 15_000L
    }

    private var started = false
    private val key = PostKey(author, permlink)
    private var loadJob: Job? = null

    /** The optimistic count after a tip, kept until a read reaches it or the final confirm read. */
    private var floor = 0

    fun start() {
        if (started) return
        started = true
        loadJob = scope.launch { read(final = true) }
        scope.launch { env.tipped.collect { if (it == key) onTipped() } }
    }

    private fun onTipped() {
        loadJob?.cancel()
        val shown = current.count
        if (shown == null) {
            loadJob = scope.launch { read(final = true) }
            return
        }
        floor = shown + 1
        setState { it.copy(count = floor) }
        loadJob = scope.launch {
            delay(CONFIRM_DELAY_MS)
            read(final = false)
            delay(FINAL_CONFIRM_MS - CONFIRM_DELAY_MS)
            read(final = true)
        }
    }

    /** RN placeholderData and retry false: a failed read keeps what was shown (unknown at first). */
    private suspend fun read(final: Boolean) {
        val server = try {
            env.walletApi.postTips(author, permlink).count
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (server != null && (final || server >= floor)) floor = 0
        setState { it.copy(loading = false, count = server?.let { n -> maxOf(n, floor) } ?: it.count) }
    }
}

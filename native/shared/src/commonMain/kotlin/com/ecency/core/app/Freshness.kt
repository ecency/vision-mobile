package com.ecency.core.app

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * How long the app was away, for the screens' data (README "Foreground refresh"). RN read a screen's data again on
 * every return to the foreground (feedQueries' AppState invalidate on the feed screen, walletScreen, assetDetailsScreen,
 * the unread count); here only a trip of at least [STALE_AFTER_MILLIS] makes the data stale, so a quick look at another
 * app reads nothing again. Timed on the app lock's monotonic clock; the app lock reports the trips
 * ([AppLockModel.onBackground], [AppLockModel.onForeground]), the one place both apps already tell. Main thread.
 *
 * A model stamps its data when it starts reading it ([stamp]) and asks [isStale] when its screen is shown (`start()`,
 * which the apps call on show and on every return to the foreground) or, for a model that knows it is on screen,
 * on [staleReturns].
 */
class Freshness internal constructor(private val now: () -> Long) {
    private var backgroundedAt: Long? = null

    /** When the latest long trip away began; data read before it is stale. */
    private var longTripBegan = Long.MIN_VALUE

    private val returns = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** A long trip away just ended: models whose screen is on show refresh quietly now, the others when shown. */
    val staleReturns: SharedFlow<Unit> = returns.asSharedFlow()

    /** The clock now, for a model to remember when it began reading its data. */
    fun stamp(): Long = now()

    /**
     * The data read from [stamp] on predates a long trip away. A trip still being reported counts too: the screen
     * may hear of the return (Android ON_START, iOS will-enter-foreground) before the app lock does.
     */
    fun isStale(stamp: Long): Boolean {
        if (stamp <= longTripBegan) return true
        val since = backgroundedAt ?: return false
        return stamp <= since && isLong(now() - since)
    }

    internal fun onBackground() {
        if (backgroundedAt == null) backgroundedAt = now()
    }

    internal fun onForeground() {
        val since = backgroundedAt ?: return
        backgroundedAt = null
        if (!isLong(now() - since)) return
        longTripBegan = since
        returns.tryEmit(Unit)
    }

    /** A clock that went back (it cannot, monotonic) is treated as a long trip, as the app lock does. */
    private fun isLong(away: Long) = away < 0 || away >= STALE_AFTER_MILLIS

    companion object {
        /** RN refetched on every return; ten minutes keeps a quick switch to another app free (README "Foreground refresh"). */
        const val STALE_AFTER_MILLIS = 10 * 60_000L
    }
}

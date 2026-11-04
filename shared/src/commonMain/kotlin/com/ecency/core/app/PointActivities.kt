package com.ecency.core.app

import com.ecency.core.api.json.SafeJson
import com.ecency.core.api.model.int
import com.ecency.core.api.model.long
import com.ecency.core.api.model.obj
import com.ecency.core.api.model.str
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.cancellation.CancellationException

/** RN `PointActivityIds` (and the SDK's `recordActivity` types). */
object PointActivity {
    const val CHECKIN = 10
    const val LOGIN = 20
    const val POST = 100
    const val COMMENT = 110
    const val VOTE = 120
    const val REBLOG = 130

    /** SDK resolveContentActivityType: an edit earns nothing, a reply is a comment, a root post a post. */
    fun content(parentAuthor: String, isUpdate: Boolean): Int? = when {
        isUpdate -> null
        parentAuthor.isNotEmpty() -> COMMENT
        else -> POST
    }
}

/**
 * RN useUserActivityMutation and the mobile adapter's `recordActivity`: the points earning actions (check-in,
 * login, post, comment, vote, reblog) are reported to `POST /private-api/usr-activity`. A report is tried three
 * times (React Query `retry: 2`); one that still fails is parked for its account in the device preferences (RN
 * keeps it in its persisted redux cache) and sent again 3 s after the app starts and after every return to the
 * foreground, for the account signed in then. A report that went through schedules the quest refresh of its
 * account ([QuestRefreshes]). Nothing is sent while the app is locked.
 */
class PointActivities internal constructor(private val env: AppEnvironment) {
    /** The replay running per account: a second replay of the same account (a start and a foreground at once) waits its turn by not starting. */
    private val replays = mutableMapOf<String, Job>()
    private var nextId = 0L
    private var started = false

    /**
     * Main thread, once (the app's start): a report refused while the app lock hid the app stays parked, so the
     * parked reports are sent again as soon as the lock stops hiding the app.
     */
    fun start() {
        if (started) return
        started = true
        env.background.launch {
            var hidden = env.appLock.current.hidesContent
            env.appLock.state.collect { s ->
                if (hidden && !s.hidesContent) replayPending()
                hidden = s.hidesContent
            }
        }
    }

    /**
     * Main thread. Reports [type] for [username] (the account that acted, captured now: the refresh follows a minute
     * later, after a possible account switch). [onResult] hears whether it went through (the check-in gives its
     * window back on a failure).
     */
    fun record(username: String?, type: Int, txId: String? = null, blockNum: Long? = null, onResult: (Boolean) -> Unit = {}) {
        val user = username?.removePrefix("@")?.takeIf { it.isNotEmpty() } ?: return onResult(false)
        env.background.launch {
            val ok = send(user, type, txId, blockNum)
            // an account without an Ecency session can never report: nothing is parked for it. A store that fails
            // to write loses this report, never the caller's coroutine.
            if (ok == false) runCatching { park(user, type, txId, blockNum) }
            onResult(ok == true)
        }
    }

    /**
     * Main thread. The SDK mutations' `recordActivity(type, txId, block_num)` after a broadcast: nothing without a
     * [type] (an edit) or without a transaction id.
     */
    fun recordBroadcast(username: String, type: Int?, signed: com.ecency.core.auth.SignedBroadcast) {
        if (type == null) return
        val tx = signed.txId?.takeIf { it.isNotEmpty() } ?: return
        record(username, type, tx, signed.blockNum)
    }

    /**
     * RN lazyMutatePendingActivities: 3 s later, the signed-in account's parked reports, each removed once sent. One
     * replay per account at a time (a replay asked for while one runs does not start), and each entry is read again
     * right before it is sent, so an entry another path already sent is skipped.
     */
    fun replayPending() {
        env.background.launch {
            delay(REPLAY_DELAY_MILLIS)
            val user = env.session.current.username ?: return@launch
            if (replays[user]?.isActive == true) return@launch
            val self = coroutineContext[Job] ?: return@launch
            replays[user] = self
            try {
                for (p in read(user)) {
                    if (env.session.current.username != user) return@launch
                    if (read(user).none { it.id == p.id }) continue
                    // a replay that fails stays parked as it is (RN only parks a report that has no cache id yet)
                    if (send(user, p.type, p.txId, p.blockNum) != false) runCatching { write(user, read(user).filterNot { it.id == p.id }) }
                }
            } finally {
                if (replays[user] === self) replays.remove(user)
            }
        }
    }

    /** The reports parked for [username] (tests and the debug screens). */
    fun pending(username: String): List<Parked> = read(username)

    /** True when it went through, false when it may go through later, null when it never can (no session). */
    private suspend fun send(user: String, type: Int, txId: String?, blockNum: Long?): Boolean? {
        repeat(ATTEMPTS) { attempt ->
            if (env.appLock.current.hidesContent) return false
            try {
                env.perksApi.userActivity(user, type, txId, blockNum)
                env.questRefreshes.schedule(user)
                return true
            } catch (e: CancellationException) {
                throw e
            } catch (e: com.ecency.core.auth.AuthException.NotSignedIn) {
                return null
            } catch (e: Exception) {
                // React Query's retry delay: 1 s, then 2 s
                if (attempt < ATTEMPTS - 1) delay(1_000L shl attempt)
            }
        }
        return false
    }

    data class Parked(val id: String, val type: Int, val txId: String?, val blockNum: Long?)

    private fun park(user: String, type: Int, txId: String?, blockNum: Long?) {
        val id = "${env.nowMillis()}-${nextId++}"
        // bounded: the oldest give way (a device offline for weeks does not grow without end)
        write(user, (read(user) + Parked(id, type, txId, blockNum)).takeLast(MAX_PARKED))
    }

    private fun read(user: String): List<Parked> {
        val list = env.prefs.getString(key(user))?.let { SafeJson.parseOrNull(it, 4) } as? JsonArray ?: return emptyList()
        return list.mapNotNull { e: JsonElement ->
            val o = e.obj() ?: return@mapNotNull null
            val id = o.str("id") ?: return@mapNotNull null
            val type = o.int("ty") ?: return@mapNotNull null
            Parked(id, type, o.str("tx")?.takeIf { it.length <= 80 }, o.long("bl"))
        }
    }

    private fun write(user: String, list: List<Parked>) {
        env.prefs.putString(
            key(user),
            if (list.isEmpty()) {
                null
            } else {
                JsonArray(
                    list.map { p ->
                        buildJsonObject {
                            put("id", p.id)
                            put("ty", p.type)
                            p.txId?.let { put("tx", it) }
                            p.blockNum?.let { put("bl", it) }
                        }
                    },
                ).toString()
            },
        )
    }

    companion object {
        /** One try and React Query's two retries. */
        const val ATTEMPTS = 3
        const val REPLAY_DELAY_MILLIS = 3_000L
        const val MAX_PARKED = 50

        /** Names an account: a wipe removes it ([AccountPrefs]). */
        const val KEY_PREFIX = "points.pendingActivities."

        fun key(username: String) = "$KEY_PREFIX$username"
    }
}

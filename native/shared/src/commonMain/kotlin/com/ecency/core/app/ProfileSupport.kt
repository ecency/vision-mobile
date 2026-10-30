package com.ecency.core.app

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * Avatar URLs with RN's cache stamp: after a profile edit the account's avatar URL gets `?stamp=<time>`
 * (RN setAvatarCacheStamp), so image caches load the new picture. One per app.
 */
class AvatarStamps internal constructor(dispatchers: AppDispatchers) : StateModel<Map<String, Long>>(emptyMap(), dispatchers) {
    /** The proxy avatar of [username] in [size] ("small", "medium", "large"), stamped after an edit. */
    fun url(username: String, size: String, proxyBase: String): String = PostCard.avatarUrl(username, proxyBase, size)

    /** Stamps [username]'s avatar everywhere ([PostCard.avatarUrl]); the state change lets open screens redraw. */
    internal fun bump(username: String, at: Long) {
        AvatarCacheStamps.set(username, at)
        setState { it + (username to at) }
    }
}

/**
 * Ecency Pro members (RN useProMembersQuery / ProBadge): loaded once on first use, from the public list.
 * A failed load leaves the set empty (no badges) and is tried again on the next [ensureLoaded].
 */
class ProMembersModel internal constructor(private val env: AppEnvironment) : StateModel<Set<String>>(emptySet(), env.dispatchers) {
    private var job: Job? = null
    private var loaded = false

    fun ensureLoaded() {
        if (loaded || job?.isActive == true) return
        job = scope.launch {
            try {
                val members = env.accountApi.proMembers()
                loaded = true
                setState { members }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // no badges until the next try
            }
        }
    }

    fun isPro(username: String): Boolean = username.lowercase() in current

    private var activation: Job? = null

    /** Accounts waiting to show on the roster after a Pro purchase, with the reads each has left. */
    private val activating = mutableMapOf<String, Int>()

    /**
     * A Pro purchase for [username] was accepted: the roster is read again past its cache now and every
     * [ACTIVATION_POLL_MILLIS] while an account waits for it, each account for at most [ACTIVATION_POLLS] reads
     * (Ecency delivers the order from a queue and its public list is edge cached for a few minutes). Several accounts
     * wait together; a new one never drops another's reads. Main thread.
     */
    fun activate(username: String) {
        val name = username.lowercase().takeIf { it.isNotEmpty() } ?: return
        activating[name] = ACTIVATION_POLLS
        // a read now for the new account (the others keep their counts)
        activation?.cancel()
        activation = scope.launch {
            var first = true
            while (activating.isNotEmpty()) {
                if (!first) delay(ACTIVATION_POLL_MILLIS)
                first = false
                try {
                    val members = env.accountApi.proMembers(fresh = true)
                    loaded = true
                    setState { members }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // read again at the next poll
                }
                activating.keys.toList().forEach { n ->
                    val left = (activating[n] ?: 0) - 1
                    if (n in current || left <= 0) activating.remove(n) else activating[n] = left
                }
            }
        }
    }

    companion object {
        const val ACTIVATION_POLL_MILLIS = 30_000L
        const val ACTIVATION_POLLS = 12
    }
}

/** A row of RN's account list screen (e.g. poll voters): the account and when (epoch millis, 0 for none). */
data class AccountListEntry(val account: String, val timestampMillis: Long = 0)

/** RN accountListContainer search: case-insensitive substring of the account name. */
object AccountListFilter {
    fun filter(entries: List<AccountListEntry>, query: String): List<AccountListEntry> {
        if (query.isEmpty()) return entries
        val q = query.uppercase()
        return entries.filter { it.account.uppercase().contains(q) }
    }
}

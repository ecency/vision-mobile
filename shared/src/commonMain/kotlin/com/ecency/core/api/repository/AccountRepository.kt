package com.ecency.core.api.repository

import com.ecency.core.api.EcencyException
import com.ecency.core.api.HiveApi
import com.ecency.core.api.guarded
import com.ecency.core.api.model.Account
import com.ecency.core.api.model.Profile
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Account basics and profile headers, cached for [ttlMillis]. */
@OptIn(ExperimentalTime::class)
class AccountRepository(
    private val api: HiveApi,
    ttlMillis: Long = 60_000,
    maxEntries: Int = 200,
    nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val accounts = MemoryCache<String, Account>(maxEntries, ttlMillis, nowMillis)
    private val profiles = MemoryCache<Pair<String, String>, Profile>(maxEntries, ttlMillis, nowMillis)

    /** `condenser_api.get_accounts` for one name. Null when the account does not exist. */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun getAccount(name: String, refresh: Boolean = false): Account? = guarded {
        if (!refresh) accounts.get(name)?.let { return@guarded it }
        val account = api.accounts(listOf(name)).firstOrNull { it.name == name } ?: return@guarded null
        accounts.put(name, account)
        note(account)
        account
    }

    private val seenLock = kotlinx.coroutines.sync.Mutex()
    private val readPosting = mutableSetOf<String>()
    private val wrotePosting = mutableMapOf<String, Written>()

    /** The posting_json_metadata this process broadcast for an account, and when. */
    internal class Written(val postingJsonMetadata: String, val atMillis: Long)

    private suspend fun note(account: Account) {
        if (hasPosting(account)) seenLock.withLock { readPosting += account.name }
    }

    /**
     * A node of this process's pool has answered with a posting_json_metadata with content for [name] (or the
     * app's own write was confirmed, [confirmWritten]). Not bound to the cache's lifetime: a later row without
     * one is then a node that stripped it, not a legacy account.
     */
    internal suspend fun sawPosting(name: String): Boolean = seenLock.withLock { name in readPosting }

    /**
     * This process broadcast a posting_json_metadata for [name] and no node has shown one yet. Weaker than
     * [sawPosting]: a broadcast can be accepted and still expire or be dropped by a fork.
     */
    internal suspend fun wrotePosting(name: String): Boolean = seenLock.withLock { name in wrotePosting && name !in readPosting }

    /** What this process last broadcast for [name], if anything. */
    internal suspend fun written(name: String): Written? = seenLock.withLock { wrotePosting[name] }

    /**
     * This process broadcast a posting profile for [name] at some point, whatever nodes showed since: hivemind
     * may lag behind that write, so what it shows is no evidence about the posting row.
     */
    internal suspend fun wroteProfile(name: String): Boolean = seenLock.withLock { name in wrotePosting }

    internal suspend fun noteWritten(name: String, postingJsonMetadata: String, atMillis: Long) {
        seenLock.withLock { wrotePosting[name] = Written(postingJsonMetadata, atMillis) }
    }

    /** The write of [noteWritten] is known to have landed (hivemind shows it): as good as a node's row. */
    internal suspend fun confirmWritten(name: String) { seenLock.withLock { readPosting += name } }

    /** The write of [noteWritten] did not land: the account is as it was. */
    internal suspend fun forgetWritten(name: String) { seenLock.withLock { wrotePosting -= name } }

    /** Drops the cached row of [name] (a read that was refused must not be shown as the account). */
    internal suspend fun evict(name: String) = accounts.remove(name)

    /** The cached row of [name], if there is a fresh one; never asks a node. */
    internal suspend fun cached(name: String): Account? = accounts.get(name)

    /** Puts [account] in the cache (a profile edit shows before the node catches up, as the SDK's optimistic update). */
    internal suspend fun remember(account: Account) = accounts.put(account.name, account)

    /** `bridge.get_profile`: header data with follower counts. Null when the account does not exist. */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun getProfile(name: String, observer: String? = null, refresh: Boolean = false): Profile? = guarded {
        val key = name to observer.orEmpty()
        if (!refresh) profiles.get(key)?.let { return@guarded it }
        val profile = api.profile(name, observer) ?: return@guarded null
        profiles.put(key, profile)
        profile
    }

    companion object {
        /** A posting_json_metadata with content (hivemind reads nothing from an empty one or `{}`). */
        internal fun hasPosting(account: Account) = account.postingJsonMetadata.trim().let { it.isNotEmpty() && it != "{}" }
    }
}

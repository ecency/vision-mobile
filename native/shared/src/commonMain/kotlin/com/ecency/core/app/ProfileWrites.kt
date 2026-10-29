package com.ecency.core.app

import com.ecency.core.api.EcencyException
import com.ecency.core.api.broadcast.ProfileMetadataGuard
import com.ecency.core.api.model.Account
import com.ecency.core.api.model.AccountProfile
import com.ecency.core.api.model.JsValue
import com.ecency.core.api.repository.AccountRepository
import com.ecency.core.hive.KeyRole
import com.ecency.core.l10n.L
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException

/**
 * The one path of every profile write (profile edit, pin and unpin to blog): the account is read again, the
 * read is checked against what hivemind shows ([ProfileMetadataGuard]), the caller's profile is merged over it
 * with the SDK's builder and the `account_update2` goes out with the posting authority, as @ecency/sdk
 * useAccountUpdate (a metadata-only update is a posting operation on chain).
 */
internal object ProfileWrites {
    /** An account row just read and the checked base to merge onto. */
    class Checked(val account: Account, val base: ProfileMetadataGuard.Base)

    /**
     * Reads [user] fresh and returns the base a write may merge onto.
     *
     * @throws AppException with [ProfileMetadataGuard.REFUSED] when the read cannot be trusted (hivemind could
     * not be read, the metadata is not valid JSON, it disagrees with what hivemind shows, or the app knew a
     * posting_json_metadata that the fresh row lacks: a node that stripped it). Such a row is read once more
     * first, as the SDK's getAccountFull does.
     */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun checkedBase(env: AppEnvironment, user: String): Checked {
        // what the app knew before this read: the cached row, whether a node ever showed a posting row for the
        // account (strong), and whether the app itself broadcast one that no node has shown yet (weak)
        val before = env.api.accounts.cached(user)?.takeIf(AccountRepository::hasPosting)
        val sawPosting = env.api.accounts.sawPosting(user)
        val wrotePosting = env.api.accounts.wrotePosting(user)
        val ownWrite = env.api.accounts.wroteProfile(user)
        val account = env.api.accounts.getAccount(user, refresh = true)
            ?: throw AppException(AppError(ErrorKind.NOT_FOUND, L.native.profileWrites.accountNotFound, false))
        val hivemind = try {
            env.api.accounts.getProfile(user, refresh = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        check(sawPosting || wrotePosting, ownWrite, account, hivemind)?.let { return Checked(account, it) }
        if (hivemind != null) {
            val again = env.api.accounts.getAccount(user, refresh = true)
            if (again != null) {
                check(sawPosting || wrotePosting, ownWrite, again, hivemind)?.let { return Checked(again, it) }
                if (wrotePosting && !sawPosting && !AccountRepository.hasPosting(again) && ownWriteDidNotLand(env, user, again, hivemind)) {
                    ProfileMetadataGuard.safeBase(again, hivemind)?.let { return Checked(again, it) }
                }
            }
        }
        // a refused read must not stay as the app's row of the account (the profile screen would show it)
        if (before != null) env.api.accounts.remember(before) else env.api.accounts.evict(user)
        throw refused()
    }

    /** Longer than a transaction lives (60 s) plus hivemind's usual lag behind the chain, with margin. */
    const val NOT_LANDED_AFTER_MILLIS = 3 * 60_000L

    /**
     * Only the app's own broadcast says [user] has a posting row, and two reads show none. Did the write land?
     * - hivemind shows the written row's fields, and they differ from the legacy ones: it landed, the blank
     *   reads are a stripping node. The mark becomes strong; false.
     * - hivemind shows the values from before the write (or the write changed nothing hivemind shows): it did
     *   not land, or hivemind lags. Not told apart before [NOT_LANDED_AFTER_MILLIS] have passed since the
     *   write; false until then (the caller refuses with "try again"). After that the mark is dropped and the
     *   account is the legacy one it was; true.
     */
    private suspend fun ownWriteDidNotLand(env: AppEnvironment, user: String, fresh: Account, hivemind: com.ecency.core.api.model.Profile): Boolean {
        val written = env.api.accounts.written(user) ?: return true
        val showsWritten = ProfileMetadataGuard.hivemindShows(written.postingJsonMetadata, fresh.jsonMetadata, hivemind)
        val showsLegacy = ProfileMetadataGuard.hivemindShows("", fresh.jsonMetadata, hivemind)
        if (showsWritten && !showsLegacy) {
            env.api.accounts.confirmWritten(user)
            return false
        }
        if (env.nowMillis() - written.atMillis < NOT_LANDED_AFTER_MILLIS) return false
        env.api.accounts.forgetWritten(user)
        return true
    }

    /**
     * [ProfileMetadataGuard.safeBase], plus the one thing hivemind cannot show: a posting row that also holds
     * what hivemind does not read (tokens, pinned, unknown keys) next to a legacy profile that matches. When
     * the app knows of a posting_json_metadata for the account and the fresh row has none, a node stripped it.
     * [ownWrite]: the app itself wrote the posting profile and hivemind may simply not show it yet.
     */
    private fun check(knowsPosting: Boolean, ownWrite: Boolean, fresh: Account, hivemind: com.ecency.core.api.model.Profile?): ProfileMetadataGuard.Base? {
        if (knowsPosting && !AccountRepository.hasPosting(fresh)) return null
        return ProfileMetadataGuard.safeBase(fresh.postingJsonMetadata, fresh.jsonMetadata, hivemind, ownWrite)
    }

    /** The profile the edit form shows: what a write would start from, or the row's own when it cannot be checked. */
    suspend fun formProfile(env: AppEnvironment, account: Account): JsValue.Obj {
        val hivemind = try {
            env.api.accounts.getProfile(account.name, refresh = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val ownWrite = env.api.accounts.wroteProfile(account.name)
        // a node has shown a posting row and this read has none: a stripped read, not a form to edit
        if (env.api.accounts.sawPosting(account.name) && !AccountRepository.hasPosting(account)) throw refused()
        return ProfileMetadataGuard.safeBase(account.postingJsonMetadata, account.jsonMetadata, hivemind, ownWrite)?.profile
            ?: ProfileMetadataGuard.effectiveProfile(account.postingJsonMetadata, account.jsonMetadata)
    }

    /** The read could not be trusted; its own type, so callers never compare the (translated) message. */
    class ReadRefused : AppException(AppError(ErrorKind.NETWORK, ProfileMetadataGuard.REFUSED, true))

    fun refused(): AppException = ReadRefused()

    fun isRefusal(e: Throwable) = e is ReadRefused

    /**
     * Broadcasts [profile] merged over [checked] and remembers the new metadata, so the profile shows the new
     * values before the node catches up (SDK optimistic cache update). Returns the posting_json_metadata written.
     */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun broadcast(env: AppEnvironment, user: String, checked: Checked, profile: JsValue.Obj): String {
        val posting = ProfileMetadataGuard.postingJsonMetadata(checked.base, profile)
        env.signing.broadcast(user, ProfileMetadataGuard.operation(user, checked.base, profile), KeyRole.POSTING)
        val shown = runCatching { AccountProfile.fromMetadata(Json.parseToJsonElement(posting)) }.getOrNull()
        env.api.accounts.remember(checked.account.copy(postingJsonMetadata = posting, profile = shown ?: checked.account.profile))
        env.api.accounts.noteWritten(user, posting, env.nowMillis())
        return posting
    }
}

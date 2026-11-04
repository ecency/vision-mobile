package com.ecency.core.app

import com.ecency.core.auth.KeyLoginOutcome
import com.ecency.core.hive.KeyRole
import com.ecency.core.l10n.L
import kotlinx.coroutines.launch

/** Why a notice is shown; the platform only needs it for test tags. */
enum class AccountNoticeKind { NO_SIGNING_KEY, PASSWORD_MISSING_ROLES, KEY_SHARED_WITH_OWNER }

/** One notice about an account's keys, shown as an alert with OK. It names its account; it holds no secret. */
data class AccountNotice(val id: Long, val kind: AccountNoticeKind, val account: String, val title: String, val body: String)

data class AccountNoticesState(val notice: AccountNotice? = null, val queued: Int = 0)

/** The notices' copy (none of it is RN's: RN stores the owner key and the master password and has none of these cases). */
object AccountNoticeCopy {
    val MANAGE_KEYS: String get() = L.native.accountNotice.manageKeys

    fun noSigningKeyTitle(account: String) = L.native.accountNotice.noSigningKeyTitle(account = account)

    fun noSigningKey(account: String) = L.native.accountNotice.noSigningKey(account = account, manageKeys = MANAGE_KEYS)

    val MISSING_ROLES_TITLE: String get() = L.native.accountNotice.missingRolesTitle

    /** [missing] of posting and active did not match; [kept] is what was stored. */
    fun missingRoles(account: String, missing: Set<KeyRole>, kept: Set<KeyRole>): String {
        fun names(roles: Set<KeyRole>) = CopyLists.and(listOf(KeyRole.POSTING, KeyRole.ACTIVE, KeyRole.MEMO).filter { it in roles }.map(CopyLists::role))
        val cannot = CopyLists.and(
            buildList {
                if (KeyRole.POSTING in missing) add(L.native.accountNotice.cannotPost)
                if (KeyRole.ACTIVE in missing) add(L.native.accountNotice.cannotTransfer)
            },
        )
        return L.native.accountNotice.missingRoles(
            account = account, missing = names(missing), kept = names(kept), manageKeys = MANAGE_KEYS, cannot = cannot,
        )
    }

    val OWNER_KEY_NOT_KEPT_TITLE: String get() = L.native.accountNotice.ownerKeyNotKeptTitle

    /** A stored key of [account] was also its owner key on chain and was removed. */
    fun ownerKeyNotKept(account: String) = L.native.accountNotice.ownerKeyNotKept(account = account)
}

/**
 * Notices about what the device holds for an account, one at a time, each dismissed with OK:
 *  - an account left without a posting and without an active key by the storage rule (the cleaning of an entry
 *    of an earlier build, or the RN import): remembered in the device preferences until it was dismissed, so
 *    it is shown once even when the app ends before the user saw it;
 *  - a master password sign-in that gave fewer than posting and active;
 *  - a stored key the chain shows on the owner authority, removed ([checkOwnerKeys], [ownerKeyFound]): remembered
 *    in the device preferences until it was dismissed, as the first kind.
 * The UIs draw them only with the app unlocked. A wipe drops them all; a notice of an account that left the
 * device is dropped.
 */
class AccountNoticesModel internal constructor(private val env: AppEnvironment) :
    StateModel<AccountNoticesState>(AccountNoticesState(), env.dispatchers) {

    private val queue = ArrayDeque<AccountNotice>()
    private var nextId = 1L

    init {
        scope.launch {
            env.events.collect { event ->
                when (event) {
                    AppEvent.DataWiped -> {
                        queue.clear()
                        writePending(emptyList())
                        writeList(OWNER_CHECK, emptyList())
                        writeList(OWNER_NOTICE, emptyList())
                        publish()
                    }
                    else -> {}
                }
            }
        }
    }

    init {
        // an account that left the device (any logout, current or not) takes its notices with it
        scope.launch {
            env.session.state.collect { session ->
                if (session.isLoading) return@collect
                // an account list that could not be read (a storage error and nothing listed) says nothing about who
                // is on the device: no notice is dropped on it
                if (session.accounts.isEmpty() && session.error?.kind == ErrorKind.STORAGE) return@collect
                val here = session.accounts.map { it.username }.toSet()
                var changed = queue.removeAll { it.account !in here }
                val owners = list(OWNER_NOTICE)
                if (owners.any { it !in here }) writeList(OWNER_NOTICE, owners.filter { it in here })
                // an account that can sign again, however it got there (a key, or a HiveSigner or HiveAuth sign-in
                // as that account), no longer needs to be told that it has no signing key
                val able = session.accounts.filter { a ->
                    KeyRole.POSTING in a.storedKeys || KeyRole.ACTIVE in a.storedKeys ||
                        a.method == com.ecency.core.auth.LoginMethod.HIVESIGNER || a.method == com.ecency.core.auth.LoginMethod.HIVEAUTH
                }.map { it.username }.toSet()
                if (queue.removeAll { it.kind == AccountNoticeKind.NO_SIGNING_KEY && it.account in able }) changed = true
                val pending = pendingNoSigningKey()
                if (pending.any { it in able }) writePending(pending.filterNot { it in able })
                if (changed) publish()
            }
        }
    }

    /** Accounts whose "no signing key" notice was not dismissed yet (device preferences; unreadable preferences read as none). */
    internal fun pendingNoSigningKey(): List<String> =
        runCatching { env.prefs.getString(PENDING) }.getOrNull().orEmpty().split(',').filter { it.isNotEmpty() }

    /** A preferences failure never breaks a sign-in, a start or a wipe: the notice is then only not remembered. */
    private fun writePending(names: List<String>) = writeList(PENDING, names)

    private fun list(key: String): List<String> =
        runCatching { env.prefs.getString(key) }.getOrNull().orEmpty().split(',').filter { it.isNotEmpty() }

    private fun writeList(key: String, names: List<String>) {
        runCatching { env.prefs.putString(key, names.distinct().joinToString(",").ifEmpty { null }) }
    }

    /**
     * At app start, with the accounts the RN import brought ([imported]): each of them, and each name still waiting
     * from an earlier start, has its authorities read once; a stored key on the owner authority is removed and the
     * account told ([ownerKeyFound]). A read that fails keeps the name for the next start; an account that is gone
     * on chain, or read and fine, is done. Notices of this kind not dismissed at an earlier start are shown again.
     */
    internal fun checkOwnerKeys(imported: List<String>) {
        val toCheck = (list(OWNER_CHECK) + imported).distinct()
        writeList(OWNER_CHECK, toCheck)
        scope.launch {
            val here = runCatching { env.accounts.accounts().map { it.username }.toSet() }.getOrNull()
            if (here != null) list(OWNER_NOTICE).filter { it in here }.forEach(::queueOwnerNotice)
            for (name in toCheck) {
                val authorities = try {
                    env.chain.authorities(name)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    continue
                }
                try {
                    if (authorities != null) ownerKeyFound(name, authorities)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    continue
                }
                writeList(OWNER_CHECK, list(OWNER_CHECK) - name)
            }
        }
    }

    /**
     * [authorities] were read for [username] (from one node): when a stored key is on the owner authority, two
     * different nodes must agree on the owner keys ([com.ecency.core.auth.ChainAccounts.confirmedAuthorities])
     * before that key is removed; it is then told once (remembered until dismissed). True when a key was removed.
     * Throws when the nodes could not confirm it (the key stays; the start check tries again next time).
     */
    @Throws(Exception::class)
    internal suspend fun ownerKeyFound(username: String, authorities: com.ecency.core.auth.ChainAuthorities): Boolean {
        val stored = env.accounts.vaultAccount(username) ?: return false
        if (stored.rolesOnOwner(authorities.owner).isEmpty()) return false
        val confirmed = env.chain.confirmedAuthorities(username) ?: return false
        if (stored.rolesOnOwner(confirmed.owner).isEmpty()) return false
        // remembered before the key goes, so a start that ends here still tells it next time
        writeList(OWNER_NOTICE, list(OWNER_NOTICE) + username)
        val removed = env.accounts.dropKeysOnOwner(username, confirmed)
        if (removed.isEmpty()) {
            writeList(OWNER_NOTICE, list(OWNER_NOTICE) - username)
            return false
        }
        queueOwnerNotice(username)
        // the stored keys changed: the session reads them again
        env.session.start()
        return true
    }

    /** The RN import did not keep [names]' key that was the owner key RN also stored: each stored one is told once. */
    internal fun ownerKeysDropped(names: List<String>) {
        if (names.isEmpty()) return
        scope.launch {
            val here = runCatching { env.accounts.accounts().map { it.username }.toSet() }.getOrNull() ?: return@launch
            val told = names.filter { it in here }
            if (told.isEmpty()) return@launch
            writeList(OWNER_NOTICE, list(OWNER_NOTICE) + told)
            told.forEach(::queueOwnerNotice)
        }
    }

    private fun queueOwnerNotice(account: String) {
        if (queue.any { it.kind == AccountNoticeKind.KEY_SHARED_WITH_OWNER && it.account == account }) return
        queue += AccountNotice(
            nextId++, AccountNoticeKind.KEY_SHARED_WITH_OWNER, account, AccountNoticeCopy.OWNER_KEY_NOT_KEPT_TITLE,
            AccountNoticeCopy.ownerKeyNotKept(account),
        )
        publish()
    }

    /**
     * [accounts] hold neither a posting nor an active key now (found at app start: just cleaned or imported, or
     * still pending from an earlier start). Replaces the pending list and queues one notice per account. [unread]
     * could not be read this time: they stay on the pending list, without a notice, for the next start to judge.
     */
    internal fun noSigningKey(accounts: List<String>, unread: List<String> = emptyList()) {
        val names = accounts.distinct()
        writePending((names + unread).distinct())
        names.filter { name -> queue.none { it.kind == AccountNoticeKind.NO_SIGNING_KEY && it.account == name } }.forEach { name ->
            queue += AccountNotice(nextId++, AccountNoticeKind.NO_SIGNING_KEY, name, AccountNoticeCopy.noSigningKeyTitle(name), AccountNoticeCopy.noSigningKey(name))
        }
        publish()
    }

    /** After a key sign-in went through. */
    internal fun signedIn(outcome: KeyLoginOutcome) {
        val account = outcome.summary.username
        val kept = outcome.summary.storedKeys
        // one notice of a kind per account: signing in twice replaces the earlier one, it does not stack
        queue.removeAll { it.account == account && (it.kind == AccountNoticeKind.PASSWORD_MISSING_ROLES || it.kind == AccountNoticeKind.KEY_SHARED_WITH_OWNER) }
        if (outcome.missingSigningRoles.isNotEmpty()) {
            queue += AccountNotice(
                nextId++, AccountNoticeKind.PASSWORD_MISSING_ROLES, account, AccountNoticeCopy.MISSING_ROLES_TITLE,
                AccountNoticeCopy.missingRoles(account, outcome.missingSigningRoles, kept),
            )
        }
        // signed in again with keys of its own (a typed owner key is refused): the owner key notice is void
        if (account in list(OWNER_NOTICE)) writeList(OWNER_NOTICE, list(OWNER_NOTICE) - account)
        // it has keys again: a pending "no signing key" notice for it is void
        if (queue.removeAll { it.kind == AccountNoticeKind.NO_SIGNING_KEY && it.account == account } || account in pendingNoSigningKey()) {
            writePending(pendingNoSigningKey() - account)
        }
        publish()
    }

    /** OK on the notice that is on screen. */
    fun dismiss() {
        current.notice?.let { dismiss(it.id) }
    }

    /**
     * OK on the notice [id]: that one is not shown again. By id, not "the first": the queue may have changed
     * under the alert (an account left, another notice was voided), and a tap must never answer a different one.
     */
    fun dismiss(id: Long) {
        val shown = queue.firstOrNull { it.id == id } ?: return
        queue.remove(shown)
        if (shown.kind == AccountNoticeKind.NO_SIGNING_KEY) {
            writePending(pendingNoSigningKey() - shown.account)
        }
        if (shown.kind == AccountNoticeKind.KEY_SHARED_WITH_OWNER) {
            writeList(OWNER_NOTICE, list(OWNER_NOTICE) - shown.account)
        }
        publish()
    }

    private fun publish() = setState { AccountNoticesState(queue.firstOrNull(), maxOf(0, queue.size - 1)) }

    companion object {
        const val PENDING = "accounts.noSigningKeyNotice"

        /** Accounts whose authorities are still to be read for a stored key that is also the owner key. */
        const val OWNER_CHECK = "accounts.ownerKeyCheck"

        /** Accounts whose "key not kept" notice was not dismissed yet. */
        const val OWNER_NOTICE = "accounts.ownerKeyNotice"
    }
}

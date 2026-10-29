package com.ecency.core.app

import com.ecency.core.api.broadcast.HiveBroadcaster
import com.ecency.core.hive.ChainTime
import com.ecency.core.l10n.L
import com.ecency.core.secure.SecureStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlin.coroutines.cancellation.CancellationException

/** What the chain said about a send that was undecided when the app last ran. */
enum class JournalOutcome { WENT_THROUGH, NOT_SENT, UNCONFIRMED }

/**
 * One value-moving send whose outcome was not known (README "Node failover", design note L5): the transaction id,
 * its expiration, the action id and the account, plus the first operation's name for the message. Nothing secret
 * and no operation body: no key, no signature, no amount, no memo. [outcome] is set once the chain decided, and
 * the entry stays until the user dismissed its notice.
 */
data class JournalEntry(
    val account: String,
    val actionId: String,
    val txId: String,
    /** Chain time, "YYYY-MM-DDTHH:MM:SS". */
    val expiration: String,
    val operation: String,
    val outcome: JournalOutcome? = null,
) {
    /** RN-style wording for the operation ("transfer", "power up", ...). */
    val label: String get() = WalletCopy.operationLabel(operation)

    val message: String
        get() = when (outcome) {
            JournalOutcome.WENT_THROUGH -> L.native.sendJournal.yourFromWentThrough(label = label, account = account)
            JournalOutcome.NOT_SENT -> L.native.sendJournal.notSent(label = label, account = account)
            JournalOutcome.UNCONFIRMED -> "Could not confirm whether your $label from @$account went through. Please check your wallet or history; start it again only if it is not there."
            null -> L.native.sendJournal.checkingWhetherYourFrom(label = label, account = account)
        }
}

data class SendJournalState(val entries: List<JournalEntry> = emptyList(), val checking: Boolean = false) {
    /** What [account] should see: its entries, undecided first. */
    fun forAccount(account: String?): List<JournalEntry> =
        if (account == null) emptyList() else entries.filter { it.account == account }.sortedBy { it.outcome != null }
}

/**
 * The restart journal of undecided sends. A key account's value-moving broadcast that may have landed without the
 * chain saying so yet is written here ([record], by [SigningModel]); once it settles in this run it is removed
 * ([settle]). At launch ([start]) every entry left from an earlier run is resolved with
 * `transaction_status_api.find_transaction`: in a block or the mempool it went through, an irreversible expiry means
 * it did not, "too_old" (or the chain's head time past its expiration plus [HiveBroadcaster.AGE_OUT_MILLIS]) cannot
 * be decided any more. The device clock never decides. What it found stays as a notice on the Wallet tab of that
 * account until dismissed.
 *
 * Stored in the secure store (entry [ENTRY]), so it goes with a wipe, and per account with the account's teardown.
 */
class SendJournal internal constructor(private val env: AppEnvironment) : StateModel<SendJournalState>(SendJournalState(), env.dispatchers) {
    private val store: SecureStore get() = env.vault.vault.store
    private var started = false
    private var resolver: Job? = null

    /** Reads the journal and resolves what an earlier run left. Both apps call it at launch; safe to call again. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            val entries = io { read() }
            setState { it.copy(entries = entries) }
            resolve()
        }
    }

    /** Asks the chain again about the entries still undecided (the Wallet tab's pull to refresh). */
    fun recheck() {
        if (resolver?.isActive == true) return
        scope.launch { resolve() }
    }

    /** The user read [entry]'s notice. Only decided entries can be dismissed. */
    fun dismiss(entry: JournalEntry) {
        if (entry.outcome == null) return
        scope.launch { update { list -> list.filterNot { it.account == entry.account && it.actionId == entry.actionId } } }
    }

    /** A send of [account]'s action [actionId] may have landed as [txId]: kept until it settles. */
    internal suspend fun record(account: String, actionId: String, operation: String, txId: String, expiration: String) {
        update { list ->
            val rest = list.filterNot { it.account == account && it.actionId == actionId }
            val all = rest + JournalEntry(account, actionId, txId, expiration, operation)
            // over the cap only decided entries (whose notice was not read yet) give way, oldest first; an undecided one is
            // never dropped (WalletSends refuses new sends while the journal is full of them)
            val over = all.size - MAX_ENTRIES
            if (over <= 0) all else {
                val drop = all.filter { it.outcome != null }.take(over).toSet()
                all.filterNot { it in drop }
            }
        }
    }

    /** The action settled in this run (sent, refused, closed as unconfirmed on screen): nothing left to resolve. */
    internal suspend fun settle(account: String, actionId: String) {
        update { list -> list.filterNot { it.account == account && it.actionId == actionId } }
    }

    /** Accounts left the device (logout, logout-all; a wipe clears the store itself): their entries go. Null: all. */
    internal suspend fun forget(accounts: List<String>?) {
        update { list -> if (accounts == null) emptyList() else list.filterNot { it.account in accounts } }
    }

    private suspend fun resolve() {
        val job = scope.launch {
            setState { it.copy(checking = true) }
            try {
                var round = 0
                while (round < MAX_ROUNDS) {
                    val open = current.entries.filter { it.outcome == null }
                    if (open.isEmpty()) break
                    for (e in open) {
                        val outcome = decide(e) ?: continue
                        update { list -> list.map { if (it.account == e.account && it.actionId == e.actionId && it.txId == e.txId) it.copy(outcome = outcome) else it } }
                    }
                    if (current.entries.none { it.outcome == null }) break
                    // 5, 10, 20 s, then every 30 s, as WalletSends looks up an undecided send
                    delay(WalletSends.lookupDelay(round))
                    round++
                }
            } finally {
                setState { it.copy(checking = false) }
            }
        }
        resolver = job
        job.join()
    }

    /** The chain's answer for [e], or null while it cannot tell yet (or cannot be reached). */
    private suspend fun decide(e: JournalEntry): JournalOutcome? {
        val status = try {
            env.api.hive.transactionStatus(e.txId, e.expiration)
        } catch (c: CancellationException) {
            throw c
        } catch (_: Exception) {
            return null
        }
        return when (status) {
            "within_mempool", "within_reversible_block", "within_irreversible_block" -> JournalOutcome.WENT_THROUGH
            "expired_irreversible" -> JournalOutcome.NOT_SENT
            "too_old" -> JournalOutcome.UNCONFIRMED
            // not found and not expired yet by the chain's clock (the expiration is given): no need to read its head. A node
            // that answers so long after the expiration ignores it; past [DEVICE_AGE_OUT_MILLIS] by the device clock the
            // entry closes as unconfirmed (it only leaves the journal; nothing is ever signed from here)
            "unknown" -> {
                val expires = runCatching { ChainTime.toEpochSeconds(e.expiration) * 1000 }.getOrElse { return JournalOutcome.UNCONFIRMED }
                if (env.nowMillis() > expires + DEVICE_AGE_OUT_MILLIS) JournalOutcome.UNCONFIRMED else null
            }
            else -> {
                val head = try {
                    ChainTime.toEpochSeconds(env.api.hive.chainHead().time) * 1000
                } catch (c: CancellationException) {
                    throw c
                } catch (_: Exception) {
                    return null
                }
                val expires = runCatching { ChainTime.toEpochSeconds(e.expiration) * 1000 }.getOrElse { return JournalOutcome.UNCONFIRMED }
                if (head >= expires + HiveBroadcaster.AGE_OUT_MILLIS) JournalOutcome.UNCONFIRMED else null
            }
        }
    }

    /**
     * Read-modify-write under the store's lock. Never throws (but cancellation): a journal the store refuses must not
     * turn a broadcast that went through into an error, so a failed write keeps the entries in memory only.
     */
    private suspend fun update(transform: (List<JournalEntry>) -> List<JournalEntry>) {
        val next = try {
            io {
                store.withLock {
                    val before = read()
                    val updated = transform(before)
                    if (updated != before) write(updated)
                    updated
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            transform(current.entries)
        }
        withContext(env.dispatchers.main) { setState { it.copy(entries = next) } }
    }

    private suspend fun <T> io(block: () -> T): T = withContext(env.dispatchers.io) { block() }

    private fun read(): List<JournalEntry> {
        val raw = try {
            store.getString(ENTRY)
        } catch (_: Exception) {
            null
        } ?: return emptyList()
        val array = runCatching { Json.parseToJsonElement(raw) as? JsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            fun s(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            val account = s("account")?.takeIf(com.ecency.core.secure.KeyVault::isValidUsername) ?: return@mapNotNull null
            JournalEntry(
                account, s("actionId") ?: return@mapNotNull null, s("txId")?.takeIf { TX_ID.matches(it) } ?: return@mapNotNull null,
                s("expiration")?.takeIf { runCatching { ChainTime.toEpochSeconds(it) }.isSuccess } ?: return@mapNotNull null,
                s("operation").orEmpty(), s("outcome")?.let { v -> JournalOutcome.entries.firstOrNull { it.name == v } },
            )
        }
    }

    private fun write(entries: List<JournalEntry>) {
        if (entries.isEmpty()) {
            store.delete(ENTRY)
            return
        }
        store.putString(
            ENTRY,
            buildJsonArray {
                entries.forEach { e ->
                    add(buildJsonObject {
                        put("account", e.account)
                        put("actionId", e.actionId)
                        put("txId", e.txId)
                        put("expiration", e.expiration)
                        put("operation", e.operation)
                        e.outcome?.let { put("outcome", it.name) }
                    })
                }
            }.toString(),
        )
    }

    companion object {
        const val ENTRY = "wallet.sendJournal"
        /** Entries kept; undecided ones are never dropped for room. */
        const val MAX_ENTRIES = 32
        /** Lookups per resolve: about 10 minutes of asking with [WalletSends.lookupDelay]'s pauses. */
        private const val MAX_ROUNDS = 22

        /** Device-clock fallback for an "unknown" answer long after the expiration (a node ignoring it). */
        const val DEVICE_AGE_OUT_MILLIS = 24 * 3600_000L
        private val TX_ID = Regex("^[0-9a-f]{40}$")
    }
}

package com.ecency.core.api.broadcast

import com.ecency.core.api.EcencyException
import com.ecency.core.api.HiveApi
import com.ecency.core.api.rpc.HiveApiException
import com.ecency.core.api.rpc.NodeFailureException
import com.ecency.core.api.rpc.RpcErrorException
import com.ecency.core.hive.ChainTime
import com.ecency.core.hive.Comment
import com.ecency.core.hive.HIVE_CHAIN_ID
import com.ecency.core.hive.Operation
import com.ecency.core.hive.PrivateKey
import com.ecency.core.hive.Transaction
import com.ecency.core.hive.Vote
import com.ecency.core.hive.hexToBytes
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** TaPoS fields for a new transaction. */
data class TxReference(val refBlockNum: Int, val refBlockPrefix: Long)

/** A transaction ready to send: [json] is exactly what goes into broadcast_transaction's params. */
class SignedTransaction(val transaction: Transaction, val signatures: List<String>) {
    val id: String get() = transaction.id()
    val json: JsonObject get() = OperationJson.signedTransaction(transaction, signatures)

    /** Bytes on chain: the serialized transaction plus its signatures. */
    val size: Int get() = transaction.serialize().size + 1 + signatures.size * 65
}

data class BroadcastResult(val txId: String)

/**
 * One logical broadcast that may take several sends. It keeps its signed transaction, so
 * [send] after a network failure re-sends the SAME transaction (Hive deduplicates it by id)
 * rather than signing a new one. Only once that transaction can no longer be accepted (near
 * expiry) is it re-signed, and then only after the chain said it did not land
 * (`transaction_status_api.find_transaction` answers expired; a reply is also looked up with
 * get_content). While the chain cannot say, the send fails with [BroadcastException.Network]
 * `undecided` and nothing new is signed, so no operation (a transfer included) goes out twice.
 */
class PendingBroadcast internal constructor(
    private val broadcaster: HiveBroadcaster,
    val operations: List<Operation>,
    private val key: PrivateKey,
    internal val idempotencyKey: String?,
) {
    private val mutex = Mutex()

    /** The transaction currently being sent, once signed. */
    var signed: SignedTransaction? = null
        internal set

    /** True after a failure that might have happened after a node took the transaction. */
    var mayHaveLanded: Boolean = false
        internal set

    /** Set once the broadcast succeeded (or was confirmed to have landed). */
    var result: BroadcastResult? = null
        internal set

    /**
     * Until when (epoch millis, device clock) the signed transaction may still be re-sent as it is; null before it is
     * signed. Past it, an undecided send is only looked up on chain.
     */
    val resendDeadline: Long? get() = signed?.let { broadcaster.failoverDeadline(it) }

    /** Sends (or re-sends) this broadcast. Returns the earlier result if it already succeeded. */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun send(): BroadcastResult = mutex.withLock { result ?: broadcaster.drive(this, key) }
}

/**
 * Builds, signs and broadcasts transactions the way @ecency/sdk's hive-tx does:
 * a TaPoS reference [TAPOS_DEPTH] blocks behind head (falling back to head), expiration
 * [expirationMillis] from now, `condenser_api.broadcast_transaction` (async: returns once a
 * node accepts it), and a "Duplicate transaction" answer read as success.
 *
 * [vote] and [comment] are idempotent across retries: while an earlier attempt with the same
 * arguments (or the same draft) failed with [BroadcastException.Network], calling them again
 * continues that attempt instead of signing a second transaction.
 *
 * Every public function throws only [BroadcastException] (or cancellation).
 */
@OptIn(ExperimentalTime::class)
class HiveBroadcaster(
    private val api: HiveApi,
    private val expirationMillis: Long = 60_000,
    private val chainId: ByteArray = HIVE_CHAIN_ID,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val inFlightMutex = Mutex()
    private val inFlight = LinkedHashMap<String, PendingBroadcast>()

    /** Action ids closed as [BroadcastException.Unconfirmed]: their tx id and when. */
    private val tombstones = LinkedHashMap<String, Pair<String, Long>>()

    /** The block reference for a new transaction (SDK getTransactionReference). */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun reference(): TxReference = mapped(null) { referenceInternal() }

    /** Builds and signs; no network beyond [reference]. Validates operations and size. */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun sign(operations: List<Operation>, key: PrivateKey): SignedTransaction = mapped(null) { signInternal(operations, key) }

    /**
     * A broadcast handle: nothing is sent until [PendingBroadcast.send]. Keep it to retry a
     * failed send safely.
     */
    fun prepare(operations: List<Operation>, key: PrivateKey): PendingBroadcast = PendingBroadcast(this, operations, key, null)

    /** Signs and sends once. On [BroadcastException.Network] retry through its `pending` handle. */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun broadcast(operations: List<Operation>, key: PrivateKey): BroadcastResult = prepare(operations, key).send()

    /** [weight] in basis points: 10000 is a full upvote, negative is a downvote, 0 removes the vote. */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun vote(voter: String, author: String, permlink: String, weight: Int, postingKey: PrivateKey): BroadcastResult {
        val op = mapped(null) { Vote(voter, author, permlink, weight) }
        return idempotent("vote/$voter/$author/$permlink/$weight", listOf(op), postingKey, actionScoped = false)
    }

    /**
     * Broadcasts a reply built once with [Comments.reply] (comment, plus comment_options when
     * needed). Retrying with the same draft never posts twice.
     */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun comment(draft: CommentDraft, postingKey: PrivateKey): BroadcastResult =
        idempotent("comment/${draft.author}/${draft.permlink}", draft.operations, postingKey, actionScoped = true)

    /**
     * Broadcasts [operations] under [id]: a later call with the same id and operations after a
     * [BroadcastException.Network] failure resumes the same signed transaction, as [vote] and [comment] do.
     * The app's signing backend uses the operations' JSON as the id, so retrying an action never sends it twice.
     */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun broadcastIdempotent(id: String, operations: List<Operation>, key: PrivateKey): BroadcastResult =
        idempotent(id, operations, key, actionScoped = false)

    /**
     * As [broadcastIdempotent], for an [id] naming one user action (a transfer the user confirmed once): only a
     * retry of that action resumes it, however late, and once it ended [BroadcastException.Unconfirmed] the same
     * id never sends again (for [TOMBSTONE_MILLIS]). Value-moving operations go through here.
     */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun broadcastAction(id: String, operations: List<Operation>, key: PrivateKey): BroadcastResult =
        idempotent(id, operations, key, actionScoped = true)

    private suspend fun idempotent(id: String, operations: List<Operation>, key: PrivateKey, actionScoped: Boolean): BroadcastResult {
        val pending = inFlightMutex.withLock {
            val now = nowMillis()
            tombstones.entries.removeAll { now - it.value.second > TOMBSTONE_MILLIS }
            tombstones[id]?.let { throw BroadcastException.Unconfirmed(it.first) }
            val existing = inFlight[id]?.takeIf { it.operations == operations }
                // a key that only names the operations (not an action): long after its transaction expired, the same
                // operations again are a new action, not a retry of the old one
                // (only for operations whose repetition changes nothing; anything else stays the retry it may be)
                ?.takeIf { actionScoped || !RepeatSafeOps.all(operations) || !it.agedOut(now) }
            existing ?: PendingBroadcast(this, operations, key, id).also {
                inFlight.remove(id)
                inFlight[id] = it
                while (inFlight.size > MAX_IN_FLIGHT) inFlight.remove(inFlight.keys.first())
            }
        }
        try {
            return pending.send().also { forget(pending) }
        } catch (e: BroadcastException.Network) {
            throw e // kept, so the next identical call resumes it
        } catch (e: BroadcastException.Unconfirmed) {
            forget(pending)
            if (actionScoped) {
                inFlightMutex.withLock {
                    tombstones[id] = e.txId to nowMillis()
                    while (tombstones.size > MAX_IN_FLIGHT) tombstones.remove(tombstones.keys.first())
                }
            }
            throw e
        } catch (e: Throwable) {
            forget(pending)
            throw e
        }
    }

    /**
     * Drops the retries kept for [signer], an account that left the device: each holds its signing key. Every
     * retry id names its signer second (`vote/<voter>/...`, `comment/<author>/...`, the app's `act/<user>/...` and
     * `ops/<user>/...`). Tombstones stay: they hold no key and still keep a closed action from sending again.
     */
    @Throws(CancellationException::class)
    suspend fun forgetAccount(signer: String) {
        inFlightMutex.withLock { inFlight.keys.removeAll { it.split('/').getOrNull(1) == signer } }
    }

    /** True while a retry (and its key) is kept under [id]. For tests. */
    internal suspend fun keeps(id: String): Boolean = inFlightMutex.withLock { id in inFlight }

    /** Drops every kept retry and the keys they hold (a wipe, or every account left). */
    @Throws(CancellationException::class)
    suspend fun clear() {
        inFlightMutex.withLock { inFlight.clear() }
    }

    /** Past [AGE_OUT_MILLIS] after its transaction's expiration: the chain has long decided (or never will). */
    private fun PendingBroadcast.agedOut(now: Long): Boolean = signed?.let { now >= expiresAt(it) + AGE_OUT_MILLIS } ?: false

    private fun expiresAt(tx: SignedTransaction): Long = ChainTime.toEpochSeconds(tx.transaction.expiration) * 1000

    private suspend fun forget(pending: PendingBroadcast) {
        val id = pending.idempotencyKey ?: return
        inFlightMutex.withLock { if (inFlight[id] === pending) inFlight.remove(id) }
    }

    /** The send loop behind [PendingBroadcast.send]. */
    internal suspend fun drive(p: PendingBroadcast, key: PrivateKey): BroadcastResult = mapped(p) {
        var resignedAfterExpiry = false
        while (true) {
            var tx = p.signed
            val stale = tx == null || nowMillis() >= failoverDeadline(tx)
            if (stale) {
                // An earlier send may be on chain: sign anew only once the chain says it is not (never a second transfer)
                val earlier = tx
                if (p.mayHaveLanded && earlier != null) {
                    when (landed(p, earlier)) {
                        Landing.LANDED -> return@mapped succeed(p, earlier.id)
                        Landing.UNDECIDED -> throw undecided(p, earlier, tooOld = false)
                        Landing.NOT_EXPIRED -> throw notExpired(p, earlier)
                        Landing.TOO_OLD -> throw undecided(p, earlier, tooOld = true)
                        Landing.NOT_LANDED -> {}
                    }
                }
                tx = signInternal(p.operations, key)
                p.signed = tx
            }
            try {
                api.client.broadcast("condenser_api.broadcast_transaction", buildJsonArray { add(tx.json) }, failoverDeadline(tx))
                return@mapped succeed(p, tx.id)
            } catch (e: RpcErrorException) {
                // The chain already has this very transaction (a retry, or a double tap): it is in.
                if (e.rpcMessage.contains("Duplicate transaction", ignoreCase = true)) return@mapped succeed(p, tx.id)
                val typed = BroadcastException.from(e)
                // An earlier send of the same vote may have landed: an identical vote is our own.
                if (typed is BroadcastException.DuplicateVote && p.mayHaveLanded) return@mapped succeed(p, tx.id)
                // Expired (or unknown TaPoS) before a resend got through: an earlier send may still be in. Re-sign once,
                // and only after the chain said that one is not.
                if (p.mayHaveLanded && !resignedAfterExpiry && BroadcastException.isExpiredOrTapos(e)) {
                    when (landed(p, tx)) {
                        Landing.LANDED -> return@mapped succeed(p, tx.id)
                        Landing.UNDECIDED -> throw undecided(p, tx, tooOld = false)
                        Landing.NOT_EXPIRED -> throw notExpired(p, tx)
                        Landing.TOO_OLD -> throw undecided(p, tx, tooOld = true)
                        Landing.NOT_LANDED -> {}
                    }
                    resignedAfterExpiry = true
                    p.signed = null
                    continue
                }
                // Any other rejection of a resend (a lagging node's RC or balance assert, say) may be about a
                // transaction an earlier send already put in: only the chain decides. In: success. Unknown: undecided,
                // the pending stays and nothing is signed. Out: the rejection is the answer.
                if (p.mayHaveLanded) {
                    when (landed(p, tx)) {
                        Landing.LANDED -> return@mapped succeed(p, tx.id)
                        Landing.UNDECIDED -> throw undecided(p, tx, tooOld = false)
                        Landing.NOT_EXPIRED -> throw notExpired(p, tx)
                        Landing.TOO_OLD -> throw undecided(p, tx, tooOld = true)
                        Landing.NOT_LANDED -> {}
                    }
                }
                throw typed
            } catch (e: HiveApiException) {
                if (e !is NodeFailureException || e.mayHaveReachedNode) p.mayHaveLanded = true
                throw BroadcastException.from(e, p)
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    /**
     * The chain cannot say whether [tx] went in: nothing is signed. That ends as [BroadcastException.Unconfirmed]
     * only when [tx] can no longer be valid by the CHAIN's clock: the status API called it too old, or the chain's
     * head time is past its expiration plus [AGE_OUT_MILLIS] (the device clock may be anything). Until then it is a
     * retryable [BroadcastException.Network] (undecided), and the user is never told to start again while [tx]
     * may still go in.
     */
    /**
     * The status API said "unknown" for [tx] with its expiration given: the chain's head is still before that expiration,
     * so it cannot be past it plus [AGE_OUT_MILLIS] either. Undecided, and the chain's head is not read.
     */
    private fun notExpired(p: PendingBroadcast, tx: SignedTransaction): BroadcastException =
        BroadcastException.Network(IllegalStateException("transaction ${tx.id} not found yet"), p, undecided = true)

    private suspend fun undecided(p: PendingBroadcast, tx: SignedTransaction, tooOld: Boolean): BroadcastException {
        val cause = IllegalStateException("transaction ${tx.id} not found yet")
        if (tooOld) return BroadcastException.Unconfirmed(tx.id, cause)
        val chainMillis = try {
            ChainTime.toEpochSeconds(api.chainHead().time) * 1000
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        return if (chainMillis != null && chainMillis >= expiresAt(tx) + AGE_OUT_MILLIS) BroadcastException.Unconfirmed(tx.id, cause)
        else BroadcastException.Network(cause, p, undecided = true)
    }

    private fun succeed(p: PendingBroadcast, txId: String) = BroadcastResult(txId).also { p.result = it }

    private enum class Landing { LANDED, NOT_LANDED, UNDECIDED, NOT_EXPIRED, TOO_OLD }

    /**
     * Did [tx], sent before with an uncertain outcome, land? A reply found on chain is in (its comment_options
     * went in the same transaction). Otherwise the chain's transaction status decides: in the mempool or a block
     * is in, irreversibly expired is out; "expired_reversible", "unknown" (not expired yet for that node),
     * "too_old" or a failed lookup cannot decide, and nothing new is signed then.
     */
    private suspend fun landed(p: PendingBroadcast, tx: SignedTransaction): Landing {
        try {
            val comment = p.operations.filterIsInstance<Comment>().firstOrNull()
            if (comment != null && api.contentExists(comment.author, comment.permlink)) return Landing.LANDED
            return when (api.transactionStatus(tx.id, tx.transaction.expiration)) {
                "within_mempool", "within_reversible_block", "within_irreversible_block" -> Landing.LANDED
                // only an irreversible expiry is final (hive-tx checkStatus): a reversible one can still change
                "expired_irreversible" -> Landing.NOT_LANDED
                // past the status API's window: the transaction is long invalid, but whether it went in is unknowable
                "too_old" -> Landing.TOO_OLD
                // not found and, by the chain's clock, not expired yet (the expiration is always given)
                "unknown" -> Landing.NOT_EXPIRED
                else -> Landing.UNDECIDED
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Landing.UNDECIDED
        }
    }

    /** Until when a send of [tx] may still fail over or be re-sent (hive-tx: expiry - timeout - 5 s). */
    internal fun failoverDeadline(tx: SignedTransaction): Long =
        ChainTime.toEpochSeconds(tx.transaction.expiration) * 1000 - api.client.broadcastTimeoutMillis - 5_000

    private suspend fun referenceInternal(): TxReference {
        val head = api.chainHead()
        var refNum = head.headBlockNumber
        var refId = head.headBlockId
        try {
            val previous = api.previousBlockId(refNum - TAPOS_DEPTH + 1)
            if (previous != null && BLOCK_ID.matches(previous) && previous.take(8).toLong(16) == refNum - TAPOS_DEPTH) {
                refNum -= TAPOS_DEPTH
                refId = previous
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Keep the head block; the transaction is only more likely to hit a lagging node.
        }
        return referenceOf(refNum, refId)
    }

    private suspend fun signInternal(operations: List<Operation>, key: PrivateKey): SignedTransaction {
        if (operations.isEmpty()) throw BroadcastException.InvalidOperation("a transaction needs at least one operation")
        val ref = referenceInternal()
        val expiration = ChainTime.fromEpochSeconds((nowMillis() + expirationMillis) / 1000)
        val tx = Transaction(ref.refBlockNum, ref.refBlockPrefix, expiration, operations)
        val signed = SignedTransaction(tx, listOf(tx.sign(key, chainId).toHex()))
        if (signed.size > MAX_TRANSACTION_SIZE) throw BroadcastException.TooLarge(signed.size)
        return signed
    }

    /** Lets only [BroadcastException] and cancellation out. */
    private inline fun <T> mapped(pending: PendingBroadcast?, block: () -> T): T = try {
        block()
    } catch (e: BroadcastException) {
        throw e
    } catch (e: CancellationException) {
        throw e
    } catch (e: HiveApiException) {
        throw BroadcastException.from(e, pending)
    } catch (e: IllegalArgumentException) {
        throw BroadcastException.InvalidOperation(e.message ?: "invalid operation", e)
    } catch (e: Exception) {
        throw BroadcastException.Rejected("unexpected error: ${e.message ?: e::class.simpleName}", e)
    }

    companion object {
        /** Blocks behind head to reference: a block every live node has (see hive-tx). */
        const val TAPOS_DEPTH = 3

        /** hived's HIVE_MAX_TRANSACTION_SIZE. */
        const val MAX_TRANSACTION_SIZE = 64 * 1024

        private const val MAX_IN_FLIGHT = 64

        /**
         * Chain time after a transaction's expiration when an undecided one is closed as unconfirmed (irreversibility
         * is about a minute); on the device's clock, when an operations-keyed pending of repeat-safe operations stops
         * being a retry.
         */
        const val AGE_OUT_MILLIS = 10 * 60_000L

        /** How long a closed action id refuses to send again. */
        const val TOMBSTONE_MILLIS = 60 * 60_000L

        private val BLOCK_ID = Regex("^[0-9a-f]{40}$")

        /** ref_block_num is the low 16 bits; ref_block_prefix is bytes 4..7 of the id, little-endian. */
        fun referenceOf(blockNumber: Long, blockId: String): TxReference {
            val bytes = blockId.hexToBytes()
            require(bytes.size >= 8) { "invalid block id" }
            var prefix = 0L
            for (i in 7 downTo 4) prefix = (prefix shl 8) or (bytes[i].toLong() and 0xff)
            return TxReference((blockNumber and 0xffff).toInt(), prefix)
        }
    }
}

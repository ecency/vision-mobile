package com.ecency.core.app

import com.ecency.core.api.broadcast.BroadcastException
import com.ecency.core.api.broadcast.PendingBroadcast
import com.ecency.core.auth.AuthException
import com.ecency.core.auth.SigningMethod
import com.ecency.core.auth.loginMethod
import com.ecency.core.hive.KeyRole
import com.ecency.core.l10n.L
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlin.coroutines.cancellation.CancellationException

/** Where one wallet send is. */
sealed interface SendPhase {
    /** Signing (a prompt may show: auth upgrade, HiveSigner page, HiveAuth wallet) and broadcasting. */
    data object Sending : SendPhase

    /**
     * The send may already have gone through and the chain has not said yet. The app keeps asking (a lookup can
     * take about 30 s) and shows a spinner with [message]; there is no retry button: nothing new is signed until
     * the chain said the earlier transaction did not land.
     */
    data class Checking(val message: String = BroadcastException.UNDECIDED_MESSAGE) : SendPhase

    /** Accepted by a node ([txId] null when the signer did not report one). */
    data class Done(val txId: String?, val method: SigningMethod) : SendPhase

    /**
     * The chain cannot tell any more whether it went in (BroadcastException.Unconfirmed), or it could not be reached
     * for [WalletSends.CHECK_BOUND_MILLIS]: the user checks the wallet; the action never sends again.
     */
    data class Unconfirmed(val message: String) : SendPhase

    /** Refused or failed before anything could have landed. [retryable]: the same action may be sent again. */
    data class Failed(val error: AppError, val retryable: Boolean) : SendPhase

    /** A signing prompt was closed: nothing was sent. */
    data object Cancelled : SendPhase
}

/** One user action (a confirmed transfer, power up, claim...): its id is made once, at the confirmation. */
data class WalletSend(val actionId: String, val username: String, val operation: String, val phase: SendPhase) {
    val isFinal: Boolean get() = phase is SendPhase.Done || phase is SendPhase.Unconfirmed || phase is SendPhase.Cancelled || (phase is SendPhase.Failed)
}

data class WalletSendsState(val sends: Map<String, WalletSend> = emptyMap())

/**
 * Every value-moving broadcast of the wallet area (and of hive:// links) goes through here: [send] takes the operations
 * and the action id made when the user confirmed, and runs the broadcast in the app's scope, so leaving the screen does
 * not stop it; a send whose screen went away ([disown]) reports its outcome as a toast.
 * An undecided outcome (the transaction may have landed) is followed up by itself ([SendPhase.Checking]): the same
 * signed transaction is asked about, or re-sent while still valid, through [SigningModel.resume], until the chain
 * decides; that may take until its expiration plus 10 minutes. Screens watch [state] by their action id.
 *
 * A retry of a [SendPhase.Failed] send ([retry]) is the same action: same id, same operations, so a key account
 * resumes its transaction instead of signing a second one.
 */
class WalletSends internal constructor(private val env: AppEnvironment) : StateModel<WalletSendsState>(WalletSendsState(), env.dispatchers) {
    private class Running(val ops: JsonArray, val authority: KeyRole, var pending: PendingBroadcast? = null, var job: Job? = null)

    private val running = mutableMapOf<String, Running>()

    /** Screens (and callers with an onDone) that show a send; a send nobody owns any more reports itself ([orphanDone]). */
    private val owners = mutableMapOf<String, Int>()

    /**
     * Main thread. Starts [actionId]'s broadcast; a second call with the same id while it runs does nothing. The caller
     * owns it until [disown] ([onDone] callers own it for good, they hear the outcome).
     */
    fun send(username: String, ops: JsonArray, authority: KeyRole, actionId: String, onDone: (suspend (WalletSend) -> Unit)? = null) {
        if (running[actionId]?.job?.isActive == true) return
        if (current.sends[actionId]?.phase is SendPhase.Done) return
        val operation = SigningModel.operationName(ops)
        owners[actionId] = (owners[actionId] ?: 0) + 1
        // the restart journal keeps every undecided send; when it is full of them, nothing new that could join them goes out
        if (env.sendJournal.current.entries.count { it.outcome == null } >= SendJournal.MAX_ENTRIES) {
            setState { it.copy(sends = it.sends + (actionId to WalletSend(actionId, username, operation, SendPhase.Failed(AppError(ErrorKind.UNKNOWN, JOURNAL_FULL, false), retryable = false)))) }
            env.background.launch { onDone?.invoke(current.sends.getValue(actionId)) }
            return
        }
        val r = running.getOrPut(actionId) { Running(ops, authority) }
        r.job = env.background.launch {
            set(WalletSend(actionId, username, operation, SendPhase.Sending))
            val final = drive(username, r, actionId, operation)
            // one main-thread step: the outcome, and who reports it. A screen that disowns right after sees the final
            // phase and releases it; one that disowned before leaves it to the toast. Never both.
            kotlinx.coroutines.withContext(env.dispatchers.main) {
                setState { it.copy(sends = it.sends + (actionId to final)) }
                if (final.isFinal && final.phase !is SendPhase.Failed) running.remove(actionId)
                if (onDone == null && (owners[actionId] ?: 0) == 0) orphanDone(final)
            }
            // an onDone caller hears the outcome itself
            onDone?.invoke(final)
        }
    }

    /** The screen of [actionId] went away. A send still running goes on; its outcome is then toasted ([orphanDone]). */
    fun disown(actionId: String) {
        val left = (owners[actionId] ?: 0) - 1
        if (left > 0) owners[actionId] = left else owners.remove(actionId)
        val s = current.sends[actionId] ?: return
        if (left <= 0 && s.isFinal) release(actionId)
    }

    /** A send whose screen is gone finished: one toast with its outcome, then it is forgotten. */
    private fun orphanDone(s: WalletSend) {
        val text = when (val p = s.phase) {
            is SendPhase.Done -> WalletCopy.SUCCESSFUL
            is SendPhase.Failed -> WalletCopy.operationFailed(WalletCopy.operationLabel(s.operation).replaceFirstChar { it.uppercase() }, p.error.message)
            is SendPhase.Unconfirmed -> p.message
            else -> null
        }
        text?.let { env.ui.toast(it) }
        release(s.actionId)
    }

    /** A [SendPhase.Failed] send with `retryable`: the same action again. */
    fun retry(actionId: String, onDone: (suspend (WalletSend) -> Unit)? = null) {
        val s = current.sends[actionId] ?: return
        val r = running[actionId] ?: return
        val phase = s.phase
        if (phase !is SendPhase.Failed || !phase.retryable) return
        owners[actionId] = ((owners[actionId] ?: 1) - 1).coerceAtLeast(0)
        send(s.username, r.ops, r.authority, actionId, onDone)
    }

    /** Forgets a finished send. A send still running is not stopped. */
    fun release(actionId: String) {
        val s = current.sends[actionId] ?: return
        if (!s.isFinal) return
        running.remove(actionId)
        setState { it.copy(sends = it.sends - actionId) }
    }

    fun phase(actionId: String): SendPhase? = current.sends[actionId]?.phase

    /**
     * Accounts left the device (logout, logout-all, wipe; null: every account): their sends stop here. A pending holds
     * the account's key in memory, and nothing may sign for an account that is gone; what was already sent is on chain or
     * not, as the chain decides.
     */
    internal fun accountsLeft(usernames: List<String>?) {
        val gone = current.sends.values.filter { usernames == null || it.username in usernames }.map { it.actionId }
        gone.forEach { id ->
            running.remove(id)?.job?.cancel()
            owners.remove(id)
        }
        setState { s -> s.copy(sends = s.sends - gone.toSet()) }
    }

    private suspend fun drive(username: String, r: Running, actionId: String, operation: String): WalletSend {
        fun at(phase: SendPhase) = WalletSend(actionId, username, operation, phase)
        // RN: the PIN before a new signature when the app lock is on (a resume signs nothing new); no PIN, nothing sent
        if (r.pending == null && !WalletPin.confirmed(env, r.ops)) return at(SendPhase.Cancelled)
        val started = env.nowMillis()
        var lookups = 0
        val attempt: suspend () -> com.ecency.core.auth.SignedBroadcast = {
            val p = r.pending
            if (p != null) env.signing.resume(username, actionId, operation, p) else env.signing.broadcast(username, r.ops, r.authority, actionId)
        }
        while (true) {
            try {
                val result = attempt()
                return at(SendPhase.Done(result.txId, result.method))
            } catch (e: CancellationException) {
                throw e
            } catch (e: AuthException.Cancelled) {
                // the HiveSigner page closed by the user: HiveSigner may have broadcast before it was closed
                if (e.mayHaveSigned) return at(SendPhase.Failed(AppError(ErrorKind.NETWORK, BroadcastException.UNDECIDED_MESSAGE, false, undecided = true), retryable = false))
                return at(SendPhase.Cancelled)
            } catch (e: BroadcastException.Unconfirmed) {
                return at(SendPhase.Unconfirmed(e.message ?: BroadcastException.UNCONFIRMED_MESSAGE))
            } catch (e: BroadcastException.Network) {
                val pending = e.pending
                if (pending == null || !(pending.mayHaveLanded || e.undecided)) {
                    // never reached a node: nothing can have landed; the same action may be tried again
                    r.pending = pending
                    return at(SendPhase.Failed(AppError.from(e), retryable = true))
                }
                r.pending = pending
                if (env.nowMillis() - started > CHECK_BOUND_MILLIS) {
                    return at(SendPhase.Unconfirmed(CHECK_LATER))
                }
                set(at(SendPhase.Checking()))
                // inside the resend window the same transaction is re-sent every few seconds; after it only the
                // chain's status is asked, less and less often
                val resendable = pending.resendDeadline?.let { env.nowMillis() < it } ?: false
                delay(if (resendable) RECHECK_MILLIS else lookupDelay(lookups++))
            } catch (e: Exception) {
                // HiveSigner and HiveAuth sign elsewhere: once their request may have been answered, a failure here (a lost
                // relay, a token call without an answer) says nothing about the chain. The user checks the wallet.
                if (signsElsewhere(username) && mayHaveReachedSigner(e)) {
                    return at(SendPhase.Failed(AppError(ErrorKind.NETWORK, BroadcastException.UNDECIDED_MESSAGE, false, undecided = true), retryable = false))
                }
                return at(SendPhase.Failed(AppError.from(e), retryable = false))
            }
        }
    }

    private suspend fun signsElsewhere(username: String): Boolean = try {
        val m = env.accounts.vaultAccount(username)?.authType?.loginMethod()
        m == com.ecency.core.auth.LoginMethod.HIVESIGNER || m == com.ecency.core.auth.LoginMethod.HIVEAUTH
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    /** Refusals before any request left the app are definite; anything else after a request may have been answered is not. */
    private fun mayHaveReachedSigner(e: Exception): Boolean =
        e !is AuthException.NotSignedIn && e !is AuthException.MissingKey && e !is BroadcastException.InvalidOperation &&
            e !is com.ecency.core.api.InvalidInputException && !(e is AuthException.HiveAuth && e.reason in DEFINITE_HIVEAUTH)

    private suspend fun set(send: WalletSend) = kotlinx.coroutines.withContext(env.dispatchers.main) {
        setState { it.copy(sends = it.sends + (send.actionId to send)) }
    }

    companion object {
        /** Pause between two re-sends of an undecided send while its transaction can still be accepted. */
        const val RECHECK_MILLIS = 5_000L

        /** Pauses between status lookups once re-sending is over: 5, 10, 20 s, then every 30 s. */
        private val LOOKUP_MILLIS = longArrayOf(5_000L, 10_000L, 20_000L, 30_000L)

        /** The pause before status lookup number [n] (0 based) of an undecided send; also the journal's at launch. */
        fun lookupDelay(n: Int): Long = LOOKUP_MILLIS[n.coerceIn(0, LOOKUP_MILLIS.size - 1)]

        /** After this long without an answer the screen stops waiting; the restart journal keeps asking at launch. */
        const val CHECK_BOUND_MILLIS = 15 * 60_000L

        val CHECK_LATER: String get() = L.native.walletSends.checkLater

        /** HiveAuth answers that say the wallet did not sign (or was never asked). */
        private val DEFINITE_HIVEAUTH = setOf(
            AuthException.HiveAuth.Reason.REJECTED, AuthException.HiveAuth.Reason.NOT_CONNECTED, AuthException.HiveAuth.Reason.SESSION_EXPIRED,
            AuthException.HiveAuth.Reason.NOT_BROADCAST,
        )

        val JOURNAL_FULL: String get() = L.native.walletSends.journalFull
    }
}

/** The sends one screen owns: each is disowned once, when the screen handled its outcome or went away. Main thread. */
internal class SendOwnership(private val sends: WalletSends) {
    private val owned = mutableSetOf<String>()

    fun owns(actionId: String) {
        owned += actionId
    }

    fun done(actionId: String) {
        if (owned.remove(actionId)) sends.disown(actionId)
    }

    fun closeAll() = owned.toList().forEach(::done)
}

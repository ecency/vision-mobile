package com.ecency.core.app

import com.ecency.core.api.broadcast.Amounts
import com.ecency.core.auth.OperationAuthority
import com.ecency.core.api.broadcast.WalletOps
import com.ecency.core.l10n.L
import com.ecency.core.l10n.LocaleFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.floor

/** Screen text of RC delegation (README "Wallet": Delegate RC). Read when shown. */
object RcDelegateCopy {
    /** The profile menu item, upper case as its neighbours (RN user.delegate "DELEGATE HIVE"). */
    val MENU_ITEM: String get() = L.native.rcDelegation.menuItem
    val CONFIRM_TITLE: String get() = L.native.rcDelegation.confirmTitle
    val AMOUNT_LABEL: String get() = L.native.rcDelegation.amountLabel
    /** After the typed number inside the iOS field (Android keeps [AMOUNT_LABEL] as the field's floating label). */
    val UNIT: String get() = L.native.rcDelegation.unit
    val DELEGATE: String get() = L.native.rcDelegation.delegate
    val REMOVE: String get() = L.native.rcDelegation.remove
    val SAME: String get() = L.native.rcDelegation.same
    val LOAD_FAILED: String get() = L.native.rcDelegation.loadFailed
    fun title(username: String): String = L.native.rcDelegation.title(username = username)
    fun intro(username: String): String = L.native.rcDelegation.intro(username = username)
    fun available(rc: Long): String = L.native.rcDelegation.available(amount = billions(rc))
    fun current(username: String, rc: Long): String = L.native.rcDelegation.current(username = username, amount = billions(rc))
    val MINIMUM: String get() = L.native.rcDelegation.minimum(amount = billions(RcDelegation.MIN_RC))
    fun problem(problem: RcAmountProblem, maxTotal: Long): String = when (problem) {
        RcAmountProblem.TOO_LOW -> L.native.rcDelegation.tooLow(amount = billions(RcDelegation.MIN_RC))
        RcAmountProblem.TOO_MUCH -> L.native.rcDelegation.tooMuch(amount = billions(maxTotal))
        RcAmountProblem.SAME -> SAME
    }
    /** The amounts exactly as they will be signed (never rounded: what is approved is what is sent). */
    fun confirm(review: RcDelegatePrompt.Review, existing: Long): String =
        if (review.remove) L.native.rcDelegation.confirmRemove(amount = exactBillions(existing), username = review.to)
        else L.native.rcDelegation.confirm(amount = exactBillions(review.maxRc), username = review.to)

    /** RC as billions in the app language, every digit kept (5_009_000_000 is "5.009"). */
    fun exactBillions(rc: Long): String {
        val whole = rc / 1_000_000_000
        val fraction = (rc % 1_000_000_000).toString().padStart(9, '0').trimEnd('0')
        return LocaleFormat.posix(if (fraction.isEmpty()) "$whole" else "$whole.$fraction", true)
    }

    /** RC as billions in the app language, at most two decimals, rounded down (never more than there is). */
    fun billions(rc: Long): String = LocaleFormat.decimal(floor(rc / 1e7) / 100, 2, 0)
}

object RcDelegation {
    /** The least a delegation may be, as web asks (the chain's own minimum, a third of the account creation fee in VESTS, is lower). */
    const val MIN_RC: Long = 5_000_000_000

    private const val BILLION: Long = 1_000_000_000

    /** RC of [typed] billions, exact (up to nine decimals, one RC); null when it is not a number. */
    fun rcOf(typed: String): Long? {
        // as typed: "5", "5.", ".5" (no digit at all is no number)
        if (!Regex("^[0-9]{0,9}(\\.[0-9]{0,9})?$").matches(typed) || typed.none { it.isDigit() }) return null
        val whole = typed.substringBefore('.').ifEmpty { "0" }.toLong()
        val fraction = typed.substringAfter('.', "").padEnd(9, '0').ifEmpty { "0" }.toLong()
        return whole * BILLION + fraction
    }
}

/** Why a typed amount cannot be sent. */
enum class RcAmountProblem { TOO_LOW, TOO_MUCH, SAME }

sealed interface RcDelegatePrompt {
    /** Delegate [maxRc] to [to] as the new total ([remove]: 0, the delegation goes). Nothing is sent before [RcDelegateModel.confirm]. */
    data class Review(val to: String, val maxRc: Long, val remove: Boolean) : RcDelegatePrompt
}

data class RcDelegateState(
    val to: String,
    val from: String? = null,
    /** RC the account can delegate now ([com.ecency.core.api.model.RcDelegator.delegable]); null until read. */
    val available: Long? = null,
    /** What [to] has from this account now; the operation's amount replaces it. */
    val existing: Long = 0,
    val loadFailed: Boolean = false,
    /** Billions of RC as typed (POSIX: dot, ASCII digits). */
    val amount: String = "",
    val prompt: RcDelegatePrompt? = null,
    val actionId: String? = null,
    val phase: SendPhase? = null,
    val closed: Boolean = false,
) {
    /** The largest new total: what can be delegated plus what [to] already has (the chain counts only the change). */
    val maxTotal: Long get() = (available ?: 0).let { if (it > Long.MAX_VALUE - existing) Long.MAX_VALUE else it + existing }

    val amountRc: Long? get() = RcDelegation.rcOf(amount)

    val problem: RcAmountProblem?
        get() {
            val rc = amountRc ?: return null
            return when {
                rc < RcDelegation.MIN_RC -> RcAmountProblem.TOO_LOW
                rc == existing -> RcAmountProblem.SAME
                rc > maxTotal -> RcAmountProblem.TOO_MUCH
                else -> null
            }
        }

    val busy: Boolean get() = phase is SendPhase.Sending || phase is SendPhase.Checking

    private val ready: Boolean get() = from != null && available != null && !busy && actionId == null

    val reviewEnabled: Boolean get() = ready && amountRc != null && problem == null

    /** An existing delegation can be taken back (max_rc 0). */
    val canRemove: Boolean get() = ready && existing > 0
}

/**
 * Delegate RC (owner request, beyond RN, whose `useDelegateRcMutation` had no screen): from someone else's profile
 * menu, [RcDelegateState.to]'s new total of this account's RC as SDK buildDelegateRcOp writes it, a posting custom_json.
 * The account's delegable RC and its current delegation to [RcDelegateState.to] are read first; the amount is typed in
 * billions, at least [RcDelegation.MIN_RC], within what can be delegated. Sent through [WalletSends] with one action id
 * per confirmation, so a retry resends the same transaction.
 */
class RcDelegateModel internal constructor(private val env: AppEnvironment, to: String) :
    StateModel<RcDelegateState>(RcDelegateState(to = to), env.dispatchers) {
    private val owned = SendOwnership(env.walletSends)
    private var started = false

    /** A send still running goes on without the sheet and reports its outcome itself. */
    override fun close() {
        owned.closeAll()
        super.close()
    }

    fun start() {
        if (started) return
        started = true
        scope.launch {
            env.session.awaitLoaded()
            val user = env.session.current.username ?: return@launch
            setState { it.copy(from = user) }
            try {
                val rc = env.api.hive.rcDelegator(user)
                val existing = env.api.hive.rcDelegated(user, current.to)
                if (rc == null) {
                    setState { it.copy(loadFailed = true) }
                    return@launch
                }
                setState { it.copy(available = rc.delegable(env.nowMillis() / 1000), existing = existing) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ErrorReports.handled(e, "rcDelegate.start")
                setState { it.copy(loadFailed = true) }
            }
        }
        scope.launch {
            env.walletSends.state.collect { s ->
                val id = current.actionId ?: return@collect
                val phase = s.sends[id]?.phase ?: return@collect
                if (phase != current.phase) onPhase(id, phase)
            }
        }
        // another account signed in: this sheet was the previous one's
        scope.launch { env.events.collect { e -> if (e is AppEvent.SessionChanged && e.username != current.from) setState { it.copy(closed = true) } } }
    }

    /** Billions of RC: the language's comma and digits read as the dot and ASCII digits; anything else is refused as typed. */
    fun setAmount(text: String) {
        if (current.busy) return
        val t = Amounts.typedText(text.trim())
        if (t.isNotEmpty() && !Regex("^[0-9]{0,9}(\\.[0-9]{0,9})?$").matches(t)) return
        setState { it.copy(amount = t) }
    }

    fun review() {
        val s = current
        if (!s.reviewEnabled) return
        val rc = s.amountRc ?: return
        setState { it.copy(prompt = RcDelegatePrompt.Review(s.to, rc, remove = false)) }
    }

    /** Takes the whole delegation back (max_rc 0), after the review. */
    fun remove() {
        val s = current
        if (!s.canRemove) return
        setState { it.copy(prompt = RcDelegatePrompt.Review(s.to, 0, remove = true)) }
    }

    fun dismissPrompt() = setState { it.copy(prompt = null) }

    fun confirm() {
        val s = current
        val review = s.prompt as? RcDelegatePrompt.Review ?: return
        val user = s.from ?: return
        // a sheet closed by an account change never signs for the account that left
        if (s.closed || s.actionId != null || env.session.current.username != user) return
        val ops = WalletOps.ops(listOf(WalletOps.delegateRc(user, review.to, review.maxRc)))
        val actionId = env.idempotencyKey()
        sentRemove = review.remove
        setState { it.copy(prompt = null, actionId = actionId, phase = SendPhase.Sending) }
        env.walletSends.send(user, ops, OperationAuthority.required(ops), actionId)
        owned.owns(actionId)
    }

    private fun onPhase(id: String, phase: SendPhase) {
        setState { it.copy(phase = phase) }
        when (phase) {
            is SendPhase.Done -> {
                owned.done(id)
                env.ui.toast(if (sentRemove) L.native.rcDelegation.removed else L.native.rcDelegation.done)
                setState { it.copy(closed = true) }
            }
            is SendPhase.Failed -> {
                owned.done(id)
                env.ui.toast("${L.native.rcDelegation.failed}\n${phase.error.text}", phase.error)
                setState { it.copy(closed = true) }
            }
            is SendPhase.Cancelled -> { owned.done(id); setState { it.copy(actionId = null, phase = null) } }
            else -> Unit
        }
    }

    /** The confirmed review took the delegation back (the toast says so). */
    private var sentRemove = false
}

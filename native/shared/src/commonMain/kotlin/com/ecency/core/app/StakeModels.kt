package com.ecency.core.app

import com.ecency.core.api.broadcast.Amounts
import com.ecency.core.api.broadcast.WalletOps
import com.ecency.core.api.wallet.Vests
import com.ecency.core.api.wallet.WalletAccount
import com.ecency.core.api.wallet.WithdrawRoute
import com.ecency.core.auth.OperationAuthority
import com.ecency.core.hive.ChainTime
import com.ecency.core.l10n.L
import com.ecency.core.l10n.LocaleFormat
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.floor
import kotlin.math.round

/** RN en-US copy of the delegate and power down screens. */
object StakeCopy {
    val DELEGATE: String get() = L.wallet.delegate
    val AMOUNT_HP: String get() = L.transfer.amountHp
    val REMAIN_HP: String get() = L.transfer.remainHp
    val REVIEW: String get() = L.transfer.review
    val CONFIRM_TITLE: String get() = L.transfer.confirm
    val INVALID_AMOUNT: String get() = L.qr.invalidAmount
    val INVALID_AMOUNT_DESC: String get() = L.transfer.invalidAmountDesc
    val ALREADY_DELEGATED: String get() = L.transfer.alreadyDelegated

    /** "Already delegated to @bob  12.345 HP"; [hp] is the amount as shown, the ticker stays. */
    fun alreadyDelegatedTo(recipient: String, hp: String): String = L.native.stake.alreadyDelegatedTo(recipient = recipient) + "  " + hp + " HP"
    val POWER_DOWN: String get() = L.wallet.powerDown
    val POWERING_DOWN: String get() = L.transfer.poweringDown
    val POWERING_DOWN_SUB: String get() = L.transfer.poweringDownSubheading
    val ESTIMATED_WEEKLY: String get() = L.transfer.estimatedWeekly
    val STOP: String get() = L.transfer.stop
    val STOP_INFORMATION: String get() = L.transfer.stopInformation
    val WITHDRAW_ACCOUNTS: String get() = L.transfer.withdrawAccounts
    val SAME_USER: String get() = L.alert.sameUser
    val ADD_ROUTE_FAILED: String get() = L.alert.addWithdrawRouteFailed
    val REMOVE_ROUTE_FAILED: String get() = L.alert.removeWithdrawRouteFailed
    val AUTO_VESTS: String get() = L.transfer.autoVests
    val PERCENT: String get() = L.transfer.percent

    fun confirmSummary(hp: String, vests: String, to: String) = L.native.stake.confirmSummary(hp = hp, vests = vests, to = to)

    fun overwrite(prev: String) = L.transfer.confirmSummaryPara(prev = prev)

    fun nextPowerDown(days: Long, hp: String) = L.native.plurals.transfer.poweringDownInfo(days = days.toInt(), hp = hp)

    fun weekly(hive: String) = L.native.stake.weekly(hive = hive)
}

/** RN conversions: HP to 3 decimals (toFixed), VESTS to 6. */
internal object StakeMath {
    /** A typed HP amount through the one amount reading ([com.ecency.core.api.broadcast.Dec]): ".5" is 0.5, "1e3" is nothing. */
    fun hp(text: String): Double? = com.ecency.core.api.broadcast.Dec.of(text)?.toString()?.toDouble()

    /** What an HP field keeps: comma to dot, 3 decimals, and only amount text. */
    fun typedHp(previous: String, text: String): String {
        val t = Amounts.typed(text, 3)
        return if (t.isEmpty() || t == "." || com.ecency.core.api.broadcast.Dec.of(t) != null) t else previous
    }

    /** HP as field text and as quoted in a confirmation of what is signed: POSIX, 3 decimals. */
    fun hpText(hp: Double): String = Num.fixed(hp, 3)

    /** HP shown to the user: 3 decimals in the app language (toFixed, no grouping, as RN). */
    fun hpDisplay(hp: Double): String = LocaleFormat.toFixed(hp, 3)

    fun vestsText(vests: Double): String {
        val units = round(vests * 1e6).toLong().coerceAtLeast(0)
        return com.ecency.core.api.wallet.WalletApi.naiText(units, 6)
    }

    /** RN availableVestingShares: own VESTS minus delegated, minus what a running power down still takes. */
    fun availableVests(a: WalletAccount): Double {
        val left = if (a.isPoweringDown) (a.toWithdraw - a.withdrawn) / 1e6 else 0.0
        return Vests.amount(a.vestingShares) - Vests.amount(a.delegatedVestingShares) - left
    }
}

sealed interface StakePrompt {
    data class Alert(val title: String, val message: String) : StakePrompt

    /** Delegation review (RN ACTION_MODAL): [summary] and, updating one, [overwrite]. */
    data class Review(val summary: String, val overwrite: String?, val vests: String) : StakePrompt

    /** RN "Continue transaction?" for a power down. */
    data class ConfirmPowerDown(val vests: String) : StakePrompt

    data object ConfirmStop : StakePrompt

    /** Remove the route to [account]? */
    data class ConfirmRemoveRoute(val account: String) : StakePrompt
}

data class DelegateState(
    val from: String? = null,
    val recipient: String = "",
    val recipientValid: Boolean = false,
    val suggestions: List<String> = emptyList(),
    val hp: String = "",
    val availableVests: Double? = null,
    val hivePerMVests: Double = 0.0,
    val existingVests: Double = 0.0,
    val existingHp: String? = null,
    val badActor: Boolean = false,
    val prompt: StakePrompt? = null,
    val actionId: String? = null,
    val phase: SendPhase? = null,
    val closed: Boolean = false,
) {
    /** The chain reads the operation as the new total: the current delegation can be taken back too. */
    val maxVests: Double get() = (availableVests ?: 0.0) + existingVests

    val maxHp: Double get() = floor(Vests.toHp(maxVests, hivePerMVests) * 1000) / 1000

    val remainingHp: String get() = StakeMath.hpDisplay((maxHp - (StakeMath.hp(hp) ?: 0.0)).coerceAtLeast(0.0))

    val busy: Boolean get() = phase is SendPhase.Sending || phase is SendPhase.Checking

    /** RN validateHP: above 0.001 and within the max, or exactly 0 to remove an existing delegation. */
    val amountValid: Boolean
        get() {
            val v = StakeMath.hp(hp) ?: return false
            if (v == 0.0) return existingVests > 0
            return v > 0.001 && v <= maxHp
        }

    val reviewEnabled: Boolean get() = !busy && actionId == null && recipientValid && availableVests != null && hp.isNotEmpty()
}

/**
 * RN delegateScreen: HP delegation to one account (`delegate_vesting_shares`, the amount is the new total; 0 removes
 * it), with the recipient's current delegation looked up, MAX, and the RN review sheet. Sent through [WalletSends]
 * with one action id per confirmation.
 */
class DelegateModel internal constructor(private val env: AppEnvironment, referredUsername: String) :
    StateModel<DelegateState>(DelegateState(recipient = referredUsername), env.dispatchers) {
    private val owned = SendOwnership(env.walletSends)

    /** A send still running goes on without the screen and reports its outcome itself. */
    override fun close() {
        owned.closeAll()
        super.close()
    }

    private var lookup: Job? = null
    private var started = false
    private var badActors: Set<String> = emptySet()

    fun start() {
        if (started) return
        started = true
        scope.launch {
            env.session.awaitLoaded()
            val user = env.session.current.username ?: return@launch
            setState { it.copy(from = user) }
            launch { badActors = runCatching { com.ecency.core.api.BadActors.load(env.auth.http) }.getOrDefault(emptySet()); setState { it.copy(badActor = it.recipient in badActors) } }
            try {
                val rate = env.walletApi.hivePerMVests()
                val a = env.walletApi.account(user) ?: return@launch
                setState { it.copy(availableVests = StakeMath.availableVests(a), hivePerMVests = rate) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setState { it.copy(prompt = StakePrompt.Alert(WalletCopy.FAIL, AppError.from(e).message)) }
            }
            if (current.recipient.isNotEmpty()) check(immediate = true)
        }
        scope.launch {
            env.walletSends.state.collect { s ->
                val id = current.actionId ?: return@collect
                val phase = s.sends[id]?.phase ?: return@collect
                if (phase != current.phase) onPhase(id, phase)
            }
        }
        scope.launch { env.events.collect { e -> if (e is AppEvent.SessionChanged && e.username != current.from) setState { it.copy(closed = true) } } }
    }

    fun setRecipient(text: String) {
        if (current.busy) return
        setState { it.copy(recipient = text.trim().lowercase().removePrefix("@"), recipientValid = false, existingVests = 0.0, existingHp = null, suggestions = emptyList()) }
        check(immediate = false)
    }

    fun pickSuggestion(name: String) {
        if (name == current.from) {
            setState { it.copy(prompt = StakePrompt.Alert(TransferCopy.USERNAME_ALERT, TransferCopy.USERNAME_ALERT_DETAIL), suggestions = emptyList()) }
            return
        }
        setState { it.copy(recipient = name, suggestions = emptyList()) }
        check(immediate = true)
    }

    fun setHp(text: String) {
        if (current.busy) return
        setState { it.copy(hp = StakeMath.typedHp(it.hp, text)) }
    }

    fun max() = setState { it.copy(hp = StakeMath.hpText(it.maxHp)) }

    /** RN slider over VESTS: a fraction 0..1 of the max. */
    fun setFraction(f: Double) = setState { it.copy(hp = StakeMath.hpText(it.maxHp * f.coerceIn(0.0, 1.0))) }

    fun dismissPrompt() = setState { it.copy(prompt = null) }

    fun review() {
        val s = current
        if (!s.reviewEnabled) return
        if (!s.amountValid) {
            setState { it.copy(prompt = StakePrompt.Alert(StakeCopy.INVALID_AMOUNT, StakeCopy.INVALID_AMOUNT_DESC)) }
            return
        }
        val hp = StakeMath.hp(s.hp) ?: return
        // at the max the whole delegatable amount goes: never a rounded VESTS figure above what the account has
        val vests = if (hp >= s.maxHp) s.maxVests else Vests.fromHp(hp, s.hivePerMVests).coerceAtMost(s.maxVests)
        val vestsText = StakeMath.vestsText(vests)
        setState {
            it.copy(prompt = StakePrompt.Review(StakeCopy.confirmSummary(StakeMath.hpText(hp), vestsText, s.recipient), s.existingHp?.let(StakeCopy::overwrite), vestsText))
        }
    }

    fun confirm() {
        val s = current
        val review = s.prompt as? StakePrompt.Review ?: return
        val user = s.from ?: return
        val ops = WalletOps.ops(listOf(WalletOps.delegateVestingShares(user, s.recipient, "${review.vests} VESTS")))
        val actionId = env.idempotencyKey()
        setState { it.copy(prompt = null, actionId = actionId, phase = SendPhase.Sending) }
        env.walletSends.send(user, ops, OperationAuthority.required(ops), actionId)
        owned.owns(actionId)
    }

    private fun onPhase(id: String, phase: SendPhase) {
        setState { it.copy(phase = phase) }
        when (phase) {
            is SendPhase.Done -> { env.ui.toast(WalletCopy.SUCCESSFUL); owned.done(id); env.wallet.refreshLater(); setState { it.copy(closed = true) } }
            is SendPhase.Failed -> { env.ui.toast(WalletCopy.operationFailed(StakeCopy.DELEGATE, phase.error.message)); owned.done(id); setState { it.copy(closed = true) } }
            is SendPhase.Cancelled -> { owned.done(id); setState { it.copy(actionId = null, phase = null) } }
            else -> Unit
        }
    }

    private fun check(immediate: Boolean) {
        lookup?.cancel()
        val name = current.recipient
        setState { it.copy(badActor = name in badActors) }
        if (name.isEmpty()) return
        lookup = scope.launch {
            if (!immediate) delay(TransferModel.LOOKUP_DEBOUNCE_MILLIS)
            val found = runCatching { env.communityApi.lookupAccounts(name, 20) }.getOrDefault(emptyList())
            if (current.recipient != name) return@launch
            val valid = name in found
            setState { it.copy(recipientValid = valid, suggestions = if (valid) emptyList() else found.take(5)) }
            if (!valid) return@launch
            val user = current.from ?: return@launch
            val existing = runCatching { env.walletApi.outgoingDelegations(user) }.getOrNull()?.firstOrNull { it.account == name }
            if (current.recipient != name || existing == null) return@launch
            val vests = Vests.amount(existing.vestingShares)
            val hp = StakeMath.hpText(Vests.toHp(vests, current.hivePerMVests))
            // RN presets the field to the current delegation
            setState { it.copy(existingVests = vests, existingHp = hp, hp = hp) }
        }
    }
}

data class PowerDownState(
    val from: String? = null,
    val routesOnly: Boolean = false,
    val loaded: Boolean = false,
    val poweringDown: Boolean = false,
    /** RN "Next unstaking is in {days} days, {hp} HIVE". */
    val nextInfo: String? = null,
    val availableVests: Double = 0.0,
    val hivePerMVests: Double = 0.0,
    val hp: String = "",
    val routes: List<WithdrawRoute> = emptyList(),
    val routesLoading: Boolean = false,
    val prompt: StakePrompt? = null,
    val actionId: String? = null,
    val phase: SendPhase? = null,
    /** A route change in flight (one at a time). */
    val routeBusy: Boolean = false,
    val closed: Boolean = false,
) {
    val maxHp: Double get() = floor(Vests.toHp(availableVests, hivePerMVests) * 1000) / 1000

    /** RN fundPerWeek = round(HP / 13 * 1000) / 1000. */
    val weekly: String get() = StakeMath.hpDisplay(round((StakeMath.hp(hp) ?: 0.0) / 13 * 1000) / 1000)

    val busy: Boolean get() = phase is SendPhase.Sending || phase is SendPhase.Checking

    val amountValid: Boolean get() = (StakeMath.hp(hp) ?: -1.0).let { it > 0 && it <= maxHp }

    val showRoutes: Boolean get() = routesOnly || !poweringDown
}

/**
 * RN powerDownScreen (`withdraw_vesting`, and `set_withdraw_vesting_route` routes): the amount in HP turned into
 * VESTS, the weekly estimate, the running power down with STOP (0 VESTS), and the withdraw routes. Route percents go
 * on chain in chain units (100% is 10000; deliberate difference, see README).
 */
class PowerDownModel internal constructor(private val env: AppEnvironment, routesOnly: Boolean) :
    StateModel<PowerDownState>(PowerDownState(routesOnly = routesOnly), env.dispatchers) {
    private val owned = SendOwnership(env.walletSends)

    /** A send still running goes on without the screen and reports its outcome itself. */
    override fun close() {
        owned.closeAll()
        super.close()
    }

    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch { load() }
        scope.launch {
            env.walletSends.state.collect { s ->
                val id = current.actionId ?: return@collect
                val phase = s.sends[id]?.phase ?: return@collect
                if (phase != current.phase) onPhase(id, phase)
            }
        }
        scope.launch { env.events.collect { e -> if (e is AppEvent.SessionChanged && e.username != current.from) setState { it.copy(closed = true) } } }
    }

    private suspend fun load() {
        env.session.awaitLoaded()
        val user = env.session.current.username ?: return
        setState { it.copy(from = user, routesLoading = true) }
        try {
            val rate = env.walletApi.hivePerMVests()
            val a = env.walletApi.account(user) ?: return
            val next = if (a.isPoweringDown) {
                val days = runCatching { ((ChainTime.toEpochSeconds(a.nextVestingWithdrawal) * 1000 - env.nowMillis()) / 86_400_000L).coerceAtLeast(0) }.getOrDefault(0)
                StakeCopy.nextPowerDown(days, StakeMath.hpDisplay(Vests.toHp(Vests.amount(a.vestingWithdrawRate), rate)))
            } else null
            setState { it.copy(loaded = true, poweringDown = a.isPoweringDown, nextInfo = next, availableVests = StakeMath.availableVests(a), hivePerMVests = rate) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            setState { it.copy(prompt = StakePrompt.Alert(WalletCopy.FAIL, AppError.from(e).message)) }
        }
        loadRoutes(user)
    }

    private suspend fun loadRoutes(user: String) {
        val routes = runCatching { env.walletApi.withdrawRoutes(user) }.getOrNull()
        setState { it.copy(routes = routes ?: it.routes, routesLoading = false) }
    }

    fun setHp(text: String) {
        if (current.busy) return
        setState { it.copy(hp = StakeMath.typedHp(it.hp, text)) }
    }

    fun setFraction(f: Double) = setState { it.copy(hp = StakeMath.hpText(it.maxHp * f.coerceIn(0.0, 1.0))) }

    fun max() = setState { it.copy(hp = StakeMath.hpText(it.maxHp)) }

    fun dismissPrompt() = setState { it.copy(prompt = null) }

    fun next() {
        val s = current
        if (s.busy || !s.amountValid || s.actionId != null) return
        val hp = StakeMath.hp(s.hp) ?: return
        val vests = if (hp >= s.maxHp) s.availableVests else Vests.fromHp(hp, s.hivePerMVests).coerceAtMost(s.availableVests)
        setState { it.copy(prompt = StakePrompt.ConfirmPowerDown(StakeMath.vestsText(vests))) }
    }

    fun stop() {
        if (!current.poweringDown || current.busy) return
        setState { it.copy(prompt = StakePrompt.ConfirmStop) }
    }

    fun confirm() {
        val s = current
        val user = s.from ?: return
        val vests = when (val p = s.prompt) {
            is StakePrompt.ConfirmPowerDown -> p.vests
            StakePrompt.ConfirmStop -> "0.000000"
            is StakePrompt.ConfirmRemoveRoute -> return confirmRemoveRoute(p.account)
            else -> return
        }
        val ops = WalletOps.ops(listOf(WalletOps.withdrawVesting(user, "$vests VESTS")))
        val actionId = env.idempotencyKey()
        setState { it.copy(prompt = null, actionId = actionId, phase = SendPhase.Sending) }
        env.walletSends.send(user, ops, OperationAuthority.required(ops), actionId)
        owned.owns(actionId)
    }

    /** RN add route: [percent] 0..100 as typed (sent as chain units), duplicates refused. */
    fun addRoute(account: String, percent: Int, autoVest: Boolean) {
        val name = account.trim().lowercase().removePrefix("@")
        if (current.routes.any { it.toAccount == name }) {
            setState { it.copy(prompt = StakePrompt.Alert(StakeCopy.ADD_ROUTE_FAILED, StakeCopy.SAME_USER)) }
            return
        }
        if (percent !in 1..100 || name.isEmpty()) return
        routeChange(name, percent * 100, autoVest, StakeCopy.ADD_ROUTE_FAILED)
    }

    fun removeRoute(account: String) {
        if (current.routes.none { it.toAccount == account }) return
        setState { it.copy(prompt = StakePrompt.ConfirmRemoveRoute(account)) }
    }

    private fun confirmRemoveRoute(account: String) {
        setState { it.copy(prompt = null) }
        routeChange(account, 0, false, StakeCopy.REMOVE_ROUTE_FAILED)
    }

    private fun routeChange(account: String, percent: Int, autoVest: Boolean, failTitle: String) {
        val user = current.from ?: return
        if (current.routeBusy) return
        val ops = try {
            WalletOps.ops(listOf(WalletOps.setWithdrawVestingRoute(user, account, percent, autoVest)))
        } catch (e: Exception) {
            setState { it.copy(prompt = StakePrompt.Alert(failTitle, AppError.from(e).message)) }
            return
        }
        val actionId = env.idempotencyKey()
        setState { it.copy(routeBusy = true) }
        env.walletSends.send(user, ops, OperationAuthority.required(ops), actionId) { final ->
            kotlinx.coroutines.withContext(env.dispatchers.main) {
                env.walletSends.disown(actionId)
                setState { it.copy(routeBusy = false) }
                when (val p = final.phase) {
                    is SendPhase.Done -> {
                        env.ui.toast(WalletCopy.SUCCESSFUL)
                        // the routes again a moment later (the node needs the block)
                        scope.launch { delay(WalletModel.AFTER_BROADCAST_DELAY_MILLIS); loadRoutes(user) }
                    }
                    is SendPhase.Failed -> setState { it.copy(prompt = StakePrompt.Alert(failTitle, p.error.message)) }
                    // not a failure: the chain cannot say any more (the user checks the routes)
                    is SendPhase.Unconfirmed -> setState { it.copy(prompt = StakePrompt.Alert(StakeCopy.WITHDRAW_ACCOUNTS, p.message)) }
                    else -> Unit
                }
            }
        }
    }

    private fun onPhase(id: String, phase: SendPhase) {
        setState { it.copy(phase = phase) }
        when (phase) {
            is SendPhase.Done -> { env.ui.toast(WalletCopy.SUCCESSFUL); owned.done(id); env.wallet.refreshLater(); setState { it.copy(closed = true) } }
            is SendPhase.Failed -> { env.ui.toast(WalletCopy.operationFailed(StakeCopy.POWER_DOWN, phase.error.message)); owned.done(id); setState { it.copy(closed = true) } }
            is SendPhase.Cancelled -> { owned.done(id); setState { it.copy(actionId = null, phase = null) } }
            else -> Unit
        }
    }
}

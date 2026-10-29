package com.ecency.core.app

import com.ecency.core.api.BadActors
import com.ecency.core.api.broadcast.Amounts
import com.ecency.core.api.broadcast.Dec
import com.ecency.core.api.broadcast.WalletOps
import com.ecency.core.api.broadcast.WalletOps.EngineAction
import com.ecency.core.api.wallet.AssetLayer
import com.ecency.core.api.wallet.RecurrentSchedule
import com.ecency.core.api.wallet.Vests
import com.ecency.core.auth.OperationAuthority
import com.ecency.core.hive.Operation
import com.ecency.core.l10n.L
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** What the transfer screen does (RN TransferTypes for the generic TransferView, normalized). */
enum class TransferKind(val rnType: String, private val titleText: () -> String) {
    TRANSFER("transfer", { L.transfer.transferToken }),
    TO_SAVINGS("transfer_to_savings", { L.wallet.transferToSavings }),
    FROM_SAVINGS("transfer_from_savings", { L.wallet.transferFromSavings }),
    POWER_UP("transfer_to_vesting", { L.transfer.transferToVesting }),
    CONVERT("convert", { L.wallet.convert }),
    POINTS("ecency_point_transfer", { L.transfer.transferToken }),
    ENGINE_TRANSFER("transfer", { L.transfer.transferToken }),
    ENGINE_STAKE("stake", { L.transfer.transferToVesting }),
    ENGINE_UNSTAKE("unstake", { L.transfer.powerDown }),
    ENGINE_DELEGATE("delegate", { L.transfer.delegate }),
    ENGINE_UNDELEGATE("undelegate", { L.wallet.undelegate }),
    ;

    val title: String get() = titleText()

    val isEngine: Boolean get() = name.startsWith("ENGINE")

    companion object {
        /**
         * RN normalizeTransferType plus the layer: `transfer_token` is `transfer`, `withdraw_hive`/`withdraw_hbd` are
         * from savings; on the engine layer the token actions; POINT(S) the points transfer. Null for what another
         * screen does (delegate, power down, routes, receive, swap).
         */
        fun of(transferType: String, layer: AssetLayer, fund: String): TransferKind? {
            val t = when (transferType) {
                "transfer_token", "transfer_engine" -> "transfer"
                "withdraw_hive", "withdraw_hbd" -> "transfer_from_savings"
                "stake_engine" -> "stake"
                "unstake_engine" -> "unstake"
                "delegate_engine" -> "delegate"
                "undelegate_engine" -> "undelegate"
                "points", "recurrent_transfer" -> if (transferType == "points") "ecency_point_transfer" else "transfer"
                else -> transferType
            }
            if (layer == AssetLayer.ENGINE) return when (t) {
                "transfer" -> ENGINE_TRANSFER
                "stake" -> ENGINE_STAKE
                "unstake" -> ENGINE_UNSTAKE
                "delegate" -> ENGINE_DELEGATE
                "undelegate" -> ENGINE_UNDELEGATE
                else -> null
            }
            if (layer == AssetLayer.POINTS || fund == "POINT" || fund == "POINTS") return if (t == "ecency_point_transfer" || t == "transfer") POINTS else null
            return when (t) {
                "transfer" -> TRANSFER
                "transfer_to_savings" -> TO_SAVINGS
                "transfer_from_savings" -> FROM_SAVINGS
                "transfer_to_vesting" -> POWER_UP
                "convert" -> CONVERT
                else -> null
            }
        }
    }
}

/** A transfer screen's amount and memo from a repeated history row, kept out of the saved navigation. */
data class TransferPrefill(val transferType: String, val fund: String, val recipient: String, val amount: String, val memo: String)

/** Which view RN's transfer screen shows for a transfer type (screens/transfer/index.tsx). */
enum class TransferView { TRANSFER, DELEGATE, POWER_DOWN, ROUTES, RECEIVE, UNSUPPORTED;

    companion object {
        fun of(screen: AppScreen.Transfer): TransferView = when (screen.transferType) {
            "delegate_vesting_shares" -> DELEGATE
            "withdraw_vesting", "power_down" -> POWER_DOWN
            "set_withdraw_vesting_route" -> ROUTES
            "receive" -> RECEIVE
            else -> if (TransferKind.of(screen.transferType, com.ecency.core.api.wallet.AssetLayer.of(screen.layer) ?: com.ecency.core.api.wallet.AssetLayer.HIVE, screen.fund) != null) TRANSFER else UNSUPPORTED
        }
    }
}

/** RN RECURRENCE_TYPES. */
enum class Recurrence(val hours: Int, private val labelText: () -> String) { DAILY(24, { L.perks.daily }), WEEKLY(168, { L.perks.weekly }), MONTHLY(731, { L.perks.monthly });

    val label: String get() = labelText()
}

/** RN constants/exchangeAccounts EXCHANGE_ACCOUNTS (exact matches; the transfer screen uses no fuzzy match). */
object ExchangeAccounts {
    val LIST = setOf(
        "deepcrypto8", "gateiodeposit", "probithive", "mxchive", "huobi-pro", "coinexdeposit", "bittrex", "blocktrades", "changelly",
        "gopax-deposit", "hitbtc-exchange", "poloniex", "upbit-exchange", "onepagex", "bdhivesteem", "bitgethive",
    )

    fun isExchange(name: String?): Boolean = name != null && name.trim().lowercase() in LIST
}

/** RN en-US `transfer.*` the transfer screen uses. */
object TransferCopy {
    val FROM: String get() = L.transfer.from
    val TO: String get() = L.transfer.to
    val AMOUNT: String get() = L.transfer.amount
    val MEMO: String get() = L.transfer.memo
    val MEMO_PLACEHOLDER: String get() = L.transfer.memoPlaceholder
    val MEMO_DESC: String get() = L.transfer.memoDesc
    val TO_PLACEHOLDER: String get() = L.transfer.toPlaceholder
    val BALANCE: String get() = L.transfer.amountDesc
    const val MAX = "MAX"
    val NEXT: String get() = L.transfer.next
    val INFORMATION: String get() = L.transfer.information
    val ONE_TIME: String get() = L.transfer.oneTime
    val SCHEDULE: String get() = L.transfer.schedule
    val EXECUTIONS: String get() = L.transfer.executions
    val EXECUTIONS_PLACEHOLDER: String get() = L.transfer.executionsPlaceholder
    val RECURRENCE: String get() = L.transfer.recurrence
    val DELETE_RECURRENT: String get() = L.transfer.deleteRecurrentTransfer
    val CONVERT_DESC: String get() = L.transfer.convertDesc
    val TOO_MANY: String get() = L.transfer.tooManyUsernames
    val ONE_RECIPIENT: String get() = L.native.transfer.oneRecipient
    val LOW_LIQUIDITY: String get() = L.wallet.lowLiquidity
    val BAD_ACTOR: String get() = L.transfer.toBadActor
    val INVALID_QR: String get() = L.transfer.invalidRecipientQr
    val USERNAME_ALERT: String get() = L.transfer.usernameAlert
    val USERNAME_ALERT_DETAIL: String get() = L.transfer.usernameAlertDetail
    val BALANCE_FAILED: String get() = L.transfer.balanceLoadFailed
    val PRECISION_FAILED: String get() = L.transfer.precisionLoadFailed
    val SELECT_TOKEN: String get() = L.transfer.selectToken
    val UNSUBSCRIBE_TITLE: String get() = L.alert.confirm

    fun exchangeMemoRequired(exchange: String) = L.transfer.exchangeMemoRequired(exchange = exchange)

    fun exchangeRecurrentWarning(exchange: String) =
        L.native.transfer.isAnExchangeAnd(exchange = exchange)

    fun customSchedule(hours: Int) = L.transfer.customSchedule(hours = hours.toString())
}

/** A question the transfer screen must answer before it goes on. */
sealed interface TransferPrompt {
    /** RN "Continue transaction?" (no summary, as RN). */
    data object Confirm : TransferPrompt

    /** RN Alert: [title] / [message], OK only. */
    data class Alert(val title: String, val message: String) : TransferPrompt

    /** Delete the existing recurrent transfer to the recipient? */
    data object ConfirmDeleteSchedule : TransferPrompt
}

data class TransferState(
    val kind: TransferKind,
    val fund: String,
    val layer: AssetLayer,
    val from: String? = null,
    val recipient: String = "",
    val recipientLocked: Boolean = false,
    /** Every name of [recipient] exists on chain (looked up after a 300 ms pause). */
    val recipientValid: Boolean = false,
    val suggestions: List<String> = emptyList(),
    val amount: String = "",
    /** Exact text, null while loading. */
    val balance: String? = null,
    val balanceFailed: Boolean = false,
    val memo: String = "",
    val showMemo: Boolean = false,
    /** HIVE/HBD switch of a plain transfer (RN canSwitchToken). */
    val tokenChoices: List<String> = emptyList(),
    /** RN canScheduleTransfer. */
    val canSchedule: Boolean = false,
    /** Null: one time. */
    val recurrenceHours: Int? = null,
    val executions: String = "",
    /** The recipient's existing schedule's next date (RN startDate), shown with "Delete Recurrent Transfer". */
    val existingScheduleDate: String? = null,
    /** Engine token precision; null while unknown (only whole amounts may go). */
    val tokenPrecision: Int? = null,
    val precisionFailed: Boolean = false,
    val badActor: Boolean = false,
    val exchange: String? = null,
    val prompt: TransferPrompt? = null,
    /** The confirmed action's send, once confirmed. */
    val actionId: String? = null,
    val phase: SendPhase? = null,
    /** The screen should close (success, RN goes back on error too). */
    val closed: Boolean = false,
) {
    val isRecurrent: Boolean get() = recurrenceHours != null

    val recipients: List<String> get() = WalletOps.recipients(recipient)

    val allowsMultiple: Boolean get() = !isRecurrent && (kind == TransferKind.TRANSFER || kind == TransferKind.POINTS || kind == TransferKind.ENGINE_TRANSFER)

    /** Several names typed where one account is sent to (a savings deposit, a recurrent transfer, a delegation of tokens). */
    val tooManyRecipients: Boolean get() = !allowsMultiple && recipients.size > 1

    val displayFund: String get() = if (fund == "POINT") L.notification.epoint else fund

    val maxDecimals: Int get() = if (kind.isEngine) tokenPrecision ?: 8 else Amounts.precision(fund)

    private val isNativeFund: Boolean get() = fund == "HIVE" || fund == "HBD"

    /** RN exchangeMemoRequired: blocks NEXT. */
    val exchangeMemoRequired: Boolean get() = exchange != null && isNativeFund && memo.isBlank()

    /** RN exchangeRecurrentWarning. */
    val exchangeRecurrentWarning: Boolean get() = isRecurrent && isNativeFund && exchange != null

    val busy: Boolean get() = phase is SendPhase.Sending || phase is SendPhase.Checking

    /** RN nextBtnDisabled, inverted. */
    val nextEnabled: Boolean
        get() {
            if (busy || actionId != null && phase !is SendPhase.Failed) return false
            val dec = Dec.of(amount) ?: return false
            val amountOk = if (kind.isEngine) !dec.isZero else dec >= Dec.of("0.001")!!
            if (!amountOk || !recipientValid || balance == null || tooManyRecipients) return false
            if (kind.isEngine && tokenPrecision == null && amount.contains('.') && amount.substringAfter('.').trimEnd('0').isNotEmpty()) return false
            if (exchangeMemoRequired) return false
            if (isRecurrent) {
                val n = executions.toIntOrNull()
                if (n == null || n < MIN_EXECUTIONS || executions.trim() != n.toString()) return false
            }
            return true
        }

    companion object {
        const val MIN_EXECUTIONS = 2
    }
}

/**
 * RN TransferView + transferContainer for HIVE, HBD, savings, power up, convert, Points and Hive Engine tokens. The
 * operations are the SDK's ([WalletOps]); a confirmed send goes through [WalletSends] with an action id made at the
 * confirmation, so retrying or checking it never signs a second transaction, and an undecided outcome shows as
 * "check your wallet" while the app keeps asking the chain.
 */
class TransferModel internal constructor(
    private val env: AppEnvironment,
    val kind: TransferKind,
    fund: String,
    layer: AssetLayer,
    referredUsername: String,
    initialAmount: String,
    initialMemo: String,
) : StateModel<TransferState>(TransferState(kind, fund, layer), env.dispatchers) {
    private val owned = SendOwnership(env.walletSends)
    private var lookup: Job? = null
    private var badActors: Set<String> = emptySet()
    private var schedules: List<RecurrentSchedule>? = null
    private var hydratedRecipient: String? = null
    private var started = false

    init {
        val selfDefault = kind in setOf(
            TransferKind.TO_SAVINGS, TransferKind.FROM_SAVINGS, TransferKind.POWER_UP, TransferKind.CONVERT,
            TransferKind.ENGINE_STAKE, TransferKind.ENGINE_UNSTAKE,
        )
        setState {
            it.copy(
                recipientLocked = kind == TransferKind.CONVERT || kind == TransferKind.ENGINE_UNSTAKE,
                showMemo = kind == TransferKind.POINTS || kind == TransferKind.TRANSFER || kind == TransferKind.ENGINE_TRANSFER || kind == TransferKind.TO_SAVINGS,
                tokenChoices = if (kind == TransferKind.TRANSFER) listOf("HIVE", "HBD") else emptyList(),
                canSchedule = kind == TransferKind.TRANSFER && (fund == "HIVE" || fund == "HBD"),
                amount = initialAmount, memo = initialMemo,
                recipient = if (selfDefault) "" else referredUsername,
            )
        }
    }

    fun start() {
        if (started) return
        started = true
        env.wallet.start()
        scope.launch {
            env.session.awaitLoaded()
            val user = env.session.current.username ?: return@launch
            setState { s ->
                val selfDefault = s.recipient.isEmpty() && kind in setOf(
                    TransferKind.TO_SAVINGS, TransferKind.FROM_SAVINGS, TransferKind.POWER_UP, TransferKind.CONVERT,
                    TransferKind.ENGINE_STAKE, TransferKind.ENGINE_UNSTAKE,
                )
                s.copy(from = user, recipient = if (selfDefault) user else s.recipient)
            }
            launch { badActors = runCatching { BadActors.load(env.auth.http) }.getOrDefault(emptySet()); checkRecipient(immediate = true) }
            loadBalance()
            if (kind.isEngine) loadPrecision()
            checkRecipient(immediate = true)
        }
        scope.launch {
            env.walletSends.state.collect { s ->
                val id = current.actionId ?: return@collect
                val send = s.sends[id] ?: return@collect
                if (send.phase != current.phase) onPhase(send.phase)
            }
        }
        scope.launch {
            // the account that opened the screen left: the screen goes (RN screens of a gated route)
            env.events.collect { e -> if (e is AppEvent.SessionChanged && e.username != current.from) setState { it.copy(closed = true) } }
        }
    }

    // ---- fields ----

    fun setRecipient(text: String) {
        if (current.recipientLocked || current.busy) return
        val names = WalletOps.recipients(text)
        val max = if (current.allowsMultiple) WalletOps.MAX_RECIPIENTS else 1
        if (names.size > max && max > 1) {
            env.ui.toast(TransferCopy.TOO_MANY)
            return
        }
        val cleaned = if (max == 1) text.trim().lowercase().removePrefix("@") else text.lowercase()
        setState { it.copy(recipient = cleaned, recipientValid = false, suggestions = emptyList()) }
        checkRecipient(immediate = false)
    }

    /** A suggestion row (RN: picking yourself is refused with an alert). */
    fun pickSuggestion(name: String) {
        if (name == current.from) {
            setState { it.copy(prompt = TransferPrompt.Alert(TransferCopy.USERNAME_ALERT, TransferCopy.USERNAME_ALERT_DETAIL), suggestions = emptyList()) }
            return
        }
        setState { it.copy(recipient = name, suggestions = emptyList()) }
        checkRecipient(immediate = true)
    }

    /** RN QR scan into the recipient ([TransferRecipientParser]). */
    fun scanned(value: String) {
        val name = TransferRecipientParser.extract(value)
        if (name.isEmpty()) env.ui.toast(TransferCopy.INVALID_QR) else setRecipient(name)
    }

    /** RN _handleAmountChange: comma to dot, decimals capped, never above the balance (unless it is still loading). */
    fun setAmount(text: String) {
        if (current.busy) return
        val capped = Amounts.typed(text, current.maxDecimals)
        if (capped.isNotEmpty() && capped != "." && Dec.of(capped) == null) return
        val balance = current.balance?.let(Dec::of)
        val value = Dec.of(capped)
        if (balance != null && value != null && value > balance) return
        setState { it.copy(amount = capped) }
    }

    fun max() {
        val b = current.balance ?: return
        setState { it.copy(amount = if (current.kind.isEngine) Amounts.tokenQuantity(b, current.maxDecimals) else Amounts.capDecimals(b, current.maxDecimals)) }
    }

    fun setMemo(text: String) {
        if (current.busy) return
        setState { it.copy(memo = text).withWarnings() }
    }

    /** The HIVE/HBD switch of a plain transfer. */
    fun setFund(symbol: String) {
        if (symbol !in current.tokenChoices || symbol == current.fund || current.busy) return
        setState { it.copy(fund = symbol, balance = null, amount = "", canSchedule = true).withWarnings() }
        scope.launch { loadBalance() }
    }

    /** The schedule menu: null for one time. */
    fun setRecurrence(hours: Int?) {
        if (!current.canSchedule || current.busy) return
        setState { it.copy(recurrenceHours = hours, executions = if (hours == null) "" else it.executions, suggestions = emptyList()).withWarnings() }
        if (hours != null) scope.launch { hydrateSchedule() }
    }

    fun setExecutions(text: String) {
        if (current.busy) return
        // the language's digits read as ASCII ([Amounts.typedText]), anything else dropped
        setState { it.copy(executions = Amounts.typedText(text).filter { it in '0'..'9' }.take(5)) }
    }

    fun retryBalance() = scope.launch { loadBalance() }

    fun retryPrecision() = scope.launch { loadPrecision() }

    // ---- send ----

    /** RN _onNextPress: exchange memo, low liquidity, then the confirmation. */
    fun next() {
        val s = current
        if (!s.nextEnabled) return
        val total = Dec.of(s.amount)!!.times(s.recipients.size.coerceAtLeast(1))
        val balance = Dec.of(s.balance ?: "0") ?: Dec.ZERO
        if (balance < total) {
            setState { it.copy(prompt = TransferPrompt.Alert(TransferCopy.LOW_LIQUIDITY, "")) }
            return
        }
        setState { it.copy(prompt = TransferPrompt.Confirm) }
    }

    fun dismissPrompt() = setState { it.copy(prompt = null) }

    /** "Confirm": the action id is made now, once; the send runs app-wide. */
    fun confirm() {
        val s = current
        if (s.prompt != TransferPrompt.Confirm) return
        val user = s.from ?: return
        val ops = try {
            operations(s, user)
        } catch (e: Exception) {
            setState { it.copy(prompt = TransferPrompt.Alert(WalletCopy.FAIL, AppError.from(e).message)) }
            return
        }
        val json = WalletOps.ops(ops)
        val actionId = env.idempotencyKey()
        setState { it.copy(prompt = null, actionId = actionId, phase = SendPhase.Sending) }
        env.walletSends.send(user, json, OperationAuthority.required(json), actionId)
        owned.owns(actionId)
    }

    /** RN "Delete Recurrent Transfer": asks first. */
    fun deleteSchedule() {
        if (current.existingScheduleDate == null || current.busy) return
        setState { it.copy(prompt = TransferPrompt.ConfirmDeleteSchedule) }
    }

    /** RN deletes with amount 0, recurrence 24 and executions 2 from the transfer screen. */
    fun confirmDeleteSchedule() {
        val s = current
        if (s.prompt != TransferPrompt.ConfirmDeleteSchedule) return
        val user = s.from ?: return
        val to = s.recipients.singleOrNull() ?: return
        val op = WalletOps.recurrentTransfer(user, to, "0.000 ${s.fund}", s.memo, Recurrence.DAILY.hours, 2)
        val json = WalletOps.ops(listOf(op))
        val actionId = env.idempotencyKey()
        setState { it.copy(prompt = null, actionId = actionId, phase = SendPhase.Sending) }
        env.walletSends.send(user, json, OperationAuthority.required(json), actionId)
        owned.owns(actionId)
    }

    private fun onPhase(phase: SendPhase) {
        setState { it.copy(phase = phase) }
        when (phase) {
            is SendPhase.Done -> {
                env.ui.toast(WalletCopy.SUCCESSFUL)
                release()
                env.wallet.refreshLater()
                setState { it.copy(closed = true) }
            }
            is SendPhase.Failed -> {
                // RN goes back and says why ("{operation} failed: {reason}", the key warning for a key problem)
                env.ui.toast(failureText(phase.error))
                release()
                setState { it.copy(closed = true) }
            }
            is SendPhase.Cancelled -> {
                release()
                setState { it.copy(actionId = null, phase = null) }
            }
            // Checking: the spinner and "check your wallet" stay until the chain decides; Unconfirmed: the message stays
            else -> Unit
        }
    }

    private fun failureText(e: AppError): String {
        val m = e.message
        val reason = if (Regex("key|authority|missing", RegexOption.IGNORE_CASE).containsMatchIn(m)) WalletCopy.KEY_WARNING else m.lineSequence().firstOrNull()?.ifBlank { null } ?: WalletCopy.FAIL
        return WalletCopy.operationFailed(kind.title, reason)
    }

    private fun release() {
        current.actionId?.let(owned::done)
    }

    /** The screen closed: a send in flight keeps running in the app (its outcome is toasted by [WalletSends]). */
    override fun close() {
        // a send still running goes on without this screen and reports its outcome itself (WalletSends.orphanDone)
        owned.closeAll()
        super.close()
    }

    // ---- internals ----

    private fun operations(s: TransferState, user: String): List<Operation> {
        val to = s.recipients
        // never the first of several names: a single-recipient operation goes to exactly the one typed
        if (s.tooManyRecipients) throw com.ecency.core.api.InvalidInputException(TransferCopy.ONE_RECIPIENT)
        val one = to.firstOrNull() ?: user
        val native = Amounts.asset(s.amount, s.fund)
        return when (s.kind) {
            TransferKind.TRANSFER -> if (s.recurrenceHours != null) {
                listOf(WalletOps.recurrentTransfer(user, one, native, s.memo, s.recurrenceHours, s.executions.toInt()))
            } else {
                WalletOps.multiTransfer(user, s.recipient, native, s.memo)
            }
            TransferKind.TO_SAVINGS -> listOf(WalletOps.transferToSavings(user, one, native, s.memo))
            TransferKind.FROM_SAVINGS -> listOf(WalletOps.transferFromSavings(user, one, native, s.memo, requestId()))
            TransferKind.POWER_UP -> listOf(WalletOps.transferToVesting(user, one, native))
            TransferKind.CONVERT -> listOf(WalletOps.convert(user, native, requestId()))
            TransferKind.POINTS -> WalletOps.multiPointTransfer(user, s.recipient, Amounts.asset(s.amount, "POINT"), s.memo)
            TransferKind.ENGINE_TRANSFER -> WalletOps.multiEngineTransfer(user, s.fund, s.recipient, quantity(s), s.memo)
            TransferKind.ENGINE_STAKE -> listOf(WalletOps.engine(user, EngineAction.STAKE, s.fund, one, quantity(s)))
            TransferKind.ENGINE_UNSTAKE -> listOf(WalletOps.engine(user, EngineAction.UNSTAKE, s.fund, one, quantity(s)))
            TransferKind.ENGINE_DELEGATE -> listOf(WalletOps.engine(user, EngineAction.DELEGATE, s.fund, one, quantity(s)))
            TransferKind.ENGINE_UNDELEGATE -> listOf(WalletOps.engine(user, EngineAction.UNDELEGATE, s.fund, one, quantity(s)))
        }
    }

    /** RN #3569: cut to the token's precision (whole amounts while it is unknown), trailing zeros dropped. */
    private fun quantity(s: TransferState) = Amounts.tokenQuantity(s.amount, s.tokenPrecision ?: 0)

    /** RN `new Date().getTime() >>> 0`. */
    private fun requestId(): Long = env.nowMillis() and 0xFFFFFFFFL

    private suspend fun loadBalance() {
        val user = current.from ?: return
        setState { it.copy(balanceFailed = false) }
        val text = try {
            when {
                kind == TransferKind.POINTS -> Amounts.fixed(env.walletApi.points(user).first.replace(",", ""), 3)
                kind.isEngine -> {
                    val (liquid, stake) = env.walletApi.engineBalances(user)[current.fund] ?: ("0" to "0")
                    when (kind) {
                        TransferKind.ENGINE_UNSTAKE, TransferKind.ENGINE_DELEGATE -> stake
                        TransferKind.ENGINE_UNDELEGATE -> env.wallet.current.item(current.fund)?.extra("delegations_out")?.value?.replace(",", "") ?: "0"
                        else -> liquid
                    }.let { Amounts.tokenQuantity(it) }
                }
                else -> {
                    val a = env.walletApi.account(user) ?: throw IllegalStateException("account not found")
                    val asset = when (kind) {
                        TransferKind.FROM_SAVINGS -> if (current.fund == "HBD") a.savingsHbdBalance else a.savingsBalance
                        TransferKind.CONVERT -> a.hbdBalance
                        TransferKind.POWER_UP -> a.balance
                        else -> if (current.fund == "HBD") a.hbdBalance else a.balance
                    }
                    asset.trim().substringBefore(' ')
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            setState { it.copy(balanceFailed = true) }
            return
        }
        setState { s -> s.copy(balance = text) }
    }

    private suspend fun loadPrecision() {
        setState { it.copy(precisionFailed = false) }
        val p = try {
            env.walletApi.engineTokens(listOf(current.fund)).firstOrNull { it.symbol == current.fund }?.precision
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        setState { it.copy(tokenPrecision = p, precisionFailed = p == null, amount = if (p != null) Amounts.capDecimals(it.amount, p) else it.amount) }
    }

    private fun checkRecipient(immediate: Boolean) {
        lookup?.cancel()
        val text = current.recipient
        val names = WalletOps.recipients(text)
        setState { it.withWarnings() }
        if (names.isEmpty()) return
        lookup = scope.launch {
            if (!immediate) delay(LOOKUP_DEBOUNCE_MILLIS)
            val valid = try {
                names.all { n -> n in env.communityApi.lookupAccounts(n, 20) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            val suggestions = if (names.size == 1 && !valid) runCatching { env.communityApi.lookupAccounts(names[0], 20) }.getOrDefault(emptyList()) else emptyList()
            // RN: an answer for a recipient that changed meanwhile is ignored
            if (current.recipient != text) return@launch
            setState { it.copy(recipientValid = valid, suggestions = suggestions.filter { s -> s != names[0] }.take(5)).withWarnings() }
            if (valid && current.isRecurrent) hydrateSchedule()
        }
    }

    private fun TransferState.withWarnings(): TransferState {
        val names = recipients
        return copy(badActor = names.any { it in badActors }, exchange = names.firstOrNull(ExchangeAccounts::isExchange))
    }

    /** RN: picking a recipient with an existing schedule fills memo, amount, recurrence, executions and its date. */
    private suspend fun hydrateSchedule() {
        val user = current.from ?: return
        val to = current.recipients.singleOrNull() ?: return
        val list = schedules ?: runCatching { env.walletApi.recurrentTransfers(user) }.getOrNull()?.also { schedules = it } ?: return
        val existing = list.firstOrNull { it.to == to && Vests.symbol(it.amount) == current.fund }
        if (existing == null) {
            if (hydratedRecipient != null) {
                hydratedRecipient = null
                setState { it.copy(existingScheduleDate = null) }
            }
            return
        }
        hydratedRecipient = to
        setState {
            it.copy(
                memo = existing.memo, amount = Amounts.capDecimals(existing.amount.substringBefore(' '), 3),
                recurrenceHours = existing.recurrence, executions = existing.remainingExecutions.toString(), existingScheduleDate = existing.triggerDate,
            ).withWarnings()
        }
    }

    companion object {
        const val LOOKUP_DEBOUNCE_MILLIS = 300L
    }
}

/** RN screens/transfer/screen/transferRecipientParser. */
object TransferRecipientParser {
    private val PAY_OPS = setOf("transfer", "recurrent_transfer", "transfer_to_savings", "transfer_to_vesting")
    private val NAME = Regex("^[a-z0-9.-]{3,16}$")

    fun normalize(v: String): String = v.trim().removePrefix("@").lowercase()

    /** The account a scanned QR names, or "" (RN extractUsernameFromScannedValue). */
    fun extract(value: String): String {
        val raw = value.trim()
        // 1. a hive:// payment link: only payment operations may seed a recipient (never e.g. a proposal's receiver)
        runCatching { com.ecency.core.auth.HiveUri.decode(raw) }.getOrNull()?.let { decoded ->
            val tx = decoded.tx as? kotlinx.serialization.json.JsonObject
            val op = (tx?.get("operations") as? kotlinx.serialization.json.JsonArray)?.firstOrNull() as? kotlinx.serialization.json.JsonArray
            val name = (op?.getOrNull(0) as? kotlinx.serialization.json.JsonPrimitive)?.content
            val to = ((op?.getOrNull(1) as? kotlinx.serialization.json.JsonObject)?.get("to") as? kotlinx.serialization.json.JsonPrimitive)?.content
            if (name in PAY_OPS && !to.isNullOrEmpty() && to != "__signer") return normalize(to)
        }
        // 2. ?to= / &username= / &account=
        Regex("[?&](?:to|username|account)=([^&#]+)", RegexOption.IGNORE_CASE).find(raw)?.let { m ->
            val v = m.groupValues[1]
            return normalize(runCatching { com.ecency.core.render.js.Js.decodeUriComponent(v) }.getOrDefault(v))
        }
        // 3. profile links
        (Regex("(?:^|/)@([a-z0-9.-]+)", RegexOption.IGNORE_CASE).find(raw)
            ?: Regex("(?:^|/)(?:profile|user|account)/([a-z0-9.-]+)", RegexOption.IGNORE_CASE).find(raw))?.let { return normalize(it.groupValues[1]) }
        // 4. a bare name
        return normalize(raw).takeIf { NAME.matches(it) } ?: ""
    }
}

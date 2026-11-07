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
import com.ecency.core.hive.Memo
import com.ecency.core.hive.Operation
import com.ecency.core.hive.PrivateKey
import com.ecency.core.hive.PublicKey
import com.ecency.core.l10n.L
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    val MEMO_HAS_SECRET: String get() = L.native.transfer.memoHasSecret
    val MEMO_ENCRYPTED: String get() = L.native.transfer.memoEncrypted
    val MEMO_ENCRYPT_NO_KEY: String get() = L.native.transfer.memoEncryptNoKey
    val MEMO_ENCRYPT_UNSUPPORTED: String get() = L.native.transfer.memoEncryptUnsupported
    val DECRYPT_MEMO: String get() = L.native.transfer.decryptMemo
    val DECRYPT_MEMO_FAILED: String get() = L.native.transfer.decryptMemoFailed
    fun memoEncryptExchange(exchange: String) = L.native.transfer.memoEncryptExchange(exchange = exchange)

    /** The exchange note for [memo]: cipher text is replaced by the exchange's deposit memo, a plain one loses its `#`. */
    fun memoExchange(exchange: String, memo: String) =
        if (Memo.isEncrypted(memo)) L.native.transfer.memoEncryptedExchange(exchange = exchange) else memoEncryptExchange(exchange)

    /**
     * The memo a recurrent transfer's deletion sends: a `#` memo is not encrypted for a deletion (it moves nothing),
     * so it goes empty rather than as text; the transfer screen and the wallet details alike.
     */
    fun deletionMemo(memo: String): String = if (memo.startsWith("#")) "" else memo
    fun recipientNoMemoKey(account: String) = L.native.transfer.memoRecipientNoKey(account = account)

    fun useSavedMemo(exchange: String, memo: String) = L.native.transfer.useSavedMemo(exchange = exchange, memo = memo)

    fun exchangeRecurrentWarning(exchange: String) =
        L.native.transfer.isAnExchangeAnd(exchange = exchange)

    fun customSchedule(hours: Int) = L.transfer.customSchedule(hours = hours.toString())
}

/** A memo starting with `#` is encrypted for the recipient (#3633). */
enum class MemoEncryption { NONE, ENCRYPTS, NO_KEY, UNSUPPORTED, EXCHANGE }

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
    /** The memo this account last sent to [exchange] ([ExchangeMemos]), while the memo field does not hold it. */
    val savedExchangeMemo: String? = null,
    /** One of the sender's keys that can encrypt a `#` memo is on the device; null until read ([MemoEncryption]). */
    val memoCipherKey: Boolean? = null,
    /** Next pressed with a `#` memo: the recipients' memo keys are read and the memo encrypted before the confirmation. */
    val encryptingMemo: Boolean = false,
    val prompt: TransferPrompt? = null,
    /** The confirmed action's send, once confirmed. */
    val actionId: String? = null,
    val phase: SendPhase? = null,
    /** The screen should close (success, RN goes back on error too). */
    val closed: Boolean = false,
) {
    val isRecurrent: Boolean get() = recurrenceHours != null

    val recipients: List<String> get() = WalletOps.recipients(recipient)

    /**
     * RN transferScreen's recipient row: an avatar for each typed recipient that is an account name, after the
     * sender's ([TransferModel.avatarUrl]), so a mistyped name shows a stranger's face before anything is sent.
     */
    val recipientAvatars: List<String>
        get() {
            if (recipientLocked) return emptyList()
            val names = recipients.filter { com.ecency.core.render.blocks.isHiveAccountName(it) }.distinct()
            // a screen that sends to one account shows one, however the text splits
            return names.take(if (allowsMultiple) MAX_AVATARS else 1)
        }

    val allowsMultiple: Boolean get() = !isRecurrent && (kind == TransferKind.TRANSFER || kind == TransferKind.POINTS || kind == TransferKind.ENGINE_TRANSFER)

    /** Several names typed where one account is sent to (a savings deposit, a recurrent transfer, a delegation of tokens). */
    val tooManyRecipients: Boolean get() = !allowsMultiple && recipients.size > 1

    val displayFund: String get() = if (fund == "POINT") L.notification.epoint else fund

    val maxDecimals: Int get() = if (kind.isEngine) tokenPrecision ?: 8 else Amounts.precision(fund)

    internal val isNativeFund: Boolean get() = fund == "HIVE" || fund == "HBD"

    /** A deposit memo is kept and offered ([ExchangeMemos]) only for a plain HIVE or HBD transfer to one exchange. */
    internal val offersExchangeMemo: Boolean get() = kind == TransferKind.TRANSFER && isNativeFund && recipients.size == 1

    /** RN exchangeMemoRequired: blocks NEXT. */
    val exchangeMemoRequired: Boolean get() = exchange != null && isNativeFund && memo.isBlank()

    /** RN exchangeRecurrentWarning. */
    val exchangeRecurrentWarning: Boolean get() = isRecurrent && isNativeFund && exchange != null

    /** The memo holds a private key or a master password ([MemoSecrets]): a memo is public, so this blocks NEXT (#3633). */
    val memoHasSecret: Boolean get() = MemoSecrets.contains(memo)

    /**
     * A memo starting with `#` (the Hive wallets' convention) goes on chain encrypted for the recipient (#3633): only on
     * a HIVE or HBD transfer (Points and Engine memos have no memo key to encrypt for), and only with one of the sender's
     * keys on the device. Never sent as plain text instead.
     */
    val memoEncryption: MemoEncryption
        get() = when {
            !memo.startsWith("#") -> MemoEncryption.NONE
            // an exchange cannot read an encrypted deposit memo, and the deposit may not be credited: no `#` memo
            // goes to one, cipher text already encrypted included
            exchange != null -> MemoEncryption.EXCHANGE
            // cipher text already (an existing schedule's memo, one pasted): it goes as it is, never encrypted twice
            Memo.isEncrypted(memo) -> MemoEncryption.NONE
            !(isNativeFund && kind in ENCRYPTED_MEMO_KINDS) -> MemoEncryption.UNSUPPORTED
            memoCipherKey == false -> MemoEncryption.NO_KEY
            else -> MemoEncryption.ENCRYPTS
        }

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
            if (memoHasSecret) return false
            // a `#` memo is sent encrypted or not at all; while the keys are being read or the memo encrypted, wait
            if (encryptingMemo || memoEncryption in BLOCKING_MEMO) return false
            if (memoEncryption == MemoEncryption.ENCRYPTS && memoCipherKey == null) return false
            if (isRecurrent) {
                val n = executions.toIntOrNull()
                if (n == null || n < MIN_EXECUTIONS || executions.trim() != n.toString()) return false
            }
            return true
        }

    companion object {
        private val ENCRYPTED_MEMO_KINDS = setOf(TransferKind.TRANSFER, TransferKind.TO_SAVINGS, TransferKind.FROM_SAVINGS)
        private val BLOCKING_MEMO = setOf(MemoEncryption.NO_KEY, MemoEncryption.UNSUPPORTED, MemoEncryption.EXCHANGE)

        const val MIN_EXECUTIONS = 2

        /** Recipient avatars shown at most (RN draws every one; a long list would push the form down). */
        const val MAX_AVATARS = 5
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

    /** The form as the confirmation showed it: what confirm() signs. */
    private var shown: TransferState? = null

    /** [shown]'s `#` memo encrypted for each recipient (#3633); what confirm() puts on chain instead of the text. */
    private var encryptedMemos: Map<String, String>? = null
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
            launch {
                val has = try {
                    withContext(env.dispatchers.io) { memoCipherKey(user) != null }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ErrorReports.quiet(e, "transfer.memoKey")
                    false
                }
                setState { it.copy(memoCipherKey = has) }
            }
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

    /** The avatar picture of [account] for the recipient row (RN UserAvatar). */
    fun avatarUrl(account: String): String = PostCard.avatarUrl(account, env.proxyBase, "small")

    /** "Use saved memo": the memo this account last sent to the exchange ([TransferState.savedExchangeMemo]). */
    fun useSavedMemo() {
        val saved = current.savedExchangeMemo ?: return
        setMemo(saved)
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
        if (s.memoEncryption == MemoEncryption.ENCRYPTS) return encryptThenConfirm(s)
        shown = s
        encryptedMemos = null
        setState { it.copy(prompt = TransferPrompt.Confirm) }
    }

    /**
     * #3633: each recipient's memo key is read from the chain and the memo encrypted with the sender's key before the
     * confirmation shows; any failure says why and sends nothing (a `#` memo never goes out as text).
     */
    private fun encryptThenConfirm(s: TransferState) {
        val user = s.from ?: return
        setState { it.copy(encryptingMemo = true) }
        scope.launch {
            val result = try {
                val key = withContext(env.dispatchers.io) { memoCipherKey(user) }
                    ?: throw com.ecency.core.api.InvalidInputException(TransferCopy.MEMO_ENCRYPT_NO_KEY)
                val names = s.recipients.ifEmpty { listOf(user) }.distinct()
                val memoKeys = env.api.hive.accounts(names).associate { it.name to it.memoKey }
                Result.success(
                    names.associateWith { name ->
                        val pub = memoKeys[name]?.let { runCatching { PublicKey.fromString(it) }.getOrNull() }
                        // no memo key, or one that is no curve point (the null key): nothing to encrypt for
                        pub?.let { runCatching { Memo.encode(key, it, s.memo) }.getOrNull() }
                            ?: throw com.ecency.core.api.InvalidInputException(TransferCopy.recipientNoMemoKey(name))
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // not reported: reported below by AppError.from in onFailure
                Result.failure(e)
            }
            result.onSuccess { memos ->
                // the form changed while the memo was encrypted, or another question opened: that is not what was
                // pressed, so nothing is offered for confirmation (Next again encrypts the form as it is now)
                // or the account changed and the screen is closing: nothing is offered for the old account
                if (current.closed || current.prompt != null || !sameForm(current, s)) return@onSuccess setState { it.copy(encryptingMemo = false) }
                shown = s
                encryptedMemos = memos
                setState { it.copy(encryptingMemo = false, prompt = TransferPrompt.Confirm) }
            }.onFailure { e ->
                if (current.closed || current.prompt != null) return@onFailure setState { it.copy(encryptingMemo = false) }
                setState { it.copy(encryptingMemo = false, prompt = TransferPrompt.Alert(WalletCopy.FAIL, AppError.from(e, "transfer.encryptMemo").message)) }
            }
        }
    }

    private fun sameForm(a: TransferState, b: TransferState) =
        a.from == b.from && a.kind == b.kind && a.fund == b.fund && a.recipient == b.recipient && a.amount == b.amount &&
            a.memo == b.memo && a.recurrenceHours == b.recurrenceHours && a.executions == b.executions

    /** The sender's key that encrypts a `#` memo: the memo key, else the posting key, else the active key (#3633). */
    private fun memoCipherKey(user: String): PrivateKey? {
        val account = env.vault.vault.account(user) ?: return null
        return listOf(account.memoKey, account.postingKey, account.activeKey).firstNotNullOfOrNull { wif ->
            wif?.takeIf { it.isNotEmpty() }?.let { runCatching { PrivateKey.fromWif(it) }.getOrNull() }
        }
    }

    fun dismissPrompt() {
        shown = null
        encryptedMemos = null
        setState { it.copy(prompt = null) }
    }

    /** "Confirm": the action id is made now, once; the send runs app-wide. */
    fun confirm() {
        if (current.closed || current.prompt != TransferPrompt.Confirm) return
        val s = shown ?: return
        shown = null
        val encrypted = encryptedMemos
        encryptedMemos = null
        val user = s.from ?: return
        val ops = try {
            operations(s, user, encrypted)
        } catch (e: Exception) {
            setState { it.copy(prompt = TransferPrompt.Alert(WalletCopy.FAIL, AppError.from(e, "transfer.confirm").message)) }
            return
        }
        val json = WalletOps.ops(ops)
        val actionId = env.idempotencyKey()
        // the memo that was confirmed, kept once this send went through, even after the screen closed (the send goes on
        // without it); not for an account that left the device meanwhile
        s.exchange?.takeIf { s.offersExchangeMemo && s.memo.isNotBlank() }?.let { exchange ->
            val memo = s.memo
            env.walletSends.afterSuccess(actionId) {
                if (env.session.current.accounts.any { it.username == user }) env.exchangeMemos.save(user, exchange, memo)
            }
        }
        setState { it.copy(prompt = null, actionId = actionId, phase = SendPhase.Sending) }
        env.walletSends.send(user, json, OperationAuthority.required(json), actionId)
        owned.owns(actionId)
    }

    /** RN "Delete Recurrent Transfer": asks first. */
    fun deleteSchedule() {
        if (current.existingScheduleDate == null || current.busy) return
        // the deletion sends the memo as it stands (RN): one holding a secret is refused like Next, and said why
        if (current.memoHasSecret) return setState { it.copy(prompt = TransferPrompt.Alert(WalletCopy.FAIL, TransferCopy.MEMO_HAS_SECRET)) }
        setState { it.copy(prompt = TransferPrompt.ConfirmDeleteSchedule) }
    }

    /** RN deletes with amount 0, recurrence 24 and executions 2 from the transfer screen. */
    fun confirmDeleteSchedule() {
        val s = current
        if (s.prompt != TransferPrompt.ConfirmDeleteSchedule) return
        // the memo may have been edited while the question was open
        if (s.memoHasSecret) return setState { it.copy(prompt = TransferPrompt.Alert(WalletCopy.FAIL, TransferCopy.MEMO_HAS_SECRET)) }
        val user = s.from ?: return
        val to = s.recipients.singleOrNull() ?: return
        val memo = TransferCopy.deletionMemo(s.memo)
        val op = WalletOps.recurrentTransfer(user, to, "0.000 ${s.fund}", memo, Recurrence.DAILY.hours, 2)
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
        // the last memo looked at (maybe a key the user typed) does not outlive the screen
        MemoSecrets.forget()
        // a send still running goes on without this screen and reports its outcome itself (WalletSends.orphanDone)
        owned.closeAll()
        super.close()
    }

    // ---- internals ----

    private fun operations(s: TransferState, user: String, encrypted: Map<String, String>?): List<Operation> {
        val to = s.recipients
        // never the first of several names: a single-recipient operation goes to exactly the one typed
        if (s.tooManyRecipients) throw com.ecency.core.api.InvalidInputException(TransferCopy.ONE_RECIPIENT)
        val one = to.firstOrNull() ?: user
        val native = Amounts.asset(s.amount, s.fund)
        // a `#` memo goes encrypted for each recipient, or nothing goes (never as text)
        fun memo(name: String): String = when (s.memoEncryption) {
            MemoEncryption.NONE -> s.memo
            MemoEncryption.ENCRYPTS -> encrypted?.get(name) ?: throw com.ecency.core.api.InvalidInputException(TransferCopy.recipientNoMemoKey(name))
            MemoEncryption.NO_KEY -> throw com.ecency.core.api.InvalidInputException(TransferCopy.MEMO_ENCRYPT_NO_KEY)
            MemoEncryption.UNSUPPORTED -> throw com.ecency.core.api.InvalidInputException(TransferCopy.MEMO_ENCRYPT_UNSUPPORTED)
            MemoEncryption.EXCHANGE -> throw com.ecency.core.api.InvalidInputException(TransferCopy.memoExchange(s.exchange.orEmpty(), s.memo))
        }
        return when (s.kind) {
            TransferKind.TRANSFER -> if (s.recurrenceHours != null) {
                listOf(WalletOps.recurrentTransfer(user, one, native, memo(one), s.recurrenceHours, s.executions.toInt()))
            } else {
                WalletOps.multiTransfer(user, s.recipient, native, s.memo).map { it.copy(memo = memo(it.to)) }
            }
            TransferKind.TO_SAVINGS -> listOf(WalletOps.transferToSavings(user, one, native, memo(one)))
            TransferKind.FROM_SAVINGS -> listOf(WalletOps.transferFromSavings(user, one, native, memo(one), requestId()))
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
            ErrorReports.quiet(e, "transfer.balance")
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
            ErrorReports.quiet(e, "transfer.precision")
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
                ErrorReports.quiet(e, "transfer.recipient")
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
        val ex = names.firstOrNull(ExchangeAccounts::isExchange)
        // offered only where the exchange needs a memo (native funds), and only while the field does not hold it
        val saved = if (ex != null && offersExchangeMemo) from?.let { env.exchangeMemos.get(it, ex) } else null
        return copy(badActor = names.any { it in badActors }, exchange = ex, savedExchangeMemo = saved?.takeIf { it != memo.trim() })
    }

    /** RN: picking a recipient with an existing schedule fills memo, amount, recurrence, executions and its date. */
    private suspend fun hydrateSchedule() {
        val user = current.from ?: return
        val to = current.recipients.singleOrNull() ?: return
        val list = schedules ?: runCatching { env.walletApi.recurrentTransfers(user) }.getOrNull()?.also { schedules = it } ?: return
        // the form changed during the read: the answer is for a recipient or choice no longer shown
        if (current.from != user || current.recipients.singleOrNull() != to || !current.isRecurrent) return
        // a confirmation is open or a send started: what is signed stays what was shown
        if (current.prompt != null || current.actionId != null) return
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

/**
 * Secrets that must never go into a memo, which is public on chain (the "send 0.001 with your key in the memo to
 * verify" scam): a private key (a WIF whose checksum holds, so ordinary text never matches) or a Hive master password
 * (`P5` and 50 base58 characters). Public keys pass. A key glued to other text counts too: every 51 character stretch
 * of a base58 run that starts with `5` is checked (the checksum keeps chance matches out). A password has no checksum,
 * so it counts only as a whole run or at either end of one (glued to a word on one side): a long run such as an
 * encrypted memo, with `P5` somewhere inside, is not taken for one. A key split by copy noise (spaces, line breaks,
 * invisible characters) counts as well.
 */
object MemoSecrets {
    private val WORD = Regex("[1-9A-HJ-NP-Za-km-z]+")
    private const val WIF_LENGTH = 51
    private const val PASSWORD_LENGTH = 52

    // the last memo looked at and its answer, as one value (never a new memo with an old answer): the forms ask on
    // every redraw while the memo stays the same. Dropped when the transfer screen closes ([forget]).
    private var last: Pair<String, Boolean>? = null

    fun contains(memo: String): Boolean {
        last?.let { (m, answer) -> if (m == memo) return answer }
        return scan(memo).also { last = memo to it }
    }

    /** Forgets the last memo looked at (it may be a key the user typed). */
    fun forget() {
        last = null
    }

    private fun scan(memo: String): Boolean =
        WORD.findAll(memo).any { m -> hasKey(m.value) || hasPassword(m.value) } ||
            // a key split by spaces, line breaks or invisible characters is still readable on chain: the copy noise
            // key input ignores (KeyInput.isNoise) is taken out and the keys looked for again
            WORD.findAll(memo.filterNot(com.ecency.core.auth.KeyInput::isNoise)).any { hasKey(it.value) } ||
            // a password has no checksum, so only invisible characters are taken out for it (never in plain text;
            // joining across spaces would take ordinary words that start with P5 for a password)
            WORD.findAll(memo.filterNot { com.ecency.core.auth.KeyInput.isNoise(it) && !it.isWhitespace() }).any { hasPassword(it.value) }

    private fun hasKey(run: String): Boolean =
        (0..run.length - WIF_LENGTH).any { i ->
            run[i] == '5' && runCatching { com.ecency.core.hive.PrivateKey.fromWif(run.substring(i, i + WIF_LENGTH)) }.isSuccess
        }

    private fun hasPassword(run: String): Boolean =
        run.length >= PASSWORD_LENGTH && (run.startsWith("P5") || run.substring(run.length - PASSWORD_LENGTH).startsWith("P5"))
}

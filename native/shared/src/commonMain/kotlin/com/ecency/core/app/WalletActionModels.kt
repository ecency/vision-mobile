package com.ecency.core.app

import com.ecency.core.api.broadcast.Amounts
import com.ecency.core.api.broadcast.Dec
import com.ecency.core.api.broadcast.WalletOps
import com.ecency.core.api.model.JsValue
import com.ecency.core.api.wallet.AssetLayer
import com.ecency.core.api.wallet.BookOrder
import com.ecency.core.api.wallet.DurationPrice
import com.ecency.core.api.wallet.PortfolioItem
import com.ecency.core.api.wallet.ProfileToken
import com.ecency.core.auth.OperationAuthority
import com.ecency.core.hive.ChainTime
import com.ecency.core.hive.Operation
import com.ecency.core.l10n.L
import com.ecency.core.l10n.LocaleFormat
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

// ---- trade (RN screens/trade) ----

/** RN swapCalculatorHelpers.processHiveOrderBook. */
data class SwapQuote(val toAmount: Double?, val tooMuchSlippage: Boolean, val invalidAmount: Boolean, val emptyOrderBook: Boolean = false)

object SwapCalculator {
    /**
     * RN calculatePrice, quirk included: the walk starts with book[0] and then adds book[index] before moving on, so
     * book[0] is counted twice once it advances.
     */
    fun price(amount: Double, book: List<BookOrder>, hive: Boolean): Double {
        fun size(o: BookOrder) = (if (hive) o.hive else o.hbd) / 1000.0
        var available = size(book[0])
        var index = 0
        while (available < amount && book.size > index + 1) {
            available += size(book[index])
            index++
        }
        return book[index].realPrice
    }

    fun quote(bids: List<BookOrder>, asks: List<BookOrder>, fromAmount: Double, fromHive: Boolean): SwapQuote {
        if (bids.isEmpty() || asks.isEmpty()) return SwapQuote(null, false, false, emptyOrderBook = true)
        val book = if (fromHive) bids else asks
        val available = book.sumOf { if (fromHive) it.hive else it.hbd } / 1000.0
        val price = price(fromAmount, book, fromHive)
        val to = if (fromHive) fromAmount * price else fromAmount / price
        val first = book[0].realPrice
        if (available == 0.0) return SwapQuote(null, false, false, emptyOrderBook = true)
        val slippage = kotlin.math.abs(price - first) > 0.01
        return when {
            fromAmount > available -> SwapQuote(null, slippage, invalidAmount = true)
            to != 0.0 && !to.isNaN() -> SwapQuote(to, slippage, invalidAmount = false)
            else -> SwapQuote(null, slippage, invalidAmount = false)
        }
    }

    /** RN stripDecimalPlaces(x, 3): floor to 3 decimals. */
    fun strip3(value: Double): Double = if (value == 0.0 || value.isNaN()) 0.0 else kotlin.math.floor(value * 1000) / 1000
}

object TradeCopy {
    val TITLE: String get() = L.trade.swapToken
    val CONFIRM_SWAP: String get() = L.trade.confirmSwap
    val FEE: String get() = L.trade.fee
    val FREE: String get() = L.trade.free
    val MORE_THAN_BALANCE: String get() = L.trade.moreThanBalance
    val OFFER_UNAVAILABLE: String get() = L.trade.offerUnavailable
    val TOO_MUCH_SLIPPAGE: String get() = L.trade.tooMuchSlippage
    val SUCCESSFUL: String get() = L.trade.swapSuccessful
    val PENDING: String get() = L.trade.swapPending
    val PENDING_BODY: String get() = L.trade.swapPendingBody
    val NEW_SWAP: String get() = L.trade.newSwap
    val DONE: String get() = L.alert.done
    val SWAP_FAILED: String get() = L.alert.swapFailed
    val MARKET_FAILED: String get() = L.alert.marketDataLoadFailed

    fun swapFor(from: String, to: String) = L.trade.swapFor(fromAmount = from, toAmount = to)
}

/** How a swap ended. */
enum class TradeOutcome { SUCCESSFUL, PENDING }

data class TradeState(
    val from: String = "HIVE",
    val fromAmount: String = "",
    val toAmount: Double = 0.0,
    val balance: Double = 0.0,
    val rateText: String? = null,
    val bookLoading: Boolean = true,
    val quote: SwapQuote? = null,
    val prompt: TradePrompt? = null,
    val actionId: String? = null,
    val phase: SendPhase? = null,
    /** After a swap: pending (the order is still open) or done. */
    /** How the swap ended; null while it has not. */
    val outcome: TradeOutcome? = null,
    val closed: Boolean = false,
) {
    /** The outcome's title in the current language. */
    val result: String? get() = outcome?.let { if (it == TradeOutcome.PENDING) TradeCopy.PENDING else TradeCopy.SUCCESSFUL }

    /** The swap may still be pending (RN then explains under the title). */
    val pending: Boolean get() = outcome == TradeOutcome.PENDING

    val to: String get() = if (from == "HIVE") "HBD" else "HIVE"

    /** The typed amount through the one amount reading (".5" is 0.5); 0 when empty or not an amount. */
    val fromValue: Double get() = Dec.of(fromAmount)?.toString()?.toDouble() ?: 0.0

    val moreThanBalance: Boolean get() = fromValue > balance

    val errors: List<String>
        get() = buildList {
            if (moreThanBalance) add(TradeCopy.MORE_THAN_BALANCE)
            if (quote?.invalidAmount == true) add(TradeCopy.OFFER_UNAVAILABLE)
            if (quote?.tooMuchSlippage == true) add(TradeCopy.TOO_MUCH_SLIPPAGE)
        }

    val busy: Boolean get() = phase is SendPhase.Sending || phase is SendPhase.Checking

    val continueEnabled: Boolean
        get() = !bookLoading && !moreThanBalance && quote?.invalidAmount != true && fromValue > 0 && toAmount > 0 && !busy && actionId == null
}

sealed interface TradePrompt {
    data class Confirm(val text: String) : TradePrompt

    data class Alert(val title: String, val message: String) : TradePrompt
}

/**
 * RN TradeScreen / swapTokenContent: a HIVE <-> HBD swap on the internal market as one `limit_order_create`
 * (orderid "9" + now % 1e8, minimum to receive = the quoted amount, 27 days, not fill or kill). The order book
 * refreshes every minute; after the send the open orders tell "pending" from "swapped".
 */
class TradeModel internal constructor(private val env: AppEnvironment, fund: String) :
    StateModel<TradeState>(TradeState(from = if (fund == "HBD") "HBD" else "HIVE"), env.dispatchers) {
    private val owned = SendOwnership(env.walletSends)

    /** A send still running goes on without the screen and reports its outcome itself. */
    override fun close() {
        owned.closeAll()
        super.close()
    }

    private var bids: List<BookOrder> = emptyList()
    private var asks: List<BookOrder> = emptyList()
    private var lowestAsk: Double? = null
    private var ordersBefore = 0
    private var started = false
    private var user: String? = null

    fun start() {
        if (started) return
        started = true
        // the portfolio (asset, balances, fiat rates) comes from the app-wide wallet, started here when opened directly
        env.wallet.start()
        scope.launch {
            env.session.awaitLoaded()
            user = env.session.current.username
            balance()
            while (isActive) {
                loadBook()
                delay(BOOK_REFRESH_MILLIS)
            }
        }
        scope.launch { env.wallet.state.collect { balance(); rate() } }
        scope.launch {
            env.walletSends.state.collect { s ->
                val id = current.actionId ?: return@collect
                val phase = s.sends[id]?.phase ?: return@collect
                if (phase != current.phase) onPhase(id, phase)
            }
        }
        scope.launch { env.events.collect { e -> if (e is AppEvent.SessionChanged && e.username != user) setState { it.copy(closed = true) } } }
    }

    private fun balance() = setState { s -> s.copy(balance = env.wallet.current.item(s.from)?.let { it.liquid ?: it.balance } ?: s.balance) }

    private suspend fun loadBook() {
        try {
            val book = env.walletApi.orderBook()
            bids = book.bids
            asks = book.asks
            lowestAsk = runCatching { env.walletApi.lowestAsk() }.getOrNull()
            setState { it.copy(bookLoading = false) }
            requote()
            rate()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            setState { it.copy(bookLoading = false, prompt = TradePrompt.Alert(TradeCopy.MARKET_FAILED, AppError.from(e).message)) }
        }
    }

    /** RN market rate line: "1 HIVE = 0.240 HBD ($0.240)". */
    private fun rate() {
        val ask = lowestAsk ?: return
        val s = current
        val r = if (s.from == "HIVE") ask else 1 / ask
        val fiat = env.wallet.current.item(s.to)?.fiatRate
        val currency = env.wallet.current.currency
        setState { it.copy(rateText = "${LocaleFormat.integer(1)} ${s.from} = ${LocaleFormat.toFixed(r, 3)} ${s.to}" + (fiat?.let { f -> " (${currency.symbol}${LocaleFormat.toFixed(r * f, 3)})" } ?: "")) }
    }

    private fun requote() {
        val s = current
        val amount = s.fromValue
        val q = SwapCalculator.quote(bids, asks, amount, s.from == "HIVE")
        setState { it.copy(quote = q, toAmount = if (amount == 0.0) 0.0 else q.toAmount?.let(SwapCalculator::strip3) ?: it.toAmount) }
    }

    fun setAmount(text: String) {
        if (current.busy) return
        val typed = Amounts.typed(text, 3)
        if (typed.isNotEmpty() && typed != "." && Dec.of(typed) == null) return
        setState { it.copy(fromAmount = typed) }
        requote()
    }

    /** RN: a tap on the balance fills the whole balance. */
    fun useBalance() = setAmount(Num.fixed(SwapCalculator.strip3(current.balance), 3))

    /** RN handleAssetChange: the other side, with the previous "to" amount as the new "from". */
    fun flip() {
        if (current.busy) return
        val previousTo = current.toAmount
        setState { it.copy(from = it.to, fromAmount = if (previousTo > 0) Num.fixed(previousTo, 3) else "", toAmount = 0.0) }
        balance()
        requote()
        rate()
    }

    fun next() {
        val s = current
        if (!s.continueEnabled) return
        setState { it.copy(prompt = TradePrompt.Confirm(TradeCopy.swapFor("${Amounts.fixed(s.fromAmount, 3)} ${s.from}", "${Num.fixed(s.toAmount, 3)} ${s.to}"))) }
    }

    fun dismissPrompt() = setState { it.copy(prompt = null) }

    fun confirm() {
        val s = current
        if (s.prompt !is TradePrompt.Confirm) return
        val owner = user ?: return
        val now = env.nowMillis()
        val op = try {
            WalletOps.limitOrderCreate(
                owner, "${Amounts.fixed(s.fromAmount, 3)} ${s.from}", "${Num.fixed(s.toAmount, 3)} ${s.to}", false,
                ChainTime.fromEpochSeconds(now / 1000 + EXPIRATION_SECONDS), orderId(now),
            )
        } catch (e: Exception) {
            setState { it.copy(prompt = TradePrompt.Alert(TradeCopy.SWAP_FAILED, AppError.from(e).message)) }
            return
        }
        val ops = WalletOps.ops(listOf(op))
        val actionId = env.idempotencyKey()
        setState { it.copy(prompt = null, actionId = actionId, phase = SendPhase.Sending) }
        scope.launch {
            ordersBefore = runCatching { env.walletApi.openOrders(owner).size }.getOrDefault(0)
            env.walletSends.send(owner, ops, OperationAuthority.required(ops), actionId)
            owned.owns(actionId)
        }
    }

    /** "New Swap": the screen again. */
    fun reset() = setState { TradeState(from = it.from, balance = it.balance, rateText = it.rateText, bookLoading = false) }.also { requote() }

    private fun onPhase(id: String, phase: SendPhase) {
        setState { it.copy(phase = phase) }
        when (phase) {
            is SendPhase.Done -> {
                owned.done(id)
                scope.launch {
                    delay(1_000)
                    val after = runCatching { env.walletApi.openOrders(user ?: return@launch).size }.getOrDefault(ordersBefore)
                    setState { it.copy(outcome = if (after != ordersBefore) TradeOutcome.PENDING else TradeOutcome.SUCCESSFUL) }
                    env.wallet.refreshLater()
                }
            }
            is SendPhase.Failed -> {
                owned.done(id)
                setState { it.copy(actionId = null, phase = null, prompt = TradePrompt.Alert(TradeCopy.SWAP_FAILED, phase.error.message)) }
            }
            is SendPhase.Cancelled -> { owned.done(id); setState { it.copy(actionId = null, phase = null) } }
            else -> Unit
        }
    }

    companion object {
        const val BOOK_REFRESH_MILLIS = 60_000L
        const val EXPIRATION_SECONDS = 27L * 86_400

        /** RN `parseInt("9" + (Date.now() % 100000000))` (OrderIdPrefix.SWAP). */
        fun orderId(nowMillis: Long): Long = "9${nowMillis % 100_000_000}".toLong()
    }
}

// ---- redeem: promote, boost+, RC top-up (RN screens/redeem) ----

enum class RedeemKind(val rn: String, private val titleText: () -> String) { PROMOTE("promote", { L.wallet.promote }), BOOST_PLUS("boost_plus", { L.boostPlus.title }), RC_TOPUP("rc_topup", { L.perks.rcTopup });

    val title: String get() = titleText()
}

object RedeemCopy {
    val USER: String get() = L.promote.user
    val POST: String get() = L.wallet.post
    val PERMLINK_PLACEHOLDER: String get() = L.promote.permlinkPlaceholder
    val DAYS: String get() = L.profile.days
    val PROMOTE_INFO: String get() = L.promote.information
    val CONFIRM_BOOST: String get() = L.boostPlus.confirmBoost
    val RC_CONFIRM: String get() = L.rcTopup.confirm
    val RC_DESC: String get() = L.rcTopup.desc
    val INSUFFICIENT: String get() = L.rcTopup.insufficient
    val BUY_POINTS: String get() = L.rcTopup.buyPoints
    val NOT_EXISTING_POST: String get() = L.alert.notExistingPost

    fun alreadyBoosted(date: String) = L.boostPlus.alreadyBoosted(date = date)

    fun points(value: String) = "$value Points"

    /** RN constants/options/points.ts PROMOTE_PRICING. */
    val PROMOTE_PRICES = listOf(DurationPrice(1, 150.0), DurationPrice(2, 250.0), DurationPrice(3, 350.0), DurationPrice(7, 500.0), DurationPrice(14, 1000.0))
}

data class RedeemState(
    val kind: RedeemKind,
    val user: String? = null,
    val balance: Double? = null,
    val permlink: String = "",
    val prices: List<DurationPrice> = emptyList(),
    val days: Int = 0,
    val boostedUntil: String? = null,
    val pricesLoading: Boolean = false,
    val prompt: TradePrompt? = null,
    val actionId: String? = null,
    val phase: SendPhase? = null,
    val closed: Boolean = false,
) {
    val price: Double? get() = prices.firstOrNull { it.duration == days }?.price

    val priceText: String get() = price?.let { RedeemCopy.points(LocaleFormat.toFixed(it, 0)) } ?: "--"

    val insufficient: Boolean get() = price != null && balance != null && price!! > balance

    val busy: Boolean get() = phase is SendPhase.Sending || phase is SendPhase.Checking

    val enabled: Boolean
        get() = !busy && actionId == null && price != null && balance != null && price!! <= balance &&
            (kind != RedeemKind.PROMOTE || permlink.contains('/')) && (kind != RedeemKind.BOOST_PLUS || boostedUntil == null)
}

/**
 * RN redeemScreen (PointsContainer + RedeemContainer): spends Ecency Points on a post promotion, a Boost+ for the
 * account or an RC top-up, each one active-key custom_json ([WalletOps.promote], [WalletOps.boostPlus],
 * [WalletOps.rcDelegation]) sent once per confirmation.
 */
class RedeemModel internal constructor(private val env: AppEnvironment, kind: RedeemKind, permlink: String) :
    StateModel<RedeemState>(RedeemState(kind, permlink = permlink), env.dispatchers) {
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
        scope.launch {
            env.session.awaitLoaded()
            val user = env.session.current.username ?: return@launch
            setState { it.copy(user = user, pricesLoading = true) }
            launch { loadBalance(user) }
            when (current.kind) {
                RedeemKind.PROMOTE -> setState { it.copy(prices = RedeemCopy.PROMOTE_PRICES, days = 1, pricesLoading = false) }
                RedeemKind.BOOST_PLUS -> {
                    val prices = runCatching { env.walletApi.prices(user, "boost-plus-price") }.getOrDefault(emptyList())
                    val until = runCatching { env.walletApi.boostedUntil(user, user) }.getOrNull()
                    val active = until?.let { boostEnd(it) }?.takeIf { it > env.nowMillis() }
                    setState {
                        it.copy(
                            prices = prices, days = prices.firstOrNull { p -> p.duration == 7 }?.duration ?: prices.firstOrNull()?.duration ?: 0,
                            boostedUntil = active?.let { m -> ChainTime.fromEpochSeconds(m / 1000).substringBefore('T') }, pricesLoading = false,
                        )
                    }
                }
                RedeemKind.RC_TOPUP -> {
                    val prices = runCatching { env.walletApi.prices(user, "rc-delegation-price") }.getOrDefault(emptyList())
                    setState { it.copy(prices = prices, days = prices.firstOrNull { p -> p.duration == 1 }?.duration ?: prices.firstOrNull()?.duration ?: 0, pricesLoading = false) }
                }
            }
        }
        scope.launch {
            env.walletSends.state.collect { s ->
                val id = current.actionId ?: return@collect
                val phase = s.sends[id]?.phase ?: return@collect
                if (phase != current.phase) onPhase(id, phase)
            }
        }
        scope.launch { env.events.collect { e -> if (e is AppEvent.SessionChanged && e.username != current.user) setState { it.copy(closed = true) } } }
    }

    /** RN: points again when the screen comes back (an in-app purchase may have added some). */
    fun refreshBalance() { current.user?.let { u -> scope.launch { loadBalance(u) } } }

    private suspend fun loadBalance(user: String) {
        val points = runCatching { env.walletApi.points(user).first }.getOrNull() ?: return
        setState { it.copy(balance = kotlin.math.round((points.replace(",", "").toDoubleOrNull() ?: 0.0) * 1000) / 1000) }
    }

    private fun boostEnd(expires: String): Long? =
        expires.toLongOrNull() ?: runCatching { ChainTime.toEpochSeconds(expires.take(19)) * 1000 }.getOrNull()

    fun setPermlink(text: String) = setState { it.copy(permlink = text.trim()) }

    fun setDays(days: Int) {
        if (current.prices.any { it.duration == days }) setState { it.copy(days = days) }
    }

    fun next() {
        val s = current
        if (!s.enabled) return
        val text = when (s.kind) {
            RedeemKind.PROMOTE -> RedeemCopy.PROMOTE_INFO
            RedeemKind.BOOST_PLUS -> RedeemCopy.CONFIRM_BOOST
            RedeemKind.RC_TOPUP -> RedeemCopy.RC_CONFIRM
        }
        setState { it.copy(prompt = TradePrompt.Confirm(text)) }
    }

    fun dismissPrompt() = setState { it.copy(prompt = null) }

    fun confirm() {
        val s = current
        if (s.prompt !is TradePrompt.Confirm) return
        val user = s.user ?: return
        setState { it.copy(prompt = null) }
        scope.launch {
            val op: Operation = try {
                when (s.kind) {
                    RedeemKind.PROMOTE -> {
                        val (author, permlink) = s.permlink.removePrefix("@").split('/', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                        // RN: the post must exist
                        val post = runCatching { env.api.hive.post(author, permlink) }.getOrNull()
                        if (post == null) {
                            setState { it.copy(prompt = TradePrompt.Alert(WalletCopy.FAIL, RedeemCopy.NOT_EXISTING_POST)) }
                            return@launch
                        }
                        WalletOps.promote(user, author, permlink, s.days)
                    }
                    RedeemKind.BOOST_PLUS -> WalletOps.boostPlus(user, user, s.days)
                    RedeemKind.RC_TOPUP -> WalletOps.rcDelegation(user, s.days)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setState { it.copy(prompt = TradePrompt.Alert(WalletCopy.FAIL, AppError.from(e).message)) }
                return@launch
            }
            val ops = WalletOps.ops(listOf(op))
            val actionId = env.idempotencyKey()
            setState { it.copy(actionId = actionId, phase = SendPhase.Sending) }
            env.walletSends.send(user, ops, OperationAuthority.required(ops), actionId)
            owned.owns(actionId)
        }
    }

    private fun onPhase(id: String, phase: SendPhase) {
        setState { it.copy(phase = phase) }
        when (phase) {
            is SendPhase.Done -> { owned.done(id); env.ui.toast(WalletCopy.SUCCESSFUL); env.wallet.refreshLater(); setState { it.copy(closed = true) } }
            // RN: the key warning and the message
            is SendPhase.Failed -> { owned.done(id); env.ui.toast("${WalletCopy.KEY_WARNING}\n${phase.error.message}"); setState { it.copy(actionId = null, phase = null) } }
            is SendPhase.Cancelled -> { owned.done(id); setState { it.copy(actionId = null, phase = null) } }
            else -> Unit
        }
    }
}

// ---- tipping (RN components/tipping) ----

object TipCopy {
    val TITLE: String get() = L.tipping.send
    val TOKEN: String get() = L.tipping.currency
    val AMOUNT: String get() = L.wallet.amount
    val SEND: String get() = L.tipping.send
    val CONFIRM_TITLE: String get() = L.tipping.confirmTitle
    val INVALID: String get() = L.tipping.invalidAmount
    val SUCCESS: String get() = L.tipping.success
    val ERROR: String get() = L.tipping.error
    val EXISTING: String get() = L.tipping.existingTips
    val NO_TIPS: String get() = L.tipping.noTips
    val LOGIN_REQUIRED: String get() = L.native.tip.loginRequired

    fun supportAuthor(author: String) = L.tipping.sendTipTo(author = author)

    fun confirm(amount: String, currency: String, author: String) = L.tipping.confirmMessage(amount = amount, currency = currency, author = author)

    fun maxDecimals(n: Int) = L.native.tip.maxDecimals(n = n)

    /** RN tip memo. */
    /** The memo sent on chain with the tip (RN tippingDialog): fixed English, never the app language. */
    fun memo(author: String, permlink: String) = "Tip for @$author/$permlink"
}

data class TipCurrency(val symbol: String, val precision: Int, val balance: String, val engine: Boolean)

data class TipState(
    val author: String,
    val permlink: String,
    val user: String? = null,
    val currencies: List<TipCurrency> = listOf(TipCurrency("HIVE", 3, "", false), TipCurrency("HBD", 3, "", false), TipCurrency("POINTS", 3, "", false)),
    val currency: String = "HIVE",
    val amount: String = "",
    val tipsCount: Int = 0,
    val tipsTotals: List<Pair<String, String>> = emptyList(),
    val confirming: Boolean = false,
    val actionId: String? = null,
    val phase: SendPhase? = null,
    val closed: Boolean = false,
) {
    val selected: TipCurrency get() = currencies.firstOrNull { it.symbol == currency } ?: currencies.first()

    val amountValid: Boolean
        get() {
            val d = Dec.of(amount) ?: return false
            val decimals = amount.substringAfter('.', "").length
            return !d.isZero && decimals <= selected.precision
        }

    val busy: Boolean get() = phase is SendPhase.Sending || phase is SendPhase.Checking

    val sendEnabled: Boolean get() = user != null && amountValid && !busy && actionId == null
}

/**
 * RN tippingDialog + useSendTipMutation: a tip to a post's author in HIVE, HBD, POINTS or a Hive Engine token the
 * account holds, memo "Tip for @author/permlink", confirmed once and sent with one action id.
 */
class TipModel internal constructor(private val env: AppEnvironment, author: String, permlink: String) :
    StateModel<TipState>(TipState(author, permlink), env.dispatchers) {
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
        scope.launch {
            env.session.awaitLoaded()
            val user = env.session.current.username
            setState { it.copy(user = user) }
            launch {
                val tips = runCatching { env.walletApi.postTips(current.author, current.permlink) }.getOrNull()
                if (tips != null) setState { it.copy(tipsCount = tips.count, tipsTotals = tips.totals) }
            }
            if (user == null) return@launch
            val all = runCatching { env.walletApi.portfolio(user, env.wallet.current.currency.code, onlyEnabled = false) }.getOrDefault(emptyList())
            fun bal(sym: String) = all.firstOrNull { it.symbol == sym }?.let { Amounts.fixed(Num.fixed(it.liquid ?: it.balance, 3), 3) }.orEmpty()
            val engine = all.filter { it.layer == AssetLayer.ENGINE && it.balance > 0 }.sortedBy { it.symbol }
                .map { TipCurrency(it.symbol, it.precision.takeIf { p -> p in 0..8 } ?: 8, Amounts.tokenQuantity(it.balance.toString(), it.precision), true) }
            setState { s ->
                s.copy(currencies = listOf(TipCurrency("HIVE", 3, bal("HIVE"), false), TipCurrency("HBD", 3, bal("HBD"), false), TipCurrency("POINTS", 3, bal("POINTS"), false)) + engine)
            }
        }
        scope.launch {
            env.walletSends.state.collect { s ->
                val id = current.actionId ?: return@collect
                val phase = s.sends[id]?.phase ?: return@collect
                if (phase != current.phase) onPhase(id, phase)
            }
        }
    }

    fun setCurrency(symbol: String) {
        if (current.busy || current.currencies.none { it.symbol == symbol }) return
        setState { it.copy(currency = symbol, amount = Amounts.capDecimals(it.amount, it.currencies.first { c -> c.symbol == symbol }.precision)) }
    }

    /** RN: comma to dot ([Amounts.typedText]: also the language's digits), digits and one dot only, decimals capped at the token's precision. */
    fun setAmount(text: String) {
        if (current.busy) return
        val t = Amounts.typedText(text)
        if (!Regex("^[0-9]*\\.?[0-9]*$").matches(t)) return
        if (t.substringAfter('.', "").length > current.selected.precision) return
        setState { it.copy(amount = t) }
    }

    fun send() {
        if (!current.sendEnabled) return
        setState { it.copy(confirming = true) }
    }

    fun dismissConfirm() = setState { it.copy(confirming = false) }

    fun confirm() {
        val s = current
        if (!s.confirming) return
        val user = s.user ?: return
        val c = s.selected
        val memo = TipCopy.memo(s.author, s.permlink)
        val op = try {
            val amount = Amounts.fixed(s.amount, c.precision)
            if (Dec.of(amount)?.isZero != false) throw IllegalArgumentException(TipCopy.INVALID)
            when {
                c.engine -> WalletOps.engine(user, WalletOps.EngineAction.TRANSFER, c.symbol, s.author, Amounts.tokenQuantity(amount, c.precision), memo)
                c.symbol == "POINTS" -> WalletOps.pointTransfer(user, s.author, "$amount POINT", memo)
                else -> WalletOps.transfer(user, s.author, "$amount ${c.symbol}", memo)
            }
        } catch (e: Exception) {
            env.ui.toast(TipCopy.ERROR)
            setState { it.copy(confirming = false) }
            return
        }
        val ops = WalletOps.ops(listOf(op))
        val actionId = env.idempotencyKey()
        setState { it.copy(confirming = false, actionId = actionId, phase = SendPhase.Sending) }
        env.walletSends.send(user, ops, OperationAuthority.required(ops), actionId)
        owned.owns(actionId)
    }

    private fun onPhase(id: String, phase: SendPhase) {
        setState { it.copy(phase = phase) }
        when (phase) {
            is SendPhase.Done -> { owned.done(id); env.ui.toast(TipCopy.SUCCESS); env.wallet.refreshLater(); env.tipped.tryEmit(com.ecency.core.api.model.PostKey(current.author, current.permlink)); setState { it.copy(closed = true) } }
            is SendPhase.Failed -> { owned.done(id); env.ui.toast(TipCopy.ERROR); setState { it.copy(closed = true) } }
            is SendPhase.Cancelled -> { owned.done(id); setState { it.copy(actionId = null, phase = null) } }
            else -> Unit
        }
    }
}

// ---- manage assets (RN screens/assetsSelect) ----

data class SelectableAsset(val symbol: String, val name: String, val iconUrl: String?, val layer: AssetLayer)

data class ManageAssetsState(
    val user: String? = null,
    val selected: List<SelectableAsset> = emptyList(),
    val available: List<SelectableAsset> = emptyList(),
    val query: String = "",
    val isLoading: Boolean = true,
    val saving: Boolean = false,
    val error: String? = null,
    val closed: Boolean = false,
) {
    /** RN search: symbol or name; selected tokens always stay visible. */
    val visibleAvailable: List<SelectableAsset>
        get() = if (query.isBlank()) available else available.filter { it.symbol.contains(query.trim(), true) || it.name.contains(query.trim(), true) }
}

/**
 * RN AssetsSelect: which Hive Engine and chain tokens the Wallet tab shows and in which order, saved on chain in
 * `profile.tokens` (account_update2 through the guarded profile write path), as RN: HIVE entries never written,
 * CHAIN entries kept with their `meta.show`, ENGINE entries in the selection order.
 */
class ManageAssetsModel internal constructor(private val env: AppEnvironment) : StateModel<ManageAssetsState>(ManageAssetsState(), env.dispatchers) {
    private var existing: List<ProfileToken> = emptyList()
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            env.session.awaitLoaded()
            val user = env.session.current.username ?: return@launch
            setState { it.copy(user = user) }
            val all = runCatching { env.walletApi.portfolio(user, env.wallet.current.currency.code, onlyEnabled = false) }.getOrDefault(emptyList())
            val account = runCatching { env.walletApi.account(user) }.getOrNull()
            existing = account?.profileTokens.orEmpty()
            val selectable = all.filter { it.layer != AssetLayer.HIVE && it.layer != AssetLayer.POINTS }.map(::selectable).toMutableList()
            // RN: engine balances the portfolio did not list can still be picked
            runCatching { env.walletApi.engineBalances(user) }.getOrNull()?.keys?.forEach { sym ->
                if (selectable.none { it.symbol == sym }) selectable += SelectableAsset(sym, sym, null, AssetLayer.ENGINE)
            }
            val shown = existing.filter { it.show && it.type != "HIVE" && it.symbol != "SPK" }
            val selected = shown.map { t -> selectable.firstOrNull { it.symbol == t.symbol } ?: SelectableAsset(t.symbol, t.symbol, null, if (t.type == "CHAIN") AssetLayer.CHAIN else AssetLayer.ENGINE) }
            setState { it.copy(selected = selected, available = selectable.filter { a -> selected.none { s -> s.symbol == a.symbol } }, isLoading = false) }
        }
    }

    private fun selectable(p: PortfolioItem) = SelectableAsset(p.symbol, p.name, p.iconUrl, p.layer)

    fun setQuery(q: String) = setState { it.copy(query = q) }

    fun toggle(symbol: String) = setState { s ->
        val sel = s.selected.firstOrNull { it.symbol == symbol }
        if (sel != null) {
            s.copy(selected = s.selected - sel, available = listOf(sel) + s.available)
        } else {
            val a = s.available.firstOrNull { it.symbol == symbol } ?: return@setState s
            s.copy(selected = s.selected + a, available = s.available - a)
        }
    }

    /** Drag reorder of the selected set. */
    fun move(from: Int, to: Int) = setState { s ->
        if (from !in s.selected.indices || to !in s.selected.indices) return@setState s
        val list = s.selected.toMutableList()
        val item = list.removeAt(from)
        list.add(to, item)
        s.copy(selected = list)
    }

    fun confirm() {
        val s = current
        val user = s.user ?: return
        if (s.saving) return
        val chainSelected = s.selected.filter { it.layer == AssetLayer.CHAIN }.map { it.symbol }
        val chain = existing.filter { it.type == "CHAIN" }.map { t ->
            val raw = t.raw?.let { JsValue.from(it) as? JsValue.Obj }?.copy() ?: JsValue.Obj(mapOf("symbol" to JsValue.Str(t.symbol), "type" to JsValue.Str("CHAIN")))
            val meta = (raw["meta"] as? JsValue.Obj)?.copy() ?: JsValue.Obj()
            meta["show"] = JsValue.Bool(t.symbol in chainSelected)
            raw["meta"] = meta
            raw
        }
        val engine = s.selected.filter { it.layer == AssetLayer.ENGINE }.map {
            JsValue.Obj(mapOf("symbol" to JsValue.Str(it.symbol), "type" to JsValue.Str("ENGINE"), "meta" to JsValue.Obj(mapOf("show" to JsValue.Bool(true)))))
        }
        val tokens = JsValue.Arr(chain + engine)
        setState { it.copy(saving = true, error = null) }
        scope.launch {
            try {
                val checked = ProfileWrites.checkedBase(env, user)
                ProfileWrites.broadcast(env, user, checked, JsValue.Obj(mapOf("tokens" to tokens)))
                val newTokens = ProfileToken.fromMetadata(kotlinx.serialization.json.JsonPrimitive("{\"profile\":{\"tokens\":${JsValue.stringify(tokens)}}}"))
                env.wallet.setSelection(newTokens)
                env.wallet.refreshLater()
                setState { it.copy(saving = false, closed = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is com.ecency.core.auth.AuthException.Cancelled) setState { it.copy(saving = false) }
                else setState { it.copy(saving = false, error = "${WalletCopy.UPDATE_TOKENS_FAILED} ${AppError.from(e).message}") }
            }
        }
    }

    fun dismissError() = setState { it.copy(error = null) }
}

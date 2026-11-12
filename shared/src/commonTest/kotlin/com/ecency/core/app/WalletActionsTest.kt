package com.ecency.core.app

import com.ecency.core.api.FakeTransport
import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.api.wallet.BookOrder
import com.ecency.core.auth.AuthLink
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Delegation, power down and routes, the swap, promote / boost / RC top-up, tips, manage assets and the wallet links. */
class WalletActionsTest {
    private fun world(scope: TestScope) = WalletWorld(scope, tokensJson = """[{"symbol":"LEO","type":"ENGINE","meta":{"show":true}},{"symbol":"BNB","type":"CHAIN","meta":{"show":true,"address":"0xabc"}},{"symbol":"OLD","type":"HIVE"}]""")

    // ---- delegation ----

    @Test
    fun delegatingTakesTheExistingDelegationIntoTheMaxAndSendsTheNewTotal() = runTest {
        val w = world(this)
        w.rpc["condenser_api.get_vesting_delegations"] = { """[{"id":1,"delegator":"spike-voter","delegatee":"spike-bob","vesting_shares":"20000.000000 VESTS","min_delegation_time":"2026-01-01T00:00:00"}]""" }
        val m = w.env.delegateModel("spike-bob")
        m.start()
        testScheduler.advanceUntilIdle()
        // own 2,000,000 - delegated 100,000 = 1,900,000 VESTS free, plus the 20,000 already with spike-bob
        assertEquals(1_920_000.0, m.current.maxVests)
        assertEquals(960.0, m.current.maxHp) // x 500 / 1e6
        assertEquals("10.000", m.current.existingHp)
        assertEquals("10.000", m.current.hp) // preset to the current delegation (RN)
        m.max()
        m.review()
        val review = assertIs<StakePrompt.Review>(m.current.prompt)
        assertEquals("1920000.000000", review.vests) // the whole amount, never a rounded figure above it
        assertEquals(StakeCopy.overwrite("10.000"), review.overwrite)
        m.confirm()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("""["delegate_vesting_shares",{"delegator":"spike-voter","delegatee":"spike-bob","vesting_shares":"1920000.000000 VESTS"}]"""), w.broadcastOpsJson())
    }

    @Test
    fun aDelegationGoesToTheRecipientTheReviewShowed() = runTest {
        val w = world(this)
        w.rpc["condenser_api.get_vesting_delegations"] = { "[]" }
        val m = w.env.delegateModel("spike-bob")
        m.start()
        testScheduler.advanceUntilIdle()
        m.setHp("1")
        m.review()
        val review = assertIs<StakePrompt.Review>(m.current.prompt)
        assertEquals("spike-bob", review.delegatee)
        // the field changes behind the open review
        m.setRecipient("spike-mallory")
        testScheduler.advanceUntilIdle()
        m.confirm()
        testScheduler.advanceUntilIdle()
        assertEquals(
            """["delegate_vesting_shares",{"delegator":"spike-voter","delegatee":"spike-bob","vesting_shares":"${review.vests} VESTS"}]""",
            w.broadcastOpsJson().single(),
        )
    }

    @Test
    fun theMaxDelegatesTheExactVestsNotARoundedHpFigure() = runTest {
        val w = world(this)
        w.rpc["condenser_api.get_vesting_delegations"] = { "[]" }
        w.rpc["condenser_api.get_accounts"] = { """[{"name":"spike-voter","vesting_shares":"1000.123457 VESTS","delegated_vesting_shares":"0.000000 VESTS","next_vesting_withdrawal":"1969-12-31T23:59:59"}]""" }
        val m = w.env.delegateModel("spike-bob")
        m.start()
        testScheduler.advanceUntilIdle()
        assertEquals(0.5, m.current.maxHp) // 1000.123457 x 500 / 1e6, floored to 3 decimals
        m.max()
        m.review()
        assertEquals("1000.123457", assertIs<StakePrompt.Review>(m.current.prompt).vests)
    }

    @Test
    fun zeroUndelegatesOnlyAnExistingDelegationAndTooMuchIsRefused() = runTest {
        val w = world(this)
        w.rpc["condenser_api.get_vesting_delegations"] = { "[]" }
        val m = w.env.delegateModel("spike-bob")
        m.start()
        testScheduler.advanceUntilIdle()
        m.setHp("0")
        m.review()
        assertEquals(StakePrompt.Alert(StakeCopy.INVALID_AMOUNT, StakeCopy.INVALID_AMOUNT_DESC), m.current.prompt)
        m.dismissPrompt()
        m.setHp("961")
        m.review()
        assertIs<StakePrompt.Alert>(m.current.prompt)
        assertTrue(w.app.broadcasts().isEmpty())
    }

    // ---- power down ----

    @Test
    fun aPowerDownSendsVestsAndShowsTheWeeklyEstimate() = runTest {
        val w = world(this)
        w.rpc["condenser_api.get_withdraw_routes"] = { "[]" }
        val m = w.env.powerDownModel(routesOnly = false)
        m.start()
        testScheduler.advanceUntilIdle()
        assertEquals(950.0, m.current.maxHp)
        m.setHp("130")
        assertEquals(com.ecency.core.l10n.LocaleFormat.toFixed(10.0, 3), m.current.weekly) // display: "10.000" in en-US
        m.next()
        assertEquals(StakePrompt.ConfirmPowerDown("260000.000000"), m.current.prompt)
        m.confirm()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("""["withdraw_vesting",{"account":"spike-voter","vesting_shares":"260000.000000 VESTS"}]"""), w.broadcastOpsJson())
    }

    @Test
    fun stoppingARunningPowerDownSendsZeroAndRoutesUseChainUnits() = runTest {
        val w = world(this)
        w.rpc["condenser_api.get_accounts"] = { r ->
            """[{"name":"spike-voter","vesting_shares":"2000000.000000 VESTS","delegated_vesting_shares":"0.000000 VESTS","vesting_withdraw_rate":"10000.000000 VESTS",
               "next_vesting_withdrawal":"2026-10-09T12:00:00","to_withdraw":130000000000,"withdrawn":10000000000}]"""
        }
        w.rpc["condenser_api.get_withdraw_routes"] = { """[{"id":1,"from_account":"spike-voter","to_account":"spike-carol","percent":2500,"auto_vest":true}]""" }
        val m = w.env.powerDownModel(routesOnly = false)
        m.start()
        testScheduler.advanceUntilIdle()
        assertTrue(m.current.poweringDown)
        assertEquals(StakeCopy.nextPowerDown(3, com.ecency.core.l10n.LocaleFormat.toFixed(5.0, 3)), m.current.nextInfo) // display, in the language of the run
        assertFalse(m.current.showRoutes)
        m.stop()
        assertEquals(StakePrompt.ConfirmStop, m.current.prompt)
        m.confirm()
        testScheduler.advanceUntilIdle()
        assertEquals("""["withdraw_vesting",{"account":"spike-voter","vesting_shares":"0.000000 VESTS"}]""", w.broadcastOpsJson().single())
        val r = w.env.powerDownModel(routesOnly = true)
        r.start()
        testScheduler.advanceUntilIdle()
        assertTrue(r.current.showRoutes)
        r.addRoute("spike-carol", 10, false)
        assertEquals(StakePrompt.Alert(StakeCopy.ADD_ROUTE_FAILED, StakeCopy.SAME_USER), r.current.prompt)
        r.dismissPrompt()
        r.addRoute("@spike-bob", 25, true)
        testScheduler.advanceUntilIdle()
        assertEquals("""["set_withdraw_vesting_route",{"from_account":"spike-voter","to_account":"spike-bob","percent":2500,"auto_vest":true}]""", w.broadcastOpsJson().last())
        r.removeRoute("spike-carol")
        r.confirm()
        testScheduler.advanceUntilIdle()
        assertEquals("""["set_withdraw_vesting_route",{"from_account":"spike-voter","to_account":"spike-carol","percent":0,"auto_vest":false}]""", w.broadcastOpsJson().last())
    }

    // ---- trade ----

    @Test
    fun theSwapCalculatorKeepsRnsPriceWalk() {
        val bids = listOf(BookOrder(hive = 1_000, hbd = 250, realPrice = 0.25), BookOrder(2_000, 480, 0.24), BookOrder(5_000, 1_000, 0.20))
        val asks = listOf(BookOrder(4_000, 1_040, 0.26))
        // 2 HIVE: book[0] (1) + book[0] again (1) reaches 2 at index 1, so the second level's price (RN quirk)
        assertEquals(0.24, SwapCalculator.price(2.0, bids, hive = true))
        val q = SwapCalculator.quote(bids, asks, 2.0, fromHive = true)
        assertEquals(0.48, q.toAmount!!, 1e-9)
        assertTrue(q.tooMuchSlippage) // 0.25 - 0.24 > 0.01
        assertTrue(SwapCalculator.quote(bids, asks, 9.0, fromHive = true).invalidAmount)
        val hbd = SwapCalculator.quote(bids, asks, 0.52, fromHive = false)
        assertEquals(2.0, hbd.toAmount!!, 1e-9)
        assertTrue(SwapCalculator.quote(emptyList(), asks, 1.0, true).emptyOrderBook)
        assertEquals(912345678L, TradeModel.orderId(1_791_212_345_678L))
    }

    @Test
    fun aSwapIsOneLimitOrderAndReportsPendingWhileTheOrderIsOpen() = runTest {
        val w = world(this)
        w.rpc["condenser_api.get_order_book"] = { """{"bids":[{"created":"x","hbd":2500,"hive":10000,"order_price":{"base":"x","quote":"y"},"real_price":"0.25000000"}],"asks":[{"created":"x","hbd":2600,"hive":10000,"real_price":"0.26000000"}]}""" }
        w.rpc["condenser_api.get_ticker"] = { """{"latest":"0.25","lowest_ask":"0.26","highest_bid":"0.25"}""" }
        var orders = 0
        w.rpc["condenser_api.get_open_orders"] = { if (orders++ == 0) "[]" else """[{"orderid":1,"created":"x","expiration":"y","for_sale":1,"sell_price":{"base":"1.000 HIVE","quote":"0.250 HBD"}}]""" }
        w.env.wallet.start()
        val m = w.env.tradeModel("HIVE")
        // closed even when an assertion fails: the order book timer would otherwise run forever in runTest's cleanup
        try {
            m.start()
            testScheduler.advanceTimeBy(1_000)
            // display text, in the app language (ChainAmountsLocaleTest runs this test in other languages too)
            val rate = com.ecency.core.l10n.LocaleFormat.toFixed(0.26, 3)
            val one = com.ecency.core.l10n.LocaleFormat.integer(1)
            assertEquals(if (com.ecency.core.l10n.L10n.language.value == "en-US") "1 HIVE = 0.260 HBD (\$0.260)" else "$one HIVE = $rate HBD (\$$rate)", m.current.rateText)
            m.setAmount("4")
            assertEquals(1.0, m.current.toAmount)
            m.setAmount("20") // more than the 12.345 balance
            assertTrue(TradeCopy.MORE_THAN_BALANCE in m.current.errors)
            assertFalse(m.current.continueEnabled)
            m.setAmount("4")
            m.next()
            assertEquals(TradePrompt.Confirm(TradeCopy.swapFor("4.000 HIVE", "1.000 HBD"), "4.000 HIVE", "1.000 HBD"), m.current.prompt) // copy in the language of the run, amounts POSIX
            m.confirm()
            testScheduler.advanceTimeBy(3_000)
            testScheduler.runCurrent()
            val op = w.broadcastOps().single()
            assertEquals("limit_order_create", op[0].jsonPrimitive.content)
            val body = op[1].jsonObject
            assertEquals("4.000 HIVE", body["amount_to_sell"]!!.jsonPrimitive.content)
            assertEquals("1.000 HBD", body["min_to_receive"]!!.jsonPrimitive.content)
            assertEquals("2026-11-02T12:00:00", body["expiration"]!!.jsonPrimitive.content) // 27 days
            assertEquals(TradeModel.orderId(TestApp.NOW), body["orderid"]!!.jsonPrimitive.content.toLong())
            assertEquals(TradeCopy.PENDING, m.current.result)
        } finally {
            m.close()
        }
    }

    @Test
    fun aRefreshThatFindsAnEmptyBookDropsTheOldQuote() = runTest {
        val w = world(this)
        var empty = false
        w.rpc["condenser_api.get_order_book"] = {
            if (empty) """{"bids":[],"asks":[]}"""
            else """{"bids":[{"created":"x","hbd":2500,"hive":10000,"order_price":{"base":"x","quote":"y"},"real_price":"0.25000000"}],"asks":[{"created":"x","hbd":2600,"hive":10000,"real_price":"0.26000000"}]}"""
        }
        w.rpc["condenser_api.get_ticker"] = { """{"latest":"0.25","lowest_ask":"0.26","highest_bid":"0.25"}""" }
        w.rpc["condenser_api.get_open_orders"] = { "[]" }
        w.env.wallet.start()
        val m = w.env.tradeModel("HIVE")
        try {
            m.start()
            testScheduler.advanceTimeBy(1_000)
            m.setAmount("4")
            assertEquals(1.0, m.current.toAmount)
            assertTrue(m.current.continueEnabled)
            // the next refresh finds no orders: the 1.000 HBD quote is gone and nothing can be confirmed
            empty = true
            testScheduler.advanceTimeBy(TradeModel.BOOK_REFRESH_MILLIS + 1_000)
            assertEquals(0.0, m.current.toAmount)
            assertTrue(TradeCopy.OFFER_UNAVAILABLE in m.current.errors)
            assertFalse(m.current.continueEnabled)
        } finally {
            m.close()
        }
    }

    @Test
    fun theSwapSignsTheAmountsTheUserConfirmedEvenWhenTheBookRefreshesMeanwhile() = runTest {
        val w = world(this)
        var worse = false
        w.rpc["condenser_api.get_order_book"] = {
            val hbd = if (worse) 2000 else 2500
            """{"bids":[{"created":"x","hbd":$hbd,"hive":10000,"order_price":{"base":"x","quote":"y"},"real_price":"0.${hbd}0000"}],"asks":[{"created":"x","hbd":2600,"hive":10000,"real_price":"0.26000000"}]}"""
        }
        w.rpc["condenser_api.get_ticker"] = { """{"latest":"0.25","lowest_ask":"0.26","highest_bid":"0.25"}""" }
        w.rpc["condenser_api.get_open_orders"] = { "[]" }
        w.env.wallet.start()
        val m = w.env.tradeModel("HIVE")
        try {
            m.start()
            testScheduler.advanceTimeBy(1_000)
            m.setAmount("4")
            m.next()
            val shown = assertIs<TradePrompt.Confirm>(m.current.prompt)
            assertEquals(TradeCopy.swapFor("4.000 HIVE", "1.000 HBD"), shown.text)
            // the book refreshes to a worse price while the question is open
            worse = true
            testScheduler.advanceTimeBy(TradeModel.BOOK_REFRESH_MILLIS + 1_000)
            assertEquals(shown, m.current.prompt, "the open question keeps its amounts")
            m.confirm()
            testScheduler.advanceTimeBy(3_000)
            testScheduler.runCurrent()
            val body = w.broadcastOps().single()[1].jsonObject
            assertEquals("4.000 HIVE", body["amount_to_sell"]!!.jsonPrimitive.content)
            assertEquals("1.000 HBD", body["min_to_receive"]!!.jsonPrimitive.content, "what was shown is what is signed")
        } finally {
            m.close()
        }
    }

    @Test
    fun aSwapQuestionClosedAfterARefreshShowsTheNewQuote() = runTest {
        val w = world(this)
        var worse = false
        w.rpc["condenser_api.get_order_book"] = {
            val hbd = if (worse) 2000 else 2500
            """{"bids":[{"created":"x","hbd":$hbd,"hive":10000,"order_price":{"base":"x","quote":"y"},"real_price":"0.${hbd}0000"}],"asks":[{"created":"x","hbd":2600,"hive":10000,"real_price":"0.26000000"}]}"""
        }
        w.rpc["condenser_api.get_ticker"] = { """{"latest":"0.25","lowest_ask":"0.26","highest_bid":"0.25"}""" }
        w.rpc["condenser_api.get_open_orders"] = { "[]" }
        w.env.wallet.start()
        val m = w.env.tradeModel("HIVE")
        try {
            m.start()
            testScheduler.advanceTimeBy(1_000)
            m.setAmount("4")
            m.next()
            worse = true
            testScheduler.advanceTimeBy(TradeModel.BOOK_REFRESH_MILLIS + 1_000)
            m.dismissPrompt()
            assertEquals(0.8, m.current.toAmount, "the screen shows the refreshed quote once the question closed")
            m.next()
            assertEquals(TradeCopy.swapFor("4.000 HIVE", "0.800 HBD"), assertIs<TradePrompt.Confirm>(m.current.prompt).text)
        } finally {
            m.close()
        }
    }

    /** A swap of the whole HIVE balance that fills at once (no open order before or after). */
    private fun TestScope.swappedWholeBalance(w: WalletWorld, m: TradeModel) {
        w.rpc["condenser_api.get_order_book"] = { """{"bids":[{"created":"x","hbd":25000,"hive":100000,"order_price":{"base":"x","quote":"y"},"real_price":"0.25000000"}],"asks":[{"created":"x","hbd":26000,"hive":100000,"real_price":"0.26000000"}]}""" }
        w.rpc["condenser_api.get_ticker"] = { """{"latest":"0.25","lowest_ask":"0.26","highest_bid":"0.25"}""" }
        w.rpc["condenser_api.get_open_orders"] = { "[]" }
        w.env.wallet.start()
        m.start()
        testScheduler.advanceTimeBy(1_000)
        m.useBalance()
        assertEquals("12.345", m.current.fromAmount)
        m.next()
        m.confirm()
        // the wallet reload after the broadcast finds the HIVE spent
        hiveLeft(w, "0")
        testScheduler.advanceTimeBy(10_000)
        testScheduler.runCurrent()
    }

    @Test
    fun aSwapThatWentThroughShowsItsDoneStateAndNoStaleFormErrors() = runTest {
        val w = world(this)
        val m = w.env.tradeModel("HIVE")
        try {
            swappedWholeBalance(w, m)
            assertEquals(1, w.broadcastOps().size)
            assertEquals(TradeOutcome.SUCCESSFUL, m.current.outcome)
            assertEquals(0.0, m.current.balance, "the balance reloaded under the done state")
            assertEquals(emptyList(), m.current.errors, "no 'more than your balance' under a success")
            assertTrue(m.current.finished, "the done state replaces the form")
            assertFalse(m.current.continueEnabled)
            assertEquals(TradeCopy.summary("12.345 HIVE", "3.086 HBD"), m.current.summary)
            // the form cannot be edited or sent again until New Swap
            m.setAmount("1")
            m.flip()
            assertEquals("12.345", m.current.fromAmount)
            assertEquals("HIVE", m.current.from)
            m.next()
            assertNull(m.current.prompt)
            testScheduler.advanceTimeBy(TradeModel.BOOK_REFRESH_MILLIS + 1_000)
            assertEquals(emptyList(), m.current.errors)
            assertEquals(1, w.broadcastOps().size)
        } finally {
            m.close()
        }
    }

    @Test
    fun newSwapStartsAnEmptyFormOnTheFreshBalance() = runTest {
        val w = world(this)
        val m = w.env.tradeModel("HIVE")
        try {
            swappedWholeBalance(w, m)
            m.reset()
            assertFalse(m.current.finished)
            assertNull(m.current.outcome)
            assertNull(m.current.summary)
            assertEquals("", m.current.fromAmount)
            assertEquals(0.0, m.current.balance)
            assertEquals(emptyList(), m.current.errors)
            m.setAmount("1")
            assertEquals("1", m.current.fromAmount, "the new form takes input again")
            assertTrue(TradeCopy.MORE_THAN_BALANCE in m.current.errors, "and checks it against the fresh balance")
        } finally {
            m.close()
        }
    }

    /** The chain and the portfolio both read [left] HIVE. */
    private fun hiveLeft(w: WalletWorld, left: String) {
        w.chainHive = left
        portfolioReads(w, left)
    }

    /** The portfolio alone reads [left] HIVE (it lags the chain). */
    private fun portfolioReads(w: WalletWorld, left: String) {
        w.http["/wallet-api/portfolio-v2"] = { HttpResult(200, WalletWorld.PORTFOLIO.replace(""""balance":12.345""", """"balance":$left""").replace(""""liquid":12.345""", """"liquid":$left""")) }
    }

    private fun TestScope.untilAccepted(m: TradeModel) {
        repeat(50) { if (m.current.phase !is SendPhase.Done) { testScheduler.advanceTimeBy(10); testScheduler.runCurrent() } }
        assertTrue(m.current.phase is SendPhase.Done, "the swap was accepted")
    }

    /** The phone's clock and the chain's both run on test time; the phone [phoneAheadMillis] ahead. */
    private fun TestScope.clocks(w: WalletWorld, phoneAheadMillis: Long = 0) {
        w.app.clock = { TestApp.NOW + testScheduler.currentTime + phoneAheadMillis }
        // the signer writes expirations from the same phone clock
        w.env.api.broadcaster.nowMillis = { w.app.clock() }
        w.chainClock = { TestApp.NOW + testScheduler.currentTime }
    }

    /** How long a key signed swap stays off a read that has it: its 60 s lifetime and the node lag margin. */
    private val keySettle = WalletModel.KEY_TRANSACTION_LIFETIME_MILLIS + WalletModel.NODE_LAG_MILLIS

    private fun TestScope.advance(millis: Long) {
        testScheduler.advanceTimeBy(millis)
        testScheduler.runCurrent()
    }

    /** The swap form opened on the 12.345 HIVE fixture, with a book to quote from. */
    private fun TestScope.swapFormOpen(w: WalletWorld, m: TradeModel, phoneAheadMillis: Long = 0) {
        clocks(w, phoneAheadMillis)
        w.rpc["condenser_api.get_order_book"] = { """{"bids":[{"created":"x","hbd":25000,"hive":100000,"order_price":{"base":"x","quote":"y"},"real_price":"0.25000000"}],"asks":[{"created":"x","hbd":26000,"hive":100000,"real_price":"0.26000000"}]}""" }
        w.rpc["condenser_api.get_ticker"] = { """{"latest":"0.25","lowest_ask":"0.26","highest_bid":"0.25"}""" }
        w.rpc["condenser_api.get_open_orders"] = { "[]" }
        w.env.wallet.start()
        m.start()
        testScheduler.advanceTimeBy(1_000)
    }

    /** A swap of [amount] (the whole balance when null) accepted by the node, which then reads [left]. */
    private fun TestScope.swapAcceptedNow(w: WalletWorld, m: TradeModel, amount: String? = null, left: String = "0", phoneAheadMillis: Long = 0) {
        swapFormOpen(w, m, phoneAheadMillis)
        if (amount == null) m.useBalance() else m.setAmount(amount)
        m.next()
        m.confirm()
        hiveLeft(w, left)
        untilAccepted(m)
        assertNull(m.current.outcome, "its outcome check still runs")
    }

    @Test
    fun doneTappedAtOnceStillReloadsTheWallet() = runTest {
        val w = world(this)
        val m = w.env.tradeModel("HIVE")
        swapAcceptedNow(w, m)
        // Done while the outcome check runs closes the screen and its model
        m.close()
        advance(10_000)
        assertEquals(0.0, w.env.wallet.current.item("HIVE")?.liquid, "the wallet reload outlives the closed swap screen")
    }

    @Test
    fun newSwapTappedAtOnceNeverOffersTheSpentBalance() = runTest {
        val w = world(this)
        val m = w.env.tradeModel("HIVE")
        try {
            swapAcceptedNow(w, m)
            m.reset()
            assertEquals(0.0, m.current.balance, "the swapped HIVE is gone before the wallet reloads")
            m.useBalance()
            assertFalse(m.current.continueEnabled, "no second swap of the spent funds")
            // 1.000 HIVE came in meanwhile: once the chain has decided the swap, the read stands as read
            hiveLeft(w, "1")
            advance(keySettle - 10_000)
            assertEquals(0.0, m.current.balance, "not before the swap's transaction expired")
            advance(15_000)
            assertEquals(1.0, m.current.balance)
        } finally {
            m.close()
        }
    }

    @Test
    fun aReadThatShowsTheWholeSwapTakesItOffOnceAtOnce() = runTest {
        val w = world(this)
        val m = w.env.tradeModel("HIVE")
        try {
            swapAcceptedNow(w, m, amount = "3", left = "9.345")
            m.reset()
            assertEquals(9.345, m.current.balance, 0.0001, "12.345 less the 3 swapped, before any reload")
            // a pull to refresh, sent after the swap, reads 12.345 less the whole 3: that read has it
            w.env.wallet.refresh()
            advance(100)
            assertEquals(9.345, w.env.wallet.current.item("HIVE")?.liquid)
            assertEquals(9.345, m.current.balance, 0.0001, "not taken off that read again")
        } finally {
            m.close()
        }
    }

    @Test
    fun aTransferFromAnotherWalletNeverPassesForTheSwap() = runTest {
        val w = world(this)
        val m = w.env.tradeModel("HIVE")
        try {
            // the node accepted the swap of 3, then another wallet sent 3 HIVE away first: the chain reads 9.345 with
            // no order of the swap in the account's history yet
            swapAcceptedNow(w, m, amount = "3", left = "9.345")
            w.ordersInHistory = false
            m.reset()
            w.env.wallet.refresh()
            advance(100)
            assertEquals(6.345, m.current.balance, 0.0001, "the swap's 3 are still committed, whatever the balance dropped by")
            // the swap lands: its order is in the history and the balance down by it too
            w.ordersInHistory = true
            hiveLeft(w, "6.345")
            w.env.wallet.refresh()
            advance(100)
            assertEquals(6.345, m.current.balance, 0.0001, "a read with the swap's own order takes it off at once")
        } finally {
            m.close()
        }
    }

    @Test
    fun aSwapWhoseBlockReachesTheHistoryLateStillComesOffInSeconds() = runTest {
        val w = world(this)
        val m = w.env.tradeModel("HIVE")
        try {
            // the reload after the swap reads the balance down by it before its block reached the account's history
            swapAcceptedNow(w, m, amount = "3", left = "9.345")
            w.ordersInHistory = false
            m.reset()
            advance(3_500)
            assertEquals(6.345, m.current.balance, 0.0001, "no order on chain yet: still taken off")
            w.ordersInHistory = true
            advance(5_500)
            assertEquals(9.345, m.current.balance, 0.0001, "a short look finds the order, long before the swap's expiration")
        } finally {
            m.close()
        }
    }

    @Test
    fun aFailedLoadAmongTheShortLooksKeepsThemGoing() = runTest {
        val w = world(this)
        val m = w.env.tradeModel("HIVE")
        try {
            swapAcceptedNow(w, m, amount = "3", left = "9.345")
            w.ordersInHistory = false
            m.reset()
            advance(3_500)
            assertEquals(6.345, m.current.balance, 0.0001, "no order on chain yet: still taken off")
            // the next short look fails
            val portfolio = w.http.getValue("/wallet-api/portfolio-v2")
            w.http["/wallet-api/portfolio-v2"] = { HttpResult(503, "") }
            advance(5_500)
            w.http["/wallet-api/portfolio-v2"] = portfolio
            w.ordersInHistory = true
            advance(5_500)
            assertEquals(9.345, m.current.balance, 0.0001, "the look after the failed one finds the order within seconds")
        } finally {
            m.close()
        }
    }

    @Test
    fun aSwapAcceptedButNotInABlockStaysOffWhateverItsAccountReads() = runTest {
        val w = world(this)
        val m = w.env.tradeModel("HIVE")
        try {
            swapAcceptedNow(w, m, amount = "3", left = "9.345")
            m.reset()
            // in the node's mempool, or read from a server behind it: the old balance, well after the reload
            hiveLeft(w, "12.345")
            advance(keySettle - 10_000)
            assertEquals(12.345, w.env.wallet.current.item("HIVE")?.liquid, "the scheduled reload landed")
            assertEquals(9.345, m.current.balance, 0.0001, "accepted is not decided: the swap stays off")
            // it landed, and 1 HIVE came in
            hiveLeft(w, "10.345")
            advance(15_000)
            assertEquals(10.345, m.current.balance, 0.0001, "decided: the read stands as read")
        } finally {
            m.close()
        }
    }

    @Test
    fun aSwapThatNeverLandedIsOfferedAgainOnceItExpired() = runTest {
        val w = world(this)
        val m = w.env.tradeModel("HIVE")
        try {
            swapAcceptedNow(w, m, amount = "3", left = "12.345")
            m.reset()
            assertEquals(9.345, m.current.balance, 0.0001)
            // it expired unincluded (or its order was cancelled): the 3 HIVE never left, or came back
            advance(keySettle + 5_000)
            assertEquals(12.345, m.current.balance, 0.0001, "past its expiration the chain's balance stands")
        } finally {
            m.close()
        }
    }

    @Test
    fun aPhoneClockAheadOfTheChainKeepsTheSwapOffUntilTheChainPassesItsExpiration() = runTest {
        val w = world(this)
        val m = w.env.tradeModel("HIVE")
        try {
            // the phone runs 5 minutes fast: the swap's transaction is valid 6 minutes on chain
            swapAcceptedNow(w, m, amount = "3", left = "12.345", phoneAheadMillis = 5 * 60_000L)
            m.reset()
            advance(keySettle + 5_000)
            // by the phone's clock it expired; a pull to refresh asks the chain
            w.env.wallet.refresh()
            advance(100)
            assertEquals(9.345, m.current.balance, 0.0001, "the chain has not passed its expiration: still off")
            advance(5 * 60_000L)
            assertEquals(12.345, m.current.balance, 0.0001, "past it on the chain's clock: it never landed")
        } finally {
            m.close()
        }
    }

    @Test
    fun twoSwapsEachComeOffOnce() = runTest {
        val w = world(this)
        val m = w.env.tradeModel("HIVE")
        try {
            swapAcceptedNow(w, m, amount = "4", left = "8.345")
            m.reset()
            assertEquals(8.345, m.current.balance, 0.0001)
            advance(20_000)
            assertEquals(8.345, m.current.balance, 0.0001, "the reload after it read the 4 gone: off once")
            m.setAmount("2")
            m.next()
            m.confirm()
            hiveLeft(w, "6.345")
            untilAccepted(m)
            m.reset()
            assertEquals(6.345, m.current.balance, 0.0001, "the second off the read that has the first")
            advance(5_000)
            assertEquals(6.345, w.env.wallet.current.item("HIVE")?.liquid, "the reload after the second landed")
            assertEquals(6.345, m.current.balance, 0.0001, "each taken off once")
        } finally {
            m.close()
        }
    }

    @Test
    fun aReadWithOnlyTheFirstOfTwoQuickSwapsKeepsTheSecondOff() = runTest {
        val w = world(this)
        val m = w.env.tradeModel("HIVE")
        try {
            swapAcceptedNow(w, m, amount = "4", left = "8.345")
            m.reset()
            // the second goes out before any reload; the node then has only the first
            m.setAmount("2")
            m.next()
            m.confirm()
            untilAccepted(m)
            m.reset()
            assertEquals(6.345, m.current.balance, 0.0001)
            advance(5_000)
            assertEquals(8.345, w.env.wallet.current.item("HIVE")?.liquid, "the reloads landed")
            assertEquals(6.345, m.current.balance, 0.0001, "the first shown and gone, the second still off")
            hiveLeft(w, "6.345")
            w.env.wallet.refresh()
            advance(100)
            assertEquals(6.345, m.current.balance, 0.0001, "both shown: each off once")
        } finally {
            m.close()
        }
    }

    @Test
    fun aLaggingPortfolioNeverBringsTheSpentHiveBack() = runTest {
        val w = world(this)
        val m = w.env.tradeModel("HIVE")
        try {
            swapAcceptedNow(w, m, amount = "3", left = "9.345")
            m.reset()
            // decided, 0.155 came in; the portfolio still has neither
            w.chainHive = "9.5"
            portfolioReads(w, "12.345")
            advance(keySettle + 5_000)
            assertEquals(12.345, w.env.wallet.current.item("HIVE")?.liquid, "the portfolio still lags")
            assertEquals(9.5, m.current.balance, 0.0001, "the chain's balance, not the portfolio's")
        } finally {
            m.close()
        }
    }

    @Test
    fun aSwapScreenReopenedBeforeTheReloadStillTakesTheSwapOff() = runTest {
        val w = world(this)
        val m = w.env.tradeModel("HIVE")
        swapAcceptedNow(w, m, amount = "3", left = "9.345")
        // Done closes the screen; swap opens again before the wallet reloads
        m.close()
        val again = w.env.tradeModel("HIVE")
        try {
            again.start()
            advance(500)
            assertEquals(9.345, again.current.balance, 0.0001, "the new screen knows the swap the closed one sent")
            advance(5_000)
            // a deposit after the reload that showed the swap
            w.chainHive = "9.5"
            w.env.wallet.refresh()
            advance(100)
            assertEquals(9.5, again.current.balance, 0.0001, "and the reads after it are not reduced again")
        } finally {
            again.close()
        }
    }

    @Test
    fun aSwapThatEndsAfterItsScreenClosedStillComesOff() = runTest {
        val w = world(this)
        val m = w.env.tradeModel("HIVE")
        swapFormOpen(w, m)
        // the node holds its answer; the user leaves the screen meanwhile
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        w.gates["condenser_api.broadcast_transaction"] = gate
        m.setAmount("3")
        m.next()
        m.confirm()
        testScheduler.runCurrent()
        m.close()
        w.gates.remove("condenser_api.broadcast_transaction")
        gate.complete(Unit)
        advance(100)
        assertEquals(1, w.app.broadcasts().size, "the swap went out")
        val again = w.env.tradeModel("HIVE")
        try {
            again.start()
            advance(500)
            assertEquals(9.345, again.current.balance, 0.0001, "the swap no screen saw end is still off")
        } finally {
            again.close()
        }
    }

    @Test
    fun aSwapWhoseOutcomeIsUnknownStaysOffAsLongAsItCouldLand() = runTest {
        val w = world(this)
        val m = w.env.tradeModel("HIVE")
        try {
            swapFormOpen(w, m)
            // every node may have taken it and none says: the send ends unconfirmed after its checks
            w.broadcastAnswer = { HttpResult(502, "") }
            m.setAmount("3")
            m.next()
            m.confirm()
            advance(WalletSends.CHECK_BOUND_MILLIS - 10_000)
            repeat(400) { if (m.current.phase !is SendPhase.Unconfirmed) advance(500) }
            assertIs<SendPhase.Unconfirmed>(m.current.phase)
            assertEquals(9.345, m.current.balance, 0.0001, "its own form takes it off as the outcome lands")
            m.close()
            val again = w.env.tradeModel("HIVE")
            try {
                again.start()
                advance(500)
                assertEquals(9.345, again.current.balance, 0.0001, "it may have landed: off")
                advance(30 * 60_000L)
                assertEquals(9.345, again.current.balance, 0.0001, "a transaction may live an hour")
                advance(40 * 60_000L)
                assertEquals(12.345, again.current.balance, 0.0001, "past any lifetime the chain's balance stands")
            } finally {
                again.close()
            }
        } finally {
            m.close()
        }
    }

    @Test
    fun aRefusedSwapIsNeverTakenOff() = runTest {
        val w = world(this)
        val m = w.env.tradeModel("HIVE")
        try {
            swapFormOpen(w, m)
            w.broadcastAnswer = { FakeTransport.error(it, 10, "Assert Exception: insufficient balance") }
            m.setAmount("3")
            m.next()
            m.confirm()
            advance(2_000)
            assertIs<TradePrompt.Alert>(m.current.prompt, "the chain refused it")
            assertEquals(12.345, m.current.balance, 0.0001, "nothing left the wallet")
        } finally {
            m.close()
        }
    }

    @Test
    fun anEarlierReadInFlightLeavesTheSwapOff() = runTest {
        val w = world(this)
        val m = w.env.tradeModel("HIVE")
        try {
            swapFormOpen(w, m)
            // a pull to refresh is in flight when the swap goes out; it reads a 3 HIVE transfer sent elsewhere
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            w.gates["condenser_api.get_accounts"] = gate
            hiveLeft(w, "9.345")
            w.env.wallet.refresh()
            testScheduler.runCurrent()
            m.setAmount("3")
            m.next()
            m.confirm()
            untilAccepted(m)
            m.reset()
            assertEquals(9.345, m.current.balance, 0.0001)
            w.gates.remove("condenser_api.get_accounts")
            gate.complete(Unit)
            advance(500)
            assertEquals(9.345, w.env.wallet.current.item("HIVE")?.liquid, "the earlier read landed")
            assertEquals(6.345, m.current.balance, 0.0001, "that read cannot have the swap, even one 3 HIVE lower")
        } finally {
            m.close()
        }
    }

    @Test
    fun aReadThatLandsBeforeTheSwapIsAcceptedNeverTakesItOffForGood() = runTest {
        val w = world(this)
        val m = w.env.tradeModel("HIVE")
        try {
            swapFormOpen(w, m)
            // the node holds its answer; meanwhile a reload reads the sale already on the chain
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            w.gates["condenser_api.broadcast_transaction"] = gate
            m.setAmount("3")
            m.next()
            m.confirm()
            testScheduler.runCurrent()
            hiveLeft(w, "9.345")
            w.env.wallet.refresh()
            advance(100)
            assertEquals(9.345, w.env.wallet.current.item("HIVE")?.liquid)
            w.gates.remove("condenser_api.broadcast_transaction")
            gate.complete(Unit)
            untilAccepted(m)
            m.reset()
            assertEquals(6.345, m.current.balance, 0.0001)
            w.chainHive = "9.5"
            advance(keySettle + 5_000)
            assertEquals(9.5, m.current.balance, 0.0001, "taken off once, not twice for good")
        } finally {
            m.close()
        }
    }

    @Test
    fun aFailedChainReadKeepsTheLastOneAndSettlesNothing() = runTest {
        val w = world(this)
        val m = w.env.tradeModel("HIVE")
        try {
            // not in a block yet when the reload reads the account
            swapAcceptedNow(w, m, amount = "3", left = "12.345")
            m.reset()
            advance(5_000)
            assertEquals(9.345, m.current.balance, 0.0001, "the reload's read, the swap off it")
            // account reads fail from now on; the portfolio has something else
            w.rpc["condenser_api.get_accounts"] = { "[]" }
            portfolioReads(w, "12.5")
            advance(keySettle * 3)
            assertEquals(12.5, w.env.wallet.current.item("HIVE")?.liquid, "reloads landed, their account reads failed")
            assertEquals(9.345, m.current.balance, 0.0001, "the last chain read stands and no read settled the swap")
        } finally {
            m.close()
        }
    }

    @Test
    fun aSwapSignedElsewhereStaysOffAsLongAsItCouldLand() = runTest {
        val w = world(this)
        clocks(w)
        w.env.wallet.start()
        advance(1_000)
        w.env.wallet.spent(WalletWorld.USER, "HIVE", 3.0, keySigned = false)
        // not in a block yet, and never will be
        w.env.wallet.refresh()
        advance(100)
        assertEquals(9.345, w.env.wallet.available("HIVE")!!, 0.0001, "its expiration is the signer's: up to an hour")
        advance(50 * 60_000L)
        assertEquals(9.345, w.env.wallet.available("HIVE")!!, 0.0001)
        advance(15 * 60_000L)
        assertEquals(12.345, w.env.wallet.available("HIVE")!!, 0.0001, "past an hour of chain time it is decided")
    }

    @Test
    fun aSwapWhoseHiveAuthPromptClosedWhileTheWalletWaitsStaysOffForTheHour() = runTest {
        val w = world(this)
        val m = w.env.tradeModel("HIVE")
        try {
            com.ecency.core.secure.KeyVault(w.app.store).save(
                com.ecency.core.secure.VaultAccount(
                    WalletWorld.USER, com.ecency.core.secure.AuthType.HIVE_AUTH, hiveAuthKey = "3f0c2e9a-1b7d-4c55-9e2a-6d8f0b1c2a3e",
                    hiveAuthToken = "has-token", hiveAuthExpiry = TestApp.NOW + 60_000_000, accessToken = "access-${WalletWorld.USER}",
                ),
            )
            swapFormOpen(w, m)
            w.app.relay.autoApproveSign = false
            m.setAmount("3")
            m.next()
            m.confirm()
            repeat(10) { testScheduler.runCurrent() }
            assertEquals(HiveAuthStep.WAITING, assertIs<SigningPrompt.HiveAuthBroadcast>(w.env.signing.current.prompt).step)
            // closed while the wallet app still holds the request: it can approve it until it expires
            w.env.signing.cancel()
            advance(100)
            assertEquals(com.ecency.core.api.broadcast.BroadcastException.UNDECIDED_MESSAGE, (m.current.prompt as? TradePrompt.Alert)?.message, "not \"nothing was sent\": check the wallet")
            assertEquals(9.345, w.env.wallet.available("HIVE")!!, 0.0001, "the swap may still land")
            advance(50 * 60_000L)
            assertEquals(9.345, w.env.wallet.available("HIVE")!!, 0.0001)
            advance(15 * 60_000L)
            assertEquals(12.345, w.env.wallet.available("HIVE")!!, 0.0001, "past an hour of chain time it is decided")
        } finally {
            m.close()
        }
    }

    /** The app quits ([w]'s environment stops) and launches again on the same device. */
    private fun TestScope.relaunched(w: WalletWorld, m: TradeModel): WalletWorld {
        advance(100)
        m.close()
        w.env.wallet.close()
        val again = WalletWorld(this, store = w.app.store)
        clocks(again)
        again.env.wallet.start()
        advance(1_000)
        return again
    }

    @Test
    fun aSwapPendingAtARestartStillComesOffUntilItExpires() = runTest {
        val w = world(this)
        val m = w.env.tradeModel("HIVE")
        // accepted; the node read after it does not have it yet
        swapAcceptedNow(w, m, amount = "3", left = "12.345")
        val again = relaunched(w, m)
        assertEquals(9.345, again.env.wallet.available("HIVE")!!, 0.0001, "the first read after the restart lacks the swap: still taken off")
        advance(keySettle)
        assertEquals(12.345, again.env.wallet.available("HIVE")!!, 0.0001, "past its signed expiration the chain decided it")
    }

    @Test
    fun anAccountThatLeftTakesItsPendingSwapsWithIt() = runTest {
        val w = world(this)
        val m = w.env.tradeModel("HIVE")
        swapAcceptedNow(w, m, amount = "3", left = "12.345")
        w.env.accountsLeft(listOf(WalletWorld.USER), forget = true) {}
        val again = relaunched(w, m)
        assertEquals(12.345, again.env.wallet.available("HIVE")!!, 0.0001, "nothing of the account left behind")
    }

    @Test
    fun aSavedSwapOlderThanAnyTransactionCanLiveIsDropped() = runTest {
        val w = world(this)
        clocks(w)
        val now = w.app.clock()
        // an hour bound never set (no load ran after it): without the age limit the next load would start a new hour
        val old = StoredSwap(WalletWorld.USER, "HIVE", 3.0, now - 3 * 3_600_000L, null, "action-old", null)
        val fresh = StoredSwap(WalletWorld.USER, "HIVE", 1.0, now - 60_000L, null, "action-fresh", null)
        w.app.store.putString("wallet.pendingSwaps", StoredSwap.encode(listOf(old, fresh)))
        w.env.wallet.start()
        advance(1_000)
        assertEquals(11.345, w.env.wallet.available("HIVE")!!, 0.0001, "only the recent one is still pending")
    }

    @Test
    fun aSwapSavedWithTheClockAheadStillGetsItsHourAfterARestart() = runTest {
        val w = world(this)
        clocks(w)
        // signed elsewhere while the phone's clock ran 5 hours ahead, corrected since
        val ahead = StoredSwap(WalletWorld.USER, "HIVE", 1.0, w.app.clock() + 5 * 3_600_000L, null, "action-ahead", null)
        w.app.store.putString("wallet.pendingSwaps", StoredSwap.encode(listOf(ahead)))
        w.env.wallet.start()
        advance(1_000)
        assertEquals(11.345, w.env.wallet.available("HIVE")!!, 0.0001)
        advance(WalletModel.MAX_TRANSACTION_LIFETIME_MILLIS + WalletModel.NODE_LAG_MILLIS + 10_000)
        assertEquals(12.345, w.env.wallet.available("HIVE")!!, 0.0001, "an hour from the first load after the restart, not from the old clock")
    }

    @Test
    fun aHiveAuthSwapWaitingAtLogoutIsNotSavedForTheAccountThatLeft() = runTest {
        val w = world(this)
        val m = w.env.tradeModel("HIVE")
        com.ecency.core.secure.KeyVault(w.app.store).save(
            com.ecency.core.secure.VaultAccount(
                WalletWorld.USER, com.ecency.core.secure.AuthType.HIVE_AUTH, hiveAuthKey = "3f0c2e9a-1b7d-4c55-9e2a-6d8f0b1c2a3e",
                hiveAuthToken = "has-token", hiveAuthExpiry = TestApp.NOW + 60_000_000, accessToken = "access-${WalletWorld.USER}",
            ),
        )
        swapFormOpen(w, m)
        w.app.relay.autoApproveSign = false
        m.setAmount("3")
        m.next()
        m.confirm()
        repeat(10) { testScheduler.runCurrent() }
        assertEquals(HiveAuthStep.WAITING, assertIs<SigningPrompt.HiveAuthBroadcast>(w.env.signing.current.prompt).step)
        // logging out closes the prompt: the send may still land, but its account left
        launch { w.env.session.logout(WalletWorld.USER) }
        advance(1_000)
        val again = relaunched(w, m)
        assertEquals(12.345, again.env.wallet.available("HIVE")!!, 0.0001, "nothing of the account that left came back")
    }

    @Test
    fun aLoadOtherThanTheFirstStillWaitsForTheSavedSwaps() = runTest {
        val w = world(this)
        clocks(w)
        w.app.store.putString("wallet.pendingSwaps", StoredSwap.encode(listOf(StoredSwap(WalletWorld.USER, "HIVE", 1.0, w.app.clock() - 60_000L, null, "action-1", null))))
        // a refresh, not start(), is the first to read the wallet
        w.env.wallet.refresh()
        advance(1_000)
        assertEquals(11.345, w.env.wallet.available("HIVE")!!, 0.0001, "the saved swap is off the first balance read")
    }

    @Test
    fun aSavedSwapReadThatFailsIsTriedAgainNotTakenAsNone() = runTest {
        val w = world(this)
        clocks(w)
        w.app.store.putString("wallet.pendingSwaps", StoredSwap.encode(listOf(StoredSwap(WalletWorld.USER, "HIVE", 1.0, w.app.clock() - 60_000L, null, "action-1", null))))
        w.app.store.failingReads["wallet.pendingSwaps"] = com.ecency.core.secure.SecureStoreException.Unavailable(-1)
        w.env.wallet.start()
        advance(1_000)
        assertNull(w.env.wallet.available("HIVE"), "no balance while the saved swaps cannot be read")
        w.app.store.failingReads.clear()
        w.env.wallet.refresh()
        advance(1_000)
        assertEquals(11.345, w.env.wallet.available("HIVE")!!, 0.0001)
    }

    @Test
    fun anAccountThatLeftRecordsNoSwapFromALateOutcome() = runTest {
        val w = world(this)
        clocks(w)
        w.env.wallet.start()
        advance(1_000)
        w.env.wallet.forgetSwaps(listOf(WalletWorld.USER))
        // its send's outcome arrives after it left
        w.env.wallet.spent(WalletWorld.USER, "HIVE", 3.0, keySigned = false, actionId = "late")
        advance(100)
        assertEquals(12.345, w.env.wallet.available("HIVE")!!, 0.0001)
        val again = WalletWorld(this, store = w.app.store)
        clocks(again)
        again.env.wallet.start()
        advance(1_000)
        assertEquals(12.345, again.env.wallet.available("HIVE")!!, 0.0001, "nothing saved for it either")
    }

    @Test
    fun aSwapRecordedJustBeforeTheWalletClosesIsStillSaved() = runTest {
        val w = world(this)
        clocks(w)
        w.env.wallet.start()
        advance(1_000)
        w.env.wallet.spent(WalletWorld.USER, "HIVE", 3.0, keySigned = false, actionId = "just-now")
        // closed before its write ran
        w.env.wallet.close()
        advance(100)
        val again = WalletWorld(this, store = w.app.store)
        clocks(again)
        again.env.wallet.start()
        advance(1_000)
        assertEquals(9.345, again.env.wallet.available("HIVE")!!, 0.0001)
    }

    @Test
    fun aSavedSwapReadsBackAsItWasAndSkipsWhatIsNotOne() {
        val s = StoredSwap(WalletWorld.USER, "HBD", 1.5, 1_000L, 2_000L, "action-1", 912345678L)
        assertEquals(listOf(s), StoredSwap.decode(StoredSwap.encode(listOf(s))))
        val bad = """[{"account":"${WalletWorld.USER}","symbol":"BTC","amount":1,"at":1},{"account":"${WalletWorld.USER}","symbol":"HIVE","amount":"1","at":1},{"symbol":"HIVE","amount":1,"at":1},7]"""
        assertEquals(emptyList(), StoredSwap.decode(bad))
        assertEquals(emptyList(), StoredSwap.decode("not json"))
    }

    @Test
    fun aLoadSentBeforeASwapNeverStartsItsHour() = runTest {
        val w = world(this)
        clocks(w)
        w.env.wallet.start()
        advance(1_000)
        // a load goes out, its account read held; the swap is accepted meanwhile; the read lands 4 s on
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        w.gates["condenser_api.get_accounts"] = gate
        w.env.wallet.refresh()
        testScheduler.runCurrent()
        advance(1_000)
        w.env.wallet.spent(WalletWorld.USER, "HIVE", 3.0, keySigned = false)
        advance(3_000)
        w.gates.remove("condenser_api.get_accounts")
        gate.complete(Unit)
        advance(100)
        // an hour from the held load's head ends about 2 minutes before one from the next load, sent after the swap
        advance(3_780_000L - testScheduler.currentTime)
        assertEquals(9.345, w.env.wallet.available("HIVE")!!, 0.0001, "the hour runs from a read sent after the swap")
        advance(120_000)
        assertEquals(12.345, w.env.wallet.available("HIVE")!!, 0.0001)
    }

    @Test
    fun aFailedReadStillBooksTheNextLook() = runTest {
        val w = world(this)
        clocks(w)
        w.env.wallet.start()
        advance(1_000)
        w.env.wallet.spent(WalletWorld.USER, "HIVE", 3.0, keySigned = true)
        // the first load after the swap fails its account read; nothing else reloads the wallet
        val accounts = w.rpc.getValue("condenser_api.get_accounts")
        w.rpc["condenser_api.get_accounts"] = { "[]" }
        w.env.wallet.refresh()
        advance(25_000)
        assertEquals(9.345, w.env.wallet.available("HIVE")!!, 0.0001)
        w.rpc["condenser_api.get_accounts"] = accounts
        advance(keySettle + 60_000)
        assertEquals(12.345, w.env.wallet.available("HIVE")!!, 0.0001, "a later look settled it")
    }

    @Test
    fun aStuckNodeAfterASwapSettledNeverBringsItBack() = runTest {
        val w = world(this)
        val m = w.env.tradeModel("HIVE")
        try {
            swapAcceptedNow(w, m, amount = "3", left = "9.345")
            m.reset()
            advance(keySettle + 5_000)
            assertEquals(9.345, m.current.balance, 0.0001, "settled")
            // a node stuck ten minutes back answers the next load: no swap there, and nothing left to take off
            val stuck = TestApp.NOW + testScheduler.currentTime - 10 * 60_000L
            w.chainClock = { stuck }
            hiveLeft(w, "12.345")
            w.env.wallet.refresh()
            advance(100)
            assertEquals(12.345, w.env.wallet.current.item("HIVE")?.liquid, "the load landed")
            assertEquals(9.345, m.current.balance, 0.0001, "a read behind the last never moves the chain read back")
        } finally {
            m.close()
        }
    }

    @Test
    fun aReadWithoutTheChainsTimeIsSetAside() = runTest {
        val w = world(this)
        val m = w.env.tradeModel("HIVE")
        try {
            swapAcceptedNow(w, m, amount = "3", left = "9.345")
            m.reset()
            advance(keySettle + 5_000)
            assertEquals(9.345, m.current.balance, 0.0001, "settled")
            // the time read never answers; the account read comes from a node without the swap
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            w.gates["condenser_api.get_dynamic_global_properties"] = gate
            hiveLeft(w, "12.345")
            w.env.wallet.refresh()
            advance(60_000)
            assertEquals(12.345, w.env.wallet.current.item("HIVE")?.liquid, "the load landed")
            assertEquals(9.345, m.current.balance, 0.0001, "an account read with no chain time to place it is set aside")
            w.gates.remove("condenser_api.get_dynamic_global_properties")
            gate.complete(Unit)
        } finally {
            m.close()
        }
    }

    @Test
    fun aFailedPortfolioReadStillBooksTheNextLook() = runTest {
        val w = world(this)
        val m = w.env.tradeModel("HIVE")
        try {
            // never landed; the portfolio fails at every reload for a while
            swapAcceptedNow(w, m, amount = "3", left = "12.345")
            m.reset()
            val portfolio = w.http.getValue("/wallet-api/portfolio-v2")
            w.http["/wallet-api/portfolio-v2"] = { HttpResult(503, "") }
            advance(30_000)
            assertEquals(9.345, m.current.balance, 0.0001)
            w.http["/wallet-api/portfolio-v2"] = portfolio
            advance(keySettle + WalletModel.NODE_LAG_MILLIS * 2)
            assertEquals(12.345, m.current.balance, 0.0001, "a later look, booked by the failed one, settled it")
        } finally {
            m.close()
        }
    }

    @Test
    fun aSendSinceTheLastReadKeepsTheSwapOffUntilItExpires() = runTest {
        val w = world(this)
        val m = w.env.tradeModel("HIVE")
        try {
            swapFormOpen(w, m)
            // 3 HIVE sent from another screen after the last read, then a swap of 3
            val transfer = com.ecency.core.api.broadcast.WalletOps.ops(listOf(com.ecency.core.api.broadcast.WalletOps.transfer(WalletWorld.USER, "spike-bob", "3.000 HIVE", "")))
            w.env.signing.broadcast(WalletWorld.USER, transfer, com.ecency.core.hive.KeyRole.ACTIVE, w.env.idempotencyKey())
            advance(100)
            m.setAmount("3")
            m.next()
            m.confirm()
            // the swap is not in a block yet: the reload reads the transfer only
            hiveLeft(w, "9.345")
            untilAccepted(m)
            m.reset()
            advance(5_000)
            assertEquals(9.345, w.env.wallet.current.item("HIVE")?.liquid, "the reload landed")
            assertEquals(6.345, m.current.balance, 0.0001, "the transfer's 3 are not the swap's: it stays off")
        } finally {
            m.close()
        }
    }

    @Test
    fun aSendAfterTheSwapNeverPassesForIt() = runTest {
        val w = world(this)
        val m = w.env.tradeModel("HIVE")
        try {
            // the swap, not in a block yet; then 3 HIVE sent from another screen, which lands first
            swapAcceptedNow(w, m, amount = "3", left = "12.345")
            m.reset()
            val transfer = com.ecency.core.api.broadcast.WalletOps.ops(listOf(com.ecency.core.api.broadcast.WalletOps.transfer(WalletWorld.USER, "spike-bob", "3.000 HIVE", "")))
            w.env.signing.broadcast(WalletWorld.USER, transfer, com.ecency.core.hive.KeyRole.ACTIVE, w.env.idempotencyKey())
            hiveLeft(w, "9.345")
            w.env.wallet.refresh()
            advance(100)
            assertEquals(9.345, w.env.wallet.current.item("HIVE")?.liquid, "the read has the transfer")
            assertEquals(6.345, m.current.balance, 0.0001, "the transfer's 3 are not the swap's: it stays off")
        } finally {
            m.close()
        }
    }

    @Test
    fun aPhoneClockSteppedBackBeforeDoneStillWaitsForTheSignedExpiration() = runTest {
        val w = world(this)
        val m = w.env.tradeModel("HIVE")
        try {
            swapFormOpen(w, m)
            // signed now; the node holds its answer while the phone's clock is set back 5 minutes
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            w.gates["condenser_api.broadcast_transaction"] = gate
            m.setAmount("3")
            m.next()
            m.confirm()
            testScheduler.runCurrent()
            w.app.clock = { TestApp.NOW + testScheduler.currentTime - 5 * 60_000L }
            w.gates.remove("condenser_api.broadcast_transaction")
            gate.complete(Unit)
            untilAccepted(m)
            m.reset()
            // not in a block yet when the reload reads
            advance(5_000)
            assertEquals(12.345, w.env.wallet.current.item("HIVE")?.liquid, "the reload landed")
            assertEquals(9.345, m.current.balance, 0.0001, "its signed expiration, a minute on, has not passed")
            advance(keySettle + 5_000)
            assertEquals(12.345, m.current.balance, 0.0001, "past it on the chain: it never landed")
        } finally {
            m.close()
        }
    }

    @Test
    fun anHbdSwapComesOffTheHbdBalanceOnly() = runTest {
        val w = world(this)
        clocks(w)
        w.env.wallet.start()
        advance(1_000)
        w.env.wallet.spent(WalletWorld.USER, "HBD", 3.0, keySigned = true)
        assertEquals(2.0, w.env.wallet.available("HBD")!!, 0.0001, "5.000 HBD on the chain less the 3 sold")
        assertEquals(12.345, w.env.wallet.available("HIVE")!!, 0.0001)
    }

    @Test
    fun anotherAccountsSwapNeverComesOffThisOne() = runTest {
        val w = world(this)
        clocks(w)
        w.env.wallet.start()
        advance(1_000)
        w.env.wallet.spent("someone-else", "HIVE", 3.0, keySigned = true)
        assertEquals(12.345, w.env.wallet.available("HIVE")!!, 0.0001)
    }

    @Test
    fun anEarlierSwapsLateOutcomeNeverLandsOnANewForm() = runTest {
        val w = world(this)
        val m = w.env.tradeModel("HIVE")
        try {
            swapAcceptedNow(w, m)
            m.reset()
            testScheduler.advanceTimeBy(10_000)
            testScheduler.runCurrent()
            assertNull(m.current.outcome)
            assertFalse(m.current.finished)
        } finally {
            m.close()
        }
    }

    @Test
    fun newSwapNeverClearsALiveForm() = runTest {
        val w = world(this)
        val m = w.env.tradeModel("HIVE")
        try {
            w.rpc["condenser_api.get_order_book"] = { """{"bids":[{"created":"x","hbd":25000,"hive":100000,"order_price":{"base":"x","quote":"y"},"real_price":"0.25000000"}],"asks":[{"created":"x","hbd":26000,"hive":100000,"real_price":"0.26000000"}]}""" }
            w.rpc["condenser_api.get_ticker"] = { """{"latest":"0.25","lowest_ask":"0.26","highest_bid":"0.25"}""" }
            w.rpc["condenser_api.get_open_orders"] = { "[]" }
            w.env.wallet.start()
            m.start()
            testScheduler.advanceTimeBy(1_000)
            m.setAmount("2")
            m.next()
            m.confirm()
            val sending = m.current
            assertEquals(SendPhase.Sending, sending.phase)
            m.reset()
            assertEquals("2", m.current.fromAmount)
            assertEquals(sending.actionId, m.current.actionId, "a send in flight keeps its form")
            assertEquals(SendPhase.Sending, m.current.phase)
        } finally {
            m.close()
        }
    }

    // ---- redeem ----

    @Test
    fun promotingNeedsAnExistingPostAndEnoughPoints() = runTest {
        val w = world(this)
        w.http["/private-api/points"] = { HttpResult(200, """{"points":"400.000","unclaimed_points":"0"}""") }
        var exists = false
        w.rpc["bridge.get_post"] = { if (exists) """{"post_id":1,"author":"spike-author","permlink":"p","title":"t","body":"b","category":"c","json_metadata":{},"created":"2026-10-01T00:00:00","updated":"2026-10-01T00:00:00","depth":0,"children":0,"net_rshares":0,"author_reputation":25,"stats":{},"active_votes":[],"payout":0,"pending_payout_value":"0.000 HBD","author_payout_value":"0.000 HBD","curator_payout_value":"0.000 HBD","promoted":"0.000 HBD","replies":[],"beneficiaries":[],"max_accepted_payout":"1000000.000 HBD","percent_hbd":10000,"url":"/c/@spike-author/p"}""" else "null" }
        val m = w.env.redeemModel(RedeemKind.PROMOTE, "spike-author/p")
        m.start()
        testScheduler.advanceUntilIdle()
        assertEquals(1, m.current.days)
        assertEquals("${com.ecency.core.l10n.LocaleFormat.toFixed(150.0, 0)} Points", m.current.priceText) // display, in the app language
        m.setDays(7)
        assertTrue(m.current.insufficient) // 500 > 400
        assertFalse(m.current.enabled)
        m.setDays(3)
        m.next()
        assertEquals(TradePrompt.Confirm(RedeemCopy.PROMOTE_INFO), m.current.prompt)
        m.confirm()
        testScheduler.advanceUntilIdle()
        assertEquals(TradePrompt.Alert(WalletCopy.FAIL, RedeemCopy.NOT_EXISTING_POST), m.current.prompt)
        assertTrue(w.app.broadcasts().isEmpty())
        exists = true
        m.dismissPrompt(); m.next(); m.confirm()
        testScheduler.advanceUntilIdle()
        val op = w.broadcastOps().single()[1].jsonObject
        assertEquals("ecency_promote", op["id"]!!.jsonPrimitive.content)
        assertEquals("""{"user":"spike-voter","author":"spike-author","permlink":"p","duration":3}""", op["json"]!!.jsonPrimitive.content)
    }

    @Test
    fun boostPlusUsesTheServerPricesAndIsRefusedWhileABoostRuns() = runTest {
        val w = world(this)
        w.http["/private-api/points"] = { HttpResult(200, """{"points":"5000","unclaimed_points":"0"}""") }
        w.http["/private-api/boost-plus-price"] = { HttpResult(200, """[{"duration":7,"price":1500},{"duration":30,"price":4500},{"duration":"x","price":1}]""") }
        w.http["/private-api/boosted-plus-account"] = { HttpResult(200, """{"account":"spike-voter","expires":"${TestApp.NOW + 86_400_000}"}""") }
        val m = w.env.redeemModel(RedeemKind.BOOST_PLUS, "")
        m.start()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(7, 30), m.current.prices.map { it.duration })
        assertEquals(7, m.current.days)
        assertEquals("2026-10-07", m.current.boostedUntil)
        assertFalse(m.current.enabled)
        val rc = w.env.redeemModel(RedeemKind.RC_TOPUP, "")
        w.http["/private-api/rc-delegation-price"] = { HttpResult(200, """[{"duration":1,"price":100}]""") }
        rc.start()
        testScheduler.advanceUntilIdle()
        rc.next(); rc.confirm()
        testScheduler.advanceUntilIdle()
        assertEquals("""{"user":"spike-voter","duration":1}""", w.broadcastOps().single()[1].jsonObject["json"]!!.jsonPrimitive.content)
    }

    // ---- tips ----

    @Test
    fun aTipGoesToTheAuthorWithRnsMemoInEachCurrency() = runTest {
        suspend fun tip(currency: String, amount: String): kotlinx.serialization.json.JsonArray {
            val w = world(this)
            w.http["/private-api/post-tips"] = { HttpResult(200, """{"meta":{"count":2,"totals":{"HIVE":"1.000"}},"list":[]}""") }
            val m = w.env.tipModel("spike-author", "a-post")
            m.start()
            testScheduler.advanceUntilIdle()
            assertEquals(2, m.current.tipsCount)
            m.setCurrency(currency)
            m.setAmount(amount)
            m.send()
            assertTrue(m.current.confirming)
            m.confirm()
            testScheduler.advanceUntilIdle()
            return w.broadcastOps().single()
        }
        assertEquals("""["transfer",{"from":"spike-voter","to":"spike-author","amount":"1.500 HIVE","memo":"Tip for @spike-author/a-post"}]""", tip("HIVE", "1,5").toString())
        assertEquals(
            """{"sender":"spike-voter","receiver":"spike-author","amount":"10.000 POINT","memo":"Tip for @spike-author/a-post"}""",
            tip("POINTS", "10")[1].jsonObject["json"]!!.jsonPrimitive.content,
        )
        assertEquals(
            """{"contractName":"tokens","contractAction":"transfer","contractPayload":{"symbol":"LEO","to":"spike-author","quantity":"0.25","memo":"Tip for @spike-author/a-post"}}""",
            tip("LEO", "0.25")[1].jsonObject["json"]!!.jsonPrimitive.content,
        )
    }

    /** Tester report "unable to send Points gifts on comments": a comment's key tips like a post's, in each currency. */
    @Test
    fun aPointsTipOnACommentGoesOutAsAnActivePointTransfer() = runTest {
        val permlink = "re-spike-voter-a-post-20261105t101010123z"
        suspend fun tip(currency: String): kotlinx.serialization.json.JsonArray {
            val w = world(this)
            val m = w.env.tipModel("spike-author", permlink)
            m.start()
            testScheduler.advanceUntilIdle()
            m.setCurrency(currency)
            m.setAmount("1")
            m.send()
            m.confirm()
            testScheduler.advanceUntilIdle()
            assertEquals(com.ecency.core.app.SendPhase.Done::class, m.current.phase?.let { it::class })
            return w.broadcastOps().single()
        }
        val points = tip("POINTS")
        assertEquals("custom_json", points[0].jsonPrimitive.content)
        val body = points[1].jsonObject
        assertEquals("ecency_point_transfer", body["id"]!!.jsonPrimitive.content)
        assertEquals("""["spike-voter"]""", body["required_auths"].toString())
        assertEquals("[]", body["required_posting_auths"].toString())
        assertEquals(
            """{"sender":"spike-voter","receiver":"spike-author","amount":"1.000 POINT","memo":"Tip for @spike-author/$permlink"}""",
            body["json"]!!.jsonPrimitive.content,
        )
        assertEquals(
            """["transfer",{"from":"spike-voter","to":"spike-author","amount":"1.000 HIVE","memo":"Tip for @spike-author/$permlink"}]""",
            tip("HIVE").toString(),
        )
    }

    /** A tester could not tip and saw only "Failed to send tip": the toast now says why the chain refused it. */
    @Test
    fun aRefusedTipSaysWhy() = runTest {
        val w = world(this)
        w.broadcastAnswer = { FakeTransport.error(it, -32000, "Assert Exception:_db.get_balance( from_account, o.amount.symbol ) >= o.amount: Account does not have sufficient funds for transfer.") }
        val m = w.env.tipModel("spike-author", "a-post")
        m.start()
        testScheduler.advanceUntilIdle()
        m.setCurrency("HIVE")
        m.setAmount("1")
        m.send()
        m.confirm()
        testScheduler.advanceUntilIdle()
        assertIs<SendPhase.Failed>(m.current.phase)
        val toast = w.app.env.ui.state.value.toast?.text.orEmpty()
        assertTrue(toast.startsWith(TipCopy.ERROR), toast)
        assertTrue("not enough funds" in toast, toast)
    }

    @Test
    fun tipAmountsAreCheckedLikeRn() = runTest {
        val w = world(this)
        val m = w.env.tipModel("spike-author", "a-post")
        m.start()
        testScheduler.advanceUntilIdle()
        m.setAmount("1.2345") // past 3 decimals: refused
        assertEquals("", m.current.amount)
        m.setAmount("abc")
        assertEquals("", m.current.amount)
        m.setAmount("0")
        assertFalse(m.current.sendEnabled)
        assertEquals(listOf("HIVE", "HBD", "POINTS", "LEO"), m.current.currencies.map { it.symbol })
    }

    // ---- manage assets ----

    @Test
    fun savingTheSelectionWritesRnsTokenList() = runTest {
        val w = world(this)
        w.rpc["bridge.get_profile"] = { """{"name":"spike-voter","metadata":{"profile":{"name":"Spike"}},"stats":{}}""" }
        w.env.wallet.start()
        val m = w.env.manageAssetsModel()
        m.start()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("LEO", "BNB"), m.current.selected.map { it.symbol })
        assertTrue(m.current.available.none { it.layer == com.ecency.core.api.wallet.AssetLayer.HIVE || it.layer == com.ecency.core.api.wallet.AssetLayer.POINTS })
        m.toggle("BNB")
        m.confirm()
        testScheduler.advanceUntilIdle()
        val op = w.broadcastOps().single()
        assertEquals("account_update2", op[0].jsonPrimitive.content)
        val posting = op[1].jsonObject["posting_json_metadata"]!!.jsonPrimitive.content
        // CHAIN kept (hidden now) with its address, ENGINE in order, the HIVE entry never written
        assertTrue(posting.contains(""""tokens":[{"symbol":"BNB","type":"CHAIN","meta":{"show":false,"address":"0xabc"}},{"symbol":"LEO","type":"ENGINE","meta":{"show":true}}]"""), posting)
        assertFalse(posting.contains("OLD"))
        assertTrue(m.current.closed)
        assertEquals(listOf("POINTS", "HP", "HIVE", "HBD", "LEO"), w.env.wallet.current.selection)
    }

    // ---- links and navigation ----

    @Test
    fun aRecipientOnlyTransferLinkOpensThePrefilledTransferScreen() = runTest {
        val w = world(this)
        w.env.authLinks.handle("ecency://transfer?to=@spike-bob&asset=HBD")
        testScheduler.advanceUntilIdle()
        assertEquals(LinkNavigation.Screen(AppScreen.Transfer("transfer", "HBD", "hive", "spike-bob")), w.env.authLinks.current.navigation)
        assertIs<AuthLink.Transfer>(com.ecency.core.auth.AuthLinks.parse("ecency://transfer?to=spike-bob"))
    }

    @Test
    fun aRecipientOnlyTransferLinkSignedOutOpensTheLogin() = runTest {
        val w = world(this)
        w.env.session.logout(w.user)
        testScheduler.advanceUntilIdle()
        w.env.authLinks.handle("ecency://transfer?to=spike-bob")
        testScheduler.advanceUntilIdle()
        assertIs<LinkNavigation.Login>(w.env.authLinks.current.navigation)
    }

    @Test
    fun walletScreensRoundTripThroughTheSavedNavigationAndRouteNamesMapToThem() {
        val screens = listOf(
            AppScreen.AssetDetails("HP"), AppScreen.Transfer("transfer", "HIVE", "hive", "spike-bob"), AppScreen.AssetsSelect,
            AppScreen.Trade("HBD"), AppScreen.Redeem("promote", "a/b"), AppScreen.Transfer("receive", "BNB", "chain", "0xabc"),
        )
        val state = screens.fold(NavState()) { s, x -> s.push(x) }
        assertEquals(screens, NavState.decode(state.encode()).screens)
        // all of them need an account: an account change drops them
        assertTrue(state.afterAccountChange().screens.isEmpty())
        assertEquals(AppScreen.Transfer("delegate_vesting_shares", "HP", "hive", "spike-bob"), AppScreen.of(RouteName.TRANSFER, "spike-bob"))
        assertEquals(AppScreen.Redeem("promote", "spike-author/p"), AppScreen.of(RouteName.REDEEM, "spike-author/p"))
        assertNull(AppScreen.of(RouteName.TRANSFER).let { (it as AppScreen.Transfer).recipient.ifEmpty { null } })
    }
}

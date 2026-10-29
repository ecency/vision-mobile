package com.ecency.core.app

import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.api.wallet.BookOrder
import com.ecency.core.auth.AuthLink
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
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
            assertEquals(TradePrompt.Confirm(TradeCopy.swapFor("4.000 HIVE", "1.000 HBD")), m.current.prompt) // copy in the language of the run, amounts POSIX
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

package com.ecency.core.app

import com.ecency.core.api.FakeTransport
import com.ecency.core.api.broadcast.BroadcastException
import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.api.wallet.AssetLayer
import com.ecency.core.api.wallet.HistoryCategory
import com.ecency.core.api.wallet.WalletHistory
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The wallet tab, asset details, every transfer type and the restart journal, over a scripted chain. */
class WalletModelsTest {
    private val leo = """[{"symbol":"LEO","type":"ENGINE","meta":{"show":true}},{"symbol":"BNB","type":"CHAIN","meta":{"show":false,"address":"0xabc"}},{"symbol":"SPK","type":"SPK"}]"""

    private fun world(scope: TestScope, tokens: String = leo) = WalletWorld(scope, tokensJson = tokens)

    // ---- portfolio ----

    @Test
    fun theWalletListsTheDefaultsThenTheProfileTokensAndTotalsEverything() = runTest {
        val w = world(this)
        w.env.wallet.start()
        testScheduler.advanceUntilIdle()
        val s = w.env.wallet.current
        assertEquals(listOf("POINTS", "HP", "HIVE", "HBD", "LEO"), s.rows.map { it.symbol })
        // SPK is gone (RN removed it), BNB is hidden by its profile entry
        assertTrue(s.all.none { it.symbol == "SPK" })
        val points = s.rows.first()
        assertEquals("Ecency Points", points.name)
        assertEquals("1,500.500", points.balanceText)
        assertEquals("$3.00", points.fiatText)
        assertEquals("0.750 POINTS", points.pendingText)
        // RN header total: every returned wallet, BNB included: 3.001 + 237.5 + 3.08625 + 5 + 0.35 + 150
        assertEquals("$398.94", s.totalText)
        val leoRow = s.rows.last()
        assertEquals("$0.35000", leoRow.fiatText) // under 1: five decimals
        assertNull(leoRow.pendingText)
        assertEquals(WalletWorld.HIVE_PER_MVESTS, s.hivePerMVests)
        assertEquals("""{"username":"spike-voter","onlyEnabled":true,"currency":"usd"}""", w.app.authBackend.sent.first { it.url.endsWith("portfolio-v2") }.body)
    }

    @Test
    fun aFailedRefreshShowsTheBannerAndKeepsTheRows() = runTest {
        val w = world(this)
        w.env.wallet.start()
        testScheduler.advanceUntilIdle()
        w.http["/wallet-api/portfolio-v2"] = { HttpResult(502, "bad gateway") }
        w.env.wallet.refresh()
        testScheduler.advanceUntilIdle()
        assertTrue(w.env.wallet.current.showError)
        assertEquals(5, w.env.wallet.current.rows.size)
    }

    @Test
    fun claimingHpSendsOnlyTheVestingRewardWithThePostingKeyAndMasksItForThreeMinutes() = runTest {
        val w = world(this)
        w.env.wallet.start()
        testScheduler.advanceUntilIdle()
        w.env.wallet.claim("HP")
        testScheduler.advanceTimeBy(500)
        assertEquals(
            listOf("""["claim_reward_balance",{"account":"spike-voter","reward_hive":"0.000 HIVE","reward_hbd":"0.000 HBD","reward_vests":"1234.567890 VESTS"}]"""),
            w.broadcastOpsJson(),
        )
        // the server still reports the claimed value: shown as 0 until it changes or 3 minutes pass
        testScheduler.advanceTimeBy(2_500)
        testScheduler.runCurrent()
        assertNull(w.env.wallet.current.rows.first { it.symbol == "HP" }.pendingText)
        assertTrue(w.app.env.ui.current.toast?.text == WalletCopy.CLAIMED || w.app.authBackend.sent.count { it.url.endsWith("portfolio-v2") } >= 2)
        w.app.now += WalletModel.CLAIM_MASK_MILLIS + 1
        w.env.wallet.refresh()
        testScheduler.advanceUntilIdle()
        assertEquals("0.617 HP", w.env.wallet.current.rows.first { it.symbol == "HP" }.pendingText)
    }

    @Test
    fun claimingAnEngineTokenIsAPostingScotClaim() = runTest {
        val w = world(this)
        w.http["/wallet-api/portfolio-v2"] = { HttpResult(200, WalletWorld.PORTFOLIO.replace("\"pendingRewards\":0,\"liquid\":3.5", "\"pendingRewards\":1.5,\"liquid\":3.5")) }
        w.env.wallet.start()
        testScheduler.advanceUntilIdle()
        w.env.wallet.claim("LEO")
        testScheduler.advanceUntilIdle()
        val op = w.broadcastOps().single()
        assertEquals("custom_json", op[0].jsonPrimitive.content)
        val body = op[1].jsonObject
        assertEquals("scot_claim_token", body["id"]!!.jsonPrimitive.content)
        assertEquals("""[{"symbol":"LEO"}]""", body["json"]!!.jsonPrimitive.content)
        assertEquals(JsonArray(emptyList()), body["required_auths"])
    }

    @Test
    fun claimingPointsIsAPrivateApiCallNotABroadcast() = runTest {
        val w = world(this)
        w.http["/private-api/points-claim"] = { HttpResult(200, "{}") }
        w.env.wallet.start()
        testScheduler.advanceUntilIdle()
        w.env.wallet.claim("POINTS")
        testScheduler.advanceUntilIdle()
        assertTrue(w.app.broadcasts().isEmpty())
        assertTrue(w.app.authBackend.sent.any { it.url.endsWith("/private-api/points-claim") && it.body!!.contains("\"code\"") })
    }

    @Test
    fun anAccountChangeEmptiesTheWallet() = runTest {
        val w = world(this)
        w.env.wallet.start()
        testScheduler.advanceUntilIdle()
        w.env.session.logout(w.user)
        testScheduler.advanceUntilIdle()
        assertNull(w.env.wallet.current.account)
        assertTrue(w.env.wallet.current.rows.isEmpty())
    }

    // ---- history ----

    private fun historyRow(num: Long, op: String, fields: String, trx: String = "0".repeat(40)) =
        """[$num,{"trx_id":"$trx","block":1,"trx_in_block":0,"op_in_trx":0,"virtual_op":false,"timestamp":"2026-10-0${(num % 9) + 1}T10:00:00","op":["$op",$fields]}]"""

    @Test
    fun chainHistoryIsNewestFirstAcrossAscendingPagesAndAsksOnlyTheTabsOperations() = runTest {
        val w = world(this)
        val pages = ArrayDeque(
            listOf(
                // the newest page: ascending, as hived sends it
                "[" + historyRow(101, "transfer", """{"from":"spike-voter","to":"spike-bob","amount":"1.000 HIVE","memo":"a"}""", "ab".repeat(20)) + "," +
                    historyRow(102, "transfer", """{"from":"spike-bob","to":"spike-voter","amount":"2.000 HBD","memo":""}""") + "," +
                    historyRow(103, "claim_reward_balance", """{"account":"spike-voter","reward_hive":"0.500 HIVE","reward_hbd":"0.000 HBD","reward_vests":"0.000000 VESTS"}""") + "]",
                "[" + historyRow(1, "transfer_to_vesting", """{"from":"spike-voter","to":"spike-voter","amount":"5.000 HIVE"}""") + "," +
                    // the HIVE tab keeps curation rewards by the SDK's filter; their HP value fails the ticker match
                    historyRow(3, "curation_reward", """{"curator":"spike-voter","reward":"10.000000 VESTS","comment_author":"a","comment_permlink":"p"}""") + "," +
                    // a node repeating a row of the newer page: shown once
                    historyRow(101, "transfer", """{"from":"spike-voter","to":"spike-bob","amount":"1.000 HIVE","memo":"a"}""", "ab".repeat(20)) + "," +
                    historyRow(2, "transfer", """{"from":"spike-voter","to":"spike-carol","amount":"3.000 HIVE","memo":""}""") + "]",
            ),
        )
        w.rpc["condenser_api.get_account_history"] = { pages.removeFirst() }
        w.env.wallet.start()
        val details = w.env.assetDetailsModel("HIVE")
        details.start()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(103L, 101L), details.current.activities.map { it.trxIndex }) // HBD row dropped by ticker
        val first = w.app.transport.sent.first { it.method == "condenser_api.get_account_history" }.params.jsonArray
        assertEquals("spike-voter", first[0].jsonPrimitive.content)
        assertEquals(-1L, first[1].jsonPrimitive.content.toLong())
        assertEquals(100L, first[2].jsonPrimitive.content.toLong())
        assertEquals(WalletHistory.mask(WalletHistory.opsFor("HIVE")).first, first[3].jsonPrimitive.content)
        details.loadMore()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(103L, 101L, 2L, 1L), details.current.activities.map { it.trxIndex })
        val second = w.app.transport.sent.last { it.method == "condenser_api.get_account_history" }.params.jsonArray
        assertEquals(100L, second[1].jsonPrimitive.content.toLong()) // oldest index of the page minus one
        assertFalse(details.current.endReached, "index 0 is still ahead")
        val row = details.current.activities.first { it.trxIndex == 101L }
        assertEquals("@spike-voter to @spike-bob", row.details)
        assertEquals("ab".repeat(20), row.trxId)
        assertTrue(row.repeatable)
        assertEquals("0.500 HIVE ", details.current.activities.first().value.trimStart())
    }

    @Test
    fun anEmptyFilteredPageAdvancesByItselfAtMostFiveTimes() = runTest {
        val w = world(this)
        var calls = 0
        w.rpc["condenser_api.get_account_history"] = { r ->
            calls++
            val start = r.params.jsonArray[1].jsonPrimitive.content.toLong()
            val top = if (start < 0) 1000L else start
            "[" + historyRow(top - 1, "transfer", """{"from":"a","to":"b","amount":"1.000 HBD","memo":""}""") + "," + historyRow(top, "transfer", """{"from":"a","to":"b","amount":"1.000 HBD","memo":""}""") + "]"
        }
        w.env.wallet.start()
        val details = w.env.assetDetailsModel("HIVE")
        details.start()
        testScheduler.advanceUntilIdle()
        assertEquals(1 + AssetDetailsModel.MAX_AUTO_ADVANCE, calls)
        assertTrue(details.current.activities.isEmpty())
    }

    @Test
    fun historyCategoriesAreWebsGroupsCutToEachAsset() {
        assertEquals(
            listOf(HistoryCategory.ALL, HistoryCategory.TRANSFERS, HistoryCategory.MARKET, HistoryCategory.STAKE, HistoryCategory.REWARDS),
            WalletHistory.categoriesFor("HIVE"),
        )
        assertEquals(
            listOf(HistoryCategory.ALL, HistoryCategory.TRANSFERS, HistoryCategory.MARKET, HistoryCategory.INTEREST, HistoryCategory.REWARDS),
            WalletHistory.categoriesFor("HBD"),
        )
        assertEquals(listOf(HistoryCategory.ALL, HistoryCategory.STAKE, HistoryCategory.REWARDS), WalletHistory.categoriesFor("HP"))
        assertEquals(listOf("transfer_to_vesting", "fill_vesting_withdraw"), HistoryCategory.STAKE.opsFor("HIVE"))
        assertEquals(
            listOf("withdraw_vesting", "transfer_to_vesting", "fill_vesting_withdraw", "delegate_vesting_shares").toSet(),
            HistoryCategory.STAKE.opsFor("HP").toSet(),
        )
        assertEquals(listOf("claim_reward_balance", "author_reward", "comment_benefactor_reward", "proposal_pay"), HistoryCategory.REWARDS.opsFor("HBD"))
        // every category asks only for operations the asset has, and the categories cover the asset's whole set
        for (symbol in listOf("HIVE", "HBD", "HP")) {
            val all = WalletHistory.opsFor(symbol).toSet()
            val cats = WalletHistory.categoriesFor(symbol).drop(1)
            cats.forEach { assertTrue(all.containsAll(it.opsFor(symbol)), "$symbol ${it.name}") }
            assertEquals(all, cats.flatMap { it.opsFor(symbol) }.toSet(), symbol)
        }
        // a set the sheet made is no category; none is All
        assertEquals(HistoryCategory.ALL, WalletHistory.categoryOf("HBD", null))
        assertEquals(HistoryCategory.INTEREST, WalletHistory.categoryOf("HBD", listOf("interest")))
        assertNull(WalletHistory.categoryOf("HBD", listOf("interest", "transfer")))
    }

    @Test
    fun aCategorySendsItsOperationsAsTheBitmaskAndHbdShowsInterest() = runTest {
        val w = world(this)
        val asked = mutableListOf<List<String?>>()
        w.rpc["condenser_api.get_account_history"] = { r ->
            val p = r.params.jsonArray
            asked += listOf(p.getOrNull(3)?.jsonPrimitive?.contentOrNull, p.getOrNull(4)?.jsonPrimitive?.contentOrNull)
            "[" + historyRow(7, "interest", """{"owner":"spike-voter","interest":"0.125 HBD","is_saved_into_hbd_balance":true}""") + "," +
                historyRow(8, "proposal_pay", """{"receiver":"spike-voter","payer":"hive.fund","payment":"10.000 HBD","trx_id":"","op_in_trx":0}""") + "]"
        }
        listOf("get_savings_withdraw_from", "get_conversion_requests", "get_collateralized_conversion_requests", "get_open_orders")
            .forEach { m -> w.rpc["condenser_api.$m"] = { "[]" } }
        w.env.wallet.start()
        val d = w.env.assetDetailsModel("HBD")
        d.start()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("proposal_pay", "interest"), d.current.activities.map { it.textKey })
        assertEquals("0.125 HBD", d.current.activities.last().value)
        assertEquals(WalletHistory.categoriesFor("HBD"), d.current.categories)
        assertEquals(HistoryCategory.ALL, d.current.category)
        assertEquals(WalletHistory.mask(WalletHistory.opsFor("HBD")).toList(), asked.last())

        d.setCategory(HistoryCategory.INTEREST)
        testScheduler.advanceUntilIdle()
        assertEquals(HistoryCategory.INTEREST, d.current.category)
        assertEquals(listOf("interest"), d.current.filter)
        assertEquals(WalletHistory.mask(listOf("interest")).toList(), asked.last(), "the server filters by the category's bitmask")
        assertEquals("Interest", WalletCopy.label("interest"))
        d.setCategory(HistoryCategory.ALL)
        testScheduler.advanceUntilIdle()
        assertNull(d.current.filter)
        assertEquals(WalletHistory.mask(WalletHistory.opsFor("HBD")).toList(), asked.last())
    }

    @Test
    fun aPointsTypeIsAskedFromTheServerAsWebDoes() = runTest {
        val w = world(this)
        val types = mutableListOf<Int>()
        w.http["/private-api/point-list"] = { r ->
            val type = kotlinx.serialization.json.Json.parseToJsonElement(r.body!!).jsonObject["type"]!!.jsonPrimitive.content.toInt()
            types += type
            HttpResult(200, if (type == 120) """[{"id":3,"amount":"0.100","type":120,"sender":null,"receiver":null,"memo":null,"created":"2026-10-06T07:00:00"}]""" else "[]")
        }
        w.env.wallet.start()
        val points = w.env.assetDetailsModel("POINTS")
        points.start()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(0) + WalletHistory.POINT_TYPES, points.current.pointTypes)
        assertTrue(points.current.categories.isEmpty())
        points.setPointType(120)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(0, 120), types)
        assertEquals(listOf("vote_title"), points.current.activities.map { it.textKey })
        // the chips drop the rows' "Points for" (owner note 10-28); the rows keep it
        assertEquals("Vote", WalletCopy.pointTypeLabel(120))
        assertEquals("Points for vote", points.current.activities.single().let { WalletCopy.label(it.textKey) })
        assertEquals(
            listOf("All", "Heartbeats", "Login", "Usage bonus", "Post", "Comment", "Vote", "Reblog", "Delegation reward", "Referral rewards", "Community Reward", "Transfers sent", "Transfers received"),
            points.current.pointTypes.map(WalletCopy::pointTypeLabel),
        )
        assertEquals(points.current.pointTypes.size, points.current.pointTypes.map(WalletCopy::pointTypeLabel).toSet().size, "every chip its own name")
        for (type in points.current.pointTypes) assertFalse(WalletCopy.pointTypeLabel(type).startsWith("Points for"), "chip $type")
        points.setPointType(4242) // not a type: nothing asked
        points.setPointType(0)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(0, 120, 0), types)
        assertEquals("All", WalletCopy.pointTypeLabel(0))
    }

    @Test
    fun hpHistoryShowsDelegationsAndPowerUpsInHp() = runTest {
        val rows = listOf(
            com.ecency.core.api.wallet.HistoryRow.fromJson(kotlinx.serialization.json.Json.parseToJsonElement(historyRow(5, "delegate_vesting_shares", """{"delegator":"spike-voter","delegatee":"spike-bob","vesting_shares":"1000.000000 VESTS"}""")))!!,
            com.ecency.core.api.wallet.HistoryRow.fromJson(kotlinx.serialization.json.Json.parseToJsonElement(historyRow(6, "transfer_to_vesting", """{"from":"spike-voter","to":"spike-voter","amount":"5.000 HIVE"}""")))!!,
            com.ecency.core.api.wallet.HistoryRow.fromJson(kotlinx.serialization.json.Json.parseToJsonElement(historyRow(7, "curation_reward", """{"curator":"spike-voter","reward":"2000000.000000 VESTS","comment_author":"a","comment_permlink":"p"}""")))!!,
            com.ecency.core.api.wallet.HistoryRow.fromJson(kotlinx.serialization.json.Json.parseToJsonElement(historyRow(8, "transfer", """{"from":"a","to":"b","amount":"1.000 HIVE","memo":""}""")))!!,
        )
        val page = WalletHistory.order(WalletHistory.page(rows, "HP", WalletHistory.opsFor("HP"), 500.0))
        assertEquals(listOf(7L, 6L, 5L), page.map { it.trxIndex })
        assertEquals("1000.000 HP", page.first().value)
        assertEquals("@a/p", page.first().details)
    }

    @Test
    fun pendingRequestsNameTheSymbolAndComeSoonestFirstWithCancelableOrders() = runTest {
        val w = world(this)
        w.rpc["condenser_api.get_account_history"] = { "[]" }
        w.rpc["condenser_api.get_savings_withdraw_from"] = { """[{"id":1,"from":"spike-voter","to":"spike-voter","memo":"","request_id":7,"amount":"1.000 HIVE","complete":"2026-10-09T00:00:00"}]""" }
        w.rpc["condenser_api.get_conversion_requests"] = { """[{"id":2,"owner":"spike-voter","requestid":9,"amount":"2.000 HBD","conversion_date":"2026-10-08T00:00:00"}]""" }
        w.rpc["condenser_api.get_collateralized_conversion_requests"] = { "[]" }
        w.rpc["condenser_api.get_open_orders"] = { """[{"id":3,"created":"2026-10-06T00:00:00","expiration":"2026-10-07T00:00:00","seller":"spike-voter","orderid":912345678,"for_sale":1000,"sell_price":{"base":"1.000 HIVE","quote":"0.250 HBD"}}]""" }
        w.rpc["condenser_api.find_recurrent_transfers"] = { """[{"id":4,"trigger_date":"2026-10-07T00:00:00","from":"spike-voter","to":"spike-bob","amount":"1.500 HIVE","memo":"m","recurrence":24,"consecutive_failures":0,"remaining_executions":5},{"id":5,"trigger_date":"2026-10-07T00:00:00","from":"spike-voter","to":"spike-bob","amount":"9.000 HBD","memo":"","recurrence":24,"remaining_executions":2}]""" }
        w.env.wallet.start()
        val d = w.env.assetDetailsModel("HIVE")
        d.start()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("open_order", "withdraw_savings"), d.current.pending.map { it.textKey })
        assertTrue(d.current.pending.first().cancelable)
        assertEquals(SummaryRow(WalletCopy.TOTAL_RECURRENT, "1.500 HIVE", DetailsSheet.RECURRENT_TRANSFERS), d.current.summary.last())
        // cancelling the order asks first, then sends one active limit_order_cancel
        d.cancelOrder(912345678)
        assertIs<DetailsPrompt.CancelOrder>(d.current.prompt)
        d.confirmPrompt()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("""["limit_order_cancel",{"owner":"spike-voter","orderid":912345678}]"""), w.broadcastOpsJson())
        // the recurrent delete keeps the schedule's recurrence and sends 0 with 2 executions
        d.deleteRecurrent(d.current.recurrent.single())
        d.confirmPrompt()
        testScheduler.advanceUntilIdle()
        assertEquals(
            """["recurrent_transfer",{"from":"spike-voter","to":"spike-bob","amount":"0.000 HIVE","memo":"m","recurrence":24,"executions":2,"extensions":[]}]""",
            w.broadcastOpsJson().last(),
        )
        assertTrue(d.current.recurrent.isEmpty())
    }

    @Test
    fun theSummaryAndActionsFollowRn() = runTest {
        val w = world(this)
        w.rpc["condenser_api.get_account_history"] = { "[]" }
        listOf("get_savings_withdraw_from", "get_conversion_requests", "get_collateralized_conversion_requests", "get_open_orders", "find_recurrent_transfers")
            .forEach { m -> w.rpc["condenser_api.$m"] = { "[]" } }
        w.env.wallet.start()
        val hp = w.env.assetDetailsModel("HP")
        hp.start()
        testScheduler.advanceUntilIdle()
        val s = hp.current
        // no balance row for HP; staked, value, then the server's extra rows
        assertEquals(listOf("Staked", "Estimated value", "Outgoing delegations", "Unstaking (144h)"), s.summary.map { it.label })
        assertEquals(DetailsSheet.OUTGOING_DELEGATIONS, s.summary[2].opens)
        assertEquals("APR 4.595%", s.aprText)
        assertEquals(listOf("Delegate", "Unstake", "Withdraw Accounts"), s.actions.map { it.label })
        assertEquals(AppScreen.Transfer("delegate_vesting_shares", "HP", "hive"), s.actions[0].screen)
        val hive = w.env.assetDetailsModel("HIVE")
        hive.start()
        testScheduler.advanceUntilIdle()
        // recurrent_transfer is not a button (RN filters it); swap goes to the trade screen
        assertEquals(listOf("transfer", "transfer_to_savings", "transfer_to_vesting", "swap_token"), hive.current.actions.map { it.actionName })
        assertEquals(AppScreen.Trade("HIVE"), hive.current.actions[3].screen)
        val points = w.env.assetDetailsModel("POINTS")
        w.http["/private-api/point-list"] = { HttpResult(200, """[{"id":9,"amount":"-50.000","type":997,"sender":null,"receiver":null,"memo":"x","created":"2026-10-06T07:00:00"},{"id":8,"amount":"5.000","type":998,"sender":null,"receiver":"spike-bob","memo":null,"created":"2026-10-05T07:00:00"}]""") }
        points.start()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(AppScreen.Transfer("ecency_point_transfer", "POINT", "points"), AppScreen.Redeem("promote"), AppScreen.Redeem("boost_plus")), points.current.actions.map { it.screen })
        assertEquals(listOf("burned_title", "outgoing_transfer_title"), points.current.activities.map { it.textKey })
        assertEquals("to @spike-bob", points.current.activities[1].details)
        // repeating the outgoing transfer prefills the counterparty and amount, outside the saved navigation
        val again = points.repeat(points.current.activities[1])
        assertEquals(AppScreen.Transfer("ecency_point_transfer", "POINT", "points", "spike-bob"), again)
        assertEquals("5.000", w.env.transferPrefill?.amount)
        assertTrue(NavState().push(again!!).encode().none { it.contains("5.000") })
    }

    // ---- transfers ----

    private suspend fun TestScope.transfer(w: WalletWorld, screen: AppScreen.Transfer): TransferModel {
        w.env.wallet.start()
        val m = w.env.transferModel(screen)!!
        m.start()
        testScheduler.advanceUntilIdle()
        return m
    }

    @Test
    fun aMultiRecipientTransferIsOneTransactionWithOneActionAndNeverSendsTwice() = runTest {
        val w = world(this)
        val m = transfer(w, AppScreen.Transfer("transfer", "HIVE", "hive"))
        assertEquals("12.345", m.current.balance)
        m.setRecipient("spike-bob, spike-carol")
        testScheduler.advanceUntilIdle()
        assertTrue(m.current.recipientValid)
        m.setAmount("1,2345")
        assertEquals("1.234", m.current.amount)
        m.setAmount("20") // above the balance: refused
        assertEquals("1.234", m.current.amount)
        m.setMemo("hi")
        m.next()
        assertEquals(TransferPrompt.Confirm, m.current.prompt)
        m.confirm()
        m.confirm()
        testScheduler.advanceUntilIdle()
        assertEquals(1, w.app.broadcasts().size)
        assertEquals(
            listOf(
                """["transfer",{"from":"spike-voter","to":"spike-bob","amount":"1.234 HIVE","memo":"hi"}]""",
                """["transfer",{"from":"spike-voter","to":"spike-carol","amount":"1.234 HIVE","memo":"hi"}]""",
            ),
            w.broadcastOpsJson(),
        )
        assertIs<SendPhase.Done>(m.current.phase)
        assertTrue(m.current.closed)
        assertEquals(WalletCopy.SUCCESSFUL, w.env.ui.current.toast?.text)
    }

    @Test
    fun lowLiquidityCountsEveryRecipient() = runTest {
        val w = world(this)
        val m = transfer(w, AppScreen.Transfer("transfer", "HIVE", "hive"))
        m.setRecipient("spike-bob spike-carol")
        testScheduler.advanceUntilIdle()
        m.setAmount("7")
        m.next()
        assertEquals(TransferPrompt.Alert(TransferCopy.LOW_LIQUIDITY, ""), m.current.prompt)
        assertTrue(w.app.broadcasts().isEmpty())
    }

    @Test
    fun anExchangeNeedsAMemoAndABadActorIsWarnedAbout() = runTest {
        val w = world(this)
        val m = transfer(w, AppScreen.Transfer("transfer", "HIVE", "hive"))
        m.setRecipient("bittrex")
        m.setAmount("1")
        testScheduler.advanceUntilIdle()
        assertTrue(m.current.recipientValid)
        assertTrue(m.current.exchangeMemoRequired)
        assertFalse(m.current.nextEnabled)
        m.setMemo("123456")
        assertTrue(m.current.nextEnabled)
        m.setRecipient("spike-scam")
        testScheduler.advanceUntilIdle()
        assertTrue(m.current.badActor)
        assertTrue(m.current.nextEnabled) // a warning, not a block (RN)
    }

    @Test
    fun anUnknownRecipientBlocksNextAndALateLookupIsIgnored() = runTest {
        val w = world(this)
        val m = transfer(w, AppScreen.Transfer("transfer", "HIVE", "hive"))
        m.setRecipient("spike-bo")
        m.setAmount("1")
        testScheduler.advanceUntilIdle()
        assertFalse(m.current.recipientValid)
        assertEquals(listOf("spike-bob", "spike-bobby"), m.current.suggestions)
        m.pickSuggestion("spike-voter")
        assertIs<TransferPrompt.Alert>(m.current.prompt) // yourself: refused
        m.dismissPrompt()
        m.pickSuggestion("spike-bob")
        testScheduler.advanceUntilIdle()
        assertTrue(m.current.nextEnabled)
    }

    @Test
    fun everyHiveTransferTypeBuildsTheSdkOperation() = runTest {
        suspend fun sent(screen: AppScreen.Transfer, setup: TransferModel.() -> Unit): String {
            val w = world(this)
            val m = transfer(w, screen)
            m.setup()
            testScheduler.advanceUntilIdle()
            m.next()
            m.confirm()
            testScheduler.advanceUntilIdle()
            return w.broadcastOpsJson().single()
        }
        assertEquals(
            """["transfer_to_savings",{"from":"spike-voter","to":"spike-voter","amount":"2.000 HBD","memo":""}]""",
            sent(AppScreen.Transfer("transfer_to_savings", "HBD", "hive")) { setAmount("2") },
        )
        val fromSavings = sent(AppScreen.Transfer("transfer_from_savings", "HBD", "hive")) { setAmount("20") }
        // RN request id: Date.now() >>> 0
        assertTrue(fromSavings.contains(""""request_id":${TestApp.NOW and 0xFFFFFFFFL}"""), fromSavings)
        assertEquals(
            """["transfer_to_vesting",{"from":"spike-voter","to":"spike-voter","amount":"12.345 HIVE"}]""",
            sent(AppScreen.Transfer("transfer_to_vesting", "HIVE", "hive")) { max() },
        )
        assertEquals(
            """["convert",{"owner":"spike-voter","amount":"5.000 HBD","requestid":${TestApp.NOW and 0xFFFFFFFFL}}]""",
            sent(AppScreen.Transfer("convert", "HBD", "hive")) { max() },
        )
    }

    @Test
    fun aRecurrentTransferNeedsTwoExecutionsAndPrefillsAnExistingSchedule() = runTest {
        val w = world(this)
        w.rpc["condenser_api.find_recurrent_transfers"] = { """[{"id":4,"trigger_date":"2026-10-07T00:00:00","from":"spike-voter","to":"spike-bob","amount":"1.500 HIVE","memo":"rent","recurrence":168,"remaining_executions":5}]""" }
        val m = transfer(w, AppScreen.Transfer("transfer", "HIVE", "hive"))
        m.setRecurrence(Recurrence.DAILY.hours)
        m.setRecipient("spike-bob")
        testScheduler.advanceUntilIdle()
        assertEquals("rent", m.current.memo)
        assertEquals("1.500", m.current.amount)
        assertEquals(168, m.current.recurrenceHours)
        assertEquals("5", m.current.executions)
        assertEquals("2026-10-07T00:00:00", m.current.existingScheduleDate)
        m.setExecutions("1")
        assertFalse(m.current.nextEnabled)
        m.setExecutions("12")
        m.next()
        m.confirm()
        testScheduler.advanceUntilIdle()
        assertEquals(
            """["recurrent_transfer",{"from":"spike-voter","to":"spike-bob","amount":"1.500 HIVE","memo":"rent","recurrence":168,"executions":12,"extensions":[]}]""",
            w.broadcastOpsJson().single(),
        )
    }

    @Test
    fun aPointsAnswerWithoutABalanceFailsInsteadOfShowingZero() = runTest {
        val w = world(this)
        // an API shape change (or an error body with a 2xx) is not an empty wallet
        w.http["/private-api/points"] = { HttpResult(200, """{"message":"maintenance"}""") }
        val p = transfer(w, AppScreen.Transfer("ecency_point_transfer", "POINT", "points"))
        assertTrue(p.current.balanceFailed, "balance ${p.current.balance}")
    }

    @Test
    fun pointsAndEngineTransfersUseTheirCustomJsonAndTheTokensPrecision() = runTest {
        val w = world(this)
        w.http["/private-api/points"] = { HttpResult(200, """{"points":"1,500.500","unclaimed_points":"0.750"}""") }
        val p = transfer(w, AppScreen.Transfer("ecency_point_transfer", "POINT", "points"))
        assertEquals("1500.500", p.current.balance)
        p.setRecipient("spike-bob")
        p.setAmount("10")
        testScheduler.advanceUntilIdle()
        p.next(); p.confirm()
        testScheduler.advanceUntilIdle()
        val pointOp = w.broadcastOps().single()[1].jsonObject
        assertEquals("ecency_point_transfer", pointOp["id"]!!.jsonPrimitive.content)
        assertEquals("""{"sender":"spike-voter","receiver":"spike-bob","amount":"10.000 POINT","memo":""}""", pointOp["json"]!!.jsonPrimitive.content)

        val w2 = world(this)
        w2.http["/private-api/engine-api"] = { req ->
            if (req.body!!.contains("\"balances\"")) HttpResult(200, """{"result":[{"account":"spike-voter","symbol":"LEO","balance":"3.50000000","stake":"7.250"}]}""")
            else HttpResult(200, """{"result":[{"symbol":"LEO","name":"Leo","precision":3,"stakingEnabled":true,"delegationEnabled":true}]}""")
        }
        val e = transfer(w2, AppScreen.Transfer("transfer", "LEO", "engine"))
        assertEquals(TransferKind.ENGINE_TRANSFER, e.kind)
        assertEquals("3.5", e.current.balance)
        assertEquals(3, e.current.tokenPrecision)
        e.setRecipient("spike-bob")
        e.setAmount("1.23456") // capped at the token's 3 decimals
        assertEquals("1.234", e.current.amount)
        e.setAmount("1.200")
        testScheduler.advanceUntilIdle()
        e.next(); e.confirm()
        testScheduler.advanceUntilIdle()
        assertEquals(
            """{"contractName":"tokens","contractAction":"transfer","contractPayload":{"symbol":"LEO","to":"spike-bob","quantity":"1.2","memo":""}}""",
            w2.broadcastOps().single()[1].jsonObject["json"]!!.jsonPrimitive.content,
        )
        val stake = transfer(world(this).also { it.http.putAll(w2.http) }, AppScreen.Transfer("unstake", "LEO", "engine"))
        assertTrue(stake.current.recipientLocked)
        assertEquals("7.25", stake.current.balance) // unstake spends the staked balance
    }

    @Test
    fun anUndecidedTransferShowsCheckingNeverSignsAgainAndIsJournaled() = runTest {
        val w = world(this)
        // every node times out: the transaction may have landed
        w.broadcastAnswer = { kotlinx.coroutines.awaitCancellation() }
        val m = transfer(w, AppScreen.Transfer("transfer", "HIVE", "hive"))
        m.setRecipient("spike-bob")
        m.setAmount("1")
        testScheduler.advanceUntilIdle()
        m.next(); m.confirm()
        // three nodes, 15 s each, inside the failover window: then the send is uncertain
        testScheduler.advanceTimeBy(60_000)
        testScheduler.runCurrent()
        assertIs<SendPhase.Checking>(m.current.phase)
        val journal = w.env.sendJournal.current.entries.single()
        assertEquals("spike-voter", journal.account)
        assertNull(journal.outcome)
        // the journal holds nothing secret and no operation body
        val stored = w.app.store.getString(SendJournal.ENTRY)!!
        assertFalse(stored.contains("spike-bob") || stored.contains("1.000") || stored.contains("HIVE"), stored)
        // until the failover window closes the same signed transaction is re-sent; then the chain is asked
        testScheduler.advanceTimeBy(120_000)
        testScheduler.runCurrent()
        assertIs<SendPhase.Checking>(m.current.phase)
        val txIds = w.app.broadcasts().map { (it.params.jsonArray[0] as JsonObject).let { tx -> tx["signatures"].toString() } }.toSet()
        assertEquals(1, txIds.size, "one signed transaction only")
        // the chain says it is in: done, and the journal entry is gone
        w.statusAnswer = { "\"within_irreversible_block\"" }
        testScheduler.advanceTimeBy(WalletSends.RECHECK_MILLIS + 1)
        testScheduler.runCurrent()
        testScheduler.advanceUntilIdle()
        assertIs<SendPhase.Done>(m.current.phase)
        assertTrue(w.env.sendJournal.current.entries.isEmpty())
    }

    @Test
    fun theJournalResolvesWhatAnEarlierRunLeftWithTheChainsAnswer() = runTest {
        val w = world(this)
        val tx = "ab".repeat(20)
        w.app.store.putString(
            SendJournal.ENTRY,
            """[{"account":"spike-voter","actionId":"a1","txId":"$tx","expiration":"2026-10-06T11:00:00","operation":"transfer"},
               {"account":"spike-voter","actionId":"a2","txId":"${"cd".repeat(20)}","expiration":"2026-10-06T11:00:00","operation":"transfer_to_vesting"},
               {"account":"spike-voter","actionId":"a3","txId":"${"ef".repeat(20)}","expiration":"2026-10-06T11:45:00","operation":"convert"},
               {"account":"spike-voter","actionId":"a4","txId":"${"12".repeat(20)}","expiration":"2026-10-06T11:48:00","operation":"transfer"},
               {"account":"spike-voter","actionId":"bad","txId":"nothex","expiration":"2026-10-06T11:00:00","operation":"x"}]""",
        )
        w.statusAnswer = { r ->
            when (r.params.jsonObject["transaction_id"]!!.jsonPrimitive.content) {
                tx -> "\"within_irreversible_block\""
                "cd".repeat(20) -> "\"expired_irreversible\""
                // a reversible expiry can still change: never final
                "12".repeat(20) -> "\"expired_reversible\""
                else -> "\"expired_reversible\""
            }
        }
        // the chain's clock lags the device's: only the chain's decides (11:50 is not past 11:45 + 10 min)
        w.chainTime = "2026-10-06T11:50:00"
        w.env.sendJournal.start()
        testScheduler.advanceTimeBy(1)
        testScheduler.runCurrent()
        val byId = w.env.sendJournal.current.entries.associateBy { it.actionId }
        assertEquals(setOf("a1", "a2", "a3", "a4"), byId.keys) // the malformed row is dropped
        assertNull(byId["a4"]!!.outcome)
        assertEquals(JournalOutcome.WENT_THROUGH, byId["a1"]!!.outcome)
        assertEquals("Your transfer from @spike-voter went through.", byId["a1"]!!.message)
        assertEquals(JournalOutcome.NOT_SENT, byId["a2"]!!.outcome)
        // not expired 10 minutes by the chain's clock yet: still checking
        assertNull(byId["a3"]!!.outcome)
        w.chainTime = "2026-10-06T12:09:00"
        testScheduler.advanceTimeBy(WalletSends.lookupDelay(0) + 1)
        testScheduler.runCurrent()
        assertEquals(JournalOutcome.UNCONFIRMED, w.env.sendJournal.current.entries.first { it.actionId == "a3" }.outcome)
        // dismissing a notice removes it from the device
        w.env.sendJournal.dismiss(w.env.sendJournal.current.entries.first { it.actionId == "a1" })
        testScheduler.advanceUntilIdle()
        assertFalse(w.app.store.getString(SendJournal.ENTRY)!!.contains(tx))
    }

    @Test
    fun aLogoutTakesTheAccountsJournalWithIt() = runTest {
        val w = world(this)
        w.app.store.putString(SendJournal.ENTRY, """[{"account":"spike-voter","actionId":"a1","txId":"${"ab".repeat(20)}","expiration":"2026-10-06T11:00:00","operation":"transfer"}]""")
        w.env.sendJournal.start()
        testScheduler.advanceTimeBy(1)
        w.env.session.logout(w.user)
        testScheduler.advanceUntilIdle()
        assertNull(w.app.store.getString(SendJournal.ENTRY))
        assertTrue(w.env.sendJournal.current.entries.isEmpty())
    }

    @Test
    fun aRefusedTransferClosesWithRnsMessageAndANetworkFailureBeforeAnyNodeCanBeRetried() = runTest {
        val w = world(this)
        w.broadcastAnswer = { FakeTransport.error(it, -32000, "Account spike-voter does not have sufficient funds for balance adjustment") }
        val m = transfer(w, AppScreen.Transfer("transfer", "HIVE", "hive"))
        m.setRecipient("spike-bob"); m.setAmount("1")
        testScheduler.advanceUntilIdle()
        m.next(); m.confirm()
        testScheduler.advanceUntilIdle()
        assertIs<SendPhase.Failed>(m.current.phase)
        assertTrue(m.current.closed)
        assertTrue(w.env.ui.current.toast!!.text.startsWith("Transfer failed: "))
        assertTrue(w.env.sendJournal.current.entries.isEmpty())
    }

    @Test
    fun aSendThatNeverReachedANodeFailsAsRetryableWithoutCheckingOrJournal() = runTest {
        val w = world(this)
        w.broadcastAnswer = { HttpResult(503, "unavailable") }
        val m = transfer(w, AppScreen.Transfer("transfer", "HIVE", "hive"))
        m.setRecipient("spike-bob"); m.setAmount("1")
        testScheduler.advanceUntilIdle()
        m.next(); m.confirm()
        testScheduler.advanceUntilIdle()
        val failed = assertIs<SendPhase.Failed>(m.current.phase)
        assertTrue(failed.retryable)
        assertTrue(w.env.sendJournal.current.entries.isEmpty())
    }

    @Test
    fun transferKindsFollowRnsTypeNormalization() {
        assertEquals(TransferKind.TRANSFER, TransferKind.of("transfer_token", AssetLayer.HIVE, "HIVE"))
        assertEquals(TransferKind.FROM_SAVINGS, TransferKind.of("withdraw_hbd", AssetLayer.HIVE, "HBD"))
        assertEquals(TransferKind.POINTS, TransferKind.of("ecency_point_transfer", AssetLayer.POINTS, "POINT"))
        assertEquals(TransferKind.ENGINE_UNDELEGATE, TransferKind.of("undelegate", AssetLayer.ENGINE, "LEO"))
        assertNull(TransferKind.of("delegate_vesting_shares", AssetLayer.HIVE, "HP"))
        assertEquals(TransferView.DELEGATE, TransferView.of(AppScreen.Transfer("delegate_vesting_shares", "HP", "hive")))
        assertEquals(TransferView.ROUTES, TransferView.of(AppScreen.Transfer("set_withdraw_vesting_route", "HP", "hive")))
        assertEquals(TransferView.RECEIVE, TransferView.of(AppScreen.Transfer("receive", "BNB", "chain", "0xabc")))
    }

    @Test
    fun scannedRecipientsFollowRnsParser() {
        assertEquals("spike-bob", TransferRecipientParser.extract("@Spike-Bob"))
        assertEquals("spike-bob", TransferRecipientParser.extract("https://ecency.com/@spike-bob/wallet"))
        assertEquals("spike-bob", TransferRecipientParser.extract("https://x.example/send?to=spike-bob&amount=1"))
        assertEquals("", TransferRecipientParser.extract("not a name at all!"))
        val pay = com.ecency.core.auth.HiveUri.encodeOps(
            com.ecency.core.auth.HiveUri.ops("transfer" to kotlinx.serialization.json.buildJsonObject {
                put("from", kotlinx.serialization.json.JsonPrimitive("__signer")); put("to", kotlinx.serialization.json.JsonPrimitive("spike-carol"))
                put("amount", kotlinx.serialization.json.JsonPrimitive("1.000 HIVE")); put("memo", kotlinx.serialization.json.JsonPrimitive(""))
            }),
            com.ecency.core.auth.HiveUri.Params(),
        )
        assertEquals("spike-carol", TransferRecipientParser.extract(pay))
    }
}

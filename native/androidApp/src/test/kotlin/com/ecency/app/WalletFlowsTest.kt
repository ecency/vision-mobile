package com.ecency.app

import androidx.compose.ui.unit.dp

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import com.ecency.app.ui.AppHost
import com.ecency.app.ui.EcencyApp
import com.ecency.app.ui.EcencyTheme
import com.ecency.app.ui.JournalNotice
import com.ecency.app.ui.Navigator
import com.ecency.app.ui.SendPhasePanel
import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.app.AppScreen
import com.ecency.core.app.AppTab
import com.ecency.core.app.JournalEntry
import com.ecency.core.app.JournalOutcome
import com.ecency.core.app.NavState
import com.ecency.core.app.SendPhase
import com.ecency.core.secure.AuthType
import com.ecency.core.secure.KeyVault
import com.ecency.core.secure.VaultAccount
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The wallet on Android against a scripted node and Ecency fakes: tab, details, a transfer, a tip, the check-your-wallet state. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WalletFlowsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val node = TestNode()
    private val env by lazy { node.env() }
    private val copied = mutableListOf<String>()
    private val host = object : AppHost {
        override fun openExternal(url: String) {}
        override fun toast(text: String) {}
        override fun copy(text: String) { copied += text }
    }

    private val portfolio = """{"wallets":[
        {"name":"Ecency Points","symbol":"POINTS","layer":"points","balance":100,"fiatRate":0.002,"precision":3,"actions":[{"id":"ecency_point_transfer"}],"pendingRewards":0},
        {"name":"Staked Hive","symbol":"HP","layer":"hive","balance":50,"fiatRate":0.25,"precision":3,"actions":[{"id":"delegate_vesting_shares"}],"pendingRewards":0.5,"staked":50},
        {"name":"Hive","symbol":"HIVE","layer":"hive","balance":12.345,"fiatRate":0.25,"precision":3,"actions":[{"id":"transfer"},{"id":"swap_token"}],"pendingRewards":0,"liquid":12.345},
        {"name":"Hive Dollar","symbol":"HBD","layer":"hive","balance":5,"fiatRate":1,"precision":3,"actions":[{"id":"transfer"}],"pendingRewards":0}]}"""

    @Before
    fun setUp() {
        SingletonImageLoader.setUnsafe { ctx ->
            ImageLoader.Builder(ctx).components {
                add(object : Interceptor {
                    override suspend fun intercept(chain: Interceptor.Chain): ImageResult =
                        ErrorResult(null, chain.request, IllegalStateException("no network in tests"))
                })
            }.build()
        }
        val vault = KeyVault(node.store)
        // a master-password account: posting and active keys stored, no prompt for a transfer
        vault.save(VaultAccount("tester", AuthType.MASTER_PASSWORD, postingKey = node.key.toWif(), activeKey = node.key.toWif(), accessToken = "access-tester"))
        vault.setCurrentUsername("tester")
        node.existingAccounts = listOf("spike-bob", "tester")
        node.extra = { method, _ ->
            when (method) {
                "condenser_api.get_accounts" ->
                    """[{"name":"tester","balance":"12.345 HIVE","hbd_balance":"5.000 HBD","reward_vesting_balance":"1000.000000 VESTS","vesting_shares":"100000.000000 VESTS",
                       "posting":{"weight_threshold":1,"account_auths":[],"key_auths":[["${node.key.publicKey()}",1]]},
                       "active":{"weight_threshold":1,"account_auths":[],"key_auths":[["${node.key.publicKey()}",1]]},"memo_key":"${node.key.publicKey()}","posting_json_metadata":"","json_metadata":""}]"""
                "condenser_api.get_account_history" -> """[[7,{"trx_id":"${"ab".repeat(20)}","timestamp":"2026-10-05T10:00:00","op":["transfer",{"from":"spike-bob","to":"tester","amount":"2.000 HIVE","memo":"hello"}]}]]"""
                "condenser_api.get_savings_withdraw_from", "condenser_api.get_conversion_requests", "condenser_api.get_collateralized_conversion_requests",
                "condenser_api.get_open_orders", "condenser_api.find_recurrent_transfers" -> "[]"
                "condenser_api.get_dynamic_global_properties" -> """{"head_block_number":100003,"head_block_id":"000186a3${"11".repeat(16)}","time":"2026-10-06T11:59:00","total_vesting_fund_hive":"200000000.000 HIVE","total_vesting_shares":"400000000000.000000 VESTS"}"""
                else -> null
            }
        }
        node.auth.rest = { r ->
            when {
                r.url.endsWith("/wallet-api/portfolio-v2") -> HttpResult(200, portfolio)
                r.url.contains("watchmen") -> HttpResult(200, "")
                r.url.contains("/post-tips/") -> HttpResult(200, """{"meta":{"count":0,"totals":{}},"list":[]}""")
                else -> null
            }
        }
    }

    @After
    fun tearDown() = SingletonImageLoader.reset()

    private fun launch(navigator: Navigator, fontScale: Float = 1f) = compose.setContent { androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(androidx.compose.ui.platform.LocalDensity.current.density, fontScale)) { EcencyTheme(dark = false) { EcencyApp(env, host, navigator) } } }

    @Test
    @Config(qualifiers = "w360dp-h740dp")
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun theAssetsReadForTalkBackAndFitAtFontScale2() {
        launch(Navigator(NavState().selectTab(AppTab.WALLET)), fontScale = 2f)
        until { exists("asset:POINTS") }
        compose.assertAccessible("wallet")
        compose.assertNothingClipped("wallet")
    }

    @Test
    @Config(qualifiers = "w360dp-h740dp")
    fun theClaimPillSitsCentredInItsCardAtItsOwnSize() = assertClaimPillsCentred(rtl = false)

    @Test
    @Config(qualifiers = "w360dp-h740dp")
    fun theClaimPillSitsCentredInItsCardRightToLeft() = assertClaimPillsCentred(rtl = true)

    private fun assertClaimPillsCentred(rtl: Boolean) {
        if (rtl) com.ecency.core.l10n.L10n.setLanguage("ar-SA")
        try {
            launch(Navigator(NavState().selectTab(AppTab.WALLET)))
            until { exists("claim:HP") && exists("claim:POINTS") }
            val oneDp = with(compose.density) { 1.dp.toPx() }
            for (symbol in listOf("HP", "POINTS")) {
                val card = compose.onNodeWithTag("asset:$symbol", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                val pillNode = compose.onNodeWithTag("claim:$symbol", useUnmergedTree = true).fetchSemanticsNode()
                val pill = pillNode.boundsInRoot
                val label = "${if (rtl) "rtl" else "ltr"} $symbol"
                assertTrue("$label: pill centre ${pill.center.x} vs card centre ${card.center.x}", kotlin.math.abs(pill.center.x - card.center.x) <= oneDp)
                assertTrue("$label: the pill keeps its own width (${pill.width} of ${card.width})", pill.width < card.width * 0.8f)
                // the visible pill may be shorter: Compose widens its touch area to the 48 dp minimum
                val touch = pillNode.touchBoundsInRoot
                val min = with(compose.density) { 48.dp.toPx() } - oneDp
                assertTrue("$label: touch target ${touch.width} x ${touch.height} px", touch.height >= min && touch.width >= min)
            }
            if (rtl) {
                // the card really is laid out right to left: the balance sits on the left of the symbol
                val balance = compose.onNodeWithTag("assetBalance:HP", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                val card = compose.onNodeWithTag("asset:HP", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                assertTrue("rtl: balance at ${balance.center.x} should be left of ${card.center.x}", balance.center.x < card.center.x)
            }
        } finally {
            com.ecency.core.l10n.L10n.setLanguage(com.ecency.core.l10n.L10n.SOURCE)
        }
    }

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun until(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 15_000
        while (true) {
            compose.waitForIdle()
            if (condition()) return
            if (System.currentTimeMillis() > end) throw AssertionError("condition not met in 15 s")
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            Thread.sleep(20)
        }
    }

    private fun text(tag: String): String =
        compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()

    @Test
    fun anOpenOrdersCancelTargetDoesNotReachIntoItsAmount() {
        node.extra.let { base ->
            node.extra = { m, r ->
                if (m == "condenser_api.get_open_orders") {
                    """[{"orderid":77,"created":"2026-10-05T10:00:00","expiration":"2026-11-05T10:00:00","for_sale":1000,"sell_price":{"base":"1.000 HIVE","quote":"0.250 HBD"}}]"""
                } else {
                    base?.invoke(m, r)
                }
            }
        }
        launch(Navigator(NavState().push(AppScreen.AssetDetails("HIVE"))))
        until { exists("cancelOrder:77") }
        val target = compose.onNodeWithTag("cancelOrder:77", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val amount = compose.onAllNodes(androidx.compose.ui.test.SemanticsMatcher("the order's amount") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("activityAmount:") == true
        }, useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }.first { it.top < target.bottom && it.bottom > target.top }
        // Compose widens a small target's touch area to 48 dp around its centre: that area must stay clear of the amount
        val half = with(compose.density) { 24.dp.toPx() }
        val touchLeft = minOf(target.left, target.center.x - half)
        assertTrue("touch area from $touchLeft reaches into the amount ending at ${amount.right}", touchLeft >= amount.right)
    }

    @Test
    fun theWalletTabListsTheAssetsAndOpensTheirDetailsWithSortedHistory() {
        val navigator = Navigator(NavState().selectTab(AppTab.WALLET))
        launch(navigator)
        until { exists("asset:POINTS") }
        assertEquals("$20.79", text("walletTotal")) // 0.2 + 12.5 + 3.08625 + 5, two decimals
        assertEquals("100.000", text("assetBalance:POINTS"))
        assertTrue(exists("claim:POINTS")) // RN: POINTS always offers its button (Get Points with nothing pending)
        compose.scrollListTo("walletList", hasTestTag("asset:HIVE"))
        compose.onNodeWithTag("asset:HIVE").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        until { exists("activity:7_2026-10-05T10:00:00") }
        assertTrue(navigator.state.top == AppScreen.AssetDetails("HIVE"))
        assertTrue(exists("action:transfer"))
        // the repeat button opens the transfer screen for the other party
        compose.onNodeWithTag("repeat:7_2026-10-05T10:00:00", useUnmergedTree = true).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        until { exists("transferForm") }
        assertEquals(AppScreen.Transfer("transfer", "HIVE", "hive", "spike-bob"), navigator.state.top)
    }

    @Test
    fun aHistoryCategoryAsksTheChainForItsOperationsOnly() {
        val masks = java.util.Collections.synchronizedList(mutableListOf<String?>())
        node.extra.let { base ->
            node.extra = { m, r ->
                if (m == "condenser_api.get_account_history") masks += r.toString()
                base?.invoke(m, r)
            }
        }
        launch(Navigator(NavState().push(AppScreen.AssetDetails("HBD"))))
        until { exists("tab:history:INTEREST") }
        // web's groups that HBD has, All selected
        assertTrue(listOf("ALL", "TRANSFERS", "MARKET", "INTEREST", "REWARDS").all { exists("tab:history:$it") })
        assertFalse(exists("tab:history:STAKE"))
        assertEquals(true, compose.onNodeWithTag("tab:history:ALL", useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected))
        val before = masks.size
        compose.onNodeWithTag("tab:history:INTEREST", useUnmergedTree = true).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        until { masks.size > before }
        val interest = com.ecency.core.api.wallet.WalletHistory.mask(listOf("interest")).first!!
        assertTrue(masks.last(), masks.last()!!.contains(interest))
        until { compose.onNodeWithTag("tab:history:INTEREST", useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected) == true }
    }

    @Test
    fun aTransferIsConfirmedOnceAndBroadcastAsTheSdksOperation() {
        val navigator = Navigator(NavState().push(AppScreen.Transfer("transfer", "HIVE", "hive")))
        launch(navigator)
        until { exists("transferForm") && text("transferBalance").contains("12.345") }
        compose.onNodeWithTag("transferTo").performTextInput("spike-bob")
        compose.onNodeWithTag("transferAmount").performTextInput("1.5")
        compose.onNodeWithTag("transferMemo").performTextInput("thanks")
        until { compose.onAllNodes(hasTestTag("transferNext")).fetchSemanticsNodes().firstOrNull()?.config?.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) == false }
        compose.onNodeWithTag("transferNext").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        try { until { exists("transferConfirm") } } catch (e: AssertionError) {
            throw AssertionError("state=${navigator.top.peek<com.ecency.core.app.TransferModel>("transfer")?.current}")
        }
        compose.onNodeWithTag("transferConfirm").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        try {
            until { node.broadcasts().isNotEmpty() && navigator.state.top == null }
        } catch (e: AssertionError) {
            throw AssertionError("sends=${env.walletSends.current} sent=${node.sent.map { it.substringAfter("method\":\"").substringBefore('"') }} top=${navigator.state.top}")
        }
        val sent = node.broadcasts().single()
        assertTrue(sent, sent.contains(""""operations":[["transfer",{"from":"tester","to":"spike-bob","amount":"1.500 HIVE","memo":"thanks"}]]"""))
    }

    @Test
    fun anUndecidedSendShowsTheCheckYourWalletStateWithASpinnerAndNoRetryButton() {
        compose.setContent { EcencyTheme(dark = false) { SendPhasePanel(SendPhase.Checking(), onClose = {}) } }
        assertTrue(exists("checkWallet"))
        assertTrue(exists("checkSpinner"))
        assertTrue(compose.onAllNodes(hasText("This may already have gone through", substring = true)).fetchSemanticsNodes().isNotEmpty())
        assertFalse(compose.onAllNodes(hasText("Try again", substring = true)).fetchSemanticsNodes().isNotEmpty())
        assertFalse(compose.onAllNodes(hasText("Retry", substring = true)).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun theJournalNoticeSpinsWhileCheckingAndCanBeDismissedOnceDecided() {
        var dismissed = 0
        val checking = JournalEntry("tester", "a1", "ab".repeat(20), "2026-10-06T11:00:00", "transfer")
        compose.setContent {
            EcencyTheme(dark = false) {
                androidx.compose.foundation.layout.Column {
                    JournalNotice(checking, onDismiss = { dismissed++ })
                    JournalNotice(checking.copy(actionId = "a2", outcome = JournalOutcome.WENT_THROUGH), onDismiss = { dismissed++ })
                }
            }
        }
        assertTrue(exists("journalSpinner"))
        assertTrue(compose.onAllNodes(hasText("Your transfer from @tester went through.")).fetchSemanticsNodes().isNotEmpty())
        assertEquals(1, compose.onAllNodes(hasTestTag("journalDismiss"), useUnmergedTree = true).fetchSemanticsNodes().size)
        compose.onNodeWithTag("journalDismiss").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        assertEquals(1, dismissed)
    }

    @Test
    fun theTipSheetSendsATipToThePostsAuthor() {
        val navigator = Navigator(NavState().push(AppScreen.Post("alice", "trending-one")))
        launch(navigator)
        until { exists("tip") }
        compose.onAllNodes(hasTestTag("tip"), useUnmergedTree = true).onFirst().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        until { exists("tipSheet") }
        compose.onNodeWithTag("tipAmount").performTextInput("1")
        compose.onNodeWithTag("tipSend").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        until { exists("tipConfirm") }
        compose.onNodeWithTag("tipConfirm").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        until { node.broadcasts().isNotEmpty() }
        val sent = node.broadcasts().single()
        // the first gift button of the screen: the post's own, or its comment's (RN tips a comment's author too)
        assertTrue(sent, sent.contains(""""to":"alice","amount":"1.000 HIVE","memo":"Tip for @alice/trending-one"""") ||
            sent.contains(""""to":"carol","amount":"1.000 HIVE","memo":"Tip for @carol/re-one""""))
    }

    @Test
    fun theDelegationReviewSummarisesBeforeAnythingIsSent() {
        node.extra.let { base ->
            node.extra = { m, r -> if (m == "condenser_api.get_vesting_delegations") "[]" else base?.invoke(m, r) }
        }
        launch(Navigator(NavState().push(AppScreen.Transfer("delegate_vesting_shares", "HP", "hive", "spike-bob"))))
        until { exists("delegateForm") }
        compose.onNodeWithTag("delegateHp").performTextInput("10")
        until { compose.onAllNodes(hasTestTag("delegateReview")).fetchSemanticsNodes().firstOrNull()?.config?.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) == false }
        compose.onNodeWithTag("delegateReview").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        until { exists("stakeConfirm") }
        assertTrue(compose.onAllNodes(hasText("Delegate 10.000 HP (20000.000000 VESTS) To @spike-bob")).fetchSemanticsNodes().isNotEmpty())
        assertTrue(node.broadcasts().isEmpty())
    }

    /** RN balanceAnalyticsSheet from the HIVE details: the history chart and the summary per granularity. */
    @Test
    fun theChartButtonOpensBalanceHistoryAndTheSummary() {
        val base = node.auth.rest
        val gets = java.util.Collections.synchronizedList(mutableListOf<String>())
        node.auth.rest = { r ->
            when {
                "/balance-api/accounts/tester/balance-history" in r.url -> {
                    gets += r.url
                    HttpResult(200, """{"total_pages":1,"operations_result":[{"balance":"12345","timestamp":"2026-10-05T10:00:00"},{"balance":"2000","timestamp":"2026-09-01T10:00:00"}]}""")
                }
                "/balance-api/accounts/tester/aggregated-history" in r.url -> {
                    gets += r.url
                    HttpResult(200, """[{"date":"2026-10-05","balance":{"balance":"12345"},"min_balance":{"balance":"2000"},"max_balance":{"balance":"12345"}},{"date":"2026-01-01","balance":{"balance":"2000"},"min_balance":{"balance":"0"},"max_balance":{"balance":"0"}}]""")
                }
                else -> base?.invoke(r)
            }
        }
        launch(Navigator(NavState().push(AppScreen.AssetDetails("HIVE"))))
        until { exists("openAnalytics") }
        compose.onNodeWithTag("openAnalytics").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        until { exists("balanceChart") }
        assertTrue(exists("chartYAxis"))
        assertTrue(gets.any { it.endsWith("balance-history?coin-type=HIVE&page-size=200&direction=desc") })
        compose.onNodeWithTag("analyticsSummaryTab").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        until { exists("summaryCurrent") }
        assertEquals("12.345 HIVE", text("summaryCurrent"))
        assertEquals("+10.345 HIVE", text("summaryChange"))
        compose.onNodeWithTag("granularity:daily").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        until { gets.any { it.endsWith("granularity=daily") } }
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.onFirst() = this[0]
}

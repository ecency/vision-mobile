package com.ecency.core.app

import com.ecency.core.api.FakeTransport
import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.auth.AuthHttpRequest
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** RN balanceAnalyticsSheet: the balance-api reads, paging back in time, units and the summary cards. */
class BalanceAnalyticsTest {
    private inner class World(val scope: TestScope) {
        val gets = mutableListOf<String>()
        var historyDown = false
        var yearlyGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        val app = TestApp(scope) { s ->
            if (s.method == "condenser_api.get_dynamic_global_properties") {
                FakeTransport.result(s, """{"total_vesting_fund_hive":"500.000 HIVE","total_vesting_shares":"1000000.000000 VESTS"}""")
            } else TestApp.chain(s) ?: HttpResult(500, "")
        }

        init {
            app.authBackend.gate = { r -> if ("granularity=yearly" in r.url) yearlyGate?.await() }
            app.authBackend.rest = { r: AuthHttpRequest ->
                if ("/balance-api/" in r.url) {
                    gets += r.url
                    when {
                        "balance-history" in r.url && historyDown -> HttpResult(503, "")
                        "balance-history" in r.url && "page=1" in r.url -> HttpResult(200, page(listOf(1000 to "2025-02-18T10:00:00")))
                        "balance-history" in r.url -> HttpResult(
                            200,
                            """{"total_operations":3,"total_pages":2,"operations_result":[""" +
                                """{"balance":"3500","timestamp":"2026-10-21T06:38:09"},{"balance":"2000","timestamp":"2026-03-01T00:00:00"}]}""",
                        )
                        "granularity=monthly" in r.url -> HttpResult(200, """[{"date":"2026-10-01T00:00:00","balance":{"balance":"1000"},"min_balance":{"balance":"900"},"max_balance":{"balance":"1100"}}]""")
                        else -> HttpResult(
                            200,
                            """[{"date":"2026-10-21","balance":{"balance":"1234567"},"min_balance":{"balance":"1000"},"max_balance":{"balance":"2000000"}},""" +
                                """{"date":"2026-01-01","balance":{"balance":"2234567"},"min_balance":{"balance":"0"},"max_balance":{"balance":"0"}}]""",
                        )
                    }
                } else null
            }
        }

        private fun page(rows: List<Pair<Int, String>>) =
            """{"total_pages":2,"operations_result":[${rows.joinToString(",") { """{"balance":"${it.first}","timestamp":"${it.second}"}""" }}]}"""

        fun idle() = scope.testScheduler.advanceUntilIdle()

        fun model(coin: String = "HBD"): BalanceAnalyticsModel = app.env.balanceAnalyticsModel("alice", coin).also { it.start(); idle() }
    }

    @Test
    fun theHistoryIsReadNewestPageFirstAndDrawnOldestFirst() = runTest {
        val w = World(this)
        val m = w.model()
        val first = w.gets.first { "balance-history" in it }
        assertTrue(first.startsWith("https://api.hive.blog/balance-api/accounts/alice/balance-history?coin-type=HBD&page-size=200&direction=desc"))
        assertFalse("page=" in first, "the first request names no page")
        assertEquals(listOf(2.0, 3.5), m.current.points.map { it.value })
        assertEquals("Mar 01 '26", m.current.points.first().label)
        assertTrue(m.current.hasOlder)
        m.loadOlder()
        w.idle()
        assertTrue(w.gets.last().endsWith("&page=1"))
        assertEquals(listOf(1.0, 2.0, 3.5), m.current.points.map { it.value })
        assertFalse(m.current.hasOlder, "page 1 is the start of the history")
        m.loadOlder()
        w.idle()
        assertEquals(2, w.gets.count { "balance-history" in it })
    }

    @Test
    fun hpIsConvertedFromRawVests() = runTest {
        val w = World(this)
        val m = w.model("VESTS")
        // 3500 raw VESTS is 0.0035 VESTS; 500 HIVE per 1M VESTS shares: hivePerMVests = 500
        assertEquals(0.0035 * 500 / 1e6, m.current.points.last().value, 1e-15)
        assertTrue(m.current.summary!!.current.endsWith(" HP"))
    }

    @Test
    fun theSummaryShowsCurrentChangeMinAndMaxPerGranularity() = runTest {
        val w = World(this)
        val m = w.model("HIVE")
        val s = m.current.summary!!
        assertEquals("1,234.567 HIVE", s.current)
        assertEquals("-1,000 HIVE", s.change)
        assertEquals(false, s.positive)
        assertEquals("1 HIVE", s.min)
        assertEquals("2,000 HIVE", s.max)
        assertEquals("Yearly Summary", m.current.summaryTitle)
        m.selectGranularity("monthly")
        w.idle()
        assertEquals("Monthly Summary", m.current.summaryTitle)
        assertEquals("Month Change", m.current.changeLabel)
        assertNull(m.current.summary!!.change, "no period before: no change, not the whole balance")
        assertTrue(w.gets.any { "aggregated-history?coin-type=HIVE&granularity=monthly" in it })
    }

    @Test
    fun aFailedHistoryReadSaysSoAndAnEmptyOneSaysNoActivity() = runTest {
        val w = World(this)
        w.historyDown = true
        val m = w.model()
        assertEquals(UiCopy.SOMETHING_WRONG, m.current.historyError)
        assertFalse(m.current.historyEmpty)
    }

    @Test
    fun labelsAndCompactNumbers() {
        assertEquals("Feb 18 '25", BalanceAnalyticsModel.dateLabel("2025-02-18T23:59:59"))
        assertEquals("Dec 31 '99", BalanceAnalyticsModel.dateLabel("1999-12-31T00:00:00"))
        // CLDR's en suffix is "K" (RN's chart wrote "k")
        assertEquals("92.34K", BalanceAnalyticsModel.compact(92_345.0), "JS toFixed of the binary value")
        assertEquals("1.03M", BalanceAnalyticsModel.compact(1_030_000.0))
        assertEquals("2.00B", BalanceAnalyticsModel.compact(2e9))
        assertEquals("-5.50", BalanceAnalyticsModel.compact(-5.5))
        assertEquals("1,234.5", BalanceAnalyticsModel.formatAmount(1234.5))
    }

    @Test
    fun labelsAndNumbersFollowTheAppLanguage() {
        try {
            com.ecency.core.l10n.L10n.setLanguage("de-DE")
            assertEquals("18. Feb. '25", BalanceAnalyticsModel.dateLabel("2025-02-18T23:59:59"))
            assertEquals("92.345", BalanceAnalyticsModel.compact(92_345.0), "de has no thousands suffix")
            assertEquals("1,03\u00a0Mio.", BalanceAnalyticsModel.compact(1_030_000.0))
            assertEquals("-5,50", BalanceAnalyticsModel.compact(-5.5))
            assertEquals("1.234,5", BalanceAnalyticsModel.formatAmount(1234.5))
            com.ecency.core.l10n.L10n.setLanguage("ar-SA")
            assertEquals("١٨ فبراير '٢٥", BalanceAnalyticsModel.dateLabel("2025-02-18T23:59:59"))
        } finally {
            com.ecency.core.l10n.L10n.setLanguage(com.ecency.core.l10n.L10n.SOURCE)
        }
    }

    @Test
    fun accountNamesThatWouldLeaveThePathAreRefused() {
        assertFalse(com.ecency.core.api.wallet.BalanceHistoryPage.validAccount("../x"))
        assertFalse(com.ecency.core.api.wallet.BalanceHistoryPage.validAccount("a?b=c"))
        assertTrue(com.ecency.core.api.wallet.BalanceHistoryPage.validAccount("good.name-1"))
    }

    @Test
    fun aSlowYearlyAnswerNeverOverwritesTheMonthlySummaryPicked() = runTest {
        val w = World(this)
        w.yearlyGate = kotlinx.coroutines.CompletableDeferred()
        val m = w.app.env.balanceAnalyticsModel("alice", "HIVE")
        m.start()
        repeat(50) { testScheduler.runCurrent() }
        m.selectGranularity("monthly")
        repeat(50) { testScheduler.runCurrent() }
        w.yearlyGate!!.complete(Unit)
        w.idle()
        assertEquals("monthly", m.current.granularity)
        assertEquals("1 HIVE", m.current.summary!!.current, "the monthly row, not the yearly one")
    }
}

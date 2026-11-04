package com.ecency.core.app

import com.ecency.core.api.wallet.AggregatedBalance
import com.ecency.core.api.wallet.BalanceHistoryEntry
import com.ecency.core.hive.ChainTime
import com.ecency.core.l10n.LocaleFormat
import com.ecency.core.l10n.LocaleFormatter
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** One chart point: the balance in coin units (HP for VESTS) and the sparse x-axis label ("" for none). */
data class ChartPoint(val value: Double, val label: String)

/** RN summary cards: Current, change against the period before, lowest and highest of the period. */
data class BalanceSummary(val current: String, val change: String?, val positive: Boolean?, val min: String, val max: String)

enum class AnalyticsTab { HISTORY, SUMMARY }

data class BalanceAnalyticsState(
    val username: String,
    /** HIVE, HBD or VESTS (RN maps HP to VESTS). */
    val coinType: String,
    val tab: AnalyticsTab = AnalyticsTab.HISTORY,
    val granularity: String = "yearly",
    /** Oldest first, as the chart draws them left to right. */
    val points: List<ChartPoint> = emptyList(),
    val historyLoading: Boolean = true,
    val historyError: String? = null,
    val loadingOlder: Boolean = false,
    val hasOlder: Boolean = false,
    val summary: BalanceSummary? = null,
    val summaryLoading: Boolean = true,
    val summaryError: String? = null,
) {
    val summaryTitle: String get() = UiCopy.summaryTitle(granularity)
    val changeLabel: String get() = UiCopy.changeLabel(granularity)
    val minLabel: String get() = UiCopy.minLabel(granularity)
    val maxLabel: String get() = UiCopy.maxLabel(granularity)
    val historyEmpty: Boolean get() = !historyLoading && historyError == null && points.isEmpty()
    val summaryEmpty: Boolean get() = !summaryLoading && summaryError == null && summary == null
}

/**
 * RN balanceAnalyticsSheet (asset details of HIVE, HBD and HP): "Balance History", the balance after every
 * operation as a line chart (200 a page from the balance-api, newest page first, older pages as the chart is
 * panned to its left edge), and the yearly / monthly / daily summary of the aggregated history. Read only.
 */
class BalanceAnalyticsModel internal constructor(private val env: AppEnvironment, username: String, coinType: String) :
    StateModel<BalanceAnalyticsState>(BalanceAnalyticsState(username, coinType), env.dispatchers) {
    private var entries: List<BalanceHistoryEntry> = emptyList()
    private var olderPage: Int? = null
    private var hivePerMVests: Double? = null
    private var summaryJob: Job? = null
    private var aggregated: Map<String, List<AggregatedBalance>> = emptyMap()
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            if (current.coinType == "VESTS") {
                hivePerMVests = try {
                    env.walletApi.hivePerMVests().takeIf { it > 0 }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            }
            summaryJob = scope.launch { loadSummary() }
            loadHistory(null)
        }
    }

    fun selectTab(tab: AnalyticsTab) = setState { it.copy(tab = tab) }

    fun selectGranularity(granularity: String) {
        if (granularity !in GRANULARITIES || granularity == current.granularity) return
        setState { it.copy(granularity = granularity) }
        summaryJob?.cancel()
        summaryJob = scope.launch { loadSummary() }
    }

    /** RN: the chart was panned within 200 px of its left edge: the next older page. */
    fun loadOlder() {
        val page = olderPage ?: return
        if (current.loadingOlder || current.historyLoading) return
        setState { it.copy(loadingOlder = true) }
        scope.launch { loadHistory(page) }
    }

    private suspend fun loadHistory(page: Int?) {
        val s = current
        try {
            val result = env.walletApi.balanceHistory(s.username, s.coinType, PAGE_SIZE, page)
            entries = entries + result.entries
            olderPage = result.olderPage
            setState { it.copy(points = points(entries), historyLoading = false, loadingOlder = false, hasOlder = olderPage != null, historyError = null) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            setState { if (page == null) it.copy(historyLoading = false, historyError = UiCopy.SOMETHING_WRONG) else it.copy(loadingOlder = false) }
        }
    }

    private suspend fun loadSummary() {
        val s = current
        val g = s.granularity
        setState { it.copy(summaryLoading = true, summaryError = null) }
        val rows = aggregated[g] ?: try {
            env.walletApi.aggregatedBalance(s.username, s.coinType, g).also { aggregated = aggregated + (g to it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (current.granularity == g) setState { it.copy(summaryLoading = false, summaryError = UiCopy.SOMETHING_WRONG, summary = null) }
            return
        }
        // a slow answer for a granularity the reader already left never overwrites the one on screen
        if (current.granularity != g) return
        setState { it.copy(summaryLoading = false, summary = summary(rows)) }
    }

    private fun human(raw: Double): Double? = when (current.coinType) {
        // RN vestsToHp(raw / 1e6, hivePerMVests); nothing without the conversion
        "VESTS" -> hivePerMVests?.let { raw / 1e6 * it / 1e6 }
        else -> raw / 1000
    }

    private fun points(desc: List<BalanceHistoryEntry>): List<ChartPoint> {
        if (current.coinType == "VESTS" && hivePerMVests == null) return emptyList()
        val ordered = desc.asReversed()
        // RN: about 6 evenly spaced dates ("Feb 18 '25")
        val step = maxOf(1, ordered.size / 6)
        return ordered.mapIndexed { i, e -> ChartPoint(human(e.raw) ?: 0.0, if (i % step == 0) dateLabel(e.timestamp) else "") }
    }

    private fun summary(rows: List<AggregatedBalance>): BalanceSummary? {
        val latest = rows.firstOrNull() ?: return null
        if (current.coinType == "VESTS" && hivePerMVests == null) return null
        val suffix = when (current.coinType) { "VESTS" -> " HP"; "HBD" -> " HBD"; else -> " HIVE" }
        fun fmt(raw: Double) = formatAmount(human(raw) ?: 0.0) + suffix
        val prev = rows.getOrNull(1)
        val change = prev?.let { latest.raw - it.raw }
        return BalanceSummary(
            current = fmt(latest.raw),
            change = change?.let { (if (it >= 0) "+" else "-") + fmt(kotlin.math.abs(it)) },
            positive = change?.let { it >= 0 },
            min = fmt(latest.minRaw),
            max = fmt(latest.maxRaw),
        )
    }

    companion object {
        const val PAGE_SIZE = 200
        val GRANULARITIES = listOf("yearly", "monthly", "daily")
        /** `intl.formatNumber(v, {maximumFractionDigits: 3})` in the app language: grouped, no trailing zeros. */
        fun formatAmount(value: Double): String = LocaleFormat.decimal(value, 3, 0)

        /**
         * RN's x label: `intl.formatDate(d, {month: 'short', day: '2-digit'})` in the app language plus " 'yy"
         * ("Feb 18 '25", "18. Feb. '25"), from a UTC chain timestamp (the day as the chain dates it).
         */
        fun dateLabel(timestamp: String): String {
            val seconds = runCatching { ChainTime.toEpochSeconds(timestamp.take(19)) }.getOrNull() ?: return ""
            val f = LocaleFormat.formatter
            val year = LocaleFormatter.civil(seconds.floorDiv(86_400L)).first
            return f.format(LocaleFormatter.Pattern.MONTH_DAY, seconds * 1000, 0) + " '" + f.posix((year % 100).toString().padStart(2, '0'), false)
        }

        /**
         * The y-axis labels: RN's compact format with two decimals at RN's magnitudes ("92.35K", "1.03M", "2.00B"),
         * in the app language (CLDR's short compact suffixes: "1,03 Mio."; a language without a thousands suffix
         * writes the whole number, de "92.345").
         */
        fun compact(value: Double): String {
            val a = kotlin.math.abs(value)
            val power = when {
                a >= 1e9 -> 9
                a >= 1e6 -> 6
                a >= 1e3 -> 3
                else -> return LocaleFormat.fixed(value, 2)
            }
            return LocaleFormat.compact(value, 2, power, 2)
        }
    }
}

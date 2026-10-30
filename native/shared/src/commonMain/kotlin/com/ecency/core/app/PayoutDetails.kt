package com.ecency.core.app

import com.ecency.core.api.EcencyException
import com.ecency.core.api.guarded
import com.ecency.core.api.model.Post
import com.ecency.core.api.model.Post.Companion.assetAmount
import com.ecency.core.api.model.obj
import com.ecency.core.api.model.str
import com.ecency.core.hive.ChainTime
import com.ecency.core.l10n.L
import com.ecency.core.l10n.LocaleFormat
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs

/** RN en-US `payout.*`. */
object PayoutCopy {
    val PROMOTED: String get() = L.payout.promoted
    val POTENTIAL: String get() = L.payout.potentialPayout
    val AUTHOR: String get() = L.payout.authorPayout
    val CURATION: String get() = L.payout.curationPayout
    val MAX_ACCEPTED: String get() = L.payout.maxAccepted
    val BREAKDOWN: String get() = L.payout.breakdown
    val BENEFICIARIES: String get() = L.payout.beneficiaries
    val PAYOUT_DATE: String get() = L.payout.payoutDate
    val WARN_ZERO: String get() = L.payout.warnZeroPayout
    val DETAILS: String get() = L.post.payoutDetails // post.payout_details
}

/** One row of the payout popover: label and value (the $0.02 warning has an empty value). */
data class PayoutRow(val label: String, val value: String)

/** The chain values the breakdown needs (RN selectGlobalProps): the median feed price and hbd_print_rate. */
data class PayoutProps(val base: Double, val quote: Double, val hbdPrintRate: Double)

/**
 * RN upvotePopover/children/payoutDetailsContent.tsx: the rows in RN's order, each only when its condition holds.
 * Money values as RN's `FormattedCurrency isApproximate` ("~$ 1.234"; dollars, as the card's payout).
 */
object PayoutDetails {
    private const val SBD_PRINT_RATE_MAX = 10_000.0
    private const val MINIMUM_FOR_PAYOUT = 0.02

    fun approx(value: Double) = "~$ " + LocaleFormat.toFixed(value, 3)

    fun rows(post: Post, props: PayoutProps?, nowMillis: Long): List<PayoutRow> {
        val author = assetAmount(post.authorPayoutValue)
        val curation = assetAmount(post.curatorPayoutValue)
        val promoted = assetAmount(post.promoted)
        val pending = assetAmount(post.pendingPayoutValue)
        val total = post.totalPayout
        val max = assetAmount(post.maxAcceptedPayout)
        val out = mutableListOf<PayoutRow>()
        if (promoted > 0) out += PayoutRow(PayoutCopy.PROMOTED, approx(promoted))
        if (pending > 0) out += PayoutRow(PayoutCopy.POTENTIAL, approx(pending))
        if (author > 0) out += PayoutRow(PayoutCopy.AUTHOR, approx(author))
        if (curation > 0) out += PayoutRow(PayoutCopy.CURATION, approx(curation))
        // RN: also on a declined payout (max 0), where it reads $0
        if (total >= max) out += PayoutRow(PayoutCopy.MAX_ACCEPTED, approx(max))
        val breakdown = props?.let { breakdown(pending, post.percentHbd, it) }.orEmpty()
        if (breakdown.isNotEmpty() && pending > 0) out += PayoutRow(PayoutCopy.BREAKDOWN, breakdown)
        if (post.beneficiaries.isNotEmpty()) {
            out += PayoutRow(PayoutCopy.BENEFICIARIES, post.beneficiaries.joinToString("\n") { "${it.account}: ${LocaleFormat.percent(it.weight / 100.0, 2, 2)}" })
        }
        fromNow(post.payoutAt, nowMillis)?.let { out += PayoutRow(PayoutCopy.PAYOUT_DATE, it) }
        if (pending > 0 && pending < MINIMUM_FOR_PAYOUT) out += PayoutRow(PayoutCopy.WARN_ZERO, "")
        return out
    }

    /** RN's breakdown: printed HBD, HIVE and HP of the pending payout, one per line, 3 decimals, zero lines left out. */
    fun breakdown(pending: Double, percentHbd: Int, props: PayoutProps): String {
        val percentHbdShare = (if (percentHbd == 0) 10_000 else percentHbd) / 20_000.0
        val hbd = pending * percentHbdShare
        val pricePerHive = props.base / props.quote
        val hp = (pending - hbd) / pricePerHive
        val printedHbd = hbd * (props.hbdPrintRate / SBD_PRINT_RATE_MAX)
        val printedHive = (hbd - printedHbd) / pricePerHive
        return (if (printedHbd > 0) "${LocaleFormat.toFixed(printedHbd, 3)} HBD\n" else "") +
            (if (printedHive > 0) "${LocaleFormat.toFixed(printedHive, 3)} HIVE\n" else "") +
            (if (hp > 0) "${LocaleFormat.toFixed(hp, 3)} HP" else "")
    }

    /**
     * moment.utc(time).fromNow(): "in 5 days", "2 hours ago" ([FromNow]'s wording in the app language, with this
     * screen's own month and year lengths); null for no time.
     */
    fun fromNow(chainTime: String, nowMillis: Long): String? {
        if (chainTime.isBlank()) return null
        val at = runCatching { ChainTime.toEpochSeconds(chainTime.take(19)) * 1000 }.getOrNull() ?: return null
        val delta = at - nowMillis
        val bucket = FromNow.bucket(abs(delta) / 1000.0, monthDays = 30.4, yearDays = 365.0, minTwo = false)
        return FromNow.phrase(bucket, future = delta >= 0)
    }

    /** RN selectGlobalProps' base, quote and hbdPrintRate (the median feed and the dynamic global properties). */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun props(env: AppEnvironment): PayoutProps = guarded {
        coroutineScope {
            val dgp = async { env.api.client.call("condenser_api.get_dynamic_global_properties").obj() }
            val feed = async { env.api.client.call("condenser_api.get_feed_history").obj() }
            val median = feed.await()?.get("current_median_history").obj()
            val rate = (dgp.await()?.get("hbd_print_rate") as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: 0.0
            PayoutProps(assetAmount(median?.str("base") ?: ""), assetAmount(median?.str("quote") ?: ""), rate)
        }
    }
}

data class PayoutDetailsState(val rows: List<PayoutRow> = emptyList())

/** The payout popover of one post or comment; the breakdown row joins once the chain values are read. */
class PayoutDetailsModel internal constructor(private val env: AppEnvironment, private val post: Post) :
    StateModel<PayoutDetailsState>(PayoutDetailsState(PayoutDetails.rows(post, null, env.nowMillis())), env.dispatchers) {
    private var started = false

    fun start() {
        if (started) return
        started = true
        if (assetAmount(post.pendingPayoutValue) <= 0) return
        scope.launch {
            val props = try {
                PayoutDetails.props(env)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@launch
            }
            setState { PayoutDetailsState(PayoutDetails.rows(post, props, env.nowMillis())) }
        }
    }
}

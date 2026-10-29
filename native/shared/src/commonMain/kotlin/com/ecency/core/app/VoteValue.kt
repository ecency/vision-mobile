package com.ecency.core.app

import com.ecency.core.api.EcencyException
import com.ecency.core.api.HiveApi
import com.ecency.core.api.guarded
import com.ecency.core.api.model.Account
import com.ecency.core.api.model.Mana
import com.ecency.core.api.model.obj
import com.ecency.core.api.model.str
import com.ecency.core.l10n.LocaleFormat
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** The chain values the vote estimate needs (SDK getDynamicPropsQueryOptions, the used subset). */
data class RewardProps(
    val fundRecentClaims: Double,
    val fundRewardBalance: Double,
    val base: Double,
    val quote: Double,
    val votePowerReserveRate: Double,
    val lastHardfork: Int,
)

/** SDK votingValue / votingRshares (HF28 rules and the legacy rule) and RN's `$` formatting. */
object VoteValue {
    private const val REGEN = 432_000.0

    private fun amount(asset: String): Double = asset.trim().substringBefore(' ').toDoubleOrNull() ?: 0.0

    /** SDK Ir: current voting mana, capped at the max. */
    private fun currentMana(account: Account, nowMillis: Long): Double {
        val max = Mana.effectiveVests(account) * 1e6
        val bar = account.votingManabar ?: return 0.0
        val cur = bar.currentMana + (nowMillis / 1000.0 - bar.lastUpdateTime) * max / REGEN
        return min(cur, max)
    }

    /** SDK votingRshares; [vPower] 0..10000, [weight] 0..10000. */
    fun rshares(account: Account, props: RewardProps, vPower: Double, weight: Double, nowMillis: Long): Double {
        if (!vPower.isFinite() || !weight.isFinite()) return 0.0
        val vests = Mana.effectiveVests(account)
        if (props.lastHardfork >= 28) {
            val rate = props.votePowerReserveRate
            if (!rate.isFinite() || rate <= 0 || vests <= 0) return 0.0
            val used = ceil(vests * 1e6 * weight * 86400 / 10000 / (rate * REGEN))
            if (used > currentMana(account, nowMillis)) return 0.0
            return max(used - 5e7, 0.0)
        }
        return (vPower * weight / 1e4 / 50 + 1) * (vests * 1e6) / 1e4
    }

    /** SDK votingValue in HBD. */
    fun value(account: Account, props: RewardProps, weight: Int, nowMillis: Long): Double {
        if (props.fundRecentClaims == 0.0 || props.quote == 0.0) return 0.0
        val vPower = Mana.votingPowerPercent(account, nowMillis) * 100
        val r = rshares(account, props, vPower, kotlin.math.abs(weight).toDouble(), nowMillis)
        return r / props.fundRecentClaims * props.fundRewardBalance * (props.base / props.quote)
    }

    /** RN upvotePopover _formatEstimate, in the app language (toFixed digits and decimal separator). */
    fun format(value: Double): String = when {
        value.isNaN() || value <= 0 -> LocaleFormat.toFixed(0.0, 3)
        value >= 1 -> LocaleFormat.toFixed(value, 2)
        value < 0.001 -> LocaleFormat.toFixed(0.001, 3)
        else -> LocaleFormat.toFixed(value, 3)
    }

    /** The four reads the estimate needs, in parallel. */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun props(api: HiveApi): RewardProps = guarded {
        coroutineScope {
            val dgp = async { api.client.call("condenser_api.get_dynamic_global_properties").obj() }
            val feed = async { api.client.call("condenser_api.get_feed_history").obj() }
            val fund = async { api.client.call("condenser_api.get_reward_fund", buildJsonArray { add(JsonPrimitive("post")) }).obj() }
            val hf = async {
                try {
                    api.client.call("database_api.get_hardfork_properties", JsonObject(emptyMap())).obj()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null // the SDK assumes HF28 when this fails
                }
            }
            val median = feed.await()?.get("current_median_history").obj()
            val f = fund.await()
            fun num(o: JsonObject?, k: String) = (o?.get(k) as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: 0.0
            RewardProps(
                fundRecentClaims = num(f, "recent_claims"),
                fundRewardBalance = amount(f?.str("reward_balance") ?: ""),
                base = amount(median?.str("base") ?: ""),
                quote = amount(median?.str("quote") ?: ""),
                votePowerReserveRate = num(dgp.await(), "vote_power_reserve_rate"),
                lastHardfork = hf.await()?.let { (it["last_hardfork"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() } ?: 28,
            )
        }
    }
}

/** What the vote sheet shows next to the slider: `$value` for a weight. */
class VotePreview(private val account: Account, private val props: RewardProps, private val nowMillis: Long) {
    /** RN `$amount` (a "-" prefix in downvote mode is the UI's). */
    fun text(weightBasisPoints: Int): String = "$" + VoteValue.format(VoteValue.value(account, props, weightBasisPoints, nowMillis))
}

/** Loads a [VotePreview] for the signed-in account; null when signed out or the reads failed (the sheet shows none). */
@Throws(CancellationException::class)
suspend fun AppEnvironment.votePreview(): VotePreview? {
    val user = session.current.username ?: return null
    return try {
        val account = api.accounts.getAccount(user) ?: return null
        VotePreview(account, VoteValue.props(api.hive), nowMillis())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}

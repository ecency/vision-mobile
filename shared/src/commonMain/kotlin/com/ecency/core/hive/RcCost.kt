package com.ecency.core.hive

import com.ecency.core.math.UBig
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.ceil

/** The comment about to be broadcast, as the SDK's `CommentLike`. */
data class RcComment(
    val author: String,
    val permlink: String,
    val parentAuthor: String,
    val parentPermlink: String,
    val title: String,
    val body: String,
    val jsonMetadata: String,
)

/** The companion comment_options: present for every new post; [beneficiaries] null when it carries no beneficiary extension. */
data class RcCommentOptions(val beneficiaries: List<Pair<String, Int>>?)

/** The SDK's `RcPrecheckResult`, the fields the editor uses. */
data class RcPrecheck(
    val ready: Boolean = false,
    val currentMana: Double = 0.0,
    val cost: Double = 0.0,
    val transactionBytes: Int = 0,
    val estimatedCost: Double = 0.0,
    val willLikelyFail: Boolean = false,
    val deficit: Double = 0.0,
)

/**
 * The SDK's resource credit estimate (`modules/resource-credits`): what the chain will charge for a comment,
 * computed the way hived computes it (`count_resources` and `compute_cost`), from `rc_api.get_resource_params`,
 * `get_rc_stats` and `find_rc_accounts`. Numbers are JavaScript numbers (doubles) wherever the SDK uses them,
 * and big integers ([UBig]) where it uses BigInt, so the results match it digit for digit.
 */
object RcCost {
    /** Resource order is consensus-defined; `pool` and `share` in rc_stats are indexed by it. */
    val RESOURCES = listOf("resource_history_bytes", "resource_new_accounts", "resource_market_bytes", "resource_state_bytes", "resource_execution_time")

    private const val TRANSACTION_HEADER_BYTES = 11
    private const val SIGNATURE_BYTES = 65
    private const val ASSET_BYTES = 16
    private const val MANA_REGENERATION_SECONDS = 432000.0
    const val BUFFER = 1.2

    // ---- sizes ----

    fun utf8ByteLength(value: String): Int = value.encodeToByteArray().size

    fun varintByteLength(value: Int): Int {
        var count = 0
        var remaining = value
        do {
            count++
            remaining = remaining ushr 7
        } while (remaining > 0)
        return count
    }

    private fun stringFieldBytes(value: String): Int = utf8ByteLength(value).let { varintByteLength(it) + it }

    /** SDK estimateCommentTransactionBytes: Hive's binary encoding of the transaction, one signature. */
    fun transactionBytes(op: RcComment, options: RcCommentOptions?, signatures: Int = 1): Int {
        var operations = 1 + stringFieldBytes(op.parentAuthor) + stringFieldBytes(op.parentPermlink) + stringFieldBytes(op.author) + stringFieldBytes(op.permlink) +
            stringFieldBytes(op.title) + stringFieldBytes(op.body) + stringFieldBytes(op.jsonMetadata)
        var count = 1
        if (options != null) {
            count = 2
            val routes = options.beneficiaries.orEmpty()
            var bytes = 1 + stringFieldBytes(op.author) + stringFieldBytes(op.permlink) + ASSET_BYTES + 2 + 2
            bytes += varintByteLength(if (routes.isNotEmpty()) 1 else 0)
            if (routes.isNotEmpty()) {
                bytes += 1 + varintByteLength(routes.size)
                routes.forEach { bytes += stringFieldBytes(it.first) + 2 }
            }
            operations += bytes
        }
        return TRANSACTION_HEADER_BYTES + varintByteLength(count) + operations + varintByteLength(signatures) + SIGNATURE_BYTES * signatures
    }

    // ---- reading the node's answers (numbers arrive as numbers or strings) ----

    private fun JsonElement?.obj(): JsonObject? = this as? JsonObject

    /** JS `Number(value)`: NaN for anything that is not a number. */
    private fun JsonElement?.number(): Double {
        val p = this as? JsonPrimitive ?: return Double.NaN
        if (p is JsonNull) return 0.0
        return p.content.trim().let { if (it.isEmpty()) 0.0 else it.toDoubleOrNull() ?: Double.NaN }
    }

    /** JS `BigInt(typeof v === "string" ? v : Math.trunc(v))`, for values that are not negative; null otherwise. */
    private fun JsonElement?.big(): UBig? {
        val p = this as? JsonPrimitive ?: return null
        val text = p.content.trim()
        return UBig.parse(text) ?: if (p.isString) null else text.toDoubleOrNull()?.let(UBig::ofDouble)
    }

    // ---- pricing ----

    /** SDK computeResourceCost, the port of hived's `compute_cost`. NaN when the curve cannot be read. */
    fun resourceCost(coeffA: UBig?, coeffB: UBig?, shift: Int?, pool: Double, resourceCount: Double, regenShare: Double): Double {
        if (!(resourceCount > 0) || !(regenShare > 0)) return 0.0
        if (coeffA == null || coeffB == null || shift == null || shift < 0) return Double.NaN
        val regen = UBig.ofDouble(regenShare) ?: return Double.NaN
        val count = UBig.ofDouble(resourceCount) ?: return Double.NaN
        // the node shifts before multiplying by the resource count: the order matters
        val num = (((regen * coeffA) shr shift) + UBig.ONE) * count
        val denom = coeffB + (if (pool > 0) UBig.ofDouble(pool) ?: return Double.NaN else UBig.ZERO)
        if (denom.isZero) return 0.0
        return (num / denom + UBig.ONE).toDouble()
    }

    /** SDK countCommentResourceUsage, by resource in [RESOURCES] order; null when `size_info` lacks a member. */
    fun commentUsage(transactionBytes: Int, permlinkLength: Int, beneficiaries: Int, hasOptions: Boolean, sizeInfo: JsonObject, signatures: Int = 1): List<Double>? {
        val state = sizeInfo["resource_state_bytes"].obj() ?: return null
        val exec = sizeInfo["resource_execution_time"].obj() ?: return null
        fun n(o: JsonObject, key: String) = o[key].number()
        val stateBytes = n(state, "comment_base_size") + n(state, "comment_permlink_char_size") * permlinkLength + n(state, "transaction_base_size") +
            n(state, "comment_beneficiaries_member_size") * beneficiaries
        val time = n(exec, "comment_time") + n(exec, "transaction_time") + n(exec, "verify_authority_time") * signatures + (if (hasOptions) n(exec, "comment_options_time") else 0.0)
        return listOf(transactionBytes.toDouble(), 0.0, 0.0, stateBytes, time)
    }

    /** SDK priceRcUsage: the sum over the resources that have a share. NaN when anything needed is unreadable. */
    fun price(usage: List<Double>, resourceParams: JsonObject, stats: JsonObject): Double {
        val regen = stats["regen"].number()
        val pools = stats["pool"] as? JsonArray ?: return Double.NaN
        val shares = stats["share"] as? JsonArray ?: return Double.NaN
        var cost = 0.0
        RESOURCES.forEachIndexed { index, name ->
            val entry = resourceParams[name].obj() ?: return@forEachIndexed
            val pool = pools.getOrNull(index)?.number() ?: 0.0
            val share = shares.getOrNull(index)?.number() ?: 0.0
            if (!(share > 0)) return@forEachIndexed
            val dynamics = entry["resource_dynamics_params"].obj()
            val unit = dynamics?.get("resource_unit")?.takeIf { it !is JsonNull }?.number() ?: 1.0
            val scaled = usage[index] * unit
            // BigInt(regen) * BigInt(share) / 10000n: both must be whole numbers
            val r = regen.takeIf { it >= 0 && it == kotlin.math.floor(it) }?.let(UBig::ofDouble) ?: return Double.NaN
            val s = share.takeIf { it == kotlin.math.floor(it) }?.let(UBig::ofDouble) ?: return Double.NaN
            val regenShare = (r * s / UBig.of(10000uL)).toDouble()
            val curve = entry["price_curve_params"].obj() ?: return Double.NaN
            cost += resourceCost(curve["coeff_a"].big(), curve["coeff_b"].big(), curve["shift"].big()?.toDouble()?.toInt(), pool, scaled, regenShare)
        }
        return cost
    }

    /**
     * SDK estimateRcPrecheck for a comment with its payload. [rcAccount] is `find_rc_accounts`' first row,
     * [stats] is `get_rc_stats`' `rc_stats`, [params] is `get_resource_params`' result. Not ready (never a
     * made-up all-clear or warning) when any of them is missing or unreadable.
     */
    fun precheck(rcAccount: JsonObject?, stats: JsonObject?, params: JsonObject?, op: RcComment, options: RcCommentOptions?, nowMillis: Long): RcPrecheck {
        if (rcAccount == null || stats == null || stats["ops"].obj() == null) return RcPrecheck()
        // calculateRCMana: regenerated since the last update, not capped at the maximum (as the SDK)
        val maxMana = rcAccount["max_rc"].number()
        val manabar = rcAccount["rc_manabar"].obj()
        val delta = nowMillis / 1000.0 - (manabar?.get("last_update_time")?.number() ?: Double.NaN)
        val currentMana = (manabar?.get("current_mana")?.number() ?: Double.NaN) + (delta * maxMana) / MANA_REGENERATION_SECONDS
        val resourceParams = params?.get("resource_params").obj()
        val sizeInfo = params?.get("size_info").obj()
        if (resourceParams == null || sizeInfo == null || stats["pool"] !is JsonArray || stats["share"] !is JsonArray) return RcPrecheck(currentMana = currentMana)
        val bytes = transactionBytes(op, options)
        val usage = commentUsage(bytes, op.permlink.length, options?.beneficiaries?.size ?: 0, options != null, sizeInfo) ?: return RcPrecheck(currentMana = currentMana)
        val cost = price(usage, resourceParams, stats)
        if (cost.isNaN() || currentMana.isNaN()) return RcPrecheck(currentMana = currentMana)
        val estimated = cost * BUFFER
        val fails = currentMana < estimated
        return RcPrecheck(true, currentMana, cost, bytes, estimated, fails, if (fails) ceil(estimated - currentMana) else 0.0)
    }
}

package com.ecency.core.auth

import com.ecency.core.hive.KeyRole
import com.ecency.core.l10n.L
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Which authority signs an operation. Operations are condenser JSON `[name, {fields}]`.
 *
 * The RN app has three rules, all ported: [required] (@ecency/sdk OPERATION_AUTHORITY_MAP, used for every
 * broadcast and the auth upgrade), [forHiveUri] (RN hiveOperationAuthority, for hive:// links) and
 * [hiveAuthKeyType] (RN useHiveAuth inferOperationKeyType, the key type sent to the HiveAuth wallet).
 */
object OperationAuthority {
    private val SDK_MAP: Map<String, KeyRole> = buildMap {
        listOf("vote", "comment", "delete_comment", "comment_options", "claim_reward_balance").forEach { put(it, KeyRole.POSTING) }
        listOf(
            "cancel_transfer_from_savings", "collateralized_convert", "convert", "delegate_vesting_shares",
            "recurrent_transfer", "set_withdraw_vesting_route", "transfer", "transfer_from_savings",
            "transfer_to_savings", "transfer_to_vesting", "withdraw_vesting", "limit_order_create",
            "limit_order_cancel", "account_update", "account_update2", "claim_account", "create_claimed_account",
            "account_witness_proxy", "account_witness_vote", "remove_proposal", "update_proposal_votes",
        ).forEach { put(it, KeyRole.ACTIVE) }
        listOf("change_recovery_account", "request_account_recovery", "recover_account", "reset_account", "set_reset_account")
            .forEach { put(it, KeyRole.OWNER) }
    }

    /**
     * Operations the SDK map leaves out, where its default (posting) would be wrong: the chain wants the active key for
     * these, and the owner key to decline voting rights. Pages (the dApp bridge) can ask for any of them.
     */
    private val BEYOND_SDK: Map<String, KeyRole> = buildMap {
        listOf(
            "limit_order_create2", "witness_update", "witness_set_properties", "feed_publish", "escrow_transfer",
            "escrow_approve", "escrow_dispute", "escrow_release", "account_create", "account_create_with_delegation",
        ).forEach { put(it, KeyRole.ACTIVE) }
        put("decline_voting_rights", KeyRole.OWNER)
    }

    /** Whether [name] is an operation this map classifies (its authority is not a guess). */
    fun isKnown(name: String): Boolean =
        name in SDK_MAP || name in BEYOND_SDK || name == "custom_json" || name == "create_proposal" || name == "update_proposal"

    private fun name(op: JsonArray) = (op.getOrNull(0) as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun body(op: JsonArray) = op.getOrNull(1) as? JsonObject
    private fun nonEmptyArray(o: JsonObject?, field: String) = (o?.get(field) as? JsonArray)?.isNotEmpty() == true

    /**
     * SDK getOperationAuthority: custom_json by its auths, proposals active, unknown operations posting.
     * Two deliberate differences: a metadata-only account_update2 (no key fields, empty json_metadata) is
     * posting, as consensus requires and as the SDK's own account update signs it (its map says active, its
     * mutation passes posting); with key fields or json_metadata it stays active. And an account_update or
     * account_update2 that sets `owner` is OWNER (Hive issue 520: a new owner authority needs the owner key,
     * for both versions), where the SDK and RN say active.
     */
    fun of(op: JsonArray): KeyRole = when (val n = name(op)) {
        "custom_json" -> if (nonEmptyArray(body(op), "required_auths")) KeyRole.ACTIVE else KeyRole.POSTING
        "account_update", "account_update2" -> accountUpdate(n, body(op))
        "create_proposal", "update_proposal" -> KeyRole.ACTIVE
        else -> SDK_MAP[n] ?: BEYOND_SDK[n] ?: KeyRole.POSTING
    }

    /** SDK getRequiredAuthority: owner over active over posting. */
    fun required(ops: JsonArray): KeyRole {
        var highest = KeyRole.POSTING
        for (op in ops) {
            when (of(op as? JsonArray ?: continue)) {
                KeyRole.OWNER -> return KeyRole.OWNER
                KeyRole.ACTIVE -> highest = KeyRole.ACTIVE
                else -> Unit
            }
        }
        return highest
    }

    /** Owner when the update sets `owner`; account_update is otherwise active, account_update2 by its fields. */
    private fun accountUpdate(name: String, b: JsonObject?): KeyRole = when {
        setsOwner(b) -> KeyRole.OWNER
        name == "account_update" || accountUpdate2NeedsActive(b) -> KeyRole.ACTIVE
        else -> KeyRole.POSTING
    }

    private fun setsOwner(b: JsonObject?): Boolean = b?.get("owner").let { it != null && it != JsonNull }

    /**
     * A custom_json that fills both `required_auths` and `required_posting_auths`. Valid on chain since HF28, but it
     * needs an active and a posting signature, and every signing path here signs with one key: refused as
     * unsupported instead of being signed as active and refused by the chain for the missing posting signature.
     */
    fun isMixedCustomJson(op: JsonArray): Boolean =
        name(op) == "custom_json" && nonEmptyArray(body(op), "required_auths") && nonEmptyArray(body(op), "required_posting_auths")

    /**
     * Why [ops] cannot be signed by this app at all, or null: a custom_json that needs two keys, or a transaction that
     * mixes posting and active operations (e.g. a vote and a transfer). Since HF28 the active key no longer signs for
     * the posting authority, and every signing path here signs with one key, so the chain would refuse it after the
     * user confirmed.
     */
    fun unsupported(ops: JsonArray): String? {
        val list = ops.mapNotNull { it as? JsonArray }
        if (list.any(::isMixedCustomJson)) return MIXED_AUTHORITIES
        val roles = list.map(::of).toSet()
        return if (KeyRole.POSTING in roles && KeyRole.ACTIVE in roles) MIXED_AUTHORITIES else null
    }

    val MIXED_AUTHORITIES: String get() = L.native.operationAuthority.thisOperationNeedsBoth
    /** An account_update2 that carries a key field (owner, active, posting, memo_key) or a json_metadata. */
    private fun accountUpdate2NeedsActive(b: JsonObject?): Boolean =
        listOf("owner", "active", "posting", "memo_key").any { b?.get(it).let { v -> v != null && v != JsonNull } } ||
            ((b?.get("json_metadata") as? JsonPrimitive)?.takeIf { it.isString }?.content?.isNotEmpty() == true)

    private val HIVE_URI_POSTING = setOf("vote", "comment", "comment_options", "custom_json", "delete_comment", "claim_reward_balance")

    /**
     * RN resolveOperationAuthority (hive:// links): account_update2 is posting when it only touches posting
     * metadata. Owner operations of the SDK map, and either account update setting `owner`, are OWNER (RN
     * resolves them to active); external links must refuse them.
     */
    fun forHiveUri(op: JsonArray): KeyRole {
        val b = body(op)
        if (SDK_MAP[name(op)] == KeyRole.OWNER || BEYOND_SDK[name(op)] == KeyRole.OWNER) return KeyRole.OWNER
        return when (val n = name(op)) {
            "custom_json" -> if (nonEmptyArray(b, "required_auths")) KeyRole.ACTIVE else KeyRole.POSTING
            "account_update", "account_update2" -> accountUpdate(n, b)
            else -> if (n in HIVE_URI_POSTING) KeyRole.POSTING else KeyRole.ACTIVE
        }
    }

    /** RN resolveTxRequiredAuthority, with OWNER above active. */
    fun forHiveUriTx(ops: JsonArray): KeyRole {
        val roles = ops.mapNotNull { (it as? JsonArray)?.let(::forHiveUri) }
        return when {
            KeyRole.OWNER in roles -> KeyRole.OWNER
            KeyRole.ACTIVE in roles -> KeyRole.ACTIVE
            else -> KeyRole.POSTING
        }
    }

    private val HIVE_AUTH_POSTING = setOf("vote", "comment", "comment_options", "delete_comment", "claim_reward_balance")

    /**
     * RN inferOperationKeyType: the key type of a HiveAuth sign request. Unlike RN (which sends every
     * account_update2 as posting), account updates are classified by their fields as in [of], so a key or
     * json_metadata change asks the wallet for active; an owner operation is OWNER, which HiveAuth cannot sign
     * and the caller refuses. Unknown operations stay active, as RN.
     */
    fun hiveAuthKeyType(ops: JsonArray): KeyRole {
        var active = false
        for (element in ops) {
            val op = element as? JsonArray ?: continue
            val n = name(op)
            val role = when {
                n == "custom_json" -> if (nonEmptyArray(body(op), "required_auths")) KeyRole.ACTIVE else KeyRole.POSTING
                n == "account_update" || n == "account_update2" -> accountUpdate(n, body(op))
                SDK_MAP[n] == KeyRole.OWNER || BEYOND_SDK[n] == KeyRole.OWNER -> KeyRole.OWNER
                n in HIVE_AUTH_POSTING -> KeyRole.POSTING
                else -> KeyRole.ACTIVE
            }
            if (role == KeyRole.OWNER) return KeyRole.OWNER
            if (role == KeyRole.ACTIVE) active = true
        }
        return if (active) KeyRole.ACTIVE else KeyRole.POSTING
    }
}

/**
 * The active key entered in the auth upgrade sheet, held for one signature of one account for at most
 * 60 seconds (RN mobilePlatformAdapter setTempActiveKey / getTempActiveKey). Reading it consumes it, whoever
 * asks; only the account it was entered for gets it back. Thread-safe.
 */
@OptIn(ExperimentalAtomicApi::class)
class TempActiveKey(private val nowMillis: () -> Long, private val ttlMillis: Long = TTL_MILLIS) {
    private class Held(val username: String, val wif: String, val expiresAt: Long)

    private val slot = AtomicReference<Held?>(null)

    fun set(username: String, wif: String) = slot.store(Held(username, wif, nowMillis() + ttlMillis))

    /** The key for [username], once; null when none was set for it, it was taken, or it expired. */
    fun take(username: String): String? =
        slot.exchange(null)?.takeIf { it.username == username && nowMillis() < it.expiresAt }?.wif

    val isHeld: Boolean get() = slot.load()?.let { nowMillis() < it.expiresAt } == true

    fun clear() = slot.store(null)

    override fun toString(): String = "TempActiveKey(held=$isHeld)"

    companion object {
        const val TTL_MILLIS = 60_000L
    }
}

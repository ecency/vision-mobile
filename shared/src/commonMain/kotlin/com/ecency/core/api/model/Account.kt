package com.ecency.core.api.model

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/** The `profile` object users write into their (posting_)json_metadata. All optional. */
data class AccountProfile(
    val displayName: String? = null,
    val about: String? = null,
    val website: String? = null,
    val location: String? = null,
    val profileImage: String? = null,
    val coverImage: String? = null,
) {
    companion object {
        internal fun fromMetadata(metadata: JsonElement?): AccountProfile? {
            val p = metadata.obj()?.get("profile").obj() ?: return null
            fun field(key: String) = p.str(key)?.trim()?.takeIf { it.isNotEmpty() }
            return AccountProfile(
                displayName = field("name"),
                about = field("about"),
                website = field("website"),
                location = field("location"),
                profileImage = field("profile_image"),
                coverImage = field("cover_image"),
            )
        }
    }
}

/** Profile header data from `bridge.get_profile`. */
data class Profile(
    val name: String,
    val created: String = "",
    val active: String = "",
    val postCount: Int = 0,
    /** Floored as the app shows it (64.7 is 64). */
    val reputation: Int = 25,
    val followers: Int = 0,
    val following: Int = 0,
    val profile: AccountProfile = AccountProfile(),
    /**
     * SDK hasProfileValues: hivemind's raw `metadata.profile` holds at least one real value (a non-empty string
     * or any other non-null value, whatever the key: also blacklist_description, muted_list_description, ...).
     */
    val hasProfileValues: Boolean = false,
) {
    companion object {
        fun fromJson(element: JsonElement?): Profile? {
            val o = element.obj() ?: return null
            val name = o.str("name")?.takeIf { it.isNotBlank() } ?: return null
            val stats = o["stats"].obj()
            return Profile(
                name = name,
                created = o.string("created"),
                active = o.string("active"),
                postCount = o.int("post_count") ?: 0,
                reputation = Reputation.display(o.double("reputation") ?: 0.0),
                followers = stats?.int("followers") ?: 0,
                following = stats?.int("following") ?: 0,
                profile = AccountProfile.fromMetadata(o["metadata"]) ?: AccountProfile(),
                hasProfileValues = o["metadata"].obj()?.get("profile").obj()?.values.orEmpty().any { v ->
                    when {
                        v is kotlinx.serialization.json.JsonNull -> false
                        v is JsonPrimitive && v.isString -> v.content.isNotEmpty()
                        else -> true
                    }
                },
            )
        }
    }
}

/** Account basics from `condenser_api.get_accounts`. */
data class Account(
    val name: String,
    val created: String = "",
    val postCount: Int = 0,
    /** Scaled and floored the way the app shows it (raw 0 is 25). */
    val reputation: Int = 25,
    val balance: String = "0.000 HIVE",
    val hbdBalance: String = "0.000 HBD",
    val vestingShares: String = "0.000000 VESTS",
    val postingPublicKeys: List<String> = emptyList(),
    val memoKey: String? = null,
    /** From posting_json_metadata, falling back to json_metadata as the SDK does. */
    val profile: AccountProfile = AccountProfile(),
    val delegatedVestingShares: String = "0.000000 VESTS",
    val receivedVestingShares: String = "0.000000 VESTS",
    val vestingWithdrawRate: String = "0.000000 VESTS",
    /** Raw VESTS units (6 decimals) still to power down, and already powered down. */
    val toWithdraw: Double = 0.0,
    val withdrawn: Double = 0.0,
    /** `voting_manabar`; null when the node left it out. */
    val votingManabar: Manabar? = null,
    /** The raw metadata strings, for a profile edit that must keep everything it does not change. */
    val postingJsonMetadata: String = "",
    val jsonMetadata: String = "",
) {
    /** Neither metadata string is set: legitimate for a new account, or a node that stripped them. */
    val isMetadataStripped: Boolean get() = postingJsonMetadata.isEmpty() && jsonMetadata.isEmpty()

    companion object {
        fun fromJson(element: JsonElement?): Account? {
            val o = element.obj() ?: return null
            val name = o.str("name")?.takeIf { it.isNotBlank() } ?: return null
            val posting = o["posting"].obj()
            return Account(
                name = name,
                created = o.string("created"),
                postCount = o.int("post_count") ?: 0,
                reputation = Reputation.display(o.double("reputation") ?: 0.0),
                balance = o.str("balance") ?: "0.000 HIVE",
                hbdBalance = o.str("hbd_balance") ?: "0.000 HBD",
                vestingShares = o.str("vesting_shares") ?: "0.000000 VESTS",
                postingPublicKeys = posting?.get("key_auths").arr().orEmpty().mapNotNull { auth ->
                    ((auth as? JsonArray)?.firstOrNull() as? JsonPrimitive)?.content
                },
                memoKey = o.str("memo_key"),
                profile = AccountProfile.fromMetadata(o["posting_json_metadata"])
                    ?: AccountProfile.fromMetadata(o["json_metadata"])
                    ?: AccountProfile(),
                delegatedVestingShares = o.str("delegated_vesting_shares") ?: "0.000000 VESTS",
                receivedVestingShares = o.str("received_vesting_shares") ?: "0.000000 VESTS",
                vestingWithdrawRate = o.str("vesting_withdraw_rate") ?: "0.000000 VESTS",
                toWithdraw = o.double("to_withdraw") ?: 0.0,
                withdrawn = o.double("withdrawn") ?: 0.0,
                votingManabar = o["voting_manabar"].obj()?.let { m ->
                    val mana = m.double("current_mana") ?: return@let null
                    val updated = m.long("last_update_time") ?: return@let null
                    Manabar(mana, updated)
                },
                postingJsonMetadata = (o["posting_json_metadata"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty(),
                jsonMetadata = (o["json_metadata"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty(),
            )
        }
    }
}

/** A Hive manabar: mana at [lastUpdateTime] (unix seconds); it refills linearly over five days. */
data class Manabar(val currentMana: Double, val lastUpdateTime: Long)

/**
 * The SDK's mana maths (`calculateVPMana`, ported from dhive).
 *
 * Two known differences from RN, both accepted:
 * - Missing vesting fields count as 0 here; in JS `parseFloat(undefined)` is NaN and the voting power becomes 0.
 *   Nodes always send them, so only malformed accounts differ (here: a number, there: 0).
 * - [oneDecimal] rounds half up on the decimal value; JS `toFixed(1)` rounds the binary double, so a tie that is
 *   not exact in binary (87.35 is 87.3499...) can come out one tenth lower in RN ("87.3" vs "87.4").
 */
object Mana {
    private const val REGENERATION_SECONDS = 432_000.0

    /** Leading number of an asset string ("12.345678 VESTS" is 12.345678); 0 when unreadable. */
    private fun amount(asset: String): Double = asset.trim().substringBefore(' ').toDoubleOrNull() ?: 0.0

    /** Effective vesting shares: own minus pending power down minus delegated plus received. */
    fun effectiveVests(account: Account): Double {
        val withdrawRate = amount(account.vestingWithdrawRate)
        val left = (account.toWithdraw - account.withdrawn) / 1e6
        return amount(account.vestingShares) - minOf(withdrawRate, left) - amount(account.delegatedVestingShares) +
            amount(account.receivedVestingShares)
    }

    /** Voting power in percent (0 to 100, two decimals) at [nowMillis]; 0 without a manabar. */
    fun votingPowerPercent(account: Account, nowMillis: Long): Double {
        val bar = account.votingManabar ?: return 0.0
        val max = effectiveVests(account) * 1e6
        val delta = nowMillis / 1000.0 - bar.lastUpdateTime
        val current = bar.currentMana + delta * max / REGENERATION_SECONDS
        val basis = kotlin.math.floor(current / max * 10000 + 0.5) // JS Math.round
        val clamped = if (basis.isNaN() || basis.isInfinite() || basis < 0) 0.0 else minOf(basis, 10000.0)
        return clamped / 100
    }

    /** RC in percent (0 to 100, two decimals) at [nowMillis], SDK calculateRCMana; 0 without RC data. */
    fun rcPercent(rc: RcAccount?, nowMillis: Long): Double {
        if (rc == null) return 0.0
        val delta = nowMillis / 1000.0 - rc.lastUpdateTime
        val current = rc.currentMana + delta * rc.maxRc / REGENERATION_SECONDS
        val basis = kotlin.math.floor(current / rc.maxRc * 10000 + 0.5)
        val clamped = if (basis.isNaN() || basis.isInfinite() || basis < 0) 0.0 else minOf(basis, 10000.0)
        return clamped / 100
    }

    /** JS `toFixed(1)` for a non-negative number, as RN shows voting power ("87.3", "100.0"). */
    fun oneDecimal(value: Double): String {
        val tenths = kotlin.math.floor(value * 10 + 0.5).toLong()
        return "${tenths / 10}.${tenths % 10}"
    }
}

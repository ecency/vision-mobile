package com.ecency.core.api.model

import kotlinx.serialization.json.JsonElement

/** `bridge.get_relationship_between_accounts` (what [observer] does to the target). All false when unknown. */
data class Relationship(
    val follows: Boolean = false,
    val ignores: Boolean = false,
    val blacklists: Boolean = false,
    val followsMuted: Boolean = false,
    val followsBlacklists: Boolean = false,
) {
    companion object {
        fun fromJson(element: JsonElement?): Relationship {
            val o = element.obj() ?: return Relationship()
            return Relationship(
                follows = o.bool("follows") == true,
                ignores = o.bool("ignores") == true,
                blacklists = o.bool("blacklists") == true,
                followsMuted = o.bool("follows_muted") == true,
                followsBlacklists = o.bool("follows_blacklists") == true,
            )
        }
    }
}

/** `condenser_api.get_follow_count`. */
data class FollowCount(val followers: Int, val following: Int)

/** One row of a followers or following list (SDK getFriendsInfiniteQueryOptions: name, reputation, last active). */
data class FriendRow(val name: String, val reputation: Int = 25, val active: String = "")

/** `rc_api.find_rc_accounts`: the resource credit manabar. */
/**
 * An account's RC as a delegation needs it, exact (64-bit integers): [maxRc] counts received delegations, which
 * cannot be passed on; the manabar refills to [maxRc] over five days.
 */
data class RcDelegator(val maxRc: Long, val receivedDelegatedRc: Long, val currentMana: Long, val lastUpdateTime: Long) {
    /** What the chain lets [delegable] at [nowSeconds]: the lower of the own maximum and the mana refilled until then. */
    fun delegable(nowSeconds: Long): Long {
        val own = (maxRc - receivedDelegatedRc).coerceAtLeast(0)
        // full after five days; maxRc * elapsed can pass 64 bits on a large account, so it is split exactly
        val elapsed = (nowSeconds - lastUpdateTime).coerceIn(0, REGENERATION_SECONDS)
        val refill = maxRc / REGENERATION_SECONDS * elapsed + maxRc % REGENERATION_SECONDS * elapsed / REGENERATION_SECONDS
        val mana = if (currentMana >= maxRc - refill) maxRc else currentMana + refill
        return minOf(own, mana).coerceAtLeast(0)
    }

    companion object {
        private const val REGENERATION_SECONDS = 432_000L

        fun fromJson(element: JsonElement?): RcDelegator? {
            val o = element.obj() ?: return null
            val bar = o["rc_manabar"].obj() ?: return null
            return RcDelegator(
                maxRc = o.long("max_rc") ?: return null,
                receivedDelegatedRc = o.long("received_delegated_rc") ?: 0,
                currentMana = bar.long("current_mana") ?: return null,
                lastUpdateTime = bar.long("last_update_time") ?: return null,
            )
        }
    }
}

data class RcAccount(val maxRc: Double, val currentMana: Double, val lastUpdateTime: Long) {
    companion object {
        fun fromJson(element: JsonElement?): RcAccount? {
            val o = element.obj() ?: return null
            val bar = o["rc_manabar"].obj() ?: return null
            return RcAccount(
                maxRc = o.double("max_rc") ?: return null,
                currentMana = bar.double("current_mana") ?: return null,
                lastUpdateTime = bar.long("last_update_time") ?: return null,
            )
        }
    }
}

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

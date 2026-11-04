package com.ecency.core.api.community

import com.ecency.core.api.model.arr
import com.ecency.core.api.model.bool
import com.ecency.core.api.model.int
import com.ecency.core.api.model.long
import com.ecency.core.api.model.obj
import com.ecency.core.api.model.str
import com.ecency.core.api.model.string
import com.ecency.core.l10n.L
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/** Hivemind community roles (SDK `ROLES`), highest first. */
enum class CommunityRole(val value: String) {
    OWNER("owner"),
    ADMIN("admin"),
    MOD("mod"),
    MEMBER("member"),
    GUEST("guest"),
    MUTED("muted"),
    ;

    /** RN en-US `community.role_*`. */
    val label: String
        get() = when (this) {
            OWNER -> L.community.roleOwner
            ADMIN -> L.community.roleAdmin
            MOD -> L.community.roleMod
            MEMBER -> L.community.roleMember
            GUEST -> L.community.roleGuest
            MUTED -> L.community.muted
        }

    /** Hivemind's ordering: muted below guest, owner on top. */
    val rank: Int get() = MUTED.ordinal - ordinal

    companion object {
        fun of(value: String?): CommunityRole? = entries.firstOrNull { it.value == value }

        /** Hivemind's `MAX_MOD_NB`: at most this many accounts with a role of mod or above in one community. */
        const val MAX_MODERATORS = 100

        /**
         * Why hivemind would refuse [actor] setting the target (currently [current]) to [new], in its own words;
         * null when it would accept. The rules and their order are hivemind's `community_set_role`
         * (hive/db/sql_scripts/community.sql): the actor is a mod or above, the new role is below the actor's,
         * an owner's role never changes (not even by the owner), and, unless the actor changes their own role
         * ([self]), the target is below the actor and the role really changes. A promotion to mod or above also
         * needs room under [MAX_MODERATORS]; [otherModerators] is how many accounts other than the target hold
         * mod or above. An account without a role is a guest there, so a null [actor] is one too.
         */
        fun setRoleRefusal(actor: CommunityRole?, current: CommunityRole, new: CommunityRole, self: Boolean, otherModerators: Int = 0): String? {
            val actorRank = (actor ?: GUEST).rank
            return when {
                actorRank < MOD.rank -> "only mods and up can alter roles"
                actorRank <= new.rank -> "cannot promote to or above own rank"
                current == OWNER -> "cant modify owner role"
                !self && current.rank >= actorRank -> "cant modify a user with a higher role"
                !self && current == new -> "role would not change"
                new.rank >= MOD.rank && otherModerators >= MAX_MODERATORS -> "moderator limit exceeded"
                else -> null
            }
        }

        /** Roles with moderation authority on chain (RN MODERATOR_ROLES). */
        val MODERATORS = setOf(OWNER, ADMIN, MOD)

        /** Roles that may edit the community props (RN SETTINGS_ROLES; mods excluded, as web). */
        val SETTINGS = setOf(OWNER, ADMIN)

        /** SDK `roleMap`: the roles each role may assign (strictly below its own). */
        fun assignable(by: CommunityRole?): List<CommunityRole> = when (by) {
            OWNER -> listOf(ADMIN, MOD, MEMBER, GUEST, MUTED)
            ADMIN -> listOf(MOD, MEMBER, GUEST, MUTED)
            MOD -> listOf(MEMBER, GUEST, MUTED)
            else -> emptyList()
        }
    }
}

/** One team row of a community (hivemind's positional `[account, role, title]`). */
data class TeamMember(val account: String, val role: String, val title: String)

/** `bridge.get_community` / `bridge.list_communities` entry (SDK `Community`). */
data class Community(
    val name: String,
    val title: String,
    val about: String = "",
    val description: String = "",
    /** The rules. */
    val flagText: String = "",
    val lang: String = "",
    val isNsfw: Boolean = false,
    val subscribers: Long = 0,
    val numAuthors: Long = 0,
    val numPending: Long = 0,
    val typeId: Int = 1,
    val createdAt: String = "",
    val team: List<TeamMember> = emptyList(),
    val admins: List<String> = emptyList(),
    /** The observer's role and subscription, when the call carried an observer. */
    val observerRole: String? = null,
    val observerSubscribed: Boolean? = null,
) {
    /** The role of [username] on the team (RN getCommunityRole), null when not on it. */
    fun roleOf(username: String?): CommunityRole? =
        if (username.isNullOrEmpty()) null else team.firstOrNull { it.account == username }?.let { CommunityRole.of(it.role) }

    /** Owner, admin or mod (RN isCommunityModerator); a UI gate only, hivemind decides. */
    fun isModerator(username: String?): Boolean = roleOf(username) in CommunityRole.MODERATORS

    /** Owner or admin: may edit the props (RN community settings canEdit). */
    fun canEditSettings(username: String?): Boolean = roleOf(username) in CommunityRole.SETTINGS

    /** SDK getCommunityType. */
    val type: String
        get() = when {
            name.startsWith("hive-3") || typeId == 3 -> L.native.community.council
            name.startsWith("hive-2") || typeId == 2 -> L.native.community.journal
            else -> L.native.community.topic
        }

    companion object {
        fun fromJson(element: JsonElement?): Community? {
            val o = element.obj() ?: return null
            val name = o.str("name")?.takeIf { it.isNotBlank() } ?: return null
            val context = o["context"].obj()
            return Community(
                name = name,
                title = o.str("title")?.takeIf { it.isNotBlank() } ?: name,
                about = o.string("about"),
                description = o.string("description"),
                flagText = o.string("flag_text"),
                lang = o.string("lang"),
                isNsfw = o.bool("is_nsfw") ?: false,
                subscribers = o.long("subscribers") ?: 0,
                numAuthors = o.long("num_authors") ?: 0,
                numPending = o.long("num_pending") ?: 0,
                typeId = o.int("type_id") ?: 1,
                createdAt = o.string("created_at"),
                team = o["team"].arr().orEmpty().mapNotNull(::teamRow),
                admins = o["admins"].arr().orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content },
                observerRole = context?.str("role"),
                observerSubscribed = context?.bool("subscribed"),
            )
        }

        private fun teamRow(e: JsonElement): TeamMember? {
            val row = e as? JsonArray ?: return null
            val account = row.text(0)?.takeIf { it.isNotEmpty() } ?: return null
            return TeamMember(account, row.text(1).orEmpty(), row.text(2).orEmpty())
        }
    }
}

/** One of an account's subscriptions (`bridge.list_all_subscriptions`: `[name, title, role, label]`). */
data class Subscription(val community: String, val title: String, val role: String = "guest", val label: String = "") {
    companion object {
        fun fromJson(e: JsonElement): Subscription? {
            val row = e as? JsonArray ?: return null
            val name = row.text(0)?.takeIf { it.isNotEmpty() } ?: return null
            return Subscription(name, row.text(1)?.takeIf { it.isNotEmpty() } ?: name, row.text(2) ?: "guest", row.text(3).orEmpty())
        }
    }
}

/** One subscriber (`bridge.list_subscribers`: `[account, role, title, joined]`). */
data class Subscriber(val account: String, val role: String, val title: String, val joined: String) {
    companion object {
        fun fromJson(e: JsonElement): Subscriber? {
            val row = e as? JsonArray ?: return null
            val account = row.text(0)?.takeIf { it.isNotEmpty() } ?: return null
            return Subscriber(account, row.text(1).orEmpty(), row.text(2).orEmpty(), row.text(3).orEmpty())
        }
    }
}

/** One community log entry (`bridge.account_notifications` for the community account). */
data class CommunityActivity(
    /** Opaque cursor (hivemind sends a big number as a string). */
    val id: String,
    val type: String,
    val msg: String,
    /** UTC "YYYY-MM-DDTHH:MM:SS". */
    val date: String,
    val score: Int,
    val url: String,
) {
    companion object {
        fun fromJson(e: JsonElement): CommunityActivity? {
            val o = e.obj() ?: return null
            val id = (o["id"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content ?: return null
            return CommunityActivity(id, o.string("type"), o.string("msg"), o.string("date"), o.int("score") ?: 0, o.string("url"))
        }
    }
}

private fun JsonArray.text(i: Int): String? = (getOrNull(i) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

/** Community account names. */
object CommunityNames {
    private val SDK = Regex("^hive-\\d+$")
    private val RN = Regex("hive-[1-3]\\d{4,6}$")

    /** SDK isCommunity: `hive-` and digits. */
    fun isCommunity(name: String): Boolean = SDK.matches(name)

    /**
     * RN utils/communityValidation isCommunity (unanchored start, numbering hive-1xxxx to hive-3xxxxxx). The members
     * screen drops such accounts (the community itself sits on its own team), so it keeps RN's rule.
     */
    fun looksLikeCommunityAccount(name: String): Boolean = RN.containsMatchIn(name)
}

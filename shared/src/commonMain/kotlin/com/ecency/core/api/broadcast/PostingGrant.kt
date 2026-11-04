package com.ecency.core.api.broadcast

import com.ecency.core.api.model.arr
import com.ecency.core.api.model.obj
import com.ecency.core.hive.AccountUpdatePosting
import com.ecency.core.hive.Authority
import com.ecency.core.hive.AuthorityEntry
import com.ecency.core.hive.PublicKey
import com.ecency.core.l10n.L
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Why a grant was not built. The message is shown to the user. */
class PostingGrantRefused(message: String) : Exception(message)

/**
 * The grant of posting authority to another account (ecency.app, so Ecency can publish scheduled posts): the SDK's
 * buildGrantPostingPermissionOp as RN calls it. It ADDS one account to the posting authority and keeps
 * everything else as the chain holds it: every key and account auth with its weight, the weight threshold and the
 * memo key. Both lists are written in hived's order (accounts by name, keys by key bytes). Owner and active are not part of the operation.
 *
 * This changes who may post for the account, so the account read is checked first and anything that does not
 * look like a complete account refuses the grant rather than writing a guess: a node that stripped or mangled the
 * authority would otherwise make the app remove the user's posting key.
 *
 * Difference from RN: RN sends the account's `posting_json_metadata` as the operation's `json_metadata`, which
 * overwrites the account's legacy metadata; here `json_metadata` is written back unchanged.
 */
object PostingGrant {
    const val GRANTED_ACCOUNT = "ecency.app"

    /** hived's limit on the entries of one authority. */
    const val MAX_AUTHORITY_MEMBERS = 40

    private val UNREADABLE: String get() = L.native.postingGrant.unreadable

    /**
     * Everything of [account] the grant writes back, in one comparable text: the posting authority's threshold,
     * every key and account auth with its weight (in a fixed order, whatever order a node returned) and the memo
     * key. Two reads of the account must give the same text before a grant is built; null when the row has no
     * readable posting authority.
     */
    fun fingerprint(account: JsonElement?): String? {
        val o = account.obj() ?: return null
        val posting = o["posting"] as? JsonObject ?: return null
        fun entries(key: String): String? = (posting[key] as? JsonArray)?.map { e ->
            val pair = e.arr()?.takeIf { it.size == 2 } ?: return null
            "${(pair[0] as? JsonPrimitive)?.content}=${(pair[1] as? JsonPrimitive)?.content}"
        }?.sorted()?.joinToString(",")
        val keys = entries("key_auths") ?: return null
        val accounts = entries("account_auths") ?: return null
        val memo = (o["memo_key"] as? JsonPrimitive)?.content ?: return null
        return "t=${(posting["weight_threshold"] as? JsonPrimitive)?.content};k=$keys;a=$accounts;m=$memo"
    }

    val READS_DISAGREE: String get() = L.native.postingGrant.readsDisagree

    /**
     * The operation for [account] (one row of `condenser_api.get_accounts`, read just now), or null when
     * [granted] already has posting authority. [PostingGrantRefused] when the row is not a complete account.
     */
    @Throws(PostingGrantRefused::class)
    fun build(
        username: String,
        account: JsonElement?,
        granted: String = GRANTED_ACCOUNT,
        /** A key account's own posting public key ("STM..."): the row must list it, or it is not this account's authority. */
        ownPostingKey: String? = null,
        /** The posting authority another read of the account gave (keys, account names): the row must agree with it. */
        expectedKeys: Collection<String>? = null,
        expectedAccounts: Collection<String>? = null,
    ): AccountUpdatePosting? {
        fun refuse(): Nothing = throw PostingGrantRefused(UNREADABLE)
        val o = account.obj() ?: refuse()
        fun string(e: JsonElement?) = (e as? JsonPrimitive)?.takeIf { it.isString }?.content
        fun number(e: JsonElement?) = (e as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()
        if (string(o["name"]) != username) refuse()
        val posting = o["posting"] as? JsonObject ?: refuse()
        // only the three fields hived defines: anything else means this is not the object we think it is
        if (posting.keys != setOf("weight_threshold", "account_auths", "key_auths")) refuse()
        val threshold = number(posting["weight_threshold"])?.takeIf { it in 1..0xFFFFFFFFL } ?: refuse()
        fun entries(key: String): List<AuthorityEntry> = (posting[key] as? JsonArray ?: refuse()).map { e ->
            val pair = e.arr()?.takeIf { it.size == 2 } ?: refuse()
            val weight = number(pair[1])?.takeIf { it in 0..65535 }?.toInt() ?: refuse()
            // valid on chain, but not something this app rewrites: say what it is instead of "could not read"
            if (weight == 0) throw PostingGrantRefused("Your posting authority has an entry with weight 0, which this app does not rewrite. Nothing was changed.")
            AuthorityEntry(string(pair[0])?.takeIf { it.isNotEmpty() } ?: refuse(), weight)
        }
        val accounts = entries("account_auths")
        val keys = entries("key_auths")
        // an account always has an owner and an active authority and a memo key: a row without them was cut down
        for (role in listOf("owner", "active")) {
            val a = o[role] as? JsonObject ?: refuse()
            if ((a["key_auths"] as? JsonArray).isNullOrEmpty() && (a["account_auths"] as? JsonArray).isNullOrEmpty()) refuse()
        }
        val memo = string(o["memo_key"]) ?: refuse()
        val metadata = string(o["json_metadata"]) ?: refuse()
        try {
            keys.forEach { PublicKey.fromString(it.name) }
            PublicKey.fromString(memo)
        } catch (_: IllegalArgumentException) {
            refuse()
        }
        if (keys.isEmpty() && accounts.isEmpty()) refuse()
        if (keys.map { it.name }.distinct().size != keys.size || accounts.map { it.name }.distinct().size != accounts.size) refuse()
        // the authority must be satisfiable as it stands, or the read is not trustworthy
        if ((keys + accounts).sumOf { it.weight.toLong() } < threshold) refuse()
        // the row must be this account's authority as the app knows it from elsewhere
        if (ownPostingKey != null && keys.none { it.name == ownPostingKey }) {
            throw PostingGrantRefused("The posting authority a node returned does not contain your own posting key. Nothing was changed; please try again.")
        }
        if ((expectedKeys != null && expectedKeys.toSet() != keys.map { it.name }.toSet()) || (expectedAccounts != null && expectedAccounts.toSet() != accounts.map { it.name }.toSet())) {
            throw PostingGrantRefused(READS_DISAGREE)
        }
        if (accounts.any { it.name == granted }) return null
        if (threshold > 65535) throw PostingGrantRefused("Your posting authority's threshold is too high for this app to add an account. Nothing was changed.")
        if (keys.size + accounts.size + 1 > MAX_AUTHORITY_MEMBERS) throw PostingGrantRefused("Your posting authority has no room for another account.")
        // SDK: push [granted, weightThreshold], then sort the account auths by name
        val withGrant = (accounts + AuthorityEntry(granted, threshold.toInt())).sortedBy { it.name }
        // keys in hived's own order (by key bytes), so the JSON sent and the bytes signed agree whatever order a node returned
        return AccountUpdatePosting(username, Authority(threshold, withGrant, Authority.sortKeyEntries(keys)), memo, metadata)
    }
}

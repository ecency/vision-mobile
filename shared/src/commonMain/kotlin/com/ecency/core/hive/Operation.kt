package com.ecency.core.hive

/** A Hive operation. Ids and field order follow hived's operation variant. */
sealed interface Operation {
    fun write(writer: HiveWriter)
}

data class Vote(
    val voter: String,
    val author: String,
    val permlink: String,
    /** -10000..10000, in basis points. */
    val weight: Int,
) : Operation {
    init {
        require(weight in -10000..10000) { "vote weight out of range: $weight" }
    }

    override fun write(writer: HiveWriter) = with(writer) {
        varint32(0)
        string(voter)
        string(author)
        string(permlink)
        i16(weight)
    }
}

data class Comment(
    val parentAuthor: String,
    val parentPermlink: String,
    val author: String,
    val permlink: String,
    val title: String,
    val body: String,
    val jsonMetadata: String,
) : Operation {
    override fun write(writer: HiveWriter) = with(writer) {
        varint32(1)
        string(parentAuthor)
        string(parentPermlink)
        string(author)
        string(permlink)
        string(title)
        string(body)
        string(jsonMetadata)
    }
}

data class Transfer(
    val from: String,
    val to: String,
    val amount: Asset,
    val memo: String,
) : Operation {
    init {
        require(amount.symbol == "HIVE" || amount.symbol == "HBD") { "transfer must be HIVE or HBD" }
        require(amount.units > 0) { "transfer amount must be positive" }
    }

    override fun write(writer: HiveWriter) = with(writer) {
        varint32(2)
        string(from)
        string(to)
        amount.write(this)
        string(memo)
    }
}

data class CustomJson(
    val requiredAuths: List<String>,
    val requiredPostingAuths: List<String>,
    val id: String,
    val json: String,
) : Operation {
    override fun write(writer: HiveWriter) = with(writer) {
        varint32(18)
        stringArray(requiredAuths)
        stringArray(requiredPostingAuths)
        string(id)
        string(json)
    }
}

/** Deletes a post or comment that has no replies and no positive votes (hived refuses otherwise). */
data class DeleteComment(
    val author: String,
    val permlink: String,
) : Operation {
    override fun write(writer: HiveWriter) = with(writer) {
        varint32(17)
        string(author)
        string(permlink)
    }
}

data class Beneficiary(val account: String, val weight: Int) {
    init {
        require(weight in 1..10000) { "beneficiary weight out of range: $weight" }
    }
}

data class CommentOptions(
    val author: String,
    val permlink: String,
    val maxAcceptedPayout: Asset,
    val percentHbd: Int,
    val allowVotes: Boolean,
    val allowCurationRewards: Boolean,
    val beneficiaries: List<Beneficiary> = emptyList(),
) : Operation {
    init {
        require(percentHbd in 0..10000) { "percent_hbd out of range: $percentHbd" }
        // hived rejects beneficiaries that are not sorted by account name, duplicated, or over 100%.
        require(beneficiaries.zipWithNext().all { (a, b) -> a.account < b.account }) {
            "beneficiaries must be sorted by account and unique"
        }
        require(beneficiaries.sumOf { it.weight } <= 10000) { "beneficiary weights exceed 100%" }
    }

    override fun write(writer: HiveWriter) = with(writer) {
        varint32(19)
        string(author)
        string(permlink)
        maxAcceptedPayout.write(this)
        u16(percentHbd)
        bool(allowVotes)
        bool(allowCurationRewards)
        if (beneficiaries.isEmpty()) {
            varint32(0)
        } else {
            varint32(1) // one extension
            varint32(0) // comment_payout_beneficiaries
            varint32(beneficiaries.size)
            beneficiaries.forEach {
                string(it.account)
                u16(it.weight)
            }
        }
    }
}

/**
 * `account_update2` that only writes metadata: owner, active, posting and memo_key stay unset (no key
 * change). Used by the profile edit and by pin / unpin to blog; operations that change keys are not built
 * by the app.
 */
data class AccountUpdate2(
    val account: String,
    val jsonMetadata: String,
    val postingJsonMetadata: String,
) : Operation {
    override fun write(writer: HiveWriter) = with(writer) {
        varint32(43)
        string(account)
        u8(0) // owner: optional<authority>, absent
        u8(0) // active
        u8(0) // posting
        u8(0) // memo_key: optional<public_key>
        string(jsonMetadata)
        string(postingJsonMetadata)
        varint32(0) // extensions
    }
}

/** One entry of an authority: an account or a public key (as "STM...") with its weight. */
data class AuthorityEntry(val name: String, val weight: Int) {
    init {
        require(weight in 0..65535) { "authority weight out of range: $weight" }
    }
}

/**
 * A Hive authority. hived keeps both lists as sorted maps (accounts by name, keys by their 33 compressed bytes) and
 * signs over that order, so [write] always serializes them sorted, whatever order they were given in.
 */
data class Authority(val weightThreshold: Long, val accountAuths: List<AuthorityEntry>, val keyAuths: List<AuthorityEntry>) {
    init {
        require(weightThreshold in 0..0xFFFFFFFFL) { "weight threshold out of range" }
    }

    internal fun write(writer: HiveWriter) = with(writer) {
        u32(weightThreshold)
        varint32(accountAuths.size)
        accountAuths.sortedBy { it.name }.forEach {
            string(it.name)
            u16(it.weight)
        }
        varint32(keyAuths.size)
        sortedKeys(keyAuths).forEach { (key, weight) ->
            bytes(key)
            u16(weight)
        }
    }

    companion object {
        /** [keys] with their compressed bytes, in hived's order: unsigned byte by byte. */
        fun sortedKeys(keys: List<AuthorityEntry>): List<Pair<ByteArray, Int>> =
            keys.map { PublicKey.fromString(it.name).compressed to it.weight }.sortedWith { a, b ->
                var i = 0
                var result = 0
                while (i < 33 && result == 0) {
                    result = (a.first[i].toInt() and 0xff) - (b.first[i].toInt() and 0xff)
                    i++
                }
                result
            }

        /** [keys] in hived's order. */
        fun sortKeyEntries(keys: List<AuthorityEntry>): List<AuthorityEntry> {
            val order = sortedKeys(keys).map { PublicKey(it.first).toString() }
            return keys.sortedBy { order.indexOf(it.name) }
        }
    }
}

/**
 * `account_update` that replaces the posting authority only: owner and active stay unset. The app builds exactly
 * one of these, the grant of posting authority to ecency.app for scheduled posts
 * ([com.ecency.core.api.broadcast.PostingGrant]); it needs the active key.
 */
data class AccountUpdatePosting(
    val account: String,
    val posting: Authority,
    /** The account's memo key, unchanged ("STM..."). */
    val memoKey: String,
    /** The account's json_metadata, unchanged. */
    val jsonMetadata: String,
) : Operation {
    override fun write(writer: HiveWriter) = with(writer) {
        varint32(10)
        string(account)
        u8(0) // owner: optional<authority>, absent
        u8(0) // active
        u8(1) // posting
        posting.write(this)
        bytes(PublicKey.fromString(memoKey).compressed)
        string(jsonMetadata)
    }
}

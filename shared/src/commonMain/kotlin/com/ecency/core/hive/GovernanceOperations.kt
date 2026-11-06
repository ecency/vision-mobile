package com.ecency.core.hive

// Governance operations (proposal votes, witness votes, the governance proxy). Ids and field order follow hived's
// operation variant (dhive serializer.js); PlatformGoldenVectors pins the bytes to dhive and the ids to @ecency/sdk.

/** Vote for ([approve]) or withdraw the vote from proposals ([proposalIds], ascending and distinct as hived wants). */
data class UpdateProposalVotes(val voter: String, val proposalIds: List<Long>, val approve: Boolean) : Operation {
    init {
        require(proposalIds.isNotEmpty()) { "no proposal" }
        require(proposalIds.size <= MAX_IDS) { "too many proposals" }
        require(proposalIds.all { it >= 0 }) { "proposal ids are not negative" }
        require(proposalIds.zipWithNext().all { (a, b) -> a < b }) { "proposal ids must be sorted and distinct" }
    }

    override fun write(writer: HiveWriter) = with(writer) {
        varint32(45)
        string(voter)
        varint32(proposalIds.size)
        proposalIds.forEach { i64(it) }
        bool(approve)
        varint32(0) // extensions
    }

    companion object {
        /** hived's HIVE_PROPOSAL_MAX_IDS_NUMBER. */
        const val MAX_IDS = 5
    }
}

/** Vote for ([approve]) or unvote a witness. */
data class AccountWitnessVote(val account: String, val witness: String, val approve: Boolean) : Operation {
    override fun write(writer: HiveWriter) = with(writer) {
        varint32(12)
        string(account)
        string(witness)
        bool(approve)
    }
}

/** Set the governance proxy; an empty [proxy] clears it. */
data class AccountWitnessProxy(val account: String, val proxy: String) : Operation {
    override fun write(writer: HiveWriter) = with(writer) {
        varint32(13)
        string(account)
        string(proxy)
    }
}

private fun requireDailyPay(dailyPay: Asset) {
    require(dailyPay.symbol == "HBD") { "daily_pay must be HBD" }
    require(dailyPay.units >= 0) { "daily_pay must not be negative" }
}

/**
 * A DHF proposal paying [receiver] [dailyPay] (HBD) a day from [startDate] to [endDate] (chain time). The app never
 * builds one; hive:// links can ask for it (#3635).
 */
data class CreateProposal(
    val creator: String,
    val receiver: String,
    val startDate: String,
    val endDate: String,
    val dailyPay: Asset,
    val subject: String,
    val permlink: String,
) : Operation {
    init {
        requireDailyPay(dailyPay)
        require(ChainTime.toEpochSeconds(endDate) > ChainTime.toEpochSeconds(startDate)) { "a proposal ends after it starts" }
    }

    override fun write(writer: HiveWriter) = with(writer) {
        varint32(44)
        string(creator)
        string(receiver)
        u32(ChainTime.toEpochSeconds(startDate))
        u32(ChainTime.toEpochSeconds(endDate))
        dailyPay.write(this)
        string(subject)
        string(permlink)
        varint32(0) // extensions
    }
}

/**
 * Lowers a proposal's [dailyPay], changes its subject or post, and with [endDate] moves its end earlier (hived's one
 * extension, update_proposal_end_date). From hive:// links only (#3635).
 */
data class UpdateProposal(
    val proposalId: Long,
    val creator: String,
    val dailyPay: Asset,
    val subject: String,
    val permlink: String,
    val endDate: String?,
) : Operation {
    init {
        require(proposalId >= 0) { "proposal id is not negative" }
        requireDailyPay(dailyPay)
        endDate?.let(ChainTime::toEpochSeconds)
    }

    override fun write(writer: HiveWriter) = with(writer) {
        varint32(47)
        i64(proposalId)
        string(creator)
        dailyPay.write(this)
        string(subject)
        string(permlink)
        if (endDate == null) {
            varint32(0) // extensions
        } else {
            varint32(1) // one extension
            varint32(1) // update_proposal_end_date (0 is void_t)
            u32(ChainTime.toEpochSeconds(endDate))
        }
    }
}

/** Removes [owner]'s proposals [proposalIds] (ascending and distinct: hived signs its flat_set sorted). From links only (#3635). */
data class RemoveProposal(val owner: String, val proposalIds: List<Long>) : Operation {
    init {
        require(proposalIds.isNotEmpty()) { "no proposal" }
        require(proposalIds.size <= UpdateProposalVotes.MAX_IDS) { "too many proposals" }
        require(proposalIds.all { it >= 0 }) { "proposal ids are not negative" }
        require(proposalIds.zipWithNext().all { (a, b) -> a < b }) { "proposal ids must be sorted and distinct" }
    }

    override fun write(writer: HiveWriter) = with(writer) {
        varint32(46)
        string(owner)
        varint32(proposalIds.size)
        proposalIds.forEach { i64(it) }
        varint32(0) // extensions
    }
}

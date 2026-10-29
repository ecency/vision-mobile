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

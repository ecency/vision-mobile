package com.ecency.core.api

import com.ecency.core.api.EcencyRestApi.Companion.double
import com.ecency.core.api.EcencyRestApi.Companion.int
import com.ecency.core.api.EcencyRestApi.Companion.list
import com.ecency.core.api.EcencyRestApi.Companion.string
import com.ecency.core.api.broadcast.PostActionOps
import com.ecency.core.api.model.obj
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** A poll as its post's json_metadata declares it (RN PollMetadata; detection as postPoll.tsx). */
data class PostPoll(
    val question: String,
    val choices: List<String>,
    /** Unix seconds; 0 when absent. */
    val endTime: Long,
    val maxChoicesVoted: Int,
    val accountAgeDays: Int,
    /** null when the metadata does not say (RN treats only an explicit false as "no changes"). */
    val allowVoteChanges: Boolean?,
    val hideResultsUntilVoted: Boolean,
    val hideVotes: Boolean,
    val preferredInterpretation: String,
    val token: String?,
    val communityMembership: List<String>,
) {
    fun isExpired(nowMillis: Long): Boolean = endTime * 1000 < nowMillis

    companion object {
        /** `content_type == "poll"` with a question, else null. */
        fun fromMetadata(rawJson: String): PostPoll? {
            val o = PostActionOps.parseObject(rawJson)
            if ((o["content_type"] as? JsonPrimitive)?.contentOrNull != "poll") return null
            val question = (o["question"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() } ?: return null
            fun prim(k: String) = o[k] as? JsonPrimitive
            return PostPoll(
                question = question.trim(),
                choices = (o["choices"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
                endTime = prim("end_time")?.let { it.longOrNull ?: it.doubleOrNull?.toLong() } ?: 0,
                maxChoicesVoted = prim("max_choices_voted")?.intOrNull ?: 1,
                accountAgeDays = ((o["filters"] as? JsonObject)?.get("account_age") as? JsonPrimitive)?.intOrNull ?: 0,
                allowVoteChanges = prim("allow_vote_changes")?.booleanOrNull,
                hideResultsUntilVoted = prim("ui_hide_res_until_voted")?.booleanOrNull ?: false,
                hideVotes = prim("hide_votes")?.booleanOrNull ?: false,
                preferredInterpretation = prim("preferred_interpretation")?.contentOrNull ?: "number_of_votes",
                token = prim("token")?.contentOrNull,
                communityMembership = (o["community_membership"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
            )
        }
    }
}

data class PollChoice(val num: Int, val text: String, val totalVotes: Int, val hiveHp: Double)

data class PollVoter(val name: String, val choices: List<Int>)

/** The polls API row (SDK normalizer), the parts the app shows. */
data class PollResults(
    val trxId: String,
    val choices: List<PollChoice>,
    val voters: List<PollVoter>,
    val maxChoicesVoted: Int,
    val accountAgeDays: Int,
    val preferredInterpretation: String,
    val token: String?,
) {
    fun choicesOf(voter: String?): List<Int> = voters.firstOrNull { it.name == voter }?.choices.orEmpty()

    companion object {
        fun fromJson(o: JsonObject): PollResults = PollResults(
            trxId = o.string("poll_trx_id") ?: "",
            choices = o.list("poll_choices").mapNotNull { c ->
                val num = c.int("choice_num") ?: return@mapNotNull null
                val votes = c.obj()?.get("votes")
                PollChoice(num, c.string("choice_text") ?: "", votes.int("total_votes") ?: 0, votes.double("hive_hp") ?: 0.0)
            }.sortedBy { it.num },
            voters = o.list("poll_voters").mapNotNull { v ->
                val name = v.string("name") ?: return@mapNotNull null
                val list = v.list("choices").mapNotNull { (it as? JsonPrimitive)?.intOrNull }
                PollVoter(name, list.ifEmpty { listOfNotNull(v.int("choice_num")) })
            },
            maxChoicesVoted = o.int("max_choices_voted") ?: 1,
            accountAgeDays = o.int("filter_account_age_days") ?: 0,
            preferredInterpretation = o.string("preferred_interpretation") ?: "number_of_votes",
            token = o.string("token"),
        )
    }
}

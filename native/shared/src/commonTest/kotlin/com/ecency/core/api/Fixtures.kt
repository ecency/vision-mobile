package com.ecency.core.api

import kotlinx.serialization.json.Json

/**
 * Synthetic bridge entries, shaped like real `bridge.*` results (field names and types taken
 * from a live response) but with made-up accounts and text. No real user content.
 */
object Fixtures {
    fun entry(
        author: String,
        permlink: String,
        created: String = "2026-10-01T10:00:00",
        parent: Pair<String, String>? = null,
        depth: Int = if (parent == null) 0 else 1,
        replies: List<String> = emptyList(),
        children: Int = replies.size,
        netRshares: Long = 1000,
        pending: String = "0.000 HBD",
        reputation: Double = 50.0,
        votes: List<String> = emptyList(),
        metadata: String = """{"tags":["hive-100001","test"],"app":"ecency/4.0.0-mobile","format":"markdown+html"}""",
        pinned: Boolean = false,
    ): String {
        val parentFields = parent?.let { """"parent_author":"${it.first}","parent_permlink":"${it.second}",""" } ?: ""
        return """{
            "author":"$author","permlink":"$permlink",$parentFields
            "category":"hive-100001","title":"Title of $permlink","body":"Body of $permlink",
            "json_metadata":$metadata,
            "created":"$created","updated":"$created","depth":$depth,"children":$children,
            "net_rshares":$netRshares,"author_reputation":$reputation,"payout":0.0,"is_paidout":false,
            "payout_at":"2026-10-08T10:00:00","pending_payout_value":"$pending",
            "author_payout_value":"0.000 HBD","curator_payout_value":"0.000 HBD",
            "promoted":"0.000 HBD","max_accepted_payout":"1000000.000 HBD","percent_hbd":10000,
            "replies":${Json.encodeToString(replies)},
            "active_votes":[${votes.joinToString(",") { """{"voter":"$it","rshares":100}""" }}],
            "community":"hive-100001","community_title":"Test Community","author_role":"guest","author_title":"",
            "stats":{"hide":false,"gray":false,"total_votes":${votes.size},"flag_weight":0.0${if (pinned) ""","is_pinned":true""" else ""}},
            "reblogs":0,"beneficiaries":[],"blacklists":[],
            "url":"/hive-100001/@$author/$permlink"
        }"""
    }

    fun array(vararg entries: String) = entries.joinToString(",", "[", "]")
}

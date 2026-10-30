package com.ecency.core.api.broadcast

import com.ecency.core.hive.AccountUpdate2
import com.ecency.core.hive.Asset
import com.ecency.core.hive.Beneficiary
import com.ecency.core.hive.Comment
import com.ecency.core.hive.CommentOptions
import com.ecency.core.hive.CustomJson
import com.ecency.core.hive.DeleteComment
import com.ecency.core.hive.Operation
import com.ecency.core.hive.Transaction
import com.ecency.core.hive.Transfer
import com.ecency.core.hive.Vote
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Condenser (legacy) JSON for operations and signed transactions, the format
 * `condenser_api.broadcast_transaction` takes and @ecency/sdk sends: operations as
 * `[name, {fields}]`, assets as "1.000 HIVE" strings, signatures as hex.
 */
object OperationJson {
    fun encode(op: Operation): JsonArray = when (op) {
        is Vote -> pair("vote") {
            put("voter", op.voter)
            put("author", op.author)
            put("permlink", op.permlink)
            put("weight", op.weight)
        }
        is Comment -> pair("comment") {
            put("parent_author", op.parentAuthor)
            put("parent_permlink", op.parentPermlink)
            put("author", op.author)
            put("permlink", op.permlink)
            put("title", op.title)
            put("body", op.body)
            put("json_metadata", op.jsonMetadata)
        }
        is CommentOptions -> pair("comment_options") {
            put("author", op.author)
            put("permlink", op.permlink)
            put("max_accepted_payout", op.maxAcceptedPayout.toString())
            put("percent_hbd", op.percentHbd)
            put("allow_votes", op.allowVotes)
            put("allow_curation_rewards", op.allowCurationRewards)
            putJsonArray("extensions") {
                if (op.beneficiaries.isNotEmpty()) {
                    add(
                        buildJsonArray {
                            add(JsonPrimitive(0))
                            add(
                                buildJsonObject {
                                    putJsonArray("beneficiaries") {
                                        op.beneficiaries.forEach { b ->
                                            add(
                                                buildJsonObject {
                                                    put("account", b.account)
                                                    put("weight", b.weight)
                                                },
                                            )
                                        }
                                    }
                                },
                            )
                        },
                    )
                }
            }
        }
        is Transfer -> pair("transfer") {
            put("from", op.from)
            put("to", op.to)
            put("amount", op.amount.toString())
            put("memo", op.memo)
        }
        is DeleteComment -> pair("delete_comment") {
            put("author", op.author)
            put("permlink", op.permlink)
        }
        is CustomJson -> pair("custom_json") {
            putJsonArray("required_auths") { op.requiredAuths.forEach { add(JsonPrimitive(it)) } }
            putJsonArray("required_posting_auths") { op.requiredPostingAuths.forEach { add(JsonPrimitive(it)) } }
            put("id", op.id)
            put("json", op.json)
        }
        is com.ecency.core.hive.AccountUpdatePosting -> pair("account_update") {
            put("account", op.account)
            put(
                "posting",
                buildJsonObject {
                    // the key order of the chain's own authority object, which the SDK spreads
                    put("weight_threshold", op.posting.weightThreshold)
                    putJsonArray("account_auths") { op.posting.accountAuths.forEach { add(buildJsonArray { add(JsonPrimitive(it.name)); add(JsonPrimitive(it.weight)) }) } }
                    putJsonArray("key_auths") { op.posting.keyAuths.forEach { add(buildJsonArray { add(JsonPrimitive(it.name)); add(JsonPrimitive(it.weight)) }) } }
                },
            )
            put("memo_key", op.memoKey)
            put("json_metadata", op.jsonMetadata)
        }
        is com.ecency.core.hive.TransferToVesting -> pair("transfer_to_vesting") {
            put("from", op.from)
            put("to", op.to)
            put("amount", op.amount.toString())
        }
        is com.ecency.core.hive.WithdrawVesting -> pair("withdraw_vesting") {
            put("account", op.account)
            put("vesting_shares", op.vestingShares.toString())
        }
        is com.ecency.core.hive.LimitOrderCreate -> pair("limit_order_create") {
            put("owner", op.owner)
            put("orderid", op.orderId)
            put("amount_to_sell", op.amountToSell.toString())
            put("min_to_receive", op.minToReceive.toString())
            put("fill_or_kill", op.fillOrKill)
            put("expiration", op.expiration)
        }
        is com.ecency.core.hive.LimitOrderCancel -> pair("limit_order_cancel") {
            put("owner", op.owner)
            put("orderid", op.orderId)
        }
        is com.ecency.core.hive.Convert -> pair("convert") {
            put("owner", op.owner)
            put("amount", op.amount.toString())
            put("requestid", op.requestId)
        }
        is com.ecency.core.hive.CollateralizedConvert -> pair("collateralized_convert") {
            put("owner", op.owner)
            put("amount", op.amount.toString())
            put("requestid", op.requestId)
        }
        is com.ecency.core.hive.SetWithdrawVestingRoute -> pair("set_withdraw_vesting_route") {
            put("from_account", op.fromAccount)
            put("to_account", op.toAccount)
            put("percent", op.percent)
            put("auto_vest", op.autoVest)
        }
        is com.ecency.core.hive.TransferToSavings -> pair("transfer_to_savings") {
            put("from", op.from)
            put("to", op.to)
            put("amount", op.amount.toString())
            put("memo", op.memo)
        }
        is com.ecency.core.hive.TransferFromSavings -> pair("transfer_from_savings") {
            put("from", op.from)
            put("to", op.to)
            put("amount", op.amount.toString())
            put("memo", op.memo)
            put("request_id", op.requestId)
        }
        is com.ecency.core.hive.CancelTransferFromSavings -> pair("cancel_transfer_from_savings") {
            put("from", op.from)
            put("request_id", op.requestId)
        }
        is com.ecency.core.hive.ClaimRewardBalance -> pair("claim_reward_balance") {
            put("account", op.account)
            put("reward_hive", op.rewardHive.toString())
            put("reward_hbd", op.rewardHbd.toString())
            put("reward_vests", op.rewardVests.toString())
        }
        is com.ecency.core.hive.DelegateVestingShares -> pair("delegate_vesting_shares") {
            put("delegator", op.delegator)
            put("delegatee", op.delegatee)
            put("vesting_shares", op.vestingShares.toString())
        }
        is com.ecency.core.hive.RecurrentTransfer -> pair("recurrent_transfer") {
            put("from", op.from)
            put("to", op.to)
            put("amount", op.amount.toString())
            put("memo", op.memo)
            put("recurrence", op.recurrence)
            put("executions", op.executions)
            putJsonArray("extensions") {}
        }
        is com.ecency.core.hive.UpdateProposalVotes -> pair("update_proposal_votes") {
            put("voter", op.voter)
            putJsonArray("proposal_ids") { op.proposalIds.forEach { add(JsonPrimitive(it)) } }
            put("approve", op.approve)
            putJsonArray("extensions") {}
        }
        is com.ecency.core.hive.AccountWitnessVote -> pair("account_witness_vote") {
            put("account", op.account)
            put("witness", op.witness)
            put("approve", op.approve)
        }
        is com.ecency.core.hive.AccountWitnessProxy -> pair("account_witness_proxy") {
            put("account", op.account)
            put("proxy", op.proxy)
        }
        is AccountUpdate2 -> pair("account_update2") {
            put("account", op.account)
            put("json_metadata", op.jsonMetadata)
            put("posting_json_metadata", op.postingJsonMetadata)
            putJsonArray("extensions") {}
        }
    }

    /**
     * The reverse of [encode] for the operations the binary serializer supports (vote, comment,
     * comment_options, transfer, custom_json, delete_comment, metadata-only account_update2, and the wallet
     * operations of WalletOperations.kt). Throws [BroadcastException.InvalidOperation] for anything
     * else or for malformed fields, so a hive:// link with an operation the app cannot sign yet fails cleanly.
     */
    /**
     * As [decode], and also the one authority change the app makes itself: an `account_update` that sets the
     * posting authority only ([com.ecency.core.hive.AccountUpdatePosting]). Only [com.ecency.core.app.SigningModel]
     * uses it, for operations the app built; what a link asks to sign goes through [canDecode], which refuses it.
     */
    @Throws(BroadcastException::class)
    fun decodeOwn(op: JsonArray): Operation {
        val name = (op.getOrNull(0) as? JsonPrimitive)?.contentOrNull
        if (name != "account_update") return decode(op)
        val body = op.getOrNull(1) as? JsonObject ?: throw BroadcastException.InvalidOperation("Invalid operation data")
        fun bad(what: String): Nothing = throw BroadcastException.InvalidOperation("Invalid operation data: $what")
        if (listOf("owner", "active").any { body[it] != null && body[it] !is JsonNull }) throw BroadcastException.InvalidOperation("Signing account key changes is not supported in this version.")
        val posting = body["posting"] as? JsonObject ?: bad("posting")
        fun entries(k: String) = (posting[k] as? JsonArray ?: bad(k)).map { e ->
            val pair = e as? JsonArray ?: bad(k)
            com.ecency.core.hive.AuthorityEntry(
                (pair.getOrNull(0) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: bad(k),
                (pair.getOrNull(1) as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull() ?: bad(k),
            )
        }
        return try {
            com.ecency.core.hive.AccountUpdatePosting(
                (body["account"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: bad("account"),
                com.ecency.core.hive.Authority(
                    (posting["weight_threshold"] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull() ?: bad("weight_threshold"),
                    entries("account_auths"), entries("key_auths"),
                ),
                (body["memo_key"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: bad("memo_key"),
                (body["json_metadata"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: bad("json_metadata"),
            ).also { it.posting.keyAuths.forEach { k -> com.ecency.core.hive.PublicKey.fromString(k.name) }; com.ecency.core.hive.PublicKey.fromString(it.memoKey) }
        } catch (e: BroadcastException) {
            throw e
        } catch (e: IllegalArgumentException) {
            throw BroadcastException.InvalidOperation("Invalid operation data", e)
        }
    }

    @Throws(BroadcastException::class)
    fun decode(op: JsonArray): Operation {
        val name = (op.getOrNull(0) as? JsonPrimitive)?.contentOrNull
        val body = op.getOrNull(1) as? JsonObject ?: throw BroadcastException.InvalidOperation("Invalid operation data")
        fun str(k: String): String = (body[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw BroadcastException.InvalidOperation("Invalid operation data: $k")
        fun int(k: String): Int = (body[k] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
            ?: throw BroadcastException.InvalidOperation("Invalid operation data: $k")
        // a JSON number (never a string, never a fraction or exponent) in uint32 range: request ids and order ids
        fun u32(k: String): Long = (body[k] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()?.takeIf { it in 0..0xFFFFFFFFL }
            ?: throw BroadcastException.InvalidOperation("Invalid operation data: $k")
        // a JSON number, never a string ("2500" stays text, as hived refuses it): percents, recurrence, executions
        fun num(k: String): Int = (body[k] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull()
            ?: throw BroadcastException.InvalidOperation("Invalid operation data: $k")
        fun bool(k: String): Boolean = (body[k] as? JsonPrimitive)?.booleanOrNull
            ?: throw BroadcastException.InvalidOperation("Invalid operation data: $k")
        fun names(k: String): List<String> = (body[k] as? JsonArray ?: throw BroadcastException.InvalidOperation("Invalid operation data: $k"))
            .map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: throw BroadcastException.InvalidOperation("Invalid operation data: $k") }
        return try {
            when (name) {
                "vote" -> Vote(str("voter"), str("author"), str("permlink"), int("weight"))
                "comment" -> Comment(
                    str("parent_author"), str("parent_permlink"), str("author"), str("permlink"),
                    str("title"), str("body"), str("json_metadata"),
                )
                "transfer" -> Transfer(str("from"), str("to"), Asset.parse(str("amount")), str("memo"))
                "delete_comment" -> DeleteComment(str("author"), str("permlink"))
                "transfer_to_vesting" -> com.ecency.core.hive.TransferToVesting(str("from"), str("to"), Asset.parse(str("amount")))
                "withdraw_vesting" -> com.ecency.core.hive.WithdrawVesting(str("account"), Asset.parse(str("vesting_shares")))
                "limit_order_create" -> com.ecency.core.hive.LimitOrderCreate(
                    str("owner"), u32("orderid"), Asset.parse(str("amount_to_sell")), Asset.parse(str("min_to_receive")),
                    bool("fill_or_kill"), str("expiration"),
                )
                "limit_order_cancel" -> com.ecency.core.hive.LimitOrderCancel(str("owner"), u32("orderid"))
                "convert" -> com.ecency.core.hive.Convert(str("owner"), u32("requestid"), Asset.parse(str("amount")))
                "collateralized_convert" -> com.ecency.core.hive.CollateralizedConvert(str("owner"), u32("requestid"), Asset.parse(str("amount")))
                "set_withdraw_vesting_route" -> com.ecency.core.hive.SetWithdrawVestingRoute(str("from_account"), str("to_account"), num("percent"), bool("auto_vest"))
                "transfer_to_savings" -> com.ecency.core.hive.TransferToSavings(str("from"), str("to"), Asset.parse(str("amount")), str("memo"))
                "transfer_from_savings" -> com.ecency.core.hive.TransferFromSavings(str("from"), u32("request_id"), str("to"), Asset.parse(str("amount")), str("memo"))
                "cancel_transfer_from_savings" -> com.ecency.core.hive.CancelTransferFromSavings(str("from"), u32("request_id"))
                "claim_reward_balance" -> com.ecency.core.hive.ClaimRewardBalance(
                    str("account"), Asset.parse(str("reward_hive")), Asset.parse(str("reward_hbd")), Asset.parse(str("reward_vests")),
                )
                "delegate_vesting_shares" -> com.ecency.core.hive.DelegateVestingShares(str("delegator"), str("delegatee"), Asset.parse(str("vesting_shares")))
                "recurrent_transfer" -> {
                    // no extension (recurrent_transfer_pair_id) is serialized by the app
                    val extensions = body["extensions"]
                    if (extensions != null && (extensions !is JsonArray || extensions.isNotEmpty())) {
                        throw BroadcastException.InvalidOperation("Invalid operation data: extensions")
                    }
                    com.ecency.core.hive.RecurrentTransfer(str("from"), str("to"), Asset.parse(str("amount")), str("memo"), num("recurrence"), num("executions"))
                }
                "update_proposal_votes" -> {
                    noExtensions(body)
                    // JSON numbers only, ascending and distinct (hived keeps them in a flat_set, which it signs sorted)
                    val ids = (body["proposal_ids"] as? JsonArray ?: throw BroadcastException.InvalidOperation("Invalid operation data: proposal_ids"))
                        .map { (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.content?.toLongOrNull() ?: throw BroadcastException.InvalidOperation("Invalid operation data: proposal_ids") }
                    com.ecency.core.hive.UpdateProposalVotes(str("voter"), ids, bool("approve"))
                }
                "account_witness_vote" -> com.ecency.core.hive.AccountWitnessVote(str("account"), str("witness"), bool("approve"))
                "account_witness_proxy" -> com.ecency.core.hive.AccountWitnessProxy(str("account"), str("proxy"))
                "custom_json" -> CustomJson(names("required_auths"), names("required_posting_auths"), str("id"), str("json"))
                "account_update2" -> {
                    // only metadata updates; a key change (owner, active, posting, memo_key) is not signed by the app
                    if (listOf("owner", "active", "posting", "memo_key").any { body[it] != null && body[it] !is JsonNull }) {
                        throw BroadcastException.InvalidOperation("Signing account key changes is not supported in this version.")
                    }
                    val extensions = body["extensions"]
                    if (extensions != null && (extensions !is JsonArray || extensions.isNotEmpty())) {
                        throw BroadcastException.InvalidOperation("Invalid operation data: extensions")
                    }
                    AccountUpdate2(
                        str("account"),
                        (body["json_metadata"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "",
                        (body["posting_json_metadata"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "",
                    )
                }
                "comment_options" -> {
                    val extensions = body["extensions"] as? JsonArray ?: JsonArray(emptyList())
                    val beneficiaries = extensions.flatMap { ext ->
                        val pair = ext as? JsonArray ?: throw BroadcastException.InvalidOperation("Invalid operation data: extensions")
                        val list = ((pair.getOrNull(1) as? JsonObject)?.get("beneficiaries") as? JsonArray).orEmpty()
                        list.map { b ->
                            val o = b as? JsonObject ?: throw BroadcastException.InvalidOperation("Invalid operation data: beneficiaries")
                            Beneficiary(
                                (o["account"] as? JsonPrimitive)?.contentOrNull ?: throw BroadcastException.InvalidOperation("Invalid operation data: beneficiaries"),
                                (o["weight"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: throw BroadcastException.InvalidOperation("Invalid operation data: beneficiaries"),
                            )
                        }
                    }
                    CommentOptions(
                        str("author"), str("permlink"), Asset.parse(str("max_accepted_payout")), int("percent_hbd"),
                        bool("allow_votes"), bool("allow_curation_rewards"), beneficiaries,
                    )
                }
                else -> throw BroadcastException.InvalidOperation("Signing ${name ?: "this operation"} is not supported in this version yet.")
            }
        } catch (e: BroadcastException) {
            throw e
        } catch (e: IllegalArgumentException) {
            throw BroadcastException.InvalidOperation("Invalid operation data", e)
        }
    }

    /** An operation whose extensions must be absent or empty (the app serializes none). */
    private fun noExtensions(body: JsonObject) {
        val extensions = body["extensions"]
        if (extensions != null && (extensions !is JsonArray || extensions.isNotEmpty())) {
            throw BroadcastException.InvalidOperation("Invalid operation data: extensions")
        }
    }

    /** Whether [decode] can turn [op] into a binary operation (the app can sign it with a key). */
    fun canDecode(op: JsonArray): Boolean = try {
        decode(op)
        true
    } catch (_: BroadcastException) {
        false
    }

    /** A signed transaction, keys in the order hive-tx builds it. */
    fun signedTransaction(tx: Transaction, signatures: List<String>): JsonObject = buildJsonObject {
        put("expiration", tx.expiration)
        putJsonArray("extensions") {}
        putJsonArray("operations") { tx.operations.forEach { add(encode(it)) } }
        put("ref_block_num", tx.refBlockNum)
        put("ref_block_prefix", tx.refBlockPrefix)
        putJsonArray("signatures") { signatures.forEach { add(JsonPrimitive(it)) } }
    }

    private inline fun pair(name: String, fields: JsonObjectBuilder.() -> Unit) = buildJsonArray {
        add(JsonPrimitive(name))
        add(buildJsonObject(fields))
    }
}

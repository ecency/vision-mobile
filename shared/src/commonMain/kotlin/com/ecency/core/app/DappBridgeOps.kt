package com.ecency.core.app

import com.ecency.core.api.broadcast.OperationJson
import com.ecency.core.api.wallet.Vests
import com.ecency.core.auth.HiveUriOperations
import com.ecency.core.auth.OperationAuthority
import com.ecency.core.hive.KeyRole
import com.ecency.core.l10n.L
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** A bridge request the app answers with an error, never signing; [message] goes back to the page. */
class BridgeRefused(message: String) : Exception(message)

/**
 * RN useHiveBridgeHandler._buildOperations: the operations a Hive Keychain request ([type], [data]) asks [account]
 * to sign, as condenser JSON. Pinned to RN's own function over the SDK builders by PlatformGoldenVectors.
 *
 * Deliberate differences, all refusals instead of guesses: an amount must be plain decimal text or a JSON number
 * with no more decimals than its asset has (RN signs `parseFloat(x).toFixed(3)`, which rounds the binary double
 * and can sign something else than the page showed); a field that must be text or a number and is not is refused
 * (RN would sign "undefined"); "post" metadata that is not JSON is refused (RN throws, which answers the same).
 */
object BridgeOps {
    /** [requestId] makes the savings withdraw and convert ids (RN: seconds since the epoch plus a random 0..999). */
    fun build(type: String, data: JsonObject, account: String, hivePerMVests: () -> Double, requestId: () -> Long): JsonArray = when (type) {
        "vote" -> ops(
            op("vote") {
                put("voter", account)
                put("author", text(data, "author"))
                put("permlink", text(data, "permlink"))
                put("weight", int(data, "weight").also { if (it !in -10000..10000) throw BridgeRefused("Invalid vote weight.") })
            },
        )
        "post" -> {
            val permlink = text(data, "permlink")
            val meta = when (val m = data["json_metadata"]) {
                null, JsonNull -> JsonObject(emptyMap())
                is JsonPrimitive -> if (m.isString) parseJson(m.content, "json_metadata") else throw BridgeRefused("Invalid json_metadata.")
                else -> m
            }
            val comment = op("comment") {
                put("parent_author", optText(data, "parent_username"))
                put("parent_permlink", optText(data, "parent_perm"))
                put("author", account)
                put("permlink", permlink)
                put("title", optText(data, "title"))
                put("body", optText(data, "body"))
                put("json_metadata", meta.toString())
            }
            val options = when (val o = data["comment_options"]) {
                null, JsonNull -> null
                is JsonPrimitive -> if (o.isString) {
                    if (o.content.isEmpty()) null else parseJson(o.content, "comment_options") as? JsonObject ?: throw BridgeRefused("Invalid comment_options.")
                } else {
                    throw BridgeRefused("Invalid comment_options.")
                }
                is JsonObject -> o
                else -> throw BridgeRefused("Invalid comment_options.")
            }
            if (options == null) {
                ops(comment)
            } else {
                ops(
                    comment,
                    op("comment_options") {
                        put("author", account)
                        put("permlink", permlink)
                        put("max_accepted_payout", (options["max_accepted_payout"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() } ?: "1000000.000 HBD")
                        // RN: opts.percent_hbd ?? opts.percent_steem_dollars ?? 10000
                        val percent = listOf("percent_hbd", "percent_steem_dollars").firstNotNullOfOrNull { k -> options[k]?.takeIf { it != JsonNull } }
                        put("percent_hbd", percent?.let { (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.contentOrNull?.toIntOrNull() ?: throw BridgeRefused("Invalid percent_hbd.") } ?: 10000)
                        // RN `!== false`: only a JSON false turns them off
                        put("allow_votes", !isFalse(options["allow_votes"]))
                        put("allow_curation_rewards", !isFalse(options["allow_curation_rewards"]))
                        put("extensions", options["extensions"]?.takeIf { it != JsonNull } ?: JsonArray(emptyList()))
                    },
                )
            }
        }
        "custom" -> {
            val json = when (val j = data["json"]) {
                is JsonPrimitive -> if (j.isString) j.content else j.toString()
                null, JsonNull -> throw BridgeRefused("Missing json.")
                else -> j.toString()
            }
            val active = (data["method"] as? JsonPrimitive)?.contentOrNull?.lowercase() == "active"
            ops(
                op("custom_json") {
                    putJsonArray("required_auths") { if (active) add(JsonPrimitive(account)) }
                    putJsonArray("required_posting_auths") { if (!active) add(JsonPrimitive(account)) }
                    put("id", text(data, "id"))
                    put("json", json)
                },
            )
        }
        "transfer" -> ops(
            op("transfer") {
                put("from", account)
                put("to", text(data, "to"))
                put("amount", asset(data, "amount", liquid(data)))
                put("memo", optText(data, "memo"))
            },
        )
        "delegation" -> {
            val unit = ((data["unit"] as? JsonPrimitive)?.contentOrNull ?: "HP").uppercase()
            val vests = if (unit == "HP") {
                val rate = hivePerMVests()
                if (rate <= 0) throw BridgeRefused("Failed to build operations for \"delegation\".")
                Num.fixed(Vests.fromHp(decimal(data, "amount", 3).toDouble(), rate), 6)
            } else {
                decimal(data, "amount", 6)
            }
            ops(
                op("delegate_vesting_shares") {
                    put("delegator", account)
                    put("delegatee", text(data, "delegatee"))
                    put("vesting_shares", "$vests VESTS")
                },
            )
        }
        "witnessVote" -> ops(
            op("account_witness_vote") {
                put("account", account)
                put("witness", text(data, "witness"))
                put("approve", (data["vote"] as? JsonPrimitive)?.booleanOrNull ?: throw BridgeRefused("Invalid vote."))
            },
        )
        "proxy" -> ops(
            op("account_witness_proxy") {
                put("account", account)
                put("proxy", optText(data, "proxy"))
            },
        )
        "powerUp" -> ops(
            op("transfer_to_vesting") {
                put("from", account)
                put("to", optText(data, "recipient").ifEmpty { account })
                put("amount", "${decimal(data, "steem", 3)} HIVE")
            },
        )
        "powerDown" -> {
            val rate = hivePerMVests()
            if (rate <= 0) throw BridgeRefused("Failed to build operations for \"powerDown\".")
            val vests = Num.fixed(Vests.fromHp(decimal(data, "steem_power", 3).toDouble(), rate), 6)
            ops(
                op("withdraw_vesting") {
                    put("account", account)
                    put("vesting_shares", "$vests VESTS")
                },
            )
        }
        "recurrentTransfer" -> ops(
            op("recurrent_transfer") {
                put("from", account)
                put("to", text(data, "to"))
                put("amount", asset(data, "amount", liquid(data)))
                put("memo", optText(data, "memo"))
                put("recurrence", int(data, "recurrence"))
                put("executions", int(data, "executions"))
                putJsonArray("extensions") {}
            },
        )
        "savings" -> when ((data["operation"] as? JsonPrimitive)?.contentOrNull) {
            "deposit" -> ops(
                op("transfer_to_savings") {
                    put("from", account)
                    put("to", optText(data, "to").ifEmpty { account })
                    put("amount", asset(data, "amount", liquid(data)))
                    put("memo", optText(data, "memo"))
                },
            )
            "withdraw_cancel" -> ops(
                op("cancel_transfer_from_savings") {
                    put("from", account)
                    put("request_id", (data["request_id"] as? JsonPrimitive)?.takeIf { !it.isString }?.contentOrNull?.toLongOrNull()?.takeIf { it in 0..0xFFFFFFFFL } ?: 0L)
                },
            )
            "withdraw" -> ops(
                op("transfer_from_savings") {
                    put("from", account)
                    put("to", optText(data, "to").ifEmpty { account })
                    put("amount", asset(data, "amount", liquid(data)))
                    put("memo", optText(data, "memo"))
                    put("request_id", requestId())
                },
            )
            else -> throw BridgeRefused("Failed to build operations for \"savings\".")
        }
        "convert" -> {
            val collateralized = (data["collaterized"] as? JsonPrimitive)?.let { it.booleanOrNull ?: (it.contentOrNull?.isNotEmpty() == true && it.contentOrNull != "0") } == true
            ops(
                op(if (collateralized) "collateralized_convert" else "convert") {
                    put("owner", account)
                    put("amount", "${decimal(data, "amount", 3)} ${if (collateralized) "HIVE" else "HBD"}")
                    put("requestid", requestId())
                },
            )
        }
        "sendToken" -> ops(
            // SDK buildEngineOp: active custom_json on ssc-mainnet-hive, the quantity as the page sent it
            op("custom_json") {
                put("id", "ssc-mainnet-hive")
                putJsonArray("required_auths") { add(JsonPrimitive(account)) }
                putJsonArray("required_posting_auths") {}
                put(
                    "json",
                    buildJsonObject {
                        put("contractName", "tokens")
                        put("contractAction", "transfer")
                        put(
                            "contractPayload",
                            buildJsonObject {
                                put("symbol", text(data, "currency"))
                                put("to", text(data, "to"))
                                put("quantity", (data["amount"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() } ?: throw BridgeRefused("Invalid amount."))
                                put("memo", optText(data, "memo"))
                            },
                        )
                    }.toString(),
                )
            },
        )
        "broadcast" -> (data["operations"] as? JsonArray)?.also { list ->
            if (list.isEmpty() || list.any { op -> op !is JsonArray || op.size != 2 || (op[0] as? JsonPrimitive)?.isString != true || op[1] !is JsonObject }) {
                throw BridgeRefused("Failed to build operations for \"broadcast\".")
            }
        } ?: throw BridgeRefused("Failed to build operations for \"broadcast\".")
        else -> throw BridgeRefused("Failed to build operations for \"$type\".")
    }

    private fun isFalse(e: JsonElement?) = e is JsonPrimitive && !e.isString && e.content == "false"

    /** RN: currency as sent; only HIVE and HBD are transfers on the chain. */
    private fun liquid(data: JsonObject): String {
        val c = (data["currency"] as? JsonPrimitive)?.contentOrNull
        return if (c == "HIVE" || c == "HBD") c else throw BridgeRefused("Invalid currency.")
    }

    private fun asset(data: JsonObject, key: String, symbol: String) = "${decimal(data, key, 3)} $symbol"

    // ASCII digits only (an app's localized "١٫٥" is refused, not signed)
    private val DECIMAL = Regex("^([0-9]{1,15})(?:\\.([0-9]+))?$")

    /** Plain decimal text or a JSON number, at most [decimals] decimals, padded to exactly [decimals]. */
    internal fun decimal(data: JsonObject, key: String, decimals: Int): String {
        val raw = (data[key] as? JsonPrimitive)?.contentOrNull?.trim() ?: throw BridgeRefused("Invalid amount.")
        val m = DECIMAL.matchEntire(raw) ?: throw BridgeRefused("Invalid amount.")
        val frac = m.groupValues[2]
        if (frac.length > decimals) throw BridgeRefused("Invalid amount: at most $decimals decimals.")
        val whole = m.groupValues[1].trimStart('0').ifEmpty { "0" }
        return if (decimals == 0) whole else "$whole.${frac.padEnd(decimals, '0')}"
    }

    private fun text(data: JsonObject, key: String): String =
        (data[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw BridgeRefused("Missing $key.")

    private fun optText(data: JsonObject, key: String): String = when (val v = data[key]) {
        null, JsonNull -> ""
        is JsonPrimitive -> if (v.isString) v.content else throw BridgeRefused("Invalid $key.")
        else -> throw BridgeRefused("Invalid $key.")
    }

    private fun int(data: JsonObject, key: String): Int =
        (data[key] as? JsonPrimitive)?.takeIf { !it.isString }?.contentOrNull?.toIntOrNull() ?: throw BridgeRefused("Invalid $key.")

    private fun parseJson(text: String, what: String): JsonElement = try {
        Json.parseToJsonElement(text)
    } catch (_: Exception) {
        throw BridgeRefused("Invalid $what.")
    }

    private fun op(name: String, body: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonArray =
        buildJsonArray {
            add(JsonPrimitive(name))
            add(buildJsonObject(body))
        }

    private fun ops(vararg list: JsonArray) = JsonArray(list.toList())
}

/** What the bridge refuses to sign, whatever the page asks, and what it can sign at all. */
object BridgeGuards {
    /** Operations that change who controls the account, or that only the owner key signs. */
    private val ACCOUNT_CONTROL = setOf(
        "account_update", "change_recovery_account", "request_account_recovery", "recover_account", "reset_account",
        "set_reset_account", "decline_voting_rights", "account_create", "account_create_with_delegation",
        "create_claimed_account", "claim_account",
    )

    /**
     * Why [ops] are refused before any prompt, or null when they may be confirmed: owner authority, key or authority
     * changes (account_update, account_update2 with owner, active, posting or memo_key; the link flow's rule plus the
     * memo key), account creation and recovery; and, for an account that signs on this device ([signsHere]), an
     * operation the app cannot serialize yet. More than [MAX_OPS] operations are refused too.
     */
    fun refusal(ops: JsonArray, signsHere: Boolean): String? {
        if (ops.isEmpty()) return NO_VALID_OPS
        if (ops.size > MAX_OPS) return TOO_MANY_OPS
        for (element in ops) {
            // [name, {body}] with the name as JSON text: hived also takes an operation's number or its
            // "<name>_operation" spelling, which would show as something else on the confirmation
            val op = element as? JsonArray ?: return NO_VALID_OPS
            val name = (op.getOrNull(0) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return NO_VALID_OPS
            if (op.size != 2 || op[1] !is JsonObject) return NO_VALID_OPS
            // whatever the account signs with: an operation without a known authority is never shown as posting
            if (!OperationAuthority.isKnown(name)) return NOT_SUPPORTED
            if (OperationAuthority.forHiveUri(op) == KeyRole.OWNER) return OWNER_REFUSED
            if (OperationAuthority.isMixedCustomJson(op)) return OperationAuthority.MIXED_AUTHORITIES
            if (name in ACCOUNT_CONTROL) return KEYS_REFUSED
            if (name == "account_update2") {
                val body = op.getOrNull(1) as? JsonObject
                if (listOf("owner", "active", "posting", "memo_key").any { body?.get(it).let { v -> v != null && v != JsonNull } }) return KEYS_REFUSED
            }
            if (signsHere && !OperationJson.canDecode(op)) return NOT_SUPPORTED
        }
        if (OperationAuthority.required(ops) == KeyRole.OWNER) return OWNER_REFUSED
        // posting and active operations in one request: one key cannot sign both since HF28
        OperationAuthority.unsupported(ops)?.let { return it }
        return null
    }

    /** One request is one confirmation: a page cannot hide dozens of operations behind it. */
    const val MAX_OPS = 10

    // Sent to the page in the Keychain answer, like every other bridge refusal: English, never localized.
    const val NO_VALID_OPS = "No valid operations found."
    const val TOO_MANY_OPS = "Too many operations in one request."
    const val OWNER_REFUSED = "Operations that need the owner key cannot be signed in Ecency."
    const val KEYS_REFUSED = "Ecency does not sign changes to account keys or authorities from a website."
    const val NOT_SUPPORTED = "Ecency cannot sign this operation in this version yet."
}

/** RN dappBrowser/utils/domainBlacklist: known Hive phishing domains, warned about on the confirmation. */
object DappBlacklist {
    private val DOMAINS = listOf(
        "leofinance-mainnet.blogspot.com", "leodefi.blogspot.com", "leodefii.blogspot.com", "defiairdrop.ml",
        "leomainet.blogspot.com", "appicslaunch.blogspot.com", "softlaunch.appics.com", "honulover.club",
        "horia69xl.monster", "leofinance-steem.web.app", "leofinance-witness.web.app", "witness-vote.web.app",
        "witness-voting.pqr.app", "security-alert-portal.web.app", "6854712547.site", "lokin69xtr.club",
        "moris69bukail.cyou", "hisksexyl.monster", "localsexyladies1.com", "por69sex.buzz", "gosex69.work",
        "atomichub.in", "5486-5487.live", "love69date.top", "jharodatex.icu", "promislove.monster",
        "jhigolove69.click", "jhindatex.cyou", "54896-78481.live", "harnightx.icu", "jmrlove69x.cyou",
        "jaro69fuck.top", "54897-78457.live", "peypaisecurity.com", "fisranilove.lol", "loyalty.reward-hive.blog",
    )
    private val SET = DOMAINS.toSet()

    /** RN checkDomainBlacklist on a host: the listed domain it is (www. stripped) or is under, else null. */
    fun check(host: String): String? {
        val h = host.lowercase().removePrefix("www.")
        if (h in SET) return h
        return DOMAINS.firstOrNull { h.endsWith(".$it") }
    }
}

/** RN bridgeTypes OPERATION_LABELS. */
object BridgeLabels {
    private val LABELS: Map<String, () -> String> = mapOf(
        "vote" to { L.wallet.vote }, "post" to { L.native.dappBridgeOps.post }, "custom" to { L.native.dappBridgeOps.custom }, "signBuffer" to { L.native.dappBridgeOps.signBuffer },
        "transfer" to { L.wallet.transfer }, "sendToken" to { L.native.dappBridgeOps.sendToken }, "delegation" to { L.wallet.delegation }, "witnessVote" to { L.native.dappBridgeOps.witnessVote },
        "proxy" to { L.native.dappBridgeOps.proxy }, "powerUp" to { L.native.dappBridgeOps.powerUp }, "powerDown" to { L.native.dappBridgeOps.powerDown }, "broadcast" to { L.native.dappBridgeOps.broadcast },
        "signTx" to { L.native.dappBridgeOps.signTx }, "encode" to { L.native.dappBridgeOps.encode }, "decode" to { L.native.dappBridgeOps.decode },
        "addAccountAuthority" to { L.native.dappBridgeOps.addAccountAuthority }, "removeAccountAuthority" to { L.native.dappBridgeOps.removeAccountAuthority },
        "addKeyAuthority" to { L.native.dappBridgeOps.addKeyAuthority }, "removeKeyAuthority" to { L.native.dappBridgeOps.removeKeyAuthority },
        "createClaimedAccount" to { L.native.dappBridgeOps.createClaimedAccount }, "createProposal" to { L.native.dappBridgeOps.createProposal }, "removeProposal" to { L.native.dappBridgeOps.removeProposal },
        "updateProposalVote" to { L.native.dappBridgeOps.updateProposalVote }, "convert" to { L.wallet.convert }, "recurrentTransfer" to { L.wallet.recurrentTransfer },
        "savings" to { L.wallet.savings }, "swap" to { L.wallet.swap }, "signedCall" to { L.native.dappBridgeOps.signedCall }, "addAccount" to { L.sideMenu.addAccount },
    )

    fun of(type: String): String = LABELS[type]?.invoke() ?: type

    /** The title of one operation on the confirmation (the hive:// link titles), else its name. */
    fun operation(name: String): String = HiveUriOperations.find(name)?.title ?: name
}

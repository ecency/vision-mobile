package com.ecency.core.auth

import com.ecency.core.l10n.L
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import com.ecency.core.hive.KeyRole

/**
 * The operations a hive:// link may carry, from RN `utils/operations.json`: display name, the authority
 * the RN app checks, and the field the signer goes into (RN findParentKey over `__signer`).
 */
class HiveUriOperation(val name: String, private val titleText: () -> String, val authority: String, val signerField: String?) {
    /** The operation's title in the current language. */
    val title: String get() = titleText()
}

object HiveUriOperations {
    /**
     * In operations.json order. custom_json's signer default sits in an array, which RN's findParentKey
     * reports as the field "defaultValue" (it then adds a bogus `defaultValue` member to the operation);
     * the port has no signer field for it instead.
     */
    val ALL: List<HiveUriOperation> = listOf(
        HiveUriOperation("transfer", { L.wallet.transfer }, "active", "from"),
        HiveUriOperation("recurrent_transfer", { L.native.hiveUriOp.recurringTransfers }, "active", "from"),
        HiveUriOperation("delegate_vesting_shares", { L.native.hiveUriOp.delegateHivePower }, "active", "delegator"),
        HiveUriOperation("transfer_to_vesting", { L.native.hiveUriOp.powerUp }, "active", "from"),
        HiveUriOperation("set_withdraw_vesting_route", { L.native.hiveUriOp.setWithdrawVestingRoute }, "active", "from_account"),
        HiveUriOperation("withdraw_vesting", { L.native.hiveUriOp.powerDown }, "active", "account"),
        HiveUriOperation("transfer_to_savings", { L.native.hiveUriOp.transferToSaving }, "active", "from"),
        HiveUriOperation("transfer_from_savings", { L.native.hiveUriOp.transferFromSaving }, "active", "from"),
        HiveUriOperation("cancel_transfer_from_savings", { L.native.hiveUriOp.cancelTransferFromSaving }, "active", "from"),
        HiveUriOperation("convert", { L.wallet.convert }, "active", "owner"),
        HiveUriOperation("collateralized_convert", { L.native.hiveUriOp.collateralizedConvert }, "active", "owner"),
        HiveUriOperation("account_witness_vote", { L.native.hiveUriOp.witnessVote }, "active", "account"),
        HiveUriOperation("witness_update", { L.native.hiveUriOp.witnessUpdate }, "active", "owner"),
        HiveUriOperation("witness_set_properties", { L.native.hiveUriOp.witnessSetProperties }, "active", "owner"),
        HiveUriOperation("account_witness_proxy", { L.native.hiveUriOp.governanceProxy }, "active", "account"),
        HiveUriOperation("claim_account", { L.native.hiveUriOp.claimAccount }, "active", "creator"),
        HiveUriOperation("account_create", { L.native.hiveUriOp.createAccount }, "active", "creator"),
        HiveUriOperation("create_claimed_account", { L.native.hiveUriOp.createAccountWithAccount }, "active", "creator"),
        HiveUriOperation("vote", { L.wallet.vote }, "posting", "voter"),
        HiveUriOperation("limit_order_create", { L.native.hiveUriOp.createLimitOrder }, "active", "owner"),
        HiveUriOperation("limit_order_create2", { L.native.hiveUriOp.createLimitOrder }, "active", "owner"),
        HiveUriOperation("limit_order_cancel", { L.native.hiveUriOp.cancelLimitOrder }, "active", "owner"),
        HiveUriOperation("claim_reward_balance", { L.native.hiveUriOp.redeemRewards }, "posting", "account"),
        HiveUriOperation("comment", { L.native.hiveUriOp.postOrComment }, "posting", "author"),
        HiveUriOperation("comment_options", { L.native.hiveUriOp.postOrCommentOptions }, "posting", "author"),
        HiveUriOperation("custom_json", { L.native.hiveUriOp.customOperation }, "posting", null),
        HiveUriOperation("delete_comment", { L.post.a11yDelete }, "posting", "author"),
        HiveUriOperation("account_update", { L.native.hiveUriOp.updateAccountActive }, "active", "account"),
        HiveUriOperation("account_update2", { L.native.hiveUriOp.updateAccountPosting }, "posting", "account"),
        HiveUriOperation("change_recovery_account", { L.native.hiveUriOp.changeRecoveryAccount }, "owner", "account_to_recover"),
        HiveUriOperation("create_proposal", { L.native.hiveUriOp.createProposal }, "active", "creator"),
        HiveUriOperation("remove_proposal", { L.native.hiveUriOp.removeProposal }, "active", "proposal_owner"),
        HiveUriOperation("update_proposal_votes", { L.native.hiveUriOp.updateProposalVotes }, "active", "voter"),
        HiveUriOperation("update_proposal", { L.native.hiveUriOp.updateProposal }, "active", "creator"),
    )

    private val BY_NAME = ALL.associateBy { it.name }

    fun find(name: String): HiveUriOperation? = BY_NAME[name]
}

/** RN getFormattedTx's outcome. Error keys are the RN i18n ids. */
sealed interface FormattedHiveUriTx {
    /** [authority] is the key the confirmation names and the signer must use ("posting" or "active"). */
    data class Ready(val tx: JsonObject, val title: String, val authority: String) : FormattedHiveUriTx

    data class Refused(val errorKey1: String, val errorKey2: String, val authorityKeyType: String) : FormattedHiveUriTx
}

object HiveUriTx {
    /**
     * RN getFormattedTx: exactly one known operation, signable with a key the account holds ([available]
     * maps "posting", "active", "owner", "memo" to whether it is held; null skips the check), amounts as
     * three-decimal HIVE or HBD, and an empty signer field filled with `__signer`.
     *
     * Two deliberate differences: owner-authority operations are always refused (an external link never
     * gets the owner key; RN only reaches them through its owner-key map), and the authority of
     * content-dependent operations (custom_json with required_auths, account_update2) comes from
     * [OperationAuthority.forHiveUri], so an active custom_json needs and shows the active key. A third:
     * account_update and account_update2 carrying owner, active or posting authorities are refused, so a link
     * cannot rotate the account's keys (metadata-only updates pass).
     */
    fun format(tx: JsonObject, available: Map<String, Boolean>?): FormattedHiveUriTx {
        val ops = tx["operations"] as? JsonArray
        val op = ops?.singleOrNull() as? JsonArray
        if (op == null || op.size != 2) return FormattedHiveUriTx.Refused("qr.multi_array_ops_alert", "qr.multi_array_ops_aler_desct", "")
        val name = (op[0] as? JsonPrimitive)?.contentOrNull
        if (name.isNullOrEmpty()) return FormattedHiveUriTx.Refused("qr.invalid_op", "qr.invalid_op_desc", "")
        val def = HiveUriOperations.find(name) ?: return FormattedHiveUriTx.Refused("qr.invalid_op", "qr.invalid_op_desc", "")
        val resolved = OperationAuthority.forHiveUri(op)
        if (def.authority == "owner" || resolved == KeyRole.OWNER) {
            return FormattedHiveUriTx.Refused("qr.invalid_op", "qr.invalid_op_desc", "owner")
        }
        if (OperationAuthority.isMixedCustomJson(op)) return FormattedHiveUriTx.Refused("qr.invalid_op", "qr.invalid_op_desc", MIXED)
        val authority = if (name in CONTENT_DEPENDENT) resolved.value else def.authority
        // a link never rotates keys: only metadata updates of account_update / account_update2 pass
        if (name in ACCOUNT_UPDATES && AUTHORITY_FIELDS.any { (op[1] as? JsonObject)?.get(it).let { v -> v != null && v != JsonNull } }) {
            return FormattedHiveUriTx.Refused("qr.invalid_op", "qr.invalid_op_desc", authority)
        }
        if (available != null && available[authority] != true) {
            return FormattedHiveUriTx.Refused("qr.invalid_key", "qr.invalid_key_desc", authority)
        }
        var body = (op[1] as? JsonObject) ?: JsonObject(emptyMap())
        val amountElement = body["amount"]
        if (amountElement != null && isTruthy(amountElement)) {
            val formatted = (amountElement as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(::formatAmount)
                ?: return FormattedHiveUriTx.Refused("qr.invalid_amount", "qr.invalid_amount_desc", def.authority)
            body = JsonObject(body + ("amount" to JsonPrimitive(formatted)))
        }
        val field = def.signerField
        if (field != null && !isTruthy(body[field])) body = JsonObject(body + (field to JsonPrimitive("__signer")))
        val formattedOp = JsonArray(listOf(JsonPrimitive(name), body))
        return FormattedHiveUriTx.Ready(JsonObject(tx + ("operations" to JsonArray(listOf(formattedOp)))), def.title, authority)
    }

    /** [FormattedHiveUriTx.Refused.authorityKeyType] of a custom_json that needs active and posting signatures. */
    const val MIXED = "mixed"

    private val CONTENT_DEPENDENT = setOf("custom_json", "account_update2")
    private val ACCOUNT_UPDATES = setOf("account_update", "account_update2")
    private val AUTHORITY_FIELDS = listOf("owner", "active", "posting")

    private fun isTruthy(e: JsonElement?): Boolean = when (e) {
        null, kotlinx.serialization.json.JsonNull -> false
        is JsonPrimitive -> if (e.isString) e.content.isNotEmpty() else e.content != "false" && e.content.toDoubleOrNull() != 0.0
        else -> true
    }

    private val PARSE_FLOAT = Regex("^[\\t\\n\\u000b\\u000c\\r \\u00a0\\ufeff]*([+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?)")

    /**
     * RN _formatAmount: "<number> <HIVE|HBD>" with a non-zero JS parseFloat prefix, rewritten with three
     * decimals. Rounding is half-up on the decimal digits; JS toFixed rounds the binary double, so a few
     * inputs with more than three decimals (e.g. "1.0005") round differently. Null when refused.
     */
    fun formatAmount(amount: String): String? {
        val parts = amount.split(' ')
        if (parts.size != 2 || (parts[1] != "HIVE" && parts[1] != "HBD")) return null
        val number = PARSE_FLOAT.find(parts[0])?.groupValues?.get(1) ?: return null
        val value = number.toDoubleOrNull() ?: return null
        if (value == 0.0 || value.isNaN()) return null
        return "${toFixed3(number, value)} ${parts[1]}"
    }

    private fun toFixed3(number: String, value: Double): String {
        if ('e' in number || 'E' in number || kotlin.math.abs(value) >= 1e15) {
            val scaled = kotlin.math.round(kotlin.math.abs(value) * 1000).toLong()
            return (if (value < 0) "-" else "") + "${scaled / 1000}." + (scaled % 1000).toString().padStart(3, '0')
        }
        val negative = number.startsWith('-')
        val digits = number.trimStart('+', '-')
        val whole = digits.substringBefore('.').ifEmpty { "0" }
        val frac = digits.substringAfter('.', "").padEnd(4, '0')
        var units = (whole + frac.take(3)).trimStart('0').ifEmpty { "0" }.toLong()
        if (frac[3] >= '5') units += 1
        val text = "${units / 1000}." + (units % 1000).toString().padStart(3, '0')
        return if (negative && units != 0L) "-$text" else text
    }
}

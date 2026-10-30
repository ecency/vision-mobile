package com.ecency.core.api.broadcast

import com.ecency.core.api.InvalidInputException
import com.ecency.core.hive.Asset
import com.ecency.core.hive.CancelTransferFromSavings
import com.ecency.core.hive.ClaimRewardBalance
import com.ecency.core.hive.Convert
import com.ecency.core.hive.CustomJson
import com.ecency.core.hive.DelegateVestingShares
import com.ecency.core.hive.LimitOrderCancel
import com.ecency.core.hive.LimitOrderCreate
import com.ecency.core.hive.Operation
import com.ecency.core.hive.RecurrentTransfer
import com.ecency.core.hive.SetWithdrawVestingRoute
import com.ecency.core.hive.Transfer
import com.ecency.core.hive.TransferFromSavings
import com.ecency.core.hive.TransferToSavings
import com.ecency.core.hive.TransferToVesting
import com.ecency.core.hive.WithdrawVesting
import kotlinx.serialization.json.JsonArray

/**
 * Every wallet operation the app builds, ported from @ecency/sdk 2.4.26 (`operations/builders/wallet.ts`,
 * `market.ts`, `ecency.ts`, the engine mutations) and RN `transferContainer`: same fields, same values, and for
 * custom_json the same `json` text as the SDK's `JSON.stringify` (WalletGoldenVectors). Amounts are already
 * formatted strings ([Amounts]); a malformed one throws [InvalidInputException], where the SDK throws an Error.
 *
 * Broadcast with `env.signing.broadcast(user, WalletOps.ops(list), authority, actionId)`: every one of these moves
 * value or spends points, so each needs an action id made once per confirmation (the posting-key claims too:
 * a claim is not in [RepeatSafeOps]).
 */
object WalletOps {
    const val ENGINE_ID = "ssc-mainnet-hive"
    const val POINT_TRANSFER_ID = "ecency_point_transfer"
    const val ENGINE_CLAIM_ID = "scot_claim_token"

    /** RN `MAX_RECIPIENTS`: one op per recipient, at most 50 in one transaction. */
    const val MAX_RECIPIENTS = 50

    fun ops(list: List<Operation>): JsonArray = JsonArray(list.map(OperationJson::encode))

    /** SDK buildMultiTransferOps split: whitespace or commas, empties dropped. */
    fun recipients(destinations: String): List<String> = destinations.trim().split(Regex("[\\s,]+")).filter { it.isNotEmpty() }

    private fun asset(value: String): Asset = try {
        Asset.parse(value)
    } catch (e: IllegalArgumentException) {
        throw InvalidInputException("Invalid amount: $value")
    }

    private val POSIX_NUMBER = Regex("^[0-9]+(\\.[0-9]+)?$")

    /**
     * Amounts that are signed are POSIX by construction (`Amounts`, `Num`, `Asset`: ASCII digits, '.' point, no
     * grouping); the custom_json builders, whose amounts are free text, refuse anything else, so a number formatted
     * for display in the app language ("1.000,5", "١٫٥") fails here instead of being broadcast.
     */
    private fun posixAmount(number: String, whole: String) {
        if (!POSIX_NUMBER.matches(number)) throw InvalidInputException("Invalid amount: $whole")
    }

    private fun need(name: String, vararg values: String) {
        if (values.any { it.isEmpty() }) throw InvalidInputException("[$name] Missing required parameters")
    }

    private inline fun <T> op(block: () -> T): T = try {
        block()
    } catch (e: IllegalArgumentException) {
        throw InvalidInputException(e.message ?: "Invalid operation")
    }

    fun transfer(from: String, to: String, amount: String, memo: String): Transfer {
        need("buildTransferOp", from, to, amount)
        return op { Transfer(from, to, asset(amount), memo) }
    }

    /** One transfer per recipient of [destinations] (SDK buildMultiTransferOps). */
    fun multiTransfer(from: String, destinations: String, amount: String, memo: String): List<Transfer> {
        need("buildMultiTransferOps", from, destinations, amount)
        return recipients(destinations).map { transfer(from, it, amount, memo) }
    }

    fun recurrentTransfer(from: String, to: String, amount: String, memo: String, recurrence: Int, executions: Int): RecurrentTransfer {
        need("buildRecurrentTransferOp", from, to, amount)
        if (recurrence < 24) throw InvalidInputException("[buildRecurrentTransferOp] Recurrence must be at least 24 hours")
        return op { RecurrentTransfer(from, to, asset(amount), memo, recurrence, executions) }
    }

    fun transferToSavings(from: String, to: String, amount: String, memo: String): TransferToSavings {
        need("buildTransferToSavingsOp", from, to, amount)
        return op { TransferToSavings(from, to, asset(amount), memo) }
    }

    fun transferFromSavings(from: String, to: String, amount: String, memo: String, requestId: Long): TransferFromSavings {
        need("buildTransferFromSavingsOp", from, to, amount)
        return op { TransferFromSavings(from, requestId, to, asset(amount), memo) }
    }

    fun cancelTransferFromSavings(from: String, requestId: Long): CancelTransferFromSavings {
        need("buildCancelTransferFromSavingsOp", from)
        return op { CancelTransferFromSavings(from, requestId) }
    }

    /** SDK buildClaimInterestOps: a savings withdrawal cancelled in the same transaction pays the interest. */
    fun claimInterest(from: String, to: String, amount: String, memo: String, requestId: Long): List<Operation> =
        listOf(transferFromSavings(from, to, amount, memo, requestId), cancelTransferFromSavings(from, requestId))

    fun transferToVesting(from: String, to: String, amount: String): TransferToVesting {
        need("buildTransferToVestingOp", from, to, amount)
        return op { TransferToVesting(from, to, asset(amount)) }
    }

    fun withdrawVesting(account: String, vestingShares: String): WithdrawVesting {
        need("buildWithdrawVestingOp", account, vestingShares)
        return op { WithdrawVesting(account, asset(vestingShares)) }
    }

    fun delegateVestingShares(delegator: String, delegatee: String, vestingShares: String): DelegateVestingShares {
        need("buildDelegateVestingSharesOp", delegator, delegatee, vestingShares)
        return op { DelegateVestingShares(delegator, delegatee, asset(vestingShares)) }
    }

    /** [percent] in chain units, 0..10000 (10000 is 100%). */
    fun setWithdrawVestingRoute(from: String, to: String, percent: Int, autoVest: Boolean): SetWithdrawVestingRoute {
        need("buildSetWithdrawVestingRouteOp", from, to)
        if (percent !in 0..10000) throw InvalidInputException("[buildSetWithdrawVestingRouteOp] Percent must be between 0 and 10000")
        return op { SetWithdrawVestingRoute(from, to, percent, autoVest) }
    }

    fun convert(owner: String, amount: String, requestId: Long): Convert {
        need("buildConvertOp", owner, amount)
        return op { Convert(owner, requestId, asset(amount)) }
    }

    /** All three strings as the account holds them; the unclaimed ones are zero ("0.000 HIVE", "0.000000 VESTS"). */
    fun claimRewardBalance(account: String, rewardHive: String, rewardHbd: String, rewardVests: String): ClaimRewardBalance {
        need("buildClaimRewardBalanceOp", account, rewardHive, rewardHbd, rewardVests)
        return op { ClaimRewardBalance(account, asset(rewardHive), asset(rewardHbd), asset(rewardVests)) }
    }

    fun limitOrderCreate(owner: String, amountToSell: String, minToReceive: String, fillOrKill: Boolean, expiration: String, orderId: Long): LimitOrderCreate {
        need("buildLimitOrderCreateOp", owner, amountToSell, minToReceive, expiration)
        return op { LimitOrderCreate(owner, orderId, asset(amountToSell), asset(minToReceive), fillOrKill, expiration) }
    }

    fun limitOrderCancel(owner: String, orderId: Long): LimitOrderCancel {
        need("buildLimitOrderCancelOp", owner)
        return op { LimitOrderCancel(owner, orderId) }
    }

    /**
     * SDK buildPointTransferOp: amount "1.000 POINT" ("POINTS" is rewritten to the singular the backend wants). The
     * number must be POSIX ([posixAmount]): a display string in the app language can never be signed.
     */
    fun pointTransfer(sender: String, receiver: String, amount: String, memo: String): CustomJson {
        need("buildPointTransferOp", sender, receiver, amount)
        posixAmount(amount.substringBefore(' '), amount)
        val normalized = amount.replace(Regex("POINTS\\b"), "POINT")
        val json = obj("sender" to q(sender), "receiver" to q(receiver), "amount" to q(normalized), "memo" to q(memo))
        return CustomJson(listOf(sender), emptyList(), POINT_TRANSFER_ID, json)
    }

    fun multiPointTransfer(sender: String, destinations: String, amount: String, memo: String): List<CustomJson> {
        need("buildMultiPointTransferOps", sender, destinations, amount)
        val list = recipients(destinations)
        if (list.isEmpty()) throw InvalidInputException("[buildMultiPointTransferOps] Missing valid destinations")
        return list.map { pointTransfer(sender, it, amount, memo) }
    }

    /** The Hive Engine token actions of the SDK mutations (use-*-engine-token.ts). */
    enum class EngineAction(val value: String) { TRANSFER("transfer"), STAKE("stake"), UNSTAKE("unstake"), DELEGATE("delegate"), UNDELEGATE("undelegate") }

    /**
     * `{"contractName":"tokens","contractAction":...,"contractPayload":{symbol, to|from, quantity[, memo]}}`;
     * [counterparty] is `to`, or `from` for UNDELEGATE (the delegatee). [quantity] is already cut to the token's
     * precision ([Amounts.tokenQuantity]); [memo] goes in for TRANSFER only.
     */
    fun engine(user: String, action: EngineAction, symbol: String, counterparty: String, quantity: String, memo: String = ""): CustomJson {
        need("engine", user, symbol, counterparty, quantity)
        posixAmount(quantity, quantity)
        val party = if (action == EngineAction.UNDELEGATE) "from" else "to"
        val payload = buildList {
            add("symbol" to q(symbol))
            add(party to q(counterparty))
            add("quantity" to q(quantity))
            if (action == EngineAction.TRANSFER) add("memo" to q(memo))
        }
        val json = obj("contractName" to q("tokens"), "contractAction" to q(action.value), "contractPayload" to obj(*payload.toTypedArray()))
        return CustomJson(listOf(user), emptyList(), ENGINE_ID, json)
    }

    /** Engine transfers to every recipient of [destinations], one op each. */
    fun multiEngineTransfer(user: String, symbol: String, destinations: String, quantity: String, memo: String): List<CustomJson> =
        recipients(destinations).map { engine(user, EngineAction.TRANSFER, symbol, it, quantity, memo) }

    /** SDK buildEngineClaimOp: posting `[{"symbol":"LEO"}]`. */
    fun engineClaim(account: String, symbols: List<String>): CustomJson {
        need("buildEngineClaimOp", account)
        return CustomJson(emptyList(), listOf(account), ENGINE_CLAIM_ID, "[" + symbols.joinToString(",") { obj("symbol" to q(it)) } + "]")
    }

    /** SDK buildPromoteOp. */
    fun promote(user: String, author: String, permlink: String, duration: Int): CustomJson {
        need("buildPromoteOp", user, author, permlink)
        return CustomJson(listOf(user), emptyList(), "ecency_promote", obj("user" to q(user), "author" to q(author), "permlink" to q(permlink), "duration" to duration.toString()))
    }

    /** SDK buildBoostPlusOp. */
    fun boostPlus(user: String, account: String, duration: Int): CustomJson {
        need("buildBoostPlusOp", user, account)
        return CustomJson(listOf(user), emptyList(), "ecency_boost_plus", obj("user" to q(user), "account" to q(account), "duration" to duration.toString()))
    }

    /** SDK buildRcDelegationOp (the RC top-up bought with points); [duration] a positive whole number of days. */
    fun rcDelegation(user: String, duration: Int): CustomJson {
        need("buildRcDelegationOp", user)
        if (duration <= 0) throw InvalidInputException("[buildRcDelegationOp] Missing or invalid parameters")
        return CustomJson(listOf(user), emptyList(), "ecency_rc_delegation", obj("user" to q(user), "duration" to duration.toString()))
    }

    private fun q(s: String) = JsJson.quote(s)

    private fun obj(vararg fields: Pair<String, String>) = fields.joinToString(",", "{", "}") { (k, v) -> "${q(k)}:$v" }
}

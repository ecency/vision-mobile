package com.ecency.core.hive

// Wallet operations. Ids and field order follow hived's operation variant (dhive serializer.js, hive-tx
// serializer.ts); WalletGoldenVectors pins the bytes to dhive and the transaction ids to @ecency/sdk.

private fun requireLiquid(amount: Asset, what: String) =
    require(amount.symbol == "HIVE" || amount.symbol == "HBD") { "$what must be HIVE or HBD" }

/** Power up: HIVE from [from] into Hive Power of [to]. */
data class TransferToVesting(val from: String, val to: String, val amount: Asset) : Operation {
    init {
        require(amount.symbol == "HIVE") { "power up must be HIVE" }
        require(amount.units > 0) { "power up amount must be positive" }
    }

    override fun write(writer: HiveWriter) = with(writer) {
        varint32(3)
        string(from)
        string(to)
        amount.write(this)
    }
}

/** Power down [vestingShares] over 13 weeks; 0 VESTS cancels a running power down. */
data class WithdrawVesting(val account: String, val vestingShares: Asset) : Operation {
    init {
        require(vestingShares.symbol == "VESTS") { "power down is in VESTS" }
        require(vestingShares.units >= 0) { "power down amount must not be negative" }
    }

    override fun write(writer: HiveWriter) = with(writer) {
        varint32(4)
        string(account)
        vestingShares.write(this)
    }
}

/** An internal market order: sell [amountToSell] for at least [minToReceive] until [expiration] (chain time). */
data class LimitOrderCreate(
    val owner: String,
    val orderId: Long,
    val amountToSell: Asset,
    val minToReceive: Asset,
    val fillOrKill: Boolean,
    val expiration: String,
) : Operation {
    init {
        requireLiquid(amountToSell, "amount_to_sell")
        requireLiquid(minToReceive, "min_to_receive")
        require(amountToSell.symbol != minToReceive.symbol) { "an order trades HIVE against HBD" }
        require(amountToSell.units > 0 && minToReceive.units > 0) { "order amounts must be positive" }
        require(orderId in 0..0xFFFFFFFFL) { "orderid out of range" }
        ChainTime.toEpochSeconds(expiration)
    }

    override fun write(writer: HiveWriter) = with(writer) {
        varint32(5)
        string(owner)
        u32(orderId)
        amountToSell.write(this)
        minToReceive.write(this)
        bool(fillOrKill)
        u32(ChainTime.toEpochSeconds(expiration))
    }
}

data class LimitOrderCancel(val owner: String, val orderId: Long) : Operation {
    init {
        require(orderId in 0..0xFFFFFFFFL) { "orderid out of range" }
    }

    override fun write(writer: HiveWriter) = with(writer) {
        varint32(6)
        string(owner)
        u32(orderId)
    }
}

/** HBD to HIVE at the median price after 3.5 days. Field order on chain: owner, requestid, amount. */
data class Convert(val owner: String, val requestId: Long, val amount: Asset) : Operation {
    init {
        require(amount.symbol == "HBD") { "convert takes HBD" }
        require(amount.units > 0) { "convert amount must be positive" }
        require(requestId in 0..0xFFFFFFFFL) { "requestid out of range" }
    }

    override fun write(writer: HiveWriter) = with(writer) {
        varint32(8)
        string(owner)
        u32(requestId)
        amount.write(this)
    }
}

/** HIVE to HBD at once, with collateral (HF25). */
data class CollateralizedConvert(val owner: String, val requestId: Long, val amount: Asset) : Operation {
    init {
        require(amount.symbol == "HIVE") { "collateralized convert takes HIVE" }
        require(amount.units > 0) { "convert amount must be positive" }
        require(requestId in 0..0xFFFFFFFFL) { "requestid out of range" }
    }

    override fun write(writer: HiveWriter) = with(writer) {
        varint32(48)
        string(owner)
        u32(requestId)
        amount.write(this)
    }
}

/** Where power down payments go: [percent] 0..10000 of each one to [toAccount], as HP when [autoVest]. */
data class SetWithdrawVestingRoute(val fromAccount: String, val toAccount: String, val percent: Int, val autoVest: Boolean) : Operation {
    init {
        require(percent in 0..10000) { "percent out of range: $percent" }
    }

    override fun write(writer: HiveWriter) = with(writer) {
        varint32(20)
        string(fromAccount)
        string(toAccount)
        u16(percent)
        bool(autoVest)
    }
}

data class TransferToSavings(val from: String, val to: String, val amount: Asset, val memo: String) : Operation {
    init {
        requireLiquid(amount, "savings deposit")
        require(amount.units > 0) { "savings amount must be positive" }
    }

    override fun write(writer: HiveWriter) = with(writer) {
        varint32(32)
        string(from)
        string(to)
        amount.write(this)
        string(memo)
    }
}

/** A savings withdrawal (3 days). Field order on chain: from, request_id, to, amount, memo. */
data class TransferFromSavings(val from: String, val requestId: Long, val to: String, val amount: Asset, val memo: String) : Operation {
    init {
        requireLiquid(amount, "savings withdrawal")
        require(amount.units > 0) { "savings amount must be positive" }
        require(requestId in 0..0xFFFFFFFFL) { "request_id out of range" }
    }

    override fun write(writer: HiveWriter) = with(writer) {
        varint32(33)
        string(from)
        u32(requestId)
        string(to)
        amount.write(this)
        string(memo)
    }
}

data class CancelTransferFromSavings(val from: String, val requestId: Long) : Operation {
    init {
        require(requestId in 0..0xFFFFFFFFL) { "request_id out of range" }
    }

    override fun write(writer: HiveWriter) = with(writer) {
        varint32(34)
        string(from)
        u32(requestId)
    }
}

/** Claims pending author and curation rewards (posting authority). */
data class ClaimRewardBalance(val account: String, val rewardHive: Asset, val rewardHbd: Asset, val rewardVests: Asset) : Operation {
    init {
        require(rewardHive.symbol == "HIVE" && rewardHbd.symbol == "HBD" && rewardVests.symbol == "VESTS") { "claim assets must be HIVE, HBD and VESTS" }
        require(rewardHive.units >= 0 && rewardHbd.units >= 0 && rewardVests.units >= 0) { "claim amounts must not be negative" }
        require(rewardHive.units > 0 || rewardHbd.units > 0 || rewardVests.units > 0) { "nothing to claim" }
    }

    override fun write(writer: HiveWriter) = with(writer) {
        varint32(39)
        string(account)
        rewardHive.write(this)
        rewardHbd.write(this)
        rewardVests.write(this)
    }
}

/** HP delegation in VESTS; 0 VESTS removes it. */
data class DelegateVestingShares(val delegator: String, val delegatee: String, val vestingShares: Asset) : Operation {
    init {
        require(vestingShares.symbol == "VESTS") { "delegation is in VESTS" }
        require(vestingShares.units >= 0) { "delegation must not be negative" }
    }

    override fun write(writer: HiveWriter) = with(writer) {
        varint32(40)
        string(delegator)
        string(delegatee)
        vestingShares.write(this)
    }
}

/**
 * A transfer repeated every [recurrence] hours, [executions] times (HF25). [amount] 0 removes an existing one.
 * No extensions (no pair id), as the SDK builds it.
 */
data class RecurrentTransfer(
    val from: String,
    val to: String,
    val amount: Asset,
    val memo: String,
    val recurrence: Int,
    val executions: Int,
) : Operation {
    init {
        requireLiquid(amount, "recurrent transfer")
        require(amount.units >= 0) { "recurrent transfer must not be negative" }
        require(recurrence in 24..0xffff) { "recurrence must be at least 24 hours" }
        require(executions in 0..0xffff) { "executions out of range" }
    }

    override fun write(writer: HiveWriter) = with(writer) {
        varint32(49)
        string(from)
        string(to)
        amount.write(this)
        string(memo)
        u16(recurrence)
        u16(executions)
        varint32(0) // extensions
    }
}

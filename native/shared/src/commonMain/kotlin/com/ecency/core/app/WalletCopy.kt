package com.ecency.core.app

import com.ecency.core.api.wallet.PortfolioItem
import com.ecency.core.l10n.L
import com.ecency.core.l10n.LocaleFormat

/** The fiat currency the wallet shows (RN `application.currency`; the settings screen picks it, [KEY] in [Prefs]). */
data class WalletCurrency(val code: String, val symbol: String) {
    companion object {
        /** The preference the settings wave writes (lowercase code, RN `currency.currency`). */
        const val KEY = "settings.currency"

        val USD = WalletCurrency("usd", "$")

        /** RN's currency list (constants/options/currency.ts) with the symbols its currency-symbol-map gives. */
        val SYMBOLS: Map<String, String> = linkedMapOf(
            "btc" to "₿", "usd" to "$", "eur" to "€", "rub" to "₽", "gbp" to "£", "jpy" to "¥", "krw" to "₩", "inr" to "₹",
            "cny" to "¥", "uah" to "₴", "sek" to "kr", "try" to "₺", "cad" to "$", "chf" to "CHF", "aud" to "$", "nok" to "kr",
            "pln" to "zł", "php" to "₱", "idr" to "Rp", "zar" to "R", "thb" to "฿", "pkr" to "₨", "vnd" to "₫", "ngn" to "₦",
        )

        fun of(prefs: Prefs): WalletCurrency {
            val code = prefs.getString(KEY)?.lowercase()?.takeIf { it in SYMBOLS } ?: return USD
            return WalletCurrency(code, SYMBOLS.getValue(code))
        }
    }
}

/**
 * RN number formatting for the wallet (utils/number formatAmount: `Intl.NumberFormat` in the app language, grouped):
 * "1,234.567" in en-US, "1.234,567" in de-DE ([LocaleFormat]). Display only; amounts that are signed come from
 * `Amounts` (POSIX).
 */
object WalletFormat {
    /** [value] with [decimals] decimals (JS toFixed rounding) and the app language's grouping and separators. */
    fun grouped(value: Double, decimals: Int): String = LocaleFormat.fixed(value, decimals)

    /** RN asset card and summary fiat: 5 decimals under 1, else 2, with the currency symbol in front (as RN). */
    fun fiat(value: Double, currency: WalletCurrency): String =
        LocaleFormat.fiat(value, currency.symbol, if (kotlin.math.abs(value) < 1) 5 else 2)

    /** RN `formatAmount(balance, 3 dp)`. */
    fun amount(value: Double): String = grouped(value, 3)

    /** RN coinSummary APR: toFixed(3) under 10, else toFixed(2). */
    fun apr(value: Double): String = "${WalletCopy.APR} ${LocaleFormat.percent(value, if (value < 10) 3 else 2, if (value < 10) 3 else 2)}"
}

/** RN receiveQrSheet: a hive-uri transfer to [username] (signer left to the payer's wallet, 0.001 HIVE as RN). */
object ReceiveQr {
    fun uri(username: String): String = com.ecency.core.auth.HiveUri.encodeOp(
        kotlinx.serialization.json.buildJsonArray {
            add(kotlinx.serialization.json.JsonPrimitive("transfer"))
            add(kotlinx.serialization.json.buildJsonObject {
                put("from", kotlinx.serialization.json.JsonPrimitive("__signer"))
                put("to", kotlinx.serialization.json.JsonPrimitive(username))
                put("amount", kotlinx.serialization.json.JsonPrimitive("0.001 HIVE"))
                put("memo", kotlinx.serialization.json.JsonPrimitive(""))
            })
        },
    )
}

/** RN en-US copy of the wallet area (`wallet.*`, `transfer.*`, `trade.*`, `recurrent.*`, `tipping.*`, ...). */
object WalletCopy {
    val ESTIMATED_BALANCE: String get() = L.wallet.estimatedBalance
    val UPDATING: String get() = L.wallet.updating
    val SEND: String get() = L.wallet.send
    val RECEIVE: String get() = L.wallet.receive
    val SWAP: String get() = L.wallet.swap
    val GET_POINTS: String get() = L.wallet.getPoints
    val NOT_LOGGED_IN: String get() = L.alert.notLoggedIn
    val REFRESH_FAILED: String get() = L.alert.walletRefreshFailed
    val TRY_AGAIN: String get() = L.alert.somethingWrongReload
    val RECEIVE_DESC: String get() = L.wallet.receiveDesc
    val CLAIMED: String get() = L.alert.claimRewardBalanceOk
    val DETAILS: String get() = L.wallet.coinDetails
    val APR: String get() = L.wallet.apr
    val BALANCE: String get() = L.wallet.amountDesc
    val STAKED: String get() = L.wallet.staked
    val SAVINGS: String get() = L.wallet.savings
    val ESTIMATED_VALUE: String get() = L.wallet.estimatedValue
    val PENDING_REQUESTS: String get() = L.wallet.pendingRequests
    val ACTIVITIES: String get() = L.wallet.activities
    val NO_ACTIVITIES: String get() = L.wallet.noActivities
    val ACTIVITIES_FAILED: String get() = L.wallet.activitiesFailed
    val EXPIRES: String get() = L.wallet.expires
    val TRANSACTION_ID: String get() = L.wallet.transactionId
    val COPY_TRANSACTION: String get() = L.wallet.copyTransactionId
    val COPIED: String get() = L.alert.copied
    val FILTER_TITLE: String get() = L.wallet.filterActivities
    val FILTER_DESC: String get() = L.wallet.filterActivitiesDesc
    val FILTER_ALL: String get() = L.wallet.filterAll
    val FILTER_NONE: String get() = L.wallet.filterNone
    val FILTER_APPLY: String get() = L.wallet.filterApply
    val MANAGE_ASSETS: String get() = L.wallet.manageAssets
    val SELECTED_ASSETS: String get() = L.wallet.selectedAssets
    val AVAILABLE_ASSETS: String get() = L.wallet.availableAssets
    val NO_SELECTED_ASSETS: String get() = L.wallet.noSelectedAssets
    val SEARCH: String get() = L.header.search
    val UPDATE_TOKENS_FAILED: String get() = L.alert.updateTokensFailed
    val TOTAL_RECURRENT: String get() = L.wallet.totalRecurrentTransfers
    val TAP_UPDATE: String get() = L.wallet.tapUpdate
    val EXPIRING: String get() = L.wallet.expiring
    val EXPIRING_SOON: String get() = L.wallet.expiringSoon
    val ADDRESS_UNAVAILABLE: String get() = L.wallet.addressUnavailable
    val CONFIRM: String get() = L.alert.confirm
    val CANCEL: String get() = L.alert.cancel
    val SUCCESSFUL: String get() = L.alert.successful
    val FAIL: String get() = L.alert.fail
    val KEY_WARNING: String get() = L.alert.keyWarning
    val CHECKING: String get() = L.native.wallet.checking
    val PICK_FAVORITE: String get() = L.transfer.pickFromFavorites
    val LEAVE_TITLE: String get() = L.native.wallet.leaveTitle
    val LEAVE_MESSAGE: String get() = L.native.wallet.leaveMessage
    val LEAVE: String get() = L.searchResult.communities.unsubscribe
    val STAY: String get() = L.native.wallet.stay

    /** One way to earn points (RN POINTS_KEYS on the Points details). */
    data class EarnWay(val name: String, val points: String, val description: String)

    /** RN POINTS_KEYS (types 160, 150, 100, 110, 120, 130, 10, 20, 30) with their points and `wallet.*_desc`. */
    val POINTS_EARN: List<EarnWay> get() = listOf(
        EarnWay(L.wallet.referral, "100", L.wallet.referralDesc),
        EarnWay(L.wallet.delegation, "10", L.wallet.delegationDesc),
        EarnWay(L.wallet.post, "15", L.wallet.postDesc),
        EarnWay(L.wallet.comment, "5", L.wallet.commentDesc),
        EarnWay(L.wallet.vote, "0.3", L.wallet.voteDesc),
        EarnWay(L.wallet.reblog, "1", L.wallet.reblogDesc),
        EarnWay(L.wallet.checkin, "0.25", L.wallet.checkinDesc),
        EarnWay(L.wallet.login, "10", L.wallet.loginDesc),
        EarnWay(L.wallet.checkinExtra, "10", L.wallet.checkinExtraDesc),
    )

    fun claimFailed(message: String) = L.alert.claimFailed(message = message)

    fun updated(time: String) = L.wallet.lastUpdated(datetime = time)

    fun addressLabel(symbol: String) = L.wallet.addressLabel(symbol = symbol)

    fun timeRemainingDays(days: Long, hours: Long) = L.wallet.timeRemainingDays(days = days.toString(), hours = hours.toString())

    fun timeRemainingHours(hours: Long, mins: Long) = L.wallet.timeRemainingHours(hours = hours.toString(), mins = mins.toString())

    /** RN `alert.operation_failed_with_reason`. */
    fun operationFailed(operation: String, reason: String) = L.alert.operationFailedWithReason(operation = operation, reason = reason)

    /** RN asset card name: `wallet.<SYMBOL>.name` when the locale has one (only POINTS), else the server's. */
    fun assetName(item: PortfolioItem): String = if (item.symbol == "POINTS") L.wallet.points.name else item.name

    /** RN `wallet.<key>` labels used by actions, history rows and extra data (read when shown, so a language switch applies). */
    private val LABELS: Map<String, () -> String> = mapOf(
        "transfer" to { L.wallet.transfer }, "transfer_token" to { L.wallet.transferToken }, "transfer_to_savings" to { L.wallet.transferToSavings }, "transfer_from_savings" to { L.wallet.transferFromSavings },
        "transfer_to_vesting" to { L.wallet.transferToVesting }, "withdraw_vesting" to { L.wallet.withdrawVesting }, "power_down" to { L.wallet.powerDown }, "delegate_vesting_shares" to { L.wallet.delegateVestingShares },
        "set_withdraw_vesting_route" to { L.wallet.setWithdrawVestingRoute }, "swap_token" to { L.wallet.swapToken }, "convert" to { L.wallet.convert }, "ecency_point_transfer" to { L.wallet.ecencyPointTransfer },
        "promote" to { L.wallet.promote }, "boost" to { L.wallet.boost }, "stake" to { L.wallet.stake }, "unstake" to { L.wallet.unstake }, "delegate" to { L.wallet.delegate }, "undelegate" to { L.wallet.undelegate },
        "receive" to { L.wallet.receive }, "recurrent_transfer" to { L.wallet.recurrentTransfer }, "stake_engine" to { L.wallet.stakeEngine }, "unstake_engine" to { L.wallet.unstakeEngine },
        "delegate_engine" to { L.wallet.delegateEngine }, "undelegate_engine" to { L.wallet.undelegateEngine }, "transfer_engine" to { L.wallet.transferEngine },
        // history rows
        "curation_reward" to { L.wallet.curationReward }, "author_reward" to { L.wallet.authorReward }, "comment_benefactor_reward" to { L.wallet.commentBenefactorReward },
        "claim_reward_balance" to { L.wallet.claimRewardBalance }, "fill_recurrent_transfer" to { L.wallet.fillRecurrentTransfer }, "fill_order" to { L.wallet.fillOrder },
        "escrow_transfer" to { L.wallet.escrowTransfer }, "escrow_dispute" to { L.wallet.escrowDispute }, "escrow_release" to { L.wallet.escrowRelease }, "escrow_approve" to { L.wallet.escrowApprove },
        "cancel_transfer_from_savings" to { L.wallet.cancelTransferFromSavings }, "fill_convert_request" to { L.wallet.fillConvertRequest }, "fill_transfer_from_savings" to { L.wallet.fillTransferFromSavings },
        "fill_vesting_withdraw" to { L.wallet.fillVestingWithdraw }, "interest" to { L.native.walletHistory.interest }, "proposal_pay" to { L.native.walletHistory.proposalPay }, "withdraw_savings" to { L.wallet.withdrawSavings }, "convert_request" to { L.wallet.convertRequest },
        "collateralized_convert_request" to { L.native.wallet.collateralizedConvertRequest }, "open_order" to { L.wallet.openOrder },
        // engine rows
        "tokens_create" to { L.wallet.tokensCreate }, "tokens_transfer" to { L.wallet.tokensTransfer }, "tokens_transferOwnership" to { L.wallet.tokensTransferOwnership },
        "tokens_transferToContract" to { L.wallet.tokensTransferToContract }, "tokens_transferFromContract" to { L.wallet.tokensTransferFromContract },
        "tokens_transferFee" to { L.wallet.tokensTransferFee }, "tokens_updatePrecision" to { L.wallet.tokensUpdatePrecision }, "tokens_updateUrl" to { L.wallet.tokensUpdateUrl },
        "tokens_updateMetadata" to { L.wallet.tokensUpdateMetadata }, "tokens_enableStaking" to { L.wallet.tokensEnableStaking }, "tokens_enableDelegation" to { L.wallet.tokensEnableDelegation },
        "tokens_issue" to { L.wallet.tokensIssue }, "tokens_delegate" to { L.wallet.tokensDelegate }, "tokens_stake" to { L.wallet.tokensStake }, "tokens_cancelUnstake" to { L.wallet.tokensCancelUnstake },
        "tokens_undelegateDone" to { L.wallet.tokensUndelegateDone }, "tokens_unstakeDone" to { L.wallet.tokensUnstakeDone }, "tokens_undelegateStart" to { L.wallet.tokensUndelegateStart },
        "tokens_unstakeStart" to { L.wallet.tokensUnstakeStart }, "market_buy" to { L.wallet.marketBuy }, "market_sell" to { L.wallet.marketSell }, "market_cancel" to { L.wallet.marketCancel },
        "market_placeOrder" to { L.wallet.marketPlaceOrder }, "market_expire" to { L.wallet.marketExpire }, "market_close" to { L.wallet.marketClose },
        "market_buyRemaining" to { L.wallet.marketBuyRemaining }, "market_sellRemaining" to { L.wallet.marketSellRemaining },
        "mining_lottery" to { L.wallet.miningLottery }, "hivepegged_buy" to { L.wallet.hivepeggedBuy }, "hivepegged_withdraw" to { L.wallet.hivepeggedWithdraw },
        "inflation_issueNewTokens" to { L.wallet.inflationIssueNewTokens }, "witnesses_proposeRound" to { L.wallet.witnessesProposeRound },
        // points rows
        "burned_title" to { L.wallet.burnedTitle }, "burn_refund_title" to { L.wallet.burnRefundTitle }, "incoming_transfer_title" to { L.wallet.incomingTransferTitle },
        "outgoing_transfer_title" to { L.wallet.outgoingTransferTitle }, "community_reward" to { L.wallet.communityReward }, "referral_title" to { L.wallet.referralTitle },
        "delegation_title" to { L.wallet.delegationTitle }, "post_title" to { L.wallet.postTitle }, "comment_title" to { L.wallet.commentTitle },
        "vote_title" to { L.wallet.voteTitle }, "reblog_title" to { L.wallet.reblogTitle }, "checkin_title" to { L.wallet.checkinTitle },
        "login_title" to { L.wallet.loginTitle }, "checkin_extra_title" to { L.wallet.checkinExtraTitle }, "points_activity" to { L.wallet.pointsActivity },
        // extra data rows
        "delegated_hive_power" to { L.wallet.delegatedHivePower }, "received_hive_power" to { L.wallet.receivedHivePower }, "total_hive_power" to { L.wallet.totalHivePower },
        "net_hive_power" to { L.wallet.netHivePower }, "scheduled_power_downs" to { L.wallet.scheduledPowerDowns }, "savings_withdrawal" to { L.wallet.savingsWithdrawal },
        "conversions_requested" to { L.wallet.conversionsRequested }, "delegations_in" to { L.wallet.delegationsIn }, "delegations_out" to { L.wallet.delegationsOut },
        "open_orders" to { L.wallet.openOrders }, "vote_value" to { L.wallet.voteValue }, "estimated_amount" to { L.wallet.estimatedAmount }, "total_recurrent_transfers" to { TOTAL_RECURRENT },
    )

    /** `wallet.<key>`, or the key humanised ("some_op" is "Some op"). */
    fun label(key: String): String = LABELS[key]?.invoke() ?: key.replace('_', ' ').replaceFirstChar { it.uppercase() }

    /**
     * A points history type chip: "All" for 0; the earning types without the rows' "Points for" ("Heartbeats",
     * "Login", "Post": the chips sit under the points balance, owner note 10-28); else the type's row label.
     */
    fun pointTypeLabel(type: Int): String = when (type) {
        0 -> L.native.walletHistory.categoryAll
        10 -> L.native.walletHistory.pointTypeCheckin
        20 -> L.native.walletHistory.pointTypeLogin
        100 -> L.native.walletHistory.pointTypePost
        110 -> L.native.walletHistory.pointTypeComment
        120 -> L.native.walletHistory.pointTypeVote
        130 -> L.native.walletHistory.pointTypeReblog
        // the rows read "Transfer/Gifting" both ways; two chips need two names (web 998 sent, 999 incoming)
        998 -> L.native.walletHistory.pointTypeTransferSent
        999 -> L.native.walletHistory.pointTypeTransferReceived
        else -> label(com.ecency.core.api.wallet.WalletHistory.pointType(type, "0").second)
    }

    /** What the category chips are, for screen readers. */
    val HISTORY_CATEGORIES: String get() = L.native.walletHistory.categories

    /** An extra data row's label; powering down carries its sub value ("Unstaking (144h)"). */
    fun extraLabel(dataKey: String, subValue: String?): String =
        if (dataKey == "powering_down_hive_power") L.wallet.poweringDownHivePower(subValue = subValue.orEmpty()) else label(dataKey)

    /** What an operation is called in the journal's messages. */
    fun operationLabel(operation: String): String = when (operation) {
        "transfer", "recurrent_transfer" -> L.native.wallet.opTransfer
        "transfer_to_vesting" -> L.native.wallet.opPowerUp
        "withdraw_vesting" -> L.native.wallet.opPowerDownChange
        "delegate_vesting_shares" -> L.native.wallet.opDelegation
        "transfer_to_savings" -> L.native.wallet.opSavingsDeposit
        "transfer_from_savings" -> L.native.wallet.opSavingsWithdrawal
        "convert", "collateralized_convert" -> L.native.wallet.opConversion
        "limit_order_create" -> L.native.wallet.opSwap
        "limit_order_cancel" -> L.native.wallet.opOrderCancel
        "claim_reward_balance" -> L.native.wallet.opRewardClaim
        "set_withdraw_vesting_route" -> L.native.wallet.opWithdrawRouteChange
        "custom_json" -> L.native.wallet.opTokenOperation
        else -> operation.replace('_', ' ')
    }
}

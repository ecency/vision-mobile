package com.ecency.core.app

import com.ecency.core.api.wallet.Vests
import com.ecency.core.api.wallet.WalletAccount
import com.ecency.core.hive.ChainTime
import com.ecency.core.l10n.L
import com.ecency.core.l10n.LocaleFormat
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.roundToLong

/** RN en-US `profile.*` of another account's wallet tab. */
object ProfileWalletCopy {
    val UNCLAIMED_REWARDS: String get() = L.profile.unclaimedRewards
    val WALLET_DETAILS: String get() = L.profile.walletDetails
    val HIVE: String get() = L.native.profileWallet.hive
    val HIVE_POWER: String get() = L.profile.hivePower // profile.hive_power
    val HIVE_DOLLARS: String get() = L.profile.hiveDollars
    val SAVINGS: String get() = L.profile.savings

    /** profile.next_power_text, the hours, profile.hours */
    fun nextPowerDown(hours: Long) = L.native.profileWallet.nextPowerDown(hours = hours.toInt())
}

/** A wallet line: [label] (empty on a detail line under the one before), the value, and whether it is the HP net line. */
data class WalletLine(val label: String, val value: String, val detail: Boolean = false, val net: Boolean = false, val savings: Boolean = false)

data class ProfileWalletState(
    val loading: Boolean = true,
    val error: AppError? = null,
    /** RN _getUnclaimedText ("1.234 HIVE 0.5 HBD 3.2 HP"), null without rewards. */
    val unclaimed: String? = null,
    val lines: List<WalletLine> = emptyList(),
    val powerDown: String? = null,
)

/**
 * RN profile's wallet tab for another account (walletView with `isShowDropdowns={false}`): the unclaimed rewards as
 * text and the Wallet Details: HIVE, staked HIVE with delegated, received and net lines, HBD, savings in HIVE and HBD,
 * and the next power down. Read only: no claim, transfer or other action.
 */
class ProfileWalletModel internal constructor(private val env: AppEnvironment, val username: String) :
    StateModel<ProfileWalletState>(ProfileWalletState(), env.dispatchers) {
    private var started = false

    fun start() {
        if (started) return
        started = true
        load()
    }

    fun refresh() = load()

    private fun load() {
        setState { it.copy(loading = true, error = null) }
        scope.launch {
            try {
                val (account, perMVests) = coroutineScope {
                    val a = async { env.walletApi.account(username) }
                    val p = async { env.walletApi.hivePerMVests() }
                    a.await() to p.await()
                }
                if (account == null) {
                    setState { it.copy(loading = false, error = AppError(ErrorKind.NOT_FOUND, L.native.profileWallet.accountNotFound, retryable = false)) }
                    return@launch
                }
                setState { ProfileWalletState(loading = false, unclaimed = unclaimed(account), lines = lines(account, perMVests), powerDown = powerDown(account, env.nowMillis())) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setState { it.copy(loading = false, error = AppError.from(e)) }
            }
        }
    }

    companion object {
        /** RN `Math.round(v * 1000) / 1000` printed as a JS number, in the app language's digits and decimal separator. */
        fun round3(v: Double): String {
            val s = Num.fixed(v, 3)
            return LocaleFormat.posix(if ('.' in s) s.trimEnd('0').trimEnd('.') else s, grouping = false)
        }

        fun unclaimed(a: WalletAccount): String? {
            val hive = Vests.amount(a.rewardHive)
            val hbd = Vests.amount(a.rewardHbd)
            val hp = Vests.amount(a.rewardVestingHive)
            if (hive <= 0 && hbd <= 0 && hp <= 0) return null
            return listOfNotNull(
                hive.takeIf { it != 0.0 }?.let { "${round3(it)} HIVE" },
                hbd.takeIf { it != 0.0 }?.let { "${round3(it)} HBD" },
                hp.takeIf { it != 0.0 }?.let { "${round3(it)} HP" },
            ).joinToString(" ")
        }

        fun lines(a: WalletAccount, hivePerMVests: Double): List<WalletLine> {
            fun hp(vests: String) = Vests.toHp(Vests.amount(vests), hivePerMVests)
            val own = hp(a.vestingShares)
            val delegated = hp(a.delegatedVestingShares)
            val received = hp(a.receivedVestingShares)
            val out = mutableListOf(
                WalletLine(ProfileWalletCopy.HIVE, "${round3(Vests.amount(a.balance))} HIVE"),
                WalletLine(ProfileWalletCopy.HIVE_POWER, "${round3(own)} HP"),
            )
            if (delegated > 0) out += WalletLine("", "- ${round3(delegated)} HP", detail = true)
            if (received > 0) out += WalletLine("", "+ ${round3(received)} HP", detail = true)
            if (delegated > 0 || received > 0) out += WalletLine("", "= ${round3(own - delegated + received)} HP", detail = true, net = true)
            out += WalletLine(ProfileWalletCopy.HIVE_DOLLARS, "$${round3(Vests.amount(a.hbdBalance))}")
            out += WalletLine(ProfileWalletCopy.SAVINGS, "${round3(Vests.amount(a.savingsBalance))} HIVE", savings = true)
            out += WalletLine("", "$${round3(Vests.amount(a.savingsHbdBalance))}", detail = true, savings = true)
            return out
        }

        /** RN showPowerDown: hours until next_vesting_withdrawal (rounded), when one runs. */
        fun powerDown(a: WalletAccount, nowMillis: Long): String? {
            if (a.nextVestingWithdrawal == WalletAccount.NO_WITHDRAWAL) return null
            val at = runCatching { ChainTime.toEpochSeconds(a.nextVestingWithdrawal.take(19)) * 1000 }.getOrNull() ?: return null
            return ProfileWalletCopy.nextPowerDown((abs(at - nowMillis) / 3_600_000.0).roundToLong())
        }
    }
}

package com.ecency.core.app

import com.ecency.core.api.InvalidInputException
import com.ecency.core.api.broadcast.Amounts
import com.ecency.core.api.broadcast.WalletOps
import com.ecency.core.auth.HiveUriTest
import com.ecency.core.hive.Asset
import com.ecency.core.hive.WalletGoldenVectorTest
import com.ecency.core.l10n.L10n
import com.ecency.core.l10n.LocaleFormat
import com.ecency.core.l10n.LocaleFormatter
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/**
 * Chain bound text never follows the app language (owner decision 10-22): operation amounts ("1.000 HIVE"), memos,
 * custom_json quantities, hive-uri links and the dApp bridge stay POSIX in every language, while display numbers do
 * localize. The broadcast suites below assert the exact operation JSON the SDK builds; here they run again with the
 * app language set to Arabic (Arabic-Indic digits, "٫" decimal point), German (comma decimal point, dot grouping),
 * Hindi (Indian grouping), Persian and Bengali (their own digits), so a localized number anywhere on the signing path
 * fails them.
 */
class ChainAmountsLocaleTest {
    private val exotic = listOf("ar-SA", "de-DE", "hi-IN", "fa-IR", "bn-BD")

    @AfterTest
    fun english() = L10n.setLanguage(L10n.SOURCE)

    /** Runs [block] in each exotic language, first checking that the display formatter really is localized. */
    private fun inEveryLanguage(block: (String) -> Unit) {
        for (id in exotic) {
            L10n.setLanguage(id)
            assertNotEquals("1,234,567.5", LocaleFormat.decimal(1_234_567.5), "$id is not active")
            block(id)
        }
    }

    @Test
    fun walletBroadcastsSignTheSameOperationsInEveryLanguage() = inEveryLanguage {
        WalletModelsTest().run {
            everyHiveTransferTypeBuildsTheSdkOperation()
            aRecurrentTransferNeedsTwoExecutionsAndPrefillsAnExistingSchedule()
            pointsAndEngineTransfersUseTheirCustomJsonAndTheTokensPrecision()
            aMultiRecipientTransferIsOneTransactionWithOneActionAndNeverSendsTwice()
            claimingAnEngineTokenIsAPostingScotClaim()
        }
        WalletActionsTest().run {
            delegatingTakesTheExistingDelegationIntoTheMaxAndSendsTheNewTotal()
            theMaxDelegatesTheExactVestsNotARoundedHpFigure()
            zeroUndelegatesOnlyAnExistingDelegationAndTooMuchIsRefused()
            aPowerDownSendsVestsAndShowsTheWeeklyEstimate()
            stoppingARunningPowerDownSendsZeroAndRoutesUseChainUnits()
            aSwapIsOneLimitOrderAndReportsPendingWhileTheOrderIsOpen()
            promotingNeedsAnExistingPostAndEnoughPoints()
            boostPlusUsesTheServerPricesAndIsRefusedWhileABoostRuns()
            aTipGoesToTheAuthorWithRnsMemoInEachCurrency()
        }
    }

    @Test
    fun serializationVectorsAndHiveUriLinksAreTheSameInEveryLanguage() = inEveryLanguage {
        WalletGoldenVectorTest().run {
            everyVectorSerializesLikeDhiveHashesLikeTheSdkAndSignsDeterministically()
            theBuildersMatchTheSdkBuilders()
            amountsTruncateLikeRn()
            decimalsCompareExactly()
        }
        HiveUriTest().run {
            encodesLikeTheLibrary()
            formatsAmountsAndRefusesOtherAssets()
            amountFormattingFollowsParseFloatAndToFixed()
        }
    }

    @Test
    fun dappBridgeQrAndAmountHelpersArePosixInEveryLanguage() {
        fun build(type: String, vararg fields: Pair<String, String>) =
            BridgeOps.build(type, JsonObject(fields.associate { (k, v) -> k to JsonPrimitive(v) }), "spike-voter", { 500.0 }, { 7L }).toString()
        val transfer = build("transfer", "to" to "spike-bob", "amount" to "1234.5", "currency" to "HIVE", "memo" to "1.5 HIVE")
        val delegation = build("delegation", "delegatee" to "spike-bob", "amount" to "1234.5", "unit" to "HP")
        val qr = ReceiveQr.uri("spike-bob")
        inEveryLanguage { id ->
            assertEquals(transfer, build("transfer", "to" to "spike-bob", "amount" to "1234.5", "currency" to "HIVE", "memo" to "1.5 HIVE"), id)
            assertEquals(delegation, build("delegation", "delegatee" to "spike-bob", "amount" to "1234.5", "unit" to "HP"), id)
            assertEquals(qr, ReceiveQr.uri("spike-bob"), id)
            assertEquals("1234.500 HIVE", Amounts.asset("1234.5", "HIVE"), id)
            assertEquals("1.5", Amounts.tokenQuantity("1.50000000"), id)
            assertEquals("1234.568", Num.fixed(1234.5678, 3), id)
            assertEquals("1234567.891 HIVE", Asset.parse("1234567.891 HIVE").toString(), id)
        }
        assertEquals("""[["transfer",{"from":"spike-voter","to":"spike-bob","amount":"1234.500 HIVE","memo":"1.5 HIVE"}]]""", transfer)
    }

    @Test
    fun aDisplayNumberIsNeverReadAsAChainAmount() {
        for (id in exotic + L10n.SOURCE) {
            val f = LocaleFormatter.of(id)
            val shown = f.token(1234.5, 3, "HIVE")
            // en-US groups "1,234.500 HIVE"; every other language changes the digits or separators
            assertFailsWith<IllegalArgumentException>(id) { Asset.parse(shown) }
            assertFailsWith<InvalidInputException>(id) { WalletOps.pointTransfer("spike-voter", "spike-bob", f.token(1234.5, 3, "POINT"), "") }
            assertFailsWith<InvalidInputException>(id) { WalletOps.engine("spike-voter", WalletOps.EngineAction.TRANSFER, "LEO", "spike-bob", f.fixed(1234.5, 3)) }
        }
        assertFailsWith<IllegalArgumentException> { Asset.parse("١٫٠٠٠ HIVE") }
        assertFailsWith<IllegalArgumentException> { Asset.parse("١.٠٠٠ HIVE") }
        assertFailsWith<IllegalArgumentException> { Asset.parse("1,000 HIVE") }
        assertFailsWith<InvalidInputException> { WalletOps.engine("spike-voter", WalletOps.EngineAction.TRANSFER, "LEO", "spike-bob", "١٫٥") }
        assertFailsWith<BridgeRefused> {
            BridgeOps.build("transfer", JsonObject(mapOf("to" to JsonPrimitive("spike-bob"), "amount" to JsonPrimitive("١٫٥"), "currency" to JsonPrimitive("HIVE"))), "spike-voter", { 500.0 }, { 7L })
        }
        // what is signed still parses
        WalletOps.pointTransfer("spike-voter", "spike-bob", "10.000 POINT", "")
        WalletOps.engine("spike-voter", WalletOps.EngineAction.TRANSFER, "LEO", "spike-bob", "1.2")
    }
}

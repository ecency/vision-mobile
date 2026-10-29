package com.ecency.core.app

import com.ecency.core.api.broadcast.Amounts
import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.l10n.L10n
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A keyboard in the app language types its own digits and decimal separator into amount fields; every field reads
 * them as the POSIX amount ([Amounts.typedText]) instead of dropping the key, and refuses a text whose separators are
 * ambiguous (one of them would be grouping). The signed text stays ASCII (ChainAmountsLocaleTest).
 */
class TypedAmountsLocaleTest {
    @AfterTest
    fun english() = L10n.setLanguage(L10n.SOURCE)

    /** Language, what its keyboard types for one and a half, and the same with grouping that must be refused. */
    private val keyboards = listOf(
        Triple("ar-SA", "١٫٥", "١٬٠٠٠٫٥"),
        Triple("fa-IR", "۱٫۵", "۱٬۰۰۰٫۵"),
        Triple("bn-BD", "১.৫", "১,০০০.৫"),
        Triple("hi-IN", "१.५", "१,०००.५"),
        Triple("de-DE", "1,5", "1.000,5"),
        Triple("en-US", "1.5", "1,000.5"),
    )

    @Test
    fun theReadingFoldsDigitsAndRefusesMixedSeparators() {
        assertEquals("1.5", Amounts.typedText("١٫٥"))
        assertEquals("0123456789", Amounts.typedText("٠١٢٣٤٥٦٧٨٩"))
        assertEquals("0123456789", Amounts.typedText("۰۱۲۳۴۵۶۷۸۹"))
        assertEquals("1.25", Amounts.typed("১,২৫৬", 2))
        assertEquals("1.000,5", Amounts.typedText("1.000,5"), "ambiguous: unchanged, so no reading accepts it")
        assertEquals("1٬000", Amounts.typedText("1٬000"))
        assertEquals("12", Amounts.typedText("12"))
        assertEquals(".", Amounts.typedText("٫"))
    }

    private suspend fun TestScope.transfer(w: WalletWorld): TransferModel {
        w.env.wallet.start()
        val m = w.env.transferModel(AppScreen.Transfer("transfer", "HIVE", "hive"))!!
        m.start()
        testScheduler.advanceUntilIdle()
        return m
    }

    @Test
    fun theTransferFieldTakesEveryKeyboard() = runTest {
        for ((id, typed, grouped) in keyboards) {
            L10n.setLanguage(id)
            val m = transfer(WalletWorld(this))
            m.setAmount(typed)
            assertEquals("1.5", m.current.amount, id)
            m.setAmount(grouped)
            assertEquals("1.5", m.current.amount, "$id keeps the previous text")
            m.close()
        }
    }

    @Test
    fun theSwapTipAndStakeFieldsTakeEveryKeyboard() = runTest {
        for ((id, typed, grouped) in keyboards) {
            L10n.setLanguage(id)
            val w = WalletWorld(this, tokensJson = """[{"symbol":"LEO","type":"ENGINE","meta":{"show":true}}]""")
            w.env.wallet.start()
            testScheduler.advanceUntilIdle()
            val trade = w.env.tradeModel("HIVE")
            try {
                trade.setAmount(typed)
                assertEquals("1.5", trade.current.fromAmount, id)
                trade.setAmount(grouped)
                assertEquals("1.5", trade.current.fromAmount, id)
            } finally {
                trade.close()
            }

            w.http["/private-api/post-tips"] = { HttpResult(200, """{"meta":{"count":0,"totals":{}},"list":[]}""") }
            val tip = w.env.tipModel("spike-author", "a-post")
            tip.start()
            testScheduler.advanceUntilIdle()
            tip.setCurrency("HIVE")
            tip.setAmount(typed)
            assertEquals("1.5", tip.current.amount, id)
            tip.setAmount(grouped)
            assertEquals("1.5", tip.current.amount, id)
            tip.close()

            val stake = w.env.delegateModel("spike-bob")
            stake.start()
            testScheduler.advanceUntilIdle()
            stake.setHp(typed)
            assertEquals("1.5", stake.current.hp, id)
            stake.setHp(grouped)
            assertEquals("1.5", stake.current.hp, id)
            stake.close()
        }
    }
}

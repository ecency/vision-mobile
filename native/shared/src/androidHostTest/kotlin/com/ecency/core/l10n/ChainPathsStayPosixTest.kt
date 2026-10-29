package com.ecency.core.l10n

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The signing and broadcast layers never call the display formatter, so what is signed cannot follow the app
 * language by accident: the operation builders and serializers (`api/broadcast`, `hive`), the signing, hive-uri and
 * auth code (`auth`, `secure`), the POSIX helpers (`Num`, `Amounts`) and the dApp bridge's operation builder. Display
 * formatting is [LocaleFormat] / [LocaleFormatter]; ChainAmountsLocaleTest checks the payloads themselves.
 */
class ChainPathsStayPosixTest {
    private val root = File("src/commonMain/kotlin/com/ecency/core")

    @Test
    fun theSigningPathDoesNotUseTheDisplayFormatter() {
        val files = listOf("api/broadcast", "hive", "auth", "secure").flatMap { dir ->
            root.resolve(dir).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        } + listOf(root.resolve("app/DappBridgeOps.kt"))
        assertTrue(files.size > 20, "found ${files.size} files")
        val offenders = files.filter { f -> f.readText().let { "LocaleFormat" in it || "L10n." in it } }
        assertEquals(emptyList(), offenders.map { it.relativeTo(root).path })
        // Num (POSIX) sits beside FromNow (display) in Formats.kt: its body must not reach the formatter either
        val num = root.resolve("app/Formats.kt").readText().substringAfter("object Num {").substringBefore("\n}\n")
        assertTrue("LocaleFormat" !in num && "L10n" !in num)
    }

    /**
     * The models that compose signed amounts (transfer, swap, tip, redeem, stake: their max(), useBalance(), typed
     * field and operation building) reach the display formatter only from the display members listed here; any
     * other line that does fails, so a localized number cannot drift into a field or an operation.
     */
    @Test
    fun amountComposersFormatForDisplayOnlyInTheirDisplayMembers() {
        val allowed = mapOf(
            "app/TransferModel.kt" to emptyList(),
            "app/WalletActionModels.kt" to listOf("import com.ecency.core.l10n.LocaleFormat", "rateText = ", "val priceText: String get() ="),
            "app/StakeModels.kt" to listOf("import com.ecency.core.l10n.LocaleFormat", "fun hpDisplay(", "val remainingHp: String get() =", "val weekly: String get() =", "StakeCopy.nextPowerDown("),
        )
        val bad = allowed.flatMap { (file, members) ->
            root.resolve(file).readLines().withIndex()
                .filter { (_, line) -> ("LocaleFormat" in line || "hpDisplay(" in line || "L10n." in line) && members.none { it in line } }
                .map { (i, line) -> "$file:${i + 1}: ${line.trim()}" }
        }
        assertEquals(emptyList(), bad)
    }
}

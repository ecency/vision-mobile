package com.ecency.core.api.wallet

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Asset icons: the portfolio's iconUrl first, else the same Ecency hosted logo the portfolio API serves. */
class AssetIconsTest {
    private fun item(json: String) = PortfolioItem.fromJson(Json.parseToJsonElement(json), "USD")!!

    @Test
    fun theServersIconWins() {
        val url = "https://images.ecency.com/DQmX/custom.png"
        assertEquals(url, item("""{"symbol":"HBD","layer":"hive","iconUrl":"$url"}""").iconUrl)
    }

    @Test
    fun withoutAnIconEachBaseAssetFallsBackToItsOwnLogo() {
        val hive = "https://images.ecency.com/DQmb7w4JNXXPKyzYVk615xGSfMTKLArQ7R9kWapuiY8dNHW/hive_icon.png"
        // HBD has its own logo (the API's hbd_icon.png), never HIVE's
        assertEquals(
            "https://images.ecency.com/DQmbXwGcbDvATn7x7XpZVPuYhB8YYTozDDvNtJanVpbdE8f/hbd_icon.png",
            item("""{"symbol":"HBD","layer":"hive"}""").iconUrl,
        )
        assertEquals(hive, item("""{"symbol":"HIVE","layer":"hive"}""").iconUrl)
        assertEquals(hive, item("""{"symbol":"HP","layer":"hive"}""").iconUrl)
        assertEquals(
            "https://images.ecency.com/DQmRhnpQt7zzfS67uDGv6TPLGmKFhWfNFrDLECP4M3bRo4f/ecency_logo_2x.png",
            item("""{"symbol":"POINTS","layer":"points"}""").iconUrl,
        )
        assertEquals(
            "https://images.ecency.com/DQmezqZDiHN1NKYsLBWYwvBQ1mNhypkoe5w4JPwjeC3E4xH/bnb.png",
            item("""{"symbol":"BNB","layer":"chain"}""").iconUrl,
        )
        assertEquals(
            "https://images.ecency.com/DQmebRVg81CP1kMeSEsMm8JHDMCu5Vkac5ndUHH4ppGSFU6/eth.png",
            item("""{"symbol":"ETH","layer":"chain"}""").iconUrl,
        )
    }

    @Test
    fun anUnknownTokenWithoutAnIconHasNone() {
        assertNull(item("""{"symbol":"LEO","layer":"engine"}""").iconUrl)
        // an engine token named like a base asset does not borrow its logo
        assertNull(item("""{"symbol":"HBD","layer":"engine"}""").iconUrl)
    }
}

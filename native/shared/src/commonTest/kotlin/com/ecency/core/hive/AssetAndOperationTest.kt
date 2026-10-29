package com.ecency.core.hive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AssetAndOperationTest {
    @Test
    fun assetsParseExactly() {
        assertEquals(1L, Asset.parse("0.001 HIVE").units)
        assertEquals(123456789L, Asset.parse("123456.789 HBD").units)
        assertEquals(1_000_000L, Asset.parse("1 VESTS").units)
        assertEquals("1.500 HBD", Asset.parse("1.5 HBD").toString())
        assertEquals("0.000001 VESTS", Asset.parse("0.000001 VESTS").toString())
    }

    @Test
    fun hiveAndHbdSerializeWithLegacySymbols() {
        fun bytes(a: String) = HiveWriter().apply { Asset.parse(a).write(this) }.toByteArray().toHex()
        assertEquals("0100000000000000" + "03" + "535445454d0000", bytes("0.001 HIVE"))
        assertEquals("0100000000000000" + "03" + "53424400000000", bytes("0.001 HBD"))
    }

    @Test
    fun invalidAssetsAreRejectedNotRounded() {
        assertFailsWith<IllegalArgumentException> { Asset.parse("0.0001 HIVE") }
        assertFailsWith<IllegalArgumentException> { Asset.parse("1.000 DOGE") }
        assertFailsWith<IllegalArgumentException> { Asset.parse("1,000 HIVE") }
        assertFailsWith<IllegalArgumentException> { Asset.parse("99999999999999999999 HIVE") }
    }

    @Test
    fun operationGuards() {
        assertFailsWith<IllegalArgumentException> { Vote("a", "b", "c", 10001) }
        assertFailsWith<IllegalArgumentException> { Transfer("a", "b", Asset.parse("0.000 HIVE"), "") }
        assertFailsWith<IllegalArgumentException> { Transfer("a", "b", Asset.parse("1.000000 VESTS"), "") }
        assertFailsWith<IllegalArgumentException> {
            CommentOptions("a", "p", Asset.parse("1.000 HBD"), 10000, true, true,
                listOf(Beneficiary("zed", 100), Beneficiary("abe", 100)))
        }
        assertFailsWith<IllegalArgumentException> {
            CommentOptions("a", "p", Asset.parse("1.000 HBD"), 10000, true, true,
                listOf(Beneficiary("abe", 6000), Beneficiary("zed", 5000)))
        }
    }
}

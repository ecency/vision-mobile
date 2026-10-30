package com.ecency.core.hive

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PrimitivesTest {
    @Test
    fun ripemd160StandardVectors() {
        mapOf(
            "" to "9c1185a5c5e9fc54612808977ee8f548b2258d31",
            "abc" to "8eb208f7e05d987a9b044a8e98c6b087f15a0bfc",
            "message digest" to "5d0689ef49d2fae572b881b123a85ffa21595f36",
            "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq" to "12a053384a9c0c88e405a06c27dcf49ada62eb2b",
            "a".repeat(1_000_000) to "52783243c1697bdbe16d37f97f68f08325dc1528",
        ).forEach { (input, expected) ->
            assertEquals(expected, Ripemd160.digest(input.encodeToByteArray()).toHex(), "len ${input.length}")
        }
    }

    @Test
    fun base58KnownValuesAndLeadingZeros() {
        assertEquals("StV1DL6CwTryKyV", Base58.encode("hello world".encodeToByteArray()))
        assertEquals("112", Base58.encode(byteArrayOf(0, 0, 1)))
        assertContentEquals(byteArrayOf(0, 0, 1), Base58.decode("112"))
        assertFailsWith<IllegalArgumentException> { Base58.decode("0OIl") }
    }

    @Test
    fun varintUsesSevenBitGroups() {
        fun enc(v: Int) = HiveWriter().apply { varint32(v) }.toByteArray().toHex()
        assertEquals("00", enc(0))
        assertEquals("7f", enc(127))
        assertEquals("8001", enc(128))
        assertEquals("ac02", enc(300))
    }

    @Test
    fun chainTimeIsUtcEpochSeconds() {
        assertEquals(0L, ChainTime.toEpochSeconds("1970-01-01T00:00:00"))
        assertEquals(951782400L, ChainTime.toEpochSeconds("2000-02-29T00:00:00"))
        assertEquals(1791288000L, ChainTime.toEpochSeconds("2026-10-06T12:00:00"))
        assertFailsWith<IllegalArgumentException> { ChainTime.toEpochSeconds("2026-10-06T12:00:00Z") }
    }
}

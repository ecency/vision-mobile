package com.ecency.core.hive

/** Little-endian binary writer matching hived's fc::raw serialization. */
class HiveWriter {
    private var buffer = ByteArray(256)
    private var size = 0

    private fun ensure(extra: Int) {
        if (size + extra > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, size + extra))
    }

    fun u8(value: Int) {
        ensure(1)
        buffer[size++] = value.toByte()
    }

    fun bool(value: Boolean) = u8(if (value) 1 else 0)

    fun u16(value: Int) {
        require(value in 0..0xffff) { "uint16 out of range: $value" }
        u8(value)
        u8(value ushr 8)
    }

    fun i16(value: Int) {
        require(value in Short.MIN_VALUE..Short.MAX_VALUE) { "int16 out of range: $value" }
        u8(value)
        u8(value ushr 8)
    }

    fun u32(value: Long) {
        require(value in 0..0xffffffffL) { "uint32 out of range: $value" }
        for (i in 0 until 4) u8((value ushr (8 * i)).toInt())
    }

    fun i64(value: Long) {
        for (i in 0 until 8) u8((value ushr (8 * i)).toInt())
    }

    /** Unsigned LEB128, as fc::unsigned_int. */
    fun varint32(value: Int) {
        require(value >= 0) { "varint32 must be non-negative: $value" }
        var v = value
        while (v >= 0x80) {
            u8((v and 0x7f) or 0x80)
            v = v ushr 7
        }
        u8(v)
    }

    fun bytes(value: ByteArray) {
        ensure(value.size)
        value.copyInto(buffer, size)
        size += value.size
    }

    /** Length-prefixed UTF-8 string. */
    fun string(value: String) {
        val utf8 = value.encodeToByteArray()
        varint32(utf8.size)
        bytes(utf8)
    }

    fun stringArray(values: List<String>) {
        varint32(values.size)
        values.forEach(::string)
    }

    fun toByteArray(): ByteArray = buffer.copyOf(size)
}

package com.ecency.core.hive

/** Hive mainnet chain id. */
val HIVE_CHAIN_ID: ByteArray = "beeab0de00000000000000000000000000000000000000000000000000000000".hexToBytes()

class Transaction(
    val refBlockNum: Int,
    val refBlockPrefix: Long,
    /** UTC, "YYYY-MM-DDTHH:MM:SS" as returned by hived. */
    val expiration: String,
    val operations: List<Operation>,
) {
    fun serialize(): ByteArray = HiveWriter().apply {
        u16(refBlockNum)
        u32(refBlockPrefix)
        u32(ChainTime.toEpochSeconds(expiration))
        varint32(operations.size)
        operations.forEach { it.write(this) }
        varint32(0) // extensions
    }.toByteArray()

    /** First 20 bytes of sha256(serialized), hex. */
    fun id(): String = sha256(serialize()).copyOf(20).toHex()

    /** sha256(chainId + serialized): the message every signature commits to. */
    fun digest(chainId: ByteArray = HIVE_CHAIN_ID): ByteArray = sha256(chainId + serialize())

    fun sign(key: PrivateKey, chainId: ByteArray = HIVE_CHAIN_ID): Signature = key.sign(digest(chainId))
}

internal object ChainTime {
    private val FORMAT = Regex("""^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})$""")

    fun toEpochSeconds(value: String): Long {
        val m = requireNotNull(FORMAT.matchEntire(value)) { "invalid chain time: $value" }
        val (y, mo, d, h, mi, s) = m.destructured.toList().map(String::toInt)
        require(mo in 1..12 && d in 1..31 && h in 0..23 && mi in 0..59 && s in 0..59) { "invalid chain time: $value" }
        return daysFromCivil(y, mo, d) * 86400L + h * 3600L + mi * 60L + s
    }

    /** Inverse of [toEpochSeconds]: "YYYY-MM-DDTHH:MM:SS" (UTC) for a non-negative epoch second. */
    fun fromEpochSeconds(epochSeconds: Long): String {
        require(epochSeconds >= 0) { "negative chain time" }
        val days = epochSeconds / 86400
        val secs = epochSeconds % 86400
        // H. Hinnant's civil_from_days.
        val z = days + 719468
        val era = z / 146097
        val doe = z - era * 146097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val day = doy - (153 * mp + 2) / 5 + 1
        val month = if (mp < 10) mp + 3 else mp - 9
        val year = yoe + era * 400 + if (month <= 2) 1 else 0
        fun two(v: Long) = v.toString().padStart(2, '0')
        return "${year.toString().padStart(4, '0')}-${two(month)}-${two(day)}T${two(secs / 3600)}:${two(secs % 3600 / 60)}:${two(secs % 60)}"
    }

    /** Days since 1970-01-01 (H. Hinnant's algorithm). */
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = (if (month <= 2) year - 1 else year).toLong()
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val mp = (month + 9) % 12
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097 + doe - 719468
    }
}

private operator fun <T> List<T>.component6(): T = this[5]

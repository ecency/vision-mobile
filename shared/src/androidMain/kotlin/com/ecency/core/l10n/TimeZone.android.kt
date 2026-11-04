package com.ecency.core.l10n

internal actual fun localOffsetMillis(epochMillis: Long): Long = java.util.TimeZone.getDefault().getOffset(epochMillis).toLong()

package com.ecency.core.api.broadcast

import com.ecency.core.hive.ChainTime
import com.ecency.core.hive.CustomJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The on-chain side of "mark all as read", as @ecency/sdk's buildSetLastReadOps writes it. */
object NotificationOps {
    /**
     * Two posting `custom_json` operations, ids `notify` (hivemind) and `ecency_notify`, each
     * `["setLastRead",{"date":"<UTC, no milliseconds>"}]`.
     */
    fun setLastRead(account: String, nowMillis: Long): JsonArray {
        val json = JsonArray(
            listOf(JsonPrimitive("setLastRead"), buildJsonObject { put("date", ChainTime.fromEpochSeconds(nowMillis.coerceAtLeast(0) / 1000)) }),
        ).toString()
        return JsonArray(listOf("notify", "ecency_notify").map { id -> OperationJson.encode(CustomJson(emptyList(), listOf(account), id, json)) })
    }
}

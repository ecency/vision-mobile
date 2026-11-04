package com.ecency.core.api.ecency

import com.ecency.core.api.model.arr
import com.ecency.core.api.model.double
import com.ecency.core.api.model.int
import com.ecency.core.api.model.obj
import com.ecency.core.api.model.str
import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.auth.AuthHttp
import com.ecency.core.auth.AuthHttpRequest
import com.ecency.core.auth.EcencyPrivateApi
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One row of `/private-api/ai-assist-price`. */
data class AiAssistPrice(val action: String, val cost: Double, val freeRemaining: Int)

data class AiImagePrice(val aspectRatio: String, val cost: Double)

data class AiPowerTier(val power: Int, val multiplier: Double)

/** `/private-api/ai-generate-price`: the base cost per aspect ratio and the power tiers that multiply it. */
data class AiImagePrices(val prices: List<AiImagePrice>, val power: List<AiPowerTier>)

/** `/private-api/ai-transcribe-price`: dictation is billed per started unit of [unitSeconds]. */
data class AiTranscribePrice(val unitSeconds: Int, val unitCost: Double, val freeRemaining: Int, val maxSeconds: Int) {
    /** RN estimatedCost: whole units rounded up, one at least, minus the free ones. */
    fun cost(seconds: Int): Double {
        val units = maxOf(1, (seconds + unitSeconds - 1) / unitSeconds)
        return maxOf(0, units - freeRemaining) * unitCost
    }
}

/**
 * The SDK's AI endpoints on Ecency's private API (`modules/ai`), which spend Ecency points. Prices and the
 * balance are read here; the paid calls return the raw answer, because what a status means for the charge is
 * decided in one place ([com.ecency.core.app.PaidCalls.classify]). No call here is ever retried.
 */
class AiApi(private val private: EcencyPrivateApi, private val http: AuthHttp, private val host: String) {
    /** SDK getAiAssistPriceQueryOptions; rows without an action or a sane cost are dropped. */
    suspend fun assistPrices(username: String): List<AiAssistPrice> =
        json(private.post("ai-assist-price", username)).arr().orEmpty().mapNotNull { e ->
            val o = e.obj() ?: return@mapNotNull null
            AiAssistPrice(o.str("action") ?: return@mapNotNull null, cost(o, "cost") ?: return@mapNotNull null, (o.int("free_remaining") ?: 0).coerceAtLeast(0))
        }

    /** SDK getAiGeneratePriceQueryOptions. */
    suspend fun imagePrices(username: String): AiImagePrices {
        val o = json(private.post("ai-generate-price", username)).obj() ?: throw IllegalStateException("malformed prices")
        return AiImagePrices(
            o["prices"].arr().orEmpty().mapNotNull { e ->
                val p = e.obj() ?: return@mapNotNull null
                AiImagePrice(p.str("aspect_ratio") ?: return@mapNotNull null, cost(p, "cost") ?: return@mapNotNull null)
            },
            o["power"].arr().orEmpty().mapNotNull { e ->
                val p = e.obj() ?: return@mapNotNull null
                AiPowerTier(p.int("power")?.takeIf { it in 1..64 } ?: return@mapNotNull null, cost(p, "multiplier")?.takeIf { it > 0 } ?: return@mapNotNull null)
            },
        )
    }

    /** SDK getPointsQueryOptions' first call: `POST /private-api/points {username}`, the balance as the server prints it. */
    suspend fun points(username: String): String? {
        val response = withTimeoutOrNull(EcencyPrivateApi.TIMEOUT_MILLIS) {
            http.send(
                AuthHttpRequest(
                    "POST", "$host/private-api/points", mapOf("Content-Type" to "application/json"),
                    buildJsonObject { put("username", username.removePrefix("@")) }.toString(),
                ),
            )
        } ?: throw IllegalStateException("timeout")
        return json(response).obj()?.str("points")
    }

    /** SDK useAiAssist: `{code, us, action, text, idempotency_key}`. One request, never retried here. */
    suspend fun assist(username: String, action: String, text: String, idempotencyKey: String): HttpResult =
        private.post("ai-assist", username, buildJsonObject {
            put("us", username)
            put("action", action)
            put("text", text)
            put("idempotency_key", idempotencyKey)
        }, EcencyPrivateApi.PAID_TIMEOUT_MILLIS)

    /** SDK useGenerateImage: `{code, us, prompt, aspect_ratio, power, idempotency_key}`. One request, never retried here. */
    suspend fun generateImage(username: String, prompt: String, aspectRatio: String, power: Int, idempotencyKey: String): HttpResult =
        private.post("ai-generate-image", username, buildJsonObject {
            put("us", username)
            put("prompt", prompt)
            put("aspect_ratio", aspectRatio)
            put("power", power)
            put("idempotency_key", idempotencyKey)
        }, EcencyPrivateApi.PAID_TIMEOUT_MILLIS)

    /** SDK getAiTranscribePriceQueryOptions. Null when the answer has no usable unit. */
    suspend fun transcribePrice(username: String): AiTranscribePrice? {
        val o = json(private.post("ai-transcribe-price", username)).obj() ?: return null
        return AiTranscribePrice(
            unitSeconds = o.int("unit_seconds")?.takeIf { it in 1..3600 } ?: return null,
            unitCost = cost(o, "unit_cost") ?: return null,
            freeRemaining = (o.int("free_remaining") ?: 0).coerceAtLeast(0),
            // RN DEFAULT_MAX_SECONDS when the server names none
            maxSeconds = o.int("max_seconds")?.takeIf { it in 2..3600 } ?: 300,
        )
    }

    /**
     * SDK useAiTranscribe: a multipart form with `code`, `duration_ms`, `idempotency_key` and the `audio` file
     * (no `us`: the server takes the account from the code). One request, never retried here.
     */
    suspend fun transcribe(username: String, audio: ByteArray, durationMillis: Long, idempotencyKey: String, boundary: String): HttpResult =
        private.postMultipart(
            "ai-transcribe", username, mapOf("duration_ms" to durationMillis.toString(), "idempotency_key" to idempotencyKey),
            "audio", "dictation.m4a", "audio/m4a", audio, boundary,
        )

    private fun cost(o: JsonObject, key: String): Double? = o.double(key)?.takeIf { it.isFinite() && it >= 0 && it < 1e9 }

    private fun json(response: HttpResult): JsonElement? {
        if (response.status !in 200..299) throw IllegalStateException("status ${response.status}")
        return runCatching { Json.parseToJsonElement(response.body) }.getOrNull()
    }
}

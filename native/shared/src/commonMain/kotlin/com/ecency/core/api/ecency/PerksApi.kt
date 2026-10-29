package com.ecency.core.api.ecency

import com.ecency.core.api.EcencyHttpException
import com.ecency.core.api.EcencyTimeoutException
import com.ecency.core.api.json.SafeJson
import com.ecency.core.api.model.int
import com.ecency.core.api.model.obj
import com.ecency.core.api.model.str
import com.ecency.core.auth.AuthHttp
import com.ecency.core.auth.AuthHttpRequest
import com.ecency.core.auth.EcencyPrivateApi
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** SDK `GetGameStatus`: whether a spin is left ([remaining]), the key that claims it, and when the next free one comes. */
data class GameStatus(val status: Int?, val key: String?, val remaining: Int, val nextDate: String?) {
    /** SDK/RN: status 18 means no spin is left today. */
    val noSpinLeft: Boolean get() = status == NO_SPIN_LEFT

    companion object {
        const val NO_SPIN_LEFT = 18

        /** RN `status === 3`: the claimed spin came from a pack, so 5 are shown until the status says otherwise. */
        const val PACK = 3

        /** An untrusted answer: only a JSON object is a status; a key is a short token. */
        fun parse(text: String): GameStatus? {
            val o = SafeJson.parseOrNull(text, 4).obj() ?: return null
            return GameStatus(
                status = o.int("status"),
                key = o.str("key")?.takeIf { it.isNotBlank() && it.length <= MAX_KEY && it.none(Char::isWhitespace) },
                remaining = (o.int("remaining") ?: 0).coerceIn(0, 1_000),
                nextDate = o.str("next_date")?.takeIf { it.length <= 40 },
            )
        }

        private const val MAX_KEY = 256
    }
}

/** SDK `StreakFreezeBuyResult`: the freezes owned and the Points balance after the debit. */
data class StreakFreezeResult(val owned: Int?, val points: Double?)

/** A refused game, freeze or quests request, with the server's own `message` when it sent one as JSON. */
class PerksHttpException(status: Int, val serverMessage: String?) : EcencyHttpException(status, serverMessage ?: "Ecency API error $status")

/**
 * The SDK's perks calls on Ecency's private API: quests (`getQuestsQueryOptions`), the spin game
 * (`getGameStatusCheckQueryOptions`, `gameClaimRequest`) and the streak freeze (`buyStreakFreezeRequest`). Every
 * call is sent once; a caller that needs another try asks again.
 */
class PerksApi(private val privateApi: EcencyPrivateApi, private val http: AuthHttp, private val host: String) {
    /**
     * `POST /private-api/quests {username}`: public (no code), read only. The answer's text, parsed by the caller.
     * @throws EcencyHttpException with the refused status, 0 on timeout.
     */
    @Throws(Exception::class)
    suspend fun quests(username: String): String {
        val body = buildJsonObject { put("username", username.removePrefix("@")) }.toString()
        val response = withTimeoutOrNull(EcencyPrivateApi.TIMEOUT_MILLIS) {
            http.send(AuthHttpRequest("POST", "$host/private-api/quests", mapOf("Content-Type" to "application/json"), body))
        } ?: throw EcencyTimeoutException()
        if (response.status !in 200..299) throw EcencyHttpException(response.status, "Failed to fetch quests: ${response.status}")
        return response.body
    }

    /**
     * `POST /private-api/get-game {game_type: "spin", code}`. Read only.
     * @throws PerksHttpException for a refused or unreadable answer, [EcencyTimeoutException] without one.
     */
    @Throws(Exception::class)
    suspend fun gameStatus(username: String): GameStatus {
        val response = privateApi.post("get-game", username, buildJsonObject { put("game_type", SPIN) })
        if (response.status !in 200..299) throw PerksHttpException(response.status, serverMessage(response.body))
        return GameStatus.parse(response.body) ?: throw PerksHttpException(response.status, null)
    }

    /**
     * SDK `gameClaimRequest`: `POST /private-api/post-game {game_type, code, key}` with the [key] the status handed
     * out. Returns the score; the server ties a claim to its key, so a key never pays twice.
     */
    @Throws(Exception::class)
    suspend fun claimSpin(username: String, key: String): Int {
        val response = privateApi.post("post-game", username, buildJsonObject { put("game_type", SPIN); put("key", key) })
        if (response.status !in 200..299) throw PerksHttpException(response.status, serverMessage(response.body))
        val o = SafeJson.parseOrNull(response.body, 4).obj() ?: throw PerksHttpException(response.status, null)
        return (o.int("score") ?: 0).coerceIn(0, 1_000_000)
    }

    /**
     * SDK `buyStreakFreezeRequest`: `POST /private-api/streak-freeze/buy {code, idempotency_key}`. Spends Points;
     * the server is idempotent per key. Sent once.
     * @throws PerksHttpException for a refusal (402 not enough Points, 409 already at the maximum).
     */
    @Throws(Exception::class)
    suspend fun buyStreakFreeze(username: String, idempotencyKey: String): StreakFreezeResult {
        val response = privateApi.post("streak-freeze/buy", username, buildJsonObject { put("idempotency_key", idempotencyKey) })
        if (response.status !in 200..299) throw PerksHttpException(response.status, serverMessage(response.body))
        // an unreadable 200 says nothing about the charge: the caller treats it as a lost answer
        val o = SafeJson.parseOrNull(response.body, 4).obj() ?: throw EcencyHttpException(502, "malformed streak freeze answer")
        return StreakFreezeResult(
            o.int("owned")?.takeIf { it >= 0 },
            (o["points"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 },
        )
    }

    /**
     * SDK `userActivity` (RN ePoint.ts): `POST /private-api/usr-activity {ty, bl?, tx?, code}` records a points
     * earning action (check-in, login, post, comment, vote, reblog).
     */
    @Throws(Exception::class)
    suspend fun userActivity(username: String, type: Int, txId: String?, blockNum: Long?) {
        val fields = buildJsonObject {
            put("ty", type)
            blockNum?.takeIf { it > 0 }?.let { put("bl", it) }
            txId?.takeIf { it.isNotEmpty() }?.let { put("tx", it) }
        }
        val response = privateApi.post("usr-activity", username, fields)
        if (response.status !in 200..299) throw EcencyHttpException(response.status)
    }

    private fun serverMessage(body: String): String? =
        (SafeJson.parseOrNull(body, 4) as? JsonObject)?.str("message")?.trim()?.takeIf { it.isNotEmpty() && it.length <= 200 }

    companion object {
        const val SPIN = "spin"
    }
}

package com.ecency.core.api.games

import com.ecency.core.api.model.arr
import com.ecency.core.api.model.asDouble
import com.ecency.core.api.model.asLong
import com.ecency.core.api.model.bool
import com.ecency.core.api.model.int
import com.ecency.core.api.model.long
import com.ecency.core.api.model.obj
import com.ecency.core.api.model.str
import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.auth.AuthHttp
import com.ecency.core.auth.AuthHttpRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** A door's question type (web `DoorKind`). */
enum class AdventDoorKind { OPEN, QUIZ, POLL }

/** What a door pays (web `RewardKind`). */
enum class AdventRewardKind { POINTS, HIVE, HP, DELEGATION, SPOTLIGHT }

/** The server's door status (web `DoorStatus`): CLOSED is past its close and not drawn yet. */
enum class AdventDoorStatus { LOCKED, OPEN, CLOSED, DRAWN }

/** A prize's payout (web `PayoutStatus`). */
enum class AdventPayoutStatus { PENDING, PAID, FAILED, UNCONFIRMED, CANCELLED }

data class AdventReward(val kind: AdventRewardKind, val amounts: List<Double>)

/** One door as the calendar shows it (web `PublicDoor`); [prompt] and [choices] only once it unlocked. */
data class AdventDoor(
    val day: Int,
    val unlockAtMillis: Long,
    val closeAtMillis: Long,
    val kind: AdventDoorKind,
    val reward: AdventReward,
    val status: AdventDoorStatus,
    val prompt: String?,
    val choices: List<String>,
)

data class AdventPrize(val place: Int, val kind: AdventRewardKind, val amount: Double, val status: AdventPayoutStatus)

/** The Points for answering a door on time; [paid] false while on its way. */
data class AdventParticipation(val amount: Double, val paid: Boolean)

/** The signed-in account's state of one door (web `MeDoor`). */
data class AdventMeDoor(
    val opened: Boolean,
    val answered: Boolean,
    val late: Boolean,
    val prize: AdventPrize?,
    val participation: AdventParticipation?,
)

data class AdventGrandPrize(val place: Int, val amount: Double, val status: AdventPayoutStatus)

data class AdventMe(val account: String, val doors: Map<Int, AdventMeDoor>, val answered: Int, val grand: AdventGrandPrize?)

/** `GET /v1/advent/{season}` (web `SeasonState`). */
data class AdventSeasonData(val season: Int, val doors: List<AdventDoor>, val poolLeft: Double?, val me: AdventMe?)

/** The account's entry in an opened door (web `OpenedDoor.entry`). */
data class AdventEntry(val answered: Boolean, val answer: String?, val choice: Int?, val late: Boolean, val wave: String?)

data class AdventOpened(val door: AdventDoor, val entry: AdventEntry)

data class AdventWinner(val place: Int, val account: String, val cancelled: Boolean)

data class AdventSkip(val account: String, val cause: String)

/** `GET .../doors/{day}/result` (web `DoorResult`). */
data class AdventResult(
    val day: Int,
    val seedBlock: Long?,
    val seedId: String?,
    val rule: String,
    val eligible: List<String>,
    val skipped: List<AdventSkip>,
    val winners: List<AdventWinner>,
    val entries: Int,
    /** The right answer of a quiz; null for a poll or a written door. */
    val answerIndex: Int?,
    val tallies: List<Int>,
)

/** `GET /v1/advent/{season}/grand` (web `GrandDraw`). */
data class AdventGrand(
    val drawn: Boolean,
    val drawAtMillis: Long,
    val minDoors: Int,
    val amounts: List<Double>,
    val seedBlock: Long?,
    val winners: List<AdventWinner>,
)

/** A game session token for [account] (games-api `/v1/raidstead/session`, shared with Raidstead and the website). */
data class GameSession(val account: String, val token: String, val expiresAtMillis: Long) {
    override fun toString(): String = "GameSession(account=$account, expiresAtMillis=$expiresAtMillis, token=<redacted>)"
}

/** A refused or failed games API call: [status] 0 is no answer, [code] the server's `error` (web `AdventError`). */
class GamesApiException(val status: Int, val code: String, message: String) : Exception(message)

/**
 * The Ecency games API as ecency.com/advent calls it (web `features/advent/advent-api.ts`, `features/raidstead/client.ts`):
 * every call is JSON over [http], a game session token as `authorization: Bearer`, results and the grand draw public.
 * The vouched sign-in goes through ecency.com's own route ([vouchHost]) with the account's Ecency token.
 */
@OptIn(ExperimentalTime::class)
class AdventApi(private val http: AuthHttp, val base: String = BASE, val vouchHost: String = VOUCH_HOST) {
    /** Exchanges a signed login proof (web `encodeProof`) for a session. */
    @Throws(Exception::class)
    suspend fun session(proof: String): GameSession =
        session(call("POST", "$base/v1/raidstead/session", null, buildJsonObject { put("proof", proof) }.toString()))

    /** ecency.com vouches for [username] with the account's Ecency access token [code] (web `vouched`). */
    @Throws(Exception::class)
    suspend fun vouched(username: String, code: String): GameSession =
        session(call("POST", "$vouchHost/api/raidstead/session", null, buildJsonObject { put("username", username); put("code", code) }.toString()))

    /** Ends a session on the server (a logout); failures are the caller's to ignore. */
    @Throws(Exception::class)
    suspend fun endSession(token: String) {
        call("DELETE", "$base/v1/raidstead/session", token, null)
    }

    @Throws(Exception::class)
    suspend fun season(season: Int, token: String?): AdventSeasonData {
        val o = call("GET", "$base/v1/advent/$season", token, null)
        return AdventSeasonData(
            season = o.int("season") ?: season,
            doors = o["doors"].arr()?.mapNotNull { door(it.obj()) }.orEmpty().sortedBy { it.day },
            poolLeft = o["pool"].obj()?.get("left").asDouble(),
            me = o["me"].obj()?.let(::me),
        )
    }

    @Throws(Exception::class)
    suspend fun open(season: Int, day: Int, token: String): AdventOpened {
        val o = call("POST", "$base/v1/advent/$season/doors/$day/open", token, null)
        val door = door(o) ?: throw GamesApiException(200, "bad_answer", "Unexpected answer")
        val e = o["entry"].obj()
        return AdventOpened(
            door,
            AdventEntry(
                answered = e?.bool("answered") == true,
                answer = e?.str("answer"),
                choice = e?.get("choice").asLong()?.toInt(),
                late = e?.bool("late") == true,
                wave = e?.str("wave")?.takeIf { it.isNotEmpty() },
            ),
        )
    }

    /** A written [answer] or a [choice] index; true when the answer came late (counts for the calendar only). */
    @Throws(Exception::class)
    suspend fun answer(season: Int, day: Int, token: String, answer: String?, choice: Int?): Boolean {
        val body = buildJsonObject { if (answer != null) put("answer", answer) else put("choice", choice ?: 0) }
        return call("POST", "$base/v1/advent/$season/doors/$day/answer", token, body.toString()).bool("late") == true
    }

    /** Links the Wave [permlink] that shares the answer to the door. */
    @Throws(Exception::class)
    suspend fun wave(season: Int, day: Int, token: String, permlink: String) {
        call("POST", "$base/v1/advent/$season/doors/$day/wave", token, buildJsonObject { put("permlink", permlink) }.toString())
    }

    /** A drawn door's result; [GamesApiException] with code `not_drawn` before the draw. */
    @Throws(Exception::class)
    suspend fun result(season: Int, day: Int): AdventResult {
        val o = call("GET", "$base/v1/advent/$season/doors/$day/result", null, null)
        return AdventResult(
            day = o.int("day") ?: day,
            seedBlock = o["seedBlock"].asLong(),
            seedId = o.str("seedId"),
            rule = o.str("rule").orEmpty(),
            eligible = o["eligible"].arr()?.mapNotNull { (it as? JsonPrimitive)?.contentOrNullSafe() }.orEmpty(),
            skipped = o["skipped"].arr()?.mapNotNull { s -> s.obj()?.let { AdventSkip(it.str("account") ?: return@mapNotNull null, it.str("cause").orEmpty()) } }.orEmpty(),
            winners = winners(o["winners"]),
            entries = o.int("entries") ?: 0,
            answerIndex = o.int("answerIndex"),
            tallies = o["tallies"].arr()?.map { it.asLong()?.toInt() ?: 0 }.orEmpty(),
        )
    }

    /** The grand draw; null when none is set (404 `no_grand`). */
    @Throws(Exception::class)
    suspend fun grand(season: Int): AdventGrand? {
        val o = try {
            call("GET", "$base/v1/advent/$season/grand", null, null)
        } catch (e: GamesApiException) {
            if (e.status == 404) return null
            throw e
        }
        return AdventGrand(
            drawn = o.str("status") == "drawn",
            drawAtMillis = millis(o.str("drawAt")) ?: 0,
            minDoors = o.int("minDoors") ?: 0,
            amounts = o["amounts"].arr()?.mapNotNull { it.asDouble() }.orEmpty(),
            seedBlock = o["seedBlock"].asLong(),
            winners = winners(o["winners"]),
        )
    }

    private suspend fun call(method: String, url: String, token: String?, body: String?): JsonObject {
        val headers = buildMap {
            if (token != null) put("authorization", "Bearer $token")
            if (body != null) put("content-type", "application/json")
        }
        val response: HttpResult = try {
            http.send(AuthHttpRequest(method, url, headers, body))
        } catch (e: CancellationException) {
            throw e
        } catch (e: GamesApiException) {
            throw e
        } catch (e: Exception) {
            throw GamesApiException(0, "offline", e.message ?: "offline")
        }
        val json: JsonElement? = try {
            Json.parseToJsonElement(response.body)
        } catch (e: Exception) {
            // not reported: a body that is not JSON is the error below
            null
        }
        val o = json.obj()
        if (response.status !in 200..299) {
            throw GamesApiException(response.status, o?.str("error")?.take(60) ?: "error", o?.str("message")?.take(200) ?: "Request failed (${response.status})")
        }
        // an empty body is a plain "done"; anything else that is not JSON (a captive Wi-Fi page) is no answer at all
        if (o == null && response.body.isNotBlank()) throw GamesApiException(response.status, "bad_answer", "Unexpected answer")
        return o ?: JsonObject(emptyMap())
    }

    private fun session(o: JsonObject): GameSession = GameSession(
        account = o.str("account") ?: throw GamesApiException(200, "bad_answer", "Unexpected answer"),
        token = o.str("token")?.takeIf { it.isNotEmpty() } ?: throw GamesApiException(200, "bad_answer", "Unexpected answer"),
        expiresAtMillis = millis(o.str("expiresAt")) ?: 0,
    )

    private fun door(o: JsonObject?): AdventDoor? {
        if (o == null) return null
        val day = o.int("day")?.takeIf { it in 1..MAX_DOORS } ?: return null
        val reward = o["reward"].obj()
        return AdventDoor(
            day = day,
            unlockAtMillis = millis(o.str("unlockAt")) ?: 0,
            closeAtMillis = millis(o.str("closeAt")) ?: 0,
            kind = when (o.str("kind")) { "quiz" -> AdventDoorKind.QUIZ; "poll" -> AdventDoorKind.POLL; else -> AdventDoorKind.OPEN },
            reward = AdventReward(rewardKind(reward?.str("kind")), reward?.get("amounts").arr()?.mapNotNull { it.asDouble() }.orEmpty()),
            status = when (o.str("status")) { "open" -> AdventDoorStatus.OPEN; "closed" -> AdventDoorStatus.CLOSED; "drawn" -> AdventDoorStatus.DRAWN; else -> AdventDoorStatus.LOCKED },
            prompt = o.str("prompt")?.takeIf { it.isNotEmpty() },
            // a choice missing from the list would move the ones after it to the wrong index: no choices then
            choices = o["choices"].arr()?.map { (it as? JsonPrimitive)?.contentOrNullSafe() }?.takeIf { null !in it }?.filterNotNull().orEmpty(),
        )
    }

    private fun me(o: JsonObject): AdventMe? {
        val account = o.str("account") ?: return null
        val doors = o["doors"].obj()?.entries?.mapNotNull { (k, v) ->
            val day = k.toIntOrNull() ?: return@mapNotNull null
            val d = v.obj() ?: return@mapNotNull null
            day to AdventMeDoor(
                opened = d.bool("opened") == true,
                answered = d.bool("answered") == true,
                late = d.bool("late") == true,
                prize = d["prize"].obj()?.let { p -> AdventPrize(p.int("place") ?: 0, rewardKind(p.str("kind")), p["amount"].asDouble() ?: 0.0, payout(p.str("status"))) },
                participation = d["participation"].obj()?.let { p -> AdventParticipation(p["amount"].asDouble() ?: 0.0, p.str("status") == "paid") },
            )
        }?.toMap().orEmpty()
        val grand = o["grand"].obj()?.let { g -> AdventGrandPrize(g.int("place") ?: 0, g["amount"].asDouble() ?: 0.0, payout(g.str("status"))) }
        return AdventMe(account, doors, o.int("answered") ?: doors.values.count { it.answered }, grand)
    }

    private fun winners(e: JsonElement?): List<AdventWinner> = e.arr()?.mapNotNull { w ->
        w.obj()?.let { AdventWinner(it.int("place") ?: return@mapNotNull null, it.str("account") ?: return@mapNotNull null, it.bool("cancelled") == true) }
    }.orEmpty().sortedBy { it.place }

    private fun rewardKind(s: String?) = when (s) {
        "hive" -> AdventRewardKind.HIVE
        "hp" -> AdventRewardKind.HP
        "delegation" -> AdventRewardKind.DELEGATION
        "spotlight" -> AdventRewardKind.SPOTLIGHT
        else -> AdventRewardKind.POINTS
    }

    private fun payout(s: String?) = when (s) {
        "paid" -> AdventPayoutStatus.PAID
        "failed" -> AdventPayoutStatus.FAILED
        "unconfirmed" -> AdventPayoutStatus.UNCONFIRMED
        "cancelled" -> AdventPayoutStatus.CANCELLED
        else -> AdventPayoutStatus.PENDING
    }

    private fun JsonPrimitive.contentOrNullSafe(): String? = if (isString) content else null

    private fun millis(iso: String?): Long? = iso?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() }

    companion object {
        /** The games API (web `RAIDSTEAD_API`). */
        const val BASE = "https://games-api.ecency.com"

        /** The website, whose `/api/raidstead/session` route vouches for an Ecency login. */
        const val VOUCH_HOST = "https://ecency.com"

        const val MAX_DOORS = 24
    }
}

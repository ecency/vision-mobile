package com.ecency.core.app

import com.ecency.core.api.games.AdventApi
import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.auth.AuthHttpRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.io.encoding.Base64
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * An offline games API for the Advent Calendar (demo runs, UI tests, screenshots and model tests): 24 doors on the
 * season's real dates, answers kept per account, a draw two hours after a door closes with made-up winners. The
 * prompts are placeholders, never the season's real questions. A session is any well formed login proof or vouch.
 */
@OptIn(ExperimentalTime::class)
class DemoAdvent(private val nowMillis: () -> Long) {
    private data class Entry(val answer: String?, val choice: Int?, val late: Boolean, var wave: String? = null)

    private val entries = mutableMapOf<Pair<String, Int>, Entry>()
    private val prizes = mutableMapOf<Pair<String, Int>, Pair<Int, String>>()
    private val sessions = mutableMapOf<String, String>()

    /** Every call made, for tests (`METHOD path`). */
    val calls = mutableListOf<String>()

    /** Proofs and vouches seen: the proof JSON as decoded, the vouch `username|code`. */
    val signIns = mutableListOf<String>()

    /** Tests: tokens the API refuses (401) from now on. */
    val refused = mutableSetOf<String>()

    /** Tests: the vouch route refuses this many calls (401) before it accepts. */
    var refuseVouches = 0

    /** Tests: the login proof is refused (401) this many times, as for a phone clock far off. */
    var refuseProofs = 0

    /** Tests: a sign-in hands back the account's existing token (a session store that reuses sessions). */
    var reuseTokens = false

    /** Tests: links to `.../wave` fail with this status (0: linked as usual). */
    var waveStatus = 0

    /** Tests: answers to `.../answer` fail with this status (0: answered as usual). */
    var answerStatus = 0

    /** Demo runs: an account's first calendar comes with a few doors answered ([seedHistory]). */
    var autoSeed = false
    private val seeded = mutableSetOf<String>()

    fun kind(day: Int): String = when {
        day % 4 == 3 -> "quiz"
        day % 5 == 0 -> "poll"
        else -> "open"
    }

    private fun reward(day: Int): Pair<String, List<Int>> = when (day % 6) {
        3 -> "hive" to listOf(100, 60, 40)
        4 -> "hp" to listOf(100, 60, 40)
        5 -> "spotlight" to listOf(1000, 1000, 1000)
        else -> "points" to listOf(500, 300, 200)
    }

    private fun unlock(day: Int) = AdventSeason.FIRST_DOOR + (day - 1) * AdventSeason.DAY_MILLIS
    private fun close(day: Int) = unlock(day) + AdventSeason.DAY_MILLIS
    private fun drawAt(day: Int) = close(day) + 2 * 3_600_000L

    private fun status(day: Int): String {
        val now = nowMillis()
        return when {
            now < unlock(day) -> "locked"
            now < close(day) -> "open"
            now < drawAt(day) -> "closed"
            else -> "drawn"
        }
    }

    /** A demo account with a few doors answered, one late, one won (screenshots, the calendar's states). */
    fun seedHistory(account: String) {
        val today = AdventSeason.todayDoor(nowMillis()).takeIf { it > 0 } ?: return
        for (day in 1 until today) {
            if (day == 5) continue
            entries[account to day] = Entry(if (kind(day) == "open") "A demo answer for door $day." else null, if (kind(day) == "open") null else 0, late = day == 6, wave = null)
        }
        if (today > 3) prizes[account to 3] = 1 to "pending"
    }

    fun http(request: AuthHttpRequest): HttpResult? {
        val url = request.url
        if (url.startsWith(AdventApi.VOUCH_HOST + "/api/raidstead/session")) return vouch(request)
        if (!url.startsWith(AdventApi.BASE + "/v1/")) return null
        val path = url.removePrefix(AdventApi.BASE).substringBefore('?')
        calls += "${request.method} $path"
        if (path == "/v1/raidstead/session") return when (request.method) {
            "POST" -> proof(request)
            else -> HttpResult(200, "{}")
        }
        val token = request.headers["authorization"]?.removePrefix("Bearer ")
        if (token != null && token in refused) return HttpResult(401, """{"error":"unauthorized"}""")
        val account = token?.let { sessions[it] }
        val parts = path.removePrefix("/v1/advent/").split('/')
        if (parts.firstOrNull() != AdventSeason.SEASON.toString()) return HttpResult(404, """{"error":"no_season"}""")
        return when {
            parts.size == 1 -> ok(season(account))
            parts.size == 2 && parts[1] == "grand" -> ok(grand())
            parts.size == 4 && parts[1] == "doors" -> door(request, parts[2].toIntOrNull() ?: 0, parts[3], account)
            else -> HttpResult(404, """{"error":"not_found"}""")
        }
    }

    private fun ok(o: JsonObject) = HttpResult(200, o.toString())

    private fun proof(request: AuthHttpRequest): HttpResult {
        val proof = body(request)?.get("proof")?.jsonPrimitive?.content ?: return HttpResult(400, """{"error":"bad_request"}""")
        val json = runCatching { Base64.Default.decode(proof).decodeToString() }.getOrNull() ?: return HttpResult(401, """{"error":"unauthorized"}""")
        val o = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return HttpResult(401, """{"error":"unauthorized"}""")
        signIns += json
        if (refuseProofs > 0) {
            refuseProofs--
            return HttpResult(401, """{"error":"unauthorized"}""")
        }
        val account = runCatching { o["authors"]!!.let { (it as kotlinx.serialization.json.JsonArray)[0].jsonPrimitive.content } }.getOrNull()
            ?: return HttpResult(401, """{"error":"unauthorized"}""")
        return session(account)
    }

    private fun vouch(request: AuthHttpRequest): HttpResult {
        calls += "${request.method} /api/raidstead/session"
        val b = body(request) ?: return HttpResult(400, """{"error":"bad_request"}""")
        val user = b["username"]?.jsonPrimitive?.content.orEmpty()
        signIns += "$user|${b["code"]?.jsonPrimitive?.content}"
        if (refuseVouches > 0) {
            refuseVouches--
            return HttpResult(401, """{"error":"unauthorized"}""")
        }
        return session(user)
    }

    private fun session(account: String): HttpResult {
        val token = sessions.entries.firstOrNull { reuseTokens && it.value == account }?.key ?: "demo-game-$account-${sessions.size + 1}"
        sessions[token] = account
        val expires = Instant.fromEpochMilliseconds(nowMillis() + 24 * 3_600_000L).toString()
        return HttpResult(200, """{"token":"$token","account":"$account","expiresAt":"$expires"}""")
    }

    private fun body(request: AuthHttpRequest): JsonObject? = runCatching { Json.parseToJsonElement(request.body ?: "").jsonObject }.getOrNull()

    private fun doorJson(day: Int, account: String?): JsonObject = buildJsonObject {
        val st = status(day)
        put("day", day)
        put("unlockAt", Instant.fromEpochMilliseconds(unlock(day)).toString())
        put("closeAt", Instant.fromEpochMilliseconds(close(day)).toString())
        put("kind", kind(day))
        putJsonObject("reward") {
            val (k, amounts) = reward(day)
            put("kind", k)
            putJsonArray("amounts") { amounts.forEach { add(JsonPrimitive(it)) } }
        }
        put("status", st)
        if (st != "locked") {
            put("prompt", "[Door $day question]")
            if (kind(day) != "open") putJsonArray("choices") { listOf("[Choice A]", "[Choice B]", "[Choice C]").forEach { add(JsonPrimitive(it)) } }
        }
    }

    private fun season(account: String?): JsonObject {
        if (autoSeed && account != null && seeded.add(account)) seedHistory(account)
        return seasonJson(account)
    }

    private fun seasonJson(account: String?): JsonObject = buildJsonObject {
        put("season", AdventSeason.SEASON)
        putJsonArray("doors") { (1..AdventSeason.DOORS).forEach { add(doorJson(it, account)) } }
        putJsonObject("pool") { put("left", 18_420) }
        if (account != null) putJsonObject("me") {
            put("account", account)
            var answered = 0
            putJsonObject("doors") {
                for (day in 1..AdventSeason.DOORS) {
                    val e = entries[account to day] ?: continue
                    if (!e.late) answered++
                    putJsonObject(day.toString()) {
                        put("opened", true)
                        put("answered", true)
                        put("late", e.late)
                        prizes[account to day]?.let { (place, st) ->
                            putJsonObject("prize") { put("place", place); put("kind", reward(day).first); put("amount", reward(day).second[place - 1]); put("status", st) }
                        }
                        if (!e.late && status(day) == "drawn") putJsonObject("participation") { put("amount", 50); put("status", "paid") }
                    }
                }
            }
            put("answered", answered)
            put("reminders", false)
        }
    }

    private fun grand(): JsonObject = buildJsonObject {
        put("season", AdventSeason.SEASON)
        put("status", "scheduled")
        put("drawAt", Instant.fromEpochMilliseconds(AdventSeason.FIRST_DOOR + 24 * AdventSeason.DAY_MILLIS + 12 * 3_600_000L).toString())
        put("minDoors", 20)
        putJsonArray("amounts") { listOf(5000, 3000, 2000).forEach { add(JsonPrimitive(it)) } }
    }

    private fun door(request: AuthHttpRequest, day: Int, action: String, account: String?): HttpResult {
        if (day !in 1..AdventSeason.DOORS) return HttpResult(404, """{"error":"no_door"}""")
        if (action == "result") {
            if (status(day) != "drawn") return HttpResult(409, """{"error":"not_drawn"}""")
            return ok(buildJsonObject {
                put("day", day)
                put("seedBlock", 101_000_000 + day)
                put("seedId", "0" + day.toString().padStart(39, '0'))
                put("rule", "sha256(block id | account), lowest first")
                putJsonArray("eligible") { listOf("demo-one", "demo-two", "demo-three").forEach { add(JsonPrimitive(it)) } }
                putJsonArray("skipped") {}
                putJsonArray("winners") {
                    val first = prizes.entries.firstOrNull { it.key.second == day && it.value.first == 1 }?.key?.first ?: "demo-one"
                    listOf(first, "demo-two", "demo-three").forEachIndexed { i, a -> add(buildJsonObject { put("place", i + 1); put("account", a) }) }
                }
                put("entries", 212)
                if (kind(day) == "quiz") put("answerIndex", 0)
                if (kind(day) != "open") putJsonArray("tallies") { listOf(136, 51, 25).forEach { add(JsonPrimitive(it)) } }
            })
        }
        if (account == null) return HttpResult(401, """{"error":"unauthorized"}""")
        if (status(day) == "locked") return HttpResult(403, """{"error":"locked"}""")
        val e = entries[account to day]
        return when (action) {
            "open" -> ok(buildJsonObject {
                doorJson(day, account).forEach { (k, v) -> put(k, v) }
                putJsonObject("entry") {
                    put("answered", e != null)
                    put("answer", e?.answer?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull)
                    put("choice", e?.choice?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull)
                    put("late", e?.late ?: false)
                    put("wave", e?.wave?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull)
                }
            })
            "answer" -> {
                if (answerStatus != 0) return HttpResult(answerStatus, """{"error":"error"}""")
                if (e != null) return HttpResult(409, """{"error":"already_answered"}""")
                val b = body(request) ?: return HttpResult(400, """{"error":"bad_request"}""")
                val late = status(day) != "open"
                entries[account to day] = Entry(b["answer"]?.jsonPrimitive?.content, b["choice"]?.jsonPrimitive?.int, late)
                ok(buildJsonObject { put("ok", true); put("late", late) })
            }
            "wave" -> {
                if (waveStatus != 0) return HttpResult(waveStatus, """{"error":"error"}""")
                if (e == null) return HttpResult(409, """{"error":"not_answered"}""")
                e.wave = body(request)?.get("permlink")?.jsonPrimitive?.content
                ok(buildJsonObject { put("ok", true) })
            }
            else -> HttpResult(404, """{"error":"not_found"}""")
        }
    }
}

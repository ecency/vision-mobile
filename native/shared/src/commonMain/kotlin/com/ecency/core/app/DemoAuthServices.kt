package com.ecency.core.app

import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.auth.AuthHttp
import com.ecency.core.auth.HasSocket
import com.ecency.core.auth.HasSocketFactory
import com.ecency.core.auth.HiveSignerMessages
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Offline stand-ins for the fake-data runs of the apps (iOS `-fakeData`, UI tests, screenshots): nothing leaves
 * the process. A locally signed code exchanges for tokens of its author, `refresh-<user>` refreshes, every
 * HiveSigner API call is refused; the HiveAuth relay puts every request in the waiting state for
 * [waitMillis] and never approves it. Debug use only: the apps never pass it in release builds.
 */
object DemoAuthServices {
    /** The offline chat of the latest demo run (debug triggers: someone types, a message arrives). */
    var chat: DemoChat? = null
        private set

    fun create(nowMillis: () -> Long, waitMillis: Long = 5 * 60_000): AuthServices {
        val demoChat = DemoChat(nowMillis).also { chat = it }
        val http = AuthHttp { request ->
            demoChat.http(request)?.let { return@AuthHttp it }
            DemoWallet.http(request.url, request.body)?.let { return@AuthHttp it }
            demoPrivateApi(request.url, nowMillis(), request.body)?.let { return@AuthHttp it }
            // the email digest sheet: no subscription yet, and a subscribe that waits for its confirmation mail
            if ("/api/newsletter/" in request.url) {
                return@AuthHttp when {
                    request.url.endsWith("/unsubscribe-all") -> {
                        demoDigests = "[]"
                        HttpResult(200, "{}")
                    }
                    else -> when (request.method) {
                    "GET" -> HttpResult(200, """{"subscriptions":$demoDigests}""")
                    "POST" -> HttpResult(200, """{"status":"pending_confirmation"}""")
                    else -> HttpResult(200, "{}")
                    }
                }
            }
            // compose translation: the text comes back marked, nothing reaches the translation service
            if (request.url.endsWith("/translate")) return@AuthHttp demoTranslate(request.body)
            // the update check: only when a demo run asks for the prompt
            if (request.url == com.ecency.core.api.ecency.OnboardingApi.RELEASES_URL) {
                return@AuthHttp demoReleaseTag?.let { HttpResult(200, """{"tag_name":"$it"}""") } ?: HttpResult(503, "offline")
            }
            // the search screen: one synthetic page (fake authors, made-up text)
            if (request.url.endsWith("/search-api/search")) return@AuthHttp HttpResult(200, demoSearch())
            if (!request.url.endsWith("/auth-api/hs-token-refresh")) return@AuthHttp HttpResult(503, "offline")
            val code = runCatching { Json.parseToJsonElement(request.body ?: "{}").jsonObject["code"]?.jsonPrimitive?.content }.getOrNull().orEmpty()
            val user = if (code.startsWith("refresh-")) code.removePrefix("refresh-")
            else runCatching { HiveSignerMessages.decode(code).authors.first() }.getOrNull()
            if (user == null) HttpResult(400, "{}")
            else HttpResult(200, """{"username":"$user","access_token":"demo-access-$user","refresh_token":"refresh-$user","expires_in":604800}""")
        }
        val sockets = HasSocketFactory { host ->
            if (DemoChat.WEBSOCKET_PATH in host) return@HasSocketFactory demoChat.socket()
            val incoming = Channel<String>(Channel.UNLIMITED)
            object : HasSocket {
                override suspend fun send(text: String) {
                    val cmd = if ("\"auth_req\"" in text) "auth_wait" else if ("\"sign_req\"" in text) "sign_wait" else return
                    incoming.trySend("""{"cmd":"$cmd","uuid":"demo-request","expire":${nowMillis() + waitMillis}}""")
                }
                override val incoming: ReceiveChannel<String> = incoming
                override suspend fun close() { incoming.close() }
            }
        }
        return AuthServices(http, sockets)
    }

    private fun demoAssist(body: String?): HttpResult {
        val action = runCatching { Json.parseToJsonElement(body ?: "{}").jsonObject["action"]?.jsonPrimitive?.content }.getOrNull().orEmpty()
        val output = when (action) {
            "generate_title" -> """["A synthetic title","Another demo title"]"""
            "suggest_tags" -> """["demo","spike","fixture"]"""
            else -> "This is a synthetic answer of the demo assistant. Nothing was sent anywhere and nothing was charged."
        }
        return HttpResult(200, """{"action":"$action","output":${kotlinx.serialization.json.JsonPrimitive(output)},"cost":0,"is_free":true}""")
    }

    private fun demoTranslate(body: String?): HttpResult {
        val q = runCatching { Json.parseToJsonElement(body ?: "{}").jsonObject["q"]?.jsonPrimitive?.content }.getOrNull().orEmpty()
        return HttpResult(200, """{"translatedText":${kotlinx.serialization.json.JsonPrimitive("(demo translation) $q")}}""")
    }

    /** Synthetic answers of the Ecency private API calls the profile area makes (bookmarks, favourites, tags, Pro). */
    private fun demoPrivateApi(url: String, nowMillis: Long, body: String? = null): HttpResult? {
        val path = url.substringAfter("/private-api/", "").takeIf { it.isNotEmpty() } ?: return null
        fun wrapped(data: String) = HttpResult(200, """{"data":$data,"pagination":{"total":2,"limit":20,"offset":0,"has_next":false}}""")
        return when {
            path == "notifications" -> HttpResult(200, demoNotifications(nowMillis / 1000))
            path == "notifications/unread" -> HttpResult(200, """{"count":3}""")
            path == "notifications/mark" -> HttpResult(200, """{"unread":0}""")
            path.startsWith("leaderboard/") -> HttpResult(
                200,
                """[{"_id":"spike-alice","count":42,"points":"310.500","quests_done":true},{"_id":"spike-bob","count":31,"points":"275.250"},""" +
                    """{"_id":"spike-carol","count":18,"points":"120.000"}]""",
            )
            // waves and shorts: synthetic rows shaped like the combined feed's, so demo runs never show live content
            path.startsWith("promoted-entries") -> HttpResult(200, "[]")
            path.startsWith("waves/trending/tags") -> HttpResult(200, """[{"tag":"hive","posts":12},{"tag":"art","posts":7},{"tag":"photography","posts":3}]""")
            path.startsWith("waves/shorts") -> HttpResult(200, if ("cursor=" in path) "[]" else "[${demoWave("spike-carol", "clip-one", "A synthetic short", video = true)}]")
            path.startsWith("waves/feed") -> HttpResult(
                200,
                when {
                    "cursor=" in path -> "[]"
                    "following=" in path -> "[${demoWave("spike-bob", "wave-following", "A wave from someone you follow")}]"
                    "tag=" in path -> "[${demoWave("spike-carol", "wave-tagged", "Only waves with this tag show here #hive")}]"
                    else -> listOf(
                        demoWave("spike-carol", "wave-one", "Good morning from the waves feed #hive"),
                        demoWave("spike-bob", "wave-two", "Second wave with a [link](https://ecency.com/@spike-alice/fake-post-1) inside"),
                        demoWave("spike-carol", "wave-three", "Third wave, plain text only."),
                    ).joinToString(",", "[", "]")
                },
            )
            // accounts and shell gaps: signup (nothing is created), referrals, the deletion request, announcements
            path == "account-create" -> HttpResult(200, "")
            path == "request-delete" -> HttpResult(200, "{}")
            path.startsWith("referrals/") && path.endsWith("/stats") -> HttpResult(200, """{"total":3,"rewarded":1}""")
            path.startsWith("referrals/") -> HttpResult(
                200,
                if ("max_id=" in path) "[]"
                else """[{"id":3,"referral":"demo","username":"spike-carol","rewarded":1,"created":"2026-09-20T10:00:00+00:00"},""" +
                    """{"id":2,"referral":"demo","username":"spike-dave","rewarded":0,"created":"2026-09-28T10:00:00+00:00"},""" +
                    """{"id":1,"referral":"demo","username":"spike-bob","rewarded":0,"created":"2026-10-02T10:00:00+00:00"}]""",
            )
            path == "announcements" -> HttpResult(200, demoAnnouncements)
            path == "spotlights" -> HttpResult(200, demoSpotlights)
            // waves gaps: one quest already done today, and the check-in is taken
            path == "quests" -> HttpResult(200, demoQuests)
            // perks: a spin with a synthetic key until it was claimed in this run, then none left; nothing is paid
            path == "get-game" -> HttpResult(
                200,
                if (demoSpinUsed) """{"status":18,"remaining":0,"next_date":"2099-01-01T05:04:03Z","wait_secs":0}"""
                else """{"status":1,"key":"demo-spin-key","remaining":1,"next_date":"2099-01-01T05:04:03Z","wait_secs":0}""",
            )
            path == "post-game" -> HttpResult(200, """{"score":25}""").also { demoSpinUsed = true }
            path == "streak-freeze/buy" -> HttpResult(200, """{"owned":2,"points":950.5}""")
            path == "usr-activity" -> HttpResult(200, "{}")
            path.startsWith("pro-members") -> HttpResult(200, buildJsonObject {
                put("members", buildJsonArray { demoProMembers.forEach { add(JsonPrimitive(it)) } })
                put("count", demoProMembers.size)
            }.toString())
            // a demo Pro purchase puts its account on the roster (Ecency's Pro fulfilment); nothing is charged
            path == "purchase-order" -> HttpResult(201, "{}").also {
                val o = runCatching { Json.parseToJsonElement(body ?: "{}").jsonObject }.getOrNull()
                if (o?.get("product")?.jsonPrimitive?.content == PurchaseSkus.PRO) o["user"]?.jsonPrimitive?.content?.let { demoProMembers += it }
            }
            path.startsWith("favorites-check") -> HttpResult(200, "false")
            path.startsWith("bookmarks?") -> wrapped("""[{"_id":"demo-b1","author":"spike-alice","permlink":"fake-post-1","timestamp":2},{"_id":"demo-b2","author":"spike-bob","permlink":"fake-post-2","timestamp":1}]""")
            path.startsWith("favorites?") -> wrapped("""[{"_id":"demo-f1","account":"spike-carol","timestamp":2},{"_id":"demo-f2","account":"spike-dave","timestamp":1}]""")
            path.startsWith("favorite-tags?") -> wrapped("""[{"tag":"photography"},{"tag":"spike"}]""")
            // editor area: drafts, schedules, gallery, snippets, support settings
            path.startsWith("drafts-add") -> HttpResult(200, """{"drafts":[{"_id":"demo-d9","title":"Saved","body":"","tags":"","modified":"2026-10-06T10:00:00.000Z","meta":{}}]}""")
            path == "drafts" || path.startsWith("drafts?") -> DEMO_DRAFTS.let { if (path == "drafts") HttpResult(200, it) else wrapped(it) }
            path.startsWith("schedules?") -> wrapped("""[{"_id":"demo-s1","title":"Scheduled fixture","body":"Goes out later.","permlink":"scheduled-fixture","tags_arr":["spike"],"schedule":"2026-11-01T10:00:00+00:00","status":1}]""")
            path.startsWith("images?") -> wrapped("""[{"_id":"demo-i1","url":"https://images.ecency.com/demo/one.jpg","created":"2026-10-01"},{"_id":"demo-i2","url":"https://images.ecency.com/demo/two.jpg","created":"2026-10-02"}]""")
            path.startsWith("fragments?") -> wrapped("""[{"id":"demo-f1","title":"Footer","body":"Thanks for reading!"},{"id":"demo-f2","title":"Disclaimer","body":"Synthetic fixture text."}]""")
            // paid AI features: synthetic prices, balance and answers; nothing is charged anywhere
            path == "points" -> HttpResult(200, """{"points":"1,250.500","unclaimed_points":"0.000"}""")
            path == "ai-assist-price" -> HttpResult(
                200,
                """[{"action":"improve","cost":5,"free_limit":3,"free_remaining":2},{"action":"suggest_tags","cost":2,"free_limit":3,"free_remaining":0},""" +
                    """{"action":"generate_title","cost":2,"free_limit":3,"free_remaining":0},{"action":"summarize","cost":3,"free_limit":3,"free_remaining":0},""" +
                    """{"action":"check_grammar","cost":3,"free_limit":3,"free_remaining":0}]""",
            )
            path == "ai-assist" -> demoAssist(body)
            path == "ai-generate-price" -> HttpResult(
                200,
                """{"prices":[{"aspect_ratio":"1:1","cost":150},{"aspect_ratio":"4:3","cost":150},{"aspect_ratio":"16:9","cost":200}],""" +
                    """"power":[{"power":1,"multiplier":1},{"power":2,"multiplier":2},{"power":4,"multiplier":4}]}""",
            )
            path == "ai-transcribe-price" -> HttpResult(200, """{"unit_seconds":30,"unit_cost":15,"free_limit":0,"free_remaining":0,"max_seconds":300,"max_bytes":26214400}""")
            path == "ai-transcribe" -> HttpResult(200, """{"text":"This sentence stands in for a transcript in the demo.","units":1,"cost":15}""")
            path == "ai-generate-image" -> HttpResult(200, """{"url":"https://images.ecency.com/demo/generated.png","cost":150}""")
            // Listen: synthetic status and unlock (nothing is charged, the audio URLs lead nowhere)
            path == "listen-status" -> HttpResult(200, demoListenStatus(body, unlock = false))
            path == "listen" -> HttpResult(200, demoListenStatus(body, unlock = true))
            path == "support-settings" -> HttpResult(200, demoSupport)
            // the settings' "Support Ecency" card: the saved percents stay for the run
            path == "support-settings-update" -> {
                val o = runCatching { Json.parseToJsonElement(body ?: "{}").jsonObject }.getOrNull()
                demoSupport = """{"beneficiary_percent":${o?.get("beneficiary_percent") ?: 0},"curation_percent":${o?.get("curation_percent") ?: 0}}"""
                HttpResult(200, demoSupport)
            }
            else -> HttpResult(200, "{}")
        }
    }

    /** Demo Listen progress per post: posts unlocked in this run move one step per status poll until ready. */
    private val demoListen = mutableMapOf<String, Int>()

    /**
     * fake-post-1 is unlocked and ready for a signed-in reader, fake-post-2 is being made (42%), every other post is
     * locked until it is unlocked here (then: in line, 40%, ready). Visitors see every post locked.
     */
    private fun demoListenStatus(body: String?, unlock: Boolean): String {
        val o = runCatching { Json.parseToJsonElement(body ?: "{}").jsonObject }.getOrNull()
        val permlink = o?.get("permlink")?.jsonPrimitive?.content.orEmpty()
        val reader = o?.get("code") != null
        val langs = """"languages":[{"lang":"en","share":0.8,"seconds":168},{"lang":"de","share":0.2,"seconds":44}]"""
        fun ready() = """{"cost":50,"unlocked":true,"status":"ready",$langs,"tracks":[""" +
            """{"lang":"en","seconds":168,"url":"https://audio.invalid/demo/$permlink-en.mp3"},{"lang":"de","seconds":44,"url":"https://audio.invalid/demo/$permlink-de.mp3"}]}"""
        fun preparing(extra: String) = """{"cost":50,"unlocked":true,"status":"preparing",$langs,$extra}"""
        if (!reader) return """{"cost":50,"unlocked":false,"status":"none",$langs}"""
        return when {
            permlink == "fake-post-1" -> ready()
            permlink == "fake-post-2" -> preparing("\"progress\":0.42")
            unlock -> {
                demoListen[permlink] = 0
                preparing("\"queued_ahead\":1")
            }
            permlink in demoListen -> {
                val step = demoListen.getValue(permlink) + 1
                demoListen[permlink] = step
                when (step) {
                    1 -> preparing("\"queued_ahead\":0")
                    2 -> preparing("\"progress\":0.4")
                    else -> ready()
                }
            }
            else -> """{"cost":50,"unlocked":false,"status":"none",$langs}"""
        }
    }

    /** The demo account's "Support Ecency" percents (settings). */
    var demoSupport: String = """{"beneficiary_percent":0,"curation_percent":0}"""

    /** The quests demo runs show: one quest done today and no streak, unless a run sets [SAMPLE_QUESTS]. */
    var demoQuests: String = """{"daily":[{"id":"vote","progress":1},{"id":"post","progress":0}],"streak":{"current":0}}"""

    /** Quests with a streak and a freeze in reserve, for the perks screenshots and UI tests. */
    const val SAMPLE_QUESTS =
        """{"daily":[{"id":"checkin","progress":1},{"id":"post","progress":0},{"id":"comment","progress":2},{"id":"vote","progress":6},{"id":"reblog","progress":1}],""" +
            """"weekly":[{"id":"post","progress":3},{"id":"comment","progress":9},{"id":"vote","progress":31}],"monthly":[{"id":"post","progress":12}],""" +
            """"streak":{"current":5,"best":12,"at_risk":false,"freezes_owned":1}}"""

    /** The demo Pro roster: spike-alice, and every account a demo Pro purchase was ordered for in this run. */
    val demoProMembers: MutableSet<String> = mutableSetOf("spike-alice")

    /** Debug `demoNoPro`: nobody is Pro until a demo purchase (the Pro card's non-member screenshots and UI tests). */
    fun clearDemoPro() = demoProMembers.clear()

    /** The demo spin was claimed in this run. */
    var demoSpinUsed: Boolean = false

    /** The email digest subscriptions demo runs list (none unless a run sets [SAMPLE_DIGESTS]). */
    var demoDigests: String = "[]"

    /** Two synthetic subscriptions under one made-up address, for the email digests screen's screenshots and UI tests. */
    const val SAMPLE_DIGESTS =
        """[{"id":"d1","email":"reader@example.com","type":"own","target":"spike-alice","cadence":"weekly","status":"active"},""" +
            """{"id":"d2","email":"reader@example.com","type":"creator","target":"spike-bob","cadence":"monthly","status":"pending_confirmation"}]"""

    /** What `/private-api/spotlights` answers in demo runs; empty unless a run sets it (the apps' `demoSpotlight` option). */
    var demoSpotlights: String = "[]"

    /** One synthetic feature spotlight for every signed-in account (UI tests, screenshots). */
    const val SAMPLE_SPOTLIGHT =
        """[{"id":"demo-waves","feature":"waves","title":"Say it in a wave","description":"Short posts with images, polls and video, in the Waves tab.","button_text":"Learn more","button_link":"https://ecency.com/faq","platforms":["mobile"],"weight":1}]"""

    /** What `/private-api/announcements` answers in demo runs; empty unless a run sets it (the apps' `demoAnnouncement` option). */
    var demoAnnouncements: String = "[]"

    /** The release tag the update check sees in demo runs; null: no answer, no prompt. */
    var demoReleaseTag: String? = null

    /** A synthetic announcement with an in-app link, for screenshots and UI tests. */
    const val SAMPLE_ANNOUNCEMENT =
        """[{"id":"demo-announce-1","title":"Community call this Friday","description":"Join the monthly community call and bring your questions.","button_text":"Read more","button_link":"/@spike-alice/fake-post-1","auth":false}]"""

    /** One synthetic row per notification type the list renders (fake accounts, made-up text). */
    private fun demoNotifications(t: Long): String {
        return """[
            {"id":"n-1","type":"vote","source":"spike-bob","read":0,"timestamp":${t - 60},"author":"spike-alice","permlink":"fake-post-1","title":"A synthetic post","weight":10000},
            {"id":"n-2","type":"reply","source":"spike-carol","read":0,"timestamp":${t - 3600},"author":"spike-carol","permlink":"re-fake-post-1","parent_title":"A synthetic post","parent_permlink":"fake-post-1"},
            {"id":"n-3","type":"mention","source":"spike-dave","read":0,"timestamp":${t - 7200},"author":"spike-dave","permlink":"fake-post-2","title":"Mentioning you","post":true},
            {"id":"n-4","type":"follow","source":"spike-bob","read":1,"timestamp":${t - 86400},"follower":"spike-bob","following":"spike-alice"},
            {"id":"n-5","type":"transfer","source":"spike-carol","read":1,"timestamp":${t - 2 * 86400},"amount":"1.000 HIVE"},
            {"id":"n-6","type":"reblog","source":"spike-dave","read":1,"timestamp":${t - 3 * 86400},"author":"spike-alice","permlink":"fake-post-1","title":"A synthetic post"},
            {"id":"n-7","type":"delegations","source":"spike-bob","read":1,"timestamp":${t - 4 * 86400},"amount":"5000000000"},
            {"id":"n-8","type":"payouts","source":"spike-alice","read":1,"timestamp":${t - 5 * 86400},"amount_usd":"1.25","author":"spike-alice","permlink":"fake-post-1"},
            {"id":"n-9","type":"weekly_earnings","source":"spike-alice","read":1,"timestamp":${t - 6 * 86400},"total_usd":"4.20","author_usd":"3.00","curation_usd":"1.20"},
            {"id":"n-10","type":"account_update","source":"spike-alice","read":1,"timestamp":${t - 9 * 86400},"keys_changed":["posting"]}
        ]"""
    }

    private const val DEMO_DRAFTS =
        """[{"_id":"demo-d1","title":"Draft fixture","body":"A synthetic draft body with **bold** text.","tags":"spike photography","modified":"2026-10-05T10:00:00.000Z","created":"2026-10-05T10:00:00.000Z","meta":{"rewardType":"sp"}},""" +
            """{"_id":"demo-t1","title":"Weekly update","body":"## This week","tags":"spike","modified":"2026-10-04T10:00:00.000Z","created":"2026-10-04T10:00:00.000Z","meta":{"postTemplate":true,"templateName":"Weekly"}}]"""

    private fun demoWave(author: String, permlink: String, body: String, video: Boolean = false): String =
        """{"id":1,"author":"$author","permlink":"$permlink","title":"","body":${Json.encodeToString(body)},"json_metadata":{"app":"ecency/4.0.0-mobile","tags":["waves"]},""" +
            """"timestamp":"2026-10-06T11:00:00+00:00","pending_payout_value":"0.250 HBD","net_votes":3,"children":2,"tip_count":0,"host":"ecency.waves","_cursor":"c-$permlink"""" +
            (if (video) ""","video":{"platform":"3speak","author":"$author","permlink":"$permlink","embed_url":"https://play.3speak.tv/embed?v=$author/$permlink","thumbnail_url":null,"duration_secs":20}""" else "") + "}"

    private fun demoSearch(): String = (1..3).joinToString(",", "{\"took\":1,\"hits\":3,\"results\":[", "]}") {
        "{\"author\":\"spike-bob\",\"permlink\":\"search-hit-$it\",\"title\":\"Search fixture $it\",\"category\":\"hive-100001\"," +
            "\"children\":$it,\"author_rep\":\"55.00\",\"body_marked\":\"A synthetic <mark>result</mark> number $it.\",\"payout\":$it," +
            "\"up_votes\":${it * 2},\"created_at\":\"2026-10-01T10:00:00+00:00\",\"depth\":0}"
    }

    /**
     * The demo transfer sent from Kotlin: a JsonArray handed to Swift and back arrives as a bare list object whose
     * content Kotlin cannot read, so Swift never passes operations itself. [actionId] null: no action journal.
     */
    @Throws(Exception::class)
    suspend fun broadcastTransfer(signing: SigningModel, from: String, to: String, actionId: String?): com.ecency.core.auth.SignedBroadcast {
        val ops = transferOps(from, to)
        return if (actionId != null) signing.broadcast(from, ops, com.ecency.core.hive.KeyRole.ACTIVE, actionId)
        else signing.broadcast(from, ops, com.ecency.core.hive.KeyRole.ACTIVE)
    }

    /** A 0.001 HIVE transfer (active authority) for the fake-data signing demo; the fake node refuses broadcasts. */
    fun transferOps(from: String, to: String): kotlinx.serialization.json.JsonArray = kotlinx.serialization.json.JsonArray(
        listOf(
            com.ecency.core.api.broadcast.OperationJson.encode(
                com.ecency.core.hive.Transfer(from, to, com.ecency.core.hive.Asset.parse("0.001 HIVE"), ""),
            ),
        ),
    )

    /**
     * Debug demo: a 0.001 HIVE transfer from the current account, which needs the active key, so the auth upgrade
     * sheet shows. Nothing is broadcast unless the user enters a key.
     */
    @Throws(kotlin.coroutines.cancellation.CancellationException::class)
    suspend fun demoUpgrade(env: AppEnvironment) {
        val user = env.session.current.username ?: return
        runCatching { env.signing.broadcast(user, transferOps(user, "spike-bob"), com.ecency.core.hive.KeyRole.ACTIVE, env.idempotencyKey()) }
    }
}

package com.ecency.core.app

import com.ecency.core.api.model.arr
import com.ecency.core.api.model.bool
import com.ecency.core.api.model.int
import com.ecency.core.api.model.obj
import com.ecency.core.api.model.str
import com.ecency.core.auth.AuthHttpRequest
import com.ecency.core.l10n.L
import com.ecency.core.render.js.Js
import com.ecency.core.secure.AuthType
import com.ecency.core.secure.VaultAccount
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The SDK's quest content rules (`modules/quests/catalog.ts`) and RN's short content hint. */
object Quests {
    /** SDK QUEST_MIN_CONTENT_LENGTH: content must be longer than this to earn points. */
    const val MIN_CONTENT_LENGTH = 25

    /** SDK getQuestCatalogEntry('daily', 'post').goal. */
    const val DAILY_POST_GOAL = 1

    val SHORT_REPLY_HINT: String get() = L.native.editorExtras.shortReplyHint
    val SHORT_CONTENT_HINT: String get() = L.native.editorExtras.shortContentHint

    /** measureQuestContentLength: code points of [body] without its links (`/https?:\/\/\S+/g`), untrimmed. */
    fun contentLength(body: String): Int {
        var count = 0
        var i = 0
        while (i < body.length) {
            if (body.startsWith("http://", i) || body.startsWith("https://", i)) {
                val after = i + if (body.startsWith("https://", i)) 8 else 7
                // \S+ needs at least one character after the scheme
                if (after < body.length && !Js.isSpace(body[after])) {
                    i = after
                    while (i < body.length && !Js.isSpace(body[i])) i++
                    continue
                }
            }
            val c = body[i]
            i += if (c.isHighSurrogate() && i + 1 < body.length && body[i + 1].isLowSurrogate()) 2 else 1
            count++
        }
        return count
    }

    fun earnsContentCredit(body: String): Boolean = contentLength(body) > MIN_CONTENT_LENGTH

    /** RN shouldShowShortContentHint: signed in, not an edit, something typed, and too short to earn. */
    fun showsShortHint(username: String?, isEditing: Boolean, body: String): Boolean =
        !username.isNullOrEmpty() && !isEditing && Js.trim(body).isNotEmpty() && !earnsContentCredit(body)
}

/** RN deriveQuestChipState (utils/questChip.ts) over `/private-api/quests`. */
data class QuestChip(val postProgress: Int, val streak: Int, val atRisk: Boolean, val postGoal: Int = Quests.DAILY_POST_GOAL) {
    val postedToday: Boolean get() = postProgress >= postGoal

    val visible: Boolean get() = streak > 0 || !postedToday

    /** "Daily post 0/1 · 🔥 3 day streak · Keep your streak alive". */
    val text: String
        get() = listOfNotNull(
            L.native.editorExtras.dailyPost(postProgress = postProgress.toString(), postGoal = postGoal.toString()),
            L.native.editorExtras.dayStreak(streak = streak).takeIf { streak > 0 },
            L.questChip.keepStreak.takeIf { atRisk },
        ).joinToString(" · ")

    companion object {
        /** Null when the answer is not a quests object. */
        fun parse(text: String): QuestChip? {
            val o = runCatching { Json.parseToJsonElement(text) }.getOrNull().obj() ?: return null
            val post = o["daily"].arr().orEmpty().mapNotNull { it.obj() }.firstOrNull { it.str("id") == "post" }
            val streak = o["streak"].obj()
            val current = maxOf(0, streak?.int("current") ?: 0)
            return QuestChip(maxOf(0, post?.int("progress") ?: 0), current, current > 0 && streak?.bool("at_risk") == true)
        }

        /**
         * SDK getQuestsQueryOptions: `POST /private-api/quests {username}` (no code). Null on any failure, and
         * when there is no answer within the private API's bound (the HTTP client has no timeout of its own).
         */
        suspend fun load(env: AppEnvironment, username: String): QuestChip? = loadRaw(env, username)?.let(::parse)

        /** The same request, as the answer's text (the Waves tab's getting-started card reads more of it). */
        suspend fun loadRaw(env: AppEnvironment, username: String): String? = try {
            val response = kotlinx.coroutines.withTimeoutOrNull(com.ecency.core.auth.EcencyPrivateApi.TIMEOUT_MILLIS) {
                env.auth.http.send(
                    AuthHttpRequest(
                        "POST", "${env.auth.tokenHost}/private-api/quests", mapOf("Content-Type" to "application/json"),
                        buildJsonObject { put("username", username.removePrefix("@")) }.toString(),
                    ),
                )
            }
            if (response != null && response.status in 200..299) response.body else null
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
}

/** RN utils/rcError.ts: a broadcast refused for lack of resource credits. */
object RcErrors {
    val OFFER_TITLE: String get() = L.alert.rcDownTitle
    val OFFER_BODY: String get() = L.alert.rcDownBody
    val TOP_UP: String get() = L.alert.rcDownTopup
    val BOOST: String get() = L.alert.rcDownBoost
    val CANCEL: String get() = L.alert.cancel

    /** RN REDEEM redeemType for the Points RC top-up. */
    const val TOP_UP_REDEEM_TYPE = "rc_topup"

    /** /Please wait to transact|has \d+ RC, needs \d+ RC/i over every message of the error chain. */
    fun isInsufficientRc(error: Throwable?): Boolean {
        var e = error
        var depth = 0
        while (e != null && depth++ < 8) {
            if (matches(e.message.orEmpty())) return true
            e = e.cause
        }
        return false
    }

    fun matches(message: String): Boolean {
        if (message.contains("Please wait to transact", ignoreCase = true)) return true
        return SHORTFALL.containsMatchIn(message)
    }

    private val SHORTFALL = Regex("has [0-9]+ RC, needs [0-9]+ RC", RegexOption.IGNORE_CASE)
}

/**
 * RN maybeRequestReview (applicationActions.ts): the store rating prompt is asked for once, after a publish or a
 * vote that went out, when the app has been in use for 3 days and 5 sessions. The platform shows it
 * ([AppEnvironment.maybeRequestReview]); a request the platform could not make leaves the one request unspent.
 */
class AppRating(private val prefs: () -> Prefs, private val nowMillis: () -> Long) {
    private var inFlight = false

    /** One app start (RN counts app opens). */
    fun recordSession() {
        val p = prefs()
        if (p.getString(FIRST_USE) == null) p.putString(FIRST_USE, nowMillis().toString())
        p.putString(SESSIONS, ((p.getString(SESSIONS)?.toIntOrNull() ?: 0) + 1).toString())
    }

    fun shouldRequest(): Boolean {
        val p = prefs()
        if (inFlight || p.getString(REQUESTED) == "1") return false
        val first = p.getString(FIRST_USE)?.toLongOrNull() ?: return false
        val days = (nowMillis() - first).toDouble() / DAY_MILLIS
        return days >= MIN_DAYS && (p.getString(SESSIONS)?.toIntOrNull() ?: 0) >= MIN_SESSIONS
    }

    /** Claims the one request; false when it is not due. The caller waits [DELAY_MILLIS], asks the platform, then [finish]. */
    fun claim(): Boolean {
        if (!shouldRequest()) return false
        inFlight = true
        return true
    }

    fun finish(requested: Boolean) {
        inFlight = false
        if (requested) prefs().putString(REQUESTED, "1")
    }

    companion object {
        const val MIN_DAYS = 3
        const val MIN_SESSIONS = 5
        const val DELAY_MILLIS = 1200L
        const val DAY_MILLIS = 24 * 60 * 60 * 1000L
        const val FIRST_USE = "rating.firstUseTime"
        const val SESSIONS = "rating.sessionCount"
        const val REQUESTED = "rating.hasRequestedReview"
    }
}

/** RN shouldPromptPostingAuthority and the POSTING_AUTHORITY_PROMPT sheet's copy. */
object PostingAuthorityPrompt {
    val TITLE: String get() = L.postingAuthority.title
    /**
     * Not RN's sentence ("Grant Ecency posting permission to post without opening Keychain each time"): the
     * confirmation names what is granted and what it takes, the account that gets the posting authority and the
     * key that signs the grant.
     */
    val DESCRIPTION: String get() = L.native.editorExtras.grantDescription
    val BENEFITS: List<String> get() = listOf(
        L.postingAuthority.benefit1,
        L.postingAuthority.benefit2,
        L.postingAuthority.benefit3,
    )
    val SECURITY_NOTE: String get() = L.postingAuthority.securityNote
    val GRANT: String get() = L.postingAuthority.grantButton
    val SKIP: String get() = L.postingAuthority.skipButton
    val GRANTED: String get() = L.postingAuthority.success

    /**
     * RN isMissingEcencyPostingAuthorityError over a failure and its causes: HiveSigner refused the broadcast
     * because `ecency.app` has no posting authority for the account.
     */
    fun isMissingAuthority(error: Throwable?): Boolean {
        var e = error
        var depth = 0
        while (e != null && depth++ < 8) {
            val api = e as? com.ecency.core.auth.AuthException.HiveSignerApi
            if (matches(api?.error, api?.description, e.message)) return true
            e = e.cause
        }
        return false
    }

    /**
     * RN's test over HiveSigner's answer `{error, error_description}` and the error's message: the phrase
     * "permission to broadcast" anywhere (lower-cased), or the code `unauthorized_client` together with a
     * mention of ecency.app. The bare code is not enough: HiveSigner also answers it for an expired token.
     */
    fun matches(code: String?, description: String?, message: String?): Boolean {
        val haystack = com.ecency.core.render.js.Js.lower("${description.orEmpty()} ${message.orEmpty()} ")
        return "permission to broadcast" in haystack || (code.orEmpty() == "unauthorized_client" && "ecency.app" in haystack)
    }

    /**
     * The account broadcasts through a wallet or a token (HiveAuth, HiveSigner, or a key login that holds no
     * posting key but a token): with ecency.app in its posting authority it would not need the wallet each time.
     */
    fun usesWalletOrToken(account: VaultAccount): Boolean = when (account.authType) {
        AuthType.HIVE_AUTH, AuthType.HIVE_SIGNER -> true
        null -> false
        else -> account.postingKey.isNullOrEmpty() && !account.accessToken.isNullOrEmpty()
    }
}

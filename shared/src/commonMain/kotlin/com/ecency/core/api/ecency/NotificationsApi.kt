package com.ecency.core.api.ecency

import com.ecency.core.api.EcencyException
import com.ecency.core.api.EcencyHttpException
import com.ecency.core.api.model.arr
import com.ecency.core.api.model.obj
import com.ecency.core.api.model.str
import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.auth.AuthException
import com.ecency.core.auth.AuthHttp
import com.ecency.core.auth.AuthHttpRequest
import com.ecency.core.auth.EcencyPrivateApi
import com.ecency.core.l10n.LocaleFormat
import com.ecency.core.l10n.TimeUnit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.coroutines.cancellation.CancellationException

/** An account an `account_update` notification says was granted an authority. */
data class GrantedAuthority(val account: String, val authority: String)

/**
 * One row of `/private-api/notifications`, with the fields the RN notification line reads. Every text field is
 * whatever the server sent (other users' post titles, account names): show it as text and validate it before
 * it becomes a navigation target.
 */
data class ActivityNotification(
    val id: String,
    val type: String,
    val source: String,
    val read: Boolean,
    /** Epoch milliseconds of `timestamp` (or `ts`), 0 when missing or unreadable. */
    val timestampMillis: Long,
    val author: String = "",
    val permlink: String = "",
    val title: String = "",
    val parentTitle: String = "",
    val parentPermlink: String = "",
    /** A mention in a post (true) or in a comment. */
    val isPost: Boolean = false,
    /** Vote weight in basis points; 0 when absent. */
    val weight: Double = 0.0,
    val amount: String = "",
    val count: Double = 0.0,
    val tags: List<String> = emptyList(),
    val tag: String = "",
    val amountUsd: String = "",
    val totalUsd: String = "",
    val authorUsd: String = "",
    val curationUsd: String = "",
    val granted: List<GrantedAuthority> = emptyList(),
    val keysChanged: List<String> = emptyList(),
    val description: String = "",
    val imgUrl: String = "",
    val parentImgUrl: String = "",
    val follower: String = "",
) {
    companion object {
        /** Null for a row without an id or a type (nothing to show or mark). */
        fun fromJson(o: JsonObject): ActivityNotification? {
            val id = text(o["id"]).ifEmpty { return null }
            val type = o.str("type") ?: return null
            return ActivityNotification(
                id = id,
                type = type,
                source = o.str("source").orEmpty(),
                read = truthy(o["read"]),
                timestampMillis = NotificationTime.parse(o["timestamp"]).takeIf { it > 0 } ?: NotificationTime.parse(o["ts"]),
                author = o.str("author").orEmpty(),
                permlink = o.str("permlink").orEmpty(),
                title = o.str("title").orEmpty(),
                parentTitle = o.str("parent_title").orEmpty(),
                parentPermlink = o.str("parent_permlink").orEmpty(),
                isPost = truthy(o["post"]),
                weight = (o["weight"] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.content.toDoubleOrNull() }?.takeIf { it.isFinite() } ?: 0.0,
                amount = text(o["amount"]),
                count = (o["count"] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.content.toDoubleOrNull() }?.takeIf { it.isFinite() } ?: 0.0,
                tags = o["tags"].arr().orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content },
                tag = o.str("tag").orEmpty(),
                amountUsd = text(o["amount_usd"]),
                totalUsd = text(o["total_usd"]),
                authorUsd = text(o["author_usd"]),
                curationUsd = text(o["curation_usd"]),
                granted = o["accounts_granted"].arr().orEmpty().mapNotNull { g ->
                    g.obj()?.let { GrantedAuthority(it.str("account").orEmpty(), it.str("authority").orEmpty()) }
                },
                keysChanged = o["keys_changed"].arr().orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content },
                description = o.str("description").orEmpty(),
                imgUrl = o.str("img_url").orEmpty(),
                parentImgUrl = o.str("parent_img_url").orEmpty(),
                follower = o.str("follower").orEmpty(),
            )
        }

        /** A string or number as text; empty for anything else. */
        private fun text(e: JsonElement?): String = (e as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content.orEmpty()

        /** JS truthiness for the 0/1, boolean and string flags the API sends. */
        private fun truthy(e: JsonElement?): Boolean {
            val p = e as? JsonPrimitive ?: return e is JsonObject || e is JsonArray
            if (p is JsonNull) return false
            if (p.isString) return p.content.isNotEmpty()
            return p.booleanOrNull ?: (p.doubleOrNull?.let { it != 0.0 && !it.isNaN() } ?: false)
        }
    }
}

/** RN `formatNotificationTimestamp`'s input handling: seconds, milliseconds, ISO 8601 or "YYYY-MM-DD HH:mm:ss" (UTC). */
object NotificationTime {
    private val DATE_TIME = Regex("""^(\d{4}-\d{2}-\d{2})[T ](\d{2}:\d{2}:\d{2})(\.\d+)?(Z|[+-]\d{2}:?\d{2})?$""")

    fun parse(e: JsonElement?): Long {
        val p = e as? JsonPrimitive ?: return 0
        if (p is JsonNull) return 0
        return parse(p.content)
    }

    /** Epoch milliseconds, 0 when unreadable. */
    fun parse(raw: String): Long {
        val value = raw.trim()
        if (value.isEmpty()) return 0
        value.toDoubleOrNull()?.let { n ->
            if (!n.isFinite() || n <= 0) return 0
            return if (n > 1e12) n.toLong() else (n * 1000).toLong()
        }
        val m = DATE_TIME.matchEntire(value) ?: return 0
        val seconds = try {
            com.ecency.core.hive.ChainTime.toEpochSeconds("${m.groupValues[1]}T${m.groupValues[2]}")
        } catch (e: Exception) {
            return 0
        }
        val zone = m.groupValues[4]
        val offset = if (zone.isEmpty() || zone == "Z") 0 else {
            val sign = if (zone[0] == '-') -1 else 1
            val digits = zone.drop(1).replace(":", "")
            sign * (digits.take(2).toInt() * 3600 + digits.drop(2).toInt() * 60)
        }
        return (seconds - offset) * 1000
    }

    private const val MINUTE = 60L
    private const val HOUR = 3600L
    private const val DAY = 86400L

    /**
     * RN's relative text for the first 7 days ("5 minutes ago", "1 day ago"), in the app language (CLDR relative
     * time, the same units and floors); null from 7 days on, where the date and time show instead
     * ([com.ecency.core.l10n.LocaleFormat.dateTime]), and for no timestamp.
     */
    fun relative(epochMillis: Long, nowMillis: Long): String? {
        if (epochMillis <= 0) return null
        val diff = ((nowMillis - epochMillis) / 1000).coerceAtLeast(0)
        if (diff >= 7 * DAY) return null
        fun ago(n: Long, unit: TimeUnit) = LocaleFormat.relative(n, unit, future = false)
        return when {
            diff < MINUTE -> ago(diff, TimeUnit.SECOND)
            diff < HOUR -> ago(diff / MINUTE, TimeUnit.MINUTE)
            diff < DAY -> ago(diff / HOUR, TimeUnit.HOUR)
            else -> ago(diff / DAY, TimeUnit.DAY)
        }
    }
}

/** A row of `/private-api/leaderboard/<duration>`. */
data class LeaderboardEntry(val username: String, val count: String, val points: String, val questsDone: Boolean)

/** The answer of `notifications/mark`: the server's unread count afterwards, when it sent one. */
data class MarkReadResult(val unread: Int?)

/**
 * The notification calls of the Ecency private API, with the paths and bodies @ecency/sdk sends for the RN app:
 * the activity list, the unread count, mark as read, the device registration for push and the leaderboard.
 */
class NotificationsApi(
    private val private: EcencyPrivateApi,
    private val http: AuthHttp,
    private val host: String = private.host,
) {
    /**
     * SDK getNotificationsInfiniteQueryOptions: `{code, filter, since}`. [filter] null is every type; [since] is the
     * id of the last row of the previous page, empty for the first page.
     */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun notifications(username: String, filter: String?, since: String): List<ActivityNotification> {
        val fields = buildJsonObject {
            if (filter != null) put("filter", filter)
            put("since", since)
        }
        return call(username, "notifications", fields).arr().orEmpty().mapNotNull { it.obj()?.let(ActivityNotification::fromJson) }
    }

    /** SDK getNotificationsUnreadCountQueryOptions: `notifications/unread` `{code}`, answer `{count}`. */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun unreadCount(username: String): Int {
        val count = (call(username, "notifications/unread").obj()?.get("count") as? JsonPrimitive)?.doubleOrNull
            ?: throw EcencyHttpException(200, "notifications/unread returned no count")
        return count.toInt().coerceAtLeast(0)
    }

    /** SDK markNotifications: `notifications/mark` `{code, id?}`; without [id] every notification is marked. */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun markRead(username: String, id: String?): MarkReadResult {
        val result = call(username, "notifications/mark", buildJsonObject { if (!id.isNullOrEmpty()) put("id", id) })
        return MarkReadResult((result.obj()?.get("unread") as? JsonPrimitive)?.doubleOrNull?.toInt()?.coerceAtLeast(0))
    }

    /**
     * SDK saveNotificationSetting: `register-device` `{code, username, token, system, allows_notify, notify_types}`.
     * [code] is passed in, not looked up: a logout disables the row after the account left the vault.
     */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun registerDevice(code: String, username: String, token: String, system: String, allowsNotify: Boolean, notifyTypes: List<Int>) {
        val fields = buildJsonObject {
            put("username", username)
            put("token", token)
            put("system", system)
            put("allows_notify", if (allowsNotify) 1 else 0)
            putJsonArray("notify_types") { notifyTypes.forEach { add(it) } }
        }
        parse(send(private.request("register-device", code, fields)))
    }

    /** SDK getDiscoverLeaderboardQueryOptions: public `GET /private-api/leaderboard/<day|week|month>`. */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun leaderboard(duration: String): List<LeaderboardEntry> {
        val response = send(AuthHttpRequest("GET", "$host/private-api/leaderboard/$duration", mapOf("Content-Type" to "application/json")))
        return parse(response).arr().orEmpty().mapNotNull { e ->
            val o = e.obj() ?: return@mapNotNull null
            val name = o.str("_id") ?: return@mapNotNull null
            fun text(key: String) = (o[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content.orEmpty()
            val quests = (o["quests_done"] as? JsonPrimitive)?.let { it.booleanOrNull ?: (it.doubleOrNull?.let { n -> n != 0.0 } ?: false) } ?: false
            LeaderboardEntry(name, text("count"), text("points"), quests)
        }
    }

    private suspend fun call(username: String, path: String, fields: JsonObject = JsonObject(emptyMap())): JsonElement? {
        val response = try {
            withTimeoutOrNull(TIMEOUT_MILLIS) { private.post(path, username, fields) } ?: throw EcencyHttpException(0, "timeout")
        } catch (e: CancellationException) {
            throw e
        } catch (e: AuthException) {
            throw e
        } catch (e: EcencyException) {
            throw e
        } catch (e: Exception) {
            throw EcencyHttpException.ofSendFailure(e)
        }
        return parse(response)
    }

    private suspend fun send(request: AuthHttpRequest): HttpResult = try {
        withTimeoutOrNull(TIMEOUT_MILLIS) { http.send(request) } ?: throw EcencyHttpException(0, "timeout")
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw EcencyHttpException.ofSendFailure(e)
    }

    companion object {
        /**
         * Every call here gives up after this long. The shared HTTP client has no timeouts of its own on Android
         * (and RN sets none), so a stalled connection would otherwise hold a logout's push release, and every
         * registration waiting behind it, until the process ends.
         */
        const val TIMEOUT_MILLIS = EcencyPrivateApi.TIMEOUT_MILLIS
    }

    private fun parse(response: HttpResult): JsonElement? {
        if (response.status !in 200..299) throw EcencyHttpException(response.status)
        return runCatching { Json.parseToJsonElement(response.body) }.getOrNull()
    }
}

package com.ecency.core.app

import com.ecency.core.l10n.L
import com.ecency.core.render.blocks.SafeTarget
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The `data` of an Ecency push (enotify's push format), or of a websocket event converted to it. Nothing in it is
 * trusted: anyone who knows a device token's project can try to send one, and titles come from other users'
 * posts. Fields only become navigation after [PushRouting] validated them; texts are length capped and shown
 * as plain text.
 */
data class PushPayload(
    val id: String,
    val type: String,
    val source: String,
    val target: String,
    val permlink: String,
    val amount: String,
    val tag: String,
    /** The system notification's own title and body, when the platform has them. */
    val title: String,
    val body: String,
    /** RN `hiveuri` pushes: `data.hiveUri`, a `hive://` or `ecency://` link; empty unless it passed [SafeTarget.external]. */
    val hiveUri: String = "",
) {
    companion object {
        /** Longer values are dropped whole (never truncated into something else that validates). */
        private const val MAX_FIELD = 1024
        private const val MAX_TEXT = 300

        /** Activity ids are short tokens; anything else is not sent back to the API. */
        private val ID = Regex("^[A-Za-z0-9._:-]{1,64}$")

        fun of(data: Map<String, String>, title: String?, body: String?): PushPayload {
            fun field(key: String) = data[key]?.takeIf { it.length <= MAX_FIELD }.orEmpty()
            return PushPayload(
                id = field("id").takeIf { ID.matches(it) }.orEmpty(),
                type = field("type"),
                source = field("source"),
                target = field("target"),
                // enotify splits long permlinks over three fields (FCM value limits); RN joins them
                permlink = field("permlink1") + field("permlink2") + field("permlink3"),
                amount = field("amount").take(MAX_TEXT),
                tag = field("tag"),
                title = clean(title),
                body = clean(body),
                hiveUri = appLink(field("hiveUri")),
            )
        }

        /** Only a `hive://` or `ecency://` link SafeTarget accepts (no whitespace, a body after the scheme). */
        private fun appLink(raw: String): String {
            val uri = (SafeTarget.external(raw) as? com.ecency.core.render.blocks.LinkTarget.AppUri)?.uri ?: return ""
            val scheme = uri.substringBefore("://").lowercase()
            return if (scheme == "hive" || scheme == "ecency") uri else ""
        }

        /** Plain, single-line, capped text for a banner. */
        internal fun clean(text: String?): String =
            text.orEmpty().filter { it >= ' ' || it == '\t' }.replace('\t', ' ').trim().take(MAX_TEXT)

        /**
         * A websocket event (`{event:"notify", type, source, target, extra}`) as a payload, with the title and body
         * RN's websocket bridge builds. Null for anything that is not a notify event of a known type.
         */
        fun fromSocket(text: String, nowMillis: Long): PushPayload? {
            val o = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
            fun str(obj: JsonObject?, key: String) = (obj?.get(key) as? JsonPrimitive)?.takeIf { it.isString || it.content != "null" }?.content.orEmpty()
            if (str(o, "event") != "notify") return null
            val type = str(o, "type")
            if (type !in PushRouting.SOCKET_TYPES) return null
            val source = str(o, "source")
            val target = str(o, "target")
            val extra = o["extra"] as? JsonObject
            val firstTag = ((extra?.get("tags") as? kotlinx.serialization.json.JsonArray)?.firstOrNull() as? JsonPrimitive)?.content
                ?: str(extra, "tag")
            val count = str(extra, "count").toIntOrNull()?.takeIf { it != 0 }
            val (title, body) = when (type) {
                "mention" -> L.native.pushPayload.mentioned(source = source, target = target) to ""
                "reply" -> L.native.pushPayload.replied(source = source, target = target) to str(extra, "body").take(100)
                "transfer" -> L.native.pushPayload.transferred(source = source, target = target) to str(extra, "amount")
                "delegations" -> L.native.pushPayload.delegated(source = source, target = target) to str(extra, "amount")
                "scheduled_published" -> L.settings.notification.scheduledPublished to str(extra, "title")
                "payouts" -> L.native.pushPayload.rewardsReceived to str(extra, "amount")
                "account_update" -> L.native.pushPayload.accountUpdated to L.native.pushPayload.accountUpdatedBody
                "weekly_earnings" -> L.notification.filters.weeklyEarnings to str(extra, "total_usd").let { if (it.isEmpty()) "" else "\$$it" }
                "follow" -> L.native.pushPayload.followed(source = source, target = target) to ""
                "unfollow" -> L.native.pushPayload.unfollowed(source = source, target = target) to ""
                "ignore" -> L.native.pushPayload.ignored(source = source, target = target) to ""
                "blacklist" -> L.native.pushPayload.blacklisted(source = source, target = target) to ""
                "tags" -> (if (count != null) L.native.plurals.notification.tagsBundle(count = count, tag = firstTag) else L.native.pushPayload.postedIn(source = source, firstTag = firstTag)) to str(extra, "title")
                else -> "@$source" to ""
            }
            val permlink = str(extra, "permlink").take(750)
            return of(
                mapOf(
                    "id" to "ws-$nowMillis", "source" to source, "target" to target, "type" to type,
                    "permlink1" to permlink, "amount" to str(extra, "amount"), "tag" to firstTag,
                ),
                title, body,
            )
        }
    }
}

/**
 * What a tapped push does: opens [entry] in the app's navigation (gated like every deep link), or hands [link] to
 * the auth link model ([AuthLinkModel.handle]), which asks before it signs or shares anything. Nothing else.
 */
data class PushAction(val entry: NavEntry?, val link: String? = null)

/** The in-app banner for a notification that arrives while the app is open (RN ForegroundNotification). */
data class PushBanner(val id: String, val avatarUser: String, val title: String, val body: String, val entry: NavEntry?)

/**
 * RN's three routing tables for pushes: the tapped system notification (`_pushNavigate`), the in-app banner
 * (ForegroundNotification) and which types refresh the unread count. Account names, permlinks and tags are
 * validated with [SafeTarget]; a field that does not validate means no navigation at all. The one link a payload
 * can carry is RN's `hiveuri` (`linkProcessor.handleLink`): only a `hive://` or `ecency://` link, and only to the
 * auth link model, which asks the user before anything is signed, exactly like the same link opened from another
 * app (on Android any app can start the activity with extras that look like a tapped push, and any app can open
 * an ecency:// link; neither gets past the confirmation).
 */
object PushRouting {
    /** Push vocabulary (FCM `data.type`): types whose arrival refreshes the unread count. */
    val FOREGROUND_TYPES = setOf(
        "mention", "reply", "transfer", "delegation", "scheduled_published", "payout", "account_update",
        "weekly_earnings", "follow", "unfollow", "ignore", "tag",
    )

    /** Websocket vocabulary (`str_activity_type`). */
    val SOCKET_TYPES = setOf(
        "mention", "reply", "transfer", "delegations", "scheduled_published", "payouts", "account_update",
        "weekly_earnings", "follow", "unfollow", "ignore", "blacklist", "tags",
    )

    /** Types the banner is shown for, in both vocabularies. */
    val BANNER_TYPES = setOf(
        "reply", "mention", "transfer", "delegation", "delegations", "scheduled_published", "payout", "payouts",
        "account_update", "weekly_earnings", "follow", "unfollow", "ignore", "blacklist", "tag", "tags",
    )

    private fun post(author: String, permlink: String): NavEntry? =
        SafeTarget.post(author, permlink)?.let { NavEntry.screen(AppScreen.Post(it.author, it.permlink)) }

    private fun profile(name: String): NavEntry? = SafeTarget.account(name)?.let { NavEntry.screen(AppScreen.Profile(it)) }

    private fun tagOrPost(p: PushPayload): NavEntry? =
        if (p.permlink.isNotEmpty()) post(p.source, p.permlink)
        else NotificationFormat.safeTag(p.tag)?.let { NavEntry.screen(AppScreen.TagResult(it)) }

    /** RN `_pushNavigate`: where a tapped system notification goes; null when it opens nothing. */
    fun opened(p: PushPayload): PushAction? {
        val entry = when (p.type) {
            "vote", "unvote", "reblog" -> post(p.target, p.permlink)
            "mention", "scheduled_published", "favorite", "bookmark", "reply" -> post(p.source, p.permlink)
            "follow", "unfollow", "ignore" -> profile(p.source)
            "tag", "tags" -> tagOrPost(p)
            "transfer" -> NavEntry.tab(AppTab.WALLET)
            "inactive" -> NavEntry.screen(AppScreen.of(RouteName.EDITOR))
            "spin" -> NavEntry.screen(AppScreen.of(RouteName.PERKS))
            "hiveuri" -> return p.hiveUri.takeIf { it.isNotEmpty() }?.let { PushAction(null, link = it) }
            else -> null
        }
        return entry?.let { PushAction(it) }
    }

    /** The account a push is addressed to, when the payload names a valid one. */
    fun addressee(p: PushPayload): String? = SafeTarget.account(p.target)

    /** RN ForegroundNotification: the banner's texts and where its tap goes; null for types it does not show. */
    fun banner(p: PushPayload): PushBanner? {
        if (p.type !in BANNER_TYPES) return null
        fun copy(key: String) = NotificationFormat.copy(key).orEmpty()
        val amount = p.amount.ifEmpty { copy("amount_unknown") }
        val (title, body) = when (p.type) {
            "reply" -> "${copy("reply_on")} @${p.target}" to copy("reply_body")
            "mention" -> "${copy("mention_on")} @${p.target}" to copy("reply_body")
            "transfer" -> "@${p.source} ${copy("transfer")}" to amount
            "delegation", "delegations" -> "@${p.source} ${copy("delegations")}" to amount
            "scheduled_published" -> copy("scheduled_published_title") to p.body.ifEmpty { copy("scheduled_published_body") }
            "tag", "tags" -> p.title.ifEmpty { copy("tags_title") } to p.body
            "payout", "payouts" -> p.title.ifEmpty { L.notification.payouts(amount = p.amount) } to p.body
            "weekly_earnings" -> p.title.ifEmpty { L.notification.weeklyEarnings(amount = p.amount, breakdown = "") } to p.body
            "account_update" -> p.title.ifEmpty { copy("account_update") } to p.body
            else -> "@${p.source} ${copy(p.type)}" to "" // follow, unfollow, ignore, blacklist
        }
        val entry = when (p.type) {
            "transfer", "delegation", "delegations", "payout", "payouts", "weekly_earnings" -> NavEntry.tab(AppTab.WALLET)
            "account_update" -> null
            "follow", "unfollow", "ignore", "blacklist" -> profile(p.source)
            "tag", "tags" -> tagOrPost(p)
            else -> post(p.source, p.permlink)
        }
        return PushBanner(p.id, SafeTarget.account(p.source).orEmpty(), PushPayload.clean(title), PushPayload.clean(body), entry)
    }
}

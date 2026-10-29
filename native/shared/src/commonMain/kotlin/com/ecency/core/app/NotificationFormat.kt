package com.ecency.core.app

import com.ecency.core.api.ecency.ActivityNotification
import com.ecency.core.l10n.L
import com.ecency.core.l10n.L10n
import com.ecency.core.l10n.LocaleFormat
import com.ecency.core.render.ImageProxy
import com.ecency.core.render.blocks.SafeTarget
import kotlin.math.abs
import kotlin.math.round
import kotlin.math.sign

/** RN notification filters (notificationView FILTERS): [apiValue] is the `filter` the API takes, null for all. */
enum class NotificationFilter(val key: String, private val labelText: () -> String) {
    ACTIVITIES("activities", { L.notification.activities }),
    REPLIES("replies", { L.notification.replies }),
    MENTIONS("mentions", { L.notification.mentions }),
    VOTES("rvotes", { L.notification.rvotes }),
    FOLLOWS("follows", { L.notification.follows }),
    REBLOGS("reblogs", { L.notification.reblogs }),
    TRANSFERS("transfers", { L.notification.filters.transfers }),
    DELEGATIONS("delegations", { L.notification.filters.delegations }),
    FAVORITES("nfavorites", { L.notification.filters.nfavorites }),
    BOOKMARKS("nbookmarks", { L.notification.filters.nbookmarks }),
    TAGS("tags", { L.notification.filters.tags }),
    PAYOUTS("payouts", { L.notification.filters.payouts }),
    SCHEDULED_PUBLISHED("scheduled_published", { L.notification.filters.scheduledPublished }),
    ACCOUNT_UPDATES("account_updates", { L.notification.filters.accountUpdates }),
    WEEKLY_EARNINGS("weekly_earnings", { L.notification.filters.weeklyEarnings }),
    ;

    val apiValue: String? get() = if (this == ACTIVITIES) null else key

    /** The filter's name in the current language. */
    val label: String get() = labelText()

    /** RN shows the filter names in capitals. */
    val title: String get() = label.uppercase()

    companion object {
        /** The quick chips next to the filter menu (they stay in the menu too). */
        val quick: List<NotificationFilter> get() = listOf(REPLIES, MENTIONS)
    }
}

/** One notification as the list draws it (RN notificationLineView). */
data class NotificationRow(
    val id: String,
    val type: String,
    /** The bold name that starts the first line. */
    val source: String,
    /** The shared avatar helper's URL for [source]; empty when it is not an account name. */
    val avatarUrl: String,
    /** The text after the name: RN copy plus amount, HP, RC or vote percent. */
    val text: String,
    /** Second line: the post or parent title (or permlink). Empty when RN shows none. */
    val moreInfo: String,
    val description: String,
    val timestampMillis: Long,
    /** A proxied 96 px thumbnail of the post, or empty. */
    val imageUrl: String,
    val read: Boolean,
    /** Where a tap goes; null when RN does nothing for this row. */
    val target: NavEntry?,
)

/** RN notificationLineView and the row routing of notificationContainer, as pure functions. */
object NotificationFormat {
    /**
     * RN's `notification.*` messages a row uses (the same English as native's, recorded in `rn-reuse.json`); a type
     * without one shows its name with spaces for underscores.
     */
    private val COPY_KEYS = setOf(
        "this_week", "reblog", "unfollow", "epoint", "reply_body", "inactive", "replies", "vote",
        "reblogs", "bookmarks", "noactivity", "transfer", "reply_on", "leaderboard_title", "delegations", "reply",
        "older_then", "leaderboard", "unvote", "activities", "rvotes", "follows", "mention_on",
        "yesterday", "mention", "favorites", "tags", "tags_title", "mentions", "spin", "last_week",
        "recent", "ignore", "blacklist", "follow", "notification", "this_month", "referral", "account_update",
        "account_update_owner_key", "account_update_active_key", "account_update_posting_key",
        "account_update_owner_authority", "account_update_active_authority", "account_update_posting_authority",
        "payouts", "scheduled_published", "scheduled_published_title", "scheduled_published_body",
    )

    internal fun copy(key: String): String? = message(key, emptyMap())

    /** The message for [key] with [values]; a placeholder without a value stays as written, as react-intl shows it. */
    private fun message(key: String, values: Map<String, String>): String? = when (key) {
        "amount_unknown" -> L.native.notification.amountUnknown
        in COPY_KEYS -> L10n.byKey("notification.$key", values)
        else -> null
    }

    /** Types whose thumbnail is the post itself, and those that show the parent post's (RN notificationImage.ts). */
    private val IMAGE_TYPES = setOf("mention", "reblog", "scheduled_published", "favorites", "payouts")
    private val PARENT_IMAGE_TYPES = setOf("reply", "bookmarks")
    private const val THUMB_SIZE = 96

    private val FOLLOW_TYPES = setOf("follow", "unfollow", "ignore", "blacklist")
    private val TAG = Regex("^[a-z0-9-]{1,32}$")

    /** A tag as hivemind stores one (RN's check before a tag feed opens). */
    fun safeTag(tag: String): String? = tag.takeIf { TAG.matches(it) }

    fun row(n: ActivityNotification, hivePerMVests: Double, proxyBase: String): NotificationRow = NotificationRow(
        id = n.id,
        type = n.type,
        source = n.source,
        avatarUrl = PostCard.avatarUrl(n.source, proxyBase),
        text = text(n, hivePerMVests),
        moreInfo = moreInfo(n),
        description = n.description,
        timestampMillis = n.timestampMillis,
        imageUrl = imageUrl(n, proxyBase),
        read = n.read,
        target = target(n),
    )

    fun text(n: ActivityNotification, hivePerMVests: Double): String {
        val key = when {
            n.type == "account_update" -> accountUpdateKey(n)
            n.type == "tags" && n.count != 0.0 -> "tags_bundle"
            else -> n.type
        }
        // the counted messages are native plural keys (`native.plurals.notification.*`); RN keeps its own
        val message = when (key) {
            "weekly_earnings" -> weeklyEarnings(n)
            "monthly_posts" -> L.native.plurals.notification.monthlyPosts(count = n.count.toInt())
            "checkin" -> L.native.plurals.notification.checkin(count = n.count.toInt())
            "tags_bundle" -> L.native.plurals.notification.tagsBundle(count = n.count.toInt(), tag = n.tags.firstOrNull() ?: n.tag)
            else -> message(key, values(n))
        }
            ?: n.type.replace('_', ' ').ifEmpty { "notification" }
        return listOf(message, extra(n, hivePerMVests)).filter { it.isNotEmpty() }.joinToString(" ")
    }

    /** RN `notification.weekly_earnings` with its breakdown, as one native message each (RN builds it from two). */
    private fun weeklyEarnings(n: ActivityNotification): String {
        val amount = n.totalUsd.ifEmpty { n.amount.ifEmpty { "0" } }
        return if (n.authorUsd.isNotEmpty() && n.curationUsd.isNotEmpty()) {
            L.native.notification.weeklyEarningsBreakdown(amount = amount, authorUsd = n.authorUsd, curationUsd = n.curationUsd)
        } else {
            L.native.notification.weeklyEarningsTotal(amount = amount)
        }
    }

    private fun accountUpdateKey(n: ActivityNotification): String = when {
        "owner" in n.keysChanged -> "account_update_owner_key"
        "active" in n.keysChanged -> "account_update_active_key"
        "posting" in n.keysChanged -> "account_update_posting_key"
        n.granted.isNotEmpty() -> {
            val authorities = n.granted.map { it.authority }.toSet()
            when {
                "owner" in authorities -> "account_update_owner_authority"
                "active" in authorities -> "account_update_active_authority"
                else -> "account_update_posting_authority"
            }
        }
        else -> "account_update"
    }

    private fun values(n: ActivityNotification): Map<String, String> = when (n.type) {
        "tags" -> mapOf("tag" to (n.tags.firstOrNull() ?: n.tag))
        "payouts" -> mapOf("amount" to n.amountUsd.ifEmpty { n.amount })
        "account_update" -> mapOf("accounts" to n.granted.joinToString(", ") { "@${it.account}" })
        else -> emptyMap()
    }

    /** RN `titleExtra`: the transfer amount, a delegation in HP or RC, or the vote weight in percent. */
    private fun extra(n: ActivityNotification, hivePerMVests: Double): String = when {
        n.type == "transfer" -> LocaleFormat.assetText(n.amount)
        n.type == "delegations" -> when {
            n.amount.isEmpty() -> ""
            "VESTS" in n.amount -> LocaleFormat.posix(Num.plain(round(vestsToHp(n.amount, hivePerMVests) * 1000) / 1000), grouping = false) + " HP"
            else -> rcFormat(leadingNumber(n.amount)) + " RC"
        }
        n.weight != 0.0 -> LocaleFormat.percent(n.weight / 100, 2, 0)
        else -> ""
    }

    /** JS parseFloat: the number a string starts with, 0 when there is none. */
    internal fun leadingNumber(text: String): Double =
        Regex("^\\s*[+-]?(\\d+\\.?\\d*|\\.\\d+)").find(text)?.value?.trim()?.toDoubleOrNull() ?: 0.0

    /** RN vestsToHp. */
    internal fun vestsToHp(vests: String, hivePerMVests: Double): Double {
        if (hivePerMVests == 0.0 || !hivePerMVests.isFinite()) return 0.0
        return leadingNumber(vests) / 1e6 * hivePerMVests
    }

    /** RN rcFormatter: K, M and B with at most two decimals, in the app language ([LocaleFormat.compact]). */
    internal fun rcFormat(num: Double): String {
        val a = abs(num)
        return when {
            a > 999 && a < 1_000_000 -> LocaleFormat.compact(num, 2, 3)
            a > 999_999 && a < 1_000_000_000 -> LocaleFormat.compact(num, 2, 6)
            a > 999_999_999 && a < 1e15 -> LocaleFormat.compact(num, 2, 9)
            else -> LocaleFormat.posix(Num.plain(num), grouping = false)
        }
    }

    private fun moreInfo(n: ActivityNotification): String = when {
        n.type in setOf("vote", "reblog", "favorites", "checkin", "monthly_posts", "scheduled_published") ||
            (n.type == "tags" && n.permlink.isNotEmpty()) || (n.type == "mention" && n.isPost) -> n.title.ifEmpty { n.permlink }
        n.type == "reply" || n.type == "bookmarks" || (n.type == "mention" && !n.isPost) ->
            n.parentTitle.ifEmpty { n.parentPermlink.ifEmpty { n.permlink } }
        else -> ""
    }

    private fun imageUrl(n: ActivityNotification, proxyBase: String): String {
        val raw = when (n.type) {
            in PARENT_IMAGE_TYPES -> n.parentImgUrl
            in IMAGE_TYPES -> n.imgUrl
            else -> ""
        }
        // http(s) only: the URL comes from someone else's post metadata
        val safe = SafeTarget.httpUrl(raw) ?: return ""
        return ImageProxy(proxyBase).proxify(safe, THUMB_SIZE, THUMB_SIZE)
    }

    /**
     * RN `_navigateToNotificationRoute`, in its order. Every account, permlink and tag is validated; a row whose
     * target does not validate opens nothing.
     */
    fun target(n: ActivityNotification): NavEntry? {
        fun post(author: String, permlink: String) = SafeTarget.post(author, permlink)?.let { NavEntry.screen(AppScreen.Post(it.author, it.permlink)) }
        return when {
            n.permlink.isNotEmpty() && n.author.isNotEmpty() -> post(n.author, n.permlink)
            n.type == "scheduled_published" && n.permlink.isNotEmpty() -> post(n.source, n.permlink)
            n.type in FOLLOW_TYPES -> SafeTarget.account(n.follower)?.let { NavEntry.screen(AppScreen.Profile(it)) }
            n.type == "tags" && n.permlink.isNotEmpty() -> post(n.source, n.permlink)
            n.type == "tags" -> safeTag(n.tag)?.let { NavEntry.screen(AppScreen.TagResult(it)) }
            n.type == "transfer" || n.type == "weekly_earnings" -> NavEntry.tab(AppTab.WALLET)
            n.type == "spin" -> NavEntry.screen(AppScreen.of(RouteName.BOOST))
            n.type == "inactive" -> NavEntry.screen(AppScreen.of(RouteName.EDITOR))
            else -> null
        }
    }
}

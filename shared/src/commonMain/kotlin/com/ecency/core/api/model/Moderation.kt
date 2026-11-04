package com.ecency.core.api.model

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max

/** Why content is de-emphasized; mirrors @ecency/sdk ContentModerationReason. */
enum class ModerationReason {
    /** `stats.gray` / `stats.hide` from hivemind (community or observer mutes). */
    MOD_MUTED,

    /** Heavily downvoted by enough distinct accounts. */
    DOWNVOTED,

    /** Low-reputation author posting an outbound promotional link. */
    LOW_TRUST,
}

/** Reputation as the app shows it (the SDK's accountReputation / the app's parseReputation). */
object Reputation {
    /** Values already on the 0-100 scale are floored; raw chain values are scaled, then floored. */
    fun display(input: Double): Int {
        if (!input.isFinite()) return 25
        if (abs(input) > 0 && abs(input) <= 100) return floor(input).toInt()
        if (input == 0.0) return 25
        val level = max(log10(abs(input)) - 9, 0.0) * (if (input < 0) -1 else 1)
        return floor(level * 9 + 25).toInt()
    }
}

/** The SDK's getContentModerationReason, same thresholds and precedence. */
object Moderation {
    /** Sum of rshares below which a post counts as heavily downvoted. */
    const val HIDDEN_POST_RSHARES_THRESHOLD = -10_000_000_000L

    /** Downvoting is only conclusive once enough accounts have voted. */
    const val HIDDEN_POST_MIN_VOTES = 5

    /** Reputation below which an author counts as low-trust. */
    const val LOW_TRUST_REPUTATION_THRESHOLD = 30

    private val INTERNAL_HOSTS = listOf(
        "ecency.com", "ecency.app", "hive.blog", "hive.io", "hiveblocks.com", "peakd.com", "snapie.io",
        "hivesuite.app", "leofinance.io", "inleo.io", "3speak.tv", "d.buzz", "waivio.com",
    )
    private val IMAGE_HOSTS = listOf(
        "imgur.com", "images.hive.blog", "files.peakd.com", "i.ecency.com", "images.ecency.com",
        "steemitimages.com", "cdn.steemitimages.com", "media.giphy.com",
    )
    private val IMAGE_EXT = Regex("""\.(jpe?g|png|gif|webp|svg|bmp|avif)(\?|#|$)""", RegexOption.IGNORE_CASE)
    private val URL = Regex("""(?:https?:)?//[^\s)<>"'\]]+""", RegexOption.IGNORE_CASE)
    private val TRAILING_PUNCT = Regex("""[.,;:!?'"]+$""")
    private val HOST = Regex("""^(?:https?:)?//([^/?#]+)""", RegexOption.IGNORE_CASE)

    /**
     * @param rawReputation the entry's author_reputation as sent, null when absent (an unknown
     * reputation is not evidence of anything, so it never makes content low-trust).
     */
    fun reason(stats: PostStats, netRshares: Long, rawReputation: Double?, body: String): ModerationReason? = when {
        stats.gray || stats.hide -> ModerationReason.MOD_MUTED
        netRshares < HIDDEN_POST_RSHARES_THRESHOLD && stats.totalVotes >= HIDDEN_POST_MIN_VOTES -> ModerationReason.DOWNVOTED
        rawReputation != null && Reputation.display(rawReputation) < LOW_TRUST_REPUTATION_THRESHOLD && hasExternalLink(body) ->
            ModerationReason.LOW_TRUST
        else -> null
    }

    /** True if the body links outside the Hive/Ecency ecosystem (images do not count). */
    fun hasExternalLink(body: String): Boolean = URL.findAll(body).any { isExternalPromoLink(it.value) }

    private fun isExternalPromoLink(raw: String): Boolean {
        val url = raw.replace(TRAILING_PUNCT, "")
        if (IMAGE_EXT.containsMatchIn(url)) return false
        val host = HOST.find(url)?.groupValues?.get(1)?.lowercase()?.removePrefix("www.") ?: return false
        if ('.' !in host) return false
        fun matches(h: String) = host == h || host.endsWith(".$h")
        return INTERNAL_HOSTS.none(::matches) && IMAGE_HOSTS.none(::matches)
    }
}

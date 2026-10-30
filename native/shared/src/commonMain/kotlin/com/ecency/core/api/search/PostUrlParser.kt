package com.ecency.core.api.search

/** What RN's postUrlParser read out of a value: a post (author and permlink), a profile (author only) or a feed. */
data class PostUrlParts(val author: String? = null, val permlink: String? = null, val feedType: String? = null, val tag: String? = null, val category: String? = null)

/**
 * Port of RN `src/utils/postUrlParser.ts`, which the search screen uses to tell a pasted post link (or
 * `@author/permlink`) from a search term. Same regexes in the same order; golden vectors run the RN source itself.
 */
object PostUrlParser {
    private const val NAME = "[\\w.\\d-]+"
    private val WAVES = Regex("^https?://(?:www\\.)?(?:ecency\\.com|esteem\\.app|estm\\.to)/waves/@?([\\w.-]+)/([\\w-]+)", RegexOption.IGNORE_CASE)
    private val FEED = Regex("^https://([\\w\\-.]*)/([\\w-]*)/?([\\w-]*)/?$")
    private val BARE = Regex("^[/]?(@$NAME)/(.*?)(?:\\?|$)")
    private val WITH_CATEGORY = Regex("($NAME)/(@$NAME)/(.*?)(?:\\?|$)")
    private val PROFILE = Regex("^https?://(.*)/(@$NAME)$")
    private val CAT_POST = Regex("^https?://(.*)/(.*)/(@$NAME)/(.*?)(?:\\?|$)", RegexOption.IGNORE_CASE)
    private val CAT_AUTHOR = Regex("^https?://(.*)/(.*)/(@$NAME)", RegexOption.IGNORE_CASE)
    private val POST = Regex("^https?://(.*)/(@$NAME)/(.*?)(?:\\?|$)", RegexOption.IGNORE_CASE)
    private val AUTHOR = Regex("^https?://(.*)/(@$NAME)", RegexOption.IGNORE_CASE)
    private val CAT_HOSTS = listOf("https://estm.to", "https://ecency.com", "https://esteem.app", "https://hive.blog", "https://peakd.com", "https://leofinance.io")
    private val HOSTS = listOf("https://ecency.com", "https://hive.blog", "https://peakd.com", "https://leofinance.io")

    fun parse(input: String): PostUrlParts? {
        var url = com.ecency.core.render.js.Js.toLowerCase(input)
        if (url.startsWith("ecency://") || url.startsWith("esteem://")) {
            url = url.replaceFirst("ecency://", "https://ecency.com/").replaceFirst("esteem://", "https://ecency.com/")
        }
        val waves = url.replace(Regex("^(ecency|esteem)://", RegexOption.IGNORE_CASE), "https://ecency.com/")
        WAVES.find(waves)?.let { return PostUrlParts(it.groupValues[1].lowercase(), it.groupValues[2]) }

        FEED.find(url)?.let { m ->
            return PostUrlParts(feedType = m.groupValues[2], tag = m.groupValues[3].ifEmpty { null })
        }
        BARE.find(url)?.let { return PostUrlParts(it.groupValues[1].removePrefix("@"), it.groupValues[2]) }
        WITH_CATEGORY.find(url)?.let { m ->
            val rest = m.groupValues[3]
            if (rest.contains("#@")) {
                val splits = rest.split('@')[1].split('/')
                return PostUrlParts(splits[0], splits.getOrNull(1), category = m.groupValues[1])
            }
            return PostUrlParts(m.groupValues[2].replaceFirst("@", ""), rest.substringBefore('#'), category = m.groupValues[1])
        }
        PROFILE.find(url)?.let { return PostUrlParts(it.groupValues[2].replaceFirst("@", "")) }
        if (CAT_HOSTS.any { url.startsWith(it) }) return catAuthorPermlink(url)
        if (HOSTS.any { url.startsWith(it) }) return authorPermlink(url)
        return null
    }

    private fun catAuthorPermlink(u: String): PostUrlParts? {
        CAT_POST.find(u)?.let { return PostUrlParts(it.groupValues[3].replaceFirst("@", ""), it.groupValues[4]) }
        CAT_AUTHOR.find(u)?.let { return PostUrlParts(it.groupValues[3].replaceFirst("@", "")) }
        POST.find(u)?.let { return PostUrlParts(it.groupValues[2].replaceFirst("@", ""), it.groupValues[3]) }
        return null
    }

    private fun authorPermlink(u: String): PostUrlParts? {
        POST.find(u)?.let { return PostUrlParts(it.groupValues[2].replaceFirst("@", ""), it.groupValues[3]) }
        AUTHOR.find(u)?.let { return PostUrlParts(it.groupValues[2].replaceFirst("@", "")) }
        return null
    }
}

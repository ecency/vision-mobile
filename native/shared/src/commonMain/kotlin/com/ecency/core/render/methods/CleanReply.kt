package com.ecency.core.render.methods

import com.ecency.core.render.js.Js
/** render-helper `methods/clean-reply.method.ts`: drops app bylines and their separators. */
internal object CleanReply {
    private val LINE_FILTERS = listOf(
        "posted using [partiko", "posted using [dapplr", "posted using [leofinance", "posted via [neoxian",
        "posted using [neoxian", "posted via [first context", "posted with [stemgeeks", "posted using [bilpcoin",
        "posted using [inleo", "posted using [sportstalksocial]", "<center><sub>[posted using aeneas.blog",
        "<center><sub>posted via [proofofbrain.io", "<center>posted on [hypnochain", "<center><sub>posted via [weedcash.network",
        "<center>posted on [naturalmedicine.io", "<center><sub>posted via [musicforlife.io",
        "if the truvvl embed is unsupported by your current frontend, click this link to view this story",
        "<center><em>posted from truvvl", "view this post <a href=\"https://travelfeed.io/",
        "read this post on travelfeed.io for the best experience", "posted via <a href=\"https://www.dporn.co/\"",
        "▶️ [watch on 3speak](https://3speak", "<sup><sub>posted via [inji.com]", "view this post on [liketu]",
    )
    private val LINE_FILTERS_AFTER = listOf(
        "originally posted through [scrobble.life", "*posted via lumen", "<sub>posted via [hivesuite](",
        "view this post <a href=\"https://travelfeed.com/",
        // "[via Inbox]" is compared against the lower-cased line, so it never matches; kept for parity.
        "[via Inbox]", "<sub>[via apps from](",
    )

    private val TAIL_REMOVALS = listOf(
        "Posted via <a href=\"https://d.buzz\" data-link=\"promote-link\">D.Buzz</a>",
        "<div class=\"pull-right\"><a href=\"/@hive.engage\">![](https://i.imgur.com/XsrNmcl.png)</a></div>",
        "<div><a href=\"https://engage.hivechain.app\">![](https://i.imgur.com/XsrNmcl.png)</a></div>",
        "<div class=\"text-center\"><img src=\"https://cdn.steemitimages.com/DQmNp6YwAm2qwquALZw8PdcovDorwaBSFuxQ38TrYziGT6b/A-20.png\">" +
            "<a href=\"https://bit.ly/actifit-app\"><img src=\"https://cdn.steemitimages.com/DQmQqfpSmcQtfrHAtzfBtVccXwUL9vKNgZJ2j93m8WNjizw/l5.png\"></a>" +
            "<a href=\"https://bit.ly/actifit-ios\"><img src=\"https://cdn.steemitimages.com/DQmbWy8KzKT1UvCvznUTaFPw6wBUcyLtBT5XL9wdbB7Hfmn/l6.png\"></a></div>",
    )

    fun cleanReply(s: String): String {
        val pre = if (s.isEmpty()) s else removeBylineSeparator(s)
        var out = if (pre.isEmpty()) {
            ""
        } else {
            pre.split('\n').filter { item ->
                val l = Js.toLowerCase(item)
                LINE_FILTERS.none { l.contains(it) } &&
                    !(l.contains("posted from liketu speak") && l.contains("auto-transcrib")) &&
                    LINE_FILTERS_AFTER.none { l.contains(it) } &&
                    !isThreeSpeakFooterLine(item) &&
                    !(l.contains("_posted from liketu clips") && l.contains("short video, trimmed"))
            }.joinToString("\n")
        }
        for (r in TAIL_REMOVALS) out = Js.replaceFirst(out, r, "")
        return out
    }

    /**
     * `/(^|\n)-{3,}[ \t]*\n+(?=[^\n]*(?:originally posted through \[scrobble\.life|\*posted via lumen)|`
     * `[ \t]*▶\uFE0F?[ \t]*\[watch on 3speak\.tv\]\(https:\/\/3speak\.tv\/watch[^)\s]*\)[^\S\n]*(?:\n|$))/i`
     * replaced with `$1` (first match). Every quantifier is forced: only maximal runs can continue.
     */
    private fun removeBylineSeparator(s: String): String {
        var p = 0
        while (p < s.length) {
            val dashStart = if (p == 0 && s[0] == '-') 0 else if (s[p] == '\n') p + 1 else -1
            if (dashStart >= 0) {
                var i = dashStart
                while (i < s.length && s[i] == '-') i++
                if (i - dashStart >= 3) {
                    while (i < s.length && (s[i] == ' ' || s[i] == '\t')) i++
                    val nl = i
                    while (i < s.length && s[i] == '\n') i++
                    if (i > nl) {
                        var le = s.indexOf('\n', i)
                        if (le < 0) le = s.length
                        val line = s.substring(i, le)
                        if (Js.indexOfCi(line, "originally posted through [scrobble.life") >= 0 ||
                            Js.indexOfCi(line, "*posted via lumen") >= 0 ||
                            isThreeSpeakFooterLine(line)
                        ) {
                            return s.substring(0, dashStart) + s.substring(i)
                        }
                    }
                }
            }
            p++
        }
        return s
    }

    /**
     * The newer 3Speak footer line (also the separator lookahead, which sees one line),
     * `/^[ \t]*▶\uFE0F?[ \t]*\[watch on 3speak\.tv\]\(https:\/\/3speak\.tv\/watch[^)\s]*\)\s*$/i`.
     */
    private fun isThreeSpeakFooterLine(line: String): Boolean {
        val end = threeSpeakLinkEnd(line)
        return end >= 0 && Js.isBlank(line.substring(end))
    }

    /**
     * Matches `[ \t]*▶\uFE0F?[ \t]*\[watch on 3speak\.tv\]\(https://3speak\.tv/watch[^)\s]*\)` (ASCII case
     * insensitive) at the start of [s]; returns the index after `)`, or -1. Every quantifier is forced.
     */
    private fun threeSpeakLinkEnd(s: String): Int {
        var i = 0
        while (i < s.length && (s[i] == ' ' || s[i] == '\t')) i++
        if (i >= s.length || s[i] != '\u25B6') return -1
        i++
        if (i < s.length && s[i] == '\uFE0F') i++
        while (i < s.length && (s[i] == ' ' || s[i] == '\t')) i++
        if (!Js.startsWithCi(s, THREE_SPEAK_LINK, i)) return -1
        i += THREE_SPEAK_LINK.length
        while (i < s.length && s[i] != ')' && !Js.isSpace(s[i])) i++
        return if (i < s.length && s[i] == ')') i + 1 else -1
    }

    private const val THREE_SPEAK_LINK = "[watch on 3speak.tv](https://3speak.tv/watch"
}

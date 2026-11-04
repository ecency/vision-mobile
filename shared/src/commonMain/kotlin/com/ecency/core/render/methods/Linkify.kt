package com.ecency.core.render.methods

import com.ecency.core.render.js.Js
/**
 * render-helper `methods/linkify.method.ts`: `#tags`, `@mentions`, `/tag/@user/permlink` and
 * `/@user/permlink` text, and bare image URLs, each pass a scanner reproducing the JS regex and
 * its global `replace` (matches never overlap; scanning resumes after each match).
 */
internal object Linkify {
    private fun isTagChar(c: Char) = c == '-' || c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'
    private fun isAlnum(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'
    private fun isWordChar(c: Char) = isAlnum(c) || c == '_'
    private fun isLetter(c: Char) = c in 'a'..'z' || c in 'A'..'Z'

    /**
     * Generic global replace driven by a matcher returning the match end at position p, or -1.
     * [next] gives the first position at or after its argument where a match can start (or -1),
     * so a pass costs one indexOf scan when the text holds no candidate.
     */
    private inline fun replaceAll(s: String, next: (Int) -> Int, matchAt: (Int) -> Int, fn: (Int, Int) -> String): String {
        var sb: StringBuilder? = null
        var last = 0
        var p = next(0)
        while (p >= 0 && p <= s.length) {
            val end = matchAt(p)
            if (end < 0) {
                p = next(p + 1)
                continue
            }
            if (sb == null) sb = StringBuilder(s.length + 64)
            sb.append(s, last, p)
            sb.append(fn(p, end))
            last = end
            p = next(if (end == p) end + 1 else end)
        }
        if (sb == null) return s
        sb.append(s, last, s.length)
        return sb.toString()
    }

    /**
     * Candidate starts for `(^|X)c...` patterns: position 0 when [s] starts with [c] (the `^`
     * branch), otherwise the position just before an occurrence of [c] (the consumed prefix char).
     */
    private fun beforeChar(s: String, from: Int, c: Char): Int {
        if (from == 0 && s.isNotEmpty() && s[0] == c) return 0
        val h = s.indexOf(c, maxOf(from + 1, 1))
        return if (h < 0) -1 else h - 1
    }

    /**
     * The same for the two at signs of the mention pattern. The next full-width at sign is
     * remembered: candidates only move forward, and searching for an absent one from every
     * candidate would rescan the rest of the text each time (quadratic on `@a.@a.@a...`).
     */
    private class AtFinder(private val s: String) {
        private var fullFrom = -1
        private var fullAt = -1

        fun next(from: Int): Int {
            if (from == 0 && s.isNotEmpty() && isAt(s[0])) return 0
            val start = maxOf(from + 1, 1)
            val a = s.indexOf('@', start)
            if (fullFrom < 0 || (fullAt in 0 until start)) {
                fullAt = s.indexOf('\uFF20', start)
                fullFrom = start
            }
            val b = fullAt
            val h = if (a < 0) b else if (b < 0) a else minOf(a, b)
            return if (h < 0) -1 else h - 1
        }
    }

    /** `/(^|\s|>)(#[-a-z\d]+)/gi` */
    private fun tagAt(s: String, p: Int): Int {
        val n = s.length
        if (p == 0 && n > 1 && s[0] == '#' && isTagChar(s[1])) return runEnd(s, 1)
        if (p < n && (Js.isSpace(s[p]) || s[p] == '>') && p + 2 < n && s[p + 1] == '#' && isTagChar(s[p + 2])) return runEnd(s, p + 2)
        return -1
    }

    private fun runEnd(s: String, from: Int): Int {
        var i = from
        while (i < s.length && isTagChar(s[i])) i++
        return i
    }

    private fun isAt(c: Char) = c == '@' || c == '＠'

    /** End of `[a-z][-.a-z\d^/]+[a-z\d]` at [q] (greedy run, backtracked to its last alphanumeric), or -1. */
    private fun userEnd(s: String, q: Int): Int {
        if (q >= s.length || !isLetter(s[q])) return -1
        var r = q + 1
        while (r < s.length && (isAlnum(s[r]) || s[r] == '-' || s[r] == '.' || s[r] == '^' || s[r] == '/')) r++
        var k = r - 1
        while (k >= q + 2) {
            if (isAlnum(s[k])) return k + 1
            k--
        }
        return -1
    }

    fun linkify(input: String, ctx: RenderContext): String {
        val forApp = ctx.forApp
        val opts = ctx.options
        var content = input

        // Tags
        content = replaceAll(content, { f -> beforeChar(content, f, '#') }, { p -> tagAt(content, p) }) { start, end ->
            var tag = content.substring(start, end)
            val body = tag.substring(tag.indexOf('#') + 1)
            if (body.all { it in '0'..'9' }) return@replaceAll tag
            val preceding = if (Js.isSpace(tag[0]) || tag[0] == '>') tag[0].toString() else ""
            if (tag.startsWith(">")) tag = tag.substring(1)
            val trimmed = Js.trim(tag)
            val tagLower = Js.toLowerCase(trimmed.substring(1))
            if (!forApp) {
                if (opts.inertAuthorAndTagChips) return@replaceAll "$preceding<span class=\"er-tag er-tag-link\">$trimmed</span>"
                return@replaceAll "$preceding<a class=\"er-tag er-tag-link\" href=\"/trending/$tagLower\">$trimmed</a>"
            }
            "$preceding<a class=\"markdown-tag-link\" data-tag=\"$tagLower\">$trimmed</a>"
        }

        // Mentions
        val placeholders = ArrayList<Pair<String, String>>()
        val c1 = content
        val atFinder = AtFinder(c1)
        content = replaceAll(c1, { f -> atFinder.next(f) }, { p ->
            var end = -1
            if (p == 0 && c1.isNotEmpty() && isAt(c1[0])) end = userEnd(c1, 1)
            if (end < 0 && p < c1.length && !(isWordChar(c1[p]) || c1[p] == '/') && p + 1 < c1.length && isAt(c1[p + 1])) {
                end = userEnd(c1, p + 2)
            }
            end
        }) { start, end ->
            val match = c1.substring(start, end)
            val atPos = if (start == 0 && isAt(c1[0]) && userEnd(c1, 1) == end) 0 else start + 1
            // `(^|[^set1]|(^|[^set2]))`: group 2 is nested in group 1, so a preceding character that only
            // the third alternative accepts (one of !#$%&*@ and the fullwidth at) is captured twice and
            // the callback's `preceeding1 + preceeding2` writes it twice.
            val pre = c1.substring(start, atPos)
            val preceding = if (pre.length == 1 && pre[0] in "!#\$%&*@\uFF20") pre + pre else pre
            val user = c1.substring(atPos + 1, end)
            val userLower = Js.toLowerCase(user)
            if (userLower.indexOf('/') == -1 && Helper.isValidUsername(user)) {
                if (!forApp) {
                    val avatarSrc = "${ctx.proxy.base}/u/$userLower/avatar/small"
                    val inner = "<img class=\"er-author-link-image\" src=\"$avatarSrc\" alt=\"$userLower\"/>@$userLower"
                    val html = if (opts.inertAuthorAndTagChips) {
                        "$preceding<span class=\"er-author er-author-link\">$inner</span>"
                    } else {
                        "$preceding<a class=\"er-author er-author-link\" href=\"/@$userLower\">$inner</a>"
                    }
                    val placeholder = "‌${placeholders.size}‌"
                    placeholders.add(placeholder to html)
                    return@replaceAll placeholder
                }
                "$preceding<a class=\"markdown-author-link\" data-author=\"$userLower\">@$user</a>"
            } else {
                match
            }
        }

        // Internal links with category: /category/@user/permlink
        val c2 = content
        content = replaceAll(c2, { f -> beforeChar(c2, f, '/') }, { p -> categoryLinkAt(c2, p) }) { start, end ->
            val match = c2.substring(start, end)
            val q = if (c2[start] == '/') start else start + 1
            val preceding = c2.substring(start, q)
            val tagEnd = c2.indexOf('/', q + 1)
            val tag = c2.substring(q + 1, tagEnd)
            val authorEnd = c2.indexOf('/', tagEnd + 2)
            val author = c2.substring(tagEnd + 2, authorEnd)
            val p3 = c2.substring(authorEnd + 1, end)
            val authorLower = Js.toLowerCase(author)
            if (!Helper.isValidUsername(authorLower)) return@replaceAll match
            val permlink = Helper.sanitizePermlink(p3)
            if (!Helper.isValidPermlink(permlink)) return@replaceAll match
            if (permlink in Consts.SECTION_LIST) {
                val external = opts.externalProfileBase
                val attrs = if (forApp || external != null) {
                    "href=\"${external ?: "https://ecency.com"}/@$authorLower/$permlink\""
                } else {
                    "href=\"/@$authorLower/$permlink\""
                }
                "$preceding<a class=\"markdown-profile-link\" $attrs>@$authorLower/$permlink</a>"
            } else {
                val attrs = if (forApp) {
                    "data-author=\"$authorLower\" data-tag=\"$tag\" data-permlink=\"$permlink\""
                } else {
                    "href=\"/@$authorLower/$permlink\""
                }
                "$preceding<a class=\"markdown-post-link\" $attrs>@$authorLower/$permlink</a>"
            }
        }

        // Internal links: /@user/permlink
        val c3 = content
        content = replaceAll(c3, { f -> beforeChar(c3, f, '/') }, { p -> userLinkAt(c3, p) }) { start, end ->
            val match = c3.substring(start, end)
            val q = if (c3[start] == '/') start else start + 1
            val nameEnd = c3.indexOf('/', q + 2)
            val u = c3.substring(start, nameEnd)
            val p3 = c3.substring(nameEnd + 1, end)
            val uu = Js.replaceFirst(Js.replaceFirst(Js.toLowerCase(Js.trim(u)), "/@", ""), "@", "")
            val permlink = Helper.sanitizePermlink(p3)
            if (!Helper.isValidPermlink(permlink)) return@replaceAll match
            if (permlink in Consts.SECTION_LIST) {
                val external = opts.externalProfileBase
                val attrs = if (forApp || external != null) {
                    "href=\"${external ?: "https://ecency.com"}/@$uu/$permlink\""
                } else {
                    "href=\"/@$uu/$permlink\""
                }
                " <a class=\"markdown-profile-link\" $attrs>@$uu/$permlink</a>"
            } else {
                val attrs = if (forApp) "data-author=\"$uu\" data-tag=\"post\" data-permlink=\"$permlink\"" else "href=\"/@$uu/$permlink\""
                " <a class=\"markdown-post-link\" $attrs>@$uu/$permlink</a>"
            }
        }

        // Image links
        var firstImageUsed = false
        content = Scanners.imgReplace(content) { link ->
            val isLCP = !firstImageUsed
            firstImageUsed = true
            ImgMethod.createImageHTML(link, isLCP, ctx)
        }

        for ((placeholder, html) in placeholders) content = Js.replaceFirstSubstituted(content, placeholder, html)
        return content
    }

    private fun nonSpaceRun(s: String, from: Int): Int {
        var i = from
        while (i < s.length && !Js.isSpace(s[i])) i++
        return i
    }

    /** `/(^|\s)\/([a-z0-9-]+)\/@([\w.\d-]+)\/(\S+)/gi` at [p]. */
    private fun categoryLinkAt(s: String, p: Int): Int {
        if (p == 0) categoryLinkFrom(s, 0).let { if (it >= 0) return it }
        if (p < s.length && Js.isSpace(s[p])) return categoryLinkFrom(s, p + 1)
        return -1
    }

    private fun categoryLinkFrom(s: String, q: Int): Int {
        if (q >= s.length || s[q] != '/') return -1
        var i = q + 1
        while (i < s.length && (isAlnum(s[i]) || s[i] == '-')) i++
        if (i == q + 1 || !s.startsWith("/@", i)) return -1
        val a = i + 2
        i = a
        while (i < s.length && (isWordChar(s[i]) || s[i] == '.' || s[i] == '-')) i++
        if (i == a || i >= s.length || s[i] != '/') return -1
        val e = nonSpaceRun(s, i + 1)
        return if (e > i + 1) e else -1
    }

    /** `/((^|\s)\/@[\w.\d-]+)\/(\S+)/gi` at [p]. */
    private fun userLinkAt(s: String, p: Int): Int {
        if (p == 0) userLinkFrom(s, 0).let { if (it >= 0) return it }
        if (p < s.length && Js.isSpace(s[p])) return userLinkFrom(s, p + 1)
        return -1
    }

    private fun userLinkFrom(s: String, q: Int): Int {
        if (!s.startsWith("/@", q)) return -1
        var i = q + 2
        while (i < s.length && (isWordChar(s[i]) || s[i] == '.' || s[i] == '-')) i++
        if (i == q + 2 || i >= s.length || s[i] != '/') return -1
        val e = nonSpaceRun(s, i + 1)
        return if (e > i + 1) e else -1
    }
}

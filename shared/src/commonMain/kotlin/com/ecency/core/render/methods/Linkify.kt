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

    /** Length of the run item (`#[-a-z\d]+` or a mention `[@＠][a-z][-.a-z\d^/]+[a-z\d]`) at [i], or 0. */
    private fun runItemEnd(s: String, i: Int): Int {
        if (i >= s.length) return -1
        if (s[i] == '#') {
            val e = runEnd(s, i + 1)
            return if (e > i + 1) e else -1
        }
        if (isAt(s[i])) return userEnd(s, i + 1)
        return -1
    }

    private class RunResult(val html: String, val linked: Int, val length: Int, val firstLength: Int)

    /** linkify `attr`: escaped for a double-quoted attribute (tags and valid usernames never need it). */
    private fun attr(v: String): String = v.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")

    /** `(^|\s|>|&gt;)` then `#` at [p]: the length of the prefix, or -1. */
    private fun tagRunPrefix(s: String, p: Int): Int {
        if (p == 0 && s.isNotEmpty() && s[0] == '#') return 0
        if (p >= s.length) return -1
        if (Js.isSpace(s[p]) || s[p] == '>') return if (p + 1 < s.length && s[p + 1] == '#') 1 else -1
        if (s.startsWith("&gt;", p)) return if (p + 4 < s.length && s[p + 4] == '#') 4 else -1
        return -1
    }

    /** Candidate starts of the tag run: 0, or the position of a prefix before a `#`. */
    private fun tagRunNext(s: String, from: Int): Int {
        var f = from
        while (true) {
            if (f == 0 && s.isNotEmpty() && s[0] == '#') return 0
            val h = s.indexOf('#', maxOf(f + 1, 1))
            if (h < 0) return -1
            // the prefix is one character, or `&gt;` (four)
            val candidate = if (h >= 4 && s.startsWith("&gt;", h - 4) && h - 4 >= f) h - 4 else h - 1
            if (candidate >= f) return candidate
            f = h
        }
    }

    fun linkify(input: String, ctx: RenderContext): String {
        val forApp = ctx.forApp
        val opts = ctx.options
        var content = input
        // the author placeholders (web enhanced mentions, app mentions built by the tag pass)
        val placeholders = ArrayList<String>()

        fun tagHtml(tag: String): String {
            val tagLower = Js.toLowerCase(tag.substring(1))
            if (!forApp) {
                return if (opts.inertAuthorAndTagChips) "<span class=\"er-tag er-tag-link\">$tag</span>"
                else "<a class=\"er-tag er-tag-link\" href=\"/trending/${attr(tagLower)}\">$tag</a>"
            }
            return "<a class=\"markdown-tag-link\" data-tag=\"${attr(tagLower)}\">$tag</a>"
        }

        fun mentionHtml(mention: String, before: String, placeholder: Boolean): String? {
            val user = mention.substring(1)
            if (user.indexOf('/') != -1 || !Helper.isValidUsername(user)) return null
            val userLower = Js.toLowerCase(user)
            val html: String
            if (!forApp) {
                val avatarSrc = "${ctx.proxy.base}/u/$userLower/avatar/small"
                val inner = "<img class=\"er-author-link-image\" src=\"${attr(avatarSrc)}\" alt=\"${attr(userLower)}\"/>@$userLower"
                html = if (opts.inertAuthorAndTagChips) "$before<span class=\"er-author er-author-link\">$inner</span>"
                else "$before<a class=\"er-author er-author-link\" href=\"/@${attr(userLower)}\">$inner</a>"
            } else {
                html = "$before<a class=\"markdown-author-link\" data-author=\"${attr(userLower)}\">@$user</a>"
                if (!placeholder) return html
            }
            val token = "\u200C${placeholders.size}\u200C"
            placeholders.add(html)
            return token
        }

        // 2.5.45 (#1920): an adjacent run of tags and mentions linked in one pass, stopping at the first item that
        // would not be linked. The items are read one at a time (each greedy, as the regex `(?:TAG|MENTION)*` takes
        // them) and the run is not read past a refused one: collecting it whole first made every refused head
        // re-read the rest of the run (quadratic). [firstEnd] is the end of the first item when the caller already
        // knows it. Returns the markup, how many items it linked, the length they cover and the first item's length.
        fun linkRun(s: String, from: Int, firstEnd: Int, before: String, placeholder: Boolean): RunResult {
            val out = StringBuilder()
            var pending = before
            var i = from
            var linked = 0
            var firstLength = 0
            while (true) {
                val e = if (linked == 0 && firstEnd >= 0) firstEnd else runItemEnd(s, i)
                if (e < 0) break
                val item = s.substring(i, e)
                if (linked == 0) firstLength = item.length
                val html = if (item[0] == '#') {
                    if (item.length > 1 && item.substring(1).all { it in '0'..'9' }) null else pending + tagHtml(item)
                } else {
                    mentionHtml(item, pending, placeholder)
                }
                if (html == null) break
                out.append(html)
                pending = ""
                linked++
                i = e
            }
            return RunResult(out.toString(), linked, i - from, firstLength)
        }

        // Tags, and any mentions glued to them.
        content = replaceRuns(content, { f -> tagRunNext(content, f) }, { p -> tagRunPrefix(content, p) }) { p, prefix ->
            val before = content.substring(p, p + prefix)
            val preceding = if (before == ">" || before == "&gt;" || (before.length == 1 && Js.isSpace(before[0]))) before else ""
            val run = linkRun(content, p + prefix, -1, preceding, true)
            if (run.firstLength == 0) return@replaceRuns null
            if (run.linked == 0) {
                val head = before.length + run.firstLength
                content.substring(p, p + head) to head
            } else {
                run.html to before.length + run.length
            }
        }

        // Mentions, and any tags glued to them.
        val c1 = content
        val atFinder = AtFinder(c1)
        content = replaceRuns(c1, { f -> atFinder.next(f) }, { p ->
            var at = -1
            if (p == 0 && c1.isNotEmpty() && isAt(c1[0]) && userEnd(c1, 1) >= 0) at = 0
            else if (p < c1.length && !(isWordChar(c1[p]) || c1[p] == '/') && p + 1 < c1.length && isAt(c1[p + 1]) && userEnd(c1, p + 2) >= 0) at = p + 1
            if (at < 0) -1 else at - p
        }) { p, prefix ->
            // `(^|[^set1]|(^|[^set2]))`: group 1 holds the character before the mention, once (2.5.45 no longer
            // writes it twice).
            val preceding = c1.substring(p, p + prefix)
            val at = p + prefix
            val firstEnd = userEnd(c1, at + 1)
            val run = linkRun(c1, at, firstEnd, preceding, false)
            val head = prefix + run.firstLength
            if (run.linked == 0) c1.substring(p, p + head) to head
            else run.html to prefix + run.length
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

        // 2.5.46 (#2094): each URL token whose path ends in an image extension is an image, and what follows it
        // stays text.
        var firstImageUsed = false
        content = urlTokens(content) { token ->
            val end = Helper.imageUrlEnd(token)
            if (end == -1) return@urlTokens token
            val isLCP = !firstImageUsed
            firstImageUsed = true
            ImgMethod.createImageHTML(token.substring(0, end), isLCP, ctx) + token.substring(end)
        }

        // Author placeholders restored in one scan, the first occurrence of each only (2.5.45).
        if (placeholders.isNotEmpty()) content = restorePlaceholders(content, placeholders)
        return content
    }

    /**
     * linkify `replaceRuns`: a global replace whose callback says how much of the match it consumed; scanning resumes
     * right after that. [prefixAt] gives the length of the match's prefix at a candidate, or -1; [fn] returns the
     * replacement and the consumed length, or null for no match there.
     */
    private inline fun replaceRuns(s: String, next: (Int) -> Int, prefixAt: (Int) -> Int, fn: (Int, Int) -> Pair<String, Int>?): String {
        var sb: StringBuilder? = null
        var last = 0
        var p = next(0)
        while (p >= 0 && p <= s.length) {
            val prefix = prefixAt(p)
            val r = if (prefix < 0) null else fn(p, prefix)
            if (r == null) {
                p = next(p + 1)
                continue
            }
            if (sb == null) sb = StringBuilder(s.length + 64)
            sb.append(s, last, p).append(r.first)
            last = p + maxOf(r.second, 1)
            p = next(last)
        }
        if (sb == null) return s
        sb.append(s, last, s.length)
        return sb.toString()
    }

    /** `/https?:\/\/(?:[^\s<>"'&]|&(?!lt;|gt;))+/gi` (the lookahead case-insensitive too), each token through [fn]. */
    private inline fun urlTokens(s: String, fn: (String) -> String): String {
        var sb: StringBuilder? = null
        var last = 0
        var i = 0
        while (i < s.length) {
            val h = Js.indexOfCi(s, "http", i)
            if (h < 0) break
            var j = h + 4
            if (j < s.length && (s[j] == 's' || s[j] == 'S')) j++
            if (!s.startsWith("://", j)) {
                i = h + 1
                continue
            }
            val start = j + 3
            var k = start
            while (k < s.length) {
                val c = s[k]
                if (Js.isSpace(c) || c == '<' || c == '>' || c == '"' || c == '\'') break
                if (c == '&' && (Js.startsWithCi(s, "lt;", k + 1) || Js.startsWithCi(s, "gt;", k + 1))) break // `/gi`
                k++
            }
            if (k == start) {
                i = h + 1
                continue
            }
            if (sb == null) sb = StringBuilder(s.length + 64)
            sb.append(s, last, h).append(fn(s.substring(h, k)))
            last = k
            i = k
        }
        if (sb == null) return s
        sb.append(s, last, s.length)
        return sb.toString()
    }

    /** `/\u200C(\d+)(?=\u200C)/g`: a real placeholder (in range, first time) becomes its markup with both delimiters. */
    private fun restorePlaceholders(s: String, html: List<String>): String {
        val restored = HashSet<Int>()
        val out = StringBuilder(s.length + 64)
        var last = 0
        var i = s.indexOf('\u200C')
        while (i >= 0) {
            var j = i + 1
            while (j < s.length && s[j] in '0'..'9') j++
            if (j == i + 1 || j >= s.length || s[j] != '\u200C') {
                // no digits, or no closing mark: the regex moves on from the next character (or past the digits)
                i = s.indexOf('\u200C', if (j == i + 1) i + 1 else j)
                continue
            }
            val digits = s.substring(i + 1, j)
            val index = if (digits.length > 9) -1 else digits.toInt()
            if (index < 0 || index >= html.size || !restored.add(index)) {
                i = s.indexOf('\u200C', j)
                continue
            }
            out.append(s, last, i).append(html[index])
            last = j + 1
            i = s.indexOf('\u200C', last)
        }
        out.append(s, last, s.length)
        return out.toString()
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

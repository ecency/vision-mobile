package com.ecency.core.render.methods

import com.ecency.core.render.js.Js
import com.ecency.core.render.js.UriError
import com.ecency.core.render.dom.DomParseException
import com.ecency.core.render.dom.Document
import com.ecency.core.render.dom.HtmlStringFixes
import com.ecency.core.render.dom.XmlDomParser
import com.ecency.core.render.sanitize.RenderText
import com.ecency.core.render.url.WebUrl

/** render-helper `helper.ts` (the parts not already ported with the DOM layer). */
internal object Helper {
    fun decodeEntities(value: String): String = RenderText.decodeEntities(value)

    /** Entities, then percent-decoding; a malformed `%` falls back to the entity-decoded string. */
    fun decodeImageSrc(src: String): String {
        val entityDecoded = decodeEntities(src)
        return try {
            Js.trim(Js.decodeUriComponent(entityDecoded))
        } catch (e: UriError) {
            Js.trim(entityDecoded)
        }
    }

    /** `createDoc`: `<body>` wrapped xmldom parse, null for blank input or a parse error. */
    fun createDoc(html: String): Document? {
        if (Js.trim(html).isEmpty()) return null
        return try {
            XmlDomParser.parseFromString("<body>" + HtmlStringFixes.removeDuplicateAttributes(html) + "</body>")
        } catch (e: DomParseException) {
            null
        }
    }

    /** Removes every `<...>` with at least one character inside; a bare `<` or `<>` stays. */
    fun stripHtmlTags(s: String): String {
        val n = s.length
        val out = StringBuilder(n)
        var i = 0
        while (i < n) {
            val lt = s.indexOf('<', i)
            if (lt < 0) {
                out.append(s, i, n)
                break
            }
            out.append(s, i, lt)
            val gt = s.indexOf('>', lt + 1)
            if (gt < 0) {
                out.append(s, lt, n)
                break
            }
            if (gt == lt + 1) {
                out.append(s, lt, gt + 1)
                i = gt + 1
                continue
            }
            i = gt + 1
        }
        return out.toString()
    }

    fun trimTrailingSlash(s: String): String {
        var end = s.length
        while (end > 0 && s[end - 1] == '/') end--
        return s.substring(0, end)
    }

    fun stripQueryString(s: String): String {
        val q = s.indexOf('?')
        return if (q >= 0 && q < s.length - 1) s.substring(0, q) else s
    }

    /** `t` (through `parseInt`) or `start` from the query of [url], else "". */
    fun extractYtStartTime(url: String): String {
        val u = WebUrl.parse(url) ?: return ""
        val params = u.searchParams
        val t = params.firstOrNull { it.first == "t" }
        if (t != null) return Js.parseIntToString(t.second.ifEmpty { "0" })
        val start = params.firstOrNull { it.first == "start" }
        return start?.second ?: ""
    }

    fun youtubeVideoLinkClass(matchedUrl: String): String {
        val base = "markdown-video-link markdown-video-link-youtube"
        return if (Js.indexOfCi(matchedUrl, "youtube.com/shorts/") >= 0) "$base markdown-video-link-youtube-portrait" else base
    }

    fun sanitizePermlink(permlink: String?): String {
        if (permlink.isNullOrEmpty()) return ""
        return permlink.substringBefore('?').substringBefore('#')
    }

    /** Hive permlink shape `^[a-z0-9-]+$` (which already rules out image extensions). */
    fun isValidPermlink(permlink: String?): Boolean {
        val s = sanitizePermlink(permlink)
        if (s.isEmpty()) return false
        for (c in s) if (!(c in 'a'..'z' || c in '0'..'9' || c == '-')) return false
        return true
    }

    fun isValidUsername(username: String?): Boolean {
        if (username.isNullOrEmpty()) return false
        if (username.length > 16) return false
        for (label in username.split('.')) {
            if (label.length < 3 || label.length > 16) return false
            if (label[0] !in 'a'..'z') return false
            for (c in label) if (!(c in 'a'..'z' || c in '0'..'9' || c == '-')) return false
            val last = label[label.length - 1]
            if (!(last in 'a'..'z' || last in '0'..'9')) return false
        }
        return true
    }
}

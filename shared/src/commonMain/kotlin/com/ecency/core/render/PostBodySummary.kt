package com.ecency.core.render

import com.ecency.core.render.js.Js
import com.ecency.core.render.methods.CleanReply
import com.ecency.core.render.methods.Helper
import com.ecency.core.render.methods.Scanners
import com.ecency.core.render.markdown.Markdown

/** render-helper `post-body-summary.ts` (without its memo cache). */
internal object PostBodySummary {
    private fun joint(arr: List<String>, limit: Int): String {
        var result = ""
        for (part in arr) {
            if (result.isNotEmpty()) result += " "
            if (result.length > limit) break
            if ((result + part).length < limit + 10) result += part else break
        }
        return Js.trim(result)
    }

    fun summary(entryBody: String, length: Int, platform: RenderHelper.Platform): String {
        if (entryBody.isEmpty()) return ""
        var body = CleanReply.cleanReply(entryBody)
        val web = platform == RenderHelper.Platform.WEB
        val placeholders = ArrayList<String>()
        if (!web) {
            val entities = Scanners.entityMatches(body)
            for ((index, entity) in LinkedHashSet(entities).withIndex()) {
                placeholders.add(entity)
                body = Js.replaceAll(body, entity, "​$index​")
            }
        }
        var text = try {
            Markdown.renderSummary(body)
        } catch (e: Exception) {
            ""
        }
        if (!web && placeholders.isNotEmpty()) {
            placeholders.forEachIndexed { index, entity -> text = Js.replaceAll(text, "​$index​", entity) }
        }
        text = Helper.stripHtmlTags(text)
        text = newlinesToSpaces(text)
        text = removeUrls(text)
        text = collapseSpaces(Js.trim(text))
        if (length > 0) text = joint(text.split(' '), length)
        if (text.isNotEmpty()) text = Helper.decodeEntities(text)
        return text
    }

    /** `.replace(/\r?\n|\r/g, ' ')` */
    private fun newlinesToSpaces(s: String): String {
        if (s.indexOf('\n') < 0 && s.indexOf('\r') < 0) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\r' && i + 1 < s.length && s[i + 1] == '\n') {
                sb.append(' ')
                i += 2
                continue
            }
            sb.append(if (c == '\n' || c == '\r') ' ' else c)
            i++
        }
        return sb.toString()
    }

    /** `.replace(/(?:https?|ftp):\/\/[\n\S]+/g, '')` */
    private fun removeUrls(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val q = when {
                s.startsWith("https://", i) -> i + 8
                s.startsWith("http://", i) -> i + 7
                s.startsWith("ftp://", i) -> i + 6
                else -> -1
            }
            if (q >= 0) {
                var e = q
                while (e < s.length && (s[e] == '\n' || !Js.isSpace(s[e]))) e++
                if (e > q) {
                    i = e
                    continue
                }
            }
            sb.append(s[i])
            i++
        }
        return sb.toString()
    }

    /** `.replace(/ {2,}/g, ' ')` */
    private fun collapseSpaces(s: String): String {
        if (!s.contains("  ")) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            if (s[i] == ' ') {
                var j = i
                while (j < s.length && s[j] == ' ') j++
                sb.append(' ')
                i = j
            } else {
                sb.append(s[i++])
            }
        }
        return sb.toString()
    }
}

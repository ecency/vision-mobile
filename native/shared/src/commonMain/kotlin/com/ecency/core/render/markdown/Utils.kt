package com.ecency.core.render.markdown

import com.ecency.core.render.js.Js
import com.ecency.core.render.js.UriError
/** remarkable's lib/common/utils.js and the link helpers shared by its rules. */
internal object Utils {
    private val entities: Map<String, String> by lazy {
        val map = HashMap<String, String>(2400)
        for (entry in EntityData.TABLE.split(';')) {
            val eq = entry.indexOf('=')
            val value = entry.substring(eq + 1).split(' ').map { it.toInt(16).toChar() }.toCharArray().concatToString()
            map[entry.substring(0, eq)] = value
        }
        map
    }

    /** Returns the decoded entity, or the name itself when it is not a known entity. */
    fun decodeEntity(name: String): String = entities[name] ?: name

    fun isKnownEntity(name: String): Boolean = entities.containsKey(name)

    private const val MD_ESCAPABLE = "\\!\"#$%&'()*+,./:;<=>?@[]^_`{|}~-"

    /** `str.replace(/\\([<escapable>])/g, '$1')` for [escapable] (MD_ESCAPABLE, optionally plus space). */
    fun unescape(str: String, escapable: String): String {
        if (str.indexOf('\\') < 0) return str
        val sb = StringBuilder(str.length)
        var i = 0
        while (i < str.length) {
            val c = str[i]
            if (c == '\\' && i + 1 < str.length && escapable.indexOf(str[i + 1]) >= 0) {
                sb.append(str[i + 1])
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    fun unescapeMd(str: String): String = unescape(str, MD_ESCAPABLE)

    /** The sub/sup rules' UNESCAPE_RE: the markdown escapable set plus space. */
    fun unescapeWithSpace(str: String): String = unescape(str, " $MD_ESCAPABLE")

    fun isValidEntityCode(c: Long): Boolean {
        if (c in 0xD800..0xDFFF) return false
        if (c in 0xFDD0..0xFDEF) return false
        if ((c and 0xFFFF) == 0xFFFFL || (c and 0xFFFF) == 0xFFFEL) return false
        if (c in 0x00..0x08) return false
        if (c == 0x0BL) return false
        if (c in 0x0E..0x1F) return false
        if (c in 0x7F..0x9F) return false
        if (c > 0x10FFFF) return false
        return true
    }

    private fun isAsciiAlpha(c: Int) = (c in 0x41..0x5A) || (c in 0x61..0x7A)
    private fun isAsciiAlnum(c: Int) = isAsciiAlpha(c) || c in 0x30..0x39
    private fun isHex(c: Int) = (c in 0x30..0x39) || (c in 0x41..0x46) || (c in 0x61..0x66)

    /** `/^#((?:x[a-f0-9]{1,8}|[0-9]{1,8}))/i.test(name)` (DIGITAL_ENTITY_TEST_RE). */
    private fun digitalEntityTest(name: String): Boolean {
        if (Js.cc(name, 0) != 0x23) return false
        val c1 = Js.cc(name, 1)
        if ((c1 == 0x78 || c1 == 0x58) && isHex(Js.cc(name, 2))) return true
        return Js.cc(name, 1) in 0x30..0x39
    }

    private fun replaceEntityPattern(match: String, name: String): String {
        val decoded = decodeEntity(name)
        if (name != decoded) return decoded
        if (name[0] == '#' && digitalEntityTest(name)) {
            val code = if (name[1] == 'x' || name[1] == 'X') Js.parseIntPrefix(name.substring(2), 16) else Js.parseIntPrefix(name.substring(1), 10)
            if (code != null && isValidEntityCode(code)) return Js.fromCodePoint(code.toInt())
        }
        return match
    }

    /** `str.replace(/&([a-z#][a-z0-9]{1,31});/gi, replaceEntityPattern)`. */
    fun replaceEntities(str: String): String {
        if (str.indexOf('&') < 0) return str
        val sb = StringBuilder(str.length)
        var i = 0
        val len = str.length
        while (i < len) {
            val c = str[i]
            if (c == '&') {
                val c1 = Js.cc(str, i + 1)
                if (isAsciiAlpha(c1) || c1 == 0x23) {
                    var j = i + 2
                    while (j < len && isAsciiAlnum(str[j].code)) j++
                    val run = j - (i + 2)
                    if (run in 1..31 && j < len && str[j] == ';') {
                        sb.append(replaceEntityPattern(str.substring(i, j + 1), str.substring(i + 1, j)))
                        i = j + 1
                        continue
                    }
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    fun escapeHtml(str: String): String {
        var i = 0
        while (i < str.length) {
            val c = str[i]
            if (c == '&' || c == '<' || c == '>' || c == '"') break
            i++
        }
        if (i == str.length) return str
        val sb = StringBuilder(str.length + 16).append(str, 0, i)
        while (i < str.length) {
            when (val c = str[i]) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    fun normalizeLink(url: String): String {
        var normalized = replaceEntities(url)
        try {
            normalized = Js.decodeUri(normalized)
        } catch (_: UriError) {
        }
        return Js.encodeUri(normalized)
    }

    private val BAD_PROTOCOLS = listOf("vbscript", "javascript", "file", "data")

    fun validateLink(url: String): Boolean {
        val str = replaceEntities(Js.trim(url).lowercase())
        val colon = str.indexOf(':')
        return !(colon != -1 && BAD_PROTOCOLS.contains(str.substring(0, colon)))
    }

    fun normalizeReference(str: String): String = Js.collapseSpaces(Js.trim(str)).uppercase()
}

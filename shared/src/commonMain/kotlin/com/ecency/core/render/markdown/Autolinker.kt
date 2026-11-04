package com.ecency.core.render.markdown

import com.ecency.core.render.js.Js
/**
 * The parts of Autolinker 3.16.2 that remarkable's linkify plugin uses, configured as linkify
 * configures it (`stripPrefix: false, url: true, email: true`; phone stays on with its default,
 * hashtag and mention off). Only url and email matches are returned, but phone and hashtag
 * matches still take part in overlap removal, exactly as in Autolinker.
 */
internal object Autolinker {

    class Link(val text: String, val url: String)

    private enum class Type { URL, EMAIL, PHONE, HASHTAG }

    private class Match(val type: Type, val matchedText: String, var offset: Int, val url: String = "")

    // ---- character tables -----------------------------------------------------------------------

    private fun decodeRanges(data: String): BooleanArray {
        val set = BooleanArray(0x10000)
        for (range in data.split(',')) {
            val dash = range.indexOf('-')
            val lo = range.substring(0, dash).toInt(16)
            val hi = range.substring(dash + 1).toInt(16)
            for (c in lo..hi) set[c] = true
        }
        return set
    }

    private fun decodeTable(data: String): CharArray {
        val table = CharArray(0x10000)
        for (run in data.split(',')) {
            val dash = run.indexOf('-')
            val colon = run.indexOf(':')
            val lo = run.substring(0, dash).toInt(16)
            val hi = run.substring(dash + 1, colon).toInt(16)
            val value = run.substring(colon + 1)
            if (value == "i") {
                for (c in lo..hi) table[c] = c.toChar()
            } else {
                val v = value.toInt(16).toChar()
                for (c in lo..hi) table[c] = v
            }
        }
        return table
    }

    private val alnumMarks: BooleanArray by lazy { decodeRanges(AutolinkerData.ALPHA_NUMERIC_AND_MARKS) }
    private val plainTable: CharArray by lazy { decodeTable(AutolinkerData.PLAIN_TABLE) }
    private val tlds: Set<String> by lazy { AutolinkerData.TLDS.split('|').toHashSet() }
    private val wordCharAfterProtocolRegex: Regex by lazy { Regex(AutolinkerData.WORD_CHAR_AFTER_PROTOCOL_PATTERN) }
    private val invalidCharAfterTldRegex: Regex by lazy { Regex(AutolinkerData.INVALID_CHAR_AFTER_TLD_PATTERN) }

    // mostPhoneNumbers|japanesePhoneRe. `\d` is spelled [0-9] and the trailing `([,;]+[0-9]+#?)*` group
    // is possessive and non-capturing: nothing follows it, so backtracking into it never changes the
    // match, and its capture only feeds a test that is always true.
    private val phoneRegex: Regex by lazy {
        Regex(
            "(?:(?:(?:(\\+)?[0-9]{1,3}[- .]?)?\\(?[0-9]{3}\\)?[- .]?[0-9]{3}[- .]?[0-9]{4})|" +
                "(?:(\\+)(?:9[976][0-9]|8[987530][0-9]|6[987][0-9]|5[90][0-9]|42[0-9]|3[875][0-9]|2[98654321][0-9]|" +
                "9[8543210]|8[6421]|6[6543210]|5[87654321]|4[987654310]|3[9643210]|2[70]|7|1)" +
                "[- .]?(?:[0-9][- .]?){6,12}[0-9]+))(?:[,;]+[0-9]+#?)*+" +
                "|(0(?:[1-9]-?[1-9][0-9]{3}|[1-9]{2}-?[0-9]{3}|[1-9]{2}[0-9]-?[0-9]{2}|[1-9]{2}[0-9]{2}-?[0-9])-?[0-9]{4}" +
                "|0[789]0-?[0-9]{4}-?[0-9]{4}|050-?[0-9]{4}-?[0-9]{4})",
        )
    }

    private fun isAlnumMarks(c: Char) = alnumMarks[c.code]

    private fun mapThrough(s: String, table: CharArray): String {
        val out = CharArray(s.length)
        for (i in s.indices) out[i] = table[s[i].code]
        return out.concatToString()
    }

    // ---- entry point ----------------------------------------------------------------------------

    /** `autolinker.link(text)` with linkify's replaceFn: the url/email links found, in order. */
    fun links(text: String): List<Link> {
        if (text.isEmpty()) return emptyList()
        var matches = parse(text)
        matches = compact(matches)
        matches.removeAll { it.type == Type.HASHTAG }
        val out = ArrayList<Link>()
        for (m in matches) {
            when (m.type) {
                Type.URL -> out.add(Link(m.matchedText, m.url))
                Type.EMAIL -> out.add(Link(m.matchedText, "mailto:" + stripMailto(m.url)))
                else -> {}
            }
        }
        return out
    }

    /** `.replace(/^mailto:/i, '')`. */
    private fun stripMailto(email: String): String =
        if (regionEqualsAsciiIgnoreCase(email, 0, "mailto:")) email.substring(7) else email

    private fun asciiLower(c: Char): Char = if (c in 'A'..'Z') c + 32 else c

    private fun compact(input: MutableList<Match>): MutableList<Match> {
        val matches = input.sortedBy { it.offset }.toMutableList()
        var i = 0
        while (i < matches.size - 1) {
            val match = matches[i]
            val offset = match.offset
            val matchedTextLength = match.matchedText.length
            val endIdx = offset + matchedTextLength
            if (i + 1 < matches.size) {
                if (matches[i + 1].offset == offset) {
                    val removeIdx = if (matches[i + 1].matchedText.length > matchedTextLength) i else i + 1
                    matches.removeAt(removeIdx)
                    continue
                }
                if (matches[i + 1].offset < endIdx) {
                    matches.removeAt(i + 1)
                    continue
                }
            }
            i++
        }
        return matches
    }

    // ---- HTML scan (htmlParser/parse-html.js), only what parse() listens to ----------------------

    private val ENTITIES = listOf("&nbsp;", "&#160;", "&lt;", "&#60;", "&gt;", "&#62;", "&quot;", "&#34;", "&#39;")

    private fun parse(html: String): MutableList<Match> {
        val matches = ArrayList<Match>()
        var skipTagsStackCount = 0
        HtmlScanner(html, object : HtmlScanner.Handler {
            override fun onOpenTag(name: String) {
                if (name == "a" || name == "style" || name == "script") skipTagsStackCount++
            }

            override fun onCloseTag(name: String) {
                if (name == "a" || name == "style" || name == "script") skipTagsStackCount = maxOf(skipTagsStackCount - 1, 0)
            }

            override fun onText(text: String, offset: Int) {
                if (skipTagsStackCount != 0) return
                // splitAndCapture(text, /(&nbsp;|&#160;|&lt;|&#60;|&gt;|&#62;|&quot;|&#34;|&#39;)/gi)
                var currentOffset = offset
                var last = 0
                var i = 0
                while (i < text.length) {
                    if (text[i] == '&') {
                        val entity = ENTITIES.firstOrNull { e -> regionEqualsAsciiIgnoreCase(text, i, e) }
                        if (entity != null) {
                            val piece = text.substring(last, i)
                            matches.addAll(parseText(piece, currentOffset))
                            currentOffset += piece.length + entity.length
                            i += entity.length
                            last = i
                            continue
                        }
                    }
                    i++
                }
                matches.addAll(parseText(text.substring(last), currentOffset))
            }
        }).run()
        return matches
    }

    private fun regionEqualsAsciiIgnoreCase(text: String, at: Int, lowerNeedle: String): Boolean {
        if (at + lowerNeedle.length > text.length) return false
        for (k in lowerNeedle.indices) if (asciiLower(text[at + k]) != lowerNeedle[k]) return false
        return true
    }

    private fun parseText(text: String, offset: Int): List<Match> {
        val matches = ArrayList<Match>()
        for (m in hashtagMatches(text)) matches.add(m)
        for (m in emailMatches(text)) matches.add(m)
        for (m in phoneMatches(text)) matches.add(m)
        for (m in urlMatches(text)) matches.add(m)
        for (m in matches) m.offset += offset
        return matches
    }

    // ---- HashtagMatcher -------------------------------------------------------------------------

    private fun hashtagMatches(text: String): List<Match> {
        val matches = ArrayList<Match>()
        var state = 0 // 0 None, 1 NonHashtagWordChar, 2 HashtagHashChar, 3 HashtagTextChar
        var hashCharIdx = -1
        var charIdx = 0
        fun capture() {
            if (hashCharIdx > -1 && charIdx - hashCharIdx <= 140) {
                matches.add(Match(Type.HASHTAG, text.substring(hashCharIdx, charIdx), hashCharIdx))
            }
        }
        while (charIdx < text.length) {
            val c = text[charIdx]
            when (state) {
                0 -> if (c == '#') {
                    state = 2
                    hashCharIdx = charIdx
                } else if (isAlnumMarks(c)) {
                    state = 1
                }
                1 -> if (!isAlnumMarks(c)) state = 0
                2 -> state = if (c == '_' || isAlnumMarks(c)) 3 else if (isAlnumMarks(c)) 1 else 0
                3 -> if (!(c == '_' || isAlnumMarks(c))) {
                    capture()
                    hashCharIdx = -1
                    state = if (isAlnumMarks(c)) 1 else 0
                }
            }
            charIdx++
        }
        capture()
        return matches
    }

    // ---- EmailMatcher ---------------------------------------------------------------------------

    private const val LOCAL_PART_EXTRA = "!#$%&'*+/=?^_`{|}~-"

    private fun isLocalPartChar(c: Char) = isAlnumMarks(c) || LOCAL_PART_EXTRA.indexOf(c) >= 0

    private fun mailtoNext(prev: Char): Char? = when (prev) {
        'm' -> 'a'; 'a' -> 'i'; 'i' -> 'l'; 'l' -> 't'; 't' -> 'o'; 'o' -> ':'
        else -> null
    }

    private fun emailMatches(text: String): List<Match> {
        val matches = ArrayList<Match>()
        val len = text.length
        var charIdx = 0
        var state = 0 // 0 Non, 1 Mailto, 2 LocalPart, 3 LocalPartDot, 4 AtSign, 5 DomainChar, 6 DomainHyphen, 7 DomainDot
        var idx = -1
        var hasMailtoPrefix = false
        var hasDomainDot = false

        fun reset() {
            state = 0
            idx = -1
            hasMailtoPrefix = false
            hasDomainDot = false
        }

        fun begin(newState: Int) {
            state = newState
            idx = charIdx
            hasMailtoPrefix = false
            hasDomainDot = false
        }

        fun captureAndReset() {
            if (hasDomainDot) {
                var matchedText = Js.slice(text, idx, charIdx)
                if (matchedText.endsWith("-") || matchedText.endsWith(".")) matchedText = Js.slice(matchedText, 0, -1)
                val emailAddress = if (hasMailtoPrefix) Js.slice(matchedText, "mailto:".length) else matchedText
                val tld = emailAddress.split('.').last().lowercase()
                if (tld in tlds) matches.add(Match(Type.EMAIL, matchedText, idx, emailAddress))
            }
            reset()
        }

        while (charIdx < len) {
            val c = text[charIdx]
            when (state) {
                0 -> if (c == 'm') begin(1) else if (isLocalPartChar(c)) begin(2)
                1 -> {
                    val prev = if (charIdx >= 1) text[charIdx - 1] else null
                    if (prev == ':') {
                        if (isLocalPartChar(c)) {
                            state = 2
                            hasMailtoPrefix = true
                        } else {
                            reset()
                        }
                    } else if (prev != null && mailtoNext(prev) == c) {
                        // still reading "mailto:"
                    } else if (isLocalPartChar(c)) {
                        state = 2
                    } else if (c == '.') {
                        state = 3
                    } else if (c == '@') {
                        state = 4
                    } else {
                        reset()
                    }
                }
                2 -> when {
                    c == '.' -> state = 3
                    c == '@' -> state = 4
                    isLocalPartChar(c) -> {}
                    else -> reset()
                }
                3 -> when {
                    c == '.' -> reset()
                    c == '@' -> reset()
                    isLocalPartChar(c) -> state = 2
                    else -> reset()
                }
                4 -> if (isAlnumMarks(c)) state = 5 else reset()
                5 -> when {
                    c == '.' -> state = 7
                    c == '-' -> state = 6
                    isAlnumMarks(c) -> {}
                    else -> captureAndReset()
                }
                6 -> when {
                    c == '-' || c == '.' -> captureAndReset()
                    isAlnumMarks(c) -> state = 5
                    else -> captureAndReset()
                }
                7 -> when {
                    c == '.' || c == '-' -> captureAndReset()
                    isAlnumMarks(c) -> {
                        state = 5
                        hasDomainDot = true
                    }
                    else -> captureAndReset()
                }
            }
            charIdx++
        }
        captureAndReset()
        return matches
    }

    // ---- PhoneMatcher ---------------------------------------------------------------------------

    private fun phoneMatches(text: String): List<Match> {
        val matches = ArrayList<Match>()
        var p = 0
        while (p < text.length) {
            // Every phone match starts with '+', '(' or a digit; trying only those positions finds
            // the same leftmost matches as scanning with the global regex.
            val c = text[p]
            if (c != '+' && c != '(' && c !in '0'..'9') {
                p++
                continue
            }
            val m = phoneRegex.matchAt(text, p)
            if (m == null) {
                p++
                continue
            }
            val matchedText = m.value
            val index = p
            p = m.range.last + 1
            val before = if (index == 0) -1 else Js.cc(text, index - 1)
            val after = Js.cc(text, index + matchedText.length)
            val contextClear = before !in 0x30..0x39 && after !in 0x30..0x39
            // testMatch(match[3]) is always true; testMatch(matchedText) needs a non-digit.
            if (matchedText.any { it !in '0'..'9' } && contextClear) {
                matches.add(Match(Type.PHONE, matchedText, index))
            }
        }
        return matches
    }

    // ---- UrlMatcher -----------------------------------------------------------------------------

    private fun urlMatches(text: String): List<Match> {
        val matches = ArrayList<Match>()
        var from = 0
        while (from < text.length) {
            val m = UrlScanner.find(text, from) ?: break
            from = m.end
            var matchStr = text.substring(m.start, m.end)
            var schemeUrlMatch = if (m.schemeEnd >= 0) text.substring(m.start, m.schemeEnd) else null
            val protocolRelativeMatch = m.protocolRelative
            var offset = m.start
            val prevChar = if (offset >= 1) text[offset - 1] else null

            if (!isValidUrl(matchStr, schemeUrlMatch)) continue
            if (offset > 0 && prevChar == '@') continue
            if (offset > 0 && protocolRelativeMatch && prevChar != null && isAlnumMarks(prevChar)) continue
            if (matchStr.endsWith("?")) matchStr = Js.substr(matchStr, 0, matchStr.length - 1)
            if (matchHasUnbalancedClosingParen(matchStr)) {
                matchStr = Js.substr(matchStr, 0, matchStr.length - 1)
            } else {
                val pos = matchHasInvalidCharAfterTld(matchStr, schemeUrlMatch)
                if (pos > -1) matchStr = Js.substr(matchStr, 0, pos)
            }
            val foundCommonScheme = listOf("http://", "https://").firstOrNull { schemeUrlMatch != null && schemeUrlMatch.indexOf(it) != -1 }
            if (foundCommonScheme != null) {
                val indexOfSchemeStart = matchStr.indexOf(foundCommonScheme)
                matchStr = Js.substr(matchStr, indexOfSchemeStart)
                schemeUrlMatch = Js.substr(schemeUrlMatch!!, indexOfSchemeStart)
                offset += indexOfSchemeStart
            }
            val protocolUrlMatch = !schemeUrlMatch.isNullOrEmpty()
            // UrlMatch.getUrl(): prepend http:// unless the match has a scheme or starts with "//".
            val url = if (!protocolRelativeMatch && !protocolUrlMatch) "http://$matchStr" else matchStr
            matches.add(Match(Type.URL, matchStr, offset, url))
        }
        return matches
    }

    private fun matchHasUnbalancedClosingParen(matchStr: String): Boolean {
        val endChar = if (matchStr.isEmpty()) return false else matchStr[matchStr.length - 1]
        val startChar = when (endChar) {
            ')' -> '('
            ']' -> '['
            '}' -> '{'
            else -> return false
        }
        var numOpenBraces = 0
        for (i in 0 until matchStr.length - 1) {
            val c = matchStr[i]
            if (c == startChar) numOpenBraces++ else if (c == endChar) numOpenBraces = maxOf(numOpenBraces - 1, 0)
        }
        return numOpenBraces == 0
    }

    private fun matchHasInvalidCharAfterTld(urlMatchIn: String, schemeUrlMatch: String?): Int {
        var urlMatch = urlMatchIn
        if (urlMatch.isEmpty()) return -1
        var offset = 0
        if (!schemeUrlMatch.isNullOrEmpty()) {
            offset = urlMatch.indexOf(':')
            urlMatch = Js.slice(urlMatch, offset)
        }
        val res = invalidCharAfterTldRegex.find(mapThrough(urlMatch, plainTable)) ?: return -1
        val group1Length = res.groups[1]!!.value.length
        offset += group1Length
        urlMatch = Js.slice(urlMatch, group1Length)
        // /^[^-.A-Za-z0-9:\/?#]/
        if (urlMatch.isNotEmpty()) {
            val c = urlMatch[0]
            val ok = c == '-' || c == '.' || c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == ':' || c == '/' || c == '?' || c == '#'
            if (!ok) return offset
        }
        return -1
    }

    // ---- UrlMatchValidator ----------------------------------------------------------------------

    /** `^[A-Za-z][-.+A-Za-z0-9]*:` length, or -1. */
    private fun schemePrefixLength(s: String): Int {
        if (s.isEmpty() || !(s[0] in 'A'..'Z' || s[0] in 'a'..'z')) return -1
        var i = 1
        while (i < s.length && (s[i] in 'A'..'Z' || s[i] in 'a'..'z' || s[i] in '0'..'9' || s[i] == '-' || s[i] == '.' || s[i] == '+')) i++
        return if (i < s.length && s[i] == ':') i + 1 else -1
    }

    /** hasFullProtocolRegex `^[A-Za-z][-.+A-Za-z0-9]*:\/\/`. */
    private fun hasFullProtocol(s: String): Boolean {
        val n = schemePrefixLength(s)
        return n > 0 && s.startsWith("//", n)
    }

    private val ipAfterProtocolRegex = Regex(
        "^[A-Za-z][-.+A-Za-z0-9]*://[0-9][0-9]?[0-9]?\\.[0-9][0-9]?[0-9]?\\.[0-9][0-9]?[0-9]?\\.[0-9][0-9]?[0-9]?(:[0-9]*)?/?(?![\\s\\S])",
    )

    private fun isValidUrl(urlMatch: String, protocolUrlMatch: String?): Boolean {
        if (!protocolUrlMatch.isNullOrEmpty() && !isValidUriScheme(protocolUrlMatch)) return false
        if (urlMatchDoesNotHaveProtocolOrDot(urlMatch, protocolUrlMatch)) return false
        if (urlMatchDoesNotHaveAtLeastOneWordChar(urlMatch, protocolUrlMatch) && !ipAfterProtocolRegex.containsMatchIn(urlMatch)) return false
        if (containsMultipleDots(urlMatch)) return false
        return true
    }

    private fun isValidUriScheme(uriSchemeMatch: String): Boolean {
        val n = schemePrefixLength(uriSchemeMatch)
        val scheme = if (n < 0) null else uriSchemeMatch.substring(0, n).lowercase()
        return scheme != "javascript:" && scheme != "vbscript:"
    }

    private fun urlMatchDoesNotHaveProtocolOrDot(urlMatch: String, protocolUrlMatch: String?): Boolean =
        urlMatch.isNotEmpty() && (protocolUrlMatch.isNullOrEmpty() || !hasFullProtocol(protocolUrlMatch)) && urlMatch.indexOf('.') == -1

    private fun urlMatchDoesNotHaveAtLeastOneWordChar(urlMatch: String, protocolUrlMatch: String?): Boolean {
        if (urlMatch.isNotEmpty() && !protocolUrlMatch.isNullOrEmpty()) {
            return !hasFullProtocol(protocolUrlMatch) && !wordCharAfterProtocolRegex.containsMatchIn(mapThrough(urlMatch, plainTable))
        }
        return false
    }

    private fun containsMultipleDots(urlMatch: String): Boolean {
        var stringBeforeSlash = urlMatch
        if (hasFullProtocol(urlMatch)) stringBeforeSlash = urlMatch.split("://")[1]
        return stringBeforeSlash.split('/')[0].indexOf("..") > -1
    }
}

/** Autolinker's htmlParser/parse-html.js state machine, reporting tags and text nodes. */
internal class HtmlScanner(private val html: String, private val handler: Handler) {
    interface Handler {
        fun onOpenTag(name: String)
        fun onCloseTag(name: String)
        fun onText(text: String, offset: Int)
    }

    private class Tag(val idx: Int = -1, val type: String = "tag", val name: String = "", val isOpening: Boolean = false, val isClosing: Boolean = false) {
        fun copy(type: String = this.type, name: String = this.name, isOpening: Boolean = this.isOpening, isClosing: Boolean = this.isClosing) =
            Tag(idx, type, name, isOpening, isClosing)
    }

    private val noCurrentTag = Tag()
    private var charIdx = 0
    private var state = DATA
    private var currentDataIdx = 0
    private var currentTag = noCurrentTag

    private fun isLetter(c: Char) = c in 'A'..'Z' || c in 'a'..'z'
    private fun isDigit(c: Char) = c in '0'..'9'
    private fun isSpace(c: Char) = Js.isSpace(c.code)
    private fun isQuote(c: Char) = c == '\'' || c == '"'
    private fun isControl(c: Char) = c.code <= 0x1F || c.code == 0x7F

    fun run() {
        val len = html.length
        while (charIdx < len) {
            val c = html[charIdx]
            when (state) {
                DATA -> if (c == '<') startNewTag()
                TAG_OPEN -> when {
                    c == '!' -> state = MARKUP_DECLARATION_OPEN
                    c == '/' -> {
                        state = END_TAG_OPEN
                        currentTag = currentTag.copy(isClosing = true)
                    }
                    c == '<' -> startNewTag()
                    isLetter(c) -> {
                        state = TAG_NAME
                        currentTag = currentTag.copy(isOpening = true)
                    }
                    else -> resetToDataState()
                }
                TAG_NAME -> when {
                    isSpace(c) -> {
                        currentTag = currentTag.copy(name = captureTagName())
                        state = BEFORE_ATTRIBUTE_NAME
                    }
                    c == '<' -> startNewTag()
                    c == '/' -> {
                        currentTag = currentTag.copy(name = captureTagName())
                        state = SELF_CLOSING_START_TAG
                    }
                    c == '>' -> {
                        currentTag = currentTag.copy(name = captureTagName())
                        emitTagAndPreviousTextNode()
                    }
                    !isLetter(c) && !isDigit(c) && c != ':' -> resetToDataState()
                }
                END_TAG_OPEN -> when {
                    c == '>' -> resetToDataState()
                    isLetter(c) -> state = TAG_NAME
                    else -> resetToDataState()
                }
                BEFORE_ATTRIBUTE_NAME -> when {
                    isSpace(c) -> {}
                    c == '/' -> state = SELF_CLOSING_START_TAG
                    c == '>' -> emitTagAndPreviousTextNode()
                    c == '<' -> startNewTag()
                    c == '=' || isQuote(c) || isControl(c) -> resetToDataState()
                    else -> state = ATTRIBUTE_NAME
                }
                ATTRIBUTE_NAME -> when {
                    isSpace(c) -> state = AFTER_ATTRIBUTE_NAME
                    c == '/' -> state = SELF_CLOSING_START_TAG
                    c == '=' -> state = BEFORE_ATTRIBUTE_VALUE
                    c == '>' -> emitTagAndPreviousTextNode()
                    c == '<' -> startNewTag()
                    isQuote(c) -> resetToDataState()
                }
                AFTER_ATTRIBUTE_NAME -> when {
                    isSpace(c) -> {}
                    c == '/' -> state = SELF_CLOSING_START_TAG
                    c == '=' -> state = BEFORE_ATTRIBUTE_VALUE
                    c == '>' -> emitTagAndPreviousTextNode()
                    c == '<' -> startNewTag()
                    isQuote(c) -> resetToDataState()
                    else -> state = ATTRIBUTE_NAME
                }
                BEFORE_ATTRIBUTE_VALUE -> when {
                    isSpace(c) -> {}
                    c == '"' -> state = ATTRIBUTE_VALUE_DOUBLE_QUOTED
                    c == '\'' -> state = ATTRIBUTE_VALUE_SINGLE_QUOTED
                    c == '>' || c == '=' || c == '`' -> resetToDataState()
                    c == '<' -> startNewTag()
                    else -> state = ATTRIBUTE_VALUE_UNQUOTED
                }
                ATTRIBUTE_VALUE_DOUBLE_QUOTED -> if (c == '"') state = AFTER_ATTRIBUTE_VALUE_QUOTED
                ATTRIBUTE_VALUE_SINGLE_QUOTED -> if (c == '\'') state = AFTER_ATTRIBUTE_VALUE_QUOTED
                ATTRIBUTE_VALUE_UNQUOTED -> when {
                    isSpace(c) -> state = BEFORE_ATTRIBUTE_NAME
                    c == '>' -> emitTagAndPreviousTextNode()
                    c == '<' -> startNewTag()
                }
                AFTER_ATTRIBUTE_VALUE_QUOTED -> when {
                    isSpace(c) -> state = BEFORE_ATTRIBUTE_NAME
                    c == '/' -> state = SELF_CLOSING_START_TAG
                    c == '>' -> emitTagAndPreviousTextNode()
                    c == '<' -> startNewTag()
                    else -> {
                        state = BEFORE_ATTRIBUTE_NAME
                        charIdx-- // reconsume
                    }
                }
                SELF_CLOSING_START_TAG -> if (c == '>') {
                    currentTag = currentTag.copy(isClosing = true)
                    emitTagAndPreviousTextNode()
                } else {
                    state = BEFORE_ATTRIBUTE_NAME
                }
                MARKUP_DECLARATION_OPEN -> if (Js.substr(html, charIdx, 2) == "--") {
                    charIdx += 2
                    currentTag = currentTag.copy(type = "comment")
                    state = COMMENT_START
                } else if (Js.substr(html, charIdx, 7).uppercase() == "DOCTYPE") {
                    charIdx += 7
                    currentTag = currentTag.copy(type = "doctype")
                    state = DOCTYPE
                } else {
                    resetToDataState()
                }
                COMMENT_START -> when (c) {
                    '-' -> state = COMMENT_START_DASH
                    '>' -> resetToDataState()
                    else -> state = COMMENT
                }
                COMMENT_START_DASH -> when (c) {
                    '-' -> state = COMMENT_END
                    '>' -> resetToDataState()
                    else -> state = COMMENT
                }
                COMMENT -> if (c == '-') state = COMMENT_END_DASH
                COMMENT_END_DASH -> state = if (c == '-') COMMENT_END else COMMENT
                COMMENT_END -> when (c) {
                    '>' -> emitTagAndPreviousTextNode()
                    '!' -> state = COMMENT_END_BANG
                    '-' -> {}
                    else -> state = COMMENT
                }
                COMMENT_END_BANG -> when (c) {
                    '-' -> state = COMMENT_END_DASH
                    '>' -> emitTagAndPreviousTextNode()
                    else -> state = COMMENT
                }
                DOCTYPE -> when (c) {
                    '>' -> emitTagAndPreviousTextNode()
                    '<' -> startNewTag()
                }
            }
            charIdx++
        }
        if (currentDataIdx < charIdx) {
            handler.onText(Js.slice(html, currentDataIdx, charIdx), currentDataIdx)
            currentDataIdx = charIdx + 1
        }
    }

    private fun resetToDataState() {
        state = DATA
        currentTag = noCurrentTag
    }

    private fun startNewTag() {
        state = TAG_OPEN
        currentTag = Tag(idx = charIdx)
    }

    private fun emitTagAndPreviousTextNode() {
        val textBeforeTag = Js.slice(html, currentDataIdx, currentTag.idx)
        if (textBeforeTag.isNotEmpty()) handler.onText(textBeforeTag, currentDataIdx)
        if (currentTag.type != "comment" && currentTag.type != "doctype") {
            if (currentTag.isOpening) handler.onOpenTag(currentTag.name)
            if (currentTag.isClosing) handler.onCloseTag(currentTag.name)
        }
        resetToDataState()
        currentDataIdx = charIdx + 1
    }

    private fun captureTagName(): String {
        val startIdx = currentTag.idx + if (currentTag.isClosing) 2 else 1
        return Js.slice(html, startIdx, charIdx).lowercase()
    }

    private companion object {
        const val DATA = 0
        const val TAG_OPEN = 1
        const val END_TAG_OPEN = 2
        const val TAG_NAME = 3
        const val BEFORE_ATTRIBUTE_NAME = 4
        const val ATTRIBUTE_NAME = 5
        const val AFTER_ATTRIBUTE_NAME = 6
        const val BEFORE_ATTRIBUTE_VALUE = 7
        const val ATTRIBUTE_VALUE_DOUBLE_QUOTED = 8
        const val ATTRIBUTE_VALUE_SINGLE_QUOTED = 9
        const val ATTRIBUTE_VALUE_UNQUOTED = 10
        const val AFTER_ATTRIBUTE_VALUE_QUOTED = 11
        const val SELF_CLOSING_START_TAG = 12
        const val MARKUP_DECLARATION_OPEN = 13
        const val COMMENT_START = 14
        const val COMMENT_START_DASH = 15
        const val COMMENT = 16
        const val COMMENT_END_DASH = 17
        const val COMMENT_END = 18
        const val COMMENT_END_BANG = 19
        const val DOCTYPE = 20
    }
}

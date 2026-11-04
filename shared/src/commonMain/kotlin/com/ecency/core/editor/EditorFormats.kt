package com.ecency.core.editor

import com.ecency.core.l10n.L
import com.ecency.core.render.methods.JsRegex

/** A selection in UTF-16 units, as both platform text fields and the RN editor count. */
data class TextSelection(val start: Int, val end: Int) {
    constructor(caret: Int) : this(caret, caret)

    val isCollapsed: Boolean get() = start == end

    /** Inside a text of [length] units, start before end. */
    fun clamped(length: Int): TextSelection {
        val s = start.coerceIn(0, length)
        val e = end.coerceIn(0, length)
        return if (e < s) TextSelection(s, s) else TextSelection(s, e)
    }
}

/** The body after an edit and where the selection goes. [selection] is RN's raw value; fields apply [clamped]. */
data class TextEdit(val text: String, val selection: TextSelection) {
    val clamped: TextEdit get() = TextEdit(text, selection.clamped(text.length))
}

enum class MediaInsertStatus { UPLOADING, READY, FAILED }

/** RN MediaInsertData: an image (or raw video url) on its way into the body. */
data class MediaInsert(
    val url: String = "",
    val filename: String? = null,
    val text: String = "",
    val status: MediaInsertStatus,
    val isVideo: Boolean = false,
)

/**
 * The RN editor's text operations (components/markdownEditor/children/formats), ported one to one: what
 * each toolbar action writes and where it leaves the caret. Golden vectors run the RN sources.
 */
object EditorFormats {
    const val BOLD = "**"
    const val ITALIC = "*"

    /** RN formats.ts "L": the table the format row inserts (through applyListFormat). */
    const val TABLE = "| Column1 | Column2 |\n| ------------ | ------------ |\n|     Text      |      Text      |"
    const val LINK_URL_PLACEHOLDER = "https://example.com"
    val LINK_TEXT_PLACEHOLDER: String get() = L.native.editor.linkTextPlaceholder
    const val UPLOAD_PLACEHOLDER_PREFIX = "Uploading... "

    private fun sub(text: String, start: Int, end: Int): String {
        // String.prototype.substring: clamped, swapped when reversed
        val a = start.coerceIn(0, text.length)
        val b = end.coerceIn(0, text.length)
        return if (a <= b) text.substring(a, b) else text.substring(b, a)
    }

    fun replaceBetween(text: String, selection: TextSelection, what: String): String =
        sub(text, 0, selection.start) + what + sub(text, selection.end, text.length)

    /** applyWrapFormat: bold and italic. */
    fun wrap(text: String, selection: TextSelection, wrapper: String): TextEdit {
        val newText = replaceBetween(text, selection, wrapper + sub(text, selection.start, selection.end) + wrapper)
        val pos = if (selection.isCollapsed) selection.end + wrapper.length else selection.end + wrapper.length * 2
        return TextEdit(newText, TextSelection(pos))
    }

    /** applyListFormat with [prefix] (RN only uses it for [TABLE]). */
    fun list(text: String, selection: TextSelection, prefix: String): TextEdit {
        val collapsed = selection.isCollapsed
        val breakOnStart = sub(text, selection.start - 1, selection.start) == "\n"
        val breakOnEnd = sub(text, selection.end - 1, selection.end) == "\n"
        val selected = sub(text, selection.start, selection.end)
        return when {
            !collapsed && breakOnStart -> TextEdit(replaceBetween(text, selection, "$prefix $selected\n"), TextSelection(selection.end + 3))
            !collapsed -> TextEdit(replaceBetween(text, selection, "\n$prefix $selected\n"), TextSelection(selection.end + 3))
            breakOnEnd -> TextEdit(replaceBetween(text, selection, "$prefix "), TextSelection(selection.start + 2))
            else -> TextEdit(replaceBetween(text, selection, "\n$prefix "), TextSelection(selection.start + 3))
        }
    }

    // Diego Perini's URL validator, as RN's webLinkValidator.ts. Its `i` flag is spelled out in the classes: JsRegex's
    // ignoreCase would also rewrite the hex letters of the \u escapes.
    private val WEB_LINK by lazy {
        JsRegex.compile(
            "^(?:(?:[hH][tT][tT][pP][sS]?|[fF][tT][pP])://)(?:\\S+(?::\\S*)?@)?(?:" +
                "(?!(?:10|127)(?:\\.\\d{1,3}){3})(?!(?:169\\.254|192\\.168)(?:\\.\\d{1,3}){2})(?!172\\.(?:1[6-9]|2\\d|3[0-1])(?:\\.\\d{1,3}){2})" +
                "(?:[1-9]\\d?|1\\d\\d|2[01]\\d|22[0-3])(?:\\.(?:1?\\d{1,2}|2[0-4]\\d|25[0-5])){2}(?:\\.(?:[1-9]\\d?|1\\d\\d|2[0-4]\\d|25[0-4]))" +
                "|(?:(?:[a-zA-Z\\u00a1-\\uffff0-9]-*)*[a-zA-Z\\u00a1-\\uffff0-9]+)(?:\\.(?:[a-zA-Z\\u00a1-\\uffff0-9]-*)*[a-zA-Z\\u00a1-\\uffff0-9]+)*(?:\\.(?:[a-zA-Z\\u00a1-\\uffff]{2,}))\\.?" +
                ")(?::\\d{2,5})?(?:[/?#]\\S*)?$",
        )
    }

    fun isWebLink(text: String): Boolean = text.length <= 2048 && WEB_LINK.containsMatchIn(text)

    /**
     * applyWebLinkFormat. The format row calls it without an item (placeholders around the selection); with
     * [itemUrl] set it inserts that link ([itemText] empty: the raw url).
     */
    fun webLink(
        text: String,
        selection: TextSelection,
        itemText: String? = null,
        itemUrl: String? = null,
        isImage: Boolean = false,
        isVideo: Boolean = false,
    ): TextEdit {
        val hasItem = itemUrl != null
        val prefix = if (isImage) "!" else ""
        val label = if (hasItem) itemText.orEmpty() else LINK_TEXT_PLACEHOLDER
        val url = itemUrl ?: LINK_URL_PLACEHOLDER
        val isRawUrl = hasItem && itemText.isNullOrEmpty()
        val selected = sub(text, selection.start, selection.end)
        if (!selection.isCollapsed) {
            val newText = if (isWebLink(selected)) {
                replaceBetween(text, selection, "$prefix[$label]($selected)")
            } else {
                replaceBetween(text, selection, "$prefix[$selected]($url)")
            }
            return TextEdit(newText, TextSelection(newText.length))
        }
        val insert = when {
            isVideo -> "\n$url\n"
            isRawUrl -> url
            isImage -> "\n$prefix[$label]($url)\n"
            else -> "$prefix[$label]($url)"
        }
        val newText = replaceBetween(text, selection, insert)
        val pos = if (isImage) newText.indexOf(url) + 2 + url.length else newText.length
        return TextEdit(newText, TextSelection(pos))
    }

    /** applySnippet: [snippet] over the selection, left selected. */
    fun snippet(text: String, selection: TextSelection, snippet: String): TextEdit =
        TextEdit(replaceBetween(text, selection, snippet), TextSelection(selection.start, selection.start + snippet.length))

    /** RN insertLinkModal's link types ("Plain", "Video", "Image"). */
    enum class LinkType { PLAIN, VIDEO, IMAGE }

    /**
     * What RN's insert link sheet hands to [snippet]: applyWebLinkFormat over an empty text. An image takes the
     * url's last path part as its label, a video is the raw url on its own line.
     */
    fun linkSnippet(label: String, url: String, type: LinkType): String = webLink(
        text = "",
        selection = TextSelection(0),
        itemText = when (type) {
            LinkType.IMAGE -> url.substringAfterLast('/')
            LinkType.VIDEO -> ""
            LinkType.PLAIN -> label
        },
        itemUrl = url,
        isImage = type == LinkType.IMAGE,
        isVideo = type == LinkType.VIDEO,
    ).text

    private fun isEnd(c: Char) = c == ',' || com.ecency.core.render.js.Js.isSpace(c)

    /** utils/editor.ts extractWordAtIndex: the word around [index], cut at whitespace and commas. */
    fun wordAt(text: String, index: Int): String {
        val range = 50
        val length = text.length
        val chunk = sub(text, maxOf(index - range, 0), minOf(index + range, length))
        val at = if (index < 50) index else if (length - index < 50) chunk.length - (length - index) else range
        val word = StringBuilder()
        var i = at
        while (i >= 0 && (i >= chunk.length || !isEnd(chunk[i]) || i == at)) {
            if (i < chunk.length) word.append(chunk[i])
            i--
        }
        word.reverse()
        if (at < chunk.length && !isEnd(chunk[at])) {
            var j = at + 1
            while (j < chunk.length && !isEnd(chunk[j])) {
                word.append(chunk[j])
                j++
            }
        } else if (at >= chunk.length) {
            // END_REGEX.test(undefined) is false in JS ("undefined" has no separator): the forward loop runs, over nothing
        }
        return word.toString()
    }

    /** applyUsername: the word at the caret becomes "@[username] ". */
    fun username(text: String, selection: TextSelection, username: String): TextEdit {
        val word = wordAt(text, selection.start)
        val from = selection.start - word.length
        // String.prototype.indexOf clamps a negative start to 0
        val insertAt = text.indexOf(word, from.coerceAtLeast(0))
        val newText = replaceBetween(text, TextSelection(insertAt, insertAt + word.length), "@$username ")
        return TextEdit(newText, TextSelection(insertAt + username.length + 2))
    }

    // ---- upload placeholders (uploadsGalleryModal/uploadPlaceholder.ts) ----

    private data class Span(val start: Int, val end: Int)

    /** Matches of /!\[[^\]\n]*\]\(Uploading\.\.\. (?:[^()\n]|\([^()\n]*\))*\)/g, by hand (no backtracking surprises). */
    private fun placeholders(text: String): List<Span> {
        val out = mutableListOf<Span>()
        var i = 0
        while (true) {
            val start = text.indexOf("![", i)
            if (start < 0) break
            val end = matchPlaceholder(text, start)
            if (end < 0) {
                i = start + 1
            } else {
                out += Span(start, end)
                i = end
            }
        }
        return out
    }

    private fun matchPlaceholder(text: String, start: Int): Int {
        var p = start + 2
        while (p < text.length && text[p] != ']' && text[p] != '\n') p++
        if (p >= text.length || text[p] != ']') return -1
        val head = "](Uploading... "
        if (!text.startsWith(head, p)) return -1
        p += head.length
        // (?:[^()\n]|\([^()\n]*\))* then ")": greedy, and no step can end differently, so no backtracking is needed
        while (p < text.length) {
            val c = text[p]
            when {
                c == ')' -> return p + 1
                c == '\n' -> return -1
                c == '(' -> {
                    var q = p + 1
                    while (q < text.length && text[q] != '(' && text[q] != ')' && text[q] != '\n') q++
                    if (q >= text.length || text[q] != ')') return -1
                    p = q + 1
                }
                else -> p++
            }
        }
        return -1
    }

    /** extractUploadPlaceholderNames. */
    fun placeholderNames(text: String): List<String> {
        if (!text.contains("(Uploading...")) return emptyList()
        return placeholders(text).mapNotNull { span ->
            val match = text.substring(span.start, span.end)
            val open = match.indexOf("](") + 2
            match.substring(open, match.length - 1).substring(UPLOAD_PLACEHOLDER_PREFIX.length).takeIf { it.isNotEmpty() }
        }
    }

    /** A removal that absorbs one adjacent line break, as the insert wrote "\n![](...)\n". */
    private fun absorb(text: String, index: Int, length: Int): Span {
        var start = index
        var end = index + length
        if (end < text.length && text[end] == '\n') {
            end += 1
        } else if (end + 1 < text.length && text[end] == '\r' && text[end + 1] == '\n') {
            end += 2
        } else if (start > 0 && text[start - 1] == '\n') {
            start -= 1
            if (start > 0 && text[start - 1] == '\r') start -= 1
        }
        return Span(start, end)
    }

    /**
     * sweepUploadingPlaceholders: placeholders left in a body being loaded are dead (no upload is running for
     * it), so they go; [caret] moves with the removals. Returns the text and the caret (null when none was given).
     */
    fun sweepPlaceholders(text: String, caret: Int? = null): Pair<String, Int?> {
        if (text.isEmpty() || !text.contains("(Uploading...")) return text to caret
        val removals = mutableListOf<Span>()
        for (m in placeholders(text)) {
            var span = absorb(text, m.start, m.end - m.start)
            val prev = removals.lastOrNull()
            if (prev != null && span.start < prev.end) span = Span(m.start, span.end)
            removals += span
        }
        if (removals.isEmpty()) return text to caret
        val swept = StringBuilder()
        var cursor = 0
        for (r in removals) {
            swept.append(text, cursor, r.start)
            cursor = r.end
        }
        swept.append(text, cursor, text.length)
        val newCaret = caret?.let { c ->
            var shift = 0
            for (r in removals) {
                if (c >= r.end) shift += r.end - r.start else if (c > r.start) shift += c - r.start
            }
            c - shift
        }
        return swept.toString() to newCaret
    }

    /**
     * applyMediaLink: placeholders for uploads that started, urls for those that finished, nothing left of
     * those that failed. [otherPending] names uploads of other batches that still own a placeholder. Null
     * when nothing changed (the body is not rewritten then).
     */
    fun media(text: String, selection: TextSelection, items: List<MediaInsert>, otherPending: List<String> = emptyList()): TextEdit? {
        var newText = text
        var sel = selection

        fun mapThroughEdit(editStart: Int, editEnd: Int, newLength: Int) {
            val diff = newLength - (editEnd - editStart)
            fun map(pos: Int) = when {
                pos <= editStart -> pos
                pos >= editEnd -> maxOf(0, pos + diff)
                else -> editStart
            }
            sel = TextSelection(map(sel.start), map(sel.end))
        }

        fun insert(alt: String, value: String, video: Boolean) {
            val formatted = if (video) "\n$value\n" else "\n![$alt]($value)\n"
            newText = replaceBetween(newText, sel, formatted)
            val offset = if (video) 1 else 2
            val index = newText.indexOf(value, sel.start.coerceAtLeast(0)) + value.length + offset
            sel = TextSelection(index)
        }

        fun hasRival(filename: String?) =
            items.any { !it.filename.isNullOrEmpty() && it.filename != filename } || otherPending.any { it != filename }

        fun lonePlaceholder(filename: String?): Span? {
            if (hasRival(filename)) return null
            return placeholders(newText).singleOrNull()
        }

        fun removeAt(index: Int, length: Int) {
            val span = absorb(newText, index, length)
            newText = newText.substring(0, span.start) + newText.substring(span.end)
            mapThroughEdit(span.start, span.end, 0)
        }

        for (item in items) {
            val placeholder = item.filename?.takeIf { it.isNotEmpty() }?.let { UPLOAD_PLACEHOLDER_PREFIX + it }
            when (item.status) {
                MediaInsertStatus.UPLOADING -> if (placeholder != null) insert(item.text, placeholder, false)
                MediaInsertStatus.READY -> if (placeholder != null) {
                    val target = "($placeholder)"
                    val at = newText.indexOf(target)
                    if (at >= 0) {
                        newText = newText.substring(0, at) + "(${item.url})" + newText.substring(at + target.length)
                        mapThroughEdit(at, at + target.length, item.url.length + 2)
                    } else if (item.url.isNotEmpty()) {
                        lonePlaceholder(item.filename)?.let { m ->
                            val match = newText.substring(m.start, m.end)
                            val paren = match.indexOf("](") + 1
                            val start = m.start + paren
                            val oldLength = match.length - paren
                            newText = newText.substring(0, start) + "(${item.url})" + newText.substring(start + oldLength)
                            mapThroughEdit(start, start + oldLength, item.url.length + 2)
                        }
                    }
                } else if (item.url.isNotEmpty()) {
                    insert(item.text, item.url, item.isVideo)
                }
                MediaInsertStatus.FAILED -> if (placeholder != null) {
                    val formatted = "![]($placeholder)"
                    val at = newText.indexOf(formatted)
                    if (at >= 0) {
                        removeAt(at, formatted.length)
                    } else {
                        lonePlaceholder(item.filename)?.let { removeAt(it.start, it.end - it.start) }
                    }
                }
            }
        }
        if (newText == text && sel == selection) return null
        return TextEdit(newText, sel.clamped(newText.length))
    }
}

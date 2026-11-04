package com.ecency.core.editor

import com.ecency.core.render.js.Js

/**
 * RN `utils/emojiStrip.ts`: emoji are taken out of a text before it goes to the translation service (they skew
 * its language detection and come back mangled) and are put back after the translated text. The matcher is
 * RN's regular expression over UTF-16 units, written by hand because the platforms' regex engines read
 * surrogate pairs differently.
 */
object EmojiStrip {
    class Stripped(val clean: String, val emojis: String)

    private fun isHigh(c: Char) = c in '\uD83C'..'\uD83F'

    private fun isLow(c: Char) = c in '\uDC00'..'\uDFFF'

    /** `[\uD83C-\uD83F][\uDC00-\uDFFF](?:\uD83C[\uDFFB-\uDFFF])?️?` at [i]; 0 when it does not match. */
    private fun pictograph(s: String, i: Int): Int {
        if (i + 1 >= s.length || !isHigh(s[i]) || !isLow(s[i + 1])) return 0
        var n = 2
        if (i + n + 1 < s.length && s[i + n] == '\uD83C' && s[i + n + 1] in '\uDFFB'..'\uDFFF') n += 2
        if (i + n < s.length && s[i + n] == '️') n++
        return n
    }

    /** Length of the emoji at [i], 0 when there is none (the alternatives in RN's order). */
    internal fun matchAt(s: String, i: Int): Int {
        var n = pictograph(s, i)
        if (n > 0) {
            while (i + n < s.length && s[i + n] == '‍') {
                val next = pictograph(s, i + n + 1)
                if (next == 0) break
                n += 1 + next
            }
            return n
        }
        val c = s[i]
        if (c in '0'..'9' || c == '#' || c == '*') {
            var k = i + 1
            if (k < s.length && s[k] == '️') k++
            if (k < s.length && s[k] == '⃣') return k + 1 - i
        }
        if (i + 1 < s.length && s[i + 1] == '️') return 2
        if (c in '☀'..'➿' || c in '⌀'..'⏿' || c in '⬀'..'⯿') return 1
        return 0
    }

    fun strip(text: String): Stripped {
        val emojis = StringBuilder()
        val stripped = StringBuilder()
        var i = 0
        while (i < text.length) {
            val n = matchAt(text, i)
            if (n > 0) {
                emojis.append(text, i, i + n)
                stripped.append(' ')
                i += n
            } else {
                stripped.append(text[i])
                i++
            }
        }
        if (emojis.isEmpty()) return Stripped(text, "")
        // .replace(/[^\S\n]+/g, ' ').replace(/ ?\n ?/g, '\n').trim()
        val spaced = StringBuilder()
        var inSpace = false
        for (c in stripped) {
            if (c != '\n' && Js.isSpace(c)) {
                if (!inSpace) spaced.append(' ')
                inSpace = true
            } else {
                spaced.append(c)
                inSpace = false
            }
        }
        val lines = StringBuilder()
        var k = 0
        while (k < spaced.length) {
            val c = spaced[k]
            if (c == ' ' && k + 1 < spaced.length && spaced[k + 1] == '\n') {
                k++
                continue
            }
            lines.append(c)
            if (c == '\n' && k + 1 < spaced.length && spaced[k + 1] == ' ') k++
            k++
        }
        return Stripped(Js.trim(lines.toString()), emojis.toString())
    }

    /** RN getTranslation around one request: [translate] gets the text without emoji; they follow the answer. */
    suspend fun translated(text: String, translate: suspend (String) -> String): String {
        val s = strip(text)
        // emoji only, or empty: nothing to translate
        if (s.clean.isEmpty()) return text
        val answer = translate(s.clean)
        return if (s.emojis.isNotEmpty()) Js.trim("$answer ${s.emojis}") else answer
    }
}

/**
 * RN `providers/translation/translateMarkdown.ts`: a markdown document is translated block by block, so that
 * only prose reaches the translation service. Code fences, raw HTML, tables, horizontal rules, image-only and
 * URL-only blocks pass through untouched; heading, quote and list markers are cut off before and put back
 * after. Requests run one after another and any failure fails the whole call: a half translated document is
 * never returned.
 */
object TranslateMarkdown {
    /** RN MAX_BLOCK_BATCH_CHARS. */
    const val MAX_BATCH_CHARS = 1500

    class Cancelled : Exception("translate-cancelled")

    private fun skipSpaces(s: String, from: Int = 0): Int {
        var i = from
        while (i < s.length && Js.isSpace(s[i])) i++
        return i
    }

    /** `/^\s*(`{3,}|~{3,})/`: the fence run, or null. */
    internal fun fence(line: String): String? {
        val start = skipSpaces(line)
        if (start >= line.length) return null
        val c = line[start]
        if (c != '`' && c != '~') return null
        var end = start
        while (end < line.length && line[end] == c) end++
        return if (end - start >= 3) line.substring(start, end) else null
    }

    /** `/^\s*!\[[^\]]*\]\([^)]*\)\s*$/` */
    internal fun isImageOnly(line: String): Boolean {
        var i = skipSpaces(line)
        if (!line.startsWith("![", i)) return false
        i = line.indexOf(']', i + 2)
        if (i < 0 || i + 1 >= line.length || line[i + 1] != '(') return false
        i = line.indexOf(')', i + 2)
        if (i < 0) return false
        return skipSpaces(line, i + 1) == line.length
    }

    /** `/^\s*([-*_][^\S\n]*){3,}$/` */
    internal fun isHorizontalRule(line: String): Boolean {
        var i = skipSpaces(line)
        var marks = 0
        var afterMark = false
        while (i < line.length) {
            val c = line[i]
            when {
                c == '-' || c == '*' || c == '_' -> {
                    marks++
                    afterMark = true
                }
                c != '\n' && Js.isSpace(c) && afterMark -> {}
                else -> return false
            }
            i++
        }
        return marks >= 3
    }

    /** `/^\s*https?:\/\/\S+\s*$/` */
    internal fun isUrlOnly(line: String): Boolean {
        val t = Js.trim(line)
        val rest = when {
            t.startsWith("https://") -> t.substring(8)
            t.startsWith("http://") -> t.substring(7)
            else -> return false
        }
        return rest.isNotEmpty() && rest.none { Js.isSpace(it) }
    }

    /** `/^\s*[|:\s-]+\s*$/` */
    internal fun isTableSeparator(line: String): Boolean =
        line.isNotEmpty() && line.all { it == '|' || it == ':' || it == '-' || Js.isSpace(it) }

    /** `/^(\s*(?:(?:#{1,6}|>|[-*+]|\d+\.)\s+)+)/`: the length of the marker prefix, 0 when there is none. */
    internal fun markerPrefix(line: String): Int {
        var i = skipSpaces(line)
        var end = 0
        while (i < line.length) {
            val c = line[i]
            var k = i
            when {
                c == '#' -> {
                    while (k < line.length && line[k] == '#') k++
                    if (k - i > 6) return end
                }
                c == '>' || c == '-' || c == '*' || c == '+' -> k = i + 1
                c in '0'..'9' -> {
                    while (k < line.length && line[k] in '0'..'9') k++
                    if (k >= line.length || line[k] != '.') return end
                    k++
                }
                else -> return end
            }
            val after = skipSpaces(line, k)
            if (after == k) return end
            end = after
            i = after
        }
        return end
    }

    /** RN splitMarkdownBlocks: blocks between blank lines; a fenced region is one block, blank lines included. */
    internal fun splitBlocks(markdown: String): List<String> {
        val blocks = mutableListOf<String>()
        var current = mutableListOf<String>()
        var open: String? = null
        fun flush() {
            if (current.isNotEmpty()) {
                blocks += current.joinToString("\n")
                current = mutableListOf()
            }
        }
        for (line in markdown.split('\n')) {
            val f = fence(line)
            val o = open
            if (o != null) {
                current += line
                if (f != null && f[0] == o[0] && f.length >= o.length) {
                    open = null
                    flush()
                }
            } else if (f != null) {
                flush()
                open = f
                current += line
            } else if (Js.trim(line).isEmpty()) {
                flush()
            } else {
                current += line
            }
        }
        flush()
        return blocks
    }

    private fun tableCells(line: String): Int {
        var t = Js.trim(line)
        if (t.startsWith("|")) t = t.substring(1)
        if (t.endsWith("|")) t = t.substring(0, t.length - 1)
        return t.split('|').size
    }

    internal fun isSkippable(block: String): Boolean {
        val lines = block.split('\n')
        // /^\s*</ and the fence test look at the block's start (a block never starts with a blank line)
        val first = skipSpaces(block)
        if (fence(lines[0]) != null || (first < block.length && block[first] == '<')) return true
        if (lines.all(::isHorizontalRule) || lines.all(::isImageOnly) || lines.all(::isUrlOnly)) return true
        for (i in 1 until lines.size) {
            val line = lines[i]
            if ('|' in line && '-' in line && isTableSeparator(line) && '|' in lines[i - 1] && tableCells(lines[i - 1]) == tableCells(line)) return true
        }
        return false
    }

    /**
     * RN chunkText: pieces of at most [max] characters split on whitespace; a single longer token is cut hard.
     * As in RN, text that ends in whitespace keeps one trailing space on its last piece.
     */
    internal fun chunk(text: String, max: Int): List<String> {
        if (text.length <= max) return listOf(text)
        val chunks = mutableListOf<String>()
        var buf = ""
        fun flush() {
            if (buf.isNotEmpty()) {
                chunks += buf
                buf = ""
            }
        }
        for (word in splitSpaces(text)) {
            if (word.length > max) {
                flush()
                var i = 0
                while (i < word.length) {
                    chunks += word.substring(i, minOf(word.length, i + max))
                    i += max
                }
            } else if (buf.isNotEmpty() && buf.length + 1 + word.length > max) {
                flush()
                buf = word
            } else {
                buf = if (buf.isNotEmpty()) "$buf $word" else word
            }
        }
        flush()
        return chunks.filter { it.isNotEmpty() }
    }

    /** JS `text.split(/\s+/)`: empty strings at the edges are kept. */
    private fun splitSpaces(text: String): List<String> {
        val out = mutableListOf<String>()
        var start = 0
        var i = 0
        while (i < text.length) {
            if (Js.isSpace(text[i])) {
                out += text.substring(start, i)
                i = skipSpaces(text, i)
                start = i
            } else {
                i++
            }
        }
        out += text.substring(start)
        return out
    }

    private class Request(val text: String, val apply: (String) -> Unit)

    /** What would be sent for [markdown], in order (for tests and the progress total). */
    internal fun requests(markdown: String): List<String> = plan(normalize(markdown)).second.map { it.text }

    private fun normalize(markdown: String): String = markdown.replace("\r\n", "\n").replace('\r', '\n')

    private fun plan(normalized: String): Pair<() -> String, List<Request>> {
        val blocks = splitBlocks(normalized)
        val output = arrayOfNulls<String>(blocks.size)
        blocks.forEachIndexed { i, b -> output[i] = b }
        val requests = mutableListOf<Request>()
        var batch = mutableListOf<Int>()
        var batchSize = 0
        fun flushBatch() {
            if (batch.isEmpty()) return
            val indices = batch
            requests += Request(indices.joinToString("\n\n") { blocks[it] }) { translated ->
                output[indices[0]] = translated
                indices.drop(1).forEach { output[it] = null }
            }
            batch = mutableListOf()
            batchSize = 0
        }
        blocks.forEachIndexed { index, block ->
            if (isSkippable(block)) {
                flushBatch()
                return@forEachIndexed
            }
            val lines = block.split('\n')
            if (lines.any { markerPrefix(it) > 0 }) {
                flushBatch()
                val translatedLines = lines.toMutableList()
                lines.forEachIndexed { lineIndex, line ->
                    // a continuation line without a marker keeps its indentation
                    val prefixLength = markerPrefix(line).takeIf { it > 0 } ?: skipSpaces(line)
                    val prefix = line.substring(0, prefixLength)
                    val rest = line.substring(prefixLength)
                    if (Js.trim(rest).isEmpty() || isImageOnly(rest) || isUrlOnly(rest)) return@forEachIndexed
                    val chunks = if (rest.length > MAX_BATCH_CHARS) chunk(rest, MAX_BATCH_CHARS) else listOf(rest)
                    val parts = MutableList(chunks.size) { "" }
                    chunks.forEachIndexed { chunkIndex, chunk ->
                        requests += Request(chunk) { translated ->
                            parts[chunkIndex] = translated
                            translatedLines[lineIndex] = prefix + parts.joinToString(" ")
                            output[index] = translatedLines.joinToString("\n")
                        }
                    }
                }
                return@forEachIndexed
            }
            if (block.length > MAX_BATCH_CHARS) {
                flushBatch()
                val chunks = chunk(block, MAX_BATCH_CHARS)
                val parts = MutableList(chunks.size) { "" }
                chunks.forEachIndexed { chunkIndex, chunk ->
                    requests += Request(chunk) { translated ->
                        parts[chunkIndex] = translated
                        output[index] = parts.joinToString(" ")
                    }
                }
                return@forEachIndexed
            }
            if (batch.isNotEmpty() && batchSize + 2 + block.length > MAX_BATCH_CHARS) flushBatch()
            batchSize += (if (batch.isNotEmpty()) 2 else 0) + block.length
            batch += index
        }
        flushBatch()
        return Pair({ if (requests.isEmpty()) blocks.joinToString("\n\n") else output.filterNotNull().joinToString("\n\n") }, requests)
    }

    /**
     * Translates [markdown]. [translate] is one request to the service (it throws on failure, which fails the
     * whole call); [isCancelled] is asked before every request and ends the run with [Cancelled].
     */
    suspend fun translate(
        markdown: String,
        translate: suspend (String) -> String,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): String {
        val normalized = normalize(markdown)
        if (Js.trim(normalized).isEmpty()) return normalized
        val (result, requests) = plan(normalized)
        if (requests.isEmpty()) return result()
        onProgress(0, requests.size)
        var done = 0
        for (request in requests) {
            if (isCancelled()) throw Cancelled()
            request.apply(EmojiStrip.translated(request.text, translate))
            done++
            onProgress(done, requests.size)
        }
        return result()
    }
}

package com.ecency.core.render.markdown

/** A run of chat text and the inline marks over it. */
data class InlineRun(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strike: Boolean = false,
    val code: Boolean = false,
)

/**
 * The inline marks of a chat message as web's `simpleMarkdownToHTML` (remarkable, default preset) reads them: bold,
 * italic, strikethrough and code spans, with remarkable's own delimiter rules (`snake_case` stays, `a*b*c` italicises,
 * a code span takes a run of backticks, a backslash escapes a marker). Nothing else is markdown here: block
 * constructs, links, HTML and entities stay as typed, line breaks too. Emphasis never runs across a blank line (on
 * web that is a new paragraph).
 */
object ChatInline {
    private val parser = InlineParser.chat()
    private val blankLines = Regex("\n(?:[ \t]*\n)+")

    fun runs(src: String): List<InlineRun> {
        if (src.isEmpty()) return emptyList()
        val out = ArrayList<InlineRun>()
        var last = 0
        for (sep in blankLines.findAll(src)) {
            paragraph(src.substring(last, sep.range.first), out)
            add(out, InlineRun(sep.value))
            last = sep.range.last + 1
        }
        paragraph(src.substring(last), out)
        return out
    }

    private fun paragraph(src: String, out: MutableList<InlineRun>) {
        if (src.isEmpty()) return
        val tokens = ArrayList<Token>()
        parser.parse(src, Env(), tokens)
        var bold = 0
        var italic = 0
        var strike = 0
        for (t in tokens) {
            when (t.type) {
                "strong_open" -> bold++
                "strong_close" -> bold--
                "em_open" -> italic++
                "em_close" -> italic--
                "del_open" -> strike++
                "del_close" -> strike--
                "text" -> add(out, InlineRun(t.content, bold > 0, italic > 0, strike > 0))
                "code" -> add(out, InlineRun(t.content, bold > 0, italic > 0, strike > 0, code = true))
                // a backslash before a line break (escape rule)
                "hardbreak", "softbreak" -> add(out, InlineRun("\n", bold > 0, italic > 0, strike > 0))
            }
        }
    }

    private fun add(out: MutableList<InlineRun>, run: InlineRun) {
        if (run.text.isEmpty()) return
        val prev = out.lastOrNull()
        if (prev != null && prev.copy(text = "") == run.copy(text = "")) out[out.size - 1] = prev.copy(text = prev.text + run.text)
        else out += run
    }
}

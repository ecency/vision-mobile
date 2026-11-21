package com.ecency.core.render.markdown

/**
 * A text token in the reading linkify gets (render-helper 2.5.55 `LinkifyView`): [text] with the app render's
 * placeholders decoded, [at] where each of its characters came from in the token as it is, [spanOf] the placeholder
 * each character was decoded from (an index into [spans], or -1), [spans] each decoded placeholder as
 * (decoded start, decoded end, source start, source end).
 */
internal class LinkifyView(val text: String, val at: IntArray, val spanOf: IntArray, val spans: List<IntArray>)

/**
 * render-helper 2.5.55 `withDecodedLinkify` (#2160): on the app render, text holds placeholders where the web holds
 * the decoded entity, so linkify ended a URL at one. Linkify reads each text token with its placeholders decoded,
 * exactly the text the web render hands it; its text pieces then get their placeholders back outside links, so the
 * text shows the entities as written. A link's href and text keep linkify's own (web) reading.
 */
internal object DecodedLinkify {
    fun parseTokens(blockTokens: List<Token>, view: (String) -> LinkifyView?) {
        val sources = HashMap<Token, Pair<String, LinkifyView>>()
        val before = LinkedHashMap<Token, List<Token>>()
        for (block in blockTokens) {
            if (block.type != "inline") continue
            var viewed = false
            for (token in block.children) {
                if (token.type != "text" || token.content.isEmpty()) continue
                val v = view(token.content) ?: continue
                sources[token] = token.content to v
                token.content = v.text
                viewed = true
            }
            if (viewed) before[block] = block.children.toList()
        }
        Linkify.parseTokens(blockTokens)
        for ((block, old) in before) {
            val now = block.children
            var k = 0
            for (token in old) {
                val pieces = ArrayList<Token>()
                if (k < now.size && now[k] === token) {
                    pieces.add(now[k++])
                } else {
                    // replaced by linkify: its pieces, up to its own text's length
                    val length = token.content.length
                    var taken = 0
                    while (k < now.size && (taken < length || now[k].type == "link_close")) {
                        val piece = now[k++]
                        if (piece.type == "text") taken += piece.content.length
                        pieces.add(piece)
                    }
                }
                val (source, v) = sources[token] ?: continue
                var d = 0
                var inLink = false
                for (piece in pieces) {
                    if (piece.type == "link_open") inLink = true else if (piece.type == "link_close") inLink = false
                    if (piece.type != "text") continue
                    val length = piece.content.length
                    if (!inLink) piece.content = sourceSlice(v, source, d, d + length)
                    d += length
                }
            }
        }
    }

    /**
     * `sourceSlice`: `text[from, to)` in the token's own spelling. Characters outside a decoded placeholder map one to
     * one; a whole decoded placeholder is its placeholder; part of one is that part of the decoded text.
     */
    private fun sourceSlice(view: LinkifyView, source: String, from: Int, to: Int): String {
        val out = StringBuilder(to - from + 8)
        var d = from
        while (d < to) {
            val k = view.spanOf[d]
            if (k != -1) {
                val span = view.spans[k]
                if (d == span[0] && span[1] <= to) {
                    out.append(source, span[2], span[3])
                    d = span[1]
                } else {
                    val stop = minOf(span[1], to)
                    out.append(view.text, d, stop)
                    d = stop
                }
                continue
            }
            var n = d
            while (n < to && view.spanOf[n] == -1) n++
            out.append(source, view.at[d], view.at[d] + (n - d))
            d = n
        }
        return out.toString()
    }
}

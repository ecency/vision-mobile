package com.ecency.core.render.markdown

import com.ecency.core.render.js.Js
/** remarkable/linkify: the core rule that turns bare URLs and emails in text tokens into links. */
internal object Linkify {
    /** LINK_SCAN_RE `/www|@|\:\/\//`. */
    private fun mayContainLink(s: String) = s.contains("www") || s.indexOf('@') >= 0 || s.contains("://")

    private fun isAsciiA(c: Int) = c == 0x61 || c == 0x41

    /** `/^<a[>\s]/i`. */
    private fun isLinkOpen(str: String): Boolean {
        if (Js.cc(str, 0) != 0x3C || !isAsciiA(Js.cc(str, 1))) return false
        val c = Js.cc(str, 2)
        return c == 0x3E || (c >= 0 && Js.isSpace(c))
    }

    /** `/^<\/a\s*>/i`. */
    private fun isLinkClose(str: String): Boolean {
        if (!str.startsWith("</") || !isAsciiA(Js.cc(str, 2))) return false
        var i = 3
        while (i < str.length && Js.isSpace(str[i].code)) i++
        return Js.cc(str, i) == 0x3E
    }

    fun parseTokens(blockTokens: List<Token>) {
        for (blockToken in blockTokens) {
            if (blockToken.type != "inline") continue
            var tokens = blockToken.children
            var htmlLinkLevel = 0
            var i = tokens.size - 1
            while (i >= 0) {
                val token = tokens[i]
                if (token.type == "link_close") {
                    i--
                    // JS reads tokens[-1].level and throws if no link_open is found first.
                    while (tokens[i].level != token.level && tokens[i].type != "link_open") i--
                    i--
                    continue
                }
                if (token.type == "htmltag") {
                    if (isLinkOpen(token.content) && htmlLinkLevel > 0) htmlLinkLevel--
                    if (isLinkClose(token.content)) htmlLinkLevel++
                }
                if (htmlLinkLevel > 0) {
                    i--
                    continue
                }
                if (token.type == "text" && mayContainLink(token.content)) {
                    var text = token.content
                    val links = Autolinker.links(text)
                    if (links.isEmpty()) {
                        i--
                        continue
                    }
                    val nodes = ArrayList<Token>()
                    var level = token.level
                    // remarkable slices the rest of the text after every link, which V8 does without copying;
                    // copying it here made a paragraph of many links quadratic. [start] marks where the rest
                    // begins instead, and the copy is only made in the rare case the JS slice arithmetic needs it.
                    var start = 0
                    for (link in links) {
                        if (!Utils.validateLink(link.url)) continue
                        val found = text.indexOf(link.text, start)
                        val pos = if (found < 0) {
                            // not found: JS slices with -1 (all but the last character), on the rest itself
                            text = text.substring(start)
                            start = 0
                            -1
                        } else {
                            found
                        }
                        if (pos != start) nodes.add(Token("text", level, if (pos < 0) Js.slice(text, 0, pos) else text.substring(start, pos)))
                        nodes.add(Token("link_open", level++).also {
                            it.href = link.url
                            it.title = ""
                        })
                        nodes.add(Token("text", level, link.text))
                        nodes.add(Token("link_close", --level))
                        if (pos < 0) {
                            text = Js.slice(text, pos + link.text.length)
                            start = 0
                        } else {
                            start = pos + link.text.length
                        }
                    }
                    if (start < text.length) nodes.add(Token("text", level, text.substring(start)))
                    val replaced = ArrayList<Token>(tokens.size + nodes.size)
                    replaced.addAll(tokens.subList(0, i))
                    replaced.addAll(nodes)
                    replaced.addAll(tokens.subList(i + 1, tokens.size))
                    tokens = replaced
                    blockToken.children = tokens
                }
                i--
            }
        }
    }
}

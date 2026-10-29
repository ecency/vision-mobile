package com.ecency.core.render.blocks

import com.ecency.core.render.dom.CDATASection
import com.ecency.core.render.dom.Element
import com.ecency.core.render.dom.Node
import com.ecency.core.render.dom.RenderBodyParser
import com.ecency.core.render.dom.Text
import com.ecency.core.render.dom.XmlDomParser

/**
 * "No text lost" checks, compared as word sequences (case-insensitive, since chips show the
 * attribute value: `#Hive` reads as `#hive`). Joined or split words fail, as do lost or reordered
 * ones.
 *
 * - [wordDiff] compares with words from htmlparser2 (`tools/render/block-words.mjs`), an oracle
 *   independent of the Kotlin parser; used for the generated inputs and the corpus.
 * - [diff] derives the words from the Kotlin DOM with the same rules, for hand-written snippets.
 *
 * Rules shared with block-words.mjs: block tags break words, iframes and invisible tags have no
 * text, inline post links that become preview cards are left out.
 */
object TextFidelity {
    private val SKIP = BlockBuilder.SKIPPED + "iframe"

    /** JS `\s` plus zero width space. */
    fun isSeparator(c: Char): Boolean = when (c) {
        ' ', '\t', '\n', '\u000B', '\u000C', '\r', ' ', ' ', ' ', ' ', ' ', ' ', '　', '﻿', '​' -> true
        else -> c in ' '..' '
    }

    fun words(text: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (c in text) {
            if (isSeparator(c)) {
                if (sb.isNotEmpty()) out += sb.toString()
                sb.clear()
            } else {
                sb.append(c)
            }
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }

    fun blockWords(blocks: List<Block>): List<String> = words(BlockText.of(blocks))

    /** Iterative (deep markup must not overflow the stack). */
    fun htmlWords(html: String): List<String> {
        val doc = try {
            XmlDomParser.parseFromString(RenderBodyParser.wrap(html))
        } catch (e: Exception) {
            RenderBodyParser.parseRepaired(html).document
        } ?: return emptyList()
        val sb = StringBuilder()
        val stack = ArrayList<Any>()
        doc.documentElement?.let { stack += it }
        while (stack.isNotEmpty()) {
            val n = stack.removeAt(stack.size - 1)
            if (n is String) {
                sb.append(n)
                continue
            }
            when (n) {
                is Text -> sb.append(n.data)
                is CDATASection -> sb.append(n.data)
                is Element -> {
                    val tag = n.localName.lowercase()
                    val block = tag in BlockBuilder.BLOCK_TAGS || (tag == "a" && BlockBuilder.isVideoAnchor(n))
                    when {
                        tag in SKIP -> if (block) sb.append(' ')
                        tag == "a" && BlockBuilder.inlineCard(n) != null -> sb.append(' ')
                        tag == "pre" -> sb.append(' ').append(n.textContent).append(' ')
                        else -> {
                            if (block) {
                                sb.append(' ')
                                stack += " "
                            }
                            for (i in n.childNodes.indices.reversed()) stack += n.childNodes[i] as Node
                        }
                    }
                }
                else -> {}
            }
        }
        return words(sb.toString())
    }

    fun wordDiff(expected: List<String>, blocks: List<Block>): String? = compare(expected, blockWords(blocks))

    fun diff(html: String, blocks: List<Block>): String? = compare(htmlWords(html), blockWords(blocks))

    private fun compare(expected: List<String>, actual: List<String>): String? {
        val a = expected.map { it.lowercase() }
        val b = actual.map { it.lowercase() }
        if (a == b) return null
        var i = 0
        while (i < a.size && i < b.size && a[i] == b[i]) i++
        return "word $i of ${a.size}/${b.size}: expected ${a.subList(i, minOf(a.size, i + 6))} got ${b.subList(i, minOf(b.size, i + 6))}"
    }
}

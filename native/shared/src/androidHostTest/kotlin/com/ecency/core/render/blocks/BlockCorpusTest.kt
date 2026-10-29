package com.ecency.core.render.blocks

import com.ecency.core.render.dom.Element
import com.ecency.core.render.dom.Node
import com.ecency.core.render.dom.RenderBodyParser
import com.ecency.core.render.dom.XmlDomParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Runs [BlockBuilder] over the local Gate 1 corpus (`native/tools/render/corpus/expected.jsonl`:
 * render-helper HTML for real posts, gitignored, never committed). Skipped when the file is absent.
 * Reports crashes, text loss, tags and classes the builder has no rule for, and time per post;
 * the report also goes to `shared/build/reports/block-corpus.txt`.
 */
class BlockCorpusTest {
    private fun corpusFile(): File? {
        System.getProperty("blocks.corpus")?.let { return File(it).takeIf(File::isFile) }
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val f = File(dir, "tools/render/corpus/expected.jsonl")
            if (f.isFile) return f
            val g = File(dir, "native/tools/render/corpus/expected.jsonl")
            if (g.isFile) return g
            dir = dir.parentFile
        }
        return null
    }

    @Test
    fun corpus() {
        val file = corpusFile() ?: run {
            println("BlockCorpusTest: no corpus, skipped (node corpus.mjs fetch && node corpus.mjs render)")
            return
        }
        val posts = file.readLines().filter { it.isNotBlank() }.map {
            val o = Json.parseToJsonElement(it).jsonObject
            o["key"]!!.jsonPrimitive.content to o["html"]!!.jsonPrimitive.content
        }
        // Expected words from htmlparser2 (node block-words.mjs corpus); without them the Kotlin-side oracle is used.
        val wordsFile = File(file.parentFile, "words.jsonl")
        val expectedWords: Map<String, List<String>>? = if (wordsFile.isFile) {
            wordsFile.readLines().filter { it.isNotBlank() }.associate { line ->
                val o = Json.parseToJsonElement(line).jsonObject
                o["key"]!!.jsonPrimitive.content to o["words"]!!.jsonArray.map { it.jsonPrimitive.content }
            }
        } else {
            null
        }
        // Warm up the JIT so timings show steady state.
        posts.take(50).forEach { BlockBuilder.build(it.second) }

        // Thread CPU time: wall time is meaningless while other builds load the machine.
        val cpu = java.lang.management.ManagementFactory.getThreadMXBean()
        val crashes = ArrayList<String>()
        val loss = ArrayList<String>()
        val times = ArrayList<Long>()
        val tags = HashMap<String, Int>()
        val classes = HashMap<String, Int>()
        val kinds = HashMap<String, Int>()
        var repaired = 0
        var topLevel = 0
        var topContainers = 0
        var topContainerChildren = 0
        for ((key, html) in posts) {
            if (runCatching { XmlDomParser.parseFromString(RenderBodyParser.wrap(html)) }.isFailure) repaired++
            val t0 = cpu.currentThreadCpuTime
            val blocks = try {
                BlockBuilder.build(html)
            } catch (e: Throwable) {
                crashes += "$key: $e"
                continue
            }
            times += cpu.currentThreadCpuTime - t0
            val expected = expectedWords?.get(key)
            (if (expected != null) TextFidelity.wordDiff(expected, blocks) else TextFidelity.diff(html, blocks))?.let { loss += "$key: $it" }
            countKinds(blocks, kinds)
            topLevel += blocks.size
            blocks.filterIsInstance<Block.Container>().forEach { topContainers++; topContainerChildren += it.blocks.size }
            survey(html, tags, classes)
        }
        times.sort()
        fun ms(q: Double) = if (times.isEmpty()) 0.0 else times[minOf(times.size - 1, (q * times.size).toInt())] / 1e6
        val unknownTags = tags.filterKeys { it !in KNOWN_TAGS }
        val unknownClasses = classes.filterKeys { it !in KNOWN_CLASSES }
        val report = buildString {
            appendLine("BlockCorpusTest: ${posts.size} posts from ${file.path}")
            appendLine("final HTML rejected by xmldom (built from the repair path): $repaired")
            appendLine("text oracle: " + if (expectedWords != null) "htmlparser2 words (${wordsFile.name})" else "Kotlin DOM words (run node block-words.mjs corpus for the independent oracle)")
            appendLine("crashes: ${crashes.size}")
            crashes.take(20).forEach { appendLine("  $it") }
            appendLine("text loss: ${loss.size}")
            loss.take(20).forEach { appendLine("  $it") }
            appendLine("CPU time per post ms: p50=${"%.3f".format(ms(0.5))} p95=${"%.3f".format(ms(0.95))} max=${"%.3f".format(ms(1.0))}")
            appendLine("top-level blocks: $topLevel, of which containers: $topContainers holding $topContainerChildren blocks")
            appendLine("block kinds (all depths): " + kinds.entries.sortedByDescending { it.value }.joinToString { "${it.key}=${it.value}" })
            appendLine("tags without a rule (children only): " + unknownTags.entries.sortedByDescending { it.value }.joinToString { "${it.key}=${it.value}" })
            appendLine("classes without a rule: " + unknownClasses.entries.sortedByDescending { it.value }.joinToString { "${it.key}=${it.value}" })
        }
        println(report)
        File("build/reports").apply { mkdirs() }.resolve("block-corpus.txt").writeText(report)
        assertEquals(emptyList(), crashes)
        assertEquals(emptyList(), loss)
    }

    private fun countKinds(blocks: List<Block>, into: MutableMap<String, Int>) {
        for (b in blocks) {
            into.merge(b::class.simpleName!!, 1, Int::plus)
            when (b) {
                is Block.Container -> countKinds(b.blocks, into)
                is Block.Quote -> countKinds(b.blocks, into)
                is Block.ListBlock -> b.items.forEach { countKinds(it, into) }
                is Block.Table -> b.rows.forEach { r -> r.cells.forEach { countKinds(it.blocks, into) } }
                is Block.Details -> countKinds(b.blocks, into)
                else -> {}
            }
        }
    }

    private fun survey(html: String, tags: MutableMap<String, Int>, classes: MutableMap<String, Int>) {
        val doc = try {
            XmlDomParser.parseFromString(RenderBodyParser.wrap(html))
        } catch (e: Exception) {
            RenderBodyParser.parseRepaired(html).document
        } ?: return
        fun walk(n: Node) {
            if (n is Element) {
                val tag = n.localName.lowercase()
                tags.merge(tag, 1, Int::plus)
                n.getAttribute("class")?.split(' ')?.filter { it.isNotEmpty() }?.forEach { classes.merge("$tag.$it", 1, Int::plus) }
            }
            n.childNodes.forEach(::walk)
        }
        walk(doc)
    }

    private companion object {
        val KNOWN_TAGS = BlockBuilder.MARKS.keys + BlockBuilder.PLAIN_BLOCKS + BlockBuilder.SKIPPED + setOf(
            "br", "img", "a", "h1", "h2", "h3", "h4", "h5", "h6", "center", "div", "blockquote", "ul", "ol", "li", "pre", "hr",
            "table", "thead", "tbody", "tfoot", "tr", "td", "th", "caption", "details", "iframe", "video",
            // Flattened on purpose, as the RN app strips spans.
            "span",
        )
        val KNOWN_CLASSES = setOf(
            "a.markdown-author-link", "a.markdown-external-link", "a.markdown-post-link", "a.markdown-tag-link", "a.markdown-community-link",
            "a.markdown-video-link", "a.markdown-video-link-youtube", "a.markdown-witnesses-link", "a.markdown-proposal-link",
            "img.video-thumbnail", "div.text-justify", "div.text-center", "div.text-right", "div.phishy",
            // Deliberately ignored: layout hints the RN app does not honour, and render-helper markers with no visual meaning.
            "div.pull-left", "div.pull-right", "img.markdown-img-link", "img.no-replace", "span.markdown-video-play",
            "a.markdown-video-link-speak", "a.markdown-video-link-vimeo", "a.markdown-video-link-youtube-portrait", "a.markdown-profile-link",
            "body.root",
        )
    }
}

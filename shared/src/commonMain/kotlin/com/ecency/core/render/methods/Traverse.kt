package com.ecency.core.render.methods

import com.ecency.core.render.js.Js
import com.ecency.core.render.dom.Element
import com.ecency.core.render.dom.Node
import com.ecency.core.render.dom.Text
import com.ecency.core.render.dom.XmlDomParser

/** render-helper `methods/text.method.ts`. */
internal object TextMethod {
    private val SKIP = setOf("a", "code", "pre")
    private val CHIP_CLASSES = setOf("er-author-link", "er-tag-link")

    /** Whether [node]'s own class list holds a chip class (exact tokens). */
    fun isChip(node: Node): Boolean {
        val cls = (node as? Element)?.getAttribute("class")
        return !cls.isNullOrEmpty() && splitWs(cls).any { it in CHIP_CLASSES }
    }

    /** Whether [node] is one of the ancestors that stop linkification (a, code, pre). */
    fun isSkipAncestor(node: Node): Boolean = Js.toLowerCase(node.nodeName) in SKIP

    private fun splitWs(s: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        val sb = StringBuilder()
        while (i < s.length) {
            if (Js.isSpace(s[i])) {
                out.add(sb.toString())
                sb.setLength(0)
                while (i < s.length && Js.isSpace(s[i])) i++
            } else {
                sb.append(s[i++])
            }
        }
        out.add(sb.toString())
        return out
    }

    /**
     * [inSkip] and [inChip] are hasAncestor(node, ['a','code','pre']) and hasChipAncestor(node),
     * tracked by [Traverse] while descending instead of walking every ancestor per text node
     * (which was quadratic in the nesting depth).
     */
    fun text(node: Text, ctx: RenderContext, inSkip: Boolean, inChip: Boolean) {
        val parent = node.parentNode ?: return
        if (inSkip) return
        if (ctx.options.inertAuthorAndTagChips && inChip) return

        val nodeValue = node.data
        val linkified = Linkify.linkify(nodeValue, ctx)
        if (linkified != nodeValue) {
            val doc = XmlDomParser.parseFromString("<span class=\"wr\">$linkified</span>")
            val replaceNode = doc.firstChild
            if (replaceNode != null) {
                parent.insertBefore(replaceNode, node)
                node.parentNode!!.removeChild(node)
            }
            return
        }

        if (Scanners.imgTest(nodeValue)) {
            val imageHTML = ImgMethod.createImageHTML(nodeValue, false, ctx)
            val replaceNode = XmlDomParser.parseFromString(imageHTML).firstChild
            if (replaceNode != null) parent.replaceChild(replaceNode, node)
            return
        }

        val yt = Scanners.youtubeFind(nodeValue)
        if (yt != null) {
            val vid = yt.id
            val doc = node.ownerDocument!!
            val thumbnail = ctx.proxy.proxify("https://img.youtube.com/vi/${vid.substringBefore('?')}/hqdefault.jpg")
            val startTime = Helper.extractYtStartTime(nodeValue)
            val container = doc.createElement("p")
            val anchor = doc.createElement("a")
            anchor.setAttribute("class", Helper.youtubeVideoLinkClass(yt.matched))
            anchor.setAttribute("data-embed-src", "https://www.youtube.com/embed/$vid?autoplay=1")
            anchor.setAttribute("data-youtube", vid)
            if (startTime.isNotEmpty()) anchor.setAttribute("data-start-time", startTime)
            val thumbImg = doc.createElement("img")
            thumbImg.setAttribute("class", "no-replace video-thumbnail")
            thumbImg.setAttribute("src", thumbnail)
            anchor.appendChild(thumbImg)
            val play = doc.createElement("span")
            play.setAttribute("class", "markdown-video-play")
            anchor.appendChild(play)
            container.appendChild(anchor)
            parent.replaceChild(container, node)
            return
        }

        if (nodeValue.isNotEmpty()) {
            val postMatch = Consts.POST_REGEX.find(Js.trim(nodeValue)) ?: return
            if (!postMatchWhitelisted(postMatch.g(1)!!)) return
            val tag = postMatch.g(2)!!
            val author = Js.replaceFirst(postMatch.g(3)!!, "@", "")
            val permlink = Helper.sanitizePermlink(postMatch.g(4))
            if (tag.isEmpty() || !tag.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' }) return
            if (!Helper.isValidUsername(author)) return
            if (!Helper.isValidPermlink(permlink)) return
            val attrs = if (ctx.forApp) {
                "data-tag=\"$tag\" data-author=\"$author\" data-permlink=\"$permlink\" class=\"markdown-post-link\""
            } else {
                "class=\"markdown-post-link\" href=\"/@$author/$permlink\""
            }
            val replaceNode = XmlDomParser.parseFromString("<a $attrs>/@$author/$permlink</a>").firstChild
            if (replaceNode != null) parent.replaceChild(replaceNode, node)
        }
    }

    private fun postMatchWhitelisted(host: String) = (if (host.startsWith("www.")) host.substring(4) else host) in Consts.WHITE_LIST
}

/**
 * render-helper `methods/traverse.method.ts` (and `p.method.ts`), iterative so deep posts cannot
 * overflow the stack.
 *
 * ROBUSTNESS DEVIATION: render-helper recurses once per nesting level, and V8 overflows its stack
 * a little above 5,000 levels (about 5,075 nested elements, about 5,450 levels of a `#a#a#a...`
 * tag chain, which nests one wrapper span per tag). The RangeError is caught by markdownToHTML,
 * which retries on the repaired body, overflows again and returns the sanitized markdown output.
 * [MAX_DEPTH] reproduces that: deeper than this the traversal throws and the same sanitize-only
 * fallback runs. Bodies nested between this limit and V8's actual one render sanitize-only here
 * but fully in Node (and Hermes has its own limit), which no real post comes near.
 */
internal object Traverse {
    const val MAX_DEPTH = 5000

    private class Frame(val node: Node, var child: Node?, val inSkip: Boolean, val inChip: Boolean)

    private fun frameFor(node: Node, parent: Frame?): Frame = Frame(
        node,
        node.firstChild,
        (parent?.inSkip ?: false) || TextMethod.isSkipAncestor(node),
        (parent?.inChip ?: false) || TextMethod.isChip(node),
    )

    fun traverse(root: Node, ctx: RenderContext, state: ImageState) {
        val stack = ArrayList<Frame>()
        stack.add(frameFor(root, null))
        while (stack.isNotEmpty()) {
            val frame = stack[stack.size - 1]
            val child = frame.child
            if (child == null) {
                stack.removeAt(stack.size - 1)
                continue
            }
            val node = frame.node
            val next = child.nextSibling
            val prev = child.previousSibling
            val name = Js.toLowerCase(child.nodeName)
            if (child is Element) {
                when (name) {
                    "a" -> AMethod.a(child, ctx)
                    "iframe" -> {
                        val originalSrc = child.getAttribute("src")
                        IframeMethod.iframe(child, ctx)
                        if (originalSrc != null) IframeMethod.enforceAllowList(child, originalSrc)
                    }
                    "img" -> ImgMethod.img(child, state, ctx)
                    "p" -> if (child.getAttribute("dir").isNullOrEmpty()) child.setAttribute("dir", "auto")
                }
            } else if (child is Text) {
                TextMethod.text(child, ctx, frame.inSkip, frame.inChip)
            } else if (name == "a" || name == "iframe" || name == "img" || name == "p") {
                // e.g. a processing instruction `<?a ?>`: the JS handlers call getAttribute on it,
                // which throws a TypeError, and markdownToHTML falls back.
                throw TransformError("${child.nodeName} is not an element")
            }
            frame.child = next
            val descend = if (child.parentNode != null) {
                child
            } else {
                val possible = if (next != null) next.previousSibling else node.lastChild
                if (possible != null && possible !== prev && possible.parentNode === node) possible else null
            }
            if (descend != null) {
                if (stack.size >= MAX_DEPTH) throw TransformError("nesting deeper than $MAX_DEPTH")
                stack.add(frameFor(descend, frame))
            }
        }
    }
}

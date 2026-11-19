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
        // 2.5.45 (#1919): the decoded text is parsed as HTML below, so its angle brackets are escaped first or the
        // author's text would become real markup with forged link markers. `&` stays.
        val escaped = nodeValue.replace("<", "&lt;").replace(">", "&gt;")
        val linkified = Linkify.linkify(escaped, ctx)
        if (linkified != escaped) {
            val doc = XmlDomParser.parseFromString("<span class=\"wr\">$linkified</span>")
            val replaceNode = doc.firstChild
            if (replaceNode != null) {
                parent.insertBefore(replaceNode, node)
                node.parentNode!!.removeChild(node)
            }
            return
        }

        // Image URLs were handled by linkify above (imageUrlEnd, 2.5.46): no whole-node image here.

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
            // 2.5.45: the text around the URL stays where it was. The match starts at `youtube.com/`; widen it to the
            // whole whitespace-delimited URL.
            var start = yt.start
            while (start > 0 && !Js.isSpace(nodeValue[start - 1])) start--
            var end = yt.end
            while (end < nodeValue.length && !Js.isSpace(nodeValue[end])) end++
            val before = nodeValue.substring(0, start)
            val after = nodeValue.substring(end)
            if (before.isNotEmpty()) parent.insertBefore(doc.createTextNode(before), node)
            if (after.isNotEmpty()) parent.insertBefore(doc.createTextNode(after), node.nextSibling)
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
 * render-helper `methods/traverse.method.ts` (and `p.method.ts`), iterative so deep posts cannot overflow the stack.
 *
 * 2.5.45 (#1920): an explicit nesting cap. Past [MAX_DEPTH] a subtree is reduced to its plain text instead of being
 * walked further, so the rest of the body is still transformed and nothing below the cap ships untransformed.
 */
internal object Traverse {
    const val MAX_DEPTH = 2000

    private class Frame(val node: Node, var child: Node?, val inSkip: Boolean, val inChip: Boolean, val depth: Int)

    private fun frameFor(node: Node, parent: Frame?): Frame = Frame(
        node,
        node.firstChild,
        (parent?.inSkip ?: false) || TextMethod.isSkipAncestor(node),
        (parent?.inChip ?: false) || TextMethod.isChip(node),
        (parent?.depth ?: -1) + 1,
    )

    /** `flattenToText`: an element's children replaced by its text (text and comment nodes are already inert). */
    private fun flattenToText(node: Node) {
        if (node !is Element) return
        val text = node.textContent.orEmpty()
        while (true) node.removeChild(node.firstChild ?: break)
        if (text.isNotEmpty()) node.appendChild(node.ownerDocument!!.createTextNode(text))
    }

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
            // script/style and malformed names never reach the output, so nothing to transform inside them
            if (child is Element && RenderBoundary.isHiddenElement(child)) {
                frame.child = next
                continue
            }
            val name = Js.toLowerCase(child.nodeName)
            if (child is Element) {
                when (name) {
                    "a" -> AMethod.a(child, ctx)
                    "iframe" -> IframeMethod.iframe(child, ctx)
                    "img" -> ImgMethod.img(child, state, ctx)
                    "p" -> if (child.getAttribute("dir").isNullOrEmpty()) child.setAttribute("dir", "auto")
                    "video" -> VideoMethod.video(child, ctx)
                }
            } else if (child is Text) {
                TextMethod.text(child, ctx, frame.inSkip, frame.inChip)
            } else if (name == "a" || name == "iframe" || name == "img" || name == "p" || name == "video") {
                // e.g. a processing instruction `<?a ?>`: the JS handlers call getAttribute on it,
                // which throws a TypeError, and markdownToHTML falls back.
                throw TransformError("${child.nodeName} is not an element")
            }
            frame.child = next
            val descend = if (child.parentNode != null) {
                child
            } else {
                val possible = if (next != null) next.previousSibling else node.lastChild
                // a refused iframe's placeholder is text the render wrote, left as written (#2095)
                val refusedPlaceholder = possible is Element && name == "iframe" && possible.getAttribute("class") == "unsupported-iframe"
                if (possible != null && !refusedPlaceholder && possible !== prev && possible.parentNode === node) possible else null
            }
            if (descend != null) {
                if (frame.depth + 1 > MAX_DEPTH) {
                    flattenToText(descend)
                } else {
                    stack.add(frameFor(descend, frame))
                }
            }
        }
    }
}

/** render-helper 2.5.45 `methods/video.method.ts`: an author's `<video poster>` loads through the image proxy (#1919). */
internal object VideoMethod {
    fun video(el: Element, ctx: RenderContext) {
        val poster = el.getAttribute("poster") ?: return
        // the parser already decoded the entities once; only percent-encoding is left
        val decoded = try {
            Js.trim(Js.decodeUriComponent(poster))
        } catch (e: com.ecency.core.render.js.UriError) {
            Js.trim(poster)
        }
        if (decoded.startsWith(Helper.trimTrailingSlash(ctx.proxy.base) + "/")) return
        val proxied = if (Js.regionMatchesLower(decoded, 0, "http://") || Js.regionMatchesLower(decoded, 0, "https://")) {
            ctx.proxy.proxify(decoded, forceProxy = true)
        } else {
            ""
        }
        if (proxied.isNotEmpty()) el.setAttribute("poster", proxied) else el.removeAttribute("poster")
    }
}

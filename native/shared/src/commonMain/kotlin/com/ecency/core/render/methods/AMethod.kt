package com.ecency.core.render.methods

import com.ecency.core.render.js.Js
import com.ecency.core.render.js.UriError
import com.ecency.core.render.SeoContext
import com.ecency.core.render.dom.Element
import com.ecency.core.render.dom.XmlDomParser

/** render-helper `methods/a.method.ts`: link classification and rewriting. */
internal object AMethod {
    private fun externalLinkRel(seo: SeoContext?): String {
        val rep = seo?.authorReputation
        val pay = seo?.postPayout
        return if (rep != null && pay != null && rep >= 40 && pay > 5) "noopener" else "nofollow ugc noopener"
    }

    private fun renderPlainVideoLink(el: Element, provider: String, embedSrc: String, ctx: RenderContext) {
        val baseClass = "markdown-video-link markdown-video-link-$provider"
        el.setAttribute("class", baseClass)
        el.removeAttribute("href")
        el.textContent = ""
        el.setAttribute("data-embed-src", embedSrc)
        val doc = el.ownerDocument!!
        if (ctx.options.embedVideosDirectly) {
            val wrapper = doc.createElement("span")
            wrapper.setAttribute("class", "er-embed-frame")
            wrapper.setAttribute("style", "display:block")
            val frame = doc.createElement("iframe")
            frame.setAttribute("src", embedSrc)
            frame.setAttribute("title", "Video player")
            frame.setAttribute("allow", "accelerometer; encrypted-media; gyroscope; picture-in-picture; web-share")
            frame.setAttribute("allowfullscreen", "")
            wrapper.appendChild(frame)
            el.appendChild(wrapper)
            el.setAttribute("class", "$baseClass er-embed")
            return
        }
        val play = doc.createElement("span")
        play.setAttribute("class", "markdown-video-play")
        el.appendChild(play)
    }

    private fun normalizeValue(v: String?): String = if (v.isNullOrEmpty()) "" else Js.trim(v)

    private fun matchesHref(href: String, value: String?): Boolean {
        val h = normalizeValue(href)
        if (h.isEmpty()) return false
        return normalizeValue(value) == h
    }

    private fun normalizeDisplayText(text: String): String {
        var s = Js.trim(text)
        Consts.NORMALIZE_DISPLAY_HOST.find(s)?.let { s = s.substring(it.range.last + 1) }
        var k = 0
        while (k < s.length && s[k] == '/') k++
        s = s.substring(k)
        s = s.substringBefore('?')
        // .replace(/#@.*$/i, ""): the first "#@" whose tail has no line terminator
        var h = s.indexOf("#@")
        while (h >= 0) {
            if ((h + 2 until s.length).none { Js.isLineTerminator(s[it]) }) {
                s = s.substring(0, h)
                break
            }
            h = s.indexOf("#@", h + 1)
        }
        return Js.toLowerCase(Helper.trimTrailingSlash(s))
    }

    private class InlineMeta(val textMatches: Boolean, val isInline: Boolean)

    private fun inlineMeta(el: Element, href: String, author: String, permlink: String, communityTag: String? = null): InlineMeta {
        val text = el.textContent ?: ""
        val textMatches = matchesHref(href, text)
        val titleMatches = matchesHref(href, el.getAttribute("title"))
        val display = normalizeDisplayText(text)
        val target = Js.toLowerCase("@$author/$permlink")
        val sophisticated = display == target || (communityTag != null && display == "${Js.toLowerCase(communityTag)}/$target")
        return InlineMeta(textMatches, textMatches || titleMatches || sophisticated)
    }

    private fun addLineBreakBeforePostLink(el: Element, forApp: Boolean, isInline: Boolean) {
        val parent = el.parentNode
        if (forApp && isInline && parent != null) {
            parent.insertBefore(el.ownerDocument!!.createElement("br"), el)
        }
    }

    private fun stripControls(s: String): String {
        if (s.none { it == '\t' || it == '\n' || it == '\r' || it == '\u000C' || it == '\u000B' || it == '\u0000' }) return s
        return s.filter { !(it == '\t' || it == '\n' || it == '\r' || it == '\u000C' || it == '\u000B' || it == '\u0000') }
    }

    /** `/^(https?|mailto|hive|tel|web\+[a-z0-9.+-]+):/i` on the lower-cased href. */
    private fun isSafeScheme(t: String): Boolean {
        for (p in arrayOf("http:", "https:", "mailto:", "hive:", "tel:")) if (Js.startsWithCi(t, p)) return true
        if (!Js.startsWithCi(t, "web+")) return false
        var i = 4
        while (i < t.length && (Js_isAlnum(t[i]) || t[i] == '.' || t[i] == '+' || t[i] == '-')) i++
        return i > 4 && i < t.length && t[i] == ':'
    }

    private fun Js_isAlnum(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'

    /** `/^(\/\/|\/[^/]?|#|\?|[a-z0-9._\-]+(\/|$))/i` */
    private fun isRelative(t: String): Boolean {
        if (t.isEmpty()) return false
        val c = t[0]
        if (c == '/' || c == '#' || c == '?') return true
        var i = 0
        while (i < t.length && (Js_isAlnum(t[i]) || t[i] == '.' || t[i] == '_' || t[i] == '-')) i++
        return i > 0 && (i == t.length || t[i] == '/')
    }

    private fun stripWww(s: String) = if (s.startsWith("www.")) s.substring(4) else s

    private fun postLinkAttrs(el: Element, href: String, tag: String, author: String, permlink: String, isInline: Boolean, forApp: Boolean) {
        if (forApp) {
            el.removeAttribute("href")
            el.setAttribute("data-href", href)
            el.setAttribute("data-is-inline", isInline.toString())
            el.setAttribute("data-tag", tag)
            el.setAttribute("data-author", author)
            el.setAttribute("data-permlink", permlink)
        } else {
            el.setAttribute("href", "/@$author/$permlink")
            el.setAttribute("data-is-inline", isInline.toString())
        }
    }

    fun a(el: Element, ctx: RenderContext) {
        if (el.parentNode == null) return
        val forApp = ctx.forApp
        var href = el.getAttribute("href")
        if (href.isNullOrEmpty()) return

        val trimmed = Js.toLowerCase(stripControls(Js.trim(href)))
        if (!isSafeScheme(trimmed) && !isRelative(trimmed)) {
            el.removeAttribute("href")
            return
        }

        val className = el.getAttribute("class")
        if (!className.isNullOrEmpty() && (
                className == "markdown-author-link" || className == "markdown-tag-link" ||
                    className.contains("er-author") || className.contains("er-tag")
                )
        ) {
            if (!el.getAttribute("target").isNullOrEmpty()) el.setAttribute("rel", externalLinkRel(ctx.seo))
            return
        }

        val doc = el.ownerDocument!!

        if (Scanners.imgTest(href) &&
            Js.trim(href).replace("&amp;", "&") == Js.trim(DomUtil.serializedInnerHTML(el)).replace("&amp;", "&")
        ) {
            val imgHTML = ImgMethod.createImageHTML(href, false, ctx)
            val parsed = XmlDomParser.parseFromString(imgHTML)
            val replaceNode = parsed.firstChild
            val parent = el.parentNode
            if (replaceNode != null && parent != null) {
                parent.replaceChild(DomUtil.importNode(doc, replaceNode, true), el)
            }
            return
        }

        if (Scanners.ipfsTest(href) &&
            Js.trim(href).replace("&amp;", "&") == Js.trim(DomUtil.serializedInnerHTML(el)).replace("&amp;", "&") &&
            href.indexOf('#') == -1
        ) {
            if (forApp) {
                el.setAttribute("data-href", href)
                el.removeAttribute("href")
            }
            el.setAttribute("class", "markdown-img-link")
            DomUtil.removeChildNodes(el)
            val img = doc.createElement("img")
            img.setAttribute("src", href)
            el.appendChild(img)
            return
        }

        // A hive post
        val postMatch = Consts.POST_REGEX.find(href)
        if (postMatch != null && stripWww(postMatch.g(1)!!) in Consts.WHITE_LIST) {
            el.setAttribute("class", "markdown-post-link")
            val tag = postMatch.g(2)!!
            val author = Js.replaceFirst(postMatch.g(3)!!, "@", "")
            val permlink = Helper.sanitizePermlink(postMatch.g(4))
            if (!Helper.isValidPermlink(permlink)) return
            val meta = inlineMeta(el, href, author, permlink)
            if (meta.textMatches) el.textContent = "@$author/$permlink"
            postLinkAttrs(el, href, tag, author, permlink, meta.isInline, forApp)
            addLineBreakBeforePostLink(el, forApp, meta.isInline)
            return
        }

        // A hive user with url
        val mentionMatch = Consts.MENTION_REGEX.find(href)
        if (mentionMatch != null && stripWww(mentionMatch.g(1)!!) in Consts.WHITE_LIST) {
            val rawAuthor = Js.replaceFirst(mentionMatch.g(2)!!, "@", "")
            if (!Helper.isValidUsername(rawAuthor)) return
            val author = Js.toLowerCase(rawAuthor)
            el.setAttribute("class", "markdown-author-link")
            if (author.indexOf('/') == -1) {
                if (el.textContent == href) el.textContent = "@$author"
                if (forApp) {
                    el.removeAttribute("href")
                    el.setAttribute("data-author", author)
                } else {
                    el.setAttribute("href", "/@$author")
                }
            }
            return
        }

        // Tagged post and profile section links
        val tpostMatch = Consts.INTERNAL_POST_TAG_REGEX.find(href)
        var isValidDomain = false
        if (tpostMatch != null) {
            val g1 = tpostMatch.g(1)!!
            if (g1.indexOf('/') == 0) {
                isValidDomain = true
            } else if (g1.contains('.')) {
                isValidDomain = stripWww(stripProtocol(g1)) in Consts.WHITE_LIST
            }
        }
        if (isValidDomain) {
            val m = tpostMatch!!
            val g1 = m.g(1)!!
            val g3 = m.g(3)!!
            val pathSegment = g3.substringBefore('?')
            if (Consts.SECTION_LIST.any { pathSegment == it || pathSegment.startsWith("$it/") }) {
                el.setAttribute("class", "markdown-profile-link")
                val author = Js.toLowerCase(Js.replaceFirst(m.g(2)!!, "@", ""))
                val section = g3
                if (!Helper.isValidPermlink(section)) return
                if (el.textContent == href) el.textContent = "@$author/$section"
                val external = ctx.options.externalProfileBase
                if (forApp || external != null) {
                    el.setAttribute("href", "${external ?: "https://ecency.com"}/@$author/$section")
                } else {
                    el.setAttribute("href", "/@$author/$section")
                }
                return
            } else {
                var tag = "post"
                if (g1.isNotEmpty() && !g1.contains('.')) {
                    tag = Js.replaceFirst(g1, "/", "")
                }
                el.setAttribute("class", "markdown-post-link")
                val author = Js.replaceFirst(m.g(2)!!, "@", "")
                val permlink = Helper.sanitizePermlink(g3)
                if (!Helper.isValidPermlink(permlink)) return
                val communityTag = if (Js.toLowerCase(tag).startsWith("hive-")) tag else null
                val meta = inlineMeta(el, href, author, permlink, communityTag)
                if (meta.textMatches) el.textContent = "@$author/$permlink"
                postLinkAttrs(el, href, tag, author, permlink, meta.isInline, forApp)
                addLineBreakBeforePostLink(el, forApp, meta.isInline)
                return
            }
        }

        // A hive user with internal url
        val imentionMatch = Consts.INTERNAL_MENTION_REGEX.find(href)
        if (imentionMatch != null) {
            val rawAuthor = Js.replaceFirst(imentionMatch.value, "/@", "")
            if (!Helper.isValidUsername(rawAuthor)) return
            val author = Js.toLowerCase(rawAuthor)
            el.setAttribute("class", "markdown-author-link")
            if (author.indexOf('/') == -1) {
                if (el.textContent == href) el.textContent = "@$author"
                if (forApp) {
                    el.removeAttribute("href")
                    el.setAttribute("data-author", author)
                } else {
                    el.setAttribute("href", "/@$author")
                }
            }
            return
        }

        // A copied post and profile section links
        val cpostMatch = Consts.INTERNAL_POST_REGEX.find(href)
        if (cpostMatch != null && cpostMatch.g(1)!!.indexOf('@') == 0) {
            val g2 = cpostMatch.g(2)!!
            val pathSegment = g2.substringBefore('?')
            if (Consts.SECTION_LIST.any { pathSegment == it || pathSegment.startsWith("$it/") }) {
                el.setAttribute("class", "markdown-profile-link")
                val author = Js.toLowerCase(Js.replaceFirst(cpostMatch.g(1)!!, "@", ""))
                val section = g2
                if (el.textContent == href) el.textContent = "@$author/$section"
                val external = ctx.options.externalProfileBase
                if (forApp || external != null) {
                    el.setAttribute("href", "${external ?: "https://ecency.com"}/@$author/$section")
                } else {
                    el.setAttribute("href", "/@$author/$section")
                }
                return
            } else {
                el.setAttribute("class", "markdown-post-link")
                val author = Js.replaceFirst(cpostMatch.g(1)!!, "@", "")
                val permlink = Helper.sanitizePermlink(g2)
                if (!Helper.isValidPermlink(permlink)) return
                val meta = inlineMeta(el, href, author, permlink)
                if (meta.textMatches) el.textContent = "@$author/$permlink"
                postLinkAttrs(el, href, "post", author, permlink, meta.isInline, forApp)
                addLineBreakBeforePostLink(el, forApp, meta.isInline)
                return
            }
        }

        // Topic with filters url
        val topicMatch = Consts.TOPIC_REGEX.find(href)
        if (topicMatch != null && stripWww(topicMatch.g(1)!!) in Consts.WHITE_LIST) {
            topicLink(el, href, topicMatch.g(2)!!, topicMatch.g(3)!!, forApp)
            return
        }

        // Topic with filters internal url
        val itopicMatch = Consts.INTERNAL_TOPIC_REGEX.find(href)
        if (itopicMatch != null) {
            topicLink(el, href, itopicMatch.g(1)!!, itopicMatch.g(2)!!, forApp)
            return
        }

        // A custom hive community link
        val comMatch = Consts.CUSTOM_COMMUNITY_REGEX.find(href)
        if (comMatch != null && stripWww(comMatch.g(1)!!) in Consts.WHITE_LIST) {
            el.setAttribute("class", "markdown-community-link")
            val community = comMatch.g(2)!!
            val g3 = comMatch.g(3)!!
            var filter = if (g3.isEmpty()) "" else g3.substring(1)
            if (filter.isEmpty()) filter = "created"
            if (filter == "about" || filter == "discord") filter = "created"
            if (el.textContent == href) el.textContent = "$filter/$community"
            if (forApp) {
                el.removeAttribute("href")
                el.setAttribute("data-community", community)
                el.setAttribute("data-filter", filter)
            } else {
                el.setAttribute("href", "/$filter/$community")
            }
            return
        }

        // A collections post
        val cccMatch = Consts.CCC_REGEX.find(href)
        if (cccMatch != null && stripWww(cccMatch.g(1)!!) in Consts.WHITE_LIST) {
            el.setAttribute("class", "markdown-post-link")
            val author = Js.replaceFirst(cccMatch.g(2)!!, "@", "")
            val permlink = Helper.sanitizePermlink(cccMatch.g(3))
            if (!Helper.isValidPermlink(permlink)) return
            val meta = inlineMeta(el, href, author, permlink)
            if (meta.textMatches) el.textContent = "@$author/$permlink"
            postLinkAttrs(el, href, "ccc", author, permlink, meta.isInline, forApp)
            addLineBreakBeforePostLink(el, forApp, meta.isInline)
            return
        }

        val textTrim = { Js.trim(el.textContent ?: "") }

        Consts.BITCHUTE_REGEX.find(href)?.let { m ->
            val id = m.g(1)
            if (!id.isNullOrEmpty() && textTrim() == href) {
                renderPlainVideoLink(el, "bitchute", "https://www.bitchute.com/embed/$id/", ctx)
                return
            }
        }
        Consts.RUMBLE_REGEX.find(href)?.let { m ->
            val id = m.g(1)
            if (!id.isNullOrEmpty() && textTrim() == href) {
                renderPlainVideoLink(el, "rumble", "https://www.rumble.com/embed/$id/?pub=4", ctx)
                return
            }
        }
        Consts.BRIGHTEON_REGEX.find(href)?.let { m ->
            val id = m.g(2)
            if (!id.isNullOrEmpty() && textTrim() == href) {
                renderPlainVideoLink(el, "brighteon", "https://www.brighteon.com/embed/$id", ctx)
                return
            }
        }
        Consts.ODYSEE_WATCH_REGEX.find(href)?.let { m ->
            val path = m.g(1)
            if (!path.isNullOrEmpty() && textTrim() == href) {
                renderPlainVideoLink(el, "odysee", "https://odysee.com/\$/embed/$path", ctx)
                return
            }
        }

        // YouTube
        val yt = Scanners.youtubeFind(href)
        if (yt != null && yt.id.isNotEmpty() && textTrim() == href) {
            val videoClass = Helper.youtubeVideoLinkClass(yt.matched)
            el.setAttribute("class", videoClass)
            el.removeAttribute("href")
            val vid = yt.id
            val thumbnail = ctx.proxy.proxify("https://img.youtube.com/vi/${vid.substringBefore('?')}/hqdefault.jpg")
            val embedSrc = "https://www.youtube.com/embed/$vid?autoplay=1"
            el.textContent = ""
            el.setAttribute("data-embed-src", embedSrc)
            el.setAttribute("data-youtube", vid)
            val startTime = Helper.extractYtStartTime(href)
            if (startTime.isNotEmpty()) el.setAttribute("data-start-time", startTime)
            if (ctx.options.embedVideosDirectly) {
                val wrapper = doc.createElement("span")
                wrapper.setAttribute("class", "er-youtube-frame")
                wrapper.setAttribute("style", "display:block")
                val iframe = doc.createElement("iframe")
                iframe.setAttribute("class", "youtube-player")
                iframe.setAttribute("src", "https://www.youtube.com/embed/$vid")
                iframe.setAttribute("title", "YouTube video")
                iframe.setAttribute("allow", "accelerometer; encrypted-media; gyroscope; picture-in-picture; web-share")
                iframe.setAttribute("allowfullscreen", "")
                wrapper.appendChild(iframe)
                el.appendChild(wrapper)
                el.setAttribute("class", "$videoClass er-youtube")
            } else {
                val thumbImg = doc.createElement("img")
                thumbImg.setAttribute("class", "no-replace video-thumbnail")
                thumbImg.setAttribute("itemprop", "thumbnailUrl")
                thumbImg.setAttribute("src", thumbnail)
                val play = doc.createElement("span")
                play.setAttribute("class", "markdown-video-play")
                el.appendChild(thumbImg)
                el.appendChild(play)
            }
            return
        }

        // Vimeo
        Consts.VIMEO_REGEX.find(href)?.let { m ->
            val id = m.g(3)
            if (!id.isNullOrEmpty() && href == el.textContent) {
                el.setAttribute("class", "markdown-video-link markdown-video-link-vimeo")
                el.removeAttribute("href")
                el.textContent = ""
                appendFrame(el, "https://player.vimeo.com/video/$id", fullscreen = true, sandbox = false)
                return
            }
        }

        // Twitch
        Consts.TWITCH_REGEX.find(href)?.let { m ->
            val name = m.g(2)
            if (!name.isNullOrEmpty() && href == el.textContent) {
                el.setAttribute("class", "markdown-video-link markdown-video-link-twitch")
                el.removeAttribute("href")
                val parent = if (ctx.parentDomain.isNotEmpty()) "&parent=${ctx.parentDomain}" else ""
                val embedSrc = if (m.g(1) == "videos") {
                    "https://player.twitch.tv/?video=$name$parent"
                } else {
                    "https://player.twitch.tv/?channel=$name$parent"
                }
                el.textContent = ""
                appendFrame(el, embedSrc, fullscreen = true, sandbox = false)
                return
            }
        }

        // Spotify playlist
        if (textTrim() == href) {
            val m = Consts.SPOTIFY_REGEX.find(href)
            val id = m?.g(1)
            if (!id.isNullOrEmpty()) {
                el.setAttribute("class", "markdown-audio-link markdown-audio-link-spotify")
                el.removeAttribute("href")
                el.textContent = ""
                appendFrame(el, "https://open.spotify.com/embed/playlist/$id", fullscreen = true, sandbox = true)
                return
            }
        }

        // Loom
        Consts.LOOM_REGEX.find(href)?.let { m ->
            val id = m.g(2)
            if (!id.isNullOrEmpty() && textTrim() == href) {
                el.setAttribute("class", "markdown-video-link markdown-video-link-loom")
                el.removeAttribute("href")
                el.textContent = ""
                appendFrame(el, "https://www.loom.com/embed/$id", fullscreen = true, sandbox = true)
                return
            }
        }

        // DTube (only d.tube links carry an image)
        val imgEls = el.getElementsByTagName("img")
        if (imgEls.size == 1 || textTrim() == href) {
            val m = Consts.D_TUBE_REGEX.find(href)
            if (m != null && !m.g(2).isNullOrEmpty() && !m.g(3).isNullOrEmpty()) {
                el.setAttribute("class", "markdown-video-link markdown-video-link-dtube")
                el.removeAttribute("href")
                el.setAttribute("data-embed-src", "https://emb.d.tube/#!/${m.g(2)}/${m.g(3)}")
                if (imgEls.size == 1) {
                    moveThumbnail(el, imgEls, ctx)
                } else {
                    el.textContent = ""
                }
                val play = doc.createElement("span")
                play.setAttribute("class", "markdown-video-play")
                el.appendChild(play)
                return
            }
        }
        Consts.D_TUBE_REGEX2.find(href)?.let { m ->
            if (!m.g(2).isNullOrEmpty() && !m.g(3).isNullOrEmpty()) {
                el.setAttribute("class", "markdown-video-link markdown-video-link-dtube")
                el.removeAttribute("href")
                el.textContent = ""
                el.setAttribute("data-embed-src", "https://emb.d.tube/#!/${m.g(2)}/${m.g(3)}")
                val play = doc.createElement("span")
                play.setAttribute("class", "markdown-video-play")
                el.appendChild(play)
                return
            }
        }

        // 3Speak
        val speak = Consts.SPEAK_REGEX.find(href)
        if (speak != null) {
            val speakImgs = el.getElementsByTagName("img")
            if (speakImgs.size == 1 || textTrim() == href) {
                if ((!speak.g(1).isNullOrEmpty() || !speak.g(2).isNullOrEmpty()) && !speak.g(3).isNullOrEmpty()) {
                    val videoHref = "https://play.3speak.tv/watch?v=${speak.g(3)}&mode=iframe"
                    el.setAttribute("class", "markdown-video-link markdown-video-link-speak")
                    el.removeAttribute("href")
                    el.setAttribute("data-embed-src", videoHref)
                    if (textTrim() == href) el.textContent = ""
                    if (ctx.options.embedVideosDirectly) {
                        val wrapper = doc.createElement("span")
                        wrapper.setAttribute("class", "er-speak-frame")
                        wrapper.setAttribute("style", "display:block")
                        val iframe = doc.createElement("iframe")
                        iframe.setAttribute("class", "speak-iframe")
                        iframe.setAttribute("src", "$videoHref&autoplay=false")
                        iframe.setAttribute("title", "3Speak video")
                        iframe.setAttribute("allow", "accelerometer; encrypted-media; gyroscope; picture-in-picture; web-share")
                        iframe.setAttribute("allowfullscreen", "")
                        wrapper.appendChild(iframe)
                        el.appendChild(wrapper)
                        el.setAttribute("class", "markdown-video-link markdown-video-link-speak er-speak")
                    } else {
                        if (speakImgs.size == 1) moveThumbnail(el, speakImgs, ctx)
                        val play = doc.createElement("span")
                        play.setAttribute("class", "markdown-video-play")
                        el.appendChild(play)
                    }
                    return
                }
            }
        }

        // 3Speak audio
        if (Consts.SPEAK_AUDIO_REGEX.containsMatchIn(href) && textTrim() == href) {
            el.setAttribute("class", "markdown-audio-link markdown-audio-link-speak")
            el.removeAttribute("href")
            val embedSrc = if (hasParam(href, "iframe")) href else "$href&iframe=1"
            val finalSrc = if (hasParam(embedSrc, "mode")) embedSrc else "$embedSrc&mode=compact"
            el.textContent = ""
            val ifr = doc.createElement("iframe")
            ifr.setAttribute("frameborder", "0")
            ifr.setAttribute("src", finalSrc)
            ifr.setAttribute("sandbox", "allow-scripts allow-same-origin allow-popups")
            el.appendChild(ifr)
            return
        }

        // Liketu Speak audio (href only)
        if (Consts.LIKETU_AUDIO_REGEX.containsMatchIn(href)) {
            el.setAttribute("class", "markdown-audio-link markdown-audio-link-liketu")
            el.removeAttribute("href")
            el.textContent = ""
            val audio = doc.createElement("audio")
            audio.setAttribute("controls", "")
            audio.setAttribute("preload", "metadata")
            audio.setAttribute("src", href)
            el.appendChild(audio)
            return
        }

        // Tweets
        val tw = Consts.TWITTER_REGEX.find(href)
        if (tw != null && textTrim() == href) {
            val url = Helper.stripHtmlTags(tw.value)
            val author = Helper.stripHtmlTags(tw.g(1) ?: "undefined")
            val blockquote = doc.createElement("blockquote")
            blockquote.setAttribute("class", "twitter-tweet")
            val p = doc.createElement("p")
            p.textContent = url
            val textNode = doc.createTextNode("- ")
            val link = doc.createElement("a")
            link.setAttribute("href", url)
            link.textContent = author
            blockquote.appendChild(p)
            blockquote.appendChild(textNode)
            blockquote.appendChild(link)
            el.parentNode?.replaceChild(blockquote, el)
            return
        }

        if (href.startsWith("https://hivesigner.com/sign/account-witness-vote?witness=") && forApp) {
            el.setAttribute("class", "markdown-witnesses-link")
            el.setAttribute("data-href", href)
            el.removeAttribute("href")
            return
        }

        if (href.indexOf("hivesigner.com/sign/update-proposal-votes?proposal_ids") >= 0 && forApp) {
            try {
                val m = Consts.PROPOSAL_IDS.find(Js.decodeUri(href))
                if (m != null) {
                    el.setAttribute("class", "markdown-proposal-link")
                    el.setAttribute("data-href", href)
                    el.setAttribute("data-proposal", m.g(1)!!)
                    el.removeAttribute("href")
                    return
                }
            } catch (e: UriError) {
                // invalid encoding: falls through to the external link
            }
        }

        // External link
        el.setAttribute("class", "markdown-external-link")
        if (!Consts.EXTERNAL_SCHEME.containsMatchIn(href)) href = "https://$href"
        if (forApp) {
            el.setAttribute("data-href", href)
            val m = Scanners.youtubeFind(href)
            if (m != null && m.id.isNotEmpty()) {
                el.setAttribute("data-youtube", m.id)
                val startTime = Helper.extractYtStartTime(href)
                if (startTime.isNotEmpty()) el.setAttribute("data-start-time", startTime)
            }
            el.removeAttribute("href")
        } else {
            if (Js.trim(href).startsWith("#")) {
                el.setAttribute("class", "markdown-internal-link")
            } else {
                el.setAttribute("target", "_blank")
                el.setAttribute("rel", externalLinkRel(ctx.seo))
            }
            el.setAttribute("href", href)
        }
    }

    /** `.replace(/^https?:\/\//, '')` */
    private fun stripProtocol(s: String): String = when {
        s.startsWith("https://") -> s.substring(8)
        s.startsWith("http://") -> s.substring(7)
        else -> s
    }

    /** `/[?&]name=/.test(s)` */
    private fun hasParam(s: String, name: String): Boolean = s.contains("?$name=") || s.contains("&$name=")

    private fun topicLink(el: Element, href: String, filter: String, tag: String, forApp: Boolean) {
        el.setAttribute("class", "markdown-tag-link")
        if (el.textContent == href) el.textContent = "/$filter/$tag"
        if (forApp) {
            el.removeAttribute("href")
            el.setAttribute("data-filter", filter)
            el.setAttribute("data-tag", tag)
        } else {
            el.setAttribute("href", "/$filter/$tag")
        }
    }

    private fun appendFrame(el: Element, src: String, fullscreen: Boolean, sandbox: Boolean) {
        val ifr = el.ownerDocument!!.createElement("iframe")
        ifr.setAttribute("frameborder", "0")
        if (fullscreen) ifr.setAttribute("allowfullscreen", "true")
        ifr.setAttribute("src", src)
        if (sandbox) ifr.setAttribute("sandbox", "allow-scripts allow-same-origin allow-popups")
        el.appendChild(ifr)
    }

    /** Replaces the single `<img>` of a video link with a proxied thumbnail (live list, as xmldom). */
    private fun moveThumbnail(el: Element, imgEls: List<Element>, ctx: RenderContext) {
        val src = imgEls[0].getAttribute("src")
        if (src.isNullOrEmpty()) return
        val thumbnail = ctx.proxy.proxify(src.filter { !Js.isSpace(it) })
        val thumbImg = el.ownerDocument!!.createElement("img")
        thumbImg.setAttribute("class", "no-replace video-thumbnail")
        thumbImg.setAttribute("itemprop", "thumbnailUrl")
        thumbImg.setAttribute("src", thumbnail)
        el.appendChild(thumbImg)
        el.removeChild(imgEls[0])
    }
}

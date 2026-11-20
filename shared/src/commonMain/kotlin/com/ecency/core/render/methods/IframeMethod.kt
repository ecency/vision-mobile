package com.ecency.core.render.methods

import com.ecency.core.render.js.Js
import com.ecency.core.render.dom.Element
import com.ecency.core.render.sanitize.EmbedSrc

/** render-helper `methods/iframe.method.ts`: normalizes known embeds, replaces the rest. */
internal object IframeMethod {
    /** `/[?&]name=/.test(s)` (case-sensitive). */
    private fun hasParam(s: String, name: String): Boolean = s.contains("?$name=") || s.contains("&$name=")

    private fun startsWithWww(s: String): Boolean {
        // /^(https?:)?\/\/www\./i
        var i = 0
        if (Js.startsWithCi(s, "https:")) i = 6 else if (Js.startsWithCi(s, "http:")) i = 5
        return s.startsWith("//", i) && Js.startsWithCi(s, "www.", i + 2)
    }

    /** `.replace(/^(https?:)?\/\/(?:m\.)?youtube\.com/i, 'https://www.youtube.com')` */
    private fun pinYoutubeHost(s: String): String {
        val starts = mutableListOf<Int>()
        if (Js.startsWithCi(s, "https:")) starts.add(6)
        if (Js.startsWithCi(s, "http:")) starts.add(5)
        starts.add(0)
        for (i in starts) {
            if (!s.startsWith("//", i)) continue
            var j = i + 2
            if (Js.startsWithCi(s, "m.youtube.com", j)) return "https://www.youtube.com" + s.substring(j + 13)
            if (Js.startsWithCi(s, "youtube.com", j)) return "https://www.youtube.com" + s.substring(j + 11)
        }
        return s
    }

    /** `.replace(/(?:play\.)?3speak\.[a-z]+/i, 'play.3speak.tv')`: first match. */
    private fun normalizeSpeakHost(s: String): String {
        var p = Js.indexOfCi(s, "3speak.")
        while (p >= 0) {
            var e = p + 7
            while (e < s.length && (s[e] in 'a'..'z' || s[e] in 'A'..'Z')) e++
            if (e > p + 7) {
                // The leftmost match starts at "play." when it directly precedes this "3speak.".
                val start = if (p >= 5 && Js.startsWithCi(s, "play.", p - 5)) p - 5 else p
                return s.substring(0, start) + "play.3speak.tv" + s.substring(e)
            }
            p = Js.indexOfCi(s, "3speak.", p + 1)
        }
        return s
    }

    /** `.replace(/([?&]autoplay=)[^&]*\/i, '$1false')`: first match. */
    private fun forceAutoplayFalse(s: String): String {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if ((c == '?' || c == '&') && Js.startsWithCi(s, "autoplay=", i + 1)) {
                val v = i + 10
                var e = v
                while (e < s.length && s[e] != '&') e++
                return s.substring(0, v) + "false" + s.substring(e)
            }
            i++
        }
        return s
    }

    /** Tab, LF, FF, CR and space: what a browser strips from a URL attribute. */
    private fun isAsciiWhitespace(c: Char) = c == '\t' || c == '\n' || c == '\u000C' || c == '\r' || c == ' '

    private fun trimAsciiWhitespace(v: String): String {
        var start = 0
        var end = v.length
        while (start < end && isAsciiWhitespace(v[start])) start++
        while (end > start && isAsciiWhitespace(v[end - 1])) end--
        return v.substring(start, end)
    }

    /**
     * 2.5.45 (#1919): the src trimmed as a browser reads it; a known provider normalises the iframe, then the result
     * is held to the embed allow list in iframe()'s canonical spelling. Anything else becomes the placeholder.
     */
    fun iframe(el: Element, ctx: RenderContext) {
        val parent = el.parentNode ?: return
        val src = trimAsciiWhitespace(el.getAttribute("src") ?: "")
        if (src.isEmpty()) {
            parent.removeChild(el)
            return
        }
        val supported = applyProvider(el, src, ctx)
        // Skatehive swaps the iframe for a <video>; nothing left to check.
        if (el.parentNode == null) return
        if (supported) {
            val finalSrc = EmbedSrc.canonicalEmbedSrc(el.getAttribute("src") ?: "")
            if (EmbedSrc.isAllowedEmbedSrc(finalSrc)) {
                el.setAttribute("src", finalSrc)
                return
            }
        }
        replaceUnsupported(el, src)
    }

    /** Normalises a known provider's iframe in place. False when no provider matched. */
    private fun applyProvider(el: Element, src: String, ctx: RenderContext): Boolean {
        val parent = el.parentNode ?: return false
        val ytMatch = Consts.YOUTUBE_EMBED_REGEX.find(src)
        if (ytMatch != null) {
            var stripped = Helper.stripQueryString(src)
            if (!startsWithWww(stripped)) stripped = pinYoutubeHost(stripped)
            // the player and the allow list only know the lower case /embed/
            stripped = lowerEmbedRoute(stripped)
            el.setAttribute("src", stripped)
            if (Js.toLowerCase(ytMatch.g(2)!!) == "shorts") {
                // not a video id, and the /shorts/ page itself cannot be framed
                val shortId = Consts.SHORTS_ID.find(stripped) ?: return false
                el.setAttribute("src", "https://www.youtube.com/embed/${shortId.g(1)}")
                el.setAttribute("class", "portrait-embed")
            }
            return true
        }

        // BitChute: /video/<id> pages and /embed/<id> share the id; frame the embed route.
        Consts.BITCHUTE_REGEX.find(src)?.let {
            el.setAttribute("src", "https://www.bitchute.com/embed/${it.g(1)}/")
            return true
        }

        val vm = Consts.VIMEO_EMBED_REGEX.find(src)
        if (vm != null) {
            el.setAttribute("src", "https://player.vimeo.com/video/${vm.g(1)}")
            return true
        }

        if (Consts.TWITCH_EMBED_REGEX.containsMatchIn(src)) {
            var s = src
            if (!s.contains("parent=")) s = s + (if (s.contains('?')) "&" else "?") + "parent=" + ctx.parentDomain
            if (!s.contains("autoplay=")) s = s + (if (s.contains('?')) "&" else "?") + "autoplay=false"
            el.setAttribute("src", s)
            return true
        }

        if (Consts.SPEAK_EMBED_REGEX.containsMatchIn(src)) {
            var normalized = normalizeSpeakHost(src)
            normalized = Js.replaceFirst(normalized, "/embed?", "/watch?")
            if (!hasParam(normalized, "mode")) normalized = "$normalized&mode=iframe"
            val hasAutoplay = hasParam(normalized, "autoplay")
            var s = if (ctx.options.embedVideosDirectly) {
                if (hasAutoplay) forceAutoplayFalse(normalized) else "$normalized&autoplay=false"
            } else {
                if (hasAutoplay) normalized else "$normalized&autoplay=true"
            }
            if (ctx.forApp && !hasParam(s, "layout")) s = "$s&layout=mobile"
            el.setAttribute("src", s)
            el.setAttribute("class", "speak-iframe")
            return true
        }

        if (Consts.SPEAK_AUDIO_EMBED_REGEX.containsMatchIn(src)) {
            var normalized = src
            if (!hasParam(normalized, "iframe")) normalized = "$normalized&iframe=1"
            if (!hasParam(normalized, "mode")) normalized = "$normalized&mode=compact"
            el.setAttribute("src", normalized)
            el.setAttribute("class", "speak-audio-iframe")
            el.setAttribute("frameborder", "0")
            el.setAttribute("sandbox", "allow-scripts allow-same-origin allow-popups")
            return true
        }

        if (Consts.SPOTIFY_EMBED_REGEX.containsMatchIn(src)) {
            el.setAttribute("src", src)
            el.setAttribute("sandbox", "allow-scripts allow-same-origin allow-popups")
            el.setAttribute("frameborder", "0")
            return true
        }

        if (Consts.SOUNDCLOUD_EMBED_REGEX.containsMatchIn(src)) {
            val m = Consts.SOUNDCLOUD_URL.find(src)
            val url = m?.g(1)
            if (!url.isNullOrEmpty()) {
                el.setAttribute(
                    "src",
                    "https://w.soundcloud.com/player/?url=$url&auto_play=false&hide_related=false&show_comments=true&show_user=true&show_reposts=false&visual=true",
                )
            }
            return true
        }

        if (Consts.D_TUBE_EMBED_REGEX.containsMatchIn(src)) {
            el.setAttribute("src", src)
            el.setAttribute("sandbox", "allow-scripts allow-same-origin")
            el.setAttribute("frameborder", "0")
            el.setAttribute("allowfullscreen", "true")
            return true
        }

        if (Consts.VIMM_EMBED_REGEX.containsMatchIn(src)) {
            el.setAttribute("src", src)
            el.setAttribute("sandbox", "allow-scripts allow-same-origin allow-popups")
            el.setAttribute("frameborder", "0")
            el.setAttribute("allowfullscreen", "true")
            return true
        }

        if (Consts.DAPPLR_REGEX.containsMatchIn(src)) {
            el.setAttribute("src", src)
            el.setAttribute("sandbox", "allow-scripts allow-same-origin")
            el.setAttribute("frameborder", "0")
            el.setAttribute("allowfullscreen", "true")
            return true
        }

        if (Consts.TRUVVL_REGEX.containsMatchIn(src)) {
            el.setAttribute("src", src)
            el.setAttribute("sandbox", "allow-scripts allow-same-origin allow-popups")
            el.setAttribute("frameborder", "0")
            el.setAttribute("class", "portrait-embed")
            el.setAttribute("allowfullscreen", "true")
            return true
        }

        if (Consts.LBRY_REGEX.containsMatchIn(src) || Consts.ODYSEE_REGEX.containsMatchIn(src)) {
            el.setAttribute("src", src)
            el.setAttribute("frameborder", "0")
            return true
        }

        if (Consts.SKATEHIVE_IPFS_REGEX.containsMatchIn(src)) {
            val video = el.ownerDocument!!.createElement("video")
            video.setAttribute("src", src)
            video.setAttribute("controls", "")
            parent.insertBefore(video, el)
            parent.removeChild(el)
            return true
        }

        if (Consts.SKATEHYPE_EMBED_REGEX.containsMatchIn(src)) {
            el.setAttribute("src", forceHttps(src))
            el.setAttribute("frameborder", "0")
            el.setAttribute("allowfullscreen", "true")
            return true
        }

        if (Consts.ARCH_REGEX.containsMatchIn(src)) {
            el.setAttribute("src", src)
            return true
        }

        if (Consts.RUMBLE_REGEX.containsMatchIn(src)) {
            el.setAttribute("src", src)
            el.setAttribute("frameborder", "0")
            return true
        }

        // Brighteon: a page URL frames as the embed route, as a.method.ts builds it.
        Consts.BRIGHTEON_REGEX.find(src)?.let {
            el.setAttribute("src", "https://www.brighteon.com/embed/${it.g(2)}")
            el.setAttribute("frameborder", "0")
            return true
        }

        if (Consts.BRAND_NEW_TUBE_REGEX.containsMatchIn(src) || Consts.LOOM_EMBED_REGEX.containsMatchIn(src)) {
            el.setAttribute("src", src)
            el.setAttribute("frameborder", "0")
            return true
        }

        if (Consts.AUREAL_EMBED_REGEX.containsMatchIn(src)) {
            el.setAttribute("src", if (src.startsWith("//")) "https:$src" else src)
            el.setAttribute("frameborder", "0")
            return true
        }

        return false
    }

    /** `.replace(/^((?:https?:)?\/\/[^/]+)\/embed\//i, '$1/embed/')` */
    private fun lowerEmbedRoute(s: String): String {
        var i = 0
        if (Js.startsWithCi(s, "https:")) i = 6 else if (Js.startsWithCi(s, "http:")) i = 5
        if (!s.startsWith("//", i)) return s
        val slash = s.indexOf('/', i + 2)
        if (slash <= i + 2) return s
        return if (Js.startsWithCi(s, "/embed/", slash) && !s.startsWith("/embed/", slash)) {
            s.substring(0, slash) + "/embed/" + s.substring(slash + 7)
        } else {
            s
        }
    }

    /** iframe()'s placeholder for an embed it does not support. */
    private fun replaceUnsupported(el: Element, src: String) {
        val parent = el.parentNode ?: return
        val replaceNode = el.ownerDocument!!.createElement("div")
        replaceNode.setAttribute("class", "unsupported-iframe")
        replaceNode.textContent = "(Unsupported $src)"
        parent.insertBefore(replaceNode, el)
        parent.removeChild(el)
    }

    /** `.replace(/^(https?:)?\/\//i, 'https://')` */
    private fun forceHttps(s: String): String {
        for (p in arrayOf("https://", "http://", "//")) if (Js.startsWithCi(s, p)) return "https://" + s.substring(p.length)
        return s
    }
}

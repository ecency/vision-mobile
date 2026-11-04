package com.ecency.core.render.methods

import com.ecency.core.render.LinearScaling
import com.ecency.core.render.RenderHelper
import com.ecency.core.render.transform.RenderCases
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** The Vimeo and Twitter href matchers against render-helper's regexes, and their scaling. */
class LinkScannersTest {
    // render-helper's sources, kept as the oracle for short inputs.
    private val vimeoRegex = JsRegex.compile("""(https?:\/\/)?(www\.)?(?:vimeo)\.com.*(?:videos|video|channels|)\/([\d]+)""", ignoreCase = true)
    private val twitterRegex = JsRegex.compile("""(?:https?:\/\/(?:(?:twitter\.com\/(.*?)\/status\/(.*))))""", ignoreCase = true)

    private fun show(s: String) = s.map { it.code }.joinToString(",")

    private fun gen(rnd: Random, tokens: List<String>): String =
        buildString { repeat(rnd.nextInt(0, 9)) { append(tokens[rnd.nextInt(tokens.size)]) } }

    private val breaks = listOf("\n", "\r", " ", " ", "\u0085", " ")

    @Test
    fun vimeoMatchesTheRegex() {
        val fixed = listOf(
            "", "vimeo.com/1", "https://vimeo.com/123", "https://www.vimeo.com/videos/45", "VIMEO.COM/7x", "vimeo.com",
            "vimeo.com/", "vimeo.com/a/1/2", "vimeo.com/1\nvimeo.com/2", "vimeo.com\n/1", "vimeo.com\u0085/1",
            "vimeo.com/1/channels/22", "vimeo.comx/9y/8", "xvimeo.com/channels/", "vimeo.com vimeo.com/5",
            "vimeo.co/1", "vimeo.com//12", "https://vimeo.com/1?h=2/3a",
        )
        fixed.forEach { assertEquals(vimeoRegex.find(it)?.g(3), Scanners.vimeoId(it), show(it)) }
        val tokens = listOf("vimeo.com", "VIMEO.COM", "Vimeo.Com", "vimeo", ".com", "https://", "www.", "/", "1", "23", "videos", "video", "channels", "a", "?") + breaks
        val rnd = Random(4242)
        repeat(60_000) {
            val s = gen(rnd, tokens)
            assertEquals(vimeoRegex.find(s)?.g(3), Scanners.vimeoId(s), show(s))
        }
    }

    @Test
    fun twitterMatchesTheRegex() {
        val oracle = { s: String -> twitterRegex.find(s)?.let { it.value to (it.g(1) ?: "undefined") } }
        val fixed = listOf(
            "", "https://twitter.com/a/status/1", "http://twitter.com/a/status/1", "HTTPS://TWITTER.COM/A/STATUS/1",
            "https://twitter.com//status/", "https://twitter.com/status/1", "https://twitter.com/a/b/status/1/status/2",
            "x https://twitter.com/a\n/status/1", "https://twitter.com/a\nhttps://twitter.com/b/status/2",
            "https://twitter.com/a/ſtatus/1", "httpss://twitter.com/a/status/1", "https:/twitter.com/a/status/1",
            "httphttps://twitter.com/a/status/1", "https://twitter.com/a/status/1\nmore", "https://twitter.com/a/status/1\u0085x",
        )
        fixed.forEach { assertEquals(oracle(it), Scanners.twitterFind(it), show(it)) }
        val tokens = listOf("https://", "http://", "HTTP://", "http", "s", "://", "twitter.com/", "Twitter.Com/", "twitter.com", "/status/", "/STATUS/", "/status", "status/", "/", "a", "ſ") + breaks
        val rnd = Random(777)
        repeat(60_000) {
            val s = gen(rnd, tokens)
            assertEquals(oracle(s), Scanners.twitterFind(s), show(s))
        }
    }

    private val helper = RenderHelper(RenderCases.PROXY_BASE)
    private fun link(u: String) = "<a href=\"$u\">$u</a>"

    @Test
    fun longVimeoHrefStaysLinear() {
        // every "vimeo.com" was a regex start whose `.*` backtracked over the rest of the href
        LinearScaling.assertLinear("vimeo href", 500, { n -> link("https://vimeo.com" + "vimeo.com".repeat(n)) }) { helper.renderPostBody(it, true) }
    }

    @Test
    fun longTwitterHrefStaysLinear() {
        LinearScaling.assertLinear("twitter href", 500, { n -> link("https://twitter.com/" + "https://twitter.com/".repeat(n)) }) {
            helper.renderPostBody(it, true)
        }
    }
}

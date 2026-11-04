package com.ecency.core.render.blocks

import com.ecency.core.render.sanitize.EmbedSrc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Player paths for every whole-site embed host, and the player's host lock across www redirects. */
class EmbedPathsTest {
    @Test
    fun everyWholeSiteEmbedHostNeedsItsPlayerPath() {
        val players = listOf(
            "https://audio.3speak.tv/play?a=someone/abc&iframe=1&mode=compact",
            "https://w.soundcloud.com/player/?url=https://api.soundcloud.com/tracks/1",
            "https://www.vimm.tv/someone/embed?autoplay=0",
            "https://www.vimm.tv/someone",
            "https://brandnewtube.com/embed/abc123",
            "https://lbry.tv/\$/embed/some-video/abc",
            "https://ipfs.skatehive.app/ipfs/QmSomething",
            "https://www.skatehype.com/ifplay.php?v=35231",
            "https://skatehype.com/ifplay.php?v=35231",
            "https://embed.truvvl.com/@someone/abc",
            "https://aureal-embed.web.app/123",
            "https://www.aureal-embed.web.app/123",
            "https://player.dapplr.in/file/dapplr-videos/someone/v.mp4",
            "https://archive.org/embed/some-item",
            "https://3speak.tv/embed?v=someone/abc",
        )
        players.forEach { assertNotNull(SafeTarget.embed(it), it) }
        val pages = listOf(
            "https://audio.3speak.tv/login",
            "https://w.soundcloud.com/login",
            // a single segment is a channel page for Vimm (render-helper embeds it), so only deeper paths are refused
            "https://www.vimm.tv/someone/settings/profile",
            "https://brandnewtube.com/login",
            "https://lbry.tv/login",
            "https://ipfs.skatehive.app/login",
            "https://www.skatehype.com/login",
            "https://skatehype.com/v/35231/some-trick",
            "https://embed.truvvl.com/login",
            "https://aureal-embed.web.app/login",
            "https://www.aureal-embed.web.app/login",
            "https://player.dapplr.in/login",
            "https://archive.org/account/login",
            "https://3speak.tv/upload",
        )
        pages.forEach { assertNull(SafeTarget.embed(it), it) }
        // hosts render-helper limits itself still pass on their own rule
        assertNotNull(SafeTarget.embed("https://www.youtube.com/embed/dQw4w9WgXcQ"))
        assertNotNull(SafeTarget.embed("https://player.vimeo.com/video/1"))
    }

    @Test
    fun playerFramesMayMoveBetweenTheBareAndWwwHost() {
        assertTrue(SafeTarget.samePlayerHost("www.youtube.com", "youtube.com"))
        assertTrue(SafeTarget.samePlayerHost("rumble.com", "www.rumble.com"))
        assertTrue(SafeTarget.samePlayerHost("WWW.YouTube.com", "www.youtube.com"))
        assertFalse(SafeTarget.samePlayerHost("m.youtube.com", "www.youtube.com"))
        assertFalse(SafeTarget.samePlayerHost("evilyoutube.com", "youtube.com"))
        assertFalse(SafeTarget.samePlayerHost("youtube.com.evil.example", "youtube.com"))
        assertFalse(SafeTarget.samePlayerHost("www.", "www."))
        assertFalse(SafeTarget.samePlayerHost("", ""))
    }

    @Test
    fun everyAllowlistedHostHasAPlayerRule() {
        val missing = (EmbedSrc.ALLOWED_HOSTS + EmbedSrc.ALLOWED_SUFFIXES).filterNot { SafeTarget.hasPlayerRule(it) }
        assertEquals(emptyList(), missing, "a host the allow list accepts needs a player path rule")
    }

    @Test
    fun aRefusedPlayerPageBecomesALinkNotAHole() {
        val url = "https://archive.org/account/login"
        val link = Block.Paragraph(listOf(Inline.Link(LinkTarget.External(url), listOf(Inline.Text(url)))))
        assertEquals(listOf(link), BlockBuilder.build("<iframe src=\"$url\"></iframe>"))
        assertEquals(listOf(link), BlockBuilder.build("<p>before<iframe src=\"$url\"></iframe></p>").filterIsInstance<Block.Paragraph>().takeLast(1))
        assertEquals(emptyList(), BlockBuilder.build("<iframe src=\"javascript:alert(1)\"></iframe>"))
        assertEquals(emptyList(), BlockBuilder.build("<iframe src=\"hive://sign/op/x\"></iframe>"), "only http(s) becomes a link")
    }
}

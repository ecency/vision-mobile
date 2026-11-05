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
    fun thePlayerNavigationRuleReadsUrlsAsTheWebViewDoes() {
        val embed = "https://www.youtube.com/embed/dQw4w9WgXcQ"
        val allowed = listOf(
            "https://www.youtube.com/embed/other",
            "https://youtube.com/embed/x",
            "https://WWW.YOUTUBE.COM/x",
            "about:blank",
            "about:srcdoc",
        )
        allowed.forEach { assertTrue(SafeTarget.playerNavigationAllowed(it, embed), it) }
        val refused = listOf(
            // a backslash is a slash to the web view: the host is the part before it
            "https://evil.example\\@www.youtube.com/embed/x",
            "https://www.youtube.com\\@evil.example/",
            "https:\\\\www.youtube.com/embed/x",
            // userinfo reads like one site and loads another
            "https://www.youtube.com@evil.example/",
            "https://user@www.youtube.com/embed/x",
            "https://user:pass@www.youtube.com/embed/x",
            // other host, other scheme
            "https://evil.example/",
            "https://m.youtube.com/watch?v=x",
            "https://evilyoutube.com/x",
            "https://player.vimeo.com/video/1",
            "http://www.youtube.com/embed/x",
            "javascript:alert(1)",
            "intent://x#Intent;end",
            "data:text/html,x",
            "about:srcdoc#x",
            "https://www.you\ttube.com/embed/x",
            "",
        )
        refused.forEach { assertFalse(SafeTarget.playerNavigationAllowed(it, embed), it) }
        assertFalse(SafeTarget.playerNavigationAllowed("https://www.youtube.com/embed/x", "https://evil.example\\@www.youtube.com/embed/x"), "nor from such an embed")
    }

    @Test
    fun anIpfsVideoStaysOnItsOwnFileWithoutScript() {
        val embed = "https://ipfs.skatehive.app/ipfs/QmVideoHash"
        assertTrue(SafeTarget.playerNavigationAllowed(embed, embed))
        assertTrue(SafeTarget.playerNavigationAllowed("$embed?filename=clip.mp4", embed))
        listOf(
            "https://ipfs.skatehive.app/ipfs/QmOtherHash",
            "https://ipfs.skatehive.app/ipfs/QmVideoHashMore",
            "https://ipfs.skatehive.app/login",
            "https://www.ipfs.skatehive.app/ipfs/QmVideoHash",
        ).forEach { assertFalse(SafeTarget.playerNavigationAllowed(it, embed), it) }
        // the player opens the file the post's video tag names, never a page under it
        assertNotNull(SafeTarget.embed("https://ipfs.skatehive.app/ipfs/bafybeigdyrzt5sfp7udm7hu76uh7y26nf3efuylqabf3oclgtqy55fbzdi"))
        assertNotNull(SafeTarget.embed("https://ipfs.skatehive.app/ipfs/QmVideoHash/clip.mp4"))
        assertNull(SafeTarget.embed("https://ipfs.skatehive.app/ipfs/QmVideoHash/index.html"))
        assertNull(SafeTarget.embed("https://ipfs.skatehive.app/ipfs/QmVideoHash/a/b.mp4"))
        assertNull(SafeTarget.embed("https://ipfs.skatehive.app/ipfs/Qm%2F..%2Fx"))
        // base32 and base36 CIDs with dashes or underscores, a trailing slash, an mkv file
        assertNotNull(SafeTarget.embed("https://ipfs.skatehive.app/ipfs/Qm_Video-Hash"))
        assertNotNull(SafeTarget.embed("https://ipfs.skatehive.app/ipfs/QmVideoHash/"))
        assertNotNull(SafeTarget.embed("https://ipfs.skatehive.app/ipfs/QmVideoHash/clip.MKV"))
        assertNull(SafeTarget.embed("https://ipfs.skatehive.app/ipfs/QmVideoHash//"))
        assertNull(SafeTarget.embed("https://ipfs.skatehive.app/ipfs/Qm.Video"))
        assertNull(SafeTarget.embed("https://ipfs.skatehive.app/ipfs/QmVideoHash/clip.mkv/"))
        assertFalse(SafeTarget.playerAllowsScript("https://ipfs.skatehive.app/ipfs/QmVideoHash/clip.mkv"))
        // the frame rule stays exact: the trailing slash form is its own file
        assertTrue(SafeTarget.playerNavigationAllowed("$embed/", "$embed/"))
        assertFalse(SafeTarget.playerNavigationAllowed("$embed/", embed))
        // a video file plays without page script; players keep theirs
        assertFalse(SafeTarget.playerAllowsScript(embed))
        assertTrue(SafeTarget.playerAllowsScript("https://www.youtube.com/embed/dQw4w9WgXcQ"))
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

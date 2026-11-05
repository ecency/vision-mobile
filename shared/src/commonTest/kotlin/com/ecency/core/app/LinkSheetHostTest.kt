package com.ecency.core.app

import kotlin.test.Test
import kotlin.test.assertEquals

/** The link sheet shows the ASCII (punycode) host under a link whose host has look-alike letters. */
class LinkSheetHostTest {
    @Test fun cyrillicLookAlikeHostShowsItsPunycode() {
        val raw = "https://аpple.com/login"
        assertEquals("$raw\nxn--pple-43d.com", LinkSheet.display(raw))
    }

    @Test fun asciiHostIsShownAsIs() {
        assertEquals("https://apple.com/login", LinkSheet.display("https://apple.com/login"))
        assertEquals("https://xn--pple-43d.com/", LinkSheet.display("https://xn--pple-43d.com/"))
    }

    @Test fun nonAsciiPathAloneAddsNothing() {
        assertEquals("https://ecency.com/а", LinkSheet.display("https://ecency.com/а"))
    }

    @Test fun unparsableLinkIsShownAsIs() {
        assertEquals("not a url", LinkSheet.display("not a url"))
    }
}

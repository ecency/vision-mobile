package com.ecency.core.render.methods

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Bylines the native port drops ahead of render-helper (vision-web #2003). The generated goldens cover the rest;
 * move these there once the render-helper release has them.
 */
class CleanReplyBylineTest {
    private fun clean(s: String) = CleanReply.cleanReply(s)

    @Test
    fun theNewHiveSuiteBylineIsDropped() {
        assertEquals("Body\n", clean("Body\n\n<sub>[via HiveSuite](https://hivesuite.app/@a/p)</sub>"))
        assertEquals("Body\n", clean("Body\n\n  <SUB>[via hivesuite](https://hivesuite.app/@a/p)</SUB>  "))
    }

    @Test
    fun theOldHiveSuiteBylineIsStillDropped() {
        assertEquals("Body\n", clean("Body\n\n<sub>Posted via [HiveSuite](https://hivesuite.app/@a/p)</sub>"))
    }

    @Test
    fun anAuthorsOwnMentionOfHiveSuiteStays() {
        val body = "I wrote this via HiveSuite today"
        assertEquals(body, clean(body))
    }

    @Test
    fun aLineQuotingTheBylineMarkupStays() {
        // only a whole line that is the byline goes, never prose around it or a link elsewhere
        val quoted = "HiveSuite now ends posts with <sub>[via HiveSuite](https://hivesuite.app/@a/p)</sub> as a footer"
        assertEquals(quoted, clean(quoted))
        val elsewhere = "<sub>[via HiveSuite](https://example.com/@a/p)</sub>"
        assertEquals(elsewhere, clean(elsewhere))
    }
}

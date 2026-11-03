package com.ecency.core.render.methods

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Bylines the native port drops ahead of render-helper (vision-web #2003). The generated goldens cover the rest;
 * move these there once the render-helper release has them.
 */
class CleanReplyBylineTest {
    @Test
    fun theNewHiveSuiteBylineIsDropped() {
        assertEquals("Body\n", CleanReply.cleanReply("Body\n\n<sub>[via HiveSuite](https://hivesuite.app/@a/p)</sub>"))
    }

    @Test
    fun theOldHiveSuiteBylineIsStillDropped() {
        assertEquals("Body\n", CleanReply.cleanReply("Body\n\n<sub>Posted via [HiveSuite](https://hivesuite.app/@a/p)</sub>"))
    }

    @Test
    fun anAuthorsOwnMentionOfHiveSuiteStays() {
        val body = "I wrote this via HiveSuite today"
        assertEquals(body, CleanReply.cleanReply(body))
    }
}

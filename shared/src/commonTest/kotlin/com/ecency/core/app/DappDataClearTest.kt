package com.ecency.core.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What a dApp stored in the Explore browser belongs to the account that browsed: every account change clears it. */
class DappDataClearTest {
    @Test fun accountSwitchLogoutAndWipeClearDappData() {
        assertTrue(AppEvent.SessionChanged("alice").clearsDappData)
        assertTrue(AppEvent.SessionChanged(null).clearsDappData)
        assertTrue(AppEvent.DataWiped.clearsDappData)
    }

    @Test fun otherEventsKeepDappData() {
        assertFalse(AppEvent.MutesLoaded.clearsDappData)
        assertFalse(AppEvent.Backgrounded.clearsDappData)
        assertFalse(AppEvent.ProfileUpdated("alice").clearsDappData)
    }
}

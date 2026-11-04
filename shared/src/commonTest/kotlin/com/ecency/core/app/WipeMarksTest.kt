package com.ecency.core.app

import com.ecency.core.api.FakeTransport
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/** The app's PIN wipe voids session renewals in flight before and after it (RN withAuthRemoval). */
class WipeMarksTest {
    @Test
    fun theAppsPinWipeMarksTheAccountsGoneBeforeAndAfter() = runTest {
        val app = TestApp(this) { FakeTransport.result(it, "1") }
        val before = app.env.accounts.removalMark("anyone")
        app.env.pinLock.wipeAll()
        val afterWipe = app.env.accounts.removalMark("anyone")
        assertTrue(afterWipe != before, "marked by the wipe itself")
        app.env.resetAfterWipe()
        assertTrue(app.env.accounts.removalMark("anyone") != afterWipe, "and again after it")
    }
}

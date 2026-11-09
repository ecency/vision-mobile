package com.ecency.core.secure

import platform.Foundation.NSUUID
import platform.Foundation.NSUserDefaults
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The store's refusal while the launch check waits for the first unlock is the one ErrorReports reads as expected. */
class LockedLaunchRefusalTest {
    @Test
    fun beforeTheFirstUnlockTheStoreRefusesUntilUnlock() {
        // a service of its own: the pending check is kept per service for the whole process
        val store = KeychainSecureStore("test.locked." + NSUUID().UUIDString)
        store.wipeIfFreshInstall(NSUserDefaults(suiteName = "test.locked.defaults")) { false }
        val e = assertFailsWith<SecureStoreException.Unavailable> { store.get("account.someone") }
        assertTrue(e.untilUnlock)
    }
}

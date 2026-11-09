package com.ecency.core.secure

import platform.Security.errSecInteractionNotAllowed
import kotlin.test.Test
import kotlin.test.assertEquals

/** The status the Keychain store throws before the first unlock is the one ErrorReports reads as "phone locked". */
class DeviceLockedStatusTest {
    @Test
    fun theKeychainsLockedStatusIsDeviceLocked() {
        assertEquals(errSecInteractionNotAllowed, SecureStoreException.DEVICE_LOCKED)
    }
}

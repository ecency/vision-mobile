package com.ecency.core.legacy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LegacyCleanupTest {
    @Test
    fun removesOnlyTheSecrets() {
        val rn = MapLegacyStorage(
            mapOf(
                "persist:root" to LegacyFixtures.PERSIST_ROOT,
                "user" to LegacyFixtures.USERS,
                "auth" to "{}",
                "sc_accounts" to "[]",
                "draft" to "keep me",
            ),
        )
        assertTrue(LegacyCleanup.hasSecrets(rn))
        assertTrue(LegacyCleanup.scrub(rn))
        assertEquals(setOf("persist:root", "draft"), rn.values.keys)
        val root = rn.read("persist:root")!!
        assertFalse(root.contains("encUnlockPin"))
        assertFalse(root.contains("\\\"pin\\\""))
        assertFalse(root.contains("local"))
        assertTrue(root.contains("isPinCodeOpen") && root.contains("spike-master") && root.contains("_persist"))
        // The scrubbed root still imports cleanly as "nothing left".
        assertFalse(LegacyCleanup.hasSecrets(rn))
        assertFalse(LegacyCleanup.scrub(rn))
    }

    @Test
    fun anUnparsableRootIsRemovedWhole() {
        val rn = MapLegacyStorage(mapOf("persist:root" to "{broken", "draft" to "x"))
        assertTrue(LegacyCleanup.scrub(rn))
        assertNull(rn.read("persist:root"))
        assertEquals("x", rn.read("draft"))
    }

    @Test
    fun nothingToDoOnEmptyStorage() {
        assertFalse(LegacyCleanup.hasSecrets(MapLegacyStorage()))
        assertFalse(LegacyCleanup.scrub(MapLegacyStorage()))
    }

    /** Storage with stale RN copies (iOS Documents folders): they count as secrets and go first. */
    private class WithStale(val live: MapLegacyStorage, var stale: Boolean) : LegacyStorage by live {
        override fun hasStaleCopies() = stale
        override fun removeStaleCopies() { stale = false }
    }

    @Test
    fun staleCopiesAreSecretsAndAreRemoved() {
        val storage = WithStale(MapLegacyStorage(mapOf("draft" to "keep")), stale = true)
        assertTrue(LegacyCleanup.hasSecrets(storage))
        assertTrue(LegacyCleanup.scrub(storage))
        assertFalse(storage.stale)
        assertEquals("keep", storage.read("draft"))
        assertFalse(LegacyCleanup.hasSecrets(storage))
        assertFalse(LegacyCleanup.scrub(storage))
    }
}

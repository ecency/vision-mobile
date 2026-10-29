package com.ecency.core.legacy

/**
 * The RN app's AsyncStorage (Android `AsyncStorageReader` over RKStorage, iOS over RCTAsyncLocalStorage_V1).
 *
 * [read] returns null only when the key is provably absent (no storage at all, or no such key). Storage
 * that exists but cannot be read right now (I/O error, iOS data protection before first unlock, malformed
 * manifest) throws [LegacyImportError.StorageUnreadable], so a migration never mistakes it for "no data".
 */
interface LegacyStorage {
    @Throws(LegacyImportError::class)
    fun read(key: String): String?

    @Throws(LegacyImportError::class)
    fun write(key: String, value: String)

    /** Removing an absent key is a no-op. */
    @Throws(LegacyImportError::class)
    fun remove(key: String)

    /**
     * Older copies of the RN storage that RN left behind and never reads again (iOS: the Documents folders RN copied
     * into Application Support without deleting them). They hold old RN secrets too, so [LegacyCleanup] deletes them.
     */
    @Throws(LegacyImportError::class)
    fun hasStaleCopies(): Boolean = false

    @Throws(LegacyImportError::class)
    fun removeStaleCopies() {}
}

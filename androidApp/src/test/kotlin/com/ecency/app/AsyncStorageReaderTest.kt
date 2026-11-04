package com.ecency.app

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.ecency.core.legacy.AsyncStorageReader
import com.ecency.core.legacy.LegacyCleanup
import com.ecency.core.legacy.LegacyImportError
import com.ecency.core.legacy.LegacyMigration
import com.ecency.core.secure.KeyVault
import com.ecency.core.secure.SecureStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AsyncStorageReaderTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** Same schema as react-native-async-storage's ReactDatabaseSupplier. */
    private fun seed(values: Map<String, String>) {
        val file = context.getDatabasePath("RKStorage").apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE catalystLocalStorage (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            values.forEach { (k, v) ->
                db.insert("catalystLocalStorage", null, ContentValues().apply { put("key", k); put("value", v) })
            }
        }
    }

    @Test
    fun readsValuesWrittenByTheRnApp() {
        seed(mapOf("user" to "[]", "persist:root" to "x".repeat(50_000)))
        val reader = AsyncStorageReader(context)
        assertEquals("[]", reader.read("user"))
        assertEquals(50_000, reader.read("persist:root")?.length)
        assertNull(reader.read("missing"))
    }

    @Test
    fun noRnDatabaseReadsAsEmpty() {
        assertNull(AsyncStorageReader(context).read("user"))
        AsyncStorageReader(context).remove("user")
        assertThrows(LegacyImportError.StorageUnreadable::class.java) { AsyncStorageReader(context).write("user", "[]") }
    }

    @Test
    fun writesAndRemovesForTheCleanup() {
        seed(mapOf("user" to "[]", "auth" to "{}", "draft" to "keep"))
        val storage = AsyncStorageReader(context)
        storage.write("persist:root", "{\"a\":1}")
        storage.write("draft", "kept")
        storage.remove("user")
        storage.remove("never-there")
        assertNull(storage.read("user"))
        assertEquals("{\"a\":1}", storage.read("persist:root"))
        assertEquals("kept", storage.read("draft"))
        assertTrue(LegacyCleanup.scrub(storage))
        assertNull(storage.read("auth"))
        assertEquals("kept", storage.read("draft"))
    }

    /** react-native-async-storage 2.2.0's ReactDatabaseSupplier: an SQLiteOpenHelper named RKStorage, version 1. */
    private class RnDatabaseSupplier(context: android.content.Context) :
        android.database.sqlite.SQLiteOpenHelper(context, "RKStorage", null, 1) {
        override fun onCreate(db: SQLiteDatabase) =
            db.execSQL("CREATE TABLE catalystLocalStorage (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    /**
     * What an RN 3.5.11 install leaves: the database the RN module itself creates (Android 9+ opens it with write-ahead
     * logging, and the RN process may die before a checkpoint, so rows can still sit in RKStorage-wal), RN's keys
     * ("persist:root" as redux-persist's JSON of JSON strings, "user", the query cache well over a cursor row of
     * small values). The RN app's database stays open while the native reader reads and scrubs it.
     */
    @Test
    fun readsAndScrubsTheDatabaseTheRnModuleWrites() {
        val rn = RnDatabaseSupplier(context)
        val db = rn.writableDatabase
        db.enableWriteAheadLogging()
        val root = "{\"application\":\"{\\\"pin\\\":\\\"c2VjcmV0\\\",\\\"encUnlockPin\\\":\\\"c2VjcmV0\\\",\\\"language\\\":\\\"en-US\\\"}\"," +
            "\"account\":\"{\\\"currentAccount\\\":{\\\"name\\\":\\\"alice\\\",\\\"local\\\":{\\\"postingKey\\\":\\\"enc\\\"}}," +
            "\\\"otherAccounts\\\":[{\\\"name\\\":\\\"bob\\\",\\\"local\\\":{\\\"postingKey\\\":\\\"enc\\\"}}]}\"," +
            "\"_persist\":\"{\\\"version\\\":22,\\\"rehydrated\\\":true}\"}"
        val cache = "{\"clientState\":{\"queries\":[]},\"pad\":\"" + "q".repeat(300_000) + "\"}"
        mapOf("persist:root" to root, "user" to "[{\"username\":\"alice\"}]", "sc_accounts" to "[]", "auth" to "{}",
            "REACT_QUERY_OFFLINE_CACHE" to cache, "locale_auto_detected" to "true").forEach { (k, v) ->
            db.execSQL("INSERT OR REPLACE INTO catalystLocalStorage VALUES (?, ?)", arrayOf(k, v))
        }
        val storage = AsyncStorageReader(context)
        assertEquals(root, storage.read("persist:root"))
        assertEquals(cache, storage.read("REACT_QUERY_OFFLINE_CACHE"))
        assertTrue(LegacyCleanup.scrub(storage))
        for (secret in listOf("user", "sc_accounts", "auth")) assertNull(storage.read(secret))
        val scrubbed = storage.read("persist:root")!!
        assertFalse(scrubbed.contains("encUnlockPin") || scrubbed.contains("\\\"pin\\\"") || scrubbed.contains("postingKey"))
        assertTrue(scrubbed.contains("en-US") && scrubbed.contains("alice") && scrubbed.contains("bob"))
        assertEquals("true", storage.read("locale_auto_detected"))
        // the RN module's own connection sees the cleanup too
        db.rawQuery("SELECT COUNT(*) FROM catalystLocalStorage WHERE key IN ('user', 'sc_accounts', 'auth')", null).use {
            it.moveToFirst()
            assertEquals(0, it.getInt(0))
        }
        rn.close()
    }

    /** A database that exists but cannot be read is not "no RN data". */
    @Test
    fun anUnreadableDatabaseIsTypedAndNeverMarkedDone() {
        context.getDatabasePath("RKStorage").apply { parentFile?.mkdirs() }.writeText("not a sqlite database")
        val storage = AsyncStorageReader(context)
        assertThrows(LegacyImportError.StorageUnreadable::class.java) { storage.read("user") }
        val vault = KeyVault(MapStore())
        assertThrows(LegacyImportError.StorageUnreadable::class.java) {
            LegacyMigration.run(vault, storage, "dummy-pin-key", "0000")
        }
        assertFalse(vault.legacyImported())
    }

    /** Minimal in-memory SecureStore: Robolectric has no AndroidKeyStore. */
    private class MapStore : SecureStore {
        private val map = mutableMapOf<String, ByteArray>()
        override fun put(name: String, value: ByteArray) { map[name] = value }
        override fun get(name: String) = map[name]
        override fun delete(name: String) { map.remove(name) }
        override fun names() = map.keys.toSet()
        override fun wipe() = map.clear()
        override fun <T> withLock(block: () -> T): T = block()
    }
}

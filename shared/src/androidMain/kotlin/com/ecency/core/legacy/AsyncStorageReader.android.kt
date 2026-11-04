package com.ecency.core.legacy

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * The RN app's AsyncStorage on Android: SQLite database "RKStorage", table catalystLocalStorage.
 * Reads for the import, writes and removes only for [LegacyCleanup]. No database file means no RN data;
 * a database that exists but cannot be opened or queried is [LegacyImportError.StorageUnreadable].
 */
class AsyncStorageReader(private val database: File) : LegacyStorage {
    constructor(context: Context) : this(context.getDatabasePath("RKStorage"))

    override fun read(key: String): String? {
        if (!database.exists()) return null
        return open(SQLiteDatabase.OPEN_READONLY) { db ->
            db.rawQuery("SELECT value FROM $TABLE WHERE key = ?", arrayOf(key)).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }
    }

    override fun write(key: String, value: String) {
        if (!database.exists()) throw LegacyImportError.StorageUnreadable()
        open(SQLiteDatabase.OPEN_READWRITE) { db ->
            val row = ContentValues().apply {
                put("key", key)
                put("value", value)
            }
            if (db.insertWithOnConflict(TABLE, null, row, SQLiteDatabase.CONFLICT_REPLACE) == -1L) {
                throw LegacyImportError.StorageUnreadable()
            }
        }
    }

    override fun remove(key: String) {
        if (!database.exists()) return
        open(SQLiteDatabase.OPEN_READWRITE) { db -> db.delete(TABLE, "key = ?", arrayOf(key)) }
    }

    private fun <T> open(flags: Int, block: (SQLiteDatabase) -> T): T = try {
        SQLiteDatabase.openDatabase(database.path, null, flags).use(block)
    } catch (e: LegacyImportError) {
        throw e
    } catch (e: Exception) {
        throw LegacyImportError.StorageUnreadable(e)
    }

    private companion object {
        const val TABLE = "catalystLocalStorage"
    }
}

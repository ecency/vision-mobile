package com.ecency.core.legacy

/** Test double for the RN app's AsyncStorage; [unreadable] simulates storage that exists but cannot be read. */
class MapLegacyStorage(initial: Map<String, String> = emptyMap()) : LegacyStorage {
    val values = initial.toMutableMap()
    var unreadable = false

    private fun check() {
        if (unreadable) throw LegacyImportError.StorageUnreadable()
    }

    override fun read(key: String): String? {
        check()
        return values[key]
    }

    override fun write(key: String, value: String) {
        check()
        values[key] = value
    }

    override fun remove(key: String) {
        check()
        values.remove(key)
    }
}

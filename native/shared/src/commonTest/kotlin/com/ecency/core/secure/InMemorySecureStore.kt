package com.ecency.core.secure

/** Test double: a [SecureStore] in a map, with switches to simulate platform failures. */
class InMemorySecureStore : SecureStore {
    val entries = linkedMapOf<String, ByteArray>()

    /** When set, every call fails as a store whose device key is gone. */
    var keyLost = false

    /** When set, listing entry names fails (the store is unavailable for that call only). */
    var namesUnavailable = false

    /** Entries whose read fails with the given error (one corrupted or unavailable entry among healthy ones). */
    val failingReads = mutableMapOf<String, SecureStoreException>()

    /** Number of successful writes, to prove a run did (not) touch the store. */
    var writes = 0
        private set

    private fun checkKey() {
        if (keyLost) throw SecureStoreException.KeyInvalidated()
    }

    /** Runs after every successful write (a test fails the next call at an exact point). */
    var afterPut: ((String) -> Unit)? = null

    override fun put(name: String, value: ByteArray) {
        SecureStore.requireValidName(name)
        checkKey()
        entries[name] = value.copyOf()
        writes++
        afterPut?.invoke(name)
    }

    override fun get(name: String): ByteArray? {
        SecureStore.requireValidName(name)
        checkKey()
        failingReads[name]?.let { throw it }
        return entries[name]?.copyOf()
    }

    override fun delete(name: String) {
        SecureStore.requireValidName(name)
        entries.remove(name)
    }

    override fun names(): Set<String> {
        if (namesUnavailable) throw SecureStoreException.Unavailable(-1)
        return entries.keys.toSet()
    }

    override fun <T> withLock(block: () -> T): T = block()

    /** When set, [wipe] fails and removes nothing (the store refuses the write). */
    var wipeUnavailable = false

    override fun wipe() {
        if (wipeUnavailable) throw SecureStoreException.Unavailable(-2)
        entries.clear()
        keyLost = false
    }
}

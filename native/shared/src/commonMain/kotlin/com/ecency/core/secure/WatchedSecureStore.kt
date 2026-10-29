package com.ecency.core.secure

/**
 * A [SecureStore] that reports a lost device key: every call of [inner] that fails with
 * [SecureStoreException.KeyInvalidated] calls [onKeyInvalidated] (on the caller's thread, possibly more than
 * once) and then fails as before. No other failure is reported: [SecureStoreException.Unavailable] may pass
 * and must never lead to a wipe. The app asks the user to sign in again ([com.ecency.core.app.StorageResetModel]).
 */
internal class WatchedSecureStore(private val inner: SecureStore, private val onKeyInvalidated: () -> Unit) : SecureStore {
    private inline fun <T> watched(block: () -> T): T = try {
        block()
    } catch (e: SecureStoreException.KeyInvalidated) {
        onKeyInvalidated()
        throw e
    }

    override fun put(name: String, value: ByteArray) = watched { inner.put(name, value) }

    override fun get(name: String): ByteArray? = watched { inner.get(name) }

    override fun delete(name: String) = watched { inner.delete(name) }

    override fun names(): Set<String> = watched { inner.names() }

    override fun wipe() = watched { inner.wipe() }

    override fun <T> withLock(block: () -> T): T = inner.withLock(block)
}

package com.ecency.core.secure

/**
 * Device-bound storage for small secrets (keys, tokens), addressed by an entry name.
 *
 * Implementations: [AndroidKeystoreSecureStore] (AES-256-GCM under a non-exportable Android Keystore key)
 * and `KeychainSecureStore` on iOS (generic passwords, this device only, after first unlock).
 * Values are opaque bytes; [putString] and [getString] store UTF-8 text.
 *
 * Entry names are 1 to 128 characters of `A-Z a-z 0-9 . _ -`, never secrets themselves.
 *
 * Every failure is a [SecureStoreException], including invalid arguments, so Swift callers (which only
 * see the declared exception types) never crash on a Kotlin exception. Calls block on platform I/O: call
 * them off the main thread, or through [KeyVaultAsync].
 */
interface SecureStore {
    @Throws(SecureStoreException::class)
    fun put(name: String, value: ByteArray)

    /** The stored value, or null when there is none. */
    @Throws(SecureStoreException::class)
    fun get(name: String): ByteArray?

    @Throws(SecureStoreException::class)
    fun delete(name: String)

    /** Names of every stored entry. Never throws for unreadable values, only lists them. */
    @Throws(SecureStoreException::class)
    fun names(): Set<String>

    /**
     * Removes every entry and, where there is one, the device key protecting them. Destroys every secret:
     * call it on the user's explicit choice (sign out, or "sign in again" after [SecureStoreException.KeyInvalidated]),
     * never automatically on an error that might be transient.
     */
    @Throws(SecureStoreException::class)
    fun wipe()

    @Throws(SecureStoreException::class)
    fun putString(name: String, value: String) = put(name, value.encodeToByteArray())

    @Throws(SecureStoreException::class)
    fun getString(name: String): String? = get(name)?.let {
        runCatching { it.decodeToString(throwOnInvalidSequence = true) }
            .getOrElse { throw SecureStoreException.Corrupted(name) }
    }

    /**
     * Runs [block] holding this store's lock, which is shared by every instance over the same storage in
     * this process and is reentrant. Use it for read-modify-write sequences across several calls.
     */
    @Throws(SecureStoreException::class)
    fun <T> withLock(block: () -> T): T

    companion object {
        private val NAME = Regex("^[A-Za-z0-9._-]{1,128}$")

        fun isValidName(name: String) = NAME.matches(name)

        @Throws(SecureStoreException::class)
        fun requireValidName(name: String) {
            if (!isValidName(name)) throw SecureStoreException.InvalidArgument("invalid secure store entry name")
        }
    }
}

/** Typed failures, so callers can recover (sign the user in again) instead of crashing. */
sealed class SecureStoreException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /**
     * The device key that protected the stored values is gone or permanently invalidated, for example
     * after a restore onto another device or a Keystore reset. Every stored value is unrecoverable; offer
     * the user to sign in again and call [SecureStore.wipe] once they confirm.
     */
    class KeyInvalidated(cause: Throwable? = null) :
        SecureStoreException("device key for secure storage is gone or invalidated", cause)

    /**
     * One entry failed authentication or decoding. Its name is reported, never its value: the failure that caused it is
     * kept as its type only ([causeType]), never as a cause, because a parser's message quotes the text it read (vault
     * JSON with keys and tokens) and a cause's message travels with every stack trace.
     */
    class Corrupted(val name: String, cause: Throwable? = null) :
        SecureStoreException("secure store entry '$name' is unreadable" + (cause?.let { " (${typeOf(it)})" } ?: "")) {
        val causeType: String? = cause?.let(::typeOf)

        private companion object {
            fun typeOf(t: Throwable): String = t::class.simpleName ?: "Throwable"
        }
    }

    /**
     * The platform refused the operation (Keychain status, Keystore provider error, device locked).
     * Possibly transient: retry later, never wipe because of it. [untilUnlock]: the store itself refused because the
     * launch check waits for the first unlock (iOS, a launch before it); it opens again on the unlock. Its own text,
     * so a crash report of it never groups with a refusal of the platform.
     */
    class Unavailable(val status: Int, cause: Throwable? = null, val untilUnlock: Boolean = false) :
        SecureStoreException(
            if (untilUnlock) "secure storage closed until the device is unlocked (status $status)" else "secure storage unavailable (status $status)",
            cause,
        )

    /** A caller error: invalid entry name or username, unknown account. Nothing was changed. */
    class InvalidArgument(message: String) : SecureStoreException(message)
}

package com.ecency.core.secure

import kotlin.io.encoding.Base64

/**
 * A salted, slow hash of the PIN (PBKDF2-HMAC-SHA256), stored instead of the PIN. The iteration count is
 * stored with it, so it can be raised later without invalidating existing PINs. Never prints its contents.
 */
internal class PinVerifier(val iterations: Int, private val salt: ByteArray, private val hash: ByteArray) {

    /** Constant-time comparison of the derived hash. */
    fun matches(pin: String): Boolean {
        val derived = derive(pin, salt, iterations)
        return constantTimeEquals(derived, hash).also { derived.fill(0) }
    }

    fun encode(): String = "$SCHEME$$iterations$${Base64.encode(salt)}$${Base64.encode(hash)}"

    override fun toString(): String = "PinVerifier(<redacted>)"

    companion object {
        private const val SCHEME = "pbkdf2-sha256"
        private const val SALT_BYTES = 16
        private const val HASH_BYTES = 32

        fun create(pin: String, iterations: Int): PinVerifier {
            val salt = secureRandomBytes(SALT_BYTES)
            return PinVerifier(iterations, salt, derive(pin, salt, iterations))
        }

        /** Null for anything that is not a verifier this code wrote. */
        fun decode(encoded: String): PinVerifier? {
            val parts = encoded.split('$')
            if (parts.size != 4 || parts[0] != SCHEME) return null
            val iterations = parts[1].toIntOrNull()?.takeIf { it >= 1 } ?: return null
            val salt = runCatching { Base64.decode(parts[2]) }.getOrNull()?.takeIf { it.size >= SALT_BYTES } ?: return null
            val hash = runCatching { Base64.decode(parts[3]) }.getOrNull()?.takeIf { it.size == HASH_BYTES } ?: return null
            return PinVerifier(iterations, salt, hash)
        }

        private fun derive(pin: String, salt: ByteArray, iterations: Int): ByteArray {
            val password = pin.encodeToByteArray()
            return Pbkdf2.derive(password, salt, iterations, HASH_BYTES).also { password.fill(0) }
        }
    }
}

/**
 * Ties biometric unlock to the biometrics enrolled when it was turned on, so a fingerprint or face added
 * later never unlocks the app. Each app implements it; [bind] runs on the I/O dispatcher.
 */
fun interface BiometricBinder {
    /**
     * A token for the biometrics enrolled right now, null when none are. iOS: `LAContext`
     * `evaluatedPolicyDomainState` (base64). Android: creates a fresh Keystore key that a new enrollment
     * invalidates and returns a fixed marker (the key, checked at each unlock, is the real binding).
     */
    fun bind(): String?
}

/** What a PIN check did. */
sealed class PinCheck {
    /** Correct PIN (or biometric unlock): the failure counter is back at zero. */
    data object Success : PinCheck()

    /** Wrong PIN. [failures] in a row so far, [remaining] before everything is wiped. */
    data class Wrong(val failures: Int, val remaining: Int) : PinCheck()

    /** The last allowed attempt failed: every account, key and credential on the device was removed. */
    data object Wiped : PinCheck()

    /** No PIN is set; nothing was counted. */
    data object NoPin : PinCheck()

    /**
     * The biometrics enrolled on the device changed since biometric unlock was turned on: it was turned
     * off, nothing was unlocked or counted, and the PIN is needed.
     */
    data object BiometricChanged : PinCheck()
}

/**
 * The app lock: a 4-digit PIN that guards the app, not the keys (they stay protected by the device key of
 * the [SecureStore]; see native/README.md, "Keys are not PIN-encrypted again inside the vault").
 *
 * Like the RN app, [MAX_ATTEMPTS] wrong PINs in a row remove every account, key and credential from the
 * device. Unlike the RN app, which kept the count in component state (killing the app reset it), the count
 * lives in the secure store, survives restarts, is written BEFORE the PIN is checked (killing the app during
 * the check cannot undo an attempt) and is reset only by a correct PIN or a biometric unlock.
 *
 * Every call blocks on the store and, for checks, on the slow hash: call it off the main thread
 * ([com.ecency.core.app.AppLockModel] does).
 */
class PinLock(private val vault: KeyVault, private val iterations: Int = DEFAULT_ITERATIONS) {
    /** Runs at the start of every [wipeAll], before anything is removed (the app voids session renewals in flight). */
    var beforeWipe: () -> Unit = {}

    /**
     * Runs at the end of every [wipeAll], also after one that failed part way, once the vault is marked migrated
     * (the app deletes the RN copies of the secrets here). What it throws is ignored.
     */
    var afterWipe: () -> Unit = {}

    init {
        require(iterations >= 1) { "iterations must be positive" }
    }

    /**
     * True when a PIN is set. The verifier entry alone decides (fails closed): unreadable or reset meta never
     * turns the lock off, and a flag without a PIN never locks anyone out. A verifier entry that exists but is
     * unreadable or undecodable keeps the lock on: no PIN matches it, wrong PINs count as usual, and "forgot
     * PIN" (a wipe) is the way out, as with an unreadable counter.
     */
    @Throws(SecureStoreException::class)
    fun isEnabled(): Boolean = pin() != Pin.Absent

    @Throws(SecureStoreException::class)
    fun hasPin(): Boolean = pin() != Pin.Absent

    /** Biometric unlock is on and bound to the biometrics enrolled when it was turned on. */
    @Throws(SecureStoreException::class)
    fun isBiometricEnabled(): Boolean = vault.withLock {
        isEnabled() && vault.biometricUnlockEnabled() && vault.biometricBinding() != null
    }

    /**
     * Biometric unlock is on but not bound yet (carried over from the RN app, which had no binding). It is
     * not offered until a correct PIN binds it ([bindBiometric]), so a fingerprint added before then never
     * unlocks.
     */
    @Throws(SecureStoreException::class)
    fun biometricNeedsBinding(): Boolean = vault.withLock {
        isEnabled() && vault.biometricUnlockEnabled() && vault.biometricBinding() == null
    }

    /** Failed attempts in a row so far, as the next check will count them. */
    @Throws(SecureStoreException::class)
    fun failedAttempts(): Int = vault.withLock { if (pin() == Pin.Absent) 0 else storedFailures() }

    /**
     * Sets [pin] and turns the lock on. Use it for the first PIN only (enabling the lock); a PIN that is
     * already set changes through [changePin], which checks the old one.
     * @throws SecureStoreException.InvalidArgument for anything but [PIN_LENGTH] digits, or when a PIN is set.
     */
    @Throws(SecureStoreException::class)
    fun setPin(pin: String) {
        requireValidPin(pin)
        vault.withLock {
            if (pin() != Pin.Absent) throw SecureStoreException.InvalidArgument("a PIN is already set; change it instead")
            writePin(pin)
            vault.setPinLockEnabled(true)
        }
    }

    /** Checks [pin]: counts the attempt first, wipes everything on the [MAX_ATTEMPTS]th failure in a row. */
    @Throws(SecureStoreException::class)
    fun verify(pin: String): PinCheck = vault.withLock { check(pin) }

    /**
     * Replaces the PIN with [new], without checking the old one: only for the caller that just got
     * [PinCheck.Success] from [verify] for this change ([com.ecency.core.app.AppLockModel] holds a
     * "verified" flag instead of the old PIN). One hash instead of the two [changePin] costs.
     */
    @Throws(SecureStoreException::class)
    internal fun replaceVerifiedPin(new: String) {
        requireValidPin(new)
        vault.withLock {
            if (pin() !is Pin.Valid) throw SecureStoreException.InvalidArgument("no usable PIN is set")
            writePin(new)
        }
    }

    /** Checks [old] exactly like [verify] (a wrong one counts), then replaces it with [new] on success. */
    @Throws(SecureStoreException::class)
    fun changePin(old: String, new: String): PinCheck {
        requireValidPin(new)
        return vault.withLock {
            check(old).also { if (it == PinCheck.Success) writePin(new) }
        }
    }

    /** Checks [pin] like [verify]; on success turns the lock and biometric unlock off and forgets the PIN. */
    @Throws(SecureStoreException::class)
    fun disable(pin: String): PinCheck = vault.withLock {
        check(pin).also { if (it == PinCheck.Success) clear() }
    }

    /**
     * Checks [pin] like [verify]; on success turns biometric unlock on or off. Turning it on calls [bind]
     * only after the PIN was right, for a token of the biometrics enrolled now (see [BiometricBinder]); with
     * no token (nothing enrolled) it stays off.
     */
    @Throws(SecureStoreException::class)
    fun setBiometricEnabled(pin: String, enabled: Boolean, bind: () -> String?): PinCheck = vault.withLock {
        check(pin).also { result ->
            if (result != PinCheck.Success) return@also
            val binding = if (enabled) bind() else null
            vault.setBiometricBinding(binding)
            vault.setBiometricUnlockEnabled(binding != null)
        }
    }

    /** Binds a carried-over biometric setting ([biometricNeedsBinding]); call it right after a correct PIN. */
    @Throws(SecureStoreException::class)
    fun bindBiometric(binding: String) = vault.withLock {
        if (biometricNeedsBinding()) vault.setBiometricBinding(binding)
    }

    /**
     * Turns biometric unlock off without a PIN: it only removes a way in. For when the platform finds the
     * enrolled biometrics changed (Android: the Keystore key was invalidated).
     */
    @Throws(SecureStoreException::class)
    fun revokeBiometric() = vault.withLock {
        vault.setBiometricUnlockEnabled(false)
        vault.setBiometricBinding(null)
    }

    /**
     * The platform's biometric prompt succeeded with the enrolled biometrics described by [binding]. Unlocks
     * without a PIN and resets the counter, as a correct PIN does (the owner proved presence), but never while
     * the counter is already exhausted (then it wipes), and never when [binding] is not the one stored when
     * biometric unlock was turned on (enrollment changed): then biometric unlock goes off
     * ([PinCheck.BiometricChanged]).
     */
    @Throws(SecureStoreException::class)
    fun unlockWithBiometric(binding: String?): PinCheck = vault.withLock {
        val stored = vault.biometricBinding()
        val pin = pin()
        when {
            pin == Pin.Absent -> PinCheck.NoPin
            !vault.biometricUnlockEnabled() -> throw SecureStoreException.InvalidArgument("biometric unlock is off")
            storedFailures() >= MAX_ATTEMPTS -> wipeAll().let { PinCheck.Wiped }
            // a broken verifier never opens the app, by PIN or biometrics
            pin == Pin.Broken -> {
                revokeBiometric()
                PinCheck.BiometricChanged
            }
            stored == null || binding == null || !constantTimeEquals(stored.encodeToByteArray(), binding.encodeToByteArray()) -> {
                revokeBiometric()
                PinCheck.BiometricChanged
            }
            else -> {
                vault.setPinFailures(0)
                PinCheck.Success
            }
        }
    }

    /**
     * Turns the lock off without a PIN. Only for when no account is left on the device (the RN app's
     * logout of the last account does the same); never offer it as a way around the PIN.
     */
    @Throws(SecureStoreException::class)
    fun clearWithoutPin() = vault.withLock { clear() }

    /**
     * Removes every account, key, credential, the PIN and the counter ("forgot PIN" and the last failed
     * attempt). The vault is marked as migrated again so an RN copy of the data left on disk is never
     * imported back; [afterWipe] deletes that copy's secrets, and the migration's cleanup on the next run
     * deletes whatever it could not.
     */
    @Throws(SecureStoreException::class)
    fun wipeAll() = vault.withLock {
        runCatching { beforeWipe() }
        // The mark is written even when the wipe failed part way (prefs cleared, key delete failed): a
        // later migration must never import the RN accounts, or the RN PIN, again.
        val failure = runCatching { vault.wipe() }.exceptionOrNull()
        try {
            vault.markLegacyImported()
        } finally {
            runCatching { afterWipe() }
        }
        if (failure != null) throw failure
    }

    private fun check(pin: String): PinCheck {
        val stored = pin()
        if (stored == Pin.Absent) return PinCheck.NoPin
        val verifier = (stored as? Pin.Valid)?.verifier
        val before = storedFailures()
        if (before >= MAX_ATTEMPTS) {
            // a wipe that failed last time (store unavailable): finish it, never check another PIN
            wipeAll()
            return PinCheck.Wiped
        }
        val failures = before + 1
        vault.setPinFailures(failures) // counted before the check: killing the app now cannot undo it
        if (verifier != null && isValidPin(pin) && verifier.matches(pin)) {
            vault.setPinFailures(0)
            return PinCheck.Success
        }
        if (failures >= MAX_ATTEMPTS) {
            wipeAll()
            return PinCheck.Wiped
        }
        return PinCheck.Wrong(failures, MAX_ATTEMPTS - failures)
    }

    /**
     * The counter is written (as 0) before every verifier, so a missing or unreadable counter next to a
     * verifier means it was deleted or tampered with: then only the last attempt is left.
     */
    private fun storedFailures(): Int = vault.pinFailures() ?: (MAX_ATTEMPTS - 1)

    private fun writePin(pin: String) {
        val encoded = PinVerifier.create(pin, iterations).encode()
        vault.setPinFailures(0)
        vault.setPinVerifier(encoded)
    }

    private fun clear() {
        vault.setPinVerifier(null) // first: the verifier alone decides whether the lock is on
        vault.setPinFailures(null)
        vault.setPinLockEnabled(false)
        vault.setBiometricUnlockEnabled(false)
        vault.setBiometricBinding(null)
    }

    /** The stored PIN: none, a verifier, or an entry that exists but does not decode (lock stays on). */
    private sealed interface Pin {
        data object Absent : Pin
        data object Broken : Pin
        class Valid(val verifier: PinVerifier) : Pin
    }

    private fun pin(): Pin {
        val encoded = vault.pinVerifier() ?: return Pin.Absent
        return PinVerifier.decode(encoded)?.let { Pin.Valid(it) } ?: Pin.Broken
    }

    companion object {
        /** Wrong PINs in a row before everything is wiped (RN: pinCodeContainer `_handleFailedAttempt`). */
        const val MAX_ATTEMPTS = 3

        /** Digits in a PIN (RN: PinCodeView completes at 4). */
        const val PIN_LENGTH = 4

        /**
         * PBKDF2 rounds. A 4-digit PIN has only 10,000 values, so no round count stops an offline search of a
         * stolen verifier; the store's device key and the attempt counter are the real protection. The hash
         * only makes such a search cost real compute instead of none. 100,000 rounds take about 140 ms on a
         * desktop JVM; with the platform implementation ([Pbkdf2.derive]) phones take tens of milliseconds, off the main thread.
         */
        const val DEFAULT_ITERATIONS = 100_000

        fun isValidPin(pin: String): Boolean = pin.length == PIN_LENGTH && pin.all { it in '0'..'9' }

        @Throws(SecureStoreException::class)
        internal fun requireValidPin(pin: String) {
            if (!isValidPin(pin)) throw SecureStoreException.InvalidArgument("a PIN is $PIN_LENGTH digits")
        }
    }
}

package com.ecency.core.app

import com.ecency.core.l10n.L
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

data class StorageResetState(
    /**
     * The device key of the secure storage is gone, so nothing stored can be read or written again: the apps
     * show the blocking "sign in again" prompt, above everything else (also above the lock screen, whose PIN
     * went with the key).
     */
    val needed: Boolean = false,
    /** The user confirmed and the device's accounts are being removed. */
    val working: Boolean = false,
    /** The removal failed (the store refused): shown in the prompt, which stays for another try. */
    val error: String? = null,
)

/**
 * What the app does about [com.ecency.core.secure.SecureStoreException.KeyInvalidated] (Android: a restore
 * onto another device, a Keystore reset). The vault reports it from whichever call met it first
 * ([com.ecency.core.secure.KeyVault.onKeyInvalidated]); the prompt then blocks the app until the user
 * confirms, and only that confirmation wipes ([confirm]: the same path as "forgot PIN", after which the app is
 * signed out and shows the login screen). Nothing is wiped without it, and no other storage failure
 * (`Unavailable`, `Corrupted`) ever opens the prompt. One per app.
 */
class StorageResetModel internal constructor(private val env: AppEnvironment) : StateModel<StorageResetState>(StorageResetState(), env.dispatchers) {
    init {
        // called on the thread of the failing storage call: the state is changed on main
        env.vault.vault.onKeyInvalidated = { scope.launch { setState { if (it.working) it else it.copy(needed = true) } } }
    }

    /** The app's one model: screens do not own it. */
    override fun close() {}

    /** "Sign in again": removes every account, key and the PIN from the device, then tells the UIs (the wipe's events). */
    fun confirm() {
        val s = current
        if (!s.needed || s.working) return
        setState { it.copy(working = true, error = null) }
        scope.launch {
            try {
                env.appLock.wipeConfirmed()
                setState { StorageResetState() }
                env.emit(AppEvent.SignInAgain)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setState { it.copy(needed = true, working = false, error = FAILED) }
            }
        }
    }

    companion object {
        val TITLE: String get() = L.native.storageReset.title
        val MESSAGE: String get() = L.native.storageReset.message
        val CONFIRM: String get() = L.native.storageReset.title
        val FAILED: String get() = L.native.storageReset.failed
    }
}

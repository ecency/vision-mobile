package com.ecency.core.app

import com.ecency.core.l10n.L
import com.ecency.core.legacy.LegacyImportError
import com.ecency.core.legacy.LegacyMigration
import com.ecency.core.legacy.LegacyMigrationReport
import com.ecency.core.legacy.LegacyStorage
import com.ecency.core.secure.BiometricBinder
import com.ecency.core.secure.PinCheck
import com.ecency.core.secure.PinLock
import com.ecency.core.secure.monotonicMillis
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/** App lock settings: PIN hash cost, how long the app may sit in the background, the clock that measures it. */
class SecurityConfig(
    val pinIterations: Int = PinLock.DEFAULT_ITERATIONS,
    /** RN applicationContainer `_startPinCodeTimer`: 1 minute away locks the app. */
    val lockTimeoutMillis: Long = 60_000,
    /** A clock the user cannot set back (see [monotonicMillis]). */
    val monotonicMillis: () -> Long = ::monotonicMillis,
) {
    companion object {
        val DEFAULT = SecurityConfig()
    }
}

/**
 * The RN app's storage and build secrets for the one-time account import ([LegacyMigration]). The app lock
 * runs it at [AppLockModel.start], before the lock state is read, so the first launch after the update locks
 * with the user's RN PIN instead of opening unlocked.
 */
class LegacyMigrationSource(val storage: LegacyStorage, val pinKey: String, val defaultPin: String?) {
    override fun toString(): String = "LegacyMigrationSource(<redacted>)"
}

/** Why the PIN pad is showing. */
enum class PinPurpose {
    /** The app is locked (cold start, back after the timeout). Cannot be cancelled. */
    UNLOCK,

    /** Settings: turn the lock on by choosing a PIN. */
    ENABLE,

    /** Settings: turn the lock off (needs the PIN). */
    DISABLE,

    /** Settings: change the PIN (current, then new twice). */
    CHANGE,

    /** Settings: turn biometric unlock on or off (needs the PIN). */
    BIOMETRIC_ON,
    BIOMETRIC_OFF,

    /**
     * Confirm the PIN before something sensitive (show private keys, hand a key or a sign-in proof to another
     * app), RN's PINCODE screen with a callback. Counts towards the wipe like every other PIN check.
     */
    VERIFY,
}

/** Which PIN the pad asks for. */
enum class PinStep { CURRENT, NEW, CONFIRM }

/** The PIN pad. The digits themselves never leave the model; [digits] is how many were typed. */
data class PinPrompt(
    val purpose: PinPurpose,
    val step: PinStep,
    /** RN pincode texts: "Enter PIN to unlock", "Set new PIN", "Write again", plus the attempt count. */
    val message: String,
    val digits: Int = 0,
    /** The last entry was wrong or did not match. */
    val error: String? = null,
    /** Wrong PINs in a row (persisted, so it survives restarts). */
    val failedAttempts: Int = 0,
    /** A PIN is being checked; input is ignored meanwhile. */
    val isChecking: Boolean = false,
    /**
     * Start the biometric prompt by itself: true once per lock (cold start, relock), cleared by
     * [AppLockModel.consumeAutoBiometric] and by any later prompt, so a wrong PIN never re-fires it.
     */
    val autoBiometric: Boolean = false,
    /**
     * Turning the lock on with biometrics: the PIN set here backs biometric unlock, which is bound once the PIN is
     * confirmed ([AppLockModel.acceptBiometricOffer]).
     */
    val withBiometric: Boolean = false,
) {
    val canCancel: Boolean get() = purpose != PinPurpose.UNLOCK

    /** RN shows "Oh, I forgot it..." until the current PIN is verified. */
    val showForgot: Boolean get() = step == PinStep.CURRENT

    /** True once the next wrong PIN wipes the device's accounts. */
    val isLastAttempt: Boolean get() = failedAttempts >= PinLock.MAX_ATTEMPTS - 1
}

data class AppLockState(
    /** False until the lock settings were read once; keep app content hidden until then. */
    val loaded: Boolean = false,
    val enabled: Boolean = false,
    val biometricEnabled: Boolean = false,
    /** The account shown on the lock screen. */
    val username: String? = null,
    val prompt: PinPrompt? = null,
    /** One-off message for an alert (after a wipe, after a settings change). */
    val notice: String? = null,
    val error: AppError? = null,
    /** Any account is stored on the device (signed in or not): the lock applies while this is true. */
    val hasAccounts: Boolean = false,
    /**
     * Turning the lock on where biometrics are set up: the platform offers biometric unlock first, with "Use PIN
     * instead" ([AppLockModel.startEnable]). Shown over the app like the PIN pad; content stays visible (the lock
     * is still off).
     */
    val biometricOffer: Boolean = false,
) {
    val isLocked: Boolean get() = prompt?.purpose == PinPurpose.UNLOCK

    /** The pad that confirms the PIN before something sensitive (RN's PINCODE screen with a callback). */
    val isVerifying: Boolean get() = prompt?.purpose == PinPurpose.VERIFY && prompt.step == PinStep.CURRENT

    /** Hide everything (including from the app switcher snapshot) while true. */
    val hidesContent: Boolean get() = !loaded || isLocked

    /**
     * Whether the pad should offer the platform biometric prompt: on the lock screen and on the PIN confirmation
     * before something sensitive (RN's PINCODE screen runs `_processBiometric` for both), never in a settings flow.
     */
    val offersBiometric: Boolean get() = (isLocked || isVerifying) && biometricEnabled && prompt?.isChecking == false

    /**
     * What the PIN pad shows under its message: the entry's own error, else a storage failure of the last
     * check. The pad shows it in place of an alert, which cannot appear while the app is locked.
     */
    val promptError: String? get() = prompt?.let { it.error ?: error?.message }

    /** Whether the lock screen should start the biometric prompt by itself now (once per lock). */
    val autoStartsBiometric: Boolean get() = offersBiometric && prompt?.autoBiometric == true
}

/**
 * The PIN app lock for both UIs: locks at start and after [SecurityConfig.lockTimeoutMillis] in the
 * background, drives the PIN pad (unlock and the settings flows) and wipes on the last failed attempt
 * through [PinLock]. Every [PinLock] call runs on [AppDispatchers.io].
 */
class AppLockModel internal constructor(private val env: AppEnvironment) :
    StateModel<AppLockState>(AppLockState(), env.dispatchers) {

    private val lock get() = env.pinLock
    private val config get() = env.security

    /** Set by the app before [start]: ties biometric unlock to the enrolled biometrics (null: never bound). */
    var biometricBinder: BiometricBinder? = null

    private var entered = ""

    /** The current PIN was verified in this change flow. The PIN itself is never kept. */
    private var changeVerified = false
    private var newPin: String? = null
    private var backgroundedAt: Long? = null
    private var job: Job? = null

    /** The failure count as last read, so a relock can show the pad at once. */
    private var knownFailures = 0

    /** [start] ran or is running; a failed start clears it, so the lock cover's retry can run it again. */
    private var started = false

    /**
     * Runs the RN migration (when the app passed a [LegacyMigrationSource]) to completion, then reads the
     * settings and locks if the lock is on and any account is stored (cold start).
     *
     * Once per process: the environment outlives activities and scenes, so a UI that calls this again
     * (Android rotation or any activity recreation) changes nothing; an unlocked app stays unlocked and no
     * biometric prompt fires. After a failed start ([AppLockState.error] while not loaded) it runs again.
     */
    /** True once [start] ran: until then the lock has not decided anything. */
    internal val hasStarted: Boolean get() = everStarted

    /** True once [start] was called, even when that start failed closed (it then resets [started] to retry). */
    private var everStarted = false

    fun start() {
        if (started) return
        started = true
        everStarted = true
        scope.launch {
            try {
                val report = migrateLegacy()
                // entries of earlier builds lose their owner key and master password here, before anything else
                // reads the vault; a failure leaves them for the next start (they are never handed out meanwhile)
                val cleaned = runCatching { io { env.vault.vault.dropRetiredSecrets() } }.getOrDefault(emptyList())
                // accounts the cleaning or the RN import left without a posting and without an active key are told
                // so once (also those still waiting for their notice from an earlier start)
                val candidates = (cleaned + report?.imported.orEmpty() + env.accountNotices.pendingNoSigningKey()).distinct()
                // Three answers per account: read and keyless (told), read and fine or gone (forgotten), not readable
                // right now. A read that failed says nothing about the account, so its name stays on the pending
                // list and is judged again at the next start: a passing storage error never loses a notice.
                val judged: Map<String, Boolean?> = runCatching {
                    io { candidates.associateWith { name -> runCatching { env.vault.vault.account(name)?.isKeyAccountWithoutSigningKey == true }.getOrNull() } }
                }.getOrElse { candidates.associateWith { null } }
                env.accountNotices.noSigningKey(judged.filterValues { it == true }.keys.toList(), unread = judged.filterValues { it == null }.keys.toList())
                val snap = io { snapshot() }
                knownFailures = snap.failures
                setState {
                    it.copy(
                        loaded = true, enabled = snap.enabled, biometricEnabled = snap.biometric, username = snap.user,
                        hasAccounts = snap.hasAccounts,
                        prompt = if (snap.enabled && snap.hasAccounts) unlockPrompt(snap.failures, auto = true) else it.prompt,
                        notice = when {
                            report?.pinLockDropped == true -> PIN_DROPPED_NOTICE
                            importNotice != null -> importNotice.also { importNotice = null }
                            else -> it.notice
                        },
                    )
                }
                // the session re-reads the vault and emits the account change itself once that read is done
                if (report != null && report.imported.isNotEmpty()) env.session.startAfterImport()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Fail closed: an unreadable lock state keeps the content hidden, with the error shown.
                started = false
                setState { it.copy(error = AppError.from(e)) }
            }
        }
    }

    /** The app went to the background (Android onStop, iOS scene phase .background). */
    fun onBackground() {
        if (backgroundedAt == null) backgroundedAt = config.monotonicMillis()
        // screens that show secrets (the keys screen) close the moment the app leaves the foreground
        env.emit(AppEvent.Backgrounded)
    }

    /** The app is in front again: locks when it was away at least the timeout. */
    fun onForeground() {
        val since = backgroundedAt ?: return
        backgroundedAt = null
        val away = config.monotonicMillis() - since
        if (away < 0 || away >= config.lockTimeoutMillis) lockIfEnabled()
    }

    /**
     * Locks now when the lock is on and any account is stored. The lock prompt is set before this returns
     * (content hidden in the same frame); the failure count is refreshed from the store afterwards.
     */
    fun lockIfEnabled() {
        val s = current
        if (!s.loaded || !s.enabled || !s.hasAccounts || s.isLocked) return
        reset()
        setState { it.copy(prompt = unlockPrompt(knownFailures, auto = true)) }
        // a PIN confirmation in progress is abandoned: the caller gets false, never a stale success
        settleVerification(false)
        scope.launch {
            val failures = runCatching { io { lock.failedAttempts() } }.getOrNull() ?: return@launch
            knownFailures = failures
            setState { st ->
                val p = st.prompt
                if (p == null || p.purpose != PinPurpose.UNLOCK || p.isChecking || p.failedAttempts == failures) st
                else st.copy(prompt = p.copy(failedAttempts = failures, message = messageFor(PinStep.CURRENT, failures)))
            }
        }
    }

    /**
     * Turns the lock on. With [biometricAvailable] (a strong biometric is enrolled) biometric unlock is offered
     * first ([AppLockState.biometricOffer]); the user takes it ([acceptBiometricOffer], after the platform prompt
     * succeeded) or picks [usePinInstead]. Either way a PIN is set: it backs biometric unlock and keeps the
     * three-wrong-PINs wipe. Without biometrics the PIN setup opens at once.
     */
    fun startEnable(biometricAvailable: Boolean = false) {
        if (current.isLocked) return
        if (!biometricAvailable) return open(PinPurpose.ENABLE, PinStep.NEW)
        reset()
        // a PIN confirmation it replaces answers false, never hangs
        settleVerification(false)
        setState { if (it.isLocked) it else it.copy(prompt = null, biometricOffer = true) }
    }

    /** The biometric prompt of the offer succeeded: set the backing PIN, then biometric unlock is bound and on. */
    fun acceptBiometricOffer() = leaveOffer(withBiometric = true)

    /** "Use PIN instead": the PIN setup as without biometrics. */
    fun usePinInstead() = leaveOffer(withBiometric = false)

    /** The offer becomes the new-PIN pad in one state change (no moment with neither, which a watcher could take for a cancel). */
    private fun leaveOffer(withBiometric: Boolean) {
        if (!current.biometricOffer || current.isLocked) return
        reset()
        setState {
            it.copy(
                biometricOffer = false,
                prompt = PinPrompt(PinPurpose.ENABLE, PinStep.NEW, messageFor(PinStep.NEW, 0, withBiometric), withBiometric = withBiometric),
            )
        }
    }

    fun startDisable() = open(PinPurpose.DISABLE, PinStep.CURRENT)

    fun startChange() = open(PinPurpose.CHANGE, PinStep.CURRENT)

    fun startBiometric(enabled: Boolean) = open(if (enabled) PinPurpose.BIOMETRIC_ON else PinPurpose.BIOMETRIC_OFF, PinStep.CURRENT)

    private var verification: kotlinx.coroutines.CompletableDeferred<Boolean>? = null

    /**
     * Asks for the PIN (the [PinPurpose.VERIFY] pad) and returns true once it was entered correctly; false when
     * the user cancelled, a relock replaced the pad, or the data was wiped. With the lock off there is no PIN
     * to ask for: returns [whenOff]. Only one verification at a time; a second caller gets false.
     */
    @Throws(CancellationException::class)
    suspend fun verifyPin(whenOff: Boolean): Boolean {
        val enabled = runCatching { io { lock.isEnabled() } }.getOrDefault(true)
        if (!enabled) return whenOff
        if (verification?.isActive == true || current.isLocked) return false
        val pending = kotlinx.coroutines.CompletableDeferred<Boolean>()
        verification = pending
        open(PinPurpose.VERIFY, PinStep.CURRENT, autoBiometric = true)
        return try {
            pending.await()
        } finally {
            if (verification === pending) verification = null
            if (pending.isActive) pending.complete(false)
            setState { s -> if (s.prompt?.purpose == PinPurpose.VERIFY) s.copy(prompt = null) else s }
        }
    }

    private fun settleVerification(ok: Boolean) {
        verification?.complete(ok)
        verification = null
    }

    /** Closes a settings flow. The unlock prompt cannot be cancelled. */
    fun cancel() {
        if (current.biometricOffer) return setState { it.copy(biometricOffer = false) }
        if (current.prompt?.canCancel != true) return
        reset()
        // the pad showed the error (promptError); it goes with the pad instead of coming back as an alert
        setState { it.copy(prompt = null, error = null) }
        settleVerification(false)
    }

    fun enterDigit(digit: Int) {
        val prompt = current.prompt ?: return
        if (prompt.isChecking || digit !in 0..9 || entered.length >= PinLock.PIN_LENGTH) return
        entered += digit.toString()
        setState { it.copy(prompt = prompt.copy(digits = entered.length)) }
        if (entered.length == PinLock.PIN_LENGTH) submit(prompt, entered.also { entered = "" })
    }

    fun deleteDigit() {
        val prompt = current.prompt ?: return
        if (prompt.isChecking || entered.isEmpty()) return
        entered = entered.dropLast(1)
        setState { it.copy(prompt = prompt.copy(digits = entered.length)) }
    }

    /** The lock screen started the biometric prompt by itself; it does not again in this lock. */
    fun consumeAutoBiometric() = setState { s -> s.copy(prompt = s.prompt?.copy(autoBiometric = false)) }

    /**
     * The platform biometric prompt succeeded on the lock screen. [binding] describes the biometrics enrolled
     * now (see [BiometricBinder]); a different one than at enable turns biometric unlock off instead.
     */
    fun biometricSucceeded(binding: String?) {
        val prompt = current.prompt ?: return
        val verifying = prompt.purpose == PinPurpose.VERIFY && prompt.step == PinStep.CURRENT
        if ((prompt.purpose != PinPurpose.UNLOCK && !verifying) || prompt.isChecking) return
        run(prompt) { handleCheck(prompt, io { lock.unlockWithBiometric(binding) }) }
    }

    /**
     * The platform found the enrolled biometrics changed before prompting (Android: the Keystore key was
     * invalidated): biometric unlock goes off and the PIN is needed.
     */
    fun biometricInvalidated() {
        scope.launch {
            try {
                io { lock.revokeBiometric() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setState { it.copy(error = AppError.from(e)) }
            }
            setState { s ->
                s.copy(biometricEnabled = false, notice = BIOMETRIC_CHANGED_NOTICE, prompt = s.prompt?.copy(autoBiometric = false))
            }
        }
    }

    /** "Oh, I forgot it...", after the user confirmed: removes every account from the device. */
    fun forgotPin() {
        val prompt = current.prompt ?: return
        if (!prompt.showForgot || prompt.isChecking) return
        run(prompt) {
            io { lock.wipeAll() }
            wiped()
        }
    }

    /**
     * The user confirmed "sign in again" after the device key was lost ([StorageResetModel]): the same wipe as
     * [forgotPin], whatever the lock shows (its PIN went with the key). Throws when the store refused.
     */
    internal suspend fun wipeConfirmed() {
        io { lock.wipeAll() }
        wiped()
    }

    fun clearNotice() = setState { it.copy(notice = null) }

    fun clearError() = setState { it.copy(error = null) }

    /** Re-reads the settings (after sign-in or sign-out). Keeps a showing prompt. */
    fun refresh() {
        scope.launch {
            try {
                val snap = io { snapshot() }
                setState {
                    it.copy(
                        loaded = true, enabled = snap.enabled, biometricEnabled = snap.biometric, username = snap.user,
                        hasAccounts = snap.hasAccounts,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setState { it.copy(error = AppError.from(e)) }
            }
        }
    }

    /** After the vault was wiped, by [PinLock] or by a session reset. */
    internal fun onWiped() {
        reset()
        knownFailures = 0
        setState {
            AppLockState(loaded = true, notice = WIPED_NOTICE)
        }
    }

    private class Snapshot(val enabled: Boolean, val biometric: Boolean, val failures: Int, val user: String?, val hasAccounts: Boolean)

    private fun snapshot(): Snapshot {
        val vault = env.vault.vault
        return Snapshot(
            enabled = lock.isEnabled(),
            biometric = lock.isBiometricEnabled(),
            failures = lock.failedAttempts(),
            user = vault.currentUsername(),
            // any stored account (also unreadable ones) keeps the lock on: keys on the device are what it guards
            hasAccounts = vault.usernames().isNotEmpty(),
        )
    }

    /**
     * RN storage that cannot be read now is retried on the next launch; vault failures propagate (fail closed).
     * A failure a retry cannot fix ([LegacyImportError.PinUnavailable], [LegacyImportError.Malformed]) is counted in
     * the prefs and reported ([AppEvent.LegacyImportFailed]); the first one tells the user once ([IMPORT_FAILED_NOTICE])
     * and after [IMPORT_ATTEMPTS] of them it is no longer counted or reported. Nothing is marked done or deleted: the
     * RN data stays for a later build that can open it (each launch still tries, silently).
     */
    private suspend fun migrateLegacy(): LegacyMigrationReport? {
        val source = env.legacy ?: return null
        return io {
            try {
                LegacyMigration.run(env.vault.vault, source.storage, source.pinKey, source.defaultPin, config.pinIterations)
            } catch (e: LegacyImportError) {
                if (e is LegacyImportError.PinUnavailable || e is LegacyImportError.Malformed) importFailed(e)
                null
            }
        }
    }

    /** Set by [importFailed] for the start that shows it. */
    private var importNotice: String? = null

    private fun importFailed(e: LegacyImportError) {
        val prefs = env.prefs
        val before = prefs.getString(IMPORT_FAILURES)?.toIntOrNull() ?: 0
        if (before >= IMPORT_ATTEMPTS) return
        val attempts = before + 1
        prefs.putString(IMPORT_FAILURES, attempts.toString())
        val kind = e::class.simpleName ?: "LegacyImportError"
        prefs.putString(IMPORT_FAILURE_KIND, kind)
        if (prefs.getString(IMPORT_NOTICE_SHOWN) != "true") {
            prefs.putString(IMPORT_NOTICE_SHOWN, "true")
            importNotice = IMPORT_FAILED_NOTICE
        }
        val abandoned = attempts >= IMPORT_ATTEMPTS
        // kind and count only, nothing secret: the platform log and a crash reporter breadcrumb (sent with the next report)
        appLogWarning("RN import failed: $kind, attempt $attempts of $IMPORT_ATTEMPTS" + if (abandoned) ", no longer reported, RN data kept" else "")
        runCatching {
            env.crashReporter.breadcrumb(
                "legacy", "RN import failed",
                mapOf("kind" to kind, "attempts" to attempts.toString(), "abandoned" to abandoned.toString()),
            )
        }
        env.emit(AppEvent.LegacyImportFailed(kind, attempts, abandoned))
    }

    private fun open(purpose: PinPurpose, step: PinStep, autoBiometric: Boolean = false, withBiometric: Boolean = false) {
        if (current.isLocked) return
        reset()
        // another pad replaces a PIN confirmation: its caller gets false instead of waiting forever
        if (purpose != PinPurpose.VERIFY) settleVerification(false)
        scope.launch {
            val failures = if (step == PinStep.CURRENT) runCatching { io { lock.failedAttempts() } }.getOrDefault(0) else 0
            setState {
                if (it.isLocked) it
                else it.copy(
                    prompt = PinPrompt(
                        purpose, step, messageFor(step, failures, withBiometric), failedAttempts = failures,
                        autoBiometric = autoBiometric, withBiometric = withBiometric,
                    ),
                )
            }
        }
    }

    private fun submit(prompt: PinPrompt, pin: String) {
        when (prompt.step) {
            PinStep.CURRENT -> run(prompt) {
                val result = io {
                    when (prompt.purpose) {
                        PinPurpose.UNLOCK -> lock.verify(pin).also { if (it == PinCheck.Success) bindPendingBiometric() }
                        PinPurpose.CHANGE, PinPurpose.VERIFY -> lock.verify(pin)
                        PinPurpose.DISABLE -> lock.disable(pin)
                        PinPurpose.BIOMETRIC_ON -> lock.setBiometricEnabled(pin, true) { biometricBinder?.bind() }
                        PinPurpose.BIOMETRIC_OFF -> lock.setBiometricEnabled(pin, false) { null }
                        PinPurpose.ENABLE -> PinCheck.NoPin
                    }
                }
                handleCheck(prompt, result)
            }
            PinStep.NEW -> {
                newPin = pin
                replacePrompt(prompt, PinPrompt(prompt.purpose, PinStep.CONFIRM, WRITE_AGAIN, withBiometric = prompt.withBiometric))
            }
            PinStep.CONFIRM -> {
                val first = newPin
                if (first != pin) {
                    newPin = null
                    replacePrompt(
                        prompt,
                        PinPrompt(prompt.purpose, PinStep.NEW, messageFor(PinStep.NEW, 0, prompt.withBiometric), error = NOT_MATCHED, withBiometric = prompt.withBiometric),
                    )
                    return
                }
                run(prompt) {
                    when (prompt.purpose) {
                        PinPurpose.ENABLE -> {
                            io { lock.setPin(pin) }
                            if (!prompt.withBiometric) return@run finish(prompt, notice = L.native.appLock.pinLockIsOn)
                            // bound to the biometrics enrolled now, exactly as the settings toggle does it
                            // The PIN is set, so the lock is on whatever the bind does: a failing binder (Keystore,
                            // storage) leaves biometrics off with a notice, and finish() reads the lock state again.
                            val on = try {
                                io {
                                    lock.setBiometricEnabled(pin, true) { biometricBinder?.bind() }
                                    lock.isBiometricEnabled()
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                false
                            }
                            finish(prompt, notice = if (on) BIOMETRIC_ON_NOTICE else BIOMETRIC_UNAVAILABLE_NOTICE)
                        }
                        PinPurpose.CHANGE -> {
                            if (!changeVerified) return@run finish(prompt)
                            io { lock.replaceVerifiedPin(pin) }
                            finish(prompt, notice = L.native.appLock.pinChanged)
                        }
                        else -> finish(prompt)
                    }
                }
            }
        }
    }

    /** A carried-over biometric setting is bound right after a correct PIN (the owner is present). */
    private fun bindPendingBiometric() {
        val binder = biometricBinder ?: return
        runCatching { if (lock.biometricNeedsBinding()) binder.bind()?.let(lock::bindBiometric) }
    }

    private suspend fun handleCheck(prompt: PinPrompt, result: PinCheck) {
        when (result) {
            PinCheck.Success -> {
                knownFailures = 0
                when {
                    prompt.purpose == PinPurpose.CHANGE && prompt.step == PinStep.CURRENT -> {
                        changeVerified = true
                        replacePrompt(prompt, PinPrompt(PinPurpose.CHANGE, PinStep.NEW, SET_NEW))
                    }
                    prompt.purpose == PinPurpose.DISABLE -> finish(prompt, notice = L.native.appLock.pinLockIsOff)
                    prompt.purpose == PinPurpose.VERIFY -> {
                        val superseded = supersededByLock(current, prompt)
                        finish(prompt)
                        settleVerification(!superseded)
                    }
                    prompt.purpose == PinPurpose.BIOMETRIC_ON -> {
                        val on = io { lock.isBiometricEnabled() }
                        finish(prompt, notice = if (on) null else BIOMETRIC_UNAVAILABLE_NOTICE)
                    }
                    else -> finish(prompt)
                }
            }
            is PinCheck.Wrong -> {
                knownFailures = result.failures
                changeVerified = false
                val message = messageFor(PinStep.CURRENT, result.failures)
                setState { s ->
                    if (supersededByLock(s, prompt)) {
                        // a relock came up meanwhile: keep it, only show the new count
                        val p = s.prompt!!
                        if (p.isChecking) s else s.copy(prompt = p.copy(failedAttempts = result.failures, message = message))
                    } else {
                        s.copy(
                            prompt = PinPrompt(
                                prompt.purpose, PinStep.CURRENT, message, error = INVALID_PIN, failedAttempts = result.failures,
                            ),
                        )
                    }
                }
            }
            PinCheck.Wiped -> wiped()
            PinCheck.NoPin -> finish(prompt)
            PinCheck.BiometricChanged -> setState { s ->
                s.copy(
                    biometricEnabled = false, notice = BIOMETRIC_CHANGED_NOTICE,
                    prompt = s.prompt?.copy(isChecking = false, digits = 0, autoBiometric = false),
                )
            }
        }
    }

    /**
     * Runs a check with the pad busy; storage failures land in [AppLockState.error] and the pad resets.
     * Never two checks at once: an entry made while another check is still running (a relock replaced its
     * pad) is dropped and the pad clears for the user to type again.
     */
    private fun run(prompt: PinPrompt, block: suspend () -> Unit) {
        if (job?.isActive == true) {
            setState { s -> s.copy(prompt = s.prompt?.copy(digits = 0)) }
            return
        }
        // a new check replaces the failure of the last one on the pad
        setState { it.copy(prompt = prompt.copy(isChecking = true, digits = PinLock.PIN_LENGTH, autoBiometric = false), error = null) }
        job = scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setState { s -> s.copy(prompt = s.prompt?.copy(isChecking = false, digits = 0), error = AppError.from(e)) }
            }
        }
    }

    /** True when a relock put up the lock prompt while [owner], a settings flow, was in flight. */
    private fun supersededByLock(s: AppLockState, owner: PinPrompt) = s.isLocked && owner.purpose != PinPurpose.UNLOCK

    /** Shows [next] unless a relock replaced [owner]'s flow meanwhile; a lock prompt is never replaced. */
    private fun replacePrompt(owner: PinPrompt, next: PinPrompt) =
        setState { s -> if (supersededByLock(s, owner)) s else s.copy(prompt = next) }

    private suspend fun finish(owner: PinPrompt, notice: String? = null) {
        val (enabled, biometric) = io { lock.isEnabled() to lock.isBiometricEnabled() }
        if (!supersededByLock(current, owner)) reset()
        setState { s ->
            val superseded = supersededByLock(s, owner)
            s.copy(
                prompt = if (superseded) s.prompt else null,
                // an error the pad showed goes with it
                error = if (superseded) s.error else null,
                enabled = enabled, biometricEnabled = biometric, notice = notice ?: s.notice,
            )
        }
    }

    private suspend fun wiped() {
        settleVerification(false)
        env.resetAfterWipe()
        onWiped()
    }

    private fun reset() {
        entered = ""
        changeVerified = false
        newPin = null
    }

    private suspend fun <T> io(block: () -> T): T = withContext(env.dispatchers.io) { block() }

    private fun unlockPrompt(failures: Int, auto: Boolean) = PinPrompt(
        PinPurpose.UNLOCK, PinStep.CURRENT, messageFor(PinStep.CURRENT, failures), failedAttempts = failures, autoBiometric = auto,
    )

    companion object {
        // RN en-US: pincode.enter_text, set_new, write_again, attempts_postfix, message_reset_warning,
        // pin_not_matched; alert.invalid_pincode.
        val ENTER: String get() = L.pincode.enterText
        val SET_NEW: String get() = L.pincode.setNew
        val WRITE_AGAIN: String get() = L.pincode.writeAgain
        val NOT_MATCHED: String get() = L.pincode.pinNotMatched
        val INVALID_PIN: String get() = L.alert.invalidPincode
        val RESET_WARNING: String get() = L.pincode.messageResetWarning
        val WIPED_NOTICE: String get() = L.native.appLock.wipedNotice
        val IMPORT_FAILED_NOTICE: String get() = L.native.appLock.weCouldNotBring
        /** Launches with a failed RN import that are counted and reported; the RN data is never deleted for it. */
        const val IMPORT_ATTEMPTS = 3
        internal const val IMPORT_FAILURES = "legacy.importFailures"
        internal const val IMPORT_FAILURE_KIND = "legacy.importFailureKind"
        internal const val IMPORT_NOTICE_SHOWN = "legacy.importNoticeShown"
        val PIN_DROPPED_NOTICE: String get() = L.native.appLock.yourPinLockCould
        val BIOMETRIC_CHANGED_NOTICE: String get() = L.native.appLock.biometricUnlockWasTurned
        val BIOMETRIC_UNAVAILABLE_NOTICE: String get() = L.native.appLock.biometricUnavailableNotice

        // turning the lock on with biometrics (deliberate addition over RN, which sets a PIN only)
        val SET_BACKUP: String get() = L.native.appLock.setBackup
        val BIOMETRIC_ON_NOTICE: String get() = L.native.appLock.biometricOnNotice
        val OFFER_TITLE: String get() = L.native.appLock.offerTitle
        val OFFER_BODY: String get() = L.native.appLock.offerBody
        val OFFER_USE: String get() = L.native.appLock.offerUse
        val OFFER_USE_PIN: String get() = L.native.appLock.offerUsePin

        /** RN `_handleFailedAttempt`: the attempt count, and the wipe warning from the 2nd failure on. */
        fun messageFor(step: PinStep, failures: Int, withBiometric: Boolean = false): String = when (step) {
            PinStep.NEW -> if (withBiometric) SET_BACKUP else SET_NEW
            PinStep.CONFIRM -> WRITE_AGAIN
            PinStep.CURRENT -> buildString {
                append(ENTER)
                // RN: "<enter_text>, <attempts_postfix with the count>"
                if (failures > 0) append(", ").append(L.native.plurals.pincode.attemptsPostfix(count = failures))
                if (failures > 1) append("\n").append(RESET_WARNING)
            }
        }
    }
}

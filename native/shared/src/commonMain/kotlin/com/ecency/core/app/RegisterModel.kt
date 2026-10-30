package com.ecency.core.app

import com.ecency.core.api.ecency.SignupException
import com.ecency.core.l10n.L
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** RN en-US `register.*`, `free_account.*`, `buy_account.*` and the alerts of the register screen. */
object RegisterCopy {
    val TITLE: String get() = L.register.title
    val TITLE_DESCRIPTION: String get() = L.register.titleDescription
    val LOGIN: String get() = L.login.login
    val USERNAME: String get() = L.register.username
    val MAIL: String get() = L.register.mail
    val REF_USER: String get() = L.register.refUser
    val FORM_DESCRIPTION: String get() = L.register.formDescription
    val CONTINUE: String get() = L.alert.continue_
    val HAVE_ACCOUNT: String get() = L.register.haveAccountText
    val LOGIN_HERE: String get() = L.register.loginHere
    val USERNAME_EXISTS: String get() = L.register.validation.usernameExists
    val MODAL_TITLE: String get() = L.register.modalTitle
    val FREE_TITLE: String get() = L.freeAccount.title
    val FREE_DESC: String get() = L.freeAccount.desc
    val FREE_BUTTON: String get() = L.freeAccount.btnRegister
    val BUY_TITLE: String get() = L.buyAccount.title
    val BUY_DESC: String get() = L.buyAccount.desc

    val REGISTERING: String get() = L.register.registering
    val REGISTERED: String get() = L.register.registered
    val FAIL: String get() = L.alert.fail
    val ERROR: String get() = L.alert.error
    val UNKNOWN_ERROR: String get() = L.alert.unknowError
    val ERROR_500: String get() = L.register._500Error
    val CAPTCHA_LOAD_FAILED: String get() = L.dappBrowser.pageLoadFailed
    val RETRY: String get() = L.dappBrowser.retry

    /** RN `register.error_codes.<code>`; any other code shows the backend's own message. */
    val ERROR_CODES: Map<String, String> get() = mapOf(
        "13" to L.register.errorCodes._13,
        "14" to L.register.errorCodes._14,
        "15" to L.register.errorCodes._15,
        "16" to L.register.errorCodes._16,
        "17" to L.register.errorCodes._17,
        "19" to L.register.errorCodes._19,
        "21" to L.register.errorCodes._21,
        "28" to L.native.register.errorCode28,
        "107" to L.register.errorCodes._107,
        "110" to L.register.errorCodes._110,
        "111" to L.register.errorCodes._111,
        "112" to L.register.errorCodes._112,
    )

    /** RN registerAccountModal's catch: what the "Fail!" alert says for a failed signup. */
    fun signupError(e: SignupException): String {
        val message = e.serverMessage?.takeIf { it.isNotEmpty() }
        return when {
            e.status == 500 -> ERROR_500
            message != null -> e.code?.takeIf { it.isNotEmpty() && it != "0" }?.let { ERROR_CODES[it] ?: message } ?: message
            else -> UNKNOWN_ERROR
        }
    }
}

/** What a signup link or a caller hands the register screen (RN route params). In memory only: an email is not saved navigation. */
data class SignupPrefill(val username: String = "", val email: String = "", val referral: String = "", val purchaseOnly: Boolean = false)

data class RegisterAlert(val title: String, val body: String)

data class RegisterState(
    val username: String = "",
    val email: String = "",
    val referral: String = "",
    /** RN starts every field as valid: nothing is red before the user typed. */
    val usernameValid: Boolean = true,
    val emailValid: Boolean = true,
    val referralValid: Boolean = true,
    /** The name is taken (RN `isUserExist`: the avatar of that account shows in the field). */
    val userExists: Boolean = false,
    val usernameError: String = "",
    /** RN registerAccountModal ("Get Hive Account") is open. */
    val optionsOpen: Boolean = false,
    /** The Turnstile token of this modal session; empty until the challenge passed. */
    val captchaToken: String = "",
    /** Bumped for a fresh challenge (RN remounts the widget): a token is single use. */
    val captchaKey: Int = 0,
    val isRegistering: Boolean = false,
    val isRegistered: Boolean = false,
    val alert: RegisterAlert? = null,
    /** RN `disableFree`: a purchase link sells only the paid account. */
    val purchaseOnly: Boolean = false,
    /** RN `isLoading` of the options' product list. */
    val productsLoading: Boolean = false,
    /** The paid account's store price; null when the store does not sell it here (RN shows no card then). */
    val paidPrice: String? = null,
    /** An earlier paid account purchase waits on the device (RN "Claim Paid Account"). */
    val paidWaiting: Boolean = false,
) {
    /** RN's paid card button. */
    val paidButton: String? get() = if (paidWaiting) PurchaseCopy.CLAIM_ACCOUNT else paidPrice?.let { PurchaseCopy.buyAccount(it) }

    /** RN's Continue `isDisable`. */
    val canContinue: Boolean
        get() = usernameValid && referralValid && emailValid && username.isNotEmpty() && email.isNotEmpty()

    /** RN: "Register Free" is disabled until the challenge passed, and while a request runs. */
    val canRegisterFree: Boolean get() = captchaToken.isNotEmpty() && !isRegistering && !isRegistered
}

/**
 * RN registerScreen with its registerAccountModal, free path: username rules ([UsernameRules]), the availability
 * lookup 500 ms after the last keystroke, the email pattern, the optional referral that must be an existing
 * account, then "Get Hive Account" with the Turnstile challenge and `POST /private-api/account-create`.
 *
 * The signup request is never retried and never sent twice at once; after any failure the captcha token is
 * dropped (it is single use) and the user starts a new challenge. The paid path buys `999accounts` through [PurchaseService].
 */
class RegisterModel internal constructor(private val env: AppEnvironment, prefill: SignupPrefill) :
    StateModel<RegisterState>(RegisterState(), env.dispatchers) {

    private var usernameJob: Job? = null
    private var referralJob: Job? = null

    init {
        // RN's mount effect: the same handlers as typing
        if (prefill.email.isNotEmpty()) setEmail(prefill.email)
        if (prefill.username.isNotEmpty()) setUsername(prefill.username)
        if (prefill.referral.isNotEmpty()) setReferral(prefill.referral)
        setState { it.copy(purchaseOnly = prefill.purchaseOnly) }
        scope.launch { env.purchases.outcomes.collect { if (it.fromBuy && it.productId == PurchaseSkus.ACCOUNT) onPurchase(it) } }
        scope.launch { env.purchases.state.collect { s -> setState { it.copy(paidWaiting = PurchaseSkus.ACCOUNT in s.unfinished) } } }
        // RN: a purchase link with a creatable name opens the paid options at once (the same rule check as Continue)
        if (prefill.purchaseOnly && prefill.email.isNotEmpty() && prefill.username.isNotEmpty() && UsernameRules.error(prefill.username.lowercase()) == null) {
            openOptions()
        }
    }

    fun setUsername(value: String) {
        val lower = value.take(MAX_FIELD).lowercase()
        setState { it.copy(username = lower) }
        usernameJob?.cancel()
        usernameJob = scope.launch {
            delay(DEBOUNCE_MILLIS)
            validateUsername(lower)
        }
    }

    fun setEmail(value: String) {
        val email = value.take(MAX_EMAIL)
        setState { it.copy(email = email, emailValid = UsernameRules.isValidEmail(email)) }
    }

    fun setReferral(value: String) {
        val lower = value.take(MAX_FIELD).lowercase()
        referralJob?.cancel()
        setState { it.copy(referral = lower) }
        if (lower.isEmpty()) {
            setState { it.copy(referralValid = true) }
            return
        }
        referralJob = scope.launch {
            val found = lookup(lower)
            if (current.referral == lower) setState { it.copy(referralValid = found?.contains(lower) ?: false) }
        }
    }

    /** RN `_isValidUsername`: the rule check and its message. */
    private fun passesRules(value: String): Boolean {
        val error = UsernameRules.error(value)
        setState { it.copy(usernameError = error?.message.orEmpty()) }
        return error == null
    }

    private suspend fun validateUsername(value: String) {
        if (!passesRules(value)) {
            setState { it.copy(userExists = false, usernameValid = false) }
            return
        }
        val found = lookup(value)
        if (current.username != value) return
        val available = found?.let { value !in it } ?: false
        setState {
            it.copy(
                usernameError = if (!available && found != null) RegisterCopy.USERNAME_EXISTS else it.usernameError,
                userExists = !available,
                usernameValid = available,
            )
        }
    }

    /** RN `_getAccountsWithUsername`: null while offline or after a failed lookup (which also alerts). */
    private suspend fun lookup(name: String): List<String>? {
        if (!env.connectivity.current.online) return null
        return try {
            env.communityApi.lookupAccounts(name, LOOKUP_LIMIT)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            setState { it.copy(alert = RegisterAlert(RegisterCopy.ERROR, RegisterCopy.UNKNOWN_ERROR)) }
            null
        }
    }

    /** RN `_onContinuePress`: the rules once more (the button lags the debounce), then the options. */
    fun continueToOptions() {
        if (!current.canContinue) return
        if (!passesRules(current.username)) {
            setState { it.copy(usernameValid = false) }
            return
        }
        openOptions()
    }

    private fun openOptions() {
        // a fresh modal session never reuses a token
        setState { it.copy(optionsOpen = true, isRegistered = false, isRegistering = false, captchaToken = "", captchaKey = it.captchaKey + 1, productsLoading = true) }
        scope.launch {
            val (products, _) = env.purchases.products(listOf(PurchaseSkus.ACCOUNT))
            setState { it.copy(productsLoading = false, paidPrice = products.firstOrNull { p -> p.productId == PurchaseSkus.ACCOUNT }?.localizedPrice) }
        }
    }

    /**
     * RN's paid card: the name is checked once more (the buyer would be charged for a name the chain rejects), then
     * the store sheet opens with the account's details saved for an interrupted purchase.
     */
    fun buyAccount() {
        val s = current
        if (!s.optionsOpen || s.isRegistering || s.isRegistered || s.paidButton == null) return
        UsernameRules.error(s.username)?.let { error ->
            setState { it.copy(alert = RegisterAlert(RegisterCopy.FAIL, error.message)) }
            return
        }
        setState { it.copy(isRegistering = true) }
        env.purchases.buy(
            PurchaseSkus.ACCOUNT,
            PurchaseTarget(meta = com.ecency.core.api.ecency.AccountOrderMeta(s.username, s.email, s.referral)),
        )
    }

    /** RN `_handleOnPurchaseSuccess` / `_handleOnPurchaseFailure`. */
    private fun onPurchase(o: PurchaseOutcome) {
        when (o) {
            is PurchaseOutcome.Delivered -> setState { it.copy(isRegistering = false, isRegistered = true) }
            is PurchaseOutcome.Earlier -> setState {
                it.copy(
                    // the earlier purchase is another name's: its delivery is not this screen's (no follow-up comes)
                    isRegistering = false,
                    alert = RegisterAlert(PurchaseCopy.WARNING, if (o.pending) PurchaseCopy.earlierPending(o.target) else PurchaseCopy.earlierPurchase(o.target)),
                )
            }
            is PurchaseOutcome.Pending -> setState { it.copy(isRegistering = false, alert = RegisterAlert(PurchaseCopy.WARNING, PurchaseCopy.PENDING)) }
            is PurchaseOutcome.Processing -> setState { it.copy(isRegistering = false, alert = RegisterAlert(PurchaseCopy.WARNING, PurchaseCopy.PROCESSING)) }
            is PurchaseOutcome.Failed -> setState { it.copy(isRegistering = false, alert = RegisterAlert(RegisterCopy.FAIL, "${PurchaseCopy.REGISTER_FAIL}\n${o.message}")) }
            is PurchaseOutcome.StoreFailed -> setState {
                // a cancelled sheet is no failure; a duplicate delivery of one failure is told once
                if (o.report != IapReport.REPORTED) it.copy(isRegistering = false)
                else it.copy(isRegistering = false, alert = RegisterAlert(RegisterCopy.FAIL, "${PurchaseCopy.REGISTER_FAIL}\n${o.error.message}"))
            }
        }
    }

    fun closeOptions() {
        if (current.isRegistering) return
        setState { it.copy(optionsOpen = false, captchaToken = "") }
    }

    /** The challenge passed. Tokens are opaque and short; anything else is not one. */
    fun captchaVerified(token: String) {
        if (!current.optionsOpen || token.isEmpty() || token.length > MAX_TOKEN || token.any { it <= ' ' }) return
        setState { it.copy(captchaToken = token) }
    }

    fun captchaExpired() = setState { it.copy(captchaToken = "") }

    /** A challenge error: a new challenge (RN `_resetCaptcha`). */
    fun captchaFailed() = setState { it.copy(captchaToken = "", captchaKey = it.captchaKey + 1) }

    fun dismissAlert() = setState { it.copy(alert = null) }

    /** RN `_handleOnPressRegister`. */
    fun registerFree() {
        val s = current
        if (!s.optionsOpen || !s.canRegisterFree) return
        UsernameRules.error(s.username)?.let { error ->
            setState { it.copy(alert = RegisterAlert(RegisterCopy.FAIL, error.message)) }
            return
        }
        setState { it.copy(isRegistering = true) }
        scope.launch {
            try {
                env.onboardingApi.signUp(s.username, s.email, s.referral, s.captchaToken)
                setState { it.copy(isRegistering = false, isRegistered = true, captchaToken = "") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val body = if (e is SignupException) RegisterCopy.signupError(e) else RegisterCopy.UNKNOWN_ERROR
                setState {
                    it.copy(
                        isRegistering = false, isRegistered = false, alert = RegisterAlert(RegisterCopy.FAIL, body),
                        captchaToken = "", captchaKey = it.captchaKey + 1,
                    )
                }
            }
        }
    }

    companion object {
        /** RN `debounce(_validateUsername, 500)`. */
        const val DEBOUNCE_MILLIS = 500L

        /** SDK lookupAccountsQueryOptions' default limit. */
        const val LOOKUP_LIMIT = 50
        const val MAX_FIELD = 64
        const val MAX_EMAIL = 320
        const val MAX_TOKEN = 4096
    }
}

/**
 * The Turnstile challenge of the free signup (RN turnstileWebView): which page loads, which navigations a web
 * view may make, and what the page's bridge messages mean. The platforms' web views only follow these answers.
 */
object TurnstilePage {
    /** RN `TURNSTILE_EMBED_URL`: the widget is hosted on ecency.com (the site key is bound to that origin). */
    const val URL = "https://ecency.com/embed/turnstile"

    /** The name the hosted page posts to (`window.ReactNativeWebView.postMessage`). */
    const val BRIDGE = "ReactNativeWebView"

    /**
     * RN `_shouldStartLoad`: sub-frames always load (the challenge computes in about:, blob: and data: frames);
     * the top document only on ecency.com or Cloudflare's challenge host. [isTopFrame] null (Android does not
     * tell) is treated as a top document.
     */
    fun allowsLoad(url: String, isTopFrame: Boolean?): Boolean = isTopFrame == false || isAllowedTop(url)

    fun isAllowedTop(url: String): Boolean = url.startsWith("https://ecency.com/") || url.startsWith("https://challenges.cloudflare.com/")

    /** A bridge message counts only while the web view's own document is Ecency's embed page. */
    fun acceptsMessagesFrom(topUrl: String?): Boolean = topUrl != null && (topUrl == URL || topUrl.startsWith("$URL?") || topUrl.startsWith("$URL#") || topUrl.startsWith("$URL/"))

    sealed interface Event {
        data class Verified(val token: String) : Event
        data object Expired : Event
        data object Failed : Event
    }

    /** RN `_onMessage`: `{type: "verify", token}`, `{type: "expire"}`, `{type: "error"}`; anything else is ignored. */
    fun parse(message: String?): Event? {
        if (message == null || message.length > 8192) return null
        val o = com.ecency.core.api.json.SafeJson.parseOrNull(message, 8) as? kotlinx.serialization.json.JsonObject ?: return null
        fun text(field: String) = (o[field] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
        return when (text("type")) {
            "verify" -> text("token")?.takeIf { it.isNotEmpty() && it.length <= RegisterModel.MAX_TOKEN && it.none { c -> c <= ' ' } }?.let { Event.Verified(it) }
            "expire" -> Event.Expired
            "error" -> Event.Failed
            else -> null
        }
    }
}

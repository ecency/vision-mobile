package com.ecency.core.app

import com.ecency.core.auth.LoginMethod
import com.ecency.core.hive.KeyRole
import com.ecency.core.l10n.L

/** The login screen's copy (RN en-US login.*), for both apps. */
object LoginCopy {
    val TITLE: String get() = L.login.signin
    val SUBTITLE: String get() = L.login.signinTitle
    /** The header's way to the register screen: "Sign up" (RN's "JOIN NOW" read like a second primary action). */
    val JOIN: String get() = L.register.title
    val USERNAME: String get() = L.login.username
    val PASSWORD: String get() = L.login.password
    val TERMS: String get() = L.login.description
    val LOGIN: String get() = L.login.login
    val OR: String get() = L.login.or
    val HIVESIGNER: String get() = L.login.loginWithHs
    val HIVEAUTH: String get() = L.login.signinWithHiveauth
    val NO_ACCOUNT: String get() = L.login.noAccountText
    val SIGN_UP: String get() = L.login.signupNow
    val FAILED_TITLE: String get() = L.login.loginFailed
    val FAILED_BODY: String get() = L.login.loginFailedBody
    const val TERMS_URL = "https://ecency.com/terms-of-service"
    const val SIGNUP_URL = "https://ecency.com/signup"
}

/** The HiveAuth sign-in sheet's copy (RN en-US hiveauth.*, and native's wallet hand-off texts). */
object HiveAuthCopy {
    val TITLE: String get() = L.hiveauth.title
    val INITIATING: String get() = L.hiveauth.initiating
    val AUTHENTICATING: String get() = L.hiveauth.authenticating
    val LOGGING_IN: String get() = L.hiveauth.loggingIn
    val SUCCESS: String get() = L.hiveauth.authSuccess
    val TRANSACTION_SUCCESS: String get() = L.hiveauth.transactionSuccess
    val NOT_INSTALLED: String get() = L.hiveauth.notInstalled
    val OPEN_WALLET: String get() = L.native.ui.auth.openWalletApp
    val SCAN: String get() = L.native.ui.auth.hiveauthScan
}

/** The account switcher, auth upgrade and deep link prompts (RN en-US side_menu.*, alert.*, auth_upgrade.*, qr.*, deep_link.*). */
object AccountCopy {
    val ADD_EXISTING: String get() = L.sideMenu.addAnExistingAccount
    val CREATE_NEW: String get() = L.sideMenu.createANewAccount
    val SWITCHING: String get() = L.native.ui.auth.switching
    val LOGOUT_TITLE: String get() = L.sideMenu.logoutText
    val LOGOUT_ALL: String get() = L.native.ui.auth.logoutAll
    val LOGOUT_ALL_TITLE: String get() = L.native.ui.auth.logoutAllTitle
    val LOGOUT: String get() = L.sideMenu.logout
    val CANCEL: String get() = L.alert.cancel
    val WARNING: String get() = L.alert.warning
    val AUTH_EXPIRED: String get() = L.alert.authExpired
    val VERIFY: String get() = L.alert.verify
    val UPGRADE_TITLE: String get() = L.authUpgrade.title
    val UPGRADE_KEY_PLACEHOLDER: String get() = L.authUpgrade.keyPlaceholder
    val UPGRADE_SIGN: String get() = L.authUpgrade.signButton
    val UPGRADE_OR: String get() = L.authUpgrade.or
    val UPGRADE_HIVESIGNER: String get() = L.authUpgrade.hivesignerButton
    val UPGRADE_HIVEAUTH: String get() = L.authUpgrade.hiveauthButton
    val CONFIRM_TX: String get() = L.qr.confirmTransaction
    val APPROVE: String get() = L.qr.approve
    val CONFIRM: String get() = L.alert.confirm

    /** auth_upgrade.description; the role reads in the app language ([CopyLists.role]), the chain word stays English. */
    fun upgradeDescription(authority: KeyRole): String = L.authUpgrade.description(authority = CopyLists.role(authority))

    fun postingKeyRequest(requester: String, username: String): String =
        L.deepLink.postingKeyRequest(requester = requester, username = username)

    fun authRequest(requester: String, username: String?): String =
        if (username != null) L.deepLink.authRequestAsUser(requester = requester, username = username)
        else L.deepLink.authRequestAsCurrent(requester = requester)

    fun authTransfer(username: String): String = L.qr.authTransferConfirm(username = username)

    /** What happens to an account already signed in here (empty: none is), as a paragraph after [authTransfer]. */
    fun authTransferStored(username: String, method: LoginMethod?): String = when (method) {
        null -> ""
        LoginMethod.KEY -> "\n\n" + L.native.ui.auth.storedKey(username = username)
        LoginMethod.HIVESIGNER -> "\n\n" + L.native.ui.auth.storedHivesigner(username = username)
        LoginMethod.HIVEAUTH -> "\n\n" + L.native.ui.auth.storedHiveauth(username = username)
    }

    /** The sign-in method shown under an account; HiveSigner and HiveAuth are brand names. */
    fun method(m: LoginMethod?): String = when (m) {
        LoginMethod.HIVESIGNER -> "HiveSigner"
        LoginMethod.HIVEAUTH -> "HiveAuth"
        else -> L.native.ui.auth.methodKey
    }
    val LOGOUT_FAILED: String get() = L.native.ui.auth.logoutFailed
}

/** The welcome screen's copy (RN en-US welcome.*). */
object WelcomeCopy {
    val LABEL: String get() = L.welcome.label
    const val TITLE = "Ecency"
    val LINES: List<Pair<String, String>> get() = listOf(
        L.welcome.line1Heading to L.welcome.line1Body,
        L.welcome.line2Heading to L.welcome.line2Body,
        L.welcome.line3Heading to L.welcome.line3Body,
    )
    val TERMS: String get() = L.welcome.termsDescription
    val READ: String get() = L.welcome.termsText
    val GET_STARTED: String get() = L.welcome.getStarted
}

/** The dApp browser's copy that its home screen does not already carry (RN en-US dapp_browser.*, native's own). */
object BrowserCopy {
    val PAGE_LOAD_FAILED: String get() = L.dappBrowser.pageLoadFailed
    val RETRY: String get() = L.dappBrowser.retry
    val CONFIRM: String get() = L.alert.confirm
    val CANCEL: String get() = L.alert.cancel
    val ACCOUNT: String get() = L.native.ui.browser.account
    val AUTHORITY: String get() = L.native.ui.browser.authority
    val PHISHING: String get() = L.native.ui.browser.phishing
}

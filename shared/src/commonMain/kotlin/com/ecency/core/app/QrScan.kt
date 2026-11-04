package com.ecency.core.app

import com.ecency.core.l10n.L

/** What a scanned code leads to (RN useLinkProcessor.handleLink). */
sealed interface QrOutcome {
    /** The link model took it (hive://, ecency://, ecency.com /auth and /signup): it asks the user itself. */
    data object LinkHandled : QrOutcome

    /** An ecency.com post, profile, feed or waves link: opened in the navigation (gated like every entry). */
    data class Open(val entry: NavEntry) : QrOutcome

    /** Another http(s) page (RN WEB_BROWSER): the in-app browser. */
    data class Browser(val url: String) : QrOutcome

    /** RN qr.unsupported_alert_*. */
    data class Unsupported(val title: String, val message: String) : QrOutcome

    /** The scanner was opened to read a value (the transfer recipient): it goes back to the caller. */
    data class Value(val text: String) : QrOutcome
}

/** Why the scanner was opened. */
enum class QrPurpose {
    /** RN header and side menu: anything it reads goes through the app's link handling. */
    LINKS,

    /** RN transferScreen `onScan`: the text goes back to the transfer screen ([TransferModel.scanned]). */
    VALUE,
}

data class QrScanState(
    /** A code was taken: the scanner stops (RN's re-entry latch) until [QrScanModel.reset]. */
    val handled: Boolean = false,
    val outcome: QrOutcome? = null,
    /** The camera permission was refused: RN's "Unable to get Camera permission" alert. */
    val permissionDenied: Boolean = false,
)

/**
 * RN qrModalView: one code per open (vision-camera can deliver frames already queued after the first; only the first
 * counts), then the sheet closes and the code is routed. The platforms feed every decoded value to [scanned] and act
 * on [QrScanState.outcome] (open the entry or the browser, show the alert, hand the value back) once.
 */
class QrScanModel internal constructor(private val env: AppEnvironment, val purpose: QrPurpose) :
    StateModel<QrScanState>(QrScanState(), env.dispatchers) {

    /** RN: the latch is cleared on every open. */
    fun reset() = setState { QrScanState() }

    fun scanned(value: String) {
        if (current.handled) return
        val text = value.trim()
        if (text.isEmpty()) return
        setState { it.copy(handled = true) }
        val outcome = when (purpose) {
            QrPurpose.VALUE -> QrOutcome.Value(text.take(MAX_VALUE))
            QrPurpose.LINKS -> route(text)
        }
        setState { it.copy(outcome = outcome) }
    }

    private fun route(text: String): QrOutcome {
        if (text.length > MAX_VALUE) return unsupported()
        if (env.authLinks.handle(text)) return QrOutcome.LinkHandled
        // ecency.com/waves?text=...: the composer opens with the text (RN deepLinkParser's waves compose link)
        env.waves.openLink(text)
        ContentLinks.entry(text)?.let { return QrOutcome.Open(it) }
        val route = LinkRouter.route(com.ecency.core.render.blocks.LinkTarget.External(text))
        if (route is Route.ExternalBrowser) return QrOutcome.Browser(route.url)
        return unsupported()
    }

    private fun unsupported() = QrOutcome.Unsupported(AuthLinkModel.UNSUPPORTED_TITLE, AuthLinkModel.UNSUPPORTED_MESSAGE)

    /** The platform acted on the outcome. */
    fun consumeOutcome() = setState { it.copy(outcome = null) }

    fun setPermissionDenied(denied: Boolean) = setState { it.copy(permissionDenied = denied) }

    companion object {
        /** Longer payloads are not links the app takes. */
        const val MAX_VALUE = 8_192

        // RN qrModalView camera permission alert (iOS)
        val PERMISSION_TITLE: String get() = L.native.qrScan.permissionTitle
        val PERMISSION_MESSAGE: String get() = L.native.qrScan.permissionMessage
        val PERMISSION_CLOSE: String get() = L.transfer.close
        val PERMISSION_ALLOW: String get() = L.native.qrScan.permissionAllow
    }
}

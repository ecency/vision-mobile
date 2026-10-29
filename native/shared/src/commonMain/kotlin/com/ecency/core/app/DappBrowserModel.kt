package com.ecency.core.app

import com.ecency.core.auth.LoginMethod
import com.ecency.core.auth.loginMethod
import com.ecency.core.hive.KeyRole
import com.ecency.core.hive.PrivateKey
import com.ecency.core.l10n.L
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

// ---------------------------------------------------------------------------------------------------------------
// What the in-app browser loads, and who may talk to the bridge

/** Where a navigation in the dApp browser goes. */
enum class BrowserNavigation {
    /** The web view loads it. */
    LOAD,

    /** Nothing happens. */
    BLOCK,

    /** A hive:// or ecency:// link the user tapped: the app's link handling takes it (and asks first). */
    APP_LINK,
}

object BrowserPolicy {
    private val SUBFRAME_ONLY = setOf("about", "data", "blob")

    /**
     * Only http and https load in a page's main frame. about:, data: and blob: load in frames only (pages build
     * frames that way); a hive:// or ecency:// link the user tapped goes to the app's link handling, which confirms
     * every request; a page cannot fire one by itself. javascript:, file:, content:, intent: and every other
     * scheme are blocked.
     */
    fun navigation(url: String, isMainFrame: Boolean, userGesture: Boolean): BrowserNavigation {
        val scheme = url.substringBefore(':', "").lowercase()
        return when {
            scheme == "https" || scheme == "http" -> if (url.contains("://")) BrowserNavigation.LOAD else BrowserNavigation.BLOCK
            scheme in SUBFRAME_ONLY -> if (isMainFrame) BrowserNavigation.BLOCK else BrowserNavigation.LOAD
            (scheme == "hive" || scheme == "ecency") && isMainFrame && userGesture -> BrowserNavigation.APP_LINK
            else -> BrowserNavigation.BLOCK
        }
    }

    /**
     * `https://host[:port]` of [url] (lower case), or null when it is not an https URL with a plain host: user info,
     * an empty host or anything but https gives null.
     */
    fun httpsOrigin(url: String): String? {
        if (!url.startsWith("https://", ignoreCase = true)) return null
        val authority = url.substring(8).substringBefore('/').substringBefore('?').substringBefore('#')
        if (authority.isEmpty() || '@' in authority || '\\' in authority) return null
        val host = authority.substringBefore(':').lowercase()
        if (host.isEmpty() || host.any { !(it.isLetterOrDigit() || it == '.' || it == '-' || it == '[' || it == ']') }) return null
        val port = authority.substringAfter(':', "")
        if (port.isNotEmpty() && (port.toIntOrNull() ?: -1) !in 1..65535) return null
        return "https://" + host + (if (port.isEmpty() || port == "443") "" else ":$port")
    }

    /** The host of an origin from [httpsOrigin]. */
    fun host(origin: String): String = origin.removePrefix("https://").substringBefore(':')

    /** Hosts the bridge never serves: the sign-in pages of HiveSigner (the app's own authorization flow). */
    private val NO_BRIDGE = listOf("hivesigner.com")

    /**
     * The origin a bridge message counts as coming from, or null when it is refused: it must be https, from the
     * page's main frame, the origin the tab shows, and not a host the bridge never serves.
     */
    fun bridgeOrigin(frameOrigin: String, isMainFrame: Boolean, tabUrl: String?): String? {
        val origin = httpsOrigin(frameOrigin.trimEnd('/')) ?: return null
        if (tabUrl == null || httpsOrigin(tabUrl) != origin) return null
        val host = host(origin)
        if (NO_BRIDGE.any { host == it || host.endsWith(".$it") }) return null
        // the frame check last, as a plain when: Kotlin/Native 2.3.10 (iosX64) answered a leading
        // `if (!isMainFrame) return null` here as if the frame were the main one (DappBrowserModelTest)
        return when (isMainFrame) {
            true -> origin
            false -> null
        }
    }

    /** The URL bar text when not editing: the page's host, else the URL (RN _getDisplayUrl). */
    fun displayHost(url: String?): String {
        if (url == null) return ""
        val rest = url.substringAfter("://", "")
        if (rest.isEmpty()) return url
        val host = rest.substringBefore('/').substringBefore('?').substringBefore('#').substringAfter('@').substringBefore(':')
        return host.ifEmpty { url }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// State

data class BrowserTab(
    val id: Int,
    /** Null: the home screen (the dApp directory). */
    val url: String? = null,
    val title: String = HOME_TITLE,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val hasError: Boolean = false,
    /** Bumped when the app asks the web view to load [url] (typed, picked, retried): the UI loads on every change. */
    val load: Int = 0,
    /** 0..1 while loading, 0 when idle. */
    val progress: Float = 0f,
    /**
     * The page whose document is live in the tab ([DappBrowserModel.pageCommitted]). [url] also follows a navigation
     * that only started, while the old document still runs; whatever names "the page" uses this one.
     */
    val committedUrl: String? = null,
) {
    companion object {
        val HOME_TITLE: String get() = L.native.dappBrowser.homeTitle
        val LOADING_TITLE: String get() = L.native.dappBrowser.loadingTitle
    }
}

/** One operation on the confirmation, as it will be signed. */
data class BridgeOperationView(val title: String, val fields: List<LinkField>)

/** RN SignConfirmSheet: a page asks to sign. Nothing is signed until [DappBrowserModel.confirm]. */
data class BridgePrompt(
    val id: Long,
    val tabId: Int,
    /** RN OPERATION_LABELS. */
    val title: String,
    /** The page's host as the web view reported it (never what the page claims). */
    val host: String,
    val username: String,
    /** "POSTING" or "ACTIVE". */
    val authority: String,
    val operations: List<BridgeOperationView> = emptyList(),
    /** Extra rows: the message of a sign request, the page's own description of a custom operation. */
    val details: List<LinkField> = emptyList(),
    /** The listed phishing domain the page is (or is under), shown as a warning. */
    val blacklisted: String? = null,
)

data class DappBrowserState(
    val tabs: List<BrowserTab>,
    val activeTabId: Int,
    val urlText: String = "",
    val urlFocused: Boolean = false,
    val prompt: BridgePrompt? = null,
    /** A confirmed request is being signed and broadcast. */
    val working: Boolean = false,
    /** A short message for a toast. */
    val notice: String? = null,
) {
    val activeTab: BrowserTab get() = tabs.firstOrNull { it.id == activeTabId } ?: tabs.first()

    val showsHome: Boolean get() = activeTab.url == null

    /** RN _getDisplayUrl. */
    val displayUrl: String get() = if (urlFocused) urlText else BrowserPolicy.displayHost(activeTab.url)

    /** RN _isSecure: the lock icon. */
    val isSecure: Boolean get() = activeTab.url?.startsWith("https://", ignoreCase = true) == true

    val canCloseTabs: Boolean get() = tabs.size > 1
}

// ---------------------------------------------------------------------------------------------------------------
// The model

/**
 * RN screens/dappBrowser: tabs, the URL bar and the Hive signing bridge (window.hive_keychain, window.peakvault).
 * One per browser screen. The platform owns the web views (one per tab), reports their navigation here, asks
 * [shouldLoad] before every navigation and hands every bridge message to [bridgeMessage] with the frame's origin
 * as the web view reports it.
 *
 * Bridge rules (beyond RN):
 * - A request counts only from the active tab's main frame, over https, from the origin the tab shows
 *   ([BrowserPolicy.bridgeOrigin]); the confirmation names that origin, never the `domain` the page sends.
 * - One request at a time: a request while another waits for the user, or is being signed, is answered busy.
 * - Every signature needs the confirmation; operations are shown as they will be signed (built here, not as the
 *   page describes them); owner and key changes are refused before any prompt ([BridgeGuards]).
 * - A confirmed broadcast is one action with its own id ([WalletSends]): a retry resumes it, a send that may have
 *   landed is followed up, never signed twice.
 * - A message signature (signBuffer) also asks for the PIN when the app lock is on, as the sign-in proof does.
 * - An answer goes only to the tab and origin that asked: a tab that navigated elsewhere or closed hears nothing.
 * - The account must be the one signed in when the request came and when it is confirmed.
 */
class DappBrowserModel internal constructor(private val env: AppEnvironment, initialUrl: String?) :
    StateModel<DappBrowserState>(initialState(initialUrl), env.dispatchers) {

    /** Answers a page: [answer] is the JSON the bridge script's receiver takes. The platform delivers it to the frame. */
    fun interface BridgeReply {
        fun send(answer: String)
    }

    private var nextTabId = current.tabs.maxOf { it.id } + 1
    private var nextPromptId = 1L
    private var pending: PendingRequest? = null

    private sealed interface Work {
        class Broadcast(val ops: JsonArray, val authority: KeyRole) : Work
        class SignBuffer(val message: String, val authority: KeyRole) : Work
    }

    private class PendingRequest(
        val promptId: Long,
        val tabId: Int,
        val origin: String,
        val username: String,
        val answer: Answerer,
        val work: Work,
    )

    /** Formats answers for one request (Keychain or Peak Vault) and sends them through its reply. */
    private class Answerer(private val kind: Kind, private val id: Long, private val account: String, private val reply: BridgeReply) {
        enum class Kind { KEYCHAIN, PEAK_VAULT }

        fun error(message: String) = reply.send(
            when (kind) {
                Kind.KEYCHAIN -> envelope(
                    "keychain",
                    buildJsonObject {
                        put("success", false)
                        put("error", message)
                        put("result", JsonNull)
                        put("message", message)
                        put("request_id", id)
                    },
                )
                Kind.PEAK_VAULT -> envelope(
                    "peakvault",
                    buildJsonObject {
                        put("success", false)
                        put("error", message)
                        put("account", account)
                        put("result", JsonNull)
                    },
                )
            },
        )

        fun success(result: JsonElement, publicKey: String? = null, message: String? = null) = reply.send(
            when (kind) {
                Kind.KEYCHAIN -> envelope(
                    "keychain",
                    buildJsonObject {
                        put("success", true)
                        put("error", JsonNull)
                        put("result", result)
                        message?.let { put("message", it) }
                        publicKey?.let { put("publicKey", it) }
                        put("request_id", id)
                    },
                )
                Kind.PEAK_VAULT -> envelope(
                    "peakvault",
                    buildJsonObject {
                        put("success", true)
                        put("error", "")
                        put("account", account)
                        publicKey?.let { put("publicKey", it) }
                        put("result", result)
                    },
                )
            },
        )

        private fun envelope(kind: String, response: JsonObject) = buildJsonObject {
            put("kind", kind)
            put("id", id)
            put("response", response)
        }.toString()
    }

    // ---- tabs and the URL bar (RN dappBrowser.tsx) ----

    fun setUrlText(text: String) = setState { it.copy(urlText = text) }

    /** RN onFocus: the full URL to edit; onBlur. */
    fun setUrlFocused(focused: Boolean) = setState { s ->
        if (focused) s.copy(urlFocused = true, urlText = s.activeTab.url ?: s.urlText) else s.copy(urlFocused = false)
    }

    /** RN _onSubmitUrl: the URL bar's (or the home field's) text, normalised, in the active tab. */
    fun submitUrl() {
        val text = current.urlText
        if (text.isBlank()) return
        navigate(text)
    }

    /** RN _navigateTo: [input] normalised ([Dapps.normalizeUrl]) and loaded in the active tab. */
    fun navigate(input: String) {
        val url = Dapps.normalizeUrl(input) ?: return
        if (BrowserPolicy.navigation(url, isMainFrame = true, userGesture = true) != BrowserNavigation.LOAD) return
        val id = current.activeTabId
        dropPendingFor(id)
        updateTab(id) { it.copy(url = url, hasError = false, title = BrowserTab.LOADING_TITLE, load = it.load + 1) }
        setState { it.copy(urlText = url, urlFocused = false) }
    }

    /** RN header's first button: home in a page, else false (the UI closes the browser). */
    fun home(): Boolean {
        val tab = current.activeTab
        if (tab.url == null) return false
        dropPendingFor(tab.id)
        updateTab(tab.id) { BrowserTab(id = it.id) }
        setState { it.copy(urlText = "") }
        return true
    }

    /** RN _addTab: a new home tab, active. */
    fun addTab() {
        val tab = BrowserTab(id = nextTabId++)
        setState { it.copy(tabs = it.tabs + tab, activeTabId = tab.id, urlText = "", urlFocused = false) }
    }

    /** RN _closeTab: the last tab closing leaves a fresh home tab; closing the active one activates its neighbour. */
    fun closeTab(id: Int) {
        dropPendingFor(id)
        setState { s ->
            val index = s.tabs.indexOfFirst { it.id == id }
            if (index < 0) return@setState s
            val next = s.tabs.filterNot { it.id == id }
            if (next.isEmpty()) {
                val tab = BrowserTab(id = nextTabId++)
                return@setState s.copy(tabs = listOf(tab), activeTabId = tab.id, urlText = "")
            }
            if (s.activeTabId != id) return@setState s.copy(tabs = next)
            val active = next[minOf(index, next.size - 1)]
            s.copy(tabs = next, activeTabId = active.id, urlText = active.url.orEmpty())
        }
    }

    /** RN _switchTab. A request waiting on the tab left behind is answered as rejected. */
    fun switchTab(id: Int) {
        val tab = current.tabs.firstOrNull { it.id == id } ?: return
        pending?.takeIf { it.tabId != id && current.prompt != null }?.let { reject() }
        setState { it.copy(activeTabId = id, urlText = tab.url.orEmpty(), urlFocused = false) }
    }

    /**
     * The web view of [tabId] changed (RN onNavigationStateChange). A tab whose page moved to another origin drops a
     * request it made, unanswered (the page that asked is gone).
     */
    fun pageChanged(tabId: Int, url: String?, title: String?, canGoBack: Boolean, canGoForward: Boolean) {
        if (url != null) pending?.takeIf { it.tabId == tabId && BrowserPolicy.httpsOrigin(url) != it.origin }?.let { dropPendingFor(tabId) }
        updateTab(tabId) { t ->
            t.copy(
                url = url?.takeIf { it.isNotEmpty() && BrowserPolicy.navigation(it, true, false) == BrowserNavigation.LOAD } ?: t.url,
                title = title?.takeIf { it.isNotBlank() } ?: t.title,
                canGoBack = canGoBack,
                canGoForward = canGoForward,
            )
        }
        if (tabId == current.activeTabId && !current.urlFocused) setState { it.copy(urlText = it.activeTab.url.orEmpty()) }
    }

    fun progress(tabId: Int, value: Float) = updateTab(tabId) { it.copy(progress = if (value >= 1f) 0f else value.coerceIn(0f, 1f)) }

    /** RN _onError. */
    fun pageFailed(tabId: Int) = updateTab(tabId) { it.copy(hasError = true, progress = 0f) }

    /** RN _onRetry. */
    fun retry() = updateTab(current.activeTabId) { it.copy(hasError = false, load = it.load + 1) }

    /**
     * Asked by the platform before every navigation of [tabId]'s web view: true to load it. A hive:// or ecency://
     * link the user tapped goes to the app's link handling (which confirms anything it would sign) and is not loaded.
     */
    fun shouldLoad(tabId: Int, url: String, isMainFrame: Boolean, userGesture: Boolean): Boolean =
        shouldLoad(tabId, url, isMainFrame, userGesture, null)

    /**
     * As above, with [sourceHost]: the host of the frame that started the navigation, as the web view reports it
     * (iOS `sourceFrame.securityOrigin`). Without it the tab's committed page names the requester, never a
     * navigation that only started.
     */
    fun shouldLoad(tabId: Int, url: String, isMainFrame: Boolean, userGesture: Boolean, sourceHost: String?): Boolean =
        when (BrowserPolicy.navigation(url, isMainFrame, userGesture)) {
            BrowserNavigation.LOAD -> true
            BrowserNavigation.BLOCK -> false
            BrowserNavigation.APP_LINK -> {
                if (tabId == current.activeTabId) {
                    val page = sourceHost?.takeIf { it.isNotEmpty() }
                        ?: current.tabs.firstOrNull { it.id == tabId }?.committedUrl?.let(BrowserPolicy::displayHost)
                    env.authLinks.handle(url, page)
                }
                false
            }
        }

    /** The web view of [tabId] committed [url]: its document is the one running now (load commit, history update). */
    fun pageCommitted(tabId: Int, url: String?) {
        if (url == null || BrowserPolicy.navigation(url, true, false) != BrowserNavigation.LOAD) return
        updateTab(tabId) { it.copy(committedUrl = url) }
    }

    fun consumeNotice() = setState { it.copy(notice = null) }

    private fun updateTab(id: Int, transform: (BrowserTab) -> BrowserTab) =
        setState { s -> s.copy(tabs = s.tabs.map { if (it.id == id) transform(it) else it }) }

    // ---- the bridge (RN useHiveBridgeHandler) ----

    /**
     * A message from [tabId]'s page: [frameOrigin] and [isMainFrame] as the web view reports the frame that posted
     * it. Answers go through [reply], which the platform binds to that frame (and drops when the page changed).
     */
    fun bridgeMessage(tabId: Int, frameOrigin: String, isMainFrame: Boolean, message: String, reply: BridgeReply) {
        if (message.length > MAX_MESSAGE) return
        val parsed = runCatching { Json.parseToJsonElement(message) as? JsonObject }.getOrNull() ?: return
        val name = (parsed["name"] as? JsonPrimitive)?.contentOrNull ?: return
        val tab = current.tabs.firstOrNull { it.id == tabId } ?: return
        val origin = BrowserPolicy.bridgeOrigin(frameOrigin, isMainFrame, tab.url) ?: return
        when (name) {
            "swHandshake_hive" -> reply.send(buildJsonObject { put("kind", "handshake") }.toString())
            "swRequest_hive" -> {
                val requestId = (parsed["request_id"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: return
                val data = parsed["data"] as? JsonObject ?: return
                val type = (data["type"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val requested = (data["username"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
                val answer = Answerer(Answerer.Kind.KEYCHAIN, requestId, requested.orEmpty(), reply)
                if (!admit(tabId, answer)) return
                val user = env.session.current.username ?: return answer.error(NO_ACCOUNT)
                if (requested != null && requested != user) return answer.error(mismatch(requested, user))
                keychain(tabId, origin, user, type, data, answer)
            }
            "pvRequest" -> {
                val data = parsed["data"] as? JsonObject ?: return
                val resId = (data["resId"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L
                val requested = (data["account"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
                val answer = Answerer(Answerer.Kind.PEAK_VAULT, resId, requested.orEmpty(), reply)
                if (!admit(tabId, answer)) return
                peakVault(tabId, origin, requested, data, answer)
            }
        }
    }

    /** Only the active tab, one request at a time, never behind the app lock. */
    private fun admit(tabId: Int, answer: Answerer): Boolean {
        val lock = env.appLock.current
        if (if (env.appLock.hasStarted) lock.hidesContent else lock.isLocked) {
            answer.error(LOCKED)
            return false
        }
        if (tabId != current.activeTabId) {
            answer.error(NOT_IN_FRONT)
            return false
        }
        if (pending != null || current.working) {
            answer.error(BUSY)
            return false
        }
        return true
    }

    private fun keychain(tabId: Int, origin: String, user: String, type: String, data: JsonObject, answer: Answerer) {
        if (type == "signBuffer") {
            val message = (data["message"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return answer.error("Invalid message.")
            val authority = when ((data["method"] as? JsonPrimitive)?.contentOrNull?.lowercase() ?: "posting") {
                "posting" -> KeyRole.POSTING
                "active" -> KeyRole.ACTIVE
                else -> return answer.error("Only posting and active signatures are supported.")
            }
            return askSignBuffer(tabId, origin, user, message, authority, answer)
        }
        if (type in UNSUPPORTED) return answer.error("Operation \"$type\" is not yet supported in Ecency browser.")
        scope.launch {
            val ops = try {
                val rate = if (type == "delegation" || type == "powerDown") env.walletApi.hivePerMVests() else 0.0
                BridgeOps.build(type, data, user, { rate }, ::requestId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: BridgeRefused) {
                return@launch answer.error(e.message ?: "Failed to build operations for \"$type\".")
            } catch (e: Exception) {
                return@launch answer.error("Failed to build operations for \"$type\".")
            }
            val details = listOfNotNull(
                (data["display_msg"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && type == "custom" }?.let { pageSays(origin, it.take(MAX_DETAIL)) },
            )
            askBroadcast(tabId, origin, user, BridgeLabels.of(type), ops, details, answer)
        }
    }

    private fun peakVault(tabId: Int, origin: String, requested: String?, data: JsonObject, answer: Answerer) {
        val ops = data["operations"] as? JsonArray ?: return answer.error("Malformed Peak Vault payload")
        val keyRole = (data["keyRole"] as? JsonPrimitive)?.contentOrNull
        if (keyRole != null && keyRole !in listOf("posting", "active", "memo")) return answer.error("Invalid keyRole: \"$keyRole\"")
        val single = (ops.singleOrNull() as? JsonArray)?.let { (it.getOrNull(0) as? JsonPrimitive)?.contentOrNull }
        val user = env.session.current.username ?: return answer.error(NO_ACCOUNT)
        if (single == "peakvault-connect") return answer.success(JsonArray(listOf(JsonPrimitive(user))))
        if (requested != null && requested != user) return answer.error(mismatch(requested, user))
        when (single) {
            "peakvault-sign_buffer" -> {
                val message = (((ops[0] as JsonArray).getOrNull(1) as? JsonObject)?.get("message") as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: return answer.error("Invalid message.")
                if (keyRole == "memo") return answer.error("Memo key operations are not yet supported.")
                return askSignBuffer(tabId, origin, user, message, if (keyRole == "active") KeyRole.ACTIVE else KeyRole.POSTING, answer)
            }
            "peakvault-choose_contact" -> return answer.error("Contact picker is not supported in Ecency browser.")
            "peakvault-decode", "peakvault-encode", "peakvault-encode_with_keys" ->
                return answer.error("Operation \"$single\" is not yet supported in Ecency browser.")
        }
        // every operation is [name, {body}] with the name as JSON text (as the Keychain path builds them); a number or
        // anything else refuses the whole request instead of being dropped or read as text
        if (ops.any { op -> op !is JsonArray || (op.getOrNull(0) as? JsonPrimitive)?.isString != true }) return answer.error("No valid operations found.")
        val standard = JsonArray(ops.filter { !((it as JsonArray)[0] as JsonPrimitive).content.startsWith("peakvault") })
        if (standard.isEmpty()) return answer.error("No valid operations found.")
        if (keyRole == "memo") return answer.error("Memo key operations are not supported for broadcast.")
        if ((data["broadcast"] as? JsonPrimitive)?.contentOrNull != "true") {
            return answer.error("Sign-only mode is not supported in Ecency browser. Please use requestBroadcast instead.")
        }
        // the page's own words never take the sheet's title: they show quoted, as the page's
        val pageTitle = when (val m = data["displayMessage"]) {
            is JsonPrimitive -> m.contentOrNull
            is JsonObject -> (m["title"] as? JsonPrimitive)?.contentOrNull
            else -> null
        }?.takeIf { it.isNotBlank() }?.take(MAX_TITLE)
        askBroadcast(tabId, origin, user, BridgeLabels.of("broadcast"), standard, listOfNotNull(pageTitle?.let { pageSays(origin, it) }), answer)
    }

    private fun askBroadcast(tabId: Int, origin: String, user: String, title: String, ops: JsonArray, details: List<LinkField>, answer: Answerer) {
        scope.launch {
            val account = env.accounts.vaultAccount(user) ?: return@launch answer.error(NO_ACCOUNT)
            val signsHere = account.authType?.loginMethod() == LoginMethod.KEY
            BridgeGuards.refusal(ops, signsHere)?.let { return@launch answer.error(it) }
            // the page may have moved on or another request taken the slot while the account was read
            if (!stillAsking(tabId, origin, user)) return@launch answer.error(BUSY)
            val authority = com.ecency.core.auth.OperationAuthority.required(ops)
            prompt(
                PendingRequest(nextPromptId++, tabId, origin, user, answer, Work.Broadcast(ops, authority)),
                title, details, ops,
            )
        }
    }

    private fun askSignBuffer(tabId: Int, origin: String, user: String, message: String, authority: KeyRole, answer: Answerer) {
        // JS hands UTF-16; a lone surrogate has no UTF-8 form the page and the app would agree on
        if (hasLoneSurrogate(message)) return answer.error("Invalid message.")
        if (message.length > MAX_MESSAGE_TO_SIGN) return answer.error("Invalid message.")
        scope.launch {
            val account = env.accounts.vaultAccount(user) ?: return@launch answer.error(NO_ACCOUNT)
            val wif = if (authority == KeyRole.ACTIVE) account.activeKey else account.postingKey
            if (wif.isNullOrEmpty()) return@launch answer.error("${if (authority == KeyRole.ACTIVE) "Active" else "Posting"} key not available.")
            if (!stillAsking(tabId, origin, user)) return@launch answer.error(BUSY)
            prompt(
                PendingRequest(nextPromptId++, tabId, origin, user, answer, Work.SignBuffer(message, authority)),
                BridgeLabels.of("signBuffer"), listOf(LinkField(MESSAGE, message.take(MAX_DETAIL))), null,
            )
        }
    }

    private fun stillAsking(tabId: Int, origin: String, user: String): Boolean =
        pending == null && !current.working && tabId == current.activeTabId &&
            current.tabs.firstOrNull { it.id == tabId }?.url?.let(BrowserPolicy::httpsOrigin) == origin &&
            env.session.current.username == user

    private fun prompt(request: PendingRequest, title: String, details: List<LinkField>, ops: JsonArray?) {
        pending = request
        val authority = when (val w = request.work) {
            is Work.Broadcast -> w.authority
            is Work.SignBuffer -> w.authority
        }
        val host = BrowserPolicy.host(request.origin)
        setState {
            it.copy(
                prompt = BridgePrompt(
                    id = request.promptId,
                    tabId = request.tabId,
                    title = title,
                    host = host,
                    username = request.username,
                    authority = authority.value.uppercase(),
                    operations = ops.orEmpty().mapNotNull { op -> (op as? JsonArray)?.let(::view) },
                    details = details,
                    blacklisted = DappBlacklist.check(host),
                ),
            )
        }
    }

    private fun view(op: JsonArray): BridgeOperationView {
        val name = (op.getOrNull(0) as? JsonPrimitive)?.contentOrNull.orEmpty()
        val body = op.getOrNull(1) as? JsonObject
        return BridgeOperationView(BridgeLabels.operation(name), body.orEmpty().map { (k, v) -> LinkField(k, fieldText(v).take(MAX_DETAIL)) })
    }

    private fun fieldText(v: JsonElement): String = when (v) {
        is JsonPrimitive -> v.contentOrNull ?: "null"
        else -> v.toString()
    }

    /** RN Confirm: the request is signed (and broadcast) for the account it was shown for. */
    fun confirm() {
        val request = pending ?: return
        if (current.working) return
        pending = null
        setState { it.copy(prompt = null, working = true) }
        scope.launch {
            try {
                if (env.session.current.username != request.username) return@launch request.answer.error(ACCOUNT_CHANGED)
                when (val w = request.work) {
                    is Work.SignBuffer -> signBuffer(request, w)
                    is Work.Broadcast -> broadcast(request, w)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                request.answer.error(AppError.from(e).message.ifEmpty { "Transaction failed." })
            } finally {
                setState { it.copy(working = false) }
            }
        }
    }

    /** RN Cancel or a dismissed sheet: "User rejected the request." */
    fun reject() {
        val request = pending ?: return
        pending = null
        setState { it.copy(prompt = null) }
        request.answer.error(REJECTED)
    }

    private suspend fun signBuffer(request: PendingRequest, w: Work.SignBuffer) {
        // as the sign-in proof for another app: the PIN first when the lock is on
        if (!env.appLock.verifyPin(whenOff = true)) return request.answer.error(REJECTED)
        if (env.session.current.username != request.username) return request.answer.error(ACCOUNT_CHANGED)
        val account = env.accounts.vaultAccount(request.username) ?: return request.answer.error(NO_ACCOUNT)
        val wif = (if (w.authority == KeyRole.ACTIVE) account.activeKey else account.postingKey)?.takeIf { it.isNotEmpty() }
            ?: return request.answer.error("${if (w.authority == KeyRole.ACTIVE) "Active" else "Posting"} key not available.")
        val (signature, publicKey) = withContext(env.dispatchers.io) {
            val key = PrivateKey.fromWif(wif)
            key.sign(BridgeSignatures.digest(w.message)).toHex() to key.publicKey().toString()
        }
        request.answer.success(JsonPrimitive(signature), publicKey = publicKey)
    }

    private suspend fun broadcast(request: PendingRequest, w: Work.Broadcast) {
        val id = env.idempotencyKey()
        val final = CompletableDeferred<WalletSend>()
        withContext(env.dispatchers.main) {
            env.walletSends.send(request.username, w.ops, w.authority, id) { final.complete(it) }
        }
        val checking = scope.launch {
            env.walletSends.state.first { it.sends[id]?.phase is SendPhase.Checking }
            setState { it.copy(notice = com.ecency.core.api.broadcast.BroadcastException.UNDECIDED_MESSAGE) }
        }
        val result = try {
            final.await()
        } finally {
            checking.cancel()
            withContext(NonCancellable + env.dispatchers.main) { env.walletSends.disown(id) }
        }
        when (val phase = result.phase) {
            is SendPhase.Done -> request.answer.success(
                buildJsonObject {
                    phase.txId?.let {
                        put("id", it)
                        put("tx_id", it)
                    }
                },
                message = "Transaction broadcast successfully.",
            )
            is SendPhase.Cancelled -> request.answer.error(REJECTED)
            is SendPhase.Unconfirmed -> {
                setState { it.copy(notice = phase.message) }
                request.answer.error(phase.message)
            }
            is SendPhase.Failed -> request.answer.error(phase.error.message.ifEmpty { "Transaction failed." })
            else -> request.answer.error("Transaction failed.")
        }
    }

    /** Text a page wrote for the sheet, labelled as the page's and quoted. */
    private fun pageSays(origin: String, text: String) = LinkField(L.native.dappBrowser.messageFrom(host = BrowserPolicy.host(origin)), "\u201C$text\u201D")

    /** A waiting request of [tabId] is dropped: its page is gone, nobody is answered. */
    private fun dropPendingFor(tabId: Int) {
        if (pending?.tabId != tabId) return
        pending = null
        setState { it.copy(prompt = null) }
    }

    /** RN: savings withdraw and convert ids are seconds since the epoch plus a random 0..999, as uint32. */
    private fun requestId(): Long = (env.nowMillis() / 1000 + (com.ecency.core.secure.secureRandomBytes(2).let { ((it[0].toInt() and 0xff) shl 8) or (it[1].toInt() and 0xff) } % 1000)) and 0xFFFFFFFFL

    override fun close() {
        // the screen went away: a waiting request is refused, a confirmed one finishes in the app (WalletSends)
        pending?.answer?.error(REJECTED)
        pending = null
        super.close()
    }

    private fun hasLoneSurrogate(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c.isHighSurrogate()) {
                if (i + 1 >= s.length || !s[i + 1].isLowSurrogate()) return true
                i += 2
                continue
            }
            if (c.isLowSurrogate()) return true
            i++
        }
        return false
    }

    companion object {
        private fun initialState(url: String?): DappBrowserState {
            val start = url?.let(Dapps::normalizeUrl)?.takeIf { BrowserPolicy.navigation(it, true, true) == BrowserNavigation.LOAD }
            val tab = if (start != null) BrowserTab(id = 1, url = start, title = BrowserTab.LOADING_TITLE, load = 1) else BrowserTab(id = 1)
            return DappBrowserState(tabs = listOf(tab), activeTabId = 1, urlText = start.orEmpty())
        }

        /** Larger messages are ignored (a post body fits many times over). */
        const val MAX_MESSAGE = 2_000_000
        const val MAX_MESSAGE_TO_SIGN = 64_000
        private const val MAX_DETAIL = 2_000
        private const val MAX_TITLE = 120

        private val UNSUPPORTED = setOf(
            "signTx", "encode", "decode", "signedCall", "addAccount", "addAccountAuthority", "removeAccountAuthority",
            "addKeyAuthority", "removeKeyAuthority", "createClaimedAccount", "swap",
        )

        const val NO_ACCOUNT = "No account available. Please log in first."
        const val REJECTED = "User rejected the request."
        const val BUSY = "Another request is waiting for your answer."
        const val NOT_IN_FRONT = "The page is not in front."
        const val LOCKED = "Ecency is locked. Unlock it and try again."
        const val ACCOUNT_CHANGED = "The signed-in account changed. Please try again."
        val MESSAGE: String get() = L.profile.message

        fun mismatch(requested: String, user: String) = "Account mismatch: requested \"$requested\" but logged in as \"$user\""
    }
}

/** The signBuffer digest: sha256 of the message's UTF-8 bytes (the SDK's sha256 of a string). */
object BridgeSignatures {
    fun digest(message: String): ByteArray = com.ecency.core.hive.sha256(message.encodeToByteArray())
}

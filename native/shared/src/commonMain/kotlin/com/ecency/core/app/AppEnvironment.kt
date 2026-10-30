package com.ecency.core.app

import com.ecency.core.api.EcencyApi
import com.ecency.core.api.EcencyException
import com.ecency.core.api.broadcast.PayoutType
import com.ecency.core.api.model.Post
import com.ecency.core.api.model.PostKey
import com.ecency.core.api.mutedUsers
import com.ecency.core.api.repository.FeedSource
import com.ecency.core.api.repository.MemoryCache
import com.ecency.core.render.ImageProxy
import com.ecency.core.render.RenderHelper
import com.ecency.core.render.blocks.Block
import com.ecency.core.auth.AccountManager
import com.ecency.core.auth.ChainAccounts
import com.ecency.core.auth.EcencyTokenApi
import com.ecency.core.auth.HiveAuthClient
import com.ecency.core.auth.RpcChainAccounts
import com.ecency.core.auth.VaultAccountStore
import com.ecency.core.secure.KeyVault
import com.ecency.core.secure.KeyVaultAsync
import com.ecency.core.secure.PinLock
import com.ecency.core.secure.SecureStore
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.ecency.core.api.model.Mana
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Something one model did that others react to. */
sealed interface AppEvent {
    /** A reply was broadcast under [root]; its discussion should show it. */
    data class CommentPosted(val root: PostKey, val comment: PostKey) : AppEvent

    /** [key] changed on chain from this device (reblog, pin, mute...): screens showing it reload it. */
    data class PostChanged(val key: PostKey) : AppEvent

    /** [key] was deleted from this device: lists drop it, its screen goes back. */
    data class PostDeleted(val key: PostKey) : AppEvent

    /** The signed-in account muted [author] ("Mute / Block", or after a report): lists drop their posts. */
    data class AuthorMuted(val author: String) : AppEvent

    /** The signed-in account's ignore list arrived ([AppEnvironment.mutedAuthors]): lists drop those authors. */
    data object MutesLoaded : AppEvent

    /** The signed-in account changed (sign-in, sign-out). */
    data class SessionChanged(val username: String?) : AppEvent

    /**
     * Every account, key and credential was removed from the device (third wrong PIN, or "forgot PIN").
     * A [SessionChanged] with null follows; UIs drop their screens and show the signed-out app.
     */
    data object DataWiped : AppEvent

    /** [username]'s profile was edited here; profile screens show the new values. */
    data class ProfileUpdated(val username: String) : AppEvent

    /** [username]'s drafts or schedules changed on the server from this device; open lists reload. */
    data class DraftsChanged(val username: String) : AppEvent

    /** A wave was published from this device: the For you list shows it on top. */
    data class WavePosted(val key: PostKey) : AppEvent

    /** A share link asked for the wave composer ([WavesService.takeComposeText]). */
    data object WaveComposeRequested : AppEvent

    /** The app left the foreground ([AppLockModel.onBackground]): screens showing secrets close. */
    data object Backgrounded : AppEvent

    /**
     * The RN import failed in a way a retry does not fix ([AppLockModel]: a `PIN_KEY` that does not open the RN data,
     * or malformed data). [kind] is the error's class name and [attempts] the launches that failed so far; nothing
     * secret. For logs and the crash reporter (as a breadcrumb); [abandoned] is true on the last reported failure (the RN data is kept).
     */
    data class LegacyImportFailed(val kind: String, val attempts: Int, val abandoned: Boolean) : AppEvent

    /** The user confirmed "sign in again" after the secure storage was reset ([StorageResetModel]): the apps open the login screen. */
    data object SignInAgain : AppEvent
}

/**
 * The app layer's object graph: one per process, shared by every screen. Create it with
 * [create] (platform defaults) or the constructor (tests: fake transport, in-memory vault,
 * test dispatchers, fixed clock), then get models from it.
 */
@OptIn(ExperimentalTime::class)
class AppEnvironment(
    val api: EcencyApi,
    val vault: KeyVaultAsync,
    renderer: PostBodyRenderer,
    val dispatchers: AppDispatchers,
    /** Image proxy for avatars, card images and body images (settings "Image Server" changes it, [setImageServer]). */
    proxyBase: String,
    /** The debug-only "import posting key" sign-in. False in release builds. */
    val debugKeyImport: Boolean,
    val nowMillis: () -> Long,
    /** PIN hash cost and lock timeout; [SecurityConfig.DEFAULT] in the apps, a cheap hash in tests. */
    val security: SecurityConfig = SecurityConfig.DEFAULT,
    /** The RN app's data to import once, before the app lock decides anything; null when there is none to try. */
    val legacy: LegacyMigrationSource? = null,
    /** Token endpoint, HiveSigner API and HiveAuth relay; [AuthServices.offline] refuses every call. */
    val auth: AuthServices = AuthServices.offline(),
) {
    /** Without RN data to migrate (Swift sees no default arguments). */
    constructor(
        api: EcencyApi,
        vault: KeyVaultAsync,
        renderer: PostBodyRenderer,
        dispatchers: AppDispatchers,
        proxyBase: String,
        debugKeyImport: Boolean,
        nowMillis: () -> Long,
        security: SecurityConfig,
    ) : this(api, vault, renderer, dispatchers, proxyBase, debugKeyImport, nowMillis, security, null)

    /** Post bodies to blocks; replaced with the image proxy ([setImageServer]). */
    @kotlin.concurrent.Volatile
    var renderer: PostBodyRenderer = renderer
        private set

    /** Image proxy for avatars, card images and body images; [setImageServer] changes it. */
    @kotlin.concurrent.Volatile
    var proxyBase: String = proxyBase
        private set

    /**
     * RN setImageServer / setProxyBase: images from now on go through [base]. Rendered bodies are dropped from the
     * cache key holds the proxy, so a post opened again is rendered with it; cards and rows already built keep their URLs until they are rebuilt.
     * Main thread.
     */
    internal fun setImageServer(base: String) {
        if (base == proxyBase) return
        proxyBase = base
        renderer = renderer.withProxyBase(base)
    }

    /** Accounts on chain (read-only), for sign-in and the auth upgrade's key check. */
    val chain: ChainAccounts = RpcChainAccounts(api.client)

    /** Multi-account sign-in and the Ecency session over the vault. */
    val accounts: AccountManager = AccountManager(
        VaultAccountStore(vault.vault, vault.vault.store), chain,
        EcencyTokenApi(auth.http, auth.tokenHost), nowMillis, dispatchers.io,
    )

    /** The HiveAuth relay client (sign-in and wallet signing). */
    val hiveAuth: HiveAuthClient = HiveAuthClient(auth.sockets, nowMillis, auth.hiveAuthHost)

    /** Run after every sign-in (RN usePostLogin). Main thread only. */
    val postLoginHooks: MutableList<PostLoginHook> = mutableListOf()

    /** Every broadcast and its prompts (auth upgrade, HiveSigner page, HiveAuth wallet). One per app. */
    val signing: SigningModel by lazy { SigningModel(this) }

    /** The wallet's reads (portfolio, history, pending requests, delegations, market, points, engine). */
    val walletApi: com.ecency.core.api.wallet.WalletApi by lazy {
        com.ecency.core.api.wallet.WalletApi(api.client, rest, auth.http, com.ecency.core.auth.EcencyPrivateApi(auth.http, accounts, auth.tokenHost), auth.tokenHost)
    }

    /** The signed-in account's portfolio and reward claims (the Wallet tab). One per app. */
    val wallet: WalletModel by lazy { WalletModel(this) }

    /** What a repeated history row (or a transfer link) prefills on the next transfer screen: taken once, in memory. Main thread. */
    var transferPrefill: TransferPrefill? = null

    /** RN assetDetailsScreen for [symbol] of the signed-in account. */
    fun assetDetailsModel(symbol: String): AssetDetailsModel = AssetDetailsModel(this, symbol)

    /** RN balanceAnalyticsSheet for [username]'s HIVE, HBD or VESTS (HP). */
    fun balanceAnalyticsModel(username: String, coinType: String): BalanceAnalyticsModel = BalanceAnalyticsModel(this, username, coinType)

    /** RN delegationsModal of [account] ([sheet] outgoing or incoming). */
    fun delegationsModel(account: String, sheet: DetailsSheet): DelegationsModel = DelegationsModel(this, account, sheet)

    /**
     * RN TransferView for [transferType] of [fund] on [layer] (null when another screen does it: see
     * [TransferKind.of]); [referredUsername], [initialAmount] and [initialMemo] prefill it (a repeated history row, a link).
     */
    fun transferModel(transferType: String, fund: String, layer: com.ecency.core.api.wallet.AssetLayer, referredUsername: String, initialAmount: String, initialMemo: String): TransferModel? =
        TransferKind.of(transferType, layer, fund)?.let { TransferModel(this, it, if (it == TransferKind.POINTS) "POINT" else fund, layer, referredUsername, initialAmount, initialMemo) }

    /** The model for a transfer screen entry: its prefill (if it was made for this screen) is taken once. */
    fun transferModel(screen: AppScreen.Transfer): TransferModel? {
        val layer = com.ecency.core.api.wallet.AssetLayer.of(screen.layer) ?: com.ecency.core.api.wallet.AssetLayer.HIVE
        val prefill = transferPrefill?.takeIf { it.transferType == screen.transferType && it.fund == screen.fund && it.recipient == screen.recipient }
        if (prefill != null) transferPrefill = null
        return transferModel(screen.transferType, screen.fund, layer, screen.recipient, prefill?.amount.orEmpty(), prefill?.memo.orEmpty())
    }

    fun delegateModel(referredUsername: String): DelegateModel = DelegateModel(this, referredUsername)

    /** RN powerDownScreen; [routesOnly] for `set_withdraw_vesting_route`. */
    fun powerDownModel(routesOnly: Boolean): PowerDownModel = PowerDownModel(this, routesOnly)

    fun tradeModel(fund: String): TradeModel = TradeModel(this, fund)

    fun redeemModel(kind: RedeemKind, permlink: String): RedeemModel = RedeemModel(this, kind, permlink)

    fun tipModel(author: String, permlink: String): TipModel = TipModel(this, author, permlink)

    /** The tip count beside a post's gift (RN postDisplayView tipsQuery). */
    fun postTipsModel(author: String, permlink: String): PostTipsModel = PostTipsModel(this, author, permlink)

    /** A tip that went through, by post: the post screens showing its count read it again (RN refetches tipsQuery). */
    internal val tipped = kotlinx.coroutines.flow.MutableSharedFlow<PostKey>(extraBufferCapacity = 16)

    fun manageAssetsModel(): ManageAssetsModel = ManageAssetsModel(this)

    /** RN TRANSFER_FAVORITES: the favourite accounts to pick a recipient from. */
    fun transferFavoritesModel(): TransferFavoritesModel = TransferFavoritesModel(this)

    /** The restart journal of undecided wallet sends ([SendJournal]). The apps call [SendJournal.start] at launch. */
    val sendJournal: SendJournal by lazy { SendJournal(this) }

    /** Wallet broadcasts in flight, one per user action, app-wide ([WalletSends]). */
    val walletSends: WalletSends by lazy { WalletSends(this) }

    /** hive://, ecency:// and ecency.com auth links. One per app. */
    val authLinks: AuthLinkModel by lazy { AuthLinkModel(this) }

    /** Content shared into the app (Android share intents, the iOS share extension). One per app. */
    val share: ShareModel by lazy { ShareModel(this) }

    /** The QR scanner of one open (RN QR_SCAN). */
    fun qrScanModel(purpose: QrPurpose): QrScanModel = QrScanModel(this, purpose)

    /** The dApp browser of one screen (RN DappBrowser), optionally starting on [url]. */
    fun dappBrowserModel(url: String?): DappBrowserModel = DappBrowserModel(this, url)

    /** database_api proposal reads (SDK proposals module). */
    val proposalApi: ProposalApi by lazy { ProposalApi(api.client) }

    /** RN ProposalVoteRequest: the proposal card on the main feed. One per app. */
    val proposalVote: ProposalVoteModel by lazy { ProposalVoteModel(this) }

    val localVotes = LocalVotes()

    /** Non-secret preferences (feed tabs, content filters). Each platform sets its own store at start. */
    var prefs: Prefs = InMemoryPrefs()

    private val mutableEvents = MutableSharedFlow<AppEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<AppEvent> = mutableEvents.asSharedFlow()

    internal fun emit(event: AppEvent) {
        mutableEvents.tryEmit(event)
    }

    private val eventScope by lazy { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + dispatchers.main) }

    /** Swift: every [AppEvent] from now on, on the main thread, until the returned [Closeable] is closed. */
    fun watchEvents(onEach: (AppEvent) -> Unit): Closeable =
        Closeable(eventScope.launch { events.collect { onEach(it) } })

    /**
     * The app's UI catalog follows [SettingsModel]'s language ([com.ecency.core.l10n.L10n.setLanguage]). The apps turn
     * it on at start; tests that do not leave it off, so one test's language never shows in another test's copy (the
     * catalog is one per process).
     */
    var drivesLanguageCatalog: Boolean = false

    /** The OS per-app language setting, where the OS has one (Android 13+, iOS); null: nothing to sync. */
    var systemLanguage: com.ecency.core.l10n.SystemLanguage? = null

    /** Swift: the catalog language ([com.ecency.core.l10n.L10n.language]) now and on every change, on the main thread. */
    fun watchLanguage(onEach: (String) -> Unit): Closeable =
        Closeable(eventScope.launch { com.ecency.core.l10n.L10n.language.collect { onEach(it) } })

    /** Rendered bodies by post and last update, so reopening a post never re-renders it. */
    private val bodies = MemoryCache<BodyKey, List<Block>>(BODY_CACHE_SIZE, Long.MAX_VALUE / 4, nowMillis)

    private data class BodyKey(val key: PostKey, val updated: String, val proxy: String)

    /** Blocks for [post]: from the cache, else rendered on [AppDispatchers.render]. */
    internal suspend fun renderBody(post: Post): List<Block> {
        val r = renderer
        val key = BodyKey(post.key, post.updated, proxyBase)
        bodies.get(key)?.let { return it }
        val blocks = withContext(dispatchers.render) { r.blocks(post) }
        bodies.put(key, blocks)
        return blocks
    }

    internal suspend fun cachedBody(post: Post): List<Block>? = bodies.get(BodyKey(post.key, post.updated, proxyBase))

    /** The signed-in account and its vote/reply state. One per app. */
    val session: SessionModel by lazy { SessionModel(this) }

    /** The PIN behind the app lock (blocking; models call it on [AppDispatchers.io]). */
    val pinLock: PinLock by lazy {
        // a session renewal in flight is voided before the wipe too (RN bumps before and after a removal)
        PinLock(vault.vault, security.pinIterations).also { lock ->
            lock.beforeWipe = { accounts.accountsWiped() }
            // the RN copies of the keys, tokens and PINs go with the wipe, not only at the next start's migration
            lock.afterWipe = { legacy?.storage?.let { com.ecency.core.legacy.LegacyCleanup.scrub(it) } }
        }
    }

    /** The app lock: lock screen, PIN settings flows, background timeout. One per app. */
    val appLock: AppLockModel by lazy { AppLockModel(this) }

    /** The blocking "sign in again" prompt after the device key of the secure storage was lost. One per app, listening from the start. */
    val storageReset: StorageResetModel = StorageResetModel(this)

    // ---- accounts and shell gaps (RegisterModel.kt, Welcome.kt, OnboardingApi.kt) ----

    /** Signup, referrals, announcements, the deletion request and the release lookup. */
    val onboardingApi: com.ecency.core.api.ecency.OnboardingApi by lazy {
        com.ecency.core.api.ecency.OnboardingApi(auth.http, com.ecency.core.auth.EcencyPrivateApi(auth.http, accounts, auth.tokenHost), auth.tokenHost)
    }

    /** RN's welcome screen decision and the stored terms acceptance. */
    val welcome: WelcomeGate by lazy { WelcomeGate({ prefs }, appVersion) }

    /** The running app's version (RN `VersionNumber.appVersion`); the apps set their own at start. */
    var appVersion: String = com.ecency.core.EcencyCore.VERSION

    /** What a signup link carried, waiting for the register screen ([registerModel] takes it once). Main thread. */
    var signupPrefill: SignupPrefill? = null

    /**
     * Debug fake-data runs only: the token the register screen uses in place of the Turnstile challenge, so a demo
     * never loads the hosted page. Null in every real build.
     */
    var captchaStandIn: String? = null

    /** RN register screen; [referral] comes from the screen's saved argument, the rest from [signupPrefill]. */
    fun registerModel(referral: String): RegisterModel {
        val prefill = signupPrefill ?: SignupPrefill()
        signupPrefill = null
        return RegisterModel(this, prefill.copy(referral = prefill.referral.ifEmpty { referral }))
    }

    /** Notices about an account's keys on this device (no signing key left, password missing roles, key shared with owner). One per app. */
    val accountNotices: AccountNoticesModel by lazy { AccountNoticesModel(this) }

    /** RN's "Delete Account" (Settings): the confirmation, the request and the sign-out. One per app. */
    val deleteAccount: DeleteAccountModel by lazy { DeleteAccountModel(this) }

    /** Where the update check looks; each app sets it from its build settings at start ([UpdateSource.of]). */
    var updateSource: UpdateSource = UpdateSource()

    /** RN's update check at app start. One per app. */
    val updates: UpdateModel by lazy { UpdateModel(this) }

    /** RN's announcement prompt. One per app. */
    val announcements: AnnouncementsModel by lazy { AnnouncementsModel(this) }

    internal val welcomeDone = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** How many keys screens are up ([BackupKeysModel], from its start until it shut): no root prompt opens over one. */
    internal val keysScreens = kotlinx.coroutines.flow.MutableStateFlow(0)

    /**
     * The app's root reports whether the welcome screen is done (the terms accepted for this version). Until it
     * says so no root prompt shows and the rating prompt is not asked for.
     */
    fun setWelcomeDone(done: Boolean) {
        welcomeDone.value = done
    }

    /** Whose turn it is among the prompts that open by themselves ([RootPrompt]). One per app. */
    val rootPrompts: RootPromptsModel by lazy { RootPromptsModel(this) }

    /** The app's root is up (unlocked or not): the update check and the announcements start. Safe to call again. */
    fun shellStarted() {
        updates.check()
        announcements.start()
        // RN useInitApplication: points reports that failed earlier are sent again 3 s after the start, and after
        // an unlock (a report refused while the lock hid the app is parked)
        pointActivities.start()
        pointActivities.replayPending()
    }

    /** "Get started!" on the welcome screen: stores it, then the announcements get their turn (RN's effect re-runs). */
    fun acceptWelcome(consent: Boolean): Boolean {
        val accepted = welcome.accept(consent)
        if (accepted) announcements.check()
        return accepted
    }

    /** The Waves tab's "Getting started" card of the signed-in account (RN WavesOnboardingChecklist). One per app. */
    val wavesOnboarding: WavesOnboardingModel by lazy { WavesOnboardingModel(this) }

    /** RN useCheckIn: the daily check-in, at most once per 15 minutes and account. One per app. */
    val checkIn: CheckInRecorder by lazy { CheckInRecorder(this) }

    // ---- points and perks (Perks.kt, SpinGame.kt, PointActivities.kt) ----

    /** Quests, the spin game, the streak freeze and points activities on Ecency's private API. */
    val perksApi: com.ecency.core.api.ecency.PerksApi by lazy { com.ecency.core.api.ecency.PerksApi(checkInApi, auth.http, auth.tokenHost) }

    /** The points earning reports (vote, post, comment, reblog, login, check-in), parked and replayed when they fail. */
    val pointActivities: PointActivities by lazy { PointActivities(this) }

    /** The quest numbers asked again a minute after an account's activity. */
    val questRefreshes: QuestRefreshes by lazy { QuestRefreshes(this) }

    /** Streak freeze buys, app-wide per account, with their kept idempotency keys. */
    val streakFreezeBuys: StreakFreezeBuys by lazy { StreakFreezeBuys(this) }

    /** The spin game's status requests, numbered app-wide. */
    internal val spinStatusRequests = SpinStatusRequests()

    // ---- in-app purchases, crash reports, analytics (Purchases.kt, StoreScreens.kt, CrashReports.kt, Analytics.kt) ----

    /** The device's app store; each platform sets its own before [PurchaseService.start]. [StorePlatform.None] sells nothing. */
    var store: StorePlatform = StorePlatform.None

    /** Sentry, when the build has a DSN; each platform sets its adapter at start. */
    var crashReporter: CrashReporter = CrashReporter.None

    /** Store products, purchases and Ecency orders. One per app. */
    val purchases: PurchaseService by lazy { PurchaseService(this) }

    /** RN boostScreen, accountBoostScreen and the spin game's pack; [username] and [productId] come from the screen's link. */
    fun storeScreenModel(kind: StoreScreenKind, username: String, productId: String): StoreScreenModel = StoreScreenModel(this, kind, username, productId)

    /** Plausible pageviews (RN usePlausibleTracker). Each app sets [PlausibleAnalytics.config] at start. */
    val analytics: PlausibleAnalytics by lazy { PlausibleAnalytics(this, auth.http) }

    fun perksModel(): PerksModel = PerksModel(this)

    /** The Pro card and sheet on Perks (Pro.kt). */
    fun proModel(): ProModel = ProModel(this)

    /** The latest Pro purchase per account (Pro.kt). One per app. */
    val proPurchases: ProPurchases by lazy { ProPurchases(dispatchers) { proMembers.isPro(it) } }

    fun spinGameModel(): SpinGameModel = SpinGameModel(this)

    internal val checkInApi: com.ecency.core.auth.EcencyPrivateApi by lazy { com.ecency.core.auth.EcencyPrivateApi(auth.http, accounts, auth.tokenHost) }

    /** The signed-in account's "Refer & Earn" screen. */
    fun referModel(): ReferModel = ReferModel(this)

    /** The keys screen of the signed-in account ("Manage Authorities"); the screen closes it when it goes away. */
    fun backupKeysModel(): BackupKeysModel = BackupKeysModel(this)

    /** Toasts and in-app banners for every screen. One per app. */
    val ui: UiMessages by lazy { UiMessages(dispatchers) }

    /** RN reply editor's account tile: the reply opens again for the account switched to. One per app. */
    val replyHandoff: ReplyAccountHandoff by lazy { ReplyAccountHandoff(this) }

    /** RN's walkthrough red dots (device-wide, imported from the RN app). One per app. */
    val walkthrough: WalkthroughModel by lazy { WalkthroughModel({ prefs }, dispatchers) }

    /** RN's feature spotlight card at the top of the first home feed tab. One per app. */
    val spotlight: SpotlightModel by lazy { SpotlightModel(this) }

    /** RN's app-wide "Not enough Resource Credits" offer ([offerRcIfShort]). One per app. */
    val rcOffer: RcOfferModel by lazy { RcOfferModel(dispatchers) }

    /** Online or not, fed by the platform's network monitor. One per app. */
    val connectivity: ConnectivityModel by lazy { ConnectivityModel(dispatchers) }

    /** Unread counts for the tab icons. One per app. */
    val badges: BadgeModel by lazy { BadgeModel(dispatchers) }

    // ---- notifications and push (NotificationsModel.kt, PushModel.kt) ----

    /** The notification list, unread count, mark as read, device registration and leaderboard calls. */
    val notificationsApi: com.ecency.core.api.ecency.NotificationsApi by lazy {
        com.ecency.core.api.ecency.NotificationsApi(com.ecency.core.auth.EcencyPrivateApi(auth.http, accounts, auth.tokenHost), auth.http, auth.tokenHost)
    }

    /** The signed-in account's notifications and unread count. One per app. */
    val notifications: NotificationsModel by lazy { NotificationsModel(this) }

    /** Push registration, settings, tapped and foreground pushes, the websocket fallback. One per app. */
    val push: PushModel by lazy { PushModel(this) }

    /** The device's push service; each platform sets its own before [PushModel.start]. Without one, push is inactive. */
    var pushPlatform: PushPlatform = PushPlatform.None

    /** RN `ACTIVITY_WEBSOCKET_URL` (a build setting, `wss://`); null or blank: no websocket fallback. */
    var activityWebsocketUrl: String? = null

    fun leaderboardModel(): LeaderboardModel = LeaderboardModel(this)

    // ---- chats (ChatService.kt, ChatsModel.kt, ChatThreadModel.kt, ChatSheets.kt) ----

    /**
     * RN `ECENCY_BACKEND_API`, where the chat API (`/api/mattermost/...`) and its websocket live: the Ecency host by
     * default; a build may name another first-party https host ([setChatHost]). Main thread, before chats start.
     */
    var chatHost: String = auth.tokenHost
        private set

    /** A build's chat host; anything but a bare first-party `https://host` (no port, path, query or fragment) is ignored (the chat token never leaves Ecency). */
    fun setChatHost(host: String?) {
        com.ecency.core.api.chat.ChatApi.canonicalBase(host?.trim())?.let { chatHost = it }
    }

    /** Chat sessions, the chat badge and the chat screens' models. One per app. */
    val chat: ChatService by lazy { ChatService(this) }

    // ---- communities and search (CommunityService.kt, CommunityModels.kt, SearchModels.kt) ----

    /** Community, subscriber, account lookup and trending tag reads. */
    val communityApi: com.ecency.core.api.community.CommunityApi by lazy { com.ecency.core.api.community.CommunityApi(api.client) }

    /** Ecency's search API over the same HTTP client as the token endpoint. */
    val searchApi: com.ecency.core.api.search.SearchApi by lazy { com.ecency.core.api.search.SearchApi(auth.http, auth.tokenHost) }

    /** Subscriptions and community operations. One per app. */
    val communities: CommunityService by lazy { CommunityService(this) }

    /**
     * The side menu header for [username]. RN fetches the account each time the menu opens; here the account
     * cache answers for up to a minute (its TTL), so opening and closing the menu does not hit the node each
     * time. Without the account (offline, unknown) the header still shows the name, with no voting power.
     */
    @Throws(CancellationException::class)
    suspend fun drawerHeader(username: String): DrawerHeader {
        val account = try {
            api.accounts.getAccount(username)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val vp = account?.let { Mana.oneDecimal(Mana.votingPowerPercent(it, nowMillis())) } ?: "0"
        return DrawerHeader(username, account?.profile?.displayName, vp)
    }

    /**
     * After a wipe: forget everything held in memory for the removed accounts (session, votes, cached
     * posts and discussions with their per-account vote state), then tell the UIs.
     */
    internal suspend fun resetAfterWipe() {
        // and after it: a renewal that read the vault during the wipe never writes into it, nor over a sign-in after it
        accounts.accountsWiped()
        // every signing prompt closes, and so does each one still waiting its turn: none redraws after the unlock
        guardedStep { withContext(dispatchers.main) { signing.forgetAll() } }
        // the wipe protects what is on the device: every local draft, reply draft and recovery copy goes too
        accountsLeft(drafts = null, forget = true) { push.wiped() }
        guardedStep { api.posts.clearUserData() }
        // before the wipe's event: the models that reload on it must find these gone
        guardedStep { AccountPrefs.clear(prefs) }
        session.onWiped()
        emit(AppEvent.DataWiped)
        emit(AppEvent.SessionChanged(null))
    }

    /**
     * Accounts left the device: the one teardown path of logout, logout-all and the wipe. Four steps in a fixed
     * order, each on its own, so a failure or a timeout in one never skips the next and none holds the caller:
     *  1. [push]: the leaving rows are remembered as pending at once (device prefs); their requests run in the
     *     background ([PushModel.release], [PushModel.wiped]);
     *  2. what is held in memory for the account that was current ([forgetSessionState]), when [forget];
     *  3. local drafts on the device, of [drafts] or of every account (null), for at most
     *     [LocalDrafts.CLEAR_BOUND_MILLIS]. The clear first marks those accounts as gone ([LocalDrafts.departed]):
     *     a screen that closes on the session event after this and flushes its text writes nothing back. The
     *     unanswered paid requests (AI assist, AI image, dictation) live in the same store and go with it;
     *  4. dictation clips of the leaving accounts ([DictationClips.purgeLeft]: a clip's name carries its owner's
     *     tag), or every clip on a wipe. Another account's waiting clip stays, with its paid request. Clips without
     *     a tag (an older build) go on any departure. A recording in progress is thrown away in the last step
     *     (its sheet also stops it on the session change).
     * Last, when the current account left ([forget]), every background session ([backgroundSessions],
     * [dictationSessions]): Listen streams with their lock screen card and media notification, and a dictation with
     * its microphone notification.
     * A picked or recorded video is held by its upload model, which lets it go when its owner left
     * ([VideoUploadModel]); the camera folder itself is shared and only emptied by a wipe.
     * The vault is already changed when this runs, so it is not cancellable: a cancelled logout still cleans up.
     */
    internal suspend fun accountsLeft(drafts: List<String>?, forget: Boolean, push: () -> Unit) = withContext(kotlinx.coroutines.NonCancellable) {
        guardedStep { push() }
        if (forget) guardedStep { forgetSessionState() }
        guardedStep { if (drafts == null) localDrafts.clearAll() else drafts.forEach { localDrafts.clear(it) } }
        guardedStep { dictationRecorder?.let { DictationClips.purgeLeft(it, drafts) } }
        // the restart journal names the account: it leaves with it (a wipe clears the store, this the memory)
        guardedStep { sendJournal.forget(drafts) }
        // nothing signs again for an account that left (a pending holds its key in memory)
        guardedStep { kotlinx.coroutines.withContext(dispatchers.main) { walletSends.accountsLeft(drafts) } }
        // nor does a broadcast retry the broadcaster keeps after a network error (it holds the key too)
        guardedStep { if (drafts == null) api.broadcaster.clear() else drafts.forEach { api.broadcaster.forgetAccount(it) } }
        // a chat token never outlives its account (RN setChatApiToken(null) + clearMattermostBootstrapCache on logout)
        guardedStep { kotlinx.coroutines.withContext(dispatchers.main) { chat.forget(drafts) } }
        // no Listen audio, lock screen card, media notification or dictation outlives the account that was current
        // (README "Background media"); another account leaving leaves the current one's audio alone
        if (forget) guardedStep { kotlinx.coroutines.withContext(dispatchers.main) { dictationSessions.stopAll(); backgroundSessions.stopAll() } }
        // the widgets' unread count and streak go with it (README "Widgets")
        if (forget) guardedStep { kotlinx.coroutines.withContext(dispatchers.main) { widgets.accountsLeft() } }
    }

    /** One teardown step: whatever it throws stays with it. */
    private inline fun guardedStep(block: () -> Unit) {
        try {
            block()
        } catch (_: Exception) {
            // the steps after this one still run
        }
    }

    /**
     * The account changed (sign-in, sign-out, wipe): forget what belonged to the previous one, its
     * optimistic votes, vote statuses (results of votes still in flight are ignored) and reply drafts.
     * Feed and post models refresh themselves on the [AppEvent.SessionChanged] that follows. Every part
     * is cleared on its own: one that throws never leaves the others holding the previous account's state.
     */
    internal suspend fun forgetSessionState() = withContext(dispatchers.main) {
        guardedStep { localVotes.clear() }
        guardedStep { mutedAuthors.clear() }
        guardedStep { detectedLanguages.clear() }
        guardedStep { votes.reset() }
        guardedStep { notifications.forgetAccount() }
        guardedStep { waves.accountChanging(session.current.username) }
        guardedStep { ui.clear() }
        guardedStep { rcOffer.clear() }
        guardedStep { signing.tempActiveKey.clear() }
        guardedStep { replies.values.toList().forEach { it.dispose() } }
        replies.clear()
        waves.reset()
    }

    // ---- waves and shorts (WavesModels.kt, WaveCompose.kt) ----

    /** Waves API, the waves published here, the wave composer. One per app. */
    val waves: WavesService by lazy { WavesService(this) }

    /** The Waves tab's tabs and their lists. */
    fun wavesTabsModel(): WavesTabsModel = WavesTabsModel(this)

    /** One author's waves (the profile's Waves tab). */
    fun authorWavesModel(username: String): WavesFeedModel = WavesFeedModel(this, WavesFeedKind.Author(username))

    /** Reply drafts by parent, app-wide: they outlive the post screen (sends keep running). Main thread only. */
    private val replies = mutableMapOf<PostKey, ComposeReplyModel>()

    /** The Hive node pool and the settings server picker ([HiveNodeSettings]). The apps call [HiveNodeSettings.start] at launch. */
    val hiveNodes: HiveNodeSettings by lazy { HiveNodeSettings(this) }

    /** True in the apps' real builds ([create] without a demo Hive transport): [hiveNodes] may change the pool. */
    var managesHiveNodes: Boolean = false

    /** Ecency's reading services (promoted and similar entries, edit history, bots, translation, polls). */
    val rest: com.ecency.core.api.EcencyRestApi by lazy {
        com.ecency.core.api.EcencyRestApi(auth.http, auth.tokenHost)
    }

    private var bots: Set<String>? = null

    /**
     * Known bot accounts (RN useBotAuthorsQuery, `public/bots`): their top-level comments go to the bot comments
     * list. Fetched once per process; a failure means none for now and is retried on the next discussion.
     */
    @Throws(CancellationException::class)
    suspend fun botAuthors(): Set<String> {
        bots?.let { return it }
        return try {
            rest.bots().also { bots = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptySet()
        }
    }

    /** The post options menu and its actions. One per app. */
    val postActions: PostActionsModel by lazy { PostActionsModel(this) }

    /** Votes for every screen. One per app. */
    val votes: VoteModel by lazy { VoteModel(this) }

    fun feedModel(source: FeedSource): FeedModel = FeedModel(this, source)

    // ---- profile, social graph and bookmarks ----

    /** Bookmarks, favourites, followed tags, report and the Pro list (Ecency API, with the account's code). */
    val accountApi: com.ecency.core.api.ecency.EcencyAccountApi by lazy {
        com.ecency.core.api.ecency.EcencyAccountApi(
            com.ecency.core.auth.EcencyPrivateApi(auth.http, accounts, auth.tokenHost), auth.http, auth.tokenHost, nowMillis,
        )
    }

    /** Avatar URLs stamped after a profile edit. One per app. */
    val avatars: AvatarStamps by lazy { AvatarStamps(dispatchers) }

    /** Ecency Pro members for the badge. One per app. */
    val proMembers: ProMembersModel by lazy { ProMembersModel(this) }

    /** [username]'s profile; [section] is a deep link's tab ("wallet", "comments", ...), empty for the default. */
    fun profileModel(username: String, section: String): ProfileModel = ProfileModel(this, username, section)

    /** Followers ([following] false) or following of [username]; [count] is the profile's count (-1: unknown). */
    fun followsModel(username: String, following: Boolean, count: Int): FollowsModel =
        FollowsModel(this, username, following, count.takeIf { it >= 0 })

    /** The signed-in account's profile edit. */
    fun profileEditModel(): ProfileEditModel = ProfileEditModel(this)

    /** The signed-in account's bookmarks screen, opened on [tab] (0 bookmarks, 1 favourites, 2 tags). */
    fun bookmarksModel(tab: Int): BookmarksModel = BookmarksModel(this, tab)

    // ---- feed, posts and reading ----

    /** A feed with Ecency's promoted entries injected (RN: the home feed and the tag screen). */
    fun feedModel(source: FeedSource, withPromoted: Boolean): FeedModel = FeedModel(this, source, withPromoted)

    /**
     * Authors the signed-in account ignores (RN currentAccount.mutes): the chain's list from sign-in
     * ([loadMutes]) plus every mute and unmute made in the app ([setAuthorMuted]). Main thread.
     */
    val mutedAuthors: MutableSet<String> = mutableSetOf()

    /**
     * A mute or unmute of [author] went through, from any screen (post menu, profile, report). Muting drops the
     * author's posts from every open list at once ([AppEvent.AuthorMuted]); unmuting (or following, which
     * replaces the ignore on chain) only stops the filtering, as RN: lists show the author again when reloaded.
     */
    fun setAuthorMuted(author: String, muted: Boolean) {
        if (!muted) {
            mutedAuthors -= author
        } else if (mutedAuthors.add(author)) {
            emit(AppEvent.AuthorMuted(author))
        }
    }

    /**
     * RN auth.ts `account.mutes`: the account's ignore list, loaded at sign-in so feeds, promoted entries and
     * other lists without an observer drop those authors. A failure leaves the list as it is.
     */
    internal suspend fun loadMutes(username: String) {
        val list = try {
            api.hive.mutedUsers(username)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        }
        withContext(dispatchers.main) {
            if (session.current.username != username) return@withContext
            mutedAuthors += list
            emit(AppEvent.MutesLoaded)
        }
    }

    /** Detected post languages for this session (translation banner). Main thread. */
    val detectedLanguages = DetectedLanguages()

    /** Muted cards the reader revealed this session (RN useMutedReveal). */
    val mutedReveals = MutedReveals()

    /** Images revealed one by one while "Show Images" is off (RN revealedImages, session only). */
    val imageReveals = ImageReveals()

    fun postModel(author: String, permlink: String): PostModel = PostModel(this, PostKey(author, permlink))

    fun votersModel(author: String, permlink: String): VotersModel = VotersModel(this, PostKey(author, permlink))

    fun reblogsModel(author: String, permlink: String): ReblogsModel = ReblogsModel(this, PostKey(author, permlink))

    fun editHistoryModel(author: String, permlink: String): EditHistoryModel = EditHistoryModel(this, PostKey(author, permlink))

    fun tagFollowModel(tag: String): TagFollowModel = TagFollowModel(this, tag)

    fun quickProfileModel(username: String): QuickProfileModel = QuickProfileModel(this, username)

    fun similarEntriesModel(): SimilarEntriesModel = SimilarEntriesModel(this)

    /** [readerLanguage] is the app or device language (RN resolveReaderLang). */
    fun translateModel(author: String, permlink: String, readerLanguage: String): TranslateModel =
        TranslateModel(this, PostKey(author, permlink), readerLanguage)

    /** RN postReadingMetadata's AI recap of a post (paid, see [RecapModel]). */
    fun recapModel(author: String, permlink: String, readerLanguage: String): RecapModel = RecapModel(this, PostKey(author, permlink), readerLanguage)

    fun translationSheetModel(post: Post, readerLanguage: String): TranslationSheetModel = TranslationSheetModel(this, post, readerLanguage)

    /** The poll of [post], or null when its json_metadata has none. */
    fun pollModel(post: Post): PollModel? =
        com.ecency.core.api.PostPoll.fromMetadata(post.metadata.rawJson)?.let { PollModel(this, post.key, it) }

    /** [appVersion] goes into the cross post's `app` ("ecency/<version>-mobile"). */
    fun crossPostModel(post: Post, appVersion: String): CrossPostModel = CrossPostModel(this, post, appVersion)

    /**
     * The reply composer for [parent], shown in the discussion of [root]. One per parent for the whole
     * app (call on the main thread): reopening the composer, even from a new post screen, gets the same
     * draft back, and a send survives the screen. Screens do not close it.
     */
    fun replyModel(root: PostKey, parent: Post): ComposeReplyModel = replyModel(root, parent, PayoutType.DEFAULT)

    internal fun replyModel(root: PostKey, parent: Post, payout: PayoutType): ComposeReplyModel =
        replies.getOrPut(parent.key) { ComposeReplyModel(this, root, parent, payout) }

    /**
     * [model] sent its reply: the next composer for that parent starts empty with a model of its own
     * (a UI that closes on `sent` would otherwise close it at once), and sent drafts do not pile up.
     */
    internal fun replySent(model: ComposeReplyModel) {
        if (replies[model.parent.key] === model) replies.remove(model.parent.key)
    }

    // ---- editor and publishing (EditorModel.kt, EditorSideModels.kt) ----

    /** Work that must finish after its screen is gone (a draft save on leaving the editor). Main dispatcher. */
    internal val background by lazy { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + dispatchers.main) }

    /** Drafts, schedules, the image gallery, snippets and support settings (Ecency API, with the account's code). */
    val editorApi: com.ecency.core.api.ecency.EditorApi by lazy {
        com.ecency.core.api.ecency.EditorApi(com.ecency.core.auth.EcencyPrivateApi(auth.http, accounts, auth.tokenHost))
    }

    /** Where local drafts are kept; each platform sets its file store at start. */
    var draftStorage: DraftStorage = InMemoryDraftStorage()

    val localDrafts: LocalDrafts by lazy { LocalDrafts(object : DraftStorage {
        override fun read(account: String, slot: String) = draftStorage.read(account, slot)
        override fun write(account: String, slot: String, value: String?) = draftStorage.write(account, slot, value)
        override fun clear(account: String) = draftStorage.clear(account)
        override fun clearAll() = draftStorage.clearAll()
    }, dispatchers) }

    /** The image host transport; refuses everything until an app sets one ([create] sets Ktor's). */
    var imageTransport: com.ecency.core.api.ecency.ImageUploadTransport = com.ecency.core.api.ecency.ImageUploadTransport.refusing()

    /** The image host uploads go to. */
    var imageHost: String = com.ecency.core.api.ecency.ImageUploader.DEFAULT_HOST

    /** Pause before upload retry [attempt] (0 based): RN's 1 s then 2 s. Tests shorten it. */
    var uploadRetryDelay: (attempt: Int) -> Long = { minOf(1000L shl it, 10_000L) }

    /** Width / height of images uploaded in this session, for `image_ratios` (RN measures every image at publish). */
    internal val knownImageRatios = mutableMapOf<String, Double>()

    internal fun imageRatios(url: String): Double? = knownImageRatios[url]

    /** RN generateRndStr: the random suffix of a permlink whose title is taken. */
    var randomString: () -> String = { kotlin.random.Random.nextLong(1L shl 40, 1L shl 52).toString(16) }

    /** RN's store rating rules; sessions are counted at app start ([SessionModel.start]). */
    val rating: AppRating by lazy { AppRating({ prefs }, nowMillis) }

    /**
     * Shows the platform's in-app review prompt (iOS StoreKit). Returns whether it was asked for. Null: the
     * platform has none wired, or uses [requestReviewAsync].
     */
    var requestReview: (() -> Boolean)? = null

    /**
     * As [requestReview] for a platform whose prompt answers later (Android: the Play In-App Review flow). True
     * once the flow ran to its end; false when it could not (no Play Store, the app not in front), which leaves
     * the one request unspent, as RN's `requestInAppReview` returning false does. Used instead of [requestReview]
     * when set.
     */
    var requestReviewAsync: (suspend () -> Boolean)? = null

    /**
     * RN maybeRequestReview: after a successful publish or vote the store rating prompt may be asked for, once
     * ([AppRating]: 3 days and 5 app starts), [AppRating.DELAY_MILLIS] later. Never from a failure path: the
     * callers only get here after their broadcast was accepted.
     */
    internal fun maybeRequestReview() {
        if (!rating.claim()) return
        background.launch {
            var asked = false
            try {
                kotlinx.coroutines.delay(AppRating.DELAY_MILLIS)
                // never over the lock, the welcome screen or another prompt: the request then stays unspent
                val free = kotlinx.coroutines.withContext(dispatchers.main) { rootPrompts.mayAskForRating() }
                asked = free && (requestReviewAsync?.let { it() } ?: (requestReview?.invoke() == true))
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                // RN: a failed request is not a request
            } finally {
                rating.finish(asked)
            }
        }
    }

    /** Accounts that closed the editor's quest chip in this run of the app (RN keeps it in memory too). Main thread. */
    internal val dismissedQuestChips = mutableSetOf<String>()

    /** [args] names what to write; the model belongs to the account signed in when it starts. */
    fun editorModel(args: EditorArgs): EditorModel = EditorModel(this, args)

    /** The signed-in account's drafts screen, opened on [tab] (0 drafts, 1 schedules, 2 templates). */
    fun draftsModel(tab: Int): DraftsModel = DraftsModel(this, tab)

    /** The image gallery and the uploads of [editor] (null: uploads for another screen, nothing is inserted). */
    fun uploadsModel(editor: EditorModel?): UploadsModel = UploadsModel(this, editor)

    fun snippetsModel(): SnippetsModel = SnippetsModel(this)

    /** Ecency's AI endpoints (assist, image, transcription), which spend points. */
    val aiApi: com.ecency.core.api.ecency.AiApi by lazy {
        com.ecency.core.api.ecency.AiApi(com.ecency.core.auth.EcencyPrivateApi(auth.http, accounts, auth.tokenHost), auth.http, auth.tokenHost)
    }

    /** Listen: a post's natural-voice audio status and its unlock with Points (RN 760b9070be). */
    val listenApi: com.ecency.core.api.ecency.ListenApi by lazy { com.ecency.core.api.ecency.ListenApi(auth.http, auth.tokenHost) }

    /** One access token renewal per account at a time, for screens that outlived their token (Listen). */
    val sessionRenewals: SessionRenewals by lazy { SessionRenewals(this) }

    /** Listen unlocks on their way and their keys, app-wide (a reopened screen joins one instead of paying twice). */
    val listenUnlocks: ListenUnlocks by lazy { ListenUnlocks(this) }

    /**
     * A shortcut link (the widgets', [AppShortcuts]): where it goes, opened by the platform like any link entry (after
     * the accounts were read, behind the lock, login gated). A new wave asks the Waves tab for an empty composer once
     * the entry really opened ([entryOpened]).
     */
    fun shortcutEntry(url: String): NavEntry? {
        val shortcut = AppShortcuts.parse(url) ?: return null
        composeAfterOpen = shortcut == AppShortcut.NewWave
        return shortcut.entry
    }

    /** A New wave shortcut waits for its entry to open. */
    private var composeAfterOpen = false

    /**
     * The platform opened a link's entry. After a New wave shortcut the composer is asked for, empty, unless a shared
     * text already waits for it (a share waiting through the login is never overwritten).
     */
    fun entryOpened() {
        if (!composeAfterOpen) return
        composeAfterOpen = false
        if (waves.pendingComposeText == null) waves.requestCompose("")
    }

    /** The home and lock screen widgets' snapshot (README "Widgets"); the platforms set its store and start it. */
    val widgets: WidgetFeed by lazy { WidgetFeed(this) }

    /** What plays or records in the background (Listen, dictation); stopped when accounts leave ([accountsLeft]). */
    val backgroundSessions: BackgroundSessions by lazy { BackgroundSessions() }

    /** The dictation that records right now, for the platform's background session and its notification. */
    val dictationSessions: DictationSessions by lazy { DictationSessions() }

    /** The now playing card for [model]'s post (title, @author, artwork unless the PIN lock is on). */
    fun nowPlaying(model: ListenModel): NowPlayingCard {
        val post = model.post
        return NowPlaying.card(post, model.key.author, appLock.current.enabled, post?.let { renderer.cardImage(it) }, proxyBase)
    }

    /** The Listen bar of one post screen: keep it with the screen (its stack entry) and close it with it. */
    fun listenModel(author: String, permlink: String): ListenModel = ListenModel(this, PostKey(author, permlink))

    /** A new idempotency key for a paid request: 32 hex characters from the platform's secure random source. */
    var idempotencyKey: () -> String = { com.ecency.core.secure.secureRandomBytes(16).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') } }

    /** RN newsletterDigestSheet: the email digest sheet and the offer after an account's first post. One per app. */
    val digest: DigestModel by lazy { DigestModel(this) }

    /** RN payoutDetailsContent: the payout popover of [post]. */
    fun payoutDetailsModel(post: Post): PayoutDetailsModel = PayoutDetailsModel(this, post)

    /** RN postDisplayView's view count and postStatsModal for [post]. */
    fun postStatsModel(post: Post): PostStatsModel = PostStatsModel(this, post)

    /** RN profile's wallet tab of another account (read only). */
    fun profileWalletModel(username: String): ProfileWalletModel = ProfileWalletModel(this, username)

    /** RN NewsletterPostPrompt for one post screen. */
    fun digestPromptModel(): DigestPromptModel = DigestPromptModel(this)

    /** RN NewsletterSenderInfo on [username]'s own profile. */
    fun digestSenderModel(username: String): DigestSenderModel = DigestSenderModel(this, username)

    /** RN settings: device settings, support percents, the RN settings import. One per app; start it at launch. */
    val settings: SettingsModel by lazy { SettingsModel(this) }

    /** RN emailDigestsScreen; the screen owns it. */
    fun emailDigestsModel(): EmailDigestsModel = EmailDigestsModel(this)

    /** Paid requests that have no answer yet, per account. */
    val paidIntents: PaidIntents by lazy { PaidIntents(this) }

    /** The platform's microphone recorder for dictation; null until an app sets one (then dictation says it is unavailable). */
    var dictationRecorder: DictationRecorder? = null

    /** False while the app is in the background (the apps report it): nothing records then. */
    @kotlin.concurrent.Volatile
    var isForeground: Boolean = true

    fun dictationModel(editor: AiTextTarget): DictationModel = DictationModel(this, editor)

    /** Ecency's 3Speak proxy (upload token, thumbnail). */
    val speakApi: com.ecency.core.api.speak.SpeakApi by lazy { com.ecency.core.api.speak.SpeakApi(auth.http, { accounts.code(it) }, auth.tokenHost) }

    /**
     * The tus client for video uploads, over the same HTTP seam as everything else: the build's user agent goes
     * to Ecency's hosts only ([com.ecency.core.auth.ClientIdentity]), so 3Speak never sees it. Tests replace it.
     */
    var tus: com.ecency.core.api.speak.TusClient = com.ecency.core.api.speak.TusClient(auth.http)

    fun videoUploadModel(editor: EditorModel): VideoUploadModel = VideoUploadModel(this, editor)

    /** `rc_api.get_resource_params`, kept for a day (the SDK's staleTime): the price curve changes at a hardfork only. Main thread. */
    internal var rcParams: kotlinx.serialization.json.JsonObject? = null
    internal var rcParamsAtMillis: Long = 0

    fun aiAssistModel(editor: AiTextTarget): AiAssistModel = AiAssistModel(this, editor)

    fun aiImageModel(editor: EditorModel): AiImageModel = AiImageModel(this, editor)

    /** The generator opened as a screen of its own (the `AiImageGeneratorScreen` route): nothing to insert into. */
    fun aiImageScreenModel(): AiImageModel = AiImageModel(this, null)

    /** The compose translate sheet of [editor]; [appLanguage] is the app or device language. */
    fun composeTranslateModel(editor: EditorModel, appLanguage: String): ComposeTranslateModel = ComposeTranslateModel(this, editor, appLanguage)

    /** The editor's "Choose a community" sheet. */
    fun communityPickerModel(): CommunityPickerModel = CommunityPickerModel(this)

    /** The editor posted the reply to [parent]: the quick reply sheet's model for it (same draft slot) starts fresh. */
    internal fun replyPostedElsewhere(parent: PostKey) {
        replies.remove(parent)?.dispose()
    }

    companion object {
        /**
         * Rendered bodies kept in memory, by entry: a post and every comment of its discussion are one
         * entry each, so this holds a few long discussions (a busy post has a few hundred comments)
         * without re-rendering when the reader goes back and forth. Block lists of comments are small.
         */
        private const val BODY_CACHE_SIZE = 1000

        /** Platform defaults: the app's Hive nodes, main/default/IO dispatchers, the default renderer. */
        @Throws(EcencyException::class)
        fun create(store: SecureStore, debugKeyImport: Boolean): AppEnvironment =
            create(store, debugKeyImport, RenderHelper.Platform.ANDROID)

        /** As [create], with the RN app's data to migrate at [AppLockModel.start]. */
        @Throws(EcencyException::class)
        fun create(store: SecureStore, debugKeyImport: Boolean, legacy: LegacyMigrationSource?): AppEnvironment =
            create(store, debugKeyImport, RenderHelper.Platform.ANDROID, legacy)

        /** As [create], with the platform render-helper formats summaries for (IOS on iOS). */
        @Throws(EcencyException::class)
        fun create(store: SecureStore, debugKeyImport: Boolean, platform: RenderHelper.Platform): AppEnvironment =
            create(store, debugKeyImport, platform, null)

        /** As [create], with the RN app's data to migrate at [AppLockModel.start]. */
        @Throws(EcencyException::class)
        fun create(
            store: SecureStore,
            debugKeyImport: Boolean,
            platform: RenderHelper.Platform,
            legacy: LegacyMigrationSource?,
        ): AppEnvironment = create(store, debugKeyImport, platform, legacy, null)

        /** As [create]; [auth] replaces the real auth services (debug demo runs only), null for the platform ones. */
        @Throws(EcencyException::class)
        fun create(
            store: SecureStore,
            debugKeyImport: Boolean,
            platform: RenderHelper.Platform,
            legacy: LegacyMigrationSource?,
            auth: AuthServices?,
        ): AppEnvironment = create(store, debugKeyImport, platform, legacy, auth, null)

        /** As [create] without a platform (iOS), with the build's user agent. */
        @Throws(EcencyException::class)
        fun create(store: SecureStore, debugKeyImport: Boolean, legacy: LegacyMigrationSource?, userAgent: String?): AppEnvironment =
            create(store, debugKeyImport, RenderHelper.Platform.ANDROID, legacy, null, userAgent)

        /** As [create]; [userAgent] is how this build names itself to Ecency's APIs (null or blank: the default). */
        @Throws(EcencyException::class)
        fun create(
            store: SecureStore,
            debugKeyImport: Boolean,
            platform: RenderHelper.Platform,
            legacy: LegacyMigrationSource?,
            auth: AuthServices?,
            userAgent: String?,
        ): AppEnvironment = create(store, debugKeyImport, platform, legacy, auth, userAgent, null)

        /**
         * As [create]; [hive] replaces the Hive nodes' transport (debug demo runs only: [DemoHiveTransport], so
         * nothing of a demo run reaches a real node), null for the real one.
         */
        @Throws(EcencyException::class)
        fun create(
            store: SecureStore,
            debugKeyImport: Boolean,
            platform: RenderHelper.Platform,
            legacy: LegacyMigrationSource?,
            auth: AuthServices?,
            userAgent: String?,
            hive: com.ecency.core.api.rpc.RpcTransport?,
        ): AppEnvironment {
            val dispatchers = AppDispatchers.platform()
            return AppEnvironment(
                api = if (hive != null) EcencyApi(listOf("https://demo-hive.invalid"), hive) else EcencyApi(),
                vault = KeyVaultAsync(KeyVault(store), dispatchers.io),
                renderer = RenderHelperPostBodyRenderer(ImageProxy.DEFAULT_BASE, platform),
                dispatchers = dispatchers,
                proxyBase = ImageProxy.DEFAULT_BASE,
                debugKeyImport = debugKeyImport,
                nowMillis = { Clock.System.now().toEpochMilliseconds() },
                legacy = legacy,
                auth = auth ?: AuthServices.platform(userAgent),
            ).also {
                // demo runs (replaced auth services) never reach the image host
                if (auth == null) it.imageTransport = com.ecency.core.api.ecency.KtorImageUploadTransport()
                // a demo Hive transport keeps its offline node: no published list, no saved server
                it.managesHiveNodes = hive == null
            }
        }
    }
}

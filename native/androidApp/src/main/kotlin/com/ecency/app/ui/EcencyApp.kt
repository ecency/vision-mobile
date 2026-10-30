package com.ecency.app.ui

import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.semantics.LiveRegionMode

import androidx.compose.ui.semantics.liveRegion

import androidx.compose.ui.semantics.semantics

import androidx.compose.ui.semantics.Role

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import com.ecency.core.app.AppScreen
import com.ecency.core.app.AppTab
import com.ecency.core.app.DrawerHeader
import com.ecency.core.app.LinkCopy
import com.ecency.core.app.MenuAction
import com.ecency.core.app.NavEntry
import com.ecency.core.app.RouteName
import com.ecency.core.app.SideMenu
import com.ecency.core.app.SideMenuItem
import com.ecency.core.app.ThemeMode
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ecency.app.render.PostActions
import com.ecency.core.api.RankedSort
import com.ecency.core.api.model.Post
import com.ecency.core.api.model.PostKey
import com.ecency.core.api.repository.FeedSource
import com.ecency.core.app.AppEnvironment
import com.ecency.core.app.AppEvent
import com.ecency.core.app.AppLockState
import com.ecency.core.app.PinPrompt
import androidx.compose.material3.TextButton
import com.ecency.core.app.ComposeReplyModel
import com.ecency.core.app.LinkRouter
import com.ecency.core.app.PerfEvent
import com.ecency.core.app.PerfMarks
import com.ecency.core.app.PostCard
import com.ecency.core.app.Route
import com.ecency.core.app.votePreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.ecency.core.app.UiCopy

/** What the app needs from the platform: browser, toasts, clipboard. */
interface AppHost {
    /** http(s) only; the app opens it in a Custom Tab. */
    fun openExternal(url: String)
    fun toast(text: String)
    fun copy(text: String)

    /** "Record Video" with the system camera app; null where there is none (the button then says so). */
    val videoCaptures: com.ecency.app.VideoCaptures? get() = null

    /** True when a strong biometric is enrolled on the device. */
    fun biometricAvailable(): Boolean = false

    /**
     * Shows the system biometric prompt. [onSuccess] runs only when it succeeded, with the binding of the
     * biometrics enrolled now; [onInvalidated] when the enrollment changed since biometric unlock was turned on.
     */
    fun authenticateBiometric(onSuccess: (String) -> Unit, onInvalidated: () -> Unit) {}

    /**
     * The biometric prompt of the offer when the PIN lock is turned on ([BiometricOfferScreen]): a strong
     * biometric, no device credential. [onSuccess] only when it succeeded; the binding is made after the PIN.
     */
    fun confirmBiometric(onSuccess: () -> Unit) {}

    /** Keeps the app out of the recents thumbnail while the PIN lock is on (FLAG_SECURE below Android 13). */
    fun setHideInRecents(hide: Boolean) {}

    /** A screen showing or taking private keys or passwords is up: FLAG_SECURE on every Android version. */
    fun setSensitiveScreen(sensitive: Boolean) {}

    /** The last vote weight in percent (1..100), offered again by the next vote sheet, as RN and iOS do. */
    fun voteWeightPercent(): Float = 100f

    fun setVoteWeightPercent(percent: Float) {}

    /** The theme an earlier preview build kept in the platform's storage, or null; it moves to the settings once. */
    fun legacyThemeName(): String? = null

    /** The device locale, for the language setting's default (RN autoLocale). */
    fun deviceLocale(): String = java.util.Locale.getDefault().toLanguageTag()

    /** RN sendEmail: opens a `mailto:` link in the mail app; false when no app takes it. */
    fun openMail(url: String): Boolean = false

    /** RN openStoreListing ("Rate Ecency"). */
    fun openStoreListing() {}

    /** RN feedback's platform line: "<device> - Android <API level>". */
    fun feedbackPlatform(): String = "Android"

    /** Light or dark status and navigation bar icons for the current theme. */
    fun setSystemBarsDark(dark: Boolean) {}

    /** For the side menu footer. */
    fun appVersion(): String = com.ecency.core.EcencyCore.VERSION

    fun buildNumber(): String = "0"

    // ---- login and accounts (AuthUi.kt) ----

    /** Opens a callback another app gave a link (its own scheme or https); built only by the shared link model. */
    fun openCallback(url: String) {}

    /** Opens the HiveAuth wallet (`has://`, `waves://`); false when no installed app takes the link. */
    fun openWallet(link: String): Boolean = false

    /** The first-run terms were accepted (RN welcome screen, `isTermsAccepted`). */
    fun termsAccepted(): Boolean = true

    fun setTermsAccepted() {}

    /** The terms were accepted on an earlier version: RN shows the welcome screen again with the box ticked. */
    fun termsPreviouslyAccepted(): Boolean = false

    /** The device's mail app (RN `openInbox` after a signup). */
    fun openInbox() {}

    /**
     * The activity is stopping only to be created again (rotation, another configuration change): the app does
     * not leave the foreground, so nothing that reacts to the background runs (the keys screen stays, the lock
     * timer does not start).
     */
    fun isChangingConfigurations(): Boolean = false

    /** "Update Now": the store page of this app for the way it was installed ([com.ecency.core.app.UpdateLinks]). */
    fun openStoreForUpdate() {}

    /** The system share sheet with [text] (RN Share.share). */
    fun share(text: String) {}

    /**
     * RN imageViewer "download": the image at [url] into the gallery's "Ecency" album; [done] gets null on success,
     * else the message to show. Asks for the storage permission first where Android needs it (before 10).
     */
    fun saveImage(url: String, done: (String?) -> Unit) = done(com.ecency.core.app.UiCopy.IMAGE_SAVE_ERROR)

    /** The reader's language for translation (RN app language, else the device locale). */
    fun readerLanguage(): String = "en"

    /** Copies a secret (a private key): marked sensitive so the system hides it from clipboard previews. */
    fun copySensitive(text: String) = copy(text)
}

/** Root of the app: lock gate, the shell (drawer, tabs, back stack), sheets and the routes every screen shares. */
@Composable
fun EcencyApp(env: AppEnvironment, host: AppHost, navigator: Navigator = remember { Navigator() }, startVideo: String? = null) {
    // the saved settings apply before the first frame (theme, image server); the RN import follows in the background
    remember(env) {
        env.settings.start(host.deviceLocale())
        env.settings.adoptPlatformTheme(host.legacyThemeName())
        true
    }
    val settings by env.settings.state.collectAsState()
    val themeMode = settings.theme
    val dark = themeMode.isDark(isSystemInDarkTheme())
    // light status bar icons over the blue side menu header too
    val drawerOpen = navigator.state.drawerOpen
    LaunchedEffect(dark, drawerOpen) { host.setSystemBarsDark(dark || drawerOpen) }
    // the app language and its direction, live (AppLanguage.kt); the settings start above set it before this frame
    AppLanguageRoot {
        EcencyTheme(dark = dark) {
            // the system's motion setting (Motion.kt); tests provide their own around EcencyApp
            val motion = LocalMotion.current.takeIf { it != Motion.Full } ?: rememberSystemMotion()
            androidx.compose.runtime.CompositionLocalProvider(LocalMotion provides motion) {
                EcencyRoot(env, host, navigator, startVideo)
            }
        }
    }
}

/** The signed-in account once the accounts were read (null username: nobody). */
private data class AccountNow(val username: String?)

@Composable
private fun EcencyRoot(env: AppEnvironment, host: AppHost, navigator: Navigator, startVideo: String?) {
    val colors = LocalEcencyColors.current
    val session by env.session.state.collectAsState()
    val lock by env.appLock.state.collectAsState()
    // read again on every resume: biometrics can be set up or removed while the app is in the background
    var biometricAvailable by remember(host) { mutableStateOf(host.biometricAvailable()) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val now by produceState(env.nowMillis()) {
        while (true) {
            delay(60_000)
            value = env.nowMillis()
        }
    }
    var voteTarget by remember { mutableStateOf<PostCard?>(null) }
    var tip by remember { mutableStateOf<PostKey?>(null) }
    var reply by remember { mutableStateOf<ComposeReplyModel?>(null) }
    var video by remember { mutableStateOf(startVideo) }
    var gallery by remember { mutableStateOf<Pair<List<String>, Int>?>(null) }
    var linkSheet by remember { mutableStateOf<String?>(null) }
    var confirmLogout by remember { mutableStateOf(false) }
    var accounts by remember { mutableStateOf(DebugLaunch.openAccounts.also { DebugLaunch.openAccounts = false }) }
    // the reply editor's account tile: the accounts sheet, and the reply again for the account switched to
    androidx.compose.runtime.SideEffect { navigator.reopenAfterChange = env.replyHandoff::reopen }
    val replyHandoff by env.replyHandoff.state.collectAsState()
    LaunchedEffect(replyHandoff.askAccounts) {
        if (replyHandoff.askAccounts) {
            accounts = true
            env.replyHandoff.sheetShown()
        }
    }
    var terms by remember { mutableStateOf(host.termsAccepted()) }
    // the shared prompt order needs it: no root prompt (and no rating prompt) before the welcome screen is done
    androidx.compose.runtime.SideEffect { env.setWelcomeDone(terms) }
    val firstFrame = rememberOnce()
    // read by the remembered actions below, which outlive any one recomposition
    val loggedIn by rememberUpdatedState(session.isLoggedIn)
    val nav = navigator.state

    LaunchedEffect(env) {
        // the Hive node pool: the saved server at once, then the published list (RN initSdkConfig)
        launch { env.hiveNodes.start() }
        // the restart journal: sends an earlier run left undecided are asked about now (README "Wallet")
        env.sendJournal.start()
        env.session.start()
        env.appLock.start()
        env.notifications.start()
        // the widgets' snapshot follows the account, the lock setting and the unread count (README "Widgets")
        env.widgets.start()
        env.chat.start()
        env.push.start()
        // in-app purchases: the store listener from the start, and whatever an earlier run left is delivered
        env.purchases.start()
        // Feed and post models refresh themselves on a session change (the reader keeps the post they
        // signed in from). A wipe removed every account: its screens go, as RN returns to the start.
        env.events.collect {
            // what dApps stored in Explore's own web profile goes when no account is left
            if (it is AppEvent.DataWiped || (it is AppEvent.SessionChanged && it.username == null)) DappWeb.clearProfile()
            if (it is AppEvent.DataWiped) {
                navigator.reset()
                // the signup challenge's own WebView profile (cookies, storage) goes with everything else
                TurnstileBridge.clearProfileSoon()
            }
            // "sign in again" after the secure storage was reset: the wipe emptied the stack, the login screen opens on it
            if (it is AppEvent.SignInAgain) navigator.push(AppScreen.Login)
        }
    }

    // Any account change (sign-out, switch, a logout that moved to the next account, a link login as someone else)
    // keeps the tab and public screens; screens that need the account belonged to the previous one and go (RN).
    // A navigation restored after process death keeps its gated screens only for the account that had them open.
    LaunchedEffect(env, navigator) {
        env.session.state.map { if (it.isLoading) null else AccountNow(it.username) }.distinctUntilChanged().collect { it?.let { now -> navigator.accountIs(now.username) } }
    }

    // the login screen left and nobody signed in: a share link's text that waited for that login goes
    LaunchedEffect(env, navigator) {
        var shown = false
        androidx.compose.runtime.snapshotFlow { navigator.state.screens.any { it == AppScreen.Login } }.collect { showing ->
            if (shown && !showing && !env.session.current.isLoggedIn) {
                env.waves.dropComposeText()
                env.share.loginDismissed()
            }
            shown = showing
        }
    }

    // a tapped notification, released by the push model once the accounts were read and the lock is open
    LaunchedEffect(env) {
        env.push.state.collect { p ->
            val entry = p.open ?: return@collect
            env.push.consumeOpen()
            navigator.open(entry, env.session.current.isLoggedIn)
        }
    }

    // failed votes: a snackbar with the shared message (network: "may have gone through, retry is safe").
    // Collected here rather than as composition state, so a vote status change never recomposes the app.
    LaunchedEffect(env) {
        val shown = mutableSetOf<PostKey>()
        env.votes.state.collect { votes ->
            val failed = votes.statuses.values.firstOrNull { it.error != null && it.key !in shown } ?: return@collect
            shown += failed.key
            launch {
                val error = failed.error!!
                val result = snackbar.showSnackbar(error.message, if (error.retryable) UiCopy.RETRY else null, duration = SnackbarDuration.Long)
                shown -= failed.key
                if (result == SnackbarResult.ActionPerformed) env.votes.retry(failed.key.author, failed.key.permlink)
                else env.votes.dismissError(failed.key.author, failed.key.permlink)
            }
        }
    }

    // a direct message started from a profile outlives the tap (its answer opens the conversation)
    val chatScope = rememberCoroutineScope()

    // Built once: recompositions of the root hand the same objects down, so screens and cards skip.
    val routes = remember(env, host, navigator) {
        object {
            fun open(route: Route) {
                when (route) {
                    is Route.ExternalBrowser -> host.openExternal(route.url)
                    is Route.VideoPlayer -> video = route.embedUrl
                    // RN postInteractionHandler: hive:// and ecency:// go to the link processor (a sign link asks first)
                    is Route.AppLink -> if (!env.authLinks.handleFromContent(route.uri)) env.ui.toast(LinkCopy.UNSUPPORTED)
                    is Route.Unsupported -> env.ui.toast(LinkCopy.UNSUPPORTED)
                    else -> NavEntry.from(route)?.let { navigator.open(it, loggedIn) }
                }
            }

            fun push(screen: AppScreen) = navigator.pushGated(screen, loggedIn)

            fun requireLogin(block: () -> Unit) = if (loggedIn) block() else navigator.push(AppScreen.Login)

            fun openPost(card: PostCard) {
                PerfMarks.mark(PerfEvent.POST_OPEN_TAP)
                navigator.push(AppScreen.Post(card.key.author, card.key.permlink))
            }

            fun openCommunity(card: PostCard) {
                val community = card.community
                if (community != null) navigator.push(AppScreen.Community(community))
                else if (card.category.isNotEmpty()) navigator.push(AppScreen.TagResult(card.category))
            }

            val profileActions = ProfileActions(
                onBack = { navigator.pop() },
                onFollows = { user, following, count -> navigator.push(AppScreen.Follows(user, following, count)) },
                onEdit = { push(AppScreen.ProfileEdit) },
                // RN profileContainer `_handleMessage`: the direct message, or "User has not joined chats"
                onMessage = { user -> requireLogin { openDirectMessage(env, chatScope, user, ::push) } },
                // RN profileSummary: the creator digest sheet of that account
                onEmailDigest = { env.digest.show("creator", it) },
                onDelegate = { push(AppScreen.of(RouteName.TRANSFER, it)) },
                onWebsite = { host.openExternal(it) },
                onWallet = { own -> if (own) navigator.selectTab(AppTab.WALLET) },
                onWaves = { navigator.selectTab(AppTab.WAVES) },
                onManageDigests = { push(AppScreen.of(RouteName.EMAIL_DIGESTS)) },
            )

            fun menu(item: SideMenuItem, hasOtherAccounts: Boolean) {
                when (val action = SideMenu.action(item, env.session.current.username, hasOtherAccounts)) {
                    is MenuAction.Open -> push(action.screen)
                    is MenuAction.Browser -> { navigator.closeDrawer(); host.openExternal(action.url) }
                    MenuAction.ConfirmLogout -> confirmLogout = true
                    MenuAction.AccountsSheet -> { navigator.closeDrawer(); accounts = true }
                    // web "Ask Esie": the conversation opens once the chat answers (or its toast shows)
                    is MenuAction.DirectMessage -> { navigator.closeDrawer(); openDirectMessage(env, chatScope, action.username, ::push) }
                }
            }
        }
    }

    // ---- feed, posts and reading: menu, sheets, quick profile (PostMenuUi.kt, ReadingScreens.kt) ----
    val feedx = remember(env, host, navigator) {
        FeedxUi(
            env, host,
            push = { routes.push(it) },
            reply = { root, post -> routes.requireLogin { reply = env.replyModel(root, post) } },
            login = { navigator.push(AppScreen.Login) },
        )
    }
    val haptics = rememberHaptics()
    feedx.haptics = haptics
    // what the post menu had open belongs to the account that opened it
    LaunchedEffect(env, feedx) {
        var previous: String? = null
        var loaded = false
        env.session.state.collect { s ->
            if (s.isLoading) return@collect
            if (loaded && s.username != previous) {
                feedx.onAccountChange()
                // a reply sheet belongs to the account that opened it (the composer refuses to send as another)
                reply = null
                tip = null
            }
            loaded = true
            previous = s.username
        }
    }

    // RN "Show Images" (hidePostsThumbnails): post bodies, comments and the image viewer follow it
    val showImages = env.notifications.state.collectAsState().value.showImages
    val bodyActions = remember(routes, feedx, showImages) {
        PostActions(
            // RN: a user link in a body opens the quick profile sheet
            onLink = { target ->
                val route = LinkRouter.route(target)
                when (route) {
                    is Route.Profile -> feedx.openProfile(route.author)
                    // RN postInteractionHandler: an external link asks what to do (copy, Explore, system browser)
                    is Route.ExternalBrowser -> linkSheet = route.url
                    else -> routes.open(route)
                }
            },
            onImage = { url, all -> gallery = (all.ifEmpty { listOf(url) }) to all.indexOf(url).coerceAtLeast(0) },
            onParagraphLongPress = { host.copy(it); env.ui.toast(UiCopy.COPIED_PLAIN) },
            avatarUrl = { PostCard.avatarUrl(it, env.proxyBase).ifEmpty { null } },
            showImages = showImages,
        )
    }

    val feedActions = remember(routes, feedx) {
        FeedActions(
            onOpen = routes::openPost,
            // RN: the avatar and name open the quick profile sheet
            onAuthor = feedx::openProfile,
            onCommunity = routes::openCommunity,
            onVote = { card -> routes.requireLogin { voteTarget = card } },
            onVoters = { card -> navigator.push(AppScreen.Voters(card.key.author, card.key.permlink)) },
            onReblogs = { card -> navigator.push(AppScreen.Reblogs(card.key.author, card.key.permlink)) },
            // RN TIPPING_DIALOG over the list
            onTip = { card -> routes.requireLogin { tip = card.key } },
        )
    }

    val wavesUi = remember(routes, bodyActions) {
        WavesUi(bodyActions, login = { navigator.push(AppScreen.Login) }, requireLogin = { routes.requireLogin(it) }, push = { routes.push(it) })
    }

    // RN applicationContainer: lock again after a minute in the background (timed on a monotonic clock)
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle, env) {
        var pointsForegrounds = 0
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> if (!host.isChangingConfigurations()) {
                    env.isForeground = false
                    env.appLock.onBackground()
                    env.push.onBackground()
                }
                androidx.lifecycle.Lifecycle.Event.ON_START -> {
                    env.isForeground = true
                    env.appLock.onForeground()
                    env.session.onForeground()
                    env.notifications.onForeground()
                    env.chat.onForeground()
                    env.push.onForeground()
                    env.purchases.onForeground()
                    // RN lazyMutatePendingActivities on every return to the foreground (the start's own replay is
                    // shellStarted's: the first ON_START is skipped)
                    if (pointsForegrounds++ > 0) env.pointActivities.replayPending()
                }
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> biometricAvailable = host.biometricAvailable()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(lock.loaded, lock.enabled) { if (lock.loaded) host.setHideInRecents(lock.enabled) }
    // login, keys and the key entry of the auth upgrade (or HiveSigner's page) never end up in a screenshot
    val signingPrompt by env.signing.state.collectAsState()
    val sensitive = navigator.top.screen.let { it == AppScreen.Login || it == AppScreen.BackupKeys } ||
        signingPrompt.prompt.let { it is com.ecency.core.app.SigningPrompt.AuthUpgrade || it is com.ecency.core.app.SigningPrompt.HiveSignerHot }
    LaunchedEffect(sensitive) { host.setSensitiveScreen(sensitive) }

    // drawer first, then the stack, then back to the Feed tab; at the Feed root the system leaves the app
    BackHandler(enabled = !lock.hidesContent && nav.back() != null) { navigator.back() }

    // the first frame of whatever comes first: lock cover, PIN pad or the feed
    val openTip = remember(routes) { { key: PostKey -> routes.requireLogin { tip = key } } }
    // the one "Show Images" read (ContentImages behind env.notifications), for feed cards and post bodies alike
    androidx.compose.runtime.CompositionLocalProvider(LocalFeedx provides feedx, LocalVideoCaptures provides host.videoCaptures, LocalTip provides openTip, LocalShowImages provides showImages) {
    Box(Modifier.fillMaxSize().background(colors.background).onFirstDraw { firstFrame.run { PerfMarks.markOnce(PerfEvent.FIRST_FRAME) } }) {
        // the device key of the secure storage is gone: a window of its own, above the lock cover, the PIN pad and every sheet
        StorageResetPrompt(env)
        // Nothing private is composed while the lock state is unknown or the app is locked.
        if (lock.hidesContent) {
            val prompt = lock.prompt
            if (prompt != null) LockPrompt(env, host, lock, prompt, biometricAvailable)
            else LockCover(lock.error?.message, onRetry = { env.appLock.clearError(); env.appLock.start() })
            LockNotice(env, lock) // e.g. biometric unlock went off because the enrollment changed
            return@Box
        }

        // RN welcome screen: the terms are accepted once before the app shows
        if (!terms) {
            WelcomeScreen(host, onAccepted = { terms = true })
            return@Box
        }

        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                // push and pop with Material shared axis, predictive back for pops (Navigation.kt)
                AnimatedNavHost(navigator, Modifier.fillMaxSize()) { entry ->
                    when (val screen = entry.screen) {
                        null -> TabsRoot(env, entry, nav.tab, session.username, now, feedActions, routes::push, navigator, wavesUi, host::openExternal)
                        is AppScreen.Feed -> if (screen.route == RouteName.TAG_RESULT && screen.source is FeedSource.Ranked) {
                            TagFeedScreen(env, entry, (screen.source as FeedSource.Ranked).tag, screen.title, now, feedActions) { navigator.pop() }
                        } else Column(Modifier.fillMaxSize().statusBarsPadding()) {
                            TopBar(screen.title, onBack = { navigator.pop() })
                            Guarded({ entry.get("feed") { env.feedModel(screen.source) } }) { feed ->
                                FeedList(feed, entry.get("list") { LazyListState() }, env.votes.state, now, feedActions, scrollTopModifier = Modifier.navigationBarsPadding())
                            }
                        }
                        // ---- profile, social graph and bookmarks (ProfileScreens.kt) ----
                        is AppScreen.Profile -> Guarded({ entry.get("profile") { env.profileModel(screen.author, screen.section) } }) { model ->
                            ProfileScreen(env, model, entry.get("list") { LazyListState() }, env.votes.state, now, feedActions, routes.profileActions, wavesUi, entry.get("summary") { SummaryCollapseState() })
                        }
                        is AppScreen.Follows -> Guarded({ entry.get("follows") { env.followsModel(screen.username, screen.following, screen.count) } }) { model ->
                            FollowsScreen(env, model, entry.get("list") { LazyListState() }, onBack = { navigator.pop() }, onUser = { navigator.push(AppScreen.Profile(it)) })
                        }
                        AppScreen.ProfileEdit -> Guarded({ entry.get("edit") { env.profileEditModel() } }) { model ->
                            ProfileEditScreen(env, model, onBack = { navigator.pop() }, onSaved = { navigator.pop() })
                        }
                        is AppScreen.Bookmarks -> Guarded({ entry.get("bookmarks") { env.bookmarksModel(screen.tab) } }) { model ->
                            BookmarksScreen(
                                env, model, onBack = { navigator.pop() },
                                onPost = { a, p -> navigator.push(AppScreen.Post(a, p)) },
                                onUser = { navigator.push(AppScreen.Profile(it)) },
                                onTag = { navigator.push(AppScreen.Feed(FeedSource.Ranked(RankedSort.TRENDING, it), "#$it")) },
                            )
                        }
                        is AppScreen.AccountList -> AccountListScreen(env, screen.title, screen.users, now, onBack = { navigator.pop() }, onUser = { navigator.push(AppScreen.Profile(it)) })
                        is AppScreen.Post -> Box(Modifier.fillMaxSize().statusBarsPadding()) {
                            val root = PostKey(screen.author, screen.permlink)
                            // an API-flagged wave opened from a list keeps its media covered here (review L8)
                            var coveredWave by remember(root) { mutableStateOf(env.mutedReveals.coversWave(root, com.ecency.core.app.NsfwMode.of(env.prefs))) }
                            val actions = remember(routes, root, coveredWave) {
                                PostScreenActions(
                                    onBack = { navigator.pop() },
                                    onAuthor = { navigator.push(AppScreen.Profile(it)) },
                                    onCommunity = routes::openCommunity,
                                    onVote = { card -> routes.requireLogin { voteTarget = card } },
                                    onReply = { parent: Post ->
                                        // one draft per parent for the whole app: closing the sheet or leaving the post keeps it
                                        routes.requireLogin { reply = env.replyModel(root, parent) }
                                    },
                                    onOpenThread = { navigator.push(AppScreen.Post(it.author, it.permlink)) },
                                    body = if (coveredWave) com.ecency.app.render.PostActions(bodyActions.onLink, bodyActions.onImage, bodyActions.onParagraphLongPress, bodyActions.onParagraphTap, bodyActions.avatarUrl, false) else bodyActions,
                                    onScreen = { routes.push(it) },
                                    root = root,
                                )
                            }
                            // deleted from its own menu: back to where the reader came from (RN popScreenOnDelete)
                            val deleted by env.postActions.state.collectAsState()
                            LaunchedEffect(deleted.deleted) { if (root in deleted.deleted) navigator.pop() }
                            Guarded({ entry.get("post") { env.postModel(screen.author, screen.permlink) } }) { model ->
                                PostScreen(
                                    model = model,
                                    listState = entry.get("list") { LazyListState() },
                                    votes = env.votes.state,
                                    nowMillis = now,
                                    actions = actions,
                                    entry = entry,
                                )
                            }
                            if (coveredWave) {
                                Text(
                                    UiCopy.NSFW_TAP_TO_REVEAL, color = androidx.compose.ui.graphics.Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp)
                                        .clip(RoundedCornerShape(20.dp)).background(colors.blue)
                                        .clickable(role = Role.Button) { env.mutedReveals.reveal(root); coveredWave = false }
                                        .padding(horizontal = 16.dp, vertical = 10.dp).testTag("postNsfwReveal"),
                                )
                            }
                        }
                        is AppScreen.Voters -> VotersScreen(env, entry, PostKey(screen.author, screen.permlink), onBack = { navigator.pop() }, onProfile = feedx::openProfile)
                        is AppScreen.Reblogs -> ReblogsScreen(
                            env, entry, PostKey(screen.author, screen.permlink), session.isLoggedIn,
                            onBack = { navigator.pop() }, onProfile = feedx::openProfile, onLogin = { navigator.push(AppScreen.Login) },
                        )
                        is AppScreen.EditHistory -> EditHistoryScreen(env, entry, PostKey(screen.author, screen.permlink)) { navigator.pop() }
                        is AppScreen.BotComments -> {
                            val root = PostKey(screen.author, screen.permlink)
                            // an API-flagged wave opened from a list keeps its media covered here (review L8)
                            var coveredWave by remember(root) { mutableStateOf(env.mutedReveals.coversWave(root, com.ecency.core.app.NsfwMode.of(env.prefs))) }
                            val actions = remember(routes, root, coveredWave) {
                                PostScreenActions(
                                    onBack = { navigator.pop() },
                                    onAuthor = feedx::openProfile,
                                    onCommunity = routes::openCommunity,
                                    onVote = { card -> routes.requireLogin { voteTarget = card } },
                                    onReply = { parent: Post -> routes.requireLogin { reply = env.replyModel(root, parent) } },
                                    onOpenThread = { navigator.push(AppScreen.Post(it.author, it.permlink)) },
                                    body = if (coveredWave) com.ecency.app.render.PostActions(bodyActions.onLink, bodyActions.onImage, bodyActions.onParagraphLongPress, bodyActions.onParagraphTap, bodyActions.avatarUrl, false) else bodyActions,
                                    onScreen = { routes.push(it) },
                                    root = root,
                                )
                            }
                            BotCommentsScreen(env, entry, root, { navigator.pop() }, actions, env.votes.state, now)
                        }
                        // ---- login and accounts (AuthUi.kt, LoginScreen.kt) ----
                        AppScreen.Login -> Box(Modifier.fillMaxSize().statusBarsPadding()) {
                            LoginScreen(env, host, onBack = { navigator.pop() }, onSignedIn = { navigator.pop() }, onSignUp = { navigator.push(AppScreen.Register()) })
                        }
                        is AppScreen.Register -> Box(Modifier.fillMaxSize().statusBarsPadding()) {
                            RegisterScreen(
                                env, host, entry.get("register") { env.registerModel(screen.referral) }, onBack = { navigator.pop() },
                                // RN navigation.replace(LOGIN)
                                onLogin = { navigator.pop(); navigator.push(AppScreen.Login) },
                                onDone = { navigator.pop() },
                            )
                        }
                        AppScreen.Refer -> Box(Modifier.fillMaxSize().statusBarsPadding()) {
                            ReferScreen(
                                env, host, entry.get("refer") { env.referModel() }, entry.get("list") { LazyListState() }, session.username,
                                onBack = { navigator.pop() }, onUser = feedx::openProfile,
                                // RN: the transfer screen in delegate mode for that account (wallet wave)
                                onDelegate = { routes.push(AppScreen.of(RouteName.TRANSFER, it)) },
                            )
                        }
                        // ---- points and perks (PerksScreens.kt) ----
                        AppScreen.Perks -> Box(Modifier.fillMaxSize().statusBarsPadding()) {
                            PerksScreen(
                                entry.get("perks") { env.perksModel() }, session.username, onBack = { navigator.pop() }, onOpen = routes::push,
                                onGetPoints = { routes.push(AppScreen.of(RouteName.BOOST, it)) },
                                nowMillis = env.nowMillis, pro = entry.get("pro") { env.proModel() },
                            )
                        }
                        AppScreen.SpinGame -> Box(Modifier.fillMaxSize().statusBarsPadding()) {
                            SpinGameScreen(
                                env, entry.get("spin") { env.spinGameModel() }, onBack = { navigator.pop() },
                                pack = entry.get("pack") { env.storeScreenModel(com.ecency.core.app.StoreScreenKind.SPINS, "", "") },
                            )
                        }
                        // ---- in-app purchases (StoreScreens.kt) ----
                        is AppScreen.GetPoints -> Box(Modifier.fillMaxSize().statusBarsPadding()) {
                            GetPointsScreen(
                                env, entry.get("store") { env.storeScreenModel(com.ecency.core.app.StoreScreenKind.POINTS, screen.username, screen.productId) },
                                onBack = { navigator.pop() }, onSpinGame = { routes.push(AppScreen.SpinGame) },
                            )
                        }
                        is AppScreen.AccountBoost -> Box(Modifier.fillMaxSize().statusBarsPadding()) {
                            AccountBoostScreen(env, entry.get("store") { env.storeScreenModel(com.ecency.core.app.StoreScreenKind.ACCOUNT_BOOST, screen.username, "") }, onBack = { navigator.pop() })
                        }
                        AppScreen.BackupKeys -> Box(Modifier.fillMaxSize().statusBarsPadding()) {
                            BackupKeysScreen(env, host, entry.get("keys") { env.backupKeysModel() }, onBack = { navigator.pop() })
                        }
                        AppScreen.Settings -> Box(Modifier.fillMaxSize().statusBarsPadding()) {
                            SettingsScreen(
                                env, host, lock, session.isLoggedIn, biometricAvailable,
                                SettingsActions(
                                    onPinLock = { if (lock.enabled) env.appLock.startDisable() else env.appLock.startEnable(biometricAvailable) },
                                    onBiometric = { env.appLock.startBiometric(!lock.biometricEnabled) },
                                    onChangePin = { env.appLock.startChange() },
                                    onBackupKeys = { navigator.pushGated(AppScreen.BackupKeys, loggedIn) },
                                    onDeleteAccount = env.deleteAccount::ask,
                                    onEmailDigests = { navigator.pushGated(AppScreen.EmailDigests, loggedIn) },
                                    onBack = { navigator.pop() },
                                ),
                            )
                        }
                        AppScreen.EmailDigests -> Box(Modifier.fillMaxSize().statusBarsPadding()) {
                            EmailDigestsScreen(entry.get("digests") { env.emailDigestsModel() }, session.username, onBack = { navigator.pop() })
                        }
                        // ---- communities and search (CommunityScreens.kt, SearchScreens.kt) ----
                        AppScreen.Communities, is AppScreen.Community, is AppScreen.CommunityMembers, is AppScreen.CommunitySettings,
                        is AppScreen.CommunityActivities, AppScreen.Search, is AppScreen.TagResult ->
                            CommunityAndSearchScreen(screen, env, entry, now, feedActions, ScreenNav(routes::push, { navigator.pop() }, { navigator.push(AppScreen.Login) }))
                        // ---- platform: the dApp browser and its bridge, the QR scanner (PlatformScreens.kt) ----
                        AppScreen.Explore, is AppScreen.DappBrowser ->
                            DappBrowserScreen(env, entry, (screen as? AppScreen.DappBrowser)?.url, onClose = { navigator.pop() })
                        AppScreen.QrScan -> QrScanScreen(env, entry, host, onClose = { navigator.pop() }, onOpen = { navigator.open(it, loggedIn) })
                        // ---- editor and publishing (EditorScreens.kt) ----
                        is AppScreen.Editor, is AppScreen.Drafts, AppScreen.AiImageGenerator ->
                            EditorAndDraftsScreen(screen, env, entry, ScreenNav(routes::push, { navigator.pop() }, { navigator.push(AppScreen.Login) }))
                        // ---- wallet (WalletScreens.kt, TransferScreens.kt) ----
                        is AppScreen.AssetDetails -> Guarded({ entry.get("asset") { env.assetDetailsModel(screen.symbol) } }) { model ->
                            AssetDetailsScreen(env, host, model, entry.get("list") { LazyListState() }, onBack = { navigator.pop() }, push = routes::push)
                        }
                        is AppScreen.Transfer -> TransferRoute(env, host, entry, screen, onBack = { navigator.pop() })
                        AppScreen.AssetsSelect -> Guarded({ entry.get("assets") { env.manageAssetsModel() } }) { ManageAssetsScreen(it, onBack = { navigator.pop() }) }
                        is AppScreen.Trade -> Guarded({ entry.get("trade") { env.tradeModel(screen.fund) } }) { TradeScreen(it, onBack = { navigator.pop() }) }
                        is AppScreen.Redeem -> Guarded({
                            entry.get("redeem") { env.redeemModel(com.ecency.core.app.RedeemKind.entries.firstOrNull { k -> k.rn == screen.redeemType } ?: com.ecency.core.app.RedeemKind.PROMOTE, screen.permlink) }
                        }) { RedeemScreen(it, onBack = { navigator.pop() }, onBuyPoints = { routes.push(AppScreen.of(RouteName.BOOST, session.username.orEmpty())) }) }
                        // ---- chats (ChatScreens.kt) ----
                        is AppScreen.ChatThread -> ChatThreadScreen(
                            env, host, entry, screen,
                            ChatThreadActions(
                                onBack = { navigator.pop() },
                                onProfile = feedx::openProfile,
                                onLink = { url -> if (!env.authLinks.handleFromContent(url)) com.ecency.core.render.blocks.SafeTarget.external(url)?.let { routes.open(LinkRouter.route(it)) } },
                                onSettings = { routes.push(AppScreen.Settings) },
                            ),
                        )
                        is AppScreen.Pending -> Column(Modifier.fillMaxSize().background(colors.background).statusBarsPadding()) {
                            TopBar(screen.title, onBack = { navigator.pop() })
                            ComingSoon(screen.title)
                        }
                    }
                }
                // above the keyboard and the navigation bar; at the tabs it also clears the tab bar and the FAB row, on a
                // pushed screen the scroll to top (16..72 dp) and the post bar
                SnackbarHost(
                    snackbar,
                    Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding()
                        .padding(bottom = if (nav.atRoot) 64.dp + FabTokens.SIZE + FabTokens.MARGIN else FabTokens.SIZE + FabTokens.MARGIN * 2)
                        .testTag("snackbar"),
                )
            }
            OfflineStrip(env)
        }

        // the side menu covers the tabs only (it belongs to the root, like RN's drawer)
        if (nav.atRoot) {
            val username = session.username
            val header by produceState<DrawerHeader?>(null, nav.drawerOpen, username) {
                if (nav.drawerOpen && username != null) value = env.drawerHeader(username)
            }
            val otherAccounts = session.accounts.any { it.username != username }
            SideMenuDrawer(
                open = nav.drawerOpen,
                username = username,
                header = header?.takeIf { it.username == username },
                avatarUrl = username?.let { PostCard.avatarUrl(it, env.proxyBase, "medium") }.orEmpty(),
                version = SideMenu.versionText(host.appVersion(), host.buildNumber()),
                onItem = { routes.menu(it, otherAccounts) },
                onAccounts = { routes.menu(SideMenuItem.ADD_ACCOUNT, true) },
                onClose = { navigator.closeDrawer() },
            )
        }

        MessagesLayer(env, onOpen = routes::push, onEntry = { navigator.open(it, loggedIn) })
        FeedxOverlays(feedx)

        voteTarget?.let { card ->
            val preview by produceState<com.ecency.core.app.VotePreview?>(null, session.username) { value = env.votePreview() }
            // RN keeps the last weight for waves apart from posts and comments (waveUpvotePercent)
            val wave = env.waves.isWave(card.key)
            VoteSheet(card, preview = preview, initialPercent = if (wave) env.waves.voteWeightPercent.toFloat() else host.voteWeightPercent(), onVote = { weight ->
                voteTarget = null
                haptics(com.ecency.core.app.HapticEvent.VOTE)
                if (weight != 0 && wave) env.waves.voteWeightPercent = kotlin.math.abs(weight) / 100.0
                else if (weight != 0) host.setVoteWeightPercent(kotlin.math.abs(weight) / 100f)
                env.votes.vote(card.key.author, card.key.permlink, weight)
            }, onDismiss = { voteTarget = null })
        }
        // RN newsletterDigestSheet: the email digest offer after an account's first post
        DigestSheetHost(env, session.username)
        // RN ShareIntentSheet: content another app shared, once the app is unlocked and the accounts were read
        val share by env.share.state.collectAsState()
        if (share.choosing) ShareChooserSheet(onChoose = env.share::choose)
        LaunchedEffect(share.navigation) {
            when (val target = share.navigation) {
                com.ecency.core.app.ShareNavigation.Login -> navigator.push(AppScreen.Login)
                is com.ecency.core.app.ShareNavigation.Editor -> navigator.pushGated(target.screen, loggedIn)
                com.ecency.core.app.ShareNavigation.Wave -> navigator.selectTab(AppTab.WAVES)
                null -> return@LaunchedEffect
            }
            env.share.consumeNavigation()
        }
        reply?.let { model -> ReplySheet(model, onSent = { reply = null }, onDismiss = { reply = null }, onExpand = { args -> reply = null; navigator.push(AppScreen.Editor(args)) }) }
        tip?.let { key ->
            val model = remember(key) { env.tipModel(key.author, key.permlink) }
            androidx.compose.runtime.DisposableEffect(model) { onDispose { model.close() } }
            TipSheet(model, onDismiss = { tip = null })
        }
        video?.let { url -> VideoPlayerSheet(url, onDismiss = { video = null }) }
        gallery?.let { (urls, index) -> GallerySheet(urls, index, onDismiss = { gallery = null }) }
        linkSheet?.let { url ->
            LinkActionSheet(
                url,
                onCopy = { host.copy(url); haptics(com.ecency.core.app.HapticEvent.SUCCESS); env.ui.toast(com.ecency.core.app.LinkSheet.COPIED) },
                onExplore = { screen -> navigator.pushGated(screen, loggedIn) },
                onSystem = { host.openExternal(url) },
                onDismiss = { linkSheet = null },
            )
        }
        if (confirmLogout) {
            LogoutDialog(
                onConfirm = {
                    confirmLogout = false
                    navigator.closeDrawer()
                    scope.launch { runCatching { env.session.signOut() }.onFailure { env.ui.toast(it.message ?: com.ecency.core.app.AccountCopy.LOGOUT_FAILED) } }
                },
                onDismiss = { confirmLogout = false },
            )
        }

        // ---- login and accounts: switcher, signing and link prompts, first-run terms ----
        if (accounts) AccountSwitcherSheet(
            env, host, onAddAccount = { navigator.push(AppScreen.Login) }, onDismiss = { accounts = false },
            onCreateAccount = { accounts = false; navigator.push(AppScreen.Register()) },
        )
        AuthOverlays(env, host, onOpenLogin = { navigator.push(AppScreen.Login) }, onOpenSignup = { navigator.push(AppScreen.Register(it)) }, onOpenScreen = { routes.push(it) })
        // ---- accounts and shell gaps: delete confirmation, update prompt, announcement (ShellPrompts.kt) ----
        ShellPrompts(env, host, onEntry = { navigator.open(it, loggedIn) })

        // settings flows (enable, change, disable, biometric) cover the app like the RN PIN screen
        // in a window of its own, so the pad (PIN confirmation for a link, settings) is above any open sheet
        lock.prompt?.let {
            Dialog(
                onDismissRequest = {},
                properties = androidx.compose.ui.window.DialogProperties(
                    dismissOnBackPress = false, dismissOnClickOutside = false, usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
                ),
            ) { LockPrompt(env, host, lock, it, biometricAvailable) }
        }
        // turning the lock on with a biometric enrolled: biometrics first, "Use PIN instead" to the plain setup
        if (lock.biometricOffer && lock.prompt == null) {
            Dialog(
                onDismissRequest = env.appLock::cancel,
                properties = androidx.compose.ui.window.DialogProperties(
                    dismissOnBackPress = true, dismissOnClickOutside = false, usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
                ),
            ) {
                BiometricOfferScreen(
                    onUseBiometric = { host.confirmBiometric(onSuccess = env.appLock::acceptBiometricOffer) },
                    onUsePin = env.appLock::usePinInstead,
                    onCancel = env.appLock::cancel,
                )
            }
        }
        LockNotice(env, lock)
        // while a PIN pad shows, the pad itself shows the error (AppLockState.promptError)
        lock.error?.takeIf { lock.prompt == null }?.let { error ->
            AlertDialog(
                onDismissRequest = { env.appLock.clearError() },
                text = { Text(error.message) },
                confirmButton = { TextButton(onClick = { env.appLock.clearError() }) { Text(UiCopy.OK) } },
            )
        }
    }
    }
}

/** The tabs: RN Header, the selected tab's screen, the create button on Feed, the bottom tab bar. */
@Composable
private fun TabsRoot(
    env: AppEnvironment,
    root: StackEntry,
    tab: AppTab,
    username: String?,
    now: Long,
    actions: FeedActions,
    push: (AppScreen) -> Unit,
    navigator: Navigator,
    waves: WavesUi,
    openExternal: (String) -> Unit,
) {
    val badges by env.badges.state.collectAsState()
    val feedx = LocalFeedx.current
    val account by produceState<com.ecency.core.api.model.Account?>(null, username) {
        value = username?.let { runCatching { env.api.accounts.getAccount(it) }.getOrNull() }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        AppHeader(
            tab, username, account?.takeIf { it.name == username }?.profile?.displayName,
            username?.let { PostCard.avatarUrl(it, env.proxyBase) }.orEmpty(),
            onAvatar = navigator::openDrawer,
            onQr = { push(AppScreen.of(RouteName.QR_SCAN)) },
            onSearch = { push(AppScreen.of(RouteName.SEARCH_RESULT)) },
            onBoost = { push(AppScreen.of(RouteName.PERKS)) },
        )
        // Material fade through between tabs (a crossfade under reduce motion, Motion.kt)
        val motion = LocalMotion.current
        androidx.compose.animation.AnimatedContent(
            targetState = tab,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            transitionSpec = { motion.tabTransform() },
            label = "tabs",
        ) { tab -> Box(Modifier.fillMaxSize()) {
            val gateText = tab.loginText
            when {
                // RN notification screen: its own Notifications / Leaderboard tabs; only the first needs an account
                tab == AppTab.NOTIFICATION -> NotificationsTab(
                    env, root, username, now,
                    onOpen = { navigator.open(it, username != null) },
                    onProfile = { feedx?.openProfile(it) },
                    onLogin = { push(AppScreen.Login) },
                )
                username == null && gateText != null -> LoginPrompt(gateText, onLogin = { push(AppScreen.Login) })
                tab == AppTab.FEED -> {
                    Column(Modifier.fillMaxSize()) {
                        // RN feedScreen: the WavesOnboardingChecklist above the feed for signed-in readers (it shows
                        // nothing for established accounts)
                        if (username != null) FeedWavesCard(env, username, onCheckIn = { push(AppScreen.of(RouteName.PERKS)) })
                        FilterHomeFeeds(
                            env, root, username, now, actions, onLogin = { push(AppScreen.Login) },
                            onTarget = { target ->
                                when (target) {
                                    is com.ecency.core.app.AnnouncementTarget.InApp -> navigator.open(target.entry, username != null)
                                    is com.ecency.core.app.AnnouncementTarget.Web -> openExternal(target.url)
                                }
                            },
                        )
                    }
                    CreateFab(onClick = { push(AppScreen.of(RouteName.EDITOR)) }, modifier = Modifier.align(Alignment.BottomEnd))
                }
                tab == AppTab.WAVES -> WavesTabScreen(env, root, username, now, actions, waves)
                tab == AppTab.WALLET && username != null -> WalletTab(env, username, root.get("walletList") { LazyListState() }, push)
                tab == AppTab.CHATS && username != null -> ChatsTab(env, root, username, push)
                else -> ComingSoon(tab.title)
            }
        } }
        BottomTabBar(tab, badges, navigator::selectTab)
    }
}

/** The Feed tab: RN's guest filters (TRENDING, HOT, NEW) over one feed each. */
@Composable
private fun HomeFeeds(env: AppEnvironment, entry: StackEntry, now: Long, actions: FeedActions) {
    val colors = LocalEcencyColors.current
    // kept in the root entry so the filter survives a trip to a post and back
    val tab = entry.get("tab") { mutableIntStateOf(0) }
    val tabs = listOf(RankedSort.TRENDING to UiCopy.TRENDING, RankedSort.HOT to UiCopy.HOT, RankedSort.CREATED to UiCopy.NEW)
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().background(colors.grayBackground).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { index, (_, label) ->
                val selected = index == tab.intValue
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        label,
                        color = if (selected) androidx.compose.ui.graphics.Color.White else colors.darkGray,
                        fontWeight = FontWeight.Bold, fontSize = 14.sp,
                        modifier = Modifier.clip(RoundedCornerShape(20.dp))
                            .background(if (selected) colors.blue else androidx.compose.ui.graphics.Color.Transparent)
                            .clickable(role = Role.Button) { tab.intValue = index }
                            .padding(horizontal = 20.dp, vertical = 4.dp).testTag("tab:" + listOf("TRENDING", "HOT", "NEW")[index]),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
        }
        val (sort, _) = tabs[tab.intValue]
        key(sort) {
            Guarded({ entry.get("feed:${sort.name}") { env.feedModel(FeedSource.Ranked(sort)) } }) { feed ->
                FeedList(feed, entry.get("list:${sort.name}") { LazyListState() }, env.votes.state, now, actions)
            }
        }
    }
}

/**
 * "Secure storage was reset on this device. Sign in again.": blocks the app (no back, no tap outside) until the
 * user confirms; only that confirmation removes the device's accounts ([com.ecency.core.app.StorageResetModel]).
 */
@Composable
private fun StorageResetPrompt(env: AppEnvironment) {
    val reset by env.storageReset.state.collectAsState()
    if (!reset.needed) return
    AlertDialog(
        onDismissRequest = {},
        properties = androidx.compose.ui.window.DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(com.ecency.core.app.StorageResetModel.TITLE) },
        text = {
            Column {
                Text(com.ecency.core.app.StorageResetModel.MESSAGE, modifier = Modifier.testTag("storageResetMessage"))
                reset.error?.let { Text(it, color = LocalEcencyColors.current.redText, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.padding(top = 8.dp).testTag("storageResetError")) }
            }
        },
        confirmButton = {
            TextButton(onClick = { env.storageReset.confirm() }, enabled = !reset.working, modifier = Modifier.testTag("storageResetConfirm")) {
                Text(com.ecency.core.app.StorageResetModel.CONFIRM)
            }
        },
    )
}

@Composable
private fun LockNotice(env: AppEnvironment, lock: AppLockState) {
    lock.notice?.let { notice ->
        AlertDialog(
            onDismissRequest = { env.appLock.clearNotice() },
            text = { Text(notice, modifier = Modifier.testTag("lockNotice")) },
            confirmButton = { TextButton(onClick = { env.appLock.clearNotice() }) { Text(UiCopy.OK) } },
        )
    }
}

@Composable
private fun LockPrompt(env: AppEnvironment, host: AppHost, lock: AppLockState, prompt: PinPrompt, biometricAvailable: Boolean) {
    val actions = remember(env, host) {
        PinPadActions(
            onDigit = env.appLock::enterDigit,
            onDelete = env.appLock::deleteDigit,
            onCancel = env.appLock::cancel,
            onForgot = env.appLock::forgotPin,
            onBiometric = {
                host.authenticateBiometric(
                    onSuccess = { env.appLock.biometricSucceeded(it) },
                    onInvalidated = { env.appLock.biometricInvalidated() },
                )
            },
        )
    }
    // once per lock: a wrong PIN or a cancelled prompt never starts it again by itself
    AutoBiometric(lock.autoStartsBiometric && biometricAvailable) {
        env.appLock.consumeAutoBiometric()
        actions.onBiometric()
    }
    PinScreen(lock.copy(biometricEnabled = lock.biometricEnabled && biometricAvailable), prompt, lockAvatar(lock.username, env.proxyBase), actions)
}

/** Toasts and the banner, read here so a new message never recomposes the root. */
@Composable
private fun BoxScope.MessagesLayer(env: AppEnvironment, onOpen: (AppScreen) -> Unit, onEntry: (NavEntry) -> Unit) {
    val messages by env.ui.state.collectAsState()
    // above every sheet and dialog opened before it (a window of its own)
    ToastWindow(messages.toast, env.ui::dismiss) { ToastView(it, env.ui::dismiss, Modifier.align(Alignment.BottomCenter), announces = false) }
    messages.banner?.let { BannerView(it, { b -> b.entry?.let(onEntry) ?: b.screen?.let(onOpen) }, env.ui::dismiss, Modifier.align(Alignment.TopCenter)) }
}

/** RN's "No connection!" strip, read here so connectivity changes never recompose the root. */
@Composable
private fun OfflineStrip(env: AppEnvironment) {
    val online by env.connectivity.state.collectAsState()
    if (!online.online) OfflineBanner()
}

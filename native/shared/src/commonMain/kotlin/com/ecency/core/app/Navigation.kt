package com.ecency.core.app

import com.ecency.core.api.AccountPostsSort
import com.ecency.core.api.RankedSort
import com.ecency.core.api.repository.FeedSource
import com.ecency.core.l10n.L

/**
 * Every screen of the RN app by its route name (src/constants/routeNames.ts), plus the two sheets the shell
 * opens. [title] names the RN feature (the "Coming soon" screen shows it); [built] says whether this app has
 * the screen yet. Later waves build a screen, flip [built] and give it a typed [AppScreen].
 */
enum class RouteName(val rnName: String, private val titleText: () -> String, val built: Boolean = false, val requiresLogin: Boolean = false) {
    // ROUTES.SCREENS
    BOOKMARKS("BookmarksScreen", { L.sideMenu.bookmarks }, built = true, requiresLogin = true),
    BOOST("BoostScreen", { L.wallet.getPoints }, built = true, requiresLogin = true),
    DRAFTS("DraftsScreen", { L.sideMenu.drafts }, built = true, requiresLogin = true),
    EDITOR("EditorScreen", { L.native.navigation.editor }, built = true, requiresLogin = true),
    FOLLOWS("FollowsScreen", { L.native.navigation.followers }, built = true),
    SPIN_GAME("SpinGameScreen", { L.native.navigation.spinGame }, built = true, requiresLogin = true),
    PERKS("PerksScreen", { L.perks.header }, built = true, requiresLogin = true),
    /** The drawer with the bottom tabs: the app's root. */
    FEED("FeedScreen", { L.home.feed }, built = true),
    LOGIN("LoginScreen", { L.wallet.login }, built = true),
    /** The app lock (AppLockModel); never pushed. */
    PINCODE("PinCodeScreen", { L.settings.pincode }, built = true),
    POST("PostScreen", { L.wallet.post }, built = true),
    PROFILE_EDIT("ProfileEditScreen", { L.native.navigation.editProfile }, built = true, requiresLogin = true),
    PROFILE("ProfileScreen", { L.sideMenu.profile }, built = true),
    REBLOGS("ReblogsScreen", { L.notification.reblogs }, built = true),
    REDEEM("RedeemScreen", { L.native.navigation.redeemPoints }, built = true, requiresLogin = true),
    REGISTER("RegisterScreen", { L.register.title }, built = true),
    SEARCH_RESULT("SearchResultScreen", { L.basicHeader.search }, built = true),
    TAG_RESULT("TagResultScreen", { L.searchResult.otherResultFilter.tag }, built = true),
    SETTINGS("SettingsScreen", { L.settings.settings }, built = true),
    TRANSFER("TransferScreen", { L.wallet.transfer }, built = true, requiresLogin = true),
    VOTERS("VotersScreen", { L.postPoll.voters }, built = true),
    ACCOUNT_BOOST("AccountBoostScreen", { L.perks.accountBoost }, built = true, requiresLogin = true),
    COMMUNITY("CommunityScreen", { L.native.navigation.community }, built = true),
    COMMUNITIES("CommunitiesScreen", { L.home.communities }, built = true),
    COMMUNITY_MEMBERS("CommunityMembersScreen", { L.native.navigation.communityMembers }, built = true),
    COMMUNITY_SETTINGS("CommunitySettingsScreen", { L.community.manageSettings }, built = true, requiresLogin = true),
    COMMUNITY_ACTIVITIES("CommunityActivitiesScreen", { L.native.navigation.communityActivities }, built = true),
    /** The system in-app browser (Custom Tabs, SFSafariViewController) stands in for RN's WebView screen. */
    WEB_BROWSER("WebBrowserScreen", { L.native.navigation.browser }, built = true),
    REFER("ReferScreen", { L.refer.referEarn }, built = true, requiresLogin = true),
    ASSET_DETAILS("AssetDetailsScreen", { L.native.navigation.assetDetails }, built = true, requiresLogin = true),
    EDIT_HISTORY("EditHistoryScreen", { L.native.navigation.editHistory }, built = true),
    WELCOME("WelcomeScreen", { L.native.navigation.welcome }),
    CHAT_THREAD("ChatThreadScreen", { L.native.navigation.chat }, built = true, requiresLogin = true),
    ACCOUNT_LIST("AccountListScreen", { L.sideMenu.accounts }, built = true),
    /** The current account's keys behind the PIN (RN backupKeysScreen); never restored after process death. */
    BACKUP_KEYS("BackupKeysScreen", { L.native.navigation.backupKeys }, built = true, requiresLogin = true),
    TRADE("TradeScreen", { L.native.navigation.trade }, built = true, requiresLogin = true),
    AI_IMAGE_GENERATOR("AiImageGeneratorScreen", { L.native.navigation.aiImageGenerator }, built = true, requiresLogin = true),
    /** The dApp browser with the Hive signing bridge: AppScreen.Explore (home) or AppScreen.DappBrowser (a page). */
    DAPP_BROWSER("DappBrowserScreen", { L.sideMenu.explore }, built = true),
    EMAIL_DIGESTS("EmailDigestsScreen", { L.settings.emailDigests }, built = true, requiresLogin = true),

    // ROUTES.MODALS
    ASSETS_SELECT("AssetsSelectModal", { L.native.navigation.selectAssets }, built = true, requiresLogin = true),
    ACCOUNT_LIST_MODAL("AccountListModal", { L.sideMenu.accounts }, built = true),
    POLL_WIZARD("PollWizardModal", { L.native.navigation.poll }, requiresLogin = true),
    BOT_COMMENTS("BotCommentsModal", { L.native.navigation.botComments }, built = true),
    HIVE_SIGNER("HiveSignerModal", { L.alert.authMethodHivesigner }),

    // sheets the shell opens (src/navigation/sheets.tsx)
    QR_SCAN("qr_sheet", { L.qr.qrScan }, built = true),
    ACCOUNTS_SHEET("accounts_sheet", { L.sideMenu.accounts }),
    ;

    /** The screen's title in the current language. */
    val title: String get() = titleText()

    companion object {
        fun ofRnName(name: String): RouteName? = entries.firstOrNull { it.rnName == name }
    }
}

/** What a tab shows while signed out. */
enum class TabGate {
    NONE,

    /** RN LoggedInContainer: "Login to see" with a button to the login screen (Wallet, Notifications). */
    LOGIN_PROMPT,

    /** RN chats: a message that sign-in is needed, with a button to the login screen. */
    LOGIN_MESSAGE,
}

/** Unread counts a tab icon can carry. */
enum class BadgeKind { NOTIFICATION, CHAT }

/**
 * The RN bottom tabs (src/navigation/botomTabNavigator.tsx) in their order. Icon only, no labels; [icon] is
 * outlined at rest and filled when the tab is selected ([AppIcon.WAVES] is RN's own wavy dash). The header flags are RN's
 * `<Header showQR showBoost hideSearch>` per tab screen.
 */
enum class AppTab(
    val rnName: String,
    private val titleText: () -> String,
    val icon: AppIcon,
    val gate: TabGate,
    val badge: BadgeKind?,
    val showQr: Boolean,
    val showSearch: Boolean,
    val showBoost: Boolean,
) {
    FEED("FeedTabbar", { L.home.feed }, AppIcon.HOME, TabGate.NONE, null, showQr = true, showSearch = true, showBoost = true),
    WAVES("WavesTabbar", { L.profile.waves }, AppIcon.WAVES, TabGate.NONE, null, showQr = false, showSearch = true, showBoost = false),
    CHATS("ChatsTabbar", { L.chats.title }, AppIcon.CHAT, TabGate.LOGIN_MESSAGE, BadgeKind.CHAT, showQr = false, showSearch = false, showBoost = false),
    WALLET("WalletTabbar", { L.profile.wallet }, AppIcon.WALLET, TabGate.LOGIN_PROMPT, null, showQr = true, showSearch = true, showBoost = false),
    NOTIFICATION("NotificationTabbar", { L.notification.notification }, AppIcon.NOTIFICATIONS, TabGate.LOGIN_PROMPT, BadgeKind.NOTIFICATION, showQr = false, showSearch = true, showBoost = false),
    ;

    /** The tab's name in the current language (accessibility; the tabs show icons only). */
    val title: String get() = titleText()

    /** The text a signed-out user sees on this tab, or null when the tab is public. */
    val loginText: String?
        get() = when (gate) {
            TabGate.NONE -> null
            TabGate.LOGIN_PROMPT -> L.profile.loginToSee
            TabGate.LOGIN_MESSAGE -> L.chats.loginRequired
        }

    /** RN only shows the boost (fire) button signed in. */
    fun showsBoost(loggedIn: Boolean): Boolean = showBoost && loggedIn

    companion object {
        /** RN `initialRouteName` and `backBehavior="initialRoute"`. */
        val INITIAL = FEED
    }
}

/**
 * A screen pushed above the tabs. Built screens are typed; the rest are [Pending] until their wave.
 *
 * The navigation is saved (Android saved state, iOS defaults) as plain text: a screen's arguments must never carry
 * secrets or private details (keys, memos, amounts, tokens). Such screens take them from a model at runtime, or are
 * never restored (see [NavState.decodeScreen] for [BackupKeys]).
 */
sealed interface AppScreen {
    val route: RouteName

    data class Post(val author: String, val permlink: String) : AppScreen {
        override val route get() = RouteName.POST
    }

    /** An account's profile; [section] is the tab a link asked for ("wallet", "comments", ...), empty for the default. */
    data class Profile(val author: String, val section: String = "") : AppScreen {
        /** Without a section (Swift sees no default arguments). */
        constructor(author: String) : this(author, "")

        override val route get() = RouteName.PROFILE
    }

    /** Followers of [username], or whom it follows ([following]); [count] is the profile's count, -1 when unknown. */
    data class Follows(val username: String, val following: Boolean, val count: Int = -1) : AppScreen {
        override val route get() = RouteName.FOLLOWS
    }

    /** The signed-in account's profile edit. */
    data object ProfileEdit : AppScreen {
        override val route get() = RouteName.PROFILE_EDIT
    }

    /** Bookmarks, favourites and followed tags; [tab] 0, 1 or 2 (RN showFavorites / showTags). */
    data class Bookmarks(val tab: Int = 0) : AppScreen {
        constructor() : this(0)

        override val route get() = RouteName.BOOKMARKS
    }

    /** RN account list (e.g. poll voters): [users] with an optional time each; [title] empty for "Users". */
    data class AccountList(val title: String, val users: List<AccountListEntry>) : AppScreen {
        override val route get() = RouteName.ACCOUNT_LIST
    }

    /** A tag (TagResult) or community feed. */
    data class Feed(val source: FeedSource, val title: String) : AppScreen {
        override val route: RouteName
            get() = when (source) {
                is FeedSource.Ranked -> if (source.tag.startsWith("hive-")) RouteName.COMMUNITY else RouteName.TAG_RESULT
                is FeedSource.Account -> RouteName.PROFILE
            }
    }

    data object Login : AppScreen {
        override val route get() = RouteName.LOGIN
    }

    data object Settings : AppScreen {
        override val route get() = RouteName.SETTINGS
    }

    /** The current account's keys, shown after the PIN (RN backupKeysScreen). Sensitive: never restored. */
    data object BackupKeys : AppScreen {
        override val route get() = RouteName.BACKUP_KEYS
    }

    /**
     * RN register screen. Only the referral (a public account name) is part of the saved navigation; a link's
     * username and email reach the screen through [AppEnvironment.signupPrefill], in memory.
     */
    data class Register(val referral: String = "") : AppScreen {
        constructor() : this("")

        override val route get() = RouteName.REGISTER
    }

    /** RN emailDigestsScreen: every digest subscription of the signed-in account. */
    data object EmailDigests : AppScreen {
        override val route get() = RouteName.EMAIL_DIGESTS
    }

    /** RN refer screen ("Refer & Earn") of the signed-in account. */
    data object Refer : AppScreen {
        override val route get() = RouteName.REFER
    }

    /** RN perks screen: quests, the streak and the ways to spend points (Perks.kt). */
    data object Perks : AppScreen {
        override val route get() = RouteName.PERKS
    }

    /**
     * RN boostScreen ("Get Points", StoreScreens.kt) buying for [username] (empty: the signed-in account). [productId]
     * (a purchase link) asks to confirm that product once; it is not part of the saved navigation.
     */
    data class GetPoints(val username: String = "", val productId: String = "") : AppScreen {
        override val route get() = RouteName.BOOST
    }

    /** RN accountBoostScreen for [username] (empty: the signed-in account). */
    data class AccountBoost(val username: String = "") : AppScreen {
        override val route get() = RouteName.ACCOUNT_BOOST
    }

    /** RN spin game ("Free Points", SpinGame.kt). */
    data object SpinGame : AppScreen {
        override val route get() = RouteName.SPIN_GAME
    }

    // ---- communities and search (CommunityModels.kt, SearchModels.kt) ----

    /** RN communities screen: Membership and Discover. */
    data object Communities : AppScreen {
        override val route get() = RouteName.COMMUNITIES
    }

    /** A community page; [filter] picks the feed tab ("trending", "hot", "created"). */
    data class Community(val name: String, val filter: String = "") : AppScreen {
        override val route get() = RouteName.COMMUNITY
    }

    data class CommunityMembers(val name: String, val title: String = "") : AppScreen {
        override val route get() = RouteName.COMMUNITY_MEMBERS
    }

    data class CommunitySettings(val name: String) : AppScreen {
        override val route get() = RouteName.COMMUNITY_SETTINGS
    }

    data class CommunityActivities(val name: String, val title: String = "") : AppScreen {
        override val route get() = RouteName.COMMUNITY_ACTIVITIES
    }

    /** RN searchResult: posts, people and communities. */
    data object Search : AppScreen {
        override val route get() = RouteName.SEARCH_RESULT
    }

    /** RN tagResult: a tag's feed with its own search field; [filter] picks the tab. */
    data class TagResult(val tag: String, val filter: String = "") : AppScreen {
        override val route get() = RouteName.TAG_RESULT
    }

    /** RN dappBrowser opened without a URL: the browser on its home tab (the dApp directory). */
    data object Explore : AppScreen {
        override val route get() = RouteName.DAPP_BROWSER
    }

    /**
     * RN dappBrowser with `url`: the browser with a first tab on [url]. Saved as [Explore] (the URL is browsing
     * history and may carry a page's tokens; it never goes into the saved navigation).
     */
    data class DappBrowser(val url: String) : AppScreen {
        override val route get() = RouteName.DAPP_BROWSER
    }

    /** RN QR_SCAN sheet (header and side menu): the scanner, whose codes go through the app's link handling. */
    data object QrScan : AppScreen {
        override val route get() = RouteName.QR_SCAN
    }

    // ---- feed, posts and reading ----

    /** RN voters screen for a post or comment. */
    data class Voters(val author: String, val permlink: String) : AppScreen {
        override val route get() = RouteName.VOTERS
    }

    /** RN reblogs screen ("Reblog Info"). */
    data class Reblogs(val author: String, val permlink: String) : AppScreen {
        override val route get() = RouteName.REBLOGS
    }

    /** RN editHistoryScreen. */
    data class EditHistory(val author: String, val permlink: String) : AppScreen {
        override val route get() = RouteName.EDIT_HISTORY
    }

    /** RN botComments modal: the bot replies of a post. */
    data class BotComments(val author: String, val permlink: String) : AppScreen {
        override val route get() = RouteName.BOT_COMMENTS
    }

    // ---- editor and publishing (EditorModel.kt) ----

    /**
     * RN editor. Only ids travel here (and into the saved navigation): the text itself comes back from the local
     * draft of the account that wrote it.
     */
    data class Editor(val args: EditorArgs) : AppScreen {
        constructor() : this(EditorArgs(EditorKind.NEW_POST))

        override val route get() = RouteName.EDITOR

        companion object {
            /**
             * From a route argument: "" a new post, "hive-123" a new post in that community, "author/permlink" an edit,
             * "reply:author/permlink" a reply, "draft:<id>" a saved draft, "template:<id>" a new post from a template.
             */
            fun of(arg: String): Editor {
                fun key(v: String) = com.ecency.core.api.model.PostKey.parse(v)
                return when {
                    arg.startsWith("reply:") -> key(arg.removePrefix("reply:"))?.let { Editor(EditorArgs(EditorKind.REPLY, it.author, it.permlink)) }
                    arg.startsWith("draft:") -> Editor(EditorArgs(EditorKind.NEW_POST, draftId = arg.removePrefix("draft:")))
                    arg.startsWith("template:") -> Editor(EditorArgs(EditorKind.NEW_POST, templateId = arg.removePrefix("template:")))
                    arg.contains('/') -> key(arg)?.let { Editor(EditorArgs(EditorKind.EDIT_POST, it.author, it.permlink)) }
                    arg.isNotEmpty() -> Editor(EditorArgs(EditorKind.NEW_POST, community = arg))
                    else -> null
                } ?: Editor()
            }
        }
    }

    /** RN drafts screen; [tab] 0 drafts, 1 schedules, 2 templates. */
    data class Drafts(val tab: Int = 0) : AppScreen {
        constructor() : this(0)

        override val route get() = RouteName.DRAFTS
    }

    /** RN aiImageGenerator opened on its own (no editor to insert into): the image can be shared, as in RN. */
    data object AiImageGenerator : AppScreen {
        override val route get() = RouteName.AI_IMAGE_GENERATOR
    }

    // ---- wallet (WalletModel.kt, TransferModel.kt, StakeModels.kt, WalletActionModels.kt) ----

    /** RN assetDetailsScreen of the signed-in account's [symbol]. */
    data class AssetDetails(val symbol: String) : AppScreen {
        override val route get() = RouteName.ASSET_DETAILS
    }

    /**
     * RN TransferScreen: [transferType] (RN TransferTypes: transfer, transfer_to_savings, delegate_vesting_shares,
     * withdraw_vesting, receive, stake, ...) of [fund] on [layer] ("hive", "points", "engine", "chain"), to the
     * public account [recipient]. Amounts and memos never travel here (saved navigation): a repeated row's prefill
     * goes through [AppEnvironment.transferPrefill], in memory.
     */
    data class Transfer(val transferType: String, val fund: String, val layer: String, val recipient: String = "") : AppScreen {
        override val route get() = RouteName.TRANSFER
    }

    /** RN AssetsSelectModal ("Add / Remove Assets"). */
    data object AssetsSelect : AppScreen {
        override val route get() = RouteName.ASSETS_SELECT
    }

    /** RN TradeScreen (swap HIVE and HBD), starting from [fund]. */
    data class Trade(val fund: String = "HIVE") : AppScreen {
        constructor() : this("HIVE")

        override val route get() = RouteName.TRADE
    }

    /** RN RedeemScreen: [redeemType] "promote" (of [permlink], "author/permlink"), "boost_plus" or "rc_topup". */
    data class Redeem(val redeemType: String, val permlink: String = "") : AppScreen {
        override val route get() = RouteName.REDEEM
    }

    /**
     * RN ChatThreadScreen: one conversation by its channel id; [type] is Mattermost's ("O", "P", "D", "G", or empty
     * when unknown). Who it is with is not part of the saved navigation: the screen reads it at runtime.
     */
    data class ChatThread(val channelId: String, val type: String = "") : AppScreen {
        override val route get() = RouteName.CHAT_THREAD
    }

    /** Not built yet: the "Coming soon" screen names [route]'s RN feature. [arg] is its main param (e.g. a url). */
    data class Pending(override val route: RouteName, val arg: String = "") : AppScreen {
        val title: String get() = route.title
    }

    companion object {
        /** The screen for [route]: typed when this app has it, else [Pending]. */
        fun of(route: RouteName, arg: String = ""): AppScreen = when (route) {
            RouteName.LOGIN -> Login
            RouteName.SETTINGS -> Settings
            RouteName.BACKUP_KEYS -> BackupKeys
            RouteName.REGISTER -> Register(arg)
            RouteName.REFER -> Refer
            RouteName.PERKS -> Perks
            RouteName.SPIN_GAME -> SpinGame
            RouteName.BOOST -> GetPoints(arg.takeIf { com.ecency.core.secure.KeyVault.isValidUsername(it) }.orEmpty())
            RouteName.ACCOUNT_BOOST -> AccountBoost(arg.takeIf { com.ecency.core.secure.KeyVault.isValidUsername(it) }.orEmpty())
            RouteName.EMAIL_DIGESTS -> EmailDigests
            RouteName.PROFILE -> if (arg.isNotEmpty()) Profile(arg) else Pending(route, arg)
            RouteName.FOLLOWS -> if (arg.isNotEmpty()) Follows(arg, following = false) else Pending(route, arg)
            RouteName.PROFILE_EDIT -> ProfileEdit
            RouteName.BOOKMARKS -> Bookmarks(arg.toIntOrNull() ?: 0)
            RouteName.ACCOUNT_LIST, RouteName.ACCOUNT_LIST_MODAL -> AccountList(arg, emptyList())
            RouteName.EDITOR -> Editor.of(arg)
            RouteName.DRAFTS -> Drafts(arg.toIntOrNull() ?: 0)
            RouteName.AI_IMAGE_GENERATOR -> AiImageGenerator
            RouteName.COMMUNITIES -> Communities
            RouteName.COMMUNITY -> if (arg.isNotEmpty()) Community(arg) else Communities
            RouteName.COMMUNITY_MEMBERS -> if (arg.isNotEmpty()) CommunityMembers(arg) else Pending(route, arg)
            RouteName.COMMUNITY_SETTINGS -> if (arg.isNotEmpty()) CommunitySettings(arg) else Pending(route, arg)
            RouteName.COMMUNITY_ACTIVITIES -> if (arg.isNotEmpty()) CommunityActivities(arg) else Pending(route, arg)
            RouteName.SEARCH_RESULT -> Search
            RouteName.TAG_RESULT -> if (arg.isNotEmpty()) TagResult(arg) else Search
            RouteName.DAPP_BROWSER -> if (arg.isEmpty()) Explore else DappBrowser(arg)
            RouteName.QR_SCAN -> QrScan
            // a bare account name is RN's "Delegate HP" (profile menu, referrals); nothing is a plain HIVE transfer
            RouteName.TRANSFER -> if (arg.isNotEmpty()) Transfer("delegate_vesting_shares", "HP", "hive", arg) else Transfer("transfer", "HIVE", "hive")
            RouteName.ASSETS_SELECT -> AssetsSelect
            RouteName.TRADE -> Trade(arg.ifEmpty { "HIVE" })
            RouteName.ASSET_DETAILS -> if (arg.isNotEmpty()) AssetDetails(arg) else Pending(route, arg)
            // an "author/permlink" is a promotion (the post menu); otherwise a Redeem type
            RouteName.CHAT_THREAD -> if (arg.isNotEmpty()) ChatThread(arg) else Pending(route, arg)
            RouteName.REDEEM -> when {
                arg == "boost_plus" || arg == "rc_topup" -> Redeem(arg)
                // RN's default redeem view is the promotion; only an "author/permlink" names its post
                else -> Redeem("promote", arg.takeIf { it.contains('/') }.orEmpty())
            }
            RouteName.VOTERS, RouteName.REBLOGS, RouteName.EDIT_HISTORY, RouteName.BOT_COMMENTS -> {
                val key = com.ecency.core.api.model.PostKey.parse(arg)
                when {
                    key == null -> Pending(route, arg)
                    route == RouteName.VOTERS -> Voters(key.author, key.permlink)
                    route == RouteName.REBLOGS -> Reblogs(key.author, key.permlink)
                    route == RouteName.EDIT_HISTORY -> EditHistory(key.author, key.permlink)
                    else -> BotComments(key.author, key.permlink)
                }
            }
            else -> Pending(route, arg)
        }
    }
}

/** One back stack entry; [id] tells two entries of the same screen apart (and keys their models). */
data class NavItem(val id: Long, val screen: AppScreen)

/**
 * Where a deep link (or a notification) goes: optionally a tab, whose stack is cleared first, then [screens]
 * pushed on top. Without a tab the screens go on top of what is open, like RN's `navigate`.
 */
data class NavEntry(val tab: AppTab?, val screens: List<AppScreen>) {
    companion object {
        fun tab(tab: AppTab): NavEntry = NavEntry(tab, emptyList())

        fun screen(screen: AppScreen): NavEntry = NavEntry(null, listOf(screen))

        /** A ranked tag feed is the tag screen, a community's the community page (RN TAG_RESULT, COMMUNITY). */
        fun feedScreen(source: FeedSource, title: String): AppScreen = when {
            source is FeedSource.Ranked && source.tag.startsWith("hive-") -> AppScreen.Community(source.tag, source.sort.apiValue)
            source is FeedSource.Ranked && source.tag.isNotEmpty() -> AppScreen.TagResult(source.tag, source.sort.apiValue)
            else -> AppScreen.Feed(source, title)
        }

        /** The in-app part of a [LinkRouter] result; null for what the platform opens (browser, video) or rejects. */
        fun from(route: Route): NavEntry? = when (route) {
            is Route.Post -> screen(AppScreen.Post(route.author, route.permlink))
            is Route.Profile -> screen(AppScreen.Profile(route.author))
            is Route.Feed -> screen(feedScreen(route.source, route.title))
            is Route.ExternalBrowser, is Route.VideoPlayer, is Route.AppLink, is Route.Unsupported, is Route.InPage -> null
        }
    }
}

/**
 * The app's navigation as a value: the selected tab, the screens pushed above the tabs (RN's main stack, which
 * covers the tab bar) and whether the side menu is open. Every change returns a new state; the platforms keep
 * one and save [encode] for process death (Android) and scene restoration (iOS).
 */
data class NavState(
    val tab: AppTab = AppTab.INITIAL,
    val stack: List<NavItem> = emptyList(),
    val drawerOpen: Boolean = false,
    val nextId: Long = 1,
) {
    /** The screen on top, or null when the tabs show. */
    val top: AppScreen? get() = stack.lastOrNull()?.screen

    val atRoot: Boolean get() = stack.isEmpty()

    val screens: List<AppScreen> get() = stack.map { it.screen }

    /** Pushes [screen] (closing the side menu). Pushing the screen already on top does nothing. */
    fun push(screen: AppScreen): NavState {
        if (top == screen) return copy(drawerOpen = false)
        return copy(stack = stack + NavItem(nextId, screen), nextId = nextId + 1, drawerOpen = false)
    }

    /** [push], or the login screen instead when [screen] needs an account and nobody is signed in (RN showLoginAlert). */
    fun pushGated(screen: AppScreen, loggedIn: Boolean): NavState =
        push(if (!loggedIn && screen.route.requiresLogin) AppScreen.Login else screen)

    /**
     * The system back action, or null when there is nothing left to go back to (the system leaves the app).
     * Order: close the side menu, pop a screen, return to the Feed tab (RN backBehavior="initialRoute").
     */
    fun back(): NavState? = when {
        drawerOpen -> copy(drawerOpen = false)
        stack.isNotEmpty() -> copy(stack = stack.dropLast(1))
        tab != AppTab.INITIAL -> copy(tab = AppTab.INITIAL)
        else -> null
    }

    /** Pops down to [size] screens (an iOS swipe back or a back button that removed several). */
    fun popTo(size: Int): NavState = if (size >= stack.size) this else copy(stack = stack.take(size.coerceAtLeast(0)))

    /** Tab bar tap. Only reachable at the root; the stack is cleared anyway so a tab never shows a stale screen. */
    fun selectTab(tab: AppTab): NavState = copy(tab = tab, stack = emptyList(), drawerOpen = false)

    /** The side menu belongs to the tabs (RN's drawer wraps the tab navigator): it opens only at the root. */
    fun openDrawer(): NavState = if (stack.isEmpty()) copy(drawerOpen = true) else this

    fun closeDrawer(): NavState = if (drawerOpen) copy(drawerOpen = false) else this

    /** A deep link or notification: see [NavEntry]. Screens that need an account open the login screen when [loggedIn] is false. */
    fun open(entry: NavEntry, loggedIn: Boolean): NavState {
        var s = if (entry.tab != null) copy(tab = entry.tab, stack = emptyList()) else this
        s = s.copy(drawerOpen = false)
        for (screen in entry.screens) {
            s = s.pushGated(screen, loggedIn)
            if (s.top == AppScreen.Login) break // nothing behind the login screen that needs the account
        }
        return s
    }

    /**
     * Signed out (not wiped): RN keeps the user where they are. The tab stays (gated tabs show their sign-in
     * prompt), the side menu closes and every screen that needs an account leaves the stack.
     */
    fun afterSignOut(): NavState = afterAccountChange()

    /**
     * The current account changed (signed out, switched, a logout that moved to the next account, a link login as
     * another account): screens that need an account belonged to the previous one and leave the stack.
     */
    fun afterAccountChange(): NavState = copy(stack = stack.filter { !it.screen.route.requiresLogin }, drawerOpen = false)

    /**
     * A navigation saved while [owner] was signed in (null: nobody, or unknown), restored while [current] is.
     * For the same account it comes back as it was; for anyone else, or signed out, the screens that need an
     * account are dropped, as on any account change: a saved editor or drafts screen is never shown to another
     * account, and never restored for a navigation whose owner was not recorded.
     */
    fun restoredFor(owner: String?, current: String?): NavState =
        if (owner != null && owner == current) this else afterAccountChange()

    /** After a wipe or sign-out from the lock: back to the Feed tab, nothing pushed. */
    fun reset(): NavState = NavState(nextId = nextId)

    /** One line for the tab, then one per pushed screen. Ids are not saved (they are given again on restore). */
    fun encode(): List<String> = listOf(join("tab", tab.name)) + stack.map { encodeScreen(it.screen) }

    companion object {
        private const val SEP = '\u001F'

        /** Rows of an [AppScreen.AccountList] kept in the saved navigation. */
        const val ACCOUNT_LIST_SAVED_ROWS = 200

        private fun join(vararg fields: String) = fields.joinToString(SEP.toString()) { it.replace(SEP, ' ') }

        fun encodeScreen(screen: AppScreen): String = when (screen) {
            AppScreen.Login -> join("login")
            AppScreen.Settings -> join("settings")
            AppScreen.BackupKeys -> join("backupKeys")
            is AppScreen.Register -> join("register", screen.referral)
            AppScreen.Refer -> join("refer")
            AppScreen.EmailDigests -> join("emailDigests")
            is AppScreen.Post -> join("post", screen.author, screen.permlink)
            is AppScreen.Profile -> join("profile", screen.author, screen.section)
            is AppScreen.Follows -> join("follows", screen.username, screen.following.toString(), screen.count.toString())
            AppScreen.ProfileEdit -> join("profileEdit")
            is AppScreen.Bookmarks -> join("bookmarks", screen.tab.toString())
            is AppScreen.AccountList ->
                // saved state is small (Android's transaction limit): a long list comes back with its first rows only
                join("accountList", screen.title, *screen.users.take(ACCOUNT_LIST_SAVED_ROWS).flatMap { listOf(it.account, it.timestampMillis.toString()) }.toTypedArray())
            is AppScreen.Voters -> join("voters", screen.author, screen.permlink)
            is AppScreen.Reblogs -> join("reblogs", screen.author, screen.permlink)
            is AppScreen.EditHistory -> join("editHistory", screen.author, screen.permlink)
            is AppScreen.BotComments -> join("botComments", screen.author, screen.permlink)
            is AppScreen.Pending -> join("pending", screen.route.name, screen.arg)
            is AppScreen.Drafts -> join("drafts", screen.tab.toString())
            AppScreen.AiImageGenerator -> join("aiimage")
            is AppScreen.Editor -> screen.args.let { a ->
                join("editor", a.kind.name, a.author, a.permlink, a.draftId, a.templateId, a.community, a.tags.joinToString(","), a.rootAuthor, a.rootPermlink)
            }
            AppScreen.Communities -> join("communities")
            is AppScreen.Community -> join("community", screen.name, screen.filter)
            is AppScreen.CommunityMembers -> join("communityMembers", screen.name, screen.title)
            is AppScreen.CommunitySettings -> join("communitySettings", screen.name)
            is AppScreen.CommunityActivities -> join("communityActivities", screen.name, screen.title)
            AppScreen.Search -> join("search")
            is AppScreen.TagResult -> join("tag", screen.tag, screen.filter)
            AppScreen.Explore, is AppScreen.DappBrowser -> join("explore")
            AppScreen.QrScan -> join("qr")
            is AppScreen.AssetDetails -> join("assetDetails", screen.symbol)
            is AppScreen.Transfer -> join("transfer", screen.transferType, screen.fund, screen.layer, screen.recipient)
            AppScreen.AssetsSelect -> join("assetsSelect")
            is AppScreen.Trade -> join("trade", screen.fund)
            is AppScreen.Redeem -> join("redeem", screen.redeemType, screen.permlink)
            AppScreen.Perks -> join("perks")
            AppScreen.SpinGame -> join("spinGame")
            // the confirmation of a link's product is asked once, never again after a restore
            is AppScreen.GetPoints -> join("getPoints", screen.username)
            is AppScreen.AccountBoost -> join("accountBoost", screen.username)
            is AppScreen.ChatThread -> join("chatThread", screen.channelId, screen.type)
            is AppScreen.Feed -> when (val s = screen.source) {
                is FeedSource.Ranked -> join("ranked", s.sort.name, s.tag, screen.title)
                is FeedSource.Account -> join("account", s.account, s.sort.name, screen.title)
            }
        }

        /** Null for anything this build does not know (a stack saved by another version). */
        fun decodeScreen(line: String): AppScreen? = runCatching {
            val f = line.split(SEP)
            when (f[0]) {
                "login" -> AppScreen.Login
                "settings" -> AppScreen.Settings
                // the keys screen asks for the PIN again; never restored after process death
                "backupKeys" -> null
                "register" -> AppScreen.Register(f.getOrElse(1) { "" })
                "refer" -> AppScreen.Refer
                "emailDigests" -> AppScreen.EmailDigests
                "post" -> AppScreen.Post(f[1], f[2])
                "profile" -> AppScreen.Profile(f[1], f.getOrElse(2) { "" })
                "follows" -> AppScreen.Follows(f[1], f[2].toBooleanStrict(), f[3].toInt())
                "profileEdit" -> AppScreen.ProfileEdit
                "bookmarks" -> AppScreen.Bookmarks(f[1].toInt())
                "accountList" -> AppScreen.AccountList(
                    f[1], f.drop(2).chunked(2).filter { it.size == 2 }.map { AccountListEntry(it[0], it[1].toLong()) },
                )
                "voters" -> AppScreen.Voters(f[1], f[2])
                "reblogs" -> AppScreen.Reblogs(f[1], f[2])
                "editHistory" -> AppScreen.EditHistory(f[1], f[2])
                "botComments" -> AppScreen.BotComments(f[1], f[2])
                // a pending line naming the keys screen is refused too (never restored, however it was written)
                "pending" -> RouteName.valueOf(f[1]).takeIf { it != RouteName.BACKUP_KEYS }?.let { AppScreen.of(it, f[2]) }
                "ranked" -> AppScreen.Feed(FeedSource.Ranked(RankedSort.valueOf(f[1]), f[2]), f[3])
                "account" -> AppScreen.Feed(FeedSource.Account(f[1], AccountPostsSort.valueOf(f[2])), f[3])
                "drafts" -> AppScreen.Drafts(f[1].toInt())
                "aiimage" -> AppScreen.AiImageGenerator
                "editor" -> AppScreen.Editor(
                    EditorArgs(EditorKind.valueOf(f[1]), f[2], f[3], f[4], f[5], f[6], f[7].split(',').filter { it.isNotEmpty() }, f[8], f[9]),
                )
                "communities" -> AppScreen.Communities
                "community" -> AppScreen.Community(f[1], f[2])
                "communityMembers" -> AppScreen.CommunityMembers(f[1], f[2])
                "communitySettings" -> AppScreen.CommunitySettings(f[1])
                "communityActivities" -> AppScreen.CommunityActivities(f[1], f[2])
                "search" -> AppScreen.Search
                "tag" -> AppScreen.TagResult(f[1], f[2])
                "explore" -> AppScreen.Explore
                // the camera is not reopened after process death
                "qr" -> null
                "assetDetails" -> AppScreen.AssetDetails(f[1])
                "transfer" -> AppScreen.Transfer(f[1], f[2], f[3], f.getOrElse(4) { "" })
                "assetsSelect" -> AppScreen.AssetsSelect
                "trade" -> AppScreen.Trade(f[1])
                "redeem" -> AppScreen.Redeem(f[1], f.getOrElse(2) { "" })
                "perks" -> AppScreen.Perks
                "spinGame" -> AppScreen.SpinGame
                "getPoints" -> AppScreen.GetPoints(f.getOrElse(1) { "" }.takeIf { it.isEmpty() || com.ecency.core.secure.KeyVault.isValidUsername(it) } ?: return@runCatching null)
                "accountBoost" -> AppScreen.AccountBoost(f.getOrElse(1) { "" }.takeIf { it.isEmpty() || com.ecency.core.secure.KeyVault.isValidUsername(it) } ?: return@runCatching null)
                "chatThread" -> f[1].takeIf { it.isNotEmpty() }?.let { AppScreen.ChatThread(it, f.getOrElse(2) { "" }) }
                else -> null
            }
        }.getOrNull()

        /** The state [encode] saved; unknown lines are skipped, an unknown tab is the Feed tab. */
        fun decode(lines: List<String>): NavState {
            var tab = AppTab.INITIAL
            var state = NavState()
            for (line in lines) {
                val f = line.split(SEP)
                if (f[0] == "tab") {
                    tab = AppTab.entries.firstOrNull { it.name == f.getOrNull(1) } ?: AppTab.INITIAL
                } else {
                    decodeScreen(line)?.let { state = state.push(it) }
                }
            }
            return state.copy(tab = tab)
        }

        /** One string for platforms that save a single value (iOS SceneStorage). */
        fun decodeJoined(saved: String): NavState = decode(if (saved.isEmpty()) emptyList() else saved.split('\u001E'))

        fun encodeJoined(state: NavState): String = state.encode().joinToString("\u001E")
    }
}

/**
 * Content links that open the app (https://ecency.com/...; auth and signing links go to [AuthLinkModel] first).
 * Only the shapes the shell can show: a post, a profile, a tag or community feed. Everything is validated with
 * [com.ecency.core.render.blocks.SafeTarget], like body links.
 */
object ContentLinks {
    private val HOSTS = setOf("ecency.com", "www.ecency.com")
    private val SORTS = setOf("trending", "hot", "created")

    /** ecency.com profile tabs; a permlink never takes one of these names on ecency.com links. */
    private val PROFILE_SECTIONS = setOf(
        "blog", "posts", "comments", "replies", "communities", "wallet", "points", "engine", "spk", "trail",
        "followers", "following", "settings", "insights", "referrals", "permissions",
    )

    /**
     * ecency.com pages that are not a post: render-helper's SECTION_LIST plus the web's other profile pages. They
     * open the profile; they are never looked up as a post with that permlink.
     */
    private val OTHER_PROFILE_PAGES = com.ecency.core.render.methods.Consts.SECTION_LIST.toSet() +
        setOf("feed", "rss", "rss.xml", "insights", "payout", "activities")

    /** A Hive permlink as ecency.com links carry it. */
    private val PERMLINK = Regex("^[a-z0-9-]{1,256}$")

    /** First-party hosts whose pages the app may be handed (App Links, universal links: ecency.com and alpha). */
    private val FIRST_PARTY_HOSTS = HOSTS + "alpha.ecency.com"

    /**
     * A first-party https page the app was handed (App Link, universal link) that no screen claims ([entry] is null):
     * it opens in the in-app browser instead of bringing the app forward with nothing on screen. Null for anything
     * an [entry] opens, and for any other host or scheme. The auth links go to [AuthLinkModel] before this.
     */
    fun browserFallback(url: String): String? {
        val safe = com.ecency.core.render.blocks.SafeTarget.httpUrl(url) ?: return null
        if (!safe.startsWith("https://", ignoreCase = true)) return null
        val host = safe.substringAfter("://", "").substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':').lowercase()
        if (host !in FIRST_PARTY_HOSTS || entry(safe) != null) return null
        return safe
    }

    /** RN iOS's old `esteem://` scheme, read as `ecency://` (RN postUrlParser); any other link unchanged. */
    fun legacyScheme(url: String): String =
        if (url.startsWith("esteem://", ignoreCase = true)) "ecency://" + url.substring("esteem://".length) else url

    fun entry(url: String): NavEntry? {
        val safe = com.ecency.core.render.blocks.SafeTarget.httpUrl(url) ?: return null
        val rest = safe.substringAfter("://", "")
        val host = rest.substringBefore('/').substringBefore(':').lowercase()
        if (host !in HOSTS || !safe.startsWith("https://", ignoreCase = true)) return null
        val path = rest.substringAfter('/', "").substringBefore('?').substringBefore('#')
        val parts = path.split('/').filter { it.isNotEmpty() }
        // RN deepLinkParser: /waves/<author>/<permlink> is the wave's thread, /waves (any query) the Waves tab;
        // a `text` query opens the composer there (WavesService.openLink)
        if (parts.firstOrNull()?.lowercase() == "waves") {
            com.ecency.core.api.search.PostUrlParser.parse(safe)?.let { p ->
                val author = p.author
                val permlink = p.permlink
                if (parts.size == 3 && author != null && permlink != null) {
                    return com.ecency.core.render.blocks.SafeTarget.post(author, permlink)?.let { NavEntry.from(LinkRouter.route(it)) }
                }
            }
            return if (com.ecency.core.api.waves.Waves.composeText(safe) != null) NavEntry.tab(AppTab.WAVES) else null
        }
        val target: com.ecency.core.render.blocks.LinkTarget? = when {
            // /@author/followers and /following: the follows list (RN deepLinkParser)
            parts.size == 2 && parts[0].startsWith("@") && (parts[1] == "followers" || parts[1] == "following") ->
                return com.ecency.core.render.blocks.SafeTarget.account(parts[0].drop(1))
                    ?.let { NavEntry.screen(AppScreen.Follows(it, following = parts[1] == "following")) }
            // /@author/wallet, /points, /comments, /replies, /posts: the profile on that tab (RN deepLinkFilter,
            // "points" is the wallet); other sections open the profile
            parts.size == 2 && parts[0].startsWith("@") && parts[1] in PROFILE_SECTIONS ->
                return com.ecency.core.render.blocks.SafeTarget.account(parts[0].drop(1))?.let {
                    val section = when (parts[1]) {
                        "points" -> "wallet"
                        "wallet", "comments", "replies", "posts" -> parts[1]
                        else -> ""
                    }
                    NavEntry.screen(AppScreen.Profile(it, section))
                }
            // /@author/feed, /rss, /settings...: pages of the profile, never a post with that permlink
            parts.size == 2 && parts[0].startsWith("@") && parts[1].lowercase() in OTHER_PROFILE_PAGES ->
                return com.ecency.core.render.blocks.SafeTarget.account(parts[0].drop(1))?.let { NavEntry.screen(AppScreen.Profile(it, "")) }
            // /@author/permlink and /category/@author/permlink; anything that is no permlink is left to the browser
            parts.size == 2 && parts[0].startsWith("@") ->
                if (PERMLINK.matches(parts[1])) com.ecency.core.render.blocks.SafeTarget.post(parts[0].drop(1), parts[1]) else null
            parts.size == 3 && parts[1].startsWith("@") -> com.ecency.core.render.blocks.SafeTarget.post(parts[1].drop(1), parts[2])
            // bare /trending, /hot, /created (RN TAG_RESULT without a tag): the global ranked feed
            parts.size == 1 && parts[0] in SORTS -> {
                val filter = FeedFilter.entries.first { it.key == parts[0] }
                return NavEntry.screen(NavEntry.feedScreen(filter.source(null)!!, filter.label))
            }
            // /@author
            parts.size == 1 && parts[0].startsWith("@") ->
                com.ecency.core.render.blocks.SafeTarget.author(parts[0].drop(1))
            // /trending/tag, /hot/hive-123
            parts.size == 2 && parts[0] in SORTS ->
                if (parts[1].startsWith("hive-")) com.ecency.core.render.blocks.SafeTarget.communityTarget(parts[1], parts[0])
                else com.ecency.core.render.blocks.SafeTarget.tagTarget(parts[1], parts[0])
            else -> null
        }
        return target?.let { NavEntry.from(LinkRouter.route(it)) }
    }
}

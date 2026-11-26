package com.ecency.core.app

import com.ecency.core.l10n.L

/** RN side menu groups: core navigation, then utility entries below one divider. */
enum class MenuGroup { MAIN, FOOTER }

/**
 * The RN side menu entries (src/constants/sideMenuItems.ts) with their RN ids, English titles
 * (`side_menu.*`), icons and groups.
 */
enum class SideMenuItem(val id: String, private val titleText: () -> String, val icon: AppIcon, val group: MenuGroup) {
    PROFILE("profile", { L.sideMenu.profile }, AppIcon.PERSON, MenuGroup.MAIN),
    BOOKMARKS("bookmarks", { L.sideMenu.bookmarks }, AppIcon.BOOKMARK, MenuGroup.MAIN),
    DRAFTS("drafts", { L.sideMenu.drafts }, AppIcon.DRAFTS, MenuGroup.MAIN),
    COMMUNITIES("communities", { L.sideMenu.communities }, AppIcon.GROUP, MenuGroup.MAIN),
    EXPLORE("explore", { L.sideMenu.explore }, AppIcon.EXPLORE, MenuGroup.MAIN),
    ADD_ACCOUNT("add_account", { L.sideMenu.addAccount }, AppIcon.PERSON_ADD, MenuGroup.MAIN),
    QR("qr", { L.sideMenu.qr }, AppIcon.QR_SCAN, MenuGroup.FOOTER),
    REFER("refer", { L.sideMenu.refer }, AppIcon.SHARE, MenuGroup.FOOTER),
    DOCS("docs", { L.sideMenu.docs }, AppIcon.HELP, MenuGroup.FOOTER),

    /** Web's "Ask Esie": the direct message with Esie, the AI helper (signed in only, as chat needs a session). */
    ASK_ESIE("ask_esie", { L.native.sideMenu.askEsie }, AppIcon.AI_ASSIST, MenuGroup.FOOTER),
    SETTINGS("settings", { L.sideMenu.settings }, AppIcon.SETTINGS, MenuGroup.FOOTER),
    LOGOUT("logout", { L.sideMenu.logout }, AppIcon.POWER, MenuGroup.FOOTER),
    ;

    val title: String get() = titleText()
}

/** What a side menu tap does; the platform carries it out and closes the menu. */
sealed interface MenuAction {
    data class Open(val screen: AppScreen) : MenuAction

    /** http(s) in the in-app browser. */
    data class Browser(val url: String) : MenuAction

    /** RN asks "Are you sure you want to logout?" first. */
    data object ConfirmLogout : MenuAction

    /** The accounts sheet (switch account, add an existing one). */
    data object AccountsSheet : MenuAction

    /** The direct message with [username] (opened or started, as profile "Message"). */
    data class DirectMessage(val username: String) : MenuAction
}

object SideMenu {
    const val DOCS_URL = "https://docs.ecency.com/get-started/onboarding-checklist/"

    val LOGOUT_TITLE: String get() = L.sideMenu.logoutText

    /** RN order, except "Ask Esie" (native and web only) right after QR scan, above "Refer & Earn" (owner, 10-28). */
    val LOGGED_IN: List<SideMenuItem> = listOf(
        SideMenuItem.PROFILE, SideMenuItem.BOOKMARKS, SideMenuItem.DRAFTS, SideMenuItem.COMMUNITIES, SideMenuItem.EXPLORE,
        SideMenuItem.QR, SideMenuItem.ASK_ESIE, SideMenuItem.REFER, SideMenuItem.DOCS, SideMenuItem.SETTINGS,
        SideMenuItem.LOGOUT,
    )

    /** Signed out there is no chat session, so no "Ask Esie" (web hides it too). */
    val LOGGED_OUT: List<SideMenuItem> = listOf(SideMenuItem.ADD_ACCOUNT, SideMenuItem.QR, SideMenuItem.DOCS, SideMenuItem.SETTINGS)

    fun items(loggedIn: Boolean): List<SideMenuItem> = if (loggedIn) LOGGED_IN else LOGGED_OUT

    /**
     * One group of the menu, in RN order. The platforms lay the main group out from the top (scrolling on its own
     * when it does not fit) and pin the footer group to the bottom, with [GROUP_GAP_DP] of space between them in
     * place of RN's divider line.
     */
    fun items(loggedIn: Boolean, group: MenuGroup): List<SideMenuItem> = items(loggedIn).filter { it.group == group }

    /** The space between the two groups (dp / pt). */
    const val GROUP_GAP_DP = 16

    /** The least room the main group keeps (one row) while the footer is pinned. */
    const val MIN_MAIN_HEIGHT_DP = 56

    /**
     * Whether the footer group (with the version line) can stay pinned in [availableHeight] below the header, the
     * main group scrolling on its own above it. False only where the footer leaves the main group less than
     * [MIN_MAIN_HEIGHT_DP] (a landscape phone, the largest text sizes): there the whole menu scrolls as one.
     */
    fun pinsFooter(availableHeight: Float, footerHeight: Float): Boolean =
        availableHeight - footerHeight - GROUP_GAP_DP >= MIN_MAIN_HEIGHT_DP

    /**
     * What [item] does for [username] (null when signed out). "Add Account" opens the accounts sheet when
     * other accounts are on the device ([hasOtherAccounts]), else the login screen, as RN does.
     */
    fun action(item: SideMenuItem, username: String?, hasOtherAccounts: Boolean): MenuAction = when (item) {
        SideMenuItem.PROFILE -> MenuAction.Open(username?.let { AppScreen.Profile(it) } ?: AppScreen.Login)
        SideMenuItem.BOOKMARKS -> MenuAction.Open(AppScreen.of(RouteName.BOOKMARKS))
        SideMenuItem.DRAFTS -> MenuAction.Open(AppScreen.of(RouteName.DRAFTS))
        SideMenuItem.COMMUNITIES -> MenuAction.Open(AppScreen.of(RouteName.COMMUNITIES))
        SideMenuItem.EXPLORE -> MenuAction.Open(AppScreen.of(RouteName.DAPP_BROWSER))
        SideMenuItem.ADD_ACCOUNT -> if (hasOtherAccounts) MenuAction.AccountsSheet else MenuAction.Open(AppScreen.Login)
        SideMenuItem.QR -> MenuAction.Open(AppScreen.of(RouteName.QR_SCAN))
        SideMenuItem.REFER -> MenuAction.Open(AppScreen.of(RouteName.REFER))
        SideMenuItem.DOCS -> MenuAction.Browser(DOCS_URL)
        SideMenuItem.ASK_ESIE -> if (username != null) MenuAction.DirectMessage(OfficialAccounts.ESIE_USERNAME) else MenuAction.Open(AppScreen.Login)
        SideMenuItem.SETTINGS -> MenuAction.Open(AppScreen.Settings)
        SideMenuItem.LOGOUT -> MenuAction.ConfirmLogout
    }

    /** RN footer: `v<version>, <build><storage>`; the native app has one storage, so no storage letter. */
    fun versionText(version: String, build: String): String = "v$version, $build"
}

/** The side menu header: RN shows the display name, @username and the voting power. */
data class DrawerHeader(
    val username: String,
    val displayName: String?,
    /** RN `getVotingPower(account).toFixed(1)`, e.g. "87.3". */
    val votingPower: String,
)

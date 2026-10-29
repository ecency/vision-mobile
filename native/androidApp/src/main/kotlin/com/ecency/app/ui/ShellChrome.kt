package com.ecency.app.ui

import androidx.compose.foundation.layout.heightIn

import androidx.compose.ui.semantics.heading


import androidx.compose.ui.semantics.Role

import com.ecency.core.app.AppIcon
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ecency.core.app.AppTab
import com.ecency.core.app.Badges
import com.ecency.core.app.ShellUiCopy
import com.ecency.core.app.UiCopy

/** RN tabBarActiveTintColor / tabBarInactiveTintColor (the same in both themes). */
private val TabActive = Color(0xFF357CE6)
private val TabInactive = Color(0xFFC1C5C7)

/** RN BottomTabBar: five icons, no labels, 26 dp, 15 dp from the top, the bottom inset (or 16) below. */
@Composable
fun BottomTabBar(selected: AppTab, badges: Badges, onSelect: (AppTab) -> Unit) {
    val colors = LocalEcencyColors.current
    val inset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Row(
        Modifier.fillMaxWidth().shadow(15.dp).background(colors.background)
            .padding(bottom = if (inset > 0.dp) inset else 16.dp).testTag("tabBar"),
    ) {
        AppTab.entries.forEach { tab ->
            val active = tab == selected
            Box(
                Modifier.weight(1f).clickable(role = Role.Tab) { if (!active) onSelect(tab) }.padding(top = 15.dp, bottom = 4.dp)
                    .semantics {
                        contentDescription = badges.text(tab.badge)?.let { ShellUiCopy.tabUnread(tab.title, it) } ?: tab.title
                        this.selected = active
                    }.testTag("bottomTab:${tab.name}"),
                contentAlignment = Alignment.Center,
            ) {
                // the selected icon takes its colour with a short fade and a small pop (Motion.kt)
                val tint by androidx.compose.animation.animateColorAsState(
                    if (active) TabActive else TabInactive, LocalMotion.current.fade(), label = "tabTint",
                )
                Glyph(tab.icon, tint, 26.dp, Modifier.popOnChange(active, enabled = active), filled = active)
                badges.text(tab.badge)?.let { text ->
                    Text(
                        text, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                        modifier = Modifier.offset(x = 12.dp, y = (-8).dp).clip(CircleShape).background(colors.red)
                            .padding(horizontal = 4.dp).testTag("badge:${tab.name}"),
                    )
                }
            }
        }
    }
}

/**
 * RN Header on every tab: the avatar in a gradient pill (opens the side menu), the display name and
 * "@user" (no reputation: only the quick profile shows it) or, signed out, "Login to customize your feed", then QR, search and boost per tab.
 */
@Composable
fun AppHeader(
    tab: AppTab,
    username: String?,
    displayName: String?,
    avatarUrl: String,
    onAvatar: () -> Unit,
    onQr: () -> Unit,
    onSearch: () -> Unit,
    onBoost: () -> Unit,
) {
    val colors = LocalEcencyColors.current
    Row(Modifier.fillMaxWidth().background(colors.background).padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.width(68.dp).height(50.dp)
                .clickable(onClick = onAvatar, role = Role.Button)
                .clip(RoundedCornerShape(topEnd = 34.dp, bottomEnd = 34.dp))
                .background(Brush.horizontalGradient(colors.headerGradient))
                .semantics { contentDescription = username?.let { ShellUiCopy.accountOf(it) } ?: ShellUiCopy.ACCOUNT_SIGNED_OUT }
                .testTag("account"),
            contentAlignment = Alignment.CenterEnd,
        ) {
            if (username != null) Avatar(avatarUrl, 32.dp, Modifier.padding(end = 12.dp))
            else Box(Modifier.padding(end = 12.dp).size(32.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                Glyph(AppIcon.PERSON, colors.icon, 24.dp)
            }
        }
        Column(Modifier.weight(1f).padding(horizontal = 8.dp), verticalArrangement = Arrangement.Center) {
            if (username != null) {
                displayName?.let { Text(it, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = colors.darkText, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                Text("@$username", fontSize = 12.sp, color = colors.darkText, modifier = Modifier.testTag("headerUser"))
            } else {
                Text(ShellUiCopy.LOGIN_TO_CUSTOMIZE_FEED, fontSize = 14.sp, color = colors.darkGray, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("headerLogin"))
            }
        }
        Row(Modifier.padding(end = 16.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            if (tab.showQr) HeaderIcon(AppIcon.QR_SCAN, colors.icon, ShellUiCopy.QR_SCAN, "headerQr", onQr, Modifier.padding(end = 8.dp))
            if (tab.showSearch) HeaderIcon(AppIcon.SEARCH, colors.icon, UiCopy.SEARCH, "headerSearch", onSearch)
            if (tab.showsBoost(username != null)) HeaderIcon(AppIcon.BOOST, colors.blueText, ShellUiCopy.PERKS, "headerBoost", onBoost, Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun HeaderIcon(icon: AppIcon, tint: Color, label: String, tag: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    // icon only: an unbounded ripple from the touch point (Motion.kt iconClickable)
    Box(modifier.iconClickable(radius = 20.dp, onClick = onClick).padding(8.dp).testTag(tag)) { Glyph(icon, tint, 24.dp, description = label) }
}

/** RN FabButton's look: a 56 dp blue circle, 8 dp elevation, a 24 dp white glyph, 16 dp from the screen edges. */
object FabTokens {
    val SIZE = 56.dp
    val MARGIN = 16.dp
    val ELEVATION = 8.dp
    val ICON = 24.dp
}

/**
 * A floating round button in the FAB's look ([FabTokens]): the compose pen and the lists' scroll to top. Fixed in
 * dp (a font size never grows it), with its margin to the screen edges included.
 */
@Composable
fun FabButton(icon: AppIcon, description: String, tag: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    // the button sinks a little while pressed and springs back (Motion.kt); the ripple stays
    val interaction = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Box(
        modifier.padding(FabTokens.MARGIN).pressScale(interaction, MotionPressScale.FAB).size(FabTokens.SIZE).shadow(FabTokens.ELEVATION, CircleShape).clip(CircleShape)
            .background(LocalEcencyColors.current.blue)
            .clickable(interactionSource = interaction, indication = androidx.compose.material3.ripple(), onClick = onClick, role = Role.Button).testTag(tag),
        contentAlignment = Alignment.Center,
    ) { Glyph(icon, Color.White, FabTokens.ICON, description = description) }
}

/** RN FabButton: the pencil at the bottom end. */
@Composable
fun CreateFab(onClick: () -> Unit, modifier: Modifier = Modifier) = FabButton(AppIcon.EDIT, ShellUiCopy.CREATE, "createFab", onClick, modifier)

/** RN LoggedInContainer / chats sign-in message for a gated tab. */
@Composable
fun LoginPrompt(text: String, onLogin: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(32.dp).testTag("loginPrompt"), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Glyph(AppIcon.PERSON, LocalEcencyColors.current.icon, 96.dp)
        Spacer(Modifier.height(16.dp))
        Text(text, color = LocalEcencyColors.current.darkGray, fontSize = 15.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Text(
            UiCopy.LOGIN, color = Color.White, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(24.dp)).background(LocalEcencyColors.current.blue).clickable(onClick = onLogin, role = Role.Button)
                .padding(horizontal = 32.dp, vertical = 10.dp).testTag("loginPromptButton"),
        )
    }
}

/** The one screen for every RN feature not built yet; it names the feature. */
@Composable
fun ComingSoon(title: String, modifier: Modifier = Modifier) {
    val colors = LocalEcencyColors.current
    Column(modifier.fillMaxSize().padding(32.dp).testTag("comingSoon"), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = colors.text, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() }.testTag("comingSoonTitle"))
        Spacer(Modifier.height(8.dp))
        Text(ShellUiCopy.COMING_SOON_TO_THE, fontSize = 14.sp, color = colors.darkGray, textAlign = TextAlign.Center)
    }
}

/** RN ErrorBoundary fallback: sad Esie in place of RN's icon, "Something went wrong", the browser hint and "Try again?". */
@Composable
fun ShellErrorScreen(onRetry: () -> Unit) {
    val colors = LocalEcencyColors.current
    Column(Modifier.fillMaxSize().background(colors.background).padding(16.dp).testTag("errorBoundary"), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        EsieView(com.ecency.core.esie.EsiePlacement.ERROR, Modifier.padding(bottom = 12.dp))
        Text(UiCopy.SOMETHING_WRONG, fontSize = 20.sp, color = colors.text)
        Text(ShellUiCopy.SOMETHING_WRONG_ALT, fontSize = 15.sp, color = colors.text, textAlign = TextAlign.Center, modifier = Modifier.padding(15.dp))
        Text(UiCopy.TRY_AGAIN_QUESTION, fontSize = 15.sp, color = colors.blueText, modifier = Modifier.clickable(onClick = onRetry, role = Role.Button).padding(top = 20.dp).testTag("errorRetry"))
    }
}

/** RN NoInternetConnection: a gray strip with "No connection!" at the bottom, above the system bar inset. */
@Composable
fun OfflineBanner() {
    val colors = LocalEcencyColors.current
    Row(
        Modifier.fillMaxWidth().background(colors.noConnection).heightIn(min = 45.dp).testTag("offlineBanner"),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(AppIcon.INFO, Color.White, 16.dp, Modifier.padding(end = 5.dp))
        Text(ShellUiCopy.NO_CONNECTION, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

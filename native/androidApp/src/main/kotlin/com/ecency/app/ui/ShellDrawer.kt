package com.ecency.app.ui


import androidx.compose.ui.semantics.contentDescription

import androidx.compose.ui.semantics.semantics

import androidx.compose.ui.semantics.Role

import com.ecency.core.app.MotionTokens

import com.ecency.core.app.AppIcon
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ecency.app.R
import com.ecency.core.app.DrawerHeader
import com.ecency.core.app.MenuGroup
import com.ecency.core.app.SideMenu
import com.ecency.core.app.SideMenuItem
import com.ecency.core.app.UiCopy

/** react-navigation's drawer on phones: 280 dp over a half-black scrim. */
private val DrawerWidth = 280.dp

/**
 * The drawer's width: RN's 280 dp, wider with larger fonts as on iOS (the rows' words and the account lines get
 * room), never more than 85% of the screen (92% from 1.5x, where the rows drop their icons).
 */
internal fun drawerWidth(fontScale: Float, screenWidth: androidx.compose.ui.unit.Dp): androidx.compose.ui.unit.Dp =
    minOf(DrawerWidth * maxOf(1f, fontScale), screenWidth * (if (fontScale >= 1.5f) 0.92f else 0.85f))

/**
 * The RN side menu (components/sideMenu) as a drawer over the tabs. Composed only while open (or animating),
 * so the closed app pays nothing for it.
 */
@Composable
fun SideMenuDrawer(
    open: Boolean,
    username: String?,
    header: DrawerHeader?,
    avatarUrl: String,
    version: String,
    onItem: (SideMenuItem) -> Unit,
    onAccounts: () -> Unit,
    onClose: () -> Unit,
) {
    // zIndex: the create button and tab bar draw with elevation, which would otherwise lift them over the scrim
    // the slide follows the system motion setting: a crossfade when reduced, nothing when animations are off
    val motion = LocalMotion.current
    AnimatedVisibility(open, Modifier.zIndex(10f), enter = fadeIn(motion.fade(MotionTokens.MEDIUM_MS)), exit = fadeOut(motion.fade(MotionTokens.MEDIUM_MS))) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f))
                .clickable(remember { MutableInteractionSource() }, null, onClickLabel = UiCopy.CLOSE, onClick = onClose, role = Role.Button)
                .semantics { contentDescription = UiCopy.CLOSE }.testTag("drawerScrim"),
        )
    }
    val slides = motion.level == com.ecency.core.app.MotionLevel.FULL
    AnimatedVisibility(
        open, Modifier.zIndex(11f),
        enter = if (slides) slideInHorizontally { -it } else fadeIn(motion.fade(MotionTokens.MEDIUM_MS)),
        exit = if (slides) slideOutHorizontally { -it } else fadeOut(motion.fade(MotionTokens.MEDIUM_MS)),
    ) {
        SideMenuPanel(username, header, avatarUrl, version, onItem, onAccounts)
    }
}

@Composable
private fun SideMenuPanel(
    username: String?,
    header: DrawerHeader?,
    avatarUrl: String,
    version: String,
    onItem: (SideMenuItem) -> Unit,
    onAccounts: () -> Unit,
) {
    val colors = LocalEcencyColors.current
    val loggedIn = username != null
    Column(
        Modifier.width(drawerWidth(LocalDensity.current.fontScale, androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp)).fillMaxHeight().background(colors.background).navigationBarsPadding()
            .pointerInput(Unit) { detectTapGestures { } }.testTag("drawer"), // taps on the panel never reach the scrim
    ) {
        // header: 164 dp of the blue Ecency background, the account on its bottom edge; taller when the account's
        // lines need it (large fonts), never cutting them off under the status bar. The picture fills the header
        // and is cut to it (Image clips a cropped painter to its own bounds, the header's)
        Box(Modifier.fillMaxWidth().heightIn(min = 164.dp).background(colors.blue).testTag("drawerHeader")) {
            Image(painterResource(R.drawable.side_menu_background), null, Modifier.matchParentSize().testTag("drawerHeaderImage"), contentScale = ContentScale.Crop)
            if (username != null) {
                Row(
                    Modifier.align(Alignment.BottomStart).statusBarsPadding().padding(bottom = 20.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(avatarUrl, 64.dp, Modifier.padding(start = 20.dp))
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        header?.displayName?.let {
                            Text(it, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("drawerName"))
                        }
                        Text("@$username", color = Color.White.copy(alpha = 0.6f), fontWeight = FontWeight.Medium, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp).testTag("drawerUser"))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Glyph(AppIcon.COLLAPSE, colors.icon, 18.dp)
                            Text(header?.votingPower ?: "0", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.testTag("drawerVp"))
                        }
                    }
                    Box(
                        Modifier.padding(start = 8.dp, end = 16.dp).size(28.dp).clip(CircleShape).border(1.dp, Color.White, CircleShape)
                            .clickable(onClick = onAccounts, role = Role.Button).testTag("drawerAccounts"),
                        contentAlignment = Alignment.Center,
                    ) { Glyph(AppIcon.MORE, Color.White, 14.dp, description = UiCopy.ACCOUNTS) }
                }
            }
        }
        // the main group from the top, scrolling on its own when it does not fit (small screens, large text);
        // a gap instead of RN's divider, then the footer group pinned to the bottom and always visible. Only
        // where the footer would leave the main group no room (landscape, a 320 x 640 dp phone with its six footer
        // rows, the largest text) does the whole menu scroll as one (SideMenu.pinsFooter).
        // The footer is measured first, in the same pass, so the choice never flips after the first frame.
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val available = constraints.maxHeight
            val density = LocalDensity.current
            val gap = with(density) { SideMenu.GROUP_GAP_DP.dp.roundToPx() }
            androidx.compose.ui.layout.Layout(
                contents = listOf(
                    {
                        Column(Modifier.verticalScroll(rememberScrollState()).testTag("drawerMain")) {
                            SideMenu.items(loggedIn, MenuGroup.MAIN).forEach { MenuRow(it, onItem) }
                        }
                    },
                    {
                        Column(Modifier.testTag("drawerFooter")) {
                            SideMenu.items(loggedIn, MenuGroup.FOOTER).forEach { MenuRow(it, onItem) }
                            Text(version, color = colors.darkGray, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("drawerVersion"))
                            Spacer(Modifier.height(4.dp))
                        }
                    },
                ),
                // scrolls only when the footer is not pinned (then the content is taller than the space)
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("drawerMenu"),
            ) { (main, footer), c ->
                val width = c.maxWidth
                val f = footer.first().measure(androidx.compose.ui.unit.Constraints.fixedWidth(width))
                if (SideMenu.pinsFooter(available / density.density, f.height / density.density)) {
                    val m = main.first().measure(androidx.compose.ui.unit.Constraints(width, width, 0, (available - f.height - gap).coerceAtLeast(0)))
                    layout(width, available) {
                        m.place(0, 0)
                        f.place(0, available - f.height)
                    }
                } else {
                    // one scroll: the main group at its full height, then the gap and the footer
                    val natural = main.first().maxIntrinsicHeight(width)
                    val m = main.first().measure(androidx.compose.ui.unit.Constraints(width, width, natural, natural))
                    layout(width, m.height + gap + f.height) {
                        m.place(0, 0)
                        f.place(0, m.height + gap)
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuRow(item: SideMenuItem, onItem: (SideMenuItem) -> Unit) {
    val colors = LocalEcencyColors.current
    val main = item.group == MenuGroup.MAIN
    // as iOS: from 1.5x the icon gives its room to the word and RN's 55 dp indent shrinks (long words broke mid-word)
    val stacked = LocalDensity.current.fontScale >= 1.5f
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button) { onItem(item) }.padding(vertical = 15.dp).padding(start = if (stacked) 16.dp else 55.dp, end = 8.dp)
            .testTag("menu:${item.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!stacked) Glyph(item.icon, colors.icon, 20.dp, Modifier.padding(end = 5.dp))
        Text(
            item.title, modifier = Modifier.padding(start = if (stacked) 0.dp else 12.dp),
            color = if (main) colors.text else colors.darkGray,
            fontWeight = if (main) FontWeight.Bold else FontWeight.Medium,
            fontSize = if (main) 15.sp else 14.sp,
        )
    }
}

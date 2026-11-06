package com.ecency.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ecency.app.R
import com.ecency.core.app.AppEnvironment
import com.ecency.core.app.LauncherIcon
import com.ecency.core.app.LauncherIconCopy
import com.ecency.core.l10n.L

/**
 * Issue #3656, the settings' "App icon" card (README "App icons"): seasonal icons on or off, and every icon to pick by
 * hand (the everyday one included). The launcher changes once the app goes to the background.
 */
@Composable
internal fun AppIconSection(env: AppEnvironment) {
    val colors = LocalEcencyColors.current
    val model = env.launcherIcons
    val state by model.state.collectAsState()
    CardTitle(LauncherIconCopy.TITLE)
    ToggleRow(LauncherIconCopy.SEASONAL, state.seasonal, "settings:seasonalIcons") { model.setSeasonal(it) }
    Text(LauncherIconCopy.SEASONAL_HINT, color = colors.darkText, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
    FlowRow(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        for (icon in LauncherIcon.entries) {
            val chosen = icon == state.current
            Box(
                Modifier.size(52.dp).clip(CircleShape)
                    .border(if (chosen) 3.dp else 1.dp, if (chosen) colors.blueText else colors.border, CircleShape)
                    .clickable(role = Role.RadioButton) { model.pick(icon) }
                    .semantics {
                        contentDescription = icon.title
                        selected = chosen
                    }
                    .testTag("appIcon:${icon.key}"),
                contentAlignment = Alignment.Center,
            ) { LauncherIconPreview(icon, Modifier.requiredSize(78.dp)) }
        }
    }
    Text(L.native.android.appIconApplies, color = colors.darkText, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
}

/** An icon as the launcher draws it: the adaptive layers at full size (108 units), cut by the caller's shape. */
@Composable
internal fun LauncherIconPreview(icon: LauncherIcon, modifier: Modifier = Modifier) {
    val (background, foreground) = launcherLayers(icon)
    Box(modifier) {
        if (background != null) Image(painterResource(background), null, Modifier.matchParentSize())
        // the everyday icon's foreground carries its own tile; the manifest's background behind it is white
        else Box(Modifier.matchParentSize().background(colorResource(R.color.ic_launcher_background)))
        Image(painterResource(foreground), null, Modifier.matchParentSize())
    }
}

/** Each icon's adaptive layers (scripts/icons/seasonal_icons.py writes them); the everyday icon is the RN app's. */
internal fun launcherLayers(icon: LauncherIcon): Pair<Int?, Int> = when (icon) {
    LauncherIcon.DEFAULT -> null to R.mipmap.ic_launcher_foreground
    LauncherIcon.YEAR_START -> R.drawable.launcher_new_year_background to R.drawable.launcher_new_year_foreground
    LauncherIcon.LUNAR_NEW_YEAR -> R.drawable.launcher_lunar_new_year_background to R.drawable.launcher_lunar_new_year_foreground
    LauncherIcon.VALENTINES -> R.drawable.launcher_valentines_background to R.drawable.launcher_valentines_foreground
    LauncherIcon.RAMADAN_EID -> R.drawable.launcher_ramadan_eid_background to R.drawable.launcher_ramadan_eid_foreground
    LauncherIcon.HIVE_DAY -> R.drawable.launcher_hive_day_background to R.drawable.launcher_hive_day_foreground
    LauncherIcon.NOWRUZ -> R.drawable.launcher_nowruz_background to R.drawable.launcher_nowruz_foreground
    LauncherIcon.HOLI -> R.drawable.launcher_holi_background to R.drawable.launcher_holi_foreground
    LauncherIcon.EARTH_DAY -> R.drawable.launcher_earth_day_background to R.drawable.launcher_earth_day_foreground
    LauncherIcon.PIZZA_DAY -> R.drawable.launcher_pizza_day_background to R.drawable.launcher_pizza_day_foreground
    LauncherIcon.FOOTBALL -> R.drawable.launcher_football_background to R.drawable.launcher_football_foreground
    LauncherIcon.SUMMER_GAMES -> R.drawable.launcher_summer_games_background to R.drawable.launcher_summer_games_foreground
    LauncherIcon.PROGRAMMERS_DAY -> R.drawable.launcher_programmers_day_background to R.drawable.launcher_programmers_day_foreground
    LauncherIcon.MID_AUTUMN -> R.drawable.launcher_mid_autumn_background to R.drawable.launcher_mid_autumn_foreground
    LauncherIcon.DIWALI -> R.drawable.launcher_diwali_background to R.drawable.launcher_diwali_foreground
    LauncherIcon.HALLOWEEN -> R.drawable.launcher_halloween_background to R.drawable.launcher_halloween_foreground
    LauncherIcon.BLACK_FRIDAY -> R.drawable.launcher_black_friday_background to R.drawable.launcher_black_friday_foreground
    LauncherIcon.CHRISTMAS -> R.drawable.launcher_christmas_background to R.drawable.launcher_christmas_foreground
}

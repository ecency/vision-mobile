package com.ecency.app.ui

import androidx.compose.ui.semantics.selected

import androidx.compose.ui.semantics.semantics

import androidx.compose.ui.semantics.Role

import com.ecency.core.app.AppIcon
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ecency.app.R
import com.ecency.core.app.AppScreen
import com.ecency.core.app.PerksCopy
import com.ecency.core.app.PerksModel
import com.ecency.core.app.QuestRow
import com.ecency.core.app.QuestTier
import com.ecency.core.app.SpendOptions
import com.ecency.core.app.SpinCopy
import com.ecency.core.app.SpinGameModel
import com.ecency.core.app.UiCopy

/** RN theme tokens the perks screens use that the shared palette does not carry. */
private val Green = Color(0xFF4FD688)
private val DarkBlue = Color(0xFF1A509A)

/** Calls [onForeground] each time the screen comes back (RN's refetch on focus). */
@Composable
private fun OnStart(onForeground: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        var first = true
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_START) {
                if (first) first = false else onForeground()
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}

/**
 * RN PerksScreen: the quests card (tiers, progress, streak and its freeze) and "Use your points". Rows are drawn only
 * while the card's account is the signed-in one.
 */
@Composable
fun PerksScreen(
    model: PerksModel, signedIn: String?, onBack: () -> Unit, onOpen: (AppScreen) -> Unit, onGetPoints: (String) -> Unit,
    nowMillis: () -> Long, pro: com.ecency.core.app.ProModel? = null,
) {
    val colors = LocalEcencyColors.current
    val s by model.state.collectAsState()
    LaunchedEffect(model) { model.start() }
    OnStart(model::onForeground)
    // RN: not enough Points for the freeze opens the Points top-up for this account
    LaunchedEffect(s.getPointsFor) {
        s.getPointsFor?.let {
            model.getPointsOpened()
            onGetPoints(it)
        }
    }
    val mine = s.account != null && s.account == signedIn
    Column(Modifier.fillMaxSize().background(colors.background).testTag("perks")) {
        TopBar(PerksCopy.HEADER, onBack = onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Card {
                Text(PerksCopy.QUESTS_TITLE, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = colors.darkText)
                if (mine && s.failed) {
                    // RN QueryErrorRetry, compact: wrong progress is worse than none
                    Row(Modifier.padding(top = 10.dp).testTag("perksError"), verticalAlignment = Alignment.CenterVertically) {
                        Glyph(AppIcon.ERROR, colors.darkGray, 18.dp)
                        Text(s.errorText, color = colors.darkGray, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp).weight(1f))
                    }
                    TextButton(onClick = model::retry, enabled = !s.retrying, modifier = Modifier.testTag("perksRetry")) {
                        Text(if (s.retrying) PerksCopy.RETRYING else PerksCopy.TRY_AGAIN, color = colors.blueText)
                    }
                } else {
                    Text(PerksCopy.QUESTS_SUBTITLE, fontSize = 13.sp, color = colors.darkGray, modifier = Modifier.padding(top = 2.dp))
                    if (mine && s.showStreak) StreakRow(s.streak, s.showFreezeOwned, s.freezesOwned, s.showFreezeBuy, s.freezeLabel, s.buyingFreeze, model::askFreeze)
                    TierTabs(s.tier, model::selectTier)
                    DailyResetLine(nowMillis, s::resetLine, "perksReset", Modifier.padding(top = 8.dp))
                    when {
                        !mine || (s.loading && s.quests == null) -> Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = colors.blueText, modifier = Modifier.size(24.dp).testTag("perksLoading"))
                        }
                        s.rows.isEmpty() -> Text(PerksCopy.QUESTS_EMPTY, fontSize = 13.sp, color = colors.darkGray, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp))
                        else -> {
                            s.rows.forEach { QuestRowView(it) }
                            // README "Esie": every quest of the tier done
                            if (s.rows.all { it.completed }) {
                                EsieView(com.ecency.core.esie.EsiePlacement.SUCCESS, Modifier.fillMaxWidth().padding(top = 12.dp).wrapContentWidth())
                            }
                        }
                    }
                }
            }
            // issue #3555: Ecency Pro by in-app purchase (ProScreens.kt)
            pro?.let { ProCard(it) }
            Card {
                Text(PerksCopy.SPEND_TITLE, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = colors.darkText)
                SpendOptions.ALL.forEachIndexed { i, option ->
                    Row(
                        Modifier.fillMaxWidth().clickable(role = Role.Button) { onOpen(option.screen) }.padding(vertical = 14.dp).testTag("spend:${option.id}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconBubble(option.icon, colors.blueText)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(option.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.darkText)
                            Text(option.description, fontSize = 12.sp, color = colors.darkGray)
                        }
                        Glyph(AppIcon.CHEVRON_RIGHT, colors.icon, 22.dp)
                    }
                    if (i < SpendOptions.ALL.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
                }
            }
        }
    }
    if (s.confirmingFreeze) {
        AlertDialog(
            onDismissRequest = model::cancelFreeze,
            title = { Text(PerksCopy.FREEZE_CONFIRM_TITLE) },
            text = { Text(PerksCopy.freezeConfirmBody()) },
            confirmButton = { TextButton(onClick = model::confirmFreeze, modifier = Modifier.testTag("freezeConfirm")) { Text(PerksCopy.CONFIRM) } },
            dismissButton = { TextButton(onClick = model::cancelFreeze, modifier = Modifier.testTag("freezeCancel")) { Text(PerksCopy.CANCEL) } },
        )
    }
    s.alert?.let { text ->
        AlertDialog(
            onDismissRequest = model::dismissAlert,
            text = { Text(text) },
            confirmButton = { TextButton(onClick = model::dismissAlert, modifier = Modifier.testTag("perksAlertOk")) { Text(UiCopy.OK) } },
        )
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    val colors = LocalEcencyColors.current
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp).clip(RoundedCornerShape(12.dp)).background(colors.lightBackground).padding(16.dp)) { content() }
}

@Composable
private fun IconBubble(icon: AppIcon, tint: Color, filled: Boolean = false) {
    val colors = LocalEcencyColors.current
    Box(Modifier.size(36.dp).clip(CircleShape).background(colors.grayBackground), contentAlignment = Alignment.Center) { Glyph(icon, tint, 18.dp, filled = filled) }
}

@Composable
private fun StreakRow(streak: Int, showOwned: Boolean, owned: Int, showBuy: Boolean, label: String, buying: Boolean, onBuy: () -> Unit) {
    val colors = LocalEcencyColors.current
    val lightBlue = if (colors.isDark) Color(0xFF2E3D51) else Color(0xFFEAF2FC)
    Row(
        Modifier.padding(top = 10.dp).clip(RoundedCornerShape(20.dp)).background(lightBlue).padding(horizontal = 12.dp, vertical = 6.dp).testTag("perksStreak"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(AppIcon.BOOST, colors.text, 16.dp)
        Text(PerksCopy.streak(streak), color = colors.text, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 6.dp))
    }
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (showOwned) {
            Row(Modifier.padding(end = 10.dp).testTag("perksFreezeOwned"), verticalAlignment = Alignment.CenterVertically) {
                Glyph(AppIcon.SNOWFLAKE, colors.blueText, 14.dp)
                Text(PerksCopy.freezeOwned(owned), fontSize = 12.sp, color = colors.darkGray, modifier = Modifier.padding(start = 4.dp))
            }
        }
        if (showBuy) {
            Text(
                label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colors.blueText,
                modifier = Modifier.alpha(if (buying) 0.6f else 1f).clip(RoundedCornerShape(20.dp)).border(1.dp, colors.blueText, RoundedCornerShape(20.dp))
                    .clickable(enabled = !buying, onClick = onBuy, role = Role.Button).padding(horizontal = 12.dp, vertical = 6.dp).testTag("perksFreezeBuy"),
            )
        }
    }
}

@Composable
private fun TierTabs(selected: QuestTier, onSelect: (QuestTier) -> Unit) {
    val colors = LocalEcencyColors.current
    Box(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp)) {
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(1.dp).background(colors.border))
        Row {
            QuestTier.entries.forEach { tier ->
                val active = tier == selected
                Column(Modifier.padding(end = 4.dp).clickable(role = Role.Tab) { onSelect(tier) }.semantics { this.selected = active }.testTag("tier:${tier.id}"), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        PerksCopy.tier(tier), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (active) colors.blueText else colors.darkGray,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                    Box(Modifier.height(2.dp).width(48.dp).background(if (active) colors.blue else Color.Transparent))
                }
            }
        }
    }
}

@Composable
private fun QuestRowView(row: QuestRow) {
    val colors = LocalEcencyColors.current
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp).testTag("quest:${row.tier.id}:${row.id}"), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.padding(end = 12.dp)) { IconBubble(row.icon, if (row.completed) Green else colors.blueText, filled = row.completed) }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(row.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.darkText, modifier = Modifier.weight(1f))
                Text(row.count, fontSize = 12.sp, color = colors.darkGray, modifier = Modifier.testTag("questCount:${row.id}"))
            }
            Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(colors.border)) {
                if (row.percent > 0) {
                    Box(Modifier.fillMaxWidth(row.percent / 100f).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(if (row.completed) Green else colors.blue))
                }
            }
        }
    }
}

/**
 * RN SpinGameView: spins left, the rings (turning for 8 s after a press), the ESTM tags while a spin is left, the
 * score bubble, SPIN & WIN, and with none left the 5 spins pack (an in-app purchase, [SpinPackRow]) and the time to
 * the next free spin.
 */
@Composable
fun SpinGameScreen(env: com.ecency.core.app.AppEnvironment, model: SpinGameModel, onBack: () -> Unit, pack: com.ecency.core.app.StoreScreenModel) {
    val colors = LocalEcencyColors.current
    val s by model.state.collectAsState()
    LaunchedEffect(model) { model.start() }
    // RN refreshes the countdown every 3 s
    val now = remember { mutableLongStateOf(env.nowMillis()) }
    LaunchedEffect(s.showProducts) {
        while (s.showProducts) {
            now.longValue = env.nowMillis()
            kotlinx.coroutines.delay(SpinCopy.TIMER_TICK_MILLIS)
        }
    }
    Column(Modifier.fillMaxSize().background(colors.background).testTag("spinGame")) {
        TopBar(SpinCopy.TITLE, onBack = onBack)
        // RN's 0.3 : 1 : 0.7 split, except that the bottom part (button, or pack and countdown, and Esie) never gets
        // less than its content: on a short phone the rings give up the room instead of the countdown being clipped
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val bottomShare = maxHeight * (0.7f / 2f)
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.weight(0.3f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (s.showCounters) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(com.ecency.core.l10n.LocaleFormat.integer(s.gameRight.toLong()), fontSize = 40.sp, fontWeight = FontWeight.Bold, color = colors.darkGray, modifier = Modifier.testTag("spinCount"))
                        Text(SpinCopy.SPIN_LEFT, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = colors.darkGray, modifier = Modifier.padding(top = 5.dp))
                    }
                }
            }
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(top = 10.dp).testTag("spinWheelPart"), contentAlignment = Alignment.Center) {
                if (s.showTags) {
                    Image(painterResource(R.drawable.spin_tags), null, contentScale = ContentScale.Fit, modifier = Modifier.width(400.dp).height(360.dp).testTag("spinTags"))
                }
                // the rings keep their size where they fit and shrink with the room where they do not (short phones,
                // large fonts): a size clamped by the part made them ovals running over the counters and the button
                val scale = minOf(1f, minOf(maxWidth, maxHeight) / SPIN_RINGS_SIZE)
                Box(Modifier.requiredSize(SPIN_RINGS_SIZE).graphicsLayer { scaleX = scale; scaleY = scale }, contentAlignment = Alignment.Center) {
                    SpinRings(s.spinning)
                }
                if (s.showScore) {
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(top = 40.dp).background(DarkBlue).widthIn(min = 75.dp).heightIn(min = 30.dp).padding(horizontal = 5.dp).testTag("spinScore"),
                        contentAlignment = Alignment.CenterStart,
                    ) { Text(SpinCopy.score(s.score), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White) }
                }
            }
            Column(Modifier.fillMaxWidth().heightIn(min = bottomShare).testTag("spinBottom"), horizontalAlignment = Alignment.CenterHorizontally) {
                // README "Esie": a won spin is a success moment; one hop per new score. Small (64 dp) so the 5 spins
                // pack and the countdown stay on short phones
                if (s.showScore) key(s.score) { EsieView(com.ecency.core.esie.EsiePlacement.SUCCESS, Modifier.padding(bottom = 4.dp), height = 64.dp) }
                if (s.showButton) {
                    Box(
                        Modifier.widthIn(min = 150.dp).heightIn(min = 50.dp).clip(RoundedCornerShape(25.dp)).background(colors.blue)
                            .clickable(onClick = model::spin, role = Role.Button).padding(horizontal = 20.dp).testTag("spinButton"),
                        contentAlignment = Alignment.Center,
                    ) { Text(SpinCopy.BUTTON, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
                } else if (s.showProducts) {
                    // RN ProductItemLine of the `499spins` purchase
                    Box(Modifier.testTag("spinProduct")) { SpinPackRow(env, pack) }
                    Text(
                        "${SpinCopy.TIMER_TEXT} ${SpinCopy.timeLeft(s.nextDate, now.longValue)}", fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                        color = colors.darkGray, modifier = Modifier.padding(top = 50.dp).testTag("spinTimer"),
                    )
                    s.resetNote?.let {
                        Text(it, fontSize = 13.sp, color = colors.darkGray, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp, start = 16.dp, end = 16.dp).testTag("spinReset"))
                    }
                }
            }
        }
        }
    }
    s.alert?.let { text ->
        AlertDialog(
            onDismissRequest = model::dismissAlert,
            text = { Text(text) },
            confirmButton = { TextButton(onClick = model::dismissAlert, modifier = Modifier.testTag("spinAlertOk")) { Text(UiCopy.OK) } },
        )
    }
}

/**
 * The daily reset line (00:00 UTC, issue #3548): [line] reads the clock, null hides it. It ticks while shown, each
 * time the minutes it reads change, so it rolls over at midnight.
 */
@Composable
internal fun DailyResetLine(nowMillis: () -> Long, line: (Long) -> String?, tag: String, modifier: Modifier = Modifier) {
    val now = remember { mutableLongStateOf(nowMillis()) }
    val text = line(now.longValue) ?: return
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(com.ecency.core.app.DailyReset.nextTickMillis(nowMillis()))
            now.longValue = nowMillis()
        }
    }
    Text(text, fontSize = 13.sp, color = LocalEcencyColors.current.darkGray, modifier = modifier.testTag(tag))
}

/** The outer ring's diameter. */
private val SPIN_RINGS_SIZE = 230.dp

/** RN BoostIndicatorAnimation: three rings around the logo, grey at rest, turning blue arcs while spinning. */
@Composable
private fun SpinRings(spinning: Boolean) {
    // the rings stand still under reduce motion or with animations off; the blue arcs still say it is spinning
    val turning = spinning && LocalMotion.current.level == com.ecency.core.app.MotionLevel.FULL
    val transition = rememberInfiniteTransition(label = "spin")
    val turns = listOf(2400, 2000, 1700).map { ms ->
        transition.animateFloat(0f, 360f, infiniteRepeatable(tween(ms, easing = LinearEasing), RepeatMode.Restart), label = "ring$ms")
    }
    val sweep by transition.animateFloat(60f, 270f, infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Reverse), label = "sweep")
    val rings = listOf(Triple(230.dp, Color(0xFF1A509A), 0f), Triple(180.dp, Color(0xFF357CE6), 20f), Triple(130.dp, Color(0xFF4DA1F1), 40f))
    Box(contentAlignment = Alignment.Center, modifier = Modifier.testTag(if (spinning) "spinRingsTurning" else "spinRings")) {
        rings.forEachIndexed { i, (size, color, start) ->
            Canvas(Modifier.size(size).rotate(if (turning) turns[i].value + start else start)) {
                val stroke = Stroke(12.dp.toPx(), cap = StrokeCap.Round)
                val inset = 6.dp.toPx()
                val arcSize = androidx.compose.ui.geometry.Size(this.size.width - 2 * inset, this.size.height - 2 * inset)
                val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
                if (spinning) drawArc(color, 0f, if (turning) sweep else 270f, false, topLeft, arcSize, style = stroke)
                else drawArc(Color(0xFFF2F2F2), 0f, 360f, false, topLeft, arcSize, style = Stroke(12.dp.toPx()))
            }
        }
        Image(painterResource(R.drawable.spin_logo), null, modifier = Modifier.size(80.dp))
    }
}

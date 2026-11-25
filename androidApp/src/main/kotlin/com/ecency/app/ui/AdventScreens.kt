package com.ecency.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ecency.core.api.games.AdventDoorKind
import com.ecency.core.app.AdventBanner
import com.ecency.core.app.AdventCopy
import com.ecency.core.app.AdventDoorLook
import com.ecency.core.app.AdventModel
import com.ecency.core.app.AdventPhase
import com.ecency.core.app.AdventSheet
import com.ecency.core.app.AdventSheetMode
import com.ecency.core.app.AdventSignIn
import com.ecency.core.app.AdventTile
import com.ecency.core.app.AdventWaveState
import com.ecency.core.app.AppEnvironment
import com.ecency.core.app.MotionLevel
import com.ecency.core.app.UiCopy
import com.ecency.core.l10n.L
import com.ecency.core.l10n.LocaleFormat

/** The calendar's festive palette (owner-approved design 11-25): night sky, presents, gold, holly. */
private object Festive {
    val night = Color(0xFF0D1A3A)
    val nightCard = Color(0xFF1B2F5E)
    val nightBorder = Color(0xFF2F4A85)
    val wire = Color(0xFF2C3D63)
    val gold = Color(0xFFFFD34E)
    val goldDark = Color(0xFFB8860B)
    val red = Color(0xFFD62F3A)
    val redDeep = Color(0xFFA8222B)
    val track = Color(0xFFE9EDF5)
    val redText = Color(0xFFB3202B)
    val holly = Color(0xFF1F7A4D)
    val hollyDark = Color(0xFF145537)
    val snow = Color(0xFFFBF7F0)
    val cream = Color(0xFFFFF4D6)
    val ink = Color(0xFF1B2230)
    val inkSoft = Color(0xFFDFE7F7)
    val answered = Color(0xFFE3EEFC)
    val answeredBorder = Color(0xFFA9C4EE)
    val missed = Color(0xFFECE8E1)
    val missedInk = Color(0xFF5F6673)
    val lights = listOf(Color(0xFFFF5A5F), Color(0xFFFFD34E), Color(0xFF5FD38A), Color(0xFF7CC7FF))

    /** Wrapping paper and ribbon of a still wrapped door, by day. */
    val papers = listOf(
        Color(0xFF1F7A4D) to gold, Color(0xFF2457B8) to Color.White, Color(0xFF7B3FA0) to gold,
        Color(0xFFB8325A) to Color.White, Color(0xFF0F6E6E) to Color(0xFFFF8FA3), Color(0xFFC1641C) to Color.White,
    )

    /** A handwritten face for the headings (the design's "Mountains of Christmas"; the system's cursive here). */
    val script = FontFamily.Cursive
}

/** The Ecency Advent Calendar (AdventModel): the night sky header, the 24 presents, the grand draw and the prizes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdventScreen(env: AppEnvironment, model: AdventModel, onBack: () -> Unit, onLogin: () -> Unit, onUrl: (String) -> Unit) {
    val colors = LocalEcencyColors.current
    val state by model.state.collectAsState()
    LaunchedEffect(model) { model.start() }
    // back from the background: a phone that slept through midnight UTC shows the new day's door
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle, model) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) model.resumed()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(state.askLogin) {
        if (state.askLogin) {
            model.consumeLogin()
            onLogin()
        }
    }
    val motion = LocalMotion.current
    val falling = state.snow && motion.level == MotionLevel.FULL
    Box(Modifier.fillMaxSize().background(Festive.snow).testTag("advent")) {
        PullToRefreshBox(isRefreshing = false, onRefresh = model::refresh, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Header(state.kicker, state.headline, state.countdown, state.answered, state.poolLeft, state.account != null, state.phase, state.snow, falling, onBack, model::toggleSnow)
                when {
                    state.signIn == AdventSignIn.FAILED -> Notice(L.native.advent.signRetry, model::retrySignIn, "adventSignRetry")
                    // also over a calendar already shown: a reload that failed (offline at midnight) leaves yesterday's doors
                    state.error != null -> Notice(L.native.advent.calendarFailed, model::refresh, "adventRetry")
                    state.account == null && state.phase == AdventPhase.SEASON -> Notice(L.native.advent.loginToOpen, onLogin, "adventLogin")
                }
                if (state.phase == AdventPhase.HIDDEN) {
                    Text(L.native.advent.ended, color = Festive.ink, fontSize = 16.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(24.dp).testTag("adventEnded"))
                } else {
                    Doors(state.tiles, onDoor = model::openDoor)
                    Legend()
                    if (state.phase == AdventPhase.TEASER) Steps()
                    state.grand?.let { GrandCard(it, state.account) }
                    if (state.prizes.isNotEmpty()) Prizes(state.prizes, state.pointsSoFar)
                }
                Spacer(Modifier.height(32.dp))
            }
        }
        if (state.loading && !state.loaded) Spinner(Modifier.align(Alignment.Center).testTag("adventLoading"))
    }
    state.sheet?.let { sheet ->
        ModalBottomSheet(onDismissRequest = model::closeSheet, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.background) {
            // "View your Wave" leaves the calendar: the sheet (its own window) closes first
            DoorSheet(env, sheet, state.account, model) { url -> model.closeSheet(); onUrl(url) }
        }
    }
}

@Composable
private fun Header(
    kicker: String, headline: String, countdown: String?, answered: Int, poolLeft: String?, signedIn: Boolean, phase: AdventPhase,
    snow: Boolean, falling: Boolean, onBack: () -> Unit, onSnow: () -> Unit,
) {
    val fall = if (falling) {
        val t = rememberInfiniteTransition(label = "snow")
        t.animateFloat(0f, 1f, infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Restart), label = "fall").value
    } else 0f
    Box(Modifier.fillMaxWidth().background(Festive.night)) {
        Canvas(Modifier.matchParentSize()) { sky(fall, snow) }
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 4.dp, end = 16.dp, bottom = 46.dp)) {
            Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onBack).semantics { contentDescription = UiCopy.BACK }.testTag("adventBack"),
                    contentAlignment = Alignment.Center,
                ) { Glyph(com.ecency.core.app.AppIcon.BACK, Color.White, 22.dp) }
                Text(AdventCopy.TITLE, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).semantics { heading() })
                Row(
                    Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(20.dp)).background(Festive.nightCard).border(1.dp, Festive.nightBorder, RoundedCornerShape(20.dp))
                        .clickable(role = Role.Switch, onClick = onSnow).padding(horizontal = 12.dp).testTag("adventSnow")
                        .semantics { contentDescription = if (snow) L.native.advent.snowOn else L.native.advent.snowOff },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Canvas(Modifier.size(14.dp)) { flake(Offset(size.width / 2, size.height / 2), size.width / 2, Color.White.copy(alpha = if (snow) 1f else 0.5f)) }
                    Text(L.native.advent.snow, color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp))
                }
            }
            Spacer(Modifier.height(34.dp))
            Text(kicker.uppercase(), color = Festive.gold, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.5.sp, modifier = Modifier.padding(start = 12.dp))
            Text(headline, color = Color.White, fontSize = 34.sp, lineHeight = 40.sp, fontFamily = Festive.script, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 12.dp).semantics { heading() }.testTag("adventHeadline"))
            countdown?.let {
                Text(it, color = Festive.gold, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 12.dp, top = 4.dp).testTag("adventCountdown"))
            }
            if (signedIn && phase == AdventPhase.SEASON) {
                Row(Modifier.padding(start = 12.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Stat(L.native.advent.doorsAnswered, "${LocaleFormat.integer(answered.toLong())} / ${LocaleFormat.integer(24)}", Color.White, Modifier.weight(1f).testTag("adventAnswered"))
                    Stat(L.native.advent.poolLeft, poolLeft ?: "", Festive.gold, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, tint: Color, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(Festive.nightCard).border(1.dp, Festive.nightBorder, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 10.dp).semantics(mergeDescendants = true) {}) {
        Text(value, color = tint, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
        Text(label, color = Festive.inkSoft, fontSize = 12.sp)
    }
}

/**
 * Stars, a moon, a string of lights, falling snow and a snowy hill with a tree on the right, clear of the headline
 * (decoration only). With [snow] off there are no flakes and no glow around the lights; [fall] 0 holds them still.
 */
private fun DrawScope.sky(fall: Float, snow: Boolean) {
    val w = size.width
    val h = size.height
    val u = w / 390f
    drawCircle(Color(0xFFFFF6D8), 20 * u, Offset(330 * u, 70 * u))
    drawCircle(Festive.night, 18 * u, Offset(340 * u, 63 * u))
    listOf(30 to 80, 90 to 110, 150 to 70, 250 to 100, 290 to 140, 60 to 160, 190 to 150, 370 to 160).forEach { (x, y) ->
        drawCircle(Color.White.copy(alpha = 0.85f), 1.5f * u, Offset(x * u, y * u))
    }
    val flakes = listOf(110 to 92, 220 to 64, 46 to 130, 275 to 175, 360 to 115, 160 to 40, 320 to 20)
    if (snow) flakes.forEachIndexed { i, (x, y) ->
        val dy = (y * u + fall * h + i * 37 * u) % h
        flake(Offset(x * u + (if (i % 2 == 0) 6 else -6) * u * fall, dy), 5.5f * u * (0.7f + (i % 3) * 0.15f), Color.White.copy(alpha = 0.85f))
    }
    val wire = Path().apply {
        moveTo(-5 * u, 52 * u)
        var x = -5f
        while (x < 395f) {
            quadraticTo((x + 49) * u, 72 * u, (x + 98) * u, 54 * u)
            x += 98f
        }
    }
    drawPath(wire, Festive.wire, style = Stroke(2 * u))
    for (i in 0 until 8) {
        val x = (24 + i * 49) * u
        val y = (if (i % 3 == 1) 69 else if (i % 3 == 2) 62 else 64) * u
        val c = Festive.lights[i % 4]
        if (snow) drawCircle(c.copy(alpha = 0.25f), 11 * u, Offset(x, y))
        drawOval(c, Offset(x - 5 * u, y - 7 * u), Size(10 * u, 14 * u))
    }
    val hill = Path().apply {
        moveTo(0f, h - 32 * u)
        quadraticTo(90 * u, h - 60 * u, 200 * u, h - 42 * u)
        quadraticTo(300 * u, h - 26 * u, w, h - 50 * u)
        lineTo(w, h)
        lineTo(0f, h)
        close()
    }
    tree(Offset(330 * u, h - 86 * u), u, Color(0xFF1F7A4D))
    drawPath(hill, Festive.snow)
}

private fun DrawScope.tree(at: Offset, u: Float, green: Color) {
    val p = Path().apply {
        moveTo(at.x, at.y - 30 * u)
        lineTo(at.x + 16 * u, at.y)
        lineTo(at.x + 8 * u, at.y)
        lineTo(at.x + 20 * u, at.y + 22 * u)
        lineTo(at.x - 20 * u, at.y + 22 * u)
        lineTo(at.x - 8 * u, at.y)
        lineTo(at.x - 16 * u, at.y)
        close()
    }
    drawPath(p, green)
    drawRect(Color(0xFF7A4A24), Offset(at.x - 3 * u, at.y + 22 * u), Size(6 * u, 8 * u))
    star(Offset(at.x, at.y - 34 * u), 6 * u, Festive.gold)
    drawCircle(Festive.lights[0], 2.2f * u, Offset(at.x - 6 * u, at.y + 4 * u))
    drawCircle(Festive.lights[3], 2.2f * u, Offset(at.x + 7 * u, at.y + 12 * u))
}

private fun DrawScope.flake(c: Offset, r: Float, color: Color) {
    for (k in 0 until 3) {
        val a = Math.toRadians(90.0 + k * 60.0)
        val dx = (kotlin.math.cos(a) * r).toFloat()
        val dy = (kotlin.math.sin(a) * r).toFloat()
        drawLine(color, Offset(c.x - dx, c.y - dy), Offset(c.x + dx, c.y + dy), strokeWidth = r / 4f)
    }
}

private fun DrawScope.star(c: Offset, r: Float, color: Color) {
    val p = Path()
    for (i in 0 until 10) {
        val radius = if (i % 2 == 0) r else r * 0.45f
        val a = Math.toRadians(-90.0 + i * 36.0)
        val x = c.x + (kotlin.math.cos(a) * radius).toFloat()
        val y = c.y + (kotlin.math.sin(a) * radius).toFloat()
        if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
    }
    p.close()
    drawPath(p, color)
}

@Composable
private fun Notice(text: String, onClick: () -> Unit, tag: String) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag(tag)) {
        Text(text, color = Festive.redText, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Doors(tiles: List<AdventTile>, onDoor: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().offset(y = (-20).dp).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        tiles.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                row.forEach { DoorTile(it, Modifier.weight(1f)) { onDoor(it.day) } }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun DoorTile(tile: AdventTile, modifier: Modifier, onClick: () -> Unit) {
    val (paper, ribbon) = Festive.papers[tile.day % Festive.papers.size]
    val wrapped = tile.look == AdventDoorLook.LOCKED || tile.look == AdventDoorLook.TODAY
    val bg = when (tile.look) {
        AdventDoorLook.TODAY -> Festive.red
        AdventDoorLook.WON -> Festive.gold
        AdventDoorLook.ANSWERED, AdventDoorLook.WAITING -> Festive.answered
        AdventDoorLook.MISSED, AdventDoorLook.CLOSED, AdventDoorLook.RESULTS -> Festive.missed
        AdventDoorLook.LOCKED -> paper
    }
    val ink = when (tile.look) {
        AdventDoorLook.LOCKED, AdventDoorLook.TODAY -> Color.White
        AdventDoorLook.WON -> Color(0xFF4A3200)
        AdventDoorLook.MISSED, AdventDoorLook.CLOSED, AdventDoorLook.RESULTS -> Festive.missedInk
        else -> Festive.ink
    }
    val band = if (tile.look == AdventDoorLook.TODAY) Festive.gold else ribbon
    val shape = RoundedCornerShape(10.dp)
    var box = modifier.height(68.dp)
    box = when (tile.look) {
        AdventDoorLook.TODAY -> box.shadow(10.dp, shape, ambientColor = Festive.gold, spotColor = Festive.gold).border(3.dp, Festive.gold, shape)
        AdventDoorLook.WON -> box.border(2.dp, Festive.goldDark, shape)
        AdventDoorLook.ANSWERED -> box.border(1.dp, Festive.answeredBorder, shape)
        AdventDoorLook.WAITING -> box.border(BorderStroke(1.dp, Color(0xFF7DA2DC)), shape)
        else -> box
    }
    Box(
        box.testTag("adventDoor:${tile.day}").clip(shape).background(bg)
            .then(if (tile.tappable) Modifier.clickable(role = Role.Button, onClickLabel = tile.tag.ifEmpty { null }, onClick = onClick) else Modifier)
            .clearAndSetSemantics { contentDescription = tile.label },
        contentAlignment = Alignment.Center,
    ) {
        if (wrapped) {
            Box(Modifier.fillMaxSize().padding(horizontal = 0.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.width(8.dp).fillMaxHeight().background(band.copy(alpha = 0.95f)))
            }
            Box(Modifier.fillMaxWidth().height(8.dp).background(band.copy(alpha = 0.95f)))
            Canvas(Modifier.align(Alignment.TopCenter).size(26.dp, 14.dp)) { bow(band) }
        }
        if (tile.look == AdventDoorLook.TODAY || tile.look == AdventDoorLook.WON) {
            Canvas(Modifier.align(Alignment.TopEnd).padding(4.dp).size(18.dp)) { star(Offset(size.width / 2, size.height / 2), size.width / 2, Festive.gold) }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // on a wrapped door the number sits on a soft dark chip, readable over a white ribbon
            Text(
                LocaleFormat.integer(tile.day.toLong()), color = ink, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold,
                modifier = if (wrapped) Modifier.clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.28f)).padding(horizontal = 6.dp) else Modifier,
            )
            if (tile.tag.isNotEmpty()) {
                val todayTag = tile.look == AdventDoorLook.TODAY
                Text(
                    tile.tag, color = if (todayTag) Festive.redText else ink, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                    modifier = if (todayTag) Modifier.clip(RoundedCornerShape(8.dp)).background(Color.White).padding(horizontal = 6.dp, vertical = 1.dp) else Modifier,
                )
            }
        }
    }
}

private fun DrawScope.bow(color: Color) {
    val w = size.width
    val h = size.height
    val p = Path().apply {
        moveTo(w / 2, h * 0.64f)
        cubicTo(w * 0.23f, 0f, 0f, h * 0.29f, w * 0.15f, h * 0.64f)
        cubicTo(w * 0.23f, h * 0.86f, w * 0.42f, h * 0.79f, w / 2, h * 0.64f)
        cubicTo(w * 0.58f, h * 0.79f, w * 0.77f, h * 0.86f, w * 0.85f, h * 0.64f)
        cubicTo(w, h * 0.29f, w * 0.77f, 0f, w / 2, h * 0.64f)
        close()
    }
    drawPath(p, color)
    drawCircle(color, h * 0.19f, Offset(w / 2, h * 0.64f))
}

@Composable
private fun Legend() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        listOf(Festive.red to L.native.advent.legendToday, Festive.holly to L.native.advent.legendWrapped, Festive.gold to L.native.advent.legendWon, Festive.answered to L.native.advent.legendAnswered).forEach { (c, t) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(c))
                Text(t, color = Color(0xFF4A5568), fontSize = 11.sp, modifier = Modifier.padding(start = 5.dp))
            }
        }
    }
}

@Composable
private fun Steps() {
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(L.native.advent.lede, color = Festive.ink, fontSize = 14.sp, lineHeight = 20.sp)
        listOf(L.native.advent.stepOpen to L.native.advent.stepOpenText, L.native.advent.stepAnswer to L.native.advent.stepAnswerText, L.native.advent.stepWin to L.native.advent.stepWinText).forEach { (t, d) ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White).padding(12.dp)) {
                Text(t, color = Festive.redText, fontFamily = Festive.script, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                Text(d, color = Festive.ink, fontSize = 13.sp, lineHeight = 18.sp)
            }
        }
    }
}

@Composable
private fun GrandCard(grand: com.ecency.core.app.AdventGrandCard, account: String?) {
    Box(Modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(16.dp)).background(Festive.red).testTag("adventGrand")) {
        Box(Modifier.align(Alignment.CenterEnd).padding(end = 18.dp).width(10.dp).fillMaxHeight().background(Festive.gold))
        Column(Modifier.fillMaxWidth().padding(start = 14.dp, top = 14.dp, bottom = 14.dp, end = 44.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(L.native.advent.grandTitle, color = Color.White, fontFamily = Festive.script, fontWeight = FontWeight.Bold, fontSize = 26.sp, modifier = Modifier.weight(1f))
                Text(grand.total, color = Color(0xFF8F1D25), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Color.White).padding(horizontal = 9.dp, vertical = 4.dp))
            }
            if (grand.drawn) {
                // web GrandCard after the draw: the winners, a withdrawn prize struck through, and the seed block
                grand.winners.forEach { w -> WinnerRow(w, "adventGrandWinner:${w.place}", Color.White, Color.White) }
                grand.seed?.let { Text(it, color = Color.White, fontSize = 12.sp, modifier = Modifier.testTag("adventGrandSeed")) }
            } else {
                Text(grand.text, color = Color.White, fontSize = 13.sp, lineHeight = 18.sp)
                if (account != null) {
                    Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(Festive.redDeep)) {
                        Box(Modifier.fillMaxWidth(grand.fraction.toFloat().coerceIn(0f, 1f)).height(10.dp).background(Festive.gold))
                    }
                    Text(grand.progress, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/** A winner as listed (web `adv-winner`): the place, the account, the prize; a withdrawn prize struck through. */
@Composable
private fun WinnerRow(w: com.ecency.core.app.AdventWinnerRow, tag: String, ink: Color, badge: Color) {
    val strike = if (w.cancelled) androidx.compose.ui.text.style.TextDecoration.LineThrough else null
    // a withdrawn prize is struck through and says so, at full contrast
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(26.dp).clip(CircleShape).background(if (w.place == 1) Festive.gold else badge), contentAlignment = Alignment.Center) {
            Text(w.placeText, color = Festive.ink, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
        }
        Text(
            w.name, color = ink, fontSize = 14.sp, textDecoration = strike,
            fontWeight = if (w.you) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.padding(start = 10.dp).weight(1f),
        )
        Text(w.prize, color = ink, fontSize = 14.sp)
    }
}

@Composable
private fun Prizes(rows: List<com.ecency.core.app.AdventPrizeRow>, pointsSoFar: String?) {
    Column(Modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(16.dp)).background(Color.White).padding(14.dp).testTag("adventPrizes"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(L.native.advent.yourPrizes, color = Festive.redText, fontFamily = Festive.script, fontWeight = FontWeight.Bold, fontSize = 24.sp, modifier = Modifier.weight(1f).semantics { heading() })
            pointsSoFar?.let { Text(it, color = Festive.ink, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
        }
        rows.forEach { r ->
            Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(r.text, color = Festive.ink, fontSize = 13.sp)
                    Text(r.amount, color = Festive.ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Text(r.status, color = if (r.paid) Festive.hollyDark else Color(0xFF7A5300), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ---- the door sheet ----

@Composable
private fun DoorSheet(env: AppEnvironment, sheet: AdventSheet, account: String?, model: AdventModel, onUrl: (String) -> Unit) {
    val colors = LocalEcencyColors.current
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp).testTag("adventSheet"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        val door = sheet.door
        Row(verticalAlignment = Alignment.CenterVertically) {
            val drawn = sheet.mode == AdventSheetMode.RESULT
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(if (drawn) Festive.gold else Festive.red), contentAlignment = Alignment.Center) {
                if (drawn) {
                    Canvas(Modifier.size(30.dp)) { star(Offset(size.width / 2, size.height / 2), size.width / 2, Color.White) }
                } else {
                    Box(Modifier.width(8.dp).fillMaxHeight().background(Festive.gold))
                    Box(Modifier.fillMaxWidth().height(8.dp).background(Festive.gold))
                }
            }
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(
                    AdventCopy.doorTitle(sheet.day, drawn),
                    color = Festive.redText, fontFamily = Festive.script, fontWeight = FontWeight.Bold, fontSize = 30.sp, modifier = Modifier.semantics { heading() }.testTag("adventSheetTitle"),
                )
                door?.let { Text(AdventCopy.kindLine(it), color = colors.darkGray, fontSize = 13.sp) }
            }
        }
        when (sheet.mode) {
            AdventSheetMode.LOADING -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) { Spinner() }
            AdventSheetMode.FAILED -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(L.native.advent.loadFailed, color = colors.text)
                TextButton(onClick = model::retrySheet, modifier = Modifier.testTag("adventSheetRetry")) { Text(L.native.advent.tryAgain, color = colors.blueText) }
            }
            AdventSheetMode.ANSWER -> AnswerForm(sheet, model)
            AdventSheetMode.DONE -> Done(sheet, account, model, onUrl)
            AdventSheetMode.RESULT -> Result(sheet, account, model)
        }
    }
}

@Composable
private fun AnswerForm(sheet: AdventSheet, model: AdventModel) {
    val colors = LocalEcencyColors.current
    val door = sheet.door ?: return
    var text by rememberSaveable(sheet.day) { mutableStateOf("") }
    var choice by rememberSaveable(sheet.day) { mutableIntStateOf(-1) }
    door.prompt?.let { Text(it, color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 23.sp, modifier = Modifier.testTag("adventPrompt")) }
    if (sheet.late) Text(L.native.advent.lateNote, color = Festive.redText, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    if (door.kind == AdventDoorKind.OPEN) {
        OutlinedTextField(
            value = text, onValueChange = { text = AdventCopy.limit(it, AdventModel.MAX_ANSWER) },
            label = { Text(L.native.advent.yourAnswer) }, placeholder = { Text(L.native.advent.writeHint) },
            minLines = 5, modifier = Modifier.fillMaxWidth().testTag("adventAnswer"),
        )
        val words = text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
        Row(Modifier.fillMaxWidth()) {
            Text(L.native.advent.words(n = words), color = colors.darkGray, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(AdventCopy.MAX_CHARS, color = colors.darkGray, fontSize = 12.sp)
        }
    } else {
        Text(L.native.advent.labelPick, color = colors.darkGray, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        door.choices.forEachIndexed { i, c ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).selectable(selected = choice == i, role = Role.RadioButton) { choice = i }.testTag("adventChoice:$i"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = choice == i, onClick = null)
                Text(c, color = colors.text, fontSize = 15.sp, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.lightBackground).padding(12.dp)) {
        Text(if (door.kind == AdventDoorKind.OPEN) L.native.advent.waveNoteOpen else L.native.advent.waveNotePick(day = LocaleFormat.integer(sheet.day.toLong())), color = colors.text, fontSize = 13.sp, lineHeight = 18.sp)
    }
    sheet.error?.let { Text(it.text, color = Festive.redText, fontSize = 13.sp) }
    val ready = if (door.kind == AdventDoorKind.OPEN) text.isNotBlank() else choice >= 0
    Button(
        onClick = { model.submit(if (door.kind == AdventDoorKind.OPEN) text else null, choice) }, enabled = ready && !sheet.submitting,
        colors = ButtonDefaults.buttonColors(containerColor = Festive.holly, contentColor = Color.White),
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("adventSubmit"),
    ) {
        if (sheet.submitting) Spinner(Modifier.size(20.dp)) else Text(L.native.advent.submitWave, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
    }
}

@Composable
private fun Done(sheet: AdventSheet, account: String?, model: AdventModel, onUrl: (String) -> Unit) {
    val colors = LocalEcencyColors.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Festive.cream).padding(12.dp).testTag("adventDone")) {
        Text(L.native.advent.answeredTitle, color = Color(0xFF8F1D25), fontFamily = Festive.script, fontWeight = FontWeight.Bold, fontSize = 26.sp)
        Text(if (sheet.entry?.late == true) L.native.advent.answeredLate else L.native.advent.answeredText, color = Color(0xFF5C3F00), fontSize = 14.sp)
    }
    sheet.entry?.answer?.let { Text(it, color = colors.text, fontSize = 15.sp, modifier = Modifier.testTag("adventYourAnswer")) }
    when (sheet.wave) {
        AdventWaveState.POSTING -> Row(verticalAlignment = Alignment.CenterVertically) {
            Spinner(Modifier.size(20.dp))
            Text(L.native.advent.postingWave, color = colors.darkGray, modifier = Modifier.padding(start = 8.dp))
        }
        AdventWaveState.FAILED -> TextButton(onClick = model::retryWave, modifier = Modifier.testTag("adventPostWave")) {
            Text(if (sheet.wavePermlink != null) L.native.advent.linkWave else L.native.advent.postWave, color = colors.blueText, fontWeight = FontWeight.Bold)
        }
        else -> Unit
    }
    val permlink = sheet.wavePermlink
    if (permlink != null && account != null && sheet.wave == AdventWaveState.POSTED) {
        TextButton(onClick = { onUrl(AdventModel.waveUrl(account, permlink)) }, modifier = Modifier.testTag("adventViewWave")) {
            Text(L.native.advent.viewWave, color = colors.blueText, fontWeight = FontWeight.Bold)
        }
    }
    TextButton(onClick = model::closeSheet, modifier = Modifier.fillMaxWidth()) { Text(L.native.advent.back, color = colors.blueText) }
}

@Composable
private fun Result(sheet: AdventSheet, account: String?, model: AdventModel) {
    val colors = LocalEcencyColors.current
    val door = sheet.door
    val result = sheet.result
    if (sheet.notDrawn || result == null) {
        Text(L.native.advent.notDrawn, color = colors.text, fontSize = 15.sp, modifier = Modifier.testTag("adventNotDrawn"))
    } else {
        val mine = result.winners.firstOrNull { it.account == account && !it.cancelled }
        if (mine != null && door != null) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Festive.cream).border(1.dp, Color(0xFFF0CF6A), RoundedCornerShape(14.dp)).padding(12.dp).testTag("adventYouWon")) {
                Text(L.native.advent.youWon, color = Color(0xFF8F1D25), fontFamily = Festive.script, fontWeight = FontWeight.Bold, fontSize = 24.sp)
                Text(AdventCopy.wonLine(mine.place, door.reward.kind, door.reward.amounts.getOrElse(mine.place - 1) { 0.0 }), color = Color(0xFF5C3F00), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Text(L.native.advent.winners, color = colors.darkGray, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        if (result.winners.isEmpty()) Text(L.native.advent.noWinners, color = colors.text)
        if (door != null) AdventCopy.winners(result.winners, door.reward.kind, door.reward.amounts, account).forEach { w ->
            WinnerRow(w, "adventWinner:${w.place}", colors.text, Color(0xFFDFE5F0))
        }
        Text(L.native.advent.entries(n = result.entries), color = colors.darkGray, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        val total = result.tallies.sum().coerceAtLeast(1)
        door?.choices?.forEachIndexed { i, c ->
            val share = (result.tallies.getOrElse(i) { 0 }.toDouble() / total)
            Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
                Row(Modifier.fillMaxWidth()) {
                    Text(AdventCopy.choiceLine(c, result.answerIndex == i), color = colors.text, fontSize = 13.sp, fontWeight = if (result.answerIndex == i) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f))
                    Text(LocaleFormat.percent(kotlin.math.round(share * 100), 0, 0), color = colors.text, fontSize = 13.sp)
                }
                Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Festive.track)) {
                    Box(Modifier.fillMaxWidth(share.toFloat()).height(8.dp).background(if (result.answerIndex == i) Festive.holly else Color(0xFF8AA6D6)))
                }
            }
        }
        result.seedBlock?.let { block ->
            Text(L.native.advent.seedBlock(block = block.toString()), color = colors.darkGray, fontSize = 12.sp, modifier = Modifier.testTag("adventSeed"))
        }
    }
    if (account != null && door != null) {
        TextButton(onClick = model::answerFromResult, modifier = Modifier.testTag("adventAnswerFromResult")) {
            Text(if (sheet.answered) L.native.advent.yourAnswerOpen else L.native.advent.answerLate, color = colors.blueText, fontWeight = FontWeight.Bold)
        }
    }
    TextButton(onClick = model::closeSheet, modifier = Modifier.fillMaxWidth()) { Text(L.native.advent.back, color = colors.blueText) }
}

// ---- the feed banner ----

/** The feed's Advent banner (web advent-banner.tsx): by date, closable for the rest of the UTC day. */
@Composable
fun AdventFeedBanner(env: AppEnvironment, now: Long, onOpen: () -> Unit) {
    var version by remember { mutableIntStateOf(0) }
    LaunchedEffect(env) {
        env.events.collect { if (it is com.ecency.core.app.AppEvent.AdventBannerChanged) version++ }
    }
    val banner: AdventBanner = remember(now, version) { env.advent.banner(now) } ?: return
    Box(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 8.dp).clip(RoundedCornerShape(16.dp)).background(Festive.night).testTag("adventBanner")) {
        Canvas(Modifier.matchParentSize()) { bannerSky() }
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, top = 24.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 6.dp).size(50.dp).clip(RoundedCornerShape(10.dp)).background(Festive.red), contentAlignment = Alignment.Center) {
                    Box(Modifier.width(8.dp).fillMaxHeight().background(Festive.gold))
                    Box(Modifier.fillMaxWidth().height(8.dp).background(Festive.gold))
                }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(banner.title, color = Festive.gold, fontFamily = Festive.script, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 28.sp, modifier = Modifier.semantics { heading() })
                    Text(banner.text, color = Festive.inkSoft, fontSize = 13.sp, lineHeight = 18.sp)
                }
                Box(
                    Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button) { env.advent.closeBanner() }
                        .semantics { contentDescription = L.native.advent.bannerClose }.testTag("adventBannerClose"),
                    contentAlignment = Alignment.Center,
                ) { Glyph(com.ecency.core.app.AppIcon.CLOSE, Festive.inkSoft, 18.dp) }
            }
            Button(
                onClick = onOpen, colors = ButtonDefaults.buttonColors(containerColor = Festive.gold, contentColor = Festive.ink),
                modifier = Modifier.padding(top = 12.dp).heightIn(min = 44.dp).testTag("adventBannerOpen"),
            ) { Text(banner.action, color = Festive.ink, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
        }
    }
}

private fun DrawScope.bannerSky() {
    val u = size.width / 366f
    val wire = Path().apply {
        moveTo(-5 * u, 4 * u)
        var x = -5f
        while (x < 372f) {
            quadraticTo((x + 46) * u, 22 * u, (x + 92) * u, 6 * u)
            x += 92f
        }
    }
    drawPath(wire, Festive.wire, style = Stroke(2 * u))
    for (i in 0 until 8) {
        val x = (22 + i * 46) * u
        val y = (if (i % 3 == 1) 15 else if (i % 3 == 2) 9 else 12) * u
        val c = Festive.lights[i % 4]
        drawCircle(c.copy(alpha = 0.25f), 10 * u, Offset(x, y))
        drawOval(c, Offset(x - 4.5f * u, y - 6 * u), Size(9 * u, 12 * u))
    }
    flake(Offset(300 * u, 70 * u), 6 * u, Color.White.copy(alpha = 0.8f))
    flake(Offset(330 * u, 120 * u), 4 * u, Color.White.copy(alpha = 0.8f))
}

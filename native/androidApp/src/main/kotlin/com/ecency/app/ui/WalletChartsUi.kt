package com.ecency.app.ui

import androidx.compose.ui.semantics.selected

import androidx.compose.ui.semantics.semantics

import androidx.compose.ui.semantics.Role

import androidx.compose.ui.text.style.TextDirection
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ecency.core.app.AnalyticsTab
import com.ecency.core.app.AppEnvironment
import com.ecency.core.app.BalanceAnalyticsModel
import com.ecency.core.app.ChartPoint
import com.ecency.core.app.UiCopy

/** RN `$chartBlue`. */
private val ChartBlue = Color(0xFF357CE6)

/** RN `$primaryGreen`. */
private val Green = Color(0xFF4FD688)

/** RN balanceAnalyticsSheet: Balance History (a pannable line chart) and the yearly / monthly / daily summary. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BalanceAnalyticsSheet(env: AppEnvironment, username: String, coinType: String, onDismiss: () -> Unit) {
    val colors = LocalEcencyColors.current
    val model = remember(username, coinType) { env.balanceAnalyticsModel(username, coinType) }
    DisposableEffect(model) { onDispose { model.close() } }
    LaunchedEffect(model) { model.start() }
    val s by model.state.collectAsState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.background) {
        Column(Modifier.fillMaxWidth().heightIn(min = 350.dp).navigationBarsPadding().padding(bottom = 32.dp).testTag("balanceAnalytics")) {
            Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp).clip(RoundedCornerShape(20.dp)).background(colors.lightBackground)) {
                Tab(UiCopy.BALANCE_HISTORY, s.tab == AnalyticsTab.HISTORY, "analyticsHistoryTab", Modifier.weight(1f)) { model.selectTab(AnalyticsTab.HISTORY) }
                Tab(s.summaryTitle, s.tab == AnalyticsTab.SUMMARY, "analyticsSummaryTab", Modifier.weight(1f)) { model.selectTab(AnalyticsTab.SUMMARY) }
            }
            if (s.tab == AnalyticsTab.HISTORY) {
                when {
                    s.historyLoading -> Centered { CircularProgressIndicator(color = colors.blueText) }
                    s.historyError != null -> Centered { Text(s.historyError!!, color = colors.darkText, fontSize = 14.sp) }
                    s.points.isEmpty() -> Centered { Text(UiCopy.NO_ACTIVITY, color = colors.darkText, fontSize = 14.sp, modifier = Modifier.testTag("analyticsEmpty")) }
                    else -> Box(Modifier.padding(horizontal = 16.dp)) {
                        BalanceChart(s.points, onNearStart = model::loadOlder)
                        if (s.loadingOlder) CircularProgressIndicator(Modifier.padding(start = 88.dp, top = 8.dp).width(18.dp).height(18.dp), color = colors.blueText, strokeWidth = 2.dp)
                    }
                }
            } else {
                Row(Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BalanceAnalyticsModel.GRANULARITIES.forEach { g ->
                        val on = g == s.granularity
                        Text(
                            UiCopy.granularity(g), color = if (on) Color.White else colors.darkText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(if (on) colors.blue else colors.lightBackground)
                                .clickable(role = Role.Button) { model.selectGranularity(g) }.padding(horizontal = 12.dp, vertical = 6.dp).testTag("granularity:$g"),
                        )
                    }
                }
                val sum = s.summary
                when {
                    s.summaryLoading -> Centered { CircularProgressIndicator(color = colors.blueText) }
                    s.summaryError != null -> Centered { Text(s.summaryError!!, color = colors.darkText, fontSize = 14.sp) }
                    sum == null -> Centered { Text(UiCopy.NO_ACTIVITY, color = colors.darkText, fontSize = 14.sp) }
                    else -> Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SummaryCard(UiCopy.CURRENT_BALANCE, sum.current, colors.text, Modifier.weight(1f), "summaryCurrent")
                            SummaryCard(
                                s.changeLabel, sum.change ?: UiCopy.NO_CHANGE,
                                when (sum.positive) { true -> Green; false -> colors.redText; null -> colors.text }, Modifier.weight(1f), "summaryChange",
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SummaryCard(s.minLabel, sum.min, colors.text, Modifier.weight(1f), "summaryMin")
                            SummaryCard(s.maxLabel, sum.max, colors.text, Modifier.weight(1f), "summaryMax")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Tab(label: String, selected: Boolean, tag: String, modifier: Modifier, onClick: () -> Unit) {
    val colors = LocalEcencyColors.current
    Box(
        modifier.clip(RoundedCornerShape(20.dp)).background(if (selected) colors.blue else Color.Transparent).clickable(onClick = onClick, role = Role.Tab)
            .semantics { this.selected = selected }.padding(vertical = 10.dp).testTag(tag),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (selected) Color.White else colors.darkText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun SummaryCard(label: String, value: String, color: Color, modifier: Modifier, tag: String) {
    val colors = LocalEcencyColors.current
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(colors.lightBackground).padding(12.dp)) {
        Text(label, color = colors.darkText, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
        Text(value, color = color, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag(tag))
    }
}

/**
 * RN SimpleChart in the analytics sheet: 12 dp per point, at least the sheet's width, opened at the newest (right)
 * end; the y labels stay pinned on the left (80 dp) while the line pans; within 200 dp of the left edge the next
 * older page is asked for, and the view keeps its place when it arrives.
 */
@Composable
fun BalanceChart(points: List<ChartPoint>, onNearStart: () -> Unit, height: Dp = 220.dp) {
    val colors = LocalEcencyColors.current
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = colors.darkText, fontSize = 10.sp, textDirection = TextDirection.Content)
    val scroll = rememberScrollState()
    // time runs left to right in every language (oldest on the left, the y axis pinned on the left, opened at the
    // newest end), so the chart keeps a left to right layout inside a right to left UI
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
    BoxWithConstraints(Modifier.fillMaxWidth().height(height).testTag("balanceChart")) {
        val viewport = maxWidth
        val width = maxOf(viewport, (points.size * 12).dp)
        val widthPx = with(density) { width.roundToPx() }
        val lastWidth = remember { mutableIntStateOf(0) }
        val positioned = remember { androidx.compose.runtime.mutableStateOf(false) }
        // first layout: the newest end; a page of older points: keep what was on screen where it was
        LaunchedEffect(widthPx) {
            val before = lastWidth.intValue
            lastWidth.intValue = widthPx
            if (before == 0) scroll.scrollTo(scroll.maxValue) else if (widthPx > before) scroll.scrollTo(scroll.value + (widthPx - before))
            positioned.value = true
        }
        // only a pan by the user reaches the left edge: not the first frame, nor a chart narrower than the sheet
        LaunchedEffect(scroll) {
            snapshotFlow { positioned.value to scroll.value }.collect { (ready, x) ->
                if (ready && scroll.maxValue > 0 && x <= with(density) { 200.dp.toPx() }) onNearStart()
            }
        }
        val min = points.minOf { it.value }
        val max = points.maxOf { it.value }
        val span = (max - min).takeIf { it > 0 } ?: 1.0
        val labelBand = 18.dp
        Box(Modifier.horizontalScroll(scroll)) {
            Canvas(Modifier.width(width).height(height)) {
                val chartH = size.height - labelBand.toPx()
                val left = 80.dp.toPx()
                val usable = size.width - left
                val stepX = if (points.size > 1) usable / (points.size - 1) else 0f
                fun x(i: Int) = left + i * stepX
                fun y(v: Double) = (chartH - ((v - min) / span * (chartH - 8)) - 4).toFloat()
                val line = Path()
                points.forEachIndexed { i, p -> if (i == 0) line.moveTo(x(i), y(p.value)) else line.lineTo(x(i), y(p.value)) }
                val fill = Path().apply {
                    addPath(line)
                    lineTo(x(points.lastIndex), chartH)
                    lineTo(x(0), chartH)
                    close()
                }
                drawPath(fill, Brush.verticalGradient(listOf(ChartBlue.copy(alpha = 0.8f), colors.background.copy(alpha = 0f)), endY = chartH))
                drawPath(line, ChartBlue, style = Stroke(width = 2.dp.toPx()))
                points.forEachIndexed { i, p ->
                    if (p.label.isNotEmpty()) {
                        val m = measurer.measure(p.label, labelStyle)
                        drawText(m, topLeft = Offset((x(i) - m.size.width / 2f).coerceAtLeast(left), chartH + 2.dp.toPx()))
                    }
                }
            }
        }
        // the pinned y axis (RN's ghost chart): four segments, RN's compact labels
        Canvas(Modifier.width(80.dp).height(height).background(colors.lightBackground).testTag("chartYAxis")) {
            val chartH = size.height - labelBand.toPx()
            for (k in 0..4) {
                val v = min + span * k / 4
                val m = measurer.measure(BalanceAnalyticsModel.compact(v), labelStyle)
                val yy = (chartH - (k / 4f) * (chartH - 8) - 4) - m.size.height / 2f
                drawText(m, topLeft = Offset(size.width - m.size.width - 6.dp.toPx(), yy.coerceIn(0f, chartH - m.size.height)))
            }
        }
    }
    }
}

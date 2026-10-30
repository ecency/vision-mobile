package com.ecency.app.ui

import androidx.compose.foundation.layout.heightIn

import androidx.compose.ui.semantics.Role

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ecency.core.app.AppEnvironment
import com.ecency.core.app.PostCard
import com.ecency.core.app.ReferCopy
import com.ecency.core.app.ReferModel
import com.ecency.core.app.ReferralRow
import com.ecency.core.app.UiCopy

/**
 * RN referScreen ("Refer & Earn"): earned and pending points, the Refer button (the signup link through the
 * share sheet), and the referred accounts with their reward mark, date and "Delegate HP". Rows are drawn only
 * while the list's account is the signed-in one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReferScreen(
    env: AppEnvironment, host: AppHost, model: ReferModel, list: LazyListState, signedIn: String?,
    onBack: () -> Unit, onUser: (String) -> Unit, onDelegate: (String) -> Unit,
) {
    val colors = LocalEcencyColors.current
    val state by model.state.collectAsState()
    LaunchedEffect(model) { model.start() }
    val mine = state.account != null && state.account == signedIn
    val rows = if (mine) state.rows else emptyList()
    // near the end: the next page (keyed on pages, so a page of nothing new does not stall)
    LaunchedEffect(list, state.pages, rows.size) {
        androidx.compose.runtime.snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { last -> if (rows.isNotEmpty() && last >= rows.size - 3) model.loadMore() }
    }
    Column(Modifier.fillMaxSize().background(colors.background).testTag("refer")) {
        TopBar(ReferCopy.TITLE, onBack = onBack)
        PullToRefreshBox(isRefreshing = state.isRefreshing, onRefresh = model::refresh, modifier = Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize(), state = list) {
                item(key = "points") {
                    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            PointsBox(if (mine) state.earnedPoints else 0, ReferCopy.EARNED, "referEarned")
                            PointsBox(if (mine) state.pendingPoints else 0, ReferCopy.PENDING, "referPending")
                        }
                        Button(
                            onClick = { if (mine) state.shareUrl?.let(host::share) }, enabled = mine,
                            colors = ButtonDefaults.buttonColors(contentColor = androidx.compose.ui.graphics.Color.White, containerColor = colors.blue),
                            modifier = Modifier.padding(top = 20.dp).heightIn(min = 50.dp).testTag("referShare"),
                        ) {
                            // RN: the label, then the share icon 20 to its end; Material's own padding on both sides
                            Text(ReferCopy.REFER, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            // sized in sp: grows with the label at large font scales, as on iOS
                            val iconSize = with(androidx.compose.ui.platform.LocalDensity.current) { 22.sp.toDp() }
                            Glyph(com.ecency.core.app.AppIcon.SHARE, Color.White, iconSize, Modifier.padding(start = 12.dp).testTag("referShareIcon"))
                        }
                    }
                }
                itemsIndexed(rows, key = { _, r -> r.id }) { _, row ->
                    ReferralRowView(env, row, onUser = { onUser(row.username) }, onHint = { env.ui.toast(row.rewardHint) }, onDelegate = { onDelegate(row.username) })
                }
                item(key = "footer") {
                    Box(Modifier.fillMaxWidth().height(72.dp), contentAlignment = Alignment.Center) {
                        when {
                            state.isLoading || state.isLoadingMore -> CircularProgressIndicator(color = colors.blueText, modifier = Modifier.testTag("referLoading"))
                            state.error != null -> TextButton(onClick = { if (state.loaded) model.loadMore() else model.retry() }, modifier = Modifier.testTag("referRetry")) {
                                Text(UiCopy.errorTryAgain(state.error?.message.orEmpty()), color = colors.blueText, textAlign = TextAlign.Center)
                            }
                            mine && state.isEmpty -> Text(ReferCopy.EMPTY, color = colors.darkGray, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp).testTag("referEmpty"))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PointsBox(points: Int, label: String, tag: String) {
    val colors = LocalEcencyColors.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(com.ecency.core.l10n.LocaleFormat.integer(points.toLong()), color = colors.blueText, fontSize = 32.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag(tag))
        Text(label, color = colors.darkGray, fontSize = 14.sp)
    }
}

@Composable
private fun ReferralRowView(env: AppEnvironment, row: ReferralRow, onUser: () -> Unit, onHint: () -> Unit, onDelegate: () -> Unit) {
    val colors = LocalEcencyColors.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onUser, role = Role.Button).padding(horizontal = 16.dp, vertical = 10.dp).testTag("referral:${row.username}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // RN: "$$" in blue once the referral was rewarded; a tap says which
        Text(
            "$$", color = if (row.isRewarded) colors.blueText else colors.darkGray, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onHint, role = Role.Button).padding(6.dp).testTag("referralMark:${row.username}"),
        )
        Avatar(PostCard.avatarUrl(row.username, env.proxyBase), 36.dp, Modifier.padding(horizontal = 10.dp))
        Column(Modifier.weight(1f)) {
            Text(row.username, color = colors.text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            row.createdMillis?.let { Text(com.ecency.core.l10n.LocaleFormat.dateShort(it), color = colors.darkGray, fontSize = 12.sp) }
        }
        Text(
            ReferCopy.DELEGATE_HP, color = colors.blueText, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onDelegate, role = Role.Button).padding(8.dp).testTag("referralDelegate:${row.username}"),
        )
    }
}

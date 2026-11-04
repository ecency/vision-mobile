package com.ecency.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ecency.core.app.AppIcon

/**
 * Debug only (`--ez iconGallery true`): every [AppIcon], outlined and filled, in the app's colours, to check the
 * icon set by eye (weight, alignment, clipping, RTL mirroring, large text). Not reachable in release builds.
 */
@Composable
fun IconGallery() {
    val colors = LocalEcencyColors.current
    Column(Modifier.fillMaxSize().background(colors.background).statusBarsPadding().testTag("iconGallery")) {
        Text("Icons (${AppIcon.entries.size})", color = colors.text, fontSize = 18.sp, modifier = Modifier.padding(12.dp))
        LazyVerticalGrid(GridCells.Adaptive(88.dp), Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp)) {
            items(AppIcon.entries) { icon ->
                Column(Modifier.padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Glyph(icon, colors.text, 24.dp)
                        Glyph(icon, colors.blueText, 24.dp, filled = true)
                    }
                    Text(
                        icon.name.lowercase(), color = colors.darkGray, fontSize = 10.sp, maxLines = 2, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

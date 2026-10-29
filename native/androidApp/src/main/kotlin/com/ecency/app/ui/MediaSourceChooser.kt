package com.ecency.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ecency.app.MediaPicker
import com.ecency.core.app.MediaPickCopy
import com.ecency.core.app.MediaSource
import com.ecency.core.app.MediaSourceMenus
import com.ecency.core.app.MediaSourceOption

// Where an image for an upload comes from, one look for every surface (README "Media picking"), as on iOS: the
// shared option sets (MediaSourceMenus) as rows with an icon tile, the surface's title and a short line under it, in
// a bottom sheet sized to its rows; the editor's uploads sheet shows the same options as tiles.

/** From this font scale on, the icon gives its room to the words (as iOS at the accessibility text sizes). */
private const val STACKED_FONT_SCALE = 1.5f

/** "Choose from Library" or "Choose from Files" for one [picker] (RN #3561: some surfaces had no files route). */
@Composable
fun MediaSourceChooser(picker: MediaPicker, tag: String, title: String? = null, onDismiss: () -> Unit) {
    MediaSourceSheet(title, MediaSourceMenus.attachment(), tagFor = { if (it == MediaSource.FILES) "$tag:files" else "$tag:library" }, onDismiss = onDismiss) {
        if (it == MediaSource.FILES) picker.pickFromFiles() else picker.pickFromLibrary()
    }
}

/** The chooser sheet: an optional [title], the [options] and Cancel. A pick closes the sheet, then runs [onPick]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaSourceSheet(
    title: String?,
    options: List<MediaSourceOption>,
    tagFor: (MediaSource) -> String,
    onDismiss: () -> Unit,
    onPick: (MediaSource) -> Unit,
) {
    val colors = LocalEcencyColors.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.background) {
        MediaSourceSheetContent(title, options, tagFor, onDismiss, onPick)
    }
}

/** The sheet's content, on its own for tests. */
@Composable
fun MediaSourceSheetContent(
    title: String?,
    options: List<MediaSourceOption>,
    tagFor: (MediaSource) -> String,
    onDismiss: () -> Unit,
    onPick: (MediaSource) -> Unit,
) {
    val colors = LocalEcencyColors.current
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 12.dp)
            .testTag("mediaSourceSheet"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!title.isNullOrEmpty()) {
            Text(
                title, color = colors.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().semantics { heading() },
            )
        }
        MediaSourceList(options, tagFor) { onDismiss(); onPick(it) }
        Box(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).background(colors.lightBackground)
                .clickable(role = Role.Button, onClick = onDismiss).testTag("mediaSourceCancel"),
            contentAlignment = Alignment.Center,
        ) { Text(MediaPickCopy.CANCEL, color = colors.blueText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
    }
}

/** The options as one grouped card of rows: the icon tile, the title and what it does, read as one. */
@Composable
fun MediaSourceList(options: List<MediaSourceOption>, tagFor: (MediaSource) -> String, onPick: (MediaSource) -> Unit) {
    val colors = LocalEcencyColors.current
    val stacked = LocalDensity.current.fontScale >= STACKED_FONT_SCALE
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.lightBackground)) {
        options.forEachIndexed { i, option ->
            if (i > 0) HorizontalDivider(Modifier.padding(start = 14.dp), color = colors.border)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button) { onPick(option.source) }
                    .semantics(mergeDescendants = true) {}.padding(horizontal = 14.dp, vertical = 10.dp).testTag(tagFor(option.source)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!stacked) {
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(tileFill()), contentAlignment = Alignment.Center) {
                        Glyph(option.source.icon, colors.blueText, 22.dp)
                    }
                    Spacer(Modifier.width(14.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(option.title, color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(option.source.subtitle, color = colors.darkGray, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}

/**
 * The options inline (the editor's uploads sheet): tiles that scroll sideways, the rows at large font scales. A tile
 * shows its title; TalkBack reads the title and what it does, and names the action by the title.
 */
@Composable
fun MediaSourceTiles(options: List<MediaSourceOption>, tagFor: (MediaSource) -> String, onPick: (MediaSource) -> Unit) {
    val colors = LocalEcencyColors.current
    if (LocalDensity.current.fontScale >= STACKED_FONT_SCALE) {
        MediaSourceList(options, tagFor, onPick)
        return
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        options.forEach { option ->
            Column(
                Modifier.testTag(tagFor(option.source)).width(88.dp).clip(RoundedCornerShape(14.dp)).background(colors.lightBackground)
                    .clickable(role = Role.Button) { onPick(option.source) }
                    // one element: the title, then what it does; the action is named by the title (a verb phrase)
                    .clearAndSetSemantics {
                        contentDescription = "${option.title}, ${option.source.subtitle}"
                        role = Role.Button
                        onClick(label = option.title) { onPick(option.source); true }
                    }
                    .padding(vertical = 10.dp, horizontal = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(tileFill()), contentAlignment = Alignment.Center) {
                    Glyph(option.source.icon, colors.blueText, 22.dp)
                }
                Spacer(Modifier.height(6.dp))
                Text(option.title, color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 2)
            }
        }
    }
}

@Composable
private fun tileFill() = LocalEcencyColors.current.let { it.blue.copy(alpha = if (it.isDark) 0.22f else 0.12f) }

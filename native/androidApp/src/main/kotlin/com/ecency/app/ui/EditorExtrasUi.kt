package com.ecency.app.ui

import androidx.compose.ui.semantics.LiveRegionMode

import androidx.compose.ui.semantics.liveRegion

import androidx.compose.ui.semantics.heading

import androidx.compose.ui.semantics.selected

import androidx.compose.ui.semantics.semantics

import androidx.compose.ui.semantics.Role

import com.ecency.core.app.AppIcon
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import coil3.compose.AsyncImage
import com.ecency.core.app.AiAssistModel
import com.ecency.core.app.AiImageModel
import com.ecency.core.app.ComposeTranslateModel
import com.ecency.core.app.DictationModel
import com.ecency.core.app.VideoUploadModel
import com.ecency.core.app.MediaPickCopy
import com.ecency.core.app.MediaSurface
import com.ecency.core.app.DictationPhase
import com.ecency.core.app.PaidCopy
import com.ecency.core.app.Languages
import com.ecency.core.app.EditorUiCopy
import com.ecency.core.app.UiCopy

/** A language picker as RN's DropdownButton: the current name, a menu of the others. */
@Composable
private fun LanguagePicker(tag: String, current: String, options: List<String>, modifier: Modifier = Modifier, onPick: (String) -> Unit) {
    val colors = LocalEcencyColors.current
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier.clip(RoundedCornerShape(8.dp)).background(colors.lightBackground).clickable(role = Role.Button) { open = true }
                .padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(Languages.name(current), color = colors.text, fontSize = 15.sp, modifier = Modifier.testTag(tag))
            Glyph(AppIcon.DROPDOWN, colors.text, 20.dp)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { code ->
                DropdownMenuItem(text = { Text(Languages.name(code)) }, onClick = { open = false; onPick(code) }, modifier = Modifier.testTag("$tag:$code"))
            }
        }
    }
}

/** RN composeTranslateModal: translate the draft and add the translation to its end. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ComposeTranslateSheet(model: ComposeTranslateModel, onDismiss: () -> Unit) {
    val colors = LocalEcencyColors.current
    val state by model.state.collectAsState()
    LaunchedEffect(model) { model.start() }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.background) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding().testTag("translateSheet")) {
            Text(ComposeTranslateModel.TITLE, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = colors.text, modifier = Modifier.semantics { heading() }.padding(bottom = 8.dp))
            when {
                state.loading -> LoadingBlock(Modifier.padding(vertical = 24.dp))
                state.tooShort -> Text(ComposeTranslateModel.TOO_SHORT, color = colors.darkGray, modifier = Modifier.padding(vertical = 16.dp).testTag("translateTooShort"))
                else -> {
                    Text(ComposeTranslateModel.REVIEW_NOTE, color = colors.darkGray, fontSize = 13.sp)
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                        Text(EditorUiCopy.SOURCE_LANGUAGE, color = colors.darkGray, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Text(EditorUiCopy.TARGET_LANGUAGE, color = colors.darkGray, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        LanguagePicker("translateSource", state.source, state.sources, Modifier.weight(1f), model::setSource)
                        Glyph(AppIcon.ARROW_FORWARD, colors.darkGray, 18.dp, Modifier.padding(horizontal = 8.dp))
                        LanguagePicker("translateTarget", state.target, state.targets, Modifier.weight(1f), model::setTarget)
                    }
                    state.detectedText?.let { Text(it, color = colors.darkGray, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp).testTag("translateDetected")) }
                    if (state.hasTitle) {
                        Row(Modifier.clickable(role = Role.Button) { model.setAddTitleMarker(!state.addTitleMarker) }.testTag("translateTitleMarker"), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = state.addTitleMarker, onCheckedChange = null, modifier = Modifier.padding(end = 8.dp, top = 8.dp, bottom = 8.dp))
                            Text(ComposeTranslateModel.ADD_TITLE_MARKER, color = colors.text, fontSize = 14.sp)
                        }
                    }
                    if (state.translating) {
                        Row(Modifier.padding(vertical = 8.dp).testTag("translateProgress"), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(20.dp), color = colors.blueText, strokeWidth = 2.dp)
                            state.progressText?.let { Text(it, color = colors.darkGray, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp)) }
                        }
                    }
                    if (state.failed) Text(ComposeTranslateModel.ERROR, color = colors.redText, fontSize = 13.sp, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.padding(vertical = 8.dp).testTag("translateError"))
                    if (state.translated.isNotEmpty() && !state.translating) {
                        CompositionLocalProvider(LocalLayoutDirection provides if (state.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                            Box(Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(max = 220.dp).clip(RoundedCornerShape(8.dp)).background(colors.lightBackground).verticalScroll(rememberScrollState()).padding(12.dp)) {
                                Text(state.translated, color = colors.text, fontSize = 14.sp, modifier = Modifier.fillMaxWidth().testTag("translatePreview"))
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                        Button(onClick = model::translate, enabled = state.canTranslate, colors = ButtonDefaults.buttonColors(contentColor = androidx.compose.ui.graphics.Color.White, containerColor = colors.blue), modifier = Modifier.weight(1f).padding(end = 6.dp).testTag("translateRun")) { Text(UiCopy.TRANSLATE) }
                        Button(onClick = { if (model.apply()) onDismiss() }, enabled = state.canApply, colors = ButtonDefaults.buttonColors(contentColor = androidx.compose.ui.graphics.Color.White, containerColor = colors.blue), modifier = Modifier.weight(1f).padding(start = 6.dp).testTag("translateApply")) { Text(EditorUiCopy.APPLY) }
                    }
                }
            }
        }
    }
}

/** The line and the two buttons a paid request without an answer gets: "Check again" is the main button, this is the way out. */
@Composable
internal fun UnresolvedNotice(notice: String?, unresolved: Boolean, running: Boolean, tag: String, confirmText: String = PaidCopy.GIVE_UP_CONFIRM, onGiveUp: () -> Unit) {
    val colors = LocalEcencyColors.current
    var confirm by remember { mutableStateOf(false) }
    notice?.let { Text(it, color = colors.darkGray, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp).testTag("${tag}Notice")) }
    if (unresolved && !running) {
        TextButton(onClick = { confirm = true }, modifier = Modifier.testTag("${tag}GiveUp")) { Text(PaidCopy.GIVE_UP, color = colors.redText) }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false }, title = { Text(PaidCopy.GIVE_UP) }, text = { Text(confirmText, Modifier.testTag("${tag}GiveUpText")) },
            confirmButton = { TextButton(onClick = { confirm = false; onGiveUp() }, modifier = Modifier.testTag("${tag}GiveUpYes")) { Text(PaidCopy.GIVE_UP, color = colors.redText) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(UiCopy.CANCEL) } },
        )
    }
}

@Composable
internal fun FailAlert(text: String?, tag: String, onDismiss: () -> Unit) {
    if (text == null) return
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(UiCopy.FAIL) }, text = { Text(text, Modifier.testTag(tag)) },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("${tag}Ok")) { Text(UiCopy.OK) } },
    )
}

/**
 * RN aiAssistModal. The button charges (no confirmation step, as RN) and says what it costs. The editor's AI hub: with
 * [onImage] and [onDictate] it also lists the image generator and dictation, each opening its own sheet unchanged.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AiAssistSheet(model: AiAssistModel, onImage: (() -> Unit)? = null, onDictate: (() -> Unit)? = null, onDismiss: () -> Unit) {
    val colors = LocalEcencyColors.current
    val state by model.state.collectAsState()
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(model) { model.start() }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.background) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding().testTag("aiAssistSheet")) {
            Text(AiAssistModel.TITLE, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = colors.text, modifier = Modifier.semantics { heading() }.padding(bottom = 8.dp))
            val result = state.result
            if (result != null) {
                Text(EditorUiCopy.RESULT, color = colors.darkGray, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                when {
                    result.isTitles -> result.titles.forEachIndexed { i, title ->
                        val selected = i == state.selectedTitle
                        Text(
                            title, color = if (selected) Color.White else colors.text,
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(8.dp)).background(if (selected) colors.blue else colors.lightBackground)
                                .clickable(role = Role.Button) { model.selectTitle(i) }.padding(12.dp).testTag("aiTitle$i"),
                        )
                    }
                    result.isTags -> Text(result.tags.joinToString("  ") { "#$it" }, color = colors.blueText, modifier = Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(8.dp)).background(colors.lightBackground).padding(12.dp).testTag("aiTags"))
                    else -> Box(Modifier.fillMaxWidth().padding(top = 6.dp).heightIn(max = 260.dp).clip(RoundedCornerShape(8.dp)).background(colors.lightBackground).verticalScroll(rememberScrollState()).padding(12.dp)) {
                        Text(result.output, color = colors.text, fontSize = 14.sp, modifier = Modifier.testTag("aiResult"))
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp).horizontalScroll(rememberScrollState())) {
                    if (result.canApply) {
                        Button(onClick = { if (model.apply()) onDismiss() }, colors = ButtonDefaults.buttonColors(contentColor = androidx.compose.ui.graphics.Color.White, containerColor = colors.blue), modifier = Modifier.padding(end = 8.dp).testTag("aiApply")) { Text(EditorUiCopy.APPLY_TO_POST) }
                    }
                    TextButton(onClick = { model.copyValue?.let { clipboard.setText(AnnotatedString(it)) } }, modifier = Modifier.testTag("aiCopy")) { Text(EditorUiCopy.COPY, color = colors.blueText) }
                    TextButton(onClick = model::tryAgain, modifier = Modifier.testTag("aiTryAgain")) { Text(UiCopy.TRY_AGAIN, color = colors.blueText) }
                    TextButton(onClick = model::tryAnother, modifier = Modifier.testTag("aiTryAnother")) { Text(EditorUiCopy.TRY_ANOTHER, color = colors.blueText) }
                }
            } else {
                Row(Modifier.fillMaxWidth()) {
                    Text(com.ecency.core.app.UiCopy.balance(state.balanceLabel), color = colors.text, fontSize = 14.sp, modifier = Modifier.weight(1f).testTag("aiBalance"))
                    state.costText?.let { Text(EditorUiCopy.cost(it), color = colors.text, fontSize = 14.sp, modifier = Modifier.testTag("aiCost")) }
                }
                Text(AiAssistModel.HINT, color = colors.darkGray, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
                if (onImage != null || onDictate != null) {
                    // the other AI tools, a group of their own above the actions (RN's generate image card, outside the
                    // price gate: each prices itself on its own sheet). Leaving would close a run that has no answer yet
                    val free = !state.running && !state.unresolved
                    Text(EditorUiCopy.AI_TOOLS, color = colors.darkGray, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    onImage?.let { AiToolCard(EditorUiCopy.AI_GENERATE_IMAGE, EditorUiCopy.AI_GENERATE_IMAGE_DESC, "aiToolImage", free, it) }
                    onDictate?.let { AiToolCard(EditorUiCopy.DICTATE, EditorUiCopy.DICTATE_DESC, "aiToolDictate", free, it) }
                    Spacer(Modifier.height(12.dp))
                }
                Text(EditorUiCopy.SELECT_AN_ACTION, color = colors.darkGray, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                if (state.loadingPrices) LoadingBlock(Modifier.padding(vertical = 16.dp))
                else state.actions.forEach { action ->
                    val selected = state.action == action
                    Row(
                        Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(8.dp)).background(if (selected) colors.blue else colors.lightBackground)
                            .clickable(enabled = !state.unresolved && !state.running, role = Role.Button) { model.selectAction(action) }.padding(12.dp).testTag("aiAction:$action"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(AiAssistModel.actionName(action), color = if (selected) Color.White else colors.text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text(AiAssistModel.actionDescription(action), color = if (selected) Color.White else colors.darkGray, fontSize = 12.sp)
                        }
                        Text(AiAssistModel.priceLine(state.prices.firstOrNull { it.action == action }), color = if (selected) Color.White else colors.darkGray, fontSize = 12.sp, modifier = Modifier.testTag("aiPrice:$action"))
                    }
                }
                if (state.action != null) {
                    Text(EditorUiCopy.YOUR_TEXT, color = colors.darkGray, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
                    OutlinedTextField(
                        state.text, model::setText, enabled = !state.unresolved && !state.running, placeholder = { Text(EditorUiCopy.AI_TEXT_PLACEHOLDER) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 220.dp).testTag("aiText"),
                    )
                    Text(state.countText, color = if (state.text.length < state.minInput) colors.redText else colors.darkGray, fontSize = 12.sp, modifier = Modifier.testTag("aiCount"))
                }
                state.insufficientText?.let { Text(it, color = colors.redText, fontSize = 13.sp, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.padding(top = 4.dp).testTag("aiInsufficient")) }
                UnresolvedNotice(state.notice, state.unresolved, state.running, "ai", onGiveUp = model::giveUp)
                Button(onClick = model::submit, enabled = state.canSubmit, colors = ButtonDefaults.buttonColors(contentColor = androidx.compose.ui.graphics.Color.White, containerColor = colors.blue), modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).testTag("aiSubmit")) {
                    if (state.running) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp) else Text(state.submitLabel)
                }
            }
        }
    }
    FailAlert(state.alert, "aiAlert", model::clearAlert)
}

/** An AI hub row that opens another AI tool: the chevron says it leaves the sheet, unlike the actions above it. */
@Composable
private fun AiToolCard(title: String, description: String, tag: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = LocalEcencyColors.current
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(8.dp)).background(colors.lightBackground)
            .clickable(enabled = enabled, onClick = onClick, role = Role.Button).padding(12.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) colors.text else colors.darkGray, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(description, color = colors.darkGray, fontSize = 12.sp)
        }
        Glyph(AppIcon.CHEVRON_RIGHT, colors.darkGray, 20.dp)
    }
}

@Composable
private fun Pill(text: String, active: Boolean, enabled: Boolean, tag: String, onClick: () -> Unit) {
    val colors = LocalEcencyColors.current
    Text(
        text, color = if (active) Color.White else colors.text, fontSize = 14.sp,
        modifier = Modifier.padding(end = 8.dp, top = 6.dp).clip(RoundedCornerShape(50)).background(if (active) colors.blue else colors.lightBackground)
            .clickable(enabled = enabled, onClick = onClick, role = Role.Button)
            .semantics { selected = active }.padding(horizontal = 14.dp, vertical = 8.dp).testTag(tag),
    )
}

/** RN aiImageGeneratorScreen, as a sheet over the editor that opened it. The button charges and says what it costs. */
@Composable
internal fun AiImageBody(model: AiImageModel, showTitle: Boolean = true, onDismiss: () -> Unit) {
    val colors = LocalEcencyColors.current
    val context = LocalContext.current
    val state by model.state.collectAsState()
    LaunchedEffect(model) { model.start() }
    run {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding().testTag("aiImageSheet")) {
            // the screen opened on its own has the title in its top bar: one title, not two
            if (showTitle) Text(AiImageModel.TITLE, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = colors.text, modifier = Modifier.semantics { heading() }.padding(bottom = 8.dp).testTag("aiImageTitle"))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(colors.lightBackground).padding(12.dp)) {
                Text(com.ecency.core.app.UiCopy.balance(state.balanceLabel), color = colors.text, fontSize = 14.sp, modifier = Modifier.weight(1f).testTag("aiImageBalance"))
                Text(EditorUiCopy.cost(state.costLabel), color = colors.text, fontSize = 14.sp, modifier = Modifier.testTag("aiImageCost"))
            }
            val url = state.resultUrl
            if (url != null) {
                AsyncImage(
                    model = url, contentDescription = EditorUiCopy.AI_GENERATED_IMAGE, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp).aspectRatio(state.ratioNumber.toFloat()).clip(RoundedCornerShape(8.dp)).background(colors.lightBackground).testTag("aiImageResult"),
                )
                Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    if (state.canInsert) Button(onClick = { if (model.insert()) onDismiss() }, colors = ButtonDefaults.buttonColors(contentColor = androidx.compose.ui.graphics.Color.White, containerColor = colors.blue), modifier = Modifier.weight(1f).padding(end = 6.dp).testTag("aiImageInsert")) { Text(EditorUiCopy.INSERT_INTO_POST) }
                    Button(
                        onClick = {
                            runCatching {
                                context.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, url), null))
                            }
                        },
                        colors = ButtonDefaults.buttonColors(contentColor = androidx.compose.ui.graphics.Color.White, containerColor = colors.blue), modifier = Modifier.weight(1f).padding(start = 6.dp).testTag("aiImageShare"),
                    ) { Text(UiCopy.SHARE) }
                }
                TextButton(onClick = model::generateAgain, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).testTag("aiImageAgain")) { Text(EditorUiCopy.GENERATE_AGAIN, color = colors.blueText) }
            } else {
                val editable = !state.running && !state.unresolved
                Text(AiImageModel.PROMPT_LABEL, color = colors.darkGray, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
                OutlinedTextField(
                    state.prompt, model::setPrompt, enabled = editable, placeholder = { Text(AiImageModel.PROMPT_PLACEHOLDER) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 200.dp).testTag("aiImagePrompt"),
                )
                Text(state.remainingText, color = colors.darkGray, fontSize = 12.sp)
                state.suggestionText?.let {
                    Text(it, color = colors.blueText, fontSize = 13.sp, maxLines = 2, modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(50)).border(1.dp, colors.blueText, RoundedCornerShape(50)).clickable(onClick = model::useSuggestion, role = Role.Button).padding(horizontal = 12.dp, vertical = 6.dp).testTag("aiImageSuggestion"))
                }
                Text(EditorUiCopy.SELECT_ASPECT_RATIO, color = colors.darkGray, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    AiImageModel.RATIOS.forEach { ratio -> Pill(ratio, ratio == state.aspectRatio, editable, "aiRatio:$ratio") { model.setAspectRatio(ratio) } }
                }
                if (state.powerTiers.isNotEmpty()) {
                    Text(EditorUiCopy.QUALITY_BOOST, color = colors.darkGray, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        state.powerTiers.forEach { power -> Pill("${power}x", power == state.power, editable, "aiPower:$power") { model.setPower(power) } }
                    }
                }
                UnresolvedNotice(state.notice, state.unresolved, state.running, "aiImage", onGiveUp = model::giveUp)
                if (state.running) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 16.dp).testTag("aiImageGenerating"), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(22.dp), color = colors.blueText, strokeWidth = 2.dp)
                        Text(AiImageModel.GENERATING, color = colors.darkGray, fontSize = 14.sp, modifier = Modifier.padding(start = 12.dp))
                    }
                } else {
                    Button(onClick = model::generate, enabled = state.canGenerate, colors = ButtonDefaults.buttonColors(contentColor = androidx.compose.ui.graphics.Color.White, containerColor = colors.blue), modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).testTag("aiImageGenerate")) { Text(state.generateLabel) }
                }
            }
        }
    }
    FailAlert(state.alert, "aiImageAlert", model::clearAlert)
}

/** The generator as a sheet over the editor that opened it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AiImageSheet(model: AiImageModel, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = LocalEcencyColors.current.background) {
        AiImageBody(model, onDismiss = onDismiss)
    }
}

/**
 * RN dictationModal: record, stop, and the transcript goes into the post; the sheet stays open for more. Stop is
 * the tap that spends points, and says how many. The microphone is asked for on the first Record, and a
 * recording goes on in the background and ends when this sheet goes away.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DictationSheet(model: DictationModel, onDismiss: () -> Unit) {
    val colors = LocalEcencyColors.current
    val state by model.state.collectAsState()
    LaunchedEffect(model) { model.start() }
    val permission = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
        model.permissionAnswered(granted)
    }
    LaunchedEffect(state.askPermission) {
        if (state.askPermission) runCatching { permission.launch(android.Manifest.permission.RECORD_AUDIO) }.onFailure { model.permissionAnswered(false) }
    }
    // a recording goes on in the background (the microphone foreground service, README "Background media"); it ends
    // with the sheet or its composer, on logout, an account change and the wipe, and is held when the lock engages
    val busy = state.busy
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.background) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding().testTag("dictationSheet"), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(DictationModel.TITLE, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = colors.text, modifier = Modifier.semantics { heading() }.padding(bottom = 8.dp))
            if (state.unavailable) Text(DictationModel.UNAVAILABLE, color = colors.redText, fontSize = 13.sp, modifier = Modifier.testTag("dictationUnavailable"))
            if (state.denied) Text(DictationModel.DENIED, color = colors.redText, fontSize = 13.sp, modifier = Modifier.testTag("dictationDenied"))
            if (state.recorderFailed) Text(DictationModel.RECORDER_ERROR, color = colors.redText, fontSize = 13.sp, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("dictationRecorderError"))
            if (state.priceFailed) {
                Text(DictationModel.PRICE_ERROR, color = colors.redText, fontSize = 13.sp, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("dictationPriceError"))
                TextButton(onClick = model::reloadPrice, modifier = Modifier.testTag("dictationPriceRetry")) { Text(UiCopy.TRY_AGAIN, color = colors.blueText) }
            }
            Text(state.clock, fontSize = 40.sp, fontWeight = FontWeight.Bold, color = colors.text, modifier = Modifier.padding(vertical = 8.dp).testTag("dictationClock"))
            if (state.phase == DictationPhase.RECORDING) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("dictationRecording")) {
                    Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(colors.red))
                    Text(EditorUiCopy.RECORDING, color = colors.redText, fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp))
                }
            }
            Text(state.costLine, color = colors.text, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp).testTag("dictationCost"))
            // The balance, the free units and the hint arrive after the sheet opened (price and points are read
            // then). Their room is there from the first frame, so the sheet opens at its final height instead of
            // growing under the user's thumb: an unseen copy of the three lines holds it, at any font scale and width.
            Box(Modifier.fillMaxWidth().testTag("dictationLines"), contentAlignment = Alignment.Center) {
                Column(Modifier.alpha(0f).clearAndSetSemantics {}, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(DICTATION_ROOM_BALANCE, fontSize = 13.sp)
                    Text(DICTATION_ROOM_FREE, fontSize = 13.sp)
                    Text(DICTATION_ROOM_HINT, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    state.balanceLine?.let { Text(it, color = colors.darkGray, fontSize = 13.sp, modifier = Modifier.testTag("dictationBalance")) }
                    state.freeLine?.let { Text(it, color = colors.darkGray, fontSize = 13.sp, modifier = Modifier.testTag("dictationFree")) }
                    state.hintLine?.let { Text(it, color = colors.darkGray, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp).testTag("dictationHint")) }
                }
            }
            UnresolvedNotice(state.notice, state.retry != null, busy, "dictation", confirmText = state.giveUpConfirm, onGiveUp = model::giveUp)
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                Button(onClick = model::main, enabled = state.mainEnabled, colors = ButtonDefaults.buttonColors(contentColor = androidx.compose.ui.graphics.Color.White, containerColor = if (state.phase == DictationPhase.RECORDING) colors.red else colors.blue), modifier = Modifier.weight(1f).padding(end = 6.dp).testTag("dictationMain")) {
                    Text(state.mainLabel)
                }
                Button(onClick = onDismiss, enabled = !busy, colors = ButtonDefaults.buttonColors(contentColor = androidx.compose.ui.graphics.Color.White, containerColor = colors.blue), modifier = Modifier.weight(1f).padding(start = 6.dp).testTag("dictationDone")) { Text(UiCopy.DONE) }
            }
            if (state.phase == DictationPhase.TRANSCRIBING) {
                Row(Modifier.padding(bottom = 12.dp).testTag("dictationTranscribing"), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = colors.blueText, strokeWidth = 2.dp)
                    Text(EditorUiCopy.TRANSCRIBING, color = colors.darkGray, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
    FailAlert(state.alert, "dictationAlert", model::clearAlert)
}

// What the dictation sheet's three late lines look like at their usual length; never shown, only measured.
/** Room the balance line keeps (the longest balance it shows). */
// the longest texts the three lines show, in the app language (their figures are placeholders of the widest shape)
private val DICTATION_ROOM_BALANCE: String get() = com.ecency.core.l10n.L.native.dictation.balance(balance = com.ecency.core.l10n.LocaleFormat.posix("000000000.000"), unit = com.ecency.core.app.PaidCopy.POINTS)
private val DICTATION_ROOM_FREE: String get() = com.ecency.core.l10n.L.dictation.freeRemaining(n = com.ecency.core.l10n.LocaleFormat.integer(0), s = com.ecency.core.l10n.LocaleFormat.posix("00", grouping = false))
private val DICTATION_ROOM_HINT: String get() = com.ecency.core.l10n.L.native.plurals.dictation.stopHint(m = 5)

/** "Record Video" for the screens that upload videos; null in previews and where the host has no camera launcher. */
val LocalVideoCaptures = androidx.compose.runtime.staticCompositionLocalOf<com.ecency.app.VideoCaptures?> { null }

/** RN's looping preview in the uploader, as a seam: tests record what would play instead of decoding a video. */
fun interface VideoPreviewPlayer {
    @Composable
    fun Play(ref: String, playing: Boolean, modifier: Modifier)
}

val LocalVideoPreview = androidx.compose.runtime.staticCompositionLocalOf<VideoPreviewPlayer> { ExoVideoPreview }

/**
 * RN `<Video repeat resizeMode="contain" paused={isUploading} volume={0}>`: one Media3 player over the picked
 * file, looping, without sound, fitted into its frame. It exists only while composed (the sheet, with a video,
 * while the app is not locked: nothing is composed under the lock) and is released on dispose; it pauses when
 * [playing] goes false (an upload runs, the app is not in front).
 */
private object ExoVideoPreview : VideoPreviewPlayer {
    @androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
    @Composable
    override fun Play(ref: String, playing: Boolean, modifier: Modifier) {
        val context = LocalContext.current
        val player = remember(ref) {
            androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply {
                setMediaItem(androidx.media3.common.MediaItem.fromUri(ref))
                repeatMode = androidx.media3.common.Player.REPEAT_MODE_ONE
                volume = 0f
                prepare()
            }
        }
        LaunchedEffect(player, playing) { player.playWhenReady = playing }
        androidx.compose.runtime.DisposableEffect(player) { onDispose { player.release() } }
        androidx.compose.ui.viewinterop.AndroidView(
            factory = {
                androidx.media3.ui.PlayerView(it).apply {
                    useController = false
                    resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                    this.player = player
                }
            },
            modifier = modifier,
        )
    }
}

/**
 * What a screen with a video upload does about "Record Video": [record] opens the camera for [owner]
 * ([com.ecency.app.VideoCaptures.Start]), and a finished recording for this screen is handed to [onRecorded]
 * once the owner is known and signed in. The recording is tagged with [kind] plus an id of this editor or
 * composer instance (saved with the screen, so it survives the lock and a new process): it never lands in
 * another editor of the same account. The launcher lives with the activity.
 */
@Composable
internal fun rememberVideoRecord(
    env: com.ecency.core.app.AppEnvironment, owner: String?, kind: String, onRecorded: (android.net.Uri) -> Unit,
): () -> com.ecency.app.VideoCaptures.Start {
    val captures = LocalVideoCaptures.current
    val instance = androidx.compose.runtime.saveable.rememberSaveable { java.util.UUID.randomUUID().toString() }
    val tag = "$kind:$instance"
    val waiting = captures?.captured?.collectAsState()?.value
    val deliver by androidx.compose.runtime.rememberUpdatedState(onRecorded)
    LaunchedEffect(waiting, owner) {
        if (captures == null || waiting == null || waiting.tag != tag || owner == null) return@LaunchedEffect
        // the screen's owner must be the account signed in now; a recording of anyone else is deleted by take
        captures.take(owner.takeIf { env.session.current.username == it }, tag)?.let { deliver(it) }
    }
    return {
        if (owner != null && env.session.current.username == owner && captures != null) captures.record(owner, tag)
        else com.ecency.app.VideoCaptures.Start.NO_CAMERA
    }
}

/** Not RN: a second "Record Video" while the camera app is still open for the first. */
internal val RECORDING_BUSY: String get() = EditorUiCopy.RECORDING_BUSY

/**
 * RN speakUploaderModal: "Record Video" or "Choose from Library", then the looping preview, the thumbnail (a
 * frame of the strip, or another image through the pencil), the progress and Close / Start Upload. The video is
 * picked with the system photo picker (no storage permission) or recorded by the camera app into the app's own
 * cache, read piece by piece, and its frames are cut off the main thread.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VideoUploadSheet(model: VideoUploadModel, onRecord: (() -> com.ecency.app.VideoCaptures.Start)? = null, onDismiss: () -> Unit) {
    val colors = LocalEcencyColors.current
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val state by model.state.collectAsState()
    var notice by remember { mutableStateOf<String?>(null) }
    // the one media picking layer (MediaPicker.kt): MP4 or MOV from the library or files, copied into the app's
    // cache first so the upload reads a file of its own (a withdrawn grant cannot break it)
    // a large picked video is copied before it can be used: said while it runs (closing the sheet stops it)
    var copying by remember { mutableStateOf(false) }
    val picker = com.ecency.app.rememberMediaPicker(MediaSurface.VIDEO, onRejected = { notice = it }) { uris ->
        val uri = uris.firstOrNull()
        if (uri != null) {
            scope.launch {
                copying = true
                val copy = try {
                    com.ecency.app.MediaPickers.copyVideoToCache(context.applicationContext, uri)
                } finally {
                    copying = false
                }
                if (copy == null) model.pickFailed() else com.ecency.app.EditorVideos.adopt(context, model, copy)
            }
        }
    }
    var thumbChoice by remember { mutableStateOf(false) }
    // RN _handleOpenImagePicker: another image as the thumbnail, prepared like any upload (no metadata, 1920 px)
    val thumbPicker = com.ecency.app.rememberMediaPicker(MediaSurface.VIDEO_THUMBNAIL, onRejected = { notice = it }) { uris ->
        val uri = uris.firstOrNull()
        if (uri != null) {
            val appContext = context.applicationContext
            scope.launch {
                val image = com.ecency.app.EditorImages.prepare(appContext, uri, 0)
                if (image == null) notice = UiCopy.INVALID_IMAGE else model.setCustomThumb(image)
            }
        }
    }
    fun choose() = picker.pickFromLibrary()
    fun close() {
        // a video that was picked or recorded and never sent goes with the sheet (and its temporary file)
        model.dismissed()
        onDismiss()
    }
    val lifecycleState by androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val inFront = lifecycleState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
    LaunchedEffect(state.done) { if (state.done) onDismiss() }
    ModalBottomSheet(onDismissRequest = { if (!state.uploading) close() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.background) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding().testTag("videoSheet")) {
            Text(VideoUploadModel.SELECT_SOURCE, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = colors.text, modifier = Modifier.semantics { heading() }.padding(bottom = 8.dp))
            if (!state.hasVideo) {
                TextButton(
                    onClick = {
                        notice = when (onRecord?.invoke()) {
                            com.ecency.app.VideoCaptures.Start.OPENED -> null
                            com.ecency.app.VideoCaptures.Start.BUSY -> RECORDING_BUSY
                            else -> VideoUploadModel.NO_CAMERA
                        }
                    },
                    modifier = Modifier.testTag("videoRecord"),
                ) { Text(VideoUploadModel.RECORD, color = colors.blueText) }
                TextButton(onClick = ::choose, modifier = Modifier.testTag("videoChoose")) { Text(VideoUploadModel.CHOOSE, color = colors.blueText) }
                TextButton(onClick = picker::pickFromFiles, modifier = Modifier.testTag("videoFiles")) { Text(MediaPickCopy.FILES, color = colors.blueText) }
                if (copying) {
                    Row(Modifier.padding(vertical = 8.dp).testTag("videoCopying"), verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), color = colors.blueText, strokeWidth = 2.dp)
                        Text(MediaPickCopy.PREPARING_VIDEO, color = colors.darkGray, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp))
                    }
                }
                // a video that cannot be used (RN's notice: a short over 60 seconds) is said here
                state.error?.let { Text(it, color = colors.redText, fontSize = 13.sp, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.padding(bottom = 8.dp).testTag("videoError")) }
            } else {
                state.previewRef?.let { ref ->
                    LocalVideoPreview.current.Play(
                        ref, state.previewPlays && inFront,
                        Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black).testTag("videoPreview"),
                    )
                }
                Text(EditorUiCopy.fileSize(state.fileName, com.ecency.core.l10n.LocaleFormat.toFixed(state.sizeBytes / 1048576.0, 1)), color = colors.text, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp).testTag("videoName"))
                Text(VideoUploadModel.SELECT_THUMB, color = colors.darkGray, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
                if (state.generatingThumbs) LoadingBlock(Modifier.padding(vertical = 12.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()).testTag("videoThumbs"), verticalAlignment = Alignment.CenterVertically) {
                    // RN's header: the thumbnail as it will be, with a pencil; a tap picks another image for it
                    Box(
                        Modifier.padding(end = 8.dp, top = 6.dp).size(96.dp, 64.dp).clip(RoundedCornerShape(6.dp)).background(colors.lightBackground)
                            .border(if (state.customThumb) 3.dp else 0.dp, if (state.customThumb) colors.blueText else Color.Transparent, RoundedCornerShape(6.dp))
                            .clickable(enabled = !state.uploading, role = Role.Button) { thumbChoice = true }.testTag("videoThumbEdit"),
                    ) {
                        // read again whenever the choice changes
                        val shown = remember(state.customThumb, state.selectedThumb, state.thumbCount) { model.thumbImage()?.bytes }
                        if (shown != null) AsyncImage(model = shown, contentDescription = EditorUiCopy.THUMBNAIL_PICK, contentScale = ContentScale.Crop, modifier = Modifier.size(96.dp, 64.dp))
                        Text(UiCopy.EDIT, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.TopStart).padding(4.dp).background(Color(0x99000000), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp))
                    }
                    Box(Modifier.padding(end = 8.dp, top = 6.dp).size(1.dp, 48.dp).background(colors.icon))
                    for (i in 0 until state.thumbCount) {
                        val selected = state.effectiveThumb == i
                        AsyncImage(
                            model = model.thumb(i)?.bytes, contentDescription = EditorUiCopy.thumbnailNumber(i + 1), contentScale = ContentScale.Crop,
                            modifier = Modifier.padding(end = 8.dp, top = 6.dp).size(96.dp, 64.dp).clip(RoundedCornerShape(6.dp)).background(colors.lightBackground)
                                .border(if (selected) 3.dp else 0.dp, if (selected) colors.blueText else Color.Transparent, RoundedCornerShape(6.dp))
                                .clickable(enabled = !state.uploading, role = Role.Button) { model.selectThumb(i) }.testTag("videoThumb$i"),
                        )
                    }
                }
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { (state.progress / 100).toFloat() }, color = colors.blueText, trackColor = colors.lightBackground,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).testTag("videoProgress"),
                )
                state.error?.let { Text(it, color = colors.redText, fontSize = 13.sp, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.padding(bottom = 8.dp).testTag("videoError")) }
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { if (state.uploading) model.cancel() else close() }, modifier = Modifier.testTag("videoClose")) { Text(if (state.uploading) UiCopy.CANCEL else UiCopy.CLOSE, color = colors.darkGray) }
                Box(Modifier.weight(1f))
                if (state.hasVideo) {
                    Button(onClick = model::start, enabled = state.canStart, colors = ButtonDefaults.buttonColors(contentColor = androidx.compose.ui.graphics.Color.White, containerColor = colors.blue), modifier = Modifier.testTag("videoStart")) { Text(state.startLabel) }
                }
            }
        }
    }
    if (thumbChoice) MediaSourceChooser(thumbPicker, "videoThumbSource", title = VideoUploadModel.SELECT_THUMB) { thumbChoice = false }
    notice?.let { text ->
        AlertDialog(
            onDismissRequest = { notice = null }, title = { Text(EditorUiCopy.NOTICE) }, text = { Text(text, Modifier.testTag("videoSheetNotice")) },
            confirmButton = { TextButton(onClick = { notice = null }) { Text(UiCopy.OK) } },
        )
    }
}

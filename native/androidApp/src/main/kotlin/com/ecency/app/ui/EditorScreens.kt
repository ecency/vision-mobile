package com.ecency.app.ui

import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TooltipBox
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.LiveRegionMode

import androidx.compose.ui.semantics.liveRegion

import androidx.compose.ui.semantics.heading


import androidx.compose.foundation.lazy.itemsIndexed

import androidx.compose.ui.semantics.selected

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription

import androidx.compose.ui.semantics.Role

import androidx.compose.ui.text.style.TextDirection
import com.ecency.core.app.AppIcon
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.compose.AsyncImage
import com.ecency.app.EditorImages
import com.ecency.app.render.PostActions
import com.ecency.app.render.PostBody
import com.ecency.core.api.ecency.ImageUploader
import com.ecency.core.app.AppEnvironment
import com.ecency.core.app.AppScreen
import com.ecency.core.app.CommunityPickerModel
import com.ecency.core.app.EditorCommunity
import com.ecency.core.app.EditorDone
import com.ecency.core.app.EditorModel
import com.ecency.core.app.EditorState
import com.ecency.core.app.SnippetsModel
import com.ecency.core.app.UploadsModel
import com.ecency.core.editor.EditorFormats
import com.ecency.core.editor.EditorMeta
import com.ecency.core.editor.EditorTags
import com.ecency.core.editor.PollDraft
import com.ecency.core.editor.RewardType
import com.ecency.core.editor.TextSelection
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import com.ecency.core.app.EditorUiCopy
import com.ecency.core.app.UiCopy

/** The editor and the drafts screen: one entry point for the root's screen switch. */
@Composable
fun EditorAndDraftsScreen(screen: AppScreen, env: AppEnvironment, entry: StackEntry, nav: ScreenNav) {
    val colors = LocalEcencyColors.current
    Box(Modifier.fillMaxSize().background(colors.background).statusBarsPadding()) {
        when (screen) {
            is AppScreen.Editor -> Guarded({ entry.get("editor") { env.editorModel(screen.args) } }) { model ->
                EditorScreen(env, model, entry, nav)
            }
            is AppScreen.Drafts -> Guarded({ entry.get("drafts") { env.draftsModel(screen.tab) } }) { model -> DraftsScreen(env, model, nav) }
            // RN aiImageGenerator opened on its own: the same generator, without "Insert into post"
            AppScreen.AiImageGenerator -> Guarded({ entry.get("aiImage") { env.aiImageScreenModel() } }) { model ->
                Column(Modifier.fillMaxSize()) {
                    TopBar(com.ecency.core.app.AiImageModel.TITLE, onBack = nav.pop)
                    AiImageBody(model, showTitle = false) { nav.pop() }
                }
            }
            else -> ComingSoon(screen.route.title)
        }
    }
}

/** RN `moment(date).format()`: local time with its offset. */
internal fun scheduleString(millis: Long, zone: TimeZone = TimeZone.getDefault()): String =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).apply { timeZone = zone }.format(millis)

/** `Date.toISOString()`. */
internal fun isoString(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(millis)

/** The picked schedule in the app language ("Tue, Mar 5, 3:07 PM"; 12 or 24 hours as the language does). */
private fun display(millis: Long): String = com.ecency.core.l10n.LocaleFormat.weekdayDateTime(millis)

/**
 * A draft's or schedule's ISO time in the app language and the device's time zone, as iOS shows it (README
 * "Localization", formatting); the server's zone-less UTC times read as UTC, text that does not parse shows as it
 * came. What the app sends stays ISO.
 */
internal fun shownDraftDate(iso: String): String? {
    if (iso.isEmpty()) return null
    val millis = EditorMeta.isoMillis(iso) ?: EditorMeta.isoMillis(iso + "Z") ?: return iso.take(16).replace('T', ' ')
    return com.ecency.core.l10n.LocaleFormat.dateTime(millis)
}

/** A date then a time, never before now (RN minimumDate). */
private fun pickDateTime(context: android.content.Context, initial: Long, onPicked: (Long) -> Unit) {
    val c = Calendar.getInstance().apply { timeInMillis = maxOf(initial, System.currentTimeMillis()) }
    DatePickerDialog(context, { _, y, m, d ->
        TimePickerDialog(context, { _, h, min ->
            c.set(y, m, d, h, min, 0)
            onPicked(maxOf(c.timeInMillis, System.currentTimeMillis()))
        }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), com.ecency.core.l10n.LocaleFormat.uses24Hour()).show()
    }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).apply { datePicker.minDate = System.currentTimeMillis() - 1000 }.show()
}

/**
 * A header action that shows its label while the label fits on one line and its icon otherwise (the label is then
 * the icon's description), as iOS's ViewThatFits: at the largest font scale or with long copy every action stays
 * visible and recognisable instead of collapsing to an ellipsis. The choice is made from the label's measured width
 * against the width the header gives it on every layout, so it switches back to the label when room comes back
 * (rotation, a wider window or split screen, a smaller font size).
 */
@Composable
internal fun androidx.compose.foundation.layout.RowScope.LabelOrIcon(
    label: String, icon: AppIcon, color: Color, enabled: Boolean = true, tag: String, onClick: () -> Unit,
) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.weight(1f, fill = false).testTag(tag)) {
        val measurer = androidx.compose.ui.text.rememberTextMeasurer()
        val style = androidx.compose.material3.LocalTextStyle.current
        androidx.compose.foundation.layout.BoxWithConstraints(contentAlignment = Alignment.Center) {
            val needed = measurer.measure(label, style, maxLines = 1, softWrap = false).size.width
            if (needed <= constraints.maxWidth) {
                Text(label, color = color, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip, modifier = Modifier.testTag("$tag.label"))
            } else {
                Glyph(icon, color, 22.dp, description = label)
            }
        }
    }
}

@Composable
private fun PlainField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    tag: String,
    modifier: Modifier = Modifier,
    style: TextStyle = TextStyle(fontSize = 16.sp, textDirection = TextDirection.Content),
    singleLine: Boolean = false,
    enabled: Boolean = true,
    keyboard: KeyboardOptions = KeyboardOptions.Default,
    actions: KeyboardActions = KeyboardActions.Default,
) {
    val colors = LocalEcencyColors.current
    BasicTextField(
        value = value, onValueChange = onValueChange, enabled = enabled, singleLine = singleLine, keyboardOptions = keyboard, keyboardActions = actions,
        textStyle = style.copy(color = colors.text), cursorBrush = SolidColor(colors.blueText), modifier = modifier.testTag(tag),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) Text(placeholder, style = style, color = colors.darkGray)
                inner()
            }
        },
    )
}

/** The largest text scale of the editor toolbar's labels: Aa, Image, Video and AI Assist fit a 320dp screen at it. */
private const val TOOLBAR_MAX_FONT_SCALE = 1.2f

/** The screen's own density inside the capped toolbar: the long press tooltip shows the name at the full text size. */
private val LocalUncappedDensity = staticCompositionLocalOf<Density?> { null }

/**
 * A toolbar button. A long press shows its name in a tooltip at the full text size, the counterpart of iOS's large
 * content viewer, since the toolbar's own labels stop growing at [TOOLBAR_MAX_FONT_SCALE].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolButton(label: String, tag: String, description: String, active: Boolean = false, style: TextStyle = TextStyle(textDirection = TextDirection.Content), onClick: () -> Unit) {
    val colors = LocalEcencyColors.current
    val fullDensity = LocalUncappedDensity.current ?: LocalDensity.current
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = {
            CompositionLocalProvider(LocalDensity provides fullDensity) {
                PlainTooltip(Modifier.testTag("$tag:tooltip")) { Text(description, fontSize = 15.sp) }
            }
        },
        state = rememberTooltipState(),
    ) {
        Box(
            Modifier.padding(horizontal = 2.dp).clip(RoundedCornerShape(8.dp)).background(if (active) colors.lightBackground else Color.Transparent)
                .clickable(onClick = onClick, role = Role.Button).padding(horizontal = 12.dp, vertical = 10.dp).semantics { contentDescription = description }.testTag(tag),
        ) { Text(label, color = colors.darkGray, fontSize = 15.sp, style = style, fontWeight = style.fontWeight ?: FontWeight.Medium) }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(env: AppEnvironment, model: EditorModel, entry: StackEntry, nav: ScreenNav) {
    val state by model.state.collectAsState()
    val colors = LocalEcencyColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uploads = remember(model) { entry.get("uploads") { env.uploadsModel(model) } }
    val uploadState by uploads.state.collectAsState()
    LaunchedEffect(model) { model.start() }

    var confirmSubmit by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var saveChoice by remember { mutableStateOf(false) }
    var draftPrompt by remember { mutableStateOf<EditorDone.Published?>(null) }
    var showFormats by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<String?>(null) }
    // debug launch (screenshots): once the editor knows its owner, the asked-for text and sheet
    LaunchedEffect(state.owner) {
        if (state.owner == null) return@LaunchedEffect
        DebugLaunch.editorText?.let { text -> DebugLaunch.editorText = null; model.setBody(text, com.ecency.core.editor.TextSelection(text.length)) }
        DebugLaunch.editorSheet?.let { DebugLaunch.editorSheet = null; sheet = it }
    }
    // kept with the screen (not with the sheet): a failed upload can be continued after the sheet was closed
    val video = remember(model) { entry.get("video") { env.videoUploadModel(model) } }
    var videoNotice by remember { mutableStateOf<String?>(null) }
    // "Record Video": the recording comes back here (also after the lock or in a new process) and opens the sheet
    val recordVideo = rememberVideoRecord(env, state.owner, "editor") { uri ->
        val appContext = context.applicationContext
        video.launch {
            if (video.refusal() != null) com.ecency.app.EditorImages.deleteIfCapture(appContext, uri) else com.ecency.app.EditorVideos.adopt(appContext, video, uri)
        }
        if (video.refusal() == null) sheet = "video"
    }

    fun replace(screen: AppScreen) {
        nav.pop()
        nav.push(screen)
    }

    fun leave() {
        if (state.isSending) return
        if (model.leavingLosesChanges) {
            confirmDiscard = true
        } else {
            model.leave()
            nav.pop()
        }
    }
    ScreenBackHandler { leave() }

    // the app going to the background writes the draft at once (RN AppState listener)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, model) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) model.flush() }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            // the editor leaves composition when the app lock covers it (and when it is popped): the stop event
            // above no longer reaches it then, so the draft is written here
            model.flush()
        }
    }

    LaunchedEffect(state.done) {
        when (val done = state.done) {
            is EditorDone.Published -> if (done.draftId != null) {
                draftPrompt = done
            } else {
                delay(500)
                replace(AppScreen.Profile(done.author))
            }
            EditorDone.Scheduled -> replace(AppScreen.Drafts(1))
            is EditorDone.Edited, is EditorDone.Replied -> nav.pop()
            null -> {}
        }
    }
    uploadState.alert?.let { text ->
        AlertDialog(
            onDismissRequest = uploads::clearAlert, title = { Text(UiCopy.FAIL) }, text = { Text(text, Modifier.testTag("uploadAlert")) },
            confirmButton = { TextButton(onClick = uploads::clearAlert) { Text(UiCopy.OK) } },
        )
    }

    // ---- pickers (registered here so a result still arrives after its sheet closed) ----
    fun upload(uris: List<android.net.Uri>, insert: Boolean = true) {
        if (uris.isEmpty()) return
        if (uris.size > ImageUploader.MAX_IMAGES_PER_PICK) env.ui.toast(EditorUiCopy.tooManyImages(ImageUploader.MAX_IMAGES_PER_PICK))
        // an upload outlives this screen's composition (the lock may cover it): the app context and the model's scope
        val appContext = context.applicationContext
        uploads.launch {
            // decoded and resized off the main thread, subsampled: a full-size bitmap is never held
            val files = uris.take(ImageUploader.MAX_IMAGES_PER_PICK).mapIndexedNotNull { i, uri -> EditorImages.prepare(appContext, uri, i) }
            uris.drop(ImageUploader.MAX_IMAGES_PER_PICK).forEach { EditorImages.deleteIfCapture(appContext, it) }
            if (files.isEmpty()) env.ui.toast(UiCopy.INVALID_IMAGE) else uploads.upload(files, insert = insert)
        }
    }
    // RN hasSharedIntent: content shared into the app lands in a new post once it loaded (text at the caret, images uploaded)
    LaunchedEffect(model, state.loading, state.owner) {
        if (state.loading || model.args.kind != com.ecency.core.app.EditorKind.NEW_POST) return@LaunchedEffect
        val shared = env.share.takeForEditor(state.owner) ?: return@LaunchedEffect
        if (shared.text.isNotEmpty()) model.insertSnippet(shared.text)
        upload(shared.images.map { android.net.Uri.parse(it) }.filter { com.ecency.app.ShareIntents.acceptable(context, it) })
    }
    // the one media picking layer (MediaPicker.kt): system photo picker or document picker, images only
    val photoPicker = com.ecency.app.rememberMediaPicker(com.ecency.core.app.MediaSurface.EDITOR, onRejected = { env.ui.toast(it) }) { upload(it) }
    // RN uploads gallery "+": the picked images only join the gallery (addToUploads), nothing goes into the body
    val galleryAdd = com.ecency.app.rememberMediaPicker(com.ecency.core.app.MediaSurface.EDITOR_GALLERY, onRejected = { env.ui.toast(it) }) { upload(it, insert = false) }
    // saved: the camera app often pushes this process out of memory, and the result arrives in a new one
    var cameraUri by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<android.net.Uri?>(null) }
    // the camera app needs the camera permission the QR scanner declared
    val cameraGate = rememberCameraGate { env.ui.toast(com.ecency.core.app.QrScanModel.PERMISSION_MESSAGE) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = cameraUri
        cameraUri = null
        when {
            ok && uri != null -> upload(listOf(uri))
            ok -> env.ui.toast(EditorUiCopy.PHOTO_LOST)
            uri != null -> EditorImages.deleteIfCapture(context, uri) // cancelled: no temp file stays behind
        }
    }

    // ---- body text field: typing lives in the field, model edits replace it ----
    var field by remember { mutableStateOf(TextFieldValue(state.body, TextRange(state.selection.start, state.selection.end))) }
    LaunchedEffect(state.bodyRevision) {
        if (field.text != state.body || field.selection.min != state.selection.start || field.selection.max != state.selection.end) {
            field = TextFieldValue(state.body, TextRange(state.selection.start, state.selection.end))
        }
    }

    Column(Modifier.fillMaxSize().imePadding().testTag("editor")) {
        // ---- header ----
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.clip(RoundedCornerShape(50)).clickable(role = Role.Button) { leave() }.padding(12.dp).testTag("editorBack")) { Glyph(AppIcon.BACK, colors.darkGray, 24.dp, description = UiCopy.BACK) }
            Text(if (state.wordsCount > 0) EditorUiCopy.wordsCount(state.wordsCount) else "", color = colors.darkGray, fontSize = 13.sp, modifier = Modifier.weight(1f).testTag("wordsCount"))
            if (state.isDraftSaving) CircularProgressIndicator(Modifier.size(18.dp), color = colors.blueText, strokeWidth = 2.dp)
            if (!state.isReply && !state.isEdit) {
                LabelOrIcon(
                    if (state.isDraftSaved) EditorUiCopy.SAVED else UiCopy.SAVE, if (state.isDraftSaved) AppIcon.CHECK else AppIcon.DRAFTS,
                    if (state.isDraftSaved) colors.icon else colors.blueText, enabled = !state.isDraftSaved && !state.isDraftSaving, tag = "saveDraft",
                ) { if (state.draftId != null) saveChoice = true else model.saveDraft() }
            }
            // long copy or large text turns Save and Preview into their icons, never squeezing the submit button
            LabelOrIcon(if (state.isPreview) UiCopy.EDIT else EditorUiCopy.PREVIEW, if (state.isPreview) AppIcon.EDIT else AppIcon.PREVIEW, colors.blueText, tag = "previewToggle") {
                model.setPreview(!state.isPreview)
            }
            if (!state.isReply) {
                Box(Modifier.clip(RoundedCornerShape(50)).clickable(role = Role.Button) { sheet = "options" }.padding(10.dp).testTag("postOptions")) { Glyph(AppIcon.SETTINGS, colors.darkGray, 22.dp, description = EditorUiCopy.POST_OPTIONS) }
            }
            if (state.isSending || state.uploading > 0) CircularProgressIndicator(Modifier.padding(horizontal = 12.dp).size(22.dp).testTag("editorBusy"), color = colors.blueText, strokeWidth = 2.dp)
            else Button(
                onClick = { if (state.kind == com.ecency.core.app.EditorKind.REPLY) model.submit() else confirmSubmit = true },
                enabled = state.canSubmit, colors = ButtonDefaults.buttonColors(contentColor = androidx.compose.ui.graphics.Color.White, containerColor = colors.blue),
                modifier = Modifier.padding(end = 8.dp).testTag("editorSubmit"),
            ) { Text(state.submitLabel, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1) }
        }
        if (state.localSaveFailed) {
            Text(EditorUiCopy.COULD_NOT_STORE_THE, color = colors.redText, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp).testTag("localSaveFailed"))
        }

        when {
            state.loading -> LoadingBlock(Modifier.padding(top = 48.dp))
            state.error != null && state.owner != null && state.body.isEmpty() && state.kind != com.ecency.core.app.EditorKind.NEW_POST ->
                EsieMessageBlock(com.ecency.core.esie.EsiePlacement.ERROR, state.error!!.message, Modifier.padding(top = 48.dp).testTag("editorError"), com.ecency.core.app.EditorLoadCopy.RETRY, model::retryLoad)
            state.isPreview -> BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).testTag("editorPreview")) {
                val bodyWidth = maxWidth
                Column {
                    if (!state.isReply) Text(state.title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = colors.text, modifier = Modifier.padding(vertical = 8.dp))
                    if (!state.isReply && state.tags.isNotEmpty()) Text(state.tags.joinToString("  ") { "#$it" }, color = colors.blueText, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
                    val blocks = state.preview
                    if (blocks == null) LoadingBlock(Modifier.padding(top = 24.dp)) else PostBody(blocks, bodyWidth, PostActions())
                    state.poll?.takeIf { it.title.isNotEmpty() }?.let { poll ->
                        Column(Modifier.padding(vertical = 12.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.lightBackground).padding(12.dp).testTag("pollPreview")) {
                            Text(poll.title, fontWeight = FontWeight.Bold, color = colors.text)
                            poll.choices.forEach { Text(it, color = colors.text, modifier = Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).border(1.dp, colors.border, RoundedCornerShape(8.dp)).padding(10.dp)) }
                        }
                    }
                }
            }
            else -> Column(Modifier.weight(1f).fillMaxWidth()) {
                if (!state.isReply && !state.isEdit) {
                    Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { sheet = "community" }.padding(horizontal = 16.dp, vertical = 8.dp).testTag("communityRow"), verticalAlignment = Alignment.CenterVertically) {
                        Avatar("https://images.ecency.com/u/${state.community?.name ?: state.owner.orEmpty()}/avatar/128", 28.dp)
                        Text(state.community?.title ?: EditorUiCopy.MY_BLOG, color = colors.text, fontSize = 15.sp, modifier = Modifier.padding(start = 8.dp).weight(1f))
                        if (!state.canPostToCommunity) Text(EditorUiCopy.communityRestriction(state.community?.title.orEmpty()), color = colors.redText, fontSize = 12.sp, modifier = Modifier.testTag("communityRestriction"))
                    }
                }
                if (!state.isReply) {
                    PlainField(state.title, model::setTitle, EditorUiCopy.TITLE, "editorTitle", Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, textDirection = TextDirection.Content))
                    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        state.tags.forEach { tag ->
                            Text(
                                tag, color = Color.White, fontSize = 13.sp,
                                modifier = Modifier.clip(RoundedCornerShape(50)).background(colors.blue).clickable(role = Role.Button) { model.removeTag(tag) }.padding(horizontal = 10.dp, vertical = 4.dp).testTag("tag:$tag"),
                            )
                        }
                        PlainField(
                            state.tagInput, model::setTagInput, EditorUiCopy.TAGS_SEPARATE_WITH_SPACE, "tagInput",
                            Modifier.width(220.dp).padding(vertical = 4.dp).onFocusChanged { if (!it.isFocused) model.endTagEditing() }, TextStyle(fontSize = 14.sp, textDirection = TextDirection.Content), singleLine = true,
                            keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                            actions = KeyboardActions(onDone = { model.endTagEditing() }),
                        )
                        // the field shows the model's text from this composition on (EditorModel.tagFieldShown)
                        val shownTags = state.tagInput
                        androidx.compose.runtime.SideEffect { model.tagFieldShown(shownTags) }
                    }
                    state.tagWarning?.let { Text(it, color = colors.redText, fontSize = 12.sp, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.padding(horizontal = 16.dp, vertical = 2.dp).testTag("tagWarning")) }
                } else {
                    state.parentSummary?.takeIf { !state.isEdit }?.let {
                        Text(it, color = colors.darkGray, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("parentSummary"))
                    }
                    if (state.isEdit) {
                        Text("@${state.owner.orEmpty()}", color = colors.text, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    } else {
                        // RN reply account tile: avatar, @name and the arrow; a tap opens the accounts sheet and the reply carries over
                        Row(
                            Modifier.padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(8.dp))
                                .clickable(enabled = !state.isSending, role = Role.Button) { env.replyHandoff.begin(model) }
                                .semantics { contentDescription = com.ecency.core.app.ReplyAccountCopy.SWITCH }
                                .padding(vertical = 4.dp, horizontal = 4.dp).testTag("replyAccountTile"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Avatar(com.ecency.core.app.PostCard.avatarUrl(state.owner.orEmpty(), env.proxyBase), 28.dp)
                            Text("@${state.owner.orEmpty()}", color = colors.text, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp))
                            Glyph(AppIcon.DROPDOWN, colors.text, 20.dp, Modifier.padding(start = 2.dp))
                        }
                    }
                }
                BasicTextField(
                    value = field,
                    onValueChange = {
                        field = it
                        model.setBody(it.text, TextSelection(it.selection.min, it.selection.max))
                    },
                    enabled = !state.isSending,
                    textStyle = TextStyle(fontSize = 16.sp, color = colors.text, lineHeight = 24.sp, textDirection = TextDirection.Content), cursorBrush = SolidColor(colors.blueText),
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("editorBody"),
                    decorationBox = { inner ->
                        Box(Modifier.fillMaxSize()) {
                            if (field.text.isEmpty()) Text(if (state.isReply) EditorUiCopy.ADD_YOUR_REPLY else EditorUiCopy.TELL_US_SOMETHING, color = colors.darkGray, fontSize = 16.sp)
                            inner()
                        }
                    },
                )
                if (state.showsShortReplyHint) {
                    Text(com.ecency.core.app.Quests.SHORT_REPLY_HINT, color = colors.darkGray, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp).testTag("shortReplyHint"))
                }
                state.questChip?.let { chip ->
                    // RN quest chip: opens Perks; the x closes it for this run of the app
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(50)).background(colors.lightBackground).testTag("questChip"), verticalAlignment = Alignment.CenterVertically) {
                        Text(chip, color = colors.text, fontSize = 13.sp, modifier = Modifier.clickable(role = Role.Button) { nav.push(AppScreen.of(com.ecency.core.app.RouteName.PERKS)) }.padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 6.dp))
                        Box(Modifier.clickable(onClick = model::dismissQuestChip, role = Role.Button).padding(8.dp).testTag("questChipClose")) { Glyph(AppIcon.CLOSE, colors.darkGray, 14.dp, description = UiCopy.CANCEL) }
                    }
                }
                if (state.recentDraftAvailable) {
                    Button(onClick = model::loadRecentDraft, colors = ButtonDefaults.buttonColors(contentColor = androidx.compose.ui.graphics.Color.White, containerColor = colors.blue), modifier = Modifier.align(Alignment.End).padding(end = 16.dp, bottom = 8.dp).testTag("loadDraft")) {
                        Text(EditorUiCopy.DRAFT, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
                MentionBar(env, model, state)
                uploadState.progress?.let { progress ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("uploadProgress"), verticalAlignment = Alignment.CenterVertically) {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.weight(1f), color = colors.blueText)
                        TextButton(onClick = uploads::cancelUploads, modifier = Modifier.testTag("cancelUpload")) { Text(UiCopy.CANCEL, color = colors.redText) }
                    }
                }
                if (state.rcWarning) {
                    // RN RcPrecheckBanner: advice while writing, never a block; a tap opens the top up / boost offer
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(8.dp)).background(colors.lightBackground)
                            .clickable(onClick = model::showRcOffer, role = Role.Button).padding(12.dp).testTag("rcPrecheck"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(EditorModel.RC_PRECHECK_TITLE, color = colors.text, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text(EditorModel.RC_PRECHECK_BODY, color = colors.darkGray, fontSize = 12.sp)
                        }
                        Text(com.ecency.core.app.RcErrors.TOP_UP, color = colors.blueText, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp))
                    }
                }
                if (showFormats) {
                    Row(Modifier.fillMaxWidth().background(colors.lightBackground).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        ToolButton("B", "formatBold", EditorUiCopy.BOLD, style = TextStyle(fontWeight = FontWeight.Bold, textDirection = TextDirection.Content)) { model.bold() }
                        ToolButton("I", "formatItalic", EditorUiCopy.ITALIC, style = TextStyle(fontStyle = FontStyle.Italic, textDirection = TextDirection.Content)) { model.italic() }
                        ToolButton(EditorUiCopy.TABLE, "formatTable", EditorUiCopy.TABLE) { model.table() }
                        ToolButton("[ ]( )", "formatLink", EditorUiCopy.LINK) { model.wrapLink() }
                    }
                }
                // the labels grow with the text size up to 1.2 times (TOOLBAR_MAX_FONT_SCALE), so Aa to AI Assist stay on a
                // 320dp screen; beyond that a long press shows the name at full size and TalkBack reads it
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale.coerceAtMost(TOOLBAR_MAX_FONT_SCALE)), LocalUncappedDensity provides density) {
                    Row(Modifier.fillMaxWidth().background(colors.background).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp).navigationBarsPadding().testTag("editorToolbar"), verticalAlignment = Alignment.CenterVertically) {
                        // owner order (10-28): the writing tools first, AI Assist on screen without scrolling (it holds the
                        // image generator and dictation), the rarely used poll late
                        ToolButton("Aa", "toolFormat", EditorUiCopy.TEXT_FORMAT, active = showFormats) { showFormats = !showFormats }
                        ToolButton(UiCopy.IMAGE, "toolImage", UiCopy.IMAGE) { sheet = "uploads" }
                        // RN _showVideoUploads: one 3Speak video a post
                        ToolButton(UiCopy.VIDEO, "toolVideo", EditorUiCopy.ADD_VIDEO) {
                            val why = video.refusal()
                            if (why != null) {
                                videoNotice = why
                            } else {
                                video.reset() // the video of an earlier upload was taken out of the body again
                                sheet = "video"
                            }
                        }
                        ToolButton(EditorUiCopy.AI_ASSIST, "toolAiAssist", EditorUiCopy.AI_WRITING_ASSISTANT) { sheet = "aiAssist" }
                        ToolButton(EditorUiCopy.SNIPPETS, "toolSnippets", EditorUiCopy.SNIPPETS) { sheet = "snippets" }
                        ToolButton(EditorUiCopy.LINK, "toolLink", EditorUiCopy.INSERT_LINK) { sheet = "link" }
                        if (!state.isEdit && !state.isReply) ToolButton(UiCopy.POLL, "toolPoll", UiCopy.POLL, active = state.poll?.title?.isNotEmpty() == true) { sheet = "poll" }
                        ToolButton(UiCopy.TRANSLATE, "toolTranslate", EditorUiCopy.TRANSLATE_POST) { sheet = "translate" }
                        ToolButton(UiCopy.CLEAR, "toolClear", UiCopy.CLEAR) { confirmClear = true }
                    }
                }
            }
        }
    }

    // ---- dialogs ----
    if (confirmSubmit) {
        AlertDialog(
            onDismissRequest = { confirmSubmit = false },
            title = { Text(if (state.isEdit) EditorUiCopy.PUBLISHING_EDITS else EditorUiCopy.PUBLISHING_NEW_POST) }, text = { Text(EditorUiCopy.ARE_YOU_SURE) },
            confirmButton = { TextButton(onClick = { confirmSubmit = false; model.submit() }, modifier = Modifier.testTag("confirmYes")) { Text(EditorUiCopy.YES_BUTTON) } },
            dismissButton = { TextButton(onClick = { confirmSubmit = false }) { Text(EditorUiCopy.NO_BUTTON) } },
        )
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false }, title = { Text(EditorUiCopy.DISCARD_CHANGES) }, text = { Text(EditorUiCopy.YOUR_EDITS_WERE_NOT) },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; model.leave(); nav.pop() }, modifier = Modifier.testTag("discardYes")) { Text(EditorUiCopy.DISCARD, color = colors.redText) } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(EditorUiCopy.KEEP_EDITING) } },
        )
    }
    if (state.publishConflict) {
        // the post (or reply) of an earlier attempt is on chain and the text changed since: never edited silently
        AlertDialog(
            onDismissRequest = model::dismissPublishConflict,
            title = { Text(if (state.conflictChangedElsewhere) EditorUiCopy.THIS_LINK_IS_ALREADY else if (state.isReply) UiCopy.YOUR_REPLY_WAS_ALREADY else EditorUiCopy.YOUR_POST_WAS_ALREADY) },
            text = {
                Text(
                    when {
                        state.conflictChangedElsewhere -> EditorUiCopy.A_POST_ALREADY_EXISTS
                        state.isReply -> UiCopy.UPDATE_IT_OR_POST
                        else -> EditorUiCopy.UPDATE_IT_OR_PUBLISH
                    },
                    Modifier.testTag("conflictText"),
                )
            },
            confirmButton = { TextButton(onClick = { model.resolvePublishConflict(update = true) }, modifier = Modifier.testTag("conflictUpdate")) { Text(UiCopy.UPDATE_IT) } },
            dismissButton = {
                Row {
                    TextButton(onClick = model::dismissPublishConflict) { Text(UiCopy.CANCEL) }
                    TextButton(onClick = { model.resolvePublishConflict(update = false) }, modifier = Modifier.testTag("conflictNew")) { Text(if (state.isReply) UiCopy.NEW_REPLY else EditorUiCopy.NEW_POST) }
                }
            },
        )
    }
    if (state.authorityPrompt) PostingAuthorityDialog(onAnswer = model::answerAuthorityPrompt, onDismiss = model::dismissAuthorityPrompt)
    if (state.grantPrompt) {
        // scheduling changes the account's posting authority: only after an explicit yes, then the active key is asked
        AlertDialog(
            onDismissRequest = model::dismissPostingGrant,
            title = { Text(EditorUiCopy.ALLOW_ECENCY_TO_PUBLISH) },
            text = { Text(EditorUiCopy.A_SCHEDULED_POST_IS) },
            confirmButton = { TextButton(onClick = model::confirmPostingGrant, modifier = Modifier.testTag("grantYes")) { Text(EditorUiCopy.GRANT) } },
            dismissButton = { TextButton(onClick = model::dismissPostingGrant) { Text(UiCopy.CANCEL) } },
        )
    }
    if (state.recoveryAvailable) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(EditorUiCopy.RESTORE_UNSAVED_CHANGES) },
            text = { Text(EditorUiCopy.YOU_HAVE_UNSAVED_CHANGES) },
            confirmButton = { TextButton(onClick = model::restoreRecovery, modifier = Modifier.testTag("recoveryRestore")) { Text(EditorUiCopy.RESTORE) } },
            dismissButton = { TextButton(onClick = model::discardRecovery, modifier = Modifier.testTag("recoveryDiscard")) { Text(EditorUiCopy.DISCARD, color = colors.redText) } },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false }, title = { Text(UiCopy.CLEAR) }, text = { Text(EditorUiCopy.CLEAR_CONFIRM) },
            confirmButton = { TextButton(onClick = { confirmClear = false; model.clear() }, modifier = Modifier.testTag("clearYes")) { Text(UiCopy.CLEAR, color = colors.redText) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(UiCopy.CANCEL) } },
        )
    }
    if (saveChoice) {
        AlertDialog(
            onDismissRequest = { saveChoice = false }, title = { Text(EditorUiCopy.SAVING_DRAFT) },
            text = {
                Column {
                    TextButton(onClick = { saveChoice = false; model.saveDraft() }, modifier = Modifier.testTag("draftUpdate")) { Text(EditorUiCopy.UPDATE_CURRENT_DRAFT) }
                    TextButton(onClick = { saveChoice = false; model.saveDraft(saveAsNew = true) }, modifier = Modifier.testTag("draftSaveNew")) { Text(EditorUiCopy.SAVE_AS_NEW_DRAFT) }
                }
            },
            confirmButton = { TextButton(onClick = { saveChoice = false }) { Text(UiCopy.CANCEL) } },
        )
    }
    draftPrompt?.let { done ->
        AlertDialog(
            onDismissRequest = {}, title = { Text(EditorUiCopy.POST_PUBLISHED) },
            // README "Esie": the publish succeeded
            icon = { EsieView(com.ecency.core.esie.EsiePlacement.SUCCESS) }, text = { Text(EditorUiCopy.PUBLISHED_DRAFT_DELETE_BODY) },
            confirmButton = { TextButton(onClick = { draftPrompt = null; model.deletePublishedDraft(); replace(AppScreen.Profile(done.author)) }, modifier = Modifier.testTag("deleteDraftYes")) { Text(UiCopy.DELETE, color = colors.redText) } },
            dismissButton = { TextButton(onClick = { draftPrompt = null; replace(AppScreen.Profile(done.author)) }, modifier = Modifier.testTag("keepDraft")) { Text(EditorUiCopy.KEEP_DRAFT) } },
        )
    }
    videoNotice?.let { text ->
        AlertDialog(
            onDismissRequest = { videoNotice = null }, title = { Text(EditorUiCopy.NOTICE) }, text = { Text(text, Modifier.testTag("videoNotice")) },
            confirmButton = { TextButton(onClick = { videoNotice = null }) { Text(UiCopy.OK) } },
        )
    }
    when (sheet) {
        "link" -> LinkDialog(state, onInsert = { label, url, type -> model.insertLink(label, url, type) }) { sheet = null }
        "snippets" -> SnippetsSheet(remember { entry.get("snippets") { env.snippetsModel() } }, onPick = { model.insertSnippet(it); sheet = null }) { sheet = null }
        "poll" -> PollSheet(state.poll, onAttach = { model.setPoll(it); sheet = null }, onReset = { model.setPoll(null) }) { sheet = null }
        "options" -> OptionsSheet(env, model, state) { sheet = null }
        "aiAssist" -> {
            val assist = remember { env.aiAssistModel(model) }
            DisposableEffect(assist) { onDispose { assist.close() } }
            // the AI hub: the image generator and dictation open in its place, each as it opened from the toolbar before
            AiAssistSheet(assist, onImage = { sheet = "aiImage" }, onDictate = { sheet = "dictation" }) { sheet = null }
        }
        "video" -> VideoUploadSheet(video, onRecord = recordVideo) { sheet = null }
        "dictation" -> {
            // leaving composition (the sheet closed, the editor popped, the app lock covering it) closes the model, which stops the microphone
            val dictation = remember { env.dictationModel(model) }
            DisposableEffect(dictation) { onDispose { dictation.close() } }
            DictationSheet(dictation) { sheet = null }
        }
        "aiImage" -> {
            val image = remember { env.aiImageModel(model) }
            DisposableEffect(image) { onDispose { image.close() } }
            AiImageSheet(image) { sheet = null }
        }
        // a sheet of its own each time it opens (RN mounts it on show): the body it reads is the body of that moment
        "translate" -> {
            val translate = remember { env.composeTranslateModel(model, env.settings.contentLanguage) }
            DisposableEffect(translate) { onDispose { translate.close() } }
            ComposeTranslateSheet(translate) { sheet = null }
        }
        "community" -> CommunitySheet(remember { entry.get("communities") { env.communityPickerModel() } }, onPick = { model.selectCommunity(it); sheet = null }) { sheet = null }
        "uploads" -> UploadsSheet(
            uploads,
            canPaste = remember(sheet) { EditorImages.clipboardHasImage(context) },
            onGallery = { sheet = null; photoPicker.pickFromLibrary() },
            onFiles = { sheet = null; photoPicker.pickFromFiles() },
            onCamera = {
                sheet = null
                cameraGate {
                    runCatching {
                        val uri = EditorImages.newCameraUri(context)
                        cameraUri = uri
                        camera.launch(uri)
                    }.onFailure { env.ui.toast(UiCopy.SOMETHING_WRONG) }
                }
            },
            onPaste = {
                sheet = null
                val uri = EditorImages.clipboardImage(context)
                if (uri == null) env.ui.toast(EditorUiCopy.CLIPBOARD_NO_IMAGE) else upload(listOf(uri))
            },
            onInsert = { urls -> uploads.insert(urls); sheet = null },
            // the sheet stays open: the new images show in it as they arrive
            onAdd = { galleryAdd.pickFromLibrary() },
        ) { sheet = null }
    }
}

/** RN usernameAutofillBar: accounts matching the "@word" at the caret. */
@Composable
private fun MentionBar(env: AppEnvironment, model: EditorModel, state: EditorState) {
    val colors = LocalEcencyColors.current
    val query = remember(state.body, state.selection) { model.mentionQuery }
    var names by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(query) {
        names = emptyList()
        val q = query ?: return@LaunchedEffect
        delay(300)
        names = runCatching { env.communityApi.lookupAccounts(q, 5) }.getOrDefault(emptyList())
    }
    if (names.isEmpty()) return
    LazyRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp).testTag("mentionBar")) {
        items(names) { name ->
            Row(Modifier.padding(4.dp).clip(RoundedCornerShape(50)).background(colors.lightBackground).clickable(role = Role.Button) { model.insertUsername(name) }.padding(horizontal = 8.dp, vertical = 4.dp).testTag("mention:$name"), verticalAlignment = Alignment.CenterVertically) {
                Avatar("https://images.ecency.com/u/$name/avatar/128", 20.dp)
                Text(name, color = colors.text, fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}

/** RN insertLinkModal: label, url and the type of link; the url must be a web link. */
@Composable
private fun LinkDialog(state: EditorState, onInsert: (String, String, EditorFormats.LinkType) -> Boolean, onDismiss: () -> Unit) {
    val colors = LocalEcencyColors.current
    val selected = remember { state.body.substring(state.selection.start.coerceIn(0, state.body.length), state.selection.end.coerceIn(0, state.body.length)) }
    var label by remember { mutableStateOf(if (EditorFormats.isWebLink(selected)) "" else selected) }
    var url by remember { mutableStateOf(if (EditorFormats.isWebLink(selected)) selected else "") }
    var type by remember { mutableStateOf(EditorFormats.LinkType.PLAIN) }
    var invalid by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(EditorUiCopy.INSERT_LINK) },
        text = {
            Column {
                Row {
                    listOf(EditorFormats.LinkType.PLAIN to EditorUiCopy.PLAIN, EditorFormats.LinkType.VIDEO to UiCopy.VIDEO, EditorFormats.LinkType.IMAGE to UiCopy.IMAGE).forEachIndexed { i, (t, name) ->
                        Row(Modifier.clickable(role = Role.Button) { type = t }.padding(end = 8.dp).testTag("linkType:" + listOf("Plain", "Video", "Image")[i]), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = type == t, onClick = { type = t })
                            Text(name, fontSize = 13.sp)
                        }
                    }
                }
                OutlinedTextField(label, { label = it }, enabled = type == EditorFormats.LinkType.PLAIN, label = { Text(EditorUiCopy.LABEL) }, placeholder = { Text(EditorUiCopy.ENTER_LABEL_OPTIONAL) }, modifier = Modifier.fillMaxWidth().testTag("linkLabel"))
                OutlinedTextField(
                    url, { url = it.trim(); invalid = false }, label = { Text(EditorUiCopy.URL) }, placeholder = { Text(EditorUiCopy.ENTER_URL) }, isError = invalid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), textStyle = ltrFieldStyle(), modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("linkUrl"),
                )
                if (invalid) Text(EditorUiCopy.PLEASE_INSERT_VALID_URL, color = colors.redText, fontSize = 12.sp, modifier = Modifier.testTag("linkInvalid"))
            }
        },
        confirmButton = { TextButton(onClick = { if (onInsert(label, url, type)) onDismiss() else invalid = true }, modifier = Modifier.testTag("linkInsert")) { Text(EditorUiCopy.INSERT_LINK) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(UiCopy.CANCEL) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SnippetsSheet(model: SnippetsModel, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val colors = LocalEcencyColors.current
    val state by model.state.collectAsState()
    LaunchedEffect(model) { model.start() }
    var editing by remember { mutableStateOf<com.ecency.core.api.ecency.Snippet?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.background) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding().testTag("snippetsSheet")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(EditorUiCopy.SNIPPETS, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = colors.text, modifier = Modifier.semantics { heading() }.weight(1f))
                TextButton(onClick = { adding = true }, modifier = Modifier.testTag("snippetAdd")) { Text(EditorUiCopy.ADD_SNIPPET_BUTTON, color = colors.blueText) }
            }
            if (state.loaded && state.snippets.isEmpty()) Text(EditorUiCopy.NO_SNIPPETS_FOUND, color = colors.darkGray, modifier = Modifier.padding(vertical = 24.dp).testTag("snippetsEmpty"))
            if (!state.loaded) LoadingBlock(Modifier.padding(vertical = 24.dp))
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                state.snippets.forEach { s ->
                    Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { onPick(s.body) }.padding(vertical = 8.dp).testTag("snippet:${s.title}"), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.title, fontWeight = FontWeight.Bold, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(s.body, color = colors.darkGray, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        if (s.id.isNotEmpty()) {
                            Box(Modifier.padding(horizontal = 6.dp).clickable(role = Role.Button) { editing = s }.padding(8.dp).testTag("snippetEdit:${s.title}")) { Glyph(AppIcon.EDIT, colors.darkGray, 18.dp, description = EditorUiCopy.EDIT_SNIPPET) }
                            Box(Modifier.padding(horizontal = 6.dp).clickable(role = Role.Button) { deleting = s.id }.padding(8.dp).testTag("snippetDelete:${s.title}")) { Glyph(AppIcon.CLOSE, colors.darkGray, 18.dp, description = EditorUiCopy.DELETE_SNIPPET) }
                        }
                    }
                }
                if (!state.endReached && state.loaded) TextButton(onClick = model::loadMore) { Text(UiCopy.LOAD_MORE) }
            }
        }
    }
    if (adding || editing != null) {
        var title by remember(editing) { mutableStateOf(editing?.title.orEmpty()) }
        var body by remember(editing) { mutableStateOf(editing?.body.orEmpty()) }
        var incomplete by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { adding = false; editing = null }, title = { Text(if (editing != null) EditorUiCopy.EDIT_SNIPPET else EditorUiCopy.ADD_SNIPPET) },
            text = {
                Column {
                    OutlinedTextField(title, { title = it.take(250) }, placeholder = { Text(EditorUiCopy.SNIPPET_TITLE) }, modifier = Modifier.fillMaxWidth().testTag("snippetTitle"))
                    OutlinedTextField(body, { body = it }, placeholder = { Text(EditorUiCopy.ADD_SNIPPET_BODY_HERE) }, modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp).padding(top = 8.dp).testTag("snippetBody"))
                    if (incomplete) Text(EditorUiCopy.SNIPPET_INCOMPLETE, color = colors.redText, fontSize = 12.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = { if (model.save(editing?.id, title, body)) { adding = false; editing = null } else incomplete = true }, modifier = Modifier.testTag("snippetSave")) { Text(UiCopy.SAVE_CAPS) }
            },
            dismissButton = { TextButton(onClick = { adding = false; editing = null }) { Text(UiCopy.CLOSE_CAPS) } },
        )
    }
    deleting?.let { id ->
        AlertDialog(
            onDismissRequest = { deleting = null }, title = { Text(EditorUiCopy.DELETE_SNIPPET) }, text = { Text(EditorUiCopy.DELETE_SNIPPET_CONFIRM) },
            confirmButton = { TextButton(onClick = { deleting = null; model.delete(id) }, modifier = Modifier.testTag("snippetDeleteConfirm")) { Text(UiCopy.CONFIRM) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(UiCopy.CANCEL) } },
        )
    }
}

/** RN pollsWizardContent. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PollSheet(current: PollDraft?, onAttach: (PollDraft) -> Unit, onReset: () -> Unit, onDismiss: () -> Unit) {
    val colors = LocalEcencyColors.current
    val context = LocalContext.current
    fun fresh() = PollDraft(endTime = isoString(System.currentTimeMillis() + PollDraft.DEFAULT_DAYS * 24L * 60 * 60 * 1000))
    var poll by remember { mutableStateOf(current ?: fresh()) }
    var more by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.background) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding().testTag("pollSheet")) {
            Text(EditorUiCopy.CREATE_A_POLL, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = colors.text, modifier = Modifier.semantics { heading() })
            Text(EditorUiCopy.POLL_QUESTION, color = colors.darkGray, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
            OutlinedTextField(poll.title, { poll = poll.copy(title = it) }, placeholder = { Text(EditorUiCopy.ADD_POLL_QUESTION) }, modifier = Modifier.fillMaxWidth().testTag("pollQuestion"))
            Text(EditorUiCopy.CHOICES, color = colors.darkGray, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
            poll.choices.forEachIndexed { i, choice ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        choice, { text -> poll = poll.copy(choices = poll.choices.toMutableList().also { it[i] = text }) },
                        placeholder = { Text(EditorUiCopy.choice(i + 1)) }, modifier = Modifier.weight(1f).padding(top = 4.dp).testTag("pollChoice$i"),
                    )
                    if (i > 1) Box(Modifier.clickable(role = Role.Button) { poll = poll.copy(choices = poll.choices.filterIndexed { j, _ -> j != i }) }.padding(8.dp).testTag("pollChoiceRemove$i")) { Glyph(AppIcon.CLOSE, colors.darkGray, 18.dp, description = UiCopy.REMOVE) }
                }
            }
            TextButton(onClick = { poll = poll.copy(choices = poll.choices + "") }, modifier = Modifier.testTag("pollAddChoice")) { Text(EditorUiCopy.ADD_CHOICE, color = colors.blueText) }
            Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { pickDateTime(context, EditorMeta.isoMillis(poll.endTime) ?: System.currentTimeMillis()) { poll = poll.copy(endTime = isoString(it)) } }.padding(vertical = 12.dp).testTag("pollEnd")) {
                Text(EditorUiCopy.END_TIME, color = colors.darkGray, modifier = Modifier.weight(1f))
                Text(EditorMeta.isoMillis(poll.endTime)?.let(::display).orEmpty(), color = colors.text)
            }
            if (!more) {
                TextButton(onClick = { more = true }, modifier = Modifier.testTag("pollMore")) { Text(EditorUiCopy.SHOW_MORE_CONFIGURATION, color = colors.blueText) }
            } else {
                OutlinedTextField(
                    poll.accountAge.toString(), { v -> v.toIntOrNull()?.takeIf { it >= 0 }?.let { poll = poll.copy(accountAge = it) } }, label = { Text(EditorUiCopy.POLL_ACCOUNT_AGE) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), textStyle = ltrFieldStyle(), modifier = Modifier.fillMaxWidth().testTag("pollAge"),
                )
                OutlinedTextField(
                    poll.maxChoicesVoted.toString(), { v -> v.toIntOrNull()?.takeIf { it >= 0 }?.let { poll = poll.copy(maxChoicesVoted = it) } }, label = { Text(EditorUiCopy.POLL_MAX_OPTIONS) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), textStyle = ltrFieldStyle(), modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("pollMax"),
                )
                Text(EditorUiCopy.INTERPRETATION, color = colors.darkGray, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
                listOf(PollDraft.NUMBER_OF_VOTES to EditorUiCopy.NUMBER_OF_VOTES, PollDraft.TOKENS to EditorUiCopy.TOKEN_HIVE_POWER).forEach { (value, name) ->
                    Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { poll = poll.copy(interpretation = value, token = if (value == PollDraft.TOKENS) PollDraft.HP_TOKEN else null) }.testTag("pollInterpretation:$value"), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = poll.interpretation == value, onClick = null, modifier = Modifier.padding(8.dp))
                        Text(name, color = colors.text)
                    }
                }
                listOf(
                    Triple(EditorUiCopy.HIDE_RESULTS_UNTIL_VOTED, poll.hideResults) { v: Boolean -> poll = poll.copy(hideResults = v) },
                    Triple(EditorUiCopy.SHOW_VOTERS, !poll.hideVotes) { v: Boolean -> poll = poll.copy(hideVotes = !v) },
                    Triple(EditorUiCopy.ALLOW_VOTE_CHANGE, poll.voteChange) { v: Boolean -> poll = poll.copy(voteChange = v) },
                ).forEachIndexed { i, (name, value, set) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(name, color = colors.text, modifier = Modifier.weight(1f))
                        Switch(checked = value, onCheckedChange = set, modifier = Modifier.testTag("pollSwitch:" + listOf("Hide results until voted", "Show voters", "Allow vote change")[i]))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { poll = fresh(); onReset() }, modifier = Modifier.testTag("pollReset")) { Text(EditorUiCopy.RESET_POLL, color = colors.redText) }
                Button(onClick = { onAttach(poll) }, enabled = poll.isValid, colors = ButtonDefaults.buttonColors(contentColor = androidx.compose.ui.graphics.Color.White, containerColor = colors.blue), modifier = Modifier.testTag("pollAttach")) {
                    Text(if (current?.title?.isNotEmpty() == true) EditorUiCopy.UPDATE_POLL else EditorUiCopy.ATTACH_POLL, color = Color.White)
                }
            }
        }
    }
}

/** RN postOptionsModal with its beneficiary section. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OptionsSheet(env: AppEnvironment, model: EditorModel, state: EditorState, onDismiss: () -> Unit) {
    val colors = LocalEcencyColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var later by remember { mutableStateOf(state.scheduledFor != null) }
    var templateName by remember { mutableStateOf<String?>(null) }
    var benefAccount by remember { mutableStateOf("") }
    var benefPercent by remember { mutableStateOf("") }
    var benefError by remember { mutableStateOf<String?>(null) }
    @Composable fun Label(text: String) = Text(text, color = colors.darkGray, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.background) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding().testTag("optionsSheet")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(EditorUiCopy.POST_OPTIONS, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = colors.text, modifier = Modifier.semantics { heading() }.weight(1f))
                TextButton(onClick = onDismiss, modifier = Modifier.testTag("optionsDone")) { Text(EditorUiCopy.DONE_CAPS, color = colors.blueText) }
            }
            if (!state.isEdit) {
                Label(EditorUiCopy.SCHEDULED_FOR)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    listOf(false to EditorUiCopy.IMMEDIATE, true to EditorUiCopy.LATER).forEach { (value, name) ->
                        Row(Modifier.clickable(role = Role.Button) { later = value; if (!value) model.setSchedule(null) }.padding(end = 12.dp).testTag(if (value) "schedule:Later" else "schedule:Immediate"), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = later == value, onClick = null, modifier = Modifier.padding(8.dp))
                            Text(name, color = colors.text)
                        }
                    }
                }
                if (later) {
                    TextButton(onClick = { pickDateTime(context, System.currentTimeMillis()) { model.setSchedule(scheduleString(it)) } }, modifier = Modifier.testTag("schedulePick")) {
                        Text(state.scheduledFor?.let { EditorMeta.isoMillis(it) }?.let(::display) ?: EditorUiCopy.SCHEDULING_TIME, color = colors.blueText)
                    }
                }
                Label(EditorUiCopy.REWARD)
                RewardType.entries.forEach { reward ->
                    Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { model.setReward(reward) }.testTag("reward:${reward.key}"), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = state.reward == reward, onClick = null, modifier = Modifier.padding(8.dp))
                        Text(reward.label, color = colors.text)
                    }
                }
                Row(Modifier.clickable(role = Role.Button) { model.setDefaultReward(!state.isDefaultReward) }.testTag("rewardDefault"), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = state.isDefaultReward, onCheckedChange = null, modifier = Modifier.padding(8.dp))
                    Text(EditorUiCopy.SET_DEFAULT, color = colors.text)
                }
                if (state.community != null) {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(EditorUiCopy.REBLOG, color = colors.text, modifier = Modifier.weight(1f))
                        Switch(checked = state.reblog, onCheckedChange = model::setReblog, modifier = Modifier.testTag("reblogSwitch"))
                    }
                }
                if (state.title.isNotEmpty() || state.body.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(EditorUiCopy.SAVE_AS_TEMPLATE, color = colors.text, modifier = Modifier.weight(1f))
                        TextButton(onClick = { templateName = "" }, modifier = Modifier.testTag("saveTemplate")) { Text(UiCopy.SAVE, color = colors.blueText) }
                    }
                }
            }
            Label(EditorUiCopy.SELECT_POST_THUMBNAIL)
            val images = state.images
            if (images.size < 2) {
                Text(EditorUiCopy.ADD_MORE_IMGS, color = colors.darkGray, fontSize = 13.sp, modifier = Modifier.testTag("thumbHint"))
            } else {
                LazyRow {
                    itemsIndexed(images) { index, url ->
                        val selected = url == state.thumbUrl || (state.thumbUrl !in images && url == images.first())
                        Box(
                            Modifier.padding(end = 8.dp).size(72.dp).clip(RoundedCornerShape(8.dp)).border(if (selected) 3.dp else 0.dp, if (selected) colors.blueText else Color.Transparent, RoundedCornerShape(8.dp))
                                .clickable(role = Role.RadioButton) { model.setThumb(url) }
                                .semantics { contentDescription = UiCopy.imageOf(index + 1, images.size); this.selected = selected }.testTag("thumb:$url"),
                        ) {
                            AsyncImage(env.proxyBase.trimEnd('/') + "/144x144/" + url, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        }
                    }
                }
            }
            Label(EditorUiCopy.SHORT_DESCRIPTION)
            OutlinedTextField(state.description, model::setDescription, placeholder = { Text(EditorUiCopy.BRIEF_SUMMARY_OF_CONTENT) }, modifier = Modifier.fillMaxWidth().testTag("description"))
            if (!state.isEdit) {
                Label(EditorUiCopy.BENEFICIARIES)
                @Composable fun BenefRow(account: String, weight: Int, removable: Boolean) = Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("benef:$account"), verticalAlignment = Alignment.CenterVertically) {
                    Text("${weight / 100}%", color = colors.text, modifier = Modifier.width(64.dp))
                    Text("@$account", color = colors.text, modifier = Modifier.weight(1f))
                    if (removable) Box(Modifier.clickable(role = Role.Button) { model.removeBeneficiary(account) }.padding(8.dp).testTag("benefRemove:$account")) { Glyph(AppIcon.CLOSE, colors.darkGray, 18.dp, description = UiCopy.REMOVE) }
                }
                BenefRow(state.owner.orEmpty(), state.authorWeight, removable = false)
                val locked = EditorMeta.hasThreeSpeakEmbed(state.body)
                state.beneficiaries.forEach { BenefRow(it.account, it.weight, removable = !(locked && it.account == EditorMeta.THREESPEAK_ACCOUNT)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        benefPercent, { benefPercent = it.filter(Char::isDigit).take(3); benefError = null }, placeholder = { Text("%") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), textStyle = ltrFieldStyle(), singleLine = true, modifier = Modifier.width(84.dp).testTag("benefPercent"),
                    )
                    OutlinedTextField(
                        benefAccount, { benefAccount = it.trim().lowercase(); benefError = null }, placeholder = { Text(EditorUiCopy.USERNAME) }, singleLine = true,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp).testTag("benefAccount"),
                    )
                    TextButton(
                        onClick = {
                            scope.launch {
                                benefError = model.addBeneficiary(benefAccount, benefPercent.toIntOrNull() ?: 0)
                                if (benefError == null) { benefAccount = ""; benefPercent = "" }
                            }
                        },
                        modifier = Modifier.testTag("benefAdd"),
                    ) { Text(EditorUiCopy.ADD_ACCOUNT, color = colors.blueText) }
                }
                benefError?.let { Text(it, color = colors.redText, fontSize = 12.sp, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("benefError")) }
                if (state.owner != EditorMeta.ECENCY_SUPPORT_ACCOUNT) {
                    Text(
                        EditorUiCopy.supportEcency(model.supportChipPercent), color = if (state.supportsEcency) Color.White else colors.blueText, fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 12.dp).clip(RoundedCornerShape(50)).background(if (state.supportsEcency) colors.blue else colors.lightBackground)
                            .clickable(onClick = model::toggleSupport, role = Role.Button).padding(horizontal = 12.dp, vertical = 6.dp).testTag("supportChip"),
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    templateName?.let { name ->
        AlertDialog(
            onDismissRequest = { templateName = null }, title = { Text(EditorUiCopy.SAVE_AS_TEMPLATE) },
            text = { OutlinedTextField(name, { templateName = it.take(255) }, placeholder = { Text(EditorUiCopy.TEMPLATE_NAME) }, modifier = Modifier.fillMaxWidth().testTag("templateName")) },
            confirmButton = { TextButton(onClick = { if (name.isNotBlank()) { model.saveAsTemplate(name); templateName = null } }, modifier = Modifier.testTag("templateSave")) { Text(UiCopy.SAVE_CAPS) } },
            dismissButton = { TextButton(onClick = { templateName = null }) { Text(UiCopy.CANCEL) } },
        )
    }
}

/** RN selectCommunityModal. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommunitySheet(model: CommunityPickerModel, onPick: (EditorCommunity?) -> Unit, onDismiss: () -> Unit) {
    val colors = LocalEcencyColors.current
    val state by model.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(model) { model.start() }
    @Composable fun Section(title: String) = Text(title, color = colors.darkGray, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
    @Composable fun CommunityRow(c: EditorCommunity) = Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { scope.launch { onPick(model.resolve(c)) } }.padding(vertical = 8.dp).testTag("community:${c.name}"), verticalAlignment = Alignment.CenterVertically) {
        Avatar("https://images.ecency.com/u/${c.name}/avatar/128", 32.dp)
        Text(c.title, color = colors.text, modifier = Modifier.padding(start = 12.dp))
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.background) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding().testTag("communitySheet")) {
            OutlinedTextField(state.query, model::setQuery, placeholder = { Text(EditorUiCopy.COMMUNITY_SEARCH) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("communitySearch"))
            val results = state.results
            if (results != null) {
                results.forEach { CommunityRow(it) }
            } else {
                Section(EditorUiCopy.MY_BLOG_CAPS)
                Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { onPick(null) }.padding(vertical = 8.dp).testTag("community:myBlog")) { Text(EditorUiCopy.MY_BLOG, color = colors.text) }
                if (state.subscribed.isNotEmpty()) Section(EditorUiCopy.MY_COMMUNITIES)
                state.subscribed.forEach { CommunityRow(it) }
                if (state.top.isNotEmpty()) Section(EditorUiCopy.TOP_COMMUNITIES)
                state.top.forEach { CommunityRow(it) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** RN uploadsGalleryModal: where an image comes from, and the account's uploaded images. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun UploadsSheet(
    model: UploadsModel,
    canPaste: Boolean,
    onGallery: () -> Unit,
    onFiles: () -> Unit,
    onCamera: () -> Unit,
    onPaste: () -> Unit,
    onInsert: (List<String>) -> Unit,
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalEcencyColors.current
    val state by model.state.collectAsState()
    LaunchedEffect(model) { model.start() }
    ModalBottomSheet(onDismissRequest = { model.clearSelection(); onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.background) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding().testTag("uploadsSheet")) {
            MediaSourceTiles(
                com.ecency.core.app.MediaSourceMenus.editorUploads(camera = true, paste = canPaste),
                tagFor = {
                    when (it) {
                        com.ecency.core.app.MediaSource.FILES -> "openFiles"
                        com.ecency.core.app.MediaSource.CAMERA -> "openCamera"
                        com.ecency.core.app.MediaSource.PASTE -> "pasteImage"
                        else -> "openGallery"
                    }
                },
            ) {
                when (it) {
                    com.ecency.core.app.MediaSource.FILES -> onFiles()
                    com.ecency.core.app.MediaSource.CAMERA -> onCamera()
                    com.ecency.core.app.MediaSource.PASTE -> onPaste()
                    else -> onGallery()
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(EditorUiCopy.UPLOADED_IMAGES, fontWeight = FontWeight.Bold, color = colors.text, modifier = Modifier.weight(1f))
                // RN's "+" pill: add images to the gallery without inserting them
                Box(
                    Modifier.clip(CircleShape).clickable(onClick = onAdd, role = Role.Button).semantics { contentDescription = com.ecency.core.app.UploadsCopy.ADD_TO_GALLERY }
                        .padding(6.dp).testTag("addToGallery"),
                ) { Glyph(AppIcon.ADD, colors.text, 24.dp, description = null) }
                if (state.uploading > 0) CircularProgressIndicator(Modifier.padding(horizontal = 6.dp).size(18.dp), color = colors.blueText, strokeWidth = 2.dp)
                if (state.selected.isNotEmpty()) TextButton(onClick = model::deleteSelected, modifier = Modifier.testTag("deleteImages")) { Text(EditorUiCopy.deleteCount(state.selected.size), color = colors.redText) }
            }
            if (!state.loaded) LoadingBlock(Modifier.padding(vertical = 24.dp))
            LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxWidth().heightIn(max = 360.dp).padding(bottom = 16.dp)) {
                items(state.images, key = { it.id.ifEmpty { it.url } }) { image ->
                    val selected = image.id in state.selected
                    Box(
                        Modifier.padding(3.dp).height(104.dp).clip(RoundedCornerShape(8.dp)).border(if (selected) 3.dp else 0.dp, if (selected) colors.redText else Color.Transparent, RoundedCornerShape(8.dp))
                            .combinedClickable(onClick = { if (state.selected.isNotEmpty()) model.toggleSelected(image.id) else onInsert(listOf(image.url)) }, onLongClick = { if (image.id.isNotEmpty()) model.toggleSelected(image.id) }, role = Role.Button)
                            .semantics { contentDescription = UiCopy.IMAGE; this.selected = selected }
                            .testTag("galleryImage:${image.url}"),
                    ) { AsyncImage(image.url, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                }
                if (!state.endReached && state.loaded) item { TextButton(onClick = model::loadMore) { Text(UiCopy.LOAD_MORE) } }
            }
        }
    }
}

// ---- drafts ----

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DraftsScreen(env: AppEnvironment, model: com.ecency.core.app.DraftsModel, nav: ScreenNav) {
    val state by model.state.collectAsState()
    val colors = LocalEcencyColors.current
    LaunchedEffect(model) {
        model.start()
        model.refreshLocalDraft()
    }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var unsynced by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().testTag("drafts")) {
        Column(Modifier.fillMaxSize()) {
            TopBar(EditorUiCopy.DRAFTS, onBack = nav.pop)
            Row(Modifier.fillMaxWidth()) {
                listOf(EditorUiCopy.DRAFTS, EditorUiCopy.SCHEDULES, EditorUiCopy.TEMPLATES).forEachIndexed { i, name ->
                    Column(Modifier.weight(1f).clickable(role = Role.Tab) { model.setTab(i) }.semantics { selected = state.tab == i }.testTag("draftsTab:" + listOf("Drafts", "Schedules", "Templates")[i]), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(name, color = if (state.tab == i) colors.blueText else colors.darkGray, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 12.dp))
                        Box(Modifier.fillMaxWidth().height(2.dp).background(if (state.tab == i) colors.blue else Color.Transparent))
                    }
                }
            }
            @Composable fun RowCard(id: String, title: String, body: String, tag: String?, date: String, selected: Boolean, onClick: () -> Unit, trailing: @Composable () -> Unit) =
                Row(
                    Modifier.fillMaxWidth().background(if (selected) colors.lightBackground else Color.Transparent)
                        .combinedClickable(onClick = { if (state.hasSelection) model.toggleSelected(id) else onClick() }, onLongClick = { if (id.isNotEmpty()) model.toggleSelected(id) }, role = Role.Button)
                        .padding(horizontal = 16.dp, vertical = 10.dp).testTag("draftRow:$title"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(listOfNotNull(tag?.let { "#$it" }, shownDraftDate(date)).joinToString("  "), color = colors.darkGray, fontSize = 12.sp)
                        Text(title, color = colors.text, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(body.take(100), color = colors.darkGray, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (id in state.busy) CircularProgressIndicator(Modifier.size(18.dp), color = colors.blueText, strokeWidth = 2.dp) else trailing()
                }
            @Composable fun Icon(tag: String, description: String, glyph: AppIcon, onClick: () -> Unit) =
                // 36 dp with 12 dp between neighbours: each reaches a 48 dp touch area
                Box(Modifier.padding(horizontal = 6.dp).clickable(onClick = onClick, role = Role.Button).padding(8.dp).testTag(tag)) { Glyph(glyph, colors.darkGray, 20.dp, description = description) }
            val loading = if (state.tab == 1) state.loadingSchedules else state.loadingDrafts
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                when (state.tab) {
                    0 -> {
                        state.localDraft?.let { local ->
                            RowCard("", local.title.ifEmpty { EditorUiCopy.UNTITLED }, local.body, local.tags.firstOrNull(), "", false, onClick = { nav.push(AppScreen.Editor()) }) {
                                Icon("unsyncedInfo", EditorUiCopy.UNSYNCED_DRAFT, AppIcon.WARNING) { unsynced = true }
                            }
                        }
                        state.drafts.forEach { d ->
                            RowCard(d.id, d.title, d.body, d.tags.firstOrNull(), d.modified.ifEmpty { d.created }, d.id in state.selectedDrafts, onClick = { nav.push(AppScreen.Editor.of("draft:${d.id}")) }) {
                                Icon("clone:${d.title}", EditorUiCopy.CLONE, AppIcon.CLIPBOARD) { model.cloneDraft(d.id) }
                                Icon("delete:${d.title}", UiCopy.DELETE, AppIcon.DISCARD) { confirm = UiCopy.REMOVE_CONFIRM to { model.deleteDraft(d.id) } }
                            }
                        }
                        if (state.loaded && !loading && state.drafts.isEmpty() && state.localDraft == null) EsieMessageBlock(com.ecency.core.esie.EsiePlacement.EMPTY, UiCopy.NOTHING_HERE, Modifier.testTag("draftsEmpty"))
                    }
                    1 -> {
                        state.schedules.forEach { s ->
                            RowCard(s.id, s.title, s.body, s.tags.firstOrNull(), s.schedule, s.id in state.selectedSchedules, onClick = { confirm = EditorUiCopy.MOVE_TO_DRAFTS_CONFIRM to { model.moveScheduleToDrafts(s.id) } }) {
                                Text(s.statusLabel, fontSize = 12.sp, color = when (s.status) { 3 -> Color(0xFF4FD688); 1, 2 -> colors.icon; else -> colors.redText }, modifier = Modifier.padding(end = 4.dp).testTag("scheduleStatus:${s.title}"))
                                Icon("delete:${s.title}", UiCopy.DELETE, AppIcon.DISCARD) { confirm = UiCopy.REMOVE_CONFIRM to { model.deleteSchedule(s.id) } }
                            }
                        }
                        if (state.loaded && !loading && state.schedules.isEmpty()) EsieMessageBlock(com.ecency.core.esie.EsiePlacement.EMPTY, UiCopy.NOTHING_HERE, Modifier.testTag("draftsEmpty"))
                    }
                    else -> {
                        state.templates.forEach { d ->
                            RowCard(d.id, d.templateName.ifEmpty { EditorUiCopy.UNTITLED_TEMPLATE }, d.body, d.tags.firstOrNull(), d.modified, d.id in state.selectedDrafts, onClick = { nav.push(AppScreen.Editor.of("template:${d.id}")) }) {
                                Icon("delete:${d.templateName}", UiCopy.DELETE, AppIcon.DISCARD) { confirm = EditorUiCopy.DELETE_TEMPLATE_CONFIRM to { model.deleteDraft(d.id) } }
                            }
                        }
                        if (state.loaded && !loading && state.templates.isEmpty()) Text(EditorUiCopy.TEMPLATES_EMPTY, color = colors.darkGray, modifier = Modifier.padding(24.dp).testTag("draftsEmpty"))
                    }
                }
                if (loading) LoadingBlock(Modifier.padding(24.dp))
                val end = if (state.tab == 1) state.schedulesEnd else state.draftsEnd
                if (!end && state.loaded && !loading) TextButton(onClick = model::loadMore, modifier = Modifier.testTag("draftsMore")) { Text(UiCopy.LOAD_MORE, color = colors.blueText) }
                state.error?.let { ErrorBlock(it, Modifier.padding(24.dp), onRetry = model::refresh) }
            }
        }
        if (state.hasSelection) {
            Button(
                onClick = { confirm = EditorUiCopy.REMOVE_ALL_CONFIRM to model::deleteSelected },
                colors = ButtonDefaults.buttonColors(contentColor = androidx.compose.ui.graphics.Color.White, containerColor = colors.red), modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp).testTag("deleteSelected"),
            ) { Text(UiCopy.DELETE, color = Color.White) }
        }
    }
    confirm?.let { (question, action) ->
        AlertDialog(
            onDismissRequest = { confirm = null }, title = { Text(question) },
            confirmButton = { TextButton(onClick = { confirm = null; action() }, modifier = Modifier.testTag("confirmAction")) { Text(if (question.contains("move")) EditorUiCopy.MOVE else UiCopy.DELETE, color = if (question.contains("move")) colors.blueText else colors.redText) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(UiCopy.CANCEL) } },
        )
    }
    if (unsynced) {
        AlertDialog(
            onDismissRequest = { unsynced = false }, title = { Text(EditorUiCopy.UNSYNCED_DRAFT) }, text = { Text(EditorUiCopy.UNSYNCED_BODY) },
            confirmButton = { TextButton(onClick = { unsynced = false }) { Text(UiCopy.OK) } },
        )
    }
}

/**
 * RN POSTING_AUTHORITY_PROMPT sheet ("Enable Faster Posting"), for the editor and, after a publish that
 * HiveSigner refused for the missing authority, the wave composer and the quick reply.
 */
@Composable
internal fun PostingAuthorityDialog(onAnswer: (grant: Boolean) -> Unit, onDismiss: () -> Unit) {
    val colors = LocalEcencyColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(com.ecency.core.app.PostingAuthorityPrompt.TITLE) },
        text = {
            Column(Modifier.testTag("authorityPrompt")) {
                Text(com.ecency.core.app.PostingAuthorityPrompt.DESCRIPTION)
                com.ecency.core.app.PostingAuthorityPrompt.BENEFITS.forEach { Text("\u2022 $it", fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
                Text(com.ecency.core.app.PostingAuthorityPrompt.SECURITY_NOTE, fontSize = 12.sp, color = colors.darkGray, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(onClick = { onAnswer(true) }, modifier = Modifier.testTag("authorityGrant")) { Text(com.ecency.core.app.PostingAuthorityPrompt.GRANT) } },
        dismissButton = { TextButton(onClick = { onAnswer(false) }, modifier = Modifier.testTag("authoritySkip")) { Text(com.ecency.core.app.PostingAuthorityPrompt.SKIP) } },
    )
}

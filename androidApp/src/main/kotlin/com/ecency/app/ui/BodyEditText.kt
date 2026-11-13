package com.ecency.app.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.editableText
import androidx.compose.ui.semantics.focused
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.insertTextAtCursor
import androidx.compose.ui.semantics.requestFocus
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setSelection
import androidx.compose.ui.semantics.setText
import androidx.compose.ui.semantics.textSelectionRange
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.ecency.core.editor.TextSelection
import kotlinx.coroutines.launch

/**
 * The editor's body text: Android's own EditText. Compose's text field lays out and draws its whole text on every
 * key, so a long draft lagged (8,000 characters on an M1 emulator, release: 17 ms of layout and 33 ms of drawing a
 * key, both growing with the text). The EditText relays out only the paragraph that changed (DynamicLayout) and
 * redraws only the blocks that changed. RN's editor was an EditText too.
 */
internal class BodyEditTextView(context: Context) : EditText(context) {
    /** Selection changes that are not the text's own (a tap, a handle, a key). */
    var onSelection: ((Int, Int) -> Unit)? = null

    /** Set while the model's text is put in: that text is not a user edit (its selection ends as the model's). */
    var applying = false

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        onSelection?.invoke(selStart, selEnd)
    }
}

/**
 * The body field. [text] and [selection] are the model's; they are put in when [revision] changes (a model edit: a
 * format, an insert, a draft that loads), never on the field's own typing. [onChange] gets every edit and selection
 * change. The focused EditText keeps its caret, and a selection handle being dragged, in view itself: it asks its
 * parents for that point and Compose scrolls the editor's column to it (tester report: selecting in a long draft).
 * [revealCaret] changing shows the caret once laid out, through [requester] (back from the preview, not focused).
 *
 * Tests and Compose reach the field through its semantics (set text, insert at the caret, select, focus), hidden from
 * accessibility: TalkBack reads the EditText itself.
 */
@Composable
internal fun BodyEditText(
    text: String,
    selection: TextSelection,
    revision: Int,
    enabled: Boolean,
    placeholder: String,
    revealCaret: Int,
    requester: BringIntoViewRequester,
    onChange: (String, TextSelection) -> Unit,
    onFocus: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalEcencyColors.current
    val scope = rememberCoroutineScope()
    val margin = with(LocalDensity.current) { 8.dp.toPx() }
    val changed by rememberUpdatedState(onChange)
    val focusChanged by rememberUpdatedState(onFocus)
    var view by remember { mutableStateOf<BodyEditTextView?>(null) }
    // what the semantics show: the field's text, selection and focus
    var shown by remember { mutableStateOf(text) }
    var shownSelection by remember { mutableStateOf(TextRange(selection.start, selection.end)) }
    var focused by remember { mutableStateOf(false) }

    /** Brings the line of [offset] into view, once the field has laid out its latest text. */
    fun reveal(v: BodyEditTextView, offset: Int) = v.post {
        val layout = v.layout ?: return@post
        val at = offset.coerceIn(0, v.text.length)
        val line = layout.getLineForOffset(at)
        val x = layout.getPrimaryHorizontal(at) + v.totalPaddingLeft
        val top = layout.getLineTop(line) + v.totalPaddingTop.toFloat()
        val bottom = layout.getLineBottom(line) + v.totalPaddingTop.toFloat()
        scope.launch { requester.bringIntoView(Rect(x, top - margin, x + 1f, bottom + margin)) }
    }

    AndroidView(
        factory = { context ->
            BodyEditTextView(context).apply {
                background = null
                setPadding(0, 0, 0, 0)
                gravity = Gravity.TOP or Gravity.START
                textDirection = View.TEXT_DIRECTION_FIRST_STRONG
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                isSingleLine = false
                setHorizontallyScrolling(false)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                val lineHeight = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 24f, resources.displayMetrics)
                if (Build.VERSION.SDK_INT >= 28) setLineHeight(lineHeight.toInt()) else setLineSpacing(lineHeight - textSize * 1.17f, 1f)
                applying = true
                setText(text)
                setSelection(selection.start.coerceIn(0, text.length), selection.end.coerceIn(0, text.length))
                applying = false
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                    override fun afterTextChanged(s: Editable?) {
                        val now = s?.toString().orEmpty()
                        shown = now
                        shownSelection = TextRange(selectionStart, selectionEnd)
                        if (applying) return
                        changed(now, TextSelection(minOf(selectionStart, selectionEnd), maxOf(selectionStart, selectionEnd)))
                    }
                })
                onSelection = { start, end ->
                    shownSelection = TextRange(start, end)
                    changed(this@apply.text.toString(), TextSelection(minOf(start, end), maxOf(start, end)))
                }
                setOnFocusChangeListener { _, has -> focused = has; focusChanged(has) }
                view = this
            }
        },
        // runs at every composition, so at every key: each setter only when its value changed (a set relays out or redraws)
        update = { v ->
            if (v.isEnabled != enabled) v.isEnabled = enabled
            if (v.hint?.toString() != placeholder) v.hint = placeholder
            val textColor = colors.text.toArgb()
            if (v.currentTextColor != textColor) v.setTextColor(textColor)
            val hintColor = colors.darkGray.toArgb()
            if (v.currentHintTextColor != hintColor) v.setHintTextColor(hintColor)
            val highlight = colors.blueText.copy(alpha = 0.3f).toArgb()
            if (v.highlightColor != highlight) v.highlightColor = highlight
            val cursor = colors.blueText.toArgb()
            if (Build.VERSION.SDK_INT >= 29 && v.tag != cursor) {
                v.tag = cursor
                v.textCursorDrawable = GradientDrawable().apply {
                    setColor(cursor)
                    setSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 2f, v.resources.displayMetrics).toInt(), 0)
                }
                // the selection handles in the app's blue too (the system's accent otherwise)
                v.textSelectHandle?.mutate()?.setTint(cursor)
                v.textSelectHandleLeft?.mutate()?.setTint(cursor)
                v.textSelectHandleRight?.mutate()?.setTint(cursor)
            }
        },
        onRelease = { view = null },
        modifier = modifier.semantics {
            hideFromAccessibility()
            editableText = AnnotatedString(shown)
            textSelectionRange = shownSelection
            this.focused = focused
            requestFocus { view?.requestFocus() == true }
            setText { new -> view?.let { it.setText(new.text); it.setSelection(new.text.length); true } == true }
            insertTextAtCursor { new ->
                view?.let { v ->
                    val start = minOf(v.selectionStart, v.selectionEnd).coerceAtLeast(0)
                    val end = maxOf(v.selectionStart, v.selectionEnd).coerceAtLeast(0)
                    v.text.replace(start, end, new.text)
                    true
                } == true
            }
            setSelection { start, end, _ ->
                view?.let { v ->
                    if (start < 0 || end < 0 || start > v.text.length || end > v.text.length) return@let false
                    v.setSelection(start, end)
                    true
                } == true
            }
        },
    )
    // a model edit (a format, an insert, a draft that loads): its text and selection go in; typing never comes back here
    LaunchedEffect(revision) {
        val v = view ?: return@LaunchedEffect
        val start = selection.start.coerceIn(0, text.length)
        val end = selection.end.coerceIn(0, text.length)
        v.applying = true
        if (v.text.toString() != text) v.setText(text)
        v.setSelection(start, end)
        v.applying = false
        shown = text
        shownSelection = TextRange(start, end)
    }
    LaunchedEffect(revealCaret) {
        if (revealCaret == 0) return@LaunchedEffect
        view?.let { reveal(it, it.selectionEnd) }
    }
}

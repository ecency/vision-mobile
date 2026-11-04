package com.ecency.app

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.ecency.app.render.LocalSpeakEngine
import com.ecency.app.render.PostActions
import com.ecency.app.render.PostBody
import com.ecency.app.render.SpeakEngine
import com.ecency.app.render.SpeakStream
import com.ecency.app.render.bodyDirection
import com.ecency.app.ui.BalanceChart
import com.ecency.app.ui.EcencyTheme
import com.ecency.app.ui.NumericKeyboard
import com.ecency.app.ui.PinPadActions
import com.ecency.app.ui.ltrFieldStyle
import com.ecency.app.ui.shownDraftDate
import com.ecency.core.app.ChartPoint
import com.ecency.core.editor.EditorMeta
import com.ecency.core.l10n.Bidi
import com.ecency.core.l10n.L10n
import com.ecency.core.l10n.LocaleFormat
import com.ecency.core.render.blocks.Block
import com.ecency.core.render.blocks.Inline
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The RTL pass (README "Localization", RTL): what keeps its own direction inside a right to left UI (post bodies by
 * their content, charts over time, the PIN keypad, media transport, LTR fields), each laid out under an RTL root.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RtlLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    @After
    fun tearDown() {
        L10n.usePseudo(null)
        L10n.setLanguage(L10n.SOURCE)
    }

    private fun show(direction: LayoutDirection, content: @Composable () -> Unit) = compose.setContent {
        EcencyTheme(dark = false) {
            CompositionLocalProvider(LocalLayoutDirection provides direction) { Box(Modifier.width(360.dp)) { content() } }
        }
    }

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    private fun textBounds(text: String): Rect = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun list(vararg items: String) = Block.ListBlock(ordered = false, start = 1, items = items.map { listOf(Block.Paragraph(listOf(Inline.Text(it)))) })

    @Test
    fun anEnglishBodyStaysLeftToRightInAnRtlUi() {
        val blocks = listOf(list("First point"))
        assertEquals(LayoutDirection.Ltr, bodyDirection(blocks))
        show(LayoutDirection.Rtl) { PostBody(blocks, 360.dp, PostActions()) }
        // the bullet sits on the left of a left to right body, whatever the UI's direction
        assertTrue(textBounds("•").left < 60f)
    }

    @Test
    fun anArabicBodyReadsRightToLeftInAnLtrUi() {
        val blocks = listOf(list("النقطة الأولى"))
        assertEquals(LayoutDirection.Rtl, bodyDirection(blocks))
        show(LayoutDirection.Ltr) { PostBody(blocks, 360.dp, PostActions()) }
        val bullet = textBounds("•")
        assertTrue("the bullet is on the right: $bullet", bullet.left > 250f)
    }

    @Test
    fun theQuoteBarIsOnTheBodysStartSide() {
        val quote = listOf(Block.Quote(listOf(Block.Paragraph(listOf(Inline.Text("اقتباس"))))))
        show(LayoutDirection.Ltr) { PostBody(quote, 360.dp, PostActions()) }
        // the quote is padded 5 from the start and 30 from the end: in a right to left body the wider gap is on the left
        val b = bounds("quote")
        assertTrue("start padding on the right: $b", b.left > 20f)
    }

    @Test
    fun theBalanceChartKeepsTimeLeftToRight() {
        val points = (0 until 40).map { ChartPoint(it.toDouble(), if (it % 10 == 0) "d$it" else "") }
        show(LayoutDirection.Rtl) { BalanceChart(points, onNearStart = {}) }
        // the y axis stays pinned on the left, as in a left to right UI
        assertEquals(0f, bounds("chartYAxis").left, 0.5f)
    }

    @Test
    fun thePinKeypadIsNotMirrored() {
        show(LayoutDirection.Rtl) { NumericKeyboard(64.dp, enabled = true, actions = PinPadActions({}, {}, {}, {}, {}), showBiometric = false) }
        assertTrue(bounds("pinKey1").left < bounds("pinKey3").left)
        // 0, then the delete key on the right, as Android's own PIN pad
        assertTrue(bounds("pinKey0").left < bounds("pinDelete").left)
    }

    @Test
    fun voicePlaybackRunsLeftToRightInARightToLeftBody() {
        val engine = SpeakEngine { _: Context, _, _ -> object : SpeakStream { override val positionSeconds = 0.0; override fun seek(seconds: Double) {}; override fun release() {} } }
        val blocks = listOf(Block.Paragraph(listOf(Inline.Text("صوت"))), Block.SpeakAudio("https://ecency.com/api/speak-audio?author=a&permlink=p", 90_000, listOf(0.2, 1.0)))
        show(LayoutDirection.Rtl) { CompositionLocalProvider(LocalSpeakEngine provides engine) { PostBody(blocks, 360.dp, PostActions()) } }
        assertTrue(bounds("speak:play").left < bounds("speak:wave").left)
    }

    @Test
    fun ltrFieldsTypeLeftToRight() {
        var direction: TextDirection? = null
        show(LayoutDirection.Rtl) {
            val style = ltrFieldStyle()
            direction = style.textDirection
            OutlinedTextField("alice", {}, textStyle = style, modifier = Modifier.testTag("field"))
        }
        compose.waitForIdle()
        assertEquals(TextDirection.Ltr, direction)
    }

    @Test
    fun appTextFollowsItsOwnFirstStrongLetter() {
        // without it Compose forces right to left on every text in an RTL layout ("@alice" would show "alice@")
        var local: TextDirection? = null
        var typography: TextDirection? = null
        show(LayoutDirection.Rtl) {
            local = androidx.compose.material3.LocalTextStyle.current.textDirection
            typography = androidx.compose.material3.MaterialTheme.typography.bodyMedium.textDirection
        }
        compose.waitForIdle()
        assertEquals(TextDirection.Content, local)
        assertEquals(TextDirection.Content, typography)
    }

    @Test
    fun uiConcatenationsIsolateOnlyInRtl() {
        assertEquals("@alice", Bidi.inUi("@alice"))
        L10n.usePseudo("ar-XB")
        assertEquals("⁨@alice⁩", Bidi.inUi("@alice"))
    }

    @Test
    fun draftDatesShowInTheAppLanguageAndLocalTime() {
        val iso = "2024-03-05T15:07:00.000Z"
        val millis = requireNotNull(EditorMeta.isoMillis(iso))
        assertEquals(LocaleFormat.dateTime(millis), shownDraftDate(iso))
        // the server's zone-less times are UTC
        assertEquals(LocaleFormat.dateTime(millis), shownDraftDate("2024-03-05T15:07:00"))
        assertEquals("not a date", shownDraftDate("not a date"))
        assertEquals(null, shownDraftDate(""))
    }
}

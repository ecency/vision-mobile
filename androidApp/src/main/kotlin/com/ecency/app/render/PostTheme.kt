package com.ecency.app.render

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Post body colors and metrics, matching the RN app's EStyleSheet theme tokens. */
@Immutable
data class PostTheme(
    val text: Color,
    val link: Color,
    val danger: Color,
    /** `$darkIconColor`: blockquote border, table header, inline code. */
    val muted: Color,
    /** `$iconColor`: chip outline. */
    val chipBorder: Color,
    val tableRow: Color,
    val tableBorder: Color,
    val placeholder: Color,
    /** `$primaryBackgroundColor`, behind the whole post. */
    val background: Color,
    /** `$borderTopColor`, the dashed outline of a hidden image. */
    val hiddenBorder: Color,
    val bodySize: TextUnit = 16.sp,
    val bodyWeight: FontWeight = FontWeight.Normal,
    val headingSizes: List<TextUnit> = listOf(24.sp, 20.sp, 18.sp, 16.sp, 15.sp, 14.sp),
    /** Heading top and bottom margins in em, react-native-render-html's user agent defaults. */
    val headingMarginsEm: List<Float> = listOf(0.67f, 0.83f, 1f, 1.33f, 1.67f, 2.33f),
    /** Line height over font size. RN Android leaves includeFontPadding on, about 1.32 for Roboto. */
    val lineHeight: Float = 1.32f,
    val paragraphSpacing: Dp = 6.dp,
    val listItemSpacing: Dp = 12.dp,
    val imageRadius: Dp = 8.dp,
) {
    companion object {
        val Light = PostTheme(
            text = Color(0xFF3C4449),
            // WCAG AA (README "Accessibility"): the screens' blueText and redText
            link = Color(0xFF2E6BD4),
            danger = Color(0xFFD32F2F),
            muted = Color(0xFFC1C5C7),
            chipBorder = Color(0xFFC1C5C7),
            tableRow = Color(0xFFF5F5F5),
            tableBorder = Color(0xFFFFFFFF),
            placeholder = Color(0xFFF6F6F6),
            background = Color(0xFFFFFFFF),
            hiddenBorder = Color(0xFFCFCFCF),
        )

        val Dark = PostTheme(
            text = Color(0xFFFCFCFC),
            link = Color(0xFF7FB0F7),
            danger = Color(0xFFFF8F8F),
            muted = Color(0xFF526D91),
            chipBorder = Color(0xFF788187),
            tableRow = Color(0xFF2E3D51),
            tableBorder = Color(0xFF1E2835),
            placeholder = Color(0xFF2E3D51),
            background = Color(0xFF1E2835),
            hiddenBorder = Color(0xFF757575),
        )
    }
}

val LocalPostTheme = staticCompositionLocalOf { PostTheme.Light }

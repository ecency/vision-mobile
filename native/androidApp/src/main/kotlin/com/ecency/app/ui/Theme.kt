package com.ecency.app.ui

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.ecency.app.render.LocalPostTheme
import com.ecency.app.render.PostTheme

/** The RN app's EStyleSheet tokens (src/themes/lightTheme.ts, darkTheme.ts) used by the screens. */
@Immutable
data class EcencyColors(
    val isDark: Boolean,
    /** `$primaryBackgroundColor` */
    val background: Color,
    /** `$primaryLightBackground`: image placeholders, card separators. */
    val lightBackground: Color,
    /** `$primaryGrayBackground`: the tab bar. */
    val grayBackground: Color,
    /** `$primaryBlack` */
    val text: Color,
    /** `$primaryDarkGray`: dates, summaries, counts. */
    val darkGray: Color,
    /** `$primaryBlue` */
    val blue: Color,
    /** `$primaryRed` */
    val red: Color,
    /** `$iconColor` */
    val icon: Color,
    /** `$borderColor`: avatar outline. */
    val border: Color,
    /** `$borderTopColor`: separators. */
    val borderTop: Color,
    /** `$white`: in dark mode this is the background color. */
    val white: Color,
    /** `$darkGrayBackground`: toasts and the in-app banner. */
    val darkGrayBackground: Color = Color(0xFF788187),
    /** `$noConnectionColor`: the offline banner. */
    val noConnection: Color = Color(0xFF788187),
    /** `$primaryDarkText`: header name and username. */
    val darkText: Color = Color(0xFF788187),
    /** The header avatar button gradient (RN headerView). */
    val headerGradient: List<Color> = listOf(Color(0xFF2D5AA0), Color(0xFF357CE6)),
    /** [blue] as text, links and small icons: 4.5:1 or more on [background] and [lightBackground] (WCAG AA). */
    val blueText: Color = blue,
    /** [red] as text (errors, destructive actions): 4.5:1 or more on the backgrounds. */
    val redText: Color = red,
) {
    companion object {
        val Light = EcencyColors(
            isDark = false,
            background = Color(0xFFFFFFFF),
            lightBackground = Color(0xFFF6F6F6),
            grayBackground = Color(0xFFF5F5F5),
            text = Color(0xFF3C4449),
            // README "Accessibility": RN's #788187, #357CE6, #E63535 and #C1C5C7 fall short of WCAG AA (text 4.5:1,
            // icons 3:1) on white; these keep their hue a little darker
            darkGray = Color(0xFF67717A),
            blue = Color(0xFF2E6BD4),
            red = Color(0xFFD32F2F),
            icon = Color(0xFF868F95),
            border = Color(0xFFC5C5C5),
            borderTop = Color(0xFFCFCFCF),
            white = Color(0xFFFFFFFF),
            darkText = Color(0xFF67717A),
        )

        val Dark = EcencyColors(
            isDark = true,
            background = Color(0xFF1E2835),
            lightBackground = Color(0xFF2E3D51),
            // the RN tab bar reads lighter than the page in dark mode
            grayBackground = Color(0xFF2E3D51),
            text = Color(0xFFFCFCFC),
            darkGray = Color(0xFFFCFCFC),
            // fills keep a blue and red that white text reads on; text, links and icons use the lighter variants
            blue = Color(0xFF2E6BD4),
            red = Color(0xFFD32F2F),
            icon = Color(0xFF8C959B),
            border = Color(0xFFC5C5C5),
            borderTop = Color(0xFF757575),
            white = Color(0xFF1E2835),
            darkGrayBackground = Color(0xFF526D91),
            noConnection = Color(0xFF788187),
            darkText = Color(0xFFFCFCFC),
            headerGradient = listOf(Color(0xFF081C36), Color(0xFF43638E)),
            blueText = Color(0xFF7FB0F7),
            redText = Color(0xFFFF8F8F),
        )
    }
}

/** Text on the dark scheme's light blue primary (a Material fill left to its defaults): 4.5:1 or more. */
internal val DarkOnPrimary = Color(0xFF0B1A2E)

val LocalEcencyColors = staticCompositionLocalOf { EcencyColors.Light }

/** Material colors, the screen tokens and the post body theme, light or dark from the system. */
@Composable
fun EcencyTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (dark) EcencyColors.Dark else EcencyColors.Light
    val scheme = if (dark) {
        darkColorScheme(
            // TextButtons and other Material text take the primary: the readable blue on dark backgrounds
            primary = colors.blueText, onPrimary = DarkOnPrimary, background = colors.background, surface = colors.background,
            onBackground = colors.text, onSurface = colors.text, surfaceContainerLow = colors.background,
            surfaceContainer = colors.lightBackground, surfaceContainerHigh = colors.lightBackground,
        )
    } else {
        lightColorScheme(
            primary = colors.blue, background = colors.background, surface = colors.background,
            onBackground = colors.text, onSurface = colors.text, surfaceContainerLow = colors.background,
            surfaceContainer = colors.background, surfaceContainerHigh = colors.background,
        )
    }
    MaterialTheme(colorScheme = scheme, typography = ContentDirectedTypography) {
        CompositionLocalProvider(
            LocalTextStyle provides LocalTextStyle.current.copy(textDirection = TextDirection.Content),
            LocalEcencyColors provides colors,
            LocalPostTheme provides if (dark) PostTheme.Dark else PostTheme.Light,
            content = content,
        )
    }
}

/**
 * Text takes the direction of its own first strong letter. Compose otherwise lays out text with no direction set in
 * the layout's direction, so in a right to left UI "@alice" reads "alice@" and "1.000 HIVE" reads "HIVE 1.000";
 * `Content` keeps Latin usernames, amounts and URLs left to right and Arabic copy right to left (README
 * "Localization", RTL). A field or text that must stay left to right whatever it holds uses [ltrFieldStyle].
 */
private val ContentDirectedTypography: Typography = Typography().let { t ->
    fun TextStyle.content() = copy(textDirection = TextDirection.Content)
    Typography(
        displayLarge = t.displayLarge.content(), displayMedium = t.displayMedium.content(), displaySmall = t.displaySmall.content(),
        headlineLarge = t.headlineLarge.content(), headlineMedium = t.headlineMedium.content(), headlineSmall = t.headlineSmall.content(),
        titleLarge = t.titleLarge.content(), titleMedium = t.titleMedium.content(), titleSmall = t.titleSmall.content(),
        bodyLarge = t.bodyLarge.content(), bodyMedium = t.bodyMedium.content(), bodySmall = t.bodySmall.content(),
        labelLarge = t.labelLarge.content(), labelMedium = t.labelMedium.content(), labelSmall = t.labelSmall.content(),
    )
}

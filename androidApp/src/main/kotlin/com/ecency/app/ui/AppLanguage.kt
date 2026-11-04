package com.ecency.app.ui

import android.content.Context
import android.os.Build
import android.os.LocaleList
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.ecency.core.l10n.L10n
import com.ecency.core.l10n.SystemLanguage

/**
 * The app language ([L10n.language]) in the Compose tree. Static on purpose: copy reads the catalog directly
 * (`L.settings.language`), not this value, so a change must recompose everything below the root. A static local
 * does that without dropping any remembered state (scroll positions, typed text, open sheets).
 */
val LocalAppLanguage = staticCompositionLocalOf { L10n.SOURCE }

/**
 * Root of the localized UI: the language and its layout direction (right to left for ar, fa and he; ku-TR is
 * Latin script and stays left to right) for [content], switching live when the language changes (picker, system
 * settings), without recreating the activity (the manifest handles `locale|layoutDirection`).
 */
@Composable
fun AppLanguageRoot(content: @Composable () -> Unit) {
    val language by L10n.language.collectAsState()
    CompositionLocalProvider(
        LocalAppLanguage provides language,
        LocalLayoutDirection provides if (L10n.isRtl(language)) LayoutDirection.Rtl else LayoutDirection.Ltr,
        content = content,
    )
}

/**
 * For the content of a separate window (Dialog, ModalBottomSheet, Popup, DropdownMenu): such a window composes on
 * its own, and a change of the root's static local does not reach the copy inside it. Observing the language here and
 * providing it again recomposes the window's content like the root does (the window inherits the direction).
 */
@Composable
fun FollowAppLanguage(content: @Composable () -> Unit) {
    val language by L10n.language.collectAsState()
    CompositionLocalProvider(LocalAppLanguage provides language, content = content)
}

/** Android 13+ per-app language (Settings > Apps > Ecency > Language), synced by the shared settings. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class AndroidSystemLanguage(context: Context) : SystemLanguage {
    private val locales = context.applicationContext.getSystemService(android.app.LocaleManager::class.java)

    override fun read(): String = locales.applicationLocales.let { if (it.isEmpty) "" else it[0].toLanguageTag() }

    override fun write(tag: String) {
        locales.applicationLocales = LocaleList.forLanguageTags(tag)
    }

    override fun deviceLanguage(): String? = locales.systemLocales.let { if (it.isEmpty) null else it[0].toLanguageTag() }
}

package com.ecency.app.widgets

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.ecency.app.MainActivity
import com.ecency.app.ui.drawableRes
import com.ecency.app.ui.filledDrawableRes
import com.ecency.core.app.AppIcon
import com.ecency.core.app.AppShortcuts
import com.ecency.core.app.WidgetSnapshot
import com.ecency.core.app.WavesMark
import com.ecency.core.app.WidgetStore
import com.ecency.core.app.SettingsModel
import com.ecency.core.app.SettingsOptions
import com.ecency.core.l10n.L
import com.ecency.core.l10n.L10n
import com.ecency.core.l10n.LocaleFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The home screen widgets (README "Widgets"): unread notifications, the daily quest streak, and quick Write / New
 * wave. They read ONLY the snapshot the app writes ([WidgetSnapshot], no keys, tokens or session codes) and never
 * reach the network; a tap opens an `ecency://` shortcut, which the app routes like any link (accounts read first,
 * behind the app lock, login gated). Android has no lock screen widgets on phones (only on some tablets and in hub
 * mode on recent releases, through `keyguard` in the provider info), so these are home screen widgets.
 *
 * Their text is the app's catalog in the app language (README "Localization"): read when a widget is drawn, and every
 * widget is drawn again when the language changes ([com.ecency.app.SystemLanguageSurfaces]). The picker labels and
 * descriptions are generated resources (`widget_*_label`, `widget_*_description`).
 */
object WidgetPrefs {
    private const val FILE = "ecency.widgets"

    fun read(context: Context): WidgetSnapshot =
        WidgetSnapshot.decode(context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(WidgetSnapshot.KEY, null))

    fun write(context: Context, snapshot: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(WidgetSnapshot.KEY, snapshot).apply()
    }
}

/**
 * The language a widget draws in. With the app running, the catalog already follows the setting; a widget drawn in a
 * process the app has not started in (the system asked for an update) reads the saved language itself, else the
 * device's, as the app's first start would pick it.
 */
internal object WidgetLanguage {
    fun ensure(context: Context, appDrivesCatalog: Boolean = com.ecency.app.EcencyGraph.current()?.drivesLanguageCatalog == true) {
        if (appDrivesCatalog) return
        val saved = runCatching { context.getSharedPreferences(com.ecency.app.EcencyGraph.PREFS_FILE, Context.MODE_PRIVATE).getString(SettingsModel.KEY_LANGUAGE, null) }.getOrNull()
        L10n.setLanguage(saved ?: SettingsOptions.registeredLocale(java.util.Locale.getDefault().toLanguageTag()) ?: L10n.SOURCE)
    }
}

/** Draws every placed widget again (a new snapshot, or the app language changed). */
internal suspend fun redrawWidgets(context: Context, compose: Boolean = true) {
    runCatching { NotificationsWidget().updateAll(context) }
    runCatching { StreakWidget().updateAll(context) }
    if (compose) runCatching { ComposeWidget().updateAll(context) }
}

/** The app's side: writes the snapshot and redraws every placed widget. */
class AndroidWidgetStore(context: Context) : WidgetStore {
    private val app = context.applicationContext ?: context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun write(snapshot: String) {
        WidgetPrefs.write(app, snapshot)
        scope.launch { redrawWidgets(app, compose = false) }
    }
}

internal object WidgetLook {
    val background = ColorProvider(day = Color(0xFFFFFFFF), night = Color(0xFF1E2835))
    val text = ColorProvider(day = Color(0xFF1B1B1F), night = Color(0xFFF2F4F7))
    val secondary = ColorProvider(day = Color(0xFF788187), night = Color(0xFF9FA8B3))
    val accent = ColorProvider(day = Color(0xFF357CE6), night = Color(0xFF5D9BFF))
    val onAccent = ColorProvider(day = Color(0xFFFFFFFF), night = Color(0xFFFFFFFF))

    /** The streak flame (RN orange). */
    val flame = ColorProvider(day = Color(0xFFF57C00), night = Color(0xFFF57C00))

    /**
     * An app icon for a widget: the filled Material Symbols drawable of [icon] (widgets show status marks filled),
     * tinted; the Waves mark is drawn from the shared [WavesMark] path into a bitmap (Glance takes no vector paths).
     */
    fun icon(icon: AppIcon): ImageProvider =
        (icon.filledDrawableRes() ?: icon.drawableRes())?.let { ImageProvider(it) } ?: ImageProvider(wavesBitmap(96))

    private fun wavesBitmap(px: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val s = px / WavesMark.GRID
        val path = Path().apply {
            moveTo(WavesMark.START[0] * s, WavesMark.START[1] * s)
            WavesMark.CURVES.forEach { c -> cubicTo(c[0] * s, c[1] * s, c[2] * s, c[3] * s, c[4] * s, c[5] * s) }
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = WavesMark.STROKE * s; strokeCap = Paint.Cap.ROUND; color = android.graphics.Color.BLACK
        }
        Canvas(bitmap).drawPath(path, paint)
        return bitmap
    }

    /** The shortcut [link], opened in the app's activity (never another app). */
    fun open(context: Context, link: String) = actionStartActivity(
        Intent(Intent.ACTION_VIEW, Uri.parse(link), context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

/** What a value widget shows for the snapshot: the number line, the caption, or the locked / signed-out text. */
internal data class ValueLines(val value: String?, val caption: String)

internal fun unreadLines(s: WidgetSnapshot): ValueLines {
    val n = s.unread
    return when {
        !s.signedIn -> ValueLines(null, L.native.widgets.signedOutText)
        s.locked -> ValueLines(null, L.native.widgets.lockedText)
        n == null -> ValueLines(null, L.notification.notification)
        else -> ValueLines(LocaleFormat.integer(n.toLong()), L.native.widget.unreadCaption(count = n))
    }
}

internal fun streakLines(s: WidgetSnapshot): ValueLines {
    val n = s.streak
    return when {
        !s.signedIn -> ValueLines(null, L.native.widgets.signedOutText)
        s.locked -> ValueLines(null, L.native.widgets.lockedText)
        n == null -> ValueLines(null, L.native.widget.streakHint)
        else -> ValueLines(LocaleFormat.integer(n.toLong()), L.native.widget.streakCaption(count = n))
    }
}

/** The quick actions' labels: Write and New wave. */
internal fun composeLabels(): Pair<String, String> = L.native.widget.write to L.native.widget.newWave

@Composable
private fun ValueWidget(context: Context, icon: AppIcon, tint: androidx.glance.unit.ColorProvider, title: String, lines: ValueLines, link: String) {
    Column(
        modifier = GlanceModifier.fillMaxSize().background(WidgetLook.background).cornerRadius(20.dp).padding(14.dp)
            .clickable(WidgetLook.open(context, link))
            .semantics { contentDescription = listOfNotNull(title, lines.value, lines.caption).joinToString(", ") },
        verticalAlignment = Alignment.Top,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(WidgetLook.icon(icon), contentDescription = null, modifier = GlanceModifier.size(20.dp), colorFilter = ColorFilter.tint(tint))
            Spacer(GlanceModifier.width(6.dp))
            Text(title, style = TextStyle(color = WidgetLook.secondary, fontSize = 13.sp, fontWeight = FontWeight.Medium))
        }
        Spacer(GlanceModifier.height(6.dp))
        if (lines.value != null) {
            Text(lines.value, style = TextStyle(color = WidgetLook.text, fontSize = 30.sp, fontWeight = FontWeight.Bold))
        }
        Text(lines.caption, style = TextStyle(color = if (lines.value != null) WidgetLook.secondary else WidgetLook.text, fontSize = 13.sp), maxLines = 2)
    }
}

/** Unread notifications; opens the Notifications tab. Hidden while the PIN lock is on and after a logout. */
class NotificationsWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        WidgetLanguage.ensure(context)
        val lines = unreadLines(WidgetPrefs.read(context))
        // the brand is the title in every language
        provideContent { ValueWidget(context, AppIcon.NOTIFICATIONS, WidgetLook.accent, "Ecency", lines, AppShortcuts.NOTIFICATIONS) }
    }
}

/** The daily quest streak as Perks last read it; opens Perks. Hidden while the PIN lock is on and after a logout. */
class StreakWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        WidgetLanguage.ensure(context)
        val lines = streakLines(WidgetPrefs.read(context))
        val title = L.native.widget.streakTitle
        provideContent { ValueWidget(context, AppIcon.BOOST, WidgetLook.flame, title, lines, AppShortcuts.PERKS) }
    }
}

/** Quick actions: Write (a new post) and New wave. Shows no data. */
class ComposeWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        WidgetLanguage.ensure(context)
        val (write, newWave) = composeLabels()
        provideContent {
            Row(
                modifier = GlanceModifier.fillMaxSize().background(WidgetLook.background).cornerRadius(20.dp).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ActionButton(context, AppIcon.EDIT, write, AppShortcuts.WRITE)
                Spacer(GlanceModifier.width(10.dp))
                ActionButton(context, AppIcon.WAVES, newWave, AppShortcuts.NEW_WAVE)
            }
        }
    }

    @Composable
    private fun ActionButton(context: Context, icon: AppIcon, label: String, link: String) {
        Column(
            modifier = GlanceModifier.background(WidgetLook.accent).cornerRadius(16.dp).padding(horizontal = 14.dp, vertical = 10.dp)
                .clickable(WidgetLook.open(context, link)).semantics { contentDescription = label },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(WidgetLook.icon(icon), contentDescription = null, modifier = GlanceModifier.size(22.dp), colorFilter = ColorFilter.tint(WidgetLook.onAccent))
            Text(label, style = TextStyle(color = WidgetLook.onAccent, fontSize = 12.sp, fontWeight = FontWeight.Medium))
        }
    }
}

class NotificationsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NotificationsWidget()
}

class StreakWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StreakWidget()
}

class ComposeWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ComposeWidget()
}

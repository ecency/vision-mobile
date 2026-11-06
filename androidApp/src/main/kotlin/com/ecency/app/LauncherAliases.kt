package com.ecency.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import com.ecency.core.app.AppEnvironment
import com.ecency.core.app.CalendarDay
import com.ecency.core.app.LauncherIcon
import java.util.Calendar
import java.util.Date
import java.util.GregorianCalendar

/**
 * The launcher icon (README "App icons"): one `<activity-alias>` of MainActivity per [LauncherIcon]
 * (`com.ecency.app.Launcher<key>`, written by scripts/icons/seasonal_icons.py), exactly one enabled. MainActivity has
 * no launcher entry of its own and keeps every link, share and widget intent, so switching never changes where those go.
 */
object LauncherAliases {
    fun component(context: Context, icon: LauncherIcon) = ComponentName(context.packageName, "com.ecency.app.Launcher${icon.key}")

    /**
     * What a notification opens: MainActivity itself. The package's launch intent names the alias enabled at the time,
     * which a later switch disables, so a tap on an older notification would open nothing.
     */
    fun openApp(context: Context): Intent =
        Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

    /** The icons whose alias is enabled now (one, unless a switch was cut short). */
    fun enabled(context: Context): List<LauncherIcon> = LauncherIcon.entries.filter { isEnabled(context.packageManager, it, component(context, it)) }

    /**
     * Enables [icon]'s alias, then disables the others (the launcher never sees the app without an entry). Only
     * changed components are written, with DONT_KILL_APP; call it when the app goes to the background, as some
     * launchers rebuild their entry for the app on a change.
     */
    fun apply(context: Context, icon: LauncherIcon) {
        val pm = context.packageManager
        val ordered = listOf(icon) + (LauncherIcon.entries - icon)
        for (each in ordered) {
            val name = component(context, each)
            val on = each == icon
            if (isEnabled(pm, each, name) == on) continue
            val state = when {
                // the manifest's own state: Default is enabled there, every other alias disabled
                on == (each == LauncherIcon.DEFAULT) -> PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                on -> PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else -> PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            pm.setComponentEnabledSetting(name, state, PackageManager.DONT_KILL_APP)
        }
    }

    /** The phone's own region and language (not the app's language) and switches on by default: Android asks nothing. */
    fun configure(env: AppEnvironment) {
        val locale = Resources.getSystem().configuration.locales.get(0)
        env.launcherIcons.configure(locale?.country?.takeIf { it.isNotEmpty() }, locale?.language, asksFirst = false)
    }

    /**
     * Today in the phone's time zone, as a Gregorian date: the celebration table is Gregorian, and
     * `Calendar.getInstance()` follows the locale's calendar (Buddhist on a Thai phone, the year 2569).
     */
    fun today(now: Date = Date()): CalendarDay = GregorianCalendar().apply { time = now }
        .let { CalendarDay(it.get(Calendar.YEAR), it.get(Calendar.MONTH) + 1, it.get(Calendar.DAY_OF_MONTH)) }

    private fun isEnabled(pm: PackageManager, icon: LauncherIcon, name: ComponentName): Boolean = when (pm.getComponentEnabledSetting(name)) {
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
        PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> icon == LauncherIcon.DEFAULT
        else -> false
    }
}

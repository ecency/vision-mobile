package com.ecency.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.ecency.core.app.LauncherIcon
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Issue #3656: one launcher alias per app icon, exactly one enabled, switched only once the app is in the background,
 * while links and shares keep opening MainActivity (README "App icons").
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LauncherAliasesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val node = TestNode()
    private val env by lazy { node.env() }

    @Before
    fun setUp() {
        acceptTerms()
        EcencyGraph.install(env)
    }

    @After
    fun tearDown() = EcencyGraph.install(null)

    private fun idle() = shadowOf(android.os.Looper.getMainLooper()).idle()

    /** Review of #3659: a Thai phone (Buddhist calendar, the year 2570) still gets the Gregorian day the table uses. */
    @Test
    fun todayIsTheGregorianDayOnAThaiPhone() {
        val locale = java.util.Locale.getDefault()
        val zone = java.util.TimeZone.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("th-TH"))
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Bangkok"))
            // 2027-03-20 22:30 UTC is Mar 21 in Bangkok
            assertEquals(com.ecency.core.app.CalendarDay(2027, 3, 21), LauncherAliases.today(java.util.Date(1805581800L * 1000)))
        } finally {
            java.util.Locale.setDefault(locale)
            java.util.TimeZone.setDefault(zone)
        }
    }

    /** The launcher entries the system lists for the app now. */
    private fun launcherEntries(): List<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName)
        return context.packageManager.queryIntentActivities(intent, 0).map { it.activityInfo.name }
    }

    private fun handler(intent: Intent): List<String> =
        context.packageManager.queryIntentActivities(intent.setPackage(context.packageName), 0).map { it.activityInfo.name }

    @Test
    fun everyIconHasAnAliasOfTheMainActivityAndOnlyTheEverydayOneStartsEnabled() {
        for (icon in LauncherIcon.entries) {
            val info = runCatching {
                context.packageManager.getActivityInfo(LauncherAliases.component(context, icon), PackageManager.MATCH_DISABLED_COMPONENTS)
            }.getOrNull()
            assertNotNull("an alias for $icon", info)
            assertEquals("$icon opens MainActivity", MainActivity::class.java.name, info!!.targetActivity)
        }
        assertEquals(listOf(LauncherIcon.DEFAULT), LauncherAliases.enabled(context))
        assertEquals("one launcher entry, and not MainActivity itself", listOf("com.ecency.app.LauncherDefault"), launcherEntries())
    }

    @Test
    fun theIconSwitchesOnlyWhenTheAppGoesToTheBackgroundAndExactlyOneAliasIsEnabled() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        idle()
        env.launcherIcons.pick(LauncherIcon.HIVE_DAY)
        idle()
        assertEquals("nothing changes while the app is in front", listOf(LauncherIcon.DEFAULT), LauncherAliases.enabled(context))

        scenario.moveToState(Lifecycle.State.CREATED)
        idle()
        assertEquals(listOf(LauncherIcon.HIVE_DAY), LauncherAliases.enabled(context))
        assertEquals(listOf("com.ecency.app.LauncherHiveDay"), launcherEntries())

        scenario.moveToState(Lifecycle.State.RESUMED)
        env.launcherIcons.pick(LauncherIcon.DEFAULT)
        idle()
        assertEquals(listOf(LauncherIcon.HIVE_DAY), LauncherAliases.enabled(context))
        scenario.moveToState(Lifecycle.State.CREATED)
        idle()
        assertEquals(listOf(LauncherIcon.DEFAULT), LauncherAliases.enabled(context))
        scenario.close()
    }

    @Test
    fun linksSharesAndAppLinksStillOpenTheMainActivityWhateverIconIsOn() {
        for (icon in listOf(LauncherIcon.DEFAULT, LauncherIcon.CHRISTMAS)) {
            LauncherAliases.apply(context, icon)
            val main = MainActivity::class.java.name
            for (url in listOf("ecency://auth-transfer", "hive://sign/op/abc", "https://ecency.com/@alice/post", "https://ecency.com/trending", "https://ecency.com/signup")) {
                val view = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
                assertEquals("$url with $icon", listOf(main), handler(view))
            }
            val share = Intent(Intent.ACTION_SEND).setType("text/plain")
            assertEquals(listOf(main), handler(share))
            assertTrue(LauncherAliases.enabled(context) == listOf(icon))
        }
    }

    /** Review of #3656: a picker, the camera, a Custom Tab or the share sheet in front is not the app leaving. */
    @Test
    fun aScreenTheAppOpensInFrontIsNotTheAppLeaving() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        idle()
        env.launcherIcons.pick(LauncherIcon.HIVE_DAY)
        idle()
        scenario.onActivity { it.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.org"))) }
        scenario.moveToState(Lifecycle.State.CREATED)
        idle()
        assertEquals("the app opened that screen itself", listOf(LauncherIcon.DEFAULT), LauncherAliases.enabled(context))
        // back in front, then really left: now it switches
        scenario.moveToState(Lifecycle.State.RESUMED)
        scenario.moveToState(Lifecycle.State.CREATED)
        idle()
        assertEquals(listOf(LauncherIcon.HIVE_DAY), LauncherAliases.enabled(context))
        scenario.close()
    }

    /** Review of #3656: a notification that opens the app names MainActivity, never the alias enabled when it was posted. */
    @Test
    fun noNotificationOpensTheAppThroughAnAlias() {
        val src = java.io.File("src/main/kotlin")
        val offenders = src.walkTopDown().filter { it.extension == "kt" && "getLaunchIntentForPackage" in it.readText() }.map { it.name }.toList()
        assertEquals("the launch intent names whichever alias is on, and a switch disables it", emptyList<String>(), offenders)
        val open = LauncherAliases.openApp(context)
        assertEquals(MainActivity::class.java.name, open.component?.className)
    }

    /**
     * Review of #3656: every alias ever shipped stays in the manifest. Android forgets the state of a removed component,
     * so removing the one a phone has enabled would leave the app with no launcher entry (Default is disabled there).
     * Retire an icon by keeping its alias with the default artwork; add new names here.
     */
    @Test
    fun everyAliasEverShippedIsStillThere() {
        val shipped = listOf(
            "Default", "NewYear", "LunarNewYear", "Valentines", "RamadanEid", "HiveDay", "Nowruz", "Holi", "EarthDay", "PizzaDay",
            "Football", "SummerGames", "ProgrammersDay", "MidAutumn", "Halloween", "Diwali", "BlackFriday", "Christmas",
        )
        for (key in shipped) {
            val info = runCatching {
                context.packageManager.getActivityInfo(android.content.ComponentName(context.packageName, "com.ecency.app.Launcher$key"), PackageManager.MATCH_DISABLED_COMPONENTS)
            }.getOrNull()
            assertNotNull("Launcher$key was shipped and must stay", info)
        }
    }
}

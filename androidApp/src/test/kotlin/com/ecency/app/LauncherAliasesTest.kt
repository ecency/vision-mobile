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
}

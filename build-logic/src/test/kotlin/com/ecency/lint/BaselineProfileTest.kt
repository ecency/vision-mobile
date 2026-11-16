package com.ecency.lint

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The release Baseline Profile (#3702, docs/release-builds.md "Baseline Profile"): committed, covering the app's own
 * code, used by release builds without generating it there, installed by profileinstaller off Play, and its journeys
 * able to find the app's screens (test tags as resource ids).
 */
class BaselineProfileTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { it.resolve("src/config/locales/en-US.json").isFile }

    @Test
    fun theCommittedProfileCoversTheAppsStartFeedAndPost() {
        val profile = root.resolve("androidApp/src/release/generated/baselineProfiles/baseline-prof.txt")
        assertTrue(profile.isFile, "the release Baseline Profile is committed at $profile")
        val rules = profile.readLines()
        assertTrue(rules.size > 10_000, "a full journey, not a stub: ${rules.size} rules")
        for (screen in listOf("com/ecency/app/MainActivity", "com/ecency/app/ui/FeedScreenKt", "com/ecency/app/ui/PostScreenKt", "com/ecency/core/app/FeedModel")) {
            assertTrue(rules.any { screen in it }, "the profile has no rule for $screen: generate it again")
        }
    }

    @Test
    fun releaseBuildsUseItWithoutGeneratingIt() {
        val gradle = root.resolve("androidApp/build.gradle.kts").readText()
        assertTrue("alias(libs.plugins.androidx.baselineprofile)" in gradle, "androidApp applies the baselineprofile plugin")
        assertTrue(Regex("""automaticGenerationDuringBuild\s*=\s*false""").containsMatchIn(gradle), "a release build never runs the generator")
        assertTrue("baselineProfile(project(\":baselineprofile\"))" in gradle, "the generator module is the profile's producer")
        assertTrue("implementation(libs.androidx.profileinstaller)" in gradle, "profileinstaller installs it where Play did not")
    }

    @Test
    fun theProfileBuildsNeverCarryTheRnSecrets() {
        val gradle = root.resolve("androidApp/build.gradle.kts").readText()
        assertTrue(Regex("""profileBuild\s*->\s*providers\.provider\s*\{\s*s\.dummy\s*}""").containsMatchIn(gradle),
            "benchmarkRelease and nonMinifiedRelease (installed on test devices, never shipped) get the dummy secrets")
    }

    @Test
    fun theJourneysCanFindTheAppsScreens() {
        val app = root.resolve("androidApp/src/main/kotlin/com/ecency/app/ui/EcencyApp.kt").readText()
        assertTrue("testTagsAsResourceId = true" in app, "the generator finds screens by their test tags as resource ids")
        // every tag the journeys look for is one the app sets (a prefix for a pattern like "card:.*")
        val journeys = root.resolve("baselineprofile/src/main/kotlin/com/ecency/baselineprofile/Journeys.kt").readText()
        val wanted = Regex("""By\.res\("([^"]+)"\)""").findAll(journeys).map { it.groupValues[1] }.filter { it != "welcome" }.toList() +
            Regex("""By\.res\(Pattern\.compile\("([a-zA-Z]+:)\.\*"\)\)""").findAll(journeys).map { it.groupValues[1] }.toList()
        val ui = root.resolve("androidApp/src/main/kotlin").walk().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        // a tag built from a template ("bottomTab:${tab.name}") is matched by its literal prefix; Skeleton takes its tag
        // as an argument (tag = "skeleton:body")
        // a literal tag must be set in full: "wave:header" is not found by another "wave:" tag
        val missing = wanted.distinct().filter { tag ->
            val exact = "testTag(\"$tag\")" in ui || "tag = \"$tag\"" in ui
            val template = ':' in tag && "testTag(\"${tag.substringBefore(':')}:\${" in ui
            !exact && !template
        }
        assertTrue(missing.isEmpty(), "Journeys.kt looks for tags the app no longer sets: $missing")
        assertTrue(wanted.size >= 6, "the journeys' tags were read: ${wanted.toList()}")
    }

    @Test
    fun theJourneysWaitForLoadedContentAndTheScrollBenchmarkMeasuresOnlyTheScroll() {
        val journeys = root.resolve("baselineprofile/src/main/kotlin/com/ecency/baselineprofile/Journeys.kt").readText()
        // a list's container is there while it shows a skeleton or an error: wait for what only loaded content has
        assertTrue("Until.gone(By.res(\"skeleton:body\"))" in journeys, "the post journey waits for the rendered body, not postList")
        assertTrue("By.res(\"wave:header\")" in journeys, "the Waves journey waits for a loaded wave, not wavesList")
        val unchecked = journeys.lines().filter { ("Until.hasObject(" in it || "Until.gone(" in it) && "|| error(" !in it }
        assertTrue(unchecked.isEmpty(), "a wait that times out must fail the journey, not scroll an error screen: $unchecked")
        // only the system's notification question may be absent (asked once, Android 13+)
        val skipped = journeys.lines().filter { "Until.findObject(" in it && "?: error(" !in it && "DENY" !in it }
        assertTrue(skipped.isEmpty(), "a button the journey needs must fail it when missing, not be skipped: $skipped")
        val fling = journeys.substringAfter("fun MacrobenchmarkScope.flingHomeFeed()").substringBefore("\n}")
        val down = Regex("""repeat\((\d+)\) \{ feed\.fling\(Direction\.DOWN\) }""").find(fling)?.groupValues?.get(1)
        val up = Regex("""repeat\((\d+)\) \{ feed\.fling\(Direction\.UP\) }""").find(fling)?.groupValues?.get(1)
        assertTrue(down != null && down == up, "each measured scroll goes back to where it started: down $down, up $up")
        val bench = root.resolve("baselineprofile/src/main/kotlin/com/ecency/baselineprofile/StartupBenchmarks.kt").readText()
        val scroll = bench.substringAfter("private fun scroll(")
        assertTrue("awaitHomeFeed()" in scroll.substringAfter("setupBlock").substringBefore("},"), "the feed loads in the setup, outside the measured frames")
        assertTrue(Regex("""\) \{\s*flingHomeFeed\(\)\s*}""").containsMatchIn(scroll), "only the flings are measured")
    }
}

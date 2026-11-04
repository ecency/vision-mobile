package com.ecency.app

import androidx.lifecycle.SavedStateHandle
import com.ecency.app.ui.NavigationViewModel
import com.ecency.core.app.AppScreen
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The saved navigation and the account it belongs to (editor and drafts screens are per account). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Wave3NavigationTest {
    private val post = AppScreen.Post("spike-bob", "their-post")
    private val gated = listOf(AppScreen.Editor.of("draft:d1"), AppScreen.Editor.of("reply:spike-bob/their-post"), AppScreen.Drafts(1))

    /** What survives process death: the handle's values only. */
    private fun saved(by: String?): SavedStateHandle {
        val handle = SavedStateHandle()
        val navigator = NavigationViewModel(handle).navigator
        navigator.accountIs(by)
        navigator.push(post)
        gated.forEach { navigator.push(it) }
        return handle
    }

    private fun restored(from: SavedStateHandle) =
        NavigationViewModel(SavedStateHandle(from.keys().associateWith { from.get<Any?>(it) })).navigator

    @Test
    fun aRestoredEditorOrDraftsScreenComesBackOnlyForTheAccountThatOpenedIt() {
        val handle = saved(by = "spike-alice")

        val same = restored(handle)
        assertEquals("until the accounts are read nothing is decided", listOf(post) + gated, same.screens)
        same.accountIs("spike-alice")
        assertEquals(listOf(post) + gated, same.screens)

        val other = restored(handle)
        other.accountIs("spike-carol")
        assertEquals("another account never sees the previous one's editor or drafts", listOf<AppScreen>(post), other.screens)

        val nobody = restored(handle)
        nobody.accountIs(null)
        assertEquals(listOf<AppScreen>(post), nobody.screens)
    }

    @Test
    fun aSavedNavigationWithoutAnOwnerIsNotTrustedWithGatedScreens() {
        val handle = saved(by = "spike-alice")
        handle.remove<String>(NavigationViewModel.KEY_OWNER)
        val navigator = restored(handle)
        navigator.accountIs("spike-alice")
        assertEquals(listOf<AppScreen>(post), navigator.screens)
    }

    @Test
    fun anAccountChangeWhileRunningDropsGatedScreensAndTheNewOwnerIsSaved() {
        val handle = SavedStateHandle()
        val navigator = NavigationViewModel(handle).navigator
        navigator.accountIs("spike-alice")
        navigator.push(post)
        gated.forEach { navigator.push(it) }
        navigator.accountIs("spike-alice") // the same account again (a recomposition): nothing changes
        assertEquals(listOf(post) + gated, navigator.screens)
        navigator.accountIs("spike-carol") // a switch, or a link login as someone else
        assertEquals(listOf<AppScreen>(post), navigator.screens)
        assertEquals("spike-carol", handle.get<String>(NavigationViewModel.KEY_OWNER))
        navigator.push(gated.first())
        navigator.accountIs(null) // signed out
        assertEquals(listOf<AppScreen>(post), navigator.screens)
    }
}

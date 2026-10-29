package com.ecency.app

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.ecency.core.EcencyCore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HomeScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun showsTheSharedCoreGreeting() {
        compose.setContent { HomeScreen() }
        compose.onNodeWithTag("greeting").assertTextContains(EcencyCore.VERSION, substring = true)
    }
}

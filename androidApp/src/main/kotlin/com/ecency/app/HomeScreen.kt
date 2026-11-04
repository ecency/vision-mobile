package com.ecency.app

import com.ecency.app.render.ProvideSpeakPlayers
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.ecency.app.render.LocalPostTheme
import com.ecency.app.render.PostActions
import com.ecency.app.render.PostTheme
import com.ecency.app.render.postBody
import com.ecency.core.EcencyCore
import com.ecency.core.render.blocks.BlockBuilder
import com.ecency.core.render.blocks.RendererSamples
import com.ecency.core.render.blocks.SampleHtml

@Composable
fun HomeScreen(modifier: Modifier = Modifier, actions: PostActions = PostActions()) {
    val theme = if (isSystemInDarkTheme()) PostTheme.Dark else PostTheme.Light
    // The demo post goes through the real pipeline: render-helper HTML in, native blocks out, plus
    // the renderer parity samples (GIF, wide table, chips) that the HTML sample does not cover.
    val blocks = remember { BlockBuilder.build(SampleHtml.POST) + RendererSamples.extras }
    Surface(modifier = modifier.fillMaxSize(), color = theme.background, contentColor = theme.text) {
        CompositionLocalProvider(LocalPostTheme provides theme) {
            BoxWithConstraints { ProvideSpeakPlayers {
                // 16dp gutters, as the RN post screen (content width = window - 32).
                val contentWidth = maxWidth - 32.dp
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding()),
                    modifier = Modifier.testTag("post"),
                ) {
                    item { Text(EcencyCore.greeting(), modifier = Modifier.padding(vertical = 8.dp).testTag("greeting")) }
                    postBody(blocks, contentWidth, actions)
                }
            }
}
        }
    }
}

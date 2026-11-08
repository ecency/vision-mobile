package com.ecency.app.ui

import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.ui.Modifier

/**
 * A control a finger drags sideways (every `Slider`): the system back gesture does not start on it. With gesture
 * navigation a slider whose thumb rests at 0% or 100% sits under the screen's left or right edge, and a drag from
 * there closed the screen (or the vote sheet) instead of moving the thumb. `SliderGestureLintTest` requires it on
 * every Slider. Android caps the excluded height per edge, so use it on the track only, never a whole screen.
 */
fun Modifier.edgeSwipeSafe(): Modifier = systemGestureExclusion()

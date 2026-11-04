package com.ecency.app.ui

import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection

/**
 * Field text that reads left to right in every language: usernames, keys, amounts, numbers, URLs and emails. In a
 * right to left UI the field keeps its place and label, while what is typed runs left to right from the left edge
 * (a key or "0.001" never reorders around the cursor). README "Localization", RTL.
 */
@Composable
fun ltrFieldStyle(base: TextStyle = LocalTextStyle.current): TextStyle = base.copy(textDirection = TextDirection.Ltr)

/**
 * Lays [content] out left to right whatever the app's direction: things that are not mirrored in a right to left
 * language (a field with an "@" before the username, media transport and scrubbers, charts over time, keypads).
 */
@Composable
fun LeftToRight(content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr, content = content)

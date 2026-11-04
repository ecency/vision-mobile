package com.ecency.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.ecency.core.app.UiCopy

/**
 * An avatar that is a button on its own (RN #1019): a screen reader announces "Profile of @name, button" instead of
 * an unlabeled tap target. Apply after the clip, in place of a bare clickable.
 */
fun Modifier.avatarButton(username: String, onClick: () -> Unit): Modifier =
    clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = UiCopy.profileOf(username) }

package com.ecency.app.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheetDefaults
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.PopupProperties

/*
 * The app's windows (dialogs, sheets, menus, popups) follow a live language switch: a separate window composes on its
 * own, so the root's language change does not reach the copy inside it. These wrappers have the Material and window
 * names and parameters the app uses and put every slot inside [FollowAppLanguage]. Screens in this package call them
 * by the plain name (a same package declaration wins over the star imports, and build-logic `UiLiteralLintTest` fails
 * on an explicit import of the Material or window version), so a new window follows the language without extra code.
 */

private fun slot(content: (@Composable () -> Unit)?): (@Composable () -> Unit)? = content?.let { c -> { FollowAppLanguage(c) } }

@Composable
fun AlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    properties: DialogProperties = DialogProperties(),
) = androidx.compose.material3.AlertDialog(
    onDismissRequest = onDismissRequest,
    confirmButton = { FollowAppLanguage(confirmButton) },
    modifier = modifier,
    dismissButton = slot(dismissButton),
    icon = slot(icon),
    title = slot(title),
    text = slot(text),
    properties = properties,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModalBottomSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(),
    containerColor: Color = BottomSheetDefaults.ContainerColor,
    properties: ModalBottomSheetProperties = ModalBottomSheetDefaults.properties,
    content: @Composable ColumnScope.() -> Unit,
) = androidx.compose.material3.ModalBottomSheet(
    onDismissRequest = onDismissRequest,
    sheetState = sheetState,
    containerColor = containerColor,
    properties = properties,
) { val column = this; FollowAppLanguage { column.content() } }

@Composable
fun DropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) = androidx.compose.material3.DropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest, modifier = modifier) {
    val column = this
    FollowAppLanguage { column.content() }
}

@Composable
fun Dialog(onDismissRequest: () -> Unit, properties: DialogProperties = DialogProperties(), content: @Composable () -> Unit) =
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismissRequest, properties = properties) { FollowAppLanguage(content) }

@Composable
fun Popup(
    alignment: Alignment = Alignment.TopStart,
    offset: IntOffset = IntOffset(0, 0),
    onDismissRequest: (() -> Unit)? = null,
    properties: PopupProperties = PopupProperties(),
    content: @Composable () -> Unit,
) = androidx.compose.ui.window.Popup(alignment, offset, onDismissRequest, properties) { FollowAppLanguage(content) }

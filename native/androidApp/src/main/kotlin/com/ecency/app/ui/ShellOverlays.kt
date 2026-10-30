package com.ecency.app.ui

import androidx.compose.ui.semantics.Role

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.ecency.core.app.SideMenu
import com.ecency.core.app.UiMessage
import kotlinx.coroutines.delay
import com.ecency.core.app.UiCopy

/** RN ToastNotification: a dark gray pill 100 dp above the bottom, white bold text, gone after 4 s. */
@Composable
fun ToastView(toast: UiMessage.Toast, onDone: (Long) -> Unit, modifier: Modifier = Modifier, announces: Boolean = true) {
    val colors = LocalEcencyColors.current
    val minWidth = with(LocalDensity.current) { (LocalWindowInfo.current.containerSize.width / 1.9f).toDp() }
    LaunchedEffect(toast.id) {
        delay(toast.durationMillis)
        onDone(toast.id)
    }
    Box(
        modifier.padding(bottom = 100.dp, start = 5.dp, end = 5.dp).widthIn(min = minWidth).shadow(3.dp, RoundedCornerShape(22.dp))
            .clip(RoundedCornerShape(22.dp)).background(colors.darkGrayBackground).clickable(role = Role.Button) { onDone(toast.id) }
            .padding(vertical = 12.dp, horizontal = 10.dp).semantics { if (announces) liveRegion = LiveRegionMode.Polite }.testTag("toast"),
        contentAlignment = Alignment.Center,
    ) { Text(toast.text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp, textAlign = TextAlign.Center) }
}

/** RN ForegroundNotification: a rounded dark gray card at the top for 5 s; a tap opens its screen. */
@Composable
fun BannerView(banner: UiMessage.Banner, onOpen: (UiMessage.Banner) -> Unit, onDone: (Long) -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalEcencyColors.current
    LaunchedEffect(banner.id) {
        delay(banner.durationMillis)
        onDone(banner.id)
    }
    Column(
        modifier.statusBarsPadding().padding(horizontal = 8.dp).fillMaxWidth().shadow(3.dp, RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp)).background(colors.darkGrayBackground)
            .clickable(role = Role.Button) { onDone(banner.id); onOpen(banner) }.padding(16.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }.testTag("banner"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // RN shows the actor's avatar; the name was validated by the push model
            if (banner.avatarUser.isNotEmpty()) Avatar(com.ecency.core.app.PostCard.avatarUrl(banner.avatarUser), 32.dp)
            Column {
                Text(banner.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, modifier = Modifier.padding(start = 16.dp))
                if (banner.body.isNotEmpty()) Text(banner.body, color = Color.White, fontSize = 14.sp, maxLines = 1, modifier = Modifier.padding(start = 16.dp, top = 4.dp))
            }
        }
    }
}

/**
 * The error boundary Compose allows: a screen's setup ([prepare], e.g. its model) runs guarded, and a
 * failure shows RN's "Something went wrong" with a retry instead of crashing. Exceptions thrown while
 * composing cannot be caught in Compose; screens keep their rendering free of throwing calls.
 */
@Composable
fun <T : Any> Guarded(prepare: () -> T, content: @Composable (T) -> Unit) {
    var attempt by remember { mutableIntStateOf(0) }
    val result = remember(attempt) { runCatching(prepare) }
    result.fold(onSuccess = { content(it) }, onFailure = { e ->
        // RN's ErrorBoundary reports what it caught
        LaunchedEffect(e) { com.ecency.app.EcencyGraph.current()?.crashReporter?.capture(com.ecency.core.app.CrashReport.of(e, "error-boundary")) }
        ShellErrorScreen(onRetry = { attempt++ })
    })
}

/** RN "Are you sure you want to logout?" with Cancel and Logout. */
@Composable
fun LogoutDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(SideMenu.LOGOUT_TITLE) },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("logoutCancel")) { Text(UiCopy.CANCEL) } },
        confirmButton = { TextButton(onClick = onConfirm, modifier = Modifier.testTag("logoutConfirm")) { Text(UiCopy.LOGOUT) } },
        modifier = Modifier.testTag("logoutDialog"),
    )
}

/**
 * The toast in a window of its own, added when the toast shows, so it stacks above every window opened before
 * it: bottom sheets and dialogs are windows of their own, and a toast drawn in the activity's content sat under
 * them. The window takes no focus and no touches (taps go to whatever is below), lifts the toast above the
 * keyboard, and is only composed where the app content is (never over the lock).
 */
@Composable
fun ToastWindow(toast: UiMessage.Toast?, onDone: (Long) -> Unit, fallback: @Composable (UiMessage.Toast) -> Unit) {
    toast ?: return
    val view = androidx.compose.ui.platform.LocalView.current
    val colors = LocalEcencyColors.current
    val done by androidx.compose.runtime.rememberUpdatedState(onDone)
    // a window the system refused: the toast is drawn in the app content instead (under sheets, but shown)
    var failed by remember(toast.id) { mutableStateOf(false) }
    androidx.compose.runtime.DisposableEffect(toast.id) {
        // TalkBack never reaches a window that takes no touches: the announcement comes from the activity's view
        announce(view, toast.text)
        val activity = view.context.findActivity()
        // the keyboard: a window that takes no focus gets no keyboard insets of its own, so the toast is lifted by
        // the keyboard the app's window reports (the inset state is the display's, whichever window has the focus)
        val keyboard = activity?.window?.decorView?.let { androidx.core.view.ViewCompat.getRootWindowInsets(it) }
            ?.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())?.bottom ?: 0
        val navigation = activity?.window?.decorView?.let { androidx.core.view.ViewCompat.getRootWindowInsets(it) }
            ?.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
        val lift = (keyboard - navigation).coerceAtLeast(0)
        val content = androidx.compose.ui.platform.ComposeView(view.context).apply {
            // the activity's owners, which a view outside its hierarchy does not find by itself
            setViewTreeLifecycleOwner(view.findViewTreeLifecycleOwner())
            setViewTreeSavedStateRegistryOwner(view.findViewTreeSavedStateRegistryOwner())
            setContent {
                androidx.compose.runtime.CompositionLocalProvider(LocalEcencyColors provides colors) {
                    val liftDp = with(LocalDensity.current) { lift.toDp() }
                    Box(Modifier.fillMaxSize().imePadding().padding(bottom = liftDp).testTag("toastWindow")) {
                        ToastView(toast, { done(it) }, Modifier.align(Alignment.BottomCenter), announces = false)
                    }
                }
            }
        }
        // the token stays null: the activity's window manager fills in the activity's own token
        val params = android.view.WindowManager.LayoutParams(
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.TYPE_APPLICATION,
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            title = TOAST_WINDOW_TITLE
            softInputMode = android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        }
        val windows = activity?.windowManager
        val added = try {
            if (windows == null) error("no activity for the toast window")
            windows.addView(content, params)
            true
        } catch (e: Exception) {
            android.util.Log.w("EcencyToast", "toast window refused, drawing it in the content", e)
            false
        }
        failed = !added
        onDispose { if (added) runCatching { windows?.removeViewImmediate(content) } }
    }
    if (failed) fallback(toast)
}

const val TOAST_WINDOW_TITLE = "EcencyToast"

private tailrec fun android.content.Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Reads [text] to TalkBack users, sent from the app's own view (the toast window is not reachable by TalkBack). */
fun announce(view: android.view.View, text: String) {
    val manager = view.context.getSystemService(android.view.accessibility.AccessibilityManager::class.java) ?: return
    if (!manager.isEnabled || text.isBlank()) return
    @Suppress("DEPRECATION")
    val event = if (android.os.Build.VERSION.SDK_INT >= 30) {
        android.view.accessibility.AccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_ANNOUNCEMENT)
    } else {
        android.view.accessibility.AccessibilityEvent.obtain(android.view.accessibility.AccessibilityEvent.TYPE_ANNOUNCEMENT)
    }
    event.text.add(text)
    event.className = view.javaClass.name
    event.packageName = view.context.packageName
    manager.sendAccessibilityEvent(event)
}

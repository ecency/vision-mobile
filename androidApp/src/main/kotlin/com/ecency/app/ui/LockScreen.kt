package com.ecency.app.ui

import androidx.compose.foundation.layout.heightIn


import androidx.compose.ui.semantics.Role

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.ecency.core.app.AppIcon
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import com.ecency.core.app.MotionTokens
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.ecency.core.app.AppLockModel
import com.ecency.core.app.AppLockState
import com.ecency.core.app.PinPrompt
import com.ecency.core.app.PostCard
import com.ecency.core.secure.PinLock
import com.ecency.core.app.AccountCopy
import com.ecency.core.app.AuthUiCopy
import com.ecency.core.app.UiCopy

/** What the PIN screen sends back to the [com.ecency.core.app.AppLockModel]. */
class PinPadActions(
    val onDigit: (Int) -> Unit,
    val onDelete: () -> Unit,
    val onCancel: () -> Unit,
    val onForgot: () -> Unit,
    val onBiometric: () -> Unit,
)

/**
 * The RN PIN screen (src/screens/pinCode/children/pinCodeView.tsx, pinCodeStyles.ts and the
 * NumericKeyboard / PinAnimatedInput components): close button, avatar, @username in blue, the
 * information text, four dots, the circular keypad and "Oh, I forgot it...".
 */
@Composable
fun PinScreen(state: AppLockState, prompt: PinPrompt, avatarUrl: String?, actions: PinPadActions) {
    val colors = LocalEcencyColors.current
    var confirmForgot by remember { mutableStateOf(false) }
    // a wrong PIN (or a confirmation that did not match) shakes the dots, with the error haptic (Motion.kt)
    val haptics = rememberHaptics()
    var shakes by remember { mutableIntStateOf(0) }
    val previous = remember { arrayOfNulls<PinPrompt>(1) }
    LaunchedEffect(prompt) {
        if (com.ecency.core.app.MotionPolicy.pinRejected(previous[0], prompt)) {
            shakes++
            haptics(com.ecency.core.app.HapticEvent.ERROR)
        }
        previous[0] = prompt
    }
    // the lock screen swallows Back; a settings flow closes like RN's close button
    BackHandler { if (prompt.canCancel) actions.onCancel() }

    BoxWithConstraints(Modifier.fillMaxSize().background(colors.background).testTag("pinScreen")) {
        val key = maxWidth / 7 // RN: '$deviceWidth / 7'
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(top = maxHeight / 15),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
                if (prompt.canCancel) {
                    Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = actions.onCancel, role = Role.Button).testTag("pinClose"), contentAlignment = Alignment.Center) {
                        Glyph(AppIcon.CLOSE, colors.icon, 24.dp, description = UiCopy.CLOSE)
                    }
                }
            }
            Box(Modifier.weight(2f), contentAlignment = Alignment.Center) {
                if (avatarUrl != null) Avatar(avatarUrl, 64.dp)
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                state.username?.let {
                    Text("@$it", color = colors.blueText, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("pinUsername"))
                }
            }
            Column(Modifier.weight(1.4f).padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    prompt.message, color = colors.text, textAlign = TextAlign.Center, fontSize = 14.sp,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("pinMessage"),
                )
                state.promptError?.let {
                    Text(
                        it, color = colors.redText, textAlign = TextAlign.Center, fontSize = 13.sp,
                        modifier = Modifier.padding(top = 6.dp).semantics { liveRegion = LiveRegionMode.Assertive }.testTag("pinError"),
                    )
                }
            }
            PinDots(prompt.digits, Modifier.weight(1f).shake(shakes))
            Box(Modifier.weight(6f), contentAlignment = Alignment.Center) {
                NumericKeyboard(key, enabled = !prompt.isChecking, actions, showBiometric = state.offersBiometric)
            }
            Box(Modifier.weight(2f), contentAlignment = Alignment.Center) {
                if (prompt.showForgot) {
                    Text(
                        AuthUiCopy.OH_I_FORGOT_IT, color = colors.darkGray, fontSize = 14.sp,
                        modifier = Modifier.clip(CircleShape).clickable(role = Role.Button) { confirmForgot = true }.padding(12.dp).testTag("pinForgot"),
                    )
                }
            }
        }
    }

    if (confirmForgot) {
        AlertDialog(
            onDismissRequest = { confirmForgot = false },
            title = { Text(AccountCopy.WARNING) },
            text = { Text(AuthUiCopy.CLEAR_USER_DATA_CONFIRM) },
            confirmButton = {
                TextButton(onClick = { confirmForgot = false; actions.onForgot() }, modifier = Modifier.testTag("pinForgotConfirm")) {
                    Text(UiCopy.CLEAR, color = colors.redText)
                }
            },
            dismissButton = { TextButton(onClick = { confirmForgot = false }) { Text(UiCopy.CANCEL) } },
        )
    }
}

/** RN PinAnimatedInput: 18 dp circles, blue outline, filled blue per typed digit. */
@Composable
private fun PinDots(filled: Int, modifier: Modifier = Modifier) {
    val colors = LocalEcencyColors.current
    // announced as it changes, so TalkBack users hear each digit land and the pad clear
    Row(modifier.semantics { contentDescription = AuthUiCopy.pinDigits(filled, PinLock.PIN_LENGTH); liveRegion = LiveRegionMode.Polite }.testTag("pinDots")) {
        repeat(PinLock.PIN_LENGTH) { i ->
            // each dot fills with a short fade and a small pop as its digit lands
            val fill by androidx.compose.animation.animateColorAsState(
                if (i < filled) colors.blueText else colors.background, LocalMotion.current.fade(MotionTokens.PRESS_MS), label = "pinDot",
            )
            Box(
                Modifier.padding(10.dp).popOnChange(i < filled, enabled = i < filled).size(18.dp).clip(CircleShape)
                    .background(fill)
                    .border(1.dp, colors.blueText, CircleShape),
            )
        }
    }
}

/**
 * RN NumericKeyboard: 1 to 9 in a wrapped row ($deviceWidth / 1.8 wide), then 0 and the backspace key
 * aligned right. The empty bottom-left slot holds the biometric key when it is on. A keypad is not mirrored in a
 * right to left language (1 2 3 from the left, as Android's own PIN and dial pads), so it is laid out left to right.
 */
@Composable
internal fun NumericKeyboard(key: Dp, enabled: Boolean, actions: PinPadActions, showBiometric: Boolean) {
    val width = key * 7 / 1.8f
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
    Column(Modifier.width(width), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (row in 0 until 3) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                for (col in 1..3) {
                    val digit = row * 3 + col
                    KeypadButton("$digit", key, enabled) { actions.onDigit(digit) }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            if (showBiometric) {
                Box(
                    Modifier.size(key).clip(CircleShape).clickable(enabled = enabled, onClick = actions.onBiometric, role = Role.Button).testTag("pinBiometric"),
                    contentAlignment = Alignment.Center,
                ) { FingerprintGlyph(LocalEcencyColors.current.blueText, 28.dp) }
            } else {
                Spacer(Modifier.size(key))
            }
            KeypadButton("0", key, enabled) { actions.onDigit(0) }
            Box(
                Modifier.size(key).clip(CircleShape).clickable(enabled = enabled, onClick = actions.onDelete, role = Role.Button).testTag("pinDelete"),
                contentAlignment = Alignment.Center,
            ) { Glyph(AppIcon.BACKSPACE, LocalEcencyColors.current.blueText, 25.dp, description = UiCopy.DELETE) }
        }
    }
    }
}

/** Concentric arcs, drawn rather than pulled from an icon font. */
@Composable
private fun FingerprintGlyph(color: Color, size: Dp) {
    Canvas(Modifier.size(size).semantics { contentDescription = AuthUiCopy.UNLOCK_WITH_BIOMETRICS }) {
        val stroke = Stroke(width = this.size.minDimension / 14, cap = StrokeCap.Round)
        for (i in 1..4) {
            val r = this.size.minDimension / 2 * i / 4.4f
            drawArc(
                color, startAngle = 150f + i * 6, sweepAngle = 240f - i * 12, useCenter = false, style = stroke,
                topLeft = center - Offset(r, r), size = Size(r * 2, r * 2),
            )
        }
    }
}

/** RN CircularButton: a ring of rgba(53,124,230,0.2), the digit in #357ce6, 24 sp, weight 500. */
@Composable
private fun KeypadButton(label: String, size: Dp, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).border(BorderStroke(1.dp, Color(0x33357CE6)), CircleShape)
            .clickable(enabled = enabled, onClick = onClick, role = Role.Button).testTag("pinKey$label"),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Color(0xFF357CE6), fontSize = 24.sp, fontWeight = FontWeight.Medium) }
}

/** Avatar for the lock screen, as the RN UserAvatar xl. */
fun lockAvatar(username: String?, proxyBase: String): String? =
    username?.let { PostCard.avatarUrl(it, proxyBase, "medium").ifEmpty { null } }

/** Covers the app while the lock state loads or after a storage error, without showing anything private. */
@Composable
fun LockCover(error: String?, onRetry: () -> Unit) {
    val colors = LocalEcencyColors.current
    Box(Modifier.fillMaxSize().background(colors.background).testTag("lockCover"), contentAlignment = Alignment.Center) {
        if (error != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                Text(error, color = colors.text, textAlign = TextAlign.Center)
                TextButton(onClick = onRetry) { Text(UiCopy.RETRY) }
            }
        }
    }
}

/** Starts the biometric prompt when [due] turns true (the model marks it due once per lock). */
@Composable
fun AutoBiometric(due: Boolean, start: () -> Unit) {
    LaunchedEffect(due) { if (due) start() }
}

/**
 * Turning the PIN lock on where a strong biometric is enrolled: biometric unlock first, "Use PIN instead" as the
 * way to the plain PIN setup (a deliberate addition over RN). The PIN is set either way; it backs biometrics.
 */
@Composable
fun BiometricOfferScreen(onUseBiometric: () -> Unit, onUsePin: () -> Unit, onCancel: () -> Unit) {
    val colors = LocalEcencyColors.current
    BackHandler(onBack = onCancel)
    Column(
        Modifier.fillMaxSize().background(colors.background).statusBarsPadding().navigationBarsPadding().padding(24.dp).testTag("biometricOffer"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
            Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onCancel, role = Role.Button).testTag("biometricOfferClose"), contentAlignment = Alignment.Center) {
                Glyph(AppIcon.CLOSE, colors.icon, 24.dp, description = UiCopy.CLOSE)
            }
        }
        Spacer(Modifier.weight(1f))
        FingerprintGlyph(colors.blueText, 96.dp)
        Text(
            AppLockModel.OFFER_TITLE, color = colors.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 24.dp),
        )
        Text(AppLockModel.OFFER_BODY, color = colors.darkGray, fontSize = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
        Spacer(Modifier.weight(1f))
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(colors.blue).clickable(onClick = onUseBiometric, role = Role.Button)
                .padding(vertical = 14.dp).testTag("biometricOfferUse"),
            contentAlignment = Alignment.Center,
        ) { Text(AppLockModel.OFFER_USE, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
        TextButton(onClick = onUsePin, modifier = Modifier.padding(top = 8.dp).testTag("biometricOfferPin")) {
            Text(AppLockModel.OFFER_USE_PIN, color = colors.blueText, fontSize = 15.sp)
        }
    }
}

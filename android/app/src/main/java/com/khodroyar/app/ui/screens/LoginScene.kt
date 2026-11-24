package com.khodroyar.app.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.khodroyar.app.R
import com.khodroyar.app.ui.components.*
import com.khodroyar.app.ui.theme.CarManagerTheme
import com.khodroyar.app.ui.theme.LocalAppPalette
import kotlin.math.roundToInt

internal const val LOGIN_PIN_LENGTH = 5

/** A static vector backdrop replaces the old full-frame animation / image scene. */
@Composable
internal fun GateBackdrop(content: @Composable BoxScope.() -> Unit) {
    val palette = LocalAppPalette.current
    CarManagerTheme(darkTheme = true, palette = palette) {
        SystemChrome(dark = true)
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Box(Modifier.fillMaxSize().background(Color(0xFF071B2B))) {
                Image(
                    painter = painterResource(R.drawable.login_background),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().alpha(0.42f),
                )
                Box(Modifier.fillMaxSize().background(Color(0x9907192B)))
                Canvas(Modifier.fillMaxSize()) {
                    val origin = Offset(size.width * 0.95f, size.height * 0.04f)
                    listOf(96.dp, 156.dp, 218.dp).forEach { radius ->
                        drawCircle(Color(0xFF35E0B0).copy(alpha = 0.10f), radius.toPx(), origin, style = Stroke(1.dp.toPx()))
                    }
                    drawCircle(Color(0xFF6B9DEB).copy(alpha = 0.05f), size.width * 0.7f, Offset(-size.width * 0.3f, size.height * 0.84f))
                }
                content()
            }
        }
    }
}

@Composable
internal fun GateBrand() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF71DFCC)), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.DirectionsCar, null, tint = Color(0xFF103C36), modifier = Modifier.size(28.dp))
        }
        Column {
            Text("خودرویار", style = MaterialTheme.typography.titleLarge, color = Color.White)
            Text("مدیریت روزهای در حرکت", style = MaterialTheme.typography.labelSmall, color = Color(0xFFAAC1C8))
        }
    }
}

/** Presentation only: PIN verification, legacy four-digit support and biometrics stay in AuthScreens. */
@Composable
internal fun AnimatedLoginScreen(
    pin: String, onPinChange: (String) -> Unit, welcome: String, prompt: String, errorMessage: String,
    errorTick: Int, success: Boolean, showFingerprint: Boolean, onFingerprint: () -> Unit,
) {
    val shake = remember { Animatable(0f) }
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(errorTick) {
        if (errorTick > 0) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            for (offset in listOf(-8f, 7f, -5f, 3f, 0f)) shake.animateTo(offset, tween(50))
        }
    }
    val hasError = errorMessage.isNotBlank()
    GateBackdrop {
        // BoxWithConstraints gives the scrolling column a minimum height of one screen, so the
        // brand sits at the top, the PIN card is vertically centred and the footer rests at the
        // bottom — yet everything still scrolls when the keyboard or a large font needs room.
        BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            val viewport = maxHeight
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).heightIn(min = viewport)
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.fillMaxWidth()) { GateBrand() }
                Spacer(Modifier.height(32.dp))
                Spacer(Modifier.weight(1f))

                LockRings(success = success, error = hasError)
                Spacer(Modifier.height(16.dp))
                Text(
                    if (success) "قفل باز شد" else welcome,
                    Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                    color = Color.White, textAlign = TextAlign.Center,
                )
                Text(prompt, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium,
                    color = LoginMist, textAlign = TextAlign.Center)
                Spacer(Modifier.height(20.dp))
                PinDots(
                    filled = pin.length, error = hasError, success = success,
                    modifier = Modifier.offset { IntOffset(shake.value.roundToInt(), 0) },
                )
                Spacer(Modifier.height(20.dp))

                Surface(
                    modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    color = LoginCard.copy(alpha = 0.92f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
                ) {
                    Column(Modifier.padding(20.dp)) {
                        OutlinedTextField(
                            value = pin, onValueChange = onPinChange, enabled = !success, singleLine = true,
                            modifier = Modifier.fillMaxWidth().offset { IntOffset(shake.value.roundToInt(), 0) },
                            label = { Text("رمز عبور") }, shape = FieldShape, colors = pinFieldColors(),
                            textStyle = MaterialTheme.typography.headlineMedium.copy(textAlign = TextAlign.Center, letterSpacing = 8.sp),
                            visualTransformation = PasswordVisualTransformation(), isError = hasError,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                            supportingText = {
                                // Live region: TalkBack reads the digit count and, above all, the error.
                                Text(
                                    errorMessage.ifBlank { "${pin.length} از $LOGIN_PIN_LENGTH رقم وارد شده" },
                                    Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            },
                        )
                        if (showFingerprint && !success) {
                            Spacer(Modifier.height(16.dp))
                            FingerprintButton(onFingerprint)
                        }
                    }
                }

                Spacer(Modifier.height(32.dp))
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Outlined.Lock, null, tint = LoginMist, modifier = Modifier.size(14.dp))
                    Text("ورود امن، روی همین دستگاه", style = MaterialTheme.typography.labelSmall, color = LoginMist)
                }
            }
        }
    }
}

// Brand colours for the lock screen (inline, as before; no resources are added).
private val LoginNavy = Color(0xFF071B2B)
private val LoginCard = Color(0xFF0C2A40)
private val LoginEmerald = Color(0xFF35E0B0)
private val LoginMint = Color(0xFF71DFCC)
private val LoginMist = Color(0xFFAAC1C8)
private val LoginCoral = Color(0xFFFF8E85)
private val LoginFieldEdge = Color(0xFF5F7F96)

/**
 * Lock badge inside the brand's concentric rings. Mint at rest, emerald once unlocked,
 * coral while an error shows. The colour change animates only when that state changes.
 */
@Composable
private fun LockRings(success: Boolean, error: Boolean) {
    val accent by animateColorAsState(
        targetValue = when { success -> LoginEmerald; error -> LoginCoral; else -> LoginMint },
        animationSpec = tween(220), label = "lock-accent",
    )
    Box(Modifier.size(136.dp).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            listOf(44.dp to 0.38f, 56.dp to 0.22f, 68.dp to 0.10f).forEach { (radius, alpha) ->
                drawCircle(accent.copy(alpha = alpha), radius.toPx(), center, style = Stroke(1.dp.toPx()))
            }
        }
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(accent.copy(alpha = 0.14f))
                .border(1.dp, accent.copy(alpha = 0.55f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (success) Icons.Outlined.CheckCircle else Icons.Outlined.Lock, null, tint = accent, modifier = Modifier.size(34.dp))
        }
    }
}

/** Visual PIN progress. Decorative: the field's supporting text already states the count. */
@Composable
private fun PinDots(filled: Int, error: Boolean, success: Boolean, modifier: Modifier = Modifier) {
    Row(modifier.clearAndSetSemantics { }, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(LOGIN_PIN_LENGTH) { index ->
            val on = success || index < filled
            val edge = when { error -> LoginCoral; on -> LoginEmerald; else -> LoginMist }
            val fill = when { error -> LoginCoral.copy(alpha = 0.35f); on -> LoginEmerald; else -> Color.Transparent }
            Box(Modifier.size(12.dp).clip(CircleShape).background(fill).border(1.5.dp, edge, CircleShape))
        }
    }
}

/** Brand field colours: navy well, emerald focus, coral error, AA-contrast resting edge. */
@Composable
private fun pinFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White, unfocusedTextColor = Color.White, disabledTextColor = LoginMist, errorTextColor = Color.White,
    focusedContainerColor = LoginNavy, unfocusedContainerColor = LoginNavy, disabledContainerColor = LoginNavy, errorContainerColor = LoginNavy,
    cursorColor = LoginEmerald, errorCursorColor = LoginCoral,
    focusedBorderColor = LoginEmerald, unfocusedBorderColor = LoginFieldEdge,
    disabledBorderColor = LoginFieldEdge.copy(alpha = 0.5f), errorBorderColor = LoginCoral,
    focusedLabelColor = LoginEmerald, unfocusedLabelColor = LoginMist, disabledLabelColor = LoginMist, errorLabelColor = LoginCoral,
    focusedSupportingTextColor = LoginMist, unfocusedSupportingTextColor = LoginMist,
    disabledSupportingTextColor = LoginMist, errorSupportingTextColor = LoginCoral,
)

/**
 * The screen's one primary action: emerald with navy text (10.4:1). Keyboard focus draws a
 * white ring outside the button (14:1 against the card); press uses the standard ripple.
 */
@Composable
private fun FingerprintButton(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        Modifier.fillMaxWidth()
            .border(2.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(21.dp))
            .padding(3.dp),
    ) {
        Button(
            onClick = onClick, interactionSource = interaction,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = FieldShape,
            colors = ButtonDefaults.buttonColors(containerColor = LoginEmerald, contentColor = LoginNavy),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 0.dp, focusedElevation = 0.dp),
        ) {
            Icon(Icons.Outlined.Fingerprint, null, Modifier.size(24.dp))
            Spacer(Modifier.width(10.dp))
            Text("ورود با اثر انگشت", fontWeight = FontWeight.Bold)
        }
    }
}

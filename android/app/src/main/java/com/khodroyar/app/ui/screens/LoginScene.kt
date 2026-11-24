package com.khodroyar.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.khodroyar.app.ui.components.*
import com.khodroyar.app.ui.theme.CarManagerTheme
import com.khodroyar.app.ui.theme.LocalAppPalette
import kotlin.math.roundToInt

internal const val LOGIN_PIN_LENGTH = 5

private val Ocean = Color(0xFF087F75)
private val Petrol = Color(0xFF063F3B)
private val Indigo = Color(0xFF5557B8)
private val IndigoLight = Color(0xFF9297FF)
private val Night = Color(0xFF10142B)

/** Animated, low-cost Compose backdrop: no video, Lottie or extra runtime dependency. */
@Composable
internal fun GateBackdrop(content: @Composable BoxScope.() -> Unit) {
    val palette = LocalAppPalette.current
    val motion = rememberInfiniteTransition(label = "gate-background")
    val drift by motion.animateFloat(0f, 1f, infiniteRepeatable(tween(6200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "drift")
    val halo by motion.animateFloat(0.22f, 0.58f, infiniteRepeatable(tween(2800), RepeatMode.Reverse), label = "halo")
    CarManagerTheme(darkTheme = true, palette = palette) {
        SystemChrome(dark = true)
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Night, Color(0xFF102D3C), Petrol)))) {
                Canvas(Modifier.fillMaxSize()) {
                    val x = size.width * (0.82f - drift * 0.12f)
                    val y = size.height * (0.12f + drift * 0.06f)
                    listOf(0.19f, 0.31f, 0.44f).forEachIndexed { index, factor ->
                        drawCircle(
                            color = if (index == 1) IndigoLight.copy(alpha = halo * 0.15f) else Color(0xFF65D9C7).copy(alpha = 0.10f),
                            radius = size.width * factor,
                            center = androidx.compose.ui.geometry.Offset(x, y),
                            style = Stroke(width = (1.1f + index * 0.35f).dp.toPx())
                        )
                    }
                    drawCircle(Color(0xFF6F77E8).copy(alpha = 0.07f), size.width * 0.62f,
                        androidx.compose.ui.geometry.Offset(size.width * (0.05f + drift * 0.06f), size.height * 0.88f))
                    // Subtle horizon lines add depth without a bitmap background.
                    for (i in 0..5) {
                        val yy = size.height * (0.72f + i * 0.045f)
                        drawLine(Color.White.copy(alpha = 0.018f), androidx.compose.ui.geometry.Offset(0f, yy),
                            androidx.compose.ui.geometry.Offset(size.width, yy), 1.dp.toPx())
                    }
                }
                content()
            }
        }
    }
}

@Composable
internal fun GateBrand() {
    val motion = rememberInfiniteTransition(label = "brand")
    val bob by motion.animateFloat(-2.5f, 2.5f, infiniteRepeatable(tween(1900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "brand-bob")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp),
        modifier = Modifier.graphicsLayer { translationY = bob.dp.toPx() }) {
        Box(Modifier.size(58.dp).clip(RoundedCornerShape(19.dp)).background(Color.White), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.DirectionsCar, null, tint = Indigo, modifier = Modifier.size(35.dp))
            Box(Modifier.align(Alignment.BottomEnd).padding(7.dp).size(12.dp).clip(CircleShape).background(Ocean))
        }
        Column {
            Text("خودرویار", style = MaterialTheme.typography.headlineSmall, color = Color.White)
            Text("همراه هوشمند مسیرهای شما", style = MaterialTheme.typography.labelSmall, color = Color(0xFFB9C5E8))
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
    val motion = rememberInfiniteTransition(label = "login-motion")
    val pulse by motion.animateFloat(0.96f, 1.045f, infiniteRepeatable(tween(1250, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "fingerprint-pulse")
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    LaunchedEffect(errorTick) {
        if (errorTick > 0) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            for (offset in listOf(-8f, 7f, -5f, 3f, 0f)) shake.animateTo(offset, tween(50))
        }
    }
    GateBackdrop {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            AnimatedVisibility(visible = entered, enter = fadeIn(tween(650)) + slideInVertically(initialOffsetY = { -it / 5 }, animationSpec = tween(650))) {
                GateBrand()
            }
            Spacer(Modifier.height(32.dp))
            AnimatedContent(targetState = success, label = "login-state") { done ->
                Box(Modifier.size(104.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.fillMaxSize().clip(CircleShape).background(Indigo.copy(alpha = 0.12f)))
                    Canvas(Modifier.fillMaxSize()) {
                        drawCircle(IndigoLight.copy(alpha = 0.55f), style = Stroke(1.4.dp.toPx()))
                        drawCircle(Color(0xFF6FE1C9).copy(alpha = 0.35f), radius = size.minDimension * 0.39f, style = Stroke(1.dp.toPx()))
                    }
                    Icon(if (done) Icons.Outlined.CheckCircle else Icons.Outlined.Lock, null,
                        tint = if (done) Color(0xFF75E1BD) else IndigoLight, modifier = Modifier.size(47.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
            AnimatedContent(targetState = success, label = "welcome-state") { done ->
                Text(if (done) "خوش آمدید!" else welcome, style = MaterialTheme.typography.headlineMedium,
                    color = Color.White, textAlign = TextAlign.Center)
            }
            Text(prompt, Modifier.padding(top = 7.dp, bottom = 24.dp), style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFB8C5D9), textAlign = TextAlign.Center)
            AnimatedVisibility(visible = entered, enter = fadeIn(tween(700, delayMillis = 120)) + slideInVertically(initialOffsetY = { it / 7 }, animationSpec = tween(700, delayMillis = 120))) {
                SurfaceCard(modifier = Modifier.widthIn(max = 420.dp)) {
                    OutlinedTextField(
                        value = pin, onValueChange = onPinChange, enabled = !success, singleLine = true,
                        modifier = Modifier.fillMaxWidth().offset { IntOffset(shake.value.roundToInt(), 0) },
                        label = { Text("رمز عبور") }, shape = FieldShape, colors = fieldColors(),
                        textStyle = MaterialTheme.typography.headlineMedium.copy(textAlign = TextAlign.Center, letterSpacing = 8.sp),
                        visualTransformation = PasswordVisualTransformation(), isError = errorMessage.isNotBlank(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                        supportingText = { Text(errorMessage.ifBlank { "${pin.length} از $LOGIN_PIN_LENGTH رقم وارد شده" }, style = MaterialTheme.typography.bodySmall) },
                    )
                    if (showFingerprint && !success) {
                        Spacer(Modifier.height(14.dp))
                        OutlinedButton(onClick = onFingerprint, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)
                            .graphicsLayer { scaleX = pulse; scaleY = pulse }, shape = FieldShape) {
                            Icon(Icons.Outlined.Fingerprint, null, Modifier.size(24.dp), tint = Indigo)
                            Spacer(Modifier.width(10.dp))
                            Text("ورود با اثر انگشت")
                        }
                    }
                }
            }
            Spacer(Modifier.height(22.dp))
            Text("محافظت‌شده با رمزگذاری و امنیت دستگاه", style = MaterialTheme.typography.labelSmall, color = Color(0xFFB8C5D9))
            AnimatedVisibility(visible = errorMessage.isNotBlank(), enter = fadeIn(), exit = fadeOut()) {
                Text(errorMessage, color = Color(0xFFFFA9B0), style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 10.dp))
            }
        }
    }
}

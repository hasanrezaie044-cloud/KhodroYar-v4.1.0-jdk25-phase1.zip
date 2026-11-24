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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Security
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
import androidx.compose.ui.text.font.FontWeight
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
private val Petrol = Color(0xFF063F38)
private val Indigo = Color(0xFF343A78)
private val IndigoBright = Color(0xFF747BE8)
private val Ink = Color(0xFF071725)
private val GlassWhite = Color(0xFFF5FAFF)

/** Full-screen atmospheric backdrop from the approved petrol-green / indigo concept. */
@Composable
internal fun GateBackdrop(content: @Composable BoxScope.() -> Unit) {
    val palette = LocalAppPalette.current
    val motion = rememberInfiniteTransition(label = "gate-background")
    val drift by motion.animateFloat(
        -0.08f, 0.08f,
        infiniteRepeatable(tween(7200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "backdrop-drift"
    )
    val glow by motion.animateFloat(
        0.20f, 0.48f,
        infiniteRepeatable(tween(3400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "backdrop-glow"
    )
    CarManagerTheme(darkTheme = true, palette = palette) {
        SystemChrome(dark = true)
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.linearGradient(
                        0f to Ink,
                        0.42f to Color(0xFF102D3D),
                        0.76f to Color(0xFF073F43),
                        1f to Petrol,
                    )
                )
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val upper = androidx.compose.ui.geometry.Offset(size.width * (0.83f + drift), size.height * 0.12f)
                    drawCircle(Color(0xFF5962D5).copy(alpha = glow * 0.18f), size.width * 0.66f, upper)
                    drawCircle(Color(0xFF21C7A5).copy(alpha = glow * 0.12f), size.width * 0.50f,
                        androidx.compose.ui.geometry.Offset(size.width * 0.08f, size.height * 0.84f))
                    for (i in 0..3) {
                        drawCircle(
                            Color(0xFF8C94FF).copy(alpha = 0.10f - i * 0.015f),
                            size.width * (0.22f + i * 0.12f),
                            upper,
                            style = Stroke(width = (1f + i * 0.35f).dp.toPx())
                        )
                    }
                    // Fine horizon strokes make the lower half feel like a road, not a flat fill.
                    for (i in 0..7) {
                        val y = size.height * (0.72f + i * 0.035f)
                        drawLine(Color.White.copy(alpha = 0.025f), androidx.compose.ui.geometry.Offset(0f, y),
                            androidx.compose.ui.geometry.Offset(size.width, y), 1.dp.toPx())
                    }
                }
                content()
            }
        }
    }
}

/** Animated car mark and wordmark, reused by the first-run profile screen. */
@Composable
internal fun GateBrand() {
    val motion = rememberInfiniteTransition(label = "brand-motion")
    val bob by motion.animateFloat(-3f, 3f,
        infiniteRepeatable(tween(1900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "brand-bob")
    val orbit by motion.animateFloat(0f, 360f,
        infiniteRepeatable(tween(9000, easing = androidx.compose.animation.core.LinearEasing)), label = "brand-orbit")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.graphicsLayer { translationY = bob.dp.toPx() }
    ) {
        Box(Modifier.size(62.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(Color.White.copy(alpha = 0.13f), style = Stroke(1.2.dp.toPx()))
                drawArc(Color(0xFF8E94FF).copy(alpha = 0.8f), orbit, 112f, false,
                    style = Stroke(2.2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
            }
            Box(Modifier.size(49.dp).clip(RoundedCornerShape(16.dp))
                .background(Brush.linearGradient(listOf(Color.White, Color(0xFFE9ECFF)))),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.DirectionsCar, null, tint = Indigo, modifier = Modifier.size(34.dp))
            }
            Box(Modifier.align(Alignment.BottomEnd).size(19.dp).clip(CircleShape)
                .background(Ocean).padding(3.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Check, null, tint = Color.White, modifier = Modifier.size(12.dp))
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("خودرویار", style = MaterialTheme.typography.headlineSmall,
                color = Color.White, fontWeight = FontWeight.Bold, letterSpacing = 0.2.sp)
            Text("همراه مطمئن خودروی شما", style = MaterialTheme.typography.labelMedium,
                color = Color(0xFFC0C9E8))
        }
    }
}

/** Large animated emblem that visually anchors the login page. */
@Composable
private fun LoginEmblem(success: Boolean) {
    val motion = rememberInfiniteTransition(label = "login-emblem")
    val pulse by motion.animateFloat(0.94f, 1.045f,
        infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "emblem-pulse")
    val sweep by motion.animateFloat(0f, 360f,
        infiniteRepeatable(tween(7200, easing = androidx.compose.animation.core.LinearEasing)), label = "emblem-sweep")
    Box(Modifier.size(142.dp).graphicsLayer { scaleX = pulse; scaleY = pulse }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(Color.White.copy(alpha = 0.035f), radius = size.minDimension * 0.49f)
            drawCircle(Color(0xFF8B91FF).copy(alpha = 0.24f), radius = size.minDimension * 0.44f,
                style = Stroke(1.2.dp.toPx()))
            drawCircle(Color(0xFF42D5B5).copy(alpha = 0.34f), radius = size.minDimension * 0.35f,
                style = Stroke(1.4.dp.toPx()))
            drawArc(Brush.sweepGradient(listOf(Color.Transparent, IndigoBright, Color(0xFF75E6CE))),
                sweep, 100f, false, style = Stroke(3.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round),
                topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.055f, size.height * 0.055f),
                size = androidx.compose.ui.geometry.Size(size.width * 0.89f, size.height * 0.89f))
        }
        Box(Modifier.size(91.dp).clip(CircleShape)
            .background(Brush.linearGradient(listOf(Color(0xFF5961C9), Color(0xFF242B68)))),
            contentAlignment = Alignment.Center) {
            Icon(
                if (success) Icons.Outlined.CheckCircle else Icons.Outlined.DirectionsCar,
                null, tint = Color.White, modifier = Modifier.size(53.dp)
            )
        }
        Box(Modifier.align(Alignment.BottomEnd).padding(end = 13.dp, bottom = 12.dp)
            .size(29.dp).clip(CircleShape).background(Color(0xFF1AB99A))
            .padding(5.dp), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Check, null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
    }
}

/** Presentation only: PIN verification and real Android biometrics remain in AuthScreens. */
@Composable
internal fun AnimatedLoginScreen(
    pin: String, onPinChange: (String) -> Unit, welcome: String, prompt: String, errorMessage: String,
    errorTick: Int, success: Boolean, showFingerprint: Boolean, onFingerprint: () -> Unit,
) {
    val shake = remember { Animatable(0f) }
    val haptic = LocalHapticFeedback.current
    val motion = rememberInfiniteTransition(label = "login-actions")
    val fingerPulse by motion.animateFloat(0.96f, 1.04f,
        infiniteRepeatable(tween(1150, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "finger-pulse")
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    LaunchedEffect(errorTick) {
        if (errorTick > 0) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            for (offset in listOf(-9f, 8f, -6f, 4f, 0f)) shake.animateTo(offset, tween(48))
        }
    }

    GateBackdrop {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 25.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            AnimatedVisibility(visible = entered,
                enter = fadeIn(tween(700)) + slideInVertically(initialOffsetY = { -it / 4 }, animationSpec = tween(700))) {
                GateBrand()
            }
            Spacer(Modifier.height(22.dp))
            AnimatedVisibility(visible = entered,
                enter = fadeIn(tween(850, delayMillis = 100)) + slideInVertically(initialOffsetY = { it / 8 }, animationSpec = tween(850))) {
                LoginEmblem(success)
            }
            Spacer(Modifier.height(5.dp))
            AnimatedContent(targetState = success, label = "login-heading") { done ->
                Text(
                    if (done) "ورود موفق بود" else welcome,
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center
                )
            }
            Text(
                if (success) "خوش آمدید؛ مسیرتان امن و همراه شماست" else prompt,
                Modifier.fillMaxWidth().padding(top = 7.dp, bottom = 22.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFC3CDE0), textAlign = TextAlign.Center
            )

            AnimatedVisibility(visible = entered,
                enter = fadeIn(tween(750, delayMillis = 180)) + slideInVertically(initialOffsetY = { it / 6 }, animationSpec = tween(750))) {
                Surface(
                    modifier = Modifier.widthIn(max = 430.dp).fillMaxWidth(),
                    shape = RoundedCornerShape(30.dp),
                    color = Color.White.copy(alpha = 0.085f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.19f)),
                    shadowElevation = 18.dp,
                ) {
                    Column(
                        Modifier.padding(horizontal = 22.dp, vertical = 23.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center) {
                            Icon(Icons.Outlined.Lock, null, tint = Color(0xFF9DA4FF), modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("ورود امن به خودرویار", color = Color.White,
                                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.height(18.dp))
                        OutlinedTextField(
                            value = pin,
                            onValueChange = onPinChange,
                            enabled = !success,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().offset { IntOffset(shake.value.roundToInt(), 0) },
                            label = { Text("رمز عبور", color = Color(0xFFD4D9ED)) },
                            placeholder = { Text("•••••", color = Color.White.copy(alpha = 0.32f)) },
                            shape = RoundedCornerShape(18.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                disabledTextColor = Color.White.copy(alpha = 0.65f),
                                focusedContainerColor = Color.White.copy(alpha = 0.075f),
                                unfocusedContainerColor = Color.White.copy(alpha = 0.055f),
                                disabledContainerColor = Color.White.copy(alpha = 0.04f),
                                focusedBorderColor = Color(0xFF969BFF),
                                unfocusedBorderColor = Color.White.copy(alpha = 0.20f),
                                errorBorderColor = Color(0xFFFF8998),
                                focusedLabelColor = Color(0xFFB7BBFF),
                                unfocusedLabelColor = Color(0xFFD4D9ED),
                                cursorColor = Color(0xFF9EA4FF),
                            ),
                            textStyle = MaterialTheme.typography.headlineMedium.copy(
                                textAlign = TextAlign.Center, letterSpacing = 10.sp, color = Color.White
                            ),
                            visualTransformation = PasswordVisualTransformation(),
                            isError = errorMessage.isNotBlank(),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done
                            ),
                            supportingText = {
                                Text(
                                    errorMessage.ifBlank { "${pin.length} از $LOGIN_PIN_LENGTH رقم وارد شده" },
                                    color = if (errorMessage.isNotBlank()) Color(0xFFFFA9B2) else Color(0xFFB8C5D9),
                                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
                                )
                            },
                        )

                        if (showFingerprint && !success) {
                            Spacer(Modifier.height(20.dp))
                            Box(contentAlignment = Alignment.Center) {
                                Canvas(Modifier.size(90.dp)) {
                                    drawCircle(Color(0xFF777FEA).copy(alpha = 0.13f), style = Stroke(1.dp.toPx()))
                                    drawCircle(Color(0xFF9AA0FF).copy(alpha = 0.35f), radius = size.minDimension * 0.40f,
                                        style = Stroke(1.5.dp.toPx()))
                                }
                                OutlinedButton(
                                    onClick = onFingerprint,
                                    modifier = Modifier.size(70.dp).graphicsLayer {
                                        scaleX = fingerPulse; scaleY = fingerPulse
                                    },
                                    shape = CircleShape,
                                    contentPadding = PaddingValues(0.dp),
                                    border = BorderStroke(1.4.dp, Color(0xFF9AA0FF).copy(alpha = 0.72f)),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        containerColor = Indigo.copy(alpha = 0.42f),
                                        contentColor = Color.White
                                    )
                                ) {
                                    Icon(Icons.Outlined.Fingerprint, "ورود با اثر انگشت",
                                        modifier = Modifier.size(36.dp), tint = Color(0xFFD9DCFF))
                                }
                            }
                            Spacer(Modifier.height(9.dp))
                            TextButton(onClick = onFingerprint, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                                Text("ورود با اثر انگشت", color = Color(0xFFD9DCFF),
                                    style = MaterialTheme.typography.labelLarge)
                            }
                        }

                        AnimatedVisibility(visible = success, enter = fadeIn(tween(250))) {
                            Row(Modifier.fillMaxWidth().padding(top = 12.dp),
                                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.CheckCircle, null, tint = Color(0xFF63E0B8))
                                Spacer(Modifier.width(8.dp))
                                Text("هویت شما با موفقیت تأیید شد", color = Color(0xFF9EF0D5),
                                    style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(19.dp))
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center) {
                Icon(Icons.Outlined.Security, null, tint = Color(0xFF6FD8C2), modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(7.dp))
                Text("اطلاعات شما فقط روی همین دستگاه محافظت می‌شود",
                    color = Color(0xFFB8C6D8), style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center)
            }
            AnimatedVisibility(visible = errorMessage.isNotBlank(), enter = fadeIn(), exit = fadeOut()) {
                Text(errorMessage, color = Color(0xFFFFA9B2), style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

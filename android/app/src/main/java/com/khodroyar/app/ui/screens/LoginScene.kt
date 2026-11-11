package com.khodroyar.app.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
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

/** A static vector backdrop replaces the old full-frame animation / image scene. */
@Composable
internal fun GateBackdrop(content: @Composable BoxScope.() -> Unit) {
    val palette = LocalAppPalette.current
    CarManagerTheme(darkTheme = true, palette = palette) {
        SystemChrome(dark = true)
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Box(Modifier.fillMaxSize().background(Color(0xFF0E2027))) {
                Canvas(Modifier.fillMaxSize()) {
                    val origin = Offset(size.width * 0.95f, size.height * 0.04f)
                    listOf(96.dp, 156.dp, 218.dp).forEach { radius ->
                        drawCircle(Color(0xFF6ED1BE).copy(alpha = 0.07f), radius.toPx(), origin, style = Stroke(1.dp.toPx()))
                    }
                    drawCircle(Color(0xFF9DBFE8).copy(alpha = 0.035f), size.width * 0.7f, Offset(-size.width * 0.3f, size.height * 0.84f))
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
    GateBackdrop {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            GateBrand()
            Spacer(Modifier.height(36.dp))
            IconBadge(if (success) Icons.Outlined.CheckCircle else Icons.Outlined.Lock, size = 72.dp)
            Spacer(Modifier.height(18.dp))
            Text(if (success) "قفل باز شد" else welcome, style = MaterialTheme.typography.headlineMedium, color = Color.White)
            Text(prompt, Modifier.padding(top = 6.dp, bottom = 24.dp), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
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
                    OutlinedButton(onClick = onFingerprint, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = FieldShape) {
                        Icon(Icons.Outlined.Fingerprint, null, Modifier.size(24.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("ورود با اثر انگشت")
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            Text("ورود امن، روی همین دستگاه", style = MaterialTheme.typography.labelSmall, color = Color(0xFFAAC1C8))
        }
    }
}

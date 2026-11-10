package com.khodroyar.app.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** The login PIN is exactly this many digits (the lock-screen shows this many indicators). */
internal const val LOGIN_PIN_LENGTH = 5

// ------------------------------------------------------------------ palette
private val NavyDeep = Color(0xFF01030A)
private val Neon = Color(0xFF2F7BFF)
private val NeonBright = Color(0xFF67B5FF)
private val Cyan = Color(0xFF63E6FF)
private val IceWhite = Color(0xFFEAF5FF)
private val ErrorRed = Color(0xFFFF5470)
private val Mint = Color(0xFF4DF0C0)

// ------------------------------------------------------------------ clock
/**
 * Every animation on this screen reads one shared clock (seconds), advanced once per frame
 * and only while the activity is RESUMED — so nothing runs, and no battery is used, while
 * the app is in the background. The clock is wrapped at [CLOCK_WRAP]; every period used
 * below divides it, so wrapping is seamless. Draw blocks read the value, composition never
 * does, so a frame only redraws and never recomposes.
 */
private const val CLOCK_WRAP = 6000f

private fun phase(t: Float, period: Float, offset: Float = 0f): Float {
    val p = (t / period + offset) % 1f
    return if (p < 0f) p + 1f else p
}

/** Smooth 0..1..0 swing over [period] seconds. */
private fun wave(t: Float, period: Float, offset: Float = 0f): Float =
    0.5f - 0.5f * cos(2f * PI.toFloat() * phase(t, period, offset))

@Composable
private fun rememberSceneClock(): FloatState {
    val activity = LocalContext.current.findActivity()
    val clock = remember { mutableFloatStateOf(0f) }
    var resumed by remember {
        mutableStateOf(
            (activity as? LifecycleOwner)?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) ?: true
        )
    }
    DisposableEffect(activity) {
        val owner = activity as? LifecycleOwner
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> { resumed = true }
                Lifecycle.Event.ON_PAUSE -> { resumed = false }
                else -> { }
            }
        }
        owner?.lifecycle?.addObserver(observer)
        onDispose { owner?.lifecycle?.removeObserver(observer) }
    }
    LaunchedEffect(resumed) {
        if (!resumed) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            clock.floatValue = (clock.floatValue + (now - last) / 1_000_000_000f) % CLOCK_WRAP
            last = now
        }
    }
    return clock
}

/** Hides the status bar while the login screen is on screen and brings it back afterwards. */
@Composable
private fun HideStatusBar() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.statusBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.statusBars()) }
    }
}

// ================================================================== screen
/**
 * The lock screen. Purely presentational: the PIN text and every decision about it
 * (verification, set-up, biometrics) stay with the caller; this draws the scene and
 * forwards the input.
 *
 * @param pin the digits typed so far (never shown — only counted by the indicators)
 * @param onPinChange raw text from the native numeric keyboard
 * @param errorTick increment to play the wrong-PIN animation
 * @param success true once authentication succeeded (keyboard is dismissed, dots glow)
 */
@Composable
internal fun AnimatedLoginScreen(
    pin: String,
    onPinChange: (String) -> Unit,
    welcome: String,
    prompt: String,
    errorMessage: String,
    errorTick: Int,
    success: Boolean,
    showFingerprint: Boolean,
    onFingerprint: () -> Unit,
) {
    HideStatusBar()
    val clock = rememberSceneClock()
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    val openKeyboard: () -> Unit = {
        focusRequester.requestFocus()
        keyboard?.show()
    }

    // Wrong PIN: the dots turn red and the row shakes; nothing about the digits is shown.
    val shake = remember { Animatable(0f) }
    var flash by remember { mutableStateOf(false) }
    LaunchedEffect(errorTick) {
        if (errorTick == 0) return@LaunchedEffect
        flash = true
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        val amp = with(density) { 14.dp.toPx() }
        for (a in listOf(-1f, 0.85f, -0.65f, 0.45f, -0.25f, 0f)) shake.animateTo(a * amp, tween(55))
        delay(380)
        flash = false
    }
    LaunchedEffect(success) {
        if (success) {
            keyboard?.hide()
            focusManager.clearFocus()
        }
    }
    val lastLength = remember { mutableIntStateOf(0) }
    LaunchedEffect(pin.length) {
        if (pin.length > lastLength.intValue) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        lastLength.intValue = pin.length
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Box(Modifier.fillMaxSize().background(NavyDeep)) {
            BackdropLayer(clock)

            // The native numeric keyboard's input target: invisible, 1dp, but really focused.
            BasicTextField(
                value = pin,
                onValueChange = onPinChange,
                modifier = Modifier.size(1.dp).focusRequester(focusRequester),
                singleLine = true,
                textStyle = TextStyle(color = Color.Transparent),
                cursorBrush = SolidColor(Color.Transparent),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
            )

            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.displayCutout)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .imePadding(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(if (imeVisible) 6.dp else 16.dp))
                    LogoBlock(clock, compact = imeVisible)
                    if (!imeVisible) {
                        Spacer(Modifier.height(2.dp))
                        LineLabelRow("مدیریت هوشمند خودرو", big = false)
                    }
                }

                StageLayer(clock, Modifier.weight(1f).fillMaxWidth())

                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    LineLabelRow(welcome, big = true)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        prompt,
                        color = Color(0xFFB7C6E6),
                        style = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = openKeyboard,
                            )
                            .padding(vertical = 4.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier
                            .offset { IntOffset(shake.value.roundToInt(), 0) }
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = openKeyboard,
                            )
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                            .semantics { contentDescription = "${pin.length} از $LOGIN_PIN_LENGTH رقم وارد شده" },
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        for (i in 0 until LOGIN_PIN_LENGTH) {
                            PinDot(filled = i < pin.length, error = flash, success = success, size = 46.dp)
                        }
                    }
                    Box(Modifier.heightIn(min = 22.dp), contentAlignment = Alignment.Center) {
                        if (errorMessage.isNotEmpty()) {
                            Text(
                                errorMessage,
                                color = ErrorRed,
                                style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                    if (showFingerprint) {
                        Spacer(Modifier.height(2.dp))
                        FingerprintButton(clock, size = if (imeVisible) 92.dp else 138.dp, onClick = onFingerprint)
                        Text(
                            "ورود با اثر انگشت",
                            color = Color(0xFFD5E2FA),
                            style = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
                            textAlign = TextAlign.Center,
                        )
                    }
                    if (!imeVisible) {
                        Spacer(Modifier.height(12.dp))
                        LineLabelRow("خودرویار، همراه همیشگی شما", big = false)
                    }
                }
            }
        }
    }
}

// ================================================================== text rows
/** `——— text ———` with neon hairlines fading out towards the screen edges. */
@Composable
private fun LineLabelRow(text: String, big: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(Brush.horizontalGradient(listOf(Neon.copy(alpha = 0.85f), Color.Transparent)))
        )
        Text(
            text,
            modifier = Modifier.padding(horizontal = 14.dp),
            color = if (big) Color.White else Color(0xFFC9D6F0),
            style = if (big) TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold)
            else TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(Brush.horizontalGradient(listOf(Color.Transparent, Neon.copy(alpha = 0.85f))))
        )
    }
}

// ================================================================== logo
@Composable
private fun LogoBlock(clock: FloatState, compact: Boolean) {
    val artHeight = if (compact) 60.dp else 92.dp
    Box(Modifier.fillMaxWidth(0.8f).widthIn(max = 380.dp), contentAlignment = Alignment.TopCenter) {
        Canvas(Modifier.matchParentSize()) {
            val t = clock.floatValue
            drawLogoGlow(t, artHeight.toPx())
            drawLogoArt(t, artHeight.toPx())
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(artHeight))
            Text(
                "خودرویار",
                maxLines = 1,
                softWrap = false,
                textAlign = TextAlign.Center,
                style = TextStyle(
                    brush = Brush.verticalGradient(listOf(Color.White, Color(0xFFC3D3EE), Color(0xFF7088B8))),
                    fontSize = if (compact) 36.sp else 52.sp,
                    lineHeight = if (compact) 46.sp else 64.sp,
                    fontWeight = FontWeight.ExtraBold,
                    shadow = Shadow(color = Neon.copy(alpha = 0.55f), offset = Offset.Zero, blurRadius = 26f),
                ),
            )
        }
    }
}

/** Soft blue glow behind the whole logo, pulsing slowly. */
private fun DrawScope.drawLogoGlow(t: Float, artH: Float) {
    val pulse = 0.55f + 0.45f * wave(t, 3f)
    val c = Offset(size.width * 0.5f, artH * 0.62f)
    drawCircle(
        brush = Brush.radialGradient(
            listOf(Neon.copy(alpha = 0.30f * pulse), Color.Transparent),
            center = c, radius = size.width * 0.55f,
        ),
        radius = size.width * 0.55f, center = c,
    )
    val tc = Offset(size.width * 0.5f, artH + (size.height - artH) * 0.5f)
    drawCircle(
        brush = Brush.radialGradient(
            listOf(NeonBright.copy(alpha = 0.16f * pulse), Color.Transparent),
            center = tc, radius = size.width * 0.42f,
        ),
        radius = size.width * 0.42f, center = tc,
    )
}

/** Speedometer arc (with a light travelling along it) over a sleek car outline. */
private fun DrawScope.drawLogoArt(t: Float, artH: Float) {
    val w = size.width
    // --- speedometer arc: 9 segments, brighter towards the right
    val n = 9
    val cx = w * 0.52f
    val cy = artH * 0.98f
    val rx = w * 0.43f
    val ry = artH * 0.92f
    val stroke = artH * 0.11f
    val start = 205f
    val total = 140f
    val gap = 2.6f
    val seg = total / n
    val hot = phase(t, 5f) * (n + 4) - 2f      // travelling highlight, in segment units
    for (i in 0 until n) {
        val k = i / (n - 1f)
        val base = androidx.compose.ui.graphics.lerp(Color(0xFF1446B8), Color(0xFF5CC0FF), k)
        val boost = max(0f, 1f - kotlin.math.abs(i - hot) / 1.9f)
        val col = androidx.compose.ui.graphics.lerp(base, Color.White, boost * 0.85f)
        drawArc(
            color = col.copy(alpha = 0.55f + 0.45f * max(k, boost)),
            startAngle = start + i * seg,
            sweepAngle = seg - gap,
            useCenter = false,
            topLeft = Offset(cx - rx, cy - ry),
            size = Size(rx * 2f, ry * 2f),
            style = Stroke(width = stroke, cap = StrokeCap.Butt),
        )
    }
    // --- car outline (two silver swooshes)
    val silver = Brush.horizontalGradient(
        listOf(Color(0xFF8CA2C8), IceWhite, Color(0xFF9DB1D4)),
        startX = w * 0.12f, endX = w * 0.88f,
    )
    val body = Path().apply {
        moveTo(w * 0.12f, artH * 0.80f)
        cubicTo(w * 0.14f, artH * 0.66f, w * 0.26f, artH * 0.62f, w * 0.34f, artH * 0.55f)
        cubicTo(w * 0.42f, artH * 0.38f, w * 0.60f, artH * 0.36f, w * 0.68f, artH * 0.53f)
        cubicTo(w * 0.78f, artH * 0.58f, w * 0.88f, artH * 0.66f, w * 0.90f, artH * 0.78f)
    }
    drawPath(body, silver, style = Stroke(width = artH * 0.045f, cap = StrokeCap.Round))
    val under = Path().apply {
        moveTo(w * 0.17f, artH * 0.86f)
        cubicTo(w * 0.34f, artH * 0.80f, w * 0.66f, artH * 0.80f, w * 0.86f, artH * 0.85f)
    }
    drawPath(under, silver, alpha = 0.75f, style = Stroke(width = artH * 0.028f, cap = StrokeCap.Round))
    // wheels
    drawCircle(IceWhite.copy(alpha = 0.8f), artH * 0.075f, Offset(w * 0.27f, artH * 0.83f), style = Stroke(artH * 0.02f))
    drawCircle(IceWhite.copy(alpha = 0.8f), artH * 0.075f, Offset(w * 0.77f, artH * 0.83f), style = Stroke(artH * 0.02f))
}

// ================================================================== PIN dots
@Composable
private fun PinDot(filled: Boolean, error: Boolean, success: Boolean, size: Dp) {
    // Entering a digit: the dot pops in with a small overshoot. Deleting: it shrinks back
    // with a bounce. Either way a thin ring ripples outward.
    val fill by animateFloatAsState(
        targetValue = if (filled) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.42f, stiffness = Spring.StiffnessMediumLow),
        label = "dotFill",
    )
    val ripple = remember { Animatable(1f) }
    val firstRun = remember { booleanArrayOf(true) }
    LaunchedEffect(filled) {
        if (firstRun[0]) {
            firstRun[0] = false
            return@LaunchedEffect
        }
        ripple.snapTo(0f)
        ripple.animateTo(1f, tween(380))
    }
    val ringColor by animateColorAsState(
        targetValue = when {
            error -> ErrorRed
            success -> Mint
            else -> NeonBright
        },
        animationSpec = tween(160),
        label = "dotColor",
    )
    Canvas(Modifier.size(size)) {
        val c = center
        val r = this.size.minDimension / 2f
        val ring = ringColor
        val glow = fill.coerceIn(0f, 1f)
        drawCircle(
            brush = Brush.radialGradient(
                listOf(ring.copy(alpha = 0.18f + 0.50f * glow), Color.Transparent),
                center = c, radius = r * 1.55f,
            ),
            radius = r * 1.55f, center = c,
        )
        drawCircle(ring.copy(alpha = 0.30f), r * 0.90f, c, style = Stroke(5.dp.toPx()))
        drawCircle(ring, r * 0.90f, c, style = Stroke(2.2.dp.toPx()))
        if (fill > 0.01f) {
            val dotR = r * 0.52f * fill
            drawCircle(
                brush = Brush.radialGradient(listOf(Color.White, ring), center = c, radius = dotR),
                radius = dotR, center = c,
            )
        }
        val rp = ripple.value
        if (rp < 1f) {
            drawCircle(ring.copy(alpha = (1f - rp) * 0.55f), r * (0.90f + 0.55f * rp), c, style = Stroke(2.dp.toPx()))
        }
    }
}

// ================================================================== fingerprint
@Composable
private fun FingerprintButton(clock: FloatState, size: Dp, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 0.93f else 1f, tween(110), label = "fpPress")
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        // glow pulse + three expanding waves + thin rings + a slowly rotating light
        Canvas(Modifier.matchParentSize()) {
            val t = clock.floatValue
            val c = center
            val outer = this.size.minDimension / 2f
            val core = outer * 0.62f
            val pulse = wave(t, 2f)
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Neon.copy(alpha = 0.30f + 0.22f * pulse), Color.Transparent),
                    center = c, radius = outer,
                ),
                radius = outer, center = c,
            )
            for (i in 0 until 3) {
                val p = phase(t, 3f, i / 3f)
                val r = core + (outer - core) * p
                val a = (1f - p) * (1f - p) * 0.75f
                drawCircle(NeonBright.copy(alpha = a), r, c, style = Stroke(2.dp.toPx() * (1f - 0.5f * p)))
            }
            drawCircle(Neon.copy(alpha = 0.40f), core * 1.14f, c, style = Stroke(1.dp.toPx()))
            drawCircle(Neon.copy(alpha = 0.20f), core * 1.28f, c, style = Stroke(1.dp.toPx()))
            rotate(phase(t, 6f) * 360f, c) {
                drawCircle(
                    brush = Brush.sweepGradient(
                        listOf(Color.Transparent, NeonBright, Color.White, Color.Transparent),
                        center = c,
                    ),
                    radius = core, center = c,
                    style = Stroke(3.dp.toPx(), cap = StrokeCap.Round),
                )
            }
        }
        Box(
            Modifier
                .size(size * 0.62f * press)
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(Color(0xFF0C1D4A), Color(0xFF030816))))
                .border(2.dp, Brush.linearGradient(listOf(NeonBright, Neon)), CircleShape)
                .semantics { contentDescription = "ورود با اثر انگشت" }
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    role = Role.Button,
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.matchParentSize()) {
                val pulse = wave(clock.floatValue, 2f)
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(Cyan.copy(alpha = 0.22f + 0.26f * pulse), Color.Transparent),
                        center = center, radius = this.size.minDimension * 0.5f,
                    ),
                    radius = this.size.minDimension * 0.5f, center = center,
                )
            }
            Icon(Icons.Outlined.Fingerprint, null, tint = Cyan, modifier = Modifier.size(size * 0.32f))
        }
    }
}

// ================================================================== neon trails
private class Trail(
    val path: Path,
    val measure: PathMeasure,
    val length: Float,
    val period: Float,
    val offset: Float,
    val reverse: Boolean,
    val width: Float,
    val tail: Float,
    val color: Color,
)

private fun makeTrail(
    path: Path, period: Float, offset: Float, reverse: Boolean,
    width: Float, tail: Float, color: Color,
): Trail {
    val m = PathMeasure()
    m.setPath(path, false)
    return Trail(path, m, m.length, period, offset, reverse, width, tail, color)
}

/** A glowing neon line with a brighter light that travels along it and loops seamlessly. */
private fun DrawScope.drawTrail(tr: Trail, t: Float, seg: Path, alpha: Float = 1f) {
    val wd = tr.width
    drawPath(tr.path, tr.color.copy(alpha = 0.10f * alpha), style = Stroke(wd * 5f, cap = StrokeCap.Round))
    drawPath(tr.path, tr.color.copy(alpha = 0.22f * alpha), style = Stroke(wd * 2.4f, cap = StrokeCap.Round))
    drawPath(tr.path, tr.color.copy(alpha = 0.80f * alpha), style = Stroke(wd, cap = StrokeCap.Round))

    if (tr.length <= 0f) return
    var p = phase(t, tr.period, tr.offset)
    if (tr.reverse) p = 1f - p
    val len = tr.length
    val span = tr.tail * len
    val chunks = 3
    for (k in 0 until chunks) {
        val from = p * len + span * k / chunks
        val to = p * len + span * (k + 1) / chunks
        val fade = (k + 1f) / chunks * alpha
        drawSegment(tr, from, to, len, seg, fade)
    }
}

private fun DrawScope.drawSegment(tr: Trail, from: Float, to: Float, len: Float, seg: Path, fade: Float) {
    fun piece(a: Float, b: Float) {
        seg.rewind()
        if (tr.measure.getSegment(a, b, seg, true)) {
            drawPath(seg, IceWhite.copy(alpha = 0.18f * fade), style = Stroke(tr.width * 3.6f, cap = StrokeCap.Round))
            drawPath(seg, NeonBright.copy(alpha = 0.50f * fade), style = Stroke(tr.width * 1.9f, cap = StrokeCap.Round))
            drawPath(seg, Color.White.copy(alpha = 0.95f * fade), style = Stroke(tr.width * 0.8f, cap = StrokeCap.Round))
        }
    }
    if (to <= len) piece(from, to)
    else if (from >= len) piece(from - len, to - len)
    else {
        piece(from, len)
        piece(0f, to - len)
    }
}

private class Star(val x: Float, val y: Float, val r: Float, val phaseOffset: Float)

/** Full-screen night sky, glow, stars and the flowing neon trails along the edges. */
@Composable
private fun BackdropLayer(clock: FloatState, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxSize()
            .drawWithCache {
                val w = size.width
                val h = size.height
                val rnd = Random(11)
                val stars = List(54) {
                    Star(rnd.nextFloat() * w, rnd.nextFloat() * h * 0.42f, 0.6f + rnd.nextFloat() * 1.3f, rnd.nextFloat())
                }
                val bg = Brush.verticalGradient(
                    0f to Color(0xFF01030A),
                    0.34f to Color(0xFF040E30),
                    0.62f to Color(0xFF040B24),
                    1f to Color(0xFF010512),
                )
                val skyGlow = Brush.radialGradient(
                    listOf(Color(0x552F7BFF), Color.Transparent),
                    center = Offset(w * 0.55f, h * 0.20f), radius = w * 0.95f,
                )
                val px = w / 390f  // stroke scale: designed on a 390-wide screen
                val topArc = Path().apply {
                    moveTo(w * 0.58f, -h * 0.02f)
                    cubicTo(w * 0.22f, h * 0.02f, -w * 0.03f, h * 0.17f, w * 0.015f, h * 0.36f)
                }
                val topArc2 = Path().apply {
                    moveTo(w * 0.66f, -h * 0.02f)
                    cubicTo(w * 0.28f, h * 0.01f, -w * 0.07f, h * 0.17f, -w * 0.01f, h * 0.35f)
                }
                val wave1 = Path().apply {
                    moveTo(-w * 0.05f, h * 0.915f)
                    cubicTo(w * 0.25f, h * 0.955f, w * 0.55f, h * 0.85f, w * 1.05f, h * 0.92f)
                }
                val wave2 = Path().apply {
                    moveTo(-w * 0.05f, h * 0.95f)
                    cubicTo(w * 0.30f, h * 0.995f, w * 0.65f, h * 0.90f, w * 1.05f, h * 0.965f)
                }
                val wave3 = Path().apply {
                    moveTo(-w * 0.05f, h * 0.885f)
                    cubicTo(w * 0.18f, h * 0.90f, w * 0.40f, h * 0.835f, w * 1.05f, h * 0.875f)
                }
                val trails = listOf(
                    makeTrail(topArc, 6f, 0.0f, false, 2.2f * px, 0.20f, Neon),
                    makeTrail(topArc2, 6f, 0.35f, false, 1.1f * px, 0.16f, NeonBright),
                    makeTrail(wave1, 5f, 0.1f, false, 2.4f * px, 0.22f, Neon),
                    makeTrail(wave2, 8f, 0.6f, true, 2.0f * px, 0.20f, NeonBright),
                    makeTrail(wave3, 10f, 0.3f, false, 1.4f * px, 0.18f, Cyan),
                )
                val seg = Path()
                onDrawBehind {
                    val t = clock.floatValue
                    drawRect(bg)
                    drawRect(skyGlow)
                    for (s in stars) {
                        val a = 0.35f + 0.45f * wave(t, 5f, s.phaseOffset)
                        drawCircle(IceWhite.copy(alpha = a), s.r, Offset(s.x, s.y))
                    }
                    for (tr in trails) drawTrail(tr, t, seg)
                }
            }
    )
}

// ================================================================== stage (city, tower, car)
private class Building(val x: Float, val w: Float, val top: Float, val color: Color)

private class Win(val x: Float, val y: Float, val w: Float, val h: Float, val warm: Boolean, val twinkle: Boolean, val phaseOffset: Float)

/**
 * The scene between the logo and the sign-in controls: skyline and an illuminated tower
 * behind, a wet road below and the car on it. It is drawn relative to its own height, so
 * the car always sits on the road whatever the screen size.
 */
@Composable
private fun StageLayer(clock: FloatState, modifier: Modifier = Modifier) {
    Box(
        modifier.drawWithCache {
            val w = size.width
            val s = size.height
            val horizon = s * 0.52f
            val ground = s * 0.80f
            val rnd = Random(5)

            // --- skyline
            val buildings = ArrayList<Building>()
            var x = w * 0.30f
            while (x < w * 1.02f) {
                val bw = w * (0.030f + rnd.nextFloat() * 0.032f)
                val tall = if (x > w * 0.74f) 0.30f + rnd.nextFloat() * 0.30f else 0.12f + rnd.nextFloat() * 0.26f
                val bh = s * tall
                val tint = 0.55f + rnd.nextFloat() * 0.45f
                buildings.add(Building(x, bw, horizon - bh, Color(0xFF050B20).copy(alpha = 1f).let {
                    androidx.compose.ui.graphics.lerp(it, Color(0xFF0B1A44), tint * 0.5f)
                }))
                x += bw + w * 0.004f
            }
            var fx = 0f
            while (fx < w * 0.30f) {
                val bw = w * (0.03f + rnd.nextFloat() * 0.03f)
                buildings.add(Building(fx, bw, horizon - s * (0.05f + rnd.nextFloat() * 0.10f), Color(0xFF040918)))
                fx += bw + w * 0.004f
            }
            val windows = ArrayList<Win>()
            for (b in buildings) {
                val ww = w * 0.0042f
                val wh = s * 0.0095f
                val cols = max(1, (b.w / (w * 0.0085f)).toInt())
                val rows = max(1, ((horizon - b.top) / (s * 0.020f)).toInt())
                for (cIdx in 0 until cols) for (rIdx in 0 until rows) {
                    if (rnd.nextFloat() < 0.55f) continue
                    windows.add(
                        Win(
                            b.x + (cIdx + 0.5f) * b.w / cols - ww / 2f,
                            b.top + s * 0.012f + rIdx * s * 0.020f,
                            ww, wh,
                            warm = rnd.nextFloat() < 0.55f,
                            twinkle = rnd.nextFloat() < 0.25f,
                            phaseOffset = rnd.nextFloat(),
                        )
                    )
                }
            }
            val streakX = FloatArray(20) { w * (0.42f + rnd.nextFloat() * 0.56f) }

            // --- tree (left)
            val treeCircles = List(11) {
                Triple(
                    Offset(w * (0.01f + rnd.nextFloat() * 0.14f), horizon - s * (0.06f + rnd.nextFloat() * 0.20f)),
                    w * (0.030f + rnd.nextFloat() * 0.035f),
                    rnd.nextFloat(),
                )
            }

            // --- tower
            val towerH = min(s * 1.5f, 300.dp.toPx())

            // --- car geometry
            val carLen = min(w * 0.78f, min(ground / 0.34f, (s - ground) / 0.12f))
            val carLeft = w * 0.035f
            val roadBrush = Brush.verticalGradient(
                listOf(Color(0x99123A9A), Color(0x55081C52), Color.Transparent),
                startY = horizon, endY = s,
            )

            onDrawBehind {
                val t = clock.floatValue
                // buildings + windows
                for (b in buildings) {
                    drawRect(b.color, Offset(b.x, b.top), Size(b.w, horizon - b.top))
                    drawLine(Neon.copy(alpha = 0.30f), Offset(b.x, b.top), Offset(b.x + b.w, b.top), strokeWidth = 1.2f)
                }
                for (win in windows) {
                    val base = if (win.warm) Color(0xFFFFC66B) else Color(0xFFBFE0FF)
                    val a = if (win.twinkle) 0.35f + 0.55f * wave(t, 4f, win.phaseOffset) else 0.80f
                    drawRect(base.copy(alpha = a), Offset(win.x, win.y), Size(win.w, win.h))
                }
                drawTower(w, horizon, towerH, t)
                // tree silhouette
                for ((c, r, _) in treeCircles) drawCircle(Color(0xFF010309), r, c)
                drawRect(Color(0xFF010309), Offset(w * 0.07f, horizon - s * 0.10f), Size(w * 0.014f, s * 0.10f))
                for ((c, r, f) in treeCircles) {
                    drawArc(
                        Neon.copy(alpha = 0.10f + 0.12f * f), -60f, 70f, false,
                        Offset(c.x - r, c.y - r), Size(r * 2f, r * 2f), style = Stroke(1.2f),
                    )
                }
                // water / road
                drawRect(roadBrush, Offset(0f, horizon), Size(w, s - horizon))
                for ((i, sx) in streakX.withIndex()) {
                    val a = 0.10f + 0.12f * wave(t, 4f, i / 20f)
                    drawLine(
                        Brush.verticalGradient(
                            listOf(Color(0xFFFFC66B).copy(alpha = a), Color.Transparent),
                            startY = horizon, endY = horizon + s * 0.20f,
                        ),
                        Offset(sx, horizon), Offset(sx, horizon + s * 0.20f), strokeWidth = w * 0.003f,
                    )
                }
                // wet-road light streaks
                val glowA = 0.45f + 0.25f * wave(t, 5f)
                drawLine(
                    Brush.horizontalGradient(listOf(NeonBright.copy(alpha = glowA), Color.Transparent), startX = 0f, endX = w * 0.34f),
                    Offset(0f, ground - s * 0.05f), Offset(w * 0.34f, ground - s * 0.075f), strokeWidth = 2.4f, cap = StrokeCap.Round,
                )
                drawLine(
                    Brush.horizontalGradient(listOf(Color.Transparent, NeonBright.copy(alpha = glowA)), startX = w * 0.66f, endX = w),
                    Offset(w * 0.66f, ground - s * 0.075f), Offset(w, ground - s * 0.045f), strokeWidth = 2.4f, cap = StrokeCap.Round,
                )
                if (s > 110.dp.toPx()) drawCar(t, carLeft, ground, carLen)
            }
        }
    )
}

/** An illuminated broadcast-tower silhouette (Milad-Tower-inspired) with a pulsing pod. */
private fun DrawScope.drawTower(w: Float, horizon: Float, u: Float, t: Float) {
    val cx = w * 0.905f
    val podY = horizon - u * 0.64f
    val topY = horizon - u
    val glow = 0.7f + 0.3f * wave(t, 4f)
    drawCircle(
        brush = Brush.radialGradient(
            listOf(Color(0xFFFFC66B).copy(alpha = 0.28f * glow), Neon.copy(alpha = 0.12f), Color.Transparent),
            center = Offset(cx, podY), radius = w * 0.20f,
        ),
        radius = w * 0.20f, center = Offset(cx, podY),
    )
    val shaft = Path().apply {
        moveTo(cx - w * 0.036f, horizon)
        lineTo(cx - w * 0.012f, horizon - u * 0.28f)
        lineTo(cx - w * 0.0075f, podY + u * 0.04f)
        lineTo(cx + w * 0.0075f, podY + u * 0.04f)
        lineTo(cx + w * 0.012f, horizon - u * 0.28f)
        lineTo(cx + w * 0.036f, horizon)
        close()
    }
    drawPath(
        shaft,
        Brush.horizontalGradient(
            listOf(Color(0xFF1B2748), Color(0xFFB3C7E8), Color(0xFF223158)),
            startX = cx - w * 0.036f, endX = cx + w * 0.036f,
        ),
    )
    drawLine(Color.White.copy(alpha = 0.45f), Offset(cx, horizon), Offset(cx, podY + u * 0.04f), strokeWidth = 1.2f)
    // pod
    val podW = w * 0.092f
    val podH = u * 0.075f
    drawOval(
        Brush.horizontalGradient(
            listOf(Color(0xFF16213F), Color(0xFFCFE0FF), Color(0xFF16213F)),
            startX = cx - podW / 2f, endX = cx + podW / 2f,
        ),
        Offset(cx - podW / 2f, podY - podH / 2f), Size(podW, podH),
    )
    for (i in 0 until 3) {
        val y = podY - podH * 0.25f + i * podH * 0.25f
        drawLine(
            Color(0xFFFFD27A).copy(alpha = 0.55f + 0.35f * glow),
            Offset(cx - podW * 0.42f, y), Offset(cx + podW * 0.42f, y), strokeWidth = 1.6f, cap = StrokeCap.Round,
        )
    }
    // spire
    val spire = Path().apply {
        moveTo(cx - w * 0.011f, podY - podH * 0.4f)
        lineTo(cx - w * 0.0035f, podY - u * 0.13f)
        lineTo(cx + w * 0.0035f, podY - u * 0.13f)
        lineTo(cx + w * 0.011f, podY - podH * 0.4f)
        close()
    }
    drawPath(spire, Color(0xFF9DB4DA))
    drawLine(IceWhite.copy(alpha = 0.9f), Offset(cx, podY - u * 0.13f), Offset(cx, topY), strokeWidth = 1.6f)
    val blink = if (phase(t, 2f) < 0.5f) 1f else 0.25f
    drawCircle(ErrorRed.copy(alpha = blink), 2.4f, Offset(cx, topY))
}

// ================================================================== the car
/**
 * A dark sedan in side view, facing right. Animated parts only: the headlights fade
 * gently on and off (lamp, bloom, beam and the light pool on the road) and the wheels
 * turn at about one revolution per three seconds. The body never moves.
 */
private fun DrawScope.drawCar(t: Float, left: Float, ground: Float, len: Float) {
    fun x(f: Float) = left + f * len
    val roofY = ground - 0.30f * len
    fun y(f: Float) = roofY + f * len

    // headlight intensity: a slow, soft on/off (never a hard flash)
    val on = wave(t, 4f)
    val lamp = 0.12f + 0.88f * (on * on * (3f - 2f * on))

    // ground shadow + under-glow
    drawOval(Color.Black.copy(alpha = 0.55f), Offset(x(0.03f), ground - 0.018f * len), Size(0.94f * len, 0.05f * len))
    val under = 0.30f + 0.12f * wave(t, 3f)
    drawOval(
        brush = Brush.radialGradient(
            listOf(Neon.copy(alpha = under), Color.Transparent),
            center = Offset(x(0.5f), ground), radius = 0.55f * len,
        ),
        topLeft = Offset(x(-0.05f), ground - 0.04f * len), size = Size(1.1f * len, 0.09f * len),
    )

    // light pool on the road and the beam (behind the body so the lamp sits on top)
    drawOval(
        brush = Brush.radialGradient(
            listOf(Cyan.copy(alpha = 0.34f * lamp), Color.Transparent),
            center = Offset(x(1.14f), ground + 0.01f * len), radius = 0.30f * len,
        ),
        topLeft = Offset(x(0.90f), ground - 0.03f * len), size = Size(0.5f * len, 0.07f * len),
    )
    val beam = Path().apply {
        moveTo(x(0.972f), y(0.140f))
        lineTo(x(1.30f), y(0.095f))
        lineTo(x(1.38f), y(0.30f))
        lineTo(x(0.985f), y(0.165f))
        close()
    }
    drawPath(
        beam,
        Brush.horizontalGradient(
            listOf(Cyan.copy(alpha = 0.40f * lamp), Neon.copy(alpha = 0.14f * lamp), Color.Transparent),
            startX = x(0.97f), endX = x(1.38f),
        ),
    )

    // reflection of the body on the wet road
    fun Path.top() {
        moveTo(x(0.02f), y(0.235f))
        cubicTo(x(-0.005f), y(0.215f), x(-0.008f), y(0.170f), x(0.012f), y(0.145f))
        cubicTo(x(0.040f), y(0.125f), x(0.100f), y(0.115f), x(0.160f), y(0.108f))
        cubicTo(x(0.220f), y(0.075f), x(0.300f), y(0.025f), x(0.400f), y(0.008f))
        cubicTo(x(0.460f), y(0.000f), x(0.530f), y(0.002f), x(0.580f), y(0.024f))
        cubicTo(x(0.640f), y(0.050f), x(0.700f), y(0.080f), x(0.780f), y(0.092f))
        cubicTo(x(0.860f), y(0.102f), x(0.930f), y(0.118f), x(0.975f), y(0.150f))
        cubicTo(x(0.995f), y(0.168f), x(0.998f), y(0.205f), x(0.985f), y(0.232f))
    }
    val body = Path().apply {
        top()
        lineTo(x(0.94f), y(0.248f))
        lineTo(x(0.07f), y(0.248f))
        close()
    }
    withTransform({ scale(1f, -0.6f, Offset(0f, ground)) }) {
        drawPath(body, Neon.copy(alpha = 0.10f))
    }

    // wheel arches (dark discs behind the tyres)
    val wheelR = 0.066f * len
    val rearC = Offset(x(0.205f), y(0.236f))
    val frontC = Offset(x(0.795f), y(0.236f))
    drawCircle(Color(0xFF010206), wheelR * 1.14f, rearC)
    drawCircle(Color(0xFF010206), wheelR * 1.14f, frontC)

    // body
    drawPath(
        body,
        Brush.verticalGradient(
            listOf(Color(0xFF1E3680), Color(0xFF0C183F), Color(0xFF050A1E)),
            startY = roofY, endY = ground,
        ),
    )
    // shoulder + lower body reflections
    drawLine(
        Brush.horizontalGradient(
            listOf(Color.Transparent, NeonBright.copy(alpha = 0.55f), Color.Transparent),
            startX = x(0.05f), endX = x(0.97f),
        ),
        Offset(x(0.06f), y(0.128f)), Offset(x(0.95f), y(0.140f)), strokeWidth = 0.004f * len,
    )
    drawLine(
        Brush.horizontalGradient(
            listOf(Color.Transparent, Neon.copy(alpha = 0.30f), Color.Transparent),
            startX = x(0.08f), endX = x(0.94f),
        ),
        Offset(x(0.09f), y(0.205f)), Offset(x(0.93f), y(0.205f)), strokeWidth = 0.003f * len,
    )
    // roofline rim light
    val rim = Path().apply { top() }
    drawPath(rim, IceWhite.copy(alpha = 0.55f), style = Stroke(0.004f * len, cap = StrokeCap.Round))

    // glass
    val glass = Path().apply {
        moveTo(x(0.285f), y(0.090f))
        cubicTo(x(0.310f), y(0.050f), x(0.370f), y(0.022f), x(0.430f), y(0.016f))
        cubicTo(x(0.500f), y(0.012f), x(0.555f), y(0.025f), x(0.585f), y(0.048f))
        cubicTo(x(0.600f), y(0.062f), x(0.630f), y(0.082f), x(0.655f), y(0.090f))
        close()
    }
    drawPath(
        glass,
        Brush.verticalGradient(
            listOf(Color(0xFF0E2658), Color(0xFF020611)),
            startY = y(0.012f), endY = y(0.090f),
        ),
    )
    drawLine(Color(0xFF0A1330), Offset(x(0.462f), y(0.014f)), Offset(x(0.462f), y(0.090f)), strokeWidth = 0.010f * len)
    drawLine(
        Color.White.copy(alpha = 0.14f),
        Offset(x(0.34f), y(0.085f)), Offset(x(0.42f), y(0.030f)), strokeWidth = 0.006f * len, cap = StrokeCap.Round,
    )

    // tail light (dim) and headlight (animated)
    drawLine(
        ErrorRed.copy(alpha = 0.55f),
        Offset(x(0.012f), y(0.150f)), Offset(x(0.052f), y(0.140f)), strokeWidth = 0.006f * len, cap = StrokeCap.Round,
    )
    val headlamp = Path().apply {
        moveTo(x(0.905f), y(0.128f))
        lineTo(x(0.975f), y(0.142f))
        lineTo(x(0.972f), y(0.153f))
        lineTo(x(0.915f), y(0.140f))
        close()
    }
    drawPath(headlamp, androidx.compose.ui.graphics.lerp(Color(0xFF16316F), IceWhite, lamp))
    drawCircle(
        brush = Brush.radialGradient(
            listOf(IceWhite.copy(alpha = 0.95f * lamp), Cyan.copy(alpha = 0.55f * lamp), Neon.copy(alpha = 0.22f * lamp), Color.Transparent),
            center = Offset(x(0.95f), y(0.140f)), radius = 0.17f * len,
        ),
        radius = 0.17f * len, center = Offset(x(0.95f), y(0.140f)),
    )
    drawLine(
        Cyan.copy(alpha = 0.25f + 0.65f * lamp),
        Offset(x(0.915f), y(0.158f)), Offset(x(0.968f), y(0.166f)), strokeWidth = 0.004f * len, cap = StrokeCap.Round,
    )

    // wheels
    val angle = phase(t, 3f) * 360f
    drawWheel(rearC, wheelR, angle)
    drawWheel(frontC, wheelR, angle)
}

private fun DrawScope.drawWheel(c: Offset, r: Float, angleDeg: Float) {
    drawCircle(Color(0xFF020307), r, c)
    drawCircle(
        brush = Brush.radialGradient(listOf(Color(0xFF263150), Color(0xFF05070D)), center = c, radius = r * 0.95f),
        radius = r * 0.92f, center = c,
    )
    drawCircle(
        brush = Brush.linearGradient(
            listOf(Color(0xFFC3D0EC), Color(0xFF5B6C98)),
            start = Offset(c.x - r * 0.6f, c.y - r * 0.6f), end = Offset(c.x + r * 0.6f, c.y + r * 0.6f),
        ),
        radius = r * 0.66f, center = c,
    )
    drawCircle(Color(0xFF0A1024), r * 0.58f, c)
    rotate(angleDeg, c) {
        for (k in 0 until 5) {
            val a = (k * 72f) * PI.toFloat() / 180f
            val dx = cos(a)
            val dy = sin(a)
            drawLine(
                Color(0xFFB4C2E2),
                Offset(c.x + dx * r * 0.12f, c.y + dy * r * 0.12f),
                Offset(c.x + dx * r * 0.60f, c.y + dy * r * 0.60f),
                strokeWidth = r * 0.17f, cap = StrokeCap.Round,
            )
            drawLine(
                Color.White.copy(alpha = 0.55f),
                Offset(c.x + dx * r * 0.14f, c.y + dy * r * 0.14f),
                Offset(c.x + dx * r * 0.58f, c.y + dy * r * 0.58f),
                strokeWidth = r * 0.04f, cap = StrokeCap.Round,
            )
        }
    }
    drawCircle(Color(0xFF8896BA), r * 0.14f, c)
    drawCircle(Neon.copy(alpha = 0.65f), r * 0.66f, c, style = Stroke(r * 0.04f))
    // a fixed highlight, so only the spokes appear to turn
    drawArc(
        Color.White.copy(alpha = 0.22f), -140f, 60f, false,
        Offset(c.x - r * 0.62f, c.y - r * 0.62f), Size(r * 1.24f, r * 1.24f), style = Stroke(r * 0.05f, cap = StrokeCap.Round),
    )
}
